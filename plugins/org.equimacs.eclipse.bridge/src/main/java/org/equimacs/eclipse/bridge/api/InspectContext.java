package org.equimacs.eclipse.bridge.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-free immutable snapshot describing a runtime or static target for moldable inspection.
 * targetKind values: "jdi-frame", "jdi-value", "jdt-symbol".
 */
public record InspectContext(
    String targetKind,
    String title,
    String typeName,
    String summary,
    Map<String, Object> data
) {
    public InspectContext {
        targetKind = targetKind == null ? "" : targetKind;
        title = title == null ? "" : title;
        typeName = typeName == null ? "" : typeName;
        summary = summary == null ? "" : summary;
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
