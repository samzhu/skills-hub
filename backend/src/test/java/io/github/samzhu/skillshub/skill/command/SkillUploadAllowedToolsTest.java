package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.query.SkillVersionReadModelRepository;

/**
 * S018 T5 — uploadSkill → SkillVersionReadModel.allowedTools first-class 持久化驗證（AC-13）。
 *
 * <p>對應 spec §3 AC-13：SKILL.md frontmatter 含 {@code allowed-tools: "Bash(git:*) Edit Read"}
 * → uploadSkill 解析 space-separated → 存於 first-class column（非僅 frontmatter JSONB）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillUploadAllowedToolsTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillVersionReadModelRepository versionRepo;

    @Test
    @DisplayName("AC-13: uploadSkill with allowed-tools → SkillVersionReadModel.allowedTools 含對應 list")
    @Tag("AC-13")
    void uploadSkillWithAllowedTools_persistsFirstClass() throws IOException {
        var skillName = "tool-test-" + UUID.randomUUID().toString().substring(0, 8);
        var zip = createZipWithFrontmatter(skillName,
                "Bash(git:*) Edit Read Write");

        var skillId = commandService.uploadSkill(zip, "1.0.0", "owner", "Testing");

        var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
        assertThat(versions).hasSize(1);
        var version = versions.get(0);

        // AC-13：allowedTools 為 first-class field（非 frontmatter Map 中的 entry）
        assertThat(version.allowedTools())
                .containsExactly("Bash(git:*)", "Edit", "Read", "Write");
    }

    @Test
    @DisplayName("AC-13: uploadSkill 無 allowed-tools frontmatter → allowedTools = empty list")
    @Tag("AC-13")
    void uploadSkillWithoutAllowedTools_emptyList() throws IOException {
        var skillName = "no-tools-" + UUID.randomUUID().toString().substring(0, 8);
        var zip = createZipWithFrontmatter(skillName, null); // 不寫 allowed-tools

        var skillId = commandService.uploadSkill(zip, "1.0.0", "owner", "Testing");

        var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
        assertThat(versions.get(0).allowedTools()).isEmpty();
    }

    /**
     * 產生最小可通過 SkillValidator 的 zip：含 SKILL.md 含 frontmatter（name + description
     * + 可選 allowed-tools）。
     */
    private byte[] createZipWithFrontmatter(String skillName, String allowedToolsLine) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var sb = new StringBuilder("---\n");
            sb.append("name: ").append(skillName).append("\n");
            sb.append("description: Allowed tools test fixture\n");
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
