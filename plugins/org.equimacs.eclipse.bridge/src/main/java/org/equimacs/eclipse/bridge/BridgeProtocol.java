package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.equimacs.protocol.Request;

final class BridgeProtocol {
    private BridgeProtocol() {}

    static Gson createGson() {
        return new GsonBuilder()
            .registerTypeAdapter(Request.class, (JsonDeserializer<Request>) (json, typeOfT, context) -> {
                JsonObject obj = json.getAsJsonObject();
                if (!obj.has("type")) {
                    throw new JsonParseException("Missing 'type' field in Request");
                }
                return context.deserialize(obj, requestClassFor(obj.get("type").getAsString()));
            })
            .create();
    }

    private static Class<? extends Request> requestClassFor(String type) {
        return switch (type) {
            case "SetBreakpoint" -> Request.SetBreakpoint.class;
            case "ClearAllBreakpoints" -> Request.ClearAllBreakpoints.class;
            case "ListBreakpoints" -> Request.ListBreakpoints.class;
            case "Resume" -> Request.Resume.class;
            case "Suspend" -> Request.Suspend.class;
            case "Step" -> Request.Step.class;
            case "GogoExec" -> Request.GogoExec.class;
            case "Reload" -> Request.Reload.class;
            case "GetWorkspace" -> Request.GetWorkspace.class;
            case "GetThreads" -> Request.GetThreads.class;
            case "GetStack" -> Request.GetStack.class;
            case "GetVariables" -> Request.GetVariables.class;
            case "GetProblems" -> Request.GetProblems.class;
            case "Build" -> Request.Build.class;
            case "GetQuickFixes" -> Request.GetQuickFixes.class;
            case "ApplyFix" -> Request.ApplyFix.class;
            case "GetClasspath" -> Request.GetClasspath.class;
            case "GetProjectDescription" -> Request.GetProjectDescription.class;
            case "RefreshProject" -> Request.RefreshProject.class;
            case "WaitEvent" -> Request.WaitEvent.class;
            case "Launch" -> Request.Launch.class;
            case "ListLaunches" -> Request.ListLaunches.class;
            case "ListSessions" -> Request.ListSessions.class;
            case "Terminate" -> Request.Terminate.class;
            default -> throw new JsonParseException("Unknown request type: " + type);
        };
    }
}
