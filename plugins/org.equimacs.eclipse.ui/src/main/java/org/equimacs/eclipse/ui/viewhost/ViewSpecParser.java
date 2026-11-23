package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses JSON view specification payloads into immutable {@link ViewSpec} ADTs
 * and optional {@link LiveBinding} refresh descriptors.
 */
public final class ViewSpecParser {

    public static final int MAX_LAYOUT_DEPTH = 6;
    private static final int MAX_TREE_DEPTH = 64;
    private static final Set<String> VALID_FORM_FIELD_TYPES =
        Set.of("text", "textarea", "checkbox", "select", "number");
    private static final Set<String> VALID_INSPECT_TARGET_KINDS =
        Set.of("jdi-frame", "jdi-value", "jdt-symbol");

    /**
     * Parsed top-level view envelope containing the {@link ViewSpec} and optional {@link LiveBinding}.
     */
    public record ParsedView(ViewSpec spec, LiveBinding live) {}

    private ViewSpecParser() {}

    /**
     * Parses a JSON string into a validated {@link ViewSpec}.
     */
    public static ViewSpec parse(String specJson) {
        return parseEnvelope(specJson).spec();
    }

    /**
     * Parses a JSON string into a validated {@link ParsedView} containing both the {@link ViewSpec}
     * and optional top-level {@link LiveBinding}.
     */
    public static ParsedView parseEnvelope(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("specJson must not be blank");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(specJson);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid view spec JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("View spec must be a JSON object");
        }
        JsonObject rootObj = root.getAsJsonObject();
        ViewSpec spec = parseObject(rootObj, 1);
        LiveBinding live = parseLiveBinding(rootObj.get("live"));
        return new ParsedView(spec, live);
    }

    private static ViewSpec parseObject(JsonObject obj, int layoutDepth) {
        if (layoutDepth > MAX_LAYOUT_DEPTH) {
            throw new IllegalArgumentException(
                "Nested layout exceeds maximum depth of " + MAX_LAYOUT_DEPTH);
        }
        String rawKind = optString(obj, "kind", null);
        if (rawKind == null || rawKind.isBlank()) {
            throw new IllegalArgumentException("View spec requires a non-blank 'kind'");
        }
        String kind = rawKind.trim().toLowerCase(Locale.ROOT);
        String title = optString(obj, "title", "");
        List<ViewSpec.ActionItem> toolbar = parseToolbar(obj.get("toolbar"));

        return switch (kind) {
            case "table" -> parseTable(obj, title, toolbar);
            case "tree" -> parseTree(obj, title, toolbar);
            case "markdown" -> parseMarkdown(obj, title, toolbar);
            case "key-value" -> parseKeyValue(obj, title, toolbar);
            case "tabs" -> parseTabs(obj, title, toolbar, layoutDepth);
            case "html" -> parseHtml(obj, title, toolbar);
            case "split" -> parseSplit(obj, title, toolbar, layoutDepth);
            case "form" -> parseForm(obj, title, toolbar);
            case "graph" -> parseGraph(obj, title, toolbar);
            default -> throw new IllegalArgumentException(
                "Unknown view kind '" + kind
                + "'; supported kinds: table, tree, markdown, key-value, tabs, html, split, form, graph");
        };
    }

    private static LiveBinding parseLiveBinding(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("Field 'live' must be a JSON object");
        }
        JsonObject liveObj = el.getAsJsonObject();
        List<String> rawEvents = parseStringArray(liveObj.get("events"), "live.events");
        List<String> events = new ArrayList<>(rawEvents.size());
        for (String event : rawEvents) {
            if (event != null && !event.isBlank()) {
                events.add(event.trim());
            }
        }
        if (events.isEmpty()) {
            return null;
        }
        ViewAction onEvent = parseOptionalAction(liveObj.get("onEvent"));
        boolean pushToHtml = optBoolean(liveObj, "pushToHtml", false);
        int debounceMs = optInt(liveObj, "debounceMs", LiveBinding.DEFAULT_DEBOUNCE_MS);
        return new LiveBinding(events, onEvent, pushToHtml, debounceMs);
    }

    private static ViewSpec.TableSpec parseTable(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        List<String> columns = parseStringArray(obj.get("columns"), "columns");
        List<ViewSpec.TableRow> rows = new ArrayList<>();
        JsonElement rowsEl = obj.get("rows");
        if (rowsEl != null && !rowsEl.isJsonNull()) {
            if (!rowsEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'rows' must be a JSON array");
            }
            JsonArray arr = rowsEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) {
                    throw new IllegalArgumentException("Table row at index " + i + " must be a JSON object");
                }
                JsonObject rowObj = el.getAsJsonObject();
                String id = optString(rowObj, "id", "");
                List<String> cells = parseStringArray(rowObj.get("cells"), "cells");
                ViewAction onSelect = parseOptionalAction(rowObj.get("onSelect"));
                ViewAction onActivate = parseOptionalAction(rowObj.get("onActivate"));
                rows.add(new ViewSpec.TableRow(id, cells, onSelect, onActivate));
            }
        }
        return new ViewSpec.TableSpec(title, columns, rows, toolbar);
    }

    private static ViewSpec.TreeSpec parseTree(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        List<String> columns = parseStringArray(obj.get("columns"), "columns");
        boolean expandAll = optBoolean(obj, "expandAll", false);
        JsonElement rootsEl = obj.has("roots") ? obj.get("roots") : obj.get("nodes");
        List<ViewSpec.TreeNode> roots = parseTreeNodes(rootsEl, 0);
        return new ViewSpec.TreeSpec(title, columns, roots, expandAll, toolbar);
    }

    private static List<ViewSpec.TreeNode> parseTreeNodes(JsonElement el, int depth) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (depth > MAX_TREE_DEPTH) {
            throw new IllegalArgumentException("Tree nesting exceeds maximum depth of " + MAX_TREE_DEPTH);
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("Tree 'roots'/'children' must be a JSON array");
        }
        JsonArray arr = el.getAsJsonArray();
        List<ViewSpec.TreeNode> nodes = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("Tree node at index " + i + " must be a JSON object");
            }
            JsonObject nodeObj = item.getAsJsonObject();
            String id = optString(nodeObj, "id", "");
            String label = optString(nodeObj, "label", "");
            List<String> values = parseStringArray(nodeObj.get("values"), "values");
            String badge = optString(nodeObj, "badge", null);
            ViewAction onActivate = parseOptionalAction(nodeObj.get("onActivate"));
            List<ViewSpec.TreeNode> children = parseTreeNodes(nodeObj.get("children"), depth + 1);
            nodes.add(new ViewSpec.TreeNode(id, label, values, badge, onActivate, children));
        }
        return nodes;
    }

    private static ViewSpec.MarkdownSpec parseMarkdown(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        String content = optString(obj, "content", "");
        return new ViewSpec.MarkdownSpec(title, content, toolbar);
    }

    private static ViewSpec.KeyValueSpec parseKeyValue(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        List<ViewSpec.KvSection> sections = new ArrayList<>();
        JsonElement sectionsEl = obj.get("sections");
        if (sectionsEl != null && !sectionsEl.isJsonNull()) {
            if (!sectionsEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'sections' must be a JSON array");
            }
            JsonArray arr = sectionsEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) {
                    throw new IllegalArgumentException("Key-value section at index " + i + " must be a JSON object");
                }
                JsonObject secObj = el.getAsJsonObject();
                String heading = optString(secObj, "heading", "");
                List<ViewSpec.KvEntry> entries = parseKvEntries(secObj.get("entries"));
                sections.add(new ViewSpec.KvSection(heading, entries));
            }
        } else if (obj.has("entries") && !obj.get("entries").isJsonNull()) {
            List<ViewSpec.KvEntry> entries = parseKvEntries(obj.get("entries"));
            sections.add(new ViewSpec.KvSection("", entries));
        }
        return new ViewSpec.KeyValueSpec(title, sections, toolbar);
    }

    private static List<ViewSpec.KvEntry> parseKvEntries(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("Field 'entries' must be a JSON array");
        }
        JsonArray arr = el.getAsJsonArray();
        List<ViewSpec.KvEntry> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("Key-value entry at index " + i + " must be a JSON object");
            }
            JsonObject entryObj = item.getAsJsonObject();
            String key = optString(entryObj, "key", "");
            String value = optString(entryObj, "value", "");
            ViewAction onActivate = parseOptionalAction(entryObj.get("onActivate"));
            entries.add(new ViewSpec.KvEntry(key, value, onActivate));
        }
        return entries;
    }

    private static ViewSpec.TabsSpec parseTabs(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar,
            int layoutDepth) {
        List<ViewSpec.TabItemSpec> tabs = new ArrayList<>();
        JsonElement tabsEl = obj.get("tabs");
        if (tabsEl != null && !tabsEl.isJsonNull()) {
            if (!tabsEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'tabs' must be a JSON array");
            }
            JsonArray arr = tabsEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) {
                    throw new IllegalArgumentException("Tab item at index " + i + " must be a JSON object");
                }
                JsonObject tabObj = el.getAsJsonObject();
                String label = requireNonBlankString(tabObj, "label", "Tab item label");
                JsonElement contentEl = tabObj.get("content");
                if (contentEl == null || !contentEl.isJsonObject()) {
                    throw new IllegalArgumentException("Tab item '" + label + "' requires a JSON object 'content'");
                }
                ViewSpec content = parseObject(contentEl.getAsJsonObject(), layoutDepth + 1);
                tabs.add(new ViewSpec.TabItemSpec(label, content));
            }
        }
        return new ViewSpec.TabsSpec(title, tabs, toolbar);
    }

    private static ViewSpec.HtmlSpec parseHtml(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        String rawHtml = optString(obj, "html", null);
        String rawContent = optString(obj, "content", null);
        String html;
        if (rawHtml != null && !rawHtml.isBlank()) {
            html = rawHtml;
        } else if (rawContent != null) {
            html = rawContent;
        } else {
            html = rawHtml != null ? rawHtml : "";
        }
        String url = optString(obj, "url", "");
        if (html.isBlank() && url.isBlank()) {
            throw new IllegalArgumentException("HTML view spec requires non-blank 'html'/'content' or 'url'");
        }
        Map<String, Object> state = parseJsonObjectMap(obj.get("state"), "state");
        return new ViewSpec.HtmlSpec(title, html, url, state, toolbar);
    }

    private static ViewSpec.SplitSpec parseSplit(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar,
            int layoutDepth) {
        String rawOrientation = optString(obj, "orientation", "horizontal");
        String orientation = (rawOrientation == null || rawOrientation.isBlank())
            ? "horizontal"
            : rawOrientation.trim().toLowerCase(Locale.ROOT);
        if (!"horizontal".equals(orientation) && !"vertical".equals(orientation)) {
            throw new IllegalArgumentException(
                "Split 'orientation' must be 'horizontal' or 'vertical', got: " + rawOrientation);
        }

        JsonElement panesEl = obj.get("panes");
        if (panesEl == null || !panesEl.isJsonArray()) {
            throw new IllegalArgumentException("Split view requires a 'panes' JSON array");
        }
        JsonArray panesArr = panesEl.getAsJsonArray();
        if (panesArr.isEmpty()) {
            throw new IllegalArgumentException("Split view 'panes' must contain at least 1 pane");
        }
        List<ViewSpec> panes = new ArrayList<>(panesArr.size());
        for (int i = 0; i < panesArr.size(); i++) {
            JsonElement paneEl = panesArr.get(i);
            if (!paneEl.isJsonObject()) {
                throw new IllegalArgumentException("Split pane at index " + i + " must be a JSON object");
            }
            panes.add(parseObject(paneEl.getAsJsonObject(), layoutDepth + 1));
        }

        List<Integer> weights = parsePositiveIntArray(obj.get("weights"), "weights");
        if (!weights.isEmpty() && weights.size() != panes.size()) {
            throw new IllegalArgumentException(
                "Split 'weights' length (" + weights.size() + ") must match 'panes' length (" + panes.size() + ")");
        }
        return new ViewSpec.SplitSpec(title, orientation, weights, panes, toolbar);
    }

    private static ViewSpec.FormSpec parseForm(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        String description = optString(obj, "description", "");
        String submitLabel = optString(obj, "submitLabel", "Submit");

        List<ViewSpec.FormField> fields = new ArrayList<>();
        JsonElement fieldsEl = obj.get("fields");
        if (fieldsEl != null && !fieldsEl.isJsonNull()) {
            if (!fieldsEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'fields' must be a JSON array");
            }
            JsonArray arr = fieldsEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                if (!item.isJsonObject()) {
                    throw new IllegalArgumentException("Form field at index " + i + " must be a JSON object");
                }
                JsonObject fieldObj = item.getAsJsonObject();
                String name = requireNonBlankString(fieldObj, "name", "Form field name");
                String label = optString(fieldObj, "label", "");
                if (label.isBlank()) {
                    label = name;
                }
                JsonElement typeEl = fieldObj.get("type");
                String fieldType = "text";
                if (typeEl != null && !typeEl.isJsonNull()) {
                    String rawType = optString(fieldObj, "type", null);
                    if (rawType == null || rawType.isBlank()) {
                        throw new IllegalArgumentException(
                            "Form field '" + name + "' type must be one of: text, textarea, checkbox, select, number");
                    }
                    fieldType = rawType.trim().toLowerCase(Locale.ROOT);
                    if (!VALID_FORM_FIELD_TYPES.contains(fieldType)) {
                        throw new IllegalArgumentException(
                            "Invalid form field type '" + rawType
                            + "'; supported types: text, textarea, checkbox, select, number");
                    }
                }
                String defaultValue = optString(fieldObj, "defaultValue", "");
                String placeholder = optString(fieldObj, "placeholder", "");
                List<String> options = parseStringArray(fieldObj.get("options"), "options");
                boolean required = optBoolean(fieldObj, "required", false);
                fields.add(new ViewSpec.FormField(name, label, fieldType, defaultValue, placeholder, options, required));
            }
        }

        JsonElement onSubmitEl = obj.get("onSubmit");
        if (onSubmitEl == null || onSubmitEl.isJsonNull()) {
            throw new IllegalArgumentException("Form view requires an 'onSubmit' action");
        }
        ViewAction onSubmit = parseAction(onSubmitEl);
        return new ViewSpec.FormSpec(title, description, fields, submitLabel, onSubmit, toolbar);
    }

    private static ViewSpec.GraphSpec parseGraph(
            JsonObject obj,
            String title,
            List<ViewSpec.ActionItem> toolbar) {
        String rawLayout = optString(obj, "layout", "focus");
        String layout = (rawLayout == null || rawLayout.isBlank())
            ? "focus"
            : rawLayout.trim().toLowerCase(Locale.ROOT);
        if (!"focus".equals(layout) && !"layered".equals(layout)) {
            throw new IllegalArgumentException(
                "Graph 'layout' must be 'focus' or 'layered', got: " + rawLayout);
        }

        List<ViewSpec.GraphNode> nodes = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();
        JsonElement nodesEl = obj.get("nodes");
        if (nodesEl != null && !nodesEl.isJsonNull()) {
            if (!nodesEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'nodes' must be a JSON array");
            }
            JsonArray arr = nodesEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                if (!item.isJsonObject()) {
                    throw new IllegalArgumentException("Graph node at index " + i + " must be a JSON object");
                }
                JsonObject nodeObj = item.getAsJsonObject();
                String id = requireNonBlankString(nodeObj, "id", "Graph node id");
                if (!seenNodeIds.add(id)) {
                    throw new IllegalArgumentException("Duplicate graph node id: " + id);
                }
                String label = optString(nodeObj, "label", id);
                String sublabel = optString(nodeObj, "sublabel", "");
                String group = optString(nodeObj, "group", "center");
                int layer = optInt(nodeObj, "layer", 0);
                String badge = optString(nodeObj, "badge", "");
                ViewAction onSelect = parseOptionalAction(nodeObj.get("onSelect"));
                ViewAction onActivate = parseOptionalAction(nodeObj.get("onActivate"));
                ViewAction onSecondary = parseOptionalAction(nodeObj.get("onSecondary"));
                String secondaryLabel = optString(nodeObj, "secondaryLabel", "");
                nodes.add(new ViewSpec.GraphNode(
                    id, label, sublabel, group, layer, badge, onSelect, onActivate, onSecondary, secondaryLabel));
            }
        }

        List<ViewSpec.GraphEdge> edges = new ArrayList<>();
        JsonElement edgesEl = obj.get("edges");
        if (edgesEl != null && !edgesEl.isJsonNull()) {
            if (!edgesEl.isJsonArray()) {
                throw new IllegalArgumentException("Field 'edges' must be a JSON array");
            }
            JsonArray arr = edgesEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                if (!item.isJsonObject()) {
                    throw new IllegalArgumentException("Graph edge at index " + i + " must be a JSON object");
                }
                JsonObject edgeObj = item.getAsJsonObject();
                String from = requireNonBlankString(edgeObj, "from", "Graph edge 'from'");
                String to = requireNonBlankString(edgeObj, "to", "Graph edge 'to'");
                String label = optString(edgeObj, "label", "");
                String style = optString(edgeObj, "style", "solid");
                edges.add(new ViewSpec.GraphEdge(from, to, label, style));
            }
        }

        return new ViewSpec.GraphSpec(title, layout, nodes, edges, toolbar);
    }

    private static List<ViewSpec.ActionItem> parseToolbar(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("Field 'toolbar' must be a JSON array");
        }
        JsonArray arr = el.getAsJsonArray();
        List<ViewSpec.ActionItem> items = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("Toolbar item at index " + i + " must be a JSON object");
            }
            JsonObject itemObj = item.getAsJsonObject();
            String label = requireNonBlankString(itemObj, "label", "Toolbar item label");
            String tooltip = optString(itemObj, "tooltip", null);
            JsonElement actionEl = itemObj.get("action");
            if (actionEl == null || actionEl.isJsonNull()) {
                throw new IllegalArgumentException("Toolbar item '" + label + "' requires an 'action'");
            }
            ViewAction action = parseAction(actionEl);
            items.add(new ViewSpec.ActionItem(label, tooltip, action));
        }
        return items;
    }

    private static ViewAction parseOptionalAction(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return parseAction(el);
    }

    static ViewAction parseAction(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            throw new IllegalArgumentException("View action must be a JSON object");
        }
        JsonObject obj = el.getAsJsonObject();
        String rawType = requireNonBlankString(obj, "type", "Action type");
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "open-file" -> {
                String file = requireNonBlankString(obj, "file", "open-file.file");
                int line = optInt(obj, "line", -1);
                int endLine = optInt(obj, "endLine", line);
                yield new ViewAction.OpenFile(file, line, endLine);
            }
            case "eclipse-command" -> {
                String commandId = requireNonBlankString(obj, "commandId", "eclipse-command.commandId");
                String file = optString(obj, "file", null);
                int offset = optInt(obj, "offset", -1);
                Map<String, String> params = parseStringMap(obj.get("params"), "params");
                yield new ViewAction.EclipseCommand(commandId, file, offset, params);
            }
            case "show-view" -> {
                String viewId = requireNonBlankString(obj, "viewId", "show-view.viewId");
                String secondaryId = optString(obj, "secondaryId", null);
                yield new ViewAction.ShowView(viewId, secondaryId);
            }
            case "custom" -> {
                String target = requireNonBlankString(obj, "target", "custom.target");
                String action = requireNonBlankString(obj, "action", "custom.action");
                String args = elementToOptionalString(obj.get("args"));
                yield new ViewAction.Custom(target, action, args);
            }
            case "emit" -> {
                String name = requireNonBlankString(obj, "name", "emit.name");
                String payload = elementToOptionalString(obj.get("payload"));
                yield new ViewAction.Emit(name, payload);
            }
            case "inspect" -> {
                String rawTargetKind = requireNonBlankString(obj, "targetKind", "inspect.targetKind");
                String targetKind = rawTargetKind.toLowerCase(Locale.ROOT);
                if (!VALID_INSPECT_TARGET_KINDS.contains(targetKind)) {
                    throw new IllegalArgumentException(
                        "inspect.targetKind must be 'jdi-frame', 'jdi-value', or 'jdt-symbol', got: "
                            + rawTargetKind);
                }
                Long frameId = optLong(obj, "frameId", null);
                String expression = optString(obj, "expression", null);
                String file = optString(obj, "file", null);
                int offset = optInt(obj, "offset", -1);
                String viewId = optString(obj, "viewId", null);
                yield new ViewAction.Inspect(targetKind, frameId, expression, file, offset, viewId);
            }
            case "toolkit-view" -> {
                String tool = requireNonBlankString(obj, "tool", "toolkit-view.tool")
                    .toLowerCase(Locale.ROOT);
                Map<String, String> params = parseStringMap(obj.get("params"), "params");
                yield new ViewAction.ToolkitView(tool, params);
            }
            default -> throw new IllegalArgumentException(
                "Unknown action type '" + type
                + "'; supported types: open-file, eclipse-command, show-view, custom, emit, inspect, toolkit-view");
        };
    }

    private static List<String> parseStringArray(JsonElement el, String fieldName) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a JSON array");
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            result.add(elementToStringOrEmpty(arr.get(i)));
        }
        return result;
    }

    private static List<Integer> parsePositiveIntArray(JsonElement el, String fieldName) {
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a JSON array");
        }
        JsonArray arr = el.getAsJsonArray();
        List<Integer> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(
                    "Field '" + fieldName + "' element at index " + i + " must be a positive integer");
            }
            double val = item.getAsDouble();
            if (val != Math.floor(val) || val <= 0 || val > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "Field '" + fieldName + "' element at index " + i + " must be a positive integer");
            }
            result.add((int) val);
        }
        return result;
    }

    private static Map<String, String> parseStringMap(JsonElement el, String fieldName) {
        if (el == null || el.isJsonNull()) {
            return Map.of();
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a JSON object");
        }
        JsonObject obj = el.getAsJsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val != null && !val.isJsonNull()) {
                result.put(entry.getKey(), elementToStringOrEmpty(val));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> parseJsonObjectMap(JsonElement el, String fieldName) {
        if (el == null || el.isJsonNull()) {
            return Map.of();
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a JSON object");
        }
        return toJavaMap(el.getAsJsonObject());
    }

    private static Map<String, Object> toJavaMap(JsonObject obj) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            result.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object toJavaValue(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            return toJavaMap(el.getAsJsonObject());
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                list.add(toJavaValue(arr.get(i)));
            }
            return Collections.unmodifiableList(list);
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) {
                return prim.getAsBoolean();
            }
            if (prim.isNumber()) {
                return parseJsonNumber(prim);
            }
            return prim.getAsString();
        }
        return el.toString();
    }

    private static Number parseJsonNumber(JsonPrimitive prim) {
        String raw = prim.getAsString();
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
            return prim.getAsDouble();
        }
        try {
            long val = Long.parseLong(raw);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) val);
            }
            return Long.valueOf(val);
        } catch (NumberFormatException ignored) {
            return prim.getAsDouble();
        }
    }

    private static String requireNonBlankString(JsonObject obj, String field, String label) {
        String val = optString(obj, field, null);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-blank string");
        }
        return val.trim();
    }

    private static String optString(JsonObject obj, String field, String defaultValue) {
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        throw new IllegalArgumentException("Field '" + field + "' must be a string or primitive");
    }

    private static int optInt(JsonObject obj, String field, int defaultValue) {
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isNumber()) {
                return prim.getAsInt();
            }
        }
        throw new IllegalArgumentException("Field '" + field + "' must be an integer");
    }

    private static Long optLong(JsonObject obj, String field, Long defaultValue) {
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isNumber()) {
                return prim.getAsLong();
            }
        }
        throw new IllegalArgumentException("Field '" + field + "' must be a long integer");
    }

    private static boolean optBoolean(JsonObject obj, String field, boolean defaultValue) {
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) {
            return defaultValue;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        throw new IllegalArgumentException("Field '" + field + "' must be a boolean");
    }

    private static String elementToStringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return el.toString();
    }

    private static String elementToOptionalString(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return el.toString();
    }
}
