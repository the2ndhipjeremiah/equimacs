package org.equimacs.eclipse.ui.inspect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
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
import org.equimacs.eclipse.ui.inspect.UiNode.UiSelector;
import org.equimacs.eclipse.ui.inspect.UiNode.UiTreeSnapshot;
import org.equimacs.protocol.Request;

/**
 * Resolves live SWT widgets and dispatches click/selection and text-input events
 * using modal-dialog-safe async dispatch with bounded wait.
 */
public final class SwtWidgetDriver {

    private static final int SELECTOR_INSPECT_DEPTH = 16;
    private static final long ASYNC_DISPATCH_TIMEOUT_MS = 500;

    private final UiWidgetRegistry registry;
    private final SwtUiInspector inspector;

    public SwtWidgetDriver(UiWidgetRegistry registry, SwtUiInspector inspector) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /**
     * Resolves the target widget and drives a click/selection event.
     */
    public Map<String, Object> click(Request.UiClick req) {
        Objects.requireNonNull(req, "req");
        UiSelector selector = new UiSelector(req.widgetId(), req.root(), req.widgetType(), req.text(), req.index());
        TargetSnapshot target = resolveTarget(selector);

        Display display = SwtUiInspector.requireDisplay();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        display.asyncExec(() -> {
            try {
                Map<String, Object> details = performClick(target.widget(), req);
                future.complete(details);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return awaitAsyncResult(future, target.summary());
    }

    /**
     * Resolves the target input widget and sets or appends text.
     */
    public Map<String, Object> type(Request.UiType req) {
        Objects.requireNonNull(req, "req");
        if (req.value() == null) {
            throw new IllegalArgumentException("UiType requires non-null value (use \"\" to clear)");
        }
        UiSelector selector = new UiSelector(req.widgetId(), req.root(), req.widgetType(), req.text(), req.index());
        TargetSnapshot target = resolveTarget(selector);

        Display display = SwtUiInspector.requireDisplay();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        display.asyncExec(() -> {
            try {
                Map<String, Object> details = performType(target.widget(), req);
                future.complete(details);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return awaitAsyncResult(future, target.summary());
    }

    private TargetSnapshot resolveTarget(UiSelector selector) {
        return SwtUiInspector.runOnUiThread(() -> {
            Widget widget;
            String requestedId = normalizeTrim(selector.widgetId());
            if (requestedId != null) {
                widget = registry.lookup(requestedId);
                if (widget == null || widget.isDisposed()) {
                    throw new IllegalArgumentException("Widget not found or disposed: " + requestedId);
                }
            } else {
                if (normalizeTrim(selector.widgetType()) == null && normalizeTrim(selector.text()) == null) {
                    throw new IllegalArgumentException(
                        "Selector requires widgetId or at least one of widgetType or text");
                }
                String rootScope = normalizeTrim(selector.root()) != null ? selector.root().trim() : "active";
                UiTreeSnapshot snapshot = inspector.captureUnfilteredOnUiThread(
                    rootScope, SELECTOR_INSPECT_DEPTH, true);
                UiTreeCore.ResolveResult resolved = UiTreeCore.resolveNode(snapshot.roots(), selector);
                String matchedId = switch (resolved) {
                    case UiTreeCore.ResolveResult.Matched m -> m.node().id();
                    case UiTreeCore.ResolveResult.NotFound nf -> throw new IllegalArgumentException(nf.reason());
                    case UiTreeCore.ResolveResult.OutOfRange oor -> throw new IllegalArgumentException(
                        "Selector matched " + oor.matchCount() + " widget(s), requested index "
                            + oor.requestedIndex() + " is out of range");
                };
                widget = registry.lookup(matchedId);
                if (widget == null || widget.isDisposed()) {
                    throw new IllegalStateException("Resolved widget is no longer available: " + matchedId);
                }
            }

            if (widget.isDisposed()) {
                throw new IllegalArgumentException("Target widget is disposed");
            }
            verifyEnabled(widget);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", registry.register(widget));
            summary.put("type", SwtUiInspector.widgetTypeName(widget));
            String widgetText = SwtUiInspector.extractWidgetText(widget);
            if (widgetText != null && !widgetText.isEmpty()) {
                summary.put("text", widgetText);
            }
            return new TargetSnapshot(widget, summary);
        });
    }

    private static void verifyEnabled(Widget widget) {
        boolean enabled = switch (widget) {
            case Control c -> c.isEnabled();
            case ToolItem ti -> ti.isEnabled();
            case MenuItem mi -> mi.isEnabled();
            case Menu m -> m.isEnabled();
            default -> true;
        };
        if (!enabled) {
            throw new IllegalArgumentException(
                "Target widget is disabled: " + SwtUiInspector.widgetTypeName(widget));
        }
    }

    private static Map<String, Object> awaitAsyncResult(
        CompletableFuture<Map<String, Object>> future,
        Map<String, Object> summary
    ) {
        try {
            Map<String, Object> details = future.get(ASYNC_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dispatched", true);
            result.put("asyncPending", false);
            result.put("widget", summary);
            if (details != null) {
                result.putAll(details);
            }
            return result;
        } catch (TimeoutException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dispatched", true);
            result.put("asyncPending", true);
            result.put("widget", summary);
            return result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException iae) {
                throw iae;
            }
            if (cause instanceof IllegalStateException ise) {
                throw ise;
            }
            String message = cause != null && cause.getMessage() != null
                ? cause.getMessage()
                : "SWT widget dispatch failed";
            throw new IllegalStateException(message, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SWT widget dispatch", e);
        }
    }

    private static Map<String, Object> performClick(Widget widget, Request.UiClick req) {
        if (widget == null || widget.isDisposed()) {
            throw new IllegalArgumentException("Target widget was disposed before click dispatch");
        }
        verifyEnabled(widget);

        boolean doubleClick = Boolean.TRUE.equals(req.doubleClick());
        Map<String, Object> details = new LinkedHashMap<>();

        switch (widget) {
            case Button button -> {
                int style = button.getStyle();
                if ((style & (SWT.CHECK | SWT.TOGGLE)) != 0) {
                    button.setSelection(!button.getSelection());
                } else if ((style & SWT.RADIO) != 0) {
                    deselectSiblingRadioButtons(button);
                    button.setSelection(true);
                }
                notifyEvent(button, null, SWT.Selection);
                if (doubleClick && !button.isDisposed()) {
                    notifyEvent(button, null, SWT.DefaultSelection);
                }
                if ((style & (SWT.CHECK | SWT.RADIO | SWT.TOGGLE)) != 0 && !button.isDisposed()) {
                    details.put("selected", button.getSelection());
                }
            }
            case ToolItem toolItem -> {
                int style = toolItem.getStyle();
                if ((style & SWT.CHECK) != 0) {
                    toolItem.setSelection(!toolItem.getSelection());
                } else if ((style & SWT.RADIO) != 0) {
                    deselectSiblingToolRadioItems(toolItem);
                    toolItem.setSelection(true);
                }
                notifyEvent(toolItem, null, SWT.Selection);
                if ((style & (SWT.CHECK | SWT.RADIO)) != 0 && !toolItem.isDisposed()) {
                    details.put("selected", toolItem.getSelection());
                }
            }
            case MenuItem menuItem -> {
                int style = menuItem.getStyle();
                if ((style & SWT.CHECK) != 0) {
                    menuItem.setSelection(!menuItem.getSelection());
                } else if ((style & SWT.RADIO) != 0) {
                    deselectSiblingMenuRadioItems(menuItem);
                    menuItem.setSelection(true);
                }
                notifyEvent(menuItem, null, SWT.Selection);
                if ((style & (SWT.CHECK | SWT.RADIO)) != 0 && !menuItem.isDisposed()) {
                    details.put("selected", menuItem.getSelection());
                }
            }
            case Tree tree -> {
                String needle = requireItemText(req.itemText(), "Tree");
                TreeItem item = findMatchingTreeItem(tree, needle);
                if (item == null) {
                    throw new IllegalArgumentException("Tree item not found: " + needle);
                }
                tree.setFocus();
                tree.setSelection(item);
                tree.showItem(item);
                String itemLabel = SwtUiInspector.formatTreeItemText(item, tree.getColumnCount());
                notifyEvent(tree, item, SWT.Selection);
                if (doubleClick && !tree.isDisposed()) {
                    notifyEvent(tree, item, SWT.DefaultSelection);
                }
                details.put("item", itemLabel);
            }
            case Table table -> {
                String needle = requireItemText(req.itemText(), "Table");
                TableItem item = findMatchingTableItem(table, needle);
                if (item == null) {
                    throw new IllegalArgumentException("Table item not found: " + needle);
                }
                table.setFocus();
                table.setSelection(item);
                table.showItem(item);
                String itemLabel = SwtUiInspector.formatTableItemText(item, table.getColumnCount());
                notifyEvent(table, item, SWT.Selection);
                if (doubleClick && !table.isDisposed()) {
                    notifyEvent(table, item, SWT.DefaultSelection);
                }
                details.put("item", itemLabel);
            }
            case org.eclipse.swt.widgets.List swtList -> {
                String needle = requireItemText(req.itemText(), "List");
                int idx = findMatchingStringIndex(swtList.getItems(), needle);
                if (idx < 0) {
                    throw new IllegalArgumentException("List item not found: " + needle);
                }
                String itemLabel = swtList.getItem(idx);
                swtList.setFocus();
                swtList.setSelection(idx);
                notifyEvent(swtList, null, SWT.Selection);
                if (doubleClick && !swtList.isDisposed()) {
                    notifyEvent(swtList, null, SWT.DefaultSelection);
                }
                details.put("item", itemLabel);
            }
            case Combo combo -> {
                String needle = requireItemText(req.itemText(), "Combo");
                int idx = findMatchingStringIndex(combo.getItems(), needle);
                if (idx < 0) {
                    throw new IllegalArgumentException("Combo item not found: " + needle);
                }
                String itemLabel = combo.getItem(idx);
                combo.setFocus();
                combo.select(idx);
                notifyEvent(combo, null, SWT.Selection);
                details.put("item", itemLabel);
            }
            case TabFolder tabFolder -> {
                String needle = requireItemText(req.itemText(), "TabFolder");
                TabItem item = findMatchingNamedItem(tabFolder.getItems(), TabItem::getText, needle);
                if (item == null) {
                    throw new IllegalArgumentException("TabItem not found: " + needle);
                }
                String itemLabel = item.getText();
                tabFolder.setSelection(item);
                notifyEvent(tabFolder, item, SWT.Selection);
                details.put("item", itemLabel);
            }
            case CTabFolder cTabFolder -> {
                String needle = requireItemText(req.itemText(), "CTabFolder");
                CTabItem item = findMatchingNamedItem(cTabFolder.getItems(), CTabItem::getText, needle);
                if (item == null) {
                    throw new IllegalArgumentException("CTabItem not found: " + needle);
                }
                String itemLabel = item.getText();
                cTabFolder.setSelection(item);
                notifyEvent(cTabFolder, item, SWT.Selection);
                details.put("item", itemLabel);
            }
            case TabItem tabItem -> {
                TabFolder parent = tabItem.getParent();
                if (parent == null || parent.isDisposed()) {
                    throw new IllegalArgumentException("TabItem parent TabFolder is disposed");
                }
                String itemLabel = tabItem.getText();
                parent.setSelection(tabItem);
                notifyEvent(parent, tabItem, SWT.Selection);
                details.put("item", itemLabel);
            }
            case CTabItem cTabItem -> {
                CTabFolder parent = cTabItem.getParent();
                if (parent == null || parent.isDisposed()) {
                    throw new IllegalArgumentException("CTabItem parent CTabFolder is disposed");
                }
                String itemLabel = cTabItem.getText();
                parent.setSelection(cTabItem);
                notifyEvent(parent, cTabItem, SWT.Selection);
                details.put("item", itemLabel);
            }
            case Link link -> {
                link.setFocus();
                notifyEvent(link, null, SWT.Selection);
            }
            default -> throw new IllegalArgumentException(
                "Unsupported clickable widget type: " + SwtUiInspector.widgetTypeName(widget));
        }

        if (doubleClick) {
            details.put("doubleClick", true);
        }
        return details;
    }

    private static Map<String, Object> performType(Widget widget, Request.UiType req) {
        if (widget == null || widget.isDisposed()) {
            throw new IllegalArgumentException("Target widget was disposed before type dispatch");
        }
        verifyEnabled(widget);

        boolean append = Boolean.TRUE.equals(req.append());
        boolean submit = Boolean.TRUE.equals(req.submit());
        String value = req.value();
        String nextText;

        switch (widget) {
            case Text textWidget -> {
                if ((textWidget.getStyle() & SWT.READ_ONLY) != 0) {
                    throw new IllegalArgumentException("Text widget is read-only");
                }
                textWidget.setFocus();
                nextText = append ? textWidget.getText() + value : value;
                textWidget.setText(nextText);
                textWidget.setSelection(nextText.length());
                if (submit && !textWidget.isDisposed()) {
                    notifyReturnTraverse(textWidget);
                    if (!textWidget.isDisposed()) {
                        notifyEvent(textWidget, null, SWT.DefaultSelection);
                    }
                }
            }
            case StyledText styledText -> {
                if (!styledText.getEditable()) {
                    throw new IllegalArgumentException("StyledText widget is read-only");
                }
                styledText.setFocus();
                nextText = append ? styledText.getText() + value : value;
                styledText.setText(nextText);
                styledText.setSelection(nextText.length());
                if (submit && !styledText.isDisposed()) {
                    notifyReturnTraverse(styledText);
                }
            }
            case Combo combo -> {
                if ((combo.getStyle() & SWT.READ_ONLY) != 0) {
                    throw new IllegalArgumentException("Combo widget is read-only");
                }
                combo.setFocus();
                nextText = append ? combo.getText() + value : value;
                combo.setText(nextText);
                combo.setSelection(new Point(nextText.length(), nextText.length()));
                if (submit && !combo.isDisposed()) {
                    notifyReturnTraverse(combo);
                    if (!combo.isDisposed()) {
                        notifyEvent(combo, null, SWT.DefaultSelection);
                    }
                }
            }
            default -> throw new IllegalArgumentException(
                "Widget does not accept text input: " + SwtUiInspector.widgetTypeName(widget));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("value", nextText);
        details.put("append", append);
        details.put("submitted", submit);
        return details;
    }

    private static void notifyEvent(Widget target, Widget item, int eventType) {
        Event event = new Event();
        event.widget = target;
        event.item = item;
        event.type = eventType;
        target.notifyListeners(eventType, event);
    }

    private static void notifyReturnTraverse(Control control) {
        Event traverse = new Event();
        traverse.widget = control;
        traverse.type = SWT.Traverse;
        traverse.detail = SWT.TRAVERSE_RETURN;
        traverse.doit = true;
        control.notifyListeners(SWT.Traverse, traverse);
    }

    private static void deselectSiblingRadioButtons(Button selected) {
        Composite parent = selected.getParent();
        if (parent == null || parent.isDisposed() || (parent.getStyle() & SWT.NO_RADIO_GROUP) != 0) {
            return;
        }
        Control[] children = parent.getChildren();
        if (children == null) {
            return;
        }
        for (Control sibling : children) {
            if (sibling instanceof Button siblingButton
                && siblingButton != selected
                && !siblingButton.isDisposed()
                && (siblingButton.getStyle() & SWT.RADIO) != 0) {
                siblingButton.setSelection(false);
            }
        }
    }

    private static void deselectSiblingToolRadioItems(ToolItem selected) {
        ToolBar parent = selected.getParent();
        if (parent == null || parent.isDisposed()) {
            return;
        }
        deselectContiguousRadioItems(
            selected,
            parent.getItems(),
            item -> (item.getStyle() & SWT.SEPARATOR) == 0 && (item.getStyle() & SWT.RADIO) != 0,
            item -> item.setSelection(false)
        );
    }

    private static void deselectSiblingMenuRadioItems(MenuItem selected) {
        Menu parent = selected.getParent();
        if (parent == null || parent.isDisposed()) {
            return;
        }
        deselectContiguousRadioItems(
            selected,
            parent.getItems(),
            item -> (item.getStyle() & SWT.SEPARATOR) == 0 && (item.getStyle() & SWT.RADIO) != 0,
            item -> item.setSelection(false)
        );
    }

    private static <T extends Widget> void deselectContiguousRadioItems(
        T selected,
        T[] items,
        Predicate<T> isRadioItem,
        Consumer<T> clearSelection
    ) {
        if (items == null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < items.length; i++) {
            if (items[i] == selected) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        for (int i = index - 1; i >= 0; i--) {
            T item = items[i];
            if (item == null || item.isDisposed() || !isRadioItem.test(item)) {
                break;
            }
            clearSelection.accept(item);
        }
        for (int i = index + 1; i < items.length; i++) {
            T item = items[i];
            if (item == null || item.isDisposed() || !isRadioItem.test(item)) {
                break;
            }
            clearSelection.accept(item);
        }
    }

    private static String requireItemText(String itemText, String widgetType) {
        String trimmed = normalizeTrim(itemText);
        if (trimmed == null) {
            throw new IllegalArgumentException("Clicking a " + widgetType + " requires non-blank itemText");
        }
        return trimmed;
    }

    private static TreeItem findMatchingTreeItem(Tree tree, String needle) {
        List<TreeItem> all = new ArrayList<>();
        collectAllTreeItems(tree.getItems(), all);
        int columnCount = tree.getColumnCount();
        return findMatchingRow(
            all,
            columnCount,
            TreeItem::getText,
            TreeItem::getText,
            item -> SwtUiInspector.formatTreeItemText(item, columnCount),
            needle
        );
    }

    private static void collectAllTreeItems(TreeItem[] items, List<TreeItem> out) {
        if (items == null) {
            return;
        }
        for (TreeItem item : items) {
            if (item == null || item.isDisposed()) {
                continue;
            }
            out.add(item);
            collectAllTreeItems(item.getItems(), out);
        }
    }

    private static TableItem findMatchingTableItem(Table table, String needle) {
        TableItem[] rows = table.getItems();
        if (rows == null) {
            return null;
        }
        int columnCount = table.getColumnCount();
        return findMatchingRow(
            Arrays.asList(rows),
            columnCount,
            TableItem::getText,
            TableItem::getText,
            row -> SwtUiInspector.formatTableItemText(row, columnCount),
            needle
        );
    }

    private static <T extends Widget> T findMatchingRow(
        List<T> rows,
        int columnCount,
        Function<T, String> primaryReader,
        BiFunction<T, Integer, String> colReader,
        Function<T, String> joinedReader,
        String needle
    ) {
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        T containsMatch = null;
        for (T row : rows) {
            if (row == null || row.isDisposed()) {
                continue;
            }
            if (rowMatches(row, columnCount, primaryReader, colReader, joinedReader, candidate -> equalsTrimmedIgnoreCase(candidate, needle))) {
                return row;
            }
            if (containsMatch == null
                && rowMatches(row, columnCount, primaryReader, colReader, joinedReader, candidate -> containsIgnoreCase(candidate, lowerNeedle))) {
                containsMatch = row;
            }
        }
        return containsMatch;
    }

    private static <T> boolean rowMatches(
        T row,
        int columnCount,
        Function<T, String> primaryReader,
        BiFunction<T, Integer, String> colReader,
        Function<T, String> joinedReader,
        Predicate<String> predicate
    ) {
        if (predicate.test(primaryReader.apply(row))) {
            return true;
        }
        if (columnCount > 1) {
            for (int c = 0; c < columnCount; c++) {
                if (predicate.test(colReader.apply(row, c))) {
                    return true;
                }
            }
            return predicate.test(joinedReader.apply(row));
        }
        return false;
    }

    private static int findMatchingStringIndex(String[] items, String needle) {
        if (items == null) {
            return -1;
        }
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        int containsIdx = -1;
        for (int i = 0; i < items.length; i++) {
            String candidate = items[i];
            if (equalsTrimmedIgnoreCase(candidate, needle)) {
                return i;
            }
            if (containsIdx < 0 && containsIgnoreCase(candidate, lowerNeedle)) {
                containsIdx = i;
            }
        }
        return containsIdx;
    }

    private static <T extends Widget> T findMatchingNamedItem(
        T[] items,
        Function<T, String> textReader,
        String needle
    ) {
        if (items == null) {
            return null;
        }
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        T containsMatch = null;
        for (T item : items) {
            if (item == null || item.isDisposed()) {
                continue;
            }
            String text = textReader.apply(item);
            if (equalsTrimmedIgnoreCase(text, needle)) {
                return item;
            }
            if (containsMatch == null && containsIgnoreCase(text, lowerNeedle)) {
                containsMatch = item;
            }
        }
        return containsMatch;
    }

    private static String normalizeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean equalsTrimmedIgnoreCase(String candidate, String needle) {
        return candidate != null && candidate.trim().equalsIgnoreCase(needle);
    }

    private static boolean containsIgnoreCase(String candidate, String lowerNeedle) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private record TargetSnapshot(Widget widget, Map<String, Object> summary) {}
}
