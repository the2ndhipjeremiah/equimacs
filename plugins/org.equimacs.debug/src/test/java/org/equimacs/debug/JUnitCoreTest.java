package org.equimacs.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JUnitCoreTest {

    @Test
    void parsesStandardModuleAndInnerStackFrames() {
        String trace = String.join("\n",
            "org.opentest4j.AssertionFailedError: expected: <5> but was: <4>",
            "\tat demo.MathTest.failsComparison(MathTest.java:14)",
            "\tat app//demo.Outer$Inner.<init>(Outer.java:22)",
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:565)",
            "\tat java.base/java.lang.Thread.run(Thread.java:1447)");

        assertEquals("org.opentest4j.AssertionFailedError: expected: <5> but was: <4>",
            JUnitTraceParser.extractSummaryLine(trace));

        List<JUnitTraceParser.StackFrame> frames = JUnitTraceParser.parseStackTrace(trace);
        assertEquals(4, frames.size());
        assertEquals(new JUnitTraceParser.StackFrame(
            "demo.MathTest", "failsComparison", "MathTest.java", 14,
            "at demo.MathTest.failsComparison(MathTest.java:14)"), frames.get(0));
        assertEquals(new JUnitTraceParser.StackFrame(
            "demo.Outer$Inner", "<init>", "Outer.java", 22,
            "at app//demo.Outer$Inner.<init>(Outer.java:22)"), frames.get(1));
        assertEquals(new JUnitTraceParser.StackFrame(
            "java.lang.Thread", "run", "Thread.java", 1447,
            "at java.base/java.lang.Thread.run(Thread.java:1447)"), frames.get(3));

        assertEquals(List.of(), JUnitTraceParser.parseStackTrace(null));
        assertEquals(List.of(), JUnitTraceParser.parseStackTrace("   "));
        assertNull(JUnitTraceParser.extractSummaryLine(""));
    }

    @Test
    void mapsRunnerKindIdsAndRejectsUnknownRunners() {
        assertEquals("org.eclipse.jdt.junit.loader.junit3", JUnitTraceParser.runnerKindId("junit3"));
        assertEquals("org.eclipse.jdt.junit.loader.junit4", JUnitTraceParser.runnerKindId("4"));
        assertEquals("org.eclipse.jdt.junit.loader.junit5", JUnitTraceParser.runnerKindId("JUNIT5"));
        assertEquals("org.eclipse.jdt.junit.loader.junit6", JUnitTraceParser.runnerKindId("junit6"));
        assertThrows(IllegalArgumentException.class, () -> JUnitTraceParser.runnerKindId("testng"));
    }

    @Test
    void storeTracksRunsCountsFailuresAndEvictsOldest() {
        JUnitTestStore store = new JUnitTestStore(2);
        String run1 = store.allocateRunId();
        assertEquals("test_1", run1);

        JUnitTestStore.TestRunSnapshot started = store.recordStarted(run1, "equimacs-junit-test_1", "proj", 100L);
        assertEquals(JUnitTestStore.RunStatus.RUNNING, started.status());

        JUnitTestStore.FailureDetail failDetail = new JUnitTestStore.FailureDetail(
            "expected: <5> but was: <4>",
            "5",
            "4",
            "expected: <5> but was: <4>\n\tat demo.MathTest.fails(MathTest.java:10)",
            JUnitTraceParser.parseStackTrace("\tat demo.MathTest.fails(MathTest.java:10)"));
        JUnitTestStore.TestRunSnapshot finished1 = store.recordFinished(
            run1,
            "equimacs-junit-test_1",
            "proj",
            100L,
            250L,
            Double.NaN,
            false,
            List.of(
                new JUnitTestStore.TestCaseResult("demo.MathTest", "ok", JUnitTestStore.TestOutcome.PASSED, 0.01, null),
                new JUnitTestStore.TestCaseResult("demo.MathTest", "fails", JUnitTestStore.TestOutcome.FAILED, 0.02, failDetail),
                new JUnitTestStore.TestCaseResult("demo.MathTest", "skip", JUnitTestStore.TestOutcome.IGNORED, Double.NaN, null)));

        assertEquals(JUnitTestStore.RunStatus.FAILED, finished1.status());
        assertEquals(0.0, finished1.elapsedSeconds());
        assertEquals(3, finished1.total());
        assertEquals(1, finished1.passed());
        assertEquals(1, finished1.failed());
        assertEquals(0, finished1.errors());
        assertEquals(1, finished1.ignored());

        Map<String, Object> failuresOnly = JUnitTestStore.toMap(finished1, true);
        List<?> filteredTests = (List<?>) failuresOnly.get("tests");
        assertEquals(1, filteredTests.size());

        Map<String, Object> finishedEvent = JUnitTestStore.toFinishedEventMap(finished1);
        assertEquals("TestRunFinished", finishedEvent.get("event"));
        assertEquals("FAILED", finishedEvent.get("status"));
        assertEquals(1, finishedEvent.get("failed"));

        String run2 = store.allocateRunId();
        JUnitTestStore.TestRunSnapshot errorRun = store.recordFinished(
            run2, "equimacs-junit-test_2", "proj", 300L, 400L, 0.1, false,
            List.of(new JUnitTestStore.TestCaseResult("demo.ErrTest", "boom", JUnitTestStore.TestOutcome.ERROR, 0.1, failDetail)));
        assertEquals(JUnitTestStore.RunStatus.ERROR, errorRun.status());

        String run3 = store.allocateRunId();
        JUnitTestStore.TestRunSnapshot stoppedRun = store.recordFinished(
            run3, "equimacs-junit-test_3", "proj", 500L, 550L, 0.05, true,
            List.of(new JUnitTestStore.TestCaseResult("demo.PassTest", "ok", JUnitTestStore.TestOutcome.PASSED, 0.05, null)));
        assertEquals(JUnitTestStore.RunStatus.STOPPED, stoppedRun.status());

        // maxRuns = 2 evicted run1
        assertNull(store.get(run1));
        assertNotNull(store.get(run2));
        assertEquals(run3, store.get(null).runId());

        store.clear();
        assertNull(store.get(null));
        assertFalse(failuresOnly.isEmpty());
        assertTrue(finishedEvent.containsKey("runId"));
    }
}
