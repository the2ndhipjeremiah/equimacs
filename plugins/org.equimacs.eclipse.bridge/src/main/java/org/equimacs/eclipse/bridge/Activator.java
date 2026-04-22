package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.FrameworkWiring;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.equimacs.eclipse.bridge";
    private static Activator plugin;
    private final JavaDebugController controller = new JavaDebugController();
    private org.osgi.framework.BundleContext bundleContext;
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(Request.class, (JsonDeserializer<Request>) (json, typeOfT, context) -> {
            JsonObject obj = json.getAsJsonObject();
            if (!obj.has("type")) throw new JsonParseException("Missing 'type' field in Request");
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "SetBreakpoint" -> context.deserialize(obj, Request.SetBreakpoint.class);
                case "ClearAllBreakpoints" -> context.deserialize(obj, Request.ClearAllBreakpoints.class);
                case "ListBreakpoints" -> context.deserialize(obj, Request.ListBreakpoints.class);
                case "Resume" -> context.deserialize(obj, Request.Resume.class);
                case "Suspend" -> context.deserialize(obj, Request.Suspend.class);
                case "Step" -> context.deserialize(obj, Request.Step.class);
                case "GogoExec" -> context.deserialize(obj, Request.GogoExec.class);
                case "Reload" -> context.deserialize(obj, Request.Reload.class);
                case "GetWorkspace" -> context.deserialize(obj, Request.GetWorkspace.class);
                case "GetThreads" -> context.deserialize(obj, Request.GetThreads.class);
                case "GetStack" -> context.deserialize(obj, Request.GetStack.class);
                case "GetVariables" -> context.deserialize(obj, Request.GetVariables.class);
                case "GetProblems" -> context.deserialize(obj, Request.GetProblems.class);
                case "Build" -> context.deserialize(obj, Request.Build.class);
                case "GetQuickFixes" -> context.deserialize(obj, Request.GetQuickFixes.class);
                case "ApplyFix" -> context.deserialize(obj, Request.ApplyFix.class);
                case "GetClasspath" -> context.deserialize(obj, Request.GetClasspath.class);
                case "GetProjectDescription" -> context.deserialize(obj, Request.GetProjectDescription.class);
                case "RefreshProject" -> context.deserialize(obj, Request.RefreshProject.class);
                default -> throw new JsonParseException("Unknown request type: " + type);
            };
        })
        .create();
    private ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private ServerSocketChannel serverChannel;
    private Path socketPath;
    private static final Path TRACE_LOG = Path.of(System.getProperty("user.home"), ".equimacs.trace");

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        bundleContext = context;
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

        serverExecutor = Executors.newCachedThreadPool();

        try {
            String userHome = System.getProperty("user.home");
            socketPath = Path.of(userHome, ".equimacs.sock");
            
            // Cleanup existing socket file if it exists
            Files.deleteIfExists(socketPath);

            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            
            logInfo("Equimacs Listening on Unix Socket: " + socketPath);

            serverExecutor.submit(() -> {
                try {
                    while (serverChannel.isOpen()) {
                        SocketChannel clientChannel = serverChannel.accept();
                        serverExecutor.submit(() -> handleClient(clientChannel));
                    }
                } catch (IOException e) {
                    if (serverChannel != null && serverChannel.isOpen()) {
                        logError("Equimacs Server Error", e);
                    }
                } catch (Exception e) {
                    logError("Equimacs Server Fatal Error", e);
                } finally {
                    logInfo("Equimacs Server Thread Stopped.");
                }
            });
        } catch (IOException e) {
            logError("Failed to start Equimacs Server", e);
            if (serverChannel != null) {
                try { serverChannel.close(); } catch (IOException ignored) {}
                serverChannel = null;
            }
        }
    }

    public synchronized void stopServer() {
        try {
            if (serverChannel != null) {
                serverChannel.close();
                serverChannel = null;
            }
            if (socketPath != null) {
                Files.deleteIfExists(socketPath);
            }
        } catch (IOException e) {
            logError("Error stopping server", e);
        }
        serverExecutor.shutdown();
        try {
            if (!serverExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                serverExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            serverExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean isServerRunning() {
        return serverChannel != null && serverChannel.isOpen();
    }

    private void handleClient(SocketChannel clientChannel) {
        try (clientChannel;
             BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(clientChannel)));
             PrintWriter out = new PrintWriter(Channels.newOutputStream(clientChannel), true)) {
            
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                
                try {
                    trace("→", line);
                    Request req = gson.fromJson(line, Request.class);
                    Response resp = dispatchRequest(req);
                    String respJson = gson.toJson(resp);
                    trace("←", respJson);
                    out.println(respJson);
                } catch (JsonParseException e) {
                    String errJson = gson.toJson(new Response.Error("Invalid JSON: " + e.getMessage(), null));
                    trace("←", errJson);
                    out.println(errJson);
                } catch (Exception e) {
                    String errJson = gson.toJson(new Response.Error("Execution Error: " + e.getMessage(), null));
                    trace("←", errJson);
                    out.println(errJson);
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
                case Request.GogoExec g -> controller.executeGogo(g.command(), bundleContext);
                case Request.Reload _ -> {
                    Bundle self = bundleContext.getBundle();
                    Bundle system = bundleContext.getBundle(0);
                    new Thread(() -> {
                        try {
                            Thread.sleep(300);
                            self.update();
                            system.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(self));
                        } catch (Exception e) {
                            logError("Reload failed", e);
                        }
                    }, "equimacs-reload").start();
                    yield "Reloading... [" + java.time.LocalTime.now().withNano(0) + "]";
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
            };
            return new Response.Success(result);
        } catch (Exception e) {
            return new Response.Error(e.getMessage(), null);
        }
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
