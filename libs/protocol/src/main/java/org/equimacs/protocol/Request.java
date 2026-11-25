package org.equimacs.protocol;

/**
 * A sealed interface representing all possible requests that an agent or CLI 
 * can send to the Eclipse Debug Bridge.
 * 
 * Each record is a "Fat Struct" containing all parameters for that specific action.
 */
public sealed interface Request {

    // --- Breakpoints ---
    record SetBreakpoint(String path, int line, String condition) implements Request {}
    record ClearAllBreakpoints() implements Request {}
    record ListBreakpoints() implements Request {}

    // --- Logpoints & Execution Trace ---
    record SetLogpoint(String path, int line, String message, String condition, Boolean captureVars) implements Request {}
    record GetTrace(int limit) implements Request {}
    record ClearTrace() implements Request {}

    // --- Execution Control ---
    record Resume() implements Request {}
    record Suspend() implements Request {}
    record Step(StepType stepType) implements Request {}

    // --- Shell ---
    record GogoExec(String command) implements Request {}
    record Reload() implements Request {}
    record HandlerDiagnostics() implements Request {}

    // --- Inspection ---
    record GetWorkspace() implements Request {}
    record GetThreads() implements Request {}
    record GetStack(long threadId) implements Request {}
    record GetVariables(long frameId) implements Request {}
    record Evaluate(long frameId, String expression) implements Request {}
    record GetProblems(String project, String severity) implements Request {}
    record Build(String project, String kind) implements Request {}
    record GetQuickFixes(String file, int line) implements Request {}
    record ApplyFix(String file, int line, int fixIndex) implements Request {}

    // --- Project Config ---
    record GetClasspath(String project) implements Request {}
    record SetClasspath(String project, String action, String kind, String path, String sourceAttachment) implements Request {}
    record GetProjectDescription(String project) implements Request {}
    record RefreshProject(String project) implements Request {}

    // --- Refactoring ---
    record PrepareRenameSymbol(String file, int offset, String newName) implements Request {}
    record ApplyPreparedRefactoring(String refactoringId) implements Request {}
    record AbortPreparedRefactoring(String refactoringId) implements Request {}
    record GetPreparedRefactoring(String refactoringId) implements Request {}

    // --- Event Streaming ---
    record WaitEvent(int timeoutMs) implements Request {}

    // --- Launch ---
    record Launch(String configName) implements Request {}
    record ListLaunches() implements Request {}
    record ListSessions() implements Request {}
    record Terminate() implements Request {}

    // --- Review ---
    record AddReviewComment(String file, int line, String author, String text) implements Request {}
    record ListReviewComments(String file, String filter) implements Request {
        public ListReviewComments(String file) {
            this(file, null);
        }
    }
    record ReplyToComment(String commentId, String author, String text) implements Request {}
    record ResolveComment(String commentId) implements Request {}
    record RemoveReviewComment(String commentId) implements Request {}
    record ReviewDiagnostics(String file) implements Request {}
    record EclipseWorkbench() implements Request {}
    record EclipseEditorDiagnostics() implements Request {}
    record ReviewEditorDiagnostics(String file, int line) implements Request {}

    // --- Headless Daemon ---
    record Shutdown() implements Request {}

    // --- Extension Seam ---
    record CustomCommand(String target, String action, String args) implements Request {}

    // --- Navigation & Code Understanding ---
    record FindReferences(String file, int offset) implements Request {}
    record CallHierarchy(String file, int offset, String direction, int depth) implements Request {}
    record TypeHierarchy(String file, int offset) implements Request {}
    record OpenFile(String file, int line, int endLine) implements Request {}

    // --- Testing (JUnit) ---
    record RunTests(
        String project,
        String className,
        String methodName,
        String mode,
        String runner,
        Boolean await,
        int timeoutMs
    ) implements Request {}
    record GetTestResults(String runId, Boolean failuresOnly) implements Request {}

    // --- Console Output ---
    record GetConsoleOutput(String launchName, String stream, int limit) implements Request {}
    record ClearConsoleOutput() implements Request {}

    // --- UI Workbench Commands ---
    record EclipseCommand(String commandId, String file, int offset, java.util.Map<String, String> params) implements Request {}
    record ShowView(String viewId, String secondaryId) implements Request {}

    // --- UI Inspection & Widget Driving ---
    record UiTree(String root, int maxDepth, Boolean visibleOnly, String textFilter, String widgetType) implements Request {}
    record UiClick(String widgetId, String root, String widgetType, String text, int index, String itemText, Boolean doubleClick) implements Request {}
    record UiType(String widgetId, String root, String widgetType, String text, int index, String value, Boolean append, Boolean submit) implements Request {}

    // --- Moddable View Host ---
    record ViewOpen(String viewId, String title, String specJson) implements Request {}
    record ViewUpdate(String viewId, String title, String specJson) implements Request {}
    record ViewClose(String viewId) implements Request {}
    record ViewList() implements Request {}

    // --- Moldable Inspector ---
    record InspectFrame(long frameId, Boolean openView, String viewId) implements Request {}
    record InspectValue(long frameId, String expression, int depth, Boolean openView, String viewId) implements Request {}
    record InspectSymbol(String file, int offset, Boolean openView, String viewId) implements Request {}
    record ListInspectProviders() implements Request {}

    // --- Built-in Moldable Toolkit Views ---
    record OpenCallExplorer(
        String file,
        int offset,
        String direction,
        int depth,
        Boolean hideJdk,
        String viewMode,
        String viewId,
        String groupBy,
        int maxFanout,
        String compareFile,
        int compareOffset
    ) implements Request {
        public OpenCallExplorer(
            String file,
            int offset,
            String direction,
            int depth,
            Boolean hideJdk,
            String viewMode,
            String viewId
        ) {
            this(file, offset, direction, depth, hideJdk, viewMode, viewId, null, 0, null, -1);
        }
    }
    record OpenTraceView(int limit, Boolean live, String viewId) implements Request {}
    record OpenJUnitView(String runId, Boolean failuresOnly, Boolean live, String viewId) implements Request {}

    /**
     * Types of stepping available.
     */
    enum StepType {
        OVER, INTO, RETURN
    }
}
