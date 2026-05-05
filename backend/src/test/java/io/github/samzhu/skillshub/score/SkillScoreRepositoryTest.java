package io.github.samzhu.skillshub.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S135a T03 — skill_scores DDL + SkillScore aggregate + repository query verification.
 * Uses RepositorySliceTestBase (@DataJdbcTest slice with Testcontainers PostgreSQL).
 * V15 migration runs automatically via Flyway in the slice.
 */
class SkillScoreRepositoryTest extends RepositorySliceTestBase {

    @Autowired
    private SkillScoreRepository scoreRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Helper: insert a row via saveIdempotent, returns the params used.
    private SkillScore insertScore(String skillId, String skillVersionId, String skillVersion,
                                    QualityAxis axis, double total, String sourceEventId, Instant at) {
        var score = SkillScore.of(skillId, skillVersionId, skillVersion, axis,
                BigDecimal.valueOf(total), Map.of("dim", "ok"), "gemini-2.5-flash@v1", sourceEventId);
        scoreRepo.saveIdempotent(
                score.getId(), skillId, skillVersionId, skillVersion, axis.name(),
                BigDecimal.valueOf(total), "{\"dim\":\"ok\"}", at,
                "gemini-2.5-flash@v1", sourceEventId);
        return score;
    }

    @Test
    @DisplayName("AC-S135a-1: SkillScore.of() factory — deterministic UUID PK + isNew = true")
    @Tag("AC-S135a-1")
    void factoryProducesDeterministicUuidAndIsNew() {
        var skillVersionId = "sv-" + uniqueSuffix();
        var sourceEventId = "evt-" + uniqueSuffix();

        var score = SkillScore.of("skill-1", skillVersionId, "1.0.0",
                QualityAxis.VALIDATION, BigDecimal.valueOf(90.0), Map.of(),
                "gemini-2.5-flash@v1", sourceEventId);

        var expectedId = UUID.nameUUIDFromBytes(
                (skillVersionId + "|VALIDATION|" + sourceEventId).getBytes(StandardCharsets.UTF_8)
        ).toString();

        assertThat(score.getId()).isEqualTo(expectedId);
        assertThat(score.isNew()).isTrue();
    }

    @Test
    @DisplayName("AC-S135a-1: save() inserts SkillScore row successfully (isNew=true → INSERT path)")
    @Tag("AC-S135a-1")
    void saveInsertsRow() {
        var skillVersionId = "sv-" + uniqueSuffix();
        var score = SkillScore.of("skill-1", skillVersionId, "1.0.0",
                QualityAxis.IMPLEMENTATION, BigDecimal.valueOf(85.0), Map.of("dim1", "ok"),
                "gemini-2.5-flash@v1", "evt-" + uniqueSuffix());

        scoreRepo.save(score);

        var found = scoreRepo.findById(score.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getAxis()).isEqualTo(QualityAxis.IMPLEMENTATION);
        assertThat(found.get().getTotalScore()).isEqualByComparingTo("85.00");
        assertThat(found.get().getSkillVersionId()).isEqualTo(skillVersionId);
    }

    @Test
    @DisplayName("AC-S135a-2: saveIdempotent() ON CONFLICT DO NOTHING — duplicate sourceEventId safe no-op")
    @Tag("AC-S135a-2")
    void saveIdempotentIsIdempotent() {
        var skillVersionId = "sv-" + uniqueSuffix();
        var sourceEventId = "evt-" + uniqueSuffix();
        var score = SkillScore.of("skill-1", skillVersionId, "1.0.0",
                QualityAxis.VALIDATION, BigDecimal.valueOf(100.0), Map.of(),
                "gemini-2.5-flash@v1", sourceEventId);

        // First insert → should succeed (return 1)
        int first = scoreRepo.saveIdempotent(
                score.getId(), "skill-1", skillVersionId, "1.0.0", "VALIDATION",
                BigDecimal.valueOf(100.0), "{}", Instant.now(), "gemini-2.5-flash@v1", sourceEventId);
        assertThat(first).isEqualTo(1);

        // Second insert with same deterministic PK → ON CONFLICT DO NOTHING (return 0, no exception)
        int second = scoreRepo.saveIdempotent(
                score.getId(), "skill-1", skillVersionId, "1.0.0", "VALIDATION",
                BigDecimal.valueOf(100.0), "{}", Instant.now(), "gemini-2.5-flash@v1", sourceEventId);
        assertThat(second).isEqualTo(0);

        // Exactly 1 row in DB
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_scores WHERE skill_version_id = ?", Integer.class, skillVersionId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-S135a-1: findLatestBySkillVersionId — DISTINCT ON (axis), returns 3 axes ordered alphabetically")
    @Tag("AC-S135a-1")
    void findLatestBySkillVersionIdReturnsDistinctAxes() throws InterruptedException {
        var skillId = "skill-" + uniqueSuffix();
        var skillVersionId = "sv-" + uniqueSuffix();
        var t1 = Instant.now().minusSeconds(10);
        var t2 = Instant.now();  // newer

        // Insert 2 ACTIVATION rows (different timestamps); only t2 should be returned.
        insertScore(skillId, skillVersionId, "1.0.0", QualityAxis.ACTIVATION, 70.0, "evt-a1-" + uniqueSuffix(), t1);
        insertScore(skillId, skillVersionId, "1.0.0", QualityAxis.ACTIVATION, 80.0, "evt-a2-" + uniqueSuffix(), t2);
        // One row each for IMPLEMENTATION and VALIDATION
        insertScore(skillId, skillVersionId, "1.0.0", QualityAxis.IMPLEMENTATION, 85.0, "evt-i-" + uniqueSuffix(), t1);
        insertScore(skillId, skillVersionId, "1.0.0", QualityAxis.VALIDATION, 100.0, "evt-v-" + uniqueSuffix(), t1);

        var rows = scoreRepo.findLatestBySkillVersionId(skillVersionId);

        assertThat(rows).hasSize(3);
        // ORDER BY axis → alphabetical: ACTIVATION, IMPLEMENTATION, VALIDATION
        assertThat(rows.get(0).getAxis()).isEqualTo(QualityAxis.ACTIVATION);
        assertThat(rows.get(1).getAxis()).isEqualTo(QualityAxis.IMPLEMENTATION);
        assertThat(rows.get(2).getAxis()).isEqualTo(QualityAxis.VALIDATION);
        // DISTINCT ON picks latest ACTIVATION (t2, total=80)
        assertThat(rows.get(0).getTotalScore()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("AC-S135a-2: existsBySourceEventId — returns true after insert, false for unknown")
    @Tag("AC-S135a-2")
    void existsBySourceEventId() {
        var skillVersionId = "sv-" + uniqueSuffix();
        var sourceEventId = "evt-exists-" + uniqueSuffix();
        insertScore("skill-1", skillVersionId, "1.0.0", QualityAxis.VALIDATION, 90.0, sourceEventId, Instant.now());

        assertThat(scoreRepo.existsBySourceEventId(sourceEventId)).isTrue();
        assertThat(scoreRepo.existsBySourceEventId("unknown-event-" + uniqueSuffix())).isFalse();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
