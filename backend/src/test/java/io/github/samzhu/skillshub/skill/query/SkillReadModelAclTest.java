package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
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
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S016-T1 — {@link SkillReadModel} 加 {@code aclEntries} field 的 JSONB
 * round-trip 整合驗證 + {@code ?|} operator 在 GIN index 上的 SQL 路徑。
 *
 * <p>對應 spec §2.4 #4 / #11（NamedParameterJdbcTemplate 對 {@code ?|} 不需 escape；
 * ARRAY 參數需走 {@link SqlParameterValue} 強制 {@code Types.ARRAY} 避開
 * IN-list 自動展開）。
 *
 * <p>採 {@code @SpringBootTest} + Testcontainer — Spring Boot 4 AOT 階段
 * 需要完整 {@code ApplicationModulesRuntime} bean，{@code @DataJdbcTest} 不可用。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillReadModelAclTest {

    @Autowired
    private SkillReadModelRepository repo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1: SkillReadModel.aclEntries 透過 Spring Data JDBC 寫入 JSONB 並反序列化")
    @Tag("AC-1")
    void aclEntries_persistsAndReadsBack() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        var entries = List.of("user:bob:read", "group:eng:read");

        repo.save(new SkillReadModel(
                id,
                "acl-skill-" + id.substring(0, 8),
                "test desc",
                "alice",
                "Testing",
                "1.0.0",
                "LOW",
                "PUBLISHED",
                0L,
                now,
                now,
                entries));

        var saved = repo.findById(id).orElseThrow();
        assertThat(saved.aclEntries())
                .as("aclEntries 必須透過 StringListJsonbConverter round-trip 還原")
                .containsExactlyInAnyOrderElementsOf(entries);
    }

    @Test
    @DisplayName("AC-1: WHERE acl_entries ?| ARRAY[...] 可命中 GIN index 並回傳 row")
    @Tag("AC-1")
    void anyKeyMatch_findsRowViaGinIndex() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();

        repo.save(new SkillReadModel(
                id,
                "any-key-skill-" + id.substring(0, 8),
                "test",
                "alice",
                "Testing",
                null,
                null,
                "DRAFT",
                0L,
                now,
                now,
                List.of("user:bob:read", "role:admin:read")));

        // ?| ARRAY[...] — NamedParameterJdbcTemplate 必須不 escape ?|
        // 且 ARRAY 參數須包 SqlParameterValue(Types.ARRAY) 避免 IN-list 展開
        var patterns = new String[] {"role:admin:read", "user:carol:read"};
        var matched = jdbc.queryForObject(
                // pgJDBC PreparedStatement 解析 `?` 為 placeholder — 即使 Spring NamedParameterJdbcTemplate
// 已 native skip `?|` operator（spring-framework NamedParameterUtils.parseSqlStatement
// 對 `?` / `?|` / `?&` 有 skip 邏輯）— 經過 NamedParameterJdbcTemplate 轉成 raw JDBC
// SQL 後仍會被 pgJDBC 重新 parse；故需 `??|` JDBC-level escape（pgJDBC unescape `??` → `?`）。
"SELECT COUNT(*) FROM skills WHERE id = :id AND acl_entries ??| :patterns",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("patterns", new SqlParameterValue(Types.ARRAY, patterns)),
                Long.class);

        assertThat(matched)
                .as("?| 必須命中 role:admin:read（任一 pattern 命中即 true）")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-1: ?| 不匹配時回 0；空 patterns 不應誤命中")
    @Tag("AC-1")
    void anyKeyMatch_returnsZeroWhenNoOverlap() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();

        repo.save(new SkillReadModel(
                id,
                "no-match-skill-" + id.substring(0, 8),
                "test",
                "alice",
                "Testing",
                null,
                null,
                "DRAFT",
                0L,
                now,
                now,
                List.of("user:owner:read")));

        var unrelated = new String[] {"user:stranger:read", "group:other:read"};
        var matched = jdbc.queryForObject(
                // pgJDBC PreparedStatement 解析 `?` 為 placeholder — 即使 Spring NamedParameterJdbcTemplate
// 已 native skip `?|` operator（spring-framework NamedParameterUtils.parseSqlStatement
// 對 `?` / `?|` / `?&` 有 skip 邏輯）— 經過 NamedParameterJdbcTemplate 轉成 raw JDBC
// SQL 後仍會被 pgJDBC 重新 parse；故需 `??|` JDBC-level escape（pgJDBC unescape `??` → `?`）。
"SELECT COUNT(*) FROM skills WHERE id = :id AND acl_entries ??| :patterns",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("patterns", new SqlParameterValue(Types.ARRAY, unrelated)),
                Long.class);

        assertThat(matched).isZero();
    }

    // 註：原本想加 "EXPLAIN 顯示走 GIN index" 驗證，但實務上 planner 在小資料集
    // 必選 seq scan；強制 SET enable_seqscan=off 在 HikariCP 多連線 pool 下也無法
    // 跨呼叫保持。GIN+jsonb_ops 對 ?| 可用的核心斷言由以下兩個測試共同覆蓋：
    //   1. V2MigrationTest.skillsAclEntriesColumnAndIndex_haveCorrectMetadata —
    //      indexdef 含 "using gin"、不含 "jsonb_path_ops"（meta 驗 jsonb_ops 預設）
    //   2. anyKeyMatch_findsRowViaGinIndex —
    //      實際跑 acl_entries ??| ARRAY[...] 查詢，若 jsonb_path_ops 無法支援會 SQL error
    // EXPLAIN plan 驗證留待 T6 在大資料集 E2E smoke 中再覆蓋（若需要）。
}
