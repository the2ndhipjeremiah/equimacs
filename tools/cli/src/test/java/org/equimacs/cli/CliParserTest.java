package org.equimacs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.equimacs.cli.util.CliArgs;
import org.equimacs.protocol.ProtocolSchema;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliParserTest {
    @Test
    void parsesBreakpointWithCondition() {
        Request req = parse("bp", "C:\\workspace\\App.java:42", "-c", "i > 0");

        Request.SetBreakpoint bp = assertInstanceOf(Request.SetBreakpoint.class, req);
        assertEquals("C:\\workspace\\App.java", bp.path());
        assertEquals(42, bp.line());
        assertEquals("i > 0", bp.condition());
    }

    @Test
    void parsesBreakpointListAliases() {
        assertInstanceOf(Request.ListBreakpoints.class, parse("list"));
        assertInstanceOf(Request.ListBreakpoints.class, parse("bps"));
    }

    @Test
    void parsesBreakpointClear() {
        assertInstanceOf(Request.ClearAllBreakpoints.class, parse("clear"));
    }

    @Test
    void parsesExecutionCommands() {
        assertInstanceOf(Request.Resume.class, parse("resume"));
        assertInstanceOf(Request.Suspend.class, parse("suspend"));
        assertEquals(Request.StepType.OVER, assertInstanceOf(Request.Step.class, parse("step")).stepType());
        assertEquals(Request.StepType.INTO, assertInstanceOf(Request.Step.class, parse("step", "INTO")).stepType());
        assertEquals(Request.StepType.RETURN, assertInstanceOf(Request.Step.class, parse("step", "return")).stepType());
    }

    @Test
    void parsesGogoCommandAsRemainder() {
        Request.GogoExec req = assertInstanceOf(Request.GogoExec.class, parse("gogo", "lb", "org.equimacs"));
        assertEquals("lb org.equimacs", req.command());
    }

    @Test
    void parsesBridgeAndWorkspaceCommands() {
        assertInstanceOf(Request.Reload.class, parse("reload"));
        assertInstanceOf(Request.GetWorkspace.class, parse("workspace"));
        assertEquals(new Request.GetProblems(null, null), parse("problems"));
        assertEquals(new Request.GetProblems("myproject", "error"), parse("problems", "myproject", "-s", "error"));
        assertEquals(new Request.Build(null, null), parse("build"));
        assertEquals(new Request.Build("myproject", "full"), parse("build", "myproject", "-k", "full"));
    }

    @Test
    void parsesProjectConfigCommands() {
        assertEquals(new Request.GetClasspath("myproject"), parse("classpath", "myproject"));
        assertEquals(
            new Request.SetClasspath("myproject", "add", "library", "/myproject/lib/foo.jar", "/myproject/lib/foo-src.jar"),
            parse("set-classpath", "myproject", "add", "library", "/myproject/lib/foo.jar", "--source-attachment", "/myproject/lib/foo-src.jar"));
        assertEquals(
            new Request.SetClasspath("myproject", "remove", null, "/myproject/lib/foo.jar", null),
            parse("set-classpath", "myproject", "remove", "/myproject/lib/foo.jar"));
        assertEquals(
            new Request.SetClasspath("myproject", "remove", "source", "/myproject/test-src", null),
            parse("set-classpath", "myproject", "remove", "source", "/myproject/test-src"));
        assertThrows(CliParseException.class, () -> parse("set-classpath", "myproject", "add", "bogus", "/x"));
        assertEquals(new Request.GetProjectDescription("myproject"), parse("describe", "myproject"));
        assertEquals(new Request.RefreshProject("myproject"), parse("refresh", "myproject"));
    }

    @Test
    void parsesRefactoringCommands() {
        assertEquals(new Request.PrepareRenameSymbol("/myproject/src/App.java", 123, "betterName"),
            parse("refactor-prepare", "rename-symbol", "/myproject/src/App.java:123", "--to", "betterName"));
        assertEquals(new Request.ApplyPreparedRefactoring("rfc_123"), parse("refactor-apply", "rfc_123"));
        assertEquals(new Request.AbortPreparedRefactoring("rfc_123"), parse("refactor-abort", "rfc_123"));
        assertEquals(new Request.GetPreparedRefactoring("rfc_123"), parse("refactor-status", "rfc_123"));
    }

    @Test
    void parsesQuickFixCommands() {
        assertEquals(new Request.GetQuickFixes("/myproject/src/App.java", 7),
            parse("quickfixes", "/myproject/src/App.java:7"));
        assertEquals(new Request.ApplyFix("/myproject/src/App.java", 7, 2),
            parse("applyfix", "/myproject/src/App.java:7", "2"));
    }

    @Test
    void parsesInspectionCommands() {
        assertInstanceOf(Request.GetThreads.class, parse("threads"));
        assertEquals(new Request.GetStack(123), parse("stack", "123"));
        assertEquals(new Request.GetVariables(456), parse("vars", "456"));
    }

    @Test
    void parsesWaitEvent() {
        assertEquals(new Request.WaitEvent(30_000), parse("wait-event"));
        assertEquals(new Request.WaitEvent(250), parse("wait-event", "--timeout", "250"));
        assertEquals(new Request.WaitEvent(500), parse("wait-event", "-t", "500"));
    }

    @Test
    void parsesLaunchAndSessionCommands() {
        assertEquals(new Request.Launch("Hello"), parse("launch", "Hello"));
        assertInstanceOf(Request.ListLaunches.class, parse("list-launches"));
        assertInstanceOf(Request.ListSessions.class, parse("sessions"));
        assertInstanceOf(Request.Terminate.class, parse("terminate"));
        assertInstanceOf(Request.Shutdown.class, parse("shutdown"));
    }

    @Test
    void parsesCustomCommand() {
        Request.CustomCommand withoutArgs = assertInstanceOf(
            Request.CustomCommand.class, parse("custom", "my-ext", "ping"));
        assertEquals("my-ext", withoutArgs.target());
        assertEquals("ping", withoutArgs.action());
        org.junit.jupiter.api.Assertions.assertNull(withoutArgs.args());

        Request.CustomCommand withArgs = assertInstanceOf(
            Request.CustomCommand.class, parse("custom", "my-ext", "echo", "{\"x\":1}"));
        assertEquals("{\"x\":1}", withArgs.args());

        Request.CustomCommand withFlag = assertInstanceOf(
            Request.CustomCommand.class, parse("custom", "my-ext", "echo", "--args", "{\"x\":2}"));
        assertEquals("{\"x\":2}", withFlag.args());
    }

    @Test
    void parsesNavigationAndOpenFileCommands() {
        Request.FindReferences refs = assertInstanceOf(
            Request.FindReferences.class, parse("find-refs", "/proj/src/A.java:42"));
        assertEquals("/proj/src/A.java", refs.file());
        assertEquals(42, refs.offset());

        Request.CallHierarchy calls = assertInstanceOf(
            Request.CallHierarchy.class, parse("call-hierarchy", "/proj/src/A.java:100", "--direction", "callees", "--depth", "3"));
        assertEquals("callees", calls.direction());
        assertEquals(3, calls.depth());

        Request.TypeHierarchy types = assertInstanceOf(
            Request.TypeHierarchy.class, parse("type-hierarchy", "/proj/src/A.java:15"));
        assertEquals(15, types.offset());

        Request.OpenFile openRange = assertInstanceOf(
            Request.OpenFile.class, parse("open-file", "/proj/src/A.java:10-20"));
        assertEquals("/proj/src/A.java", openRange.file());
        assertEquals(10, openRange.line());
        assertEquals(20, openRange.endLine());

        Request.OpenFile openNoLine = assertInstanceOf(
            Request.OpenFile.class, parse("open-file", "C:\\proj\\A.java"));
        assertEquals("C:\\proj\\A.java", openNoLine.file());
        assertEquals(-1, openNoLine.line());
    }

    @Test
    void parsesEvaluateCommand() {
        Request.Evaluate eval = assertInstanceOf(
            Request.Evaluate.class, parse("eval", "12345", "value + next"));
        assertEquals(12345L, eval.frameId());
        assertEquals("value + next", eval.expression());

        Request.Evaluate evaluate = assertInstanceOf(
            Request.Evaluate.class, parse("evaluate", "99", "a", "+", "b"));
        assertEquals(99L, evaluate.frameId());
        assertEquals("a + b", evaluate.expression());
    }

    @Test
    void parsesLogpointAndTraceCommands() {
        Request.SetLogpoint posMsg = assertInstanceOf(
            Request.SetLogpoint.class, parse("logpoint", "/proj/src/App.java:10", "i={i}", "-c", "i > 0"));
        assertEquals("/proj/src/App.java", posMsg.path());
        assertEquals(10, posMsg.line());
        assertEquals("i={i}", posMsg.message());
        assertEquals("i > 0", posMsg.condition());
        assertEquals(Boolean.TRUE, posMsg.captureVars());

        Request.SetLogpoint aliasNoVars = assertInstanceOf(
            Request.SetLogpoint.class, parse("lp", "/proj/src/App.java:12", "-m", "val={x}", "--no-vars"));
        assertEquals("/proj/src/App.java", aliasNoVars.path());
        assertEquals(12, aliasNoVars.line());
        assertEquals("val={x}", aliasNoVars.message());
        org.junit.jupiter.api.Assertions.assertNull(aliasNoVars.condition());
        assertEquals(Boolean.FALSE, aliasNoVars.captureVars());

        Request.SetLogpoint varsFalse = assertInstanceOf(
            Request.SetLogpoint.class, parse("logpoint", "/proj/src/App.java:14", "--vars", "false", "only msg"));
        assertEquals("only msg", varsFalse.message());
        assertEquals(Boolean.FALSE, varsFalse.captureVars());

        Request.SetLogpoint noMsg = assertInstanceOf(
            Request.SetLogpoint.class, parse("logpoint", "/proj/src/App.java:16"));
        org.junit.jupiter.api.Assertions.assertNull(noMsg.message());
        assertEquals(Boolean.TRUE, noMsg.captureVars());

        assertEquals(new Request.GetTrace(100), parse("trace"));
        assertEquals(new Request.GetTrace(25), parse("trace", "--limit", "25"));
        assertEquals(new Request.GetTrace(10), parse("trace", "-n", "10"));
        assertInstanceOf(Request.ClearTrace.class, parse("trace-clear"));
    }

    @Test
    void parsesJUnitTestCommands() {
        Request.RunTests projOnly = assertInstanceOf(
            Request.RunTests.class, parse("run-tests", "myproject"));
        assertEquals("myproject", projOnly.project());
        org.junit.jupiter.api.Assertions.assertNull(projOnly.className());
        org.junit.jupiter.api.Assertions.assertNull(projOnly.methodName());
        org.junit.jupiter.api.Assertions.assertNull(projOnly.mode());
        org.junit.jupiter.api.Assertions.assertNull(projOnly.runner());
        org.junit.jupiter.api.Assertions.assertNull(projOnly.await());
        assertEquals(60_000, projOnly.timeoutMs());

        Request.RunTests methodTarget = assertInstanceOf(
            Request.RunTests.class,
            parse("run-tests", "myproject", "com.example.MathTest#addsNumbers", "--runner", "junit5", "--timeout", "15000"));
        assertEquals("myproject", methodTarget.project());
        assertEquals("com.example.MathTest", methodTarget.className());
        assertEquals("addsNumbers", methodTarget.methodName());
        assertEquals("junit5", methodTarget.runner());
        assertEquals(15_000, methodTarget.timeoutMs());

        Request.RunTests debugNoWait = assertInstanceOf(
            Request.RunTests.class,
            parse("run-tests", "myproject", "com.example.MathTest", "--mode", "debug", "--no-wait"));
        assertEquals("com.example.MathTest", debugNoWait.className());
        assertEquals("debug", debugNoWait.mode());
        assertEquals(Boolean.FALSE, debugNoWait.await());

        Request.RunTests waitFalse = assertInstanceOf(
            Request.RunTests.class,
            parse("run-tests", "myproject", "--wait", "false"));
        assertEquals(Boolean.FALSE, waitFalse.await());

        Request.GetTestResults latest = assertInstanceOf(Request.GetTestResults.class, parse("test-results"));
        org.junit.jupiter.api.Assertions.assertNull(latest.runId());
        assertEquals(Boolean.FALSE, latest.failuresOnly());

        assertEquals(
            new Request.GetTestResults("test_3", Boolean.TRUE),
            parse("test-results", "test_3", "--failures"));
        assertEquals(
            new Request.GetTestResults("test_3", Boolean.TRUE),
            parse("test-results", "--failures-only", "true", "test_3"));
    }

    @Test
    void parsesConsoleCommands() {
        assertEquals(
            new Request.GetConsoleOutput(null, "all", 200),
            parse("console"));
        assertEquals(
            new Request.GetConsoleOutput("Main", "all", 200),
            parse("console", "Main"));
        assertEquals(
            new Request.GetConsoleOutput("Main", "stderr", 50),
            parse("console", "-l", "Main", "--stream", "stderr", "-n", "50"));
        assertEquals(
            new Request.GetConsoleOutput(null, "stdout", 10),
            parse("console", "-s", "out", "--limit", "10"));
        assertThrows(CliParseException.class, () -> parse("console", "--stream", "invalid"));
        assertThrows(CliParseException.class, () -> parse("console", "--limit", "0"));
        assertEquals(new Request.ClearConsoleOutput(), parse("console-clear"));
    }

    @Test
    void parsesEclipseCommand() {
        assertEquals(
            new Request.EclipseCommand("org.eclipse.jdt.ui.edit.text.java.open.call.hierarchy", null, -1, null),
            parse("eclipse-command", "org.eclipse.jdt.ui.edit.text.java.open.call.hierarchy"));
        assertEquals(
            new Request.EclipseCommand("org.eclipse.jdt.ui.edit.text.java.open.call.hierarchy", "/toy-app/src/toy/Pipeline.java", 531, null),
            parse("eclipse-command", "org.eclipse.jdt.ui.edit.text.java.open.call.hierarchy", "/toy-app/src/toy/Pipeline.java:531"));
        assertEquals(
            new Request.EclipseCommand(
                "org.eclipse.ui.views.showView",
                null,
                -1,
                java.util.Map.of("org.eclipse.ui.views.showView.viewId", "org.equimacs.eclipse.ui.reviewView")),
            parse(
                "eclipse-command",
                "org.eclipse.ui.views.showView",
                "--params",
                "{\"org.eclipse.ui.views.showView.viewId\":\"org.equimacs.eclipse.ui.reviewView\"}"));
    }

    @Test
    void parsesShowView() {
        assertEquals(
            new Request.ShowView("org.equimacs.eclipse.ui.reviewView", null),
            parse("show-view", "org.equimacs.eclipse.ui.reviewView"));
        assertEquals(
            new Request.ShowView("org.eclipse.ui.console.ConsoleView", "sec1"),
            parse("show-view", "org.eclipse.ui.console.ConsoleView", "sec1"));
    }

    @Test
    void parsesReviewListFilters() {
        assertEquals(new Request.ListReviewComments(null, null), parse("review-list"));
        assertEquals(
            new Request.ListReviewComments("/proj/src/App.java", null),
            parse("review-list", "/proj/src/App.java"));
        assertEquals(
            new Request.ListReviewComments(null, "changed-files"),
            parse("review-list", "--changed-files"));
        assertEquals(
            new Request.ListReviewComments("/proj/src/App.java", "changed-hunks"),
            parse("review-list", "/proj/src/App.java", "--changed-hunks"));
        assertEquals(
            new Request.ListReviewComments("/proj/src/App.java", "changed-files"),
            parse("review-list", "--filter", "changed-files", "/proj/src/App.java"));
        assertThrows(CliParseException.class, () -> parse("review-list", "--filter", "bogus"));
        assertEquals(new Request.RemoveReviewComment("cmt-123"), parse("review-remove", "cmt-123"));
        assertThrows(CliParseException.class, () -> parse("review-remove"));
    }

    @Test
    void parsesUiInspectionAndDrivingCommands() {
        assertEquals(
            new Request.UiTree("active", 8, Boolean.TRUE, null, null),
            parse("ui-tree"));
        assertEquals(
            new Request.UiTree("view:org.equimacs.eclipse.ui.reviewView", 4, Boolean.FALSE, "Resolve", "Button"),
            parse("ui-tree", "--root", "view:org.equimacs.eclipse.ui.reviewView", "--depth", "4", "--filter", "Resolve", "--type", "Button", "--all"));

        assertEquals(
            new Request.UiClick("w_14", null, null, null, 0, null, Boolean.FALSE),
            parse("ui-click", "w_14"));
        assertEquals(
            new Request.UiClick(null, "dialog", "Tree", "Packages", 1, "src/main/java", Boolean.TRUE),
            parse("ui-click", "--root", "dialog", "--type", "Tree", "--text", "Packages", "--index", "1", "--item", "src/main/java", "--double-click"));

        assertEquals(
            new Request.UiType("w_20", null, null, null, 0, "hello world", Boolean.FALSE, Boolean.FALSE),
            parse("ui-type", "w_20", "hello", "world"));
        assertEquals(
            new Request.UiType(null, "dialog", "Text", "Search", 0, "OrderService", Boolean.TRUE, Boolean.TRUE),
            parse("ui-type", "--root", "dialog", "--type", "Text", "--text", "Search", "--value", "OrderService", "--append", "--submit"));
    }

    @Test
    void parsesModdableViewCommands(@TempDir Path tempDir) throws Exception {
        String inlineSpec = "{\"kind\":\"markdown\",\"content\":\"# Hi\"}";
        assertEquals(
            new Request.ViewOpen("notes", null, inlineSpec),
            parse("view-open", "notes", inlineSpec));
        assertEquals(
            new Request.ViewOpen("metrics", "Metrics", inlineSpec),
            parse("view-open", "metrics", "--title", "Metrics", "--spec", inlineSpec));

        Path specFile = tempDir.resolve("view.json");
        String fileSpec = "{\"kind\":\"table\",\"columns\":[\"A\"],\"rows\":[]}";
        Files.writeString(specFile, fileSpec, StandardCharsets.UTF_8);
        assertEquals(
            new Request.ViewUpdate("metrics", "Updated", fileSpec),
            parse("view-update", "metrics", "--title", "Updated", "--spec-file", specFile.toString()));

        assertEquals(new Request.ViewClose("metrics"), parse("view-close", "metrics"));
        assertEquals(new Request.ViewList(), parse("view-list"));
        assertNull(assertInstanceOf(Request.ViewOpen.class, parse("view-open", "notes", inlineSpec)).title());
    }

    @Test
    void parsesMoldableInspectorCommands() {
        assertEquals(
            new Request.InspectFrame(101L, Boolean.TRUE, null),
            parse("inspect-frame", "101"));
        assertEquals(
            new Request.InspectFrame(101L, Boolean.FALSE, "insp-1"),
            parse("inspect-frame", "101", "--no-open", "--view-id", "insp-1"));
        assertEquals(
            new Request.InspectFrame(101L, Boolean.FALSE, null),
            parse("inspect-frame", "--open", "false", "101"));
        assertEquals(
            new Request.InspectFrame(101L, Boolean.TRUE, null),
            parse("inspect-frame", "--open", "true", "101"));
        assertThrows(CliParseException.class, () -> parse("inspect-frame"));
        assertThrows(CliParseException.class, () -> parse("inspect-frame", "101", "--open", "bogus"));

        assertEquals(
            new Request.InspectValue(42L, "order.getItems()", 2, Boolean.TRUE, null),
            parse("inspect-value", "42", "order.getItems()"));
        assertEquals(
            new Request.InspectValue(42L, "a + b", 2, Boolean.TRUE, null),
            parse("inspect-value", "42", "a", "+", "b"));
        assertEquals(
            new Request.InspectValue(42L, "map", 4, Boolean.FALSE, "val-1"),
            parse("inspect-value", "42", "--expr", "map", "--depth", "4", "--no-open", "--view-id", "val-1"));
        assertEquals(
            new Request.InspectValue(42L, "items", 3, Boolean.FALSE, null),
            parse("inspect-value", "42", "-e", "items", "-d", "3", "--open", "false"));
        assertThrows(CliParseException.class, () -> parse("inspect-value", "42"));
        assertThrows(CliParseException.class, () -> parse("inspect-value", "42", "   "));
        assertThrows(CliParseException.class, () -> parse("inspect-value", "42", "--expr", " "));
        assertThrows(CliParseException.class, () -> parse("inspect-value", "42", "x", "--depth", "0"));

        assertEquals(
            new Request.InspectSymbol("/proj/src/App.java", 120, Boolean.TRUE, null),
            parse("inspect-symbol", "/proj/src/App.java:120"));
        assertEquals(
            new Request.InspectSymbol("C:\\proj\\App.java", 120, Boolean.FALSE, "sym-1"),
            parse("inspect-symbol", "C:\\proj\\App.java:120", "--no-open", "--view-id", "sym-1"));
        assertEquals(
            new Request.InspectSymbol("/proj/src/App.java", 0, Boolean.FALSE, null),
            parse("inspect-symbol", "--open", "false", "/proj/src/App.java:0"));
        assertThrows(CliParseException.class, () -> parse("inspect-symbol", "C:\\proj\\App.java"));
        assertThrows(CliParseException.class, () -> parse("inspect-symbol", "App.java"));
        assertThrows(CliParseException.class, () -> parse("inspect-symbol", ":10"));

        assertInstanceOf(Request.ListInspectProviders.class, parse("inspect-providers"));
    }

    @Test
    void parsesToolkitViewCommands() {
        assertEquals(
            new Request.OpenCallExplorer("/proj/src/Service.java", 88, "callers", 3, Boolean.TRUE, "hybrid", null),
            parse("call-explorer", "/proj/src/Service.java:88"));
        assertEquals(
            new Request.OpenCallExplorer("C:\\proj\\Service.java", 88, "callees", 5, Boolean.FALSE, "tree", "cx-1"),
            parse("call-explorer", "C:\\proj\\Service.java:88", "--direction", "callees", "--depth", "5", "--show-jdk", "--mode", "tree", "--view-id", "cx-1"));
        assertEquals(
            new Request.OpenCallExplorer("/proj/src/Service.java", 88, "both", 3, Boolean.FALSE, "hybrid", null),
            parse("call-explorer", "/proj/src/Service.java:88", "-d", "both", "--hide-jdk", "false", "-m", "hybrid"));
        assertEquals(
            new Request.OpenCallExplorer(
                "/proj/src/Service.java", 88, "callers", 3, Boolean.TRUE, "hybrid", null,
                "package", 10, "/proj/src/Other.java", 42),
            parse("call-explorer", "/proj/src/Service.java:88", "--group-by", "package", "--max-fanout", "10", "--compare", "/proj/src/Other.java:42"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "/proj/src/Service.java:88", "--group-by", "bogus"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "C:\\proj\\Service.java"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "/proj/src/Service.java:88", "--direction", "up"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "/proj/src/Service.java:88", "--depth", "0"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "/proj/src/Service.java:88", "--mode", "bogus"));
        assertThrows(CliParseException.class, () -> parse("call-explorer", "/proj/src/Service.java:88", "--hide-jdk", "maybe"));

        assertEquals(
            new Request.OpenTraceView(200, Boolean.TRUE, null),
            parse("trace-view"));
        assertEquals(
            new Request.OpenTraceView(50, Boolean.FALSE, "tv-1"),
            parse("trace-view", "--limit", "50", "--no-live", "--view-id", "tv-1"));
        assertEquals(
            new Request.OpenTraceView(10, Boolean.FALSE, null),
            parse("trace-view", "-n", "10", "--live", "false"));
        assertThrows(CliParseException.class, () -> parse("trace-view", "--limit", "0"));
        assertThrows(CliParseException.class, () -> parse("trace-view", "--live", "nope"));

        assertEquals(
            new Request.OpenJUnitView(null, Boolean.FALSE, Boolean.TRUE, null),
            parse("junit-view"));
        assertEquals(
            new Request.OpenJUnitView("test_4", Boolean.FALSE, Boolean.TRUE, null),
            parse("junit-view", "test_4"));
        assertEquals(
            new Request.OpenJUnitView("test_4", Boolean.TRUE, Boolean.FALSE, "jv-1"),
            parse("junit-view", "test_4", "--failures", "--no-live", "--view-id", "jv-1"));
        assertEquals(
            new Request.OpenJUnitView("test_5", Boolean.TRUE, Boolean.FALSE, null),
            parse("junit-view", "--run-id", "test_5", "--failures-only", "true", "--live", "false"));
        assertEquals(
            new Request.OpenJUnitView("test_6", Boolean.FALSE, Boolean.TRUE, null),
            parse("junit-view", "--failures-only", "false", "test_6"));
        assertEquals(
            new Request.OpenJUnitView(null, Boolean.TRUE, Boolean.FALSE, null),
            parse("junit-view", "--failures", "--no-live"));
        assertThrows(CliParseException.class, () -> parse("junit-view", "--failures-only", "maybe"));
    }

    @Test
    void protocolSchemaIsValidJsonWithToolkitCommands() {
        JsonObject root = JsonParser.parseString(ProtocolSchema.JSON_SCHEMA).getAsJsonObject();
        JsonObject commands = root.getAsJsonObject("commands");

        assertEquals("InspectFrame", commands.getAsJsonObject("inspect-frame").get("record").getAsString());
        assertEquals("InspectValue", commands.getAsJsonObject("inspect-value").get("record").getAsString());
        assertEquals("InspectSymbol", commands.getAsJsonObject("inspect-symbol").get("record").getAsString());
        assertEquals("ListInspectProviders", commands.getAsJsonObject("inspect-providers").get("record").getAsString());
        assertEquals("OpenCallExplorer", commands.getAsJsonObject("call-explorer").get("record").getAsString());
        assertEquals("OpenTraceView", commands.getAsJsonObject("trace-view").get("record").getAsString());
        assertEquals("OpenJUnitView", commands.getAsJsonObject("junit-view").get("record").getAsString());

        String viewOpenDesc = commands.getAsJsonObject("view-open")
            .getAsJsonObject("params")
            .getAsJsonObject("specJson")
            .get("description")
            .getAsString();
        String viewUpdateDesc = commands.getAsJsonObject("view-update")
            .getAsJsonObject("params")
            .getAsJsonObject("specJson")
            .get("description")
            .getAsString();
        for (String desc : new String[] { viewOpenDesc, viewUpdateDesc }) {
            assertTrue(desc.contains("graph"));
            assertTrue(desc.contains("split"));
            assertTrue(desc.contains("form"));
            assertTrue(desc.contains("html"));
            assertTrue(desc.contains("live"));
        }
    }

    private static Request parse(String... args) {
        return EquimacsCLI.parseCommand(CliArgs.parse(args));
    }
}
