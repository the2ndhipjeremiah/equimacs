package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Thin SWT shell that renders a {@link ViewSpec.HtmlSpec} inside an Edge WebView2 / SWT {@link Browser},
 * falling back cleanly to a read-only notice when no browser backend is available.
 */
public final class HtmlBrowserRenderer {

    /**
     * Handle to a rendered HTML view control enabling live state/event pushing and clean disposal.
     */
    public interface HtmlHandle {
        Control control();

        boolean isAlive();

        boolean matchesSource(ViewSpec.HtmlSpec other);

        void pushState(String stateJson);

        void pushEvent(String eventJson);

        void dispose();
    }

    private HtmlBrowserRenderer() {}

    /**
     * Renders {@code spec} inside {@code container} and wires the {@code window.equimacs} bridge functions.
     */
    public static HtmlHandle render(
            Composite container,
            String viewId,
            ViewSpec.HtmlSpec spec,
            Gson gson) {
        Gson safeGson = gson != null ? gson : new Gson();
        Browser browser = createBrowser(container);
        if (browser == null) {
            return createFallbackHandle(container, spec, safeGson);
        }

        browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        BrowserFunction actionFn = new BrowserFunction(browser, "__equimacsAction") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments == null || arguments.length == 0 || arguments[0] == null) {
                    return "ignored";
                }
                String actionJson = String.valueOf(arguments[0]);
                try {
                    JsonElement parsed = JsonParser.parseString(actionJson);
                    ViewAction action = ViewSpecParser.parseAction(parsed);
                    ViewActionDispatcher.dispatch(viewId, null, action);
                    return "ok";
                } catch (Exception e) {
                    System.err.println("[Equimacs HtmlBrowserRenderer] Invalid __equimacsAction payload: "
                        + e.getMessage());
                    return "error:" + e.getMessage();
                }
            }
        };

        BrowserFunction requestFn = new BrowserFunction(browser, "__equimacsRequest") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments == null || arguments.length < 2 || !(arguments[0] instanceof Number num)) {
                    return "error:invalid-arguments";
                }
                long callId = num.longValue();
                String requestType = arguments[1] == null ? "" : String.valueOf(arguments[1]);
                String paramsJson = (arguments.length >= 3 && arguments[2] != null)
                    ? String.valueOf(arguments[2])
                    : "{}";
                Display display = browser.getDisplay();
                ViewActionDispatcher.dispatchJsonAsync(requestType, paramsJson, (ok, json) -> {
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!browser.isDisposed()) {
                                browser.execute(HtmlBridgeInjector.buildResolveJs(callId, ok, json));
                            }
                        });
                    }
                });
                return "pending";
            }
        };

        AtomicReference<String> stateRef = new AtomicReference<>(safeGson.toJson(spec.state()));

        browser.addDisposeListener(e -> disposeFunctions(actionFn, requestFn));

        if (!spec.url().isBlank()) {
            browser.addProgressListener(new ProgressAdapter() {
                @Override
                public void completed(ProgressEvent event) {
                    if (!browser.isDisposed()) {
                        String currentState = stateRef.get();
                        browser.execute(HtmlBridgeInjector.buildBridgeBootstrapJs(viewId, currentState));
                    }
                }
            });
            browser.setUrl(spec.url());
        } else {
            browser.setText(HtmlBridgeInjector.inject(spec.html(), viewId, stateRef.get()));
        }

        return new LiveBrowserHandle(browser, spec, stateRef, actionFn, requestFn);
    }

    private static Browser createBrowser(Composite container) {
        try {
            return new Browser(container, SWT.EDGE);
        } catch (SWTError ignoredEdge) {
            try {
                return new Browser(container, SWT.NONE);
            } catch (SWTError ignoredDefault) {
                return null;
            }
        }
    }

    private static HtmlHandle createFallbackHandle(
            Composite container,
            ViewSpec.HtmlSpec spec,
            Gson gson) {
        StyledText text = new StyledText(
            container,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        StringBuilder notice = new StringBuilder();
        notice.append("SWT Browser / WebView2 backend is unavailable in this Eclipse environment.\n");
        if (!spec.url().isBlank()) {
            notice.append("URL: ").append(spec.url()).append('\n');
        }
        if (!spec.html().isBlank()) {
            notice.append("\n--- Raw HTML ---\n").append(spec.html()).append('\n');
        }
        if (!spec.state().isEmpty()) {
            notice.append("\n--- State ---\n").append(gson.toJson(spec.state())).append('\n');
        }
        text.setText(notice.toString());
        return new InertHtmlHandle(text, spec);
    }

    private static void disposeFunctions(BrowserFunction actionFn, BrowserFunction requestFn) {
        if (actionFn != null && !actionFn.isDisposed()) {
            actionFn.dispose();
        }
        if (requestFn != null && !requestFn.isDisposed()) {
            requestFn.dispose();
        }
    }

    private static final class LiveBrowserHandle implements HtmlHandle {

        private final Browser browser;
        private final ViewSpec.HtmlSpec sourceSpec;
        private final AtomicReference<String> stateRef;
        private final BrowserFunction actionFn;
        private final BrowserFunction requestFn;

        private LiveBrowserHandle(
                Browser browser,
                ViewSpec.HtmlSpec sourceSpec,
                AtomicReference<String> stateRef,
                BrowserFunction actionFn,
                BrowserFunction requestFn) {
            this.browser = browser;
            this.sourceSpec = sourceSpec;
            this.stateRef = stateRef;
            this.actionFn = actionFn;
            this.requestFn = requestFn;
        }

        @Override
        public Control control() {
            return browser;
        }

        @Override
        public boolean isAlive() {
            return browser != null && !browser.isDisposed();
        }

        @Override
        public boolean matchesSource(ViewSpec.HtmlSpec other) {
            if (other == null) {
                return false;
            }
            return Objects.equals(sourceSpec.html(), other.html())
                && Objects.equals(sourceSpec.url(), other.url())
                && Objects.equals(sourceSpec.toolbar(), other.toolbar());
        }

        @Override
        public void pushState(String stateJson) {
            stateRef.set(stateJson);
            executeScript(HtmlBridgeInjector.buildPushStateJs(stateJson));
        }

        @Override
        public void pushEvent(String eventJson) {
            executeScript(HtmlBridgeInjector.buildPushEventJs(eventJson));
        }

        @Override
        public void dispose() {
            disposeFunctions(actionFn, requestFn);
        }

        private void executeScript(String js) {
            if (browser == null || browser.isDisposed()) {
                return;
            }
            Display display = browser.getDisplay();
            if (display == null || display.isDisposed()) {
                return;
            }
            if (Display.getCurrent() == display) {
                if (!browser.isDisposed()) {
                    browser.execute(js);
                }
            } else {
                display.asyncExec(() -> {
                    if (!browser.isDisposed()) {
                        browser.execute(js);
                    }
                });
            }
        }
    }

    private static final class InertHtmlHandle implements HtmlHandle {

        private final Control control;
        private final ViewSpec.HtmlSpec sourceSpec;

        private InertHtmlHandle(Control control, ViewSpec.HtmlSpec sourceSpec) {
            this.control = control;
            this.sourceSpec = sourceSpec;
        }

        @Override
        public Control control() {
            return control;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public boolean matchesSource(ViewSpec.HtmlSpec other) {
            if (other == null) {
                return false;
            }
            return Objects.equals(sourceSpec.html(), other.html())
                && Objects.equals(sourceSpec.url(), other.url())
                && Objects.equals(sourceSpec.toolbar(), other.toolbar());
        }

        @Override
        public void pushState(String stateJson) {}

        @Override
        public void pushEvent(String eventJson) {}

        @Override
        public void dispose() {}
    }
}
