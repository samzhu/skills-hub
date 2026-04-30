package io.github.samzhu.skillshub.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/**
 * `@Async` 與 `@ApplicationModuleListener` 共用的 thread pool 配置（S023）。
 *
 * <p><b>為何必須提供此 bean</b>（per S023 task T01 POC findings + ADR-002）：
 * Spring `@Async`（{@code @ApplicationModuleListener} 內含）若應用未定義
 * {@link TaskExecutor} bean，預設 fallback 至 {@code SimpleAsyncTaskExecutor} —
 * 每 task 開新 thread、無上限。Skills Hub 在 GCP Cloud Run（HikariCP
 * {@code maximum-pool-size=3}）下，async listener 並發無限 → DB connection 池
 * 飽和拋 {@code Connection is not available} → cascade failure。
 *
 * <p><b>容量設計</b>：
 * <ul>
 *   <li>{@code corePoolSize=2 / maxPoolSize=2} — pool=3 留 1 connection 給主請求 thread；
 *       async listener 並發上限 2</li>
 *   <li>{@code queueCapacity=200} — 緩衝突發負載（如批次匯入觸發大量
 *       {@code SkillCreatedEvent}）；超過 200 件 pending 時 fallback 至 caller-runs
 *       policy（同步 publisher thread 處理，自然 backpressure）</li>
 *   <li>bean name {@code applicationTaskExecutor} — Spring `@Async` 預設查找名稱</li>
 * </ul>
 *
 * <p><b>SecurityContext 傳播</b>（S023-T07 production bug fix）：用
 * {@link DelegatingSecurityContextAsyncTaskExecutor} 包裝原 executor —
 * 將呼叫端 thread 的 {@code SecurityContextHolder} 內容**複製**到 async listener thread，
 * 以便 {@code @ApplicationModuleListener} 內 {@code CurrentUserProvider.userId()} /
 * {@code current()} 仍能取得正確 user。沒有此包裝，async thread 的 SecurityContext
 * 為 null → SearchProjection / 其他依賴當前 user 的 listener 在 production 拿不到正確
 * owner、寫入 vector_store / read model 時欄位為 null。
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async">Spring @Async docs</a>
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/integrations/concurrency.html">Spring Security Concurrency</a>
 * @see <a href="../../../../../../../../adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Configuration
@EnableAsync
public class AsyncListenerConfig {

    /**
     * 應用層 async task executor — `@Async` 與 `@ApplicationModuleListener` 共用。
     *
     * <p>Bean name 必須為 {@code applicationTaskExecutor}（Spring `@Async` 預設查找名稱），
     * 否則 fallback 至 unbounded {@code SimpleAsyncTaskExecutor} 引發本檔目的所要避免的問題。
     *
     * <p>外層用 {@link DelegatingSecurityContextAsyncTaskExecutor} wrap，
     * 確保 async thread 仍能存取呼叫端 SecurityContext（per S023-T07 production bug fix）。
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public TaskExecutor applicationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("modulith-listener-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        // S025a-T03 production fix（深層 root cause from S023-T07）：
        // Spring 7.0 + Spring Boot 4 環境下，多 TaskExecutor bean（applicationTaskExecutor +
        // taskScheduler）造成 @Async 無法 by-type 解析 → fallback SimpleAsyncTaskExecutor →
        // DelegatingSecurityContextAsyncTaskExecutor 包裝失效 → CurrentUserProvider 在 async
        // listener 拿到空 SecurityContext → fallback labUserId。修法：對 bean 加 alias
        // "taskExecutor"（@Async DEFAULT_TASK_EXECUTOR_BEAN_NAME）— 透過 by-name lookup 強制
        // 使用此 wrapped executor。S023-T07 spec §7.3 production bug 真因落地。
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
