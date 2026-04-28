package io.github.samzhu.skillshub.skill.domain;

/**
 * S018：Skill 停用領域事件 — 將 PUBLISHED skill 轉至 SUSPENDED 狀態。
 *
 * <p>由 {@code Skill#suspend(SuspendCommand)} 在 aggregate 通過 state machine 不變量
 * （{@link SkillStatus#suspend()}）後產生，經 {@code SkillCommandService#suspend} 持久化
 * 至 {@code domain_events} 並透過 {@code ApplicationEventPublisher} 發布。
 *
 * <p>查詢側 {@code SkillProjection.on(SkillSuspendedEvent)} 訂閱此事件以將 read model
 * status 更新為 SUSPENDED。
 *
 * @param aggregateId  Skill 聚合根 UUID
 * @param reason       停用理由（policy violation / security incident / 等）
 * @param suspendedBy  執行 suspend 的 user-id（從 SecurityContext 取得，非由 client 提供避免 spoof）
 */
public record SkillSuspendedEvent(
        String aggregateId,
        String reason,
        String suspendedBy
) {}
