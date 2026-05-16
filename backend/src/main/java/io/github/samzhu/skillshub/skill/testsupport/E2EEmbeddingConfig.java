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
 * <p>S168 升級：原 {@code Random(input.hashCode())} 純亂數 → 改為 <b>word-overlap biased</b> —
 * 拆解 input 的 alphanumeric token，每 token 對 {@code hashCode % dim} 加固定 boost。
 * 同 token 的 doc/query 在該 slot 對齊 → cosine 顯著正；不同 token → cosine 接近 0
 * （由小 random noise 主導）。這讓 keyword query 對 keyword-命中的 doc 可分離出來，
 * 同時保留 deterministic + cross-reload 一致性（POC 既驗）。
 *
 * <p>對 e2e tests：
 * <ul>
 *   <li>AC-1 「docker」query → 3 docker-* skill cosine 高，其他低 → threshold 0.15
 *       篩出 3 → "找到 3 個相關技能" 對齊 expect</li>
 *   <li>AC-5 中文 query「容器環境」→ 跟 English doc tokens 無 overlap → cosine 全低 →
 *       semantic 回空 → HomePage fallback 到 keyword OR AC-5 改用英文 query</li>
 * </ul>
 *
 * <p><b>結構決策</b>：用 {@code @Primary} bean 而非獨立 {@code @ConditionalOnMissingBean} —
 * {@link io.github.samzhu.skillshub.search.SearchConfig} 既有 {@code noOpEmbeddingModel} 已用
 * 該機制，獨立 stub bean 會 race。本 config 加 {@code @Primary} 後在 {@code e2e} profile 下
 * 優先於 {@code googleGenAiEmbeddingModel} / {@code noOpEmbeddingModel} 注入。
 *
 * <p>對齊 {@link io.github.samzhu.skillshub.SkillshubProperties.GenAI#dimensions()} 預設 768 —
 * {@code skills.embedding} 欄位接收 768-dim {@code float[]}。
 *
 * @see io.github.samzhu.skillshub.search.SearchConfig
 */
@Configuration
@Profile("e2e")
class E2EEmbeddingConfig {

    /** 對齊 {@code SkillshubProperties.GenAI.dimensions} 預設值；skills embedding 欄位接收 768-dim。 */
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
            // 小 random noise base — input.hashCode() 同字串 → 同 vector（Java spec 保證）。
            var rng = new Random(input.hashCode());
            var vec = new float[dim];
            for (int i = 0; i < dim; i++) {
                vec[i] = (rng.nextFloat() * 2 - 1) * 0.05f;
            }
            // Word-overlap boost — 每個 alphanumeric token（length ≥ 2）在 hashCode % dim 加 1.0。
            // 同 token 的 doc/query 在該 slot 對齊 → cosine 顯著正；無 overlap → cosine ≈ 0。
            for (var token : input.toLowerCase().split("[^a-z0-9]+")) {
                if (token.length() < 2) continue;
                int idx = Math.floorMod(token.hashCode(), dim);
                vec[idx] += 1.0f;
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
