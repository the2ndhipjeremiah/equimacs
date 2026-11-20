package org.equimacs.debug;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure parser and renderer for logpoint message templates with {@code {expr}} placeholders.
 */
final class LogpointTemplate {

    private LogpointTemplate() {}

    sealed interface Segment permits Literal, Expression {}

    record Literal(String text) implements Segment {}

    record Expression(String source) implements Segment {}

    sealed interface EvalOutcome permits Value, EvalError {}

    record Value(String value, String type, boolean isNull) implements EvalOutcome {}

    record EvalError(String message) implements EvalOutcome {}

    /**
     * Parses a template into literal text and Java expression segments.
     * Escaped braces ({@code {{} and {@code }}}) produce literal braces; unclosed or
     * blank braces are preserved as literal text.
     */
    static List<Segment> parse(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int len = template.length();
        int i = 0;
        while (i < len) {
            char ch = template.charAt(i);
            if (ch == '{') {
                if (i + 1 < len && template.charAt(i + 1) == '{') {
                    literal.append('{');
                    i += 2;
                    continue;
                }
                int closeIdx = findMatchingBrace(template, i + 1);
                if (closeIdx < 0) {
                    literal.append('{');
                    i++;
                    continue;
                }
                String expr = template.substring(i + 1, closeIdx).trim();
                if (expr.isEmpty()) {
                    literal.append(template, i, closeIdx + 1);
                } else {
                    if (literal.length() > 0) {
                        segments.add(new Literal(literal.toString()));
                        literal.setLength(0);
                    }
                    segments.add(new Expression(expr));
                }
                i = closeIdx + 1;
            } else if (ch == '}') {
                if (i + 1 < len && template.charAt(i + 1) == '}') {
                    literal.append('}');
                    i += 2;
                } else {
                    literal.append('}');
                    i++;
                }
            } else {
                literal.append(ch);
                i++;
            }
        }
        if (literal.length() > 0) {
            segments.add(new Literal(literal.toString()));
        }
        return List.copyOf(segments);
    }

    /**
     * Returns distinct expression sources in first-encounter order.
     */
    static List<String> distinctExpressions(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Segment segment : segments) {
            switch (segment) {
                case Literal _ -> {}
                case Expression expr -> seen.add(expr.source());
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Renders parsed segments using evaluated expression outcomes.
     */
    static String render(List<Segment> segments, Map<String, EvalOutcome> outcomes) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            switch (segment) {
                case Literal lit -> sb.append(lit.text());
                case Expression expr -> {
                    EvalOutcome outcome = outcomes != null ? outcomes.get(expr.source()) : null;
                    switch (outcome) {
                        case Value v -> sb.append((v.isNull() || v.value() == null) ? "null" : v.value());
                        case EvalError err -> sb.append("<error: ").append(err.message()).append(">");
                        case null -> sb.append("<unavailable>");
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} when the expression is a single Java identifier suitable for
     * direct frame variable lookup instead of AST evaluation.
     */
    static boolean isSimpleIdentifier(String expression) {
        if (expression == null || expression.isEmpty()) {
            return false;
        }
        if ("true".equals(expression) || "false".equals(expression) || "null".equals(expression)) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(expression.charAt(0))) {
            return false;
        }
        for (int i = 1; i < expression.length(); i++) {
            if (!Character.isJavaIdentifierPart(expression.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int findMatchingBrace(String template, int start) {
        int depth = 1;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = start; i < template.length(); i++) {
            char ch = template.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '\'') {
                inChar = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
