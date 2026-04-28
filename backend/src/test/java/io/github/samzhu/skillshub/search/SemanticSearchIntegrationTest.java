package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.query.SkillReadModel;
import io.github.samzhu.skillshub.skill.query.SkillReadModelRepository;

/**
 * E2E Integration test for the semantic search HTTP endpoint — verifies AC-1 and AC-2
 * against the full Spring application context with real {@link SkillshubPgVectorStore}
 * + Testcontainers pgvector PostgreSQL（T8 重寫；移除 simple fallback override）。
 *
 * <p>Uses {@link MockitoBean} to replace any auto-configured {@link EmbeddingModel} with
 * a Mockito mock that returns a fixed-seed random vector for all inputs. Because the same
 * seed produces the same 768-dimensional vector for both document and query embeddings,
 * cosine similarity equals 1.0, guaranteeing seeded documents are returned regardless
 * of the query text.
 *
 * <p>**T8 變動**：seed 改用 {@link SkillshubPgVectorStore#builder} 直接寫入真實 vector_store
 * 表（valid UUID 取代 T7 的字串 ID）；test isolation 用 {@code DELETE FROM vector_store
 * WHERE id = ?::uuid} 在 {@link #setUp} 清乾淨。
 *
 * <p>FK 前置：seed 前先寫一筆 skills row 滿足 vector_store.skill_id → skills.id FK。
 *
 * @see SemanticSearchService
 * @see SkillshubPgVectorStore
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Inline properties have the highest priority (override external config files).
        // Prevents config/application-secrets.properties from enabling googleGenAiTextEmbedding,
        // which would create a second EmbeddingModel bean and make @MockitoBean ambiguous.
        properties = {
                "spring.ai.model.embedding.text=none",
                "spring.ai.google.genai.embedding.api-key=TEST-DISABLED"
        })
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SemanticSearchIntegrationTest {

    /** Stable UUID used in AC-1; deleted in @BeforeEach to isolate AC-2. */
    private static final String TEST_DOC_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillReadModelRepository skillRepo;

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
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });

        // Clean state: remove any document left over from previous test runs.
        jdbc.update("DELETE FROM vector_store WHERE id = ?::uuid", TEST_DOC_ID);
    }

    @Test
    @DisplayName("AC-1: GET /api/v1/search/semantic returns HTTP 200 with results containing all required fields")
    void semanticSearchReturnsResultsWithAllRequiredFields() {
        // FK 前置：skills row 必須存在（vector_store.skill_id REFERENCES skills.id）
        var now = Instant.now();
        if (skillRepo.findById(TEST_DOC_ID).isEmpty()) {
            skillRepo.save(new SkillReadModel(
                    TEST_DOC_ID, "docker-compose-helper", "管理 Docker Compose 多容器部署",
                    "sam", "DevOps", "1.0.0", "LOW", "DRAFT", 0L, now, now,
                    List.of())); // S016 aclEntries — 本 IT 不驗 ACL filter
        }

        // Seed: 直接用 SkillshubPgVectorStore 寫入真實 vector_store 表
        // 這跳過 event pipeline（由 SearchProjectionTest 涵蓋），focus 在 HTTP endpoint contract
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("integration-test-owner")
                .skillId(TEST_DOC_ID)
                .build()
                .add(List.of(Document.builder()
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
        // score = 1 - cosine distance；fixed-seed embeddings → distance ≈ 0 → score ≈ 1.0
        assertThat(result.get().score()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("AC-2: GET /api/v1/search/semantic returns HTTP 200 with empty array when no documents match")
    void semanticSearchReturnsEmptyArrayWhenNoDocumentsMatch() {
        // 先確保 vector_store 完全空（無任何 row）
        jdbc.update("DELETE FROM vector_store");

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
