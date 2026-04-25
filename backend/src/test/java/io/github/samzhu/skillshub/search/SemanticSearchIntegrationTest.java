package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * E2E Integration test for the semantic search HTTP endpoint — verifies AC-1 and AC-2
 * against the full Spring application context with SimpleVectorStore.
 *
 * <p>Uses {@link MockitoBean} to replace any auto-configured {@link EmbeddingModel} with
 * a Mockito mock that returns a fixed-seed random vector for all inputs. Because the same
 * seed produces the same 768-dimensional vector for both document and query embeddings,
 * cosine similarity equals 1.0, guaranteeing seeded documents are returned regardless
 * of the query text.
 *
 * <p>Test isolation: {@code TEST_DOC_ID} is deleted in {@link #setUp()} before each test,
 * ensuring the in-memory VectorStore is empty at the start of AC-2.
 *
 * @see SemanticSearchService
 * @see SearchConfig
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SemanticSearchIntegrationTest {

    /** Stable document ID used in AC-1; deleted in @BeforeEach to isolate AC-2. */
    private static final String TEST_DOC_ID = "integration-test-docker-skill";

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Real in-memory VectorStore bean (SimpleVectorStore) — allows direct seeding
     * without going through the event pipeline, keeping E2E scope focused on the HTTP layer.
     */
    @Autowired
    private VectorStore vectorStore;

    /**
     * Replaces any auto-configured EmbeddingModel (including the noOp fallback and any
     * Google GenAI bean). Stubbings are configured in {@link #setUp()}.
     */
    @MockitoBean
    EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        // Fixed random embeddings (seed 42): same 768-dim vector for all inputs.
        // Cosine similarity of a vector with itself = 1.0 > SIMILARITY_THRESHOLD (0.3),
        // so any seeded document is always returned by similaritySearch.
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        // Clean state: remove any document left over from a previous test run in this class.
        vectorStore.delete(List.of(TEST_DOC_ID));
    }

    @Test
    @DisplayName("AC-1: GET /api/v1/search/semantic returns HTTP 200 with results containing all required fields")
    void semanticSearchReturnsResultsWithAllRequiredFields() {
        // Seed: add a known document directly to the in-memory VectorStore.
        // This bypasses the event pipeline (tested separately in SearchProjectionTest)
        // and keeps this test focused on the HTTP endpoint contract.
        vectorStore.add(List.of(Document.builder()
                .id(TEST_DOC_ID)
                .text("docker-compose-helper 管理 Docker Compose 多容器部署")
                .metadata(Map.of(
                        "skillId", TEST_DOC_ID,
                        "name", "docker-compose-helper",
                        "description", "管理 Docker Compose 多容器部署",
                        "author", "sam",
                        "category", "DevOps",
                        "downloadCount", 0L))
                .build()));

        var response = restTemplate.getForEntity(
                "/api/v1/search/semantic?q=" + URLEncoder.encode("部署容器應用", StandardCharsets.UTF_8),
                SemanticSearchResult[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isNotEmpty();

        // Verify the seeded document appears with all required fields
        var result = Arrays.stream(response.getBody())
                .filter(r -> TEST_DOC_ID.equals(r.id()))
                .findFirst();
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("docker-compose-helper");
        assertThat(result.get().description()).isEqualTo("管理 Docker Compose 多容器部署");
        assertThat(result.get().author()).isEqualTo("sam");
        assertThat(result.get().category()).isEqualTo("DevOps");
        // score comes from SimpleVectorStore cosine similarity (1.0 with fixed-seed embeddings)
        assertThat(result.get().score()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("AC-2: GET /api/v1/search/semantic returns HTTP 200 with empty array when VectorStore has no documents")
    void semanticSearchReturnsEmptyArrayWhenVectorStoreIsEmpty() {
        // No documents seeded — VectorStore is empty after @BeforeEach cleanup.
        // The endpoint must return 200 [] instead of 404 or throwing an exception.
        var response = restTemplate.getForEntity(
                "/api/v1/search/semantic?q=" + URLEncoder.encode("量子力學計算", StandardCharsets.UTF_8),
                SemanticSearchResult[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    /**
     * Returns a fixed-seed 768-dimensional random unit vector.
     * Using the same seed on every call produces the same vector, giving cosine
     * similarity 1.0 between any two embeddings generated by this method.
     */
    private static float[] randomVector(int dim) {
        var v = new float[dim];
        var r = new Random(42);
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextFloat() * 2 - 1;
        }
        return v;
    }
}
