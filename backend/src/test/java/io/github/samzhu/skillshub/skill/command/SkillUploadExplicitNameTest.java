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
    @DisplayName("AC-S176-2: uploadSkill 寫入 request display skillName；frontmatter 保留 package name")
    @Tag("AC-S176-2")
    void uploadSkillPersistsDisplayNameSeparatelyFromPackageName() throws IOException {
        var platformName = "Team Transcribe " + UUID.randomUUID().toString().substring(0, 8);
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
        var platformName = "團隊轉錄工具";

        var firstId = commandService.uploadSkill(createZip(unique("internal-one")),
                platformName, "1.0.0", "owner-a", "testing", Visibility.PUBLIC, "Owner A");
        var secondId = commandService.uploadSkill(createZip(unique("internal-two")),
                platformName, "1.0.0", "owner-b", "testing", Visibility.PUBLIC, "Owner B");

        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(countSkillsByName(platformName)).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-S176-4: missing/blank/control-char skillName → IllegalArgumentException；DB 不新增 row")
    @Tag("AC-S176-4")
    void missingBlankOrControlCharacterPlatformSkillNameRejectsBeforeInsert() throws IOException {
        var before = countSkills();
        var zip = createZip(unique("internal-name"));

        assertThatThrownBy(() -> commandService.uploadSkill(zip, null, "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        assertThat(countSkills()).isEqualTo(before);

        assertThatThrownBy(() -> commandService.uploadSkill(zip, "   ", "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill name must not be blank");
        assertThat(countSkills()).isEqualTo(before);

        assertThatThrownBy(() -> commandService.uploadSkill(zip, "Team\nTranscribe", "1.0.0",
                "owner", "testing", Visibility.PUBLIC, "Owner Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill name must not contain control characters");
        assertThat(countSkills()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-S188-1: uploadSkill 未帶 version → skill_versions.version='1' 且 storage path 使用 /1/")
    @Tag("AC-S188-1")
    void uploadSkillWithoutVersionUsesInitialVersionOne() throws IOException {
        var skillName = unique("version-label-upload");

        var skillId = commandService.uploadSkill(createZip(unique("internal-upload")),
                skillName, null, "owner", "testing", Visibility.PUBLIC, "Owner Name");

        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getLatestVersion()).isEqualTo("1");

        var version = versionRepo.findBySkillIdAndVersion(skillId, "1").orElseThrow();
        assertThat(version.getStoragePath()).isEqualTo("skills/" + skillId + "/1/skill.zip");
    }

    @Test
    @DisplayName("AC-S188-2: addVersion 未帶 version → max numeric version + 1，忽略 custom label")
    @Tag("AC-S188-2")
    void addVersionWithoutVersionUsesNextNumericVersion() throws IOException {
        var skillId = commandService.uploadSkill(createZip(unique("internal-seq-one")),
                unique("version-label-seq"), null, "owner", "testing", Visibility.PUBLIC, "Owner Name");
        commandService.addVersion(skillId, createZip(unique("internal-seq-two")), null);
        commandService.addVersion(skillId, createZip(unique("internal-seq-hotfix")), "2026.05-hotfix");

        commandService.addVersion(skillId, createZip(unique("internal-seq-three")), null);

        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "3")).isPresent();
        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "2026.05-hotfix")).isPresent();
        assertThat(skillRepo.findById(skillId).orElseThrow().getLatestVersion()).isEqualTo("3");
    }

    @Test
    @DisplayName("AC-S188-3: custom version label 可儲存；同 skill 重複 label → 409 domain error")
    @Tag("AC-S188-3")
    void customVersionLabelStoresAndDuplicateRejects() throws IOException {
        var skillId = commandService.uploadSkill(createZip(unique("internal-custom-one")),
                unique("version-label-custom"), "0.1.0", "owner", "testing", Visibility.PUBLIC, "Owner Name");

        commandService.addVersion(skillId, createZip(unique("internal-custom-two")), "2026.05-hotfix");

        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "0.1.0")).isPresent();
        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "2026.05-hotfix")).isPresent();
        assertThatThrownBy(() -> commandService.addVersion(
                skillId, createZip(unique("internal-custom-dupe")), "2026.05-hotfix"))
                .isInstanceOf(VersionExistsException.class)
                .hasMessageContaining("Version 2026.05-hotfix already exists");
    }

    @Test
    @DisplayName("AC-S188-4: unsafe version label → reject 且不新增 skill_versions row")
    @Tag("AC-S188-4")
    void unsafeVersionLabelRejectsBeforeVersionInsert() throws IOException {
        var skillId = commandService.uploadSkill(createZip(unique("internal-unsafe-one")),
                unique("version-label-unsafe"), null, "owner", "testing", Visibility.PUBLIC, "Owner Name");
        var before = countVersions(skillId);

        assertThatThrownBy(() -> commandService.addVersion(
                skillId, createZip(unique("internal-unsafe-two")), "../prod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(VersionLabelPolicy.INVALID_VERSION_MESSAGE);

        assertThat(countVersions(skillId)).isEqualTo(before);
        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "../prod")).isEmpty();
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

    private long countVersions(String skillId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM skill_versions WHERE skill_id = :skillId",
                new MapSqlParameterSource("skillId", skillId),
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
