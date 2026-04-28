package org.equimacs.eclipse.bridge.api;

import com.google.gson.JsonObject;

public interface IBridgeService {
    void publishEvent(JsonObject event);
}
