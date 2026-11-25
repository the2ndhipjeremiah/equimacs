package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;

/**
 * Dispatches {@link ViewAction} triggers off the SWT UI thread via {@link IBridgeService}.
 */
public final class ViewActionDispatcher {

    private static final Gson GSON = new Gson();
    private static volatile IBridgeService bridge;

    private ViewActionDispatcher() {}

    /**
     * Wires the active {@link IBridgeService} reference (or {@code null} when unbound).
     */
    public static void setBridge(IBridgeService service) {
        bridge = service;
    }

    /**
     * Dispatches {@code action} asynchronously on a virtual thread so the SWT UI thread never stalls.
     */
    public static void dispatch(String viewId, String itemId, ViewAction action) {
        if (action == null) {
            return;
        }
        IBridgeService service = bridge;
        if (service == null) {
            return;
        }
        Thread.ofVirtual()
            .name("equimacs-view-action")
            .start(() -> execute(service, viewId, itemId, action));
    }

    /**
     * Dispatches a typed bridge request JSON payload asynchronously on a virtual thread and
     * delivers {@code (ok, jsonPayload)} to {@code callback}.
     */
    public static void dispatchJsonAsync(
            String requestType,
            String paramsJson,
            BiConsumer<Boolean, String> callback) {
        if (callback == null) {
            return;
        }
        IBridgeService service = bridge;
        if (service == null) {
            callback.accept(false, GSON.toJson("Bridge service unavailable"));
            return;
        }
        Thread.ofVirtual()
            .name("equimacs-view-request")
            .start(() -> {
                try {
                    Response res = service.dispatchJson(requestType, paramsJson);
                    switch (res) {
                        case Response.Success s -> callback.accept(true, GSON.toJson(s.result()));
                        case Response.Error err -> {
                            String msg = err.message() != null ? err.message() : "Request failed";
                            callback.accept(false, GSON.toJson(msg));
                        }
                        case Response.Event ev -> callback.accept(true, GSON.toJson(ev));
                    }
                } catch (Throwable t) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    callback.accept(false, GSON.toJson(msg));
                }
            });
    }

    private static void execute(IBridgeService service, String viewId, String itemId, ViewAction action) {
        try {
            switch (action) {
                case ViewAction.OpenFile f -> {
                    Response res = service.dispatch(new Request.OpenFile(f.file(), f.line(), f.endLine()));
                    logIfError("OpenFile", res);
                }
                case ViewAction.EclipseCommand c -> {
                    Response res = service.dispatch(
                        new Request.EclipseCommand(c.commandId(), c.file(), c.offset(), c.params()));
                    logIfError("EclipseCommand", res);
                }
                case ViewAction.ShowView sv -> {
                    Response res = service.dispatch(new Request.ShowView(sv.viewId(), sv.secondaryId()));
                    logIfError("ShowView", res);
                }
                case ViewAction.Custom c -> {
                    Response res = service.dispatch(new Request.CustomCommand(c.target(), c.action(), c.args()));
                    logIfError("CustomCommand", res);
                }
                case ViewAction.Emit e -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("event", "ViewAction");
                    obj.addProperty("viewId", viewId);
                    obj.addProperty("action", e.name());
                    obj.addProperty("itemId", itemId);
                    obj.addProperty("payload", e.payload());
                    obj.addProperty("timestampMs", System.currentTimeMillis());
                    service.publishEvent(obj);
                }
                case ViewAction.Inspect ins -> dispatchInspect(service, ins);
                case ViewAction.ToolkitView tv -> dispatchToolkitView(service, tv);
            }
        } catch (Throwable t) {
            System.err.println("[Equimacs ViewActionDispatcher] Failed to dispatch "
                + action.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void dispatchInspect(IBridgeService service, ViewAction.Inspect ins) {
        String targetKind = ins.targetKind() == null ? "" : ins.targetKind().trim().toLowerCase(Locale.ROOT);
        long frameId = ins.frameId() != null ? ins.frameId() : 0L;
        String targetViewId = (ins.viewId() == null || ins.viewId().isBlank()) ? null : ins.viewId().trim();
        switch (targetKind) {
            case "jdi-frame" -> {
                Response res = service.dispatch(new Request.InspectFrame(frameId, Boolean.TRUE, targetViewId));
                logIfError("InspectFrame", res);
            }
            case "jdi-value" -> {
                Response res = service.dispatch(
                    new Request.InspectValue(frameId, ins.expression(), 2, Boolean.TRUE, targetViewId));
                logIfError("InspectValue", res);
            }
            case "jdt-symbol" -> {
                Response res = service.dispatch(
                    new Request.InspectSymbol(ins.file(), ins.offset(), Boolean.TRUE, targetViewId));
                logIfError("InspectSymbol", res);
            }
            default -> System.err.println(
                "[Equimacs ViewActionDispatcher] Unknown inspect targetKind: " + ins.targetKind());
        }
    }

    private static void dispatchToolkitView(IBridgeService service, ViewAction.ToolkitView tv) {
        String tool = tv.tool() == null ? "" : tv.tool().trim().toLowerCase(Locale.ROOT);
        Map<String, String> params = tv.params();
        switch (tool) {
            case "call-explorer" -> {
                Response res = service.dispatch(new Request.OpenCallExplorer(
                    param(params, "file", null),
                    parseInt(params.get("offset"), -1),
                    param(params, "direction", "callers"),
                    parseInt(params.get("depth"), 3),
                    parseBool(params.get("hideJdk"), Boolean.TRUE),
                    param(params, "viewMode", "hybrid"),
                    param(params, "viewId", null),
                    param(params, "groupBy", null),
                    parseInt(params.get("maxFanout"), 0),
                    param(params, "compareFile", null),
                    parseInt(params.get("compareOffset"), -1)));
                logIfError("OpenCallExplorer", res);
            }
            case "trace" -> {
                Response res = service.dispatch(new Request.OpenTraceView(
                    parseInt(params.get("limit"), 200),
                    parseBool(params.get("live"), Boolean.TRUE),
                    param(params, "viewId", null)));
                logIfError("OpenTraceView", res);
            }
            case "trace-clear" -> {
                Response clearRes = service.dispatch(new Request.ClearTrace());
                logIfError("ClearTrace", clearRes);
                Response openRes = service.dispatch(new Request.OpenTraceView(
                    parseInt(params.get("limit"), 200),
                    parseBool(params.get("live"), Boolean.TRUE),
                    param(params, "viewId", null)));
                logIfError("OpenTraceView", openRes);
            }
            case "junit" -> {
                Response res = service.dispatch(new Request.OpenJUnitView(
                    param(params, "runId", null),
                    parseBool(params.get("failuresOnly"), Boolean.FALSE),
                    parseBool(params.get("live"), Boolean.TRUE),
                    param(params, "viewId", null)));
                logIfError("OpenJUnitView", res);
            }
            case "junit-rerun" -> {
                Response runRes = service.dispatch(new Request.RunTests(
                    param(params, "project", null),
                    param(params, "className", null),
                    param(params, "methodName", null),
                    null,
                    null,
                    Boolean.TRUE,
                    60_000));
                logIfError("RunTests", runRes);
                Response openRes = service.dispatch(new Request.OpenJUnitView(
                    null,
                    parseBool(params.get("failuresOnly"), Boolean.FALSE),
                    parseBool(params.get("live"), Boolean.TRUE),
                    param(params, "viewId", null)));
                logIfError("OpenJUnitView", openRes);
            }
            default -> System.err.println(
                "[Equimacs ViewActionDispatcher] Unknown toolkit-view tool: " + tv.tool());
        }
    }

    private static String param(Map<String, String> params, String key, String defaultValue) {
        String value = params.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Boolean parseBool(String raw, Boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return Boolean.TRUE;
        }
        if ("false".equals(lower)) {
            return Boolean.FALSE;
        }
        return defaultValue;
    }

    private static void logIfError(String actionName, Response response) {
        if (response instanceof Response.Error err) {
            System.err.println("[Equimacs ViewActionDispatcher] " + actionName
                + " returned error: " + err.message());
        }
    }
}
