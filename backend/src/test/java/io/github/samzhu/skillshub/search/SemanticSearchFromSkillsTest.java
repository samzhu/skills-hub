package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * S186-T02 — semantic search reads result cards from {@code skills.embedding} instead of
 * the removed {@code vector_store} table.
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

    private static final String QUERY = "部署容器";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
        TestUserSeed.seedDefaults(jdbc);
    }

    @Test
    @DisplayName("AC-S186-2: semantic search returns public skill from skills.embedding without vector_store")
    void semanticSearchReturnsPublicSkillFromSkillsEmbeddingWithoutVectorStore() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.vector_store')", String.class)).isNull();
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
}
