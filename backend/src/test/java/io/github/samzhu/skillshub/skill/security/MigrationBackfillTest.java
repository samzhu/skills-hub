package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S114a AC-8 - V16/V17 migration backfill behavior verification.
 *
 * <p>Flyway V1-V17 runs on empty DB at test startup. Tests insert legacy-format
 * data via JdbcTemplate and re-execute V17 SQL to reproduce backfill behavior:
 * <ol>
 *   <li>V16 schema: skill_grants table + is_public GENERATED column exist</li>
 *   <li>V17 format: {@code "*:read"} converted to {@code "public:*:read"};
 *       is_public updates automatically</li>
 *   <li>V17 OWNER backfill: principal with {@code :delete} permission gets OWNER grant;
 *       without delete gets VIEWER grant</li>
 *   <li>V17 public VIEWER backfill: skill with {@code "public:*:read"} gets
 *       public-VIEWER grant; private skill gets none</li>
 * </ol>
 *
 * @see RepositorySliceTestBase DataJdbcTest slice base (Flyway + Testcontainers)
 */
class MigrationBackfillTest extends RepositorySliceTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AC-8: V16 schema - skill_grants table and is_public GENERATED column exist")
    @Tag("AC-8")
    void v16Schema_skillGrantsAndIsPublicColumnExist() {
        Integer grantTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'skill_grants'",
                Integer.class);
        assertThat(grantTableCount).isEqualTo(1);

        Integer isPublicCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'skills' AND column_name = 'is_public'",
                Integer.class);
        assertThat(isPublicCount).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-8: V17 format migration - '*:read' converts to 'public:*:read', is_public auto-updates")
    @Tag("AC-8")
    void v17FormatMigration_convertsLegacyPublicEntry_isPublicUpdates() {
        // Given: skill with legacy "*:read" 2-segment format
        var skillId = insertSkill("alice",
                "[\"user:alice:read\", \"user:alice:write\", \"user:alice:delete\", \"*:read\"]");

        // is_public = false before migration ("*:read" does not match "public:*:read" condition)
        Boolean beforeMigration = jdbcTemplate.queryForObject(
                "SELECT is_public FROM skills WHERE id = ?", Boolean.class, skillId);
        assertThat(beforeMigration).isFalse();

        // When: V17 format migration UPDATE (V17__backfill_skill_grants.sql Step 1)
        jdbcTemplate.update(
                "UPDATE skills SET acl_entries = (" +
                "  SELECT COALESCE(jsonb_agg(" +
                "    CASE WHEN entry IN ('*:read', 'public:read') THEN '\"public:*:read\"'::jsonb" +
                "         ELSE to_jsonb(entry) END ORDER BY entry), '[]'::jsonb)" +
                "  FROM jsonb_array_elements_text(acl_entries) AS entry)" +
                " WHERE id = ?",
                skillId);

        // Then: acl_entries contains "public:*:read"; is_public GENERATED column auto-updates to true
        String aclEntries = jdbcTemplate.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = ?", String.class, skillId);
        assertThat(aclEntries).contains("public:*:read");
        assertThat(aclEntries).doesNotContain("\"*:read\"");

        Boolean afterMigration = jdbcTemplate.queryForObject(
                "SELECT is_public FROM skills WHERE id = ?", Boolean.class, skillId);
        assertThat(afterMigration).isTrue();
    }

    @Test
    @DisplayName("AC-8: V17 OWNER backfill - delete permission maps to OWNER; no delete maps to VIEWER")
    @Tag("AC-8")
    void v17OwnerBackfill_deletePermissionMapsToOwnerRole() {
        var ownerSkillId = insertSkill("bob",
                "[\"user:bob:read\", \"user:bob:write\", \"user:bob:delete\"]");
        var viewerSkillId = insertSkill("carol",
                "[\"user:carol:read\", \"user:carol:write\"]");

        // When: V17 OWNER backfill INSERT (V17__backfill_skill_grants.sql Step 2)
        jdbcTemplate.update(
                "INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at) " +
                "SELECT gen_random_uuid()::text, s.id, parsed.ptype, parsed.principal," +
                "  CASE WHEN 'delete' = ANY(parsed.perms) THEN 'OWNER' ELSE 'VIEWER' END," +
                "  COALESCE(s.author, 'system'), s.created_at " +
                "FROM skills s, LATERAL (" +
                "  SELECT split_part(entry, ':', 1) AS ptype, split_part(entry, ':', 2) AS principal," +
                "    array_agg(DISTINCT split_part(entry, ':', 3)) AS perms " +
                "  FROM jsonb_array_elements_text(s.acl_entries) AS entry " +
                "  WHERE split_part(entry, ':', 1) IN ('user', 'group', 'company')" +
                "    AND entry <> 'public:*:read' " +
                "  GROUP BY split_part(entry, ':', 1), split_part(entry, ':', 2)) parsed " +
                "WHERE s.id IN (?, ?) " +
                "ON CONFLICT ON CONSTRAINT uq_skill_grants_principal DO NOTHING",
                ownerSkillId, viewerSkillId);

        // Then: bob (has delete) -> OWNER; carol (no delete) -> VIEWER
        String bobRole = jdbcTemplate.queryForObject(
                "SELECT role FROM skill_grants WHERE skill_id = ? AND principal_id = 'bob'",
                String.class, ownerSkillId);
        assertThat(bobRole).isEqualTo("OWNER");

        String carolRole = jdbcTemplate.queryForObject(
                "SELECT role FROM skill_grants WHERE skill_id = ? AND principal_id = 'carol'",
                String.class, viewerSkillId);
        assertThat(carolRole).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("AC-8: V17 public VIEWER backfill - public:*:read entry creates VIEWER grant; private skill has none")
    @Tag("AC-8")
    void v17PublicViewerBackfill_publicEntryCreatesViewerGrant() {
        var publicSkillId = insertSkill("dan",
                "[\"user:dan:delete\", \"public:*:read\"]");
        var privateSkillId = insertSkill("eve",
                "[\"user:eve:delete\"]");

        // When: V17 public VIEWER backfill INSERT (V17__backfill_skill_grants.sql Step 3)
        jdbcTemplate.update(
                "INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at) " +
                "SELECT gen_random_uuid()::text, s.id, 'public', '*', 'VIEWER'," +
                "  COALESCE(s.author, 'system'), s.created_at " +
                "FROM skills s " +
                "WHERE s.acl_entries @> '[\"public:*:read\"]'::jsonb" +
                "  AND s.id IN (?, ?) " +
                "ON CONFLICT ON CONSTRAINT uq_skill_grants_principal DO NOTHING",
                publicSkillId, privateSkillId);

        // Then: public skill has public VIEWER grant; private skill has none
        Integer publicGrantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_grants WHERE skill_id = ? AND principal_type = 'public'",
                Integer.class, publicSkillId);
        assertThat(publicGrantCount).isEqualTo(1);

        Integer privateGrantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_grants WHERE skill_id = ? AND principal_type = 'public'",
                Integer.class, privateSkillId);
        assertThat(privateGrantCount).isEqualTo(0);
    }

    private String insertSkill(String author, String aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO skills (id, name, description, author, category, latest_version," +
                " risk_level, status, download_count, created_at, updated_at, acl_entries, owner_id)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?::timestamptz, ?::timestamptz, ?::jsonb, ?)",
                id, "mbt-" + author + "-" + id.substring(0, 6),
                "Migration backfill test fixture", author,
                "testing", "1.0.0", "LOW", "PUBLISHED",
                now, now, aclEntries, author);
        return id;
    }
}
