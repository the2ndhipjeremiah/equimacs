package org.equimacs.eclipse.ui.viewhost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Thread-safe in-memory registry of hosted moddable views keyed by secondary view ID.
 */
public final class ViewHostStore {

    public static final ViewHostStore DEFAULT = new ViewHostStore();

    private static final int MAX_VIEW_ID_LENGTH = 128;
    private static final Pattern VALID_VIEW_ID = Pattern.compile("^[A-Za-z0-9._-]+$");

    public record HostedView(String viewId, String title, ViewSpec spec, LiveBinding live, long updatedAtMs) {
        public HostedView(String viewId, String title, ViewSpec spec, long updatedAtMs) {
            this(viewId, title, spec, null, updatedAtMs);
        }
    }

    private final ConcurrentHashMap<String, HostedView> views = new ConcurrentHashMap<>();

    public ViewHostStore() {}

    /**
     * Trims and validates a moddable view ID.
     */
    public static String validateViewId(String viewId) {
        if (viewId == null) {
            throw new IllegalArgumentException("viewId must not be null");
        }
        String trimmed = viewId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("viewId must not be blank");
        }
        if (trimmed.length() > MAX_VIEW_ID_LENGTH) {
            throw new IllegalArgumentException("viewId length must be <= " + MAX_VIEW_ID_LENGTH);
        }
        if (trimmed.indexOf(':') >= 0) {
            throw new IllegalArgumentException("viewId must not contain ':'");
        }
        if (!VALID_VIEW_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("viewId must match ^[A-Za-z0-9._-]+$: " + trimmed);
        }
        return trimmed;
    }

    /**
     * Inserts or replaces a hosted view specification without a live binding.
     */
    public HostedView put(String viewId, String titleOverride, ViewSpec spec) {
        return put(viewId, titleOverride, spec, null);
    }

    /**
     * Inserts or replaces a hosted view specification with an optional {@link LiveBinding}.
     */
    public HostedView put(String viewId, String titleOverride, ViewSpec spec, LiveBinding live) {
        String validId = validateViewId(viewId);
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        String title = resolveTitle(titleOverride, spec.title(), validId);
        HostedView hosted = new HostedView(validId, title, spec, live, System.currentTimeMillis());
        views.put(validId, hosted);
        return hosted;
    }

    /**
     * Updates an existing hosted view specification without a live binding. Throws if the view ID is unknown.
     */
    public HostedView update(String viewId, String titleOverride, ViewSpec spec) {
        return update(viewId, titleOverride, spec, null);
    }

    /**
     * Updates an existing hosted view specification and {@link LiveBinding}. Throws if the view ID is unknown.
     */
    public HostedView update(String viewId, String titleOverride, ViewSpec spec, LiveBinding live) {
        String validId = validateViewId(viewId);
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        return views.compute(validId, (id, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Unknown moddable view: " + validId);
            }
            String fallback = existing.title() != null && !existing.title().isBlank()
                ? existing.title()
                : validId;
            String title = resolveTitle(titleOverride, spec.title(), fallback);
            return new HostedView(validId, title, spec, live, System.currentTimeMillis());
        });
    }

    /**
     * Removes a hosted view by ID, returning the removed entry or {@code null}.
     */
    public HostedView remove(String viewId) {
        String validId = validateViewId(viewId);
        return views.remove(validId);
    }

    /**
     * Returns the hosted view for {@code viewId}, or {@code null} if absent/blank.
     */
    public HostedView get(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return null;
        }
        return views.get(viewId.trim());
    }

    /**
     * Returns all hosted views sorted lexicographically by {@code viewId}.
     */
    public List<HostedView> listAll() {
        List<HostedView> list = new ArrayList<>(views.values());
        list.sort(Comparator.comparing(HostedView::viewId));
        return List.copyOf(list);
    }

    /**
     * Converts a {@link HostedView} into a JSON-serializable summary map.
     */
    public static Map<String, Object> toSummaryMap(HostedView view, boolean open) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("viewId", view.viewId());
        map.put("title", view.title());
        map.put("kind", view.spec().kind());
        map.put("itemCount", view.spec().itemCount());
        map.put("open", open);
        if (view.live() != null && !view.live().events().isEmpty()) {
            map.put("liveEvents", view.live().events());
        }
        map.put("updatedAtMs", view.updatedAtMs());
        return map;
    }

    private static String resolveTitle(String titleOverride, String specTitle, String fallback) {
        if (titleOverride != null && !titleOverride.isBlank()) {
            return titleOverride.trim();
        }
        if (specTitle != null && !specTitle.isBlank()) {
            return specTitle.trim();
        }
        return fallback;
    }
}
