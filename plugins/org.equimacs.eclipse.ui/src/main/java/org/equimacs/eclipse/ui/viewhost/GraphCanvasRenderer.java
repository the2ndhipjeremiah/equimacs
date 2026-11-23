package org.equimacs.eclipse.ui.viewhost;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;

/**
 * Thin SWT {@link Canvas} renderer for native Java/SWT {@link ViewSpec.GraphSpec} graphs.
 * Supports pan, cursor-anchored wheel zoom, hover/selection highlights, double-click open,
 * Shift+click secondary action, and context menu actions.
 */
public final class GraphCanvasRenderer {

    private static final String HINT_TEXT =
        "Double-click: Open | Shift+Click / Right-click: Focus | Drag: Pan | Wheel: Zoom";
    private static final double WHEEL_ZOOM_STEP = 1.12;
    private static final double BUTTON_ZOOM_STEP = 1.25;

    private GraphCanvasRenderer() {}

    /**
     * Renders {@code spec} inside {@code container} and returns the focusable {@link Canvas} control.
     */
    public static Control render(Composite container, String viewId, ViewSpec.GraphSpec spec) {
        GraphLayoutCore.GraphLayout layout = GraphLayoutCore.layout(spec);
        CanvasState state = new CanvasState();

        Composite bar = new Composite(container, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout barLayout = new GridLayout(2, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        barLayout.horizontalSpacing = 8;
        bar.setLayout(barLayout);

        Composite buttons = new Composite(bar, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.spacing = 4;
        buttons.setLayout(rowLayout);

        Button zoomInBtn = new Button(buttons, SWT.PUSH);
        zoomInBtn.setText("Zoom +");
        Button zoomOutBtn = new Button(buttons, SWT.PUSH);
        zoomOutBtn.setText("Zoom -");
        Button fitBtn = new Button(buttons, SWT.PUSH);
        fitBtn.setText("Fit");
        Button resetBtn = new Button(buttons, SWT.PUSH);
        resetBtn.setText("Reset");

        Label hintLabel = new Label(bar, SWT.WRAP);
        hintLabel.setText(HINT_TEXT);
        hintLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Canvas canvas = new Canvas(container, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Display display = canvas.getDisplay();
        GraphPalette palette = new GraphPalette(display, canvas.getFont());
        canvas.setBackground(palette.canvasBg);
        canvas.addDisposeListener(e -> palette.dispose());

        zoomInBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                zoomAroundCenter(canvas, layout, state, BUTTON_ZOOM_STEP);
            }
        });
        zoomOutBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                zoomAroundCenter(canvas, layout, state, 1.0 / BUTTON_ZOOM_STEP);
            }
        });
        fitBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                fitGraph(canvas, layout, state);
            }
        });
        resetBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                resetGraph(canvas, layout, state);
            }
        });

        canvas.addPaintListener(e -> {
            Rectangle area = canvas.getClientArea();
            if (!state.initialized && area.width > 0 && area.height > 0) {
                GraphLayoutCore.ViewportTransform fit =
                    GraphLayoutCore.computeFitTransform(layout, area.width, area.height);
                state.zoom = fit.zoom();
                state.panX = fit.panX();
                state.panY = fit.panY();
                state.initialized = true;
            }
            paintGraph(e.gc, area, layout, state, palette);
        });

        canvas.addMouseWheelListener(e -> {
            ensureInitialized(canvas, layout, state);
            double factor = e.count > 0 ? WHEEL_ZOOM_STEP : (1.0 / WHEEL_ZOOM_STEP);
            double newZoom = GraphLayoutCore.clampZoom(state.zoom * factor);
            double worldX = (e.x - state.panX) / state.zoom;
            double worldY = (e.y - state.panY) / state.zoom;
            state.zoom = newZoom;
            state.panX = e.x - (worldX * newZoom);
            state.panY = e.y - (worldY * newZoom);
            canvas.redraw();
        });

        canvas.addMouseMoveListener(e -> {
            ensureInitialized(canvas, layout, state);
            if (state.dragging) {
                int dx = e.x - state.dragStartX;
                int dy = e.y - state.dragStartY;
                if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                    state.didDrag = true;
                }
                state.panX = state.dragOriginPanX + dx;
                state.panY = state.dragOriginPanY + dy;
                canvas.redraw();
                return;
            }
            ViewSpec.GraphNode hit = hitTestScreen(layout, state, e.x, e.y);
            String hitId = hit != null ? hit.id() : null;
            if (!equalsNullable(state.hoveredNodeId, hitId)) {
                state.hoveredNodeId = hitId;
                canvas.setCursor(hit != null ? display.getSystemCursor(SWT.CURSOR_HAND) : null);
                canvas.redraw();
            }
        });

        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                canvas.setFocus();
                ensureInitialized(canvas, layout, state);
                if (e.button == 1) {
                    state.dragging = true;
                    state.didDrag = false;
                    state.dragStartX = e.x;
                    state.dragStartY = e.y;
                    state.dragOriginPanX = state.panX;
                    state.dragOriginPanY = state.panY;
                }
            }

            @Override
            public void mouseUp(MouseEvent e) {
                if (e.button != 1) {
                    return;
                }
                boolean wasDrag = state.didDrag;
                state.dragging = false;
                state.didDrag = false;
                if (wasDrag) {
                    return;
                }
                ViewSpec.GraphNode hit = hitTestScreen(layout, state, e.x, e.y);
                if (hit == null) {
                    if (state.selectedNodeId != null) {
                        state.selectedNodeId = null;
                        canvas.redraw();
                    }
                    return;
                }
                state.selectedNodeId = hit.id();
                canvas.redraw();

                if ((e.stateMask & SWT.SHIFT) != 0) {
                    if (hit.onSecondary() != null) {
                        ViewActionDispatcher.dispatch(viewId, hit.id(), hit.onSecondary());
                    }
                } else if (hit.onSelect() != null) {
                    ViewActionDispatcher.dispatch(viewId, hit.id(), hit.onSelect());
                }
            }

            @Override
            public void mouseDoubleClick(MouseEvent e) {
                if (e.button != 1) {
                    return;
                }
                ensureInitialized(canvas, layout, state);
                ViewSpec.GraphNode hit = hitTestScreen(layout, state, e.x, e.y);
                if (hit != null && hit.onActivate() != null) {
                    state.selectedNodeId = hit.id();
                    canvas.redraw();
                    ViewActionDispatcher.dispatch(viewId, hit.id(), hit.onActivate());
                }
            }
        });

        installContextMenu(canvas, viewId, layout, state);
        return canvas;
    }

    private static void installContextMenu(
            Canvas canvas,
            String viewId,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state) {
        canvas.addMenuDetectListener(e -> {
            Point local = canvas.toControl(e.x, e.y);
            state.contextMenuX = local.x;
            state.contextMenuY = local.y;
        });

        MenuManager menuMgr = new MenuManager("#GraphContextMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            ensureInitialized(canvas, layout, state);
            ViewSpec.GraphNode hit = hitTestScreen(layout, state, state.contextMenuX, state.contextMenuY);
            if (hit != null) {
                state.selectedNodeId = hit.id();
                canvas.redraw();
                if (hit.onActivate() != null) {
                    manager.add(new Action("Open") {
                        @Override
                        public void run() {
                            ViewActionDispatcher.dispatch(viewId, hit.id(), hit.onActivate());
                        }
                    });
                }
                if (hit.onSecondary() != null) {
                    String secLabel = hit.secondaryLabel().isBlank() ? "Focus" : hit.secondaryLabel();
                    manager.add(new Action(secLabel) {
                        @Override
                        public void run() {
                            ViewActionDispatcher.dispatch(viewId, hit.id(), hit.onSecondary());
                        }
                    });
                }
                manager.add(new Action("Copy Label") {
                    @Override
                    public void run() {
                        copyToClipboard(canvas.getDisplay(), hit.label());
                    }
                });
            } else {
                manager.add(new Action("Fit Graph") {
                    @Override
                    public void run() {
                        fitGraph(canvas, layout, state);
                    }
                });
                manager.add(new Action("Reset Zoom") {
                    @Override
                    public void run() {
                        resetGraph(canvas, layout, state);
                    }
                });
            }
        });
        Menu menu = menuMgr.createContextMenu(canvas);
        canvas.setMenu(menu);
    }

    private static void paintGraph(
            GC gc,
            Rectangle area,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state,
            GraphPalette palette) {
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(palette.canvasBg);
        gc.fillRectangle(area);

        if (layout.nodes().isEmpty()) {
            gc.setForeground(palette.subtextFg);
            gc.setFont(palette.regularFont);
            String msg = "No graph nodes to display.";
            Point ext = gc.stringExtent(msg);
            gc.drawString(msg, Math.max(8, (area.width - ext.x) / 2), Math.max(8, (area.height - ext.y) / 2), true);
            return;
        }

        paintEdges(gc, layout, state, palette);
        paintNodes(gc, layout, state, palette);
    }

    private static void paintEdges(
            GC gc,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state,
            GraphPalette palette) {
        int lineWidth = Math.max(1, (int) Math.round(1.5 * Math.min(state.zoom, 1.3)));
        gc.setLineWidth(lineWidth);

        for (GraphLayoutCore.LayoutEdge layoutEdge : layout.edges()) {
            double[] pts = layoutEdge.points();
            if (pts.length < 4) {
                continue;
            }
            boolean dashed = "dashed".equals(layoutEdge.edge().style());
            gc.setLineStyle(dashed ? SWT.LINE_DASH : SWT.LINE_SOLID);
            gc.setForeground(dashed ? palette.edgeDashed : palette.edgeSolid);

            int[] screenPts = new int[pts.length];
            for (int i = 0; i < pts.length; i += 2) {
                screenPts[i] = toScreenX(state, pts[i]);
                screenPts[i + 1] = toScreenY(state, pts[i + 1]);
            }
            gc.drawPolyline(screenPts);

            drawArrowhead(gc, screenPts, state.zoom, dashed ? palette.edgeDashed : palette.edgeSolid);

            String edgeLabel = layoutEdge.edge().label();
            if (!edgeLabel.isBlank() && state.zoom >= 0.45) {
                int midIdx = ((pts.length / 2) / 2) * 2;
                int mx = screenPts[midIdx];
                int my = screenPts[midIdx + 1];
                gc.setFont(palette.smallFont);
                Point ext = gc.stringExtent(edgeLabel);
                int padX = 5;
                int padY = 2;
                int rx = mx - (ext.x / 2) - padX;
                int ry = my - (ext.y / 2) - padY;
                gc.setBackground(palette.canvasBg);
                gc.fillRoundRectangle(rx, ry, ext.x + padX * 2, ext.y + padY * 2, 6, 6);
                gc.setForeground(palette.subtextFg);
                gc.drawString(edgeLabel, rx + padX, ry + padY, true);
            }
        }
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    private static void drawArrowhead(GC gc, int[] screenPts, double zoom, Color color) {
        int n = screenPts.length;
        if (n < 4) {
            return;
        }
        int tipX = screenPts[n - 2];
        int tipY = screenPts[n - 1];
        int tailX = screenPts[n - 4];
        int tailY = screenPts[n - 3];
        for (int i = n - 4; i >= 0; i -= 2) {
            int cx = screenPts[i];
            int cy = screenPts[i + 1];
            if (Math.hypot(tipX - cx, tipY - cy) >= 2.0) {
                tailX = cx;
                tailY = cy;
                break;
            }
        }
        double dx = tipX - tailX;
        double dy = tipY - tailY;
        double len = Math.hypot(dx, dy);
        if (len < 1e-3) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double arrowLen = Math.max(6.0, 9.5 * Math.min(zoom, 1.2));
        double arrowHalfW = arrowLen * 0.45;
        double baseX = tipX - ux * arrowLen;
        double baseY = tipY - uy * arrowLen;
        double px = -uy * arrowHalfW;
        double py = ux * arrowHalfW;

        int[] poly = new int[] {
            tipX,
            tipY,
            (int) Math.round(baseX + px),
            (int) Math.round(baseY + py),
            (int) Math.round(baseX - px),
            (int) Math.round(baseY - py)
        };
        gc.setBackground(color);
        gc.fillPolygon(poly);
    }

    private static void paintNodes(
            GC gc,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state,
            GraphPalette palette) {
        int arc = Math.max(6, (int) Math.round(10 * Math.min(state.zoom, 1.2)));
        for (GraphLayoutCore.LayoutNode ln : layout.nodes()) {
            ViewSpec.GraphNode node = ln.node();
            int x = toScreenX(state, ln.x());
            int y = toScreenY(state, ln.y());
            int w = Math.max(8, (int) Math.round(ln.width() * state.zoom));
            int h = Math.max(8, (int) Math.round(ln.height() * state.zoom));

            boolean selected = node.id().equals(state.selectedNodeId);
            boolean hovered = node.id().equals(state.hoveredNodeId);
            Color accent = accentForGroup(node.group(), palette);
            Color cardBg = "center".equals(node.group()) ? palette.centerCardBg : palette.cardBg;

            gc.setBackground(cardBg);
            gc.fillRoundRectangle(x, y, w, h, arc, arc);

            int stripW = Math.max(4, (int) Math.round(5 * Math.min(state.zoom, 1.3)));
            gc.setBackground(accent);
            gc.fillRoundRectangle(x + 1, y + 1, Math.min(stripW + 4, w - 2), h - 2, arc / 2, arc / 2);
            if (w > stripW + 8) {
                gc.setBackground(cardBg);
                gc.fillRectangle(x + stripW, y + 1, 6, h - 2);
            }

            Color borderColor = selected
                ? palette.selectedBorder
                : (hovered ? palette.hoverBorder : accent);
            gc.setForeground(borderColor);
            gc.setLineWidth(selected || hovered || "center".equals(node.group()) ? 2 : 1);
            gc.drawRoundRectangle(x, y, w, h, arc, arc);

            if (state.zoom < 0.35 || w < 24 || h < 18) {
                continue;
            }

            int padLeft = stripW + Math.max(6, (int) Math.round(8 * state.zoom));
            int padRight = Math.max(6, (int) Math.round(8 * state.zoom));
            int padTop = Math.max(4, (int) Math.round(7 * state.zoom));
            int availW = Math.max(10, w - padLeft - padRight);

            gc.setClipping(x + 2, y + 2, Math.max(1, w - 4), Math.max(1, h - 4));

            int badgeReserved = 0;
            String badge = node.badge();
            if (!badge.isBlank() && w > 80) {
                gc.setFont(palette.smallFont);
                String badgeText = truncateText(gc, badge, Math.max(24, availW / 2));
                Point badgeExt = gc.stringExtent(badgeText);
                int bPadX = 5;
                int bPadY = 1;
                int bw = badgeExt.x + bPadX * 2;
                int bh = badgeExt.y + bPadY * 2;
                int bx = x + w - padRight - bw;
                int by = y + padTop;
                gc.setBackground(palette.badgeBg);
                gc.fillRoundRectangle(bx, by, bw, bh, 8, 8);
                gc.setForeground(accent);
                gc.drawString(badgeText, bx + bPadX, by + bPadY, true);
                badgeReserved = bw + 6;
            }

            gc.setFont(palette.boldFont);
            gc.setForeground(palette.textFg);
            String label = truncateText(gc, node.label(), Math.max(12, availW - badgeReserved));
            Point labelExt = gc.stringExtent(label);
            gc.drawString(label, x + padLeft, y + padTop, true);

            String sublabel = node.sublabel();
            if (!sublabel.isBlank() && h >= padTop + labelExt.y + 12) {
                gc.setFont(palette.smallFont);
                gc.setForeground(palette.subtextFg);
                String sub = truncateText(gc, sublabel, availW);
                gc.drawString(sub, x + padLeft, y + padTop + labelExt.y + 4, true);
            }

            gc.setClipping((Rectangle) null);
        }
        gc.setLineWidth(1);
    }

    private static Color accentForGroup(String group, GraphPalette palette) {
        if ("left".equals(group)) {
            return palette.leftAccent;
        }
        if ("right".equals(group)) {
            return palette.rightAccent;
        }
        return palette.centerAccent;
    }

    private static String truncateText(GC gc, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 6) {
            return "";
        }
        if (gc.stringExtent(text).x <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        if (gc.stringExtent(ellipsis).x > maxWidth) {
            return "";
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = text.substring(0, mid) + ellipsis;
            if (gc.stringExtent(candidate).x <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    private static void zoomAroundCenter(
            Canvas canvas,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state,
            double factor) {
        ensureInitialized(canvas, layout, state);
        Rectangle area = canvas.getClientArea();
        double cx = area.width / 2.0;
        double cy = area.height / 2.0;
        double newZoom = GraphLayoutCore.clampZoom(state.zoom * factor);
        double worldX = (cx - state.panX) / state.zoom;
        double worldY = (cy - state.panY) / state.zoom;
        state.zoom = newZoom;
        state.panX = cx - (worldX * newZoom);
        state.panY = cy - (worldY * newZoom);
        canvas.redraw();
    }

    private static void fitGraph(
            Canvas canvas,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state) {
        Rectangle area = canvas.getClientArea();
        GraphLayoutCore.ViewportTransform fit =
            GraphLayoutCore.computeFitTransform(layout, area.width, area.height);
        state.zoom = fit.zoom();
        state.panX = fit.panX();
        state.panY = fit.panY();
        state.initialized = true;
        canvas.redraw();
    }

    private static void resetGraph(
            Canvas canvas,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state) {
        Rectangle area = canvas.getClientArea();
        double centerX = (layout.minX() + layout.maxX()) / 2.0;
        double centerY = (layout.minY() + layout.maxY()) / 2.0;
        state.zoom = 1.0;
        state.panX = (area.width / 2.0) - centerX;
        state.panY = (area.height / 2.0) - centerY;
        state.initialized = true;
        canvas.redraw();
    }

    private static void ensureInitialized(
            Canvas canvas,
            GraphLayoutCore.GraphLayout layout,
            CanvasState state) {
        if (state.initialized) {
            return;
        }
        Rectangle area = canvas.getClientArea();
        if (area.width > 0 && area.height > 0) {
            GraphLayoutCore.ViewportTransform fit =
                GraphLayoutCore.computeFitTransform(layout, area.width, area.height);
            state.zoom = fit.zoom();
            state.panX = fit.panX();
            state.panY = fit.panY();
            state.initialized = true;
        }
    }

    private static ViewSpec.GraphNode hitTestScreen(
            GraphLayoutCore.GraphLayout layout,
            CanvasState state,
            int screenX,
            int screenY) {
        double worldX = (screenX - state.panX) / state.zoom;
        double worldY = (screenY - state.panY) / state.zoom;
        return GraphLayoutCore.hitTest(layout, worldX, worldY);
    }

    private static int toScreenX(CanvasState state, double worldX) {
        return (int) Math.round(worldX * state.zoom + state.panX);
    }

    private static int toScreenY(CanvasState state, double worldY) {
        return (int) Math.round(worldY * state.zoom + state.panY);
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void copyToClipboard(Display display, String text) {
        if (display == null || display.isDisposed()) {
            return;
        }
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(
                new Object[] { text == null ? "" : text },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private static final class CanvasState {
        boolean initialized;
        double zoom = 1.0;
        double panX;
        double panY;
        boolean dragging;
        boolean didDrag;
        int dragStartX;
        int dragStartY;
        double dragOriginPanX;
        double dragOriginPanY;
        int contextMenuX;
        int contextMenuY;
        String selectedNodeId;
        String hoveredNodeId;
    }

    private static final class GraphPalette {
        private final List<Color> ownedColors = new ArrayList<>();
        private final List<Font> ownedFonts = new ArrayList<>();

        final Color canvasBg;
        final Color cardBg;
        final Color centerCardBg;
        final Color badgeBg;
        final Color textFg;
        final Color subtextFg;
        final Color centerAccent;
        final Color leftAccent;
        final Color rightAccent;
        final Color selectedBorder;
        final Color hoverBorder;
        final Color edgeSolid;
        final Color edgeDashed;
        final Font regularFont;
        final Font boldFont;
        final Font smallFont;

        GraphPalette(Display display, Font baseFont) {
            RGB sysBg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
            boolean dark = (sysBg.red + sysBg.green + sysBg.blue) < 384;

            if (dark) {
                canvasBg = ownColor(display, 30, 32, 36);
                cardBg = ownColor(display, 43, 47, 54);
                centerCardBg = ownColor(display, 37, 49, 68);
                badgeBg = ownColor(display, 56, 62, 74);
                textFg = ownColor(display, 234, 238, 245);
                subtextFg = ownColor(display, 166, 174, 188);
                centerAccent = ownColor(display, 96, 165, 250);
                leftAccent = ownColor(display, 45, 212, 191);
                rightAccent = ownColor(display, 251, 146, 60);
                selectedBorder = ownColor(display, 250, 204, 21);
                hoverBorder = ownColor(display, 191, 219, 254);
                edgeSolid = ownColor(display, 130, 142, 160);
                edgeDashed = ownColor(display, 110, 120, 136);
            } else {
                canvasBg = ownColor(display, 248, 250, 252);
                cardBg = ownColor(display, 255, 255, 255);
                centerCardBg = ownColor(display, 239, 246, 255);
                badgeBg = ownColor(display, 241, 245, 249);
                textFg = ownColor(display, 15, 23, 42);
                subtextFg = ownColor(display, 100, 116, 139);
                centerAccent = ownColor(display, 37, 99, 235);
                leftAccent = ownColor(display, 13, 148, 136);
                rightAccent = ownColor(display, 217, 119, 6);
                selectedBorder = ownColor(display, 220, 38, 38);
                hoverBorder = ownColor(display, 59, 130, 246);
                edgeSolid = ownColor(display, 100, 116, 139);
                edgeDashed = ownColor(display, 148, 163, 184);
            }

            FontData[] baseData = baseFont.getFontData();
            regularFont = ownFont(display, copyWithDelta(baseData, 0, SWT.NORMAL));
            boldFont = ownFont(display, copyWithDelta(baseData, 0, SWT.BOLD));
            smallFont = ownFont(display, copyWithDelta(baseData, -1, SWT.NORMAL));
        }

        private Color ownColor(Display display, int r, int g, int b) {
            Color c = new Color(display, r, g, b);
            ownedColors.add(c);
            return c;
        }

        private Font ownFont(Display display, FontData[] data) {
            Font f = new Font(display, data);
            ownedFonts.add(f);
            return f;
        }

        private static FontData[] copyWithDelta(FontData[] base, int deltaHeight, int style) {
            FontData[] copy = new FontData[base.length];
            for (int i = 0; i < base.length; i++) {
                FontData fd = base[i];
                copy[i] = new FontData(fd.getName(), Math.max(7, fd.getHeight() + deltaHeight), style);
            }
            return copy;
        }

        void dispose() {
            for (Color c : ownedColors) {
                if (c != null && !c.isDisposed()) {
                    c.dispose();
                }
            }
            ownedColors.clear();
            for (Font f : ownedFonts) {
                if (f != null && !f.isDisposed()) {
                    f.dispose();
                }
            }
            ownedFonts.clear();
        }
    }
}
