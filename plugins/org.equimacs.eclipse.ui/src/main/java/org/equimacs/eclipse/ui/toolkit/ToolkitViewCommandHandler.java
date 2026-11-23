package org.equimacs.eclipse.ui.toolkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;

/**
 * OSGi DS bridge command handler for built-in Moldable Toolkit views
 * ({@code OpenCallExplorer}, {@code OpenTraceView}, {@code OpenJUnitView}).
 */
public final class ToolkitViewCommandHandler implements IBridgeCommandHandler {

    private static final Gson GSON = new Gson();
    private static final List<String> SOURCE_PREFIXES = List.of(
        "src/test/java/",
        "src/main/java/",
        "src/",
        "test/",
        ""
    );

    private volatile IBridgeService bridge;

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        if (this.bridge == bridge) {
            this.bridge = null;
        }
    }

    void activate() {
        // Intentionally empty so activation remains safe before workspace/workbench initialization.
    }

    void deactivate() {
        bridge = null;
    }

    @Override
    public Object handle(Request req) throws Exception {
        return switch (req) {
            case Request.OpenCallExplorer r -> openCallExplorer(r);
            case Request.OpenTraceView r -> openTraceView(r);
            case Request.OpenJUnitView r -> openJUnitView(r);
            default -> throw new IllegalArgumentException(
                "ToolkitViewCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }

    private Object openCallExplorer(Request.OpenCallExplorer req) {
        if (req.file() == null || req.file().isBlank()) {
            throw new IllegalArgumentException("OpenCallExplorer requires a non-blank file path");
        }
        if (req.offset() < 0) {
            throw new IllegalArgumentException("OpenCallExplorer requires a non-negative offset");
        }

        IBridgeService service = requireBridge();
        String direction = CallExplorerSpecBuilder.normalizeDirection(req.direction());
        int depth = CallExplorerSpecBuilder.clampDepth(req.depth());
        boolean hideJdk = req.hideJdk() == null || req.hideJdk();
        String viewMode = CallExplorerSpecBuilder.normalizeViewMode(req.viewMode());
        String viewId = (req.viewId() != null && !req.viewId().isBlank())
            ? req.viewId().trim()
            : CallExplorerSpecBuilder.defaultViewId(req.file(), req.offset());

        JsonObject callersPayload = ("callers".equals(direction) || "both".equals(direction))
            ? dispatchJsonObject(service, new Request.CallHierarchy(req.file(), req.offset(), "callers", depth))
            : null;
        JsonObject calleesPayload = ("callees".equals(direction) || "both".equals(direction))
            ? dispatchJsonObject(service, new Request.CallHierarchy(req.file(), req.offset(), "callees", depth))
            : null;

        CallExplorerSpecBuilder.CallExplorerOptions options = new CallExplorerSpecBuilder.CallExplorerOptions(
            req.file(), req.offset(), direction, depth, hideJdk, viewMode, viewId);
        String specJson = CallExplorerSpecBuilder.buildSpecJson(options, callersPayload, calleesPayload);
        return dispatchViewOpen(service, viewId, specJson);
    }

    private Object openTraceView(Request.OpenTraceView req) {
        IBridgeService service = requireBridge();
        int limit = ExecutionTraceSpecBuilder.clampLimit(req.limit());
        boolean live = req.live() == null || req.live();
        String viewId = (req.viewId() != null && !req.viewId().isBlank())
            ? req.viewId().trim()
            : ExecutionTraceSpecBuilder.defaultViewId();

        JsonObject tracePayload = dispatchJsonObject(service, new Request.GetTrace(limit));
        ExecutionTraceSpecBuilder.TraceViewOptions options =
            new ExecutionTraceSpecBuilder.TraceViewOptions(limit, live, viewId);
        String specJson = ExecutionTraceSpecBuilder.buildSpecJson(options, tracePayload);
        return dispatchViewOpen(service, viewId, specJson);
    }

    private Object openJUnitView(Request.OpenJUnitView req) {
        IBridgeService service = requireBridge();
        boolean failuresOnly = Boolean.TRUE.equals(req.failuresOnly());
        boolean live = req.live() == null || req.live();
        String viewId = (req.viewId() != null && !req.viewId().isBlank())
            ? req.viewId().trim()
            : JUnitViewSpecBuilder.defaultViewId();

        Response response = service.dispatch(new Request.GetTestResults(req.runId(), failuresOnly));
        JsonObject testResultsPayload;
        switch (response) {
            case Response.Success s -> {
                testResultsPayload = toJsonObject(s.result());
                enrichJUnitFramePaths(testResultsPayload);
            }
            case Response.Error e -> {
                String msg = e.message();
                if (isNoTestRunsError(msg)) {
                    testResultsPayload = null;
                } else {
                    throw new IllegalStateException(
                        msg != null && !msg.isBlank() ? msg : "GetTestResults failed");
                }
            }
            default -> throw new IllegalStateException("Unexpected GetTestResults response: " + response);
        }

        JUnitViewSpecBuilder.JUnitViewOptions options =
            new JUnitViewSpecBuilder.JUnitViewOptions(req.runId(), failuresOnly, live, viewId);
        String specJson = JUnitViewSpecBuilder.buildSpecJson(options, testResultsPayload);
        return dispatchViewOpen(service, viewId, specJson);
    }

    private IBridgeService requireBridge() {
        IBridgeService service = bridge;
        if (service == null) {
            throw new IllegalStateException("Bridge service unavailable");
        }
        return service;
    }

    private static JsonObject dispatchJsonObject(IBridgeService service, Request request) {
        Response response = service.dispatch(request);
        return switch (response) {
            case Response.Success s -> toJsonObject(s.result());
            case Response.Error e -> throw new IllegalStateException(
                e.message() != null && !e.message().isBlank()
                    ? e.message()
                    : request.getClass().getSimpleName() + " failed");
            default -> throw new IllegalStateException(
                "Unexpected response for " + request.getClass().getSimpleName() + ": " + response);
        };
    }

    private static Object dispatchViewOpen(IBridgeService service, String viewId, String specJson) {
        Response response = service.dispatch(new Request.ViewOpen(viewId, null, specJson));
        return switch (response) {
            case Response.Success s -> s.result();
            case Response.Error e -> throw new IllegalStateException(
                e.message() != null && !e.message().isBlank() ? e.message() : "ViewOpen failed");
            default -> throw new IllegalStateException("Unexpected ViewOpen response: " + response);
        };
    }

    private static JsonObject toJsonObject(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof JsonObject obj) {
            return obj;
        }
        JsonElement tree = GSON.toJsonTree(result);
        if (tree != null && tree.isJsonObject()) {
            return tree.getAsJsonObject();
        }
        throw new IllegalStateException("Expected JSON object result from backend, got " + result.getClass().getSimpleName());
    }

    private static boolean isNoTestRunsError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("no junit test runs");
    }

    private static void enrichJUnitFramePaths(JsonObject payload) {
        if (payload == null || !payload.has("project") || !payload.has("tests")) {
            return;
        }
        JsonElement projEl = payload.get("project");
        if (projEl == null || !projEl.isJsonPrimitive()) {
            return;
        }
        String projectName = projEl.getAsString();
        if (projectName == null || projectName.isBlank()) {
            return;
        }
        IProject project;
        try {
            project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.trim());
        } catch (IllegalStateException e) {
            return;
        }
        if (project == null || !project.isAccessible()) {
            return;
        }
        JsonElement testsEl = payload.get("tests");
        if (testsEl == null || !testsEl.isJsonArray()) {
            return;
        }
        JsonArray tests = testsEl.getAsJsonArray();
        for (int i = 0; i < tests.size(); i++) {
            JsonElement tcEl = tests.get(i);
            if (tcEl == null || !tcEl.isJsonObject()) {
                continue;
            }
            JsonObject tc = tcEl.getAsJsonObject();
            JsonElement failEl = tc.get("failure");
            if (failEl == null || !failEl.isJsonObject()) {
                continue;
            }
            JsonElement framesEl = failEl.getAsJsonObject().get("frames");
            if (framesEl == null || !framesEl.isJsonArray()) {
                continue;
            }
            JsonArray frames = framesEl.getAsJsonArray();
            for (int f = 0; f < frames.size(); f++) {
                JsonElement frameEl = frames.get(f);
                if (frameEl == null || !frameEl.isJsonObject()) {
                    continue;
                }
                JsonObject frame = frameEl.getAsJsonObject();
                String resolved = resolveProjectFilePath(project, frame);
                if (resolved != null) {
                    frame.addProperty("path", resolved);
                }
            }
        }
    }

    private static String resolveProjectFilePath(IProject project, JsonObject frame) {
        JsonElement fileEl = frame.get("file");
        JsonElement lineEl = frame.get("line");
        if (fileEl == null || !fileEl.isJsonPrimitive()
                || lineEl == null || !lineEl.isJsonPrimitive()
                || !lineEl.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        String fileName = fileEl.getAsString();
        int line = lineEl.getAsInt();
        if (fileName == null || fileName.isBlank() || line <= 0) {
            return null;
        }
        String className = frame.has("class") && frame.get("class").isJsonPrimitive()
            ? frame.get("class").getAsString()
            : "";
        int lastDot = className.lastIndexOf('.');
        String pkgPath = lastDot > 0 ? className.substring(0, lastDot).replace('.', '/') + "/" : "";

        for (String prefix : SOURCE_PREFIXES) {
            IResource member = project.findMember(prefix + pkgPath + fileName.trim());
            if (member instanceof IFile file && file.exists()) {
                return file.getFullPath().toString();
            }
        }
        return null;
    }
}
