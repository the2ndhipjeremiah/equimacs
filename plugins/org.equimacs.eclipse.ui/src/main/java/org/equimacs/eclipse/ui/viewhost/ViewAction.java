package org.equimacs.eclipse.ui.viewhost;

import java.util.Map;

/**
 * Interactive action triggered by a moddable view toolbar button, item selection,
 * item activation, or markdown link.
 */
public sealed interface ViewAction {

    /**
     * Opens a workspace or filesystem file in an Eclipse editor, optionally selecting lines.
     */
    record OpenFile(String file, int line, int endLine) implements ViewAction {}

    /**
     * Executes an Eclipse workbench command, optionally navigating to a file/offset first.
     */
    record EclipseCommand(String commandId, String file, int offset, Map<String, String> params) implements ViewAction {
        public EclipseCommand {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * Activates an Eclipse view by view ID and optional secondary ID.
     */
    record ShowView(String viewId, String secondaryId) implements ViewAction {}

    /**
     * Dispatches a custom bridge extension command.
     */
    record Custom(String target, String action, String args) implements ViewAction {}

    /**
     * Publishes an event onto the Equimacs bridge event stream for agents/CLI listeners.
     */
    record Emit(String name, String payload) implements ViewAction {}

    /**
     * Opens the Moldable Inspector on a JDI frame, JDI expression value, or JDT symbol.
     */
    record Inspect(
        String targetKind,
        Long frameId,
        String expression,
        String file,
        int offset,
        String viewId
    ) implements ViewAction {}

    /**
     * Triggers or refreshes a built-in Moldable Toolkit view (e.g. "call-explorer", "trace", "junit",
     * "trace-clear", "junit-rerun").
     */
    record ToolkitView(String tool, Map<String, String> params) implements ViewAction {
        public ToolkitView {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
