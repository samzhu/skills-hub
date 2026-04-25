package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * AC-1, AC-2 驗證：語意搜尋結果包含完整欄位、按 score 排序；無結果時回傳空陣列。
 */
class SemanticSearchTest {

    private VectorStore vectorStore;
    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new SemanticSearchService(vectorStore);
    }

    @Test
    @DisplayName("AC-1: 語意搜尋回傳含所有必要欄位的結果，且按 score 遞減排序")
    void semanticSearchReturnsResultsWithAllFields() {
        var doc1 = Document.builder()
                .id("skill-1")
                .text("docker-compose-helper")
                .metadata(Map.of(
                        "skillId", "skill-1",
                        "name", "docker-compose-helper",
                        "description", "管理 Docker Compose 多容器部署",
                        "author", "sam",
                        "category", "DevOps",
                        "latestVersion", "1.0.0",
                        "riskLevel", "LOW",
                        "downloadCount", 42L))
                .score(0.89)
                .build();

        var doc2 = Document.builder()
                .id("skill-2")
                .text("k8s-deployment")
                .metadata(Map.of(
                        "skillId", "skill-2",
                        "name", "k8s-deployment",
                        "description", "自動化 Kubernetes 部署流程",
                        "author", "jane",
                        "category", "DevOps",
                        "latestVersion", "2.0.0",
                        "riskLevel", "MEDIUM",
                        "downloadCount", 15L))
                .score(0.75)
                .build();

        // VectorStore already returns results ordered by score descending
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc1, doc2));

        var results = service.search("我想把應用部署到容器環境");

        assertThat(results).hasSize(2);

        var first = results.get(0);
        assertThat(first.id()).isEqualTo("skill-1");
        assertThat(first.name()).isEqualTo("docker-compose-helper");
        assertThat(first.description()).isEqualTo("管理 Docker Compose 多容器部署");
        assertThat(first.author()).isEqualTo("sam");
        assertThat(first.category()).isEqualTo("DevOps");
        assertThat(first.latestVersion()).isEqualTo("1.0.0");
        assertThat(first.riskLevel()).isEqualTo("LOW");
        assertThat(first.downloadCount()).isEqualTo(42L);
        assertThat(first.score()).isEqualTo(0.89);

        // Verify order: score should be descending (VectorStore responsibility, preserved by stream)
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    @DisplayName("AC-2: 無相關結果 — 回傳空陣列（非 404，非例外）")
    void semanticSearchReturnsEmptyListWhenNoMatch() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        var results = service.search("量子力學計算");

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("AC-1: null score 降級為 0.0（SearchProjection 尚未設定 score 時的防禦）")
    void nullScoreFallsBackToZero() {
        // Document built without explicit score → getScore() returns null
        var doc = Document.builder()
                .id("skill-3")
                .text("test-skill")
                .metadata(Map.of(
                        "skillId", "skill-3",
                        "name", "test-skill",
                        "description", "test",
                        "author", "bob",
                        "category", "Testing"))
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        var results = service.search("some query");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(0.0);
    }
}
