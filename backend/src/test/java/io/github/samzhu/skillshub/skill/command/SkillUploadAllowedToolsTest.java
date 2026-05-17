package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S018 T5 — uploadSkill → SkillVersionReadModel.allowedTools first-class 持久化驗證（AC-13）。
 *
 * <p>對應 spec §3 AC-13：SKILL.md frontmatter 含 {@code allowed-tools: "Bash(git:*) Edit Read"}
 * → uploadSkill 解析 space-separated → 存於 first-class column（非僅 frontmatter JSONB）。
 *
 * <p>S025b T02 — {@code @SpringBootTest} → {@code @ApplicationModuleTest(DIRECT_DEPENDENCIES)}：
 * skill module slice 自動載 SkillCommandService + 全部 {@code @Component} deps（PackageService /
 * SkillValidator / StorageService 來自 TestcontainersConfiguration）— 對齊 S025a
 * {@code AuditEventListenerTest} pattern；assertion 純 sync state（不涉跨 module audit listener），
 * skill module 自含足夠 deps。
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class SkillUploadAllowedToolsTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillVersionRepository versionRepo;

    @Autowired
    private SkillRepository skillRepo;

    @Test
    @DisplayName("AC-13: uploadSkill with allowed-tools → SkillVersionReadModel.allowedTools 含對應 list")
    @Tag("AC-13")
    void uploadSkillWithAllowedTools_persistsFirstClass() throws IOException {
        var skillName = "tool-test-" + UUID.randomUUID().toString().substring(0, 8);
        var zip = createZipWithFrontmatter(skillName,
                "Bash(git:*) Edit Read Write");

        var skillId = commandService.uploadSkill(zip, skillName, "1.0.0", "owner", "testing", null, null);

        var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
        assertThat(versions).hasSize(1);
        var version = versions.get(0);

        // AC-13：allowedTools 為 first-class field（非 frontmatter Map 中的 entry）
        assertThat(version.getAllowedTools())
                .containsExactly("Bash(git:*)", "Edit", "Read", "Write");
    }

    @Test
    @DisplayName("AC-13: uploadSkill 無 allowed-tools frontmatter → allowedTools = empty list")
    @Tag("AC-13")
    void uploadSkillWithoutAllowedTools_emptyList() throws IOException {
        var skillName = "no-tools-" + UUID.randomUUID().toString().substring(0, 8);
        var zip = createZipWithFrontmatter(skillName, null); // 不寫 allowed-tools

        var skillId = commandService.uploadSkill(zip, skillName, "1.0.0", "owner", "testing", null, null);

        var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
        assertThat(versions.get(0).getAllowedTools()).isEmpty();
    }

    @Test
    @DisplayName("AC-S176-5: addVersion accepts SKILL.md name different from platform skill name")
    @Tag("AC-S176-5")
    void addVersionWithDifferentPackageName_updatesLatestVersionAndStoresFrontmatter() throws IOException {
        var platformName = "platform-skill-" + UUID.randomUUID().toString().substring(0, 8);
        var packageV1 = "internal-package-v1-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = createZipWithFrontmatter(packageV1, null);
        var skillId = commandService.uploadSkill(v1, platformName, "1.0.0", "owner", "testing", null, null);

        var packageV2 = "internal-package-v2-" + UUID.randomUUID().toString().substring(0, 8);
        var v2 = createZipWithFrontmatter(packageV2, null);
        commandService.addVersion(skillId, v2, "1.1.0");

        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getName()).isEqualTo(platformName);
        assertThat(skill.getLatestVersion()).isEqualTo("1.1.0");

        var version = versionRepo.findBySkillIdAndVersion(skillId, "1.1.0").orElseThrow();
        assertThat(version.getFrontmatter()).containsEntry("name", packageV2);

        assertThat(versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId))
                .extracting("version")
                .containsExactly("1.1.0", "1.0.0");

        assertThatThrownBy(() -> commandService.addVersion(skillId, v2, "1.1.0"))
                .isInstanceOf(VersionExistsException.class)
                .hasMessageContaining("Version 1.1.0 already exists");
    }

    @Test
    @DisplayName("AC-S187-5: 新版本 publish 更新 skills.description snapshot")
    @Tag("AC-S187-5")
    void addVersionUpdatesDescriptionSnapshot() throws IOException {
        var platformName = "snapshot-skill-" + UUID.randomUUID().toString().substring(0, 8);
        var packageV1 = "snapshot-package-v1-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = createZipWithFrontmatter(packageV1, "Old description snapshot", null);
        var skillId = commandService.uploadSkill(v1, platformName, "1.0.0", "owner", "testing", null, null);

        assertThat(skillRepo.findById(skillId).orElseThrow().getDescription())
                .isEqualTo("Old description snapshot");

        var packageV2 = "snapshot-package-v2-" + UUID.randomUUID().toString().substring(0, 8);
        var v2 = createZipWithFrontmatter(packageV2, "New description from SKILL.md", null);
        commandService.addVersion(skillId, v2, "1.1.0");

        assertThat(skillRepo.findById(skillId).orElseThrow().getDescription())
                .isEqualTo("New description from SKILL.md");

        var version = versionRepo.findBySkillIdAndVersion(skillId, "1.1.0").orElseThrow();
        assertThat(version.getFrontmatter())
                .containsEntry("description", "New description from SKILL.md");
    }

    @Test
    @DisplayName("AC-S187-10: addVersion 清掉上一版 riskLevel 讓 validate page 等新掃描")
    @Tag("AC-S187-10")
    void addVersionClearsStaleRiskLevel() throws IOException {
        var platformName = "risk-reset-skill-" + UUID.randomUUID().toString().substring(0, 8);
        var packageV1 = "risk-reset-package-v1-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = createZipWithFrontmatter(packageV1, "Initial risk description", null);
        var skillId = commandService.uploadSkill(v1, platformName, "1.0.0", "owner", "testing", null, null);
        skillRepo.updateRiskLevel(skillId, "LOW", Instant.now());

        assertThat(skillRepo.findById(skillId).orElseThrow().getRiskLevel())
                .isEqualTo("LOW");

        var packageV2 = "risk-reset-package-v2-" + UUID.randomUUID().toString().substring(0, 8);
        var v2 = createZipWithFrontmatter(packageV2, "Needs a fresh scan", null);
        commandService.addVersion(skillId, v2, "1.1.0");

        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getLatestVersion()).isEqualTo("1.1.0");
        assertThat(skill.getRiskLevel()).isNull();
    }

    @Test
    @DisplayName("AC-S187-7: duplicate version 不覆寫 description snapshot")
    @Tag("AC-S187-7")
    void duplicateVersionDoesNotUpdateDescriptionSnapshot() throws IOException {
        var platformName = "duplicate-skill-" + UUID.randomUUID().toString().substring(0, 8);
        var packageV1 = "duplicate-package-v1-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = createZipWithFrontmatter(packageV1, "Initial description", null);
        var skillId = commandService.uploadSkill(v1, platformName, "1.0.0", "owner", "testing", null, null);

        var packageV2 = "duplicate-package-v2-" + UUID.randomUUID().toString().substring(0, 8);
        var v2 = createZipWithFrontmatter(packageV2, "Published description", null);
        commandService.addVersion(skillId, v2, "1.1.0");

        var packageV3 = "duplicate-package-v3-" + UUID.randomUUID().toString().substring(0, 8);
        var duplicate = createZipWithFrontmatter(packageV3, "Should not overwrite snapshot", null);
        assertThatThrownBy(() -> commandService.addVersion(skillId, duplicate, "1.1.0"))
                .isInstanceOf(VersionExistsException.class)
                .hasMessageContaining("Version 1.1.0 already exists");

        assertThat(skillRepo.findById(skillId).orElseThrow().getDescription())
                .isEqualTo("Published description");
        assertThat(versionRepo.findBySkillIdAndVersion(skillId, "1.1.0").orElseThrow().getFrontmatter())
                .containsEntry("description", "Published description");
    }

    /**
     * 產生最小可通過 SkillValidator 的 zip：含 SKILL.md 含 frontmatter（name + description
     * + 可選 allowed-tools）。
     */
    private byte[] createZipWithFrontmatter(String skillName, String allowedToolsLine) throws IOException {
        return createZipWithFrontmatter(skillName, "Allowed tools test fixture", allowedToolsLine);
    }

    private byte[] createZipWithFrontmatter(String skillName, String description, String allowedToolsLine)
            throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var sb = new StringBuilder("---\n");
            sb.append("name: ").append(skillName).append("\n");
            sb.append("description: ").append(description).append("\n");
            if (allowedToolsLine != null) {
                sb.append("allowed-tools: \"").append(allowedToolsLine).append("\"\n");
            }
            sb.append("---\n# ").append(skillName);
            zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
