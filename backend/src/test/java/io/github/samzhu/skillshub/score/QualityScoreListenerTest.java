package io.github.samzhu.skillshub.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S135a T05 AC-S135a-1/AC-S135a-2 — QualityScoreListener Scenario 整合測試。
 *
 * <p>使用 StubQualityJudge（{@code skillshub.quality.judge.enabled=false}）— 不打真實 Gemini。
 * StorageService 由 TestcontainersConfiguration.InMemoryStorageService 取代。
 *
 * <p>AC-S135a-1：SkillVersionPublishedEvent publish → 3 skill_scores rows（VALIDATION/IMPLEMENTATION/ACTIVATION）。
 * AC-S135a-2：同 sourceEventId 重投 → idempotent（仍 3 rows）。
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration.class)
class QualityScoreListenerTest {

    private static final String VALID_SKILL_MD = """
            ---
            name: test-skill
            description: A test skill for automated quality score integration testing.
            allowed-tools:
              - Bash
            ---
            # Test Skill

            Automates test execution for quality scoring pipeline validation.

            ## Steps
            1. Run the test command
            2. Observe output
            3. Report results

            ## Example
            ```bash
            ./gradlew test
            ```

            ## Output
            Returns test results with pass/fail counts.
            """;

    @Autowired
    private SkillScoreRepository scoreRepo;

    @Autowired
    private StorageService storageService;

    @Test
    @DisplayName("AC-S135a-1: SkillVersionPublishedEvent → 3 skill_scores rows (VALIDATION/IMPL/ACTIVATION)")
    @Tag("AC-S135a-1")
    void publishEvent_writes3ScoreRows(Scenario scenario) throws IOException {
        var skillId = UUID.randomUUID().toString();
        var storagePath = "skills/test/" + skillId + ".zip";
        storageService.upload(storagePath, makeZip(VALID_SKILL_MD));

        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0", storagePath, 1024L,
                Map.of("name", "test-skill", "description",
                        "A test skill for automated quality score integration testing."),
                List.of("Bash"));

        scenario.publish(event)
                .andWaitForStateChange(() -> scoreRepo.findLatestBySkillId(skillId))
                .andVerify(rows -> {
                    assertThat(rows).hasSize(3);
                    var axes = rows.stream().map(SkillScore::getAxis).collect(Collectors.toSet());
                    assertThat(axes).containsExactlyInAnyOrder(
                            QualityAxis.VALIDATION, QualityAxis.IMPLEMENTATION, QualityAxis.ACTIVATION);
                    rows.forEach(r -> {
                        assertThat(r.getSourceEventId()).isEqualTo(event.sourceEventId());
                        assertThat(r.getTotalScore()).isNotNull();
                        assertThat(r.getDimensions()).isNotEmpty();
                    });
                });
    }

    @Test
    @DisplayName("AC-S135a-2: 同 sourceEventId 重投 3 次 → idempotent (仍 3 rows)")
    @Tag("AC-S135a-2")
    void duplicateEvent_idempotent(Scenario scenario) throws IOException {
        var skillId = UUID.randomUUID().toString();
        var storagePath = "skills/test/" + skillId + ".zip";
        storageService.upload(storagePath, makeZip(VALID_SKILL_MD));

        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0", storagePath, 1024L,
                Map.of("name", "test-skill", "description",
                        "A test skill for automated quality score integration testing."),
                List.of("Bash"));

        scenario.stimulate((tx, pub) -> {
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                    tx.executeWithoutResult(s -> pub.publishEvent(event));
                })
                .andWaitForStateChange(() -> scoreRepo.findLatestBySkillId(skillId))
                .andVerify(rows -> assertThat(rows).hasSize(3));
    }

    private static byte[] makeZip(String skillMdContent) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(skillMdContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
