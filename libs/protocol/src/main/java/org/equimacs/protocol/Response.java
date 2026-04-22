package org.equimacs.protocol;

/**
 * A sealed interface representing all possible responses that the Eclipse Debug
 * Bridge can send back to an agent or CLI.
 */
public sealed interface Response {

    /**
     * A successful response containing the result of the request.
     */
    record Success(Object result) implements Response {}

    /**
     * A response sent when a request fails or an error occurs in the bridge.
     */
    record Error(String message, String trace) implements Response {}

    /**
     * Unsolicited notifications sent by the bridge (Events).
     */
    sealed interface Event extends Response permits 
        BreakpointHit, 
        StepCompleted, 
        ThreadSuspended, 
        ThreadResumed, 
        ThreadTerminated {}

    record BreakpointHit(String path, int line, long threadId) implements Event {}
    record StepCompleted(long threadId) implements Event {}
    record ThreadSuspended(long threadId, String reason) implements Event {}
    record ThreadResumed(long threadId) implements Event {}
    record ThreadTerminated(long threadId) implements Event {}
}
