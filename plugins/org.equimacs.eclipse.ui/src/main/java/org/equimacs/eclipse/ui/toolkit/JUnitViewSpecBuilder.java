package org.equimacs.eclipse.ui.toolkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure JSON spec builder for the built-in Moldable JUnit Results view.
 */
public final class JUnitViewSpecBuilder {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Configuration options controlling JUnit view spec generation.
     */
    public record JUnitViewOptions(String runId, boolean failuresOnly, boolean live, String viewId) {}

    private JUnitViewSpecBuilder() {}

    /**
     * Returns the default secondary view ID for the JUnit Results view.
     */
    public static String defaultViewId() {
        return "junit-results";
    }

    /**
     * Resolves an explicit view ID or falls back to {@link #defaultViewId()}.
     */
    public static String resolveViewId(JUnitViewOptions options) {
        if (options != null && options.viewId() != null && !options.viewId().isBlank()) {
            return options.viewId().trim();
        }
        return defaultViewId();
    }

    /**
     * Builds a JSON ViewSpec from a {@code GetTestResults} payload, or a friendly empty-state markdown spec when {@code null}.
     */
    public static String buildSpecJson(JUnitViewOptions options, JsonObject testResultsPayload) {
        JUnitViewOptions safeOptions = options != null
            ? options
            : new JUnitViewOptions(null, false, true, defaultViewId());
        boolean failuresOnly = safeOptions.failuresOnly();
        boolean live = safeOptions.live();
        String viewId = resolveViewId(safeOptions);

        if (testResultsPayload == null) {
            return buildEmptySpec(safeOptions.runId(), failuresOnly, live, viewId);
        }

        String runId = optString(testResultsPayload, "runId", "");
        String name = optString(testResultsPayload, "name", "");
        String project = optString(testResultsPayload, "project", "");
        String status = optString(testResultsPayload, "status", "UNKNOWN");
        double elapsed = optDouble(testResultsPayload, "elapsedSeconds", 0.0);
        int total = optInt(testResultsPayload, "total", 0);
        int passed = optInt(testResultsPayload, "passed", 0);
        int failed = optInt(testResultsPayload, "failed", 0);
        int errors = optInt(testResultsPayload, "errors", 0);
        int ignored = optInt(testResultsPayload, "ignored", 0);

        List<JsonObject> tests = extractTests(testResultsPayload, failuresOnly);
        String suiteLabel = !project.isBlank() ? project : (!name.isBlank() ? name : runId);
        String title = "JUnit" + (suiteLabel.isBlank() ? "" : ": " + suiteLabel)
            + " [" + status + " " + passed + "/" + total + "]";

        JsonObject split = new JsonObject();
        split.addProperty("kind", "split");
        split.addProperty("title", title);
        split.addProperty("orientation", "horizontal");
        JsonArray weights = new JsonArray();
        weights.add(55);
        weights.add(45);
        split.add("weights", weights);
        split.add("toolbar", buildToolbar(safeOptions.runId(), project, failuresOnly, live, viewId));
        if (live) {
            split.add("live", buildLiveBinding(failuresOnly, viewId));
        }

        JsonArray panes = new JsonArray();
        panes.add(buildResultsTree(project, tests, failuresOnly));
        panes.add(buildRightTabs(
            project, runId, name, status, total, passed, failed, errors, ignored, elapsed, tests));
        split.add("panes", panes);

        return GSON.toJson(split);
    }

    private static String buildEmptySpec(String runId, boolean failuresOnly, boolean live, String viewId) {
        StringBuilder md = new StringBuilder();
        md.append("# JUnit Test Results\n\n");
        md.append("No JUnit test runs have been recorded yet.\n\n");
        md.append("Run tests via the Equimacs CLI or Eclipse Run As -> JUnit Test:\n\n");
        md.append("```bash\n");
        md.append("eqm-cli run-tests <project>\n");
        md.append("eqm-cli run-tests <project> <FullyQualifiedTestClass>#<methodName>\n");
        md.append("```\n\n");
        md.append("Click **Refresh** after running tests");
        if (live) {
            md.append(" — live updates are enabled and will refresh automatically on `TestRunFinished`.");
        } else {
            md.append(".");
        }

        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "markdown");
        spec.addProperty("title", "JUnit Results");
        spec.addProperty("content", md.toString());

