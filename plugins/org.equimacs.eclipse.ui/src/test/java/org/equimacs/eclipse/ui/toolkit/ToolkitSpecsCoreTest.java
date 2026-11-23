package org.equimacs.eclipse.ui.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.equimacs.eclipse.ui.viewhost.GraphLayoutCore;
import org.equimacs.eclipse.ui.viewhost.LiveBinding;
import org.equimacs.eclipse.ui.viewhost.MarkdownStyler;
import org.equimacs.eclipse.ui.viewhost.ViewAction;
import org.equimacs.eclipse.ui.viewhost.ViewSpec;
import org.equimacs.eclipse.ui.viewhost.ViewSpecParser;
import org.junit.jupiter.api.Test;

final class ToolkitSpecsCoreTest {

    @Test
    void callExplorerTreeModeFiltersJdkMarksCyclesAndParsesCleanly() {
        JsonObject callers = JsonParser.parseString("""
            {
              "target": {
                "name": "process",
                "kind": "method",
                "file": "/proj/src/App.java",
                "declaringType": "demo.App",
                "line": 20
              },
              "direction": "callers",
              "depth": 3,
              "nodes": [
                {
                  "element": {
                    "name": "main",
                    "kind": "method",
                    "file": "/proj/src/Main.java",
                    "declaringType": "demo.Main"
                  },
                  "file": "/proj/src/Main.java",
                  "line": 10,
                  "offset": 120,
                  "snippet": "app.process();",
                  "children": [
                    {
                      "element": {
                        "name": "invoke",
                        "kind": "method",
                        "file": null,
                        "declaringType": "java.lang.reflect.Method"
                      },
                      "file": null,
                      "line": -1,
                      "offset": -1,
                      "snippet": "",
                      "children": []
                    }
                  ]
                }
              ]
            }
            """).getAsJsonObject();

        String viewId = CallExplorerSpecBuilder.defaultViewId("C:\\proj\\My File.java", 42);
        assertTrue(viewId.startsWith("call-explorer-"));
        assertTrue(viewId.matches("^[A-Za-z0-9._-]+$"));
        assertTrue(viewId.length() <= 128);

        CallExplorerSpecBuilder.CallExplorerOptions opts = new CallExplorerSpecBuilder.CallExplorerOptions(
            "/proj/src/App.java", 250, "callers", 99, true, "tree", null);
        String json = CallExplorerSpecBuilder.buildSpecJson(opts, callers, null);

        ViewSpecParser.ParsedView parsed = ViewSpecParser.parseEnvelope(json);
        assertNull(parsed.live());
        ViewSpec.TreeSpec tree = assertInstanceOf(ViewSpec.TreeSpec.class, parsed.spec());
        assertTrue(tree.expandAll());
        assertEquals(List.of("Method", "Type", "Location", "Snippet"), tree.columns());
        assertTrue(tree.title().contains("d=5"));
        assertTrue(tree.title().contains("1 JDK filtered"));

        assertEquals(1, tree.roots().size());
        ViewSpec.TreeNode mainNode = tree.roots().get(0);
        assertEquals("main", mainNode.label());
        assertEquals(List.of("Main", "Main.java:10", "app.process();"), mainNode.values());
        assertTrue(mainNode.children().isEmpty());
        ViewAction.OpenFile openMain = assertInstanceOf(ViewAction.OpenFile.class, mainNode.onActivate());
        assertEquals("/proj/src/Main.java", openMain.file());
        assertEquals(10, openMain.line());

        // Toolbar has toolkit-view controls and Inspect Symbol action.
        assertEquals(9, tree.toolbar().size());
        ViewSpec.ActionItem inspectItem = tree.toolbar().get(8);
        assertEquals("Inspect Symbol", inspectItem.label());
        ViewAction.Inspect inspectAction = assertInstanceOf(ViewAction.Inspect.class, inspectItem.action());
        assertEquals("jdt-symbol", inspectAction.targetKind());
        assertEquals("/proj/src/App.java", inspectAction.file());
        assertEquals(250, inspectAction.offset());

        ViewAction.ToolkitView depth1Action =
            assertInstanceOf(ViewAction.ToolkitView.class, tree.toolbar().get(3).action());
        assertEquals("call-explorer", depth1Action.tool());
        assertEquals("1", depth1Action.params().get("depth"));
    }

