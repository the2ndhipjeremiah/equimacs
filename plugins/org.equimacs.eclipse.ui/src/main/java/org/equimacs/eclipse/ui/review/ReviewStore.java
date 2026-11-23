package org.equimacs.eclipse.ui.review;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Thin Eclipse workspace adapter delegating review persistence and anchoring to ReviewStoreCore.
 */
public class ReviewStore {

    private static final ReviewStoreCore CORE =
        new ReviewStoreCore(ReviewStore::resolveStorePath, ReviewStore::loadFileLines);

    private static Path resolveStorePath() {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (location == null) {
            throw new IllegalStateException("Workspace root location unavailable");
        }
        return location.toFile().toPath().resolve(".equimacs-review/comments.json");
    }

    private static List<String> loadFileLines(String wsPath) {
        if (wsPath == null || wsPath.isBlank()) {
            return null;
        }
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            org.eclipse.core.runtime.Path eclipsePath = new org.eclipse.core.runtime.Path(wsPath);
            IResource member = root.findMember(eclipsePath);
            if (!(member instanceof IFile file)) {
                return null;
            }
            ITextFileBufferManager mgr = FileBuffers.getTextFileBufferManager();
            if (mgr != null) {
                ITextFileBuffer buf = mgr.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
                if (buf != null && buf.getDocument() != null) {
                    return readLines(new StringReader(buf.getDocument().get()));
                }
            }
            if (!file.exists()) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
                return readLines(reader);
            } catch (Exception outOfSync) {
                IPath location = file.getLocation();
                if (location != null) {
                    Path osPath = location.toFile().toPath();
                    if (Files.isRegularFile(osPath)) {
                        return Files.readAllLines(osPath, StandardCharsets.UTF_8);
                    }
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readLines(Reader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    public ReviewComment addComment(String file, int line, String author, String text) throws IOException {
        return CORE.addComment(file, line, author, text);
    }

    public ReviewComment addReply(String commentId, String author, String text) throws IOException {
        return CORE.addReply(commentId, author, text);
    }

    public ReviewComment resolve(String commentId) throws IOException {
        return CORE.resolve(commentId);
    }

    public ReviewComment setResolved(String commentId, boolean resolved) throws IOException {
        return CORE.setResolved(commentId, resolved);
    }

    public List<ReviewComment> list(String file) throws IOException {
        return CORE.list(file);
    }

    public ReviewStoreCore.ReanchorReport reanchorAll() throws IOException {
        return CORE.reanchorAll();
    }

    public ReviewStoreCore.ReanchorReport reanchorFile(String file) throws IOException {
        return CORE.reanchorFile(file, loadFileLines(file));
    }
}
