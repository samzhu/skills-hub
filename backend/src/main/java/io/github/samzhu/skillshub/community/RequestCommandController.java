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

import io.github.samzhu.skillshub.shared.api.MarkdownSafeDeserializer;
import io.github.samzhu.skillshub.shared.api.PlainTextDeserializer;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S096g2 → S156c — Request command endpoints。
 *
 * <p>S156c voting-board pivot：移除 {@code POST /{id}/claim} / {@code DELETE /{id}/claim} /
 * {@code POST /{id}/fulfill} 三 endpoint（state machine 已拆）。剩餘 commands：
 * <ul>
 *   <li>{@code POST /api/v1/requests} — create</li>
 *   <li>{@code POST /api/v1/requests/{id}/vote} — toggle vote</li>
 *   <li>{@code DELETE /api/v1/requests/{id}} — delete own request</li>
 * </ul>
 *
 * <p>Comment add/delete endpoints 由 T02 新增 {@code CommentController}。
 */
@RestController
@RequestMapping("/api/v1/requests")
class RequestCommandController {

    private final RequestService service;
    private final RequestVoteService voteService;
    private final CurrentUserProvider users;

    RequestCommandController(RequestService service, RequestVoteService voteService, CurrentUserProvider users) {
        this.service = service;
        this.voteService = voteService;
        this.users = users;
    }

    /** AC-1 — 建立新 request；reporter 從 CurrentUserProvider 抽 sub。 */
    @PostMapping
    ResponseEntity<Map<String, String>> create(@RequestBody CreateRequestBody body) {
        var requesterId = users.current().userId();
        var id = service.createRequest(body.title(), body.description(), requesterId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    /** AC-5/AC-6 — vote toggle；同 user 重 POST 切 on/off。 */
    @PostMapping("/{requestId}/vote")
    ResponseEntity<RequestVoteService.VoteResult> toggleVote(@PathVariable String requestId) {
        var userId = users.current().userId();
        return ResponseEntity.ok(voteService.toggle(requestId, userId));
    }

    /** S156c AC-7 — 刪除 own request（無 status guard；CASCADE 刪 comments via V22 FK）。 */
    @DeleteMapping("/{requestId}")
    ResponseEntity<Void> delete(@PathVariable String requestId) {
        var userId = users.current().userId();
        service.deleteRequest(requestId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * S161：{@code title} 為短標題、純文字 — 走 {@link PlainTextDeserializer} silently
     * strip HTML markup。{@code description} 屬「多行 markdown 安全 subset」— 走
     * {@link MarkdownSafeDeserializer} 用 OWASP {@code HtmlPolicyBuilder} allowlist
     * 保留 {@code <p>}/{@code <strong>}/{@code <a href>} 等合法 markdown 元素，
     * silently strip {@code <script>}/{@code <iframe>}/event handlers/{@code javascript:} URL。
     */
    record CreateRequestBody(
            @JsonDeserialize(using = PlainTextDeserializer.class) String title,
            @JsonDeserialize(using = MarkdownSafeDeserializer.class) String description) {}
}
