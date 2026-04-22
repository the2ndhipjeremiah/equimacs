package org.equimacs.eclipse.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static Activator plugin;
    private static final String BUILD_STAMP = BuildInfo.BUILD_STAMP;
    private final JavaDebugController controller = new JavaDebugController();
    private BundleContext bundleContext;
    private BridgeServer bridgeServer;
    private static final Path TRACE_LOG = Path.of(System.getProperty("user.home"), ".equimacs.trace");

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        bundleContext = context;
        logInfo("Equimacs Bridge Bundle Started. " + buildBanner());
        controller.init();
        bridgeServer = new BridgeServer(new BridgeRequestDispatcher(controller, () -> bundleContext));
        startServer();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        stopServer();
        bridgeServer = null;
        bundleContext = null;
        plugin = null;
        super.stop(context);
    }

    public synchronized void startServer() {
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
        String version = plugin != null ? plugin.getBundle().getVersion().toString() : "unknown";
        return "version=" + version + ", build=" + BUILD_STAMP;
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
