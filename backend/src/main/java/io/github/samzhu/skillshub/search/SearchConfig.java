package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * 語意搜尋基礎設施配置 — 提供 {@link EmbeddingModel} bean（真實 Google GenAI 或無操作 fallback）。
 *
 * <p>**T8 之後不註冊任何 VectorStore @Bean**：vector store 操作由
 * {@link SkillshubPgVectorStore#builder(org.springframework.jdbc.core.JdbcTemplate, EmbeddingModel)}
 * per-request 建構，owner / skillId 鎖在 instance attribute 裡，操作完即可被 GC，
 * 無 thread-safety 顧慮、無 singleton state leak。
 *
 * <p>EmbeddingModel 仍走 Spring AI Manual Configuration 模式（與 S007/S010 一致）：
 * <ul>
 *   <li>{@code skillshub.genai.api-key} 已設 → {@link GoogleGenAiTextEmbeddingModel}（真實呼叫 Gemini）</li>
 *   <li>未設 → {@link NoOpEmbeddingModel}（768 維零向量；本機無憑證時應用程式仍可啟動）</li>
 * </ul>
 *
 * <p>相依模組：{@code search} — 不依賴任何業務模組。
 *
 * @see SkillshubPgVectorStore
 * @see SearchProjection
 * @see SemanticSearchService
 */
@Configuration
class SearchConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchConfig.class);

    /**
     * 真實 EmbeddingModel — 當 {@code skillshub.genai.api-key} 屬性存在於 Environment 時啟用。
     *
     * <p>使用 Manual Configuration（非 Spring AI auto-config）以 API key 模式建立
     * {@link GoogleGenAiTextEmbeddingModel}。model 和 dimensions 從
     * {@link SkillshubProperties#genai()} 讀取，不在 YAML 重複設定。
     *
     * <p>優先於 {@link #noOpEmbeddingModel()} 建立；無 API key 時由 NoOp fallback 接管。
     *
     * @param props 應用程式屬性，透過 {@link SkillshubProperties#genai()} 取得 API key、model、dimensions
     */
    @Bean
    @ConditionalOnProperty(name = "skillshub.genai.api-key")
    EmbeddingModel googleGenAiEmbeddingModel(SkillshubProperties props) {
        var genai = props.genai();
        log.info("Initialising GoogleGenAiTextEmbeddingModel (Manual Config, API key mode)");
        var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(genai.apiKey())
                .build();
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(genai.model())
                .dimensions(genai.dimensions())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
    }

    /**
     * 備用 EmbeddingModel — 當 {@code skillshub.genai.api-key} 未設定時自動啟用（本機開發模式）。
     *
     * <p>回傳全零的 768 維向量，語意搜尋無法產生有意義的結果，但應用程式可正常啟動。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    EmbeddingModel noOpEmbeddingModel() {
        // skillshub.genai.api-key 未設定 → 語意搜尋停用，其餘功能正常
        log.warn("No EmbeddingModel configured — semantic search disabled. "
                + "Set skillshub.genai.api-key to enable.");
        return new NoOpEmbeddingModel();
    }

    /**
     * 無操作 EmbeddingModel — 本機開發模式使用，所有文件回傳 768 維零向量。
     *
     * <p>此實作讓應用程式在缺少 GCP 憑證時仍可啟動。
     * 語意搜尋功能不可用（結果無意義），但其他功能正常運作。
     *
     * <p>正式環境（gcp profile）由 Google GenAI autoconfiguration 的 bean 取代。
     */
    private static final class NoOpEmbeddingModel implements EmbeddingModel {

        private static final float[] ZERO_VECTOR = new float[768];

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            // Return zero-vector embedding for each instruction text
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(ZERO_VECTOR, i))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(@Nullable Document document) {
            return ZERO_VECTOR;
        }
    }
}
