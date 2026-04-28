package org.equimacs.eclipse.bridge.api;

import org.equimacs.protocol.Request;

public interface IBridgeCommandHandler {
    Object handle(Request req) throws Exception;
}
