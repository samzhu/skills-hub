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
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
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
        assertThat(version.getAllowedTools())
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
        assertThat(versions.get(0).getAllowedTools()).isEmpty();
    }

    @Test
    @DisplayName("AC-S032: addVersion 偵測 zip SKILL.md name 與 skill aggregate 不一致 → IllegalArgumentException")
    @Tag("AC-S032")
    void addVersion_nameMismatch_rejects() throws IOException {
        // 先 upload 建立 skill A（name = realName）
        var realName = "real-name-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = createZipWithFrontmatter(realName, null);
        var skillId = commandService.uploadSkill(v1, "1.0.0", "owner", "Testing");

        // 嘗試 PUT 版本，但 zip SKILL.md 的 name 不同
        var v2 = createZipWithFrontmatter("totally-different-name", null);
        assertThatThrownBy(() -> commandService.addVersion(skillId, v2, "1.1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match")
                .hasMessageContaining(realName);

        // 既有 1.0.0 不被破壞
        assertThat(versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId)).hasSize(1);
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
