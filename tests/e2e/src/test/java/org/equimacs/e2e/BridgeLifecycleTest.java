package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BridgeLifecycleTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.start();
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void gogoLbListsEquimacsBundles() throws Exception {
        JsonObject response = harness.rpc().request(new Request.GogoExec("lb org.equimacs"));

        assertTrue(response.has("result"), response::toString);
        String output = response.get("result").getAsString();
        assertTrue(output.contains("Equimacs Eclipse Bridge"), output);
        assertTrue(output.contains("Equimacs Debug"), output);
        assertTrue(output.contains("Equimacs Headless Application"), output);
        assertTrue(output.contains("Active"), output);
    }
}
