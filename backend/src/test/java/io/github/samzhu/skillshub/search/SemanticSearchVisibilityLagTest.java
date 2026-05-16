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
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.security.testsupport.TestUserSeed;

/**
 * S186-T04 — semantic search reads visibility and explicit grants from the same
 * {@code skills} row that stores the embedding.
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
class SemanticSearchVisibilityLagTest {

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
    @DisplayName("AC-S186-4: semantic search sees public visibility change from skills row without vector projection")
    void semanticSearchSeesPublicVisibilityChangeFromSkillsRowWithoutVectorProjection() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.vector_store')", String.class)).isNull();
        seedSkill("skill-public-after-toggle", false, List.of(), embeddingModel.embed(QUERY));

        jdbc.update("UPDATE skills SET is_public = TRUE WHERE id = ?", "skill-public-after-toggle");

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-public-after-toggle')]").exists());
    }

    @Test
    @DisplayName("AC-S186-4: semantic search sees explicit read grant from skills.acl_entries without vector projection")
    void semanticSearchSeesExplicitReadGrantFromSkillsAclEntriesWithoutVectorProjection() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.vector_store')", String.class)).isNull();
        seedSkill("skill-granted-private", false, List.of("user:" + TestUserSeed.BOB_ID + ":read"),
                embeddingModel.embed(QUERY));

        mvc.perform(get("/api/v1/search/semantic")
                        .param("q", QUERY)
                        .param("limit", "10")
                        .with(jwt().jwt(j -> j.subject("bob")
                                        .claim("roles", List.of("user"))
                                        .claim("groups", List.of()))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'skill-granted-private')]").exists());
    }

    private void seedSkill(String id, boolean isPublic, List<String> aclEntries, float[] embedding) {
        jdbc.update("""
                INSERT INTO skills (
                    id, name, description, author, category, category_display, latest_version,
                    risk_level, status, download_count, created_at, updated_at, acl_entries,
                    is_public, owner_id, embedding_content, embedding_model, embedding_updated_at
                )
                VALUES (?, ?, '協助團隊部署容器', ?, 'devops', 'DevOps', '1.0.0',
                    'LOW', 'PUBLISHED', 0, NOW(), NOW(), ?::jsonb, ?, ?, ?, 'test-embedding', NOW())
                """, id, "test-skill-" + id, TestUserSeed.ALICE_ID, jsonArray(aclEntries),
                isPublic, TestUserSeed.ALICE_ID, "test-skill-" + id + " 協助團隊部署容器");

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
