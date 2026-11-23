package org.equimacs.debug;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.eclipse.bridge.api.InspectContext;
import org.equimacs.eclipse.bridge.api.MoldableTab;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;

/**
 * Orchestrates JDI/JDT extraction, built-in and whiteboard-contributed {@link IMoldableViewProvider}
 * assembly, and optional workbench view rendering via {@link Request.ViewOpen}.
 */
final class MoldableInspectorService {

    private final JavaDebugController controller;
    private final JavaNavigationService navigation;
    private final CopyOnWriteArrayList<IMoldableViewProvider> externalProviders;

    MoldableInspectorService(
        JavaDebugController controller,
        JavaNavigationService navigation,
        Collection<IMoldableViewProvider> initialProviders
    ) {
        this.controller = controller;
        this.navigation = navigation;
        this.externalProviders = new CopyOnWriteArrayList<>();
        if (initialProviders != null) {
            for (IMoldableViewProvider provider : initialProviders) {
                if (provider != null) {
                    this.externalProviders.addIfAbsent(provider);
                }
            }
        }
    }

    void addProvider(IMoldableViewProvider provider) {
        if (provider != null) {
            externalProviders.addIfAbsent(provider);
        }
    }

    void removeProvider(IMoldableViewProvider provider) {
        if (provider != null) {
            externalProviders.remove(provider);
        }
    }

    Map<String, Object> inspectFrame(
        long frameId,
        Boolean openView,
        String viewId,
        IBridgeService bridge
    ) throws CoreException {
        IStackFrame frame = controller.getStackFrame(frameId);
        InspectContext context = JdiValueExtractor.extractFrame(frameId, frame);
        return assembleAndRespond(context, openView, viewId, bridge);
    }

    Map<String, Object> inspectValue(
        long frameId,
        String expression,
        int depth,
        Boolean openView,
        String viewId,
        IBridgeService bridge
    ) throws CoreException {
        IJavaValue value = controller.evaluateRawValue(frameId, expression);
        InspectContext context = JdiValueExtractor.extractValue(frameId, expression, value, depth, controller);
        return assembleAndRespond(context, openView, viewId, bridge);
    }

    Map<String, Object> inspectSymbol(
        String file,
        int offset,
        Boolean openView,
        String viewId,
        IBridgeService bridge
    ) throws CoreException {
        if (file == null || file.isBlank()) {
            throw error("InspectSymbol requires a non-blank file path");
        }
        Map<String, Object> data = navigation.inspectSymbolData(file, offset);
        @SuppressWarnings("unchecked")
        Map<String, Object> target = data.get("target") instanceof Map<?, ?> raw
            ? (Map<String, Object>) raw
            : Map.of();

        String name = str(target.get("name"));
        String kind = str(target.get("kind"));
        String fqn = str(target.get("fqn"));
        String declaringType = str(target.get("declaringType"));
        String targetFile = str(target.get("file"));
        int line = target.get("line") instanceof Number n ? n.intValue() : -1;

        String title = !name.isBlank() ? name : file;
        String typeName = !fqn.isBlank() ? fqn : (!declaringType.isBlank() ? declaringType : kind);
        String summary = buildSymbolSummary(kind, title, targetFile, line);

        InspectContext context = new InspectContext("jdt-symbol", title, typeName, summary, data);
        return assembleAndRespond(context, openView, viewId, bridge);
    }

    List<Map<String, Object>> listProviders() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IMoldableViewProvider provider : BuiltInInspectProviders.all()) {
            result.add(describeProvider(provider, true));
        }
        for (IMoldableViewProvider provider : externalProviders) {
            if (provider != null) {
                result.add(describeProvider(provider, false));
            }
        }
        return result;
    }

    private Map<String, Object> assembleAndRespond(
        InspectContext context,
        Boolean openView,
        String viewIdOverride,
        IBridgeService bridge
    ) {
        List<IMoldableViewProvider> externalsSnapshot = List.copyOf(externalProviders);
        MoldableInspectorCore.AssemblyResult assembled = MoldableInspectorCore.assemble(
            context, BuiltInInspectProviders.all(), externalsSnapshot);

        String resolvedViewId = (viewIdOverride != null && !viewIdOverride.isBlank())
            ? viewIdOverride.trim()
            : MoldableInspectorCore.defaultViewId(context.targetKind(), context.title());

        boolean viewOpened = false;
        String viewOpenNote = null;
        if (!Boolean.FALSE.equals(openView)) {
            if (bridge == null) {
                viewOpenNote = "Bridge service unavailable";
            } else {
                try {
                    Response response = bridge.dispatch(
                        new Request.ViewOpen(resolvedViewId, assembled.title(), assembled.specJson()));
                    switch (response) {
                        case Response.Success _ -> viewOpened = true;
                        case Response.Error err -> viewOpenNote = err.message() != null
                            ? err.message()
                            : "ViewOpen failed";
                        default -> viewOpenNote = "Unexpected ViewOpen response";
                    }
                } catch (RuntimeException e) {
                    viewOpenNote = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
            }
        } else {
            viewOpenNote = "openView=false";
        }

        List<Map<String, Object>> tabEntries = new ArrayList<>(assembled.tabs().size());
        for (MoldableTab tab : assembled.tabs()) {
            Map<String, Object> tabMap = new LinkedHashMap<>();
            tabMap.put("id", tab.id());
            tabMap.put("label", tab.label());
            tabMap.put("priority", tab.priority());
            tabMap.put("spec", JsonParser.parseString(tab.specJson()));
            tabEntries.add(tabMap);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetKind", context.targetKind());
        result.put("title", context.title());
        result.put("typeName", context.typeName());
        result.put("summary", context.summary());
        result.put("viewId", resolvedViewId);
        result.put("viewOpened", viewOpened);
        if (!viewOpened && viewOpenNote != null && !viewOpenNote.isBlank()) {
            result.put("viewOpenNote", viewOpenNote);
        }
        result.put("providers", assembled.providerIds());
        result.put("tabs", tabEntries);
        result.put("spec", JsonParser.parseString(assembled.specJson()));
        return result;
    }

    private static Map<String, Object> describeProvider(IMoldableViewProvider provider, boolean builtIn) {
        String id;
        try {
            String rawId = provider.id();
            id = (rawId != null && !rawId.isBlank()) ? rawId : provider.getClass().getName();
        } catch (Throwable ignored) {
            id = provider.getClass().getName();
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("source", builtIn ? "built-in" : "external");
        info.put("builtIn", builtIn);
        info.put("className", provider.getClass().getName());
        return info;
    }

    private static String buildSymbolSummary(String kind, String name, String file, int line) {
        StringBuilder sb = new StringBuilder();
        if (!kind.isBlank()) {
            sb.append(kind).append(' ');
        }
        sb.append(name);
        if (!file.isBlank()) {
            sb.append(" (").append(file);
            if (line > 0) {
                sb.append(':').append(line);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static CoreException error(String message) {
        return new CoreException(new Status(IStatus.ERROR, "org.equimacs.debug", message));
    }
}
