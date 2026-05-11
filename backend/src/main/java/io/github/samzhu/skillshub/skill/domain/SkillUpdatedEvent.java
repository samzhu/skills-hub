package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;

/**
 * S163 — Published when a skill owner updates the editable metadata of a skill.
 *
 * <p>只有 owner 可改的「中間 management」欄位：description / category。
 * name / version 不可改（屬 publish flow）；visibility 走既有 ACL grant / revoke API。
 *
 * <p>Downstream listeners：
 * <ul>
 *   <li>{@code SearchProjection} — description 變更觸發 embedding regeneration</li>
 *   <li>{@code AuditEventListener} — async 寫 {@code domain_events} 表</li>
 *   <li>{@code SkillSubscription} — 訂閱者 notification（per S145）</li>
 * </ul>
 *
 * @param aggregateId  updated skill id
 * @param description  新 description（trim 後）；null 表示本次未動
 * @param category     新 category（trim 後）；null 表示本次未動
 * @param updatedBy    platform user id that performed the update
 * @param updatedAt    update timestamp
 */
public record SkillUpdatedEvent(
        String aggregateId,
        String description,
        String category,
        String updatedBy,
        Instant updatedAt
) {}
