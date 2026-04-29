package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * S023-T06 — 驗證 Micrometer gauges 反映 event_publication 表狀態（per S023 spec §3 AC-10）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPublicationMetricsTest {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        // 清掉本 test 注入的 row 避免 cross-test pollution
        jdbc.update("DELETE FROM event_publication WHERE listener_id = 'test-metrics-listener'");
    }

    @Test
    @DisplayName("AC-10: event_publication.failed.count gauge 即時反映 DB 計數")
    @Tag("AC-10")
    void failedCountGauge_reflectsDbState() {
        var failedGauge = registry.find("event_publication.failed.count").gauge();
        assertThat(failedGauge).as("EventPublicationMetrics 應註冊 failed.count gauge").isNotNull();

        double baseline = failedGauge.value();

        // 注入 1 筆 FAILED row
        insertEventPublication("FAILED", null);

        assertThat(failedGauge.value())
                .as("注入 1 筆 FAILED 後 gauge 應 +1")
                .isEqualTo(baseline + 1.0);
    }

    @Test
    @DisplayName("AC-10: event_publication.incomplete.count gauge 反映 completion_date IS NULL 計數")
    @Tag("AC-10")
    void incompleteCountGauge_reflectsDbState() {
        var incompleteGauge = registry.find("event_publication.incomplete.count").gauge();
        assertThat(incompleteGauge).as("應註冊 incomplete.count gauge").isNotNull();

        double baseline = incompleteGauge.value();

        // 注入 incomplete row（completion_date NULL）
        insertEventPublication("PUBLISHED", null);

        assertThat(incompleteGauge.value())
                .as("注入 1 筆 PUBLISHED + 無 completion_date 後 gauge +1")
                .isEqualTo(baseline + 1.0);

        // 注入 COMPLETED row（completion_date 有值）— 不算 incomplete
        insertEventPublication("COMPLETED", Timestamp.from(Instant.now()));

        assertThat(incompleteGauge.value())
                .as("加入 COMPLETED row 不影響 incomplete count")
                .isEqualTo(baseline + 1.0);
    }

    private void insertEventPublication(String status, Timestamp completionDate) {
        jdbc.update("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event,
                                               publication_date, completion_date, status)
                VALUES (?::uuid, 'test-metrics-listener', 'TestEvt', '{}', ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                Timestamp.from(Instant.now()),
                completionDate,
                status);
    }
}
