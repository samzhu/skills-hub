package io.github.samzhu.skillshub.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S135a T07 — E2E smoke test with real Gemini LLM.
 *
 * <p>Gated by GEMINI_API_KEY env var — skips in CI but can be run locally:
 * {@code GEMINI_API_KEY=... ./gradlew test --tests E2EQualityScoreTest -Dspring.profiles.active=dev}
 *
 * <p>Active profile "dev" activates {@code skillshub.quality.judge.enabled=true} so the real
 * QualityJudge (Gemini) is used instead of StubQualityJudge. Without the dev profile,
 * tests run with StubQualityJudge and still verify wiring but not real LLM scoring ranges.
 *
 * <p>Boundary scenarios:
 * <ul>
 *   <li>high-quality: total ≥ 80 (verifies LLM identifies good skill)</li>
 *   <li>low-quality: total ≤ 60 (verifies LLM does NOT inflate scores)</li>
 *   <li>borderline: validation.totalScore = 100 (boundary conditions pass)</li>
 * </ul>
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@Tag("E2E")
class E2EQualityScoreTest {

    private static final Duration E2E_WAIT = Duration.ofSeconds(30);

    private static final String HIGH_QUALITY_MD = """
            ---
            name: docker-compose-orchestrator
            description: Orchestrates multi-service Docker Compose environments for local development. \
            Use when starting, stopping, or debugging docker-compose.yml services. Handles port \
            conflicts, volume mounts, and health checks. Not for Kubernetes or single-container Docker.
            allowed-tools:
              - Bash(docker:*)
              - Read
            ---
            # Docker Compose Orchestrator

            Manages multi-service Docker Compose environments for local development.

            ## When to use

            - Start or stop docker compose services
            - Debug unhealthy services or port conflicts

            ## Steps

            1. Check running containers: `docker compose ps`
            2. View logs: `docker compose logs <service> --tail=50`
            3. Restart: `docker compose restart <service>`
            4. Fix port conflict: update docker-compose.yml port mapping
            5. Force recreate: `docker compose up -d --force-recreate`

            ## Example

            ```bash
            docker compose -f docker-compose.yml up -d --build
            docker compose ps
            docker compose logs web --tail=50
            ```

            ## Output

            Returns a running environment with all services healthy.
            Reports services that failed to start with root cause.
            """;

    private static final String LOW_QUALITY_MD = """
            ---
            name: do-stuff
            description: does stuff
            ---
            # Do Stuff

            This skill does stuff.
            """;

    private static final String BORDERLINE_MD = """
            ---
            name: borderline-skill
            description: This skill processes requests at exactly the minimum viable description length \
            to test boundary conditions in the quality scoring system for validation axis checks.
            allowed-tools:
              - Bash
            ---
            # Borderline Skill

            Processes edge cases in the quality scoring pipeline.

            ## Steps

            1. Process the input
            2. Execute the operation
            3. Return results

            ## Example

            ```bash
            run-operation --input value
            ```

            ## Output

            Returns processed results.
            """;

    @Autowired
    private SkillScoreRepository scoreRepo;

    @Autowired
    private StorageService storageService;

    @Test
    @DisplayName("E2E AC-S135a-1/AC-S135a-3: high-quality skill → 3 rows + total ≥ 80")
    void highQualitySkill_scoresHighTotal(Scenario scenario) throws IOException {
        var skillId = UUID.randomUUID().toString();
        uploadZip(skillId, HIGH_QUALITY_MD);

        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0",
                "skills/e2e/" + skillId + ".zip", 2048L,
                Map.of("name", "docker-compose-orchestrator",
                        "description", "Orchestrates multi-service Docker Compose environments."),
                List.of("Bash(docker:*)"));

        scenario.publish(event)
                .andWaitAtMost(E2E_WAIT)
                .andWaitForStateChange(() -> scoreRepo.findLatestBySkillId(skillId))
                .andVerify(rows -> {
                    assertThat(rows).hasSize(3);
                    var response = ScoreResponse.from(rows);
                    assertThat(response.total())
                            .as("high-quality skill should score ≥ 80")
                            .isGreaterThanOrEqualTo(80);
                    rows.forEach(r -> assertThat(r.getDimensions()).isNotEmpty());
                });
    }

    @Test
    @DisplayName("E2E: low-quality skill → total ≤ 60 (LLM not inflating scores)")
    void lowQualitySkill_scoresLowTotal(Scenario scenario) throws IOException {
        var skillId = UUID.randomUUID().toString();
        uploadZip(skillId, LOW_QUALITY_MD);

        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0",
                "skills/e2e/" + skillId + ".zip", 512L,
                Map.of("name", "do-stuff", "description", "does stuff"),
                List.of());

        scenario.publish(event)
                .andWaitAtMost(E2E_WAIT)
                .andWaitForStateChange(() -> scoreRepo.findLatestBySkillId(skillId))
                .andVerify(rows -> {
                    var response = ScoreResponse.from(rows);
                    assertThat(response.total())
                            .as("low-quality skill should score ≤ 60")
                            .isLessThanOrEqualTo(60);
                });
    }

    @Test
    @DisplayName("E2E: borderline skill → validation.totalScore = 100 (boundary passes)")
    void borderlineSkill_validationPasses(Scenario scenario) throws IOException {
        var skillId = UUID.randomUUID().toString();
        uploadZip(skillId, BORDERLINE_MD);

        var event = SkillVersionPublishedEvent.of(skillId, "1.0.0",
                "skills/e2e/" + skillId + ".zip", 1024L,
                Map.of("name", "borderline-skill",
                        "description", "This skill processes requests at exactly the minimum viable description length."),
                List.of("Bash"));

        scenario.publish(event)
                .andWaitAtMost(E2E_WAIT)
                .andWaitForStateChange(() -> scoreRepo.findLatestBySkillId(skillId))
                .andVerify(rows -> {
                    var response = ScoreResponse.from(rows);
                    assertThat(response.validation().totalScore())
                            .as("borderline skill should pass all validation rules → totalScore=100")
                            .isEqualTo(100);
                });
    }

    private void uploadZip(String skillId, String skillMd) throws IOException {
        storageService.upload("skills/e2e/" + skillId + ".zip", makeZip(skillMd));
    }

    private static byte[] makeZip(String content) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
