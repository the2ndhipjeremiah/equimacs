package org.equimacs.eclipse.bridge;

import java.time.LocalTime;
import java.util.Collections;
import java.util.function.Supplier;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.FrameworkWiring;

final class BridgeRequestDispatcher {
    private final JavaDebugController controller;
    private final Supplier<BundleContext> bundleContextSupplier;

    BridgeRequestDispatcher(JavaDebugController controller, Supplier<BundleContext> bundleContextSupplier) {
        this.controller = controller;
        this.bundleContextSupplier = bundleContextSupplier;
    }

    Response dispatch(Request req) {
        Activator.logInfo("Dispatching request: " + req.getClass().getSimpleName());
        try {
            return new Response.Success(dispatchResult(req));
        } catch (Exception e) {
            return new Response.Error(e.getMessage(), null);
        }
    }

    private Object dispatchResult(Request req) throws Exception {
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
            case Request.GogoExec g -> controller.executeGogo(g.command(), requireBundleContext());
            case Request.Reload _ -> reloadBundle();
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
            case Request.WaitEvent w -> controller.waitEvent(w.timeoutMs());
            case Request.Launch l -> controller.launch(l.configName());
            case Request.ListLaunches _ -> controller.listLaunches();
            case Request.ListSessions _ -> controller.listSessions();
            case Request.Terminate _ -> controller.terminate();
        };
    }

    private BundleContext requireBundleContext() {
        BundleContext bundleContext = bundleContextSupplier.get();
        if (bundleContext == null) {
            throw new IllegalStateException("Bundle context unavailable");
        }
        return bundleContext;
    }

    private String reloadBundle() {
        BundleContext bundleContext = requireBundleContext();
        Bundle self = bundleContext.getBundle();
        Bundle system = bundleContext.getBundle(0);
        new Thread(() -> {
            try {
                Thread.sleep(300);
                self.update();
                system.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(self));
            } catch (Exception e) {
                Activator.logError("Reload failed", e);
            }
        }, "equimacs-reload").start();
        return "Reloading... [" + LocalTime.now().withNano(0) + "]";
    }
}
