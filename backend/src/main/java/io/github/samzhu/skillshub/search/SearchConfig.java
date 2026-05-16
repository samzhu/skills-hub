package io.github.samzhu.skillshub.search;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.ai.AiModelConfig;

/**
 * Search module compatibility config.
 *
 * <p>S171 moves the real {@link EmbeddingModel} bean to {@link AiModelConfig}.
 * This class keeps the old factory method as a non-bean delegate for S157 unit
 * tests until those tests are folded into the shared AI config tests.
 *
 * <p>S186 後 semantic search 直接讀寫 {@code skills.embedding} 欄位，本 module
 * 不再註冊或建構 VectorStore runtime 物件。
 *
 * <p>相依模組：{@code search} — 不依賴任何業務模組。
 *
 * @see SearchProjection
 * @see SemanticSearchService
 */
@Configuration
class SearchConfig {

    /**
     * Compatibility delegate for legacy S157 tests.
     *
     * @param props resolved application properties
     * @return embedding model built by {@link AiModelConfig}
     */
    EmbeddingModel embeddingModel(SkillshubProperties props) {
        return AiModelConfig.createEmbeddingModel(props);
    }
}
