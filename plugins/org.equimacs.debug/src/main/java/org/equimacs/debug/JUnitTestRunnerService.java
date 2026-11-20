package org.equimacs.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.junit.model.ITestSuiteElement;

/**
 * Thin Eclipse JDT JUnit runner and listener adapter backed by {@link JUnitTestStore}.
 */
final class JUnitTestRunnerService {

    private static final String PLUGIN_ID = "org.equimacs.debug";
    private static final String JUNIT_LAUNCH_TYPE_ID = "org.eclipse.jdt.junit.launchconfig";
    private static final String ATTR_PROJECT_NAME = "org.eclipse.jdt.launching.PROJECT_ATTR";
    private static final String ATTR_MAIN_TYPE_NAME = "org.eclipse.jdt.launching.MAIN_TYPE";
    private static final String ATTR_TEST_CONTAINER = "org.eclipse.jdt.junit.CONTAINER";
    private static final String ATTR_TEST_NAME = "org.eclipse.jdt.junit.TESTNAME";
    private static final String ATTR_TEST_RUNNER_KIND = "org.eclipse.jdt.junit.TEST_KIND";
    private static final String ATTR_KEEPRUNNING = "org.eclipse.jdt.junit.KEEPRUNNING_ATTR";
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final Gson GSON = new Gson();

    private record PendingRun(
        String runId,
        String launchName,
        String projectName,
        long startedAtMs,
        CompletableFuture<JUnitTestStore.TestRunSnapshot> future
    ) {}

    private final JUnitTestStore store = new JUnitTestStore(JUnitTestStore.DEFAULT_MAX_RUNS);
    private final Map<String, PendingRun> pendingByLaunchName = new ConcurrentHashMap<>();

    private volatile Consumer<JsonObject> eventSink;
    private TestRunListener testRunListener;

    void init(Consumer<JsonObject> eventSink) {
        this.eventSink = eventSink;
        this.testRunListener = new BridgeTestRunListener();
        JUnitCore.addTestRunListener(testRunListener);
    }

    void dispose() {
        if (testRunListener != null) {
            try {
                JUnitCore.removeTestRunListener(testRunListener);
            } catch (RuntimeException ignored) {
                // JUnitCorePlugin may already be stopped during shutdown.
            }
            testRunListener = null;
        }
        for (PendingRun pending : pendingByLaunchName.values()) {
            pending.future().cancel(true);
        }
        pendingByLaunchName.clear();
        store.clear();
        eventSink = null;
    }

