package io.github.samzhu.skillshub.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S177-T04 — semantic search service no longer appends public:*:read.
 */
class SemanticSearchServiceVisibilityTest {

    @Test
    @DisplayName("AC-S177-4: semantic search does not append public pseudo principal")
    @Tag("AC-S177-4")
    void semanticSearchDoesNotAppendPublicPseudoPrincipal() {
        var jdbc = mock(JdbcTemplate.class);
        var embeddingModel = mock(EmbeddingModel.class);
        var principals = mock(PrincipalContextService.class);
        var skillRepo = mock(SkillRepository.class);
        when(principals.currentPrincipalKeys()).thenReturn(Set.of());
        when(embeddingModel.embed("hello")).thenReturn(new float[] { 1.0f, 0.0f });
        when(skillRepo.findAllById(List.of())).thenReturn(List.of());
        when(jdbc.query(eq(SkillshubPgVectorStore.SIMILARITY_SEARCH_SQL_ACL),
                any(RowMapper.class), any(PGvector.class), any(String.class),
                any(PGvector.class), anyDouble(), anyInt()))
                .thenReturn(List.of());

        var service = new SemanticSearchService(jdbc, embeddingModel, principals, skillRepo, 0.0);
        service.search("hello", 10);

        verify(jdbc).query(eq(SkillshubPgVectorStore.SIMILARITY_SEARCH_SQL_ACL),
                any(RowMapper.class), any(PGvector.class), eq("{}"),
                any(PGvector.class), anyDouble(), anyInt());
    }
}
