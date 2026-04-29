package io.github.samzhu.skillshub.shared.events;

import java.lang.invoke.MethodHandles;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * S023：定期重投未完成的 event publication（status='FAILED' 或 listener 從未跑完）。
 *
 * <p>由 Spring `@Scheduled` 定期觸發；多 Cloud Run instance 部署透過 ShedLock 互斥，
 * 同一時間只有一個 instance 持有 lock 執行。失敗 retry 是 at-least-once delivery 的核心 —
 * Spring Modulith 不內建排程，須由應用提供（per `docs/deepwiki/spring-data-jdbc-modulith/
 * event-publication-registry.md §5`）。
 *
 * <p>關鍵設計：
 * <ul>
 *   <li>{@link Scheduled#fixedDelayString} = 上次 task 完成後 N 時間再執行（避免任務重疊）</li>
 *   <li>{@link SchedulerLock#name} = 全域唯一識別此 task；多 instance 競爭同一 row</li>
 *   <li>{@link SchedulerLock#lockAtMostFor} = 安全上限；JVM crash 後鎖自動過期，他 instance 接手</li>
 *   <li>{@link SchedulerLock#lockAtLeastFor} = 防短任務 + clock skew 重複觸發</li>
 *   <li>{@link IncompleteEventPublications#resubmitIncompletePublicationsOlderThan} = 只重投
 *       超過 olderThan 時間還未完成的 publication；新建 publication 不會被誤抓</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.shared.config.SchedulerConfig
 */
@Component
public class IncompleteEventRepublishTask {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final IncompleteEventPublications incompletePublications;
    private final Duration olderThan;

    public IncompleteEventRepublishTask(
            IncompleteEventPublications incompletePublications,
            @Value("${skillshub.scheduler.republish-older-than:PT5M}") Duration olderThan) {
        this.incompletePublications = incompletePublications;
        this.olderThan = olderThan;
    }

    /** Test-only accessor — Spring AOP proxy 對 field 不透明，需 method 暴露。 */
    Duration getOlderThan() {
        return olderThan;
    }

    /**
     * 定期重投 incomplete event publication。
     *
     * <p>{@link LockAssert#assertLocked()} 在 `@SchedulerLock` AOP proxy 失效時立即拋
     * `IllegalStateException`，避免 silently 跑兩次（per ShedLock 官方建議；
     * deepwiki design-decisions §3 陷阱）。
     */
    @Scheduled(fixedDelayString = "${skillshub.scheduler.republish-delay:PT1M}")
    @SchedulerLock(
            name = "republish-incomplete-events",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void republishIncompleteEvents() {
        LockAssert.assertLocked();
        log.atDebug()
                .addKeyValue("olderThan", olderThan)
                .log("Republishing incomplete event publications");
        incompletePublications.resubmitIncompletePublicationsOlderThan(olderThan);
    }
}
