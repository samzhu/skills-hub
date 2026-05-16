package io.github.samzhu.skillshub.skill.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
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
 * <p>S186-T08：e2e stub 用 token-only sparse vector。拆解 input 的 alphanumeric token，
 * 每 token 以 SHA-256 對 16 個 salted hash slot 加固定 signed boost；同 token 的 doc/query
 * 在這些 slots 對齊，無共同 token 則接近 0。這讓 keyword query 對 keyword-命中的 doc
 * 可分離出來，同時避免 random noise 或單一 hash collision 把 non-overlap fixture 推過 threshold。
 *
 * <p>對 e2e tests：
 * <ul>
 *   <li>AC-1 「docker」query → 3 docker-* skill cosine 高，其他低 → threshold 0.1
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
    private static final int TOKEN_HASH_SLOTS = 16;

    @Bean
    @Primary
    EmbeddingModel e2eStubEmbeddingModel() {
        return new DeterministicTokenEmbeddingModel(E2E_EMBEDDING_DIMENSIONS);
    }

    /**
     * 同 input → 同 768-dim unit vector；跨 process 排序穩定（SHA-256 輸出固定）。
     */
    static final class DeterministicTokenEmbeddingModel implements EmbeddingModel {

        private final int dim;

        DeterministicTokenEmbeddingModel(int dim) {
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
            var vec = new float[dim];
            for (var token : input.toLowerCase().split("[^a-z0-9]+")) {
                if (token.length() < 2) continue;
                addToken(vec, token);
            }
            return normalize(vec);
        }

        private void addToken(float[] vec, String token) {
            // S186-T08：16 個 signed slots 讓單一 hash collision 不會讓 non-overlap pair 過 threshold。
            for (int i = 0; i < TOKEN_HASH_SLOTS; i++) {
                byte[] digest = sha256(token + "#" + i);
                int idx = Math.floorMod(firstInt(digest), dim);
                vec[idx] += (digest[4] & 1) == 0 ? 1.0f : -1.0f;
            }
        }

        private static byte[] sha256(String input) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 digest unavailable", e);
            }
        }

        private static int firstInt(byte[] digest) {
            return ((digest[0] & 0xff) << 24)
                    | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
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
