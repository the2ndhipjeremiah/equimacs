package org.equimacs.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.eclipse.bridge.api.InspectContext;
import org.equimacs.eclipse.bridge.api.MoldableTab;

/**
 * Built-in pure-Java {@link IMoldableViewProvider} implementations for JDI frames,
 * JDI runtime values, collections/maps, strings/JSON, and JDT symbols.
 */
public final class BuiltInInspectProviders {

    private static final int MAX_JSON_TREE_DEPTH = 32;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<IMoldableViewProvider> ALL = List.of(
        new FrameInspectorProvider(),
        new RawObjectViewProvider(),
        new CollectionViewProvider(),
        new StringAndJsonViewProvider(),
        new JdtSymbolViewProvider()
    );

    private BuiltInInspectProviders() {}

    /**
     * Returns all built-in moldable inspector providers in deterministic registration order.
     */
    public static List<IMoldableViewProvider> all() {
        return ALL;
    }

    // --- 1. Frame Inspector ---

    private static final class FrameInspectorProvider implements IMoldableViewProvider {
        @Override
        public String id() {
            return "equimacs.builtin.frame";
        }

        @Override
        public boolean supports(InspectContext context) {
            return context != null && "jdi-frame".equals(context.targetKind());
        }

        @Override
        public List<MoldableTab> provideViews(InspectContext context) {
            Map<String, Object> data = context.data();
            Long frameId = asLong(data.get("frameId"));
            String className = asString(data.get("className"));
            String method = asString(data.get("method"));
            String file = asString(data.get("file"));
            int line = asInt(data.get("line"), -1);
            List<Map<String, Object>> variables = asListOfMaps(data.get("variables"));

            JsonObject openFileAction = (!file.isBlank() && line > 0) ? openFileAction(file, line) : null;
            JsonObject fileOpenAction = !file.isBlank() ? openFileAction(file, line) : null;

            JsonArray overviewEntries = new JsonArray();
            overviewEntries.add(kvEntry("Class", className, null));
            overviewEntries.add(kvEntry("Method", method, null));
            overviewEntries.add(kvEntry("File", file, fileOpenAction));
            overviewEntries.add(kvEntry("Line", line > 0 ? Integer.toString(line) : "", openFileAction));
            overviewEntries.add(kvEntry("Frame ID", frameId != null ? Long.toString(frameId) : "", null));

            String overviewTitle = !context.title().isBlank() ? context.title() : "Frame Overview";
            JsonObject overviewSpec = keyValueSpec(overviewTitle, "Frame", overviewEntries);

            JsonArray varRows = new JsonArray();
            for (int i = 0; i < variables.size(); i++) {
                Map<String, Object> varMap = variables.get(i);
                String name = asString(varMap.get("name"));
                String type = asString(varMap.get("type"));
                String value = asString(varMap.get("value"));
                JsonObject inspectAction = (frameId != null && !name.isBlank())
                    ? inspectValueAction(frameId, name)
                    : null;
                varRows.add(tableRow("var-" + i + "-" + name, List.of(name, type, value), inspectAction));
            }
            JsonObject varsSpec = tableSpec("Variables", List.of("Name", "Type", "Value"), varRows, null);

            JsonObject exprField = new JsonObject();
            exprField.addProperty("name", "expression");
            exprField.addProperty("label", "Java Expression");
            exprField.addProperty("type", "text");
            exprField.addProperty("placeholder", "e.g. items.size()");
            exprField.addProperty("required", true);
            JsonArray fields = new JsonArray();
            fields.add(exprField);

            JsonObject onSubmit = new JsonObject();
            onSubmit.addProperty("type", "inspect");
            onSubmit.addProperty("targetKind", "jdi-value");
            if (frameId != null) {
                onSubmit.addProperty("frameId", frameId);
            }

            JsonObject evalSpec = new JsonObject();
            evalSpec.addProperty("kind", "form");
            evalSpec.addProperty("title", "Evaluate Expression");
            evalSpec.add("fields", fields);
            evalSpec.addProperty("submitLabel", "Inspect Expression");
            evalSpec.add("onSubmit", onSubmit);

            return List.of(
                new MoldableTab("overview", "Overview", 10, GSON.toJson(overviewSpec)),
                new MoldableTab("variables", "Variables", 20, GSON.toJson(varsSpec)),
                new MoldableTab("evaluate", "Evaluate", 30, GSON.toJson(evalSpec)));
        }
    }

