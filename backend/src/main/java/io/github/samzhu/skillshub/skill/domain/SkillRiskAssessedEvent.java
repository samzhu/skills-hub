package io.github.samzhu.skillshub.skill.domain;

/**
 * S024 T3 引入 — 風險評估完成事件，由 {@link SkillVersion#attachRiskAssessment(java.util.Map)} 註冊。
 *
 * <p>觸發路徑：S010 ScanOrchestrator multi-engine scan pipeline 完成 →
 * {@code SkillVersion} aggregate 載入 → {@code attachRiskAssessment(...)} 充血方法 mutate
 * {@code risk_assessment} JSONB 欄位 + register 本事件 → {@code SkillVersionRepository.save}
 * 透過 {@code @DomainEvents} publish 至 Modulith outbox。
 *
 * <p>主要訂閱者（T5 加）：{@code AuditEventListener} — 寫入 {@code domain_events} audit log
 * （event_type='SkillRiskAssessed'）；T1 / T3 期間無 listener 訂閱（outbox row count = 0）。
 *
 * <p>v1.5.0 為止 ScanOrchestrator 直接呼叫 {@code DomainEventRepository.save}（domain_events
 * row 直接寫入）；S024 T5 改為走 aggregate event publish path 統一收口。
 *
 * @param skillId  Skill 聚合根 UUID（對應 skills.id；非 SkillVersion 自身的 id）
 * @param version  被評估的版本字串（SemVer）
 * @param level    評估結果嚴重等級（{@code LOW} / {@code MEDIUM} / {@code HIGH}；對應 {@code Severity} enum）
 * @param findings 風險發現清單（型別為 {@code Object} 因為實際內容為 {@code List<SecurityFinding>}
 *                 但 audit 端只關心 size；listener 端可向下 cast 取得）
 */
public record SkillRiskAssessedEvent(
        String skillId,
        String version,
        String level,
        Object findings
) {}
