package org.equimacs.eclipse.ui.inspect;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.swt.widgets.Widget;

/**
 * Thread-safe bounded weak registry assigning stable runtime {@code w_<N>} IDs
 * to live SWT {@link Widget} instances across repeated inspection snapshots.
 */
public final class UiWidgetRegistry {

    static final int MAX_ENTRIES = 4000;

    private final AtomicLong nextId = new AtomicLong(0);
    private final ReferenceQueue<Widget> queue = new ReferenceQueue<>();
    private final Map<String, WeakKey> idToWidget = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<WeakKey, String> widgetToId = new HashMap<>(256);

    /**
     * Registers {@code widget} or returns its existing live {@code w_<N>} identifier.
     */
    public synchronized String register(Widget widget) {
        if (widget == null || widget.isDisposed()) {
            return null;
        }
        drainQueueLocked();

        WeakKey probe = new WeakKey(widget, null);
        String existingId = widgetToId.get(probe);
        if (existingId != null) {
            WeakKey existingRef = idToWidget.get(existingId);
            Widget existingWidget = existingRef != null ? existingRef.get() : null;
            if (existingWidget == widget && !existingWidget.isDisposed()) {
                return existingId;
            }
            idToWidget.remove(existingId);
            widgetToId.remove(probe);
        }

        String id = "w_" + nextId.incrementAndGet();
        WeakKey key = new WeakKey(widget, queue);
        idToWidget.put(id, key);
        widgetToId.put(key, id);

        if (idToWidget.size() > MAX_ENTRIES) {
            pruneLocked();
        }
        return id;
    }

    /**
     * Looks up a registered widget by {@code widgetId}, returning {@code null} if missing,
     * garbage-collected, or disposed.
     */
    public synchronized Widget lookup(String widgetId) {
        if (widgetId == null || widgetId.isBlank()) {
            return null;
        }
        drainQueueLocked();

        String trimmedId = widgetId.trim();
        WeakKey key = idToWidget.get(trimmedId);
        if (key == null) {
            return null;
        }
        Widget widget = key.get();
        if (widget == null || widget.isDisposed()) {
            idToWidget.remove(trimmedId);
            widgetToId.remove(key);
            return null;
        }
        return widget;
    }

    private void drainQueueLocked() {
        Reference<? extends Widget> cleared;
        while ((cleared = queue.poll()) != null) {
            if (cleared instanceof WeakKey key) {
                String id = widgetToId.remove(key);
                if (id != null) {
                    idToWidget.remove(id, key);
                }
            }
        }
    }

    private void pruneLocked() {
        Iterator<Map.Entry<String, WeakKey>> it = idToWidget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WeakKey> entry = it.next();
            WeakKey key = entry.getValue();
            Widget widget = key.get();
            if (widget == null || widget.isDisposed()) {
                it.remove();
                widgetToId.remove(key);
            }
        }
        Iterator<Map.Entry<String, WeakKey>> lruIt = idToWidget.entrySet().iterator();
        while (idToWidget.size() > MAX_ENTRIES && lruIt.hasNext()) {
            Map.Entry<String, WeakKey> eldest = lruIt.next();
            lruIt.remove();
            widgetToId.remove(eldest.getValue());
        }
    }

    private static final class WeakKey extends WeakReference<Widget> {
        private final int identityHash;

        WeakKey(Widget widget, ReferenceQueue<Widget> queue) {
            super(Objects.requireNonNull(widget, "widget"), queue);
            this.identityHash = System.identityHashCode(widget);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof WeakKey other)) {
                return false;
            }
            Widget self = get();
            Widget theirs = other.get();
            return self != null && self == theirs;
        }
    }
}
