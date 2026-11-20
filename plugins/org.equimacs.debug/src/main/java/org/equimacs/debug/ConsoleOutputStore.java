package org.equimacs.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure thread-safe in-memory buffer of captured stdout/stderr output from launched processes.
 */
public final class ConsoleOutputStore {

    public static final int DEFAULT_MAX_ENTRIES = 1000;
    public static final int DEFAULT_PAGE_LIMIT = 200;

    public enum StreamKind {
        STDOUT,
        STDERR;

        public String toWireName() {
            return switch (this) {
                case STDOUT -> "stdout";
                case STDERR -> "stderr";
            };
        }
    }

    public enum StreamFilter {
        ALL,
        STDOUT,
        STDERR
    }

    public record ConsoleEntry(
        long seq,
        long timestampMs,
        String launch,
        String process,
        StreamKind stream,
        String text
    ) {}

    public record ConsolePage(
        int count,
        long totalRecorded,
        String text,
        List<ConsoleEntry> entries
    ) {}

    private final int maxEntries;
    private final List<ConsoleEntry> entries = new ArrayList<>();
    private long nextSeq;
    private long totalRecorded;

    public ConsoleOutputStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public ConsoleOutputStore(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    public static StreamFilter parseStreamFilter(String stream) {
        if (stream == null || stream.isBlank()) {
            return StreamFilter.ALL;
        }
        String normalized = stream.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all" -> StreamFilter.ALL;
            case "stdout", "out" -> StreamFilter.STDOUT;
            case "stderr", "err" -> StreamFilter.STDERR;
            default -> throw new IllegalArgumentException(
                "Invalid stream filter (expected all, stdout, or stderr): " + stream);
        };
    }

    public synchronized void append(
        long timestampMs,
        String launch,
        String process,
        StreamKind stream,
        String chunk
    ) {
        Objects.requireNonNull(stream, "stream");
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String safeLaunch = launch != null ? launch : "";
        String safeProcess = process != null ? process : "";
        // Preserve trailing newline characters on each line so concatenation stays verbatim.
        String[] parts = chunk.split("(?<=\\n)");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            nextSeq++;
            totalRecorded++;
            entries.add(new ConsoleEntry(nextSeq, timestampMs, safeLaunch, safeProcess, stream, part));
            while (entries.size() > maxEntries) {
                entries.removeFirst();
            }
        }
    }

    public synchronized ConsolePage snapshot(String launchFilter, StreamFilter streamFilter, int limit) {
        String normalizedLaunch = (launchFilter == null || launchFilter.isBlank()) ? null : launchFilter.trim();
        StreamFilter filter = streamFilter != null ? streamFilter : StreamFilter.ALL;
        int effectiveLimit = limit > 0 ? limit : DEFAULT_PAGE_LIMIT;

        List<ConsoleEntry> matching = new ArrayList<>();
        for (ConsoleEntry entry : entries) {
            if (normalizedLaunch != null && !normalizedLaunch.equals(entry.launch())) {
                continue;
            }
            if (!matchesStream(filter, entry.stream())) {
                continue;
            }
            matching.add(entry);
        }

        int fromIndex = Math.max(0, matching.size() - effectiveLimit);
        List<ConsoleEntry> slice = List.copyOf(matching.subList(fromIndex, matching.size()));
        StringBuilder combined = new StringBuilder();
        for (ConsoleEntry entry : slice) {
            combined.append(entry.text());
        }
        return new ConsolePage(slice.size(), totalRecorded, combined.toString(), slice);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public static Map<String, Object> toPageMap(ConsolePage page) {
        Objects.requireNonNull(page, "page");
        List<Map<String, Object>> entryMaps = new ArrayList<>(page.entries().size());
        for (ConsoleEntry entry : page.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.seq());
            row.put("timestampMs", entry.timestampMs());
            row.put("launch", entry.launch());
            row.put("process", entry.process());
            row.put("stream", entry.stream().toWireName());
            row.put("text", entry.text());
            entryMaps.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", page.count());
        out.put("totalRecorded", page.totalRecorded());
        out.put("text", page.text());
        out.put("entries", entryMaps);
        return out;
    }

    private static boolean matchesStream(StreamFilter filter, StreamKind kind) {
        return switch (filter) {
            case ALL -> true;
            case STDOUT -> kind == StreamKind.STDOUT;
            case STDERR -> kind == StreamKind.STDERR;
        };
    }
}
