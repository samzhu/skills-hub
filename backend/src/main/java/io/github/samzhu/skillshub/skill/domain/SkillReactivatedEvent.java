package io.github.samzhu.skillshub.skill.domain;

/**
 * S018：Skill 重啟領域事件 — 將 SUSPENDED skill 轉回 PUBLISHED 狀態。
 *
 * <p>由 {@code Skill#reactivate(ReactivateCommand)} 在 aggregate 通過 state machine 不變量
 * （{@link SkillStatus#reactivate()}）後產生。
 *
 * <p>查詢側 {@code SkillProjection.on(SkillReactivatedEvent)} 訂閱此事件以將 read model
 * status 更新為 PUBLISHED。
 *
 * @param aggregateId  Skill 聚合根 UUID
 * @param reason       重啟理由（manual review approved / scan re-passed / 等）
 */
public record SkillReactivatedEvent(
        String aggregateId,
        String reason
) {}
