package org.equimacs.debug;

import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

public final class DebugCommandHandler implements IBridgeCommandHandler {

    private volatile IBridgeService bridge;

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void activate() {
        // Round 1 stub. Real debug logic moves here in Round 2.
    }

    void deactivate() {
    }

    @Override
    public Object handle(Request req) {
        return "pong from debug bundle (round 1 stub)";
    }
}
