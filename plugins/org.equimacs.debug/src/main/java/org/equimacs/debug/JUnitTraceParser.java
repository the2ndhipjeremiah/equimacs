package org.equimacs.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for JUnit failure stack traces and runner kind identifiers.
 */
final class JUnitTraceParser {

    private static final Pattern FRAME_PATTERN = Pattern.compile(
        "^\\s*at\\s+(?:[A-Za-z0-9_.$@/-]+/)?([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>-]+)\\(([^:()]+):(\\d+)\\)\\s*$");

    private JUnitTraceParser() {}

    record StackFrame(String className, String methodName, String fileName, int line, String raw) {}

    /**
     * Parses Java stack frames with source file and line numbers from a failure trace.
     */
    static List<StackFrame> parseStackTrace(String trace) {
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        List<StackFrame> frames = new ArrayList<>();
        String[] lines = trace.split("\\R");
        for (String line : lines) {
            Matcher matcher = FRAME_PATTERN.matcher(line);
            if (matcher.matches()) {
                frames.add(new StackFrame(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    Integer.parseInt(matcher.group(4)),
                    line.trim()));
            }
        }
        return List.copyOf(frames);
    }

    /**
     * Returns the first non-blank line of a failure trace as a concise summary.
     */
    static String extractSummaryLine(String trace) {
        if (trace == null || trace.isBlank()) {
            return null;
        }
        String[] lines = trace.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * Maps a short runner name or Eclipse loader ID to the canonical JDT JUnit test-kind ID.
     */
    static String runnerKindId(String runnerName) {
        String normalized = runnerName == null ? "" : runnerName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "junit3", "3", "org.eclipse.jdt.junit.loader.junit3" ->
                "org.eclipse.jdt.junit.loader.junit3";
            case "junit4", "4", "org.eclipse.jdt.junit.loader.junit4" ->
                "org.eclipse.jdt.junit.loader.junit4";
            case "junit5", "5", "org.eclipse.jdt.junit.loader.junit5" ->
                "org.eclipse.jdt.junit.loader.junit5";
            case "junit6", "6", "org.eclipse.jdt.junit.loader.junit6" ->
                "org.eclipse.jdt.junit.loader.junit6";
            default -> throw new IllegalArgumentException(
                "Unsupported JUnit runner: " + runnerName + " (expected junit3, junit4, junit5, or junit6)");
        };
    }
}
