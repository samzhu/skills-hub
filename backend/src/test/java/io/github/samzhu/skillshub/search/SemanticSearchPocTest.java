package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * POC: Spring AI 2.0.0-M4 SimpleVectorStore API 驗證。
 *
 * <p>驗證假設：
 * <ul>
 *   <li>H1: SimpleVectorStore.builder(EmbeddingModel) API 正確</li>
 *   <li>H2: Document.builder() + SearchRequest.builder() API 正確</li>
 *   <li>H3: add / similaritySearch / delete 管線可正常運作</li>
 * </ul>
 *
 * 純 JUnit 5 unit test，不需 Spring context，不需 GCP 憑證。
 */
class SemanticSearchPocTest {

    private EmbeddingModel embeddingModel;
    private SimpleVectorStore vectorStore;

    @BeforeEach
    void setup() {
        embeddingModel = mock(EmbeddingModel.class);

        // Stub embed(String) → random 768-dim vector (used by similaritySearch)
        when(embeddingModel.embed(anyString()))
                .thenAnswer(inv -> randomVector(768));

        // Stub embed(Document) → random 768-dim vector (used by SimpleVectorStore.doAdd per-doc)
        when(embeddingModel.embed(any(Document.class)))
                .thenAnswer(inv -> randomVector(768));

        // Stub embed(List<Document>, EmbeddingOptions, BatchingStrategy) → list of vectors
        // (used by AbstractObservationVectorStore-based implementations in production)
        when(embeddingModel.embed(anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<?> docs = inv.getArgument(0);
                    return docs.stream().map(d -> randomVector(768)).toList();
                });

        vectorStore = SimpleVectorStore.builder(embeddingModel).build();
    }

    @Test
    @DisplayName("POC-1: SimpleVectorStore 可新增文件並回傳搜尋結果")
    void addAndSearchReturnsDocuments() {
        var doc = Document.builder()
                .id("skill-1")
                .text("docker-compose-helper 管理 Docker Compose 多容器部署")
                .metadata(Map.of(
                        "skillId", "skill-1",
                        "name", "docker-compose-helper",
                        "category", "DevOps"))
                .build();

        vectorStore.add(List.of(doc));

        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("部署容器應用")
                        .topK(5)
                        .similarityThreshold(0.0)   // 0.0 because vectors are random
                        .build());

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getMetadata()).containsEntry("skillId", "skill-1");
    }

    @Test
    @DisplayName("POC-2: VectorStore.delete() 成功移除 embedding（不拋出例外）")
    void deleteDocumentSucceeds() {
        var doc = Document.builder()
                .id("to-delete")
                .text("delete me")
                .metadata(Map.of())
                .build();

        vectorStore.add(List.of(doc));
        vectorStore.delete(List.of("to-delete"));

        // Assertion: no exception thrown. SimpleVectorStore doesn't expose size,
        // but deleting an existing document must succeed without error.
    }

    @Test
    @DisplayName("POC-3: 新增多筆文件後搜尋結果按 topK 截斷")
    void topKLimitsResults() {
        for (int i = 1; i <= 5; i++) {
            vectorStore.add(List.of(
                    Document.builder()
                            .id("skill-" + i)
                            .text("skill " + i + " description")
                            .metadata(Map.of("skillId", "skill-" + i))
                            .build()));
        }

        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("some query")
                        .topK(3)
                        .similarityThreshold(0.0)
                        .build());

        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    // ---- helpers ----

    private static float[] randomVector(int dimensions) {
        var v = new float[dimensions];
        var random = new Random(42); // fixed seed for reproducibility
        for (int i = 0; i < dimensions; i++) {
            v[i] = random.nextFloat() * 2 - 1; // [-1, 1]
        }
        return v;
    }
}
