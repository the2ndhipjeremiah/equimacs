package org.equimacs.eclipse.ui.viewhost;

import java.util.List;

/**
 * Optional live-refresh binding attached to a hosted moddable view.
 */
public record LiveBinding(
    List<String> events,
    ViewAction onEvent,
    boolean pushToHtml,
    int debounceMs
) {
    public static final int DEFAULT_DEBOUNCE_MS = 150;
    public static final int MIN_DEBOUNCE_MS = 50;
    public static final int MAX_DEBOUNCE_MS = 5000;

    public LiveBinding {
        events = events == null ? List.of() : List.copyOf(events);
        debounceMs = clampDebounce(debounceMs);
    }

    public static int clampDebounce(int ms) {
        if (ms <= 0) {
            return DEFAULT_DEBOUNCE_MS;
        }
        return Math.max(MIN_DEBOUNCE_MS, Math.min(ms, MAX_DEBOUNCE_MS));
    }

    public boolean matchesEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        return events.contains(eventName);
    }
}
