package org.equimacs.eclipse.ui.review;

import java.util.ArrayList;
import java.util.List;

public record ReviewComment(
    String id,
    String file,
    int line,
    String author,
    String text,
    long timestamp,
    List<Reply> replies,
    boolean resolved,
    LineAnchor anchor
) {
    public record Reply(String author, String text, long timestamp) {}

    public ReviewComment {
        replies = replies == null ? List.of() : List.copyOf(replies);
    }

    public ReviewComment(
        String id,
        String file,
        int line,
        String author,
        String text,
        long timestamp,
        List<Reply> replies,
        boolean resolved
    ) {
        this(id, file, line, author, text, timestamp, replies, resolved, null);
    }

    public ReviewComment withReply(Reply reply) {
        List<Reply> next = new ArrayList<>(replies);
        next.add(reply);
        return new ReviewComment(id, file, line, author, text, timestamp, next, resolved, anchor);
    }

    public ReviewComment withResolved(boolean r) {
        return new ReviewComment(id, file, line, author, text, timestamp, replies, r, anchor);
    }

    public ReviewComment withLineAndAnchor(int nextLine, LineAnchor nextAnchor) {
        return new ReviewComment(id, file, nextLine, author, text, timestamp, replies, resolved, nextAnchor);
    }
}
