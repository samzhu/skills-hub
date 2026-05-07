package io.github.samzhu.skillshub.skill.testsupport;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * S140 — {@code e2e} profile 專用 deterministic stub {@link EmbeddingModel}。
 *
 * <p><b>POC source</b>：{@code poc/S140/StubEmbeddingPoc.java}（2026-05-07）— H1 determinism /
 * H2 separation / H3 stable ranking 全 PASS（cosine 範圍 0.09，足夠分離不 tie）。
 *
 * <p><b>關鍵 caveat</b>：{@code Random(input.hashCode())} 產生 unit-normed 768-dim vector 是
 * <b>deterministic 但非 semantic</b> — query「容器/部署」對 docker 排序不會比對 junit 高。E2E 只驗
 * deterministic 排序 + UI 渲染，不驗 semantic 質量；真 Gemini 排序質量由 LAB 部署人工驗證。
 *
 * <p><b>結構決策</b>：用 {@code @Primary} bean 而非獨立 {@code @ConditionalOnMissingBean} —
 * {@link io.github.samzhu.skillshub.search.SearchConfig} 既有 {@code noOpEmbeddingModel} 已用
 * 該機制，獨立 stub bean 會 race。本 config 加 {@code @Primary} 後在 {@code e2e} profile 下
 * 優先於 {@code googleGenAiEmbeddingModel} / {@code noOpEmbeddingModel} 注入。
 *
 * <p>對齊 {@link io.github.samzhu.skillshub.SkillshubProperties.GenAI#dimensions()} 預設 768 —
 * vector_store schema 接收 768-dim {@code float[]}。
 *
 * @see io.github.samzhu.skillshub.search.SearchConfig
 */
@Configuration
@Profile("e2e")
class E2EEmbeddingConfig {

    /** 對齊 {@code SkillshubProperties.GenAI.dimensions} 預設值；vector_store schema 接收 768-dim。 */
    static final int E2E_EMBEDDING_DIMENSIONS = 768;

    @Bean
    @Primary
    EmbeddingModel e2eStubEmbeddingModel() {
        return new DeterministicRandomEmbeddingModel(E2E_EMBEDDING_DIMENSIONS);
    }

    /**
     * Per POC validation — 同 input → 同 768-dim unit vector；不同 input cosine 範圍約 0.09
     * （足夠分離不 tie）；跨 process 排序穩定（Java {@link String#hashCode()} 規範保證一致）。
     */
    static final class DeterministicRandomEmbeddingModel implements EmbeddingModel {

        private final int dim;

        DeterministicRandomEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(embedString(request.getInstructions().get(i)), i))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(@Nullable Document document) {
            return embedString(document == null ? "" : document.getText());
        }

        private float[] embedString(String input) {
            // input.hashCode() 同字串 → 同 seed → 同 vector — Java spec 保證 String.hashCode 跨 JVM 一致
            var rng = new Random(input.hashCode());
            var vec = new float[dim];
            for (int i = 0; i < dim; i++) {
                vec[i] = rng.nextFloat() * 2 - 1;
            }
            return normalize(vec);
        }

        private static float[] normalize(float[] v) {
            double norm = 0;
            for (var f : v) {
                norm += f * f;
            }
            norm = Math.sqrt(norm);
            // 防止零向量除以零；input.hashCode 衝突 0 機率極低，但保險
            if (norm < 1e-10) {
                return v;
            }
            var out = new float[v.length];
            for (int i = 0; i < v.length; i++) {
                out[i] = (float) (v[i] / norm);
            }
            return out;
        }
    }
}
