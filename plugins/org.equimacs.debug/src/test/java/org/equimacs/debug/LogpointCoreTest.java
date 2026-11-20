package org.equimacs.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogpointCoreTest {

    @Test
    void parsesLiteralsExpressionsEscapesAndNestedBraces() {
        List<LogpointTemplate.Segment> simple = LogpointTemplate.parse("i={i}, sum={sum * 2}");
        assertEquals(
            List.of(
                new LogpointTemplate.Literal("i="),
                new LogpointTemplate.Expression("i"),
                new LogpointTemplate.Literal(", sum="),
                new LogpointTemplate.Expression("sum * 2")),
            simple);

        List<LogpointTemplate.Segment> escaped = LogpointTemplate.parse("{{literal}} and {x} and {new int[]{1, 2}.length}");
        assertEquals(
            List.of(
                new LogpointTemplate.Literal("{literal} and "),
                new LogpointTemplate.Expression("x"),
                new LogpointTemplate.Literal(" and "),
                new LogpointTemplate.Expression("new int[]{1, 2}.length")),
            escaped);

        List<LogpointTemplate.Segment> stringBrace = LogpointTemplate.parse("msg={\"a}\" + x}");
        assertEquals(
            List.of(
                new LogpointTemplate.Literal("msg="),
                new LogpointTemplate.Expression("\"a}\" + x")),
            stringBrace);

        List<LogpointTemplate.Segment> degenerate = LogpointTemplate.parse("empty={} unclosed={foo");
        assertEquals(List.of(new LogpointTemplate.Literal("empty={} unclosed={foo")), degenerate);
        assertEquals(List.of(), LogpointTemplate.parse(null));
        assertEquals(List.of(), LogpointTemplate.parse(""));
    }

    @Test
    void extractsDistinctExpressionsInFirstSeenOrder() {
        List<LogpointTemplate.Segment> segments = LogpointTemplate.parse("{b} + {a} + {b} + {c + 1}");
        assertEquals(List.of("b", "a", "c + 1"), LogpointTemplate.distinctExpressions(segments));
    }

    @Test
    void rendersValuesNullsErrorsAndMissingOutcomes() {
        List<LogpointTemplate.Segment> segments =
            LogpointTemplate.parse("x={x}, n={n}, err={bad}, missing={miss}");
        Map<String, LogpointTemplate.EvalOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("x", new LogpointTemplate.Value("42", "int", false));
        outcomes.put("n", new LogpointTemplate.Value("ignored", "java.lang.String", true));
        outcomes.put("bad", new LogpointTemplate.EvalError("cannot resolve"));

        String rendered = LogpointTemplate.render(segments, outcomes);
        assertEquals("x=42, n=null, err=<error: cannot resolve>, missing=<unavailable>", rendered);
    }

    @Test
    void detectsSimpleIdentifiers() {
        assertTrue(LogpointTemplate.isSimpleIdentifier("i"));
        assertTrue(LogpointTemplate.isSimpleIdentifier("_value$1"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("sum * 2"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("obj.field"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("true"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("false"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("null"));
        assertFalse(LogpointTemplate.isSimpleIdentifier("1abc"));
        assertFalse(LogpointTemplate.isSimpleIdentifier(""));
        assertFalse(LogpointTemplate.isSimpleIdentifier(null));
    }

    @Test
    void traceStoreEvictsOldestSlicesTailAndFormatsMaps() {
        ExecutionTraceStore store = new ExecutionTraceStore(3);
        for (int i = 1; i <= 5; i++) {
            Map<String, ExecutionTraceStore.EvalSnapshot> evals = Map.of(
                "i", new ExecutionTraceStore.EvalSnapshot(Integer.toString(i), "int", false, null));
            List<ExecutionTraceStore.VariableSnapshot> vars = (i % 2 == 0)
                ? null
                : List.of(new ExecutionTraceStore.VariableSnapshot("i", "int", Integer.toString(i)));
            store.record(
                1000L + i,
                "/proj/src/App.java",
                "App.java",
                "App",
                "main",
                10,
                99L,
                "main",
                "i={i}",
                "i=" + i,
                evals,
                vars);
        }

        ExecutionTraceStore.TracePage all = store.snapshot(10);
        assertEquals(3, all.count());
        assertEquals(5L, all.totalRecorded());
        assertEquals(3L, all.entries().get(0).seq());
        assertEquals("i=3", all.entries().get(0).message());
        assertEquals(5L, all.entries().get(2).seq());
        assertEquals("i=5", all.entries().get(2).message());

        ExecutionTraceStore.TracePage tail = store.snapshot(2);
        assertEquals(2, tail.count());
        assertEquals(4L, tail.entries().get(0).seq());
        assertEquals(5L, tail.entries().get(1).seq());

        Map<String, Object> withoutVars = ExecutionTraceStore.toMap(tail.entries().get(0));
        assertFalse(withoutVars.containsKey("variables"));
        assertEquals("i=4", withoutVars.get("message"));

        Map<String, Object> eventMap = ExecutionTraceStore.toEventMap(tail.entries().get(1));
        assertEquals("LogpointHit", eventMap.get("event"));
        assertTrue(eventMap.containsKey("variables"));
        assertEquals(5L, eventMap.get("seq"));

        assertEquals(3, store.clear());
        ExecutionTraceStore.TracePage empty = store.snapshot(10);
        assertEquals(0, empty.count());
        assertEquals(5L, empty.totalRecorded());
        assertNull(ExecutionTraceStore.toMap(tail.entries().get(0)).get("variables"));
    }
}