    @Test
    void callExplorerHybridModeBuildsNativeSplitWithFocusGraphAndRecursion() {
        JsonObject callers = JsonParser.parseString("""
            {
              "target": {
                "name": "compute",
                "kind": "method",
                "file": "/proj/src/Calc.java",
                "declaringType": "demo.Calc",
                "line": 30
              },
              "direction": "callers",
              "depth": 3,
              "nodes": [
                {
                  "element": { "name": "entry", "kind": "method", "file": "/proj/src/Main.java", "declaringType": "demo.Main" },
                  "file": "/proj/src/Main.java",
                  "line": 14,
                  "offset": 200,
                  "snippet": "calc.compute();",
                  "children": [
                    {
                      "element": { "name": "bootstrap", "kind": "method", "file": "/proj/src/Boot.java", "declaringType": "demo.Boot" },
                      "file": "/proj/src/Boot.java",
                      "line": 5,
                      "offset": 55,
                      "snippet": "Main.entry();",
                      "children": []
                    }
                  ]
                }
              ]
            }
            """).getAsJsonObject();

        JsonObject callees = JsonParser.parseString("""
            {
              "target": {
                "name": "compute",
                "kind": "method",
                "file": "/proj/src/Calc.java",
                "declaringType": "demo.Calc",
                "line": 30
              },
              "direction": "callees",
              "depth": 3,
              "nodes": [
                {
                  "element": { "name": "step", "kind": "method", "file": "/proj/src/Calc.java", "declaringType": "demo.Calc" },
                  "file": "/proj/src/Calc.java",
                  "line": 40,
                  "offset": 410,
                  "snippet": "step();",
                  "children": [
                    {
                      "element": { "name": "compute", "kind": "method", "file": "/proj/src/Calc.java", "declaringType": "demo.Calc" },
                      "file": "/proj/src/Calc.java",
                      "line": 52,
                      "offset": 520,
                      "snippet": "compute();",
                      "children": []
                    }
                  ]
                },
                {
                  "element": { "name": "println", "kind": "method", "file": null, "declaringType": "java.io.PrintStream" },
                  "file": null,
                  "line": -1,
                  "offset": -1,
                  "snippet": "System.out.println()",
                  "children": []
                }
              ]
            }
            """).getAsJsonObject();

        CallExplorerSpecBuilder.CallExplorerOptions opts = new CallExplorerSpecBuilder.CallExplorerOptions(
            "/proj/src/Calc.java", 300, "both", 3, true, "hybrid", "ce-hybrid");
        String json = CallExplorerSpecBuilder.buildSpecJson(opts, callers, callees);

        ViewSpecParser.ParsedView parsed = ViewSpecParser.parseEnvelope(json);
        ViewSpec.SplitSpec split = assertInstanceOf(ViewSpec.SplitSpec.class, parsed.spec());
        assertEquals("horizontal", split.orientation());
        assertEquals(List.of(55, 45), split.weights());
        assertEquals(2, split.panes().size());

        ViewSpec.TreeSpec tree = assertInstanceOf(ViewSpec.TreeSpec.class, split.panes().get(0));
        assertEquals(2, tree.roots().size());
        assertEquals("CALLERS", tree.roots().get(0).badge());
        assertEquals("CALLEES", tree.roots().get(1).badge());

        ViewSpec.TreeNode stepTreeNode = tree.roots().get(1).children().get(0);
        assertEquals("step", stepTreeNode.label());
        ViewSpec.TreeNode recTreeNode = stepTreeNode.children().get(0);
        assertEquals("RECURSIVE", recTreeNode.badge());

        // Hybrid pane 1 must be native GraphSpec (not HtmlSpec).
        ViewSpec.GraphSpec graph = assertInstanceOf(ViewSpec.GraphSpec.class, split.panes().get(1));
        assertEquals("focus", graph.layout());
        assertEquals("Call Focus Graph", graph.title());

        ViewSpec.GraphNode focusNode = findGraphNode(graph, "n_focus");
        assertEquals("center", focusNode.group());
        assertEquals(0, focusNode.layer());
        assertEquals("FOCUS", focusNode.badge());
        assertNotNull(focusNode.onActivate());
        assertNotNull(focusNode.onSecondary());
        assertEquals("Focus in Call Explorer", focusNode.secondaryLabel());

        ViewSpec.GraphNode callerNode = findGraphNodeByLabel(graph, "entry");
        assertEquals("left", callerNode.group());
        assertEquals(-1, callerNode.layer());
        assertEquals("CALLER", callerNode.badge());

        ViewSpec.GraphNode deeperCaller = findGraphNodeByLabel(graph, "bootstrap");
        assertEquals("left", deeperCaller.group());
        assertEquals(-2, deeperCaller.layer());

        ViewSpec.GraphNode calleeNode = findGraphNodeByLabel(graph, "step");
        assertEquals("right", calleeNode.group());
        assertEquals(1, calleeNode.layer());
        assertEquals("CALLEE", calleeNode.badge());
        ViewAction.ToolkitView secondaryAction =
            assertInstanceOf(ViewAction.ToolkitView.class, calleeNode.onSecondary());
        assertEquals("call-explorer", secondaryAction.tool());
        assertEquals("hybrid", secondaryAction.params().get("viewMode"));
        assertEquals("410", secondaryAction.params().get("offset"));

        ViewSpec.GraphNode recursiveNode = graph.nodes().stream()
            .filter(n -> "RECURSIVE".equals(n.badge()))
            .findFirst()
            .orElse(null);
        assertNotNull(recursiveNode);
        assertEquals("right", recursiveNode.group());
        assertEquals(2, recursiveNode.layer());

        // Verify caller -> callee edge directions and dashed recursive edge.
        assertTrue(graph.edges().stream().anyMatch(e ->
            e.from().equals(callerNode.id()) && e.to().equals("n_focus") && "solid".equals(e.style())));
        assertTrue(graph.edges().stream().anyMatch(e ->
            e.from().equals(deeperCaller.id()) && e.to().equals(callerNode.id()) && "solid".equals(e.style())));
        assertTrue(graph.edges().stream().anyMatch(e ->
            e.from().equals("n_focus") && e.to().equals(calleeNode.id()) && "solid".equals(e.style())));
        assertTrue(graph.edges().stream().anyMatch(e ->
            e.from().equals(calleeNode.id()) && e.to().equals(recursiveNode.id()) && "dashed".equals(e.style())));

        // Also ensure GraphLayoutCore lays out the spec cleanly.
        GraphLayoutCore.GraphLayout laidOut = GraphLayoutCore.layout(graph);
        assertEquals(graph.nodes().size(), laidOut.nodes().size());
    }

