package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S186-T06 evidence test for the skills-table semantic query plan.
 */
@Tag("S186")
class SemanticSearchExplainEvidenceTest extends RepositorySliceTestBase {
    private static final String REMOVED_VECTOR_TABLE = "vector" + "_store";

    private static final String SEMANTIC_SQL = """
            SELECT id, name, description, author, category, category_display,
                   latest_version, risk_level, download_count,
                   embedding <=> ? AS distance
              FROM skills
             WHERE status = 'PUBLISHED'
               AND embedding IS NOT NULL
               AND (is_public = TRUE OR acl_entries ??| ?::text[])
               AND embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC-S186-7: semantic skills query records EXPLAIN ANALYZE BUFFERS evidence")
    void semanticSkillsQueryRecordsExplainAnalyzeBuffersEvidence() {
        seedSkill("skill-public", "docker-compose-helper", true, "[]", "PUBLISHED", unitVector(0));
        seedSkill("skill-private", "internal-release-helper", false,
                "[\"user:alice:read\"]", "PUBLISHED", unitVector(0));
        seedSkill("skill-draft", "draft-deploy-helper", true, "[]", "DRAFT", unitVector(0));

        var plan = explainAnalyze(unitVector(0), List.of());
        System.out.println("\nS186 EXPLAIN ANALYZE BUFFERS\n" + plan + "\n");

        assertThat(plan).contains("skills");
        assertThat(plan).contains("Execution Time");
        assertThat(plan).contains("Buffers: shared");
        assertThat(plan.contains("idx_skills_embedding_hnsw")
                || plan.contains("idx_skills_status")
                || plan.contains("Seq Scan on skills")).isTrue();
        assertThat(plan).doesNotContain(REMOVED_VECTOR_TABLE);
    }

    private void seedSkill(String id, String name, boolean isPublic, String aclEntries,
            String status, float[] embedding) {
        jdbc.update("""
                INSERT INTO skills (
                    id, name, description, author, category, category_display, latest_version,
                    risk_level, status, download_count, created_at, updated_at, acl_entries,
                    is_public, owner_id, embedding_content, embedding_model, embedding_updated_at
                )
                VALUES (?, ?, ?, 'alice', 'devops', 'DevOps', '1.0.0',
                    'LOW', ?, 0, NOW(), NOW(), ?::jsonb, ?, 'alice', ?, 'test-model', NOW())
                """, id, name, "Container deployment helper", status, aclEntries, isPublic,
                name + " Container deployment helper");

        jdbc.update(connection -> {
            var ps = connection.prepareStatement("UPDATE skills SET embedding = ? WHERE id = ?");
            bind(ps, 1, new PGvector(embedding));
            bind(ps, 2, id);
            return ps;
        });
    }

    private String explainAnalyze(float[] queryVector, List<String> aclPatterns) {
        return String.join("\n", jdbc.query(connection -> {
            var ps = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + SEMANTIC_SQL);
            bind(ps, 1, new PGvector(queryVector));
            bind(ps, 2, pgArrayLiteral(aclPatterns));
            bind(ps, 3, new PGvector(queryVector));
            bind(ps, 4, 2.0d);
            bind(ps, 5, 10);
            return ps;
        }, (rs, rowNum) -> rs.getString(1)));
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        StatementCreatorUtils.setParameterValue(ps, index, SqlTypeValue.TYPE_UNKNOWN, value);
    }

    private static String pgArrayLiteral(List<String> items) {
        return items.isEmpty() ? "{}" : "{" + String.join(",", items) + "}";
    }

    private static float[] unitVector(int dimension) {
        var vector = new float[768];
        Arrays.fill(vector, 0.0f);
        vector[dimension] = 1.0f;
        return vector;
    }
}
