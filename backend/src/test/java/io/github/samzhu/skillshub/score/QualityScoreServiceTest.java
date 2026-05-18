package io.github.samzhu.skillshub.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.score.judge.JudgeResponse;
import io.github.samzhu.skillshub.score.judge.QualityJudge;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S135a T05 — QualityScoreService 純單元測試（無 Spring context）。
 * 驗 3-axis 評分建立、saveAll 呼叫、total score 計算邏輯。
 */
class QualityScoreServiceTest {

    private static final String SKILL_CONTENT = """
            ---
            name: test-skill
            description: A test skill for quality scoring.
            ---
            # Test Skill

            ## Steps
            1. Run the command
            2. Check output

            ## Example
            ```bash
            run-test
            ```
            """;

    private static JudgeResponse stubResponse(String d1, String d2, String d3, String d4, int score) {
        return new JudgeResponse(
                List.of(
                        new JudgeResponse.DimensionScore(d1, score, "stub"),
                        new JudgeResponse.DimensionScore(d2, score, "stub"),
                        new JudgeResponse.DimensionScore(d3, score, "stub"),
                        new JudgeResponse.DimensionScore(d4, score, "stub")),
                "stub verdict");
    }

    private static QualityScoreService service(StorageService storage, PackageService pkg,
                                                QualityJudge judge, SkillScoreRepository repo,
                                                SkillVersionRepository versionRepo) {
        return new QualityScoreService(new SkillValidator(), judge, repo, versionRepo, storage, pkg);
    }

    private static SkillScore validationRowForContent(String content) throws Exception {
        var storage = mock(StorageService.class);
        var pkg = mock(PackageService.class);
        var judge = mock(QualityJudge.class);
        var repo = mock(SkillScoreRepository.class);
        var versionRepo = mock(SkillVersionRepository.class);

        when(storage.download(anyString())).thenReturn(new byte[]{1});
        when(pkg.extractSkillMd(any())).thenReturn(content);
        when(judge.evaluatorVersion()).thenReturn("stub@v0");
        when(judge.judgeImplementation(anyString())).thenReturn(
                stubResponse("Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure", 3));
        when(judge.judgeActivation(anyString())).thenReturn(
                stubResponse("Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness", 3));
        when(versionRepo.findBySkillIdAndVersion(anyString(), anyString())).thenReturn(Optional.empty());

        var event = SkillVersionPublishedEvent.of("skill-s194", "1.0.0", "skills/test.zip", 100L,
                Map.of("name", "test-skill", "description", "A test skill for quality scoring."),
                List.of());

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(Iterable.class);
        service(storage, pkg, judge, repo, versionRepo).evaluateAndPersist(event);
        verify(repo).saveAll(captor.capture());

        return ((List<SkillScore>) captor.getValue()).stream()
                .filter(r -> r.getAxis() == QualityAxis.VALIDATION)
                .findFirst()
                .orElseThrow();
    }

