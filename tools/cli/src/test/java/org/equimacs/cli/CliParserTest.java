package org.equimacs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.equimacs.cli.util.CliArgs;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.Test;

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

    private static Request parse(String... args) {
        return EquimacsCLI.parseCommand(CliArgs.parse(args));
    }
}