    // --- 2. Raw Object Inspector ---

    private static final class RawObjectViewProvider implements IMoldableViewProvider {
        @Override
        public String id() {
            return "equimacs.builtin.raw-object";
        }

        @Override
        public boolean supports(InspectContext context) {
            return context != null && "jdi-value".equals(context.targetKind());
        }

        @Override
        public List<MoldableTab> provideViews(InspectContext context) {
            Map<String, Object> data = context.data();
            Long frameId = asLong(data.get("frameId"));
            String expression = asString(data.get("expression"));
            boolean isNull = asBoolean(data.get("isNull"));
            String valueString = isNull ? "null" : asString(data.get("valueString"));
            Long uniqueId = asLong(data.get("uniqueId"));
            boolean isArray = asBoolean(data.get("isArray"));
            Long arrayLength = asLong(data.get("arrayLength"));
            List<Map<String, Object>> fields = asListOfMaps(data.get("fields"));

            JsonArray entries = new JsonArray();
            entries.add(kvEntry("Expression", expression, null));
            entries.add(kvEntry("Type", context.typeName(), null));
            entries.add(kvEntry("Value", valueString, null));
            entries.add(kvEntry("Null", Boolean.toString(isNull), null));
            if (uniqueId != null) {
                entries.add(kvEntry("Object ID", Long.toString(uniqueId), null));
            }
            if (isArray || arrayLength != null) {
                entries.add(kvEntry("Array Length", arrayLength != null ? Long.toString(arrayLength) : "0", null));
            }

            String overviewTitle = !context.title().isBlank() ? context.title() : "Object Overview";
            JsonObject overviewSpec = keyValueSpec(overviewTitle, "Value", entries);

            List<MoldableTab> tabs = new ArrayList<>();
            tabs.add(new MoldableTab("overview", "Overview", 10, GSON.toJson(overviewSpec)));

            if (!fields.isEmpty()) {
                JsonArray roots = convertFieldNodes(fields, frameId, "root");
                JsonObject fieldsSpec = treeSpec(
                    "Fields", List.of("Field", "Type", "Value"), roots, true, null);
                tabs.add(new MoldableTab("fields", "Fields", 20, GSON.toJson(fieldsSpec)));
            }
            return List.copyOf(tabs);
        }

        private static JsonArray convertFieldNodes(
            List<Map<String, Object>> fields,
            Long frameId,
            String pathPrefix
        ) {
            JsonArray nodes = new JsonArray();
            for (int i = 0; i < fields.size(); i++) {
                Map<String, Object> field = fields.get(i);
                String name = asString(field.get("name"));
                String type = asString(field.get("type"));
                String value = asString(field.get("value"));
                String childExpr = asString(field.get("expression"));
                boolean cycle = asBoolean(field.get("cycle"));
                List<Map<String, Object>> nested = asListOfMaps(field.get("fields"));

                String nodeId = pathPrefix + "/" + i + ":" + name;
                JsonObject onActivate = (!cycle && frameId != null && !childExpr.isBlank())
                    ? inspectValueAction(frameId, childExpr)
                    : null;
                JsonArray children = (!cycle && !nested.isEmpty())
                    ? convertFieldNodes(nested, frameId, nodeId)
                    : new JsonArray();
                String badge = cycle ? "cycle" : null;
                nodes.add(treeNode(nodeId, name, List.of(type, value), badge, onActivate, children));
            }
            return nodes;
        }
    }

    // --- 3. Collection & Map Inspector ---

    private static final class CollectionViewProvider implements IMoldableViewProvider {
        @Override
        public String id() {
            return "equimacs.builtin.collection";
        }

        @Override
        public boolean supports(InspectContext context) {
            if (context == null || !"jdi-value".equals(context.targetKind())) {
                return false;
            }
            Map<String, Object> data = context.data();
            return data.get("elements") instanceof List<?> || data.get("entries") instanceof List<?>;
        }

