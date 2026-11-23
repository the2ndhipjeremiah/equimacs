package org.equimacs.eclipse.ui.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure functions for capturing and re-resolving line anchors against modified file contents.
 */
public final class LineAnchorMatcher {

    private static final int CONTEXT_RADIUS = 2;

    private LineAnchorMatcher() {}

    public sealed interface AnchorResolution {
        record Unchanged(int line) implements AnchorResolution {}
        record Relocated(int oldLine, int newLine, String reason) implements AnchorResolution {}
        record Stale(int fallbackLine, String reason) implements AnchorResolution {}
    }

    public static String hashLine(String line) {
        String normalized = trimSafe(line);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static LineAnchor capture(List<String> lines, int line1Based) {
        if (lines == null || line1Based < 1 || line1Based > lines.size()) {
            return null;
        }
        int index = line1Based - 1;
        String rawLine = lines.get(index);
        String lineText = trimSafe(rawLine);
        String lineHash = hashLine(rawLine);

        int beforeStart = Math.max(0, index - CONTEXT_RADIUS);
        List<String> beforeLines = new ArrayList<>();
        for (int i = beforeStart; i < index; i++) {
            beforeLines.add(trimSafe(lines.get(i)));
        }

        int afterEnd = Math.min(lines.size(), index + 1 + CONTEXT_RADIUS);
        List<String> afterLines = new ArrayList<>();
        for (int i = index + 1; i < afterEnd; i++) {
            afterLines.add(trimSafe(lines.get(i)));
        }

        return new LineAnchor(
            lineText,
            lineHash,
            String.join("\n", beforeLines),
            String.join("\n", afterLines),
            LineAnchor.STATUS_EXACT
        );
    }

    public static AnchorResolution resolve(int storedLine, LineAnchor anchor, List<String> currentLines) {
        if (anchor == null || anchor.lineHash() == null || currentLines == null || currentLines.isEmpty()) {
            return new AnchorResolution.Unchanged(storedLine);
        }

        if (storedLine >= 1 && storedLine <= currentLines.size()) {
            if (hashLine(currentLines.get(storedLine - 1)).equals(anchor.lineHash())) {
                return new AnchorResolution.Unchanged(storedLine);
            }
        }

        List<Integer> hashMatches = new ArrayList<>();
        for (int i = 0; i < currentLines.size(); i++) {
            if (hashLine(currentLines.get(i)).equals(anchor.lineHash())) {
                hashMatches.add(i + 1);
            }
        }

        if (hashMatches.size() == 1) {
            return new AnchorResolution.Relocated(storedLine, hashMatches.get(0), "hash-match");
        }

        if (hashMatches.size() > 1) {
            int bestLine = pickBestHashCandidate(storedLine, anchor, currentLines, hashMatches);
            return new AnchorResolution.Relocated(storedLine, bestLine, "context-disambiguated");
        }

        String before = anchor.contextBefore();
        String after = anchor.contextAfter();
        if (before != null && !before.isEmpty() && after != null && !after.isEmpty()) {
            Integer contextMatch = findByContextWindow(storedLine, before, after, currentLines);
            if (contextMatch != null) {
                return new AnchorResolution.Relocated(storedLine, contextMatch, "context-window");
            }
        }

        int clamped = Math.max(1, Math.min(storedLine, currentLines.size()));
        return new AnchorResolution.Stale(clamped, "line-modified-or-deleted");
    }

    private static int pickBestHashCandidate(
        int storedLine,
        LineAnchor anchor,
        List<String> currentLines,
        List<Integer> candidates
    ) {
        int bestLine = candidates.get(0);
        int bestScore = Integer.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        for (int candidateLine : candidates) {
            int idx = candidateLine - 1;
            int score = scoreContext(idx, anchor.contextBefore(), anchor.contextAfter(), currentLines);
            int distance = Math.abs(candidateLine - storedLine);
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score;
                bestDistance = distance;
                bestLine = candidateLine;
            }
        }
        return bestLine;
    }

    private static int scoreContext(
        int idx,
        String contextBefore,
        String contextAfter,
        List<String> currentLines
    ) {
        int score = 0;
        if (contextBefore != null) {
            if (contextBefore.isEmpty()) {
                if (idx == 0) {
                    score += 1;
                }
            } else {
                String[] parts = contextBefore.split("\n", -1);
                for (int offset = 1; offset <= parts.length; offset++) {
                    int targetIdx = idx - offset;
                    if (targetIdx >= 0) {
                        String expected = parts[parts.length - offset];
                        if (trimSafe(currentLines.get(targetIdx)).equals(expected)) {
                            score += (offset == 1 ? 2 : 1);
                        }
                    }
                }
            }
        }
        if (contextAfter != null) {
            if (contextAfter.isEmpty()) {
                if (idx == currentLines.size() - 1) {
                    score += 1;
                }
            } else {
                String[] parts = contextAfter.split("\n", -1);
                for (int offset = 1; offset <= parts.length; offset++) {
                    int targetIdx = idx + offset;
                    if (targetIdx < currentLines.size()) {
                        String expected = parts[offset - 1];
                        if (trimSafe(currentLines.get(targetIdx)).equals(expected)) {
                            score += (offset == 1 ? 2 : 1);
                        }
                    }
                }
            }
        }
        return score;
    }

    private static Integer findByContextWindow(
        int storedLine,
        String contextBefore,
        String contextAfter,
        List<String> currentLines
    ) {
        String[] beforeParts = contextBefore.split("\n", -1);
        String[] afterParts = contextAfter.split("\n", -1);
        String immediateBefore = beforeParts[beforeParts.length - 1];
        String immediateAfter = afterParts[0];
        boolean bothImmediateBlank = immediateBefore.isEmpty() && immediateAfter.isEmpty();

        Integer bestLine = null;
        int bestOuterScore = Integer.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        for (int idx = 1; idx < currentLines.size() - 1; idx++) {
            if (!trimSafe(currentLines.get(idx - 1)).equals(immediateBefore)) {
                continue;
            }
            if (!trimSafe(currentLines.get(idx + 1)).equals(immediateAfter)) {
                continue;
            }

            int outerScore = 0;
            if (beforeParts.length >= 2 && idx >= 2 && trimSafe(currentLines.get(idx - 2)).equals(beforeParts[beforeParts.length - 2])) {
                outerScore += 1;
            }
            if (afterParts.length >= 2 && idx + 2 < currentLines.size() && trimSafe(currentLines.get(idx + 2)).equals(afterParts[1])) {
                outerScore += 1;
            }
            if (bothImmediateBlank && outerScore == 0) {
                continue;
            }

            int candidateLine = idx + 1;
            int distance = Math.abs(candidateLine - storedLine);
            if (outerScore > bestOuterScore || (outerScore == bestOuterScore && distance < bestDistance)) {
                bestOuterScore = outerScore;
                bestDistance = distance;
                bestLine = candidateLine;
            }
        }
        return bestLine;
    }

    private static String trimSafe(String line) {
        return line == null ? "" : line.trim();
    }
}
