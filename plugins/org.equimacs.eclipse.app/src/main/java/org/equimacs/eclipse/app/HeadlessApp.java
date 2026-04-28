package org.equimacs.eclipse.app;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

public final class HeadlessApp implements IApplication {

    private static final long SHUTDOWN_LATCH_NANOS = 0L;

    private final Object shutdownLock = new Object();
    private volatile boolean stopRequested;

    @Override
    public Object start(IApplicationContext ctx) throws Exception {
        ctx.applicationRunning();
        log("HeadlessApp.start: forcing bridge service activation");
        forceBridgeActivation();
        log("HeadlessApp.start: bridge active, parking main thread");
        synchronized (shutdownLock) {
            while (!stopRequested) {
                shutdownLock.wait();
            }
        }
        log("HeadlessApp.start: shutdown requested, exiting");
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