        @Override
        public List<MoldableTab> provideViews(InspectContext context) {
            Map<String, Object> data = context.data();
            Long frameId = asLong(data.get("frameId"));
            List<MoldableTab> tabs = new ArrayList<>();

            if (data.get("elements") instanceof List<?>) {
                List<Map<String, Object>> elements = asListOfMaps(data.get("elements"));
                String label = "Elements (" + elements.size() + ")";
                JsonArray rows = new JsonArray();
                for (int i = 0; i < elements.size(); i++) {
                    Map<String, Object> el = elements.get(i);
                    String idx = el.containsKey("index") ? asString(el.get("index")) : Integer.toString(i);
                    String type = asString(el.get("type"));
                    String value = asString(el.get("value"));
                    String childExpr = asString(el.get("expression"));
                    JsonObject onActivate = (frameId != null && !childExpr.isBlank())
                        ? inspectValueAction(frameId, childExpr)
                        : null;
                    rows.add(tableRow("el-" + idx, List.of(idx, type, value), onActivate));
                }
                JsonObject spec = tableSpec(label, List.of("Index", "Type", "Value"), rows, null);
                tabs.add(new MoldableTab("elements", label, 15, GSON.toJson(spec)));
            }

            if (data.get("entries") instanceof List<?>) {
                List<Map<String, Object>> entries = asListOfMaps(data.get("entries"));
                String label = "Entries (" + entries.size() + ")";
                JsonArray rows = new JsonArray();
                for (int i = 0; i < entries.size(); i++) {
                    Map<String, Object> entry = entries.get(i);
                    String key = asString(entry.get("key"));
                    String value = asString(entry.get("value"));
                    String valueType = asString(entry.get("valueType"));
                    String childExpr = asString(entry.get("expression"));
                    JsonObject onActivate = (frameId != null && !childExpr.isBlank())
                        ? inspectValueAction(frameId, childExpr)
                        : null;
                    rows.add(tableRow("entry-" + i, List.of(key, value, valueType), onActivate));
                }
                JsonObject spec = tableSpec(label, List.of("Key", "Value", "Value Type"), rows, null);
                tabs.add(new MoldableTab("entries", label, 15, GSON.toJson(spec)));
            }

            return List.copyOf(tabs);
        }
    }

    // --- 4. String & JSON Inspector ---

    private static final class StringAndJsonViewProvider implements IMoldableViewProvider {
        @Override
        public String id() {
            return "equimacs.builtin.string";
        }

        @Override
        public boolean supports(InspectContext context) {
            if (context == null || !"jdi-value".equals(context.targetKind())) {
                return false;
            }
            if (asBoolean(context.data().get("isNull"))) {
                return false;
            }
            String typeName = context.typeName();
            return "java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName);
        }

        @Override
        public List<MoldableTab> provideViews(InspectContext context) {
            String value = asString(context.data().get("valueString"));
            long lineCount = value.isEmpty() ? 0L : value.lines().count();
            String fence = chooseMarkdownFence(value);

            StringBuilder md = new StringBuilder();
            md.append("Length: ").append(value.length())
              .append(" chars | Lines: ").append(lineCount)
              .append("\n\n")
              .append(fence).append("text\n")
              .append(value);
            if (!value.endsWith("\n")) {
                md.append('\n');
            }
            md.append(fence);

            JsonObject textSpec = new JsonObject();
            textSpec.addProperty("kind", "markdown");
            textSpec.addProperty("title", "Text");
            textSpec.addProperty("content", md.toString());

            List<MoldableTab> tabs = new ArrayList<>();
            tabs.add(new MoldableTab("text", "Text", 25, GSON.toJson(textSpec)));

            JsonElement parsedJson = tryParseObjectOrArray(value);
            if (parsedJson != null) {
                JsonArray roots = jsonElementToRootNodes(parsedJson);
                JsonObject jsonSpec = treeSpec(
                    "JSON", List.of("Key", "Type", "Value"), roots, true, null);
                tabs.add(new MoldableTab("json", "JSON", 18, GSON.toJson(jsonSpec)));
            }
            return List.copyOf(tabs);
        }

