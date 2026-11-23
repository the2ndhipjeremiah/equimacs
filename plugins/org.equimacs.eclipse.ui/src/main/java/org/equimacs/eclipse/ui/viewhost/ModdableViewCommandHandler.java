package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

/**
 * OSGi DS bridge command handler for {@code ViewOpen}, {@code ViewUpdate}, {@code ViewClose},
 * and {@code ViewList}, managing live-refresh event subscriptions.
 */
public final class ModdableViewCommandHandler implements IBridgeCommandHandler {

    private static final LiveViewRefreshCoordinator LIVE_COORDINATOR = new LiveViewRefreshCoordinator();

    private final Consumer<JsonObject> bridgeEventListener = ModdableViewCommandHandler::handleBridgeEvent;
    private volatile IBridgeService boundBridge;

    void setBridge(IBridgeService bridge) {
        IBridgeService previous = this.boundBridge;
        if (previous != null && previous != bridge) {
            previous.removeEventListener(bridgeEventListener);
        }
        this.boundBridge = bridge;
        ViewActionDispatcher.setBridge(bridge);
        if (bridge != null) {
            rehydrateLiveBindings();
            bridge.addEventListener(bridgeEventListener);
        }
    }

    void unsetBridge(IBridgeService bridge) {
        if (bridge != null) {
            bridge.removeEventListener(bridgeEventListener);
        }
        if (this.boundBridge == bridge) {
            this.boundBridge = null;
        }
        ViewActionDispatcher.setBridge(null);
        LIVE_COORDINATOR.clear();
    }

    void activate() {
        // Intentionally empty so activation remains safe before workspace/workbench initialization.
    }

    void deactivate() {
        IBridgeService bridge = this.boundBridge;
        this.boundBridge = null;
        if (bridge != null) {
            bridge.removeEventListener(bridgeEventListener);
        }
        ViewActionDispatcher.setBridge(null);
        LIVE_COORDINATOR.shutdown();
    }

    private static void rehydrateLiveBindings() {
        for (ViewHostStore.HostedView hosted : ViewHostStore.DEFAULT.listAll()) {
            if (hosted.live() != null && !hosted.live().events().isEmpty() && ModdableView.isOpen(hosted.viewId())) {
                LIVE_COORDINATOR.register(hosted.viewId(), hosted.live());
            }
        }
    }

    static void onViewDisposed(String viewId) {
        if (viewId != null && !viewId.isBlank()) {
            LIVE_COORDINATOR.unregister(viewId);
        }
    }

