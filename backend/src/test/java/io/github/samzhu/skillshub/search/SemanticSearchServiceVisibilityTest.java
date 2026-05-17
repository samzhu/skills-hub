package io.github.samzhu.skillshub.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;

/**
 * S177-T04 — semantic search service no longer appends public:*:read.
 */
class SemanticSearchServiceVisibilityTest {

    @Test
    @DisplayName("AC-S177-4: semantic search does not append public pseudo principal")
    @Tag("AC-S177-4")
    void semanticSearchDoesNotAppendPublicPseudoPrincipal() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var embeddingModel = mock(EmbeddingModel.class);
        var principals = mock(PrincipalContextService.class);
        var userDisplayService = mock(UserDisplayService.class);
        var connection = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        when(principals.currentPrincipalKeys()).thenReturn(Set.of());
        when(embeddingModel.embed("hello")).thenReturn(new float[] { 1.0f, 0.0f });
        when(connection.prepareStatement(SemanticSearchService.SEMANTIC_SEARCH_SQL_FROM_SKILLS)).thenReturn(ps);
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            creator.createPreparedStatement(connection);
            return List.of();
        });

        var service = new SemanticSearchService(jdbc, embeddingModel, principals, userDisplayService, 0.0);
        service.search("hello", 10);

        verify(ps).setString(2, "{}");
    }
}
