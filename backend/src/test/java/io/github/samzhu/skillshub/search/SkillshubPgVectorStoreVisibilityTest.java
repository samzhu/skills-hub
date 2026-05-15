package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S177-T04 — vector similarity search uses vector_store.is_public for public
 * visibility and ACL only for explicit private grants.
 */
class SkillshubPgVectorStoreVisibilityTest extends RepositorySliceTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private SkillRepository skillRepo;

    @BeforeEach
    void cleanState() {
        jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC-S177-4: anonymous semantic search ignores public pseudo ACL in vector_store")
    @Tag("AC-S177-4")
    void anonymousSemanticSearchUsesIsPublicInsteadOfPublicAcl() {
        var publicSkill = seedVector(true, List.of("user:u_alice0:read"), "s177-public-vector");
        var privateStalePublicAcl = seedVector(false, List.of("user:u_alice0:read", "public:*:read"),
                "s177-private-stale-vector");

        var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .aclPatterns(List.of())
                .build()
                .similaritySearch(SearchRequest.builder()
                        .query("s177 vector").topK(10).similarityThreshold(0.0).build());

        assertThat(docs).extracting(Document::getId)
                .contains(publicSkill)
                .doesNotContain(privateStalePublicAcl);
    }

    @Test
    @DisplayName("AC-S177-5: authenticated semantic search returns public and granted private vectors")
    @Tag("AC-S177-5")
    void authenticatedSemanticSearchReturnsPublicAndGrantedPrivateVectors() {
        var publicSkill = seedVector(true, List.of("user:u_alice0:read"), "s177-public-vector");
        var privateShared = seedVector(false, List.of("user:u_bob00:read"), "s177-private-shared-vector");
        var privateHidden = seedVector(false, List.of("user:u_alice0:read"), "s177-private-hidden-vector");

        var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .aclPatterns(List.of("user:u_bob00:read"))
                .build()
                .similaritySearch(SearchRequest.builder()
                        .query("s177 vector").topK(10).similarityThreshold(0.0).build());

        assertThat(docs).extracting(Document::getId)
                .contains(publicSkill, privateShared)
                .doesNotContain(privateHidden);
    }

    private String seedVector(boolean isPublic, List<String> aclEntries, String name) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                id, name, "S177 vector fixture", "u_alice0", "testing",
                "1.0.0", null, "PUBLISHED", 0L, now, now, List.of(), null));
        jdbc.update("UPDATE skills SET is_public = ? WHERE id = ?", isPublic, id);

        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("u_alice0")
                .skillId(id)
                .aclEntries(aclEntries)
                .build()
                .add(List.of(Document.builder()
                        .id(id)
                        .text(name + " S177 vector fixture")
                        .metadata(Map.of("skillId", id, "name", name, "description", "S177 vector fixture"))
                        .build()));
        jdbc.update("UPDATE vector_store SET is_public = ? WHERE id = ?::uuid", isPublic, id);
        return id;
    }
}
