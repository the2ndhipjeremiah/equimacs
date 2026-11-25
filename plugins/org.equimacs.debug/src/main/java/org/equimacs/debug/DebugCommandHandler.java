package org.equimacs.debug;

import java.util.concurrent.CopyOnWriteArrayList;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.protocol.Request;

public final class DebugCommandHandler implements IBridgeCommandHandler {

    private final CopyOnWriteArrayList<IMoldableViewProvider> viewProviders = new CopyOnWriteArrayList<>();
    private volatile JavaDebugController controller;
    private volatile JUnitTestRunnerService testRunner;
    private volatile JavaNavigationService navigation;
    private volatile ConsoleCaptureService console;
    private volatile MoldableInspectorService inspector;
    private volatile IBridgeService bridge;

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void addViewProvider(IMoldableViewProvider provider) {
        if (provider == null) {
            return;
        }
        synchronized (this) {
            viewProviders.addIfAbsent(provider);
            MoldableInspectorService ins = inspector;
            if (ins != null) {
                ins.addProvider(provider);
            }
        }
    }

    void removeViewProvider(IMoldableViewProvider provider) {
        if (provider == null) {
            return;
        }
        synchronized (this) {
            viewProviders.remove(provider);
            MoldableInspectorService ins = inspector;
            if (ins != null) {
                ins.removeProvider(provider);
            }
        }
    }

    void activate() {
        // Keep activation free of JDT/debug class loading. DS may activate this
        // component before org.eclipse.core.resources has opened the workspace.
    }

    void deactivate() {
        JavaDebugController c = controller;
        controller = null;
        if (c != null) {
            c.dispose();
        }
        JUnitTestRunnerService tr = testRunner;
        testRunner = null;
        if (tr != null) {
            tr.dispose();
        }
        ConsoleCaptureService cs = console;
        console = null;
        if (cs != null) {
            cs.dispose();
        }
        navigation = null;
        inspector = null;
    }

