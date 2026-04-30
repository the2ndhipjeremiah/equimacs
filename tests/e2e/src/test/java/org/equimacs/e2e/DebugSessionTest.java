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
        drainEvents();
    }

    @Test
    void launchAndBreakpointHit() throws Exception {
        JsonObject event = launchToBreakpoint(4);

        assertEquals("BreakpointHit", event.get("event").getAsString(), event::toString);
        assertEquals("Hello.java", event.get("file").getAsString(), event::toString);
        assertEquals(4, event.get("line").getAsInt(), event::toString);
        assertEquals("Hello", event.get("class").getAsString(), event::toString);
    }

    @Test
    void stackAndVarsAfterHit() throws Exception {
        JsonObject event = launchToBreakpoint(5);

        long threadId = event.get("threadId").getAsLong();
        JsonObject threadsResponse = harness.rpc().request(new Request.GetThreads());
        assertTrue(threadsResponse.has("result"), threadsResponse::toString);
        assertTrue(hasSuspendedThread(threadsResponse.getAsJsonArray("result"), threadId), threadsResponse::toString);

        JsonObject stackResponse = harness.rpc().request(new Request.GetStack(threadId));
        assertTrue(stackResponse.has("result"), stackResponse::toString);
        JsonObject topFrame = stackResponse.getAsJsonArray("result").get(0).getAsJsonObject();
        assertEquals("Hello", topFrame.get("class").getAsString(), stackResponse::toString);
        assertEquals("main", topFrame.get("method").getAsString(), stackResponse::toString);
        assertEquals(5, topFrame.get("line").getAsInt(), stackResponse::toString);

        JsonObject varsResponse = harness.rpc().request(new Request.GetVariables(topFrame.get("id").getAsLong()));
        assertTrue(varsResponse.has("result"), varsResponse::toString);
        JsonArray variables = varsResponse.getAsJsonArray("result");
        assertTrue(hasVariable(variables, "value", "41"), varsResponse::toString);
        assertTrue(hasVariable(variables, "next", "42"), varsResponse::toString);
    }

    @Test
    void stepOverAdvancesLine() throws Exception {
        JsonObject hit = launchToBreakpoint(3);
        assertEquals(3, hit.get("line").getAsInt(), hit::toString);

        JsonObject step = harness.rpc().request(new Request.Step(Request.StepType.OVER));
        assertTrue(step.has("result"), step::toString);
        assertEquals("Step executed", step.get("result").getAsString());

        JsonObject event = waitForEvent("StepCompleted");
        assertEquals("Hello.java", event.get("file").getAsString(), event::toString);
        assertEquals("Hello", event.get("class").getAsString(), event::toString);
        assertEquals(4, event.get("line").getAsInt(), event::toString);
    }

    @Test
    void terminateClearsSession() throws Exception {
        launchToBreakpoint(4);

        JsonObject sessionsBefore = harness.rpc().request(new Request.ListSessions());
        assertTrue(sessionsBefore.has("result"), sessionsBefore::toString);
        assertTrue(sessionsBefore.getAsJsonArray("result").size() > 0, sessionsBefore::toString);

        JsonObject terminate = harness.rpc().request(new Request.Terminate());
        assertTrue(terminate.has("result"), terminate::toString);
        assertEquals("Terminated 1 session(s)", terminate.get("result").getAsString());

        JsonObject terminated = waitForEvent("Terminated");
        assertEquals("Terminated", terminated.get("event").getAsString(), terminated::toString);

        JsonObject sessionsAfter = harness.rpc().request(new Request.ListSessions());
        assertTrue(sessionsAfter.has("result"), sessionsAfter::toString);
        assertEquals(0, sessionsAfter.getAsJsonArray("result").size(), sessionsAfter::toString);
    }

    private static JsonObject launchToBreakpoint(int line) throws Exception {
        JsonObject launches = harness.rpc().request(new Request.ListLaunches());
        assertTrue(launches.has("result"), launches::toString);
        assertTrue(containsString(launches.getAsJsonArray("result"), "Hello"), launches::toString);

        JsonObject breakpoint = harness.rpc().request(new Request.SetBreakpoint("/hello-java/src/Hello.java", line, null));
        assertTrue(breakpoint.has("result"), breakpoint::toString);

        JsonObject launch = harness.rpc().request(new Request.Launch("Hello"));
        assertTrue(launch.has("result"), launch::toString);
        assertEquals("launched: Hello", launch.get("result").getAsString());

        return waitForBreakpointHit();
    }

    private static JsonObject waitForBreakpointHit() throws Exception {
        return waitForEvent("BreakpointHit");
    }

    private static JsonObject waitForEvent(String eventName) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        JsonObject lastEvent = null;
        while (System.nanoTime() < deadline) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1_000));
            assertTrue(eventResponse.has("result"), eventResponse::toString);
            JsonObject event = eventResponse.getAsJsonObject("result");
            lastEvent = event;
            if (event.get("event").getAsString().equals(eventName)) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for " + eventName + ". Last event: " + lastEvent);
    }

    private static void drainEvents() throws Exception {
        while (true) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1));
            if (!eventResponse.has("result")) return;
            JsonObject event = eventResponse.getAsJsonObject("result");
            if (event.get("event").getAsString().equals("Timeout")) return;
        }
    }

    private static boolean hasSuspendedThread(JsonArray threads, long threadId) {
        for (JsonElement element : threads) {
            JsonObject thread = element.getAsJsonObject();
            if (thread.get("id").getAsLong() == threadId && thread.get("suspended").getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVariable(JsonArray variables, String name, String value) {
        for (JsonElement element : variables) {
            JsonObject variable = element.getAsJsonObject();
            if (variable.get("name").getAsString().equals(name)
                && variable.get("value").getAsString().equals(value)) {
                return true;
            }
        }
        return false;
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
