package io.github.samzhu.skillshub.community;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;
import io.github.samzhu.skillshub.shared.api.SkillNotPublishableException;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

/**
 * S096g2 — Request 應用服務（3-line orchestration per ADR-002 canonical pattern）。
 */
@Service
public class RequestService {

    private final RequestRepository repo;
    private final SkillRepository skillRepo;
    private final ApplicationEventPublisher events;

    public RequestService(RequestRepository repo, SkillRepository skillRepo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.skillRepo = skillRepo;
        this.events = events;
    }

    @Transactional
    public String createRequest(String title, String description, String requesterId) {
        var request = Request.create(title, description, requesterId);
        return repo.save(request).getId();
    }

    /** AC-3/AC-4 — list with optional sort + status filter。 */
    public List<Request> listRequests(String status, String sort) {
        boolean byCreated = "created".equalsIgnoreCase(sort);
        if (status == null || status.isBlank()) {
            return byCreated
                    ? repo.findAllOrderByCreatedDesc()
                    : repo.findAllOrderByVotesDesc();
        }
        return byCreated
                ? repo.findByStatusOrderByCreatedDesc(status)
                : repo.findByStatusOrderByVotesDesc(status);
    }

    public Request getRequest(String requestId) {
        return repo.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    @Transactional
    public Request claim(String requestId, String userId) {
        var request = getRequest(requestId);
        request.claim(userId);
        return repo.save(request);
    }

    @Transactional
    public void release(String requestId, String userId) {
        var request = getRequest(requestId);
        request.release(userId);
        repo.save(request);
    }

    /**
     * AC-10/AC-11/AC-12 — fulfill：claimer 比對 + skill PUBLISHED 驗（cross-module
     * skill::domain SkillRepository.findById）。
     *
     * <p>S096f2-T02 caller migration：原 throw {@code IllegalArgumentException("skill_not_publishable: ...")}
     * 改用獨立 {@link SkillNotPublishableException} class — 對齊 RequestNotFoundException /
     * NotRequestClaimerException naming + 給 GlobalExceptionHandler 路由更精確（400 with
     * specific error code 而非 fall through 到 generic VALIDATION_ERROR）。
     */
    @Transactional
    public Request fulfill(String requestId, String userId, String skillId) {
        Skill skill = skillRepo.findById(skillId)
                .orElseThrow(() -> new SkillNotPublishableException(skillId, "skill not found"));
        if (skill.getStatus() != SkillStatus.PUBLISHED) {
            throw new SkillNotPublishableException(skillId,
                    "skill status is " + skill.getStatus() + ", expected PUBLISHED");
        }
        var request = getRequest(requestId);
        request.fulfill(userId, skillId);
        return repo.save(request);
    }

    /** AC-13 — delete：requester 比對 + status==OPEN guard。 */
    @Transactional
    public void deleteRequest(String requestId, String userId) {
        var request = getRequest(requestId);
        if (!userId.equals(request.getRequesterId())) {
            throw new IllegalStateException("not_request_requester: only requester can delete");
        }
        request.assertDeletable(); // 非 OPEN → IllegalStateException → 409 cannot_delete_active_request
        repo.deleteById(requestId);
        // 不發 RequestDeletedEvent — spec §1 只 5 events，本 op 沒對應 record
    }
}
