package io.github.samzhu.skillshub.shared.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * S115 §2.6 — JWT claim parsing 偏離預期時 emit Micrometer counter，給 Grafana
 * dashboard / Alerting 觀測 IdP claim schema 演化動態。
 *
 * <p>Counter shape：{@code jwt_claim_anomaly_total{claim, reason}}
 * <ul>
 *   <li>{@code claim} — 偏離的 claim 名（如 "roles" / "groups" / "sub"）</li>
 *   <li>{@code reason} — 偏離原因（"missing" / "type_mismatch" / "non_string_element"）</li>
 * </ul>
 *
 * <p>對齊既有 OpenTelemetry / Grafana LGTM stack（per CLAUDE.md tech stack）；
 * 不擴 ApiError shape 為 graceful 路徑提供 ops 觀測能力，前端使用者無感受。
 */
@Component
public class JwtClaimAnomalyMetrics {

    private final MeterRegistry registry;

    public JwtClaimAnomalyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 紀錄一次 JWT claim parsing 偏離預期事件。
     *
     * @param claim  偏離的 claim 名（例：{@code "roles"} / {@code "sub"}）
     * @param reason 偏離原因（例：{@code "missing"} / {@code "type_mismatch"}）
     */
    public void increment(String claim, String reason) {
        registry.counter("jwt_claim_anomaly_total",
                "claim", claim, "reason", reason).increment();
    }
}
