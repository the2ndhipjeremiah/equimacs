package org.equimacs.eclipse.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static final int WORKSPACE_READY_ATTEMPTS = 120;
    private static final long WORKSPACE_READY_INTERVAL_MS = 500;
    private static final Path TRACE_LOG = Path.of(System.getProperty("user.home"), ".equimacs.trace");

    private static Activator plugin;
    private JavaDebugController controller;
    private BundleContext bundleContext;
    private BridgeServer bridgeServer;
    private volatile boolean stopping;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        bundleContext = context;
        stopping = false;
        logInfo("Equimacs Bridge Bundle Started. " + buildBanner());
        startWhenWorkspaceReady();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        stopping = true;
        stopServer();
        controller = null;
        bridgeServer = null;
        bundleContext = null;
        plugin = null;
        super.stop(context);
    }

    private void startWhenWorkspaceReady() {
        Thread thread = new Thread(() -> {
            if (!waitForWorkspaceReady()) {
                logError("Workspace was not ready; Equimacs server startup deferred", null);
                return;
            }
            startServer();
        }, "equimacs-startup");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean waitForWorkspaceReady() {
        for (int i = 0; i < WORKSPACE_READY_ATTEMPTS && !stopping; i++) {
            if (isWorkspaceReady()) {
                return true;
            }
            try {
                Thread.sleep(WORKSPACE_READY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isWorkspaceReady() {
        try {
            ResourcesPlugin.getWorkspace();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public synchronized void startServer() {
        if (bridgeServer == null && !initializeServer()) {
            logInfo("startServer deferred: workspace is not ready");
            return;
        }
        if (bridgeServer == null || bridgeServer.isRunning()) {
            logInfo("startServer skipped: bridgeServer=" + (bridgeServer != null)
                + ", running=" + (bridgeServer != null && bridgeServer.isRunning()));
            return;
        }
        try {
            logInfo("startServer requested: user.home=" + System.getProperty("user.home"));
            bridgeServer.start();
        } catch (IOException e) {
            logError("Failed to start Equimacs Server", e);
        }
    }

    private boolean initializeServer() {
        if (!isWorkspaceReady()) {
            return false;
        }
        controller = new JavaDebugController();
        controller.init();
        bridgeServer = new BridgeServer(new BridgeRequestDispatcher(controller, () -> bundleContext));
        return true;
    }

    public synchronized void stopServer() {
        if (bridgeServer == null) {
            logInfo("stopServer skipped: bridgeServer=false");
            return;
        }
        try {
            bridgeServer.stop();
        } catch (IOException e) {
            logError("Error stopping server", e);
        }
    }

    public synchronized boolean isServerRunning() {
        return bridgeServer != null && bridgeServer.isRunning();
    }

    public static Activator getDefault() {
        return plugin;
    }

    private static void trace(String dir, String json) {
        try {
            String line = "[" + java.time.LocalTime.now().withNano(0) + "] " + dir + " " + json + "\n";
            Files.writeString(TRACE_LOG, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    static void traceInbound(String json) {
        trace("→", json);
    }

    static void traceOutbound(String json) {
        trace("←", json);
    }

    static void traceLifecycle(String message) {
        trace("•", message);
    }

    public static String buildBanner() {
        if (plugin == null) {
            return "version=unknown";
        }
        return "version=" + plugin.getBundle().getVersion()
            + ", modified=" + Instant.ofEpochMilli(plugin.getBundle().getLastModified());
    }

    public static void logInfo(String message) {
        if (plugin != null) {
            plugin.getLog().log(new Status(Status.INFO, PLUGIN_ID, message));
        } else {
            System.out.println("Equimacs [INFO]: " + message);
        }
    }

    public static void logError(String message, Throwable exception) {
        if (plugin != null) {
            plugin.getLog().log(new Status(Status.ERROR, PLUGIN_ID, message, exception));
        } else {
            System.err.println("Equimacs [ERROR]: " + message);
            if (exception != null) exception.printStackTrace();
        }
    }
}
