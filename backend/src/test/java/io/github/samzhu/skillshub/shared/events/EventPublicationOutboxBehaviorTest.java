package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * S023-T05 — 驗證 Spring Modulith Event Publication Registry 的 outbox 行為：
 * <ul>
 *   <li>AC-5：業務 TX rollback 時 event_publication 也 rollback（atomic outbox）</li>
 *   <li>AC-6：listener 失敗時 event_publication 留 status='FAILED'</li>
 * </ul>
 *
 * <p>用 test-only event {@link TestOutboxEvent} + {@link TestOutboxConfig} 內定義的
 * 兩個 listener（success / failing）避免與 production listener 混淆。
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, EventPublicationOutboxBehaviorTest.TestOutboxConfig.class })
class EventPublicationOutboxBehaviorTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TxBoundary txBoundary;

    @Autowired
    private TestOutboxFailingListener failingListener;

    @Autowired
    private TestOutboxSuccessListener successListener;

    @AfterEach
    void cleanupOutbox() {
        // 清掉 test events 避免 cross-test pollution
        jdbc.update("DELETE FROM event_publication WHERE event_type LIKE '%TestOutboxEvent%'");
        failingListener.reset();
        successListener.reset();
    }

    @Test
    @DisplayName("AC-5: 業務 TX rollback 時 event_publication 也 rollback (atomic outbox)")
    @Tag("AC-5")
    void txRollback_propagatesToOutbox() {
        var marker = "rollback-marker-" + UUID.randomUUID();

        // publish event 後拋例外觸發 rollback
        try {
            txBoundary.publishThenThrow(new TestOutboxEvent(marker));
        } catch (RuntimeException expected) {
            // ignore — TX 已 rollback
        }

        // event_publication 應無此 event 的 row（與業務 TX 同生共死）
        var rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class, "%" + marker + "%");
        assertThat(rowCount)
                .as("業務 TX rollback 時 event_publication INSERT 也應 rollback")
                .isEqualTo(0);

        // listener 也不該被呼叫（事件根本沒 publish 到 outbox）
        assertThat(successListener.getInvocations())
                .as("rollback 後 listener 不應觸發")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("AC-6: listener 失敗時 event_publication 留 status='FAILED'")
    @Tag("AC-6")
    void listenerFailure_marksPublicationAsFailed() {
        var marker = "fail-marker-" + UUID.randomUUID();

        // commit 業務 TX；async listener 在 AFTER_COMMIT 觸發並 throw
        txBoundary.publishInTx(new TestOutboxEvent(marker));

        // 等待 async listener 執行 + Modulith outbox markFailed 完成
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(failingListener.getInvocations())
                    .as("failing listener 應被 async 觸發過")
                    .isGreaterThan(0);

            var rows = jdbc.queryForList(
                    "SELECT status, completion_date FROM event_publication " +
                            "WHERE serialized_event LIKE ? AND listener_id LIKE '%FailingListener%'",
                    "%" + marker + "%");

            assertThat(rows).as("failing listener 對應的 publication row 應存在").hasSize(1);
            assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
            assertThat(rows.get(0).get("completion_date"))
                    .as("FAILED publication 的 completion_date 應為 NULL").isNull();
        });
    }

    /** Test-only event class，避免與 production domain events 混淆。 */
    record TestOutboxEvent(String marker) {}

    /**
     * 提供 @Transactional boundary 的 helper bean — 直接在 @Test method 加 @Transactional
     * 會讓事件在 test method 結束時 rollback（@Transactional in @SpringBootTest 預設行為），
     * 影響 outbox 觀察。改用獨立 bean。
     */
    @org.springframework.stereotype.Component
    static class TxBoundary {
        private final ApplicationEventPublisher publisher;

        TxBoundary(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        void publishInTx(TestOutboxEvent event) {
            publisher.publishEvent(event);
        }

        @Transactional
        void publishThenThrow(TestOutboxEvent event) {
            publisher.publishEvent(event);
            throw new RuntimeException("simulated business failure → rollback");
        }
    }

    @org.springframework.stereotype.Component
    static class TestOutboxSuccessListener {
        // AtomicInteger 用 method 暴露而非 field — Spring AOP proxy 對 field 不透明
        private final AtomicInteger invocations = new AtomicInteger(0);

        public int getInvocations() { return invocations.get(); }
        public void reset() { invocations.set(0); }

        @ApplicationModuleListener
        void onSuccess(TestOutboxEvent event) {
            invocations.incrementAndGet();
        }
    }

    @org.springframework.stereotype.Component
    static class TestOutboxFailingListener {
        private final AtomicInteger invocations = new AtomicInteger(0);

        public int getInvocations() { return invocations.get(); }
        public void reset() { invocations.set(0); }

        @ApplicationModuleListener
        void onFail(TestOutboxEvent event) {
            invocations.incrementAndGet();
            throw new RuntimeException("simulated listener failure for AC-6");
        }
    }

    @TestConfiguration
    static class TestOutboxConfig {
        @Bean
        TxBoundary txBoundary(ApplicationEventPublisher publisher) {
            return new TxBoundary(publisher);
        }

        @Bean
        TestOutboxSuccessListener testOutboxSuccessListener() {
            return new TestOutboxSuccessListener();
        }

        @Bean
        TestOutboxFailingListener testOutboxFailingListener() {
            return new TestOutboxFailingListener();
        }
    }
}
