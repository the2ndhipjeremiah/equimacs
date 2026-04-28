package org.equimacs.eclipse.bridge.internal;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.equimacs.eclipse.bridge.Activator;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;

public final class BridgeServiceImpl implements IBridgeService {

    private final Map<String, IBridgeCommandHandler> dispatchMap = new ConcurrentHashMap<>();

    void activate() {
        Activator.logInfo("BridgeServiceImpl activated");
    }

    void deactivate() {
        Activator.logInfo("BridgeServiceImpl deactivated; clearing " + dispatchMap.size() + " handler(s)");
        dispatchMap.clear();
    }

    @Override
    public void publishEvent(JsonObject event) {
        // Round 1 stub: events still flow through the legacy controller queue.
        // Round 2 wires this into the bridge's WaitEvent queue.
    }

    public Map<String, IBridgeCommandHandler> handlers() {
        return Map.copyOf(dispatchMap);
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
        if (val instanceof String[] arr) return arr;
        if (val instanceof String s) return s.split(",");
        return new String[0];
    }
}
