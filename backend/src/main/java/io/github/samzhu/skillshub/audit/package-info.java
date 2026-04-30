/**
 * Audit module — S024 T05B 引入。訂閱所有 Skill 相關 domain events，將每筆 publish
 * 寫入 {@code domain_events} table 作為 audit log。取代 v1.5.0 ES path
 * {@code SkillCommandService.saveDomainEventOnly} transitional bridge。
 *
 * <p>依賴：
 * <ul>
 *   <li>{@code skill :: domain} — 訂閱 9 個 domain event records（SkillCreated / SkillVersionPublished
 *       / SkillVersionPublishedFromAggregate / SkillDownloaded / SkillAclGranted / SkillAclRevoked
 *       / SkillSuspended / SkillReactivated / SkillRiskAssessed）</li>
 *   <li>{@code shared :: events} — 透過 {@code DomainEventRepository.saveAuditIdempotent} 原子寫入</li>
 * </ul>
 *
 * <p>Audit 屬性與 skill 業務邏輯解耦 — 獨立 module 避免 shared → skill cycle，並為 future audit
 * 擴充（如 user activity log、cross-aggregate events）保留邊界。
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "skill :: domain"}
)
package io.github.samzhu.skillshub.audit;
