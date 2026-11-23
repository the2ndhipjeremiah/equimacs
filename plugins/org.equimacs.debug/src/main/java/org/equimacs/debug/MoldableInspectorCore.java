package org.equimacs.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.equimacs.eclipse.bridge.api.IMoldableViewProvider;
import org.equimacs.eclipse.bridge.api.InspectContext;
import org.equimacs.eclipse.bridge.api.MoldableTab;

/**
 * Pure core assembly logic for the Moldable Inspector.
 */
public final class MoldableInspectorCore {

    private static final int MAX_VIEW_ID_LENGTH = 100;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MoldableInspectorCore() {}

    /**
     * Immutable result of assembling inspector tabs from built-in and external providers.
     */
    public record AssemblyResult(
        String title,
        String specJson,
        List<MoldableTab> tabs,
        List<String> providerIds
    ) {
        public AssemblyResult {
            title = title == null ? "" : title;
            specJson = specJson == null ? "{}" : specJson;
            tabs = tabs == null ? List.of() : List.copyOf(tabs);
            providerIds = providerIds == null ? List.of() : List.copyOf(providerIds);
        }
    }

    private record TabEntry(MoldableTab tab, JsonObject content) {}

    /**
     * Evaluates built-in then external providers, validates and deduplicates tabs by ID,
     * sorts them deterministically, and returns a ModdableView {@code TabsSpec} JSON bundle.
     */
    public static AssemblyResult assemble(
        InspectContext context,
        List<IMoldableViewProvider> builtIns,
        List<IMoldableViewProvider> externalProviders
    ) {
        InspectContext safeContext = context != null
            ? context
            : new InspectContext("", "", "", "", Map.of());

        Map<String, TabEntry> tabsById = new LinkedHashMap<>();
        Set<String> matchedProviderIds = new LinkedHashSet<>();

        evaluateProviders(safeContext, builtIns, tabsById, matchedProviderIds);
        evaluateProviders(safeContext, externalProviders, tabsById, matchedProviderIds);

        String resolvedTitle = resolveTitle(safeContext);
        if (tabsById.isEmpty()) {
            TabEntry fallback = buildFallbackSummaryTab(safeContext, resolvedTitle);
            tabsById.put(fallback.tab().id(), fallback);
        }

        List<TabEntry> ordered = new ArrayList<>(tabsById.values());
        ordered.sort(Comparator
            .comparingInt((TabEntry entry) -> entry.tab().priority())
            .thenComparing(entry -> entry.tab().label())
            .thenComparing(entry -> entry.tab().id()));

        List<MoldableTab> finalTabs = new ArrayList<>(ordered.size());
        JsonArray tabItems = new JsonArray();
        for (TabEntry entry : ordered) {
            finalTabs.add(entry.tab());
            JsonObject tabJson = new JsonObject();
            tabJson.addProperty("label", entry.tab().label());
            tabJson.add("content", entry.content());
            tabItems.add(tabJson);
        }

        JsonObject tabsSpec = new JsonObject();
        tabsSpec.addProperty("kind", "tabs");
        tabsSpec.addProperty("title", resolvedTitle);
        tabsSpec.add("tabs", tabItems);

        return new AssemblyResult(
            resolvedTitle,
            GSON.toJson(tabsSpec),
            finalTabs,
            List.copyOf(matchedProviderIds));
    }

    /**
     * Generates a sanitized ModdableView ID matching {@code ^[A-Za-z0-9._-]+$} with length <= 100.
     */
    public static String defaultViewId(String targetKind, String title) {
        String kindPart = sanitizeSegment(targetKind);
        if (kindPart.isEmpty()) {
            kindPart = "target";
        }
        String titlePart = sanitizeSegment(title);
        if (titlePart.isEmpty()) {
            titlePart = "item";
        }
        String combined = "inspect-" + kindPart + "-" + titlePart;
        if (combined.length() <= MAX_VIEW_ID_LENGTH) {
            return combined;
        }
        String truncated = combined.substring(0, MAX_VIEW_ID_LENGTH);
        while (truncated.length() > "inspect-x".length()
                && (truncated.endsWith("-") || truncated.endsWith("."))) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }

    private static void evaluateProviders(
        InspectContext context,
        List<IMoldableViewProvider> providers,
        Map<String, TabEntry> tabsById,
        Set<String> matchedProviderIds
    ) {
        if (providers == null || providers.isEmpty()) {
            return;
        }
        for (IMoldableViewProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            boolean supported;
            try {
                supported = provider.supports(context);
            } catch (Throwable ignored) {
                continue;
            }
            if (!supported) {
                continue;
            }

            List<MoldableTab> contributed;
            try {
                contributed = provider.provideViews(context);
            } catch (Throwable ignored) {
                continue;
            }
            if (contributed == null || contributed.isEmpty()) {
                continue;
            }

            int validCount = 0;
            for (MoldableTab tab : contributed) {
                TabEntry validated = validateTab(tab);
                if (validated != null) {
                    tabsById.put(validated.tab().id(), validated);
                    validCount++;
                }
            }
            if (validCount > 0) {
                String providerId = safeProviderId(provider);
                if (providerId != null && !providerId.isBlank()) {
                    matchedProviderIds.add(providerId);
                }
            }
        }
    }

    private static String safeProviderId(IMoldableViewProvider provider) {
        try {
            return provider.id();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TabEntry validateTab(MoldableTab tab) {
        if (tab == null
                || tab.id() == null || tab.id().isBlank()
                || tab.label() == null || tab.label().isBlank()
                || tab.specJson() == null || tab.specJson().isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(tab.specJson());
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            return new TabEntry(tab, parsed.getAsJsonObject());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TabEntry buildFallbackSummaryTab(InspectContext context, String resolvedTitle) {
        JsonArray entries = new JsonArray();
        entries.add(kvEntry("Target Kind", context.targetKind()));
        entries.add(kvEntry("Title", context.title()));
        entries.add(kvEntry("Type", context.typeName()));
        entries.add(kvEntry("Summary", context.summary()));

        JsonObject section = new JsonObject();
        section.addProperty("heading", "Summary");
        section.add("entries", entries);

        JsonArray sections = new JsonArray();
        sections.add(section);

        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "key-value");
        spec.addProperty("title", resolvedTitle);
        spec.add("sections", sections);

        MoldableTab tab = new MoldableTab("summary", "Summary", 100, GSON.toJson(spec));
        return new TabEntry(tab, spec);
    }

    private static JsonObject kvEntry(String key, String value) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("value", value == null ? "" : value);
        return entry;
    }

    private static String resolveTitle(InspectContext context) {
        if (!context.title().isBlank()) {
            return context.title();
        }
        if (!context.targetKind().isBlank()) {
            return context.targetKind();
        }
        return "Inspector";
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isAllowedViewIdChar(ch)) {
                sb.append(ch);
                lastDash = (ch == '-');
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        while (sb.length() > 0 && (sb.charAt(0) == '-' || sb.charAt(0) == '.')) {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == '-' || sb.charAt(sb.length() - 1) == '.')) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private static boolean isAllowedViewIdChar(char ch) {
        return (ch >= 'A' && ch <= 'Z')
            || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9')
            || ch == '.'
            || ch == '_'
            || ch == '-';
    }
}
