package org.equimacs.debug;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolutionGenerator;
import org.eclipse.ui.IMarkerResolutionGenerator2;
import org.eclipse.ui.PlatformUI;
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
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

public class JavaDebugController {
    private static final String PLUGIN_ID = "org.equimacs.debug";
    private static final NullProgressMonitor NULL_MONITOR = new NullProgressMonitor();

    private final Map<Long, IThread> threadRegistry = new ConcurrentHashMap<>();
    private final Map<Long, IStackFrame> frameRegistry = new ConcurrentHashMap<>();
    private final Map<String, PreparedRefactoring> preparedRefactorings = new ConcurrentHashMap<>();

    private volatile Consumer<JsonObject> eventSink;
    private IDebugEventSetListener listener;

    private record PreparedRefactoring(
        String id,
        String kind,
        long createdAtMillis,
        Map<String, Object> preview,
        Change change
    ) {}

    public void init(Consumer<JsonObject> eventSink) {
        this.eventSink = eventSink;
        this.listener = events -> {
            Consumer<JsonObject> sink = this.eventSink;
            if (sink == null) return;
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
                    sink.accept(ev);
                } else if (event.getKind() == DebugEvent.TERMINATE) {
                    JsonObject ev = new JsonObject();
                    ev.addProperty("event", "Terminated");
                    sink.accept(ev);
                }
            }
        };
        DebugPlugin.getDefault().addDebugEventListener(listener);
    }

    public void dispose() {
        if (listener != null) {
            DebugPlugin plugin = DebugPlugin.getDefault();
            if (plugin != null) plugin.removeDebugEventListener(listener);
            listener = null;
        }
        eventSink = null;
        threadRegistry.clear();
        frameRegistry.clear();
        for (PreparedRefactoring refactoring : preparedRefactorings.values()) {
            refactoring.change().dispose();
        }
        preparedRefactorings.clear();
    }

    // --- Breakpoints ---

    public void setBreakpoint(String filePath, int lineNumber, String condition) throws CoreException {
        IResource resource = requireResource(filePath, "Resource not found in workspace: " + filePath);

        String typeName = resolveJavaTypeName(resource);
        IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(resource, typeName, lineNumber, -1, -1, 0, true, null);
        if (condition != null && !condition.isBlank()) {
            bp.setCondition(condition);
            bp.setConditionEnabled(true);
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
        if (thread == null) throw error("Thread not found: " + threadId + " — call 'threads' first");
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
        if (frame == null) throw error("Frame not found: " + frameId + " — call 'stack' first");
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
            IMarkerResolution[] resolutions = getMarkerResolutions(marker);
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
            IMarkerResolution[] resolutions = getMarkerResolutions(marker);
            for (IMarkerResolution res : resolutions) {
                if (globalIndex++ == fixIndex) {
                    String label = res.getLabel();
                    IMarker targetMarker = marker;
                    Display display = Display.getDefault();
                    if (display == null) {
                        res.run(targetMarker);
                    } else {
                        display.syncExec(() -> res.run(targetMarker));
                    }
                    return "Applied fix [" + fixIndex + "]: " + label;
                }
            }
        }
        throw error("No fix at index " + fixIndex + " — call quickfixes first");
    }

    private IMarkerResolution[] getMarkerResolutions(IMarker marker) throws CoreException {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return getHeadlessMarkerResolutions(marker);
            }
        } catch (RuntimeException e) {
            return getHeadlessMarkerResolutions(marker);
        }

        try {
            return IDE.getMarkerHelpRegistry().getResolutions(marker);
        } catch (RuntimeException e) {
            List<IMarkerResolution> result = new ArrayList<>();
            for (IMarkerResolution resolution : getMarkerResolutionsFromExtensions(marker)) {
                result.add(resolution);
            }
            for (IMarkerResolution resolution : getHeadlessMarkerResolutions(marker)) {
                result.add(resolution);
            }
            return result.toArray(IMarkerResolution[]::new);
        }
    }

    private IMarkerResolution[] getMarkerResolutionsFromExtensions(IMarker marker) throws CoreException {
        List<IMarkerResolution> result = new ArrayList<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) return new IMarkerResolution[0];

        IConfigurationElement[] elements = registry.getConfigurationElementsFor("org.eclipse.ui.ide.markerResolution");
        for (IConfigurationElement element : elements) {
            if (!element.getName().equals("markerResolutionGenerator")) continue;

            String markerType = element.getAttribute("markerType");
            if (markerType == null || !marker.isSubtypeOf(markerType)) continue;

            try {
                Object executable = element.createExecutableExtension("class");
                if (executable instanceof IMarkerResolutionGenerator2 generator2
                    && !generator2.hasResolutions(marker)) {
                    continue;
                }
                if (executable instanceof IMarkerResolutionGenerator generator) {
                    for (IMarkerResolution resolution : generator.getResolutions(marker)) {
                        result.add(resolution);
                    }
                }
            } catch (CoreException | RuntimeException e) {
                // Some UI-contributed resolvers still require a workbench in headless eqmd.
            }
        }
        return result.toArray(IMarkerResolution[]::new);
    }

    private IMarkerResolution[] getHeadlessMarkerResolutions(IMarker marker) throws CoreException {
        String message = marker.getAttribute(IMarker.MESSAGE, "");
        List<IMarkerResolution> result = new ArrayList<>();
        addJavaUtilImportResolution(marker, message, result, "List");
        addJavaUtilImportResolution(marker, message, result, "ArrayList");
        addJavaUtilImportResolution(marker, message, result, "Map");
        addJavaUtilImportResolution(marker, message, result, "HashMap");
        addJavaUtilImportResolution(marker, message, result, "Set");
        addJavaUtilImportResolution(marker, message, result, "HashSet");
        return result.toArray(IMarkerResolution[]::new);
    }

    private void addJavaUtilImportResolution(
        IMarker marker,
        String message,
        List<IMarkerResolution> result,
        String simpleName
    ) {
        String importName = "java.util." + simpleName;
        if (!message.contains(simpleName + " cannot be resolved to a type")) return;
        if (!(marker.getResource() instanceof IFile file)) return;
        try {
            String source = readFile(file);
            if (!source.contains(simpleName) || source.contains("import " + importName + ";")) return;
            result.add(new ImportResolution(importName));
        } catch (CoreException e) {
            // Ignore broken fallback candidates; marker listing should still succeed.
        }
    }

    private String readFile(IFile file) throws CoreException {
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw error("Could not read " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private final class ImportResolution implements IMarkerResolution {
        private final String importName;

        private ImportResolution(String importName) {
            this.importName = importName;
        }

        @Override
        public String getLabel() {
            int lastDot = importName.lastIndexOf('.');
            String simpleName = importName.substring(lastDot + 1);
            return "Import '" + simpleName + "' (" + importName + ")";
        }

        @Override
        public void run(IMarker marker) {
            try {
                if (!(marker.getResource() instanceof IFile file)) return;
                String source = readFile(file);
                if (source.contains("import " + importName + ";")) return;

                String updated = insertImport(source, importName);
                byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
                file.setContents(new ByteArrayInputStream(bytes), true, true, NULL_MONITOR);
            } catch (CoreException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String insertImport(String source, String importName) {
        String importLine = "import " + importName + ";" + System.lineSeparator();
        if (source.startsWith("package ")) {
            int packageEnd = source.indexOf(';');
            if (packageEnd >= 0) {
                int insertAt = packageEnd + 1;
                String lineBreak = source.startsWith("\r\n", insertAt) ? "\r\n" : "\n";
                if (source.startsWith(lineBreak, insertAt)) insertAt += lineBreak.length();
                return source.substring(0, insertAt) + importLine + source.substring(insertAt);
            }
        }
        return importLine + source;
    }

    private IMarker[] findMarkersAtLine(String filePath, int line) throws CoreException {
        IResource resource = requireResource(filePath, "Resource not found: " + filePath);
        IMarker[] all = resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
        List<IMarker> atLine = new ArrayList<>();
        for (IMarker m : all) {
            if (m.getAttribute(IMarker.LINE_NUMBER, -1) == line) atLine.add(m);
        }
        return atLine.toArray(IMarker[]::new);
    }

    // --- Refactoring ---

    public Map<String, Object> prepareRenameSymbol(String filePath, int offset, String newName) throws CoreException {
        IResource resource = requireResource(filePath, "Resource not found: " + filePath);
        if (!(resource instanceof IFile file)) {
            throw error("Refactoring target is not a file: " + filePath);
        }

        IJavaElement javaElement = JavaCore.create(file);
        if (!(javaElement instanceof ICompilationUnit unit)) {
            throw error("Refactoring target is not a Java compilation unit: " + filePath);
        }

        IJavaElement[] selected = unit.codeSelect(offset, 0);
        if (selected.length == 0) {
            selected = unit.codeSelect(offset, 1);
        }
        if (selected.length == 0) {
            throw error("No Java symbol found at offset " + offset + " in " + filePath);
        }

        IJavaElement target = selected[0];
        String refactoringKind = renameRefactoringId(target);
        RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(refactoringKind);
        if (contribution == null) {
            throw error("Rename refactoring is unavailable for " + refactoringKind);
        }

        RenameJavaElementDescriptor descriptor =
            (RenameJavaElementDescriptor) contribution.createDescriptor();
        descriptor.setProject(file.getProject().getName());
        descriptor.setJavaElement(target);
        descriptor.setNewName(newName);
        descriptor.setUpdateReferences(true);

        RefactoringStatus status = descriptor.validateDescriptor();
        Refactoring refactoring = descriptor.createRefactoring(status);
        if (refactoring == null) {
            throw error("Could not create rename refactoring: " + status.toString());
        }

        CheckConditionsOperation checks =
            new CheckConditionsOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
        CreateChangeOperation create =
            new CreateChangeOperation(checks, RefactoringCore.getConditionCheckingFailedSeverity());
        ResourcesPlugin.getWorkspace().run(create, NULL_MONITOR);
        status.merge(create.getConditionCheckingStatus());

        Change change = create.getChange();
        if (change == null) {
            throw error("Rename refactoring did not produce a change: " + status.toString());
        }

        String id = "rfc_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> preview = previewForChange(id, "rename-symbol", status, change);
        preview.put("symbol", target.getElementName());
        preview.put("newName", newName);

        preparedRefactorings.put(id, new PreparedRefactoring(
            id,
            "rename-symbol",
            System.currentTimeMillis(),
            preview,
            change));
        return preview;
    }

    public Map<String, Object> getPreparedRefactoring(String refactoringId) throws CoreException {
        PreparedRefactoring refactoring = preparedRefactorings.get(refactoringId);
        if (refactoring == null) {
            throw error("Prepared refactoring not found: " + refactoringId);
        }
        return refactoring.preview();
    }

    public Map<String, Object> abortPreparedRefactoring(String refactoringId) throws CoreException {
        PreparedRefactoring refactoring = preparedRefactorings.remove(refactoringId);
        if (refactoring == null) {
            throw error("Prepared refactoring not found: " + refactoringId);
        }
        refactoring.change().dispose();

        Map<String, Object> result = new HashMap<>();
        result.put("refactoringId", refactoringId);
        result.put("kind", refactoring.kind());
        result.put("aborted", true);
        return result;
    }

    public Map<String, Object> applyPreparedRefactoring(String refactoringId) throws CoreException {
        PreparedRefactoring refactoring = preparedRefactorings.remove(refactoringId);
        if (refactoring == null) {
            throw error("Prepared refactoring not found: " + refactoringId);
        }

        Change change = refactoring.change();
        try {
            PerformChangeOperation perform = new PerformChangeOperation(change);
            ResourcesPlugin.getWorkspace().run(perform, NULL_MONITOR);
            RefactoringStatus validation = perform.getValidationStatus();
            if (validation != null && validation.hasError()) {
                throw error("Prepared refactoring is no longer valid: " + validation.toString());
            }

            Map<String, Object> summary = castMap(refactoring.preview().get("summary"));
            Map<String, Object> result = new HashMap<>();
            result.put("refactoringId", refactoringId);
            result.put("kind", refactoring.kind());
            result.put("applied", true);
            result.put("filesChanged", summary.get("filesChanged"));
            result.put("textEdits", summary.get("textEdits"));
            return result;
        } finally {
            change.dispose();
        }
    }

    private String renameRefactoringId(IJavaElement element) throws CoreException {
        return switch (element.getElementType()) {
            case IJavaElement.LOCAL_VARIABLE -> IJavaRefactorings.RENAME_LOCAL_VARIABLE;
            case IJavaElement.FIELD -> IJavaRefactorings.RENAME_FIELD;
            case IJavaElement.METHOD -> IJavaRefactorings.RENAME_METHOD;
            case IJavaElement.TYPE -> IJavaRefactorings.RENAME_TYPE;
            default -> throw error("Unsupported rename target: " + element.getElementName());
        };
    }

    private Map<String, Object> previewForChange(
        String refactoringId,
        String kind,
        RefactoringStatus status,
        Change change
    ) throws CoreException {
        List<Map<String, Object>> changes = new ArrayList<>();
        collectChangePreview(change, changes);

        int textEdits = 0;
        for (Map<String, Object> entry : changes) {
            textEdits += ((List<?>) entry.get("edits")).size();
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("filesChanged", changes.size());
        summary.put("textEdits", textEdits);

        Map<String, Object> result = new HashMap<>();
        result.put("refactoringId", refactoringId);
        result.put("kind", kind);
        result.put("status", statusToMap(status));
        result.put("summary", summary);
        result.put("changes", changes);
        return result;
    }

    private void collectChangePreview(Change change, List<Map<String, Object>> changes) throws CoreException {
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectChangePreview(child, changes);
            }
            return;
        }

        if (change instanceof TextChange textChange) {
            Map<String, Object> entry = new HashMap<>();
            if (change instanceof TextFileChange fileChange) {
                entry.put("file", fileChange.getFile().getFullPath().toString());
            } else {
                Object modified = change.getModifiedElement();
                entry.put("file", modified != null ? modified.toString() : change.getName());
            }

            List<Map<String, Object>> edits = new ArrayList<>();
            TextEdit root = textChange.getEdit();
            if (root != null) {
                collectTextEdits(root, edits);
            }
            if (!edits.isEmpty()) {
                entry.put("edits", edits);
                changes.add(entry);
            }
        }
    }

    private void collectTextEdits(TextEdit edit, List<Map<String, Object>> edits) {
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collectTextEdits(child, edits);
            }
            return;
        }

        Map<String, Object> entry = new HashMap<>();
        if (edit instanceof ReplaceEdit replace) {
            entry.put("start", replace.getOffset());
            entry.put("end", replace.getOffset() + replace.getLength());
            entry.put("replacement", replace.getText());
        } else if (edit instanceof InsertEdit insert) {
            entry.put("start", insert.getOffset());
            entry.put("end", insert.getOffset());
            entry.put("replacement", insert.getText());
        } else if (edit instanceof DeleteEdit delete) {
            entry.put("start", delete.getOffset());
            entry.put("end", delete.getOffset() + delete.getLength());
            entry.put("replacement", "");
        } else {
            return;
        }
        edits.add(entry);
    }

    private Map<String, Object> statusToMap(RefactoringStatus status) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", !status.hasError() && !status.hasFatalError());
        result.put("severity", severityName(status.getSeverity()));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            Map<String, Object> info = new HashMap<>();
            info.put("severity", severityName(entry.getSeverity()));
            info.put("message", entry.getMessage());
            entries.add(info);
        }
        result.put("entries", entries);
        return result;
    }

    private String severityName(int severity) {
        return switch (severity) {
            case RefactoringStatus.FATAL -> "fatal";
            case RefactoringStatus.ERROR -> "error";
            case RefactoringStatus.WARNING -> "warning";
            case RefactoringStatus.INFO -> "info";
            default -> "ok";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    // --- Project Config ---

    public Map<String, Object> getClasspath(String projectName) throws CoreException {
        IJavaProject javaProject = requireJavaProject(projectName);
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
        IProject project = requireProject(projectName);
        project.refreshLocal(IResource.DEPTH_INFINITE, NULL_MONITOR);
        org.eclipse.core.resources.IProjectDescription desc = project.getDescription();
        project.setDescription(desc, NULL_MONITOR);
        return "Refreshed: " + projectName;
    }

    public Map<String, Object> getProjectDescription(String projectName) throws CoreException {
        IProject project = requireProject(projectName);

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
        if (project != null && !project.isBlank()) {
            requireProject(project).build(buildKind, NULL_MONITOR);
            return "Build complete: " + project;
        }
        ResourcesPlugin.getWorkspace().build(buildKind, NULL_MONITOR);
        return "Workspace build complete";
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
        throw error("No launch config found: " + configName);
    }

    public List<String> listLaunches() throws CoreException {
        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
        List<String> names = new ArrayList<>();
        for (ILaunchConfiguration cfg : lm.getLaunchConfigurations()) {
            names.add(cfg.getName());
        }
        return names;
    }

    public List<Map<String, Object>> listSessions() throws CoreException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (launch.isTerminated()) continue;
            Map<String, Object> info = new HashMap<>();
            info.put("name", launch.getLaunchConfiguration() != null
                ? launch.getLaunchConfiguration().getName() : "unknown");
            info.put("terminated", false);
            IDebugTarget target = launch.getDebugTarget();
            info.put("threads", target != null && !target.isTerminated()
                ? target.getThreads().length : 0);
            result.add(info);
        }
        return result;
    }

    public String terminate() throws CoreException {
        int count = 0;
        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (!launch.isTerminated()) {
                launch.terminate();
                count++;
            }
        }
        return "Terminated " + count + " session(s)";
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

    private IResource requireResource(String pathStr, String message) throws CoreException {
        IResource resource = resolveResource(pathStr);
        if (resource == null) {
            throw error(message);
        }
        return resource;
    }

    private IProject requireProject(String projectName) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) {
            throw error("Project not found: " + projectName);
        }
        return project;
    }

    private IJavaProject requireJavaProject(String projectName) throws CoreException {
        IProject project = requireProject(projectName);
        if (!project.hasNature(JavaCore.NATURE_ID)) {
            throw error("Not a Java project: " + projectName);
        }
        return JavaCore.create(project);
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
        return name.substring(0, name.lastIndexOf('.'));
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

    private CoreException error(String message) {
        return new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, message));
    }

    @FunctionalInterface interface TargetAction { void run(IDebugTarget target) throws CoreException; }
    @FunctionalInterface interface ThreadAction { void run(IThread thread) throws CoreException; }
}