    @Override
    public Object handle(Request req) throws Exception {
        return switch (req) {
            case Request.ViewOpen r -> viewOpen(r);
            case Request.ViewUpdate r -> viewUpdate(r);
            case Request.ViewClose r -> viewClose(r);
            case Request.ViewList _ -> viewList();
            default -> throw new IllegalArgumentException(
                "ModdableViewCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }

    private static Map<String, Object> viewOpen(Request.ViewOpen req) {
        String validId = ViewHostStore.validateViewId(req.viewId());
        ViewSpecParser.ParsedView envelope = ViewSpecParser.parseEnvelope(req.specJson());
        ViewHostStore.HostedView previous = ViewHostStore.DEFAULT.get(validId);
        ViewHostStore.HostedView hosted =
            ViewHostStore.DEFAULT.put(validId, req.title(), envelope.spec(), envelope.live());

        try {
            runOnUiThread(() -> {
                IWorkbenchPage page = requireActiveWorkbenchPage();
                try {
                    IViewPart part = page.showView(ModdableView.VIEW_ID, validId, IWorkbenchPage.VIEW_ACTIVATE);
                    if (part instanceof ModdableView modView) {
                        modView.render(hosted);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "Failed to open moddable view '" + validId + "': " + e.getMessage(), e);
                }
                return null;
            });
        } catch (RuntimeException e) {
            if (previous == null) {
                ViewHostStore.DEFAULT.remove(validId);
            } else {
                ViewHostStore.DEFAULT.put(validId, previous.title(), previous.spec(), previous.live());
            }
            throw e;
        }

        LIVE_COORDINATOR.register(validId, envelope.live());
        return ViewHostStore.toSummaryMap(hosted, true);
    }

    private static Map<String, Object> viewUpdate(Request.ViewUpdate req) {
        String validId = ViewHostStore.validateViewId(req.viewId());
        ViewSpecParser.ParsedView envelope = ViewSpecParser.parseEnvelope(req.specJson());
        ViewHostStore.HostedView hosted =
            ViewHostStore.DEFAULT.update(validId, req.title(), envelope.spec(), envelope.live());
        LIVE_COORDINATOR.register(validId, envelope.live());

        Display display = workbenchDisplay();
        if (display != null) {
            runOnUiThread(() -> {
                ModdableView active = ModdableView.findActive(validId);
                if (active != null) {
                    active.render(hosted);
                }
                return null;
            });
        }

        return ViewHostStore.toSummaryMap(hosted, ModdableView.isOpen(validId));
    }

    private static Map<String, Object> viewClose(Request.ViewClose req) {
        String validId = ViewHostStore.validateViewId(req.viewId());
        LIVE_COORDINATOR.unregister(validId);
        ViewHostStore.HostedView removed = ViewHostStore.DEFAULT.remove(validId);

        boolean closedInWorkbench = false;
        Display display = workbenchDisplay();
        if (display != null) {
            closedInWorkbench = runOnUiThread(() -> hideViewReferences(validId));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("closed", true);
        result.put("viewId", validId);
        result.put("wasPresent", removed != null || closedInWorkbench);
        return result;
    }

    private static List<Map<String, Object>> viewList() {
        List<ViewHostStore.HostedView> all = ViewHostStore.DEFAULT.listAll();
        List<Map<String, Object>> summaries = new ArrayList<>(all.size());
        for (ViewHostStore.HostedView hosted : all) {
            summaries.add(ViewHostStore.toSummaryMap(hosted, ModdableView.isOpen(hosted.viewId())));
        }
        return summaries;
    }

    private static void handleBridgeEvent(JsonObject event) {
        LIVE_COORDINATOR.onBridgeEvent(
            event,
            (viewId, binding) -> {
                if (ModdableView.isOpen(viewId) && binding.onEvent() != null) {
                    ViewActionDispatcher.dispatch(viewId, null, binding.onEvent());
                }
            },
            (viewId, ev) -> {
                ModdableView active = ModdableView.findActive(viewId);
                if (active != null) {
                    active.pushEvent(ev.toString());
                }
            });
    }

    private static boolean hideViewReferences(String validId) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return false;
        }
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null) {
            return false;
        }
        boolean found = false;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IViewReference ref : page.getViewReferences()) {
                    if (ModdableView.VIEW_ID.equals(ref.getId()) && validId.equals(ref.getSecondaryId())) {
                        page.hideView(ref);
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    private static IWorkbenchPage requireActiveWorkbenchPage() {
        if (!PlatformUI.isWorkbenchRunning()) {
            throw new IllegalStateException("Eclipse workbench is not running");
        }
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null) {
            throw new IllegalStateException("Eclipse workbench is unavailable");
        }
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
            if (windows.length > 0) {
                window = windows[0];
            }
        }
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null) {
            throw new IllegalStateException("No active Eclipse workbench page");
        }
        return page;
    }

    private static Display workbenchDisplay() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbench workbench;
        try {
            workbench = PlatformUI.getWorkbench();
        } catch (IllegalStateException e) {
            return null;
        }
        if (workbench == null || workbench.isClosing()) {
            return null;
        }
        Display display = workbench.getDisplay();
        return (display != null && !display.isDisposed()) ? display : null;
    }

    private static <T> T runOnUiThread(Supplier<T> action) {
        Display display = workbenchDisplay();
        if (display == null) {
            throw new IllegalStateException("SWT display unavailable");
        }
        if (Display.getCurrent() == display) {
            return action.get();
        }
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                resultRef.set(action.get());
            } catch (RuntimeException e) {
                errorRef.set(e);
            }
        });
        RuntimeException err = errorRef.get();
        if (err != null) {
            throw err;
        }
        return resultRef.get();
    }
}
