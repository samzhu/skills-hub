package io.github.samzhu.skillshub.skill.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class E2EEmbeddingConfigTest {

    private static final double E2E_SEMANTIC_THRESHOLD = 0.1;

    private final EmbeddingModel model = new E2EEmbeddingConfig().e2eStubEmbeddingModel();

    @Test
    @DisplayName("AC-S186-6: e2e embedding stub keeps docker non-overlap below threshold")
    void e2eEmbeddingStubKeepsDockerNonOverlapBelowThreshold() {
        var query = model.embed("docker");

        assertThat(cosine(query, model.embed("docker-compose-helper docker-compose-helper Orchestrates docker-compose dev stacks.")))
                .isGreaterThanOrEqualTo(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("docker-image-builder docker-image-builder Builds OCI images via Buildkit.")))
                .isGreaterThanOrEqualTo(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("docker-cleaner docker-cleaner Prunes dangling images and containers.")))
                .isGreaterThanOrEqualTo(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("csv-to-parquet csv-to-parquet Converts CSV datasets to Parquet.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("k8s-deploy-helper k8s-deploy-helper Deploys workloads to Kubernetes.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("junit-test-generator junit-test-generator Scaffolds JUnit 5 cases from interfaces.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("eslint-config-pack eslint-config-pack Shared ESLint preset for TS projects.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("docs-publisher docs-publisher Publishes mkdocs sites to GH Pages.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("markdown-linter markdown-linter Lints markdown for style and links.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
        assertThat(cosine(query, model.embed("pytest-runner pytest-runner Runs pytest with coverage in CI.")))
                .isLessThan(E2E_SEMANTIC_THRESHOLD);
    }

    @Test
    @DisplayName("AC-S186-6: e2e embedding stub is deterministic for the same input")
    void e2eEmbeddingStubIsDeterministicForTheSameInput() {
        var first = model.embed("docker-compose-helper docker-compose-helper Orchestrates docker-compose dev stacks.");
        var second = model.embed("docker-compose-helper docker-compose-helper Orchestrates docker-compose dev stacks.");

        assertThat(first).containsExactly(second);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }
}
