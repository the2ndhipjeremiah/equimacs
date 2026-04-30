package org.equimacs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    private static Request parse(String... args) {
        return EquimacsCLI.parseCommand(CliArgs.parse(args));
    }
}
