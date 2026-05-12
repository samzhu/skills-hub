package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.convention.TestBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S157 — Real Gemini API fixture-based regression test。
 *
 * <p>**為什麼存在**：既有 {@link SemanticSearchIntegrationTest} 用 fixed-seed random vector
 * （{@code TestcontainersConfiguration.mockEmbeddingModel}）— cosine ≈ 1.0 對 doc/query 同
 * 函式 generate，**不能驗 cross-semantic ranking**（"browser automation" 該排到 agent-browser
 * 不是 agent-memory）。Production 用真 Gemini 算的 768-d 向量帶語意；random vector 沒帶。
 *
 * <p>本 IT 用 SQL fixture（{@code search/embedding-fixture.sql}）含 5 個來自 ClawHub
 * (github.com/VoltAgent/awesome-openclaw-skills) 真實 agent skill 的 **real Gemini 向量**，
 * 配 3 個 pre-embed query 向量（{@link EmbeddingFixture#QUERIES}），在 Testcontainers
 * pgvector 跑真實 cosine 排序，驗 cross-semantic ranking 不退化。
 *
 * <p>**為什麼不每次跑都打 Gemini**：fixture 在 git；test 用 fixture-based {@link EmbeddingModel}
 * 走 lookup 不打 API；只在 model 升級或半年 maintenance 才 refresh（refresh 程序見
 * {@link EmbeddingFixture} javadoc）。
 *
 * <p>Production 真實風險覆蓋：
 * <ul>
 *   <li>S157 修的 native AOT bake-out — 此 IT JVM 模式跑，不直接驗 native bake-out；但若
 *       SearchConfig 設計回退（@Conditional 加回）→ embedding bean 不出現 → 此 IT
 *       Spring context load 階段炸（{@code @TestBean(name = "mockEmbeddingModel")} 找不到
 *       既有 bean 替換） → regression 抓得到</li>
 *   <li>Gemini embedding 維度從 768 變動 → SQL fixture INSERT vector(768) 對不上實際 dim → IT 炸</li>
 *   <li>cosine 排序行為退化（如有人改 SkillshubPgVectorStore 排序方向 / threshold 算法）→ 排序
 *       assertion 抓得到</li>
 * </ul>
 *
 * @see EmbeddingFixture
 * @see SemanticSearchIntegrationTest
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.embedding.text=none",
                "spring.ai.google.genai.embedding.api-key=TEST-DISABLED"
        })
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class SemanticSearchRealFixtureIT {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Override {@code TestcontainersConfiguration.mockEmbeddingModel} (random vector @Primary)
     * with fixture-lookup 版本，避開 dual-@Primary 衝突。Spring 7 {@code @TestBean} 按 name
     * 找既有 bean 替換，無 @Primary 副作用。
     */
    @TestBean(name = "mockEmbeddingModel")
    EmbeddingModel mockEmbeddingModel;

    static EmbeddingModel mockEmbeddingModel() {
        return new FixtureEmbeddingModel();
    }

    @BeforeEach
    void seedFixture() throws Exception {
        // CASCADE 連動清 vector_store / skill_versions / etc
        jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
        // ScriptUtils 走 ;-terminated statement parse；SQL fixture 一行一條 INSERT
        try (var conn = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new ClassPathResource("search/embedding-fixture.sql"));
        }
    }

    @Test
    @Tag("AC-real-1")
    @DisplayName("AC-real-1: query 'browser automation and web scraping' → top match agent-browser")
    void browserAutomationQueryRanksAgentBrowserTop() {
        assertTopMatch("browser automation and web scraping");
    }

    @Test
    @Tag("AC-real-2")
    @DisplayName("AC-real-2: query 'container deployment and process management' → top match agentic-devops")
    void containerDeploymentQueryRanksAgenticDevopsTop() {
        assertTopMatch("container deployment and process management");
    }

    @Test
    @Tag("AC-real-3")
    @DisplayName("AC-real-3: query 'code security review' → top match agent-skills-audit")
    void securityReviewQueryRanksAgentSkillsAuditTop() {
        assertTopMatch("code security review");
    }

    @Test
    @Tag("AC-real-4")
    @DisplayName("AC-real-4: vector_store 5 rows real 768-d embeddings — schema dim check")
    void fixtureVectorsAre768Dim() {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
        assertThat(count).isEqualTo(5L);

        // pgvector 沒有公開 dim API；用 vector::text length 粗略驗（[v1,v2,...,v768] 應接近 fixed range）
        var sampleDim = jdbc.queryForObject(
                "SELECT array_length(string_to_array(trim(both '[]' from embedding::text), ','), 1) "
                        + "FROM vector_store LIMIT 1",
                Integer.class);
        assertThat(sampleDim).isEqualTo(768);
    }

    private void assertTopMatch(String queryText) {
        var expectedSkillId = EmbeddingFixture.EXPECTED_TOP.get(queryText);
        assertThat(expectedSkillId).as("fixture must have expected mapping for '" + queryText + "'").isNotNull();

        var url = "/api/v1/search/semantic?q=" + URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        var response = restTemplate.getForEntity(url, SemanticSearchResult[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isNotEmpty();

        var top = response.getBody()[0];
        assertThat(top.id())
                .as("query '%s' top match should be skill %s (real Gemini cosine ranking)",
                        queryText, expectedSkillId)
                .isEqualTo(expectedSkillId);
        // score = 1 - cosine distance；same-text doc embeddings vs query embeddings 預期 strong match
        assertThat(top.score()).isGreaterThan(0.5);
    }

    /**
     * 查表型 EmbeddingModel — text matches {@link EmbeddingFixture#QUERIES} 則回 real Gemini
     * pre-embed 向量；不在 fixture 則 fail-fast（避免 test 靜默走錯路徑）。
     */
    static class FixtureEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = request.getInstructions().stream()
                    .map(text -> new org.springframework.ai.embedding.Embedding(embed(text), 0))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            var vec = EmbeddingFixture.QUERIES.get(text);
            if (vec == null) {
                throw new IllegalStateException(
                        "Fixture lookup miss for text='" + text + "'. Add it to EmbeddingFixture (refresh procedure in javadoc).");
            }
            return Arrays.copyOf(vec, vec.length);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }
}
