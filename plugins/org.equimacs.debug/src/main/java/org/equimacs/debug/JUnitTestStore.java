package org.equimacs.debug;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe bounded store of immutable JUnit test run snapshots.
 */
final class JUnitTestStore {

    static final int DEFAULT_MAX_RUNS = 20;

    enum TestOutcome {
        PASSED, FAILED, ERROR, IGNORED, RUNNING, STOPPED, UNDEFINED
    }

    enum RunStatus {
        RUNNING, PASSED, FAILED, ERROR, STOPPED
    }

    record FailureDetail(
        String summary,
        String expected,
        String actual,
        String trace,
        List<JUnitTraceParser.StackFrame> frames
    ) {}

    record TestCaseResult(
        String className,
        String methodName,
        TestOutcome outcome,
        double elapsedSeconds,
        FailureDetail failure
    ) {}

    record TestRunSnapshot(
        String runId,
        String name,
        String project,
        RunStatus status,
        long startedAtMs,
        Long finishedAtMs,
        double elapsedSeconds,
        int total,
        int passed,
        int failed,
        int errors,
        int ignored,
        List<TestCaseResult> tests
    ) {}

    private final int maxRuns;
    private final Map<String, TestRunSnapshot> runs = new LinkedHashMap<>();
    private long nextSeq = 0;
    private String latestRunId;

    JUnitTestStore(int maxRuns) {
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be >= 1, got " + maxRuns);
        }
        this.maxRuns = maxRuns;
    }

    /**
     * Allocates a unique sequential run identifier.
     */
    synchronized String allocateRunId() {
        nextSeq++;
        return "test_" + nextSeq;
    }

    /**
     * Records an initial {@code RUNNING} snapshot when a test run launches.
     */
    synchronized TestRunSnapshot recordStarted(String runId, String name, String project, long startedAtMs) {
        TestRunSnapshot snapshot = new TestRunSnapshot(
            runId,
            name,
            project,
            RunStatus.RUNNING,
            startedAtMs,
            null,
            0.0,
            0,
            0,
            0,
            0,
            0,
            List.of());
        putSnapshot(runId, snapshot);
        return snapshot;
    }

    /**
     * Records the completed snapshot for a finished or stopped test run.
     */
    synchronized TestRunSnapshot recordFinished(
        String runId,
        String name,
        String project,
        long startedAtMs,
        long finishedAtMs,
        double elapsedSeconds,
        boolean stopped,
        List<TestCaseResult> tests
    ) {
        List<TestCaseResult> safeTests = tests != null ? List.copyOf(tests) : List.of();
        int passed = 0;
        int failed = 0;
        int errors = 0;
        int ignored = 0;
        for (TestCaseResult tc : safeTests) {
            switch (tc.outcome()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> errors++;
                case IGNORED -> ignored++;
                case RUNNING, STOPPED, UNDEFINED -> {}
            }
        }
        RunStatus status = stopped
            ? RunStatus.STOPPED
            : (errors > 0 ? RunStatus.ERROR : (failed > 0 ? RunStatus.FAILED : RunStatus.PASSED));

        TestRunSnapshot snapshot = new TestRunSnapshot(
            runId,
            name,
            project,
            status,
            startedAtMs,
            finishedAtMs,
            sanitizeElapsed(elapsedSeconds),
            safeTests.size(),
            passed,
            failed,
            errors,
            ignored,
            safeTests);
        putSnapshot(runId, snapshot);
        return snapshot;
    }

    /**
     * Returns the snapshot for {@code runId}, or the most recent run when {@code runId} is blank/null.
     */
    synchronized TestRunSnapshot get(String runId) {
        if (runId == null || runId.isBlank()) {
            return latestRunId != null ? runs.get(latestRunId) : null;
        }
        return runs.get(runId);
    }

    /**
     * Clears all stored test runs.
     */
    synchronized void clear() {
        runs.clear();
        latestRunId = null;
    }

    /**
     * Serializes a snapshot to a JSON-friendly map, optionally filtering to failures/errors only.
     */
    static Map<String, Object> toMap(TestRunSnapshot snapshot, boolean failuresOnly) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", snapshot.runId());
        map.put("name", snapshot.name());
        if (snapshot.project() != null) {
            map.put("project", snapshot.project());
        }
        map.put("status", snapshot.status().name());
        map.put("startedAtMs", snapshot.startedAtMs());
        if (snapshot.finishedAtMs() != null) {
            map.put("finishedAtMs", snapshot.finishedAtMs());
        }
        map.put("elapsedSeconds", sanitizeElapsed(snapshot.elapsedSeconds()));
        map.put("total", snapshot.total());
        map.put("passed", snapshot.passed());
        map.put("failed", snapshot.failed());
        map.put("errors", snapshot.errors());
        map.put("ignored", snapshot.ignored());

        List<Map<String, Object>> testList = new ArrayList<>();
        for (TestCaseResult tc : snapshot.tests()) {
            if (failuresOnly && tc.outcome() != TestOutcome.FAILED && tc.outcome() != TestOutcome.ERROR) {
                continue;
            }
            testList.add(testCaseToMap(tc));
        }
        map.put("tests", testList);
        return map;
    }

    /**
     * Serializes a completed snapshot as a {@code TestRunFinished} bridge event map.
     */
    static Map<String, Object> toFinishedEventMap(TestRunSnapshot snapshot) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "TestRunFinished");
        event.put("runId", snapshot.runId());
        event.put("name", snapshot.name());
        if (snapshot.project() != null) {
            event.put("project", snapshot.project());
        }
        event.put("status", snapshot.status().name());
        event.put("total", snapshot.total());
        event.put("passed", snapshot.passed());
        event.put("failed", snapshot.failed());
        event.put("errors", snapshot.errors());
        event.put("ignored", snapshot.ignored());
        event.put("elapsedSeconds", sanitizeElapsed(snapshot.elapsedSeconds()));
        return event;
    }

    private void putSnapshot(String runId, TestRunSnapshot snapshot) {
        runs.put(runId, snapshot);
        latestRunId = runId;
        while (runs.size() > maxRuns) {
            Iterator<String> it = runs.keySet().iterator();
            if (it.hasNext()) {
                String oldest = it.next();
                runs.remove(oldest);
            }
        }
    }

    private static Map<String, Object> testCaseToMap(TestCaseResult tc) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("class", tc.className());
        item.put("method", tc.methodName());
        item.put("outcome", tc.outcome().name());
        item.put("elapsedSeconds", sanitizeElapsed(tc.elapsedSeconds()));
        if (tc.failure() != null) {
            item.put("failure", failureToMap(tc.failure()));
        }
        return item;
    }

    private static Map<String, Object> failureToMap(FailureDetail failure) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (failure.summary() != null) {
            map.put("summary", failure.summary());
        }
        if (failure.expected() != null) {
            map.put("expected", failure.expected());
        }
        if (failure.actual() != null) {
            map.put("actual", failure.actual());
        }
        if (failure.trace() != null) {
            map.put("trace", failure.trace());
        }
        List<Map<String, Object>> frameList = new ArrayList<>();
        if (failure.frames() != null) {
            for (JUnitTraceParser.StackFrame frame : failure.frames()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("class", frame.className());
                f.put("method", frame.methodName());
                f.put("file", frame.fileName());
                f.put("line", frame.line());
                f.put("raw", frame.raw());
                frameList.add(f);
            }
        }
        map.put("frames", frameList);
        return map;
    }

    private static double sanitizeElapsed(double value) {
        return (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) ? 0.0 : value;
    }
}
