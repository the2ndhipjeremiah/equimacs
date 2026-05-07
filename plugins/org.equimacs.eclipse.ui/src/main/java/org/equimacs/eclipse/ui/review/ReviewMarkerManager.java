package org.equimacs.eclipse.ui.review;

import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.IDocument;

public final class ReviewMarkerManager {

    static final String MARKER_TYPE = "org.equimacs.eclipse.ui.reviewComment";

    private static final Map<String, IMarker> registry = new ConcurrentHashMap<>();

    private ReviewMarkerManager() {}

    public static void add(ReviewComment comment) {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IResource resource = root.findMember(new Path(comment.file()));
            if (resource == null || !resource.exists()) return;

            IMarker marker = findByCommentId(comment.id());
            if (marker == null || !marker.exists()) {
                marker = resource.createMarker(MARKER_TYPE);
            }
            updateMarker(marker, resource, comment);

            registry.put(comment.id(), marker);
        } catch (CoreException e) {
            // Best-effort; marker is decorative
        }
    }

    public static void reconcile(List<ReviewComment> comments) {
        try {
            Set<String> unresolvedIds = new HashSet<>();
            Map<String, ReviewComment> unresolvedById = new HashMap<>();
            for (ReviewComment comment : comments) {
                if (!comment.resolved()) {
                    unresolvedIds.add(comment.id());
                    unresolvedById.put(comment.id(), comment);
                }
            }

            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IMarker[] markers = root.findMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers) {
                String commentId = marker.getAttribute("equimacs.commentId", null);
                if (commentId == null || !unresolvedIds.contains(commentId)) {
                    marker.delete();
                    continue;
                }
                registry.put(commentId, marker);
                ReviewComment comment = unresolvedById.get(commentId);
                IResource resource = root.findMember(new Path(comment.file()));
                if (resource != null && resource.exists()) {
                    updateMarker(marker, resource, comment);
                }
            }

            for (ReviewComment comment : unresolvedById.values()) {
                IMarker marker = registry.get(comment.id());
                if (marker == null || !marker.exists()) {
                    add(comment);
                }
            }
        } catch (CoreException e) {
            // Best-effort; review data remains in ReviewStore
        }
    }

    private static void updateMarker(IMarker marker, IResource resource, ReviewComment comment) throws CoreException {
        marker.setAttribute(IMarker.LINE_NUMBER, comment.line());
        marker.setAttribute(IMarker.MESSAGE, comment.author() + ": " + comment.text());
        marker.setAttribute("equimacs.commentId", comment.id());
        setCharRange(marker, resource, comment.line());
    }

    private static void setCharRange(IMarker marker, IResource resource, int line) {
        ITextFileBufferManager mgr = FileBuffers.getTextFileBufferManager();
        org.eclipse.core.runtime.IPath path = resource.getFullPath();
        try {
            mgr.connect(path, LocationKind.IFILE, null);
            try {
                ITextFileBuffer buf = mgr.getTextFileBuffer(path, LocationKind.IFILE);
                IDocument doc = buf.getDocument();
                int lineIndex = line - 1;
                if (lineIndex >= 0 && lineIndex < doc.getNumberOfLines()) {
                    int start = doc.getLineOffset(lineIndex);
                    int length = doc.getLineLength(lineIndex);
                    marker.setAttribute(IMarker.CHAR_START, start);
                    marker.setAttribute(IMarker.CHAR_END, start + length);
                }
            } finally {
                mgr.disconnect(path, LocationKind.IFILE, null);
            }
        } catch (Exception e) {
            // Without char range, gutter hover still works; highlighting won't
        }
    }

    public static void remove(String commentId) {
        IMarker marker = registry.remove(commentId);
        if (marker == null) {
            marker = findByCommentId(commentId);
        }
        if (marker == null) return;
        try {
            marker.delete();
        } catch (CoreException e) {
            // Best-effort
        }
    }

    public static List<Map<String, Object>> diagnostics(String file) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IMarker[] markers = root.findMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers) {
                IResource resource = marker.getResource();
                String resourcePath = resource == null ? "" : resource.getFullPath().toString();
                if (file != null && !file.isBlank() && !file.equals(resourcePath)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resource", resourcePath);
                item.put("commentId", marker.getAttribute("equimacs.commentId", null));
                item.put("line", marker.getAttribute(IMarker.LINE_NUMBER, -1));
                item.put("charStart", marker.getAttribute(IMarker.CHAR_START, -1));
                item.put("charEnd", marker.getAttribute(IMarker.CHAR_END, -1));
                item.put("message", marker.getAttribute(IMarker.MESSAGE, ""));
                item.put("exists", marker.exists());
                result.add(item);
            }
        } catch (CoreException e) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("error", e.getMessage());
            result.add(item);
        }
        return result;
    }

    private static IMarker findByCommentId(String commentId) {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IMarker[] markers = root.findMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers) {
                if (commentId.equals(marker.getAttribute("equimacs.commentId", null))) {
                    return marker;
                }
            }
        } catch (CoreException e) {
            // Best-effort
        }
        return null;
    }

    public static void disposeAll() {
        registry.clear();
    }
}
