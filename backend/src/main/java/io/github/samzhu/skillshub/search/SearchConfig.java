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
