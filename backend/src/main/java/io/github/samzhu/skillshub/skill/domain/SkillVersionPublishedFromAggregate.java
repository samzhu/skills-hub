package io.github.samzhu.skillshub.skill.domain;

/**
 * S024 內部事件 — 標識 Skill aggregate 自身的 state 變化（latestVersion 更新 +
 * status DRAFT→PUBLISHED transition）。
 *
 * <p>與 {@link SkillVersionPublishedEvent}（SkillVersion aggregate 自己發布的事件，
 * 帶有完整 storagePath / fileSize / frontmatter / allowedTools 載荷）區分：
 * 本事件只表達「Skill 物件本身」的 state 改變，**不**作為 SearchProjection /
 * ScanOrchestrator 的訂閱來源（這些 listener 訂閱 {@link SkillVersionPublishedEvent}
 * 取得完整 publish 資訊）。
 *
 * <p>主要訂閱者：S024 T5 引入的 {@code AuditEventListener} — 寫入 {@code domain_events}
 * audit log row（event_type='SkillStateAdvancedToPublished'）以保留 audit trail
 * 中「Skill 自身狀態變化」的紀錄。其他 listener 不訂閱本事件。
 *
 * <p>此事件透過 {@code Skill.recordVersionPublished} 由 aggregate 自己 register；
 * Spring Data JDBC 在 {@code skillRepository.save(skill)} 時透過 {@code @DomainEvents}
 * 自動 publish 至 Modulith {@code event_publication} outbox（同 transaction）。
 *
 * @param aggregateId 對應 Skill 聚合根 UUID（同 skills.id）
 * @param version     觸發此 transition 的版本字串（與 {@link SkillVersionPublishedEvent#version()} 一致）
 */
public record SkillVersionPublishedFromAggregate(
        String aggregateId,
        String version
) {}