    @Test
    void executionTraceBuildsTableSnapshotTimelineAndLiveBinding() {
        JsonObject tracePayload = JsonParser.parseString("""
            {
              "count": 2,
              "totalRecorded": 5,
              "entries": [
                {
                  "seq": 4,
                  "timestampMs": 1700000000100,
                  "resource": "/proj/src/Worker.java",
                  "file": "Worker.java",
                  "class": "demo.Worker",
                  "method": "step",
                  "line": 22,
                  "threadId": 1,
                  "threadName": "main",
                  "template": "i={i}",
                  "message": "i=1",
                  "evaluations": { "i": { "value": "1", "type": "int", "isNull": false } },
                  "variables": [ { "name": "i", "type": "int", "value": "1" } ]
                },
                {
                  "seq": 5,
                  "timestampMs": 1700000000250,
                  "resource": "/proj/src/Worker.java",
                  "file": "Worker.java",
                  "class": "demo.Worker",
                  "method": "finish",
                  "line": 48,
                  "threadId": 1,
                  "threadName": "main",
                  "template": "total={sum}",
                  "message": "total=42",
                  "evaluations": {
                    "sum": { "value": "42", "type": "int", "isNull": false },
                    "bad": { "error": "Cannot resolve" }
                  },
                  "variables": [
                    { "name": "sum", "type": "int", "value": "42" }
                  ]
                }
              ]
            }
            """).getAsJsonObject();

        assertEquals("execution-trace", ExecutionTraceSpecBuilder.defaultViewId());
        ExecutionTraceSpecBuilder.TraceViewOptions opts =
            new ExecutionTraceSpecBuilder.TraceViewOptions(200, true, null);
        String json = ExecutionTraceSpecBuilder.buildSpecJson(opts, tracePayload);

        ViewSpecParser.ParsedView parsed = ViewSpecParser.parseEnvelope(json);
        LiveBinding live = parsed.live();
        assertNotNull(live);
        assertEquals(List.of("LogpointHit"), live.events());
        assertFalse(live.pushToHtml());
        assertEquals(200, live.debounceMs());
        ViewAction.ToolkitView liveAction = assertInstanceOf(ViewAction.ToolkitView.class, live.onEvent());
        assertEquals("trace", liveAction.tool());
        assertEquals("200", liveAction.params().get("limit"));
        assertEquals("true", liveAction.params().get("live"));
        assertEquals("execution-trace", liveAction.params().get("viewId"));

        ViewSpec.SplitSpec split = assertInstanceOf(ViewSpec.SplitSpec.class, parsed.spec());
        assertEquals("vertical", split.orientation());
        assertEquals(6, split.toolbar().size());
        assertEquals("Refresh", split.toolbar().get(0).label());
        assertEquals("Clear Trace", split.toolbar().get(1).label());
        assertEquals("Live: ON", split.toolbar().get(5).label());

        ViewSpec.TableSpec table = assertInstanceOf(ViewSpec.TableSpec.class, split.panes().get(0));
        assertEquals(List.of("#", "Time", "Location", "Method", "Message", "Thread"), table.columns());
        assertEquals(2, table.rows().size());
        assertEquals("4", table.rows().get(0).cells().get(0));
        assertEquals("Worker.java:22", table.rows().get(0).cells().get(2));
        assertEquals("Worker.step", table.rows().get(0).cells().get(3));
        assertEquals("i=1", table.rows().get(0).cells().get(4));
        ViewAction.OpenFile rowOpen = assertInstanceOf(ViewAction.OpenFile.class, table.rows().get(1).onActivate());
        assertEquals("/proj/src/Worker.java", rowOpen.file());
        assertEquals(48, rowOpen.line());

        ViewSpec.TabsSpec tabs = assertInstanceOf(ViewSpec.TabsSpec.class, split.panes().get(1));
        assertEquals(2, tabs.tabs().size());
        assertEquals("Latest Snapshot", tabs.tabs().get(0).label());
        assertEquals("Timeline", tabs.tabs().get(1).label());

        ViewSpec.KeyValueSpec snapshot = assertInstanceOf(ViewSpec.KeyValueSpec.class, tabs.tabs().get(0).content());
        assertEquals(3, snapshot.sections().size());
        assertEquals("Evaluations", snapshot.sections().get(1).heading());
        assertEquals("42 (int)", snapshot.sections().get(1).entries().get(0).value());
        assertEquals("ERROR: Cannot resolve", snapshot.sections().get(1).entries().get(1).value());
        assertEquals("Variables", snapshot.sections().get(2).heading());

        ViewSpec.MarkdownSpec timeline = assertInstanceOf(ViewSpec.MarkdownSpec.class, tabs.tabs().get(1).content());
        MarkdownStyler.StyledDocument styledTimeline = MarkdownStyler.parse(timeline.content());
        assertEquals(2, styledTimeline.links().size());
        ViewAction.OpenFile linkOpen =
            assertInstanceOf(ViewAction.OpenFile.class, styledTimeline.links().get(1).action());
        assertEquals("/proj/src/Worker.java", linkOpen.file());
        assertEquals(48, linkOpen.line());
    }

