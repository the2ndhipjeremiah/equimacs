package org.equimacs.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.eclipse.bridge.api.InspectContext;
import org.equimacs.eclipse.bridge.api.MoldableTab;
import org.junit.jupiter.api.Test;

class MoldableInspectorCoreTest {

    private static final Pattern VALID_VIEW_ID = Pattern.compile("^[A-Za-z0-9._-]+$");

    @Test
    void frameInspectorBuildsOverviewVariablesAndEvaluateTabs() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("frameId", 101L);
        data.put("className", "demo.OrderService");
        data.put("method", "checkout");
        data.put("file", "/proj/src/demo/OrderService.java");
        data.put("line", 42);
        data.put("variables", List.of(
            Map.of("name", "items", "type", "java.util.ArrayList", "value", "[a, b]"),
            Map.of("name", "total", "type", "int", "value", "2")));

        InspectContext ctx = new InspectContext(
            "jdi-frame", "OrderService.checkout:42", "demo.OrderService", "frame summary", data);

        MoldableInspectorCore.AssemblyResult result =
            MoldableInspectorCore.assemble(ctx, BuiltInInspectProviders.all(), List.of());

        assertEquals("OrderService.checkout:42", result.title());
        assertEquals(List.of("equimacs.builtin.frame"), result.providerIds());
        assertEquals(3, result.tabs().size());
        assertEquals(List.of("overview", "variables", "evaluate"),
            result.tabs().stream().map(MoldableTab::id).toList());

        JsonObject root = JsonParser.parseString(result.specJson()).getAsJsonObject();
        assertEquals("tabs", root.get("kind").getAsString());
        assertEquals("OrderService.checkout:42", root.get("title").getAsString());
        JsonArray tabs = root.getAsJsonArray("tabs");
        assertEquals(3, tabs.size());

        JsonObject overview = tabs.get(0).getAsJsonObject().getAsJsonObject("content");
        assertEquals("key-value", overview.get("kind").getAsString());
        JsonArray entries = overview.getAsJsonArray("sections").get(0).getAsJsonObject().getAsJsonArray("entries");
        JsonObject lineEntry = findKvEntry(entries, "Line");
        assertNotNull(lineEntry);
        assertEquals("42", lineEntry.get("value").getAsString());
        JsonObject openFile = lineEntry.getAsJsonObject("onActivate");
        assertEquals("open-file", openFile.get("type").getAsString());
        assertEquals("/proj/src/demo/OrderService.java", openFile.get("file").getAsString());
        assertEquals(42, openFile.get("line").getAsInt());

        JsonObject varsTab = tabs.get(1).getAsJsonObject().getAsJsonObject("content");
        assertEquals("table", varsTab.get("kind").getAsString());
        JsonObject firstRow = varsTab.getAsJsonArray("rows").get(0).getAsJsonObject();
        JsonObject inspectVar = firstRow.getAsJsonObject("onActivate");
        assertEquals("inspect", inspectVar.get("type").getAsString());
        assertEquals("jdi-value", inspectVar.get("targetKind").getAsString());
        assertEquals(101L, inspectVar.get("frameId").getAsLong());
        assertEquals("items", inspectVar.get("expression").getAsString());

