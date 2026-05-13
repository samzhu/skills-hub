package io.github.samzhu.skillshub.community;

import java.lang.invoke.MethodHandles;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.CommentNotFoundException;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S156c — Request comment 應用服務（ADR-002 canonical pattern：3-line orchestration）。
 *
 * <p>addComment：load request → factory create comment → request.addComment 充血 registerEvent
 * → 同 TX 寫 comment row + repo.save(request) 觸發 outbox publish；commentId 由
 * {@link RequestComment#create} 工廠生成並透傳至 {@link Request#addComment} 確保 event
 * 與 row 共用同一 id（給下游 audit dedupKey 用，per spec §2.8）。
 *
 * <p>deleteComment：owner-only soft delete；非 author 拋 {@link AccessDeniedException}
 * （Spring Security handler → 403）；已 soft-deleted 視為不存在 → {@link CommentNotFoundException}
 * → 404。對齊 spec AC-6。
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final RequestRepository requestRepo;
    private final RequestCommentRepository commentRepo;

    public CommentService(RequestRepository requestRepo, RequestCommentRepository commentRepo) {
        this.requestRepo = requestRepo;
        this.commentRepo = commentRepo;
    }

    /**
     * S156c AC-5 — 對 request 新增 comment；event + row 同 TX 寫入。
     *
     * @return 新 comment 的 id（UUID v4）
     */
    @Transactional
    public String addComment(String requestId, String authorId, String content) {
        var request = requestRepo.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        // 先 factory create comment 取得 id，再透傳至 aggregate registerEvent —
        // 確保 event payload 的 commentId 與 row 的 id 是同一 UUID（給 audit dedupKey 用）
        var comment = RequestComment.create(requestId, authorId, content);
        request.addComment(comment.getId(), authorId, content);
        commentRepo.save(comment);
        requestRepo.save(request); // triggers @DomainEvents → Modulith outbox publish (同 TX)
        log.info("Comment added: requestId={}, commentId={}, authorId={}",
                requestId, comment.getId(), authorId);
        return comment.getId();
    }

    /**
     * S156c AC-6 — Soft delete comment（owner only）。
     *
     * <p>404 case：comment 不存在 / 已 soft-deleted（已 deleted_at 不 null 視為不存在）。
     * 403 case：非 comment author 嘗試刪。
     */
    @Transactional
    public void deleteComment(String requestId, String commentId, String userId) {
        var comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException(commentId));
        // 已 soft-deleted 視為 404 — 防止「我看到別人 Delete 按鈕→點下去發現 403/200」UX 漏洞
        if (comment.isDeleted()) {
            throw new CommentNotFoundException(commentId);
        }
        // 對齊 spec AC-6 — owner 比對失敗拋 Spring Security AccessDeniedException → handler 路由 403
        if (!userId.equals(comment.getAuthorId())) {
            throw new AccessDeniedException("not_comment_author: only comment author can delete");
        }
        // 防呆 — 路徑參數 requestId 應對齊 comment.requestId；避免 cross-request 刪
        if (!requestId.equals(comment.getRequestId())) {
            throw new CommentNotFoundException(commentId);
        }
        comment.softDelete();
        commentRepo.save(comment);
        log.info("Comment soft-deleted: requestId={}, commentId={}, by={}",
                requestId, commentId, userId);
    }

    /** S156c AC-4 — detail page 取 comments（過濾 soft-deleted，ASC by createdAt）。 */
    public List<RequestComment> listByRequest(String requestId) {
        return commentRepo.findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc(requestId);
    }
}
