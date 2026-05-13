package io.github.samzhu.skillshub.shared.api;

/**
 * S156c — Request comment id 不存在或已 soft-deleted。GlobalExceptionHandler →
 * 404 + {@code error: "comment_not_found"}。對齊 RequestNotFoundException naming convention。
 */
public class CommentNotFoundException extends RuntimeException {
    public CommentNotFoundException(String commentId) {
        super("comment_not_found: " + commentId);
    }
}
