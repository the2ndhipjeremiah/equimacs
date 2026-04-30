package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.equimacs.protocol.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BreakpointTest {
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
    void clearBreakpoints() throws Exception {
        harness.rpc().request(new Request.ClearAllBreakpoints());
        harness.rpc().request(new Request.RefreshProject("hello-java"));
    }

    @Test
    void breakpointSetAndList() throws Exception {
        JsonObject set = harness.rpc().request(new Request.SetBreakpoint("/hello-java/src/Hello.java", 4, null));
        assertTrue(set.has("result"), set::toString);

        JsonObject listed = harness.rpc().request(new Request.ListBreakpoints());
        assertTrue(listed.has("result"), listed::toString);
        JsonArray breakpoints = listed.getAsJsonArray("result");
        assertEquals(1, breakpoints.size(), listed::toString);

        JsonObject breakpoint = breakpoints.get(0).getAsJsonObject();
        assertEquals("/hello-java/src/Hello.java", breakpoint.get("resource").getAsString());
        assertEquals(4, breakpoint.get("line").getAsInt());
        assertEquals("Hello", breakpoint.get("typeName").getAsString());
        assertTrue(breakpoint.get("enabled").getAsBoolean(), listed::toString);
    }

    @Test
    void clearAllBreakpoints() throws Exception {
        JsonObject first = harness.rpc().request(new Request.SetBreakpoint("/hello-java/src/Hello.java", 4, null));
        assertTrue(first.has("result"), first::toString);
        JsonObject second = harness.rpc().request(new Request.SetBreakpoint("/hello-java/src/Hello.java", 5, null));
        assertTrue(second.has("result"), second::toString);

        JsonObject beforeClear = harness.rpc().request(new Request.ListBreakpoints());
        assertTrue(beforeClear.has("result"), beforeClear::toString);
        assertEquals(2, beforeClear.getAsJsonArray("result").size(), beforeClear::toString);

        JsonObject clear = harness.rpc().request(new Request.ClearAllBreakpoints());
        assertTrue(clear.has("result"), clear::toString);

        JsonObject afterClear = harness.rpc().request(new Request.ListBreakpoints());
        assertTrue(afterClear.has("result"), afterClear::toString);
        assertEquals(0, afterClear.getAsJsonArray("result").size(), afterClear::toString);
    }
}
