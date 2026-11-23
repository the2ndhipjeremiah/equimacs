package org.equimacs.eclipse.ui.inspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.MUILabel;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.equimacs.eclipse.ui.inspect.UiNode.UiBounds;
import org.equimacs.eclipse.ui.inspect.UiNode.UiTreeSnapshot;
import org.equimacs.eclipse.ui.inspect.UiNode.WorkbenchPartSummary;
import org.equimacs.protocol.Request;

/**
 * SWT and Eclipse workbench UI inspector that captures a snapshot of live widgets
 * and open workbench parts on the SWT display thread.
 */
public final class SwtUiInspector {

    static final int MAX_NODES_DEFAULT = 1500;
    private static final int MAX_COLLECTION_ITEMS = 100;
    private static final int MAX_STYLED_TEXT_CHARS = 200;

    private final UiWidgetRegistry registry;

    public SwtUiInspector(UiWidgetRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    UiWidgetRegistry registry() {
        return registry;
    }

    /**
     * Captures and filters a live SWT UI tree snapshot according to {@code req}.
     */
    public UiTreeSnapshot capture(Request.UiTree req) {
        String rootScope = normalizeRoot(req != null ? req.root() : null);
        int maxDepth = UiTreeCore.clampMaxDepth(req != null ? req.maxDepth() : 0);
        boolean visibleOnly = req == null || !Boolean.FALSE.equals(req.visibleOnly());

        UiTreeSnapshot raw = runOnUiThread(() -> captureUnfilteredOnUiThread(rootScope, maxDepth, visibleOnly));
        String textFilter = req != null ? req.textFilter() : null;
        String widgetType = req != null ? req.widgetType() : null;
        List<UiNode> filteredRoots = UiTreeCore.filterTree(raw.roots(), textFilter, widgetType);

        return new UiTreeSnapshot(
            raw.root(),
            raw.perspectiveId(),
            raw.activeShellTitle(),
            raw.views(),
            raw.editors(),
            filteredRoots,
            raw.totalNodesVisited(),
            raw.truncated()
        );
    }

    /**
     * Captures an unfiltered snapshot on the current SWT UI thread (for selector resolution).
     */
    UiTreeSnapshot captureUnfilteredOnUiThread(String rootScope, int maxDepth, boolean visibleOnly) {
        Display display = requireDisplay();
        String normalizedRoot = normalizeRoot(rootScope);
        int clampedDepth = UiTreeCore.clampMaxDepth(maxDepth);

        WorkbenchSummary workbenchSummary = readWorkbenchSummary();
        Shell activeShell = display.getActiveShell();
        String activeShellTitle = (activeShell != null && !activeShell.isDisposed())
            ? safeString(activeShell.getText())
            : null;

        List<Widget> rootWidgets = resolveRootWidgets(
            display,
            workbenchSummary.window(),
            workbenchSummary.page(),
            workbenchSummary.mainShell(),
            normalizedRoot,
            visibleOnly
        );

        WalkState state = new WalkState(display.getFocusControl(), clampedDepth, visibleOnly, MAX_NODES_DEFAULT);
        List<UiNode> roots = new ArrayList<>();
        for (int i = 0; i < rootWidgets.size(); i++) {
            Widget rootWidget = rootWidgets.get(i);
            String rootPath = widgetTypeName(rootWidget) + "[" + i + "]";
            UiNode node = walkWidget(rootWidget, rootPath, 0, state);
            if (node != null) {
                roots.add(node);
            }
            if (state.truncated) {
                break;
            }
        }

        return new UiTreeSnapshot(
            normalizedRoot,
            workbenchSummary.perspectiveId(),
            activeShellTitle,
            workbenchSummary.views(),
            workbenchSummary.editors(),
            roots,
            state.visitedNodes,
            state.truncated
        );
    }

    static Display requireDisplay() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("SWT display unavailable");
        }
        return display;
    }

    static <T> T runOnUiThread(Supplier<T> task) {
        Display display = requireDisplay();
        if (Display.getCurrent() == display) {
            return task.get();
        }
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                resultRef.set(task.get());
            } catch (Throwable t) {
                errorRef.set(t);
            }
        });
        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof RuntimeException re) {
                throw re;
            }
            if (error instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(error.getMessage(), error);
        }
        return resultRef.get();
    }

    static String widgetTypeName(Widget widget) {
        if (widget == null) {
            return "Widget";
        }
        Class<?> cls = widget.getClass();
        while (cls != null) {
            String simple = cls.getSimpleName();
            if (!simple.isEmpty()) {
                return simple;
            }
            cls = cls.getSuperclass();
        }
        return "Widget";
    }

    static String extractWidgetText(Widget widget) {
        if (widget == null || widget.isDisposed()) {
            return null;
        }
        String direct = switch (widget) {
            case Shell s -> s.getText();
            case Group g -> g.getText();
            case Button b -> b.getText();
            case Label l -> l.getText();
            case Link l -> l.getText();
            case Text t -> t.getText();
            case StyledText st -> {
                int count = st.getCharCount();
                yield count == 0 ? "" : st.getTextRange(0, Math.min(MAX_STYLED_TEXT_CHARS, count));
            }
            case Combo c -> c.getText();
            case TabFolder tf -> {
                int idx = tf.getSelectionIndex();
                yield (idx >= 0 && idx < tf.getItemCount()) ? tf.getItem(idx).getText() : null;
            }
            case CTabFolder ctf -> {
                CTabItem sel = ctf.getSelection();
                yield sel != null ? sel.getText() : null;
            }
            case ToolItem ti -> ti.getText();
            case MenuItem mi -> mi.getText();
            case TabItem ti -> ti.getText();
            case CTabItem cti -> cti.getText();
            default -> null;
        };
        if (direct == null || direct.isBlank()) {
            Object modelObj = widget.getData("modelElement");
            if (modelObj instanceof MUILabel uiLabel) {
                String localized = uiLabel.getLocalizedLabel();
                if (localized != null && !localized.isBlank()) {
                    return localized;
                }
            }
        }
        return direct;
    }

    private static String normalizeRoot(String root) {
        if (root == null || root.isBlank()) {
            return "active";
        }
        return root.trim();
    }

    private WorkbenchSummary readWorkbenchSummary() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return new WorkbenchSummary(null, null, null, null, List.of(), List.of());
        }
        IWorkbench workbench;
        try {
            workbench = PlatformUI.getWorkbench();
        } catch (IllegalStateException e) {
            return new WorkbenchSummary(null, null, null, null, List.of(), List.of());
        }
        if (workbench == null) {
            return new WorkbenchSummary(null, null, null, null, List.of(), List.of());
        }
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        IWorkbenchWindow fallbackWindow = window;
        if (fallbackWindow == null) {
            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
            if (windows != null && windows.length > 0) {
                fallbackWindow = windows[0];
            }
        }
        Shell mainShell = fallbackWindow != null ? fallbackWindow.getShell() : null;
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null) {
            return new WorkbenchSummary(window, null, mainShell, null, List.of(), List.of());
        }

        IPerspectiveDescriptor perspective = page.getPerspective();
        String perspectiveId = perspective != null ? perspective.getId() : null;
        IWorkbenchPartReference activeRef = page.getActivePartReference();
        IWorkbenchPart activePart = page.getActivePart();

        List<WorkbenchPartSummary> views = new ArrayList<>();
        IViewReference[] viewRefs = page.getViewReferences();
        if (viewRefs != null) {
            for (IViewReference ref : viewRefs) {
                if (ref == null) {
                    continue;
                }
                IWorkbenchPart part = ref.getPart(false);
                boolean active = ref == activeRef || (part != null && part == activePart);
                boolean visible = part != null && page.isPartVisible(part);
                views.add(new WorkbenchPartSummary(
                    "view",
                    ref.getId(),
                    nonEmptyOrNull(ref.getSecondaryId()),
                    safeString(ref.getTitle()),
                    active,
                    visible,
                    ref.isDirty()
                ));
            }
        }

        List<WorkbenchPartSummary> editors = new ArrayList<>();
        IEditorReference[] editorRefs = page.getEditorReferences();
        if (editorRefs != null) {
            for (IEditorReference ref : editorRefs) {
                if (ref == null) {
                    continue;
                }
                IWorkbenchPart part = ref.getPart(false);
                boolean active = ref == activeRef || (part != null && part == activePart);
                boolean visible = part != null && page.isPartVisible(part);
                editors.add(new WorkbenchPartSummary(
                    "editor",
                    ref.getId(),
                    null,
                    safeString(ref.getTitle()),
                    active,
                    visible,
                    ref.isDirty()
                ));
            }
        }

        return new WorkbenchSummary(window, page, mainShell, perspectiveId, views, editors);
    }

    private List<Widget> resolveRootWidgets(
        Display display,
        IWorkbenchWindow window,
        IWorkbenchPage page,
        Shell mainWorkbenchShell,
        String rootScope,
        boolean visibleOnly
    ) {
        String lower = rootScope.toLowerCase(Locale.ROOT);
        if ("active".equals(lower)) {
            Shell active = display.getActiveShell();
            if (active != null && !active.isDisposed() && (!visibleOnly || active.isVisible())) {
                return List.of(active);
            }
            Shell fallback = firstShell(display.getShells(), visibleOnly);
            return fallback != null ? List.of(fallback) : List.of();
        }
        if ("all".equals(lower)) {
            List<Widget> shells = new ArrayList<>();
            Shell[] allShells = display.getShells();
            if (allShells != null) {
                for (Shell shell : allShells) {
                    if (shell != null && !shell.isDisposed() && (!visibleOnly || shell.isVisible())) {
                        shells.add(shell);
                    }
                }
            }
            return shells;
        }
        if ("dialog".equals(lower)) {
            Shell active = display.getActiveShell();
            if (active != null && !active.isDisposed()
                && active != mainWorkbenchShell
                && (!visibleOnly || active.isVisible())) {
                return List.of(active);
            }
            Shell[] allShells = display.getShells();
            if (allShells != null) {
                for (Shell shell : allShells) {
                    if (shell != null && !shell.isDisposed()
                        && shell != mainWorkbenchShell
                        && shell.isVisible()) {
                        return List.of(shell);
                    }
                }
            }
            if (active != null && !active.isDisposed()) {
                return List.of(active);
            }
            return List.of();
        }
        if ("editor".equals(lower)) {
            if (page == null) {
                throw new IllegalArgumentException("No active Eclipse workbench page for editor root");
            }
            IEditorPart editor = page.getActiveEditor();
            if (editor == null) {
                throw new IllegalArgumentException("No active editor open");
            }
            Control control = resolvePartControl(editor);
            if (control == null || control.isDisposed()) {
                throw new IllegalArgumentException("Active editor control unavailable");
            }
            return List.of(control);
        }
        if (rootScope.startsWith("view:")) {
            if (page == null) {
                throw new IllegalArgumentException("No active Eclipse workbench page for view root");
            }
            String viewSpec = rootScope.substring("view:".length()).trim();
            if (viewSpec.isEmpty()) {
                throw new IllegalArgumentException("View root requires view:<viewId>");
            }
            Control viewControl = resolveViewControl(page, viewSpec);
            return List.of(viewControl);
        }
        throw new IllegalArgumentException("Unsupported UI inspection root: " + rootScope);
    }

    private static Shell firstShell(Shell[] shells, boolean visibleOnly) {
        if (shells == null) {
            return null;
        }
        for (Shell shell : shells) {
            if (shell != null && !shell.isDisposed() && (!visibleOnly || shell.isVisible())) {
                return shell;
            }
        }
        return null;
    }

    private static Control resolveViewControl(IWorkbenchPage page, String viewSpec) {
        int colonIdx = viewSpec.indexOf(':');
        String viewId = colonIdx >= 0 ? viewSpec.substring(0, colonIdx).trim() : viewSpec;
        String secondaryId = colonIdx >= 0 ? nonEmptyOrNull(viewSpec.substring(colonIdx + 1).trim()) : null;

        IViewReference[] refs = page.getViewReferences();
        IViewReference matched = null;
        if (refs != null) {
            for (IViewReference ref : refs) {
                if (ref == null || !viewId.equals(ref.getId())) {
                    continue;
                }
                if (secondaryId != null) {
                    if (secondaryId.equals(ref.getSecondaryId())) {
                        matched = ref;
                        break;
                    }
                } else {
                    if (ref.getSecondaryId() == null) {
                        matched = ref;
                        break;
                    }
                    if (matched == null) {
                        matched = ref;
                    }
                }
            }
        }
        if (matched == null) {
            throw new IllegalArgumentException("View not open: " + viewSpec);
        }
        IWorkbenchPart part = matched.getPart(false);
        if (part == null) {
            throw new IllegalArgumentException("View is not initialized yet: " + viewSpec);
        }
        Control control = resolvePartControl(part);
        if (control == null || control.isDisposed()) {
            throw new IllegalArgumentException("View control unavailable: " + viewSpec);
        }
        return control;
    }

    private static Control resolvePartControl(IWorkbenchPart part) {
        if (part == null) {
            return null;
        }
        IWorkbenchPartSite site = part.getSite();
        if (site != null) {
            MPart mpart = site.getService(MPart.class);
            if (mpart != null && mpart.getWidget() instanceof Control control && !control.isDisposed()) {
                return control;
            }
        }
        Control adapted = part.getAdapter(Control.class);
        if (adapted != null && !adapted.isDisposed()) {
            return adapted;
        }
        return null;
    }

    private UiNode walkWidget(Widget widget, String path, int depth, WalkState state) {
        if (widget == null || widget.isDisposed()) {
            return null;
        }
        if (widget instanceof Shell shell) {
            if (state.visibleOnly && !shell.isVisible()) {
                return null;
            }
        } else if (widget instanceof Control control) {
            if (state.visibleOnly && !control.isVisible()) {
                return null;
            }
        }
        if (state.visitedNodes >= state.maxNodes) {
            state.truncated = true;
            return null;
        }

        state.visitedNodes++;
        String id = registry.register(widget);
        String type = widgetTypeName(widget);
        String text = extractWidgetText(widget);
        String tooltip = extractTooltip(widget);
        String message = widget instanceof Text textWidget ? nonEmptyOrNull(textWidget.getMessage()) : null;
        String elementId = extractElementId(widget);
        boolean visible = isWidgetVisible(widget);
        boolean enabled = isWidgetEnabled(widget);
        boolean focused = widget == state.focusControl;
        Boolean selected = extractSelected(widget);
        UiBounds bounds = extractBounds(widget);
        List<String> items = extractItems(widget);

        List<UiNode> children = new ArrayList<>();
        if (depth < state.maxDepth) {
            collectChildren(widget, path, depth, state, children);
        }

        return new UiNode(
            id,
            path,
            type,
            text,
            tooltip,
            message,
            elementId,
            visible,
            enabled,
            focused,
            selected,
            bounds,
            items,
            children
        );
    }

    private void collectChildren(
        Widget widget,
        String parentPath,
        int depth,
        WalkState state,
        List<UiNode> children
    ) {
        if (widget instanceof Shell shell) {
            Menu menuBar = shell.getMenuBar();
            if (menuBar != null && !menuBar.isDisposed()) {
                UiNode menuBarNode = walkMenu(menuBar, parentPath + "/MenuBar", depth + 1, true, state);
                if (menuBarNode != null) {
                    children.add(menuBarNode);
                }
            }
        }
        if (widget instanceof ToolBar toolBar) {
            ToolItem[] toolItems = toolBar.getItems();
            if (toolItems != null) {
                for (int i = 0; i < toolItems.length; i++) {
                    if (state.truncated) {
                        return;
                    }
                    ToolItem item = toolItems[i];
                    if (item == null || item.isDisposed() || (item.getStyle() & SWT.SEPARATOR) != 0) {
                        continue;
                    }
                    String childPath = parentPath + "/ToolItem[" + i + "]";
                    UiNode childNode = walkWidget(item, childPath, depth + 1, state);
                    if (childNode != null) {
                        children.add(childNode);
                    }
                }
            }
        }
        if (widget instanceof TabFolder tabFolder) {
            TabItem[] tabItems = tabFolder.getItems();
            if (tabItems != null) {
                for (int i = 0; i < tabItems.length; i++) {
                    if (state.truncated) {
                        return;
                    }
                    TabItem item = tabItems[i];
                    if (item == null || item.isDisposed()) {
                        continue;
                    }
                    String childPath = parentPath + "/TabItem[" + i + "]";
                    UiNode childNode = walkWidget(item, childPath, depth + 1, state);
                    if (childNode != null) {
                        children.add(childNode);
                    }
                }
            }
        } else if (widget instanceof CTabFolder cTabFolder) {
            CTabItem[] cTabItems = cTabFolder.getItems();
            if (cTabItems != null) {
                for (int i = 0; i < cTabItems.length; i++) {
                    if (state.truncated) {
                        return;
                    }
                    CTabItem item = cTabItems[i];
                    if (item == null || item.isDisposed()) {
                        continue;
                    }
                    String childPath = parentPath + "/CTabItem[" + i + "]";
                    UiNode childNode = walkWidget(item, childPath, depth + 1, state);
                    if (childNode != null) {
                        children.add(childNode);
                    }
                }
            }
        }
        if (widget instanceof Composite composite) {
            Control[] childControls = composite.getChildren();
            if (childControls != null) {
                Map<String, Integer> typeCounts = new LinkedHashMap<>();
                for (Control child : childControls) {
                    if (state.truncated) {
                        return;
                    }
                    if (child == null || child.isDisposed()) {
                        continue;
                    }
                    String childType = widgetTypeName(child);
                    int typeIndex = typeCounts.getOrDefault(childType, 0);
                    typeCounts.put(childType, typeIndex + 1);
                    String childPath = parentPath + "/" + childType + "[" + typeIndex + "]";
                    UiNode childNode = walkWidget(child, childPath, depth + 1, state);
                    if (childNode != null) {
                        children.add(childNode);
                    }
                }
            }
        }
    }

    private UiNode walkMenu(
        Menu menu,
        String path,
        int depth,
        boolean ignoreMenuVisibility,
        WalkState state
    ) {
        if (menu == null || menu.isDisposed()) {
            return null;
        }
        if (state.visibleOnly && !ignoreMenuVisibility && !menu.isVisible()) {
            return null;
        }
        if (state.visitedNodes >= state.maxNodes) {
            state.truncated = true;
            return null;
        }

        state.visitedNodes++;
        String id = registry.register(menu);
        String elementId = extractElementId(menu);
        List<UiNode> children = new ArrayList<>();
        if (depth < state.maxDepth) {
            MenuItem[] items = menu.getItems();
            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    if (state.truncated) {
                        break;
                    }
                    MenuItem item = items[i];
                    if (item == null || item.isDisposed() || (item.getStyle() & SWT.SEPARATOR) != 0) {
                        continue;
                    }
                    UiNode itemNode = walkMenuItem(
                        item,
                        path + "/MenuItem[" + i + "]",
                        depth + 1,
                        ignoreMenuVisibility,
                        state
                    );
                    if (itemNode != null) {
                        children.add(itemNode);
                    }
                }
            }
        }

        return new UiNode(
            id,
            path,
            widgetTypeName(menu),
            null,
            null,
            null,
            elementId,
            menu.isVisible(),
            menu.isEnabled(),
            false,
            null,
            null,
            List.of(),
            children
        );
    }

    private UiNode walkMenuItem(
        MenuItem item,
        String path,
        int depth,
        boolean ignoreMenuVisibility,
        WalkState state
    ) {
        if (item == null || item.isDisposed()) {
            return null;
        }
        if (state.visitedNodes >= state.maxNodes) {
            state.truncated = true;
            return null;
        }

        state.visitedNodes++;
        String id = registry.register(item);
        String text = extractWidgetText(item);
        String tooltip = nonEmptyOrNull(item.getToolTipText());
        String elementId = extractElementId(item);
        Boolean selected = extractSelected(item);

        List<UiNode> children = new ArrayList<>();
        if (depth < state.maxDepth) {
            Menu cascade = item.getMenu();
            if (cascade != null && !cascade.isDisposed()) {
                UiNode cascadeNode = walkMenu(cascade, path + "/Menu", depth + 1, ignoreMenuVisibility, state);
                if (cascadeNode != null) {
                    children.add(cascadeNode);
                }
            }
        }

        return new UiNode(
            id,
            path,
            widgetTypeName(item),
            text,
            tooltip,
            null,
            elementId,
            true,
            item.isEnabled(),
            false,
            selected,
            null,
            List.of(),
            children
        );
    }

    private static String extractTooltip(Widget widget) {
        return switch (widget) {
            case Control control -> nonEmptyOrNull(control.getToolTipText());
            case ToolItem item -> nonEmptyOrNull(item.getToolTipText());
            case MenuItem item -> nonEmptyOrNull(item.getToolTipText());
            case TabItem item -> nonEmptyOrNull(item.getToolTipText());
            case CTabItem item -> nonEmptyOrNull(item.getToolTipText());
            default -> null;
        };
    }

    private static String extractElementId(Widget widget) {
        Object modelObj = widget.getData("modelElement");
        if (modelObj instanceof MUIElement uiElement) {
            return nonEmptyOrNull(uiElement.getElementId());
        }
        return null;
    }

    private static boolean isWidgetVisible(Widget widget) {
        if (widget instanceof Shell shell) {
            return shell.isVisible();
        }
        if (widget instanceof Control control) {
            return control.isVisible();
        }
        if (widget instanceof Menu menu) {
            return menu.isVisible();
        }
        return true;
    }

    private static boolean isWidgetEnabled(Widget widget) {
        if (widget instanceof Control control) {
            return control.isEnabled();
        }
        if (widget instanceof ToolItem item) {
            return item.isEnabled();
        }
        if (widget instanceof MenuItem item) {
            return item.isEnabled();
        }
        if (widget instanceof Menu menu) {
            return menu.isEnabled();
        }
        return true;
    }

    private static Boolean extractSelected(Widget widget) {
        if (widget instanceof Button button) {
            if ((button.getStyle() & (SWT.CHECK | SWT.RADIO | SWT.TOGGLE)) != 0) {
                return Boolean.valueOf(button.getSelection());
            }
            return null;
        }
        if (widget instanceof ToolItem toolItem) {
            if ((toolItem.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) {
                return Boolean.valueOf(toolItem.getSelection());
            }
            return null;
        }
        if (widget instanceof MenuItem menuItem) {
            if ((menuItem.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) {
                return Boolean.valueOf(menuItem.getSelection());
            }
            return null;
        }
        if (widget instanceof TabItem tabItem) {
            TabFolder parent = tabItem.getParent();
            if (parent != null && !parent.isDisposed()) {
                return Boolean.valueOf(parent.indexOf(tabItem) == parent.getSelectionIndex());
            }
            return null;
        }
        if (widget instanceof CTabItem cTabItem) {
            CTabFolder parent = cTabItem.getParent();
            if (parent != null && !parent.isDisposed()) {
                return Boolean.valueOf(parent.getSelection() == cTabItem);
            }
            return null;
        }
        return null;
    }

    private static UiBounds extractBounds(Widget widget) {
        return switch (widget) {
            case Shell shell -> {
                Rectangle rect = shell.getBounds();
                yield new UiBounds(rect.x, rect.y, rect.width, rect.height);
            }
            case Control control -> toDisplayBounds(control.getParent(), control.getBounds());
            case ToolItem toolItem -> toDisplayBounds(toolItem.getParent(), toolItem.getBounds());
            case TabItem tabItem -> toDisplayBounds(tabItem.getParent(), tabItem.getBounds());
            case CTabItem cTabItem -> toDisplayBounds(cTabItem.getParent(), cTabItem.getBounds());
            default -> null;
        };
    }

    private static UiBounds toDisplayBounds(Control parent, Rectangle rect) {
        if (parent != null && !parent.isDisposed()) {
            Point displayPos = parent.toDisplay(rect.x, rect.y);
            return new UiBounds(displayPos.x, displayPos.y, rect.width, rect.height);
        }
        return new UiBounds(rect.x, rect.y, rect.width, rect.height);
    }

    private static List<String> extractItems(Widget widget) {
        if (widget instanceof Combo combo) {
            return copyStrings(combo.getItems());
        }
        if (widget instanceof org.eclipse.swt.widgets.List list) {
            return copyStrings(list.getItems());
        }
        if (widget instanceof Tree tree) {
            return collectTreeItems(tree, MAX_COLLECTION_ITEMS);
        }
        if (widget instanceof Table table) {
            return collectTableItems(table, MAX_COLLECTION_ITEMS);
        }
        if (widget instanceof TabFolder tabFolder) {
            TabItem[] items = tabFolder.getItems();
            if (items == null || items.length == 0) {
                return List.of();
            }
            List<String> result = new ArrayList<>(items.length);
            for (TabItem item : items) {
                if (item != null && !item.isDisposed()) {
                    result.add(safeString(item.getText()));
                }
            }
            return result;
        }
        if (widget instanceof CTabFolder cTabFolder) {
            CTabItem[] items = cTabFolder.getItems();
            if (items == null || items.length == 0) {
                return List.of();
            }
            List<String> result = new ArrayList<>(items.length);
            for (CTabItem item : items) {
                if (item != null && !item.isDisposed()) {
                    result.add(safeString(item.getText()));
                }
            }
            return result;
        }
        return List.of();
    }

    private static List<String> copyStrings(String[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(raw.length);
        for (String item : raw) {
            result.add(safeString(item));
        }
        return result;
    }

    private static List<String> collectTreeItems(Tree tree, int limit) {
        List<String> items = new ArrayList<>();
        int columnCount = tree.getColumnCount();
        TreeItem[] roots = tree.getItems();
        if (roots != null) {
            for (TreeItem item : roots) {
                if (items.size() >= limit) {
                    break;
                }
                appendVisibleTreeItems(item, columnCount, limit, items);
            }
        }
        return items;
    }

    private static void appendVisibleTreeItems(
        TreeItem item,
        int columnCount,
        int limit,
        List<String> out
    ) {
        if (item == null || item.isDisposed() || out.size() >= limit) {
            return;
        }
        out.add(formatTreeItemText(item, columnCount));
        if (!item.getExpanded() || out.size() >= limit) {
            return;
        }
        TreeItem[] children = item.getItems();
        if (children != null) {
            for (TreeItem child : children) {
                if (out.size() >= limit) {
                    break;
                }
                appendVisibleTreeItems(child, columnCount, limit, out);
            }
        }
    }

    static String formatTreeItemText(TreeItem item, int columnCount) {
        return joinColumnTexts(columnCount, item.getText(), item::getText);
    }

    private static List<String> collectTableItems(Table table, int limit) {
        TableItem[] rows = table.getItems();
        if (rows == null || rows.length == 0) {
            return List.of();
        }
        int columnCount = table.getColumnCount();
        int count = Math.min(limit, rows.length);
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TableItem row = rows[i];
            if (row != null && !row.isDisposed()) {
                items.add(formatTableItemText(row, columnCount));
            }
        }
        return items;
    }

    static String formatTableItemText(TableItem item, int columnCount) {
        return joinColumnTexts(columnCount, item.getText(), item::getText);
    }

    private static String joinColumnTexts(
        int columnCount,
        String primaryText,
        IntFunction<String> columnReader
    ) {
        if (columnCount <= 1) {
            return safeString(primaryText);
        }
        List<String> cols = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            cols.add(safeString(columnReader.apply(c)));
        }
        return String.join(" | ", cols);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String nonEmptyOrNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private record WorkbenchSummary(
        IWorkbenchWindow window,
        IWorkbenchPage page,
        Shell mainShell,
        String perspectiveId,
        List<WorkbenchPartSummary> views,
        List<WorkbenchPartSummary> editors
    ) {}

    private static final class WalkState {
        final Control focusControl;
        final int maxDepth;
        final boolean visibleOnly;
        final int maxNodes;
        int visitedNodes;
        boolean truncated;

        WalkState(Control focusControl, int maxDepth, boolean visibleOnly, int maxNodes) {
            this.focusControl = focusControl;
            this.maxDepth = maxDepth;
            this.visibleOnly = visibleOnly;
            this.maxNodes = maxNodes;
        }
    }
}
