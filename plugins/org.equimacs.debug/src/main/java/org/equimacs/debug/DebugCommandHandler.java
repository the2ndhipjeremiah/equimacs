package org.equimacs.debug;

import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

public final class DebugCommandHandler implements IBridgeCommandHandler {

    private volatile JavaDebugController controller;
    private volatile IBridgeService bridge;

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
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
    }

    @Override
    public Object handle(Request req) throws Exception {
        JavaDebugController c = controller();
        return switch (req) {
            case Request.SetBreakpoint b -> {
                c.setBreakpoint(b.path(), b.line(), b.condition());
                yield "Breakpoint set at " + b.path() + ":" + b.line()
                    + (b.condition() != null ? " with condition: " + b.condition() : "");
            }
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
                switch (s.type()) {
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
            case Request.GetProblems p -> c.getProblems(p.project(), p.severity());
            case Request.Build b -> c.build(b.project(), b.kind());
            case Request.GetQuickFixes q -> c.getQuickFixes(q.file(), q.line());
            case Request.ApplyFix a -> c.applyFix(a.file(), a.line(), a.fixIndex());
            case Request.GetClasspath cp -> c.getClasspath(cp.project());
            case Request.GetProjectDescription d -> c.getProjectDescription(d.project());
            case Request.RefreshProject r -> c.refreshProject(r.project());
            case Request.Launch l -> c.launch(l.configName());
            case Request.ListLaunches _ -> c.listLaunches();
            case Request.ListSessions _ -> c.listSessions();
            case Request.Terminate _ -> c.terminate();
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
}
