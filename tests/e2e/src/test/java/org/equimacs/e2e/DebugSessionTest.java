package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebugSessionTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("hello-java");
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void resetSession() throws Exception {
        harness.rpc().request(new Request.Terminate());
        harness.rpc().request(new Request.ClearAllBreakpoints());
        harness.rpc().request(new Request.RefreshProject("hello-java"));
    }

    @Test
    void launchAndBreakpointHit() throws Exception {
        JsonObject launches = harness.rpc().request(new Request.ListLaunches());
        assertTrue(launches.has("result"), launches::toString);
        assertTrue(containsString(launches.getAsJsonArray("result"), "Hello"), launches::toString);

        JsonObject breakpoint = harness.rpc().request(new Request.SetBreakpoint("/hello-java/src/Hello.java", 4, null));
        assertTrue(breakpoint.has("result"), breakpoint::toString);

        JsonObject launch = harness.rpc().request(new Request.Launch("Hello"));
        assertTrue(launch.has("result"), launch::toString);
        assertEquals("launched: Hello", launch.get("result").getAsString());

        JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(30_000));
        assertTrue(eventResponse.has("result"), eventResponse::toString);
        JsonObject event = eventResponse.getAsJsonObject("result");
        assertEquals("BreakpointHit", event.get("event").getAsString(), eventResponse::toString);
        assertEquals("Hello.java", event.get("file").getAsString(), eventResponse::toString);
        assertEquals(4, event.get("line").getAsInt(), eventResponse::toString);
        assertEquals("Hello", event.get("class").getAsString(), eventResponse::toString);
    }

    private static boolean containsString(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value.getAsString().equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
