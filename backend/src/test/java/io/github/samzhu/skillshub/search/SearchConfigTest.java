package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * AC-5 驗證：SearchConfig 的 @Bean 方法直接測試，避免觸發 Spring Modulith AOT 處理。
 *
 * <p>純 JUnit 5 unit test。@ConditionalOnProperty 條件邏輯由整合測試（T6 SemanticSearchIntegrationTest）
 * 在完整 application context 中驗證。
 */
class SearchConfigTest {

    private final SearchConfig config = new SearchConfig();

    @Test
    @DisplayName("AC-5: simpleVectorStore() 回傳 SimpleVectorStore 實例")
    void simpleVectorStoreFactory() {
        var em = mockEmbeddingModel();

        var store = config.simpleVectorStore(em);

        assertThat(store).isInstanceOf(SimpleVectorStore.class);
    }

    @Test
    @DisplayName("AC-5: noOpEmbeddingModel() 回傳 768 維零向量（本機開發備用）")
    void noOpEmbeddingModelReturns768DimVector() {
        var em = config.noOpEmbeddingModel();

        var vector = em.embed("test text");

        assertThat(vector).hasSize(768);
    }

    @Test
    @DisplayName("AC-5: SimpleVectorStore 可執行 add + similaritySearch（in-memory cosine 正常）")
    void simpleVectorStoreIsOperational() {
        var em = mockEmbeddingModel();
        var store = config.simpleVectorStore(em);

        var doc = Document.builder()
                .id("skill-1")
                .text("docker-helper 管理容器部署")
                .metadata(Map.of("skillId", "skill-1"))
                .build();

        store.add(List.of(doc));

        var results = store.similaritySearch(
                SearchRequest.builder()
                        .query("容器部署")
                        .topK(5)
                        .similarityThreshold(0.0)
                        .build());

        assertThat(results).isNotNull();
    }

    @Test
    @DisplayName("AC-3: googleGenAiEmbeddingModel() 當 skillshub.genai.api-key 設定時回傳 GoogleGenAiTextEmbeddingModel")
    void googleGenAiEmbeddingModelReturnsRealModel() {
        var props = new SkillshubProperties(
                new SkillshubProperties.Storage("skillshub-packages", "./storage-local"),
                new SkillshubProperties.Search("simple", "skill_embeddings"),
                new SkillshubProperties.GenAI("gemini-embedding-2", 768, "test-api-key"));

        var em = config.googleGenAiEmbeddingModel(props);

        assertThat(em).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }

    // ---- helpers ----

    private static EmbeddingModel mockEmbeddingModel() {
        var em = mock(EmbeddingModel.class);
        when(em.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(em.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        return em;
    }

    private static float[] randomVector(int dim) {
        var v = new float[dim];
        var r = new Random(42);
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextFloat() * 2 - 1;
        }
        return v;
    }
}
