package org.equimacs.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.equimacs.cli.EquimacsCLI;
import org.equimacs.protocol.Request;

final class EqmdRpc {
    private final Path socket;

    EqmdRpc(Path socket) {
        this.socket = socket;
    }

    JsonObject request(Request request) throws Exception {
        String response = EquimacsCLI.sendRequest(request, socket);
        return JsonParser.parseString(response).getAsJsonObject();
    }
}
