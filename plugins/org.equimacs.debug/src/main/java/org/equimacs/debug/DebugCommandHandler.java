package org.equimacs.debug;

import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

public final class DebugCommandHandler implements IBridgeCommandHandler {

    private final JavaDebugController controller = new JavaDebugController();
    private volatile IBridgeService bridge;

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void activate() {
        controller.init(bridge::publishEvent);
    }

    void deactivate() {
        controller.dispose();
    }

    @Override
    public Object handle(Request req) throws Exception {
        return switch (req) {
            case Request.SetBreakpoint b -> {
                controller.setBreakpoint(b.path(), b.line(), b.condition());
                yield "Breakpoint set at " + b.path() + ":" + b.line()
                    + (b.condition() != null ? " with condition: " + b.condition() : "");
            }
            case Request.ListBreakpoints _ -> controller.listBreakpoints();
            case Request.ClearAllBreakpoints _ -> {
                controller.clearAllBreakpoints();
                yield "All breakpoints cleared";
            }
            case Request.Resume _ -> {
                controller.resume();
                yield "Resumed";
            }
            case Request.Suspend _ -> {
                controller.suspend();
                yield "Suspended";
            }
            case Request.Step s -> {
                switch (s.type()) {
                    case OVER -> controller.stepOver();
                    case INTO -> controller.stepInto();
                    case RETURN -> controller.stepReturn();
                }
                yield "Step executed";
            }
            case Request.GetWorkspace _ -> controller.getWorkspace();
            case Request.GetThreads _ -> controller.getThreads();
            case Request.GetStack s -> controller.getStack(s.threadId());
            case Request.GetVariables v -> controller.getVariables(v.frameId());
            case Request.GetProblems p -> controller.getProblems(p.project(), p.severity());
            case Request.Build b -> controller.build(b.project(), b.kind());
            case Request.GetQuickFixes q -> controller.getQuickFixes(q.file(), q.line());
            case Request.ApplyFix a -> controller.applyFix(a.file(), a.line(), a.fixIndex());
            case Request.GetClasspath c -> controller.getClasspath(c.project());
            case Request.GetProjectDescription d -> controller.getProjectDescription(d.project());
            case Request.RefreshProject r -> controller.refreshProject(r.project());
            case Request.Launch l -> controller.launch(l.configName());
            case Request.ListLaunches _ -> controller.listLaunches();
            case Request.ListSessions _ -> controller.listSessions();
            case Request.Terminate _ -> controller.terminate();
            default -> throw new IllegalArgumentException(
                "DebugCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }
}
