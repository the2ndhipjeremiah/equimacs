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

    // --- Execution Control ---
    record Resume() implements Request {}
    record Suspend() implements Request {}
    record Step(StepType type) implements Request {}

    // --- Shell ---
    record GogoExec(String command) implements Request {}
    record Reload() implements Request {}

    // --- Inspection ---
    record GetWorkspace() implements Request {}
    record GetThreads() implements Request {}
    record GetStack(long threadId) implements Request {}
    record GetVariables(long frameId) implements Request {}
    record GetProblems(String project, String severity) implements Request {}
    record Build(String project, String kind) implements Request {}
    record GetQuickFixes(String file, int line) implements Request {}
    record ApplyFix(String file, int line, int fixIndex) implements Request {}

    // --- Project Config ---
    record GetClasspath(String project) implements Request {}
    record GetProjectDescription(String project) implements Request {}

    /**
     * Types of stepping available.
     */
    enum StepType {
        OVER, INTO, RETURN
    }
}
