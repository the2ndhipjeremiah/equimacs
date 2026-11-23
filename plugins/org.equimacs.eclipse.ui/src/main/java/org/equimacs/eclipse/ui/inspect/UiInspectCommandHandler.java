package org.equimacs.eclipse.ui.inspect;

import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;

/**
 * OSGi Declarative Services bridge command handler for UI inspection and SWT widget driving.
 */
public final class UiInspectCommandHandler implements IBridgeCommandHandler {

    private volatile IBridgeService bridge;
    private final UiWidgetRegistry registry = new UiWidgetRegistry();
    private final SwtUiInspector inspector = new SwtUiInspector(registry);
    private final SwtWidgetDriver driver = new SwtWidgetDriver(registry, inspector);

    void setBridge(IBridgeService bridge) {
        this.bridge = bridge;
    }

    void unsetBridge(IBridgeService bridge) {
        this.bridge = null;
    }

    void activate() {
        // Defer SWT/workbench access until command handling so startup never fails in headless/uninitialized state.
    }

    void deactivate() {
        // Registry holds WeakReferences and prunes disposed widgets automatically.
    }

    @Override
    public Object handle(Request req) throws Exception {
        return switch (req) {
            case Request.UiTree r -> UiTreeCore.toMap(inspector.capture(r));
            case Request.UiClick r -> driver.click(r);
            case Request.UiType r -> driver.type(r);
            default -> throw new IllegalArgumentException(
                "UiInspectCommandHandler: no handler for " + req.getClass().getSimpleName());
        };
    }
}
