package io.github.samzhu.skillshub.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S159b-T01 — V20 Flyway migration backfill + CHECK constraint 驗證。
 *
 * <p>對應 spec §3：
 * <ul>
 *   <li>AC-1: V20 backfill `UPDATE skills SET category = lower(trim(category))`
 *       — 模擬既存 mix-case row 透過 V20 SQL 統一為 lowercase（drop CHECK + seed dirty
 *       + run backfill + verify + restore CHECK，全程 try/finally 確保 state 還原）</li>
 *   <li>AC-2: V20 加 `skills_category_lowercase` CHECK constraint — 任何繞 aggregate
 *       直接 INSERT 大寫 category 的 SQL 拒收（DataIntegrityViolationException）</li>
 *   <li>Flyway schema_history 含 V20 row（success=true）— 確認 migration 完整套用</li>
 * </ul>
 *
 * <p>本 IT 在 Flyway 自動套用 V1-V20 之後執行 — Testcontainer pgvector/pg16 透過
 * {@code @ServiceConnection} 注入 datasource。
 *
 * @see io.github.samzhu.skillshub.db.V18MigrationTest 既有同 pattern reference
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V20MigrationTest {

    private static final String CHECK_NAME = "skills_category_lowercase";
    private static final String ADD_CHECK_SQL = """
            ALTER TABLE skills ADD CONSTRAINT %s
              CHECK (category IS NULL OR category = lower(category))
            """.formatted(CHECK_NAME);
    private static final String DROP_CHECK_SQL =
            "ALTER TABLE skills DROP CONSTRAINT IF EXISTS " + CHECK_NAME;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1: Flyway schema_history 含 V20 row（success=true，description 含 'normalize'）")
    @Tag("AC-1")
    void flywaySchemaHistory_containsV20() {
        var v20 = jdbc.queryForMap("""
                SELECT version, success, description
                FROM   flyway_schema_history
                WHERE  version = '20'
                """, Map.of());

        assertThat((Boolean) v20.get("success"))
                .as("V20 必須成功套用")
                .isTrue();
        assertThat(((String) v20.get("description")).toLowerCase())
                .as("description 從 file name 推導，含 'normalize'/'category' 關鍵字")
                .containsAnyOf("normalize", "category");
    }

    @Test
    @DisplayName("AC-1: V20 backfill SQL 把 mix-case row UPDATE 為 lower(trim(...))")
    @Tag("AC-1")
    void v20BackfillSql_lowercasesMixedCaseRows() {
        // 模擬 V20 套用 *之前* 的狀態：drop CHECK 後 INSERT 帶 leading/trailing space + 大小寫
        // 變體的 dirty row。再手動跑 V20 backfill 同段 SQL，驗 lower(trim) 行為正確。
        // try/finally 確保 CHECK + dirty row 還原，不污染其他 test。
        var ids = List.of(
                "v20test-" + UUID.randomUUID().toString().substring(0, 8),
                "v20test-" + UUID.randomUUID().toString().substring(0, 8),
                "v20test-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            jdbc.getJdbcTemplate().execute(DROP_CHECK_SQL);

            var now = Instant.now();
            seedDirtyRow(ids.get(0), "Testing", now);
            seedDirtyRow(ids.get(1), " DEVOPS ", now);
            seedDirtyRow(ids.get(2), "Documentation", now);

            // 跑 V20 backfill SQL（與 V20__normalize_skill_category.sql 內 UPDATE 一致）
            int affected = jdbc.getJdbcTemplate().update(
                    "UPDATE skills SET category = lower(trim(category)) WHERE category IS NOT NULL");
            assertThat(affected).as("dirty row 全 UPDATE").isGreaterThanOrEqualTo(3);

            var values = jdbc.queryForList(
                    "SELECT category FROM skills WHERE id IN (:ids) ORDER BY id",
                    new MapSqlParameterSource("ids", ids),
                    String.class);
            assertThat(values)
                    .as("backfill 後全 lowercase + trim")
                    .containsOnly("testing", "devops", "documentation");
        } finally {
            jdbc.update("DELETE FROM skills WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
            jdbc.getJdbcTemplate().execute(ADD_CHECK_SQL);
        }
    }

    @Test
    @DisplayName("AC-2: CHECK constraint 拒收直接 INSERT 大寫 category（繞 aggregate）")
    @Tag("AC-2")
    void checkConstraint_rejectsRawUppercaseInsert() {
        var rejected = "v20test-" + UUID.randomUUID().toString().substring(0, 8);
        var now = Instant.now();
        try {
            assertThatThrownBy(() -> seedDirtyRow(rejected, "Testing", now))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(CHECK_NAME);
        } finally {
            // 若 INSERT 真的失敗，row 不存在；DELETE 安全 no-op
            jdbc.update("DELETE FROM skills WHERE id = :id", Map.of("id", rejected));
        }
    }

    @Test
    @DisplayName("AC-2: CHECK constraint 接受 lowercase + NULL（V1 column 允許 NULL）")
    @Tag("AC-2")
    void checkConstraint_acceptsLowercaseAndNull() {
        var idLower = "v20test-" + UUID.randomUUID().toString().substring(0, 8);
        var idNull = "v20test-" + UUID.randomUUID().toString().substring(0, 8);
        var now = Instant.now();
        try {
            seedDirtyRow(idLower, "testing", now);
            seedDirtyRow(idNull, null, now);

            var rows = jdbc.queryForList(
                    "SELECT id, category FROM skills WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", List.of(idLower, idNull)));
            assertThat(rows).as("lowercase + NULL 兩列皆成功 INSERT").hasSize(2);
        } finally {
            jdbc.update("DELETE FROM skills WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", List.of(idLower, idNull)));
        }
    }

    /**
     * 直接 raw INSERT 一筆 skills row（繞 aggregate）。schema 對齊 V1 + V2 acl_entries +
     * V18 author_name_snapshot — 帶最少必填欄位 + 預設值即可。
     */
    private void seedDirtyRow(String id, String category, Instant now) {
        jdbc.update("""
                INSERT INTO skills
                  (id, name, description, author, category, status, download_count,
                   created_at, updated_at, acl_entries, owner_id)
                VALUES (:id, :name, 'V20MigrationTest fixture', 'v20-tester',
                        :cat, 'DRAFT', 0, :ts, :ts, '[]'::jsonb, 'v20-tester')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", "v20-" + id.substring(0, 12))
                .addValue("cat", category)
                .addValue("ts", java.sql.Timestamp.from(now)));
    }
}
