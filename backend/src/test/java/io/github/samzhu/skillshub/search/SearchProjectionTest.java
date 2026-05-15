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
import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
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
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
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
        seedSkillRow(skillId, skillName, "管理 Docker 容器", "sam", "devops");
    }

    private void seedSkillRow(String id, String name, String description,
                              String author, String category) {
        var ts = Timestamp.from(Instant.now());
        // S114a: owner_id NOT NULL (V16 schema) — derive from author
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count,
                                    created_at, updated_at, acl_entries, owner_id)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 0, ?, ?, '[]'::jsonb, ?)
                """, id, name, description, author, category, ts, ts, author);
    }

    @Test
    @DisplayName("AC-3: SkillCreatedEvent → vector_store row 含正確 metadata + owner + skill_id")
    @Tag("AC-3")
    void onSkillCreated_writesRowWithMetadataOwnerAndSkillId(Scenario scenario) {
        var event = new SkillCreatedEvent(skillId, skillName, "管理 Docker 容器", "sam", "devops");

        scenario.publish(event)
                .andWaitForStateChange(() -> rowOrNull(skillId))
                .andVerify(row -> {
                    assertThat(row.get("content")).isEqualTo(skillName + " 管理 Docker 容器");
                    // S034: owner 從 event.author() 取（不再依賴 SecurityContext propagation）
                    assertThat(row.get("owner")).isEqualTo("sam");
                    assertThat(row.get("skill_id")).isEqualTo(skillId);
                });
    }

    @Test
    @DisplayName("AC-4: SkillVersionPublishedEvent → delete+add 重寫 row 為新版本 frontmatter")
    @Tag("AC-4")
    void onVersionPublished_deletesAndReWritesWithFrontmatter(Scenario scenario) {
        // 先用 SkillCreatedEvent seed 一筆初版（async）— Scenario.publish 等 async 完成
        scenario.publish(new SkillCreatedEvent(skillId, skillName, "舊描述", "sam", "devops"))
                .andWaitForStateChange(() -> rowOrNull(skillId));

        var frontmatter = Map.<String, Object>of(
                "name", skillName,
                "description", "新版：管理 Docker 容器與 Compose",
                "author", "sam",
                "category", "devops");
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
                    // S034: owner 從 aggregate.author 取（setUp seeded skills row author='sam'）
                    assertThat(row.get("owner")).isEqualTo("sam");
                    assertThat(row.get("skill_id")).isEqualTo(skillId);
                });
    }

    /**
     * S034 rewrite — owner 不再來自 SecurityContext，而是 event.author()。
     * 兩個不同 author 的 SkillCreatedEvent 應各自獨立寫入；row1 不被 row2 污染（per-request builder isolation）。
     */
    @Test
    @DisplayName("AC-3: 多個 SkillCreatedEvent → 各自獨立 row（owner 來自 event.author，不被互相污染）")
    @Tag("AC-3")
    void onSkillCreated_multipleSkillsHaveIndependentOwnerState(Scenario scenario) {
        var skillId2 = UUID.randomUUID().toString();
        var skillName2 = "k8s-helper-" + UUID.randomUUID();
        seedSkillRow(skillId2, skillName2, "管理 K8s", "jane", "devops");

        scenario.publish(new SkillCreatedEvent(skillId, skillName, "...", "sam", "devops"))
                .andWaitForStateChange(() -> ownerOrNull(skillId))
                .andVerify(owner -> assertThat(owner).isEqualTo("sam"));

        scenario.publish(new SkillCreatedEvent(skillId2, skillName2, "...", "jane", "devops"))
                .andWaitForStateChange(() -> ownerOrNull(skillId2))
                .andVerify(owner -> assertThat(owner).isEqualTo("jane"));

        // 二次驗證：第 1 個 row 的 owner 不被第 2 次寫入污染（per-request builder isolation）
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM vector_store WHERE id = ?::uuid", String.class, skillId))
                .isEqualTo("sam");
    }

    @Test
    @DisplayName("AC-S033: SkillSuspendedEvent → vector_store row 被刪")
    @Tag("AC-S033")
    void onSkillSuspended_deletesVectorStoreRow(Scenario scenario) {
        // Phase 1: 先有一筆 vector_store row（via SkillCreatedEvent）
        scenario.publish(new SkillCreatedEvent(skillId, skillName, "管理 Docker 容器", "sam", "devops"))
                .andWaitForStateChange(() -> rowOrNull(skillId));

        // Phase 2: publish SkillSuspendedEvent → 等 row 被刪
        scenario.publish(new SkillSuspendedEvent(skillId, "policy", "admin"))
                .andWaitForStateChange(() -> rowOrNull(skillId) == null ? "deleted" : null)
                .andVerify(token -> assertThat(token).isEqualTo("deleted"));
    }

    @Test
    @DisplayName("AC-S033: SkillReactivatedEvent → vector_store row 重新 embed（保留 is_public）")
    @Tag("AC-S033")
    void onSkillReactivated_reEmbedsVectorStoreRow(Scenario scenario) {
        // Phase 1: 透過 prod 路徑（SkillRepository.save）seed skill aggregate + 一筆 SkillVersion
        // 已 seed skills row by setUp()；額外 INSERT 一筆 skill_versions
        var versionId = UUID.randomUUID().toString();
        var ts = Timestamp.from(Instant.now());
        var frontmatterJson = String.format(
                "{\"name\":\"%s\",\"description\":\"管理 Docker 容器\",\"author\":\"sam\",\"category\":\"DevOps\"}",
                skillName);
        jdbc.update("""
                INSERT INTO skill_versions (id, skill_id, version, storage_path, file_size,
                                            frontmatter, published_at, allowed_tools)
                VALUES (?, ?, '1.0.0', 'gs://bucket/p', 100, ?::jsonb, ?, '[]'::jsonb)
                """, versionId, skillId, frontmatterJson, ts);

        // 起始：vector_store 無 row（skill 為 SUSPENDED 狀態）— 直接更新 skills.status = SUSPENDED
        jdbc.update("UPDATE skills SET status = 'SUSPENDED' WHERE id = ?", skillId);
        assertThat(rowOrNull(skillId)).isNull();

        // Phase 2: 發 reactivate event；listener 應重 embed vector_store row
        // 注意：onSkillReactivated 從 skill aggregate 取 author='sam'（不依賴 SecurityContext，per S025b §7 tech debt 落地）
        jdbc.update("UPDATE skills SET status = 'PUBLISHED', is_public = TRUE WHERE id = ?", skillId);
        scenario.publish(new SkillReactivatedEvent(skillId, "approved"))
                .andWaitForStateChange(() -> rowOrNull(skillId))
                .andVerify(row -> {
                    assertThat(row.get("skill_id")).isEqualTo(skillId);
                    var ownerVal = row.get("owner");
                    assertThat(ownerVal).isEqualTo("sam");
                    var aclJson = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                            String.class, skillId);
                    assertThat(aclJson).contains("user:sam:read");
                    assertThat(aclJson).doesNotContain("public:*:read");
                    var publicSkill = jdbc.queryForObject(
                            "SELECT is_public FROM vector_store WHERE id = ?::uuid",
                            Boolean.class, skillId);
                    assertThat(publicSkill).isTrue();
                });
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
