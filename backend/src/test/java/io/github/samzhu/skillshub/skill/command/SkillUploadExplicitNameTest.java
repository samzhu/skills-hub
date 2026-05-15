package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S176-T02 — upload API separates platform skillName from package SKILL.md name.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class SkillUploadExplicitNameTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private SkillVersionRepository versionRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S176-2: uploadSkill 寫入 request skillName；frontmatter 保留 package name")
    @Tag("AC-S176-2")
    void uploadSkillPersistsPlatformNameSeparatelyFromPackageName() throws IOException {
        var platformName = unique("platform-skill");
        var packageName = unique("internal-package-name");
        var zip = createZip(packageName);

        var skillId = commandService.uploadSkill(zip, platformName, "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name");

        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getName()).isEqualTo(platformName);

        var version = versionRepo.findBySkillIdAndVersion(skillId, "1.0.0").orElseThrow();
        assertThat(version.getFrontmatter()).containsEntry("name", packageName);
    }

    @Test
    @DisplayName("AC-S176-3: duplicate platform skillName 允許建立兩筆 skill")
    @Tag("AC-S176-3")
    void duplicatePlatformSkillNamesAreAllowed() throws IOException {
        var platformName = unique("transcribe-video");

        var firstId = commandService.uploadSkill(createZip(unique("internal-one")),
                platformName, "1.0.0", "owner-a", "testing", Visibility.PUBLIC, "Owner A");
        var secondId = commandService.uploadSkill(createZip(unique("internal-two")),
                platformName, "1.0.0", "owner-b", "testing", Visibility.PUBLIC, "Owner B");

        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(countSkillsByName(platformName)).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-S176-4: missing/invalid skillName → IllegalArgumentException；DB 不新增 row")
    @Tag("AC-S176-4")
    void missingOrInvalidPlatformSkillNameRejectsBeforeInsert() throws IOException {
        var before = countSkills();
        var zip = createZip(unique("internal-name"));

        assertThatThrownBy(() -> commandService.uploadSkill(zip, null, "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        assertThat(countSkills()).isEqualTo(before);

        assertThatThrownBy(() -> commandService.uploadSkill(zip, "Bad Name!", "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill name must match");
        assertThat(countSkills()).isEqualTo(before);
    }

    private long countSkills() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM skills", java.util.Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private long countSkillsByName(String name) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM skills WHERE name = :name",
                new MapSqlParameterSource("name", name),
                Long.class);
        return count == null ? 0 : count;
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
                    description: Explicit upload name fixture
                    ---

                    # %s
                    """.formatted(skillName, skillName);
            zos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
