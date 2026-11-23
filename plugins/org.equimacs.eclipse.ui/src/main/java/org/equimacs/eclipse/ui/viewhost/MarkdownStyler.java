package org.equimacs.eclipse.ui.viewhost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure markdown-to-styled-spans parser for the moddable view host.
 * Produces plain text alongside exact character-offset spans and clickable link actions.
 */
public final class MarkdownStyler {

    private static final Pattern BULLET_PATTERN = Pattern.compile("^(\\s*)[-*] (.*)$");
    private static final Pattern LINE_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    private MarkdownStyler() {}

    public enum SpanKind {
        HEADING_1,
        HEADING_2,
        HEADING_3,
        BOLD,
        ITALIC,
        INLINE_CODE,
        CODE_BLOCK,
        LINK
    }

    public record TextSpan(int start, int length, SpanKind kind) {}

    public record LinkSpan(int start, int length, String label, String target, ViewAction action) {}

    public record StyledDocument(String plainText, List<TextSpan> spans, List<LinkSpan> links) {
        public StyledDocument {
            plainText = plainText == null ? "" : plainText;
            spans = spans == null ? List.of() : List.copyOf(spans);
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    /**
     * Parses lightweight Markdown into plain text, formatting spans, and interactive link spans.
     */
    public static StyledDocument parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new StyledDocument("", List.of(), List.of());
        }

        String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder out = new StringBuilder(normalized.length());
        List<TextSpan> spans = new ArrayList<>();
        List<LinkSpan> links = new ArrayList<>();

