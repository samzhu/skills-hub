package io.github.samzhu.skillshub.shared.aot;

import javax.sql.DataSource;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

/**
 * AOT 編譯 + native runtime 共用的 DataSource bean — 走 System.getenv 直連 process env vars，
 * 繞開 Spring Boot 4 AOT processing 對 {@code @ConfigurationProperties} eager-bean binding 失效
 * 的 framework 限制（spring-projects/spring-security#12653 同根問題）。
 *
 * <h2>背景：為何不用標準 Spring Boot autoconfig DataSource</h2>
 *
 * <p>Spring Boot 4 AOT processor 強制 eager instantiate {@code DataSourceConfiguration$Hikari →
 * dataSource()}，工廠方法呼叫 {@code DataSourceProperties.determineDriverClassName()}，要
 * URL 不為空。{@code DataSourceProperties} 是 {@code @ConfigurationProperties} 物件，但 AOT
 * 階段對 eager bean 跳過 property binding —— 不論餵 yaml / system property / CLI args 給
 * {@code spring.datasource.url} 全部失效。實證 build #8/#9/#10/#11 連續 4 次同 error 確認。
 *
 * <h2>本檔的設計</h2>
 *
 * <p>AOT processing 階段（build time）：env vars 沒設 → 用 stub URL。HikariCP 是 lazy connect，
 * ctor 不真連 DB，純粹通過 driver class 偵測。
 *
 * <p>Native runtime 階段：BeanInstanceSupplier 重新 invoke {@link #dataSource()} 工廠方法，
 * env vars {@code SPRING_DATASOURCE_URL/USERNAME/PASSWORD}（Cloud Run 部署設）有值 → 用真實
 * 連線資訊。直接走 {@link System#getenv} 而非 Spring {@code Environment} —— 避開任何 property
 * source / @CP binding 的不確定性，行為純 JVM 標準 API。
 *
 * <h2>Profile 設計</h2>
 *
 * <p>{@code @Profile("aot")}：AOT processing 啟用 aot,local profile，本 config active。Spring
 * AOT 把 baked active profile 寫進 {@code __ApplicationContextInitializer.addActiveProfile("aot")}
 * （per spring-projects/spring-boot#41562 / #48408 確認），所以 native runtime 也 active aot
 * profile，本 config 也 active —— 同個 dataSource bean 跨 build/runtime 共用，runtime 由 env
 * vars 切換真實值。
 *
 * <h2>Why not @Profile-less</h2>
 *
 * <p>JVM bootRun（dev）模式 active profile = local,dev，無 aot → 本 config disabled →
 * Spring Boot DataSourceAutoConfiguration 走 {@code spring.datasource.url=${skillshub.db.url}}
 * 標準路徑，不被本 stub 蓋。
 */
@Configuration
@Profile("aot")
public class AotStubConfig {

    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(getEnvOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://aot-stub:5432/aot_stub"));
        ds.setUsername(getEnvOrDefault("SPRING_DATASOURCE_USERNAME", "aot_stub"));
        ds.setPassword(getEnvOrDefault("SPRING_DATASOURCE_PASSWORD", "aot_stub"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    /**
     * Flyway migration 跳過策略 — AOT processing 階段用 stub DataSource（URL = aot-stub host
     * 無法 resolve）跑 migrate 會炸；改檢 env var，build 環境跳過、native runtime 才真跑。
     *
     * <p>背後機制：Spring Boot {@code FlywayMigrationInitializer.afterPropertiesSet} 會呼叫
     * {@code FlywayMigrationStrategy.migrate(flyway)}（默認 lambda = {@code Flyway::migrate}）。
     * 提供本 bean 後 default 被 override，build time 不真連 DB。
     *
     * <p>之前在 application-aot.yaml 設 {@code flyway.enabled=false} 會讓 autoconfig 整組
     * disable，beans 不 baked → native runtime 即使設 SPRING_FLYWAY_ENABLED=true 也救不回。
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            if (System.getenv("SPRING_DATASOURCE_URL") != null) {
                flyway.migrate();
            }
            // else: AOT processing 階段，跳過 migrate（沒真實 DB）
        };
    }

    private static String getEnvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
