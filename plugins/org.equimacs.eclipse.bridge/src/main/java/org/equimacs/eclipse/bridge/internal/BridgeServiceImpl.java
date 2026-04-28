package org.equimacs.eclipse.bridge.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.equimacs.eclipse.bridge.Activator;
import org.equimacs.eclipse.bridge.BridgeServer;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.FrameworkWiring;

public final class BridgeServiceImpl implements IBridgeService {

    private static final int WORKSPACE_READY_ATTEMPTS = 120;
    private static final long WORKSPACE_READY_INTERVAL_MS = 500;

    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();
    private final Map<String, IBridgeCommandHandler> dispatchMap = new ConcurrentHashMap<>();

    private volatile BundleContext ctx;
    private volatile BridgeServer server;
    private volatile boolean stopping;

    void activate(BundleContext ctx) {
        this.ctx = ctx;
        this.stopping = false;
        Activator.logInfo("BridgeServiceImpl activated. " + Activator.buildBanner());
        startWhenWorkspaceReady();
    }

    void deactivate() {
        stopping = true;
        Activator.logInfo("BridgeServiceImpl deactivating; clearing " + dispatchMap.size() + " handler(s)");
        dispatchMap.clear();
        BridgeServer running = server;
        server = null;
        if (running != null) {
            try {
                running.stop();
            } catch (IOException e) {
                Activator.logError("Error stopping bridge server", e);
            }
        }
        ctx = null;
    }

    @Override
    public void publishEvent(JsonObject event) {
        eventQueue.offer(event);
    }

    void addHandler(IBridgeCommandHandler handler, Map<String, Object> props) {
        String[] types = handledTypes(props);
        for (String type : types) {
            dispatchMap.put(type, handler);
        }
        Activator.logInfo("Registered command handler "
            + handler.getClass().getName() + " for types " + Arrays.toString(types));
    }

    void removeHandler(IBridgeCommandHandler handler, Map<String, Object> props) {
        String[] types = handledTypes(props);
        for (String type : types) {
            dispatchMap.remove(type, handler);
        }
        Activator.logInfo("Unregistered command handler "
            + handler.getClass().getName() + " for types " + Arrays.toString(types));
    }

    private static String[] handledTypes(Map<String, Object> props) {
        Object val = props.get("equimacs.commands");
        String[] raw;
        if (val instanceof String[] arr) {
            raw = arr;
        } else if (val instanceof String s) {
            raw = s.split("[,\\s]+");
        } else {
            return new String[0];
        }
        List<String> cleaned = new ArrayList<>(raw.length);
        for (String r : raw) {
            String t = r.trim();
            if (!t.isEmpty()) cleaned.add(t);
        }
        return cleaned.toArray(String[]::new);
    }

    public Response dispatch(Request req) {
        Activator.logInfo("Dispatching request: " + req.getClass().getSimpleName());
        try {
            return new Response.Success(dispatchResult(req));
        } catch (Exception e) {
            return new Response.Error(e.getMessage(), null);
        }
    }

    private Object dispatchResult(Request req) throws Exception {
        return switch (req) {
            case Request.WaitEvent w -> waitEvent(w.timeoutMs());
            case Request.Reload _ -> reloadBundle();
            case Request.GogoExec g -> executeGogo(g.command());
            default -> {
                String typeName = req.getClass().getSimpleName();
                IBridgeCommandHandler handler = dispatchMap.get(typeName);
                if (handler == null) {
                    throw new IllegalArgumentException("No handler registered for: " + typeName);
                }
                yield handler.handle(req);
            }
        };
    }

    // --- Server lifecycle ---

