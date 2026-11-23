package org.equimacs.eclipse.ui.inspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.equimacs.eclipse.ui.inspect.UiNode.UiBounds;
import org.equimacs.eclipse.ui.inspect.UiNode.UiSelector;
import org.equimacs.eclipse.ui.inspect.UiNode.UiTreeSnapshot;
import org.equimacs.eclipse.ui.inspect.UiNode.WorkbenchPartSummary;

/**
 * Pure tree-filtering, selector-resolution, and compact JSON-map projection
 * functions over {@link UiNode} snapshots.
 */
public final class UiTreeCore {

    public static final int DEFAULT_MAX_DEPTH = 8;
    public static final int MAX_DEPTH_LIMIT = 32;

    private UiTreeCore() {}

    public sealed interface ResolveResult
        permits ResolveResult.Matched, ResolveResult.NotFound, ResolveResult.OutOfRange {
        record Matched(UiNode node) implements ResolveResult {}
        record NotFound(String reason) implements ResolveResult {}
        record OutOfRange(int matchCount, int requestedIndex) implements ResolveResult {}
    }

    /**
     * Clamps requested traversal depth into {@code [1, 32]}, defaulting non-positive values to {@code 8}.
     */
    public static int clampMaxDepth(int requested) {
        if (requested <= 0) {
            return DEFAULT_MAX_DEPTH;
        }
        return Math.min(requested, MAX_DEPTH_LIMIT);
    }

