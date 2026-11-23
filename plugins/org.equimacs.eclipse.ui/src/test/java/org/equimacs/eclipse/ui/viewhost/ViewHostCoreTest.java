package org.equimacs.eclipse.ui.viewhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ViewHostCoreTest {

    @Test
    void parsesTableSpecAndAllActionTypes() {
        String json = """
            {
              "kind": "table",
              "title": "Findings",
              "columns": ["File", "Line", "Message"],
              "toolbar": [
                {
                  "label": "Refresh",
                  "tooltip": "Re-run scan",
                  "action": { "type": "emit", "name": "refresh", "payload": { "scope": "all" } }
                },
                {
                  "label": "Save",
                  "action": {
                    "type": "eclipse-command",
                    "commandId": "org.eclipse.ui.file.save",
                    "file": "/proj/App.java",
                    "offset": 12,
                    "params": { "mode": "fast" }
                  }
                },
                {
                  "label": "Problems",
                  "action": { "type": "show-view", "viewId": "org.eclipse.ui.views.ProblemView", "secondaryId": "sec1" }
                },
                {
                  "label": "Custom",
                  "action": { "type": "custom", "target": "analyzer", "action": "run", "args": { "level": 2 } }
                },
                {
                  "label": "Inspect",
                  "action": {
                    "type": "inspect",
                    "targetKind": "jdi-value",
                    "frameId": 99,
                    "expression": "user.name",
                    "viewId": "insp-1"
                  }
                },
                {
                  "label": "Calls",
                  "action": {
                    "type": "toolkit-view",
                    "tool": "call-explorer",
                    "params": { "direction": "callees", "depth": "2" }
                  }
                }
              ],
              "rows": [
                {
                  "id": "r1",
                  "cells": ["App.java", 42, "Unused import"],
                  "onSelect": { "type": "emit", "name": "row-selected", "payload": "r1" },
                  "onActivate": { "type": "open-file", "file": "/proj/App.java", "line": 42 }
                }
              ]
            }
            """;

        ViewSpec spec = ViewSpecParser.parse(json);
        ViewSpec.TableSpec table = assertInstanceOf(ViewSpec.TableSpec.class, spec);
        assertEquals("table", table.kind());
        assertEquals("Findings", table.title());
        assertEquals(List.of("File", "Line", "Message"), table.columns());
        assertEquals(1, table.itemCount());

        assertEquals(6, table.toolbar().size());
        ViewAction.Emit emitToolbar = assertInstanceOf(ViewAction.Emit.class, table.toolbar().get(0).action());
        assertEquals("refresh", emitToolbar.name());
        assertEquals("{\"scope\":\"all\"}", emitToolbar.payload());

        ViewAction.EclipseCommand cmdAction =
            assertInstanceOf(ViewAction.EclipseCommand.class, table.toolbar().get(1).action());
        assertEquals("org.eclipse.ui.file.save", cmdAction.commandId());
        assertEquals("/proj/App.java", cmdAction.file());
        assertEquals(12, cmdAction.offset());
        assertEquals(Map.of("mode", "fast"), cmdAction.params());

        ViewAction.ShowView showView =
            assertInstanceOf(ViewAction.ShowView.class, table.toolbar().get(2).action());
        assertEquals("org.eclipse.ui.views.ProblemView", showView.viewId());
        assertEquals("sec1", showView.secondaryId());

        ViewAction.Custom custom = assertInstanceOf(ViewAction.Custom.class, table.toolbar().get(3).action());
        assertEquals("analyzer", custom.target());
        assertEquals("run", custom.action());
        assertEquals("{\"level\":2}", custom.args());

        ViewAction.Inspect inspect = assertInstanceOf(ViewAction.Inspect.class, table.toolbar().get(4).action());
        assertEquals("jdi-value", inspect.targetKind());
        assertEquals(99L, inspect.frameId());
        assertEquals("user.name", inspect.expression());
        assertEquals(-1, inspect.offset());
        assertEquals("insp-1", inspect.viewId());

        ViewAction.ToolkitView toolkitView =
            assertInstanceOf(ViewAction.ToolkitView.class, table.toolbar().get(5).action());
        assertEquals("call-explorer", toolkitView.tool());
        assertEquals(Map.of("direction", "callees", "depth", "2"), toolkitView.params());

        ViewSpec.TableRow row = table.rows().get(0);
        assertEquals("r1", row.id());
        assertEquals(List.of("App.java", "42", "Unused import"), row.cells());
        ViewAction.Emit selectAction = assertInstanceOf(ViewAction.Emit.class, row.onSelect());
        assertEquals("row-selected", selectAction.name());
        assertEquals("r1", selectAction.payload());
        ViewAction.OpenFile openFile = assertInstanceOf(ViewAction.OpenFile.class, row.onActivate());
        assertEquals("/proj/App.java", openFile.file());
        assertEquals(42, openFile.line());
        assertEquals(42, openFile.endLine());
    }

    @Test
    void parsesTreeMarkdownKeyValueAndTabsSpecs() {
        String treeJson = """
            {
              "kind": "tree",
              "title": "Hierarchy",
              "columns": ["Node", "Details"],
              "expandAll": true,
              "roots": [
                {
                  "id": "root1",
                  "label": "src",
                  "values": ["2 items"],
                  "badge": "DIR",
                  "children": [
                    {
                      "id": "leaf1",
                      "label": "Main.java",
                      "values": ["Line 10"],
                      "onActivate": { "type": "open-file", "file": "/proj/Main.java", "line": 10, "endLine": 15 }
                    }
                  ]
                }
              ]
            }
            """;
        ViewSpec.TreeSpec tree = assertInstanceOf(ViewSpec.TreeSpec.class, ViewSpecParser.parse(treeJson));
        assertEquals("tree", tree.kind());
        assertTrue(tree.expandAll());
        assertEquals(2, tree.itemCount());
        assertEquals("DIR", tree.roots().get(0).badge());
        ViewAction.OpenFile leafOpen = assertInstanceOf(
            ViewAction.OpenFile.class, tree.roots().get(0).children().get(0).onActivate());
        assertEquals(10, leafOpen.line());
        assertEquals(15, leafOpen.endLine());

        String mdJson = """
            {
              "kind": "markdown",
              "title": "Notes",
              "content": "# Summary\\nAll good."
            }
            """;
        ViewSpec.MarkdownSpec md = assertInstanceOf(ViewSpec.MarkdownSpec.class, ViewSpecParser.parse(mdJson));
        assertEquals("markdown", md.kind());
        assertEquals(2, md.itemCount());

        String kvJson = """
            {
              "kind": "key-value",
              "title": "Session",
              "sections": [
                {
                  "heading": "Runtime",
                  "entries": [
                    { "key": "Java", "value": "26" },
                    { "key": "Main", "value": "App.java", "onActivate": { "type": "open-file", "file": "/proj/App.java" } }
                  ]
                }
              ]
            }
            """;
        ViewSpec.KeyValueSpec kv = assertInstanceOf(ViewSpec.KeyValueSpec.class, ViewSpecParser.parse(kvJson));
        assertEquals("key-value", kv.kind());
        assertEquals(2, kv.itemCount());
        ViewAction.OpenFile kvOpen = assertInstanceOf(
            ViewAction.OpenFile.class, kv.sections().get(0).entries().get(1).onActivate());
        assertEquals(-1, kvOpen.line());
        assertEquals(-1, kvOpen.endLine());

        String tabsJson = """
            {
              "kind": "tabs",
              "title": "Dashboard",
              "tabs": [
                { "label": "Overview", "content": { "kind": "markdown", "content": "Hello" } },
                { "label": "Metrics", "content": { "kind": "key-value", "sections": [] } }
              ]
            }
            """;
        ViewSpec.TabsSpec tabs = assertInstanceOf(ViewSpec.TabsSpec.class, ViewSpecParser.parse(tabsJson));
        assertEquals("tabs", tabs.kind());
        assertEquals(2, tabs.itemCount());
        assertEquals("markdown", tabs.tabs().get(0).content().kind());
        assertEquals("key-value", tabs.tabs().get(1).content().kind());
    }

    @Test
    void parsesHtmlSplitFormAndLiveBindingSpecs() {
        String htmlContentJson = """
            {
              "kind": "html",
              "title": "Live Chart",
              "content": "<h1>Hi</h1>",
              "state": {
                "count": 3,
                "active": true,
                "empty": null,
                "tags": ["a", 2, null],
                "meta": { "owner": "equimacs" }
              },
              "live": {
                "events": ["LogpointHit", "TestRunFinished"],
                "onEvent": { "type": "toolkit-view", "tool": "trace", "params": { "limit": "50" } },
                "pushToHtml": true,
                "debounceMs": 200
              }
            }
            """;
        ViewSpecParser.ParsedView envelope = ViewSpecParser.parseEnvelope(htmlContentJson);
        ViewSpec.HtmlSpec html = assertInstanceOf(ViewSpec.HtmlSpec.class, envelope.spec());
        assertEquals("html", html.kind());
        assertEquals("<h1>Hi</h1>", html.html());
        assertEquals("", html.url());
        assertEquals(3, html.state().get("count"));
        assertEquals(Boolean.TRUE, html.state().get("active"));
        assertTrue(html.state().containsKey("empty"));
        assertNull(html.state().get("empty"));
        assertEquals(Arrays.asList("a", 2, null), html.state().get("tags"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) html.state().get("meta");
        assertEquals("equimacs", meta.get("owner"));

        LiveBinding live = envelope.live();
        assertNotNull(live);
        assertEquals(List.of("LogpointHit", "TestRunFinished"), live.events());
        assertTrue(live.pushToHtml());
        assertEquals(200, live.debounceMs());
        ViewAction.ToolkitView onEv = assertInstanceOf(ViewAction.ToolkitView.class, live.onEvent());
        assertEquals("trace", onEv.tool());

        String htmlUrlJson = """
            {
              "kind": "html",
              "html": "<p>ignored fallback</p>",
              "url": "https://example.local/panel"
            }
            """;
        ViewSpecParser.ParsedView urlEnv = ViewSpecParser.parseEnvelope(htmlUrlJson);
        assertNull(urlEnv.live());
        ViewSpec.HtmlSpec urlHtml = assertInstanceOf(ViewSpec.HtmlSpec.class, urlEnv.spec());
        assertEquals("<p>ignored fallback</p>", urlHtml.html());
        assertEquals("https://example.local/panel", urlHtml.url());

        String splitJson = """
            {
              "kind": "split",
              "title": "Workspace Split",
              "orientation": "vertical",
              "weights": [30, 70],
              "panes": [
                { "kind": "markdown", "content": "Left" },
                { "kind": "html", "html": "<div>Right</div>" }
              ]
            }
            """;
        ViewSpec.SplitSpec split = assertInstanceOf(ViewSpec.SplitSpec.class, ViewSpecParser.parse(splitJson));
        assertEquals("split", split.kind());
        assertEquals("vertical", split.orientation());
        assertEquals(List.of(30, 70), split.weights());
        assertEquals(2, split.panes().size());
        assertEquals("markdown", split.panes().get(0).kind());
        assertEquals("html", split.panes().get(1).kind());

        String formJson = """
            {
              "kind": "form",
              "title": "Watch Expression",
              "description": "Evaluate expression in frame",
              "submitLabel": "Inspect Value",
              "fields": [
                { "name": "expression", "label": "Expression", "type": "text", "defaultValue": "x + 1", "placeholder": "expr", "required": true },
                { "name": "notes", "label": "Notes", "type": "textarea" },
                { "name": "depth", "label": "Depth", "type": "number", "defaultValue": "2", "required": true },
                { "name": "mode", "label": "Mode", "type": "select", "options": ["fast", "deep"], "defaultValue": "fast" },
                { "name": "confirm", "label": "Confirm", "type": "checkbox", "defaultValue": "true", "required": true }
              ],
              "onSubmit": { "type": "inspect", "targetKind": "jdi-value", "frameId": 7, "expression": "placeholder" }
            }
            """;
        ViewSpec.FormSpec form = assertInstanceOf(ViewSpec.FormSpec.class, ViewSpecParser.parse(formJson));
        assertEquals("form", form.kind());
        assertEquals("Inspect Value", form.submitLabel());
        assertEquals(5, form.itemCount());
        assertEquals("select", form.fields().get(3).fieldType());
        assertEquals(List.of("fast", "deep"), form.fields().get(3).options());
        assertInstanceOf(ViewAction.Inspect.class, form.onSubmit());
    }

    @Test
    void validatesAndMergesFormSpecValues() {
        String formJson = """
            {
              "kind": "form",
              "fields": [
                { "name": "expression", "label": "Expression", "type": "text", "required": true },
                { "name": "depth", "label": "Depth", "type": "number", "required": false },
                { "name": "agree", "label": "Agree", "type": "checkbox", "required": true }
              ],
              "onSubmit": { "type": "emit", "name": "eval", "payload": { "source": "form" } }
            }
            """;
        ViewSpec.FormSpec form = assertInstanceOf(ViewSpec.FormSpec.class, ViewSpecParser.parse(formJson));

        FormSpecSupport.ValidationResult invalid = FormSpecSupport.validate(
            form,
            Map.of("expression", " ", "depth", "abc", "agree", "false"));
        assertFalse(invalid.valid());
        assertEquals(3, invalid.errors().size());

        FormSpecSupport.ValidationResult valid = FormSpecSupport.validate(
            form,
            Map.of("expression", "items.size()", "depth", "", "agree", "true"));
        assertTrue(valid.valid());
        assertTrue(valid.errors().isEmpty());

        ViewAction mergedEmit = FormSpecSupport.mergeIntoAction(
            form.onSubmit(),
            Map.of("expression", "items.size()", "depth", "3"));
        ViewAction.Emit emit = assertInstanceOf(ViewAction.Emit.class, mergedEmit);
        assertTrue(emit.payload().contains("\"source\":\"form\""));
        assertTrue(emit.payload().contains("\"expression\":\"items.size()\""));
        assertTrue(emit.payload().contains("\"depth\":\"3\""));

        ViewAction mergedCustom = FormSpecSupport.mergeIntoAction(
            new ViewAction.Custom("ext", "run", "{\"base\":1}"),
            Map.of("k", "v"));
        ViewAction.Custom custom = assertInstanceOf(ViewAction.Custom.class, mergedCustom);
        assertTrue(custom.args().contains("\"base\":1"));
        assertTrue(custom.args().contains("\"k\":\"v\""));

        ViewAction mergedCmd = FormSpecSupport.mergeIntoAction(
            new ViewAction.EclipseCommand("cmd.id", null, -1, Map.of("a", "1")),
            Map.of("b", "2"));
        ViewAction.EclipseCommand cmd = assertInstanceOf(ViewAction.EclipseCommand.class, mergedCmd);
        assertEquals(Map.of("a", "1", "b", "2"), cmd.params());

        ViewAction mergedToolkit = FormSpecSupport.mergeIntoAction(
            new ViewAction.ToolkitView("trace", Map.of("limit", "100")),
            Map.of("viewId", "tv-1"));
        ViewAction.ToolkitView tv = assertInstanceOf(ViewAction.ToolkitView.class, mergedToolkit);
        assertEquals(Map.of("limit", "100", "viewId", "tv-1"), tv.params());

        ViewAction mergedInspect = FormSpecSupport.mergeIntoAction(
            new ViewAction.Inspect("jdi-value", 5L, "oldExpr", "/old/File.java", 10, "insp-1"),
            Map.of("expression", "newExpr", "file", "/new/File.java"));
        ViewAction.Inspect inspect = assertInstanceOf(ViewAction.Inspect.class, mergedInspect);
        assertEquals("newExpr", inspect.expression());
        assertEquals("/new/File.java", inspect.file());
        assertEquals(5L, inspect.frameId());
    }

    @Test
    void injectsHtmlBridgeAndEscapesScriptTags() {
        String stateJson = "{\"msg\":\"</SCRIPT><script>alert(1)</script>\",\"n\":42}";
        String withHead = HtmlBridgeInjector.inject(
            "<!doctype html><html><HEAD><title>T</title></HEAD><body>Hi</body></html>",
            "view-1",
            stateJson);
        int headIdx = withHead.indexOf("<HEAD>");
        int scriptIdx = withHead.indexOf("<script>");
        assertTrue(headIdx >= 0 && scriptIdx > headIdx);
        assertFalse(withHead.toLowerCase(Locale.ROOT).contains("</script><script>"));
        assertTrue(withHead.contains("<\\/script"));
        assertTrue(withHead.contains("window.equimacs"));
        assertTrue(withHead.contains("openFile"));
        assertTrue(withHead.contains("showView"));
        assertTrue(withHead.contains("eclipseCommand"));
        assertTrue(withHead.contains("custom"));
        assertTrue(withHead.contains("inspect"));
        assertTrue(withHead.contains("toolkitView"));
        assertTrue(withHead.contains("emit"));
        assertTrue(withHead.contains("__equimacsRequest"));
        assertTrue(withHead.contains("window.__equimacsResolve"));
        assertTrue(withHead.contains("window.__equimacsPushState"));
        assertTrue(withHead.contains("window.__equimacsPushEvent"));

        String withBodyOnly = HtmlBridgeInjector.inject("<html><body>Hi</BODY></html>", "view-2", "{}");
        assertTrue(withBodyOnly.indexOf("<script>") < withBodyOnly.indexOf("</BODY>"));

        String fragment = HtmlBridgeInjector.inject("<div>Hi</div>", "view-3", null);
        assertTrue(fragment.startsWith("<script>"));

        assertTrue(HtmlBridgeInjector.buildResolveJs(12L, true, "{\"ok\":1}")
            .contains("window.__equimacsResolve(12, true, {\"ok\":1})"));
        assertTrue(HtmlBridgeInjector.buildPushStateJs("{\"x\":2}")
            .contains("window.__equimacsPushState({\"x\":2})"));
        assertTrue(HtmlBridgeInjector.buildPushEventJs("{\"event\":\"Hit\"}")
            .contains("window.__equimacsPushEvent({\"event\":\"Hit\"})"));
    }

    @Test
    void coordinatesLiveViewRefreshesWithDebounceAndHtmlPush() throws InterruptedException {
        LiveViewRefreshCoordinator coordinator = new LiveViewRefreshCoordinator();
        try {
            LiveBinding binding = new LiveBinding(
                List.of("LogpointHit"),
                new ViewAction.ToolkitView("trace", Map.of()),
                true,
                60);
            coordinator.register("trace-view", binding);
            assertTrue(coordinator.isRegistered("trace-view"));

            List<String> htmlPushes = new CopyOnWriteArrayList<>();
            AtomicInteger refreshCount = new AtomicInteger(0);
            CountDownLatch refreshLatch = new CountDownLatch(1);

            JsonObject hit1 = new JsonObject();
            hit1.addProperty("event", "LogpointHit");
            hit1.addProperty("seq", 1);

            JsonObject hit2 = new JsonObject();
            hit2.addProperty("event", "LogpointHit");
            hit2.addProperty("seq", 2);

            JsonObject ignored = new JsonObject();
            ignored.addProperty("event", "ThreadResumed");

            coordinator.onBridgeEvent(
                hit1,
                (id, b) -> {
                    refreshCount.incrementAndGet();
                    refreshLatch.countDown();
                },
                (id, ev) -> htmlPushes.add(id + ":" + ev.get("seq").getAsInt()));
            coordinator.onBridgeEvent(
                hit2,
                (id, b) -> {
                    refreshCount.incrementAndGet();
                    refreshLatch.countDown();
                },
                (id, ev) -> htmlPushes.add(id + ":" + ev.get("seq").getAsInt()));
            coordinator.onBridgeEvent(
                ignored,
                (id, b) -> refreshCount.incrementAndGet(),
                (id, ev) -> htmlPushes.add(id + ":ignored"));

            assertEquals(List.of("trace-view:1", "trace-view:2"), htmlPushes);
            assertTrue(refreshLatch.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, refreshCount.get());

            coordinator.unregister("trace-view");
            assertFalse(coordinator.isRegistered("trace-view"));
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void parsesGraphSpecAndComputesFocusAndLayeredLayouts() {
        String focusJson = """
            {
              "kind": "graph",
              "title": "Call Explorer",
              "layout": "focus",
              "nodes": [
                { "id": "c1", "label": "callerA()", "sublabel": "Service.java:12", "group": "left", "badge": "CALLER" },
                { "id": "c2", "label": "callerB()", "group": "left" },
                {
                  "id": "center",
                  "label": "target()",
                  "sublabel": "Core.java:44",
                  "group": "center",
                  "onSelect": { "type": "emit", "name": "node-selected", "payload": "center" },
                  "onActivate": { "type": "open-file", "file": "/proj/Core.java", "line": 44 },
                  "onSecondary": { "type": "toolkit-view", "tool": "call-explorer", "params": { "file": "/proj/Core.java" } },
                  "secondaryLabel": "Focus Calls"
                },
                { "id": "d1", "label": "calleeA()", "group": "right", "layer": 1 },
                { "id": "d2", "label": "calleeDeep()", "group": "right", "layer": 2 }
              ],
              "edges": [
                { "from": "c1", "to": "center", "label": "calls", "style": "solid" },
                { "from": "c2", "to": "center", "style": "dashed" },
                { "from": "center", "to": "d1" },
                { "from": "d1", "to": "d2" }
              ]
            }
            """;

        ViewSpec.GraphSpec focusSpec = assertInstanceOf(ViewSpec.GraphSpec.class, ViewSpecParser.parse(focusJson));
        assertEquals("graph", focusSpec.kind());
        assertEquals("focus", focusSpec.layout());
        assertEquals(5, focusSpec.itemCount());
        assertEquals("Focus Calls", focusSpec.nodes().get(2).secondaryLabel());

        GraphLayoutCore.GraphLayout focusLayout = GraphLayoutCore.layout(focusSpec);
        assertEquals(5, focusLayout.nodes().size());
        assertEquals(4, focusLayout.edges().size());

        GraphLayoutCore.LayoutNode leftNode = findLayoutNode(focusLayout, "c1");
        GraphLayoutCore.LayoutNode centerNode = findLayoutNode(focusLayout, "center");
        GraphLayoutCore.LayoutNode rightNode = findLayoutNode(focusLayout, "d1");
        GraphLayoutCore.LayoutNode deepRightNode = findLayoutNode(focusLayout, "d2");

        assertTrue(leftNode.x() + leftNode.width() < centerNode.x());
        assertEquals(0.0, centerNode.x() + centerNode.width() / 2.0, 0.01);
        assertTrue(rightNode.x() > centerNode.x() + centerNode.width());
        assertTrue(deepRightNode.x() > rightNode.x() + rightNode.width());
        assertNoOverlap(focusLayout.nodes());

        for (GraphLayoutCore.LayoutEdge edge : focusLayout.edges()) {
            assertTrue(edge.points().length >= 4 && edge.points().length % 2 == 0);
        }

        ViewSpec.GraphNode hitCenter = GraphLayoutCore.hitTest(
            focusLayout,
            centerNode.x() + centerNode.width() / 2.0,
            centerNode.y() + centerNode.height() / 2.0);
        assertNotNull(hitCenter);
        assertEquals("center", hitCenter.id());
        assertNull(GraphLayoutCore.hitTest(focusLayout, -99999.0, -99999.0));

        GraphLayoutCore.ViewportTransform fit = GraphLayoutCore.computeFitTransform(focusLayout, 800, 600);
        assertTrue(fit.zoom() >= GraphLayoutCore.MIN_ZOOM && fit.zoom() <= GraphLayoutCore.MAX_ZOOM);

        String layeredJson = """
            {
              "kind": "graph",
              "layout": "layered",
              "nodes": [
                { "id": "n0", "label": "Root" },
                { "id": "n1", "label": "Mid" },
                { "id": "n2", "label": "Leaf" }
              ],
              "edges": [
                { "from": "n0", "to": "n1" },
                { "from": "n1", "to": "n2" }
              ]
            }
            """;
        ViewSpec.GraphSpec layeredSpec = assertInstanceOf(ViewSpec.GraphSpec.class, ViewSpecParser.parse(layeredJson));
        GraphLayoutCore.GraphLayout layeredLayout = GraphLayoutCore.layout(layeredSpec);
        GraphLayoutCore.LayoutNode ln0 = findLayoutNode(layeredLayout, "n0");
        GraphLayoutCore.LayoutNode ln1 = findLayoutNode(layeredLayout, "n1");
        GraphLayoutCore.LayoutNode ln2 = findLayoutNode(layeredLayout, "n2");
        assertTrue(ln0.x() + ln0.width() < ln1.x());
        assertTrue(ln1.x() + ln1.width() < ln2.x());
        assertNoOverlap(layeredLayout.nodes());
    }

    @Test
    void rejectsInvalidSpecsAndExceededLayoutDepth() {
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse("{\"kind\":\"html\"}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"html\",\"html\":\"   \",\"url\":\"\"}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"split\",\"orientation\":\"diagonal\",\"panes\":[{\"kind\":\"markdown\",\"content\":\"a\"}]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"split\",\"panes\":[]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"split\",\"weights\":[1,2],\"panes\":[{\"kind\":\"markdown\",\"content\":\"a\"}]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"split\",\"weights\":[0],\"panes\":[{\"kind\":\"markdown\",\"content\":\"a\"}]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"form\",\"fields\":[{\"name\":\"x\",\"type\":\"date\"}],\"onSubmit\":{\"type\":\"emit\",\"name\":\"go\"}}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"form\",\"fields\":[{\"name\":\"x\",\"type\":\"text\"}]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"graph\",\"layout\":\"radial\",\"nodes\":[]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse(
                "{\"kind\":\"graph\",\"layout\":\"layered\",\"nodes\":[{\"id\":\"dup\"},{\"id\":\"dup\"}]}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewSpecParser.parse("{\"kind\":\"table\",\"toolbar\":[{\"label\":\"Bad\",\"action\":{\"type\":\"inspect\",\"targetKind\":\"unknown\"}}]}"));

        // Programmatic layered GraphSpec with duplicate IDs must not spin forever.
        ViewSpec.GraphSpec dupLayered = new ViewSpec.GraphSpec(
            "Dup",
            "layered",
            List.of(
                new ViewSpec.GraphNode("dup", "A", "", "center", 0, "", null, null, null, ""),
                new ViewSpec.GraphNode("dup", "B", "", "center", 0, "", null, null, null, "")),
            List.of(),
            List.of());
        GraphLayoutCore.GraphLayout dupLayout = GraphLayoutCore.layout(dupLayered);
        assertEquals(2, dupLayout.nodes().size());

        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse("{\"kind\":\"unknown\"}"));
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse("not-json"));
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse("  "));

        String tooDeepTabs = """
            { "kind": "tabs", "tabs": [{ "label": "1", "content":
              { "kind": "tabs", "tabs": [{ "label": "2", "content":
                { "kind": "tabs", "tabs": [{ "label": "3", "content":
                  { "kind": "tabs", "tabs": [{ "label": "4", "content":
                    { "kind": "tabs", "tabs": [{ "label": "5", "content":
                      { "kind": "tabs", "tabs": [{ "label": "6", "content":
                        { "kind": "markdown", "content": "too deep" }
                      }]}
                    }]}
                  }]}
                }]}
              }]}
            }]}
            """;
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse(tooDeepTabs));

        String tooDeepMixedSplitAndTabs = """
            { "kind": "split", "panes": [
              { "kind": "tabs", "tabs": [{ "label": "2", "content":
                { "kind": "split", "panes": [
                  { "kind": "tabs", "tabs": [{ "label": "4", "content":
                    { "kind": "split", "panes": [
                      { "kind": "tabs", "tabs": [{ "label": "6", "content":
                        { "kind": "markdown", "content": "too deep" }
                      }]}
                    ]}
                  }]}
                ]}
              }]}
            ]}
            """;
        assertThrows(IllegalArgumentException.class, () -> ViewSpecParser.parse(tooDeepMixedSplitAndTabs));
    }

    @Test
    void stylesMarkdownHeadingsBulletsInlineFormattingCodeBlocksAndLinks() {
        String md = """
            # Title
            ## Subtitle
            ### Section
            - First **bold** and *italic* and `inline()`
            * Second [App.java](/proj/missing-scheme) and [Jump](file:/proj/App.java:42-55)
            - Win [WinFile](file:C:\\proj\\Win.java:10) and [WinOnly](file:C:\\proj\\Win.java)
            - Actions [Run](action:rerun:fast) [Plain](action:ping) [Cmd](command:org.eclipse.ui.file.save) [View](view:org.eclipse.ui.views.ProblemView)
            ```java
            if (*raw* && `code`) {}
            ```
            """;

        MarkdownStyler.StyledDocument doc = MarkdownStyler.parse(md);
        String text = doc.plainText();
        assertTrue(text.contains("Title\nSubtitle\nSection\n"));
        assertTrue(text.contains("\u2022 First bold and italic and inline()\n"));
        assertTrue(text.contains("if (*raw* && `code`) {}"));

        assertHasSpan(doc, text, MarkdownStyler.SpanKind.HEADING_1, "Title");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.HEADING_2, "Subtitle");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.HEADING_3, "Section");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.BOLD, "bold");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.ITALIC, "italic");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.INLINE_CODE, "inline()");
        assertHasSpan(doc, text, MarkdownStyler.SpanKind.CODE_BLOCK, "if (*raw* && `code`) {}");

        assertEquals(8, doc.links().size());

        MarkdownStyler.LinkSpan unknown = doc.links().get(0);
        assertEquals("App.java", text.substring(unknown.start(), unknown.start() + unknown.length()));
        assertEquals(new ViewAction.Emit("link", "/proj/missing-scheme"), unknown.action());

        MarkdownStyler.LinkSpan posixRange = doc.links().get(1);
        assertEquals("Jump", posixRange.label());
        assertEquals(new ViewAction.OpenFile("/proj/App.java", 42, 55), posixRange.action());

        MarkdownStyler.LinkSpan winLine = doc.links().get(2);
        assertEquals(new ViewAction.OpenFile("C:\\proj\\Win.java", 10, 10), winLine.action());

        MarkdownStyler.LinkSpan winPlain = doc.links().get(3);
        assertEquals(new ViewAction.OpenFile("C:\\proj\\Win.java", -1, -1), winPlain.action());

        assertEquals(new ViewAction.Emit("rerun", "fast"), doc.links().get(4).action());
        assertEquals(new ViewAction.Emit("ping", null), doc.links().get(5).action());
        assertEquals(
            new ViewAction.EclipseCommand("org.eclipse.ui.file.save", null, -1, Map.of()),
            doc.links().get(6).action());
        assertEquals(
            new ViewAction.ShowView("org.eclipse.ui.views.ProblemView", null),
            doc.links().get(7).action());
    }

    @Test
    void validatesViewIdsManagesStoreLifecycleAndFormatsSummaryMap() {
        assertEquals("coverage-1.0_a", ViewHostStore.validateViewId("  coverage-1.0_a  "));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId(null));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId(""));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId("   "));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId("bad:id"));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId("bad id"));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId("bad/id"));
        assertThrows(IllegalArgumentException.class, () -> ViewHostStore.validateViewId("x".repeat(129)));

        ViewHostStore store = new ViewHostStore();
        ViewSpec specB = new ViewSpec.MarkdownSpec("", "line1\nline2", List.of());
        ViewSpec specA = new ViewSpec.MarkdownSpec("Spec A Title", "hello", List.of());
        LiveBinding liveB = new LiveBinding(List.of("LogpointHit", "TestRunFinished"), null, true, 120);

        ViewHostStore.HostedView viewB = store.put("beta", null, specB);
        assertEquals("beta", viewB.title());
        assertNull(viewB.live());

        ViewHostStore.HostedView viewA = store.put("alpha", "Override A", specA);
        assertEquals("Override A", viewA.title());

        assertThrows(IllegalArgumentException.class, () -> store.update("missing", null, specA));

        ViewHostStore.HostedView updatedB = store.update("beta", "Updated Beta", specA, liveB);
        assertEquals("Updated Beta", updatedB.title());
        assertEquals("hello", ((ViewSpec.MarkdownSpec) updatedB.spec()).content());
        assertEquals(liveB, updatedB.live());

        List<ViewHostStore.HostedView> all = store.listAll();
        assertEquals(List.of("alpha", "beta"), all.stream().map(ViewHostStore.HostedView::viewId).toList());

        Map<String, Object> summary = ViewHostStore.toSummaryMap(updatedB, true);
        assertEquals("beta", summary.get("viewId"));
        assertEquals("Updated Beta", summary.get("title"));
        assertEquals("markdown", summary.get("kind"));
        assertEquals(1, summary.get("itemCount"));
        assertEquals(true, summary.get("open"));
        assertEquals(List.of("LogpointHit", "TestRunFinished"), summary.get("liveEvents"));
        assertNotNull(summary.get("updatedAtMs"));

        assertNotNull(store.remove("alpha"));
        assertNull(store.get("alpha"));
        assertFalse(store.listAll().isEmpty());
    }

    private static GraphLayoutCore.LayoutNode findLayoutNode(GraphLayoutCore.GraphLayout layout, String id) {
        for (GraphLayoutCore.LayoutNode ln : layout.nodes()) {
            if (id.equals(ln.node().id())) {
                return ln;
            }
        }
        throw new AssertionError("Missing graph layout node: " + id);
    }

    private static void assertNoOverlap(List<GraphLayoutCore.LayoutNode> nodes) {
        List<GraphLayoutCore.LayoutNode> list = new ArrayList<>(nodes);
        for (int i = 0; i < list.size(); i++) {
            GraphLayoutCore.LayoutNode a = list.get(i);
            for (int j = i + 1; j < list.size(); j++) {
                GraphLayoutCore.LayoutNode b = list.get(j);
                boolean separated = (a.x() + a.width() <= b.x())
                    || (b.x() + b.width() <= a.x())
                    || (a.y() + a.height() <= b.y())
                    || (b.y() + b.height() <= a.y());
                assertTrue(
                    separated,
                    "Nodes overlap: " + a.node().id() + " and " + b.node().id());
            }
        }
    }

    private static void assertHasSpan(
            MarkdownStyler.StyledDocument doc,
            String text,
            MarkdownStyler.SpanKind kind,
            String expectedSubstring) {
        boolean found = false;
        for (MarkdownStyler.TextSpan span : doc.spans()) {
            if (span.kind() == kind) {
                String actual = text.substring(span.start(), span.start() + span.length());
                if (expectedSubstring.equals(actual)) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Expected span " + kind + " for '" + expectedSubstring + "' in spans: " + doc.spans());
    }
}
