package io.github.samzhu.skillshub.notification;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.community.RequestRepository;
import io.github.samzhu.skillshub.community.events.RequestClaimedEvent;
import io.github.samzhu.skillshub.community.events.RequestFulfilledEvent;
import io.github.samzhu.skillshub.review.domain.ReviewCreatedEvent;
import io.github.samzhu.skillshub.security.SkillFlaggedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S096h2-T02 — Cross-module event projection 寫 notifications 表（per spec §4.4）。
 *
 * <p>4 個 {@code @ApplicationModuleListener}（Modulith AFTER_COMMIT async + outbox redelivery
 * 保護）訂閱跨模組 domain events：
 * <ul>
 *   <li>{@link SkillFlaggedEvent}      → skill.author（owner）</li>
 *   <li>{@link ReviewCreatedEvent}     → skill.author</li>
 *   <li>{@link RequestClaimedEvent}    → request.requester_id</li>
 *   <li>{@link RequestFulfilledEvent}  → request.requester_id</li>
 * </ul>
 *
 * <p><b>Idempotency</b>：UNIQUE(recipient_id, ref_event_id, category) constraint 守護
 * outbox redelivery 重複 INSERT；listener catch {@link DuplicateKeyException} 並 log
 * 但不重拋（per spec §4.4 範本）。每事件的 ref_event_id 由 deterministic 從 payload
 * 派生（見各 method JavaDoc）— 同 payload 二次重送 → 同 ref_event_id → DB 攔截。
 *
 * <p><b>Self-action skip</b>：對 ReviewCreated / RequestClaimed，當 actor == recipient
 * 時 skip notification（user 不通知自己；spec §4.4 範本明示）。
 *
 * <p><b>Preferences gate</b>：每事件先查 {@link NotificationPreferenceRepository}：
 * 無 row → 預設 ON；有 row 對應 category boolean false → skip。
 */
@Component
public class NotificationProjectionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationProjectionListener.class);

    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final SkillRepository skillRepo;
    private final RequestRepository requestRepo;

    NotificationProjectionListener(NotificationRepository notifRepo,
                                   NotificationPreferenceRepository prefRepo,
                                   SkillRepository skillRepo,
                                   RequestRepository requestRepo) {
        this.notifRepo = notifRepo;
        this.prefRepo = prefRepo;
        this.skillRepo = skillRepo;
        this.requestRepo = requestRepo;
    }

    /**
     * S096h2 AC-1 — Skill 被回報通知 owner。
     *
     * <p>{@code ref_event_id = aggregateId + ":" + type}：SkillFlaggedEvent record 無 flagId
     * 欄位（per security 模組既有 design），改用 (skillId, type) composite 作 idempotency key。
     * 副作用：同 skill 同 type 多筆 flag dedupe 為 1 個 notification — 防 spam 通知，
     * UX 可接受（owner 知道「skill X 有 spam 類回報」一次即可，逐筆 review 進 FlagsList）。
     */
    @ApplicationModuleListener
    public void onSkillFlagged(SkillFlaggedEvent e) {
        var skill = skillRepo.findById(e.aggregateId()).orElse(null);
        if (skill == null) {
            log.debug("SkillFlagged listener: skill {} not found, skip", e.aggregateId());
            return;
        }
        var ownerId = skill.getAuthor();
        if (ownerId == null || ownerId.isBlank()) return;
        if (!isCategoryEnabled(ownerId, "flags")) return;

        var refEventId = e.aggregateId() + ":" + e.type();
        var title = "你的技能 " + skill.getName() + " 被標記回報（" + e.type() + "）";
        save(ownerId, "flags", title, e.description(), e.aggregateId(), refEventId);
    }

    /**
     * S096h2 AC-2 — Review 建立通知 owner（skip 自我 review）。
     * {@code ref_event_id = reviewId}（unique per review；Review aggregate UNIQUE(skill,author) 已強制 1 user 1 review）。
     */
    @ApplicationModuleListener
    public void onReviewCreated(ReviewCreatedEvent e) {
        var skill = skillRepo.findById(e.skillId()).orElse(null);
        if (skill == null) return;
        var ownerId = skill.getAuthor();
        if (ownerId == null || ownerId.isBlank()) return;
        if (Objects.equals(ownerId, e.authorId())) return; // skip 自己對自己 skill 寫評論
        if (!isCategoryEnabled(ownerId, "reviews")) return;

        var title = e.authorId() + " 對你的技能 " + skill.getName() + " 寫了 " + e.rating() + "★ 評論";
        save(ownerId, "reviews", title, null, e.skillId(), e.reviewId());
    }

    /**
     * S096h2 AC-3 — Request 被認領通知 requester（skip 自我 claim）。
     * {@code ref_event_id = requestId + ":" + claimerId + ":claim"}：同 user 重複 claim 同 request
     * 走 idempotency dedupe；release-then-reclaim by 同人也 dedupe（合理 — 同事件重發）；
     * release-then-reclaim by 不同人 → 不同 claimerId → 新 ref_event_id → 新 notification。
     */
    @ApplicationModuleListener
    public void onRequestClaimed(RequestClaimedEvent e) {
        var req = requestRepo.findById(e.requestId()).orElse(null);
        if (req == null) return;
        var requesterId = req.getRequesterId();
        if (requesterId == null || requesterId.isBlank()) return;
        if (Objects.equals(requesterId, e.claimerId())) return; // skip 自己 claim 自己 request
        if (!isCategoryEnabled(requesterId, "requests")) return;

        var refEventId = e.requestId() + ":" + e.claimerId() + ":claim";
        var title = e.claimerId() + " 認領了你發起的需求 " + req.getTitle();
        save(requesterId, "requests", title, null, null, refEventId);
    }

    /**
     * S096h2 AC-4 — Request 完成通知 requester。
     * {@code ref_event_id = requestId + ":fulfill"}：fulfill 為 terminal state，每 request 至多 1 次。
     * 若 fulfilledSkillId 對應的 skill 還在，title 含 skill name 增 UX。
     */
    @ApplicationModuleListener
    public void onRequestFulfilled(RequestFulfilledEvent e) {
        var req = requestRepo.findById(e.requestId()).orElse(null);
        if (req == null) return;
        var requesterId = req.getRequesterId();
        if (requesterId == null || requesterId.isBlank()) return;
        if (!isCategoryEnabled(requesterId, "requests")) return;

        var skill = skillRepo.findById(e.fulfilledSkillId()).orElse(null);
        var title = "你發起的需求 " + req.getTitle() + " 已完成"
                + (skill != null ? "（skill: " + skill.getName() + "）" : "");
        var refEventId = e.requestId() + ":fulfill";
        save(requesterId, "requests", title, null, e.fulfilledSkillId(), refEventId);
    }

    private boolean isCategoryEnabled(String userId, String category) {
        return prefRepo.findById(userId)
                .map(p -> p.isEnabled(category))
                .orElse(true); // 無 row = 完全 opt-in
    }

    private void save(String recipientId, String category, String title,
                      String body, String skillId, String refEventId) {
        try {
            notifRepo.save(Notification.create(recipientId, category, title, body, skillId, refEventId));
        } catch (DuplicateKeyException ignored) {
            // S096h2 AC-10 — outbox redelivery 防護；UNIQUE(recipient, ref_event, category) 攔截
            log.debug("Notification idempotent skip — recipient={}, refEventId={}, category={}",
                    recipientId, refEventId, category);
        }
    }
}