        JsonArray toolbar = new JsonArray();
        toolbar.add(toolbarButton(
            "Refresh",
            "Check for latest JUnit test run",
            buildJUnitAction("junit", runId, null, failuresOnly, live, viewId)));
        toolbar.add(toolbarButton(
            live ? "Live: ON" : "Live: OFF",
            live ? "Disable live refresh on TestRunFinished" : "Enable live refresh on TestRunFinished",
            buildJUnitAction("junit", runId, null, failuresOnly, !live, viewId)));
        spec.add("toolbar", toolbar);

        if (live) {
            spec.add("live", buildLiveBinding(failuresOnly, viewId));
        }
        return GSON.toJson(spec);
    }

    private static JsonObject buildLiveBinding(boolean failuresOnly, String viewId) {
        JsonObject liveObj = new JsonObject();
        JsonArray events = new JsonArray();
        events.add("TestRunFinished");
        liveObj.add("events", events);
        liveObj.add("onEvent", buildJUnitAction("junit", null, null, failuresOnly, true, viewId));
        liveObj.addProperty("pushToHtml", false);
        liveObj.addProperty("debounceMs", 200);
        return liveObj;
    }

    private static JsonArray buildToolbar(
            String runId,
            String project,
            boolean failuresOnly,
            boolean live,
            String viewId) {
        JsonArray toolbar = new JsonArray();
        toolbar.add(toolbarButton(
            "Refresh",
            "Reload JUnit test results",
            buildJUnitAction("junit", runId, null, failuresOnly, live, viewId)));
        toolbar.add(toolbarButton(
            failuresOnly ? "Show All" : "Failures Only",
            failuresOnly ? "Show all test cases" : "Filter to failed and errored test cases",
            buildJUnitAction("junit", runId, null, !failuresOnly, live, viewId)));
        if (project != null && !project.isBlank()) {
            toolbar.add(toolbarButton(
                "Rerun Suite",
                "Rerun JUnit tests for project " + project,
                buildJUnitAction("junit-rerun", null, project, failuresOnly, live, viewId)));
        }
        toolbar.add(toolbarButton(
            live ? "Live: ON" : "Live: OFF",
            live ? "Disable live refresh on TestRunFinished" : "Enable live refresh on TestRunFinished",
            buildJUnitAction("junit", runId, null, failuresOnly, !live, viewId)));
        return toolbar;
    }

    private static JsonObject toolbarButton(String label, String tooltip, JsonObject action) {
        JsonObject btn = new JsonObject();
        btn.addProperty("label", label);
        btn.addProperty("tooltip", tooltip);
        btn.add("action", action);
        return btn;
    }

    private static JsonObject buildJUnitAction(
            String tool,
            String runId,
            String project,
            boolean failuresOnly,
            boolean live,
            String viewId) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "toolkit-view");
        action.addProperty("tool", tool);
        JsonObject params = new JsonObject();
        if (runId != null && !runId.isBlank()) {
            params.addProperty("runId", runId);
        }
        if (project != null && !project.isBlank()) {
            params.addProperty("project", project);
        }
        params.addProperty("failuresOnly", String.valueOf(failuresOnly));
        params.addProperty("live", String.valueOf(live));
        params.addProperty("viewId", viewId);
        action.add("params", params);
        return action;
    }

    private static JsonObject buildResultsTree(String project, List<JsonObject> tests, boolean failuresOnly) {
        Map<String, List<JsonObject>> grouped = new LinkedHashMap<>();
        for (JsonObject tc : tests) {
            String className = optString(tc, "class", "<unknown>");
            grouped.computeIfAbsent(className, k -> new ArrayList<>()).add(tc);
        }

        JsonArray roots = new JsonArray();
        int classIdx = 1;
        for (Map.Entry<String, List<JsonObject>> entry : grouped.entrySet()) {
            String className = entry.getKey();
            List<JsonObject> cases = entry.getValue();

            int classPass = 0;
            int classFail = 0;
            double classElapsed = 0.0;
            JsonArray children = new JsonArray();

            for (int m = 0; m < cases.size(); m++) {
                JsonObject tc = cases.get(m);
                String method = optString(tc, "method", "<test>");
                String outcome = optString(tc, "outcome", "UNDEFINED");
                String badge = outcomeBadge(outcome);
                double elapsed = optDouble(tc, "elapsedSeconds", 0.0);
                classElapsed += elapsed;

                if ("PASS".equals(badge)) {
                    classPass++;
                } else if ("FAIL".equals(badge) || "ERROR".equals(badge)) {
                    classFail++;
                }

                JsonObject failure = optObject(tc, "failure");
                String summary = optString(failure, "summary", "");

                JsonObject methodNode = new JsonObject();
                methodNode.addProperty("id", "tc_" + classIdx + "_" + (m + 1));
                methodNode.addProperty("label", method);
                methodNode.addProperty("badge", badge);
                methodNode.add("values", stringArray(List.of(
                    badge,
                    formatSeconds(elapsed),
                    summary)));
                methodNode.add("children", buildFailureChildren(project, classIdx, m + 1, failure));
                children.add(methodNode);
            }

            String classBadge = classFail > 0
                ? classPass + "/" + cases.size() + " PASS, " + classFail + " FAIL"
                : classPass + "/" + cases.size() + " PASS";

            JsonObject classNode = new JsonObject();
            classNode.addProperty("id", "cls_" + (classIdx++));
            classNode.addProperty("label", simpleTypeName(className));
            classNode.addProperty("badge", classBadge);
            classNode.add("values", stringArray(List.of(
                classFail > 0 ? classFail + " failing" : "passing",
                formatSeconds(classElapsed),
                className)));
            classNode.add("children", children);
            roots.add(classNode);
        }

        JsonObject tree = new JsonObject();
        tree.addProperty("kind", "tree");
        tree.addProperty("title", failuresOnly ? "Failing Tests" : "Test Cases");
        tree.add("columns", stringArray(List.of("Test", "Outcome", "Time", "Details")));
        tree.addProperty("expandAll", true);
        tree.add("roots", roots);
        return tree;
    }

    private static JsonArray buildFailureChildren(
            String project,
            int classIdx,
            int methodIdx,
            JsonObject failure) {
        JsonArray details = new JsonArray();
        if (failure == null) {
            return details;
        }
        String prefix = "fail_" + classIdx + "_" + methodIdx;

        String summary = optString(failure, "summary", null);
        if (summary != null && !summary.isBlank()) {
            details.add(leafNode(prefix + "_sum", "Summary", List.of("", "", summary), null));
        }
        String expected = optString(failure, "expected", null);
        if (expected != null) {
            details.add(leafNode(prefix + "_exp", "Expected", List.of("", "", expected), null));
        }
        String actual = optString(failure, "actual", null);
        if (actual != null) {
            details.add(leafNode(prefix + "_act", "Actual", List.of("", "", actual), null));
        }

        JsonArray frames = optArray(failure, "frames");
        if (frames != null) {
            for (int f = 0; f < frames.size(); f++) {
                JsonElement frameEl = frames.get(f);
                if (frameEl == null || !frameEl.isJsonObject()) {
                    continue;
                }
                JsonObject frame = frameEl.getAsJsonObject();
                String frameClass = optString(frame, "class", "");
                String frameMethod = optString(frame, "method", "");
                String frameFile = optString(frame, "file", "");
                int frameLine = optInt(frame, "line", -1);
                String raw = optString(frame, "raw", "");

                String label = (!frameClass.isBlank() || !frameMethod.isBlank())
                    ? simpleTypeName(frameClass) + "." + frameMethod
                    : raw;
                String loc = !frameFile.isBlank() && frameLine > 0
                    ? frameFile + ":" + frameLine
                    : frameFile;
                String openPath = resolveFrameFilePath(project, frame);
                JsonObject onActivate = (openPath != null && !openPath.isBlank() && frameLine > 0)
                    ? buildOpenFileAction(openPath, frameLine)
                    : null;

                details.add(leafNode(
                    prefix + "_fr_" + (f + 1),
                    label,
                    List.of("FRAME", loc, raw),
                    onActivate));
            }
        }
        return details;
    }

    private static JsonObject buildRightTabs(
            String project,
            String runId,
            String name,
            String status,
            int total,
            int passed,
            int failed,
            int errors,
            int ignored,
            double elapsed,
            List<JsonObject> tests) {
        JsonObject tabsSpec = new JsonObject();
        tabsSpec.addProperty("kind", "tabs");
        tabsSpec.addProperty("title", "Details");

        JsonArray tabs = new JsonArray();
        tabs.add(tabItem("Summary", buildSummaryKv(
            project, runId, name, status, total, passed, failed, errors, ignored, elapsed)));
        tabs.add(tabItem("Failures", buildFailuresMarkdown(project, tests)));
        tabsSpec.add("tabs", tabs);
        return tabsSpec;
    }

    private static JsonObject buildSummaryKv(
            String project,
            String runId,
            String name,
            String status,
            int total,
            int passed,
            int failed,
            int errors,
            int ignored,
            double elapsed) {
        JsonArray entries = new JsonArray();
        entries.add(kvEntry("Run ID", runId));
        entries.add(kvEntry("Project", project));
        entries.add(kvEntry("Suite", name));
        entries.add(kvEntry("Status", status));
        entries.add(kvEntry("Total", String.valueOf(total)));
        entries.add(kvEntry("Passed", String.valueOf(passed)));
        entries.add(kvEntry("Failed", String.valueOf(failed)));
        entries.add(kvEntry("Errors", String.valueOf(errors)));
        entries.add(kvEntry("Ignored", String.valueOf(ignored)));
        entries.add(kvEntry("Elapsed", formatSeconds(elapsed)));

        JsonObject sec = new JsonObject();
        sec.addProperty("heading", "Run Summary");
        sec.add("entries", entries);

        JsonArray sections = new JsonArray();
        sections.add(sec);

        JsonObject kv = new JsonObject();
        kv.addProperty("kind", "key-value");
        kv.addProperty("title", "Summary");
        kv.add("sections", sections);
        return kv;
    }

    private static JsonObject buildFailuresMarkdown(String project, List<JsonObject> tests) {
        StringBuilder md = new StringBuilder();
        md.append("# Failures\n\n");

        int failureCount = 0;
        for (JsonObject tc : tests) {
            String outcome = optString(tc, "outcome", "");
            String badge = outcomeBadge(outcome);
            if (!"FAIL".equals(badge) && !"ERROR".equals(badge)) {
                continue;
            }
            failureCount++;
            String className = optString(tc, "class", "<unknown>");
            String methodName = optString(tc, "method", "<test>");
            JsonObject failure = optObject(tc, "failure");

            md.append("## ").append(className).append("#").append(methodName).append(" (").append(badge).append(")\n\n");
            if (failure == null) {
                md.append("_No failure trace recorded._\n\n");
                continue;
            }
            String summary = optString(failure, "summary", null);
            if (summary != null && !summary.isBlank()) {
                md.append(summary).append("\n\n");
            }
            String expected = optString(failure, "expected", null);
            String actual = optString(failure, "actual", null);
            if (expected != null) {
                md.append("- **Expected:** `").append(expected).append("`\n");
            }
            if (actual != null) {
                md.append("- **Actual:** `").append(actual).append("`\n");
            }
            if (expected != null || actual != null) {
                md.append('\n');
            }

            JsonArray frames = optArray(failure, "frames");
            if (frames != null && frames.size() > 0) {
                md.append("### Stack Frames\n");
                for (int i = 0; i < frames.size(); i++) {
                    JsonElement el = frames.get(i);
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject frame = el.getAsJsonObject();
                    String frameClass = optString(frame, "class", "");
                    String frameMethod = optString(frame, "method", "");
                    String frameFile = optString(frame, "file", "");
                    int frameLine = optInt(frame, "line", -1);
                    String openPath = resolveFrameFilePath(project, frame);

                    md.append("- `").append(frameClass).append(".").append(frameMethod).append("`");
                    if (openPath != null && !openPath.isBlank() && frameLine > 0) {
                        String linkLabel = !frameFile.isBlank() ? frameFile + ":" + frameLine : openPath + ":" + frameLine;
                        md.append(" [").append(linkLabel).append("](file:")
                          .append(openPath).append(":").append(frameLine).append(")");
                    } else if (!frameFile.isBlank()) {
                        md.append(" (").append(frameFile).append(frameLine > 0 ? ":" + frameLine : "").append(")");
                    }
                    md.append('\n');
                }
                md.append('\n');
            }

            String trace = optString(failure, "trace", null);
            if (trace != null && !trace.isBlank()) {
                String excerpt = traceExcerpt(trace, 20);
                md.append("```\n").append(excerpt).append("\n```\n\n");
            }
        }

        if (failureCount == 0) {
            md.append("No failing or errored tests in this view.");
        }

        JsonObject markdown = new JsonObject();
        markdown.addProperty("kind", "markdown");
        markdown.addProperty("title", "Failures");
        markdown.addProperty("content", md.toString());
        return markdown;
    }

    /**
     * Resolves an open-file target path for a stack frame when {@code line > 0}.
     */
    static String resolveFrameFilePath(String project, JsonObject frame) {
        if (frame == null) {
            return null;
        }
        String explicitPath = optString(frame, "path", null);
        if (explicitPath != null && !explicitPath.isBlank()) {
            return explicitPath.trim();
        }
        String file = optString(frame, "file", null);
        int line = optInt(frame, "line", -1);
        if (file == null || file.isBlank() || line <= 0) {
            return null;
        }
        String trimmedFile = file.trim();
        if (trimmedFile.indexOf('/') >= 0 || trimmedFile.indexOf('\\') >= 0) {
            return trimmedFile;
        }
        String frameClass = optString(frame, "class", "");
        if (project != null && !project.isBlank() && !frameClass.isBlank()) {
            String trimmedProject = project.trim().replaceAll("^/+", "");
            int lastDot = frameClass.lastIndexOf('.');
            String pkgPath = lastDot > 0 ? frameClass.substring(0, lastDot).replace('.', '/') + "/" : "";
            return "/" + trimmedProject + "/src/" + pkgPath + trimmedFile;
        }
        return trimmedFile;
    }

    private static List<JsonObject> extractTests(JsonObject payload, boolean failuresOnly) {
        JsonArray arr = optArray(payload, "tests");
        if (arr == null || arr.size() == 0) {
            return List.of();
        }
        List<JsonObject> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject tc = el.getAsJsonObject();
            if (failuresOnly) {
                String badge = outcomeBadge(optString(tc, "outcome", ""));
                if (!"FAIL".equals(badge) && !"ERROR".equals(badge)) {
                    continue;
                }
            }
            result.add(tc);
        }
        return result;
    }

    private static String outcomeBadge(String rawOutcome) {
        if (rawOutcome == null || rawOutcome.isBlank()) {
            return "UNDEFINED";
        }
        return switch (rawOutcome.trim().toUpperCase(Locale.ROOT)) {
            case "PASSED", "PASS" -> "PASS";
            case "FAILED", "FAIL" -> "FAIL";
            case "ERROR" -> "ERROR";
            case "IGNORED" -> "IGNORED";
            default -> rawOutcome.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static String traceExcerpt(String trace, int maxLines) {
        String[] lines = trace.replace("\r\n", "\n").split("\n", -1);
        if (lines.length <= maxLines) {
            return trace.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        sb.append("\n... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    private static JsonObject leafNode(
            String id,
            String label,
            List<String> values,
            JsonObject onActivate) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("label", label == null ? "" : label);
        node.add("values", stringArray(values));
        if (onActivate != null) {
            node.add("onActivate", onActivate);
        }
        node.add("children", new JsonArray());
        return node;
    }

    private static JsonObject tabItem(String label, JsonObject content) {
        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.add("content", content);
        return item;
    }

    private static JsonObject kvEntry(String key, String value) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("value", value == null ? "" : value);
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

    private static String formatSeconds(double seconds) {
        double safe = (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0.0) ? 0.0 : seconds;
        return String.format(Locale.ROOT, "%.3fs", safe);
    }

    private static String simpleTypeName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 && lastDot + 1 < fqn.length() ? fqn.substring(lastDot + 1) : fqn;
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

    private static double optDouble(JsonObject obj, String field, double defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsDouble();
        }
        return defaultValue;
    }
}
