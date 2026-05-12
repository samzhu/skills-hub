package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.security.test.context.support.WithMockUser;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;

/**
 * S016 T6 / S025a-T03 — SearchProjection 處理 SkillCreatedEvent 時把 owner 衍生 acl_entries 寫入 vector_store。
 *
 * <p>對應 spec §2.3 + §4.16：{@code aclEntries = List.of("user:" + owner + ":read")}
 * 從 owner 衍生（與 V2 backfill vector_store 邏輯一致）。
 *
 * <p><b>S025a-T03 migration</b>：
 * <ul>
 *   <li>移除 {@code @MockitoBean EmbeddingModel}（lift 至 {@link TestcontainersConfiguration}）</li>
 *   <li>移除 {@code @MockitoBean CurrentUserProvider} → 改用 {@link WithMockUser}（zero production
 *       code change：{@code CurrentUserProvider.current()} 已從 SecurityContextHolder 取，
 *       {@code AsyncListenerConfig} 已 wrap {@code DelegatingSecurityContextAsyncTaskExecutor}
 *       自動 propagate 到 async listener thread）</li>
 *   <li>{@code TestEventTxHelper.publishInTx} → {@code Scenario.publish(event).andWaitForStateChange(...)}</li>
 *   <li>{@code Awaitility 30s} → {@code ScenarioCustomizer 5s default}（per S025a §2.6 POC validated）</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.shared.security.CurrentUserProvider
 * @see io.github.samzhu.skillshub.shared.config.AsyncListenerConfig
 */
/**
 * S025b T02 — 同 {@link SearchProjectionTest} 模式：MODULE slice 對齊 S025a {@code AuditEventListenerTest}。
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "alice")
class SearchProjectionAclWriteTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1 vector_store: SearchProjection 處理 SkillCreatedEvent → vector_store.acl_entries 衍生自 author")
    @Tag("AC-1")
    void searchProjectionWritesAclEntriesFromAuthor(Scenario scenario) {
        var skillId = UUID.randomUUID().toString();
        var name = "search-acl-" + skillId.substring(0, 8);
        // S025b T02：MODULE slice 不載 skill module beans，FK 前置改 raw JdbcTemplate INSERT
        // （取代既有 skillRepo.save(Skill.fromRow(...))，行為等價，避開 cross-module bean 依賴）
        var ts = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count,
                                    created_at, updated_at, acl_entries, owner_id)
                VALUES (?, ?, 'search projection ACL write fixture', 'alice', 'testing',
                        'DRAFT', 0, ?, ?, '[]'::jsonb, 'alice')
                """, skillId, name, ts, ts);

        scenario.publish(new SkillCreatedEvent(skillId, name,
                        "search projection ACL write fixture", "alice", "testing"))
                .andWaitForStateChange(() -> aclJsonOrNull(skillId))
                .andVerify(aclJson -> assertThat(aclJson).contains("user:alice:read"));
    }

    /**
     * Scenario.andWaitForStateChange supplier — 等 SearchProjection async listener 寫入 vector_store。
     * 回 null 表示 row 還沒出現；非 null 表示 row 已存在，wait 結束。
     */
    private String aclJsonOrNull(String skillId) {
        var rows = jdbc.queryForList(
                "SELECT acl_entries::text AS acl FROM vector_store WHERE id = ?::uuid",
                skillId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("acl");
    }
}
