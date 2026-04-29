package io.github.samzhu.skillshub.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * S023-T05 — 驗證 {@link IncompleteEventRepublishTask} 的 wiring：
 * <ul>
 *   <li>AC-7：method 標 {@link Scheduled}（由 Spring 排程觸發）</li>
 *   <li>AC-8：method 標 {@link SchedulerLock}（多 instance 互斥；name="republish-incomplete-events"）</li>
 *   <li>{@link LockProvider} bean 存在於 context（{@link io.github.samzhu.skillshub.shared.config.SchedulerConfig}
 *       已正確 wire）</li>
 * </ul>
 *
 * <p>實際多 instance 並發 ShedLock 互斥行為由 ShedLock 7.7.0 框架本身保證
 * （per ShedLock README + deepwiki §3 陷阱 5），本 test 不重複驗證 lock 機制本身，
 * 只驗 Skills Hub 的 wiring 正確（annotation 配置 + bean 註冊）。AC-7 / AC-8 的
 * end-to-end retry 行為由 T06 整合測試 + manual verification 覆蓋。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncompleteEventRepublishTaskWiringTest {

    @Autowired
    private IncompleteEventRepublishTask task;

    @Autowired
    private LockProvider lockProvider;

    @Test
    @DisplayName("AC-7: republishIncompleteEvents 標 @Scheduled（fixedDelayString=PT1M default）")
    @Tag("AC-7")
    void republishMethod_isScheduled() {
        Method method = findRepublishMethod();
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("應從 skillshub.scheduler.republish-delay property 讀取")
                .contains("skillshub.scheduler.republish-delay");
    }

    @Test
    @DisplayName("AC-8: republishIncompleteEvents 標 @SchedulerLock 設定多 instance 互斥")
    @Tag("AC-8")
    void republishMethod_hasSchedulerLock() {
        Method method = findRepublishMethod();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("republish-incomplete-events");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT10M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT30S");
    }

    @Test
    @DisplayName("AC-8: LockProvider bean 由 SchedulerConfig 註冊（JdbcTemplateLockProvider）")
    @Tag("AC-8")
    void lockProviderBean_isWired() {
        assertThat(lockProvider).isNotNull();
        // class name 包含 JdbcTemplateLockProvider 即代表 SchedulerConfig 的 LockProvider bean
        // 走 PostgreSQL shedlock 表（V5 migration）
        assertThat(lockProvider.getClass().getName()).contains("JdbcTemplate");
    }

    @Test
    @DisplayName("AC-7: olderThan 預設讀 PT5M")
    @Tag("AC-7")
    void olderThanDefault_isFiveMinutes() {
        // 用 getter method 而非 reflection — Spring AOP proxy 對 field 不透明（method call 才會 delegate 到 target）
        assertThat(task.getOlderThan()).isEqualTo(Duration.parse("PT5M"));
    }

    private Method findRepublishMethod() {
        return Arrays.stream(IncompleteEventRepublishTask.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("republishIncompleteEvents"))
                .filter(m -> m.getParameterCount() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "IncompleteEventRepublishTask.republishIncompleteEvents() not found"));
    }
}
