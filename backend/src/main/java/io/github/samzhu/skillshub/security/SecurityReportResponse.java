package io.github.samzhu.skillshub.security;

import java.time.Instant;
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
        Map<String, CheckDetail> checks) {

    /** 單一 category 的狀態與 detail string。 */
    public record CheckDetail(String status, String detail) {}
}
