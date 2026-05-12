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
 * S159b-T03 Round 2 — V21 Flyway migration verify。
 *
 * <p>對應 spec §7.5c：Round 1 lossy capitalize 解 `"DevOps"` → `"Devops"` UX regression（V07 抓到）；
 * Round 2 加 dual-column 設計 — V21 加 `category_display VARCHAR(50)` 保留原始 case，
 * `initcap()` backfill 既有 row（lossy best-effort，dev/LAB 可接受）。
 *
 * <p>覆蓋：
 * <ul>
 *   <li>AC-R2-1: schema column 存在 + nullable + max length 50</li>
 *   <li>AC-R2-1: Flyway schema_history 含 V21 row（success=true）</li>
 *   <li>AC-R2-1: backfill 對既有 row 跑 `initcap(category)`（drop V20 CHECK 後 seed dirty + verify）</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.db.V20MigrationTest Round 1 baseline pattern
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V21MigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-R2-1: skills.category_display VARCHAR(50) NULLABLE column 存在")
    @Tag("AC-R2-1")
    void categoryDisplayColumn_existsWithCorrectMetadata() {
        var col = jdbc.queryForMap("""
                SELECT data_type, is_nullable, character_maximum_length
                FROM   information_schema.columns
                WHERE  table_name = 'skills' AND column_name = 'category_display'
                """, Map.of());

        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(col.get("is_nullable"))
                .as("nullable — 舊 row 無 categoryDisplay；frontend 走 capitalize fallback")
                .isEqualTo("YES");
        assertThat(col.get("character_maximum_length"))
                .as("對齊 category 欄位長度上限")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("AC-R2-1: Flyway schema_history 含 V21 row（success=true）")
    @Tag("AC-R2-1")
    void flywaySchemaHistory_containsV21() {
        var v21 = jdbc.queryForMap("""
                SELECT version, success, description
                FROM   flyway_schema_history
                WHERE  version = '21'
                """, Map.of());

        assertThat((Boolean) v21.get("success")).isTrue();
        assertThat(((String) v21.get("description")).toLowerCase())
                .containsAnyOf("category_display", "display", "category");
    }

    @Test
    @DisplayName("AC-R2-1: V21 backfill SQL 對既有 row 走 initcap(category) lossy best-effort")
    @Tag("AC-R2-1")
    void v21BackfillSql_initcapsExistingRows() {
        // 模擬 V20 已套用、V21 之前的狀態：existing row 有 lowercase category 但 category_display=NULL
        // 跑 V21 backfill SQL（與 V21__add_category_display.sql 內 UPDATE 一致）驗 initcap 行為
        var ids = List.of(
                "v21test-" + UUID.randomUUID().toString().substring(0, 8),
                "v21test-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            var now = Instant.now();
            // seed 兩列 lowercase + category_display=NULL（模擬 V20-only state）
            seedRow(ids.get(0), "devops", null, now);
            seedRow(ids.get(1), "testing", null, now);

            // run V21 backfill SQL
            int affected = jdbc.getJdbcTemplate().update(
                    "UPDATE skills SET category_display = initcap(category) WHERE category IS NOT NULL AND category_display IS NULL");
            assertThat(affected).as("seed 兩列應被 UPDATE").isGreaterThanOrEqualTo(2);

            var values = jdbc.queryForList(
                    "SELECT category_display FROM skills WHERE id IN (:ids) ORDER BY id",
                    new MapSqlParameterSource("ids", ids),
                    String.class);
            assertThat(values)
                    .as("initcap 首字母大寫；'devops'→'Devops'、'testing'→'Testing'")
                    .containsOnly("Devops", "Testing");
        } finally {
            jdbc.update("DELETE FROM skills WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ids));
        }
    }

    /**
     * 直接 INSERT 一筆 skills row。schema 已對齊 V20 CHECK + V21 category_display；
     * V20 CHECK 要求 category 必為 lowercase（測試裡 input 也 lowercase）。
     */
    private void seedRow(String id, String category, String categoryDisplay, Instant now) {
        jdbc.update("""
                INSERT INTO skills
                  (id, name, description, author, category, category_display, status,
                   download_count, created_at, updated_at, acl_entries, owner_id)
                VALUES (:id, :name, 'V21MigrationTest fixture', 'v21-tester',
                        :cat, :catDisp, 'DRAFT', 0, :ts, :ts, '[]'::jsonb, 'v21-tester')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", "v21-" + id.substring(0, 12))
                .addValue("cat", category)
                .addValue("catDisp", categoryDisplay)
                .addValue("ts", java.sql.Timestamp.from(now)));
    }
}
