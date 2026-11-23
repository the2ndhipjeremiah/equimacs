package org.equimacs.eclipse.ui.review;

import java.util.function.IntUnaryOperator;

/**
 * Pure range and duration calculations for temporary editor line highlighting.
 */
public final class EditorHighlightCore {

    public static final int DEFAULT_DURATION_MS = 4000;
    public static final int MIN_DURATION_MS = 250;
    public static final int MAX_DURATION_MS = 30000;

    /**
     * Computed document character range and clamped duration for a temporary highlight.
     */
    public record HighlightRange(int offset, int length, int startLine, int endLine, int durationMs) {}

    private EditorHighlightCore() {}

    /**
     * Clamps a requested highlight duration to [{@value #MIN_DURATION_MS}, {@value #MAX_DURATION_MS}] ms,
     * falling back to {@value #DEFAULT_DURATION_MS} ms when {@code ms <= 0}.
     */
    public static int clampDuration(int ms) {
        if (ms <= 0) {
            return DEFAULT_DURATION_MS;
        }
        return Math.max(MIN_DURATION_MS, Math.min(ms, MAX_DURATION_MS));
    }

    /**
     * Computes a clamped 1-based line range and 0-based document offset/length using 0-based line lookup functions.
     */
    public static HighlightRange computeRange(
            int totalLines,
            int requestedStart,
            int requestedEnd,
            IntUnaryOperator lineOffset,
            IntUnaryOperator lineLength,
            int durationMs) {
        if (totalLines <= 0 || requestedStart <= 0 || lineOffset == null || lineLength == null) {
            return null;
        }
        int startLine = Math.max(1, Math.min(requestedStart, totalLines));
        int endLine = requestedEnd >= startLine ? Math.min(requestedEnd, totalLines) : startLine;
        int startIndex = startLine - 1;
        int endIndex = endLine - 1;
        int offset = Math.max(0, lineOffset.applyAsInt(startIndex));
        int endLineStart = Math.max(0, lineOffset.applyAsInt(endIndex));
        int endLineLen = Math.max(0, lineLength.applyAsInt(endIndex));
        int endOffset = Math.max(offset, endLineStart + endLineLen);
        int length = Math.max(0, endOffset - offset);
        return new HighlightRange(offset, length, startLine, endLine, clampDuration(durationMs));
    }
}
