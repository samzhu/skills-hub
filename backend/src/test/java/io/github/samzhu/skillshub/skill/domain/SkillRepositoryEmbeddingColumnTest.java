package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.UpdateSkillCommand;

@Tag("S186")
class SkillRepositoryEmbeddingColumnTest extends RepositorySliceTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillRepository skillRepo;

    @Test
    @DisplayName("AC-S186-1: Skill aggregate save preserves unmapped embedding columns")
    @Tag("AC-S186-1")
    void aggregateSavePreservesUnmappedEmbeddingColumns() {
        var skill = Skill.create(new CreateSkillCommand(
                uniqueName("s186-save"), "S186 embedding guard", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var saved = skillRepo.save(skill);
        updateEmbedding(saved.getId(), "docker compose helper", unitVector(), "test-embedding-model");

        var loaded = skillRepo.findById(saved.getId()).orElseThrow();
        loaded.update(new UpdateSkillCommand("Platform Tools"), "alice");
        skillRepo.save(loaded);

        var row = jdbc.queryForMap("""
                SELECT embedding_content, embedding_model, embedding IS NOT NULL AS has_embedding
                FROM skills
                WHERE id = ?
                """, saved.getId());

        assertThat(Skill.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("embedding", "embeddingContent", "embeddingUpdatedAt");
        assertThat(row.get("embedding_content")).isEqualTo("docker compose helper");
        assertThat(row.get("embedding_model")).isEqualTo("test-embedding-model");
        assertThat(row.get("has_embedding")).isEqualTo(true);
    }

    @Test
    @DisplayName("AC-S186-1: SkillRepository.findByAuthorAndName does not select star from skills")
    @Tag("AC-S186-1")
    void authorNameLookupDoesNotSelectStarFromSkills() throws NoSuchMethodException {
        var method = SkillRepository.class.getMethod("findByAuthorAndName", String.class, String.class);
        var query = method.getAnnotation(Query.class).value();

        assertThat(query.toLowerCase(Locale.ROOT))
                .doesNotContain("select *")
                .contains("from skills");
        assertThat(query)
                .contains("author_name_snapshot")
                .contains("category_display")
                .contains("owner_id")
                .contains("version");
    }

    private void updateEmbedding(String skillId, String content, float[] embedding, String model) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    UPDATE skills
                    SET embedding_content = ?,
                        embedding = ?,
                        embedding_model = ?,
                        embedding_updated_at = NOW()
                    WHERE id = ?
                    """);
            bind(ps, 1, content);
            bind(ps, 2, new PGvector(embedding));
            bind(ps, 3, model);
            bind(ps, 4, skillId);
            return ps;
        });
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        StatementCreatorUtils.setParameterValue(ps, index, SqlTypeValue.TYPE_UNKNOWN, value);
    }

    private static float[] unitVector() {
        var vector = new float[768];
        Arrays.fill(vector, 0.0f);
        vector[0] = 1.0f;
        return vector;
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
