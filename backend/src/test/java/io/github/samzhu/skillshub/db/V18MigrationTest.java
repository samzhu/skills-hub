package io.github.samzhu.skillshub.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S154-T01 — V18 Flyway migration schema 驗證。
 *
 * <p>驗 V18 fresh schema（per spec §2.8 — 2026-05-10 user 決策 no backfill）：
 * <ul>
 *   <li>users 表存在；10 個 column 型別 / nullability 對；UNIQUE(oauth_provider, sub) +
 *       UNIQUE(handle) constraint 對；idx_users_email 索引存在</li>
 *   <li>skills 表新增 author_name_snapshot VARCHAR(255) NULLABLE column</li>
 *   <li>Flyway schema_history 含 V18 row（idempotency 由 Flyway 機制本身擔保）</li>
 * </ul>
 *
 * <p>本 IT 在 Flyway 自動套用 V1-V18 之後執行 — Testcontainer pgvector/pg16
 * 透過 {@code @ServiceConnection} 注入 datasource。
 *
 * @see io.github.samzhu.skillshub.db.V2MigrationTest 既有同 pattern reference
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V18MigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1: users 表 schema — 10 column 型別 / nullability 完整對齊 spec §2.8")
    @Tag("AC-1")
    void usersTableSchema_matchesSpec() {
        // 查 information_schema 取 users 表所有 column 的 data_type / is_nullable / character_maximum_length
        var rows = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, character_maximum_length, column_default
                FROM   information_schema.columns
                WHERE  table_name = 'users'
                ORDER  BY ordinal_position
                """, Map.of());

        assertThat(rows).hasSize(10);

        // 把 list 轉 map (column_name → row) 方便逐欄位比對
        var byName = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("column_name"),
                        r -> r));

        assertColumn(byName, "id", "character varying", "NO", 20);
        assertColumn(byName, "oauth_provider", "character varying", "NO", 20);
        assertColumn(byName, "sub", "character varying", "NO", 255);
        assertColumn(byName, "email", "character varying", "NO", 320);
        assertColumn(byName, "name", "character varying", "YES", 255);
        assertColumn(byName, "handle", "character varying", "NO", 64);
        assertColumn(byName, "avatar_url", "text", "YES", null);
        assertColumn(byName, "contact_email_public", "boolean", "NO", null);
        assertColumn(byName, "created_at", "timestamp with time zone", "NO", null);
        assertColumn(byName, "last_seen_at", "timestamp with time zone", "NO", null);

        // contact_email_public DEFAULT FALSE — 後台不主動公開 email
        assertThat(((String) byName.get("contact_email_public").get("column_default")).toLowerCase())
                .as("contact_email_public 預設 false（fail-secure：不公開 email）")
                .contains("false");
    }

    @Test
    @DisplayName("AC-1: users 雙 UNIQUE constraint — (oauth_provider, sub) + (handle)")
    @Tag("AC-1")
    void usersTable_uniqueConstraints() {
        // pg_constraint 查 UNIQUE constraint；contype = 'u' 即 UNIQUE
        var constraints = jdbc.queryForList("""
                SELECT conname,
                       pg_get_constraintdef(c.oid) AS def
                FROM   pg_constraint c
                JOIN   pg_class t ON c.conrelid = t.oid
                WHERE  t.relname = 'users'
                  AND  c.contype = 'u'
                """, Map.of());

        // (oauth_provider, sub) UNIQUE — 同 provider 同 sub 唯一；
        // (handle) UNIQUE — username slug 全平台唯一
        assertThat(constraints)
                .as("users 必須有 2 個 UNIQUE constraint")
                .hasSize(2);

        var defs = constraints.stream().map(c -> ((String) c.get("def")).toLowerCase()).toList();

        assertThat(defs).anyMatch(d -> d.contains("(oauth_provider, sub)"));
        assertThat(defs).anyMatch(d -> d.contains("(handle)"));
    }

    @Test
    @DisplayName("AC-1: idx_users_email 索引存在（UserResolver 與後台找人用，非 UNIQUE）")
    @Tag("AC-1")
    void usersEmailIndex_exists() {
        var indexDef = jdbc.queryForObject("""
                SELECT indexdef
                FROM   pg_indexes
                WHERE  indexname = 'idx_users_email'
                """, Map.of(), String.class);

        assertThat(indexDef)
                .as("idx_users_email 必須存在")
                .isNotNull();
        assertThat(indexDef.toLowerCase())
                .as("idx_users_email 必須建在 users.email 上")
                .contains("on public.users")
                .contains("(email)");
    }

    @Test
    @DisplayName("AC-1: skills 表新增 author_name_snapshot VARCHAR(255) NULLABLE column")
    @Tag("AC-1")
    void skillsAuthorNameSnapshotColumn_added() {
        var col = jdbc.queryForMap("""
                SELECT data_type, is_nullable, character_maximum_length
                FROM   information_schema.columns
                WHERE  table_name = 'skills' AND column_name = 'author_name_snapshot'
                """, Map.of());

        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(col.get("is_nullable"))
                .as("nullable — 既存 row 無 snapshot 資料；新 publish 由 Skill aggregate 寫入（T04）")
                .isEqualTo("YES");
        assertThat(col.get("character_maximum_length")).isEqualTo(255);
    }

    @Test
    @DisplayName("AC-1: Flyway schema_history 含 V18 row（idempotency 機制檢查）")
    @Tag("AC-1")
    void flywaySchemaHistory_containsV18() {
        // Flyway 紀錄 V18 已套用；二次跑 Flyway 因該 row 存在會 skip — 冪等由 Flyway 本身擔保
        var v18 = jdbc.queryForMap("""
                SELECT version, success, description
                FROM   flyway_schema_history
                WHERE  version = '18'
                """, Map.of());

        assertThat((Boolean) v18.get("success"))
                .as("V18 必須成功套用")
                .isTrue();
        assertThat((String) v18.get("description"))
                .as("description 從 file name 推導，含 'create users' 等關鍵字")
                .containsIgnoringCase("users");
    }

    /**
     * 比對 column metadata：data_type / is_nullable / 字串型別的 max length。
     *
     * @param byName              column_name → information_schema row 的 map
     * @param name                column 名稱
     * @param expectedType        information_schema.data_type 預期值（如 "character varying"）
     * @param expectedNullable    "YES" / "NO"
     * @param expectedMaxLength   字串型別 maxLength；非字串型別傳 null
     */
    private static void assertColumn(Map<String, Map<String, Object>> byName,
                                     String name,
                                     String expectedType,
                                     String expectedNullable,
                                     Integer expectedMaxLength) {
        var row = byName.get(name);
        assertThat(row)
                .as("column %s 必須存在", name)
                .isNotNull();
        assertThat(row.get("data_type"))
                .as("column %s data_type", name)
                .isEqualTo(expectedType);
        assertThat(row.get("is_nullable"))
                .as("column %s is_nullable", name)
                .isEqualTo(expectedNullable);
        if (expectedMaxLength != null) {
            assertThat(row.get("character_maximum_length"))
                    .as("column %s max length", name)
                    .isEqualTo(expectedMaxLength);
        }
    }
}
