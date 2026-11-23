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
        "logpoint": {
          "description": "Set a non-blocking logpoint that records interpolated messages and variable snapshots",
          "aliases": ["lp"],
          "record": "SetLogpoint",
          "params": {
            "path": { "type": "string", "description": "Workspace-relative or absolute file path" },
            "line": { "type": "integer", "description": "1-based line number" },
            "message": { "type": "string", "optional": true, "description": "Message template with {expr} placeholders" },
            "condition": { "type": "string", "optional": true, "description": "Conditional Java expression" },
            "captureVars": { "type": "boolean", "optional": true, "default": true, "description": "Whether to capture local variable snapshots" }
          }
        },
        "trace": {
          "description": "Retrieve recent execution trace entries recorded by logpoints",
          "record": "GetTrace",
          "params": {
            "limit": { "type": "integer", "optional": true, "default": 100, "description": "Maximum number of recent entries to return" }
          }
        },
        "trace-clear": {
          "description": "Clear the execution trace buffer",
          "record": "ClearTrace",
          "params": {}
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
        "eval": {
          "description": "Evaluate a Java expression in a suspended stack frame",
          "aliases": ["evaluate"],
          "record": "Evaluate",
          "params": {
            "frameId": { "type": "long", "description": "Unique ID of the stack frame" },
            "expression": { "type": "string", "description": "Java expression to evaluate" }
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
          "description": "List review comment threads, optionally filtered to a file and/or git diff status",
          "record": "ListReviewComments",
          "params": {
            "file": { "type": "string", "optional": true, "description": "Workspace-relative file path; omit for all" },
            "filter": { "type": "string", "optional": true, "enum": ["all", "changed-files", "changed-hunks"], "default": "all", "description": "Filter threads against current git working tree diff" }
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
        },
        "custom": {
          "description": "Invoke a custom IBridgeCommandHandler registered by an external OSGi bundle",
          "record": "CustomCommand",
          "params": {
            "target": { "type": "string", "description": "Handler target key (equimacs.commands)" },
            "action": { "type": "string", "description": "Action name within the target handler" },
            "args": { "type": "string", "optional": true, "description": "Optional JSON payload string" }
          }
        },
        "find-refs": {
          "description": "Find all workspace references to the Java symbol at <file>:<offset>",
          "record": "FindReferences",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" }
          }
        },
        "call-hierarchy": {
          "description": "Compute callers or callees for the Java method at <file>:<offset>",
          "record": "CallHierarchy",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" },
            "direction": { "type": "string", "enum": ["callers", "callees"], "default": "callers" },
            "depth": { "type": "integer", "default": 1, "description": "Traversal depth (1..5)" }
          }
        },
        "type-hierarchy": {
          "description": "Inspect superclasses, interfaces, and subtypes for the Java type at <file>:<offset>",
          "record": "TypeHierarchy",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" }
          }
        },
        "open-file": {
          "description": "Open a file in the active Eclipse workbench editor and optionally select a line or line range",
          "record": "OpenFile",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute file path" },
            "line": { "type": "integer", "optional": true, "description": "1-based start line" },
            "endLine": { "type": "integer", "optional": true, "description": "1-based end line" }
          }
        },
        "run-tests": {
          "description": "Run JUnit tests for a Java project, class, or method",
          "record": "RunTests",
          "params": {
            "project": { "type": "string", "description": "Java project name in workspace" },
            "className": { "type": "string", "optional": true, "description": "Fully qualified test class name" },
            "methodName": { "type": "string", "optional": true, "description": "Single test method name (requires className)" },
            "mode": { "type": "string", "enum": ["run", "debug"], "default": "run", "optional": true },
            "runner": { "type": "string", "enum": ["junit6", "junit5", "junit4", "junit3"], "optional": true },
            "await": { "type": "boolean", "optional": true, "description": "Wait for completion (defaults true in run mode, false in debug mode)" },
            "timeoutMs": { "type": "integer", "optional": true, "default": 60000 }
          }
        },
        "test-results": {
          "description": "Inspect results and parsed stack frames for a JUnit test run",
          "record": "GetTestResults",
          "params": {
            "runId": { "type": "string", "optional": true, "description": "Test run id (defaults to most recent run)" },
            "failuresOnly": { "type": "boolean", "optional": true, "default": false }
          }
        },
        "console": {
          "description": "Read captured stdout/stderr console output from launched processes",
          "record": "GetConsoleOutput",
          "params": {
            "launchName": { "type": "string", "optional": true, "description": "Filter by launch configuration name" },
            "stream": { "type": "string", "optional": true, "enum": ["all", "stdout", "stderr"], "default": "all" },
            "limit": { "type": "integer", "optional": true, "default": 200 }
          }
        },
        "console-clear": {
          "description": "Clear captured console output buffer",
          "record": "ClearConsoleOutput",
          "params": {}
        },
        "eclipse-command": {
          "description": "Execute an Eclipse workbench command ID, optionally opening file:offset first",
          "record": "EclipseCommand",
          "params": {
            "commandId": { "type": "string", "description": "Eclipse workbench command ID" },
            "file": { "type": "string", "optional": true, "description": "Optional file path to open before running the command" },
            "offset": { "type": "integer", "optional": true, "default": -1, "description": "Optional character offset to select" },
            "params": { "type": "object", "optional": true, "description": "Optional key/value parameters for parameterized Eclipse commands" }
          }
        },
        "show-view": {
          "description": "Open and activate an Eclipse workbench view by ID",
          "record": "ShowView",
          "params": {
            "viewId": { "type": "string", "description": "Eclipse view ID (e.g. org.equimacs.eclipse.ui.reviewView)" },
            "secondaryId": { "type": "string", "optional": true, "description": "Optional secondary view ID for multi-instance views" }
          }
        },
        "ui-tree": {
          "description": "Inspect the live SWT widget hierarchy and Eclipse workbench/E4 parts",
          "record": "UiTree",
          "params": {
            "root": { "type": "string", "optional": true, "default": "active", "description": "Root scope: active, all, dialog, editor, or view:<viewId>" },
            "maxDepth": { "type": "integer", "optional": true, "default": 8, "description": "Maximum widget tree depth (1..32)" },
            "visibleOnly": { "type": "boolean", "optional": true, "default": true, "description": "When true, omit hidden SWT controls" },
            "textFilter": { "type": "string", "optional": true, "description": "Case-insensitive substring filter matched against widget text/tooltip/message/elementId/items" },
            "widgetType": { "type": "string", "optional": true, "description": "Case-insensitive SWT widget class simple-name filter (e.g. Button, Text, Tree)" }
          }
        },
        "ui-click": {
          "description": "Drive a click/selection event on an SWT widget or item",
          "record": "UiClick",
          "params": {
            "widgetId": { "type": "string", "optional": true, "description": "Runtime widget ID from ui-tree (e.g. w_14)" },
            "root": { "type": "string", "optional": true, "default": "active", "description": "Root scope for selector matching when widgetId is omitted" },
            "widgetType": { "type": "string", "optional": true, "description": "Widget simple class name selector" },
            "text": { "type": "string", "optional": true, "description": "Widget text/tooltip/message selector" },
            "index": { "type": "integer", "optional": true, "default": 0, "description": "0-based match index when selector matches multiple widgets" },
            "itemText": { "type": "string", "optional": true, "description": "Item text for Tree, Table, List, Combo, TabFolder, or CTabFolder" },
            "doubleClick": { "type": "boolean", "optional": true, "default": false, "description": "Also dispatch SWT.DefaultSelection" }
          }
        },
        "ui-type": {
          "description": "Focus and enter text into an SWT Text, StyledText, or Combo widget",
          "record": "UiType",
          "params": {
            "widgetId": { "type": "string", "optional": true, "description": "Runtime widget ID from ui-tree" },
            "root": { "type": "string", "optional": true, "default": "active", "description": "Root scope for selector matching when widgetId is omitted" },
            "widgetType": { "type": "string", "optional": true, "description": "Widget simple class name selector" },
            "text": { "type": "string", "optional": true, "description": "Widget text/message/tooltip selector" },
            "index": { "type": "integer", "optional": true, "default": 0, "description": "0-based match index" },
            "value": { "type": "string", "description": "Text to set or append" },
            "append": { "type": "boolean", "optional": true, "default": false, "description": "Append to existing text instead of replacing" },
            "submit": { "type": "boolean", "optional": true, "default": false, "description": "Dispatch Return traversal / default selection after entering text" }
          }
        },
        "view-open": {
          "description": "Open or replace a multi-instance native SWT moddable view from a JSON ViewSpec (with optional top-level live event binding)",
          "record": "ViewOpen",
          "params": {
            "viewId": { "type": "string", "description": "Logical view instance ID (alphanumeric, dot, underscore, hyphen)" },
            "title": { "type": "string", "optional": true, "description": "Optional tab title override" },
            "specJson": { "type": "string", "description": "JSON ViewSpec (kinds: table, tree, markdown, key-value, tabs, graph, split, form, html — native Java/SWT kinds including graph preferred; html is optional WebView2) plus optional top-level live binding ({ events, onEvent, pushToHtml, debounceMs })" }
          }
        },
        "view-update": {
          "description": "Update an existing moddable view with a new JSON ViewSpec (with optional top-level live event binding)",
          "record": "ViewUpdate",
          "params": {
            "viewId": { "type": "string", "description": "Existing logical view instance ID" },
            "title": { "type": "string", "optional": true, "description": "Optional tab title override" },
            "specJson": { "type": "string", "description": "JSON ViewSpec (kinds: table, tree, markdown, key-value, tabs, graph, split, form, html — native Java/SWT kinds including graph preferred; html is optional WebView2) plus optional top-level live binding ({ events, onEvent, pushToHtml, debounceMs })" }
          }
        },
        "view-close": {
          "description": "Close and remove a moddable view by logical viewId",
          "record": "ViewClose",
          "params": {
            "viewId": { "type": "string", "description": "Logical view instance ID" }
          }
        },
        "view-list": {
          "description": "List registered moddable views and whether their Eclipse ViewPart is open",
          "record": "ViewList",
          "params": {}
        },
        "inspect-frame": {
          "description": "Inspect a suspended debug stack frame with registered moldable inspector tabs",
          "record": "InspectFrame",
          "params": {
            "frameId": { "type": "long", "description": "Unique ID of the suspended stack frame" },
            "openView": { "type": "boolean", "optional": true, "default": true, "description": "Open or update the moldable inspector view in the workbench" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        },
        "inspect-value": {
          "description": "Evaluate and inspect a Java expression in a suspended stack frame with moldable inspector tabs",
          "record": "InspectValue",
          "params": {
            "frameId": { "type": "long", "description": "Unique ID of the suspended stack frame" },
            "expression": { "type": "string", "description": "Java expression to evaluate and inspect" },
            "depth": { "type": "integer", "optional": true, "default": 2, "description": "Object graph expansion depth (>= 1)" },
            "openView": { "type": "boolean", "optional": true, "default": true, "description": "Open or update the moldable inspector view in the workbench" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        },
        "inspect-symbol": {
          "description": "Inspect the Java symbol at <file>:<offset> with moldable JDT inspector tabs",
          "record": "InspectSymbol",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" },
            "openView": { "type": "boolean", "optional": true, "default": true, "description": "Open or update the moldable inspector view in the workbench" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        },
        "inspect-providers": {
          "description": "List registered moldable object inspector providers and their capabilities",
          "record": "ListInspectProviders",
          "params": {}
        },
        "call-explorer": {
          "description": "Open a moldable call-graph explorer view for the Java method at <file>:<offset>",
          "record": "OpenCallExplorer",
          "params": {
            "file": { "type": "string", "description": "Workspace-relative or absolute Java file path" },
            "offset": { "type": "integer", "description": "0-based character offset inside the file" },
            "direction": { "type": "string", "optional": true, "enum": ["callers", "callees", "both"], "default": "callers", "description": "Traversal direction" },
            "depth": { "type": "integer", "optional": true, "default": 3, "description": "Traversal depth (>= 1)" },
            "hideJdk": { "type": "boolean", "optional": true, "default": true, "description": "Hide JDK/standard library methods" },
            "viewMode": { "type": "string", "optional": true, "enum": ["tree", "hybrid"], "default": "hybrid", "description": "Explorer presentation mode" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        },
        "trace-view": {
          "description": "Open a moldable live execution trace view over recorded logpoint events",
          "record": "OpenTraceView",
          "params": {
            "limit": { "type": "integer", "optional": true, "default": 200, "description": "Maximum number of recent trace entries (>= 1)" },
            "live": { "type": "boolean", "optional": true, "default": true, "description": "Automatically refresh on LogpointHit events" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        },
        "junit-view": {
          "description": "Open a moldable live JUnit test runner view for a test run",
          "record": "OpenJUnitView",
          "params": {
            "runId": { "type": "string", "optional": true, "description": "Test run ID (defaults to most recent run)" },
            "failuresOnly": { "type": "boolean", "optional": true, "default": false, "description": "Show only failed or errored tests" },
            "live": { "type": "boolean", "optional": true, "default": true, "description": "Automatically refresh on TestRunFinished events" },
            "viewId": { "type": "string", "optional": true, "description": "Optional moddable view ID override" }
          }
        }
      },
      "responses": {
        "Success": { "fields": ["result (object)"] },
        "Error": { "fields": ["message (string)", "trace (string/null)"] },
        "Events": {
          "BreakpointHit": { "fields": ["path", "line", "threadId"] },
          "LogpointHit": { "fields": ["seq", "timestampMs", "resource", "file", "class", "method", "line", "threadId", "threadName", "template", "message", "evaluations", "variables"] },
          "StepCompleted": { "fields": ["threadId"] },
          "ThreadSuspended": { "fields": ["threadId", "reason"] },
          "ThreadResumed": { "fields": ["threadId"] },
          "ThreadTerminated": { "fields": ["threadId"] },
          "TestRunFinished": { "fields": ["runId", "name", "project", "status", "total", "passed", "failed", "errors", "ignored", "elapsedSeconds"] },
          "ViewAction": { "fields": ["viewId", "action", "itemId", "payload", "timestampMs"] }
        }
      }
    }
    """;
}
