package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure validation and submit-action payload merging utilities for {@link ViewSpec.FormSpec}.
 */
public final class FormSpecSupport {

    private static final Gson GSON = new Gson();

    /**
     * Outcome of validating a set of form field values against a {@link ViewSpec.FormSpec}.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private FormSpecSupport() {}

    /**
     * Validates {@code values} against {@code spec}'s field definitions (required checks and numeric parsing).
     */
    public static ValidationResult validate(ViewSpec.FormSpec spec, Map<String, String> values) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        List<String> errors = new ArrayList<>();

        for (ViewSpec.FormField field : spec.fields()) {
            String raw = safeValues.get(field.name());
            String displayLabel = (field.label() == null || field.label().isBlank())
                ? field.name()
                : field.label().trim();

            if ("checkbox".equals(field.fieldType())) {
                if (field.required() && (raw == null || !"true".equalsIgnoreCase(raw.trim()))) {
                    errors.add(displayLabel + " must be checked");
                }
                continue;
            }

            if (field.required() && (raw == null || raw.isBlank())) {
                errors.add(displayLabel + " is required");
                continue;
            }

            if ("number".equals(field.fieldType()) && raw != null && !raw.isBlank()) {
                try {
                    double parsed = Double.parseDouble(raw.trim());
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        errors.add(displayLabel + " must be a valid number");
                    }
                } catch (NumberFormatException ignored) {
                    errors.add(displayLabel + " must be a valid number");
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Merges submitted form field {@code values} into {@code onSubmit}.
     */
    public static ViewAction mergeIntoAction(ViewAction onSubmit, Map<String, String> values) {
        if (onSubmit == null) {
            return null;
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        return switch (onSubmit) {
            case ViewAction.Emit e ->
                new ViewAction.Emit(e.name(), mergeJsonObjectPayload(e.payload(), "payload", safeValues));
            case ViewAction.Custom c ->
                new ViewAction.Custom(c.target(), c.action(), mergeJsonObjectPayload(c.args(), "args", safeValues));
            case ViewAction.EclipseCommand cmd ->
                new ViewAction.EclipseCommand(
                    cmd.commandId(),
                    cmd.file(),
                    cmd.offset(),
                    mergeStringParams(cmd.params(), safeValues));
            case ViewAction.ToolkitView tv ->
                new ViewAction.ToolkitView(tv.tool(), mergeStringParams(tv.params(), safeValues));
            case ViewAction.Inspect ins -> {
                String expr = nonBlankOrElse(safeValues.get("expression"), ins.expression());
                String file = nonBlankOrElse(safeValues.get("file"), ins.file());
                yield new ViewAction.Inspect(
                    ins.targetKind(),
                    ins.frameId(),
                    expr,
                    file,
                    ins.offset(),
                    ins.viewId());
            }
            case ViewAction.OpenFile of -> of;
            case ViewAction.ShowView sv -> sv;
        };
    }

    private static String mergeJsonObjectPayload(
            String existingPayload,
            String fallbackKey,
            Map<String, String> values) {
        JsonObject target = new JsonObject();
        if (existingPayload != null && !existingPayload.isBlank()) {
            String trimmed = existingPayload.trim();
            boolean mergedObject = false;
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (parsed != null && parsed.isJsonObject()) {
                    JsonObject existingObj = parsed.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : existingObj.entrySet()) {
                        target.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                    mergedObject = true;
                }
            } catch (JsonSyntaxException ignored) {
                // Fall through to preserve raw string payload under fallbackKey.
            }
            if (!mergedObject) {
                target.addProperty(fallbackKey, existingPayload);
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return GSON.toJson(target);
    }

    private static Map<String, String> mergeStringParams(
            Map<String, String> base,
            Map<String, String> values) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static String nonBlankOrElse(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        return fallback;
    }
}
