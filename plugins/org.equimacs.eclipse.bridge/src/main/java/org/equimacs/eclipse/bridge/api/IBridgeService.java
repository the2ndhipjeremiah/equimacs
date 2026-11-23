package org.equimacs.eclipse.bridge.api;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;

public interface IBridgeService {
    void publishEvent(JsonObject event);
    Response dispatch(Request req);

    default void addEventListener(Consumer<JsonObject> listener) {}
    default void removeEventListener(Consumer<JsonObject> listener) {}

    default Response dispatchJson(String requestType, String paramsJson) {
        return new Response.Error("JSON request dispatch unavailable", null);
    }
}
