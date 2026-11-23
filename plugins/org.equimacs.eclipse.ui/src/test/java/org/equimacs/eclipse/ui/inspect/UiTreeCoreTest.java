package org.equimacs.eclipse.ui.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.equimacs.eclipse.ui.inspect.UiNode.UiBounds;
import org.equimacs.eclipse.ui.inspect.UiNode.UiSelector;
import org.equimacs.eclipse.ui.inspect.UiNode.UiTreeSnapshot;
import org.equimacs.eclipse.ui.inspect.UiNode.WorkbenchPartSummary;
import org.equimacs.eclipse.ui.inspect.UiTreeCore.ResolveResult;
import org.junit.jupiter.api.Test;

class UiTreeCoreTest {

    @Test
    void clampMaxDepthDefaultsAndCapsWithinBounds() {
        assertEquals(8, UiTreeCore.clampMaxDepth(0));
        assertEquals(8, UiTreeCore.clampMaxDepth(-5));
        assertEquals(1, UiTreeCore.clampMaxDepth(1));
        assertEquals(16, UiTreeCore.clampMaxDepth(16));
        assertEquals(32, UiTreeCore.clampMaxDepth(32));
        assertEquals(32, UiTreeCore.clampMaxDepth(99));
    }

    @Test
    void filterTreeSupportsTextTypeCombinedAndPreservesAncestors() {
        UiNode okButton = node("w_4", "Shell[0]/Composite[0]/Button[0]", "Button", "OK", null, null, null, null, List.of(), List.of());
        UiNode cancelButton = node("w_5", "Shell[0]/Composite[0]/Button[1]", "Button", "Cancel", "Abort dialog", null, null, null, List.of(), List.of());
        UiNode searchText = node("w_6", "Shell[0]/Composite[0]/Text[0]", "Text", "", "Filter items", "Type filter text", "org.example.search", null, List.of(), List.of());
        UiNode treeWidget = node("w_7", "Shell[0]/Composite[0]/Tree[0]", "Tree", null, null, null, null, null, List.of("AlphaProject", "BetaLib"), List.of());
        UiNode panel = node("w_2", "Shell[0]/Composite[0]", "Composite", null, null, null, null, null, List.of(), List.of(okButton, cancelButton, searchText, treeWidget));
        UiNode shell = node("w_1", "Shell[0]", "Shell", "Preferences", null, null, null, null, List.of(), List.of(panel));
        List<UiNode> roots = List.of(shell);

        assertSame(roots, UiTreeCore.filterTree(roots, null, "  "));

        List<UiNode> byType = UiTreeCore.filterTree(roots, null, "button");
        assertEquals(1, byType.size());
        List<UiNode> panelChildren = byType.get(0).children().get(0).children();
        assertEquals(2, panelChildren.size());
        assertEquals("OK", panelChildren.get(0).text());
        assertEquals("Cancel", panelChildren.get(1).text());

        List<UiNode> byTextInItems = UiTreeCore.filterTree(roots, "betalib", null);
        assertEquals(1, byTextInItems.size());
        assertEquals("w_7", byTextInItems.get(0).children().get(0).children().get(0).id());

        List<UiNode> byElementId = UiTreeCore.filterTree(roots, "ORG.EXAMPLE.SEARCH", null);
        assertEquals(1, byElementId.size());
        assertEquals("w_6", byElementId.get(0).children().get(0).children().get(0).id());

        List<UiNode> combined = UiTreeCore.filterTree(roots, "abort", "Button");
        assertEquals(1, combined.size());
        List<UiNode> matchedButtons = combined.get(0).children().get(0).children();
        assertEquals(1, matchedButtons.size());
        assertEquals("w_5", matchedButtons.get(0).id());

        assertTrue(UiTreeCore.filterTree(roots, "abort", "Text").isEmpty());
    }

