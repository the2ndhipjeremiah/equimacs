package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Pure thread-safe live-refresh coordinator that subscribes views to bridge events
 * with per-view debouncing and optional HTML event pushing.
 */
public final class LiveViewRefreshCoordinator {

    private final ConcurrentHashMap<String, LiveBinding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRefreshes = new ConcurrentHashMap<>();
    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;

    public LiveViewRefreshCoordinator() {}

    /**
     * Registers or replaces the {@link LiveBinding} for {@code viewId}.
     * Passing {@code null} or an empty event list unregisters {@code viewId}.
     */
    public void register(String viewId, LiveBinding binding) {
        if (viewId == null || viewId.isBlank()) {
            return;
        }
        String key = viewId.trim();
        cancelPending(key);
        if (binding == null || binding.events().isEmpty()) {
            bindings.remove(key);
            return;
        }
        bindings.put(key, binding);
    }

    /**
     * Unregisters {@code viewId} and cancels any pending debounced refresh task for it.
     */
    public void unregister(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return;
        }
        String key = viewId.trim();
        bindings.remove(key);
        cancelPending(key);
    }

    /**
     * Returns {@code true} if {@code viewId} currently has an active live binding.
     */
    public boolean isRegistered(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return false;
        }
        return bindings.containsKey(viewId.trim());
    }

    /**
     * Removes all registered bindings and cancels all pending debounced refresh tasks.
     */
    public void clear() {
        bindings.clear();
        for (Map.Entry<String, ScheduledFuture<?>> entry : pendingRefreshes.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future != null) {
                future.cancel(false);
            }
        }
        pendingRefreshes.clear();
    }

    /**
     * Clears all registrations and shuts down the background debounce scheduler.
     */
    public void shutdown() {
        clear();
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    /**
     * Processes an incoming bridge event, pushing immediately to HTML listeners when enabled
     * and scheduling debounced {@code onEvent} refreshes for matching registered views.
     */
    public void onBridgeEvent(
            JsonObject event,
            BiConsumer<String, LiveBinding> onRefresh,
            BiConsumer<String, JsonObject> onHtmlPush) {
        if (event == null) {
            return;
        }
        JsonElement eventEl = event.get("event");
        if (eventEl == null || !eventEl.isJsonPrimitive()) {
            return;
        }
        String eventName = eventEl.getAsString();
        if (eventName == null || eventName.isBlank()) {
            return;
        }

        for (Map.Entry<String, LiveBinding> entry : bindings.entrySet()) {
            String viewId = entry.getKey();
            LiveBinding binding = entry.getValue();
            if (!binding.matchesEvent(eventName)) {
                continue;
            }
            if (binding.pushToHtml() && onHtmlPush != null) {
                try {
                    onHtmlPush.accept(viewId, event);
                } catch (Throwable t) {
                    System.err.println("[Equimacs LiveViewRefreshCoordinator] HTML push failed for '"
                        + viewId + "': " + t.getMessage());
                }
            }
            if (binding.onEvent() != null && onRefresh != null) {
                scheduleDebouncedRefresh(viewId, binding, onRefresh);
            }
        }
    }

    private void scheduleDebouncedRefresh(
            String viewId,
            LiveBinding binding,
            BiConsumer<String, LiveBinding> onRefresh) {
        AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();
        pendingRefreshes.compute(viewId, (id, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            Runnable task = () -> {
                try {
                    LiveBinding active = bindings.get(id);
                    if (active != null && active.equals(binding)) {
                        onRefresh.accept(id, active);
                    }
                } catch (Throwable t) {
                    System.err.println("[Equimacs LiveViewRefreshCoordinator] Refresh callback failed for '"
                        + id + "': " + t.getMessage());
                } finally {
                    ScheduledFuture<?> self = selfRef.get();
                    if (self != null) {
                        pendingRefreshes.remove(id, self);
                    }
                }
            };
            ScheduledFuture<?> future = scheduleTask(task, binding.debounceMs());
            selfRef.set(future);
            return future;
        });
    }

    private ScheduledFuture<?> scheduleTask(Runnable task, long delayMs) {
        synchronized (schedulerLock) {
            ScheduledExecutorService exec = ensureSchedulerLocked();
            try {
                return exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                return null;
            }
        }
    }

    private void cancelPending(String viewId) {
        ScheduledFuture<?> pending = pendingRefreshes.remove(viewId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private ScheduledExecutorService ensureSchedulerLocked() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "equimacs-live-view-debounce");
                t.setDaemon(true);
                return t;
            });
        }
        return scheduler;
    }
}
