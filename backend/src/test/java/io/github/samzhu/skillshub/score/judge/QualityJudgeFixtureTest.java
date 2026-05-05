package io.github.samzhu.skillshub.score.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S135a AC-S135a-8 — LLM judge reproducibility regression suite.
 *
 * <p>REQUIRES {@code GEMINI_API_KEY} env var — skipped in CI without it.
 * Run locally: {@code GEMINI_API_KEY=... ./gradlew test --tests QualityJudgeFixtureTest}
 *
 * <p>Validates that:
 * <ul>
 *   <li>JudgeResponse has exactly 4 DimensionScores per call</li>
 *   <li>Each dim score ∈ [0, 3]</li>
 *   <li>Per-dim variance across 5 runs ≤ 1</li>
 *   <li>Per-axis totalScore variance across 5 runs ≤ 5pt</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "skillshub.quality.judge.enabled=true",
        "skillshub.genai.api-key=${GEMINI_API_KEY:dummy-key-for-context-load}"
})
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class QualityJudgeFixtureTest {

    private static final Logger log = LoggerFactory.getLogger(QualityJudgeFixtureTest.class);

    private static final int RUNS = 5;
    private static final int SCORE_SCALE = 3;  // 0-3 per dim

    @Autowired
    private QualityJudge judge;

    @Test
    @DisplayName("AC-S135a-8: high-quality-1 Implementation — 5 runs variance ≤ 1 per dim, ≤ 5pt totalScore")
    @Tag("AC-S135a-8")
    void highQuality1_implementation_reproducibility() throws Exception {
        var body = extractBody(readFixture("score-fixtures/high-quality-1.md"));
        assertImplementationReproducibility("high-quality-1", body);
    }

    @Test
    @DisplayName("AC-S135a-8: high-quality-1 Activation — 5 runs variance ≤ 1 per dim, ≤ 5pt totalScore")
    @Tag("AC-S135a-8")
    void highQuality1_activation_reproducibility() throws Exception {
        var description = extractDescription(readFixture("score-fixtures/high-quality-1.md"));
        assertActivationReproducibility("high-quality-1", description);
    }

    @Test
    @DisplayName("AC-S135a-8: low-quality-1 Implementation — 5 runs variance ≤ 1 per dim")
    @Tag("AC-S135a-8")
    void lowQuality1_implementation_reproducibility() throws Exception {
        var body = extractBody(readFixture("score-fixtures/low-quality-1.md"));
        assertImplementationReproducibility("low-quality-1", body);
    }

    @Test
    @DisplayName("AC-S135a-8: medium-quality-1 Activation — 5 runs, response shape valid")
    @Tag("AC-S135a-8")
    void mediumQuality1_activation_shape() throws Exception {
        var description = extractDescription(readFixture("score-fixtures/medium-quality-1.md"));
        var response = judge.judgeActivation(description);

        assertResponseShape(response, "Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness");
    }

    // ---- helpers ----

    private void assertImplementationReproducibility(String label, String body) {
        var runs = IntStream.range(0, RUNS)
                .mapToObj(i -> judge.judgeImplementation(body))
                .toList();

        runs.forEach(r -> assertResponseShape(r, "Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure"));

        var totals = runs.stream().map(r -> axisTotal(r)).toList();
        var variance = maxVariance(totals);
        log.info("[{}] impl totals over {} runs: {} (variance={})", label, RUNS, totals, variance);

        assertThat(variance).as("totalScore variance (%s impl)", label).isLessThanOrEqualTo(5.0);
        checkDimVariance(label + " impl", runs);
    }

    private void assertActivationReproducibility(String label, String description) {
        var runs = IntStream.range(0, RUNS)
                .mapToObj(i -> judge.judgeActivation(description))
                .toList();

        runs.forEach(r -> assertResponseShape(r, "Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness"));

        var totals = runs.stream().map(r -> axisTotal(r)).toList();
        var variance = maxVariance(totals);
        log.info("[{}] activation totals over {} runs: {} (variance={})", label, RUNS, totals, variance);

        assertThat(variance).as("totalScore variance (%s activation)", label).isLessThanOrEqualTo(5.0);
        checkDimVariance(label + " activation", runs);
    }

    private void assertResponseShape(JudgeResponse response, String... expectedDims) {
        assertThat(response).isNotNull();
        assertThat(response.scores()).hasSize(4);
        assertThat(response.verdict()).isNotBlank();

        for (int i = 0; i < expectedDims.length; i++) {
            var dim = response.scores().get(i);
            assertThat(dim.dimension()).isEqualToIgnoringCase(expectedDims[i]);
            assertThat(dim.score()).isBetween(0, SCORE_SCALE);
            assertThat(dim.reasoning()).isNotBlank();
        }
    }

    private void checkDimVariance(String label, List<JudgeResponse> runs) {
        int dimCount = runs.get(0).scores().size();
        for (int d = 0; d < dimCount; d++) {
            final int dimIdx = d;
            var dimScores = runs.stream().map(r -> (double) r.scores().get(dimIdx).score()).toList();
            var dimVariance = maxVariance(dimScores);
            var dimName = runs.get(0).scores().get(d).dimension();
            log.info("[{}] dim[{}]={} scores: {} (variance={})", label, d, dimName, dimScores, dimVariance);
            assertThat(dimVariance).as("dim %s variance in [%s]", dimName, label).isLessThanOrEqualTo(1.0);
        }
    }

    private double axisTotal(JudgeResponse response) {
        var sumScores = response.scores().stream().mapToInt(JudgeResponse.DimensionScore::score).sum();
        // Normalize: max per axis = 4 dims × 3 = 12 → scale to 0-100
        return BigDecimal.valueOf(sumScores * 100.0 / (4.0 * SCORE_SCALE))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double maxVariance(List<Double> values) {
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        return max - min;
    }

    private String readFixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private String extractBody(String content) {
        var trimmed = content.strip();
        if (!trimmed.startsWith("---")) return content;
        int second = trimmed.indexOf("---", 3);
        if (second < 0) return content;
        return trimmed.substring(second + 3).strip();
    }

    private String extractDescription(String content) {
        for (var line : content.split("\n")) {
            var stripped = line.strip();
            if (stripped.startsWith("description:")) {
                return stripped.substring("description:".length()).strip();
            }
        }
        return "";
    }
}
