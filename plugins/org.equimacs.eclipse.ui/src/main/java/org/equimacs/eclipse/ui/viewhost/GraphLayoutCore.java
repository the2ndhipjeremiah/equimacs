package org.equimacs.eclipse.ui.viewhost;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure deterministic graph layout engine for {@link ViewSpec.GraphSpec}.
 * Computes world-space node rectangles, sampled cubic Bezier edge polylines,
 * hit-testing, and viewport fit transforms without any SWT dependency.
 */
public final class GraphLayoutCore {

    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 2.0;

    private static final double MIN_NODE_WIDTH = 170.0;
    private static final double MAX_NODE_WIDTH = 300.0;
    private static final double SINGLE_LINE_HEIGHT = 52.0;
    private static final double TWO_LINE_HEIGHT = 70.0;
    private static final double COL_GAP = 110.0;
    private static final double ROW_GAP = 32.0;
    private static final int BEZIER_STEPS = 16;

    /**
     * Positioned graph node in world coordinates.
     */
    public record LayoutNode(ViewSpec.GraphNode node, double x, double y, double width, double height) {}

    /**
     * Positioned directed edge represented as a sampled cubic Bezier polyline
     * {@code [x0, y0, x1, y1, ...]}.
     */
    public record LayoutEdge(ViewSpec.GraphEdge edge, double[] points) {
        public LayoutEdge {
            points = points == null ? new double[0] : points;
        }
    }

