package org.equimacs.protocol;

/**
 * A machine-readable definition of the Equimacs Protocol.
 * Agents can query this to understand available commands, parameters, and events.
 */
public class ProtocolSchema {
    public static final String JSON_SCHEMA = """
    {
      "protocol_version": "0.1.0",
      "commands": {
        "bp": {
          "description": "Set a line breakpoint",
          "record": "SetBreakpoint",
          "params": {
            "path": { "type": "string", "description": "Workspace-relative or absolute file path" },
            "line": { "type": "integer", "description": "1-based line number" },
            "condition": { "type": "string", "optional": true, "description": "Conditional expression (Java/JS)" }
          }
        },
        "list": {
          "description": "List all registered breakpoints",
          "aliases": ["bps"],
          "record": "ListBreakpoints",
          "params": {}
        },
        "clear": {
          "description": "Clear all breakpoints in the workspace",
          "record": "ClearAllBreakpoints",
          "params": {}
        },
        "resume": {
          "description": "Resume execution of all threads",
          "record": "Resume",
          "params": {}
        },
        "suspend": {
          "description": "Suspend execution of all threads",
          "record": "Suspend",
          "params": {}
        },
        "step": {
          "description": "Step execution of the first suspended thread",
          "record": "Step",
          "params": {
            "type": { "type": "string", "enum": ["OVER", "INTO", "RETURN"], "default": "OVER" }
          }
        },
        "reload": {
          "description": "Hot-reload the bridge plugin in-process; CLI polls until socket is back",
          "record": "Reload",
          "params": {}
        },
        "gogo": {
          "description": "Execute an arbitrary Gogo shell command and return its output",
          "record": "GogoExec",
          "params": {
            "command": { "type": "string", "description": "The Gogo shell command line to execute" }
          }
        },
        "threads": {
          "description": "List all active threads in the debug session",
          "record": "GetThreads",
          "params": {}
        },
        "stack": {
          "description": "Get the stack trace for a specific thread",
          "record": "GetStack",
          "params": {
            "threadId": { "type": "long", "description": "Unique ID of the thread" }
          }
        },
        "vars": {
          "description": "Get variables for a specific stack frame",
          "record": "GetVariables",
          "params": {
            "frameId": { "type": "long", "description": "Unique ID of the stack frame" }
          }
        }
      },
      "responses": {
        "Success": { "fields": ["result (object)"] },
        "Error": { "fields": ["message (string)", "trace (string/null)"] },
        "Events": {
          "BreakpointHit": { "fields": ["path", "line", "threadId"] },
          "StepCompleted": { "fields": ["threadId"] },
          "ThreadSuspended": { "fields": ["threadId", "reason"] },
          "ThreadResumed": { "fields": ["threadId"] },
          "ThreadTerminated": { "fields": ["threadId"] }
        }
      }
    }
    """;
}
