package io.github.samzhu.skillshub.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * S142b §4.4 — GET /api/v1/skills/{id}/security-report 回應 DTO。
 * 4-quad checks (shell / paths / secrets / deps) + overall + metadata。
 */
public record SecurityReportResponse(
        String skillId,
        String skillVersionId,
        String skillVersion,
        Instant scannedAt,
        String engineVersion,
        String ruleSetVersion,
        String overall,
        Map<String, CheckDetail> checks,
        List<CategorySummary> categories,
        List<FindingSummary> findings,
        List<RiskReason> riskReasons) {

    /** Legacy constructor — keeps S142b controller tests and old callers source-compatible. */
    public SecurityReportResponse(
            String skillId,
            String skillVersionId,
            String skillVersion,
            Instant scannedAt,
            String engineVersion,
            String ruleSetVersion,
            String overall,
            Map<String, CheckDetail> checks) {
        this(skillId, skillVersionId, skillVersion, scannedAt, engineVersion, ruleSetVersion,
                overall, checks, List.of(), List.of(), List.of());
    }

    /** Legacy constructor — keeps S147 callers source-compatible while S190 adds riskReasons. */
    public SecurityReportResponse(
            String skillId,
            String skillVersionId,
            String skillVersion,
            Instant scannedAt,
            String engineVersion,
            String ruleSetVersion,
            String overall,
            Map<String, CheckDetail> checks,
            List<CategorySummary> categories,
            List<FindingSummary> findings) {
        this(skillId, skillVersionId, skillVersion, scannedAt, engineVersion, ruleSetVersion,
                overall, checks, categories, findings, List.of());
    }

    /** 單一 category 的狀態與 detail string。 */
    public record CheckDetail(String status, String detail) {}

    /** S147-T01 — issue-code category summary for the security tab. */
    public record CategorySummary(
            String key,
            String label,
            String status,
            int findingCount,
            String highestSeverity) {}

    /** S147-T01 — issue-code finding detail for the security tab. */
    public record FindingSummary(
            String ruleId,
            String issueCode,
            String severity,
            String message,
            String remediation,
            String confidence,
            String filePath,
            Integer line,
            String evidence) {}

    /**
     * S190 — user-readable reason for the package risk tier, separate from fixable findings.
     *
     * @param code stable reason code from scan JSON or legacy fallback
     * @param label short zh-TW heading rendered by the security tab
     * @param detail plain-language explanation for non-engineers
     * @param impact NONE / LOW / MEDIUM / HIGH tier impact
     * @param evidence tool names, script paths, or finding identifiers that support the reason
     * @param action recommended next action token for the UI
     */
    public record RiskReason(
            String code,
            String label,
            String detail,
            String impact,
            List<String> evidence,
            String action) {}
}
