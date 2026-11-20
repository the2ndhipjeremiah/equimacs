package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
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
        JsonObject build = harness.rpc().request(new Request.Build("hello-java", "full"));
        assertTrue(build.has("result"), build::toString);
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void resetSession() throws Exception {
        harness.rpc().request(new Request.Terminate());
        harness.rpc().request(new Request.ClearAllBreakpoints());
        harness.rpc().request(new Request.ClearTrace());
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

    @Test
    void evaluateExpressionInSuspendedFrame() throws Exception {
        JsonObject event = launchToBreakpoint(5);
        long threadId = event.get("threadId").getAsLong();

        JsonObject threadsResponse = harness.rpc().request(new Request.GetThreads());
        assertTrue(threadsResponse.has("result"), threadsResponse::toString);
        assertTrue(hasSuspendedThread(threadsResponse.getAsJsonArray("result"), threadId), threadsResponse::toString);

        JsonObject stackResponse = harness.rpc().request(new Request.GetStack(threadId));
        assertTrue(stackResponse.has("result"), stackResponse::toString);
        long frameId = stackResponse.getAsJsonArray("result").get(0).getAsJsonObject().get("id").getAsLong();

        JsonObject sumResp = harness.rpc().request(new Request.Evaluate(frameId, "value + next"));
        assertTrue(sumResp.has("result"), sumResp::toString);
        JsonObject sumResult = sumResp.getAsJsonObject("result");
        assertEquals("value + next", sumResult.get("expression").getAsString(), sumResp::toString);
        assertEquals("83", sumResult.get("value").getAsString(), sumResp::toString);
        assertEquals("int", sumResult.get("type").getAsString(), sumResp::toString);
        assertFalse(sumResult.get("isNull").getAsBoolean(), sumResp::toString);

        JsonObject strResp = harness.rpc().request(new Request.Evaluate(frameId, "\"hello \" + next"));
        assertTrue(strResp.has("result"), strResp::toString);
        JsonObject strResult = strResp.getAsJsonObject("result");
        assertEquals("hello 42", strResult.get("value").getAsString(), strResp::toString);
        assertEquals("java.lang.String", strResult.get("type").getAsString(), strResp::toString);
        assertFalse(strResult.get("isNull").getAsBoolean(), strResp::toString);

        JsonObject nextEvent = harness.rpc().request(new Request.WaitEvent(200));
        assertTrue(nextEvent.has("result"), nextEvent::toString);
        assertEquals("Timeout", nextEvent.getAsJsonObject("result").get("event").getAsString(), nextEvent::toString);
    }

    @Test
    void logpointStreamsNonBlockingTraceAndSnapshot() throws Exception {
        JsonObject setLp = harness.rpc().request(new Request.SetLogpoint(
            "/hello-java/src/TraceLoop.java",
            6,
            "i={i}, doubled={sum * 2}",
            null,
            Boolean.TRUE));
        assertTrue(setLp.has("result"), setLp::toString);
        JsonObject lpResult = setLp.getAsJsonObject("result");
        assertEquals("logpoint", lpResult.get("kind").getAsString(), setLp::toString);
        assertEquals(6, lpResult.get("line").getAsInt(), setLp::toString);

        JsonObject listed = harness.rpc().request(new Request.ListBreakpoints());
        assertTrue(listed.has("result"), listed::toString);
        JsonArray bps = listed.getAsJsonArray("result");
        assertEquals(1, bps.size(), listed::toString);
        JsonObject bpInfo = bps.get(0).getAsJsonObject();
        assertEquals("logpoint", bpInfo.get("kind").getAsString(), listed::toString);
        assertEquals("i={i}, doubled={sum * 2}", bpInfo.get("message").getAsString(), listed::toString);
        assertTrue(bpInfo.get("captureVars").getAsBoolean(), listed::toString);

        JsonObject launch = harness.rpc().request(new Request.Launch("TraceLoop"));
        assertTrue(launch.has("result"), launch::toString);
        assertEquals("launched: TraceLoop", launch.get("result").getAsString());

        List<JsonObject> hits = collectEventsUntil("Terminated", "BreakpointHit");
        assertEquals(4, hits.size(), () -> "Expected 4 LogpointHit events, got: " + hits);

        List<String> expectedMessages = List.of(
            "i=1, doubled=2",
            "i=2, doubled=6",
            "i=3, doubled=12",
            "i=4, doubled=20");
        List<String> expectedDoubled = List.of("2", "6", "12", "20");
        for (int idx = 0; idx < 4; idx++) {
            JsonObject ev = hits.get(idx);
            assertEquals("LogpointHit", ev.get("event").getAsString(), ev::toString);
            assertEquals("TraceLoop.java", ev.get("file").getAsString(), ev::toString);
            assertEquals("TraceLoop", ev.get("class").getAsString(), ev::toString);
            assertEquals("main", ev.get("method").getAsString(), ev::toString);
            assertEquals(6, ev.get("line").getAsInt(), ev::toString);
            assertEquals(expectedMessages.get(idx), ev.get("message").getAsString(), ev::toString);

            JsonObject evaluations = ev.getAsJsonObject("evaluations");
            assertTrue(evaluations.has("sum * 2"), ev::toString);
            assertEquals(
                expectedDoubled.get(idx),
                evaluations.getAsJsonObject("sum * 2").get("value").getAsString(),
                ev::toString);

            JsonArray vars = ev.getAsJsonArray("variables");
            assertTrue(hasVariable(vars, "i", Integer.toString(idx + 1)), ev::toString);
            assertTrue(hasVariable(vars, "sum", Integer.toString(Integer.parseInt(expectedDoubled.get(idx)) / 2)),
                ev::toString);
        }

        JsonObject traceAll = harness.rpc().request(new Request.GetTrace(10));
        assertTrue(traceAll.has("result"), traceAll::toString);
        JsonObject allPage = traceAll.getAsJsonObject("result");
        assertEquals(4, allPage.get("count").getAsInt(), traceAll::toString);
        assertEquals(4, allPage.getAsJsonArray("entries").size(), traceAll::toString);

        JsonObject traceTail = harness.rpc().request(new Request.GetTrace(2));
        assertTrue(traceTail.has("result"), traceTail::toString);
        JsonObject tailPage = traceTail.getAsJsonObject("result");
        assertEquals(2, tailPage.get("count").getAsInt(), traceTail::toString);
        JsonArray tailEntries = tailPage.getAsJsonArray("entries");
        assertEquals("i=3, doubled=12", tailEntries.get(0).getAsJsonObject().get("message").getAsString(), traceTail::toString);
        assertEquals("i=4, doubled=20", tailEntries.get(1).getAsJsonObject().get("message").getAsString(), traceTail::toString);

        JsonObject cleared = harness.rpc().request(new Request.ClearTrace());
        assertTrue(cleared.has("result"), cleared::toString);

        JsonObject afterClear = harness.rpc().request(new Request.GetTrace(10));
        assertTrue(afterClear.has("result"), afterClear::toString);
        assertEquals(0, afterClear.getAsJsonObject("result").get("count").getAsInt(), afterClear::toString);
    }

    @Test
    void conditionalLogpointCoexistsWithBreakpoint() throws Exception {
        JsonObject setLp = harness.rpc().request(new Request.SetLogpoint(
            "/hello-java/src/TraceLoop.java",
            6,
            "even i={i}",
            "i % 2 == 0",
            Boolean.FALSE));
        assertTrue(setLp.has("result"), setLp::toString);

        JsonObject setBp = harness.rpc().request(new Request.SetBreakpoint(
            "/hello-java/src/TraceLoop.java",
            8,
            null));
        assertTrue(setBp.has("result"), setBp::toString);

        JsonObject launch = harness.rpc().request(new Request.Launch("TraceLoop"));
        assertTrue(launch.has("result"), launch::toString);
        assertEquals("launched: TraceLoop", launch.get("result").getAsString());

        List<JsonObject> collected = collectEventsUntil("BreakpointHit", null);
        assertFalse(collected.isEmpty(), "Expected LogpointHit and BreakpointHit events");

        JsonObject bpHit = collected.get(collected.size() - 1);
        assertEquals("BreakpointHit", bpHit.get("event").getAsString(), bpHit::toString);
        assertEquals("TraceLoop.java", bpHit.get("file").getAsString(), bpHit::toString);
        assertEquals(8, bpHit.get("line").getAsInt(), bpHit::toString);

        List<JsonObject> lpHits = new ArrayList<>();
        for (int i = 0; i < collected.size() - 1; i++) {
            JsonObject ev = collected.get(i);
            if ("LogpointHit".equals(ev.get("event").getAsString())) {
                lpHits.add(ev);
            }
        }
        assertEquals(2, lpHits.size(), () -> "Expected 2 filtered LogpointHit events, got: " + collected);
        assertEquals("even i=2", lpHits.get(0).get("message").getAsString(), () -> lpHits.get(0).toString());
        assertEquals("even i=4", lpHits.get(1).get("message").getAsString(), () -> lpHits.get(1).toString());
        assertFalse(lpHits.get(0).has("variables"), () -> lpHits.get(0).toString());
        assertFalse(lpHits.get(1).has("variables"), () -> lpHits.get(1).toString());
    }

    private static List<JsonObject> collectEventsUntil(String stopEvent, String forbiddenEvent) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        List<JsonObject> events = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1_000));
            assertTrue(eventResponse.has("result"), eventResponse::toString);
            JsonObject event = eventResponse.getAsJsonObject("result");
            String type = event.get("event").getAsString();
            if ("Timeout".equals(type)) {
                continue;
            }
            if (forbiddenEvent != null && forbiddenEvent.equals(type)) {
                throw new AssertionError("Unexpected forbidden event " + type + ": " + event);
            }
            if (stopEvent.equals(type)) {
                if ("BreakpointHit".equals(stopEvent)) {
                    events.add(event);
                }
                return events;
            }
            if ("LogpointHit".equals(type)) {
                events.add(event);
            }
        }
        throw new AssertionError("Timed out waiting for " + stopEvent + ". Collected: " + events);
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
        List<String> seenEvents = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1_000));
            assertTrue(eventResponse.has("result"), eventResponse::toString);
            JsonObject event = eventResponse.getAsJsonObject("result");
            lastEvent = event;
            String type = event.get("event").getAsString();
            if (!"Timeout".equals(type)) {
                seenEvents.add(event.toString());
            }
            if (type.equals(eventName)) {
                return event;
            }
        }
        throw new AssertionError(
            "Timed out waiting for " + eventName + ". Seen events: " + seenEvents + ". Last event: " + lastEvent);
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
