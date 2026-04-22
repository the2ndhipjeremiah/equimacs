package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.ide.IDE;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.JDIDebugModel;

public class JavaDebugController {

    private final Map<Long, IThread> threadRegistry = new ConcurrentHashMap<>();
    private final Map<Long, IStackFrame> frameRegistry = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();

    public void init() {
        DebugPlugin.getDefault().addDebugEventListener(events -> {
            for (DebugEvent event : events) {
                if (event.getKind() == DebugEvent.SUSPEND && event.getSource() instanceof IThread thread) {
                    JsonObject ev = new JsonObject();
                    ev.addProperty("event", event.getDetail() == DebugEvent.BREAKPOINT
                        ? "BreakpointHit" : "StepCompleted");
                    ev.addProperty("threadId", (long) System.identityHashCode(thread));
                    try { ev.addProperty("threadName", thread.getName()); } catch (Exception ignored) {}
                    try {
                        IStackFrame top = thread.getTopStackFrame();
                        if (top != null) {
                            ev.addProperty("line", top.getLineNumber());
                            if (top instanceof IJavaStackFrame jf) {
                                ev.addProperty("file", jf.getSourceName());
                                ev.addProperty("class", jf.getDeclaringTypeName());
                            }
                        }
                    } catch (Exception ignored) {}
                    eventQueue.offer(ev);
                } else if (event.getKind() == DebugEvent.TERMINATE) {
                    JsonObject ev = new JsonObject();
                    ev.addProperty("event", "Terminated");
                    eventQueue.offer(ev);
                }
            }
        });
    }

    // --- Breakpoints ---

