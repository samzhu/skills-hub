package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * AC-3, AC-4 驗證：SearchProjection 監聽領域事件並透過 {@link SkillshubPgVectorStore}
 * 寫入 vector_store 表（owner / skill_id 隨 6-欄 INSERT 一次寫入）。
 *
 * <p>S025a-T03 migration（從 S023-T07 disabled state 恢復 + Scenario）：
 * <ul>
 *   <li>{@code @MockitoBean CurrentUserProvider} → method-level {@link WithMockUser} —
 *       prod {@code CurrentUserProvider} 已從 SecurityContextHolder 取，
 *       {@code DelegatingSecurityContextAsyncTaskExecutor} 自動 propagate 到 async thread</li>
 *   <li>{@code projection.onSkillCreated(event)} 直接呼叫 → {@code Scenario.publish(event)}
 *       讓 listener 從 outbox 觸發（更貼近 prod 行為）</li>
 *   <li>{@code Awaitility 30s} → {@code Scenario 5s default}</li>
 *   <li>S023-T07 disabled method（line 127）恢復 — 用 programmatic SecurityContextHolder
 *       切換 + Scenario 序列化兩次發布驗 per-request builder isolation</li>
 * </ul>
 *
 * <p>**FK 前置**：vector_store.skill_id REFERENCES skills(id) ON DELETE CASCADE。
 * 每個 test 用 SkillRepository 寫一筆 skills row 滿足 FK。
 *
 * @see SearchProjection
 * @see SkillshubPgVectorStore
 * @see io.github.samzhu.skillshub.shared.security.CurrentUserProvider
 */
