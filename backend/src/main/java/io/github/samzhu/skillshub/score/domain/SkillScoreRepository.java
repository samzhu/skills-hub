package io.github.samzhu.skillshub.score.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * S135a: skill_scores repository.
 *
 * <p>Regular {@link #save} / {@link #saveAll} are used for first-time inserts (isNew = true).
 * {@link #saveIdempotent} uses ON CONFLICT DO NOTHING for retry-safe writes (outbox re-delivery).
 *
 * <p>DISTINCT ON queries return the latest evaluation per axis, ordered alphabetically by axis name.
 */
public interface SkillScoreRepository extends ListCrudRepository<SkillScore, String> {

    /** Idempotency early-return check — true if any row with this sourceEventId already exists. */
    boolean existsBySourceEventId(String sourceEventId);

    /**
     * Latest score per axis for a skill (all versions combined).
     * Returns ACTIVATION, IMPLEMENTATION, VALIDATION (alphabetical ORDER BY axis).
     */
    @Query("""
            SELECT DISTINCT ON (axis) *
            FROM skill_scores
            WHERE skill_id = :skillId
            ORDER BY axis, evaluated_at DESC
            """)
    List<SkillScore> findLatestBySkillId(@Param("skillId") String skillId);

    /**
     * Latest score per axis for a specific skill version.
     * Returns ACTIVATION, IMPLEMENTATION, VALIDATION (alphabetical ORDER BY axis).
     */
    @Query("""
            SELECT DISTINCT ON (axis) *
            FROM skill_scores
            WHERE skill_version_id = :skillVersionId
            ORDER BY axis, evaluated_at DESC
            """)
    List<SkillScore> findLatestBySkillVersionId(@Param("skillVersionId") String skillVersionId);

    /**
     * Idempotent insert — ON CONFLICT (id) DO NOTHING.
     * Same (skillVersionId, axis, sourceEventId) deterministic UUID PK → duplicate call is safe no-op.
     * Caller is responsible for pre-serializing {@code dimensions} Map to a valid JSON string.
     *
     * @return 1 on first insert; 0 if row already exists (conflict skipped)
     */
    @Modifying
    @Query("""
            INSERT INTO skill_scores
            (id, skill_id, skill_version_id, skill_version, axis, total_score, dimensions,
             evaluated_at, evaluator_version, source_event_id)
            VALUES (:id, :skillId, :skillVersionId, :skillVersion, :axis, :totalScore,
                    CAST(:dimensionsJson AS jsonb), :evaluatedAt, :evaluatorVersion, :sourceEventId)
            ON CONFLICT (id) DO NOTHING
            """)
    int saveIdempotent(
            @Param("id") String id,
            @Param("skillId") String skillId,
            @Param("skillVersionId") String skillVersionId,
            @Param("skillVersion") String skillVersion,
            @Param("axis") String axis,
            @Param("totalScore") BigDecimal totalScore,
            @Param("dimensionsJson") String dimensionsJson,
            @Param("evaluatedAt") Instant evaluatedAt,
            @Param("evaluatorVersion") String evaluatorVersion,
            @Param("sourceEventId") String sourceEventId);
}