    /**
     * Complete world-space graph layout with bounding box.
     */
    public record GraphLayout(
            List<LayoutNode> nodes,
            List<LayoutEdge> edges,
            double minX,
            double minY,
            double maxX,
            double maxY) {
        public GraphLayout {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /**
     * Screen mapping transform: {@code screenCoord = worldCoord * zoom + pan}.
     */
    public record ViewportTransform(double zoom, double panX, double panY) {}

    private record NodeSize(ViewSpec.GraphNode node, double width, double height) {}

    private GraphLayoutCore() {}

    /**
     * Computes deterministic world-space coordinates for {@code spec}.
     */
    public static GraphLayout layout(ViewSpec.GraphSpec spec) {
        if (spec == null || spec.nodes().isEmpty()) {
            return new GraphLayout(List.of(), List.of(), 0.0, 0.0, 0.0, 0.0);
        }

        List<ViewSpec.GraphNode> rawNodes = spec.nodes();
        List<ViewSpec.GraphEdge> rawEdges = spec.edges();

        List<LayoutNode> positionedNodes = "layered".equals(spec.layout())
            ? layoutLayered(rawNodes, rawEdges)
            : layoutFocus(rawNodes);

        Map<String, LayoutNode> byId = new HashMap<>(positionedNodes.size() * 2);
        for (LayoutNode ln : positionedNodes) {
            byId.put(ln.node().id(), ln);
        }

        List<LayoutEdge> positionedEdges = new ArrayList<>(rawEdges.size());
        for (ViewSpec.GraphEdge edge : rawEdges) {
            LayoutNode src = byId.get(edge.from());
            LayoutNode dst = byId.get(edge.to());
            if (src == null || dst == null) {
                continue;
            }
            double[] points = sampleBezierEdge(src, dst);
            positionedEdges.add(new LayoutEdge(edge, points));
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (LayoutNode ln : positionedNodes) {
            minX = Math.min(minX, ln.x());
            minY = Math.min(minY, ln.y());
            maxX = Math.max(maxX, ln.x() + ln.width());
            maxY = Math.max(maxY, ln.y() + ln.height());
        }
        for (LayoutEdge le : positionedEdges) {
            double[] pts = le.points();
            for (int i = 0; i + 1 < pts.length; i += 2) {
                minX = Math.min(minX, pts[i]);
                maxX = Math.max(maxX, pts[i]);
                minY = Math.min(minY, pts[i + 1]);
                maxY = Math.max(maxY, pts[i + 1]);
            }
        }

        if (!Double.isFinite(minX)) {
            minX = 0.0;
            minY = 0.0;
            maxX = 0.0;
            maxY = 0.0;
        }

        return new GraphLayout(positionedNodes, positionedEdges, minX, minY, maxX, maxY);
    }

    /**
     * Returns the {@link ViewSpec.GraphNode} whose bounding box contains {@code (worldX, worldY)},
     * or {@code null} if none matches.
     */
    public static ViewSpec.GraphNode hitTest(GraphLayout layout, double worldX, double worldY) {
        if (layout == null || layout.nodes().isEmpty()) {
            return null;
        }
        List<LayoutNode> nodes = layout.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            LayoutNode ln = nodes.get(i);
            if (worldX >= ln.x()
                    && worldX <= ln.x() + ln.width()
                    && worldY >= ln.y()
                    && worldY <= ln.y() + ln.height()) {
                return ln.node();
            }
        }
        return null;
    }

    /**
     * Computes a {@link ViewportTransform} centering and scaling {@code layout} to fit inside
     * {@code viewportW x viewportH} within {@code [0.25, 2.0]} zoom.
     */
    public static ViewportTransform computeFitTransform(GraphLayout layout, int viewportW, int viewportH) {
        if (viewportW <= 0 || viewportH <= 0) {
            return new ViewportTransform(1.0, 0.0, 0.0);
        }
        if (layout == null || layout.nodes().isEmpty()) {
            return new ViewportTransform(1.0, viewportW / 2.0, viewportH / 2.0);
        }
        double graphW = Math.max(1.0, layout.maxX() - layout.minX());
        double graphH = Math.max(1.0, layout.maxY() - layout.minY());
        double padding = 40.0;
        double availW = Math.max(20.0, viewportW - padding * 2.0);
        double availH = Math.max(20.0, viewportH - padding * 2.0);

        double rawZoom = Math.min(availW / graphW, availH / graphH);
        double zoom = clampZoom(rawZoom);

        double centerX = (layout.minX() + layout.maxX()) / 2.0;
        double centerY = (layout.minY() + layout.maxY()) / 2.0;
        double panX = (viewportW / 2.0) - (centerX * zoom);
        double panY = (viewportH / 2.0) - (centerY * zoom);
        return new ViewportTransform(zoom, panX, panY);
    }

    public static double clampZoom(double zoom) {
        if (Double.isNaN(zoom) || Double.isInfinite(zoom)) {
            return 1.0;
        }
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    private static List<LayoutNode> layoutFocus(List<ViewSpec.GraphNode> rawNodes) {
        TreeMap<Integer, List<NodeSize>> columns = new TreeMap<>();
        for (ViewSpec.GraphNode node : rawNodes) {
            int layer = resolveFocusLayer(node);
            columns.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(measureNode(node));
        }

        double centerWidth = columns.containsKey(0) ? maxColumnWidth(columns.get(0)) : 0.0;
        Map<Integer, Double> columnCenters = new HashMap<>();

        if (columns.containsKey(0)) {
            columnCenters.put(0, 0.0);
        }

        double rightCursor = centerWidth > 0.0 ? (centerWidth / 2.0) + COL_GAP : COL_GAP * 0.5;
        for (Integer layer : columns.keySet()) {
            if (layer > 0) {
                double w = maxColumnWidth(columns.get(layer));
                columnCenters.put(layer, rightCursor + w / 2.0);
                rightCursor += w + COL_GAP;
            }
        }

        double leftCursor = centerWidth > 0.0 ? -(centerWidth / 2.0) - COL_GAP : -COL_GAP * 0.5;
        for (Integer layer : columns.descendingKeySet()) {
            if (layer < 0) {
                double w = maxColumnWidth(columns.get(layer));
                columnCenters.put(layer, leftCursor - w / 2.0);
                leftCursor -= (w + COL_GAP);
            }
        }

        return stackColumns(columns, columnCenters);
    }

    private static List<LayoutNode> layoutLayered(
            List<ViewSpec.GraphNode> rawNodes,
            List<ViewSpec.GraphEdge> rawEdges) {
        boolean allZero = true;
        for (ViewSpec.GraphNode node : rawNodes) {
            if (node.layer() != 0) {
                allZero = false;
                break;
            }
        }

        Map<String, Integer> layerById = allZero
            ? computeTopologicalLayers(rawNodes, rawEdges)
            : explicitLayerMap(rawNodes);

        TreeMap<Integer, List<NodeSize>> columns = new TreeMap<>();
        for (ViewSpec.GraphNode node : rawNodes) {
            int layer = layerById.getOrDefault(node.id(), 0);
            columns.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(measureNode(node));
        }

        Map<Integer, Double> columnCenters = new HashMap<>();
        double cursorX = 0.0;
        for (Map.Entry<Integer, List<NodeSize>> entry : columns.entrySet()) {
            double colWidth = maxColumnWidth(entry.getValue());
            columnCenters.put(entry.getKey(), cursorX + colWidth / 2.0);
            cursorX += colWidth + COL_GAP;
        }

        return stackColumns(columns, columnCenters);
    }

    private static List<LayoutNode> stackColumns(
            TreeMap<Integer, List<NodeSize>> columns,
            Map<Integer, Double> columnCenters) {
        List<LayoutNode> positioned = new ArrayList<>();
        for (Map.Entry<Integer, List<NodeSize>> entry : columns.entrySet()) {
            double centerX = columnCenters.get(entry.getKey());
            List<NodeSize> items = entry.getValue();
            double totalHeight = 0.0;
            for (int i = 0; i < items.size(); i++) {
                totalHeight += items.get(i).height();
                if (i + 1 < items.size()) {
                    totalHeight += ROW_GAP;
                }
            }
            double y = -totalHeight / 2.0;
            for (NodeSize item : items) {
                double x = centerX - (item.width() / 2.0);
                positioned.add(new LayoutNode(item.node(), x, y, item.width(), item.height()));
                y += item.height() + ROW_GAP;
            }
        }
        return positioned;
    }

    private static int resolveFocusLayer(ViewSpec.GraphNode node) {
        if (node.layer() != 0) {
            return node.layer();
        }
        String group = node.group();
        if ("left".equals(group)) {
            return -1;
        }
        if ("right".equals(group)) {
            return 1;
        }
        return 0;
    }

    private static Map<String, Integer> explicitLayerMap(List<ViewSpec.GraphNode> nodes) {
        Map<String, Integer> layers = new LinkedHashMap<>(nodes.size() * 2);
        for (ViewSpec.GraphNode node : nodes) {
            layers.put(node.id(), node.layer());
        }
        return layers;
    }

    private static Map<String, Integer> computeTopologicalLayers(
            List<ViewSpec.GraphNode> nodes,
            List<ViewSpec.GraphEdge> edges) {
        Map<String, Integer> depthMap = new LinkedHashMap<>(nodes.size() * 2);
        Map<String, Integer> inDegree = new LinkedHashMap<>(nodes.size() * 2);
        Map<String, List<String>> outgoing = new LinkedHashMap<>(nodes.size() * 2);

        for (ViewSpec.GraphNode node : nodes) {
            depthMap.put(node.id(), 0);
            inDegree.put(node.id(), 0);
            outgoing.put(node.id(), new ArrayList<>());
        }

        for (ViewSpec.GraphEdge edge : edges) {
            String from = edge.from();
            String to = edge.to();
            if (!from.equals(to) && outgoing.containsKey(from) && inDegree.containsKey(to)) {
                outgoing.get(from).add(to);
                inDegree.put(to, inDegree.get(to) + 1);
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>(nodes.size() * 2);
        for (ViewSpec.GraphNode node : nodes) {
            if (inDegree.get(node.id()) == 0) {
                queue.add(node.id());
                visited.add(node.id());
            }
        }

        while (visited.size() < depthMap.size()) {
            if (queue.isEmpty()) {
                boolean seeded = false;
                for (ViewSpec.GraphNode node : nodes) {
                    if (!visited.contains(node.id())) {
                        queue.add(node.id());
                        visited.add(node.id());
                        seeded = true;
                        break;
                    }
                }
                if (!seeded) {
                    break;
                }
            }
            while (!queue.isEmpty()) {
                String u = queue.poll();
                int uDepth = depthMap.get(u);
                for (String v : outgoing.get(u)) {
                    if (!visited.contains(v) && uDepth + 1 > depthMap.get(v)) {
                        depthMap.put(v, uDepth + 1);
                    }
                    int remaining = inDegree.get(v) - 1;
                    inDegree.put(v, remaining);
                    if (remaining <= 0 && !visited.contains(v)) {
                        visited.add(v);
                        queue.add(v);
                    }
                }
            }
        }

        return depthMap;
    }

    private static NodeSize measureNode(ViewSpec.GraphNode node) {
        String label = node.label() == null ? "" : node.label();
        String sublabel = node.sublabel() == null ? "" : node.sublabel();
        String badge = node.badge() == null ? "" : node.badge();

        double badgePad = badge.isBlank() ? 0.0 : (badge.length() * 7.2 + 24.0);
        double labelW = label.length() * 8.0 + badgePad + 32.0;
        double subW = sublabel.length() * 6.8 + 32.0;
        double minW = "center".equals(node.group()) ? 190.0 : MIN_NODE_WIDTH;
        double width = Math.max(minW, Math.min(MAX_NODE_WIDTH, Math.max(labelW, subW)));
        double height = sublabel.isBlank() ? SINGLE_LINE_HEIGHT : TWO_LINE_HEIGHT;
        return new NodeSize(node, width, height);
    }

    private static double maxColumnWidth(List<NodeSize> items) {
        double maxW = MIN_NODE_WIDTH;
        for (NodeSize item : items) {
            maxW = Math.max(maxW, item.width());
        }
        return maxW;
    }

    private static double[] sampleBezierEdge(LayoutNode src, LayoutNode dst) {
        double x0;
        double y0;
        double c1x;
        double c1y;
        double c2x;
        double c2y;
        double x3;
        double y3;

        if (src == dst || src.node().id().equals(dst.node().id())) {
            x0 = src.x() + src.width() * 0.35;
            y0 = src.y();
            x3 = src.x() + src.width() * 0.65;
            y3 = src.y();
            c1x = src.x() + src.width() * 0.15;
            c1y = src.y() - 56.0;
            c2x = src.x() + src.width() * 0.85;
            c2y = src.y() - 56.0;
        } else {
            double srcCx = src.x() + src.width() / 2.0;
            double srcCy = src.y() + src.height() / 2.0;
            double dstCx = dst.x() + dst.width() / 2.0;
            double dstCy = dst.y() + dst.height() / 2.0;

            if (Math.abs(srcCx - dstCx) < 1.0) {
                boolean downward = srcCy <= dstCy;
                x0 = srcCx;
                y0 = downward ? (src.y() + src.height()) : src.y();
                x3 = dstCx;
                y3 = downward ? dst.y() : (dst.y() + dst.height());
                double bow = Math.max(36.0, Math.abs(y3 - y0) * 0.35);
                c1x = x0 + bow;
                c1y = y0 + (y3 - y0) * 0.33;
                c2x = x3 + bow;
                c2y = y0 + (y3 - y0) * 0.67;
            } else if (srcCx < dstCx) {
                x0 = src.x() + src.width();
                y0 = srcCy;
                x3 = dst.x();
                y3 = dstCy;
                double dx = Math.max(45.0, Math.abs(x3 - x0) * 0.5);
                c1x = x0 + dx;
                c1y = y0;
                c2x = x3 - dx;
                c2y = y3;
            } else {
                x0 = src.x();
                y0 = srcCy;
                x3 = dst.x() + dst.width();
                y3 = dstCy;
                double dx = Math.max(45.0, Math.abs(x0 - x3) * 0.5);
                c1x = x0 - dx;
                c1y = y0;
                c2x = x3 + dx;
                c2y = y3;
            }
        }

        double[] pts = new double[(BEZIER_STEPS + 1) * 2];
        for (int i = 0; i <= BEZIER_STEPS; i++) {
            double t = (double) i / BEZIER_STEPS;
            double u = 1.0 - t;
            double uu = u * u;
            double tt = t * t;
            double uuu = uu * u;
            double ttt = tt * t;
            double bx = uuu * x0 + 3.0 * uu * t * c1x + 3.0 * u * tt * c2x + ttt * x3;
            double by = uuu * y0 + 3.0 * uu * t * c1y + 3.0 * u * tt * c2y + ttt * y3;
            pts[i * 2] = bx;
            pts[i * 2 + 1] = by;
        }
        return pts;
    }
}
