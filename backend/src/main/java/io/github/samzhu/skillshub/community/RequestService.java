package io.github.samzhu.skillshub.community;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S096g2 → S156c — Request 應用服務（3-line orchestration per ADR-002 canonical pattern）。
 *
 * <p>S156c voting-board pivot：移除 {@code claim} / {@code release} / {@code fulfill} 三
 * service method（aggregate 對應 state machine 已拆）；{@code deleteRequest} guard 簡化
 * 為「requester only」（無 status guard，因 status column 拆掉）。Comment 機制由 T02
 * 新增 {@code CommentService}。詳 spec
 * {@code docs/grimo/specs/2026-05-12-S156c-request-voting-board.md} §2.2 / §2.3。
 */
@Service
public class RequestService {

    private final RequestRepository repo;

    public RequestService(RequestRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public String createRequest(String title, String description, String requesterId) {
        var request = Request.create(title, description, requesterId);
        return repo.save(request).getId();
    }

    /** AC-3 — list with optional sort（status param 已隨 V22 移除）。 */
    public List<Request> listRequests(String sort) {
        boolean byCreated = "created".equalsIgnoreCase(sort);
        return byCreated ? repo.findAllOrderByCreatedDesc() : repo.findAllOrderByVotesDesc();
    }

    public Request getRequest(String requestId) {
        return repo.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    /**
     * S156c AC-7 — delete：requester 比對（無 status guard，simplified per voting-board pivot）。
     *
     * <p>非 requester 拋 {@link AccessDeniedException} → Spring Security handler 路由 403
     * （對齊 spec AC-7 期望 status code；原 T01 拋 IllegalStateException 會 mapping 至 409
     * — S156c voting-board pivot 順手對齊）。
     */
    @Transactional
    public void deleteRequest(String requestId, String userId) {
        var request = getRequest(requestId);
        if (!userId.equals(request.getRequesterId())) {
            throw new AccessDeniedException("not_request_requester: only requester can delete");
        }
        repo.deleteById(requestId);
        // 不發 RequestDeletedEvent — 對齊 spec §1 events 不可變原則；
        // events 已由 AuditEventListener 寫入 domain_events 永存（T03），
        // hard delete 只清 read-side row（requests / request_comments via CASCADE）。
    }
}