    /**
     * Filters {@code roots} by optional {@code textFilter} and {@code widgetType}, keeping any
     * matching node together with its ancestor chain back to the root.
     */
    public static List<UiNode> filterTree(List<UiNode> roots, String textFilter, String widgetType) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        String normalizedText = normalizeLower(textFilter);
        String normalizedType = normalizeTrim(widgetType);
        if (normalizedText == null && normalizedType == null) {
            return roots;
        }
        List<UiNode> filtered = new ArrayList<>();
        for (UiNode root : roots) {
            UiNode kept = filterNode(root, normalizedText, normalizedType);
            if (kept != null) {
                filtered.add(kept);
            }
        }
        return List.copyOf(filtered);
    }

    private static UiNode filterNode(UiNode node, String normalizedText, String normalizedType) {
        if (node == null) {
            return null;
        }
        List<UiNode> keptChildren = new ArrayList<>();
        for (UiNode child : node.children()) {
            UiNode keptChild = filterNode(child, normalizedText, normalizedType);
            if (keptChild != null) {
                keptChildren.add(keptChild);
            }
        }
        boolean selfMatches = matchesFilter(node, normalizedText, normalizedType);
        if (!selfMatches && keptChildren.isEmpty()) {
            return null;
        }
        return new UiNode(
            node.id(),
            node.path(),
            node.type(),
            node.text(),
            node.tooltip(),
            node.message(),
            node.elementId(),
            node.visible(),
            node.enabled(),
            node.focused(),
            node.selected(),
            node.bounds(),
            node.items(),
            keptChildren
        );
    }

    private static boolean matchesFilter(UiNode node, String normalizedText, String normalizedType) {
        if (normalizedType != null) {
            if (node.type() == null || !node.type().equalsIgnoreCase(normalizedType)) {
                return false;
            }
        }
        if (normalizedText != null) {
            return matchesSubstringFilter(node, normalizedText);
        }
        return true;
    }

    private static boolean matchesSubstringFilter(UiNode node, String lowerNeedle) {
        if (containsIgnoreCase(node.text(), lowerNeedle)
            || containsIgnoreCase(node.tooltip(), lowerNeedle)
            || containsIgnoreCase(node.message(), lowerNeedle)
            || containsIgnoreCase(node.elementId(), lowerNeedle)) {
            return true;
        }
        for (String item : node.items()) {
            if (containsIgnoreCase(item, lowerNeedle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Depth-first search matching optional {@code widgetType} and optional {@code text}.
     * When {@code text} is provided, exact case-insensitive matches on {@code text}, {@code tooltip},
     * or {@code message} are returned if any exist; otherwise substring matches are returned.
     */
    public static List<UiNode> findMatchingNodes(List<UiNode> roots, UiSelector selector) {
        if (roots == null || roots.isEmpty() || selector == null) {
            return List.of();
        }
        String targetType = normalizeTrim(selector.widgetType());
        String targetText = normalizeTrim(selector.text());
        String lowerText = targetText != null ? targetText.toLowerCase(Locale.ROOT) : null;

        List<UiNode> exactMatches = new ArrayList<>();
        List<UiNode> containsMatches = new ArrayList<>();
        for (UiNode root : roots) {
            collectSelectorMatches(root, targetType, targetText, lowerText, exactMatches, containsMatches);
        }
        if (targetText == null) {
            return List.copyOf(exactMatches);
        }
        return exactMatches.isEmpty() ? List.copyOf(containsMatches) : List.copyOf(exactMatches);
    }

    private static void collectSelectorMatches(
        UiNode node,
        String targetType,
        String targetText,
        String lowerText,
        List<UiNode> exactMatches,
        List<UiNode> containsMatches
    ) {
        if (node == null) {
            return;
        }
        boolean typeMatches = targetType == null
            || (node.type() != null && node.type().equalsIgnoreCase(targetType));
        if (typeMatches) {
            if (targetText == null) {
                exactMatches.add(node);
            } else if (matchesTextExact(node, targetText)) {
                exactMatches.add(node);
            } else if (matchesTextContains(node, lowerText)) {
                containsMatches.add(node);
            }
        }
        for (UiNode child : node.children()) {
            collectSelectorMatches(child, targetType, targetText, lowerText, exactMatches, containsMatches);
        }
    }

    private static boolean matchesTextExact(UiNode node, String targetText) {
        return equalsTrimmedIgnoreCase(node.text(), targetText)
            || equalsTrimmedIgnoreCase(node.tooltip(), targetText)
            || equalsTrimmedIgnoreCase(node.message(), targetText);
    }

    private static boolean matchesTextContains(UiNode node, String lowerNeedle) {
        return containsIgnoreCase(node.text(), lowerNeedle)
            || containsIgnoreCase(node.tooltip(), lowerNeedle)
            || containsIgnoreCase(node.message(), lowerNeedle);
    }

    /**
     * Resolves a single target {@link UiNode} from {@code roots} using {@code selector}.
     */
    public static ResolveResult resolveNode(List<UiNode> roots, UiSelector selector) {
        if (selector == null) {
            return new ResolveResult.NotFound("Selector is required");
        }
        String widgetId = normalizeTrim(selector.widgetId());
        if (widgetId != null) {
            UiNode byId = findById(roots, widgetId);
            if (byId != null) {
                return new ResolveResult.Matched(byId);
            }
            return new ResolveResult.NotFound("Widget id not found: " + widgetId);
        }
        List<UiNode> matches = findMatchingNodes(roots, selector);
        if (matches.isEmpty()) {
            return new ResolveResult.NotFound(buildNotFoundReason(selector));
        }
        int requestedIndex = Math.max(0, selector.index());
        if (requestedIndex >= matches.size()) {
            return new ResolveResult.OutOfRange(matches.size(), requestedIndex);
        }
        return new ResolveResult.Matched(matches.get(requestedIndex));
    }

    private static UiNode findById(List<UiNode> nodes, String widgetId) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        for (UiNode node : nodes) {
            if (node == null) {
                continue;
            }
            if (widgetId.equals(node.id())) {
                return node;
            }
            UiNode nested = findById(node.children(), widgetId);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String buildNotFoundReason(UiSelector selector) {
        StringBuilder sb = new StringBuilder("No matching widget found");
        String type = normalizeTrim(selector.widgetType());
        String text = normalizeTrim(selector.text());
        if (type != null) {
            sb.append(" (type=").append(type);
            if (text != null) {
                sb.append(", text=").append(text);
            }
            sb.append(')');
        } else if (text != null) {
            sb.append(" (text=").append(text).append(')');
        }
        return sb.toString();
    }

    /**
     * Converts a {@link UiTreeSnapshot} into a compact JSON-serializable map.
     */
    public static Map<String, Object> toMap(UiTreeSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot == null) {
            return map;
        }
        if (snapshot.root() != null) {
            map.put("root", snapshot.root());
        }
        if (!isNullOrEmpty(snapshot.perspectiveId())) {
            map.put("perspectiveId", snapshot.perspectiveId());
        }
        if (!isNullOrEmpty(snapshot.activeShellTitle())) {
            map.put("activeShellTitle", snapshot.activeShellTitle());
        }
        List<Map<String, Object>> viewMaps = new ArrayList<>(snapshot.views().size());
        for (WorkbenchPartSummary view : snapshot.views()) {
            viewMaps.add(toPartMap(view));
        }
        map.put("views", viewMaps);

        List<Map<String, Object>> editorMaps = new ArrayList<>(snapshot.editors().size());
        for (WorkbenchPartSummary editor : snapshot.editors()) {
            editorMaps.add(toPartMap(editor));
        }
        map.put("editors", editorMaps);

        List<Map<String, Object>> rootMaps = new ArrayList<>(snapshot.roots().size());
        for (UiNode root : snapshot.roots()) {
            rootMaps.add(toNodeMap(root));
        }
        map.put("roots", rootMaps);
        map.put("totalNodesVisited", snapshot.totalNodesVisited());
        map.put("truncated", snapshot.truncated());
        return map;
    }

    private static Map<String, Object> toPartMap(WorkbenchPartSummary part) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", part.kind());
        map.put("id", part.id());
        if (!isNullOrEmpty(part.secondaryId())) {
            map.put("secondaryId", part.secondaryId());
        }
        if (part.title() != null) {
            map.put("title", part.title());
        }
        map.put("active", part.active());
        map.put("visible", part.visible());
        map.put("dirty", part.dirty());
        return map;
    }

    /**
     * Converts a {@link UiNode} into a compact JSON-serializable map, omitting null/empty optional fields.
     */
    public static Map<String, Object> toNodeMap(UiNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node == null) {
            return map;
        }
        map.put("id", node.id());
        if (!isNullOrEmpty(node.path())) {
            map.put("path", node.path());
        }
        map.put("type", node.type());
        if (node.text() != null) {
            map.put("text", node.text());
        }
        if (!isNullOrEmpty(node.tooltip())) {
            map.put("tooltip", node.tooltip());
        }
        if (!isNullOrEmpty(node.message())) {
            map.put("message", node.message());
        }
        if (!isNullOrEmpty(node.elementId())) {
            map.put("elementId", node.elementId());
        }
        map.put("visible", node.visible());
        map.put("enabled", node.enabled());
        map.put("focused", node.focused());
        if (node.selected() != null) {
            map.put("selected", node.selected());
        }
        if (node.bounds() != null) {
            UiBounds bounds = node.bounds();
            Map<String, Object> boundsMap = new LinkedHashMap<>();
            boundsMap.put("x", bounds.x());
            boundsMap.put("y", bounds.y());
            boundsMap.put("width", bounds.width());
            boundsMap.put("height", bounds.height());
            map.put("bounds", boundsMap);
        }
        if (node.items() != null && !node.items().isEmpty()) {
            map.put("items", node.items());
        }
        if (node.children() != null && !node.children().isEmpty()) {
            List<Map<String, Object>> childMaps = new ArrayList<>(node.children().size());
            for (UiNode child : node.children()) {
                childMaps.add(toNodeMap(child));
            }
            map.put("children", childMaps);
        }
        return map;
    }

    private static String normalizeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeLower(String value) {
        String trimmed = normalizeTrim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean equalsTrimmedIgnoreCase(String candidate, String target) {
        return candidate != null && candidate.trim().equalsIgnoreCase(target);
    }

    private static boolean containsIgnoreCase(String candidate, String lowerNeedle) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
