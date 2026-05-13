/**
 * Audit module — S024 T05B 引入 → S156c T03 擴充。訂閱所有 Skill + Request 相關 domain events，
 * 將每筆 publish 寫入 {@code domain_events} table 作為 audit log（per spec §2.8 ES 永存原則）。
 *
 * <p>依賴：
 * <ul>
 *   <li>{@code skill :: domain} — 訂閱 8 個 Skill domain event records（SkillCreated /
 *       SkillVersionPublished / SkillVersionPublishedFromAggregate / SkillDownloaded /
 *       SkillSuspended / SkillReactivated / SkillRiskAssessed / SkillDeleted）</li>
 *   <li>{@code community :: events} — 訂閱 3 個 Request domain event records（S156c T03：
 *       RequestPosted / RequestVoted / RequestCommented）</li>
 *   <li>{@code shared :: events} — 透過 {@code DomainEventRepository.saveAuditIdempotent} 原子寫入</li>
 * </ul>
 *
 * <p>Audit 屬性與 skill / community 業務邏輯解耦 — 獨立 module 避免 shared → skill cycle，並為
 * future audit 擴充（如 user activity log、cross-aggregate events）保留邊界。
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "skill :: domain", "community :: events"}
)
package io.github.samzhu.skillshub.audit;