    @Override
    public Object handle(Request req) throws Exception {
        JavaDebugController c = controller();
        console();
        return switch (req) {
            case Request.SetBreakpoint b -> {
                c.setBreakpoint(b.path(), b.line(), b.condition());
                yield "Breakpoint set at " + b.path() + ":" + b.line()
                    + (b.condition() != null ? " with condition: " + b.condition() : "");
            }
            case Request.SetLogpoint lp -> c.setLogpoint(
                lp.path(), lp.line(), lp.message(), lp.condition(), lp.captureVars());
            case Request.GetTrace t -> c.getTrace(t.limit());
            case Request.ClearTrace _ -> c.clearTrace();
            case Request.ListBreakpoints _ -> c.listBreakpoints();
            case Request.ClearAllBreakpoints _ -> {
                c.clearAllBreakpoints();
                yield "All breakpoints cleared";
            }
            case Request.Resume _ -> {
                c.resume();
                yield "Resumed";
            }
            case Request.Suspend _ -> {
                c.suspend();
                yield "Suspended";
            }
            case Request.Step s -> {
                switch (s.stepType()) {
                    case OVER -> c.stepOver();
                    case INTO -> c.stepInto();
                    case RETURN -> c.stepReturn();
                }
                yield "Step executed";
            }
            case Request.GetWorkspace _ -> c.getWorkspace();
            case Request.GetThreads _ -> c.getThreads();
            case Request.GetStack s -> c.getStack(s.threadId());
            case Request.GetVariables v -> c.getVariables(v.frameId());
            case Request.Evaluate e -> c.evaluate(e.frameId(), e.expression());
            case Request.GetProblems p -> c.getProblems(p.project(), p.severity());
            case Request.Build b -> c.build(b.project(), b.kind());
            case Request.GetQuickFixes q -> c.getQuickFixes(q.file(), q.line());
            case Request.ApplyFix a -> c.applyFix(a.file(), a.line(), a.fixIndex());
            case Request.GetClasspath cp -> c.getClasspath(cp.project());
            case Request.SetClasspath scp -> c.setClasspath(
                scp.project(), scp.action(), scp.kind(), scp.path(), scp.sourceAttachment());
            case Request.GetProjectDescription d -> c.getProjectDescription(d.project());
            case Request.RefreshProject r -> c.refreshProject(r.project());
            case Request.PrepareRenameSymbol r -> c.prepareRenameSymbol(r.file(), r.offset(), r.newName());
            case Request.ApplyPreparedRefactoring r -> c.applyPreparedRefactoring(r.refactoringId());
            case Request.AbortPreparedRefactoring r -> c.abortPreparedRefactoring(r.refactoringId());
            case Request.GetPreparedRefactoring r -> c.getPreparedRefactoring(r.refactoringId());
            case Request.Launch l -> c.launch(l.configName());
            case Request.ListLaunches _ -> c.listLaunches();
            case Request.ListSessions _ -> c.listSessions();
            case Request.Terminate _ -> c.terminate();
            case Request.FindReferences f -> navigation().findReferences(f.file(), f.offset());
            case Request.CallHierarchy ch -> navigation().callHierarchy(ch.file(), ch.offset(), ch.direction(), ch.depth());
            case Request.TypeHierarchy th -> navigation().typeHierarchy(th.file(), th.offset());
            case Request.RunTests rt -> testRunner().runTests(
                rt.project(), rt.className(), rt.methodName(), rt.mode(), rt.runner(), rt.await(), rt.timeoutMs());
            case Request.GetTestResults tr -> testRunner().getTestResults(tr.runId(), tr.failuresOnly());
            case Request.GetConsoleOutput co -> console().getConsoleOutput(co.launchName(), co.stream(), co.limit());
            case Request.ClearConsoleOutput _ -> console().clearConsoleOutput();
            case Request.InspectFrame inf -> inspector().inspectFrame(
                inf.frameId(), inf.openView(), inf.viewId(), bridge);
            case Request.InspectValue inv -> inspector().inspectValue(
                inv.frameId(), inv.expression(), inv.depth(), inv.openView(), inv.viewId(), bridge);
            case Request.InspectSymbol ins -> inspector().inspectSymbol(
                ins.file(), ins.offset(), ins.openView(), ins.viewId(), bridge);
            case Request.ListInspectProviders _ -> inspector().listProviders();
            default -> throw new IllegalArgumentException(
                "DebugCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }

    private JavaDebugController controller() {
        JavaDebugController c = controller;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            c = controller;
            if (c == null) {
                IBridgeService b = bridge;
                if (b == null) {
                    throw new IllegalStateException("Bridge service unavailable");
                }
                c = new JavaDebugController();
                c.init(b::publishEvent);
                controller = c;
            }
            return c;
        }
    }

    private JUnitTestRunnerService testRunner() {
        JUnitTestRunnerService tr = testRunner;
        if (tr != null) {
            return tr;
        }
        synchronized (this) {
            tr = testRunner;
            if (tr == null) {
                IBridgeService b = bridge;
                if (b == null) {
                    throw new IllegalStateException("Bridge service unavailable");
                }
                tr = new JUnitTestRunnerService();
                tr.init(b::publishEvent);
                testRunner = tr;
            }
            return tr;
        }
    }

    private JavaNavigationService navigation() {
        JavaNavigationService nav = navigation;
        if (nav != null) {
            return nav;
        }
        synchronized (this) {
            nav = navigation;
            if (nav == null) {
                nav = new JavaNavigationService();
                navigation = nav;
            }
            return nav;
        }
    }

    private ConsoleCaptureService console() {
        ConsoleCaptureService cs = console;
        if (cs != null) {
            return cs;
        }
        synchronized (this) {
            cs = console;
            if (cs == null) {
                cs = new ConsoleCaptureService();
                console = cs;
            }
            return cs;
        }
    }

    private MoldableInspectorService inspector() {
        MoldableInspectorService ins = inspector;
        if (ins != null) {
            return ins;
        }
        synchronized (this) {
            ins = inspector;
            if (ins == null) {
                ins = new MoldableInspectorService(controller(), navigation(), viewProviders);
                inspector = ins;
            }
            return ins;
        }
    }
}
