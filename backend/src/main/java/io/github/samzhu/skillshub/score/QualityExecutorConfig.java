package io.github.samzhu.skillshub.score;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/**
 * S135a §4.4 — 獨立 qualityExecutor pool，隔離 LLM judge 長時呼叫（5-30s/call）
 * 不擠占 applicationTaskExecutor（corePool=2/queue=200）。
 */
@Configuration
class QualityExecutorConfig {

    @Bean(name = "qualityExecutor")
    public TaskExecutor qualityExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("quality-judge-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(ex);
    }
}