        private static JsonElement tryParseObjectOrArray(String value) {
            String trimmed = value.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return null;
            }
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (parsed != null && (parsed.isJsonObject() || parsed.isJsonArray())) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // Not valid JSON; fall back to plain text tab only.
            }
            return null;
        }

        private static JsonArray jsonElementToRootNodes(JsonElement root) {
            JsonArray nodes = new JsonArray();
            if (root.isJsonObject()) {
                int idx = 0;
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    nodes.add(toJsonTreeNode("$" + idx++, entry.getKey(), entry.getValue(), 0));
                }
            } else if (root.isJsonArray()) {
                JsonArray arr = root.getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    nodes.add(toJsonTreeNode("$[" + i + "]", "[" + i + "]", arr.get(i), 0));
                }
            }
            return nodes;
        }

        private static JsonObject toJsonTreeNode(String path, String key, JsonElement el, int depth) {
            if (el == null || el.isJsonNull()) {
                return treeNode(path, key, List.of("null", "null"), null, null, new JsonArray());
            }
            if (el.isJsonPrimitive()) {
                JsonPrimitive prim = el.getAsJsonPrimitive();
                String type = prim.isBoolean() ? "boolean" : (prim.isNumber() ? "number" : "string");
                return treeNode(path, key, List.of(type, prim.getAsString()), null, null, new JsonArray());
            }
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                String summary = "{" + obj.size() + (obj.size() == 1 ? " key}" : " keys}");
                JsonArray children = new JsonArray();
                if (depth < MAX_JSON_TREE_DEPTH) {
                    int idx = 0;
                    for (Map.Entry<String, JsonElement> child : obj.entrySet()) {
                        children.add(toJsonTreeNode(
                            path + "." + idx++, child.getKey(), child.getValue(), depth + 1));
                    }
                }
                return treeNode(path, key, List.of("object", summary), null, null, children);
            }
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                String summary = "[" + arr.size() + (arr.size() == 1 ? " item]" : " items]");
                JsonArray children = new JsonArray();
                if (depth < MAX_JSON_TREE_DEPTH) {
                    for (int i = 0; i < arr.size(); i++) {
                        children.add(toJsonTreeNode(
                            path + "[" + i + "]", "[" + i + "]", arr.get(i), depth + 1));
                    }
                }
                return treeNode(path, key, List.of("array", summary), null, null, children);
            }
            return treeNode(path, key, List.of("unknown", el.toString()), null, null, new JsonArray());
        }

        private static String chooseMarkdownFence(String text) {
            int maxRun = 0;
            int currentRun = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '`') {
                    currentRun++;
                    if (currentRun > maxRun) {
                        maxRun = currentRun;
                    }
                } else {
                    currentRun = 0;
                }
            }
            return "`".repeat(Math.max(3, maxRun + 1));
        }
    }

    // --- 5. JDT Symbol Inspector ---

    private static final class JdtSymbolViewProvider implements IMoldableViewProvider {
        @Override
        public String id() {
            return "equimacs.builtin.jdt-symbol";
        }

        @Override
        public boolean supports(InspectContext context) {
            return context != null && "jdt-symbol".equals(context.targetKind());
        }

        @Override
        public List<MoldableTab> provideViews(InspectContext context) {
            Map<String, Object> data = context.data();
            Map<String, Object> target = asMap(data.get("target"));
            String targetName = asString(target.get("name"));
            String targetKind = asString(target.get("kind"));
            String targetFqn = asString(target.get("fqn"));
            String declaringType = asString(target.get("declaringType"));
            String targetFile = asString(target.get("file"));
            int targetLine = asInt(target.get("line"), -1);
            int targetOffset = asInt(target.get("offset"), -1);
            String signature = asString(target.get("signature"));

            List<MoldableTab> tabs = new ArrayList<>();
            tabs.add(buildOverviewTab(
                context, targetName, targetKind, targetFqn, declaringType,
                targetFile, targetLine, targetOffset, signature));

            List<Map<String, Object>> members = asListOfMaps(data.get("members"));
            if (!members.isEmpty()) {
                tabs.add(buildMembersTab(members, targetFile));
            }

            if (data.get("references") instanceof List<?>) {
                tabs.add(buildReferencesTab(asListOfMaps(data.get("references"))));
            }

            if (data.get("hierarchy") instanceof Map<?, ?>) {
                tabs.add(buildHierarchyTab(asMap(data.get("hierarchy"))));
            }

            boolean hasCallers = data.get("callers") instanceof List<?>;
            boolean hasCallees = data.get("callees") instanceof List<?>;
            if (hasCallers || hasCallees) {
                List<Map<String, Object>> callers = hasCallers ? asListOfMaps(data.get("callers")) : null;
                List<Map<String, Object>> callees = hasCallees ? asListOfMaps(data.get("callees")) : null;
                tabs.add(buildCallsTab(callers, callees, targetFile, targetOffset));
            }

            return List.copyOf(tabs);
        }

        private static MoldableTab buildOverviewTab(
            InspectContext context,
            String name,
            String kind,
            String fqn,
            String declaringType,
            String file,
            int line,
            int offset,
            String signature
        ) {
            JsonObject openTarget = !file.isBlank() ? openFileAction(file, line) : null;
            JsonArray entries = new JsonArray();
            entries.add(kvEntry("Name", name, openTarget));
            entries.add(kvEntry("Kind", kind, null));
            if (!fqn.isBlank()) {
                entries.add(kvEntry("Qualified Name", fqn, null));
            }
            if (!declaringType.isBlank()) {
                entries.add(kvEntry("Declaring Type", declaringType, null));
            }
            if (!signature.isBlank()) {
                entries.add(kvEntry("Signature", signature, null));
            }
            entries.add(kvEntry("File", file, openTarget));
            if (line > 0) {
                entries.add(kvEntry("Line", Integer.toString(line), openTarget));
            }
            if (offset >= 0) {
                entries.add(kvEntry("Offset", Integer.toString(offset), openTarget));
            }
            String title = !context.title().isBlank() ? context.title() : (!name.isBlank() ? name : "Symbol");
            return new MoldableTab(
                "overview", "Overview", 10, GSON.toJson(keyValueSpec(title, "Symbol", entries)));
        }

        private static MoldableTab buildMembersTab(List<Map<String, Object>> members, String fallbackFile) {
            JsonArray rows = new JsonArray();
            for (int i = 0; i < members.size(); i++) {
                Map<String, Object> member = members.get(i);
                String kind = asString(member.get("kind"));
                String name = asString(member.get("name"));
                String sig = asString(member.get("signature"));
                String file = asString(member.get("file"));
                if (file.isBlank()) {
                    file = fallbackFile;
                }
                int line = asInt(member.get("line"), -1);
                JsonObject onActivate = !file.isBlank() ? openFileAction(file, line) : null;
                rows.add(tableRow(
                    "member-" + i,
                    List.of(kind, name, sig, line > 0 ? Integer.toString(line) : ""),
                    onActivate));
            }
            JsonObject spec = tableSpec(
                "Members", List.of("Kind", "Name", "Signature", "Line"), rows, null);
            return new MoldableTab("members", "Members", 20, GSON.toJson(spec));
        }

        private static MoldableTab buildReferencesTab(List<Map<String, Object>> references) {
            JsonArray rows = new JsonArray();
            for (int i = 0; i < references.size(); i++) {
                Map<String, Object> ref = references.get(i);
                String file = asString(ref.get("file"));
                int line = asInt(ref.get("line"), -1);
                String enclosing = formatEnclosing(ref);
                String snippet = asString(ref.get("snippet"));
                JsonObject onActivate = !file.isBlank() ? openFileAction(file, line) : null;
                rows.add(tableRow(
                    "ref-" + i,
                    List.of(file, line > 0 ? Integer.toString(line) : "", enclosing, snippet),
                    onActivate));
            }
            JsonObject spec = tableSpec(
                "References", List.of("File", "Line", "Enclosing", "Snippet"), rows, null);
            return new MoldableTab("references", "References", 30, GSON.toJson(spec));
        }

        private static MoldableTab buildHierarchyTab(Map<String, Object> hierarchy) {
            List<Map<String, Object>> superclasses = asListOfMaps(hierarchy.get("superclasses"));
            if (superclasses.isEmpty() && hierarchy.get("superclass") instanceof Map<?, ?>) {
                superclasses = List.of(asMap(hierarchy.get("superclass")));
            }
            List<Map<String, Object>> interfaces = asListOfMaps(hierarchy.get("interfaces"));
            List<Map<String, Object>> subtypes = asListOfMaps(hierarchy.get("subtypes"));

            JsonArray roots = new JsonArray();
            roots.add(hierarchyGroupNode("group-superclasses", "Superclasses", superclasses));
            roots.add(hierarchyGroupNode("group-interfaces", "Interfaces", interfaces));
            roots.add(hierarchyGroupNode("group-subtypes", "Subtypes", subtypes));

            JsonObject spec = treeSpec(
                "Type Hierarchy", List.of("Type", "Qualified Name", "Location"), roots, true, null);
            return new MoldableTab("hierarchy", "Hierarchy", 40, GSON.toJson(spec));
        }

        private static JsonObject hierarchyGroupNode(
            String id,
            String groupLabel,
            List<Map<String, Object>> types
        ) {
            JsonArray children = new JsonArray();
            for (int i = 0; i < types.size(); i++) {
                Map<String, Object> typeMap = types.get(i);
                String name = asString(typeMap.get("name"));
                String fqn = asString(typeMap.get("fqn"));
                boolean isInterface = asBoolean(typeMap.get("isInterface"));
                String file = asString(typeMap.get("file"));
                int line = asInt(typeMap.get("line"), -1);
                String location = formatLocation(file, line);
                JsonObject onActivate = !file.isBlank() ? openFileAction(file, line) : null;
                children.add(treeNode(
                    id + "-" + i,
                    !name.isBlank() ? name : fqn,
                    List.of(fqn, location),
                    isInterface ? "interface" : "class",
                    onActivate,
                    new JsonArray()));
            }
            return treeNode(
                id, groupLabel, List.of("", Integer.toString(types.size()) + " type(s)"), null, null, children);
        }

        private static MoldableTab buildCallsTab(
            List<Map<String, Object>> callers,
            List<Map<String, Object>> callees,
            String targetFile,
            int targetOffset
        ) {
            JsonArray roots = new JsonArray();
            if (callers != null) {
                JsonArray callerNodes = convertCallNodes(callers, "callers");
                roots.add(treeNode(
                    "group-callers",
                    "Callers",
                    List.of("", callers.size() + " item(s)"),
                    null,
                    null,
                    callerNodes));
            }
            if (callees != null) {
                JsonArray calleeNodes = convertCallNodes(callees, "callees");
                roots.add(treeNode(
                    "group-callees",
                    "Callees",
                    List.of("", callees.size() + " item(s)"),
                    null,
                    null,
                    calleeNodes));
            }

            JsonArray toolbar = null;
            if (!targetFile.isBlank()) {
                JsonObject params = new JsonObject();
                params.addProperty("file", targetFile);
                params.addProperty("offset", Integer.toString(Math.max(0, targetOffset)));
                JsonObject toolkitAction = new JsonObject();
                toolkitAction.addProperty("type", "toolkit-view");
                toolkitAction.addProperty("tool", "call-explorer");
                toolkitAction.add("params", params);

                JsonObject button = new JsonObject();
                button.addProperty("label", "Open Call Explorer");
                button.add("action", toolkitAction);
                toolbar = new JsonArray();
                toolbar.add(button);
            }

            JsonObject spec = treeSpec(
                "Call Hierarchy", List.of("Method", "Location", "Snippet"), roots, true, toolbar);
            return new MoldableTab("calls", "Calls", 45, GSON.toJson(spec));
        }

        private static JsonArray convertCallNodes(List<Map<String, Object>> nodes, String pathPrefix) {
            JsonArray result = new JsonArray();
            for (int i = 0; i < nodes.size(); i++) {
                Map<String, Object> node = nodes.get(i);
                Map<String, Object> element = asMap(node.get("element"));
                String name = asString(element.get("name"));
                if (name.isBlank()) {
                    name = asString(node.get("name"));
                }
                String declaring = asString(element.get("declaringType"));
                String label = (!declaring.isBlank() && !name.isBlank())
                    ? declaring + "." + name
                    : (!name.isBlank() ? name : declaring);
                String file = asString(node.get("file"));
                if (file.isBlank()) {
                    file = asString(element.get("file"));
                }
                int line = asInt(node.get("line"), -1);
                String snippet = asString(node.get("snippet"));
                String nodeId = pathPrefix + "/" + i;
                JsonObject onActivate = !file.isBlank() ? openFileAction(file, line) : null;
                JsonArray children = convertCallNodes(asListOfMaps(node.get("children")), nodeId);
                result.add(treeNode(
                    nodeId, label, List.of(formatLocation(file, line), snippet), null, onActivate, children));
            }
            return result;
        }

        private static String formatEnclosing(Map<String, Object> ref) {
            if (ref.get("enclosingElement") instanceof Map<?, ?>) {
                Map<String, Object> enc = asMap(ref.get("enclosingElement"));
                String name = asString(enc.get("name"));
                String declaring = asString(enc.get("declaringType"));
                if (!declaring.isBlank() && !name.isBlank()) {
                    return declaring + "." + name;
                }
                return name;
            }
            return asString(ref.get("enclosing"));
        }

        private static String formatLocation(String file, int line) {
            if (file.isBlank()) {
                return "";
            }
            return line > 0 ? file + ":" + line : file;
        }
    }

    // --- Shared Spec & Action Builders ---

    private static JsonObject keyValueSpec(String title, String heading, JsonArray entries) {
        JsonObject section = new JsonObject();
        section.addProperty("heading", heading);
        section.add("entries", entries);

        JsonArray sections = new JsonArray();
        sections.add(section);

        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "key-value");
        spec.addProperty("title", title);
        spec.add("sections", sections);
        return spec;
    }

    private static JsonObject kvEntry(String key, String value, JsonObject onActivate) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("value", value == null ? "" : value);
        if (onActivate != null) {
            entry.add("onActivate", onActivate);
        }
        return entry;
    }

    private static JsonObject tableSpec(
        String title,
        List<String> columns,
        JsonArray rows,
        JsonArray toolbar
    ) {
        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "table");
        spec.addProperty("title", title);
        spec.add("columns", toJsonArray(columns));
        spec.add("rows", rows);
        if (toolbar != null && !toolbar.isEmpty()) {
            spec.add("toolbar", toolbar);
        }
        return spec;
    }

    private static JsonObject tableRow(String id, List<String> cells, JsonObject onActivate) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.add("cells", toJsonArray(cells));
        if (onActivate != null) {
            row.add("onActivate", onActivate);
        }
        return row;
    }

    private static JsonObject treeSpec(
        String title,
        List<String> columns,
        JsonArray roots,
        boolean expandAll,
        JsonArray toolbar
    ) {
        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "tree");
        spec.addProperty("title", title);
        spec.add("columns", toJsonArray(columns));
        spec.addProperty("expandAll", expandAll);
        spec.add("roots", roots);
        if (toolbar != null && !toolbar.isEmpty()) {
            spec.add("toolbar", toolbar);
        }
        return spec;
    }

    private static JsonObject treeNode(
        String id,
        String label,
        List<String> values,
        String badge,
        JsonObject onActivate,
        JsonArray children
    ) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("label", label == null ? "" : label);
        node.add("values", toJsonArray(values));
        if (badge != null && !badge.isBlank()) {
            node.addProperty("badge", badge);
        }
        if (onActivate != null) {
            node.add("onActivate", onActivate);
        }
        node.add("children", children != null ? children : new JsonArray());
        return node;
    }

    private static JsonObject openFileAction(String file, int line) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "open-file");
        action.addProperty("file", file);
        action.addProperty("line", line);
        action.addProperty("endLine", line);
        return action;
    }

    private static JsonObject inspectValueAction(long frameId, String expression) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "inspect");
        action.addProperty("targetKind", "jdi-value");
        action.addProperty("frameId", frameId);
        action.addProperty("expression", expression);
        return action;
    }

    private static JsonArray toJsonArray(List<String> items) {
        JsonArray arr = new JsonArray();
        if (items != null) {
            for (String item : items) {
                arr.add(item == null ? "" : item);
            }
        }
        return arr;
    }

    // --- Safe Map Coercion Helpers ---

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return Long.valueOf(n.longValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof Map<?, ?>) {
                result.add(asMap(item));
            }
        }
        return result;
    }
}
