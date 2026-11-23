package org.equimacs.eclipse.ui.toolkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure JSON spec builder for the built-in Moldable Execution Trace view.
 */
public final class ExecutionTraceSpecBuilder {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 5000;
    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Configuration options controlling Execution Trace spec generation.
     */
    public record TraceViewOptions(int limit, boolean live, String viewId) {}

    private ExecutionTraceSpecBuilder() {}

    /**
     * Returns the default secondary view ID for the Execution Trace view.
     */
    public static String defaultViewId() {
        return "execution-trace";
    }

    /**
     * Clamps trace entry limit to [{@value #MIN_LIMIT}, {@value #MAX_LIMIT}], defaulting to {@value #DEFAULT_LIMIT}.
     */
    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.max(MIN_LIMIT, Math.min(limit, MAX_LIMIT));
    }

    /**
     * Resolves an explicit view ID or falls back to {@link #defaultViewId()}.
     */
    public static String resolveViewId(TraceViewOptions options) {
        if (options != null && options.viewId() != null && !options.viewId().isBlank()) {
            return options.viewId().trim();
        }
        return defaultViewId();
    }

    /**
     * Builds a JSON ViewSpec from a {@code GetTrace} response payload.
     */
    public static String buildSpecJson(TraceViewOptions options, JsonObject tracePayload) {
        TraceViewOptions safeOptions = options != null
            ? options
            : new TraceViewOptions(DEFAULT_LIMIT, true, defaultViewId());
        int limit = clampLimit(safeOptions.limit());
        boolean live = safeOptions.live();
        String viewId = resolveViewId(safeOptions);

        int count = optInt(tracePayload, "count", 0);
        long totalRecorded = optLong(tracePayload, "totalRecorded", count);
        List<JsonObject> entries = extractEntries(tracePayload);
        if (count == 0 && !entries.isEmpty()) {
            count = entries.size();
            if (totalRecorded < count) {
                totalRecorded = count;
            }
        }

        String title = "Execution Trace (" + entries.size() + " / " + totalRecorded + ")";

        JsonObject splitSpec = new JsonObject();
        splitSpec.addProperty("kind", "split");
        splitSpec.addProperty("title", title);
        splitSpec.addProperty("orientation", "vertical");
        JsonArray weights = new JsonArray();
        weights.add(55);
        weights.add(45);
        splitSpec.add("weights", weights);
        splitSpec.add("toolbar", buildToolbar(limit, live, viewId));
        if (live) {
            splitSpec.add("live", buildLiveBinding(limit, viewId));
        }

        JsonArray panes = new JsonArray();
        panes.add(buildEntriesTable(entries));
        panes.add(buildBottomTabs(entries, count, totalRecorded));
        splitSpec.add("panes", panes);

        return GSON.toJson(splitSpec);
    }

    private static JsonObject buildLiveBinding(int limit, String viewId) {
        JsonObject liveObj = new JsonObject();
        JsonArray events = new JsonArray();
        events.add("LogpointHit");
        liveObj.add("events", events);
        liveObj.add("onEvent", buildTraceAction("trace", limit, true, viewId));
        liveObj.addProperty("pushToHtml", false);
        liveObj.addProperty("debounceMs", 200);
        return liveObj;
    }

    private static JsonArray buildToolbar(int limit, boolean live, String viewId) {
        JsonArray toolbar = new JsonArray();
        toolbar.add(toolbarButton("Refresh", "Reload execution trace", "trace", limit, live, viewId));
        toolbar.add(toolbarButton("Clear Trace", "Clear execution trace buffer", "trace-clear", limit, live, viewId));
        toolbar.add(toolbarButton("Limit 50", "Show up to 50 recent entries", "trace", 50, live, viewId));
        toolbar.add(toolbarButton("Limit 200", "Show up to 200 recent entries", "trace", 200, live, viewId));
        toolbar.add(toolbarButton("Limit 500", "Show up to 500 recent entries", "trace", 500, live, viewId));
        toolbar.add(toolbarButton(
            live ? "Live: ON" : "Live: OFF",
            live ? "Disable live trace updates on LogpointHit" : "Enable live trace updates on LogpointHit",
            "trace",
            limit,
            !live,
            viewId));
        return toolbar;
    }

    private static JsonObject toolbarButton(
            String label,
            String tooltip,
            String tool,
            int limit,
            boolean live,
            String viewId) {
        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.addProperty("tooltip", tooltip);
        item.add("action", buildTraceAction(tool, limit, live, viewId));
        return item;
    }

    private static JsonObject buildTraceAction(String tool, int limit, boolean live, String viewId) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "toolkit-view");
        action.addProperty("tool", tool);
        JsonObject params = new JsonObject();
        params.addProperty("limit", String.valueOf(limit));
        params.addProperty("live", String.valueOf(live));
        params.addProperty("viewId", viewId);
        action.add("params", params);
        return action;
    }

    private static JsonObject buildEntriesTable(List<JsonObject> entries) {
        JsonObject table = new JsonObject();
        table.addProperty("kind", "table");
        table.addProperty("title", "Trace Entries");
        table.add("columns", stringArray(List.of("#", "Time", "Location", "Method", "Message", "Thread")));

        JsonArray rows = new JsonArray();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i);
            long seq = optLong(entry, "seq", i + 1L);
            long ts = optLong(entry, "timestampMs", 0L);
            String resource = optString(entry, "resource", null);
            String file = optString(entry, "file", null);
            String openTarget = firstNonBlank(resource, file);
            String shortFile = baseName(firstNonBlank(file, resource));
            int line = optInt(entry, "line", -1);
            String className = optString(entry, "class", "");
            String method = optString(entry, "method", "");
            String message = firstNonBlank(optString(entry, "message", null), optString(entry, "template", ""));
            long threadId = optLong(entry, "threadId", -1L);
            String threadName = optString(entry, "threadName", threadId >= 0 ? "#" + threadId : "");

            String location = !shortFile.isEmpty() && line > 0
                ? shortFile + ":" + line
                : (shortFile.isEmpty() ? nonNull(openTarget) : shortFile);
            String methodDisplay = formatMethod(className, method);

            JsonObject row = new JsonObject();
            row.addProperty("id", "seq-" + seq);
            row.add("cells", stringArray(List.of(
                String.valueOf(seq),
                formatTimestamp(ts),
                location,
                methodDisplay,
                nonNull(message),
                nonNull(threadName))));
            if (openTarget != null && !openTarget.isBlank() && line > 0) {
                row.add("onActivate", buildOpenFileAction(openTarget, line));
            }
            rows.add(row);
        }
        table.add("rows", rows);
        return table;
    }

    private static JsonObject buildBottomTabs(List<JsonObject> entries, int count, long totalRecorded) {
        JsonObject tabsSpec = new JsonObject();
        tabsSpec.addProperty("kind", "tabs");
        tabsSpec.addProperty("title", "Trace Details");

        JsonArray tabs = new JsonArray();
        tabs.add(tabItem("Latest Snapshot", buildLatestSnapshotKv(entries, count, totalRecorded)));
        tabs.add(tabItem("Timeline", buildTimelineMarkdown(entries, count, totalRecorded)));
        tabsSpec.add("tabs", tabs);
        return tabsSpec;
    }

    private static JsonObject buildLatestSnapshotKv(List<JsonObject> entries, int count, long totalRecorded) {
        JsonObject kv = new JsonObject();
        kv.addProperty("kind", "key-value");
        kv.addProperty("title", "Latest Snapshot");

        JsonArray sections = new JsonArray();
        if (entries.isEmpty()) {
            JsonArray statusEntries = new JsonArray();
            statusEntries.add(kvEntry("Recorded", "0 / " + totalRecorded, null));
            statusEntries.add(kvEntry(
                "Hint",
                "No logpoint trace entries recorded yet. Set a logpoint with eqm-cli lp <file>:<line>.",
                null));
            sections.add(kvSection("Status", statusEntries));
            kv.add("sections", sections);
            return kv;
        }

        JsonObject latest = entries.get(entries.size() - 1);
        long seq = optLong(latest, "seq", entries.size());
        long ts = optLong(latest, "timestampMs", 0L);
        String resource = optString(latest, "resource", null);
        String file = optString(latest, "file", null);
        String openTarget = firstNonBlank(resource, file);
        int line = optInt(latest, "line", -1);
        String className = optString(latest, "class", "");
        String method = optString(latest, "method", "");
        long threadId = optLong(latest, "threadId", -1L);
        String threadName = optString(latest, "threadName", "");
        String message = optString(latest, "message", "");
        String template = optString(latest, "template", "");

        JsonArray metaEntries = new JsonArray();
        metaEntries.add(kvEntry("Seq", String.valueOf(seq), null));
        metaEntries.add(kvEntry("Time", formatTimestamp(ts), null));
        String locText = openTarget != null ? (line > 0 ? openTarget + ":" + line : openTarget) : "";
        JsonObject locAction = (openTarget != null && !openTarget.isBlank() && line > 0)
            ? buildOpenFileAction(openTarget, line)
            : null;
        metaEntries.add(kvEntry("Location", locText, locAction));
        metaEntries.add(kvEntry("Class", nonNull(className), null));
        metaEntries.add(kvEntry("Method", nonNull(method), null));
        String threadText = !threadName.isBlank()
            ? (threadId >= 0 ? threadName + " (id=" + threadId + ")" : threadName)
            : (threadId >= 0 ? "#" + threadId : "");
        metaEntries.add(kvEntry("Thread", threadText, null));
        metaEntries.add(kvEntry("Message", nonNull(message), null));
        if (!template.isEmpty()) {
            metaEntries.add(kvEntry("Template", template, null));
        }
        metaEntries.add(kvEntry("Buffer", count + " shown / " + totalRecorded + " total", null));
        sections.add(kvSection("Metadata", metaEntries));

        JsonObject evalsObj = optObject(latest, "evaluations");
        if (evalsObj != null && evalsObj.size() > 0) {
            JsonArray evalEntries = new JsonArray();
            for (Map.Entry<String, JsonElement> e : evalsObj.entrySet()) {
                evalEntries.add(kvEntry(e.getKey(), formatEvalDetail(e.getValue()), null));
            }
            sections.add(kvSection("Evaluations", evalEntries));
        }

        JsonArray varsArr = optArray(latest, "variables");
        if (varsArr != null && varsArr.size() > 0) {
            JsonArray varEntries = new JsonArray();
            for (int i = 0; i < varsArr.size(); i++) {
                JsonElement item = varsArr.get(i);
                if (item == null || !item.isJsonObject()) {
                    continue;
                }
                JsonObject vObj = item.getAsJsonObject();
                String vName = optString(vObj, "name", "var" + i);
                String vType = optString(vObj, "type", "");
                String vValue = optString(vObj, "value", "null");
                String keyLabel = vType.isEmpty() ? vName : vName + " (" + vType + ")";
                varEntries.add(kvEntry(keyLabel, vValue, null));
            }
            sections.add(kvSection("Variables", varEntries));
        }

        kv.add("sections", sections);
        return kv;
    }

    private static JsonObject buildTimelineMarkdown(List<JsonObject> entries, int count, long totalRecorded) {
        StringBuilder md = new StringBuilder();
        md.append("# Execution Timeline (")
          .append(entries.size())
          .append(" of ")
          .append(totalRecorded)
          .append(")\n\n");

        if (entries.isEmpty()) {
            md.append("No trace entries recorded yet.");
        } else {
            for (int i = 0; i < entries.size(); i++) {
                JsonObject entry = entries.get(i);
                long seq = optLong(entry, "seq", i + 1L);
                long ts = optLong(entry, "timestampMs", 0L);
                String resource = optString(entry, "resource", null);
                String file = optString(entry, "file", null);
                String openTarget = firstNonBlank(resource, file);
                String shortFile = baseName(firstNonBlank(file, resource));
                int line = optInt(entry, "line", -1);
                String className = optString(entry, "class", "");
                String method = optString(entry, "method", "");
                String message = firstNonBlank(optString(entry, "message", null), optString(entry, "template", ""));

                md.append("- **#").append(seq).append("**");
                String formattedTime = formatTimestamp(ts);
                if (!formattedTime.isEmpty()) {
                    md.append(" `").append(formattedTime).append("`");
                }
                if (openTarget != null && !openTarget.isBlank() && line > 0) {
                    String linkLabel = !shortFile.isEmpty() ? shortFile + ":" + line : openTarget + ":" + line;
                    md.append(" [").append(linkLabel).append("](file:").append(openTarget).append(":").append(line).append(")");
                } else if (!shortFile.isEmpty()) {
                    md.append(" `").append(shortFile).append("`");
                }
                String methodDisplay = formatMethod(className, method);
                if (!methodDisplay.isEmpty()) {
                    md.append(" `").append(methodDisplay).append("`");
                }
                if (message != null && !message.isBlank()) {
                    md.append(" — ").append(message);
                }

                String evalSummary = formatEvalSummaryLine(optObject(entry, "evaluations"));
                if (!evalSummary.isEmpty()) {
                    md.append(" | evals: ").append(evalSummary);
                }
                md.append('\n');
            }
        }

        JsonObject markdown = new JsonObject();
        markdown.addProperty("kind", "markdown");
        markdown.addProperty("title", "Timeline");
        markdown.addProperty("content", md.toString());
        return markdown;
    }

    private static String formatEvalDetail(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        if (!el.isJsonObject()) {
            return el.toString();
        }
        JsonObject obj = el.getAsJsonObject();
        String error = optString(obj, "error", null);
        if (error != null && !error.isBlank()) {
            return "ERROR: " + error;
        }
        String value = optString(obj, "value", "null");
        String type = optString(obj, "type", "");
        boolean isNull = optBoolean(obj, "isNull", false);
        String renderedValue = isNull ? "null" : value;
        return type.isEmpty() ? renderedValue : (renderedValue + " (" + type + ")");
    }

    private static String formatEvalSummaryLine(JsonObject evalsObj) {
        if (evalsObj == null || evalsObj.size() == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>(evalsObj.size());
        for (Map.Entry<String, JsonElement> e : evalsObj.entrySet()) {
            parts.add("`" + e.getKey() + "=" + formatEvalDetail(e.getValue()) + "`");
        }
        return String.join(", ", parts);
    }

    private static List<JsonObject> extractEntries(JsonObject payload) {
        JsonArray arr = optArray(payload, "entries");
        if (arr == null || arr.size() == 0) {
            return List.of();
        }
        List<JsonObject> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el != null && el.isJsonObject()) {
                result.add(el.getAsJsonObject());
            }
        }
        return result;
    }

    private static JsonObject tabItem(String label, JsonObject content) {
        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.add("content", content);
        return item;
    }

    private static JsonObject kvSection(String heading, JsonArray entries) {
        JsonObject sec = new JsonObject();
        sec.addProperty("heading", heading);
        sec.add("entries", entries);
        return sec;
    }

    private static JsonObject kvEntry(String key, String value, JsonObject onActivate) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("value", value == null ? "" : value);
        if (onActivate != null) {
            entry.add("onActivate", onActivate);
        }
        return entry;
    }

    private static JsonObject buildOpenFileAction(String file, int line) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "open-file");
        action.addProperty("file", file);
        action.addProperty("line", line);
        action.addProperty("endLine", line);
        return action;
    }

    private static String formatTimestamp(long timestampMs) {
        if (timestampMs <= 0L) {
            return "";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMs));
    }

    private static String formatMethod(String className, String method) {
        String simpleClass = simpleTypeName(className);
        if (simpleClass.isEmpty()) {
            return nonNull(method);
        }
        if (method == null || method.isBlank()) {
            return simpleClass;
        }
        return simpleClass + "." + method;
    }

    private static String simpleTypeName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 && lastDot + 1 < fqn.length() ? fqn.substring(lastDot + 1) : fqn;
    }

    private static String baseName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
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

    private static long optLong(JsonObject obj, String field, long defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsLong();
        }
        return defaultValue;
    }

    private static boolean optBoolean(JsonObject obj, String field, boolean defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        return defaultValue;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : null;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
