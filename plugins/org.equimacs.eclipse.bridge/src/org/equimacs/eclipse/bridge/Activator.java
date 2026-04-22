package org.equimacs.eclipse.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static Activator plugin;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private final DebugController controller = new DebugController();

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        logInfo("Equimacs Bridge Bundle Started.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        stopServer();
        plugin = null;
        super.stop(context);
    }

    public synchronized void startServer() {
        if (serverThread != null && serverThread.isAlive()) {
            return;
        }

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(12345);
                logInfo("Equimacs Listening on Port 12345...");
                
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        
                        String line = in.readLine();
                        if (line != null && !line.trim().isEmpty()) {
                            handleCommand(line.trim());
                        }
                    } catch (Exception e) {
                        if (!serverSocket.isClosed()) {
                            logError("Error accepting connection", e);
                        }
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    logError("Equimacs Server Error", e);
                }
            } finally {
                closeQuietly(serverSocket);
                logInfo("Equimacs Server Stopped.");
            }
        }, "Equimacs Server Thread");
        
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public synchronized void stopServer() {
        if (serverThread != null) {
            serverThread.interrupt();
            closeQuietly(serverSocket);
            serverThread = null;
        }
    }

    private void handleCommand(String command) {
        try {
            logInfo("Received command: " + command);
            if (command.startsWith("set-breakpoint:")) {
                String[] parts = command.split(":", 4);
                if (parts.length < 4) {
                    logError("Invalid set-breakpoint command: " + command, null);
                    return;
                }
                String projectPath = parts[1];
                String typeOrHandle = parts[2];
                int lineNumber = Integer.parseInt(parts[3]);
                controller.setBreakpoint(projectPath, typeOrHandle, lineNumber);
            } else if ("clear-all".equals(command)) {
                controller.clearAllBreakpoints();
            } else if ("resume".equals(command)) {
                controller.resume();
            } else if ("suspend".equals(command)) {
                controller.suspend();
            } else if ("step-over".equals(command)) {
                controller.stepOver();
            } else if ("step-into".equals(command)) {
                controller.stepInto();
            } else if ("step-return".equals(command)) {
                controller.stepReturn();
            } else {
                logError("Unknown command: " + command, null);
            }
        } catch (Exception e) {
            logError("Error handling command: " + command, e);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
    }

    public static Activator getDefault() {
        return plugin;
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
