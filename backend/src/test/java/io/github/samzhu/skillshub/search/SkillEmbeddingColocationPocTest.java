package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.UpdateSkillCommand;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S186 POC — 驗證把 semantic embedding 放在 skills 同表時，Spring Data JDBC aggregate
 * 不 mapping embedding 欄位仍可正常讀寫，semantic SQL 也能直接用同一 row 的 visibility/ACL。
 */
@Tag("S186")
class SkillEmbeddingColocationPocTest extends RepositorySliceTestBase {

    private static final String SEMANTIC_SQL = """
            SELECT id, name, description, author, category, embedding <=> ? AS distance
              FROM skills
             WHERE status = 'PUBLISHED'
               AND embedding IS NOT NULL
               AND (is_public = TRUE OR acl_entries ??| ?::text[])
               AND embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillRepository skillRepo;

    @BeforeEach
    void setUp() {
        ensureSkillEmbeddingColumns();
        jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("POC-S186-1: Skill aggregate 不 mapping embedding，repo save 後 embedding 欄位仍保留")
    void skillAggregateSaveDoesNotClearUnmappedEmbeddingColumns() {
        var skill = Skill.create(new CreateSkillCommand(
                uniqueName("poc-save"), "POC save keeps embedding", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var saved = skillRepo.save(skill);
        updateEmbedding(saved.getId(), "docker compose helper", unitVector(0), "poc-model");

        var loaded = skillRepo.findById(saved.getId()).orElseThrow();
        loaded.update(new UpdateSkillCommand("POC save keeps embedding after normal edit", null), "alice");
        skillRepo.save(loaded);

        var embeddingContent = jdbc.queryForObject(
                "SELECT embedding_content FROM skills WHERE id = ?",
                String.class, saved.getId());
        var embeddingModel = jdbc.queryForObject(
                "SELECT embedding_model FROM skills WHERE id = ?",
                String.class, saved.getId());
        var distance = distanceTo(saved.getId(), unitVector(0));

        assertThat(Skill.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .noneMatch(name -> name.toLowerCase().contains("embedding"));
        assertThat(embeddingContent).isEqualTo("docker compose helper");
        assertThat(embeddingModel).isEqualTo("poc-model");
        assertThat(distance).isLessThan(0.000001);
    }

    @Test
    @DisplayName("POC-S186-2: semantic SQL 直接查 skills.embedding 並套同 row 的 is_public/acl_entries")
    void sameTableSemanticSqlUsesSkillVisibilityAndAclWithoutVectorStore() {
        var publicSkill = seedPublishedSkill("poc-public", "alice", Visibility.PUBLIC, unitVector(0));
        var alicePrivateSkill = seedPublishedSkill("poc-alice-private", "alice", Visibility.PRIVATE, unitVector(0));
        var bobPrivateSkill = seedPublishedSkill("poc-bob-private", "bob", Visibility.PRIVATE, unitVector(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class)).isZero();

        var anonymousHits = searchSkillsTable(unitVector(0), List.of());
        assertThat(anonymousHits).extracting(Hit::id)
                .contains(publicSkill.getId())
                .doesNotContain(alicePrivateSkill.getId(), bobPrivateSkill.getId());

        var aliceHits = searchSkillsTable(unitVector(0), List.of("user:alice:read"));
        assertThat(aliceHits).extracting(Hit::id)
                .contains(publicSkill.getId(), alicePrivateSkill.getId())
                .doesNotContain(bobPrivateSkill.getId());
        assertThat(aliceHits).extracting(Hit::name)
                .contains(alicePrivateSkill.getName());
    }

    @Test
    @DisplayName("POC-S186-3: EXPLAIN 同表查詢只碰 skills，不碰 vector_store")
    void explainSameTableSemanticSqlTouchesOnlySkills() {
        seedPublishedSkill("poc-explain", "alice", Visibility.PUBLIC, unitVector(0));

        var plan = explainSkillsTableSearch(unitVector(0), List.of());

        assertThat(plan).contains("skills");
        assertThat(plan).doesNotContain("vector_store");
    }

    private Skill seedPublishedSkill(String prefix, String author, Visibility visibility, float[] embedding) {
        var skill = Skill.create(new CreateSkillCommand(
                uniqueName(prefix), "POC semantic SQL fixture", author, "Testing", visibility));
        skill.recordVersionPublished("1.0.0");
        var saved = skillRepo.save(skill);
        updateEmbedding(saved.getId(), saved.getName() + " body", embedding, "poc-model");
        return saved;
    }

    private void ensureSkillEmbeddingColumns() {
        jdbc.execute("ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_content TEXT");
        jdbc.execute("ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding VECTOR(768)");
        jdbc.execute("ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64)");
        jdbc.execute("ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_updated_at TIMESTAMPTZ");
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_skills_embedding_hnsw_poc
                    ON skills USING HNSW (embedding vector_cosine_ops)
                """);
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

    private double distanceTo(String skillId, float[] queryVector) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement("SELECT embedding <=> ? FROM skills WHERE id = ?");
            bind(ps, 1, new PGvector(queryVector));
            bind(ps, 2, skillId);
            return ps;
        }, rs -> {
            assertThat(rs.next()).isTrue();
            return rs.getDouble(1);
        });
    }

    private List<Hit> searchSkillsTable(float[] queryVector, List<String> aclPatterns) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(SEMANTIC_SQL);
            bind(ps, 1, new PGvector(queryVector));
            bind(ps, 2, pgArrayLiteral(aclPatterns));
            bind(ps, 3, new PGvector(queryVector));
            bind(ps, 4, 0.1d);
            bind(ps, 5, 10);
            return ps;
        }, (rs, rowNum) -> new Hit(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("author"),
                rs.getString("category"),
                rs.getDouble("distance")));
    }

    private String explainSkillsTableSearch(float[] queryVector, List<String> aclPatterns) {
        return String.join("\n", jdbc.query(connection -> {
            var ps = connection.prepareStatement("EXPLAIN (COSTS OFF) " + SEMANTIC_SQL);
            bind(ps, 1, new PGvector(queryVector));
            bind(ps, 2, pgArrayLiteral(aclPatterns));
            bind(ps, 3, new PGvector(queryVector));
            bind(ps, 4, 0.1d);
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

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Hit(String id, String name, String description, String author, String category, double distance) {
    }
}
