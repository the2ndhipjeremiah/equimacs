package org.equimacs.debug;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaInterfaceType;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.equimacs.eclipse.bridge.api.InspectContext;

/**
 * Eclipse JDI extraction boundary converting live stack frames and runtime values
 * into UI-free {@link InspectContext} snapshots.
 */
final class JdiValueExtractor {

    static final int DEFAULT_DEPTH = 2;
    static final int MIN_DEPTH = 1;
    static final int MAX_DEPTH = 4;
    static final int MAX_ITEMS = 100;

    private JdiValueExtractor() {}

    static InspectContext extractFrame(long frameId, IStackFrame frame) {
        String className = "";
        String method = "";
        String file = "";
        int line = -1;

        try {
            line = frame.getLineNumber();
        } catch (DebugException ignored) {}

        if (frame instanceof IJavaStackFrame jf) {
            className = safeString(() -> jf.getDeclaringTypeName(), "");
            method = safeString(() -> jf.getMethodName(), "");
            file = resolveFrameSourceFile(jf);
        } else {
            method = safeString(frame::getName, "");
        }

        List<Map<String, Object>> variables = new ArrayList<>();
        try {
            IVariable[] frameVars = frame.getVariables();
            if (frameVars != null) {
                for (IVariable variable : frameVars) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String name = safeString(variable::getName, "<unknown>");
                    item.put("name", name);
                    try {
                        IValue val = variable.getValue();
                        if (val == null) {
                            item.put("type", "");
                            item.put("value", "null");
                        } else {
                            boolean isNull = (val instanceof IJavaValue jv) && jv.isNull();
                            item.put("type", safeString(val::getReferenceTypeName, ""));
                            item.put("value", isNull ? "null" : safeString(val::getValueString, "<unavailable>"));
                        }
                    } catch (Exception ignored) {
                        item.put("type", "");
                        item.put("value", "<unavailable>");
                    }
                    variables.add(item);
                }
            }
        } catch (DebugException ignored) {}

        String title = buildFrameTitle(frameId, className, method, line);
        String summary = buildFrameSummary(className, method, file, line, variables.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("frameId", frameId);
        data.put("className", className);
        data.put("method", method);
        data.put("file", file);
        data.put("line", line);
        data.put("variables", variables);

        return new InspectContext("jdi-frame", title, className, summary, data);
    }

    static InspectContext extractValue(
        long frameId,
        String expression,
        IJavaValue value,
        int depth,
        JavaDebugController controller
    ) {
        int clampedDepth = clampDepth(depth);
        boolean isNull = value == null || value.isNull();
        String typeName = value == null ? "void" : safeString(value::getReferenceTypeName, "");
        String valueString = isNull ? "null" : safeString(value::getValueString, "<unavailable>");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("frameId", frameId);
        data.put("expression", expression != null ? expression : "");
        data.put("valueString", valueString);
        data.put("isNull", isNull);

        if (value instanceof IJavaObject obj && !isNull) {
            Long uniqueId = safeUniqueId(obj);
            if (uniqueId != null) {
                data.put("uniqueId", uniqueId);
            }

            if (value instanceof IJavaArray array) {
                int len = safeArrayLength(array);
                data.put("isArray", true);
                data.put("arrayLength", len);
                data.put("elements", extractArrayElements(expression, array, len));
            } else {
                boolean isMap = isMapValue(value, typeName);
                boolean isCollection = !isMap && isCollectionValue(value, typeName);

                if (isMap && controller != null && expression != null && !expression.isBlank()) {
                    List<Map<String, Object>> entries = extractMapEntries(frameId, expression, controller);
                    if (entries != null) {
                        data.put("entries", entries);
                    }
                } else if (isCollection && controller != null && expression != null && !expression.isBlank()) {
                    boolean isList = isListValue(value, typeName);
                    List<Map<String, Object>> elements =
                        extractCollectionElements(frameId, expression, isList, controller);
                    if (elements != null) {
                        data.put("elements", elements);
                    }
                }

                if (!"java.lang.String".equals(typeName)) {
                    Set<Long> visitedObjectIds = new HashSet<>();
                    if (uniqueId != null) {
                        visitedObjectIds.add(uniqueId);
                    }
                    List<Map<String, Object>> fields =
                        extractObjectFields(expression, obj, clampedDepth, visitedObjectIds);
                    if (!fields.isEmpty()) {
                        data.put("fields", fields);
                    }
                }
            }
        }

        String exprTitle = (expression != null && !expression.isBlank()) ? expression : "<value>";
        String title = !typeName.isBlank() ? exprTitle + " (" + simpleTypeName(typeName) + ")" : exprTitle;
        String summary = (!typeName.isBlank() ? typeName + " = " : "") + truncate(valueString, 120);

        return new InspectContext("jdi-value", title, typeName, summary, data);
    }

