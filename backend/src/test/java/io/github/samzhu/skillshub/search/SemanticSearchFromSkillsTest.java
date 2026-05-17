package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.security.testsupport.TestUserSeed;

/**
 * S186-T02 — semantic search reads result cards from {@code skills.embedding}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.ai.model.embedding.text=none",
        "spring.ai.google.genai.embedding.api-key=TEST-DISABLED",
        "skillshub.search.semantic-similarity-threshold=-1.0"
})
@Tag("S186")
class SemanticSearchFromSkillsTest {

    private static final int EMBEDDING_DIMENSIONS = 768;
    private static final String QUERY = "部署容器";
    private static final String REMOVED_VECTOR_TABLE = "vector" + "_store";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private SemanticSearchService semanticSearchService;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
        TestUserSeed.seedDefaults(jdbc);
    }

    @Test
    @DisplayName("AC-S186-2: semantic search returns public skill from skills.embedding")
    void semanticSearchReturnsPublicSkillFromSkillsEmbeddingWithoutVectorStore() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public." + REMOVED_VECTOR_TABLE + "')", String.class))
                .isNull();
        seedSkill("skill-docker", "docker-compose-helper", "u_current", "devops", "DevOps",
                true, List.of("public:*:read"), 7L, embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-docker')]").exists());
    }

    @Test
    @DisplayName("AC-S186-3: anonymous semantic search hides private skill stored in skills.embedding")
    void anonymousSemanticSearchHidesPrivateSkillStoredInSkillsEmbedding() throws Exception {
        seedSkill("skill-private", "private-deploy-helper", "u_current", "devops", "DevOps",
                false, List.of("user:" + TestUserSeed.ALICE_ID + ":read"), 3L, embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-private')]").doesNotExist());
    }

    @Test
    @DisplayName("AC-S186-3: granted user semantic search sees private skill from skills.acl_entries")
    void grantedUserSemanticSearchSeesPrivateSkillFromSkillsAclEntries() throws Exception {
        seedSkill("skill-private", "private-deploy-helper", "u_current", "devops", "DevOps",
                false, List.of("user:" + TestUserSeed.ALICE_ID + ":read"), 3L, embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10")
                        .with(jwt().jwt(j -> j.subject("alice")
                                        .claim("roles", List.of("user"))
                                        .claim("groups", List.of()))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-private')]").exists());
    }

    @Test
    @DisplayName("AC-S186-8: semantic result card fields come from the same skills row")
    void semanticResultCardFieldsComeFromTheSameSkillsRow() throws Exception {
        seedSkill("skill-card", "docker-compose-helper", "u_current", "devops", "DevOps",
                true, List.of("public:*:read"), 7L, embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-card' && @.name == 'docker-compose-helper' "
                        + "&& @.author == 'u_current' && @.category == 'devops' "
                        + "&& @.categoryDisplay == 'DevOps' && @.downloadCount == 7)]").exists());
    }

    @Test
    @DisplayName("AC-S192-3: semantic search result includes author display fields without author-based ranking")
    @Tag("AC-S192-3")
    void semanticSearchResultIncludesAuthorDisplayFieldsWithoutAuthorBasedRanking() throws Exception {
        seedUser("u_f7eb3a", "sam-sub", "sam@example.com", "Sam Zhu", "samzhu");
        seedSkill("skill-subtitle", "subtitle-helper", "u_f7eb3a", "video", "Video",
                true, List.of("public:*:read"), 11L, embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-subtitle' "
                        + "&& @.author == 'u_f7eb3a' "
                        + "&& @.authorDisplayName == 'Sam Zhu' "
                        + "&& @.authorHandle == 'samzhu')]").exists());

        assertThat(SemanticSearchService.SEMANTIC_SEARCH_SQL_FROM_SKILLS)
                .as("S192 display enrichment must not add author name/handle to SQL search or ranking")
                .doesNotContain("users")
                .doesNotContain("author_display_name")
                .doesNotContain("author_handle")
                .doesNotContain("name ILIKE")
                .doesNotContain("handle ILIKE")
                .doesNotContain("ORDER BY author");
    }

    @Test
    @DisplayName("AC-S193-2: semantic search log includes top hit scores without sensitive fields")
    @Tag("AC-S193-2")
    void semanticSearchLogIncludesTopHitScoresWithoutSensitiveFields() {
        var queryVector = vectorAt(0);
        when(embeddingModel.embed("影片轉字幕")).thenReturn(queryVector);
        seedSkill("skill-subtitle", "產生字幕檔", "u_current", "video", "Video",
                true, List.of("public:*:read"), 7L, queryVector);

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SemanticSearchService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            semanticSearchService.search("影片轉字幕", 10);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        var event = appender.list.stream()
                .filter(item -> "ACL-aware semantic search 完成".equals(item.getFormattedMessage()))
                .reduce((first, second) -> second)
                .orElseThrow();
        var keys = event.getKeyValuePairs().stream().map(pair -> pair.key).toList();

        assertThat(keys)
                .contains("query", "resultsCount", "topHitIds", "topHitNames", "topHitScores")
                .doesNotContain("token", "cookie", "email", "requestBody");
        assertThat(logValue(event, "query")).isEqualTo("影片轉字幕");
        assertThat(logValue(event, "resultsCount")).isEqualTo(1);
        assertThat(logValue(event, "topHitIds")).isEqualTo(List.of("skill-subtitle"));
        assertThat(logValue(event, "topHitNames")).isEqualTo(List.of("產生字幕檔"));
        assertThat(logValue(event, "topHitScores")).isEqualTo(List.of(1.0d));
    }

    @Test
    @DisplayName("AC-S193-3: positive query score is higher than weak query score")
    @Tag("AC-S193-3")
    void positiveQueryScoreIsHigherThanWeakQueryScore() {
        var positiveVector = vectorAt(0);
        var weakVector = vectorAt(1);
        when(embeddingModel.embed("影片轉字幕")).thenReturn(positiveVector);
        when(embeddingModel.embed("甜點")).thenReturn(weakVector);
        seedSkill("skill-subtitle", "產生字幕檔", "u_current", "video", "Video",
                true, List.of("public:*:read"), 7L, positiveVector);

        var positiveScore = semanticSearchService.search("影片轉字幕", 10).stream()
                .filter(result -> "skill-subtitle".equals(result.id()))
                .findFirst()
                .orElseThrow()
                .score();
        var weakScore = semanticSearchService.search("甜點", 10).stream()
                .filter(result -> "skill-subtitle".equals(result.id()))
                .findFirst()
                .orElseThrow()
                .score();

        assertThat(positiveScore).isGreaterThan(weakScore);
    }

    @Test
    @DisplayName("AC-S193-4: semantic response is ordered by score descending")
    @Tag("AC-S193-4")
    void semanticResponseIsOrderedByScoreDescending() {
        var queryVector = vectorAt(0);
        var weakVector = vectorAt(1);
        when(embeddingModel.embed("影片轉字幕")).thenReturn(queryVector);
        seedSkill("skill-strong", "產生字幕檔", "u_current", "video", "Video",
                true, List.of("public:*:read"), 7L, queryVector);
        seedSkill("skill-weak", "甜點整理", "u_current", "utility", "Utility",
                true, List.of("public:*:read"), 3L, weakVector);

        var results = semanticSearchService.search("影片轉字幕", 10);
        var scores = results.stream().map(SemanticSearchResult::score).toList();

        assertThat(results)
                .extracting(SemanticSearchResult::id)
                .containsSubsequence("skill-strong", "skill-weak");
        assertThat(scores).isSortedAccordingTo((left, right) -> Double.compare(right, left));
    }

    private void seedUser(String id, String sub, String email, String name, String handle) {
        jdbc.update("""
                INSERT INTO users (id, oauth_provider, sub, email, name, handle, created_at, last_seen_at)
                VALUES (?, 'google', ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT DO NOTHING
                """, id, sub, email, name, handle);
    }

    private void seedSkill(String id, String name, String author, String category, String categoryDisplay,
            boolean isPublic, List<String> aclEntries, long downloadCount, float[] embedding) {
        jdbc.update("""
                INSERT INTO skills (
                    id, name, description, author, category, category_display, latest_version,
                    risk_level, status, download_count, created_at, updated_at, acl_entries,
                    is_public, owner_id, embedding_content, embedding_model, embedding_updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, '1.2.3', 'LOW', 'PUBLISHED', ?, NOW(), NOW(),
                    ?::jsonb, ?, ?, ?, 'test-embedding', NOW())
                """, id, name, "協助團隊部署容器", author, category, categoryDisplay,
                downloadCount, jsonArray(aclEntries), isPublic, author, name + " 協助團隊部署容器");

        jdbc.update(connection -> {
            var ps = connection.prepareStatement("UPDATE skills SET embedding = ? WHERE id = ?");
            StatementCreatorUtils.setParameterValue(ps, 1, SqlTypeValue.TYPE_UNKNOWN, new PGvector(embedding));
            ps.setString(2, id);
            return ps;
        });
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static float[] vectorAt(int index) {
        var vector = new float[EMBEDDING_DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

    private static Object logValue(ch.qos.logback.classic.spi.ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElseThrow();
    }
}