    @Test
    void resolveNodeHandlesIdExactVsSubstringIndexOutOfRangeAndNotFound() {
        UiNode saveAll = node("w_10", "Shell[0]/Button[0]", "Button", "Save All", null, null, null, null, List.of(), List.of());
        UiNode saveExact = node("w_11", "Shell[0]/Button[1]", "Button", "Save", null, null, null, null, List.of(), List.of());
        UiNode altButton = node("w_12", "Shell[0]/Button[2]", "Button", "Save As...", null, null, null, null, List.of(), List.of());
        UiNode tooltipMatch = node("w_13", "Shell[0]/ToolItem[0]", "ToolItem", "", "Refresh Workspace", null, null, null, List.of(), List.of());
        UiNode root = node("w_1", "Shell[0]", "Shell", "Workspace", null, null, null, null, List.of(), List.of(saveAll, saveExact, altButton, tooltipMatch));
        List<UiNode> roots = List.of(root);

        ResolveResult byId = UiTreeCore.resolveNode(roots, new UiSelector("w_12", "active", "Text", "Ignored", 99));
        ResolveResult.Matched matchedById = assertInstanceOf(ResolveResult.Matched.class, byId);
        assertEquals("Save As...", matchedById.node().text());

        ResolveResult missingId = UiTreeCore.resolveNode(roots, new UiSelector("w_999", "active", null, null, 0));
        assertInstanceOf(ResolveResult.NotFound.class, missingId);

        // Exact match ("Save") takes precedence over substring matches ("Save All", "Save As...").
        List<UiNode> exactPreferred = UiTreeCore.findMatchingNodes(roots, new UiSelector(null, "active", "Button", "save", 0));
        assertEquals(1, exactPreferred.size());
        assertEquals("w_11", exactPreferred.get(0).id());

        ResolveResult exactHit = UiTreeCore.resolveNode(roots, new UiSelector(null, "active", "Button", "save", 0));
        ResolveResult.Matched matchedExact = assertInstanceOf(ResolveResult.Matched.class, exactHit);
        assertEquals("w_11", matchedExact.node().id());

        ResolveResult outOfRangeExact = UiTreeCore.resolveNode(roots, new UiSelector(null, "active", "Button", "save", 1));
        ResolveResult.OutOfRange oor = assertInstanceOf(ResolveResult.OutOfRange.class, outOfRangeExact);
        assertEquals(1, oor.matchCount());
        assertEquals(1, oor.requestedIndex());

        // When no exact match exists, contains matches are collected in depth-first order.
        ResolveResult substringSecond = UiTreeCore.resolveNode(roots, new UiSelector(null, "active", "Button", "Save A", 1));
        ResolveResult.Matched matchedSub = assertInstanceOf(ResolveResult.Matched.class, substringSecond);
        assertEquals("w_12", matchedSub.node().id());

        ResolveResult tooltipHit = UiTreeCore.resolveNode(roots, new UiSelector(null, "active", null, "refresh", 0));
        ResolveResult.Matched matchedTip = assertInstanceOf(ResolveResult.Matched.class, tooltipHit);
        assertEquals("w_13", matchedTip.node().id());

        ResolveResult notFound = UiTreeCore.resolveNode(roots, new UiSelector(null, "active", "Combo", "Missing", 0));
        assertInstanceOf(ResolveResult.NotFound.class, notFound);
    }

    @Test
    void toMapSerializesCompactJsonOmittingEmptyOptionalFields() {
        UiNode minimalChild = new UiNode(
            "w_2",
            "Shell[0]/Label[0]",
            "Label",
            "Ready",
            null,
            "",
            null,
            true,
            true,
            false,
            null,
            new UiBounds(10, 20, 100, 24),
            List.of(),
            List.of()
        );
        UiNode root = new UiNode(
            "w_1",
            "Shell[0]",
            "Shell",
            "Main",
            "Main Window",
            null,
            "org.eclipse.ui.window",
            true,
            true,
            true,
            Boolean.TRUE,
            new UiBounds(0, 0, 800, 600),
            List.of("ItemA"),
            List.of(minimalChild)
        );
        UiTreeSnapshot snapshot = new UiTreeSnapshot(
            "active",
            "org.eclipse.jdt.ui.JavaPerspective",
            "Main",
            List.of(new WorkbenchPartSummary("view", "org.eclipse.ui.views.ProblemView", null, "Problems", true, true, false)),
            List.of(new WorkbenchPartSummary("editor", "org.eclipse.jdt.ui.CompilationUnitEditor", null, "App.java", false, true, true)),
            List.of(root),
            2,
            false
        );

        Map<String, Object> map = UiTreeCore.toMap(snapshot);
        assertEquals("active", map.get("root"));
        assertEquals("org.eclipse.jdt.ui.JavaPerspective", map.get("perspectiveId"));
        assertEquals("Main", map.get("activeShellTitle"));
        assertEquals(2, map.get("totalNodesVisited"));
        assertEquals(false, map.get("truncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rootMaps = (List<Map<String, Object>>) map.get("roots");
        Map<String, Object> rootMap = rootMaps.get(0);
        assertEquals("Main Window", rootMap.get("tooltip"));
        assertEquals("org.eclipse.ui.window", rootMap.get("elementId"));
        assertEquals(Boolean.TRUE, rootMap.get("selected"));
        assertEquals(List.of("ItemA"), rootMap.get("items"));
        assertFalse(rootMap.containsKey("message"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> childMaps = (List<Map<String, Object>>) rootMap.get("children");
        Map<String, Object> childMap = childMaps.get(0);
        assertEquals("Ready", childMap.get("text"));
        assertFalse(childMap.containsKey("tooltip"));
        assertFalse(childMap.containsKey("message"));
        assertFalse(childMap.containsKey("elementId"));
        assertFalse(childMap.containsKey("selected"));
        assertFalse(childMap.containsKey("items"));
        assertFalse(childMap.containsKey("children"));
    }

    private static UiNode node(
        String id,
        String path,
        String type,
        String text,
        String tooltip,
        String message,
        String elementId,
        Boolean selected,
        List<String> items,
        List<UiNode> children
    ) {
        return new UiNode(
            id,
            path,
            type,
            text,
            tooltip,
            message,
            elementId,
            true,
            true,
            false,
            selected,
            new UiBounds(0, 0, 50, 20),
            items,
            children
        );
    }
}
