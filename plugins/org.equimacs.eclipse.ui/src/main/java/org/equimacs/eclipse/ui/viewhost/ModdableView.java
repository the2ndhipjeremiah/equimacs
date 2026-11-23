package org.equimacs.eclipse.ui.viewhost;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.part.ViewPart;

/**
 * Multi-instance SWT {@link ViewPart} rendering declarative {@link ViewSpec} structures.
 */
public class ModdableView extends ViewPart {

    public static final String VIEW_ID = "org.equimacs.eclipse.ui.moddableView";

    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, ModdableView> ACTIVE_VIEWS = new ConcurrentHashMap<>();

    private final List<Font> ownedFonts = new ArrayList<>();
    private final List<HtmlBrowserRenderer.HtmlHandle> htmlHandles = new ArrayList<>();
    private HtmlBrowserRenderer.HtmlHandle topLevelHtmlHandle;
    private Composite rootComposite;
    private Control focusControl;
    private ViewHostStore.HostedView lastRendered;

    /**
     * Returns the open {@link ModdableView} instance for {@code viewId}, or {@code null}.
     */
    public static ModdableView findActive(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return null;
        }
        ModdableView view = ACTIVE_VIEWS.get(viewId.trim());
        if (view == null || view.rootComposite == null || view.rootComposite.isDisposed()) {
            return null;
        }
        return view;
    }

    /**
     * Returns {@code true} if a {@link ModdableView} for {@code viewId} is currently open.
     */
    public static boolean isOpen(String viewId) {
        return findActive(viewId) != null;
    }

    @Override
    public void createPartControl(Composite parent) {
        rootComposite = new Composite(parent, SWT.NONE);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 4;
        rootLayout.marginHeight = 4;
        rootLayout.verticalSpacing = 4;
        rootComposite.setLayout(rootLayout);

        String viewId = secondaryViewId();
        if (viewId != null && !viewId.isBlank()) {
            ACTIVE_VIEWS.put(viewId, this);
        }

        ViewHostStore.HostedView existing = ViewHostStore.DEFAULT.get(viewId);
        if (existing != null) {
            render(existing);
        } else {
            Label placeholder = new Label(rootComposite, SWT.WRAP);
            String labelText = (viewId != null && !viewId.isBlank())
                ? "Equimacs Moddable View: " + viewId
                : "Equimacs Moddable View";
            placeholder.setText(labelText);
            placeholder.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            focusControl = placeholder;
        }
    }

    /**
     * Renders (or re-renders) {@code hosted} inside this view's root composite.
     */
    public void render(ViewHostStore.HostedView hosted) {
        if (hosted == null || rootComposite == null || rootComposite.isDisposed()) {
            return;
        }
        if (hosted.equals(lastRendered)) {
            return;
        }
        if (lastRendered != null
                && lastRendered.spec() instanceof ViewSpec.HtmlSpec prevHtml
                && hosted.spec() instanceof ViewSpec.HtmlSpec nextHtml
                && prevHtml.html().equals(nextHtml.html())
                && prevHtml.url().equals(nextHtml.url())
                && prevHtml.toolbar().equals(nextHtml.toolbar())
                && topLevelHtmlHandle != null
                && topLevelHtmlHandle.isAlive()) {
            setPartName(hosted.title());
            topLevelHtmlHandle.pushState(GSON.toJson(nextHtml.state()));
            lastRendered = hosted;
            return;
        }
        for (Control child : rootComposite.getChildren()) {
            child.dispose();
        }
        disposeOwnedResources();
        focusControl = null;

        setPartName(hosted.title());
        renderSpec(rootComposite, hosted.viewId(), hosted.spec(), true);
        lastRendered = hosted;
        rootComposite.layout(true, true);
    }

    /**
     * Pushes a serialized bridge event JSON string into any alive HTML views hosted by this part.
     */
    public void pushEvent(String eventJson) {
        Composite root = rootComposite;
        if (root == null || root.isDisposed()) {
            return;
        }
        Display display = root.getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (root.isDisposed()) {
                return;
            }
            for (HtmlBrowserRenderer.HtmlHandle handle : htmlHandles) {
                if (handle != null && handle.isAlive()) {
                    handle.pushEvent(eventJson);
                }
            }
        });
    }

    @Override
    public void setFocus() {
        if (focusControl != null && !focusControl.isDisposed()) {
            focusControl.setFocus();
        } else if (rootComposite != null && !rootComposite.isDisposed()) {
            rootComposite.setFocus();
        }
    }

    @Override
    public void dispose() {
        String viewId = secondaryViewId();
        if (viewId != null && !viewId.isBlank()) {
            ACTIVE_VIEWS.remove(viewId, this);
            ModdableViewCommandHandler.onViewDisposed(viewId);
        }
        disposeOwnedResources();
        lastRendered = null;
        super.dispose();
    }

    private String secondaryViewId() {
        IViewSite site = getViewSite();
        return site != null ? site.getSecondaryId() : null;
    }

    private void disposeOwnedResources() {
        for (HtmlBrowserRenderer.HtmlHandle handle : htmlHandles) {
            if (handle != null) {
                handle.dispose();
            }
        }
        htmlHandles.clear();
        topLevelHtmlHandle = null;
        for (Font font : ownedFonts) {
            if (font != null && !font.isDisposed()) {
                font.dispose();
            }
        }
        ownedFonts.clear();
    }

    private void renderSpec(Composite container, String viewId, ViewSpec spec, boolean isTopLevel) {
        renderToolbar(container, viewId, spec.toolbar());
        switch (spec) {
            case ViewSpec.TableSpec tableSpec -> renderTable(container, viewId, tableSpec);
            case ViewSpec.TreeSpec treeSpec -> renderTree(container, viewId, treeSpec);
            case ViewSpec.MarkdownSpec markdownSpec -> renderMarkdown(container, viewId, markdownSpec);
            case ViewSpec.KeyValueSpec kvSpec -> renderKeyValue(container, viewId, kvSpec);
            case ViewSpec.TabsSpec tabsSpec -> renderTabs(container, viewId, tabsSpec);
            case ViewSpec.HtmlSpec htmlSpec -> renderHtml(container, viewId, htmlSpec, isTopLevel);
            case ViewSpec.SplitSpec splitSpec -> renderSplit(container, viewId, splitSpec);
            case ViewSpec.FormSpec formSpec -> renderForm(container, viewId, formSpec);
            case ViewSpec.GraphSpec graphSpec -> {
                Control graphCanvas = GraphCanvasRenderer.render(container, viewId, graphSpec);
                registerFocusControl(graphCanvas);
            }
        }
    }

    private static void renderToolbar(
            Composite container,
            String viewId,
            List<ViewSpec.ActionItem> toolbar) {
        if (toolbar == null || toolbar.isEmpty()) {
            return;
        }
        Composite bar = new Composite(container, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.spacing = 4;
        rowLayout.wrap = true;
        bar.setLayout(rowLayout);

        for (ViewSpec.ActionItem item : toolbar) {
            Button btn = new Button(bar, SWT.PUSH);
            btn.setText(item.label());
            if (item.tooltip() != null && !item.tooltip().isBlank()) {
                btn.setToolTipText(item.tooltip());
            }
            btn.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    ViewActionDispatcher.dispatch(viewId, null, item.action());
                }
            });
        }
    }

    private void renderTable(Composite container, String viewId, ViewSpec.TableSpec spec) {
        Text filterText = new Text(container, SWT.SEARCH | SWT.ICON_CANCEL | SWT.ICON_SEARCH);
        filterText.setMessage("Filter rows...");
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        TableViewer viewer = new TableViewer(
            container,
            SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(table);

        List<String> columnNames = resolveTableColumns(spec);
        int[] sortColumn = new int[] { -1 };
        boolean[] sortAscending = new boolean[] { true };

        viewer.setComparator(new ViewerComparator() {
            @Override
            public int compare(Viewer v, Object e1, Object e2) {
                int col = sortColumn[0];
                if (col < 0) {
                    return 0;
                }
                ViewSpec.TableRow r1 = (ViewSpec.TableRow) e1;
                ViewSpec.TableRow r2 = (ViewSpec.TableRow) e2;
                String s1 = cellAt(r1, col);
                String s2 = cellAt(r2, col);
                int cmp = compareSmart(s1, s2);
                return sortAscending[0] ? cmp : -cmp;
            }
        });

        for (int i = 0; i < columnNames.size(); i++) {
            int colIdx = i;
            TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
            TableColumn tc = col.getColumn();
            tc.setText(columnNames.get(colIdx));
            tc.setResizable(true);
            tc.setMoveable(true);
            col.setLabelProvider(new ColumnLabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof ViewSpec.TableRow row) {
                        return cellAt(row, colIdx);
                    }
                    return "";
                }
            });
            tc.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (sortColumn[0] == colIdx) {
                        sortAscending[0] = !sortAscending[0];
                    } else {
                        sortColumn[0] = colIdx;
                        sortAscending[0] = true;
                    }
                    table.setSortColumn(tc);
                    table.setSortDirection(sortAscending[0] ? SWT.UP : SWT.DOWN);
                    viewer.refresh();
                }
            });
        }

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.addFilter(new ViewerFilter() {
            @Override
            public boolean select(Viewer v, Object parentElement, Object element) {
                String query = filterText.getText().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty()) {
                    return true;
                }
                if (!(element instanceof ViewSpec.TableRow row)) {
                    return false;
                }
                if (row.id() != null && row.id().toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
                for (String cell : row.cells()) {
                    if (cell != null && cell.toLowerCase(Locale.ROOT).contains(query)) {
                        return true;
                    }
                }
                return false;
            }
        });

        filterText.addModifyListener(e -> viewer.refresh());
        viewer.setInput(spec.rows());
        packTableColumns(table);

        viewer.addSelectionChangedListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.TableRow row) {
                if (row.onSelect() != null) {
                    ViewActionDispatcher.dispatch(viewId, row.id(), row.onSelect());
                }
            }
        });

        viewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.TableRow row) {
                if (row.onActivate() != null) {
                    ViewActionDispatcher.dispatch(viewId, row.id(), row.onActivate());
                }
            }
        });

        installTableContextMenu(viewer, table, viewId);
    }

    private void renderTree(Composite container, String viewId, ViewSpec.TreeSpec spec) {
        Composite bar = new Composite(container, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout barLayout = new GridLayout(3, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        bar.setLayout(barLayout);

        Text filterText = new Text(bar, SWT.SEARCH | SWT.ICON_CANCEL | SWT.ICON_SEARCH);
        filterText.setMessage("Filter tree...");
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        TreeViewer viewer = new TreeViewer(
            container,
            SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        Tree tree = viewer.getTree();
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(tree);

        Button expandAllBtn = new Button(bar, SWT.PUSH);
        expandAllBtn.setText("Expand All");
        expandAllBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                viewer.expandAll();
            }
        });

        Button collapseAllBtn = new Button(bar, SWT.PUSH);
        collapseAllBtn.setText("Collapse All");
        collapseAllBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                viewer.collapseAll();
            }
        });

        List<String> cols = spec.columns();
        if (cols.size() > 1) {
            tree.setHeaderVisible(true);
            tree.setLinesVisible(true);

            TreeViewerColumn primaryCol = new TreeViewerColumn(viewer, SWT.NONE);
            primaryCol.getColumn().setText(cols.get(0));
            primaryCol.getColumn().setResizable(true);
            primaryCol.setLabelProvider(new ColumnLabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof ViewSpec.TreeNode node) {
                        return formatNodeLabel(node);
                    }
                    return "";
                }
            });

            for (int i = 1; i < cols.size(); i++) {
                int valueIdx = i - 1;
                TreeViewerColumn col = new TreeViewerColumn(viewer, SWT.NONE);
                col.getColumn().setText(cols.get(i));
                col.getColumn().setResizable(true);
                col.setLabelProvider(new ColumnLabelProvider() {
                    @Override
                    public String getText(Object element) {
                        if (element instanceof ViewSpec.TreeNode node && valueIdx < node.values().size()) {
                            return node.values().get(valueIdx);
                        }
                        return "";
                    }
                });
            }
        } else {
            if (cols.size() == 1) {
                tree.setHeaderVisible(true);
                TreeColumn tc = new TreeColumn(tree, SWT.NONE);
                tc.setText(cols.get(0));
                tc.setWidth(360);
            }
            viewer.setLabelProvider(new ColumnLabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof ViewSpec.TreeNode node) {
                        String base = formatNodeLabel(node);
                        if (node.values().isEmpty()) {
                            return base;
                        }
                        return base + " \u2014 " + String.join(", ", node.values());
                    }
                    return String.valueOf(element);
                }
            });
        }

        viewer.setContentProvider(new TreeNodeContentProvider());
        viewer.addFilter(new ViewerFilter() {
            @Override
            public boolean select(Viewer v, Object parentElement, Object element) {
                String query = filterText.getText().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty()) {
                    return true;
                }
                if (element instanceof ViewSpec.TreeNode node) {
                    return treeNodeMatches(node, query);
                }
                return false;
            }
        });

        filterText.addModifyListener(e -> {
            viewer.refresh();
            if (!filterText.getText().isBlank()) {
                viewer.expandAll();
            }
        });

        viewer.setInput(spec.roots());
        if (spec.expandAll()) {
            viewer.expandAll();
        }
        if (cols.size() > 1) {
            packTreeColumns(tree);
        }

        viewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.TreeNode node) {
                if (node.onActivate() != null) {
                    ViewActionDispatcher.dispatch(viewId, node.id(), node.onActivate());
                } else if (!node.children().isEmpty()) {
                    viewer.setExpandedState(node, !viewer.getExpandedState(node));
                }
            }
        });

        installTreeContextMenu(viewer, tree, viewId);
    }

    private void renderMarkdown(Composite container, String viewId, ViewSpec.MarkdownSpec spec) {
        MarkdownStyler.StyledDocument doc = MarkdownStyler.parse(spec.content());
        StyledText styled = new StyledText(
            container,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        styled.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(styled);

        styled.setText(doc.plainText());
        applyMarkdownStyles(styled, doc);

        Display display = styled.getDisplay();
        styled.addMouseMoveListener(e -> {
            MarkdownStyler.LinkSpan hit = findLinkAtPoint(styled, doc, e.x, e.y);
            styled.setCursor(hit != null ? display.getSystemCursor(SWT.CURSOR_HAND) : null);
        });

        styled.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) {
                if (e.button != 1 || styled.getSelectionCount() > 0) {
                    return;
                }
                MarkdownStyler.LinkSpan hit = findLinkAtPoint(styled, doc, e.x, e.y);
                if (hit != null && hit.action() != null) {
                    ViewActionDispatcher.dispatch(viewId, hit.label(), hit.action());
                }
            }
        });
    }

    private void renderKeyValue(Composite container, String viewId, ViewSpec.KeyValueSpec spec) {
        TreeViewer viewer = new TreeViewer(
            container,
            SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        Tree tree = viewer.getTree();
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(tree);

        Font boldDefaultFont = JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);

        TreeViewerColumn keyCol = new TreeViewerColumn(viewer, SWT.NONE);
        keyCol.getColumn().setText("Key");
        keyCol.getColumn().setResizable(true);
        keyCol.getColumn().setWidth(220);
        keyCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return switch (element) {
                    case ViewSpec.KvSection s ->
                        (s.heading() == null || s.heading().isBlank()) ? "(General)" : s.heading();
                    case ViewSpec.KvEntry e -> e.key() == null ? "" : e.key();
                    default -> String.valueOf(element);
                };
            }

            @Override
            public Font getFont(Object element) {
                return element instanceof ViewSpec.KvSection ? boldDefaultFont : null;
            }
        });

        TreeViewerColumn valueCol = new TreeViewerColumn(viewer, SWT.NONE);
        valueCol.getColumn().setText("Value");
        valueCol.getColumn().setResizable(true);
        valueCol.getColumn().setWidth(360);
        valueCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof ViewSpec.KvEntry e) {
                    return e.value() == null ? "" : e.value();
                }
                return "";
            }
        });

        viewer.setContentProvider(new KvContentProvider());
        viewer.setInput(spec.sections());
        viewer.expandAll();

        viewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            Object element = sel.getFirstElement();
            if (element instanceof ViewSpec.KvEntry entry && entry.onActivate() != null) {
                ViewActionDispatcher.dispatch(viewId, entry.key(), entry.onActivate());
            } else if (element instanceof ViewSpec.KvSection section) {
                viewer.setExpandedState(section, !viewer.getExpandedState(section));
            }
        });

        installKeyValueContextMenu(viewer, tree, viewId);
    }

    private void renderTabs(Composite container, String viewId, ViewSpec.TabsSpec spec) {
        CTabFolder folder = new CTabFolder(container, SWT.TOP | SWT.BORDER);
        folder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(folder);

        for (ViewSpec.TabItemSpec tab : spec.tabs()) {
            CTabItem item = new CTabItem(folder, SWT.NONE);
            item.setText(tab.label());
            Composite tabBody = new Composite(folder, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 4;
            layout.marginHeight = 4;
            layout.verticalSpacing = 4;
            tabBody.setLayout(layout);
            renderSpec(tabBody, viewId, tab.content(), false);
            item.setControl(tabBody);
        }

        if (folder.getItemCount() > 0) {
            folder.setSelection(0);
        }
    }

    private void renderHtml(
            Composite container,
            String viewId,
            ViewSpec.HtmlSpec spec,
            boolean isTopLevel) {
        HtmlBrowserRenderer.HtmlHandle handle = HtmlBrowserRenderer.render(container, viewId, spec, GSON);
        htmlHandles.add(handle);
        if (isTopLevel) {
            topLevelHtmlHandle = handle;
        }
        registerFocusControl(handle.control());
    }

    private void renderSplit(Composite container, String viewId, ViewSpec.SplitSpec spec) {
        int style = "vertical".equals(spec.orientation()) ? SWT.VERTICAL : SWT.HORIZONTAL;
        SashForm sash = new SashForm(container, style | SWT.SMOOTH);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        registerFocusControl(sash);

        for (ViewSpec pane : spec.panes()) {
            Composite paneBody = new Composite(sash, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            layout.verticalSpacing = 4;
            paneBody.setLayout(layout);
            renderSpec(paneBody, viewId, pane, false);
        }

        if (!spec.weights().isEmpty() && spec.weights().size() == spec.panes().size()) {
            int[] weights = new int[spec.weights().size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = spec.weights().get(i);
            }
            sash.setWeights(weights);
        }
    }

    private void renderForm(Composite container, String viewId, ViewSpec.FormSpec spec) {
        ScrolledComposite scroller = new ScrolledComposite(container, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);

        Composite formBody = new Composite(scroller, SWT.NONE);
        GridLayout bodyLayout = new GridLayout(2, false);
        bodyLayout.marginWidth = 10;
        bodyLayout.marginHeight = 10;
        bodyLayout.horizontalSpacing = 10;
        bodyLayout.verticalSpacing = 8;
        formBody.setLayout(bodyLayout);
        scroller.setContent(formBody);

        if (!spec.description().isBlank()) {
            Label descLabel = new Label(formBody, SWT.WRAP);
            descLabel.setText(spec.description());
            GridData descData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            descData.widthHint = 340;
            descLabel.setLayoutData(descData);
        }

        Map<String, Supplier<String>> valueExtractors = new LinkedHashMap<>();
        for (ViewSpec.FormField field : spec.fields()) {
            String fieldLabel = formFieldLabel(field);
            if ("checkbox".equals(field.fieldType())) {
                Button check = new Button(formBody, SWT.CHECK);
                check.setText(fieldLabel);
                check.setSelection("true".equalsIgnoreCase(field.defaultValue().trim()));
                check.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
                registerFocusControl(check);
                valueExtractors.put(field.name(), () -> String.valueOf(check.getSelection()));
                continue;
            }

            Label lbl = new Label(formBody, SWT.NONE);
            lbl.setText(fieldLabel);
            lbl.setLayoutData(new GridData(
                SWT.LEFT,
                "textarea".equals(field.fieldType()) ? SWT.TOP : SWT.CENTER,
                false,
                false));

            if ("textarea".equals(field.fieldType())) {
                Text area = new Text(formBody, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
                area.setText(field.defaultValue());
                if (!field.placeholder().isBlank()) {
                    area.setMessage(field.placeholder());
                }
                GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
                gd.widthHint = 260;
                gd.heightHint = 84;
                area.setLayoutData(gd);
                registerFocusControl(area);
                valueExtractors.put(field.name(), area::getText);
            } else if ("select".equals(field.fieldType())) {
                Combo combo = new Combo(formBody, SWT.DROP_DOWN | SWT.READ_ONLY);
                combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                for (String option : field.options()) {
                    combo.add(option);
                }
                if (!field.defaultValue().isBlank()) {
                    int idx = field.options().indexOf(field.defaultValue());
                    if (idx >= 0) {
                        combo.select(idx);
                    }
                }
                registerFocusControl(combo);
                valueExtractors.put(field.name(), combo::getText);
            } else {
                Text text = new Text(formBody, SWT.SINGLE | SWT.BORDER);
                text.setText(field.defaultValue());
                if (!field.placeholder().isBlank()) {
                    text.setMessage(field.placeholder());
                }
                GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
                gd.widthHint = 260;
                text.setLayoutData(gd);
                registerFocusControl(text);
                valueExtractors.put(field.name(), text::getText);
            }
        }

        Label errorLabel = new Label(formBody, SWT.WRAP);
        errorLabel.setForeground(formBody.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridData errorData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        errorData.exclude = true;
        errorLabel.setLayoutData(errorData);
        errorLabel.setVisible(false);

        Composite btnBar = new Composite(formBody, SWT.NONE);
        btnBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
        RowLayout btnLayout = new RowLayout(SWT.HORIZONTAL);
        btnLayout.marginWidth = 0;
        btnLayout.marginHeight = 0;
        btnBar.setLayout(btnLayout);

        Button submitBtn = new Button(btnBar, SWT.PUSH);
        submitBtn.setText(spec.submitLabel());
        registerFocusControl(submitBtn);

        Runnable updateScrollBounds = () -> {
            if (!scroller.isDisposed() && !formBody.isDisposed()) {
                int widthHint = scroller.getClientArea().width;
                scroller.setMinSize(formBody.computeSize(widthHint > 0 ? widthHint : SWT.DEFAULT, SWT.DEFAULT));
            }
        };

        submitBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, Supplier<String>> entry : valueExtractors.entrySet()) {
                    values.put(entry.getKey(), entry.getValue().get());
                }
                FormSpecSupport.ValidationResult result = FormSpecSupport.validate(spec, values);
                if (!result.valid()) {
                    errorLabel.setText(String.join("; ", result.errors()));
                    errorData.exclude = false;
                    errorLabel.setVisible(true);
                    formBody.layout(true, true);
                    updateScrollBounds.run();
                    return;
                }
                errorLabel.setText("");
                errorData.exclude = true;
                errorLabel.setVisible(false);
                formBody.layout(true, true);
                updateScrollBounds.run();

                ViewAction merged = FormSpecSupport.mergeIntoAction(spec.onSubmit(), values);
                ViewActionDispatcher.dispatch(viewId, null, merged);
            }
        });

        scroller.addControlListener(ControlListener.controlResizedAdapter(e -> updateScrollBounds.run()));
        updateScrollBounds.run();
    }

    private static String formFieldLabel(ViewSpec.FormField field) {
        String base = (field.label() == null || field.label().isBlank()) ? field.name() : field.label();
        return field.required() ? base + " *" : base;
    }

    private void applyMarkdownStyles(StyledText styled, MarkdownStyler.StyledDocument doc) {
        String text = doc.plainText();
        List<MarkdownStyler.TextSpan> spans = doc.spans();
        if (text.isEmpty() || spans.isEmpty()) {
            return;
        }

        int len = text.length();
        TreeSet<Integer> boundaries = new TreeSet<>();
        for (MarkdownStyler.TextSpan span : spans) {
            if (span.length() <= 0 || span.start() >= len || span.start() + span.length() <= 0) {
                continue;
            }
            int s = Math.max(0, span.start());
            int e = Math.min(len, span.start() + span.length());
            if (e > s) {
                boundaries.add(s);
                boundaries.add(e);
            }
        }
        if (boundaries.size() < 2) {
            return;
        }

        Display display = styled.getDisplay();
        FontData[] baseData = styled.getFont().getFontData();
        Font h1Font = createSizedBoldFont(display, baseData, 4);
        Font h2Font = createSizedBoldFont(display, baseData, 2);
        Font h3Font = createSizedBoldFont(display, baseData, 1);
        Font monoFont = JFaceResources.getTextFont();

        Integer[] pts = boundaries.toArray(new Integer[0]);
        List<StyleRange> ranges = new ArrayList<>();
        for (int i = 0; i < pts.length - 1; i++) {
            int segStart = pts[i];
            int segEnd = pts[i + 1];
            if (segEnd <= segStart) {
                continue;
            }

            boolean bold = false;
            boolean italic = false;
            boolean link = false;
            boolean inlineCode = false;
            boolean codeBlock = false;
            MarkdownStyler.SpanKind heading = null;

            for (MarkdownStyler.TextSpan span : spans) {
                int spanStart = Math.max(0, span.start());
                int spanEnd = Math.min(len, span.start() + span.length());
                if (spanStart <= segStart && spanEnd >= segEnd) {
                    switch (span.kind()) {
                        case HEADING_1 -> heading = MarkdownStyler.SpanKind.HEADING_1;
                        case HEADING_2 -> {
                            if (heading != MarkdownStyler.SpanKind.HEADING_1) {
                                heading = MarkdownStyler.SpanKind.HEADING_2;
                            }
                        }
                        case HEADING_3 -> {
                            if (heading == null) {
                                heading = MarkdownStyler.SpanKind.HEADING_3;
                            }
                        }
                        case BOLD -> bold = true;
                        case ITALIC -> italic = true;
                        case INLINE_CODE -> inlineCode = true;
                        case CODE_BLOCK -> codeBlock = true;
                        case LINK -> link = true;
                    }
                }
            }

            if (!bold && !italic && !link && !inlineCode && !codeBlock && heading == null) {
                continue;
            }

            StyleRange range = new StyleRange();
            range.start = segStart;
            range.length = segEnd - segStart;

            if (heading == MarkdownStyler.SpanKind.HEADING_1) {
                range.font = h1Font;
                bold = true;
            } else if (heading == MarkdownStyler.SpanKind.HEADING_2) {
                range.font = h2Font;
                bold = true;
            } else if (heading == MarkdownStyler.SpanKind.HEADING_3) {
                range.font = h3Font;
                bold = true;
            } else if (codeBlock || inlineCode) {
                range.font = monoFont;
            }

            if (inlineCode) {
                range.background = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
            }

            int fontStyle = SWT.NORMAL;
            if (bold) {
                fontStyle |= SWT.BOLD;
            }
            if (italic) {
                fontStyle |= SWT.ITALIC;
            }
            range.fontStyle = fontStyle;

            if (link) {
                range.foreground = display.getSystemColor(SWT.COLOR_BLUE);
                range.underline = true;
            }
            ranges.add(range);
        }

        if (!ranges.isEmpty()) {
            styled.setStyleRanges(ranges.toArray(new StyleRange[0]));
        }
    }

    private Font createSizedBoldFont(Display display, FontData[] baseData, int deltaHeight) {
        FontData[] copy = new FontData[baseData.length];
        for (int i = 0; i < baseData.length; i++) {
            FontData fd = baseData[i];
            copy[i] = new FontData(fd.getName(), Math.max(8, fd.getHeight() + deltaHeight), fd.getStyle() | SWT.BOLD);
        }
        Font font = new Font(display, copy);
        ownedFonts.add(font);
        return font;
    }

    private static MarkdownStyler.LinkSpan findLinkAtPoint(
            StyledText styled,
            MarkdownStyler.StyledDocument doc,
            int x,
            int y) {
        if (doc.links().isEmpty()) {
            return null;
        }
        int offset;
        try {
            offset = styled.getOffsetAtPoint(new Point(x, y));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (offset < 0) {
            return null;
        }
        for (MarkdownStyler.LinkSpan link : doc.links()) {
            if (offset >= link.start() && offset < link.start() + link.length()) {
                return link;
            }
        }
        return null;
    }

    private void installTableContextMenu(TableViewer viewer, Table table, String viewId) {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.TableRow row) {
                ViewAction action = row.onActivate() != null ? row.onActivate() : row.onSelect();
                if (action != null) {
                    manager.add(new Action("Run Action") {
                        @Override
                        public void run() {
                            ViewActionDispatcher.dispatch(viewId, row.id(), action);
                        }
                    });
                }
                manager.add(new Action("Copy Row") {
                    @Override
                    public void run() {
                        copyToClipboard(table.getDisplay(), String.join("\t", row.cells()));
                    }
                });
            }
        });
        Menu menu = menuMgr.createContextMenu(table);
        table.setMenu(menu);
    }

    private void installTreeContextMenu(TreeViewer viewer, Tree tree, String viewId) {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.TreeNode node) {
                if (node.onActivate() != null) {
                    manager.add(new Action("Run Action") {
                        @Override
                        public void run() {
                            ViewActionDispatcher.dispatch(viewId, node.id(), node.onActivate());
                        }
                    });
                }
                manager.add(new Action("Copy Label") {
                    @Override
                    public void run() {
                        copyToClipboard(tree.getDisplay(), formatNodeLabel(node));
                    }
                });
            }
        });
        Menu menu = menuMgr.createContextMenu(tree);
        tree.setMenu(menu);
    }

    private void installKeyValueContextMenu(TreeViewer viewer, Tree tree, String viewId) {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            IStructuredSelection sel = viewer.getStructuredSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof ViewSpec.KvEntry entry) {
                if (entry.onActivate() != null) {
                    manager.add(new Action("Run Action") {
                        @Override
                        public void run() {
                            ViewActionDispatcher.dispatch(viewId, entry.key(), entry.onActivate());
                        }
                    });
                }
                manager.add(new Action("Copy Value") {
                    @Override
                    public void run() {
                        String val = entry.value() == null ? "" : entry.value();
                        copyToClipboard(tree.getDisplay(), val);
                    }
                });
            }
        });
        Menu menu = menuMgr.createContextMenu(tree);
        tree.setMenu(menu);
    }

    private void registerFocusControl(Control control) {
        if (focusControl == null) {
            focusControl = control;
        }
    }

    private static List<String> resolveTableColumns(ViewSpec.TableSpec spec) {
        if (!spec.columns().isEmpty()) {
            return spec.columns();
        }
        int maxCells = 1;
        for (ViewSpec.TableRow row : spec.rows()) {
            maxCells = Math.max(maxCells, row.cells().size());
        }
        List<String> inferred = new ArrayList<>(maxCells);
        for (int i = 0; i < maxCells; i++) {
            inferred.add("Column " + (i + 1));
        }
        return inferred;
    }

    private static String cellAt(ViewSpec.TableRow row, int idx) {
        if (idx < 0 || idx >= row.cells().size()) {
            return "";
        }
        String value = row.cells().get(idx);
        return value == null ? "" : value;
    }

    private static int compareSmart(String a, String b) {
        try {
            double da = Double.parseDouble(a.trim());
            double db = Double.parseDouble(b.trim());
            return Double.compare(da, db);
        } catch (NumberFormatException ignored) {
            return String.CASE_INSENSITIVE_ORDER.compare(a, b);
        }
    }

    private static void packTableColumns(Table table) {
        for (TableColumn tc : table.getColumns()) {
            tc.pack();
            if (tc.getWidth() < 80) {
                tc.setWidth(80);
            } else if (tc.getWidth() > 420) {
                tc.setWidth(420);
            }
        }
    }

    private static void packTreeColumns(Tree tree) {
        for (TreeColumn tc : tree.getColumns()) {
            tc.pack();
            if (tc.getWidth() < 100) {
                tc.setWidth(100);
            } else if (tc.getWidth() > 420) {
                tc.setWidth(420);
            }
        }
    }

    private static String formatNodeLabel(ViewSpec.TreeNode node) {
        String label = node.label() == null ? "" : node.label();
        if (node.badge() != null && !node.badge().isBlank()) {
            return label + " [" + node.badge().trim() + "]";
        }
        return label;
    }

    private static boolean treeNodeMatches(ViewSpec.TreeNode node, String query) {
        if (node.id() != null && node.id().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (node.label() != null && node.label().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (node.badge() != null && node.badge().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String val : node.values()) {
            if (val != null && val.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        for (ViewSpec.TreeNode child : node.children()) {
            if (treeNodeMatches(child, query)) {
                return true;
            }
        }
        return false;
    }

    private static void copyToClipboard(Display display, String text) {
        if (display == null || display.isDisposed()) {
            return;
        }
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private static final class TreeNodeContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof List<?> list) {
                return list.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof ViewSpec.TreeNode node) {
                return node.children().toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof ViewSpec.TreeNode node && !node.children().isEmpty();
        }
    }

    private static final class KvContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof List<?> list) {
                return list.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof ViewSpec.KvSection section) {
                return section.entries().toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof ViewSpec.KvSection section && !section.entries().isEmpty();
        }
    }
}
