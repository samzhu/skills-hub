package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S023-T06 — 驗證 50 並發 event publish 下 HikariCP pool 不飽和（per S023 spec §3 AC-9）。
 *
 * <p>POC findings 驗證：{@code applicationTaskExecutor} pool=2 + queueCapacity=200
 * 在突發 50 events 場景下不超載；event_publication 全部達 COMPLETED status。
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, HikariPoolUnderLoadTest.LoadTestConfig.class })
class HikariPoolUnderLoadTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private LoadTestPublisher loadPublisher;

    @Autowired
    private LoadTestListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM event_publication WHERE event_type LIKE '%LoadTestEvent%'");
        listener.reset();
    }

    @Test
    @DisplayName("AC-9: 50 並發 LoadTestEvent 全部達 COMPLETED，無 pool exhaustion")
    @Tag("AC-9")
    void fiftyConcurrentEvents_allComplete() {
        var batchMarker = "load-batch-" + UUID.randomUUID();

        // 在 single TX 內 publish 50 events（commit 後 50 個 outbox row INSERT，
        // AFTER_COMMIT 觸發 50 個 async listener 執行，並走 ThreadPoolTaskExecutor pool=2 + queue=200）
        loadPublisher.publishBatch(50, batchMarker);

        // 等所有 listener 完成（最多 30 秒）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(listener.getInvocations())
                    .as("50 events 應全部觸發 listener")
                    .isEqualTo(50);

            var completed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication " +
                            "WHERE serialized_event LIKE ? AND status = 'COMPLETED'",
                    Integer.class, "%" + batchMarker + "%");
            assertThat(completed)
                    .as("50 個 event_publication 全部達 COMPLETED 狀態")
                    .isEqualTo(50);
        });

        // 確認沒有任何 publication 留在 FAILED 狀態（pool exhaustion 會以 Connection-not-available
        // 引發 listener 異常 → status='FAILED'）
        var failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication " +
                        "WHERE serialized_event LIKE ? AND status = 'FAILED'",
                Integer.class, "%" + batchMarker + "%");
        assertThat(failed)
                .as("無 listener 因 pool exhaustion fail")
                .isEqualTo(0);
    }

    record LoadTestEvent(String marker, int seq) {}

    /** Helper bean — 在 @Transactional 內批次 publish 50 events，確保所有 outbox INSERT 在同一 TX。 */
    static class LoadTestPublisher {
        private final ApplicationEventPublisher publisher;

        LoadTestPublisher(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        void publishBatch(int count, String marker) {
            for (int i = 0; i < count; i++) {
                publisher.publishEvent(new LoadTestEvent(marker, i));
            }
        }
    }

    static class LoadTestListener {
        private final AtomicInteger invocations = new AtomicInteger(0);

        public int getInvocations() { return invocations.get(); }
        public void reset() { invocations.set(0); }

        @ApplicationModuleListener
        void onLoad(LoadTestEvent event) {
            invocations.incrementAndGet();
        }
    }

    @TestConfiguration
    static class LoadTestConfig {
        @Bean
        LoadTestPublisher loadTestPublisher(ApplicationEventPublisher publisher) {
            return new LoadTestPublisher(publisher);
        }

        @Bean
        LoadTestListener loadTestListener() {
            return new LoadTestListener();
        }
    }
}