    private void startWhenWorkspaceReady() {
        Thread thread = new Thread(() -> {
            if (!waitForWorkspaceReady()) {
                Activator.logError("Workspace was not ready; Equimacs server startup deferred", null);
                return;
            }
            startServer();
        }, "equimacs-startup");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean waitForWorkspaceReady() {
        for (int i = 0; i < WORKSPACE_READY_ATTEMPTS && !stopping; i++) {
            if (isWorkspaceReady()) return true;
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

    private synchronized void startServer() {
        if (stopping || ctx == null) return;
        if (server != null && server.isRunning()) return;
        try {
            BridgeServer s = new BridgeServer(this::dispatch);
            s.start();
            server = s;
        } catch (IOException e) {
            Activator.logError("Failed to start Equimacs Server", e);
        }
    }

    // --- Bridge-intrinsic ops ---

    private JsonObject waitEvent(int timeoutMs) throws InterruptedException {
        JsonObject event = eventQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (event == null) {
            JsonObject timeout = new JsonObject();
            timeout.addProperty("event", "Timeout");
            return timeout;
        }
        return event;
    }

    private String executeGogo(String command) throws Exception {
        BundleContext c = requireBundleContext();
        ServiceReference<CommandProcessor> ref = c.getServiceReference(CommandProcessor.class);
        if (ref == null) throw new Exception("Gogo CommandProcessor service not found");
        CommandProcessor processor = c.getService(ref);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos, true, "UTF-8");
            try (CommandSession session = processor.createSession(InputStream.nullInputStream(), out, out)) {
                Object result = session.execute(command);
                out.flush();
                String output = baos.toString("UTF-8").trim();
                if (isRawRefString(output)) output = "(unserializable: " + extractClassName(output) + ")";
                if (result != null) {
                    String rs;
                    if (looksLikeRawRef(result)) {
                        String json = new Gson().toJson(result);
                        rs = isTriviallyEmpty(json)
                            ? "(unserializable: " + result.getClass().getSimpleName() + ")"
                            : json;
                    } else {
                        rs = result.toString();
                    }
                    if (!rs.isEmpty()) output = output.isEmpty() ? rs : output + "\n" + rs;
                }
                return output.isEmpty() ? "(no output)" : output;
            }
        } finally {
            c.ungetService(ref);
        }
    }

    private static boolean looksLikeRawRef(Object o) {
        if (o.getClass().isArray()) return true;
        return isRawRefString(o.toString());
    }

    private static boolean isRawRefString(String s) {
        return s.matches("\\[?[\\w.$]+@[0-9a-f]{6,}\\]?");
    }

    private static String extractClassName(String rawRef) {
        String stripped = rawRef.replaceAll("^\\[|\\]$", "");
        String className = stripped.contains("@") ? stripped.substring(0, stripped.indexOf('@')) : stripped;
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static boolean isTriviallyEmpty(String json) {
        String trimmed = json.replaceAll("\\s", "");
        return trimmed.equals("{}") || trimmed.equals("[]")
            || trimmed.matches("\\[\\{(\"\\w+\":\\{\\}(,\"\\w+\":\\{\\})*)?\\}\\]");
    }

    private String reloadBundle() {
        BundleContext c = requireBundleContext();
        Bundle self = c.getBundle();
        Bundle system = c.getBundle(0);
        new Thread(() -> {
            try {
                Thread.sleep(300);
                List<Bundle> updated = updateEquimacsBundles(c);
                if (updated.isEmpty()) {
                    self.update();
                    updated = List.of(self);
                }
                system.adapt(FrameworkWiring.class).refreshBundles(updated);
            } catch (Exception e) {
                Activator.logError("Reload failed", e);
            }
        }, "equimacs-reload").start();
        return "Reloading... [" + LocalTime.now().withNano(0) + "]";
    }

    private static List<Bundle> updateEquimacsBundles(BundleContext ctx) throws IOException {
        Map<String, Path> latest = readEquimacsJarsFromBundlesInfo();
        List<Bundle> updated = new ArrayList<>();
        for (Bundle bundle : ctx.getBundles()) {
            String name = bundle.getSymbolicName();
            if (name == null || !name.startsWith("org.equimacs.")) continue;
            Path jar = latest.get(name);
            if (jar == null) continue;
            Activator.logInfo("Reload: updating " + name + " from " + jar);
            try (InputStream in = Files.newInputStream(jar)) {
                bundle.update(in);
            } catch (Exception e) {
                Activator.logError("Reload: failed to update " + name + " from " + jar, e);
                continue;
            }
            updated.add(bundle);
        }
        return updated;
    }

    private static Map<String, Path> readEquimacsJarsFromBundlesInfo() throws IOException {
        Path eclipse = locateEclipseInstall();
        if (eclipse == null) return Map.of();
        Path bundlesInfo = eclipse.resolve("configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
        if (!Files.exists(bundlesInfo)) return Map.of();

        Map<String, Path> latest = new LinkedHashMap<>();
        for (String line : Files.readAllLines(bundlesInfo)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split(",", -1);
            if (parts.length < 3) continue;
            String name = parts[0];
            if (!name.startsWith("org.equimacs.")) continue;
            Path jar = eclipse.resolve(parts[2]);
            if (Files.exists(jar)) latest.put(name, jar);
        }
        return latest;
    }

    private static Path locateEclipseInstall() {
        try {
            URL url = Platform.getInstallLocation().getURL();
            return Path.of(url.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private BundleContext requireBundleContext() {
        BundleContext c = ctx;
        if (c == null) throw new IllegalStateException("Bundle context unavailable");
        return c;
    }
}
