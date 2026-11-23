package org.equimacs.eclipse.ui.review;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsers and matching predicates for git unified=0 diffs and porcelain statuses.
 */
public final class GitDiffParser {

    private static final Pattern HUNK_PATTERN =
        Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private GitDiffParser() {}

    public static Map<String, List<GitDiffModel.LineRange>> parseUnifiedZeroDiff(String diffOutput) {
        Map<String, List<GitDiffModel.LineRange>> rangesByPath = new LinkedHashMap<>();
        if (diffOutput == null || diffOutput.isEmpty()) {
            return rangesByPath;
        }

        String currentPath = null;
        String[] lines = diffOutput.split("\n", -1);
        for (String rawLine : lines) {
            String line = stripTrailingCr(rawLine);
            if (line.startsWith("diff --git ")) {
                currentPath = null;
                continue;
            }
            if (line.startsWith("+++ ")) {
                String target = line.substring(4);
                if ("/dev/null".equals(target)) {
                    currentPath = null;
                    continue;
                }
                String unquoted = unquoteGitPath(target);
                if (unquoted.startsWith("b/")) {
                    unquoted = unquoted.substring(2);
                }
                currentPath = normalizeRepoPath(unquoted);
                continue;
            }
            if (currentPath == null || !line.startsWith("@@ ")) {
                continue;
            }
            Matcher matcher = HUNK_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int start = Integer.parseInt(matcher.group(1));
            String countGroup = matcher.group(2);
            GitDiffModel.LineRange range;
            if (countGroup == null) {
                int single = Math.max(1, start);
                range = new GitDiffModel.LineRange(single, single);
            } else {
                int count = Integer.parseInt(countGroup);
                if (count == 0) {
                    int point = Math.max(1, start);
                    range = new GitDiffModel.LineRange(point, point);
                } else {
                    int clampedStart = Math.max(1, start);
                    range = new GitDiffModel.LineRange(clampedStart, clampedStart + count - 1);
                }
            }
            rangesByPath.computeIfAbsent(currentPath, k -> new ArrayList<>()).add(range);
        }

        return rangesByPath;
    }

    public static Map<String, String> parseStatusPorcelain(String statusOutput) {
        Map<String, String> statusByPath = new LinkedHashMap<>();
        if (statusOutput == null || statusOutput.isEmpty()) {
            return statusByPath;
        }

        String[] lines = statusOutput.split("\n", -1);
        for (String rawLine : lines) {
            String line = stripTrailingCr(rawLine);
            if (line.length() < 4 || line.charAt(2) != ' ') {
                continue;
            }
            String xy = line.substring(0, 2);
            String pathPart = line.substring(3);
            if (xy.charAt(0) == 'R' || xy.charAt(1) == 'R' || xy.charAt(0) == 'C' || xy.charAt(1) == 'C') {
                pathPart = extractRenameTarget(pathPart);
            }
            String normalized = normalizeRepoPath(unquoteGitPath(pathPart));
            if (!normalized.isEmpty()) {
                statusByPath.put(normalized, xy);
            }
        }
        return statusByPath;
    }

    public static GitDiffModel.GitDiffSummary buildSummary(
        String repoRoot,
        String statusOutput,
        String diffOutput
    ) {
        Map<String, String> statusMap = parseStatusPorcelain(statusOutput);
        Map<String, List<GitDiffModel.LineRange>> diffMap = parseUnifiedZeroDiff(diffOutput);
        Map<String, GitDiffModel.ChangedFile> filesByPath = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : statusMap.entrySet()) {
            String path = entry.getKey();
            String status = entry.getValue();
            boolean untracked = "??".equals(status);
            List<GitDiffModel.LineRange> ranges = diffMap.getOrDefault(path, List.of());
            filesByPath.put(path, new GitDiffModel.ChangedFile(path, status, untracked, ranges));
        }

        for (Map.Entry<String, List<GitDiffModel.LineRange>> entry : diffMap.entrySet()) {
            String path = entry.getKey();
            if (!filesByPath.containsKey(path)) {
                filesByPath.put(path, new GitDiffModel.ChangedFile(path, " M", false, entry.getValue()));
            }
        }

        return new GitDiffModel.GitDiffSummary(true, repoRoot, filesByPath);
    }

    public static boolean matches(
        ReviewComment comment,
        GitDiffModel.GitDiffSummary diff,
        GitDiffModel.FilterMode mode,
        Function<String, String> workspacePathToRepoRelative
    ) {
        if (comment == null) {
            return false;
        }
        GitDiffModel.FilterMode effectiveMode = mode == null ? GitDiffModel.FilterMode.ALL : mode;
        if (effectiveMode == GitDiffModel.FilterMode.ALL) {
            return true;
        }
        if (diff == null || !diff.available()) {
            return false;
        }
        String mapped = workspacePathToRepoRelative != null
            ? workspacePathToRepoRelative.apply(comment.file())
            : comment.file();
        String relPath = normalizeRepoPath(mapped);
        if (relPath.isEmpty()) {
            return false;
        }
        GitDiffModel.ChangedFile changed = diff.filesByPath().get(relPath);
        if (changed == null) {
            return false;
        }
        return switch (effectiveMode) {
            case ALL -> true;
            case CHANGED_FILES -> true;
            case CHANGED_HUNKS -> matchesChangedHunk(comment.line(), changed);
        };
    }

    private static boolean matchesChangedHunk(int line, GitDiffModel.ChangedFile changed) {
        if (changed.untracked() || changed.ranges().isEmpty()) {
            return true;
        }
        for (GitDiffModel.LineRange range : changed.ranges()) {
            if (range.containsWithTolerance(line, 1)) {
                return true;
            }
        }
        return false;
    }

    private static String extractRenameTarget(String pathPart) {
        if (pathPart.startsWith("\"")) {
            int closing = findClosingQuote(pathPart, 0);
            if (closing > 0) {
                int arrow = pathPart.indexOf(" -> ", closing + 1);
                if (arrow >= 0) {
                    return pathPart.substring(arrow + 4);
                }
            }
        }
        int arrow = pathPart.indexOf(" -> ");
        if (arrow >= 0) {
            return pathPart.substring(arrow + 4);
        }
        return pathPart;
    }

    private static int findClosingQuote(String text, int openIndex) {
        boolean escaped = false;
        for (int i = openIndex + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unquoteGitPath(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            return raw == null ? "" : raw;
        }
        String inner = raw.substring(1, raw.length() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch != '\\' || i + 1 >= inner.length()) {
                byte[] utf8 = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
                continue;
            }
            char next = inner.charAt(i + 1);
            switch (next) {
                case '\\' -> { out.write('\\'); i++; }
                case '"' -> { out.write('"'); i++; }
                case 't' -> { out.write('\t'); i++; }
                case 'n' -> { out.write('\n'); i++; }
                case 'r' -> { out.write('\r'); i++; }
                case 'b' -> { out.write('\b'); i++; }
                case 'f' -> { out.write('\f'); i++; }
                default -> {
                    if (isOctalDigit(next) && i + 3 < inner.length()
                        && isOctalDigit(inner.charAt(i + 2))
                        && isOctalDigit(inner.charAt(i + 3))) {
                        int val = Integer.parseInt(inner.substring(i + 1, i + 4), 8);
                        out.write(val);
                        i += 3;
                    } else {
                        byte[] utf8 = String.valueOf(next).getBytes(StandardCharsets.UTF_8);
                        out.write(utf8, 0, utf8.length);
                        i++;
                    }
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isOctalDigit(char ch) {
        return ch >= '0' && ch <= '7';
    }

    private static String normalizeRepoPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
