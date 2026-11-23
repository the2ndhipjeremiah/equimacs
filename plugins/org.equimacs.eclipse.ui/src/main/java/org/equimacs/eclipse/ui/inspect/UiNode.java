package org.equimacs.eclipse.ui.inspect;

import java.util.List;

/**
 * Pure immutable data records representing inspected SWT widget trees,
 * Eclipse workbench part summaries, and widget selector queries.
 */
public record UiNode(
    String id,
    String path,
    String type,
    String text,
    String tooltip,
    String message,
    String elementId,
    boolean visible,
    boolean enabled,
    boolean focused,
    Boolean selected,
    UiBounds bounds,
    List<String> items,
    List<UiNode> children
) {
    public UiNode {
        items = items == null ? List.of() : List.copyOf(items);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public record UiBounds(int x, int y, int width, int height) {}

    public record WorkbenchPartSummary(
        String kind,
        String id,
        String secondaryId,
        String title,
        boolean active,
        boolean visible,
        boolean dirty
    ) {}

    public record UiTreeSnapshot(
        String root,
        String perspectiveId,
        String activeShellTitle,
        List<WorkbenchPartSummary> views,
        List<WorkbenchPartSummary> editors,
        List<UiNode> roots,
        int totalNodesVisited,
        boolean truncated
    ) {
        public UiTreeSnapshot {
            views = views == null ? List.of() : List.copyOf(views);
            editors = editors == null ? List.of() : List.copyOf(editors);
            roots = roots == null ? List.of() : List.copyOf(roots);
        }
    }

    public record UiSelector(
        String widgetId,
        String root,
        String widgetType,
        String text,
        int index
    ) {}
}
