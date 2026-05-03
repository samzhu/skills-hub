package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S025b T04 demote — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate} 改
 * {@link RepositorySliceTestBase}（{@code @DataJdbcTest} slice）。原 HTTP-bound assertion
 * 已被 {@link io.github.samzhu.skillshub.S016EndToEndSmokeTest} E2E 涵蓋；本 test
 * 收斂為純 service + repo 互動驗證。
 *
 * <p>Audit log {@code domain_events} 的 async 寫入（{@code AuditEventListener
 * @ApplicationModuleListener}）在 {@code @DataJdbcTest} slice 不啟用 — 改驗
 * {@link SkillRepository} aggregate state（sync TX commit）取代 audit assertion；
 * 完整 audit pipeline 由 S016 e2e + {@code AuditEventListenerTest} module test 涵蓋。
 */
@Import({SkillCommandService.class, PackageService.class, SkillValidator.class})
class SkillCommandServiceTest extends RepositorySliceTestBase {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private SkillVersionRepository versionRepo;

    @Test
    @DisplayName("AC-1: createSkill 寫入 skills 表 — name/description/author/category 對齊 command")
    void createSkill_writesAggregateState() {
        var command = new CreateSkillCommand("docker-helper", "Docker compose helper", "sam", "DevOps");

        var skillId = commandService.createSkill(command);

        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getName()).isEqualTo("docker-helper");
        assertThat(skill.getDescription()).isEqualTo("Docker compose helper");
        assertThat(skill.getAuthor()).isEqualTo("sam");
        assertThat(skill.getCategory()).isEqualTo("DevOps");
        assertThat(skill.getStatus()).isEqualTo(SkillStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-5: publishVersion 寫入 skill_versions 表 — version + storagePath 對齊 command")
    void publishVersion_writesVersionRow() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("k8s-deploy", "K8s deployment skill", "jane", "DevOps"));

        commandService.publishVersion(
                new PublishVersionCommand(skillId, "1.0.0", "gs://bucket/k8s-deploy/1.0.0.zip", 0, 0, java.util.Map.of()));

        var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
        assertThat(versions).hasSize(1);
        var v = versions.getFirst();
        assertThat(v.getVersion()).isEqualTo("1.0.0");
        assertThat(v.getStoragePath()).isEqualTo("gs://bucket/k8s-deploy/1.0.0.zip");

        // skill aggregate latestVersion 已更新（sync via @DomainEvents proxy save）
        var skill = skillRepo.findById(skillId).orElseThrow();
        assertThat(skill.getLatestVersion()).isEqualTo("1.0.0");
    }
}
