package io.github.samzhu.skillshub.shared.aot;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

/**
 * AOT 編譯 + Paketo training run 階段的 stub bean 提供。
 *
 * <p>S132 ship — 解 Spring Boot 4 AOT processing 階段 @ConfigurationProperties
 * binding 不生效的問題。AOT processor 在 sort `@PreAuthorize` advisor 時強制
 * instantiate {@code methodSecurityExpressionHandler} →
 * {@code DelegatingPermissionEvaluator} → {@code SkillPermissionStrategy} →
 * 需要 {@link DataSource} bean。但 AOT 階段 Spring 跳過 ConfigurationProperties
 * binding，DataSourceProperties 拿不到 URL → DataSourceAutoConfiguration 失敗。
 *
 * <p>本 config 只在 {@code aot} profile 啟用 — runtime 用 {@code gcp,prod} 或
 * {@code gcp,lab} 蓋掉，永不在 runtime 啟用，正常 DataSourceAutoConfiguration
 * 走完整流程。
 *
 * @see io.github.samzhu.skillshub.skill.security.SkillPermissionStrategy
 */
@Configuration
@Profile("aot")
public class AotStubConfig {

    /**
     * Stub DataSource — 直接 new HikariDataSource，不走 ConfigurationProperties
     * binding。HikariCP 是 lazy（建構時不真連 DB），URL / driver 設好讓 bean
     * 建構成功即可，AOT 階段不會觸發 connection。
     */
    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/aot_stub");
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUsername("aot_stub");
        ds.setPassword("aot_stub");
        return ds;
    }
}
