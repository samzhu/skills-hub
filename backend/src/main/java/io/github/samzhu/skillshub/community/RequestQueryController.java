package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.UserDisplay;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;

/**
 * S096g2 → S156c — Request query endpoints。
 *
 * <p>S156c voting-board pivot：list response 移除 {@code status} / {@code claimerId} /
 * {@code fulfilledSkillId} 三 field（state machine 已拆）；{@code ?status=} query param
 * 一併移除（per S159a 未列 param 將被 fail-fast 400）。
 *
 * <p>S156c T04：GET /{id} 改回 {@link RequestDetailResponse}（含 {@code comments[]} +
 * {@code canDelete}）— 對齊 spec AC-4 detail page shape。list endpoint 維持精簡（不含
 * comments，省流量；list 場景 UI 也用不到）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/requests?sort=} — list with sort (votes / created)</li>
 *   <li>{@code GET /api/v1/requests/{id}} — detail (含 comments + canDelete)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/requests")
class RequestQueryController {

    private final RequestService service;
    private final CommentService commentService;
    private final CurrentUserProvider users;
    private final UserDisplayService userDisplayService;

    RequestQueryController(RequestService service, CommentService commentService, CurrentUserProvider users,
            UserDisplayService userDisplayService) {
        this.service = service;
        this.commentService = commentService;
        this.users = users;
        this.userDisplayService = userDisplayService;
    }

    @GetMapping
    List<RequestResponse> list(@RequestParam(required = false) String sort) {
        return service.listRequests(sort).stream()
                .map(RequestResponse::from)
                .toList();
    }

    /** S156c AC-4 — detail response 含 comments + canDelete；unauth user 一律 canDelete=false。 */
    @GetMapping("/{requestId}")
    RequestDetailResponse getOne(@PathVariable String requestId) {
        var request = service.getRequest(requestId);
        var commentRows = commentService.listByRequest(requestId);
        var authorDisplays = userDisplayService.resolveAll(commentRows.stream()
                .map(RequestComment::getAuthorId)
                .toList(), false);
        var comments = commentRows.stream()
                .map(comment -> CommentDto.from(comment, authorDisplays.get(comment.getAuthorId())))
                .toList();
        var canDelete = isAuthenticated() && users.current().userId().equals(request.getRequesterId());
        return RequestDetailResponse.from(request, comments, canDelete);
    }

    /**
     * 判斷當前 SecurityContext 是否為已認證 user（非 anonymous）。
     *
     * <p>未認證 caller GET 仍可看 detail（public read），但 {@code canDelete} 須回 false —
     * 否則 LAB fallback 路徑會誤判 anonymous 為 lab user 而 expose 不該有的「刪除」按鈕。
     */
    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** S156c — list 精簡 DTO；無 status / claimer / fulfilled / comments / canDelete field。 */
    record RequestResponse(
            String id,
            String title,
            String description,
            String requesterId,
            long voteCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        static RequestResponse from(Request r) {
            return new RequestResponse(r.getId(), r.getTitle(), r.getDescription(), r.getRequesterId(),
                    r.getVoteCount(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** S156c AC-4 — detail DTO；list response superset（多 comments + canDelete）。 */
    record RequestDetailResponse(
            String id,
            String title,
            String description,
            String requesterId,
            long voteCount,
            Instant createdAt,
            Instant updatedAt,
            List<CommentDto> comments,
            boolean canDelete
    ) {
        static RequestDetailResponse from(Request r, List<CommentDto> comments, boolean canDelete) {
            return new RequestDetailResponse(r.getId(), r.getTitle(), r.getDescription(), r.getRequesterId(),
                    r.getVoteCount(), r.getCreatedAt(), r.getUpdatedAt(), comments, canDelete);
        }
    }

    /** S156c AC-4 — comment 對外 DTO；deletedAt 不外洩（filter 已在 query 層處理）。 */
    record CommentDto(
            String id,
            String authorId,
            String authorDisplayName,
            String authorHandle,
            String content,
            Instant createdAt
    ) {
        static CommentDto from(RequestComment c, UserDisplay authorDisplay) {
            return new CommentDto(c.getId(), c.getAuthorId(),
                    authorDisplay == null ? null : authorDisplay.displayName(),
                    authorDisplay == null ? null : authorDisplay.handle(),
                    c.getContent(), c.getCreatedAt());
        }
    }
}