        JsonObject evalTab = tabs.get(2).getAsJsonObject().getAsJsonObject("content");
        assertEquals("form", evalTab.get("kind").getAsString());
        assertEquals("Inspect Expression", evalTab.get("submitLabel").getAsString());
        JsonObject exprField = evalTab.getAsJsonArray("fields").get(0).getAsJsonObject();
        assertEquals("expression", exprField.get("name").getAsString());
        assertTrue(exprField.get("required").getAsBoolean());
        JsonObject onSubmit = evalTab.getAsJsonObject("onSubmit");
        assertEquals("inspect", onSubmit.get("type").getAsString());
        assertEquals(101L, onSubmit.get("frameId").getAsLong());
    }

    @Test
    void rawObjectViewRendersNestedFieldsAndStopsCycles() {
        Map<String, Object> cyclicNext = new LinkedHashMap<>();
        cyclicNext.put("name", "next");
        cyclicNext.put("type", "demo.Node");
        cyclicNext.put("value", "Node@1");
        cyclicNext.put("expression", "head.next.next");
        cyclicNext.put("cycle", true);

        Map<String, Object> childField = new LinkedHashMap<>();
        childField.put("name", "next");
        childField.put("type", "demo.Node");
        childField.put("value", "Node@2");
        childField.put("expression", "head.next");
        childField.put("cycle", false);
        childField.put("fields", List.of(cyclicNext));

        Map<String, Object> labelField = new LinkedHashMap<>();
        labelField.put("name", "label");
        labelField.put("type", "java.lang.String");
        labelField.put("value", "root");
        labelField.put("expression", "head.label");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("frameId", 77L);
        data.put("expression", "head");
        data.put("valueString", "Node@1");
        data.put("isNull", false);
        data.put("uniqueId", 9001L);
        data.put("fields", List.of(labelField, childField));

        InspectContext ctx = new InspectContext("jdi-value", "head (Node)", "demo.Node", "demo.Node = Node@1", data);
        MoldableInspectorCore.AssemblyResult result =
            MoldableInspectorCore.assemble(ctx, BuiltInInspectProviders.all(), List.of());

        assertEquals(List.of("equimacs.builtin.raw-object"), result.providerIds());
        assertEquals(List.of("overview", "fields"), result.tabs().stream().map(MoldableTab::id).toList());

        JsonObject fieldsSpec = JsonParser.parseString(result.tabs().get(1).specJson()).getAsJsonObject();
        assertEquals("tree", fieldsSpec.get("kind").getAsString());
        assertTrue(fieldsSpec.get("expandAll").getAsBoolean());
        JsonArray roots = fieldsSpec.getAsJsonArray("roots");
        assertEquals(2, roots.size());

        JsonObject labelNode = roots.get(0).getAsJsonObject();
        assertEquals("label", labelNode.get("label").getAsString());
        assertEquals("head.label", labelNode.getAsJsonObject("onActivate").get("expression").getAsString());

        JsonObject nextNode = roots.get(1).getAsJsonObject();
        assertEquals("head.next", nextNode.getAsJsonObject("onActivate").get("expression").getAsString());
        JsonObject cycleNode = nextNode.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("cycle", cycleNode.get("badge").getAsString());
        assertFalse(cycleNode.has("onActivate"));
    }

    @Test
    void collectionViewHandlesElementsAndMapEntries() {
        Map<String, Object> listData = new LinkedHashMap<>();
        listData.put("frameId", 55L);
        listData.put("expression", "items");
        listData.put("valueString", "[alpha, beta]");
        listData.put("isNull", false);
        listData.put("elements", List.of(
            Map.of("index", 0, "type", "java.lang.String", "value", "alpha", "expression", "items.get(0)"),
            Map.of("index", 1, "type", "java.lang.String", "value", "beta")));

        InspectContext listCtx = new InspectContext(
            "jdi-value", "items (ArrayList)", "java.util.ArrayList", "size=2", listData);
        MoldableInspectorCore.AssemblyResult listResult =
            MoldableInspectorCore.assemble(listCtx, BuiltInInspectProviders.all(), List.of());

        assertEquals(List.of("equimacs.builtin.raw-object", "equimacs.builtin.collection"),
            listResult.providerIds());
        assertEquals(List.of("overview", "elements"),
            listResult.tabs().stream().map(MoldableTab::id).toList());
        assertEquals("Elements (2)", listResult.tabs().get(1).label());

        JsonObject elementsSpec = JsonParser.parseString(listResult.tabs().get(1).specJson()).getAsJsonObject();
        JsonArray rows = elementsSpec.getAsJsonArray("rows");
        assertEquals("items.get(0)", rows.get(0).getAsJsonObject().getAsJsonObject("onActivate").get("expression").getAsString());
        assertFalse(rows.get(1).getAsJsonObject().has("onActivate"));

        Map<String, Object> mapData = new LinkedHashMap<>();
        mapData.put("frameId", 55L);
        mapData.put("expression", "config");
        mapData.put("valueString", "{port=8080}");
        mapData.put("isNull", false);
        mapData.put("entries", List.of(
            Map.of("key", "port", "value", "8080", "valueType", "java.lang.Integer"),
            Map.of("key", "env", "value", "prod", "valueType", "java.lang.String")));

        InspectContext mapCtx = new InspectContext(
            "jdi-value", "config (HashMap)", "java.util.HashMap", "size=2", mapData);
        MoldableInspectorCore.AssemblyResult mapResult =
            MoldableInspectorCore.assemble(mapCtx, BuiltInInspectProviders.all(), List.of());

        assertEquals(List.of("overview", "entries"),
            mapResult.tabs().stream().map(MoldableTab::id).toList());
        assertEquals("Entries (2)", mapResult.tabs().get(1).label());
    }

    @Test
    void stringAndJsonViewHandlesPlainStringsAndJsonPayloads() {
        Map<String, Object> plainData = Map.of(
            "frameId", 1L,
            "expression", "msg",
            "valueString", "hello\nworld",
            "isNull", false);
        InspectContext plainCtx = new InspectContext(
            "jdi-value", "msg (String)", "java.lang.String", "hello", plainData);

        MoldableInspectorCore.AssemblyResult plainResult =
            MoldableInspectorCore.assemble(plainCtx, BuiltInInspectProviders.all(), List.of());
        assertEquals(List.of("overview", "text"), plainResult.tabs().stream().map(MoldableTab::id).toList());
        JsonObject textSpec = JsonParser.parseString(plainResult.tabs().get(1).specJson()).getAsJsonObject();
        assertEquals("markdown", textSpec.get("kind").getAsString());
        assertTrue(textSpec.get("content").getAsString().contains("Length: 11 chars | Lines: 2"));

        String jsonPayload = "{\"user\":{\"name\":\"Alice\",\"roles\":[\"admin\"]},\"active\":true}";
        Map<String, Object> jsonData = Map.of(
            "frameId", 1L,
            "expression", "payload",
            "valueString", jsonPayload,
            "isNull", false);
        InspectContext jsonCtx = new InspectContext(
            "jdi-value", "payload (CharSequence)", "java.lang.CharSequence", "json", jsonData);

        MoldableInspectorCore.AssemblyResult jsonResult =
            MoldableInspectorCore.assemble(jsonCtx, BuiltInInspectProviders.all(), List.of());
        assertEquals(List.of("overview", "json", "text"),
            jsonResult.tabs().stream().map(MoldableTab::id).toList());

        JsonObject jsonSpec = JsonParser.parseString(jsonResult.tabs().get(1).specJson()).getAsJsonObject();
        assertEquals("tree", jsonSpec.get("kind").getAsString());
        assertTrue(jsonSpec.get("expandAll").getAsBoolean());
        JsonArray roots = jsonSpec.getAsJsonArray("roots");
        assertEquals("user", roots.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("active", roots.get(1).getAsJsonObject().get("label").getAsString());

        Map<String, Object> nullData = new LinkedHashMap<>();
        nullData.put("frameId", 1L);
        nullData.put("expression", "s");
        nullData.put("valueString", "null");
        nullData.put("isNull", true);
        InspectContext nullCtx = new InspectContext("jdi-value", "s", "java.lang.String", "null", nullData);
        MoldableInspectorCore.AssemblyResult nullResult =
            MoldableInspectorCore.assemble(nullCtx, BuiltInInspectProviders.all(), List.of());
        assertEquals(List.of("equimacs.builtin.raw-object"), nullResult.providerIds());
    }

    @Test
    void jdtSymbolViewBuildsOverviewMembersReferencesHierarchyAndCalls() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("name", "process");
        target.put("kind", "method");
        target.put("fqn", "demo.Worker#process");
        target.put("declaringType", "demo.Worker");
        target.put("file", "/proj/src/demo/Worker.java");
        target.put("line", 28);
        target.put("offset", 412);
        target.put("signature", "void process(String input)");

        Map<String, Object> member = Map.of(
            "kind", "field",
            "name", "count",
            "signature", "int",
            "file", "/proj/src/demo/Worker.java",
            "line", 10);

        Map<String, Object> ref = Map.of(
            "file", "/proj/src/demo/App.java",
            "line", 19,
            "enclosingElement", Map.of("name", "main", "declaringType", "demo.App"),
            "snippet", "worker.process(\"hi\");");

        Map<String, Object> hierarchy = Map.of(
            "superclasses", List.of(Map.of(
                "name", "Object", "fqn", "java.lang.Object", "isInterface", false, "file", "", "line", -1)),
            "interfaces", List.of(Map.of(
                "name", "Runnable", "fqn", "java.lang.Runnable", "isInterface", true, "file", "", "line", -1)),
            "subtypes", List.of(Map.of(
                "name", "FastWorker", "fqn", "demo.FastWorker", "isInterface", false,
                "file", "/proj/src/demo/FastWorker.java", "line", 3)));

        Map<String, Object> caller = Map.of(
            "element", Map.of("name", "main", "declaringType", "demo.App"),
            "file", "/proj/src/demo/App.java",
            "line", 19,
            "snippet", "worker.process(\"hi\");",
            "children", List.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target", target);
        data.put("members", List.of(member));
        data.put("references", List.of(ref));
        data.put("hierarchy", hierarchy);
        data.put("callers", List.of(caller));
        data.put("callees", List.of());

        InspectContext ctx = new InspectContext("jdt-symbol", "process", "demo.Worker#process", "method process", data);
        MoldableInspectorCore.AssemblyResult result =
            MoldableInspectorCore.assemble(ctx, BuiltInInspectProviders.all(), List.of());

        assertEquals(List.of("equimacs.builtin.jdt-symbol"), result.providerIds());
        assertEquals(List.of("overview", "members", "references", "hierarchy", "calls"),
            result.tabs().stream().map(MoldableTab::id).toList());

        JsonObject callsSpec = JsonParser.parseString(result.tabs().get(4).specJson()).getAsJsonObject();
        assertEquals("tree", callsSpec.get("kind").getAsString());
        assertTrue(callsSpec.get("expandAll").getAsBoolean());
        JsonArray toolbar = callsSpec.getAsJsonArray("toolbar");
        assertEquals(1, toolbar.size());
        JsonObject btn = toolbar.get(0).getAsJsonObject();
        assertEquals("Open Call Explorer", btn.get("label").getAsString());
        JsonObject action = btn.getAsJsonObject("action");
        assertEquals("toolkit-view", action.get("type").getAsString());
        assertEquals("call-explorer", action.get("tool").getAsString());
        JsonObject params = action.getAsJsonObject("params");
        assertEquals("/proj/src/demo/Worker.java", params.get("file").getAsString());
        assertEquals("412", params.get("offset").getAsString());
    }

    @Test
    void externalProvidersOverrideOrderIsolateFaultsAndFallback() {
        IMoldableViewProvider brokenSupports = new IMoldableViewProvider() {
            @Override public String id() { return "ext.broken.supports"; }
            @Override public boolean supports(InspectContext context) { throw new RuntimeException("boom"); }
            @Override public List<MoldableTab> provideViews(InspectContext context) { return List.of(); }
        };
        IMoldableViewProvider brokenProvide = new IMoldableViewProvider() {
            @Override public String id() { return "ext.broken.provide"; }
            @Override public boolean supports(InspectContext context) { return true; }
            @Override public List<MoldableTab> provideViews(InspectContext context) { throw new Error("fatal"); }
        };
        IMoldableViewProvider invalidTabs = new IMoldableViewProvider() {
            @Override public String id() { return "ext.invalid"; }
            @Override public boolean supports(InspectContext context) { return true; }
            @Override public List<MoldableTab> provideViews(InspectContext context) {
                return List.of(
                    new MoldableTab("", "BlankId", 1, "{\"kind\":\"markdown\",\"content\":\"x\"}"),
                    new MoldableTab("badjson", "BadJson", 1, "not-json"),
                    new MoldableTab("arrayjson", "ArrJson", 1, "[1,2,3]"));
            }
        };
        IMoldableViewProvider overrideAndCustom = new IMoldableViewProvider() {
            @Override public String id() { return "ext.custom"; }
            @Override public boolean supports(InspectContext context) { return true; }
            @Override public List<MoldableTab> provideViews(InspectContext context) {
                return List.of(
                    new MoldableTab("overview", "Custom Overview", 10,
                        "{\"kind\":\"markdown\",\"content\":\"# Overridden\"}"),
                    new MoldableTab("extra", "Extra", 5,
                        "{\"kind\":\"markdown\",\"content\":\"Extra tab\"}"));
            }
        };

        Map<String, Object> data = Map.of("frameId", 1L, "expression", "x", "valueString", "42", "isNull", false);
        InspectContext ctx = new InspectContext("jdi-value", "x (int)", "int", "int = 42", data);

        MoldableInspectorCore.AssemblyResult result = MoldableInspectorCore.assemble(
            ctx,
            BuiltInInspectProviders.all(),
            List.of(brokenSupports, brokenProvide, invalidTabs, overrideAndCustom));

        assertEquals(List.of("extra", "overview"), result.tabs().stream().map(MoldableTab::id).toList());
        assertEquals("Custom Overview", result.tabs().get(1).label());
        assertEquals(List.of("equimacs.builtin.raw-object", "ext.custom"), result.providerIds());

        // Fallback summary tab when no provider supports context
        InspectContext unknownCtx = new InspectContext("custom-kind", "Mystery", "com.foo.Bar", "unknown summary", Map.of());
        MoldableInspectorCore.AssemblyResult fallbackResult =
            MoldableInspectorCore.assemble(unknownCtx, BuiltInInspectProviders.all(), List.of(brokenSupports));
        assertEquals(List.of(), fallbackResult.providerIds());
        assertEquals(1, fallbackResult.tabs().size());
        assertEquals("summary", fallbackResult.tabs().get(0).id());
        JsonObject fallbackKv = JsonParser.parseString(fallbackResult.tabs().get(0).specJson()).getAsJsonObject();
        assertEquals("key-value", fallbackKv.get("kind").getAsString());
        JsonArray entries = fallbackKv.getAsJsonArray("sections").get(0).getAsJsonObject().getAsJsonArray("entries");
        assertEquals("custom-kind", findKvEntry(entries, "Target Kind").get("value").getAsString());
        assertEquals("Mystery", findKvEntry(entries, "Title").get("value").getAsString());
        assertEquals("com.foo.Bar", findKvEntry(entries, "Type").get("value").getAsString());
        assertEquals("unknown summary", findKvEntry(entries, "Summary").get("value").getAsString());
        assertNull(findKvEntry(entries, "NonExistent"));
    }

    @Test
    void defaultViewIdSanitizesAndBoundsLength() {
        String id1 = MoldableInspectorCore.defaultViewId("jdi-value", "order.getItems() (ArrayList)");
        assertTrue(VALID_VIEW_ID.matcher(id1).matches());
        assertTrue(id1.startsWith("inspect-jdi-value-"));
        assertTrue(id1.length() <= 100);

        String id2 = MoldableInspectorCore.defaultViewId("jdt-symbol", "C:\\proj\\App.java:42");
        assertTrue(VALID_VIEW_ID.matcher(id2).matches());

        String id3 = MoldableInspectorCore.defaultViewId(null, "!!!");
        assertEquals("inspect-target-item", id3);

        String longId = MoldableInspectorCore.defaultViewId("jdi-value", "veryLongName_".repeat(20));
        assertTrue(VALID_VIEW_ID.matcher(longId).matches());
        assertTrue(longId.length() <= 100);
    }

    private static JsonObject findKvEntry(JsonArray entries, String key) {
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            if (key.equals(entry.get("key").getAsString())) {
                return entry;
            }
        }
        return null;
    }
}
