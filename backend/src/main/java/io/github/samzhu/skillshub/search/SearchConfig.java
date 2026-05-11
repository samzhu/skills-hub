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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * 語意搜尋基礎設施配置 — 提供 {@link EmbeddingModel} bean（真實 Google GenAI 或無操作 fallback）。
 *
 * <p>S157：拿掉 build-time {@code @ConditionalOnProperty(skillshub.genai.api-key)} — Spring AOT 在
 * native image 階段評估該 condition 時 api-key 還沒從 Secret Manager resolve（CI 容器無 sm@
 * 解析能力），導致 baked context 永遠排除真實 EmbeddingModel，runtime 即使 env 已注入也救不回。
 * 改由 factory body 在 runtime 讀 {@link SkillshubProperties#genai()}.apiKey() 走 branch — 同
 * S135a {@code QualityJudgeConfig} 已 ship 驗證的 pattern。
 *
 * <p>**T8 之後不註冊任何 VectorStore @Bean**：vector store 操作由
 * {@link SkillshubPgVectorStore#builder(org.springframework.jdbc.core.JdbcTemplate, EmbeddingModel)}
 * per-request 建構，owner / skillId 鎖在 instance attribute 裡，操作完即可被 GC，
 * 無 thread-safety 顧慮、無 singleton state leak。
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
     * EmbeddingModel factory — runtime 依 api-key 是否存在 branch real / NoOp。
     *
     * <p>S157 修法：無 build-time condition。Spring AOT 在 native image build 時只記錄
     * 「此 bean 必然存在」，runtime SkillshubProperties resolve 完 sm@ 後 factory 才執行，
     * 此時 api-key 為真實值 → 走 {@link GoogleGenAiTextEmbeddingModel} 真實路徑；本機無
     * api-key 走 NoOp（768 維零向量），讓應用程式仍可啟動。
     *
     * @param props 已 resolve 完 placeholder 的 properties；{@code genai().apiKey()} 為真實 key 或 null
     */
    @Bean
    EmbeddingModel embeddingModel(SkillshubProperties props) {
        var apiKey = props.genai().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No skillshub.genai.api-key configured — semantic search disabled (NoOp 768-d zero vector)");
            return new NoOpEmbeddingModel();
        }
        log.info("Initialising GoogleGenAiTextEmbeddingModel (Manual Config, API key mode)");
        var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(apiKey)
                .build();
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(props.genai().model())
                .dimensions(props.genai().dimensions())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
    }

    /**
     * 無操作 EmbeddingModel — 本機無 api-key 時使用，所有文件回傳 768 維零向量。
     *
     * <p>應用程式仍可啟動，語意搜尋永遠 0 結果（零向量 cosine 距離永遠 = 1，大於任何 threshold）。
     */
    static final class NoOpEmbeddingModel implements EmbeddingModel {

        private static final float[] ZERO_VECTOR = new float[768];

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
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
