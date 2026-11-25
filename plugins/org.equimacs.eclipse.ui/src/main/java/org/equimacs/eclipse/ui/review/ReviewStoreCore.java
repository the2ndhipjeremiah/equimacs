package org.equimacs.eclipse.ui.review;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Pure-Java persistence and line-anchor maintenance for review comments.
 */
public class ReviewStoreCore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ReviewComment>>() {}.getType();

    public record ReanchorReport(int unchanged, int relocated, int stale, boolean modified) {}

    private final Supplier<Path> storePathSupplier;
    private final Function<String, List<String>> fileLineLoader;

    public ReviewStoreCore(
        Supplier<Path> storePathSupplier,
        Function<String, List<String>> fileLineLoader
    ) {
        this.storePathSupplier = Objects.requireNonNull(storePathSupplier, "storePathSupplier");
        this.fileLineLoader = fileLineLoader != null ? fileLineLoader : f -> null;
    }

    public synchronized ReviewComment addComment(String file, int line, String author, String text) throws IOException {
        List<ReviewComment> comments = load();
        List<String> lines = fileLineLoader.apply(file);
        LineAnchor anchor = LineAnchorMatcher.capture(lines, line);
        ReviewComment comment = new ReviewComment(
            UUID.randomUUID().toString(),
            file,
            line,
            author,
            text,
            System.currentTimeMillis(),
            new ArrayList<>(),
            false,
            anchor
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
        return setResolved(commentId, true);
    }

    public synchronized ReviewComment setResolved(String commentId, boolean resolved) throws IOException {
        List<ReviewComment> comments = load();
        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.id().equals(commentId)) {
                ReviewComment updated = c.withResolved(resolved);
                comments.set(i, updated);
                save(comments);
                return updated;
            }
        }
        throw new IllegalArgumentException("Comment not found: " + commentId);
    }

    public synchronized ReviewComment remove(String commentId) throws IOException {
        List<ReviewComment> comments = load();
        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.id().equals(commentId)) {
                comments.remove(i);
                save(comments);
                return c;
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

    public synchronized ReanchorReport reanchorAll() throws IOException {
        List<ReviewComment> comments = load();
        if (comments.isEmpty()) {
            return new ReanchorReport(0, 0, 0, false);
        }

        Map<String, List<String>> linesByFile = new HashMap<>();
        int unchanged = 0;
        int relocated = 0;
        int stale = 0;
        boolean modified = false;

        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.resolved() || c.anchor() == null) {
                continue;
            }
            String file = c.file();
            if (!linesByFile.containsKey(file)) {
                linesByFile.put(file, fileLineLoader.apply(file));
            }
            List<String> lines = linesByFile.get(file);
            ReanchorOutcome outcome = reanchorComment(comments, i, lines);
            unchanged += outcome.unchanged();
            relocated += outcome.relocated();
            stale += outcome.stale();
            if (outcome.modified()) {
                modified = true;
            }
        }

        if (modified) {
            save(comments);
        }
        return new ReanchorReport(unchanged, relocated, stale, modified);
    }

    public synchronized ReanchorReport reanchorFile(String file, List<String> lines) throws IOException {
        if (file == null || file.isBlank()) {
            return new ReanchorReport(0, 0, 0, false);
        }
        List<ReviewComment> comments = load();
        if (comments.isEmpty()) {
            return new ReanchorReport(0, 0, 0, false);
        }

        int unchanged = 0;
        int relocated = 0;
        int stale = 0;
        boolean modified = false;

        for (int i = 0; i < comments.size(); i++) {
            ReviewComment c = comments.get(i);
            if (c.resolved() || c.anchor() == null || !sameWorkspacePath(file, c.file())) {
                continue;
            }
            ReanchorOutcome outcome = reanchorComment(comments, i, lines);
            unchanged += outcome.unchanged();
            relocated += outcome.relocated();
            stale += outcome.stale();
            if (outcome.modified()) {
                modified = true;
            }
        }

        if (modified) {
            save(comments);
        }
        return new ReanchorReport(unchanged, relocated, stale, modified);
    }

    private record ReanchorOutcome(int unchanged, int relocated, int stale, boolean modified) {}

    private static ReanchorOutcome reanchorComment(
        List<ReviewComment> comments,
        int index,
        List<String> lines
    ) {
        ReviewComment c = comments.get(index);
        LineAnchor currentAnchor = c.anchor();
        LineAnchorMatcher.AnchorResolution resolution = LineAnchorMatcher.resolve(c.line(), currentAnchor, lines);
        return switch (resolution) {
            case LineAnchorMatcher.AnchorResolution.Unchanged u -> {
                // Keep relocated status sticky across subsequent idempotent checks so UI continues showing (moved).
                String nextStatus = LineAnchor.STATUS_RELOCATED.equals(currentAnchor.status())
                    ? LineAnchor.STATUS_RELOCATED
                    : LineAnchor.STATUS_EXACT;
                boolean changed = u.line() != c.line() || !Objects.equals(nextStatus, currentAnchor.status());
                if (changed) {
                    comments.set(index, c.withLineAndAnchor(u.line(), currentAnchor.withStatus(nextStatus)));
                }
                yield new ReanchorOutcome(1, 0, 0, changed);
            }
            case LineAnchorMatcher.AnchorResolution.Relocated r -> {
                String nextStatus = LineAnchor.STATUS_RELOCATED;
                boolean changed = r.newLine() != c.line() || !Objects.equals(nextStatus, currentAnchor.status());
                if (changed) {
                    comments.set(index, c.withLineAndAnchor(r.newLine(), currentAnchor.withStatus(nextStatus)));
                }
                yield new ReanchorOutcome(0, 1, 0, changed);
            }
            case LineAnchorMatcher.AnchorResolution.Stale s -> {
                String nextStatus = LineAnchor.STATUS_STALE;
                boolean changed = s.fallbackLine() != c.line() || !Objects.equals(nextStatus, currentAnchor.status());
                if (changed) {
                    comments.set(index, c.withLineAndAnchor(s.fallbackLine(), currentAnchor.withStatus(nextStatus)));
                }
                yield new ReanchorOutcome(0, 0, 1, changed);
            }
        };
    }

    private static boolean sameWorkspacePath(String left, String right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return stripLeadingSlashes(left).equals(stripLeadingSlashes(right));
    }

    private static String stripLeadingSlashes(String path) {
        String s = path.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private List<ReviewComment> load() throws IOException {
        Path storePath = storePathSupplier.get();
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        String json = Files.readString(storePath, StandardCharsets.UTF_8);
        List<ReviewComment> result = GSON.fromJson(json, LIST_TYPE);
        return result != null ? result : new ArrayList<>();
    }

    private void save(List<ReviewComment> comments) throws IOException {
        Path storePath = storePathSupplier.get();
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
            storePath,
            GSON.toJson(comments),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
