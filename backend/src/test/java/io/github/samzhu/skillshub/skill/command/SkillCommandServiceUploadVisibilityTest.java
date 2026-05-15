package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S177-T02 — upload transaction writes Skill visibility and grant mirror rows
 * synchronously, before async ACL/search projections run.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class SkillCommandServiceUploadVisibilityTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S177-1b: upload public skill writes is_public owner grant and public grant in one transaction")
    @Tag("AC-S177-1b")
    void uploadPublicSkillWritesVisibilityAndGrantsInSameTransaction() throws IOException {
        var skillName = unique("s177-public-upload");

        var skillId = commandService.uploadSkill(createZip(skillName), skillName, "1.0.0",
                "u_alice0", "testing", Visibility.PUBLIC, "Alice");

        assertThat(isPublic(skillId)).isTrue();
        assertThat(grantCount(skillId, "user", "u_alice0")).isEqualTo(1);
        assertThat(grantCount(skillId, "public", "*")).isEqualTo(1);
        assertThat(aclEntries(skillId))
                .contains("user:u_alice0:read")
                .doesNotContain("public:*:read");
    }

    @Test
    @DisplayName("AC-S177-1b: upload private skill writes is_public false and no public grant")
    @Tag("AC-S177-1b")
    void uploadPrivateSkillWritesOwnerGrantOnly() throws IOException {
        var skillName = unique("s177-private-upload");

        var skillId = commandService.uploadSkill(createZip(skillName), skillName, "1.0.0",
                "u_alice0", "testing", Visibility.PRIVATE, "Alice");

        assertThat(isPublic(skillId)).isFalse();
        assertThat(grantCount(skillId, "user", "u_alice0")).isEqualTo(1);
        assertThat(grantCount(skillId, "public", "*")).isZero();
        assertThat(aclEntries(skillId))
                .contains("user:u_alice0:read")
                .doesNotContain("public:*:read");
    }

    private boolean isPublic(String skillId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_public FROM skills WHERE id = :id",
                Map.of("id", skillId),
                Boolean.class));
    }

    private int grantCount(String skillId, String principalType, String principalId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM skill_grants
                WHERE skill_id = :skillId
                  AND principal_type = :principalType
                  AND principal_id = :principalId
                """, new MapSqlParameterSource()
                .addValue("skillId", skillId)
                .addValue("principalType", principalType)
                .addValue("principalId", principalId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private String aclEntries(String skillId) {
        return jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = :id",
                Map.of("id", skillId),
                String.class);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] createZip(String skillName) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var skillMd = """
                    ---
                    name: %s
                    description: S177 upload visibility fixture
                    ---

                    # %s
                    """.formatted(skillName, skillName);
            zos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
