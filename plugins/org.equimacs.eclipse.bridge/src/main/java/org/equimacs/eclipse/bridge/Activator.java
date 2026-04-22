package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static Activator plugin;
    private final DebugController controller = new DebugController();
    private final Gson gson = new GsonBuilder().create();
    private final ExecutorService serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocketChannel serverChannel;
    private Path socketPath;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        logInfo("Equimacs Bridge Bundle Started.");
        startServer();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        stopServer();
        plugin = null;
        super.stop(context);
    }

    public synchronized void startServer() {
        if (serverChannel != null && serverChannel.isOpen()) {
            return;
        }

        serverExecutor.submit(() -> {
            try {
                // Use a standard location for the Unix Domain Socket
                String userHome = System.getProperty("user.home");
                socketPath = Path.of(userHome, ".equimacs.sock");
                
                // Cleanup existing socket file if it exists
                Files.deleteIfExists(socketPath);

                serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
                
                logInfo("Equimacs Listening on Unix Socket: " + socketPath);

                while (serverChannel.isOpen()) {
                    SocketChannel clientChannel = serverChannel.accept();
                    serverExecutor.submit(() -> handleClient(clientChannel));
                }
            } catch (IOException e) {
                if (serverChannel != null && serverChannel.isOpen()) {
                    logError("Equimacs Server Error", e);
                }
            } finally {
                logInfo("Equimacs Server Thread Stopped.");
            }
        });
    }

    public synchronized void stopServer() {
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
            if (socketPath != null) {
                Files.deleteIfExists(socketPath);
            }
            serverExecutor.shutdownNow();
        } catch (IOException e) {
            logError("Error stopping server", e);
        }
    }

    private void handleClient(SocketChannel clientChannel) {
        try (clientChannel;
             BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(clientChannel)));
             PrintWriter out = new PrintWriter(Channels.newOutputStream(clientChannel), true)) {
            
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                
                try {
                    Request req = gson.fromJson(line, Request.class);
                    Response resp = dispatchRequest(req);
                    out.println(gson.toJson(resp));
                } catch (JsonSyntaxException e) {
                    out.println(gson.toJson(new Response.Error("Invalid JSON: " + e.getMessage(), null)));
                } catch (Exception e) {
                    out.println(gson.toJson(new Response.Error("Execution Error: " + e.getMessage(), null)));
                }
            }
        } catch (IOException e) {
            logError("Client connection error", e);
        }
    }

    private Response dispatchRequest(Request req) {
        logInfo("Dispatching request: " + req.getClass().getSimpleName());
        try {
            Object result = switch (req) {
                case Request.SetBreakpoint b -> {
                    controller.setBreakpoint(b.path(), b.line(), b.condition());
                    yield "Breakpoint set at " + b.path() + ":" + b.line() + 
                          (b.condition() != null ? " with condition: " + b.condition() : "");
                }
                case Request.ClearAllBreakpoints c -> {
                    controller.clearAllBreakpoints();
                    yield "All breakpoints cleared";
                }
                case Request.Resume r -> {
                    controller.resume();
                    yield "Resumed";
                }
                case Request.Suspend s -> {
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
                case Request.GetThreads t -> "Not implemented yet"; // TODO
                case Request.GetStack s -> "Not implemented yet"; // TODO
                case Request.GetVariables v -> "Not implemented yet"; // TODO
            };
            return new Response.Success(result);
        } catch (Exception e) {
            return new Response.Error(e.getMessage(), null);
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
