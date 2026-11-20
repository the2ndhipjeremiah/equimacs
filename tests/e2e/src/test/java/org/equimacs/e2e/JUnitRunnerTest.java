package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class JUnitRunnerTest {
    private static EqmdHarness harness;

    @BeforeAll
    static void startDaemon() throws Exception {
        harness = EqmdHarness.startWithFixture("junit-java");
        JsonObject build = harness.rpc().request(new Request.Build("junit-java", "full"));
        assertTrue(build.has("result"), build::toString);
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void drainBeforeTest() throws Exception {
        drainEvents();
    }

    @Test
    void runSinglePassingClassAndGetResults() throws Exception {
        JsonObject runResp = harness.rpc().request(new Request.RunTests(
            "junit-java", "demo.PassingTest", null, null, null, Boolean.TRUE, 60_000));
        assertTrue(runResp.has("result"), runResp::toString);
        JsonObject result = runResp.getAsJsonObject("result");
        String runId = result.get("runId").getAsString();
        assertEquals("PASSED", result.get("status").getAsString(), runResp::toString);
        assertEquals(1, result.get("total").getAsInt(), runResp::toString);
        assertEquals(1, result.get("passed").getAsInt(), runResp::toString);
        assertEquals(0, result.get("failed").getAsInt(), runResp::toString);
        assertEquals(0, result.get("errors").getAsInt(), runResp::toString);
        assertEquals(0, result.get("ignored").getAsInt(), runResp::toString);

        JsonObject fetchedResp = harness.rpc().request(new Request.GetTestResults(runId, Boolean.FALSE));
        assertTrue(fetchedResp.has("result"), fetchedResp::toString);
        JsonObject fetched = fetchedResp.getAsJsonObject("result");
        assertEquals(runId, fetched.get("runId").getAsString(), fetchedResp::toString);
        assertEquals("PASSED", fetched.get("status").getAsString(), fetchedResp::toString);
        assertEquals(1, fetched.getAsJsonArray("tests").size(), fetchedResp::toString);

        JsonObject finishedEvent = waitForEvent("TestRunFinished");
        assertEquals(runId, finishedEvent.get("runId").getAsString(), finishedEvent::toString);
        assertEquals("PASSED", finishedEvent.get("status").getAsString(), finishedEvent::toString);
        assertEquals(1, finishedEvent.get("passed").getAsInt(), finishedEvent::toString);
    }

    @Test
    void runMixedClassCapturesFailureTraceFramesAndFilter() throws Exception {
        JsonObject runResp = harness.rpc().request(new Request.RunTests(
            "junit-java", "demo.MathTest", null, null, null, Boolean.TRUE, 60_000));
        assertTrue(runResp.has("result"), runResp::toString);
        JsonObject result = runResp.getAsJsonObject("result");
        assertEquals("FAILED", result.get("status").getAsString(), runResp::toString);
        assertEquals(3, result.get("total").getAsInt(), runResp::toString);
        assertEquals(1, result.get("passed").getAsInt(), runResp::toString);
        assertEquals(1, result.get("failed").getAsInt(), runResp::toString);
        assertEquals(1, result.get("ignored").getAsInt(), runResp::toString);

        JsonObject failedCase = findTestCase(result.getAsJsonArray("tests"), "failsComparison");
        assertNotNull(failedCase, result::toString);
        assertEquals("FAILED", failedCase.get("outcome").getAsString(), failedCase::toString);
        JsonObject failure = failedCase.getAsJsonObject("failure");
        assertEquals("5", failure.get("expected").getAsString(), failure::toString);
        assertEquals("4", failure.get("actual").getAsString(), failure::toString);
        assertFalse(failure.get("summary").getAsString().isBlank(), failure::toString);
        assertFalse(failure.get("trace").getAsString().isBlank(), failure::toString);

        JsonArray frames = failure.getAsJsonArray("frames");
        assertTrue(hasFrame(frames, "demo.MathTest", "failsComparison", "MathTest.java"), failure::toString);

        JsonObject filteredResp = harness.rpc().request(new Request.GetTestResults(null, Boolean.TRUE));
        assertTrue(filteredResp.has("result"), filteredResp::toString);
        JsonArray filteredTests = filteredResp.getAsJsonObject("result").getAsJsonArray("tests");
        assertEquals(1, filteredTests.size(), filteredResp::toString);
        assertEquals("failsComparison", filteredTests.get(0).getAsJsonObject().get("method").getAsString(),
            filteredResp::toString);
    }

    @Test
    void runSingleMethodOnly() throws Exception {
        JsonObject runResp = harness.rpc().request(new Request.RunTests(
            "junit-java", "demo.MathTest", "addsNumbers", null, null, Boolean.TRUE, 60_000));
        assertTrue(runResp.has("result"), runResp::toString);
        JsonObject result = runResp.getAsJsonObject("result");
        assertEquals("PASSED", result.get("status").getAsString(), runResp::toString);
        assertEquals(1, result.get("total").getAsInt(), runResp::toString);
        JsonArray tests = result.getAsJsonArray("tests");
        assertEquals(1, tests.size(), runResp::toString);
        assertEquals("addsNumbers", tests.get(0).getAsJsonObject().get("method").getAsString(), runResp::toString);
    }

    @Test
    void runWholeProject() throws Exception {
        JsonObject runResp = harness.rpc().request(new Request.RunTests(
            "junit-java", null, null, null, null, Boolean.TRUE, 60_000));
        assertTrue(runResp.has("result"), runResp::toString);
        JsonObject result = runResp.getAsJsonObject("result");
        assertEquals("FAILED", result.get("status").getAsString(), runResp::toString);
        assertEquals(4, result.get("total").getAsInt(), runResp::toString);
        assertEquals(2, result.get("passed").getAsInt(), runResp::toString);
        assertEquals(1, result.get("failed").getAsInt(), runResp::toString);
        assertEquals(1, result.get("ignored").getAsInt(), runResp::toString);
    }

    private static JsonObject findTestCase(JsonArray tests, String methodName) {
        for (JsonElement element : tests) {
            JsonObject obj = element.getAsJsonObject();
            if (methodName.equals(obj.get("method").getAsString())) {
                return obj;
            }
        }
        return null;
    }

    private static boolean hasFrame(JsonArray frames, String className, String methodName, String fileName) {
        for (JsonElement element : frames) {
            JsonObject f = element.getAsJsonObject();
            if (className.equals(f.get("class").getAsString())
                && methodName.equals(f.get("method").getAsString())
                && fileName.equals(f.get("file").getAsString())
                && f.get("line").getAsInt() > 0) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject waitForEvent(String eventName) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1_000));
            assertTrue(eventResponse.has("result"), eventResponse::toString);
            JsonObject event = eventResponse.getAsJsonObject("result");
            String type = event.get("event").getAsString();
            if (!"Timeout".equals(type)) {
                seen.add(event.toString());
            }
            if (eventName.equals(type)) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for " + eventName + ". Seen: " + seen);
    }

    private static void drainEvents() throws Exception {
        while (true) {
            JsonObject eventResponse = harness.rpc().request(new Request.WaitEvent(1));
            if (!eventResponse.has("result")) return;
            JsonObject event = eventResponse.getAsJsonObject("result");
            if ("Timeout".equals(event.get("event").getAsString())) return;
        }
    }
}
