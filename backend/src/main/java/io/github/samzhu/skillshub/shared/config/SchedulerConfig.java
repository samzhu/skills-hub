package io.github.samzhu.skillshub.shared.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 排程任務基礎建設配置（S023）— Spring `@Scheduled` + ShedLock 多 instance 互斥。
 *
 * <p>由 {@link io.github.samzhu.skillshub.shared.events.IncompleteEventRepublishTask} 等
 * 排程任務（標 {@code @SchedulerLock}）依賴。每個排程方法執行前由 ShedLock 嘗試取得
 * lock，僅成功者執行；其他 instance 跳過。
 *
 * <p>關鍵設計：
 * <ul>
 *   <li>{@link JdbcTemplateLockProvider} {@code usingDbTime()} 由 PostgreSQL {@code NOW()}
 *       決定時間戳，規避 cluster clock skew 風險（per deepwiki design-decisions §3 陷阱 5；
 *       Cloud Run multi-instance 場景必要）</li>
 *   <li>{@code defaultLockAtMostFor=PT10M} 為全域 safety net — 若 JVM crash，鎖在
 *       此時間後自動過期，他 instance 才能接手</li>
 *   <li>shedlock 表 schema 由 {@code V5__shedlock.sql} Flyway migration 建立</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.shared.events.IncompleteEventRepublishTask
 * @see <a href="https://github.com/lukas-krecan/ShedLock">ShedLock GitHub</a>
 * @see <a href="../../../../../../../../adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerConfig {

    /**
     * ShedLock {@link LockProvider} bean — 透過 JDBC 操作 {@code shedlock} 表互斥。
     *
     * <p>{@code usingDbTime()} 強制以資料庫端時鐘判斷 lock_until，避免應用端 JVM
     * 時鐘漂移問題；對 GCP Cloud SQL Auth Proxy 多 instance 部署為必要設定。
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
