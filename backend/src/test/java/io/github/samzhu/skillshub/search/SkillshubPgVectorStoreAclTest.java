package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 T6 — {@link SkillshubPgVectorStore} INSERT_SQL 升 7 欄（含 acl_entries）+
 * ON CONFLICT 保留 acl_entries 行為驗證。
 *
 * <p>對應 spec §4.16：{@code INSERT INTO vector_store (..., acl_entries)} 7-col 寫入；
 * {@code ON CONFLICT (id) DO UPDATE SET acl_entries = COALESCE(EXCLUDED.acl_entries, vector_store.acl_entries)}
 * 確保 re-embed 場景不覆蓋已 grant 的 entries。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillshubPgVectorStoreAclTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillRepository skillRepo;
    // S025a-T03: 從 @MockitoBean 改 @Autowired — TestcontainersConfiguration.@Bean @Primary
    // mockEmbeddingModel() 提供 stub；test 需傳給 SkillshubPgVectorStore.builder()。
    @Autowired private EmbeddingModel embeddingModel;

    @Test
    @DisplayName("AC-1 vector_store: SkillshubPgVectorStore.add() 一次 7-欄 INSERT 含 acl_entries")
    @Tag("AC-1")
    void singleInsertWritesAclEntries() {
        var skillId = seedSkillFk();

        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("alice")
                .skillId(skillId)
                .aclEntries(List.of("user:alice:read"))
                .build()
                .add(List.of(Document.builder()
                        .id(skillId)
                        .text("acl write test body")
                        .metadata(Map.of("skillId", skillId, "name", "acl-test"))
                        .build()));

        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson).contains("user:alice:read");
    }

    @Test
    @DisplayName("AC-1 vector_store: ON CONFLICT 用 COALESCE 保留既有 acl_entries（re-embed 不覆蓋）")
    @Tag("AC-1")
    void onConflictPreservesExistingAclEntriesViaCoalesce() {
        var skillId = seedSkillFk();

        // 第 1 次寫入：帶 acl_entries（user + group 兩條）
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("alice")
                .skillId(skillId)
                .aclEntries(List.of("user:alice:read", "group:engineering:read"))
                .build()
                .add(List.of(Document.builder()
                        .id(skillId).text("v1").metadata(Map.of()).build()));

        // 第 2 次寫入：不帶 aclEntries — 模擬 re-embed batch（不該覆寫已存在的 acl）
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("alice")
                .skillId(skillId)
                .build()
                .add(List.of(Document.builder()
                        .id(skillId).text("v2").metadata(Map.of()).build()));

        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson)
                .contains("user:alice:read")
                .contains("group:engineering:read");
    }

    @Test
    @DisplayName("AC-3 vector_store: 不帶 aclEntries 預設寫入空 array（fail-secure）")
    @Tag("AC-3")
    void unsetAclEntriesDefaultsToEmptyArray() {
        var skillId = seedSkillFk();

        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("alice")
                .skillId(skillId)
                // 故意不調 .aclEntries(...)
                .build()
                .add(List.of(Document.builder()
                        .id(skillId).text("default empty acl").metadata(Map.of()).build()));

        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson).isEqualTo("[]");
    }

    private String seedSkillFk() {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                skillId, "vec-acl-" + skillId.substring(0, 8),
                "vector_store ACL test fixture", "alice", "Testing",
                null, null, "DRAFT", 0L, now, now, List.of(), null));
        return skillId;
    }

    // S025a-T03: randomVector helper removed — lifted to TestcontainersConfiguration.
}
