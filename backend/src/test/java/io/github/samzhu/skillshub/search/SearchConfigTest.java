package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * AC-5 驗證：SearchConfig 的 EmbeddingModel @Bean 方法直接測試。
 *
 * <p>T8：SearchConfig 已削減為「只提供 EmbeddingModel beans」（無 VectorStore @Bean）；
 * VectorStore 操作由 {@link SkillshubPgVectorStore} per-request builder 提供，由
 * {@code SearchProjectionTest} / {@code PgVectorStoreOwnerWriteTest} 覆蓋。
 *
 * <p>純 JUnit 5 unit test，無 Spring context 啟動。
 */
class SearchConfigTest {

    private final SearchConfig config = new SearchConfig();

    @Test
    @DisplayName("AC-5: noOpEmbeddingModel() 回傳 768 維零向量（本機開發備用）")
    void noOpEmbeddingModelReturns768DimVector() {
        var em = config.noOpEmbeddingModel();

        var vector = em.embed("test text");

        assertThat(vector).hasSize(768);
    }

    @Test
    @DisplayName("AC-3: googleGenAiEmbeddingModel() 當 skillshub.genai.api-key 設定時回傳 GoogleGenAiTextEmbeddingModel")
    void googleGenAiEmbeddingModelReturnsRealModel() {
        var props = new SkillshubProperties(
                new SkillshubProperties.Storage("skillshub-packages", "./storage-local"),
                new SkillshubProperties.Search("simple", "skill_embeddings"),
                new SkillshubProperties.GenAI("gemini-embedding-2", 768, "test-api-key"),
                new SkillshubProperties.Scanner(new SkillshubProperties.Engines(
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(false),
                        new SkillshubProperties.Engine(true))),
                new SkillshubProperties.Security(
                        new SkillshubProperties.OAuth(true),
                        new SkillshubProperties.Lab("lab-user")));

        var em = config.googleGenAiEmbeddingModel(props);

        assertThat(em).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }

    // T8: helpers (mockEmbeddingModel + randomVector) 已移除 —
    // 不再有 simpleVectorStore tests 需要 mock embedding；EmbeddingModel 整合驗證
    // 由 SemanticSearchIntegrationTest（@MockitoBean EmbeddingModel）覆蓋。
}
