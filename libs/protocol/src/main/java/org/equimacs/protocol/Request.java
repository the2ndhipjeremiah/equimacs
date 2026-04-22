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

    // --- Execution Control ---
    record Resume() implements Request {}
    record Suspend() implements Request {}
    record Step(StepType type) implements Request {}

    // --- Inspection ---
    record GetThreads() implements Request {}
    record GetStack(long threadId) implements Request {}
    record GetVariables(long frameId) implements Request {}

    /**
     * Types of stepping available.
     */
    enum StepType {
        OVER, INTO, RETURN
    }
}