/**
 * S025b T02 — {@code @SpringBootTest + @EnableScenarios} → {@code @ApplicationModuleTest(DIRECT_DEPENDENCIES)}：
 * 對齊 S025a {@link io.github.samzhu.skillshub.audit.AuditEventListenerTest} pattern，slice 載 search
 * module + 直接依賴；{@code @ApplicationModuleTest} 內建 {@code ScenarioParameterResolver}（{@code @EnableScenarios}
 * 不再需要）。{@code @WithMockUser} 維持 — 不影響 cache key（TestExecutionListener，非 ContextCustomizer）。
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "test-owner")
class SearchProjectionTest {

    @Autowired private JdbcTemplate jdbc;

    private String skillId;
    private String skillName;

    @BeforeEach
    void setUp() {
        // 每個 test 用獨立 UUID + 隨機 name（skills.name 有 UNIQUE constraint，避免跨測試衝突）
        skillId = UUID.randomUUID().toString();
        skillName = "docker-helper-" + UUID.randomUUID();
        // S025b T02：MODULE slice 不載 skill module beans，FK 前置改 raw JdbcTemplate INSERT。
        seedSkillRow(skillId, skillName, "管理 Docker 容器", "sam", "DevOps");
    }

    private void seedSkillRow(String id, String name, String description,
                              String author, String category) {
        var ts = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count,
                                    created_at, updated_at, acl_entries)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 0, ?, ?, '[]'::jsonb)
                """, id, name, description, author, category, ts, ts);
    }

    @Test
    @DisplayName("AC-3: SkillCreatedEvent → vector_store row 含正確 metadata + owner + skill_id")
    @Tag("AC-3")
    void onSkillCreated_writesRowWithMetadataOwnerAndSkillId(Scenario scenario) {
        var event = new SkillCreatedEvent(skillId, skillName, "管理 Docker 容器", "sam", "DevOps");

        scenario.publish(event)
                .andWaitForStateChange(() -> rowOrNull(skillId))
                .andVerify(row -> {
                    assertThat(row.get("content")).isEqualTo(skillName + " 管理 Docker 容器");
                    assertThat(row.get("owner")).isEqualTo("test-owner");
                    assertThat(row.get("skill_id")).isEqualTo(skillId);
                });
    }

    @Test
    @DisplayName("AC-4: SkillVersionPublishedEvent → delete+add 重寫 row 為新版本 frontmatter")
    @Tag("AC-4")
    void onVersionPublished_deletesAndReWritesWithFrontmatter(Scenario scenario) {
        // 先用 SkillCreatedEvent seed 一筆初版（async）— Scenario.publish 等 async 完成
        scenario.publish(new SkillCreatedEvent(skillId, skillName, "舊描述", "sam", "DevOps"))
                .andWaitForStateChange(() -> rowOrNull(skillId));

        var frontmatter = Map.<String, Object>of(
                "name", skillName,
                "description", "新版：管理 Docker 容器與 Compose",
                "author", "sam",
                "category", "DevOps");
        var event = SkillVersionPublishedEvent.of(
                skillId, "2.0.0", "skills/" + skillId + "/2.0.0.zip", 1024L, frontmatter, List.of());

        // delete-then-add 後：應該只有 1 row（同 id）但 content 已更新為新版描述
        scenario.publish(event)
                .andWaitForStateChange(
                        () -> rowOrNull(skillId),
                        row -> row != null && row.get("content").toString().contains("新版"))
                .andVerify(row -> {
                    var rows = jdbc.queryForList(
                            "SELECT content, owner, skill_id FROM vector_store WHERE id = ?::uuid",
                            skillId);
                    assertThat(rows).hasSize(1);
                    assertThat(row.get("content"))
                            .isEqualTo(skillName + " 新版：管理 Docker 容器與 Compose");
                    assertThat(row.get("owner")).isEqualTo("test-owner");
                    assertThat(row.get("skill_id")).isEqualTo(skillId);
                });
    }

    /**
     * S025a-T03 recovered from S023-T07 disabled state（spec §3 AC-6）— 驗 per-request
     * builder pattern 鎖 owner 在 instance 中，不會被後續寫入污染。
     *
     * <p>關鍵：{@code DelegatingSecurityContextAsyncTaskExecutor} 在 async task 排隊時
     * snapshot 當前 SecurityContext。本 test 順序設定不同 SecurityContext + 序列化
     * Scenario.publish + andWaitForStateChange，確保第一次 listener 完成後才設第二次 context，
     * 避免 race。
     */
    @Test
    @DisplayName("AC-3: 多個 SkillCreatedEvent → 各自獨立 row（per-request instance 不共用 owner state）")
    @Tag("AC-3")
    void onSkillCreated_multipleSkillsHaveIndependentOwnerState(Scenario scenario) {
        var skillId2 = UUID.randomUUID().toString();
        var skillName2 = "k8s-helper-" + UUID.randomUUID();
        seedSkillRow(skillId2, skillName2, "管理 K8s", "jane", "DevOps");

        // Phase 1: SecurityContext = test-owner → publish event for skillId → wait → verify owner
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-owner", null, List.of()));

        scenario.publish(new SkillCreatedEvent(skillId, skillName, "...", "sam", "DevOps"))
                .andWaitForStateChange(() -> ownerOrNull(skillId))
                .andVerify(owner -> assertThat(owner).isEqualTo("test-owner"));

        // Phase 2: SecurityContext = other-owner → publish event for skillId2 → wait → verify owner
        // 此時第一次 listener 已完成（Scenario wait ensure），切換 context 不會 race。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("other-owner", null, List.of()));

        scenario.publish(new SkillCreatedEvent(skillId2, skillName2, "...", "jane", "DevOps"))
                .andWaitForStateChange(() -> ownerOrNull(skillId2))
                .andVerify(owner -> assertThat(owner).isEqualTo("other-owner"));

        // 二次驗證：第 1 個 row 的 owner 不被第 2 次寫入污染（per-request isolation）
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM vector_store WHERE id = ?::uuid", String.class, skillId))
                .isEqualTo("test-owner");
    }

    /**
     * Scenario.andWaitForStateChange supplier — 等 vector_store row 出現；回 null 表示尚未寫入。
     */
    private Map<String, Object> rowOrNull(String id) {
        var rows = jdbc.queryForList(
                "SELECT content, owner, skill_id FROM vector_store WHERE id = ?::uuid", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Scenario supplier — 取 vector_store.owner（非 null 表示 listener 已寫入）。 */
    private String ownerOrNull(String id) {
        var rows = jdbc.queryForList(
                "SELECT owner FROM vector_store WHERE id = ?::uuid", id);
        return rows.isEmpty() ? null : (String) rows.get(0).get("owner");
    }
}