    private static String validSkillWithTotalLines(int totalLines) {
        var header = "---\nname: test-skill\ndescription: A test skill for quality scoring.\n---\n";
        var headerLines = 4;
        return header + "line\n".repeat(totalLines - headerLines - 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dimension(SkillScore row, String key) {
        return (Map<String, Object>) row.getDimensions().get(key);
    }

    @Test
    @DisplayName("AC-S135a-1: evaluateAndPersist → saveAll called with 3 SkillScore rows")
    @Tag("AC-S135a-1")
    void evaluateAndPersist_savesThreeRows() throws Exception {
        var storage = mock(StorageService.class);
        var pkg = mock(PackageService.class);
        var judge = mock(QualityJudge.class);
        var repo = mock(SkillScoreRepository.class);
        var versionRepo = mock(SkillVersionRepository.class);

        when(storage.download(anyString())).thenReturn(new byte[]{1});
        when(pkg.extractSkillMd(any())).thenReturn(SKILL_CONTENT);
        when(judge.evaluatorVersion()).thenReturn("stub@v0");
        when(judge.judgeImplementation(anyString())).thenReturn(
                stubResponse("Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure", 2));
        when(judge.judgeActivation(anyString())).thenReturn(
                stubResponse("Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness", 2));
        when(versionRepo.findBySkillIdAndVersion(anyString(), anyString())).thenReturn(Optional.empty());

        var event = SkillVersionPublishedEvent.of("skill-1", "1.0.0", "skills/test.zip", 100L,
                Map.of("name", "test-skill", "description", "A test skill for quality scoring."),
                List.of("Bash"));

        service(storage, pkg, judge, repo, versionRepo).evaluateAndPersist(event);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(captor.capture());
        var rows = (List<SkillScore>) captor.getValue();
        assertThat(rows).hasSize(3);
        var axes = rows.stream().map(SkillScore::getAxis).toList();
        assertThat(axes).containsExactlyInAnyOrder(
                QualityAxis.VALIDATION, QualityAxis.IMPLEMENTATION, QualityAxis.ACTIVATION);
    }

    @Test
    @DisplayName("AC-S135a-11: IMPLEMENTATION totalScore = dimSum/maxScore×100 (all score=2/3 → 66.67)")
    @Tag("AC-S135a-11")
    void implementationScore_totalFormula() throws Exception {
        var storage = mock(StorageService.class);
        var pkg = mock(PackageService.class);
        var judge = mock(QualityJudge.class);
        var repo = mock(SkillScoreRepository.class);
        var versionRepo = mock(SkillVersionRepository.class);

        when(storage.download(anyString())).thenReturn(new byte[]{1});
        when(pkg.extractSkillMd(any())).thenReturn(SKILL_CONTENT);
        when(judge.evaluatorVersion()).thenReturn("stub@v0");
        // score=2 × 4 dims → sum=8 / max=12 → 66.67
        when(judge.judgeImplementation(anyString())).thenReturn(
                stubResponse("Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure", 2));
        // activation all 3 → 100
        when(judge.judgeActivation(anyString())).thenReturn(
                stubResponse("Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness", 3));
        when(versionRepo.findBySkillIdAndVersion(anyString(), anyString())).thenReturn(Optional.empty());

        var event = SkillVersionPublishedEvent.of("skill-2", "1.0.0", "skills/test.zip", 100L,
                Map.of("name", "test-skill", "description", "A test skill."), List.of());

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(Iterable.class);
        service(storage, pkg, judge, repo, versionRepo).evaluateAndPersist(event);
        verify(repo).saveAll(captor.capture());

        var rows = (List<SkillScore>) captor.getValue();
        var implRow = rows.stream().filter(r -> r.getAxis() == QualityAxis.IMPLEMENTATION).findFirst().orElseThrow();
        var actRow = rows.stream().filter(r -> r.getAxis() == QualityAxis.ACTIVATION).findFirst().orElseThrow();

        assertThat(implRow.getTotalScore()).isEqualByComparingTo("66.67");
        assertThat(actRow.getTotalScore()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("AC-S135a-1: VALIDATION all-pass → totalScore=100")
    @Tag("AC-S135a-1")
    void validationScore_allPass_returns100() throws Exception {
        var storage = mock(StorageService.class);
        var pkg = mock(PackageService.class);
        var judge = mock(QualityJudge.class);
        var repo = mock(SkillScoreRepository.class);
        var versionRepo = mock(SkillVersionRepository.class);

        when(storage.download(anyString())).thenReturn(new byte[]{1});
        when(pkg.extractSkillMd(any())).thenReturn(SKILL_CONTENT);
        when(judge.evaluatorVersion()).thenReturn("stub@v0");
        when(judge.judgeImplementation(anyString())).thenReturn(
                stubResponse("Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure", 2));
        when(judge.judgeActivation(anyString())).thenReturn(
                stubResponse("Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness", 2));
        when(versionRepo.findBySkillIdAndVersion(anyString(), anyString())).thenReturn(Optional.empty());

        var event = SkillVersionPublishedEvent.of("skill-3", "1.0.0", "skills/test.zip", 100L,
                Map.of("name", "test-skill", "description", "A test skill for quality scoring."),
                List.of());

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(Iterable.class);
        service(storage, pkg, judge, repo, versionRepo).evaluateAndPersist(event);
        verify(repo).saveAll(captor.capture());

        var rows = (List<SkillScore>) captor.getValue();
        var validationRow = rows.stream().filter(r -> r.getAxis() == QualityAxis.VALIDATION).findFirst().orElseThrow();
        assertThat(validationRow.getTotalScore()).isEqualByComparingTo("100.00");
        assertThat(validationRow.getDimensions()).containsKey("lineCount");
        assertThat(validationRow.getDimensions()).containsKey("bodyPresent");
    }

    @Test
    @DisplayName("AC-S198-3: quality score lineCount dimension deducts for recommended max warning")
    @Tag("AC-S198-3")
    void lineCountRecommendationReducesValidationScore() throws Exception {
        var row = validationRowForContent(validSkillWithTotalLines(589));

        assertThat((Integer) dimension(row, "lineCount").get("score")).isLessThan(100);
        assertThat(row.getTotalScore()).isLessThan(new java.math.BigDecimal("100.00"));
        assertThat(dimension(row, "lineCount"))
                .containsEntry("reasoning", "589 / 500 recommended lines");
        assertThat(row.getDimensions().get("warnings").toString())
                .contains("skill_md_line_count")
                .contains("recommended max 500");
    }

    @Test
    @DisplayName("AC-S194-6: VALIDATION all-pass official frontmatter keeps totalScore 100 and official-format dimension 100")
    @Tag("AC-S194-6")
    void officialFrontmatterKeepsValidation100() throws Exception {
        var row = validationRowForContent("""
                ---
                name: test-skill
                description: A test skill for quality scoring.
                allowed-tools: "Read Glob"
                metadata:
                  author: howielab
                ---
                # Test Skill

                ## Steps
                1. Run the command

                ## Example
                ```bash
                run-test
                ```
                """);

        assertThat(row.getTotalScore()).isEqualByComparingTo("100.00");
        assertThat(dimension(row, "frontmatterOfficialFormat"))
                .containsEntry("score", 100)
                .containsEntry("reasoning", "frontmatter follows agentskills.io official format");
    }

    @Test
    @DisplayName("AC-S194-6: allowed-tools list reduces frontmatterOfficialFormat and totalScore")
    @Tag("AC-S194-6")
    void allowedToolsListReducesFrontmatterOfficialFormatScore() throws Exception {
        var row = validationRowForContent("""
                ---
                name: test-skill
                description: A test skill for quality scoring.
                allowed-tools: [Read, Glob]
                ---
                # Test Skill

                ## Steps
                1. Run the command

                ## Example
                ```bash
                run-test
                ```
                """);

        assertThat(row.getTotalScore()).isEqualByComparingTo("97.14");
        assertThat(dimension(row, "frontmatterOfficialFormat"))
                .containsEntry("score", 80);
        assertThat(dimension(row, "frontmatterOfficialFormat").get("reasoning").toString())
                .contains("allowed-tools");
    }

    @Test
    @DisplayName("AC-S194-6: metadata tags list reduces frontmatterOfficialFormat and totalScore")
    @Tag("AC-S194-6")
    void metadataTagsListReducesFrontmatterOfficialFormatScore() throws Exception {
        var row = validationRowForContent("""
                ---
                name: test-skill
                description: A test skill for quality scoring.
                metadata:
                  tags: [session-management, context-preservation]
                ---
                # Test Skill

                ## Steps
                1. Run the command

                ## Example
                ```bash
                run-test
                ```
                """);

        assertThat(row.getTotalScore()).isEqualByComparingTo("97.14");
        assertThat(dimension(row, "frontmatterOfficialFormat"))
                .containsEntry("score", 80);
        assertThat(dimension(row, "frontmatterOfficialFormat").get("reasoning").toString())
                .contains("metadata: key 'tags'");
    }

    @Test
    @DisplayName("AC-S194-6: multiple frontmatter compatibility warnings only apply one official-format penalty")
    @Tag("AC-S194-6")
    void multipleFrontmatterWarningsApplyOnePenalty() throws Exception {
        var row = validationRowForContent("""
                ---
                name: test-skill
                description: A test skill for quality scoring.
                allowed-tools: [Read, Glob]
                metadata:
                  tags: [session-management, context-preservation]
                ---
                # Test Skill

                ## Steps
                1. Run the command

                ## Example
                ```bash
                run-test
                ```
                """);

        assertThat(row.getTotalScore()).isEqualByComparingTo("97.14");
        assertThat(dimension(row, "frontmatterOfficialFormat"))
                .containsEntry("score", 80);
        assertThat(row.getDimensions().get("warnings").toString())
                .contains("allowed-tools")
                .contains("metadata: key 'tags'");
    }

    @Test
    @DisplayName("AC-S135a-2: alreadyScored delegates to existsBySourceEventId")
    @Tag("AC-S135a-2")
    void alreadyScored_delegatesToRepo() {
        var repo = mock(SkillScoreRepository.class);
        when(repo.existsBySourceEventId("evt-123")).thenReturn(true);
        var svc = new QualityScoreService(null, null, repo, null, null, null);
        assertThat(svc.alreadyScored("evt-123")).isTrue();
        assertThat(svc.alreadyScored("unknown")).isFalse();
    }
}