    public void setBreakpoint(String filePath, int lineNumber, String condition) throws CoreException {
        IResource resource = resolveResource(filePath);
        if (resource == null) throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
            "Resource not found in workspace: " + filePath));

        String typeName = resolveJavaTypeName(resource);
        IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(resource, typeName, lineNumber, -1, -1, 0, true, null);
        if (condition != null && !condition.isBlank()) {
            bp.setCondition(condition);
            bp.setConditionEnabled(true);
            Activator.logInfo("Java Conditional Breakpoint Set: " + typeName + ":" + lineNumber + " if (" + condition + ")");
        } else {
            Activator.logInfo("Java Breakpoint Set: " + typeName + ":" + lineNumber);
        }
    }

    public List<Map<String, Object>> listBreakpoints() throws CoreException {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        List<Map<String, Object>> result = new ArrayList<>();
        for (IBreakpoint bp : manager.getBreakpoints()) {
            Map<String, Object> info = new HashMap<>();
            IResource resource = bp.getMarker().getResource();
            info.put("resource", resource.getFullPath().toString());
            info.put("enabled", bp.isEnabled());
            if (bp instanceof ILineBreakpoint lineBp) info.put("line", lineBp.getLineNumber());
            if (bp instanceof IJavaLineBreakpoint javaBp) {
                info.put("typeName", javaBp.getTypeName());
                String cond = javaBp.getCondition();
                if (cond != null && !cond.isBlank()) info.put("condition", cond);
            }
            result.add(info);
        }
        return result;
    }

    public void clearAllBreakpoints() throws CoreException {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        manager.removeBreakpoints(manager.getBreakpoints(), true);
        Activator.logInfo("All breakpoints cleared.");
    }

    // --- Execution Control ---

    public void resume() throws CoreException {
        executeOnActiveTarget(target -> { if (target.canResume()) target.resume(); });
    }

    public void suspend() throws CoreException {
        executeOnActiveTarget(target -> { if (target.canSuspend()) target.suspend(); });
    }

    public void stepOver() throws CoreException {
        executeOnFirstSuspendedThread(thread -> { if (thread.canStepOver()) thread.stepOver(); });
    }

    public void stepInto() throws CoreException {
        executeOnFirstSuspendedThread(thread -> { if (thread.canStepInto()) thread.stepInto(); });
    }

    public void stepReturn() throws CoreException {
        executeOnFirstSuspendedThread(thread -> { if (thread.canStepReturn()) thread.stepReturn(); });
    }

    // --- Workspace ---

    public List<Map<String, Object>> getWorkspace() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (org.eclipse.core.resources.IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", project.getName());
            info.put("open", project.isOpen());
            org.eclipse.core.runtime.IPath loc = project.getLocation();
            info.put("location", loc != null ? loc.toString() : null);
            result.add(info);
        }
        return result;
    }

    // --- Inspection ---

    public List<Map<String, Object>> getThreads() throws CoreException {
        threadRegistry.clear();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            IDebugTarget target = launch.getDebugTarget();
            if (target == null || target.isTerminated()) continue;
            for (IThread thread : target.getThreads()) {
                long id = (long) System.identityHashCode(thread);
                threadRegistry.put(id, thread);
                Map<String, Object> info = new HashMap<>();
                info.put("id", id);
                info.put("name", thread.getName());
                info.put("suspended", thread.isSuspended());
                info.put("state", thread.isSuspended() ? "SUSPENDED" : "RUNNING");
                result.add(info);
            }
        }
        return result;
    }

    public List<Map<String, Object>> getStack(long threadId) throws CoreException {
        IThread thread = threadRegistry.get(threadId);
        if (thread == null) throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
            "Thread not found: " + threadId + " — call 'threads' first"));
        frameRegistry.clear();
        List<Map<String, Object>> result = new ArrayList<>();
        for (IStackFrame frame : thread.getStackFrames()) {
            long id = (long) System.identityHashCode(frame);
            frameRegistry.put(id, frame);
            Map<String, Object> info = new HashMap<>();
            info.put("id", id);
            info.put("line", frame.getLineNumber());
            if (frame instanceof IJavaStackFrame jf) {
                info.put("class", jf.getDeclaringTypeName());
                info.put("method", jf.getMethodName());
                info.put("file", jf.getSourceName());
            }
            result.add(info);
        }
        return result;
    }

    public List<Map<String, Object>> getVariables(long frameId) throws CoreException {
        IStackFrame frame = frameRegistry.get(frameId);
        if (frame == null) throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
            "Frame not found: " + frameId + " — call 'stack' first"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (IVariable var : frame.getVariables()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", var.getName());
            try {
                IValue val = var.getValue();
                info.put("value", val.getValueString());
                info.put("type", val.getReferenceTypeName());
            } catch (Exception ignored) {
                info.put("value", "<unavailable>");
            }
            result.add(info);
        }
        return result;
    }

    // --- Problems ---

    public List<Map<String, Object>> getProblems(String project, String severity) throws CoreException {
        IResource scope = (project != null && !project.isBlank())
            ? ResourcesPlugin.getWorkspace().getRoot().getProject(project)
            : ResourcesPlugin.getWorkspace().getRoot();

        int minSeverity = switch (severity == null ? "warning" : severity.toLowerCase()) {
            case "error"       -> IMarker.SEVERITY_ERROR;
            case "info", "all" -> IMarker.SEVERITY_INFO;
            default            -> IMarker.SEVERITY_WARNING;
        };

        IMarker[] markers = scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        List<Map<String, Object>> result = new ArrayList<>();
        for (IMarker marker : markers) {
            int sev = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            if (sev < minSeverity) continue;
            Map<String, Object> info = new HashMap<>();
            info.put("file", marker.getResource().getFullPath().toString());
            info.put("line", marker.getAttribute(IMarker.LINE_NUMBER, -1));
            info.put("severity", sev == IMarker.SEVERITY_ERROR ? "error"
                               : sev == IMarker.SEVERITY_WARNING ? "warning" : "info");
            info.put("message", marker.getAttribute(IMarker.MESSAGE, ""));
            info.put("source", marker.getType());
            result.add(info);
        }
        return result;
    }

    // --- Quick Fixes ---

    public List<Map<String, Object>> getQuickFixes(String filePath, int line) throws CoreException {
        List<Map<String, Object>> result = new ArrayList<>();
        int globalIndex = 0;
        for (IMarker marker : findMarkersAtLine(filePath, line)) {
            IMarkerResolution[] resolutions = IDE.getMarkerHelpRegistry().getResolutions(marker);
            for (IMarkerResolution res : resolutions) {
                Map<String, Object> info = new HashMap<>();
                info.put("index", globalIndex++);
                info.put("label", res.getLabel());
                info.put("marker", marker.getAttribute(IMarker.MESSAGE, ""));
                result.add(info);
            }
        }
        return result;
    }

    public String applyFix(String filePath, int line, int fixIndex) throws CoreException {
        int globalIndex = 0;
        for (IMarker marker : findMarkersAtLine(filePath, line)) {
            IMarkerResolution[] resolutions = IDE.getMarkerHelpRegistry().getResolutions(marker);
            for (IMarkerResolution res : resolutions) {
                if (globalIndex++ == fixIndex) {
                    String label = res.getLabel();
                    IMarker targetMarker = marker;
                    Display display = Display.getDefault();
                    if (display == null) throw new CoreException(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "No SWT Display available"));
                    display.syncExec(() -> res.run(targetMarker));
                    return "Applied fix [" + fixIndex + "]: " + label;
                }
            }
        }
        throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID,
            "No fix at index " + fixIndex + " — call quickfixes first"));
    }

    private IMarker[] findMarkersAtLine(String filePath, int line) throws CoreException {
        IResource resource = resolveResource(filePath);
        if (resource == null) throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Resource not found: " + filePath));
        IMarker[] all = resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
        List<IMarker> atLine = new ArrayList<>();
        for (IMarker m : all) {
            if (m.getAttribute(IMarker.LINE_NUMBER, -1) == line) atLine.add(m);
        }
        return atLine.toArray(IMarker[]::new);
    }

    // --- Project Config ---

    public Map<String, Object> getClasspath(String projectName) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Project not found: " + projectName));
        if (!project.hasNature(JavaCore.NATURE_ID)) throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Not a Java project: " + projectName));

        IJavaProject javaProject = JavaCore.create(project);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (IClasspathEntry entry : javaProject.getRawClasspath()) {
            Map<String, Object> info = new HashMap<>();
            info.put("kind", switch (entry.getEntryKind()) {
                case IClasspathEntry.CPE_SOURCE    -> "source";
                case IClasspathEntry.CPE_LIBRARY   -> "library";
                case IClasspathEntry.CPE_PROJECT   -> "project";
                case IClasspathEntry.CPE_CONTAINER -> "container";
                case IClasspathEntry.CPE_VARIABLE  -> "variable";
                default -> "unknown";
            });
            info.put("path", entry.getPath().toString());
            if (entry.getOutputLocation() != null) info.put("output", entry.getOutputLocation().toString());
            entries.add(info);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("project", projectName);
        result.put("outputLocation", javaProject.getOutputLocation().toString());
        result.put("entries", entries);
        return result;
    }

    public String refreshProject(String projectName) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Project not found: " + projectName));
        NullProgressMonitor monitor = new NullProgressMonitor();
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        // Re-apply the project description to force nature re-validation
        org.eclipse.core.resources.IProjectDescription desc = project.getDescription();
        project.setDescription(desc, monitor);
        return "Refreshed: " + projectName;
    }

    public Map<String, Object> getProjectDescription(String projectName) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Project not found: " + projectName));

        org.eclipse.core.resources.IProjectDescription desc = project.getDescription();
        Map<String, Object> result = new HashMap<>();
        result.put("name", desc.getName());
        result.put("natures", List.of(desc.getNatureIds()));
        result.put("open", project.isOpen());
        org.eclipse.core.runtime.IPath loc = project.getLocation();
        result.put("location", loc != null ? loc.toString() : null);

        List<String> refs = new ArrayList<>();
        for (IProject ref : desc.getReferencedProjects()) refs.add(ref.getName());
        result.put("referencedProjects", refs);

        List<Map<String, Object>> builders = new ArrayList<>();
        for (org.eclipse.core.resources.ICommand cmd : desc.getBuildSpec()) {
            Map<String, Object> b = new HashMap<>();
            b.put("builder", cmd.getBuilderName());
            builders.add(b);
        }
        result.put("builders", builders);
        return result;
    }

    // --- Build ---

    public String build(String project, String kind) throws CoreException {
        int buildKind = switch (kind == null ? "incremental" : kind.toLowerCase()) {
            case "full"  -> IncrementalProjectBuilder.FULL_BUILD;
            case "clean" -> IncrementalProjectBuilder.CLEAN_BUILD;
            case "auto"  -> IncrementalProjectBuilder.AUTO_BUILD;
            default      -> IncrementalProjectBuilder.INCREMENTAL_BUILD;
        };
        NullProgressMonitor monitor = new NullProgressMonitor();
        if (project != null && !project.isBlank()) {
            IProject proj = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
            if (!proj.exists()) throw new CoreException(new org.eclipse.core.runtime.Status(
                org.eclipse.core.runtime.Status.ERROR, Activator.PLUGIN_ID, "Project not found: " + project));
            proj.build(buildKind, monitor);
            return "Build complete: " + project;
        }
        ResourcesPlugin.getWorkspace().build(buildKind, monitor);
        return "Workspace build complete";
    }

    // --- Event Streaming ---

    public JsonObject waitEvent(int timeoutMs) throws InterruptedException {
        JsonObject event = eventQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (event == null) {
            JsonObject timeout = new JsonObject();
            timeout.addProperty("event", "Timeout");
            return timeout;
        }
        return event;
    }

    // --- Launch ---

    public String launch(String configName) throws CoreException {
        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunchConfiguration cfg : lm.getLaunchConfigurations()) {
            if (cfg.getName().equals(configName)) {
                cfg.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
                return "launched: " + configName;
            }
        }
        throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
            "No launch config found: " + configName));
    }

    public List<String> listLaunches() throws CoreException {
        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
        List<String> names = new ArrayList<>();
        for (ILaunchConfiguration cfg : lm.getLaunchConfigurations()) {
            names.add(cfg.getName());
        }
        return names;
    }

    // --- OSGi Shell ---

    public String executeGogo(String command, BundleContext ctx) throws Exception {
        ServiceReference<CommandProcessor> ref = ctx.getServiceReference(CommandProcessor.class);
        if (ref == null) throw new Exception("Gogo CommandProcessor service not found");
        CommandProcessor processor = ctx.getService(ref);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos, true, "UTF-8");
            try (CommandSession session = processor.createSession(InputStream.nullInputStream(), out, out)) {
                Object result = session.execute(command);
                out.flush();
                String output = baos.toString("UTF-8").trim();
                // stdout itself may be a raw object ref (command printed its return value)
                if (isRawRefString(output)) output = "(unserializable: " + extractClassName(output) + ")";
                if (result != null) {
                    String rs;
                    if (looksLikeRawRef(result)) {
                        String json = new Gson().toJson(result);
                        rs = isTriviallyEmpty(json)
                            ? "(unserializable: " + result.getClass().getSimpleName() + ")"
                            : json;
                    } else {
                        rs = result.toString();
                    }
                    if (!rs.isEmpty()) output = output.isEmpty() ? rs : output + "\n" + rs;
                }
                return output.isEmpty() ? "(no output)" : output;
            }
        } finally {
            ctx.ungetService(ref);
        }
    }

    private static boolean looksLikeRawRef(Object o) {
        if (o.getClass().isArray()) return true;
        return isRawRefString(o.toString());
    }

    private static boolean isRawRefString(String s) {
        return s.matches("\\[?[\\w.$]+@[0-9a-f]{6,}\\]?");
    }

    private static String extractClassName(String rawRef) {
        // "[pkg.Outer$Inner@abc123]" -> "Outer$Inner"
        String stripped = rawRef.replaceAll("^\\[|\\]$", "");
        String className = stripped.contains("@") ? stripped.substring(0, stripped.indexOf('@')) : stripped;
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static boolean isTriviallyEmpty(String json) {
        String trimmed = json.replaceAll("\\s", "");
        return trimmed.equals("{}") || trimmed.equals("[]") || trimmed.matches("\\[\\{(\"\\w+\":\\{\\}(,\"\\w+\":\\{\\})*)?\\}\\]");
    }

    // --- Helpers ---

    private IResource resolveResource(String pathStr) {
        Path path = new Path(pathStr);
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        if (resource != null) return resource;

        IResource[] resources = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(
            java.net.URI.create("file:///" + pathStr.replace("\\", "/")));
        return resources.length > 0 ? resources[0] : null;
    }

    private String resolveJavaTypeName(IResource resource) {
        org.eclipse.jdt.core.IJavaElement element = org.eclipse.jdt.core.JavaCore.create(resource);
        if (element instanceof org.eclipse.jdt.core.ICompilationUnit cu) {
            try {
                org.eclipse.jdt.core.IType[] types = cu.getAllTypes();
                if (types.length > 0) return types[0].getFullyQualifiedName();
            } catch (org.eclipse.jdt.core.JavaModelException ignored) {}
        }
        String name = resource.getName();
        String simpleName = name.substring(0, name.lastIndexOf('.'));
        Activator.logError("Could not resolve fully-qualified type name for " + resource.getFullPath()
            + ", falling back to '" + simpleName + "'.", null);
        return simpleName;
    }

    private void executeOnActiveTarget(TargetAction action) throws CoreException {
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (!launch.isTerminated()) {
                IDebugTarget target = launch.getDebugTarget();
                if (target != null && !target.isTerminated()) action.run(target);
            }
        }
    }

    private void executeOnFirstSuspendedThread(ThreadAction action) throws CoreException {
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            IDebugTarget target = launch.getDebugTarget();
            if (target != null && !target.isTerminated()) {
                for (IThread thread : target.getThreads()) {
                    if (thread.isSuspended()) {
                        action.run(thread);
                        return;
                    }
                }
            }
        }
    }

    @FunctionalInterface interface TargetAction { void run(IDebugTarget target) throws CoreException; }
    @FunctionalInterface interface ThreadAction { void run(IThread thread) throws CoreException; }
}