    static int clampDepth(int depth) {
        if (depth <= 0) {
            return DEFAULT_DEPTH;
        }
        return Math.max(MIN_DEPTH, Math.min(depth, MAX_DEPTH));
    }

    private static List<Map<String, Object>> extractObjectFields(
        String parentExpression,
        IJavaObject obj,
        int remainingDepth,
        Set<Long> visitedObjectIds
    ) {
        if (remainingDepth <= 0) {
            return List.of();
        }
        IVariable[] vars;
        try {
            vars = obj.getVariables();
        } catch (DebugException ignored) {
            return List.of();
        }
        if (vars == null || vars.length == 0) {
            return List.of();
        }

        int limit = Math.min(vars.length, MAX_ITEMS);
        List<Map<String, Object>> fields = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            IVariable variable = vars[i];
            String name = safeString(variable::getName, "<field>");
            IValue fieldVal = null;
            try {
                fieldVal = variable.getValue();
            } catch (Exception ignored) {}

            boolean fNull = fieldVal == null || (fieldVal instanceof IJavaValue jv && jv.isNull());
            String fType = fieldVal == null ? "" : safeString(fieldVal::getReferenceTypeName, "");
            String fValue = fNull ? "null" : safeString(fieldVal::getValueString, "<unavailable>");
            String childExpression = buildFieldExpression(parentExpression, name);

            Map<String, Object> fieldMap = new LinkedHashMap<>();
            fieldMap.put("name", name);
            fieldMap.put("type", fType);
            fieldMap.put("value", fValue);
            if (childExpression != null) {
                fieldMap.put("expression", childExpression);
            }

            if (fieldVal instanceof IJavaObject childObj && !fNull) {
                Long childUid = safeUniqueId(childObj);
                if (childUid != null && visitedObjectIds.contains(childUid)) {
                    fieldMap.put("cycle", true);
                } else if (remainingDepth > 1 && !(childObj instanceof IJavaArray)) {
                    if (childUid != null) {
                        visitedObjectIds.add(childUid);
                    }
                    try {
                        List<Map<String, Object>> nested =
                            extractObjectFields(childExpression, childObj, remainingDepth - 1, visitedObjectIds);
                        if (!nested.isEmpty()) {
                            fieldMap.put("fields", nested);
                        }
                    } finally {
                        if (childUid != null) {
                            visitedObjectIds.remove(childUid);
                        }
                    }
                }
            }
            fields.add(fieldMap);
        }
        return fields;
    }

    private static List<Map<String, Object>> extractArrayElements(
        String parentExpression,
        IJavaArray array,
        int length
    ) {
        int limit = Math.min(Math.max(0, length), MAX_ITEMS);
        List<Map<String, Object>> elements = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            try {
                IJavaValue el = array.getValue(i);
                boolean isNull = el == null || el.isNull();
                item.put("type", el == null ? "" : safeString(el::getReferenceTypeName, ""));
                item.put("value", isNull ? "null" : safeString(el::getValueString, "<unavailable>"));
            } catch (Exception ignored) {
                item.put("type", "");
                item.put("value", "<unavailable>");
            }
            if (parentExpression != null && !parentExpression.isBlank()) {
                item.put("expression", buildArrayIndexExpression(parentExpression, i));
            }
            elements.add(item);
        }
        return elements;
    }

    private static List<Map<String, Object>> extractCollectionElements(
        long frameId,
        String expression,
        boolean isList,
        JavaDebugController controller
    ) {
        try {
            IJavaValue arrayVal = evaluateBestEffort(
                controller,
                frameId,
                "(" + expression + ").toArray()",
                "((java.util.Collection)(" + expression + ")).toArray()");
            if (!(arrayVal instanceof IJavaArray arr)) {
                return null;
            }
            int length = safeArrayLength(arr);
            int limit = Math.min(Math.max(0, length), MAX_ITEMS);
            List<Map<String, Object>> elements = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", i);
                try {
                    IJavaValue el = arr.getValue(i);
                    boolean isNull = el == null || el.isNull();
                    item.put("type", el == null ? "" : safeString(el::getReferenceTypeName, ""));
                    item.put("value", isNull ? "null" : safeString(el::getValueString, "<unavailable>"));
                } catch (Exception ignored) {
                    item.put("type", "");
                    item.put("value", "<unavailable>");
                }
                if (isList) {
                    item.put("expression", buildMethodExpression(expression, "get(" + i + ")"));
                }
                elements.add(item);
            }
            return elements;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Map<String, Object>> extractMapEntries(
        long frameId,
        String expression,
        JavaDebugController controller
    ) {
        try {
            IJavaValue arrayVal = evaluateBestEffort(
                controller,
                frameId,
                "(" + expression + ").entrySet().toArray()",
                "((java.util.Map)(" + expression + ")).entrySet().toArray()");
            if (!(arrayVal instanceof IJavaArray arr)) {
                return null;
            }
            IJavaThread javaThread = resolveJavaThread(controller, frameId);
            int length = safeArrayLength(arr);
            int limit = Math.min(Math.max(0, length), MAX_ITEMS);
            List<Map<String, Object>> entries = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                IJavaValue entryVal;
                try {
                    entryVal = arr.getValue(i);
                } catch (Exception ignored) {
                    continue;
                }
                if (!(entryVal instanceof IJavaObject entryObj) || entryObj.isNull()) {
                    continue;
                }
                IJavaValue keyVal = readEntryPart(entryObj, javaThread, "key", "k", "getKey");
                IJavaValue valVal = readEntryPart(entryObj, javaThread, "value", "v", "getValue");

                boolean keyNull = keyVal == null || keyVal.isNull();
                boolean valNull = valVal == null || valVal.isNull();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", keyNull ? "null" : safeString(keyVal::getValueString, "<unavailable>"));
                item.put("value", valNull ? "null" : safeString(valVal::getValueString, "<unavailable>"));
                item.put("valueType", valVal == null ? "" : safeString(valVal::getReferenceTypeName, ""));
                entries.add(item);
            }
            return entries;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IJavaValue readEntryPart(
        IJavaObject entryObj,
        IJavaThread thread,
        String primaryField,
        String altField,
        String methodName
    ) {
        for (String fieldName : new String[] { primaryField, altField }) {
            try {
                IJavaFieldVariable field = entryObj.getField(fieldName, true);
                if (field != null && field.getValue() instanceof IJavaValue jv) {
                    return jv;
                }
            } catch (Exception ignored) {}
        }
        if (thread != null) {
            try {
                return entryObj.sendMessage(
                    methodName, "()Ljava/lang/Object;", new IJavaValue[0], thread, false);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static IJavaValue evaluateBestEffort(
        JavaDebugController controller,
        long frameId,
        String primaryExpr,
        String fallbackExpr
    ) {
        try {
            return controller.evaluateRawValue(frameId, primaryExpr);
        } catch (Exception primaryErr) {
            try {
                return controller.evaluateRawValue(frameId, fallbackExpr);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static IJavaThread resolveJavaThread(JavaDebugController controller, long frameId) {
        try {
            IStackFrame frame = controller.getStackFrame(frameId);
            IThread thread = frame.getThread();
            return thread instanceof IJavaThread jt ? jt : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isMapValue(IJavaValue value, String typeName) {
        if (implementsInterface(value, "java.util.Map")) {
            return true;
        }
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        return typeName.startsWith("java.util.ImmutableCollections$Map")
            || typeName.endsWith("Map")
            || typeName.contains("Map$");
    }

    private static boolean isCollectionValue(IJavaValue value, String typeName) {
        if (implementsInterface(value, "java.util.Collection")) {
            return true;
        }
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        return typeName.startsWith("java.util.ImmutableCollections$List")
            || typeName.startsWith("java.util.ImmutableCollections$Set")
            || typeName.startsWith("java.util.Arrays$ArrayList")
            || typeName.endsWith("List")
            || typeName.endsWith("Set")
            || typeName.endsWith("Queue")
            || typeName.endsWith("Deque")
            || typeName.endsWith("Collection");
    }

    private static boolean isListValue(IJavaValue value, String typeName) {
        if (implementsInterface(value, "java.util.List")) {
            return true;
        }
        return typeName != null
            && (typeName.startsWith("java.util.ImmutableCollections$List")
                || typeName.endsWith("List"));
    }

    private static boolean implementsInterface(IJavaValue value, String targetInterface) {
        try {
            IJavaType javaType = value.getJavaType();
            if (javaType instanceof IJavaClassType classType) {
                IJavaInterfaceType[] interfaces = classType.getAllInterfaces();
                if (interfaces != null) {
                    for (IJavaInterfaceType iface : interfaces) {
                        if (targetInterface.equals(iface.getName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String resolveFrameSourceFile(IJavaStackFrame jf) {
        try {
            ILaunch launch = jf.getLaunch();
            ISourceLocator locator = launch != null ? launch.getSourceLocator() : null;
            if (locator != null) {
                Object source = locator.getSourceElement(jf);
                if (source instanceof IFile file) {
                    return file.getFullPath().toString();
                }
                if (source instanceof IJavaElement je && je.getResource() instanceof IFile file) {
                    return file.getFullPath().toString();
                }
            }
        } catch (Exception ignored) {}
        String sourcePath = safeString(jf::getSourcePath, "");
        if (!sourcePath.isBlank()) {
            return sourcePath;
        }
        return safeString(jf::getSourceName, "");
    }

    private static String buildFrameTitle(long frameId, String className, String method, int line) {
        String simpleClass = simpleTypeName(className);
        if (simpleClass.isBlank() && method.isBlank()) {
            return "Frame " + frameId;
        }
        String base = simpleClass.isBlank() ? method : (method.isBlank() ? simpleClass : simpleClass + "." + method);
        return line > 0 ? base + ":" + line : base;
    }

    private static String buildFrameSummary(
        String className,
        String method,
        String file,
        int line,
        int varCount
    ) {
        StringBuilder sb = new StringBuilder();
        if (!className.isBlank()) {
            sb.append(className);
        }
        if (!method.isBlank()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(method);
        }
        if (!file.isBlank()) {
            sb.append(" (").append(file);
            if (line > 0) {
                sb.append(':').append(line);
            }
            sb.append(')');
        }
        if (sb.length() > 0) {
            sb.append(" — ");
        }
        sb.append(varCount).append(varCount == 1 ? " variable" : " variables");
        return sb.toString();
    }

    private static String buildFieldExpression(String parentExpression, String fieldName) {
        if (parentExpression == null || parentExpression.isBlank()
                || !LogpointTemplate.isSimpleIdentifier(fieldName)) {
            return null;
        }
        return wrapTargetIfNeeded(parentExpression) + "." + fieldName;
    }

    private static String buildArrayIndexExpression(String parentExpression, int index) {
        return wrapTargetIfNeeded(parentExpression) + "[" + index + "]";
    }

    private static String buildMethodExpression(String parentExpression, String callSuffix) {
        return wrapTargetIfNeeded(parentExpression) + "." + callSuffix;
    }

    private static String wrapTargetIfNeeded(String expression) {
        String trimmed = expression.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (!Character.isJavaIdentifierPart(ch) && ch != '.' && ch != '[' && ch != ']') {
                return "(" + trimmed + ")";
            }
        }
        return trimmed;
    }

    private static Long safeUniqueId(IJavaObject obj) {
        try {
            return Long.valueOf(obj.getUniqueId());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int safeArrayLength(IJavaArray array) {
        try {
            return array.getLength();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "";
        }
        int lastDot = typeName.lastIndexOf('.');
        return lastDot >= 0 && lastDot + 1 < typeName.length()
            ? typeName.substring(lastDot + 1)
            : typeName;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen) + "...";
    }

    @FunctionalInterface
    private interface ThrowingStringSupplier {
        String get() throws Exception;
    }

    private static String safeString(ThrowingStringSupplier supplier, String fallback) {
        try {
            String value = supplier.get();
            return value != null ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