        boolean hasEmittedLine = false;
        boolean inCodeBlock = false;
        int codeBlockStart = -1;

        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeBlockStart = -1;
                } else {
                    if (codeBlockStart >= 0 && out.length() > codeBlockStart) {
                        spans.add(new TextSpan(codeBlockStart, out.length() - codeBlockStart, SpanKind.CODE_BLOCK));
                    }
                    inCodeBlock = false;
                    codeBlockStart = -1;
                }
                continue;
            }

            if (hasEmittedLine) {
                out.append('\n');
            }
            hasEmittedLine = true;

            if (inCodeBlock) {
                if (codeBlockStart < 0) {
                    codeBlockStart = out.length();
                }
                out.append(line);
                continue;
            }

            int lineStart = out.length();
            if (line.startsWith("### ")) {
                parseInline(line.substring(4), out, spans, links);
                addSpanIfNonEmpty(spans, lineStart, out.length(), SpanKind.HEADING_3);
            } else if (line.startsWith("## ")) {
                parseInline(line.substring(3), out, spans, links);
                addSpanIfNonEmpty(spans, lineStart, out.length(), SpanKind.HEADING_2);
            } else if (line.startsWith("# ")) {
                parseInline(line.substring(2), out, spans, links);
                addSpanIfNonEmpty(spans, lineStart, out.length(), SpanKind.HEADING_1);
            } else {
                Matcher bullet = BULLET_PATTERN.matcher(line);
                if (bullet.matches()) {
                    out.append(bullet.group(1)).append("\u2022 ");
                    parseInline(bullet.group(2), out, spans, links);
                } else {
                    parseInline(line, out, spans, links);
                }
            }
        }

        if (inCodeBlock && codeBlockStart >= 0 && out.length() > codeBlockStart) {
            spans.add(new TextSpan(codeBlockStart, out.length() - codeBlockStart, SpanKind.CODE_BLOCK));
        }

        return new StyledDocument(out.toString(), spans, links);
    }

    private static void parseInline(
            String text,
            StringBuilder out,
            List<TextSpan> spans,
            List<LinkSpan> links) {
        int i = 0;
        int len = text.length();
        while (i < len) {
            char ch = text.charAt(i);
            if (ch == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    int spanStart = out.length();
                    String code = text.substring(i + 1, close);
                    out.append(code);
                    spans.add(new TextSpan(spanStart, code.length(), SpanKind.INLINE_CODE));
                    i = close + 1;
                    continue;
                }
            } else if (ch == '[') {
                int closeBracket = text.indexOf(']', i + 1);
                if (closeBracket > i && closeBracket + 1 < len && text.charAt(closeBracket + 1) == '(') {
                    int closeParen = findClosingParen(text, closeBracket + 2);
                    if (closeParen > closeBracket + 1) {
                        String label = text.substring(i + 1, closeBracket);
                        String target = text.substring(closeBracket + 2, closeParen).trim();
                        int linkStart = out.length();
                        out.append(label);
                        if (!label.isEmpty()) {
                            spans.add(new TextSpan(linkStart, label.length(), SpanKind.LINK));
                            links.add(new LinkSpan(
                                linkStart,
                                label.length(),
                                label,
                                target,
                                parseLinkTarget(target)));
                        }
                        i = closeParen + 1;
                        continue;
                    }
                }
            } else if (ch == '*' && i + 1 < len && text.charAt(i + 1) == '*') {
                int close = text.indexOf("**", i + 2);
                if (close > i + 2) {
                    int spanStart = out.length();
                    parseInline(text.substring(i + 2, close), out, spans, links);
                    addSpanIfNonEmpty(spans, spanStart, out.length(), SpanKind.BOLD);
                    i = close + 2;
                    continue;
                }
            } else if (ch == '*') {
                int close = findClosingSingleAsterisk(text, i + 1);
                if (close > i + 1) {
                    int spanStart = out.length();
                    parseInline(text.substring(i + 1, close), out, spans, links);
                    addSpanIfNonEmpty(spans, spanStart, out.length(), SpanKind.ITALIC);
                    i = close + 1;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
    }

    private static int findClosingParen(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findClosingSingleAsterisk(String text, int start) {
        int len = text.length();
        for (int i = start; i < len; i++) {
            if (text.charAt(i) == '*') {
                boolean nextIsStar = i + 1 < len && text.charAt(i + 1) == '*';
                boolean prevIsStar = i > start && text.charAt(i - 1) == '*';
                if (!nextIsStar && !prevIsStar) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void addSpanIfNonEmpty(List<TextSpan> spans, int start, int end, SpanKind kind) {
        if (end > start) {
            spans.add(new TextSpan(start, end - start, kind));
        }
    }

    /**
     * Converts a markdown link target into a {@link ViewAction}.
     */
    static ViewAction parseLinkTarget(String target) {
        if (target == null || target.isBlank()) {
            return new ViewAction.Emit("link", "");
        }
        if (target.startsWith("file:")) {
            return parseFileTarget(target.substring("file:".length()));
        }
        if (target.startsWith("action:")) {
            String rest = target.substring("action:".length());
            if (rest.isBlank()) {
                return new ViewAction.Emit("link", target);
            }
            int colon = rest.indexOf(':');
            if (colon < 0) {
                return new ViewAction.Emit(rest.trim(), null);
            }
            String name = rest.substring(0, colon).trim();
            if (name.isEmpty()) {
                return new ViewAction.Emit("link", target);
            }
            String payload = rest.substring(colon + 1);
            return new ViewAction.Emit(name, payload);
        }
        if (target.startsWith("command:")) {
            String commandId = target.substring("command:".length()).trim();
            if (commandId.isEmpty()) {
                return new ViewAction.Emit("link", target);
            }
            return new ViewAction.EclipseCommand(commandId, null, -1, Map.of());
        }
        if (target.startsWith("view:")) {
            String viewId = target.substring("view:".length()).trim();
            if (viewId.isEmpty()) {
                return new ViewAction.Emit("link", target);
            }
            return new ViewAction.ShowView(viewId, null);
        }
        return new ViewAction.Emit("link", target);
    }

    private static ViewAction.OpenFile parseFileTarget(String rest) {
        int lastColon = rest.lastIndexOf(':');
        if (lastColon >= 0) {
            boolean isWindowsDriveColon =
                lastColon == 1 && rest.length() >= 2 && Character.isLetter(rest.charAt(0));
            if (!isWindowsDriveColon) {
                String suffix = rest.substring(lastColon + 1);
                Matcher m = LINE_RANGE_PATTERN.matcher(suffix);
                if (m.matches()) {
                    try {
                        int line = Integer.parseInt(m.group(1));
                        int endLine = m.group(2) != null ? Integer.parseInt(m.group(2)) : line;
                        String filePath = rest.substring(0, lastColon);
                        return new ViewAction.OpenFile(filePath, line, endLine);
                    } catch (NumberFormatException ignored) {
                        // Fall through to whole-path OpenFile when integer overflows.
                    }
                }
            }
        }
        return new ViewAction.OpenFile(rest, -1, -1);
    }
}
