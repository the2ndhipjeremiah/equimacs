package org.equimacs.eclipse.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static final Path TRACE_LOG = Path.of(System.getProperty("user.home"), ".equimacs.trace");

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        logInfo("Equimacs Bridge Bundle Started. " + buildBanner());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
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

    public static void traceInbound(String json) {
        trace("→", json);
    }

    public static void traceOutbound(String json) {
        trace("←", json);
    }

    public static void traceLifecycle(String message) {
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
