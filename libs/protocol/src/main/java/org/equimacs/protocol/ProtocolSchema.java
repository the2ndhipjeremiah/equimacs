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
            "stepType": { "type": "string", "enum": ["OVER", "INTO", "RETURN"], "default": "OVER" }
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
        "handlers": {
          "description": "Inspect bridge command handler dispatch and OSGi service binding state",
          "record": "HandlerDiagnostics",
          "params": {}
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
        },
        "refactor-prepare": {
          "description": "Prepare a Java refactoring preview without applying edits",
          "record": "PrepareRenameSymbol",
          "params": {
            "kind": { "type": "string", "enum": ["rename-symbol"] },
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" },
            "newName": { "type": "string", "description": "New Java symbol name" }
          }
        },
        "refactor-apply": {
          "description": "Apply a previously prepared refactoring",
          "record": "ApplyPreparedRefactoring",
          "params": {
            "refactoringId": { "type": "string" }
          }
        },
        "refactor-abort": {
          "description": "Discard a previously prepared refactoring",
          "record": "AbortPreparedRefactoring",
          "params": {
            "refactoringId": { "type": "string" }
          }
        },
        "refactor-status": {
          "description": "Inspect a previously prepared refactoring preview",
          "record": "GetPreparedRefactoring",
          "params": {
            "refactoringId": { "type": "string" }
          }
        },
        "review-add": {
          "description": "Post a review comment on a file:line",
          "record": "AddReviewComment",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative file path" },
            "line": { "type": "integer", "description": "1-based line number" },
            "author": { "type": "string", "description": "Author name (default: user)" },
            "text": { "type": "string", "description": "Comment body" }
          }
        },
        "review-list": {
          "description": "List review comment threads, optionally filtered to a file",
          "record": "ListReviewComments",
          "params": {
            "file": { "type": "string", "optional": true, "description": "Workspace-relative file path; omit for all" }
          }
        },
        "review-reply": {
          "description": "Reply to an existing review comment thread",
          "record": "ReplyToComment",
          "params": {
            "commentId": { "type": "string" },
            "author": { "type": "string", "description": "Author name (default: user)" },
            "text": { "type": "string" }
          }
        },
        "review-resolve": {
          "description": "Mark a review comment thread as resolved",
          "record": "ResolveComment",
          "params": {
            "commentId": { "type": "string" }
          }
        },
        "review-diagnostics": {
          "description": "Inspect review comments, markers, and annotation preferences",
          "record": "ReviewDiagnostics",
          "params": {
            "file": { "type": "string", "optional": true, "description": "Workspace-relative file path; omit for all" }
          }
        },
        "eclipse-workbench": {
          "description": "Inspect active Eclipse workbench/window/page/editor state",
          "record": "EclipseWorkbench",
          "params": {}
        },
        "eclipse-editor-diagnostics": {
          "description": "Inspect the active Eclipse editor, document, selection, and annotation model",
          "record": "EclipseEditorDiagnostics",
          "params": {}
        },
        "review-editor-diagnostics": {
          "description": "Inspect review annotations in the active Eclipse editor",
          "record": "ReviewEditorDiagnostics",
          "params": {
            "file": { "type": "string", "optional": true, "description": "Workspace-relative file path" },
            "line": { "type": "integer", "optional": true, "description": "1-based line number" }
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