    Map<String, Object> runTests(
        String projectName,
        String className,
        String methodName,
        String mode,
        String runner,
        Boolean wait,
        int timeoutMs
    ) throws CoreException {
        IJavaProject javaProject = requireJavaProject(projectName);
        String normalizedMode = normalizeMode(mode);

        String normalizedClass = (className != null && !className.isBlank()) ? className.trim() : null;
        String normalizedMethod = (methodName != null && !methodName.isBlank()) ? methodName.trim() : null;
        if (normalizedMethod != null && normalizedClass == null) {
            throw error("methodName requires className");
        }
        if (normalizedClass != null) {
            validateTarget(javaProject, normalizedClass, normalizedMethod);
        }

        String runnerKindId = resolveRunnerKindId(javaProject, runner);

        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType configType = lm.getLaunchConfigurationType(JUNIT_LAUNCH_TYPE_ID);
        if (configType == null) {
            throw error("JUnit launch configuration type unavailable: " + JUNIT_LAUNCH_TYPE_ID);
        }

        String runId = store.allocateRunId();
        String launchName = "equimacs-junit-" + runId;
        long startedAtMs = System.currentTimeMillis();
        CompletableFuture<JUnitTestStore.TestRunSnapshot> future = new CompletableFuture<>();
        PendingRun pending = new PendingRun(runId, launchName, javaProject.getElementName(), startedAtMs, future);
        pendingByLaunchName.put(launchName, pending);
        JUnitTestStore.TestRunSnapshot started = store.recordStarted(
            runId, launchName, javaProject.getElementName(), startedAtMs);

        ILaunchConfigurationWorkingCopy wc = configType.newInstance(null, launchName);
        wc.setAttribute(ATTR_PROJECT_NAME, javaProject.getElementName());
        wc.setAttribute(ATTR_TEST_RUNNER_KIND, runnerKindId);
        wc.setAttribute(ATTR_KEEPRUNNING, false);
        if (normalizedClass != null) {
            wc.setAttribute(ATTR_MAIN_TYPE_NAME, normalizedClass);
            wc.setAttribute(ATTR_TEST_NAME, normalizedMethod != null ? normalizedMethod : "");
            wc.setAttribute(ATTR_TEST_CONTAINER, "");
        } else {
            wc.setAttribute(ATTR_MAIN_TYPE_NAME, "");
            wc.setAttribute(ATTR_TEST_CONTAINER, javaProject.getHandleIdentifier());
            wc.setAttribute(ATTR_TEST_NAME, "");
        }

        try {
            wc.launch(normalizedMode, new NullProgressMonitor(), true);
        } catch (CoreException | RuntimeException e) {
            pendingByLaunchName.remove(launchName, pending);
            throw e;
        }

        boolean shouldWait = (wait != null) ? wait : ILaunchManager.RUN_MODE.equals(normalizedMode);
        if (!shouldWait) {
            return JUnitTestStore.toMap(started, false);
        }

        int effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        try {
            JUnitTestStore.TestRunSnapshot completed = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            return JUnitTestStore.toMap(completed, false);
        } catch (TimeoutException e) {
            pendingByLaunchName.remove(launchName, pending);
            throw error("Test run timed out after " + effectiveTimeoutMs + "ms (runId=" + runId + ")");
        } catch (InterruptedException e) {
            pendingByLaunchName.remove(launchName, pending);
            Thread.currentThread().interrupt();
            throw error("Test run interrupted (runId=" + runId + ")");
        } catch (ExecutionException e) {
            pendingByLaunchName.remove(launchName, pending);
            Throwable cause = e.getCause();
            throw error("Test run failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    Map<String, Object> getTestResults(String runId, Boolean failuresOnly) throws CoreException {
        JUnitTestStore.TestRunSnapshot snapshot = store.get(runId);
        if (snapshot == null) {
            if (runId == null || runId.isBlank()) {
                throw error("No JUnit test runs recorded yet");
            }
            throw error("Test run not found: " + runId);
        }
        return JUnitTestStore.toMap(snapshot, Boolean.TRUE.equals(failuresOnly));
    }

    private final class BridgeTestRunListener extends TestRunListener {
        @Override
        public void sessionFinished(ITestRunSession session) {
            List<JUnitTestStore.TestCaseResult> results = new ArrayList<>();
            collectTestResults(session, results);
            boolean stopped = session.getProgressState() == ITestElement.ProgressState.STOPPED;
            double elapsed = sanitizeElapsed(session.getElapsedTimeInSeconds());

            String sessionName = session.getTestRunName();
            PendingRun pending = sessionName != null ? pendingByLaunchName.remove(sessionName) : null;

            String runId = pending != null ? pending.runId() : store.allocateRunId();
            String projectName = pending != null
                ? pending.projectName()
                : (session.getLaunchedProject() != null ? session.getLaunchedProject().getElementName() : null);
            long startedAtMs = pending != null ? pending.startedAtMs() : System.currentTimeMillis();
            long finishedAtMs = System.currentTimeMillis();

            JUnitTestStore.TestRunSnapshot snapshot = store.recordFinished(
                runId,
                sessionName != null ? sessionName : runId,
                projectName,
                startedAtMs,
                finishedAtMs,
                elapsed,
                stopped,
                results);

            if (pending != null) {
                pending.future().complete(snapshot);
            }
            Consumer<JsonObject> sink = eventSink;
            if (sink != null) {
                JsonObject ev = GSON.toJsonTree(JUnitTestStore.toFinishedEventMap(snapshot)).getAsJsonObject();
                sink.accept(ev);
            }
        }
    }

    private static void collectTestResults(
        ITestElementContainer container,
        List<JUnitTestStore.TestCaseResult> out
    ) {
        for (ITestElement child : container.getChildren()) {
            switch (child) {
                case ITestCaseElement tc -> out.add(new JUnitTestStore.TestCaseResult(
                    tc.getTestClassName(),
                    tc.getTestMethodName(),
                    mapOutcome(tc),
                    sanitizeElapsed(tc.getElapsedTimeInSeconds()),
                    mapFailure(tc.getFailureTrace())));
                case ITestSuiteElement suite -> {
                    if (suite.getFailureTrace() != null) {
                        out.add(new JUnitTestStore.TestCaseResult(
                            suite.getSuiteTypeName(),
                            "<suite>",
                            mapOutcome(suite),
                            sanitizeElapsed(suite.getElapsedTimeInSeconds()),
                            mapFailure(suite.getFailureTrace())));
                    }
                    collectTestResults(suite, out);
                }
                case ITestElementContainer nested -> collectTestResults(nested, out);
                default -> {}
            }
        }
    }

    private static JUnitTestStore.TestOutcome mapOutcome(ITestElement element) {
        ITestElement.Result result = element.getTestResult(false);
        if (result == ITestElement.Result.OK) {
            return JUnitTestStore.TestOutcome.PASSED;
        }
        if (result == ITestElement.Result.FAILURE) {
            return JUnitTestStore.TestOutcome.FAILED;
        }
        if (result == ITestElement.Result.ERROR) {
            return JUnitTestStore.TestOutcome.ERROR;
        }
        if (result == ITestElement.Result.IGNORED) {
            return JUnitTestStore.TestOutcome.IGNORED;
        }
        ITestElement.ProgressState progress = element.getProgressState();
        if (progress == ITestElement.ProgressState.RUNNING) {
            return JUnitTestStore.TestOutcome.RUNNING;
        }
        if (progress == ITestElement.ProgressState.STOPPED) {
            return JUnitTestStore.TestOutcome.STOPPED;
        }
        return JUnitTestStore.TestOutcome.UNDEFINED;
    }

    private static JUnitTestStore.FailureDetail mapFailure(ITestElement.FailureTrace trace) {
        if (trace == null) {
            return null;
        }
        String rawTrace = trace.getTrace();
        return new JUnitTestStore.FailureDetail(
            JUnitTraceParser.extractSummaryLine(rawTrace),
            trace.getExpected(),
            trace.getActual(),
            rawTrace,
            JUnitTraceParser.parseStackTrace(rawTrace));
    }

    private IJavaProject requireJavaProject(String projectName) throws CoreException {
        if (projectName == null || projectName.isBlank()) {
            throw error("RunTests requires a non-blank project name");
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.trim());
        if (!project.exists()) {
            throw error("Project not found: " + projectName);
        }
        if (!project.isOpen()) {
            throw error("Project is closed: " + projectName);
        }
        if (!project.hasNature(JavaCore.NATURE_ID)) {
            throw error("Not a Java project: " + projectName);
        }
        return JavaCore.create(project);
    }

    private String normalizeMode(String mode) throws CoreException {
        if (mode == null || mode.isBlank()) {
            return ILaunchManager.RUN_MODE;
        }
        String lower = mode.trim().toLowerCase(Locale.ROOT);
        if (ILaunchManager.RUN_MODE.equals(lower) || ILaunchManager.DEBUG_MODE.equals(lower)) {
            return lower;
        }
        throw error("Unsupported test launch mode: " + mode + " (expected run or debug)");
    }

    private void validateTarget(IJavaProject javaProject, String className, String methodName) throws CoreException {
        IType type = javaProject.findType(className);
        if (type == null || !type.exists()) {
            throw error("Test class not found in project " + javaProject.getElementName() + ": " + className);
        }
        if (methodName != null) {
            int paren = methodName.indexOf('(');
            String targetMethod = paren > 0 ? methodName.substring(0, paren) : methodName;
            boolean found = false;
            for (IMethod m : type.getMethods()) {
                if (m.getElementName().equals(targetMethod)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw error("Test method not found on " + className + ": " + methodName);
            }
        }
    }

    private String resolveRunnerKindId(IJavaProject javaProject, String runner) throws CoreException {
        if (runner != null && !runner.isBlank()) {
            try {
                return JUnitTraceParser.runnerKindId(runner);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }
        return detectRunnerKindId(javaProject);
    }

    private String detectRunnerKindId(IJavaProject javaProject) throws CoreException {
        for (IClasspathEntry entry : javaProject.getRawClasspath()) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                IPath path = entry.getPath();
                if (JUnitCore.JUNIT6_CONTAINER_PATH.equals(path)) {
                    return JUnitTraceParser.runnerKindId("junit6");
                }
                if (JUnitCore.JUNIT5_CONTAINER_PATH.equals(path)) {
                    return JUnitTraceParser.runnerKindId("junit5");
                }
                if (JUnitCore.JUNIT4_CONTAINER_PATH.equals(path)) {
                    return JUnitTraceParser.runnerKindId("junit4");
                }
                if (JUnitCore.JUNIT3_CONTAINER_PATH.equals(path)) {
                    return JUnitTraceParser.runnerKindId("junit3");
                }
            }
        }

        IType jupiterType = javaProject.findType("org.junit.jupiter.api.Test");
        if (jupiterType == null) {
            jupiterType = javaProject.findType("org.junit.platform.commons.annotation.Testable");
        }
        if (jupiterType != null) {
            IPackageFragmentRoot root = (IPackageFragmentRoot) jupiterType.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            String jarName = root != null ? root.getElementName() : "";
            if (jarName.contains("junit-jupiter-api_6.")
                || jarName.contains("junit-jupiter-api-6.")
                || jarName.contains("junit-platform-commons_6.")
                || jarName.contains("junit-platform-commons-6.")) {
                return JUnitTraceParser.runnerKindId("junit6");
            }
            return JUnitTraceParser.runnerKindId("junit5");
        }
        if (javaProject.findType("org.junit.Test") != null) {
            return JUnitTraceParser.runnerKindId("junit4");
        }
        if (javaProject.findType("junit.framework.TestCase") != null) {
            return JUnitTraceParser.runnerKindId("junit3");
        }
        throw error("Could not detect JUnit version on classpath of project: " + javaProject.getElementName());
    }

    private static double sanitizeElapsed(double value) {
        return (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) ? 0.0 : value;
    }

    private CoreException error(String message) {
        return new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, message));
    }
}
