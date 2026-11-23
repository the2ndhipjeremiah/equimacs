package org.equimacs.eclipse.ui.viewhost;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Declarative specification for a moddable native Eclipse view.
 */
public sealed interface ViewSpec {

    String title();

    List<ActionItem> toolbar();

    String kind();

    int itemCount();

    /**
     * Toolbar button descriptor.
     */
    record ActionItem(String label, String tooltip, ViewAction action) {}

    /**
     * Row inside a {@link TableSpec}.
     */
    record TableRow(String id, List<String> cells, ViewAction onSelect, ViewAction onActivate) {
        public TableRow {
            cells = safeList(cells);
        }
    }

    /**
     * Filterable, sortable table specification.
     */
    record TableSpec(String title, List<String> columns, List<TableRow> rows, List<ActionItem> toolbar)
            implements ViewSpec {
        public TableSpec {
            columns = safeList(columns);
            rows = safeList(rows);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "table";
        }

        @Override
        public int itemCount() {
            return rows.size();
        }
    }

    /**
     * Node inside a {@link TreeSpec}.
     */
    record TreeNode(
            String id,
            String label,
            List<String> values,
            String badge,
            ViewAction onActivate,
            List<TreeNode> children) {
        public TreeNode {
            values = safeList(values);
            children = safeList(children);
        }
    }

    /**
     * Hierarchical single- or multi-column tree specification.
     */
    record TreeSpec(
            String title,
            List<String> columns,
            List<TreeNode> roots,
            boolean expandAll,
            List<ActionItem> toolbar) implements ViewSpec {
        public TreeSpec {
            columns = safeList(columns);
            roots = safeList(roots);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "tree";
        }

        @Override
        public int itemCount() {
            return countTreeNodes(roots);
        }
    }

    /**
     * Styled markdown specification.
     */
    record MarkdownSpec(String title, String content, List<ActionItem> toolbar) implements ViewSpec {
        public MarkdownSpec {
            content = content == null ? "" : content;
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "markdown";
        }

        @Override
        public int itemCount() {
            return content.isEmpty() ? 0 : (int) content.lines().count();
        }
    }

    /**
     * Key-value row inside a {@link KvSection}.
     */
    record KvEntry(String key, String value, ViewAction onActivate) {}

    /**
     * Named group of key-value entries.
     */
    record KvSection(String heading, List<KvEntry> entries) {
        public KvSection {
            entries = safeList(entries);
        }
    }

    /**
     * Two-column grouped key-value inspector specification.
     */
    record KeyValueSpec(String title, List<KvSection> sections, List<ActionItem> toolbar) implements ViewSpec {
        public KeyValueSpec {
            sections = safeList(sections);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "key-value";
        }

        @Override
        public int itemCount() {
            int count = 0;
            for (KvSection section : sections) {
                count += section.entries().size();
            }
            return count;
        }
    }

    /**
     * Single tab entry inside a {@link TabsSpec}.
     */
    record TabItemSpec(String label, ViewSpec content) {}

    /**
     * Tabbed container specification holding nested {@link ViewSpec} panes.
     */
    record TabsSpec(String title, List<TabItemSpec> tabs, List<ActionItem> toolbar) implements ViewSpec {
        public TabsSpec {
            tabs = safeList(tabs);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "tabs";
        }

        @Override
        public int itemCount() {
            return tabs.size();
        }
    }

    /**
     * WebView2 / SWT Browser HTML view specification with optional state payload.
     */
    record HtmlSpec(
            String title,
            String html,
            String url,
            Map<String, Object> state,
            List<ActionItem> toolbar) implements ViewSpec {
        public HtmlSpec {
            html = html == null ? "" : html;
            url = url == null ? "" : url;
            state = safeMap(state);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "html";
        }

        @Override
        public int itemCount() {
            return 1;
        }
    }

    /**
     * Weighted horizontal or vertical split container holding nested {@link ViewSpec} panes.
     */
    record SplitSpec(
            String title,
            String orientation,
            List<Integer> weights,
            List<ViewSpec> panes,
            List<ActionItem> toolbar) implements ViewSpec {
        public SplitSpec {
            String normalizedOrientation = orientation == null || orientation.isBlank()
                ? "horizontal"
                : orientation.trim().toLowerCase(Locale.ROOT);
            orientation = normalizedOrientation;
            weights = safeList(weights);
            panes = safeList(panes);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "split";
        }

        @Override
        public int itemCount() {
            return panes.size();
        }
    }

    /**
     * Single interactive field inside a {@link FormSpec}.
     */
    record FormField(
            String name,
            String label,
            String fieldType,
            String defaultValue,
            String placeholder,
            List<String> options,
            boolean required) {
        public FormField {
            name = name == null ? "" : name;
            label = label == null ? "" : label;
            fieldType = (fieldType == null || fieldType.isBlank())
                ? "text"
                : fieldType.trim().toLowerCase(Locale.ROOT);
            defaultValue = defaultValue == null ? "" : defaultValue;
            placeholder = placeholder == null ? "" : placeholder;
            options = safeList(options);
        }
    }

    /**
     * Interactive native SWT form specification that validates inputs and merges them into {@code onSubmit}.
     */
    record FormSpec(
            String title,
            String description,
            List<FormField> fields,
            String submitLabel,
            ViewAction onSubmit,
            List<ActionItem> toolbar) implements ViewSpec {
        public FormSpec {
            description = description == null ? "" : description;
            fields = safeList(fields);
            submitLabel = (submitLabel == null || submitLabel.isBlank()) ? "Submit" : submitLabel.trim();
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "form";
        }

        @Override
        public int itemCount() {
            return fields.size();
        }
    }

    /**
     * Node inside a native SWT {@link GraphSpec}.
     */
    record GraphNode(
            String id,
            String label,
            String sublabel,
            String group,
            int layer,
            String badge,
            ViewAction onSelect,
            ViewAction onActivate,
            ViewAction onSecondary,
            String secondaryLabel) {
        public GraphNode {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            sublabel = sublabel == null ? "" : sublabel;
            group = (group == null || group.isBlank()) ? "center" : group.trim().toLowerCase(Locale.ROOT);
            badge = badge == null ? "" : badge;
            secondaryLabel = secondaryLabel == null ? "" : secondaryLabel;
        }
    }

    /**
     * Directed edge inside a native SWT {@link GraphSpec}.
     */
    record GraphEdge(String from, String to, String label, String style) {
        public GraphEdge {
            from = from == null ? "" : from;
            to = to == null ? "" : to;
            label = label == null ? "" : label;
            style = (style == null || style.isBlank()) ? "solid" : style.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Native Java/SWT Canvas graph specification (focus or layered DAG layout).
     */
    record GraphSpec(
            String title,
            String layout,
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            List<ActionItem> toolbar) implements ViewSpec {
        public GraphSpec {
            layout = (layout == null || layout.isBlank()) ? "focus" : layout.trim().toLowerCase(Locale.ROOT);
            nodes = safeList(nodes);
            edges = safeList(edges);
            toolbar = safeList(toolbar);
        }

        @Override
        public String kind() {
            return "graph";
        }

        @Override
        public int itemCount() {
            return nodes.size();
        }
    }

    private static <T> List<T> safeList(List<T> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private static Map<String, Object> safeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static int countTreeNodes(List<TreeNode> nodes) {
        int total = 0;
        for (TreeNode node : nodes) {
            total += 1 + countTreeNodes(node.children());
        }
        return total;
    }
}
