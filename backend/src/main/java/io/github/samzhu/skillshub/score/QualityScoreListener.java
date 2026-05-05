package io.github.samzhu.skillshub.score;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S135a §4.2 — SkillVersionPublishedEvent 訂閱者；非同步觸發三軸品質評分。
 *
 * <p>AFTER_COMMIT + REQUIRES_NEW（via {@code @ApplicationModuleListener}）確保
 * skill_versions FK 在 publisher TX commit 後才讀取，且評分寫入為獨立 TX。
 *
 * <p>{@code @Async("qualityExecutor")} 使評分走獨立 pool（corePool=1，queue=500），
 * 不擠占 applicationTaskExecutor — 避免 LLM 5-30s/call 阻塞其他 listener。
 */
@Component
class QualityScoreListener {

    private static final Logger log = LoggerFactory.getLogger(QualityScoreListener.class);

    private final QualityScoreService service;

    QualityScoreListener(QualityScoreService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    @Async("qualityExecutor")
    void on(SkillVersionPublishedEvent event) {
        if (service.alreadyScored(event.sourceEventId())) {
            log.atDebug()
                    .addKeyValue("sourceEventId", event.sourceEventId())
                    .log("[quality] skip duplicate sourceEventId");
            return;
        }
        // re-throw on failure → outbox keeps completion_date=NULL → IncompleteEventRepublishTask retry
        service.evaluateAndPersist(event);
    }
}
