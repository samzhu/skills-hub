package io.github.samzhu.skillshub.skill.domain;

import java.util.UUID;

/**
 * 技能下載領域事件 — 當使用者成功取得技能套件下載連結時發布。
 *
 * <p>由 {@code SkillCommandService#downloadSkill} 與 {@code SkillQueryService} 產生。
 * 分析模組訂閱此事件以累計各版本的下載次數投影（{@code analytics} 模組）。
 *
 * <p>S023 加入 {@code eventId} 欄位作為 idempotency key — async listener 在 retry 場景下可
 * 透過此 ID 去重（per ADR-002 + S023 spec §2.2）。實際使用點：T03 AnalyticsProjection
 * {@code download_events.event_id UNIQUE} constraint。SkillProjection 不依賴此欄位
 * 做 dedup（接受極罕見的 markCompleted-fail-then-retry 雙計；UI counter 非財務性）。
 *
 * @param aggregateId 技能聚合根的唯一識別碼（UUID）
 * @param version     被下載的語意化版本號（SemVer）
 * @param eventId     事件唯一識別碼（UUID 字串）— 給 AnalyticsProjection idempotency 用
 */
public record SkillDownloadedEvent(
        String aggregateId,
        String version,
        String eventId
) {

    /**
     * Factory — 自動產生 {@code eventId} 為新 UUID 字串。
     *
     * <p>絕大多數 publisher 用此 factory；僅 retry / replay 場景才直接呼叫
     * canonical constructor 沿用既有 eventId。
     */
    public static SkillDownloadedEvent of(String aggregateId, String version) {
        return new SkillDownloadedEvent(aggregateId, version, UUID.randomUUID().toString());
    }
}
