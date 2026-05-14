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
        List<FindingSummary> findings) {

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
                overall, checks, List.of(), List.of());
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
}
