package org.equimacs.eclipse.ui.toolkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure JSON spec builder for the built-in Moldable Call Explorer (tree and hybrid tree+graph views).
 */
public final class CallExplorerSpecBuilder {

    public static final int DEFAULT_DEPTH = 3;
    public static final int MIN_DEPTH = 1;
    public static final int MAX_DEPTH = 5;
    private static final int MAX_VIEW_ID_LENGTH = 128;
    private static final int MAX_GRAPH_NODES = 100;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Configuration options controlling Call Explorer spec generation.
     */
    public record CallExplorerOptions(
            String file,
            int offset,
            String direction,
            int depth,
            boolean hideJdk,
            String viewMode,
            String viewId) {}

    private CallExplorerSpecBuilder() {}

    /**
     * Normalizes direction to {@code "callers"}, {@code "callees"}, or {@code "both"} (default {@code "callers"}).
     */
    public static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "callers";
        }
        String normalized = direction.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "callers", "callees", "both" -> normalized;
            default -> throw new IllegalArgumentException(
                "Unsupported call explorer direction: " + direction + " (expected callers, callees, or both)");
        };
    }

    /**
     * Clamps traversal depth to {@code 1..5}, defaulting to {@value #DEFAULT_DEPTH} when {@code depth <= 0}.
     */
    public static int clampDepth(int depth) {
        if (depth <= 0) {
            return DEFAULT_DEPTH;
        }
        return Math.max(MIN_DEPTH, Math.min(depth, MAX_DEPTH));
    }

    /**
     * Normalizes view mode to {@code "tree"} or {@code "hybrid"} (default {@code "hybrid"}).
     */
    public static String normalizeViewMode(String viewMode) {
        if (viewMode == null || viewMode.isBlank()) {
            return "hybrid";
        }
        String normalized = viewMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tree", "hybrid" -> normalized;
            default -> throw new IllegalArgumentException(
                "Unsupported call explorer viewMode: " + viewMode + " (expected tree or hybrid)");
        };
    }

    /**
     * Computes a deterministic, sanitized view ID matching {@code ^[A-Za-z0-9._-]+$}.
     */
    public static String defaultViewId(String file, int offset) {
        String prefix = "call-explorer-";
        String suffix = "-" + Math.max(0, offset);
        String raw = file == null ? "" : file.trim();
        String sanitized = raw.replaceAll("[^A-Za-z0-9._-]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^[.-]+|[.-]+$", "");
        if (sanitized.isEmpty()) {
            return prefix + Math.max(0, offset);
        }
        int maxMiddleLen = MAX_VIEW_ID_LENGTH - prefix.length() - suffix.length();
        if (sanitized.length() > maxMiddleLen) {
            sanitized = sanitized.substring(sanitized.length() - maxMiddleLen).replaceAll("^[.-]+", "");
            if (sanitized.isEmpty()) {
                sanitized = "file";
            }
        }
        return prefix + sanitized + suffix;
    }

    /**
     * Resolves an explicit view ID from options or falls back to {@link #defaultViewId(String, int)}.
     */
    public static String resolveViewId(CallExplorerOptions options) {
        if (options != null && options.viewId() != null && !options.viewId().isBlank()) {
            return options.viewId().trim();
        }
        String file = options != null ? options.file() : null;
        int offset = options != null ? options.offset() : 0;
        return defaultViewId(file, offset);
    }

    /**
     * Builds a JSON ViewSpec for {@code tree} or {@code split(tree, graph)} from CallHierarchy JSON payloads.
     */
    public static String buildSpecJson(
            CallExplorerOptions options,
            JsonObject callersPayload,
            JsonObject calleesPayload) {
        CallExplorerOptions safeOptions = options != null
            ? options
            : new CallExplorerOptions("", 0, "both", DEFAULT_DEPTH, false, "hybrid", null);

        String direction = normalizeDirection(safeOptions.direction());
        int depth = clampDepth(safeOptions.depth());
        boolean hideJdk = safeOptions.hideJdk();
        String viewMode = normalizeViewMode(safeOptions.viewMode());
        String resolvedViewId = resolveViewId(safeOptions);

        JsonObject targetObj = resolveTargetObject(callersPayload, calleesPayload);
        String targetName = optString(targetObj, "name", fallbackFileName(safeOptions.file(), "<target>"));
        String targetType = optString(targetObj, "declaringType", "");
        String targetDeclFile = optString(targetObj, "file", null);
        int targetDeclLine = optInt(targetObj, "line", -1);
        int targetDeclOffset = optInt(targetObj, "offset", -1);
        String targetFile = firstNonBlank(targetDeclFile, safeOptions.file());
        int focusOffset = (targetDeclFile != null && !targetDeclFile.isBlank() && targetDeclOffset >= 0)
            ? targetDeclOffset
            : safeOptions.offset();
        String focusFile = (targetDeclFile != null && !targetDeclFile.isBlank() && targetDeclOffset >= 0)
            ? targetDeclFile
            : safeOptions.file();
        String targetKey = methodKey(targetType, targetName, targetFile);

        int[] filteredCount = new int[] { 0 };
        int[] treeNodeSeq = new int[] { 1 };

        Set<String> initialAncestors = new LinkedHashSet<>();
        if (!targetKey.isEmpty()) {
            initialAncestors.add(targetKey);
        }

        JsonArray callerTreeNodes = ("callers".equals(direction) || "both".equals(direction))
            ? convertTreeNodes(
                extractNodesArray(callersPayload), initialAncestors, hideJdk, "caller", filteredCount, treeNodeSeq)
            : new JsonArray();

        JsonArray calleeTreeNodes = ("callees".equals(direction) || "both".equals(direction))
            ? convertTreeNodes(
                extractNodesArray(calleesPayload), initialAncestors, hideJdk, "callee", filteredCount, treeNodeSeq)
            : new JsonArray();

        JsonArray roots;
        if ("both".equals(direction)) {
            roots = new JsonArray();
            roots.add(buildGroupRootNode(
                "grp_callers",
                "Callers (" + callerTreeNodes.size() + ")",
                "CALLERS",
                callerTreeNodes));
            roots.add(buildGroupRootNode(
                "grp_callees",
                "Callees (" + calleeTreeNodes.size() + ")",
                "CALLEES",
                calleeTreeNodes));
        } else if ("callers".equals(direction)) {
            roots = callerTreeNodes;
        } else {
            roots = calleeTreeNodes;
        }

        String title = "Call Explorer: " + targetName + " (" + direction + ", d=" + depth + ")"
            + (hideJdk && filteredCount[0] > 0 ? " [" + filteredCount[0] + " JDK filtered]" : "");

        JsonArray toolbar = buildToolbar(
            focusFile, focusOffset, direction, depth, hideJdk, viewMode, resolvedViewId, filteredCount[0]);

        JsonObject treeSpec = new JsonObject();
        treeSpec.addProperty("kind", "tree");
        treeSpec.addProperty("title", title);
        treeSpec.add("columns", stringArray(List.of("Method", "Type", "Call Site", "Snippet")));
        treeSpec.addProperty("expandAll", true);
        treeSpec.add("roots", roots);

        if ("tree".equals(viewMode)) {
            treeSpec.add("toolbar", toolbar);
            return GSON.toJson(treeSpec);
        }

        JsonObject graphSpec = buildFocusGraphSpec(
            targetName,
            targetType,
            targetFile,
            targetDeclLine,
            focusOffset,
            targetKey,
            direction,
            depth,
            hideJdk,
            resolvedViewId,
            callersPayload,
            calleesPayload);

        JsonObject splitSpec = new JsonObject();
        splitSpec.addProperty("kind", "split");
        splitSpec.addProperty("title", title);
        splitSpec.addProperty("orientation", "horizontal");
        JsonArray weights = new JsonArray();
        weights.add(55);
        weights.add(45);
        splitSpec.add("weights", weights);
        splitSpec.add("toolbar", toolbar);
        JsonArray panes = new JsonArray();
        panes.add(treeSpec);
        panes.add(graphSpec);
        splitSpec.add("panes", panes);

        return GSON.toJson(splitSpec);
    }

    private static JsonArray buildToolbar(
            String file,
            int offset,
            String direction,
            int depth,
            boolean hideJdk,
            String viewMode,
            String viewId,
            int filteredCount) {
        JsonArray toolbar = new JsonArray();
        toolbar.add(toolkitButton(
            "callers".equals(direction) ? "* Callers" : "Callers",
            "Show incoming callers", file, offset, "callers", depth, hideJdk, viewMode, viewId));
        toolbar.add(toolkitButton(
            "callees".equals(direction) ? "* Callees" : "Callees",
            "Show outgoing callees", file, offset, "callees", depth, hideJdk, viewMode, viewId));
        toolbar.add(toolkitButton(
            "both".equals(direction) ? "* Both" : "Both",
            "Show both callers and callees", file, offset, "both", depth, hideJdk, viewMode, viewId));
        toolbar.add(toolkitButton(
            depth == 1 ? "* Depth 1" : "Depth 1",
            "Limit call hierarchy depth to 1", file, offset, direction, 1, hideJdk, viewMode, viewId));
        toolbar.add(toolkitButton(
            depth == 3 ? "* Depth 3" : "Depth 3",
            "Limit call hierarchy depth to 3", file, offset, direction, 3, hideJdk, viewMode, viewId));
        toolbar.add(toolkitButton(
            depth == 5 ? "* Depth 5" : "Depth 5",
            "Limit call hierarchy depth to 5", file, offset, direction, 5, hideJdk, viewMode, viewId));

        String jdkLabel = hideJdk ? "Show JDK" : "Hide JDK";
        String jdkTooltip = hideJdk
            ? "Show JDK methods (currently hiding " + filteredCount + ")"
            : "Hide java.*/javax.*/sun.*/jdk.* methods";
        toolbar.add(toolkitButton(
            jdkLabel, jdkTooltip, file, offset, direction, depth, !hideJdk, viewMode, viewId));

        String nextMode = "tree".equals(viewMode) ? "hybrid" : "tree";
        String modeLabel = "tree".equals(viewMode) ? "Hybrid" : "Tree";
        String modeTooltip = "Switch to " + nextMode + " view";
        toolbar.add(toolkitButton(
            modeLabel, modeTooltip, file, offset, direction, depth, hideJdk, nextMode, viewId));

        if (file != null && !file.isBlank() && offset >= 0) {
            JsonObject inspectBtn = new JsonObject();
            inspectBtn.addProperty("label", "Inspect Symbol");
            inspectBtn.addProperty("tooltip", "Open Moldable Inspector for symbol");
            JsonObject action = new JsonObject();
            action.addProperty("type", "inspect");
            action.addProperty("targetKind", "jdt-symbol");
            action.addProperty("file", file);
            action.addProperty("offset", offset);
            inspectBtn.add("action", action);
            toolbar.add(inspectBtn);
        }
        return toolbar;
    }

    private static JsonObject toolkitButton(
            String label,
            String tooltip,
            String file,
            int offset,
            String direction,
            int depth,
            boolean hideJdk,
            String viewMode,
            String viewId) {
        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.addProperty("tooltip", tooltip);
        item.add("action", buildCallExplorerAction(file, offset, direction, depth, hideJdk, viewMode, viewId));
        return item;
    }

    private static JsonObject buildCallExplorerAction(
            String file,
            int offset,
            String direction,
            int depth,
            boolean hideJdk,
            String viewMode,
            String viewId) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "toolkit-view");
        action.addProperty("tool", "call-explorer");
        JsonObject params = new JsonObject();
        params.addProperty("file", file == null ? "" : file);
        params.addProperty("offset", String.valueOf(Math.max(0, offset)));
        params.addProperty("direction", direction);
        params.addProperty("depth", String.valueOf(depth));
        params.addProperty("hideJdk", String.valueOf(hideJdk));
        params.addProperty("viewMode", viewMode);
        params.addProperty("viewId", viewId);
        action.add("params", params);
        return action;
    }

    private static JsonObject buildGroupRootNode(String id, String label, String badge, JsonArray children) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("label", label);
        node.add("values", stringArray(List.of("", "", "")));
        node.addProperty("badge", badge);
        node.add("children", children);
        return node;
    }

    private static JsonArray convertTreeNodes(
            JsonArray rawNodes,
            Set<String> branchAncestors,
            boolean hideJdk,
            String idPrefix,
            int[] filteredCount,
            int[] nodeSeq) {
        JsonArray out = new JsonArray();
        if (rawNodes == null) {
            return out;
        }
        for (int i = 0; i < rawNodes.size(); i++) {
            JsonElement el = rawNodes.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject rawNode = el.getAsJsonObject();
            JsonObject elementObj = optObject(rawNode, "element");
            String name = optString(elementObj, "name", "<unknown>");
            String declaringType = optString(elementObj, "declaringType", "");
            if (hideJdk && isJdkType(declaringType)) {
                filteredCount[0]++;
                continue;
            }
            String file = firstNonBlank(optString(rawNode, "file", null), optString(elementObj, "file", null));
            int line = optInt(rawNode, "line", -1);
            String snippet = optString(rawNode, "snippet", "");
            String key = methodKey(declaringType, name, file);
            boolean recursive = !key.isEmpty() && branchAncestors.contains(key);

            JsonObject treeNode = new JsonObject();
            treeNode.addProperty("id", idPrefix + "_" + (nodeSeq[0]++));
            treeNode.addProperty("label", name);
            treeNode.add("values", stringArray(List.of(
                simpleTypeName(declaringType),
                formatLocation(file, line),
                snippet == null ? "" : snippet)));
            if (recursive) {
                treeNode.addProperty("badge", "RECURSIVE");
            }
            if (isValidFileLine(file, line)) {
                treeNode.add("onActivate", buildOpenFileAction(file, line));
            }
            JsonArray children;
            if (!recursive) {
                Set<String> nextAncestors = new LinkedHashSet<>(branchAncestors);
                if (!key.isEmpty()) {
                    nextAncestors.add(key);
                }
                children = convertTreeNodes(
                    optArray(rawNode, "children"), nextAncestors, hideJdk, idPrefix, filteredCount, nodeSeq);
            } else {
                children = new JsonArray();
            }
            treeNode.add("children", children);
            out.add(treeNode);
        }
        return out;
    }

    private static JsonObject buildFocusGraphSpec(
            String targetName,
            String targetType,
            String targetFile,
            int targetLine,
            int targetOffset,
            String targetKey,
            String direction,
            int depth,
            boolean hideJdk,
            String viewId,
            JsonObject callersPayload,
            JsonObject calleesPayload) {
        JsonArray graphNodes = new JsonArray();
        JsonArray graphEdges = new JsonArray();
        Map<String, String> seenNonRecursiveNodes = new HashMap<>();
        Set<String> seenEdges = new HashSet<>();
        int[] graphSeq = new int[] { 1 };

        JsonObject focusNode = buildGraphNode(
            "n_focus",
            targetName,
            buildSublabel(targetType, targetFile, targetLine),
            "center",
            0,
            "FOCUS",
            targetFile,
            targetLine,
            targetFile,
            targetOffset,
            direction,
            depth,
            hideJdk,
            viewId);
        graphNodes.add(focusNode);

        Set<String> initialAncestors = new LinkedHashSet<>();
        if (!targetKey.isEmpty()) {
            initialAncestors.add(targetKey);
        }

        if ("callers".equals(direction) || "both".equals(direction)) {
            appendGraphSide(
                extractNodesArray(callersPayload),
                "n_focus",
                true,
                1,
                initialAncestors,
                hideJdk,
                direction,
                depth,
                viewId,
                graphNodes,
                graphEdges,
                seenNonRecursiveNodes,
                seenEdges,
                graphSeq);
        }

        if ("callees".equals(direction) || "both".equals(direction)) {
            appendGraphSide(
                extractNodesArray(calleesPayload),
                "n_focus",
                false,
                1,
                initialAncestors,
                hideJdk,
                direction,
                depth,
                viewId,
                graphNodes,
                graphEdges,
                seenNonRecursiveNodes,
                seenEdges,
                graphSeq);
        }

        JsonObject graphSpec = new JsonObject();
        graphSpec.addProperty("kind", "graph");
        graphSpec.addProperty("title", "Call Focus Graph");
        graphSpec.addProperty("layout", "focus");
        graphSpec.add("nodes", graphNodes);
        graphSpec.add("edges", graphEdges);
        return graphSpec;
    }

    private static void appendGraphSide(
            JsonArray rawNodes,
            String parentNodeId,
            boolean isCallerSide,
            int hopDepth,
            Set<String> branchAncestors,
            boolean hideJdk,
            String direction,
            int maxDepth,
            String viewId,
            JsonArray graphNodes,
            JsonArray graphEdges,
            Map<String, String> seenNonRecursiveNodes,
            Set<String> seenEdges,
            int[] graphSeq) {
        if (rawNodes == null || graphNodes.size() >= MAX_GRAPH_NODES) {
            return;
        }
        String group = isCallerSide ? "left" : "right";
        int layer = isCallerSide ? -hopDepth : hopDepth;
        String defaultBadge = isCallerSide ? "CALLER" : "CALLEE";

        for (int i = 0; i < rawNodes.size(); i++) {
            if (graphNodes.size() >= MAX_GRAPH_NODES) {
                return;
            }
            JsonElement el = rawNodes.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject rawNode = el.getAsJsonObject();
            JsonObject elementObj = optObject(rawNode, "element");
            String name = optString(elementObj, "name", "<unknown>");
            String declaringType = optString(elementObj, "declaringType", "");
            if (hideJdk && isJdkType(declaringType)) {
                continue;
            }
            String callFile = optString(rawNode, "file", null);
            int callLine = optInt(rawNode, "line", -1);
            int callOffset = optInt(rawNode, "offset", -1);

            String declFile = optString(elementObj, "file", null);
            int declLine = optInt(elementObj, "line", -1);
            int declOffset = optInt(elementObj, "offset", -1);

            boolean hasDecl = declFile != null && !declFile.isBlank() && declLine > 0;
            String openFile = hasDecl ? declFile : firstNonBlank(declFile, callFile);
            int openLine = hasDecl ? declLine : callLine;
            String sublabelFile = hasDecl ? declFile : null;
            int sublabelLine = hasDecl ? declLine : -1;

            boolean hasDeclOffset = declFile != null && !declFile.isBlank() && declOffset >= 0;
            String focusFile = hasDeclOffset ? declFile : callFile;
            int focusOffset = hasDeclOffset ? declOffset : callOffset;

            String key = methodKey(declaringType, name, firstNonBlank(declFile, callFile));
            boolean recursive = !key.isEmpty() && branchAncestors.contains(key);

            String nodeId;
            boolean isNewNode = false;
            String dedupKey = group + "|" + key;
            if (!recursive && !key.isEmpty() && seenNonRecursiveNodes.containsKey(dedupKey)) {
                nodeId = seenNonRecursiveNodes.get(dedupKey);
            } else {
                nodeId = (recursive ? "n_rec_" : (isCallerSide ? "n_caller_" : "n_callee_")) + (graphSeq[0]++);
                if (!recursive && !key.isEmpty()) {
                    seenNonRecursiveNodes.put(dedupKey, nodeId);
                }
                isNewNode = true;
            }

            if (isNewNode) {
                graphNodes.add(buildGraphNode(
                    nodeId,
                    name,
                    buildSublabel(declaringType, sublabelFile, sublabelLine),
                    group,
                    layer,
                    recursive ? "RECURSIVE" : defaultBadge,
                    openFile,
                    openLine,
                    focusFile,
                    focusOffset,
                    direction,
                    maxDepth,
                    hideJdk,
                    viewId));
            }

            String fromId = isCallerSide ? nodeId : parentNodeId;
            String toId = isCallerSide ? parentNodeId : nodeId;
            String style = recursive ? "dashed" : "solid";
            String edgeKey = fromId + "->" + toId + "|" + style;
            if (seenEdges.add(edgeKey)) {
                JsonObject edge = new JsonObject();
                edge.addProperty("from", fromId);
                edge.addProperty("to", toId);
                edge.addProperty("label", "");
                edge.addProperty("style", style);
                graphEdges.add(edge);
            }

            if (!recursive) {
                Set<String> nextAncestors = new LinkedHashSet<>(branchAncestors);
                if (!key.isEmpty()) {
                    nextAncestors.add(key);
                }
                appendGraphSide(
                    optArray(rawNode, "children"),
                    nodeId,
                    isCallerSide,
                    hopDepth + 1,
                    nextAncestors,
                    hideJdk,
                    direction,
                    maxDepth,
                    viewId,
                    graphNodes,
                    graphEdges,
                    seenNonRecursiveNodes,
                    seenEdges,
                    graphSeq);
            }
        }
    }

    private static JsonObject buildGraphNode(
            String id,
            String label,
            String sublabel,
            String group,
            int layer,
            String badge,
            String openFile,
            int openLine,
            String focusFile,
            int focusOffset,
            String direction,
            int depth,
            boolean hideJdk,
            String viewId) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("label", label);
        node.addProperty("sublabel", sublabel);
        node.addProperty("group", group);
        node.addProperty("layer", layer);
        node.addProperty("badge", badge);
        if (isValidFileLine(openFile, openLine)) {
            node.add("onActivate", buildOpenFileAction(openFile, openLine));
        }
        if (focusFile != null && !focusFile.isBlank() && focusOffset >= 0) {
            node.add("onSecondary", buildCallExplorerAction(focusFile, focusOffset, direction, depth, hideJdk, "hybrid", viewId));
            node.addProperty("secondaryLabel", "Focus in Call Explorer");
        }
        return node;
    }

    private static JsonObject buildOpenFileAction(String file, int line) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "open-file");
        action.addProperty("file", file);
        action.addProperty("line", line);
        action.addProperty("endLine", line);
        return action;
    }

    private static JsonObject resolveTargetObject(JsonObject callersPayload, JsonObject calleesPayload) {
        JsonObject fromCallers = optObject(callersPayload, "target");
        if (fromCallers != null) {
            return fromCallers;
        }
        return optObject(calleesPayload, "target");
    }

    private static JsonArray extractNodesArray(JsonObject payload) {
        return optArray(payload, "nodes");
    }

    private static boolean isJdkType(String declaringType) {
        if (declaringType == null || declaringType.isBlank()) {
            return false;
        }
        return declaringType.startsWith("java.")
            || declaringType.startsWith("javax.")
            || declaringType.startsWith("sun.")
            || declaringType.startsWith("jdk.")
            || declaringType.startsWith("com.sun.");
    }

    private static String methodKey(String declaringType, String methodName, String file) {
        String name = methodName == null ? "" : methodName.trim();
        if (name.isEmpty()) {
            return "";
        }
        String owner = (declaringType != null && !declaringType.isBlank())
            ? declaringType.trim()
            : (file != null ? file.trim() : "");
        return owner + "#" + name;
    }

    private static String buildSublabel(String declaringType, String file, int line) {
        String simple = simpleTypeName(declaringType);
        if (simple.isEmpty()) {
            simple = fallbackFileName(file, "");
        }
        if (line > 0) {
            return simple.isEmpty() ? ("line " + line) : (simple + " : " + line);
        }
        return simple;
    }

    private static String simpleTypeName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 && lastDot + 1 < fqn.length() ? fqn.substring(lastDot + 1) : fqn;
    }

    private static String formatLocation(String file, int line) {
        String base = fallbackFileName(file, "");
        if (base.isEmpty()) {
            return "";
        }
        return line > 0 ? (base + ":" + line) : base;
    }

    private static String fallbackFileName(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
    }

    private static boolean isValidFileLine(String file, int line) {
        return file != null && !file.isBlank() && line > 0;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray arr = new JsonArray();
        for (String val : values) {
            arr.add(val == null ? "" : val);
        }
        return arr;
    }

    private static JsonObject optObject(JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(field);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static String optString(JsonObject obj, String field, String defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return defaultValue;
        }
        return el.getAsString();
    }

    private static int optInt(JsonObject obj, String field, int defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        return defaultValue;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : null;
    }
}
