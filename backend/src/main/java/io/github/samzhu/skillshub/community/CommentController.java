package io.github.samzhu.skillshub.community;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.github.samzhu.skillshub.shared.api.PlainTextDeserializer;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S156c — Request comment endpoints。
 *
 * <ul>
 *   <li>{@code POST /api/v1/requests/{id}/comments} — 新增 comment（logged-in only）</li>
 *   <li>{@code DELETE /api/v1/requests/{id}/comments/{cid}} — soft delete（comment author only）</li>
 * </ul>
 *
 * <p>對齊 RequestCommandController 既有 {@code CurrentUserProvider} 注入 + S161
 * {@link PlainTextDeserializer} sanitize comment {@code content}。
 */
@RestController
@RequestMapping("/api/v1/requests")
class CommentController {

    private final CommentService service;
    private final CurrentUserProvider users;

    CommentController(CommentService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    /** S156c AC-5 — 新增 comment；body content 走 S161 plain-text sanitize（strip HTML markup）。 */
    @PostMapping("/{requestId}/comments")
    ResponseEntity<Map<String, String>> add(
            @PathVariable String requestId,
            @RequestBody AddCommentBody body) {
        var authorId = users.current().userId();
        var commentId = service.addComment(requestId, authorId, body.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", commentId));
    }

    /** S156c AC-6 — soft delete comment；非 author → 403；不存在 / 已刪 → 404。 */
    @DeleteMapping("/{requestId}/comments/{commentId}")
    ResponseEntity<Void> delete(
            @PathVariable String requestId,
            @PathVariable String commentId) {
        var userId = users.current().userId();
        service.deleteComment(requestId, commentId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * S156c — comment body shape；{@code content} 走 {@link PlainTextDeserializer} silently
     * strip HTML markup（mirror RequestCommandController create body title pattern）。
     * Comment 不走 markdown — spec §1 非目標「不做 comment markdown rendering」。
     */
    record AddCommentBody(
            @JsonDeserialize(using = PlainTextDeserializer.class) String content) {}
}
