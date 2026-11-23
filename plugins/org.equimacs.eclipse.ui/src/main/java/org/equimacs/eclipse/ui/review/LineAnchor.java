package org.equimacs.eclipse.ui.review;

/**
 * Anchors a review comment to the content and immediate line context around its target line.
 */
public record LineAnchor(
    String lineText,
    String lineHash,
    String contextBefore,
    String contextAfter,
    String status
) {
    public static final String STATUS_EXACT = "exact";
    public static final String STATUS_RELOCATED = "relocated";
    public static final String STATUS_STALE = "stale";

    public LineAnchor withStatus(String nextStatus) {
        return new LineAnchor(lineText, lineHash, contextBefore, contextAfter, nextStatus);
    }
}
