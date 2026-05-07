package org.equimacs.eclipse.ui.review;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.core.resources.ResourcesPlugin;

public class ReviewStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ReviewComment>>(){}.getType();

    private Path storePath() {
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        return workspace.resolve(".equimacs-review/comments.json");
    }

    public synchronized ReviewComment addComment(String file, int line, String author, String text) throws IOException {
        List<ReviewComment> comments = load();
        ReviewComment comment = new ReviewComment(
            UUID.randomUUID().toString(),
            file, line, author, text,
            System.currentTimeMillis(),
            new ArrayList<>(),
            false
        );
        comments.add(comment);
        save(comments);
        return comment;
    }

    public synchronized ReviewComment addReply(String commentId, String author, String text) throws IOException {
        List<ReviewComment> comments = load();
        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.id().equals(commentId)) {
                ReviewComment updated = c.withReply(new ReviewComment.Reply(author, text, System.currentTimeMillis()));
                comments.set(i, updated);
                save(comments);
                return updated;
            }
        }
        throw new IllegalArgumentException("Comment not found: " + commentId);
    }

    public synchronized ReviewComment resolve(String commentId) throws IOException {
        List<ReviewComment> comments = load();
        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.id().equals(commentId)) {
                ReviewComment updated = c.withResolved(true);
                comments.set(i, updated);
                save(comments);
                return updated;
            }
        }
        throw new IllegalArgumentException("Comment not found: " + commentId);
    }

    public synchronized List<ReviewComment> list(String file) throws IOException {
        List<ReviewComment> comments = load();
        if (file == null || file.isBlank()) {
            return new ArrayList<>(comments);
        }
        return comments.stream()
            .filter(c -> c.file().equals(file))
            .collect(Collectors.toList());
    }

    private List<ReviewComment> load() throws IOException {
        Path storePath = storePath();
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        String json = Files.readString(storePath);
        List<ReviewComment> result = GSON.fromJson(json, LIST_TYPE);
        return result != null ? result : new ArrayList<>();
    }

    private void save(List<ReviewComment> comments) throws IOException {
        Path storePath = storePath();
        Files.createDirectories(storePath.getParent());
        Files.writeString(storePath, GSON.toJson(comments),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
