package org.equimacs.eclipse.ui.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure domain types for git working-tree diff filtering of review comments.
 */
public final class GitDiffModel {

    private GitDiffModel() {}

    public enum FilterMode {
        ALL,
        CHANGED_FILES,
        CHANGED_HUNKS;

        public static FilterMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALL;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "changed-files" -> CHANGED_FILES;
                case "changed-hunks" -> CHANGED_HUNKS;
                default -> throw new IllegalArgumentException(
                    "Invalid review filter (expected all, changed-files, or changed-hunks): " + raw);
            };
        }
    }

    public record LineRange(int startLine, int endLine) {
        public boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }

        public boolean containsWithTolerance(int line, int tol) {
            int tolerance = Math.max(0, tol);
            return line >= startLine - tolerance && line <= endLine + tolerance;
        }
    }

    public record ChangedFile(
        String normalizedPath,
        String status,
        boolean untracked,
        List<LineRange> ranges
    ) {
        public ChangedFile {
            ranges = ranges == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(ranges));
        }
    }

    public record GitDiffSummary(
        boolean available,
        String repoRoot,
        Map<String, ChangedFile> filesByPath
    ) {
        public GitDiffSummary {
            filesByPath = filesByPath == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(filesByPath));
        }
    }
}
