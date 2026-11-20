package org.equimacs.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe bounded ring buffer storing non-blocking logpoint trace entries.
 */
final class ExecutionTraceStore {

    static final int DEFAULT_MAX_ENTRIES = 500;

    record VariableSnapshot(String name, String type, String value) {}

    record EvalSnapshot(String value, String type, boolean isNull, String error) {}

    record TraceEntry(
        long seq,
        long timestampMs,
        String resource,
        String file,
        String className,
        String method,
        int line,
        long threadId,
        String threadName,
        String template,
        String message,
        Map<String, EvalSnapshot> evaluations,
        List<VariableSnapshot> variables
    ) {}

    record TracePage(int count, long totalRecorded, List<TraceEntry> entries) {}

    private final int maxEntries;
    private final ArrayDeque<TraceEntry> buffer = new ArrayDeque<>();
    private long nextSeq = 0;
    private long totalRecorded = 0;

    ExecutionTraceStore(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Records a new trace entry, evicting the oldest entry if the ring buffer is full.
     */
    synchronized TraceEntry record(
        long timestampMs,
        String resource,
        String file,
        String className,
        String method,
        int line,
        long threadId,
        String threadName,
        String template,
        String message,
        Map<String, EvalSnapshot> evaluations,
        List<VariableSnapshot> variables
    ) {
        nextSeq++;
        totalRecorded++;
        Map<String, EvalSnapshot> safeEvals = (evaluations == null || evaluations.isEmpty())
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(evaluations));
        List<VariableSnapshot> safeVars = variables != null ? List.copyOf(variables) : null;

        TraceEntry entry = new TraceEntry(
            nextSeq,
            timestampMs,
            resource,
            file,
            className,
            method,
            line,
            threadId,
            threadName,
            template,
            message,
            safeEvals,
            safeVars);

        buffer.addLast(entry);
        while (buffer.size() > maxEntries) {
            buffer.removeFirst();
        }
        return entry;
    }

    /**
     * Returns up to {@code limit} most recent entries in chronological order (oldest to newest).
     */
    synchronized TracePage snapshot(int limit) {
        List<TraceEntry> all = new ArrayList<>(buffer);
        List<TraceEntry> slice = (limit > 0 && limit < all.size())
            ? all.subList(all.size() - limit, all.size())
            : all;
        return new TracePage(slice.size(), totalRecorded, List.copyOf(slice));
    }

    /**
     * Clears buffered entries and returns how many were removed.
     */
    synchronized int clear() {
        int cleared = buffer.size();
        buffer.clear();
        return cleared;
    }

    /**
     * Converts a trace entry to an immutable-shaped map representation for JSON serialization.
     */
    static Map<String, Object> toMap(TraceEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", entry.seq());
        map.put("timestampMs", entry.timestampMs());
        putIfNonNull(map, "resource", entry.resource());
        putIfNonNull(map, "file", entry.file());
        putIfNonNull(map, "class", entry.className());
        putIfNonNull(map, "method", entry.method());
        map.put("line", entry.line());
        map.put("threadId", entry.threadId());
        putIfNonNull(map, "threadName", entry.threadName());
        putIfNonNull(map, "template", entry.template());
        putIfNonNull(map, "message", entry.message());
        if (entry.evaluations() != null && !entry.evaluations().isEmpty()) {
            Map<String, Object> evalMap = new LinkedHashMap<>();
            for (Map.Entry<String, EvalSnapshot> e : entry.evaluations().entrySet()) {
                EvalSnapshot snap = e.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                if (snap.error() != null) {
                    item.put("error", snap.error());
                } else {
                    item.put("value", snap.value());
                    item.put("type", snap.type());
                    item.put("isNull", snap.isNull());
                }
                evalMap.put(e.getKey(), item);
            }
            map.put("evaluations", evalMap);
        }
        if (entry.variables() != null) {
            List<Map<String, Object>> varList = new ArrayList<>(entry.variables().size());
            for (VariableSnapshot v : entry.variables()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", v.name());
                item.put("type", v.type());
                item.put("value", v.value());
                varList.add(item);
            }
            map.put("variables", varList);
        }
        return map;
    }

    /**
     * Converts a trace entry to a {@code LogpointHit} event map for {@code wait-event}.
     */
    static Map<String, Object> toEventMap(TraceEntry entry) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "LogpointHit");
        event.putAll(toMap(entry));
        return event;
    }

    /**
     * Converts a trace snapshot page to a response map for {@code GetTrace}.
     */
    static Map<String, Object> toPageMap(TracePage page) {
        List<Map<String, Object>> entries = new ArrayList<>(page.entries().size());
        for (TraceEntry entry : page.entries()) {
            entries.add(toMap(entry));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", page.count());
        map.put("totalRecorded", page.totalRecorded());
        map.put("entries", entries);
        return map;
    }

    private static void putIfNonNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
