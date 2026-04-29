package io.github.samzhu.skillshub.shared.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

/**
 * S023：Spring Modulith Event Publication Registry 觀測 metrics。
 *
 * <p>暴露兩個 Micrometer gauge 給 Prometheus / actuator scrape：
 * <ul>
 *   <li>{@code event_publication.failed.count} — 即時 status='FAILED' row 數，
 *       Prometheus alert 應對此 gauge {@code > 0 for 5m} 觸發告警</li>
 *   <li>{@code event_publication.incomplete.count} — completion_date IS NULL row 數
 *       （含 PUBLISHED / PROCESSING / FAILED / RESUBMITTED 各狀態）；
 *       長期攀升表示 listener 跟不上發佈速度</li>
 * </ul>
 *
 * <p>Spring Modulith 2.0.6 內建 {@code modulith.events.processed} counter 但
 * <b>未提供</b> incomplete / failed gauge — 需應用自寫
 * （per deepwiki design-decisions §3 + event-publication-registry.md §8）。
 *
 * <p>查詢 SQL 直接走 {@code event_publication} 表 — Spring Modulith 公開該 table 為
 * stable schema（V2 自 2.0.0 GA）。
 *
 * @see io.github.samzhu.skillshub.shared.config.SchedulerConfig
 * @see io.github.samzhu.skillshub.shared.events.IncompleteEventRepublishTask
 */
@Component
public class EventPublicationMetrics {

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public EventPublicationMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("event_publication.failed.count",
                        this, m -> queryCount("WHERE status = 'FAILED'"))
                .description("Spring Modulith outbox publications in FAILED state")
                .register(registry);

        Gauge.builder("event_publication.incomplete.count",
                        this, m -> queryCount("WHERE completion_date IS NULL"))
                .description("Spring Modulith outbox publications not yet completed")
                .register(registry);
    }

    private double queryCount(String whereClause) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication " + whereClause, Long.class);
        return count == null ? 0.0 : count.doubleValue();
    }
}
