package io.github.samzhu.skillshub.notification;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.community.RequestRepository;
import io.github.samzhu.skillshub.community.SkillSubscriptionService;
import io.github.samzhu.skillshub.community.events.RequestCommentedEvent;
import io.github.samzhu.skillshub.review.domain.ReviewCreatedEvent;
import io.github.samzhu.skillshub.security.SkillFlaggedEvent;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S096h2-T02 + S125b → S156c — Cross-module event projection 寫 notifications 表。
 *
 * <p>S156c voting-board pivot：移除 {@code onRequestClaimed} / {@code onRequestFulfilled}
 * 兩 listener（claim/fulfill 機制已拆）；新增 {@code onRequestCommented} 通知 requester。
 *
 * <p>4 個 {@code @ApplicationModuleListener}（Modulith AFTER_COMMIT async + outbox redelivery
 * 保護）訂閱跨模組 domain events：
 * <ul>
 *   <li>{@link SkillFlaggedEvent}            → skill.author（owner）</li>
 *   <li>{@link ReviewCreatedEvent}           → skill.author</li>
 *   <li>{@link SkillVersionPublishedEvent}   → SkillSubscription.findSubscribersOf（S125b）</li>
 *   <li>{@link RequestCommentedEvent}        → request.requester_id（S156c；skip self-comment）</li>
 * </ul>
 *
 * <p><b>Idempotency</b>：UNIQUE(recipient_id, ref_event_id, category) constraint 守護
 * outbox redelivery 重複 INSERT；listener catch {@link DuplicateKeyException} 並 log
 * 但不重拋（per spec §4.4 範本）。每事件的 ref_event_id 由 deterministic 從 payload
 * 派生（見各 method JavaDoc）— 同 payload 二次重送 → 同 ref_event_id → DB 攔截。
 *
 * <p><b>Self-action skip</b>：對 ReviewCreated，當 actor == recipient 時 skip notification
 * （user 不通知自己；spec §4.4 範本明示）。
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
    private final SkillSubscriptionService subscriptionService;
    private final UserDisplayService userDisplayService;

    NotificationProjectionListener(NotificationRepository notifRepo,
                                   NotificationPreferenceRepository prefRepo,
                                   SkillRepository skillRepo,
                                   RequestRepository requestRepo,
                                   SkillSubscriptionService subscriptionService,
                                   UserDisplayService userDisplayService) {
        this.notifRepo = notifRepo;
        this.prefRepo = prefRepo;
        this.skillRepo = skillRepo;
        this.requestRepo = requestRepo;
        this.subscriptionService = subscriptionService;
        this.userDisplayService = userDisplayService;
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

        var title = actorLabel(e.authorId()) + " 對你的技能 " + skill.getName() + " 寫了 " + e.rating() + "★ 評論";
        save(ownerId, "reviews", title, null, e.skillId(), e.reviewId());
    }

    /**
     * S125b — Skill 新版發布通知所有訂閱者（PRD §285-§291 P9 SBE scenario 1）。
     *
     * <p>{@code ref_event_id = skillId + ":" + version}：每個 (skill, version) 組合至多 1 次通知；
     * outbox redelivery 由 UNIQUE(recipient_id, ref_event_id, category) 攔截。
     *
     * <p><b>Self-action skip</b>：當 subscriber == skill.author 時 skip — 作者不通知自己（per
     * 既驗 onReviewCreated / onRequestClaimed pattern）。
     *
     * <p><b>Preferences gate</b>：每 subscriber 各自查 NotificationPreference category=`versions`；
     * opt-out 者不寫 notification。
     */
    @ApplicationModuleListener
    public void onVersionPublished(SkillVersionPublishedEvent e) {
        var skill = skillRepo.findById(e.aggregateId()).orElse(null);
        if (skill == null) {
            log.debug("VersionPublished listener: skill {} not found, skip", e.aggregateId());
            return;
        }
        var ownerId = skill.getAuthor();
        var subscribers = subscriptionService.findSubscribersOf(e.aggregateId());
        if (subscribers.isEmpty()) {
            log.debug("VersionPublished listener: no subscribers for skill {}, skip", e.aggregateId());
            return;
        }
        var refEventId = e.aggregateId() + ":" + e.version();
        var title = skill.getName() + " " + e.version() + " 已發布";
        for (var subscriberId : subscribers) {
            if (Objects.equals(ownerId, subscriberId)) continue; // skip 作者自己訂閱自己
            if (!isCategoryEnabled(subscriberId, "versions")) continue;
            save(subscriberId, "versions", title, null, e.aggregateId(), refEventId);
        }
    }

    /**
     * S156c AC-5 — Request comment 通知 requester（skip 自我 comment）。
     *
     * <p>{@code ref_event_id = commentId}：comment id 為 UUID v4，自然 unique → 1 comment
     * 至多 1 notification；outbox redelivery 由 UNIQUE(recipient_id, ref_event_id, category)
     * 攔截。對齊 onReviewCreated 既驗 self-action skip pattern。
     */
    @ApplicationModuleListener
    public void onRequestCommented(RequestCommentedEvent e) {
        var req = requestRepo.findById(e.requestId()).orElse(null);
        if (req == null) {
            log.debug("RequestCommented listener: request {} not found, skip", e.requestId());
            return;
        }
        var requesterId = req.getRequesterId();
        if (requesterId == null || requesterId.isBlank()) return;
        if (Objects.equals(requesterId, e.authorId())) return; // skip 自己 comment 自己 request
        if (!isCategoryEnabled(requesterId, "requests")) return;

        var title = actorLabel(e.authorId()) + " 在你的需求「" + req.getTitle() + "」留言";
        save(requesterId, "requests", title, e.content(), null, e.commentId());
    }

    private String actorLabel(String actorId) {
        var display = userDisplayService.resolve(actorId, false);
        if (display.displayName() != null) {
            return display.displayName();
        }
        if (display.handle() != null) {
            return "@" + display.handle();
        }
        return actorId.matches("u_[0-9a-fA-F]{6}") ? "使用者" : actorId;
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
