package org.equimacs.eclipse.app;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.ServiceReference;
import java.util.Hashtable;

public final class HeadlessApp implements IApplication {

    private final Object shutdownLock = new Object();
    private volatile boolean stopRequested;
    private ServiceRegistration<IBridgeCommandHandler> shutdownRegistration;

    @Override
    public Object start(IApplicationContext ctx) throws Exception {
        ctx.applicationRunning();
        registerShutdownHandler();
        log("HeadlessApp.start: forcing bridge service activation");
        forceBridgeActivation();
        log("HeadlessApp.start: bridge active, parking main thread");
        synchronized (shutdownLock) {
            while (!stopRequested) {
                shutdownLock.wait();
            }
        }
        log("HeadlessApp.start: shutdown requested, exiting");
        unregisterShutdownHandler();
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        log("HeadlessApp.stop: requested");
        synchronized (shutdownLock) {
            stopRequested = true;
            shutdownLock.notifyAll();
        }
    }

    private void registerShutdownHandler() {
        BundleContext ctx = FrameworkUtil.getBundle(HeadlessApp.class).getBundleContext();
        Hashtable<String, Object> props = new Hashtable<>();
        props.put("equimacs.commands", new String[] { "Shutdown" });
        shutdownRegistration = ctx.registerService(IBridgeCommandHandler.class, req -> {
            if (!(req instanceof Request.Shutdown)) {
                throw new IllegalArgumentException("Unsupported request: " + req.getClass().getSimpleName());
            }
            requestShutdown();
            return "shutting down";
        }, props);
        log("HeadlessApp.start: registered shutdown command handler");
    }

    private void unregisterShutdownHandler() {
        if (shutdownRegistration == null) return;
        try {
            shutdownRegistration.unregister();
        } catch (IllegalStateException ignored) {
            // Already unregistered during framework shutdown.
        } finally {
            shutdownRegistration = null;
        }
    }

    private void requestShutdown() {
        synchronized (shutdownLock) {
            stopRequested = true;
            shutdownLock.notifyAll();
        }
    }

    private static void forceBridgeActivation() {
        BundleContext ctx = FrameworkUtil.getBundle(HeadlessApp.class).getBundleContext();
        ServiceReference<IBridgeService> ref = ctx.getServiceReference(IBridgeService.class);
        if (ref == null) {
            log("WARN: IBridgeService not yet registered; DS may activate it asynchronously");
            return;
        }
        IBridgeService svc = ctx.getService(ref);
        if (svc == null) {
            log("WARN: IBridgeService reference present but service unavailable");
            return;
        }
        log("Bridge service obtained: " + svc.getClass().getName());
    }

    private static void log(String message) {
        System.out.println("[equimacs-app] " + message);
    }
}
