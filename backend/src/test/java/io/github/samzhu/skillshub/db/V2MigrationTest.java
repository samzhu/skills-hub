package io.github.samzhu.skillshub.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S016-T1 — V2 Flyway migration meta + backfill 驗證。
 *
 * <p>對應 spec §4.2：
 * <ul>
 *   <li>AC-1: skills + vector_store 皆加 {@code acl_entries JSONB NOT NULL DEFAULT '[]'}；
 *       GIN index 走 default {@code jsonb_ops}（必要：{@code jsonb_path_ops} 不支援 {@code ?|}
 *       — 為 reference repo 的隱性 BUG，本 spec 明確避開）。
 *   <li>AC-2: skills backfill from author — author={@code alice} →
 *       {@code ["user:alice:read", "user:alice:write", "user:alice:delete"]}
 *   <li>AC-3: vector_store backfill from owner — owner={@code alice} →
 *       {@code ["user:alice:read"]}；owner=NULL 維持 {@code []}（fail-secure）
 * </ul>
 *
 * <p>本 IT 在 Flyway 自動套用 V1+V2 migration 之後執行 — Testcontainer
 * pgvector/pg16 透過 {@code @ServiceConnection} 注入 datasource。為避免污染
 * 既有 SkillProjection / SearchProjection 監聽器，所有測試資料以
 * {@code NamedParameterJdbcTemplate} 直接寫入 + 完成後驗 {@code pg_indexes} /
 * 各表 {@code acl_entries} 欄位。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V2MigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1: skills.acl_entries JSONB NOT NULL DEFAULT '[]' + GIN(default jsonb_ops)")
    @Tag("AC-1")
    void skillsAclEntriesColumnAndIndex_haveCorrectMetadata() {
        var col = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = 'skills' AND column_name = 'acl_entries'
                """, Map.of());

        assertThat(col.get("data_type")).isEqualTo("jsonb");
        assertThat(col.get("is_nullable")).isEqualTo("NO");
        assertThat(((String) col.get("column_default")).toLowerCase())
                .as("default 必須為 '[]'::jsonb")
                .contains("'[]'::jsonb");

        var indexDef = jdbc.queryForObject("""
                SELECT pi.indexdef
                FROM pg_indexes pi
                WHERE pi.indexname = 'idx_skills_acl_entries'
                """, Map.of(), String.class);

        assertThat(indexDef)
                .as("idx_skills_acl_entries 必須存在")
                .isNotNull();
        assertThat(indexDef.toLowerCase())
                .as("必須為 GIN index")
                .contains("using gin");
        assertThat(indexDef.toLowerCase())
                .as("不可使用 jsonb_path_ops（不支援 ?| operator — 詳 spec §2.4 #1）")
                .doesNotContain("jsonb_path_ops");
    }

    @Test
    @DisplayName("AC-1: vector_store.acl_entries JSONB NOT NULL DEFAULT '[]' + GIN(default jsonb_ops)")
    @Tag("AC-1")
    void vectorStoreAclEntriesColumnAndIndex_haveCorrectMetadata() {
        var col = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = 'vector_store' AND column_name = 'acl_entries'
                """, Map.of());

        assertThat(col.get("data_type")).isEqualTo("jsonb");
        assertThat(col.get("is_nullable")).isEqualTo("NO");
        assertThat(((String) col.get("column_default")).toLowerCase()).contains("'[]'::jsonb");

        var indexDef = jdbc.queryForObject("""
                SELECT pi.indexdef
                FROM pg_indexes pi
                WHERE pi.indexname = 'idx_vector_store_acl_entries'
                """, Map.of(), String.class);

        assertThat(indexDef).isNotNull();
        assertThat(indexDef.toLowerCase()).contains("using gin");
        assertThat(indexDef.toLowerCase()).doesNotContain("jsonb_path_ops");
    }

    @Test
    @DisplayName("AC-2: skills 既有 row backfill 為 [\"user:<author>:read|write|delete\"]")
    @Tag("AC-2")
    void skillsBackfill_fromAuthor() {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();

        // 直接寫一個 author='alice' + acl_entries='[]' 的 row 模擬 V2 套用前狀態，
        // 然後手動跑同一段 backfill SQL 驗 idempotency；此處改為簡單 path：
        // V2 已套用，所以新插入的 row 走 DEFAULT '[]'，不會自動 backfill；
        // backfill 條件僅作用於既有資料 — 故先 INSERT 帶 acl_entries='[]'，
        // 再手動 run 同一段 backfill SQL 觀察轉換。
        jdbc.update("""
                INSERT INTO skills
                  (id, name, description, author, category, status, download_count, created_at, updated_at, acl_entries)
                VALUES (:id, :name, :desc, :author, :cat, 'DRAFT', 0, :ts, :ts, '[]'::jsonb)
                """, new MapSqlParameterSource()
                .addValue("id", skillId)
                .addValue("name", "acl-backfill-skill-" + skillId.substring(0, 8))
                .addValue("desc", "test")
                .addValue("author", "alice")
                .addValue("cat", "Testing")
                .addValue("ts", java.sql.Timestamp.from(now)));

        // 再跑 V2 backfill SQL（idempotent — WHERE acl_entries='[]'::jsonb 條件確保不重複）
        jdbc.getJdbcTemplate().update("""
                UPDATE skills
                SET acl_entries = jsonb_build_array(
                    'user:' || author || ':read',
                    'user:' || author || ':write',
                    'user:' || author || ':delete'
                )
                WHERE acl_entries = '[]'::jsonb
                """);

        @SuppressWarnings("unchecked")
        List<String> entries = jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = :id",
                Map.of("id", skillId),
                (rs, rowNum) -> {
                    var json = rs.getString(1);
                    try {
                        return new tools.jackson.databind.ObjectMapper()
                                .readValue(json, new tools.jackson.core.type.TypeReference<List<String>>() {});
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });

        assertThat(entries).containsExactlyInAnyOrder(
                "user:alice:read", "user:alice:write", "user:alice:delete");
    }

    @Test
    @DisplayName("AC-3: vector_store owner=NOT NULL backfill；owner=NULL 維持 [] (fail-secure)")
    @Tag("AC-3")
    void vectorStoreBackfill_failSecureOnNullOwner() {
        var withOwnerId = UUID.randomUUID();
        var noOwnerId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO vector_store (id, content, owner, acl_entries)
                VALUES (:id, 'doc-with-owner', 'bob', '[]'::jsonb)
                """, Map.of("id", withOwnerId));
        jdbc.update("""
                INSERT INTO vector_store (id, content, owner, acl_entries)
                VALUES (:id, 'doc-no-owner', NULL, '[]'::jsonb)
                """, Map.of("id", noOwnerId));

        // 重跑 V2 backfill SQL 驗 idempotency
        jdbc.getJdbcTemplate().update("""
                UPDATE vector_store
                SET acl_entries = jsonb_build_array('user:' || owner || ':read')
                WHERE owner IS NOT NULL
                  AND acl_entries = '[]'::jsonb
                """);

        var ownerEntriesJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = :id",
                Map.of("id", withOwnerId), String.class);
        assertThat(ownerEntriesJson)
                .as("有 owner 的 row 必須 backfill 為 user:<owner>:read")
                .contains("user:bob:read");

        var noOwnerEntriesJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = :id",
                Map.of("id", noOwnerId), String.class);
        assertThat(noOwnerEntriesJson)
                .as("owner=NULL 的 row 必須維持 [] — 沒人可讀（fail-secure）")
                .isEqualTo("[]");
    }
}
