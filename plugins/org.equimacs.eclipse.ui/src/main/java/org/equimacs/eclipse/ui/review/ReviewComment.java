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
    boolean resolved
) {
    public record Reply(String author, String text, long timestamp) {}

    public ReviewComment withReply(Reply reply) {
        List<Reply> next = new ArrayList<>(replies);
        next.add(reply);
        return new ReviewComment(id, file, line, author, text, timestamp, next, resolved);
    }

    public ReviewComment withResolved(boolean r) {
        return new ReviewComment(id, file, line, author, text, timestamp, replies, r);
    }
}
