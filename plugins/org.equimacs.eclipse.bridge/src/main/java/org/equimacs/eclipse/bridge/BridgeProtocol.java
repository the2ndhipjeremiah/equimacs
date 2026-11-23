package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.equimacs.protocol.Request;

public final class BridgeProtocol {
    private static final Gson GSON = createGson();

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

    /**
     * Deserializes a typed {@link Request} from a request type name and JSON object parameter payload.
     */
    public static Request deserializeTyped(String requestType, String paramsJson) {
        if (requestType == null || requestType.isBlank()) {
            throw new IllegalArgumentException("requestType must not be blank");
        }
        JsonObject obj;
        if (paramsJson == null || paramsJson.isBlank()) {
            obj = new JsonObject();
        } else {
            JsonElement parsed = JsonParser.parseString(paramsJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("paramsJson must be a JSON object");
            }
            obj = parsed.getAsJsonObject().deepCopy();
        }
        obj.addProperty("type", requestType.trim());
        return GSON.fromJson(obj, Request.class);
    }

    private static Class<? extends Request> requestClassFor(String type) {
        return switch (type) {
            case "SetBreakpoint" -> Request.SetBreakpoint.class;
            case "SetLogpoint" -> Request.SetLogpoint.class;
            case "GetTrace" -> Request.GetTrace.class;
            case "ClearTrace" -> Request.ClearTrace.class;
            case "ClearAllBreakpoints" -> Request.ClearAllBreakpoints.class;
            case "ListBreakpoints" -> Request.ListBreakpoints.class;
            case "Resume" -> Request.Resume.class;
            case "Suspend" -> Request.Suspend.class;
            case "Step" -> Request.Step.class;
            case "GogoExec" -> Request.GogoExec.class;
            case "Reload" -> Request.Reload.class;
            case "HandlerDiagnostics" -> Request.HandlerDiagnostics.class;
            case "GetWorkspace" -> Request.GetWorkspace.class;
            case "GetThreads" -> Request.GetThreads.class;
            case "GetStack" -> Request.GetStack.class;
            case "GetVariables" -> Request.GetVariables.class;
            case "Evaluate" -> Request.Evaluate.class;
            case "GetProblems" -> Request.GetProblems.class;
            case "Build" -> Request.Build.class;
            case "GetQuickFixes" -> Request.GetQuickFixes.class;
            case "ApplyFix" -> Request.ApplyFix.class;
            case "GetClasspath" -> Request.GetClasspath.class;
            case "GetProjectDescription" -> Request.GetProjectDescription.class;
            case "RefreshProject" -> Request.RefreshProject.class;
            case "PrepareRenameSymbol" -> Request.PrepareRenameSymbol.class;
            case "ApplyPreparedRefactoring" -> Request.ApplyPreparedRefactoring.class;
            case "AbortPreparedRefactoring" -> Request.AbortPreparedRefactoring.class;
            case "GetPreparedRefactoring" -> Request.GetPreparedRefactoring.class;
            case "WaitEvent" -> Request.WaitEvent.class;
            case "Launch" -> Request.Launch.class;
            case "ListLaunches" -> Request.ListLaunches.class;
            case "ListSessions" -> Request.ListSessions.class;
            case "Terminate" -> Request.Terminate.class;
            case "AddReviewComment" -> Request.AddReviewComment.class;
            case "ListReviewComments" -> Request.ListReviewComments.class;
            case "ReplyToComment" -> Request.ReplyToComment.class;
            case "ResolveComment" -> Request.ResolveComment.class;
            case "ReviewDiagnostics" -> Request.ReviewDiagnostics.class;
            case "EclipseWorkbench" -> Request.EclipseWorkbench.class;
            case "EclipseEditorDiagnostics" -> Request.EclipseEditorDiagnostics.class;
            case "ReviewEditorDiagnostics" -> Request.ReviewEditorDiagnostics.class;
            case "Shutdown" -> Request.Shutdown.class;
            case "CustomCommand" -> Request.CustomCommand.class;
            case "FindReferences" -> Request.FindReferences.class;
            case "CallHierarchy" -> Request.CallHierarchy.class;
            case "TypeHierarchy" -> Request.TypeHierarchy.class;
            case "OpenFile" -> Request.OpenFile.class;
            case "RunTests" -> Request.RunTests.class;
            case "GetTestResults" -> Request.GetTestResults.class;
            case "GetConsoleOutput" -> Request.GetConsoleOutput.class;
            case "ClearConsoleOutput" -> Request.ClearConsoleOutput.class;
            case "EclipseCommand" -> Request.EclipseCommand.class;
            case "ShowView" -> Request.ShowView.class;
            case "UiTree" -> Request.UiTree.class;
            case "UiClick" -> Request.UiClick.class;
            case "UiType" -> Request.UiType.class;
            case "ViewOpen" -> Request.ViewOpen.class;
            case "ViewUpdate" -> Request.ViewUpdate.class;
            case "ViewClose" -> Request.ViewClose.class;
            case "ViewList" -> Request.ViewList.class;
            case "InspectFrame" -> Request.InspectFrame.class;
            case "InspectValue" -> Request.InspectValue.class;
            case "InspectSymbol" -> Request.InspectSymbol.class;
            case "ListInspectProviders" -> Request.ListInspectProviders.class;
            case "OpenCallExplorer" -> Request.OpenCallExplorer.class;
            case "OpenTraceView" -> Request.OpenTraceView.class;
            case "OpenJUnitView" -> Request.OpenJUnitView.class;
            default -> throw new JsonParseException("Unknown request type: " + type);
        };
    }
}