    @Test
    void junitViewBuildsEmptyStateAndGroupedResultsWithFailuresAndLiveBinding() {
        assertEquals("junit-results", JUnitViewSpecBuilder.defaultViewId());

        JUnitViewSpecBuilder.JUnitViewOptions emptyOpts =
            new JUnitViewSpecBuilder.JUnitViewOptions(null, false, true, null);
        String emptyJson = JUnitViewSpecBuilder.buildSpecJson(emptyOpts, null);
        ViewSpecParser.ParsedView emptyParsed = ViewSpecParser.parseEnvelope(emptyJson);
        assertNotNull(emptyParsed.live());
        assertEquals(List.of("TestRunFinished"), emptyParsed.live().events());
        ViewSpec.MarkdownSpec emptyMd = assertInstanceOf(ViewSpec.MarkdownSpec.class, emptyParsed.spec());
        assertTrue(emptyMd.content().contains("eqm-cli run-tests"));
        assertEquals("Refresh", emptyMd.toolbar().get(0).label());

        JsonObject resultsPayload = JsonParser.parseString("""
            {
              "runId": "test_3",
              "name": "equimacs-junit-test_3",
              "project": "myproject",
              "status": "FAILED",
              "startedAtMs": 1700000000000,
              "finishedAtMs": 1700000001250,
              "elapsedSeconds": 1.25,
              "total": 3,
              "passed": 1,
              "failed": 1,
              "errors": 0,
              "ignored": 1,
              "tests": [
                {
                  "class": "demo.CalcTest",
                  "method": "testAdd",
                  "outcome": "PASSED",
                  "elapsedSeconds": 0.10
                },
                {
                  "class": "demo.CalcTest",
                  "method": "testDivide",
                  "outcome": "FAILED",
                  "elapsedSeconds": 0.45,
                  "failure": {
                    "summary": "expected: <5> but was: <4>",
                    "expected": "5",
                    "actual": "4",
                    "trace": "org.opentest4j.AssertionFailedError: expected: <5> but was: <4>\\n\\tat demo.CalcTest.testDivide(CalcTest.java:28)",
                    "frames": [
                      {
                        "class": "demo.CalcTest",
                        "method": "testDivide",
                        "file": "CalcTest.java",
                        "line": 28,
                        "raw": "at demo.CalcTest.testDivide(CalcTest.java:28)"
                      }
                    ]
                  }
                },
                {
                  "class": "demo.ExtraTest",
                  "method": "testSkipped",
                  "outcome": "IGNORED",
                  "elapsedSeconds": 0.0
                }
              ]
            }
            """).getAsJsonObject();

        JUnitViewSpecBuilder.JUnitViewOptions opts =
            new JUnitViewSpecBuilder.JUnitViewOptions("test_3", false, true, null);
        String json = JUnitViewSpecBuilder.buildSpecJson(opts, resultsPayload);

        ViewSpecParser.ParsedView parsed = ViewSpecParser.parseEnvelope(json);
        assertNotNull(parsed.live());
        assertEquals(List.of("TestRunFinished"), parsed.live().events());
        ViewAction.ToolkitView liveEventAction =
            assertInstanceOf(ViewAction.ToolkitView.class, parsed.live().onEvent());
        assertEquals("junit", liveEventAction.tool());
        assertEquals("false", liveEventAction.params().get("failuresOnly"));

        ViewSpec.SplitSpec split = assertInstanceOf(ViewSpec.SplitSpec.class, parsed.spec());
        assertEquals("horizontal", split.orientation());
        assertEquals(List.of(55, 45), split.weights());
        assertEquals("Refresh", split.toolbar().get(0).label());
        assertEquals("Failures Only", split.toolbar().get(1).label());
        assertEquals("Rerun Suite", split.toolbar().get(2).label());
        ViewAction.ToolkitView rerunAction =
            assertInstanceOf(ViewAction.ToolkitView.class, split.toolbar().get(2).action());
        assertEquals("junit-rerun", rerunAction.tool());
        assertEquals("myproject", rerunAction.params().get("project"));

        ViewSpec.TreeSpec tree = assertInstanceOf(ViewSpec.TreeSpec.class, split.panes().get(0));
        assertEquals(2, tree.roots().size());
        ViewSpec.TreeNode calcClass = tree.roots().get(0);
        assertEquals("CalcTest", calcClass.label());
        assertTrue(calcClass.badge().contains("1/2 PASS"));
        assertTrue(calcClass.badge().contains("1 FAIL"));
        assertEquals("PASS", calcClass.children().get(0).badge());
        assertEquals("FAIL", calcClass.children().get(1).badge());
        assertEquals("IGNORED", tree.roots().get(1).children().get(0).badge());

        ViewSpec.TreeNode failedMethod = calcClass.children().get(1);
        assertEquals(4, failedMethod.children().size());
        ViewSpec.TreeNode frameNode = failedMethod.children().get(3);
        ViewAction.OpenFile frameOpen = assertInstanceOf(ViewAction.OpenFile.class, frameNode.onActivate());
        assertEquals("/myproject/src/demo/CalcTest.java", frameOpen.file());
        assertEquals(28, frameOpen.line());

        ViewSpec.TabsSpec tabs = assertInstanceOf(ViewSpec.TabsSpec.class, split.panes().get(1));
        assertEquals("Summary", tabs.tabs().get(0).label());
        assertEquals("Failures", tabs.tabs().get(1).label());

        ViewSpec.KeyValueSpec summary = assertInstanceOf(ViewSpec.KeyValueSpec.class, tabs.tabs().get(0).content());
        Map<String, String> summaryMap = new java.util.LinkedHashMap<>();
        for (ViewSpec.KvEntry entry : summary.sections().get(0).entries()) {
            summaryMap.put(entry.key(), entry.value());
        }
        assertEquals("test_3", summaryMap.get("Run ID"));
        assertEquals("myproject", summaryMap.get("Project"));
        assertEquals("FAILED", summaryMap.get("Status"));
        assertEquals("3", summaryMap.get("Total"));

        ViewSpec.MarkdownSpec failuresMd = assertInstanceOf(ViewSpec.MarkdownSpec.class, tabs.tabs().get(1).content());
        assertTrue(failuresMd.content().contains("demo.CalcTest#testDivide (FAIL)"));
        assertTrue(failuresMd.content().contains("**Expected:** `5`"));
        MarkdownStyler.StyledDocument styledFailures = MarkdownStyler.parse(failuresMd.content());
        assertEquals(1, styledFailures.links().size());

        // Failures-only mode filters passing/ignored tests and toggles button label.
        JUnitViewSpecBuilder.JUnitViewOptions failOnlyOpts =
            new JUnitViewSpecBuilder.JUnitViewOptions(null, true, false, null);
        ViewSpecParser.ParsedView failParsed = ViewSpecParser.parseEnvelope(
            JUnitViewSpecBuilder.buildSpecJson(failOnlyOpts, resultsPayload));
        assertNull(failParsed.live());
        ViewSpec.SplitSpec failSplit = assertInstanceOf(ViewSpec.SplitSpec.class, failParsed.spec());
        assertEquals("Show All", failSplit.toolbar().get(1).label());
        ViewSpec.TreeSpec failTree = assertInstanceOf(ViewSpec.TreeSpec.class, failSplit.panes().get(0));
        assertEquals(1, failTree.roots().size());
        assertEquals(1, failTree.roots().get(0).children().size());
    }

    private static ViewSpec.GraphNode findGraphNode(ViewSpec.GraphSpec graph, String id) {
        return graph.nodes().stream()
            .filter(n -> id.equals(n.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing graph node with id=" + id));
    }

    private static ViewSpec.GraphNode findGraphNodeByLabel(ViewSpec.GraphSpec graph, String label) {
        return graph.nodes().stream()
            .filter(n -> label.equals(n.label()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing graph node with label=" + label));
    }
}
