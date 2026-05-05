package io.github.samzhu.skillshub.score.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S135a: per-axis quality score aggregate (Direction A wide-JSONB schema).
 *
 * <p>PK is a deterministic UUID derived from {@code skillVersionId|axis|sourceEventId}
 * — identical inputs always produce the same PK, enabling ON CONFLICT DO NOTHING idempotency
 * without needing a separate UNIQUE constraint. Pattern matches {@link io.github.samzhu.skillshub.audit.AuditEventListener}.
 *
 * <p>{@link #isNew} follows the {@code SkillVersion} explicit Persistable flag pattern — factory
 * sets {@code true} (INSERT), {@code @PersistenceCreator} re-hydration keeps {@code false} (UPDATE).
 */
@Table("skill_scores")
public class SkillScore implements Persistable<String> {

    @Id
    private String id;

    /** @see SkillVersion#isNew — same explicit INSERT/UPDATE control pattern. */
    @Transient
    private boolean isNew = false;

    @Column("skill_id")
    private String skillId;

    @Column("skill_version_id")
    private String skillVersionId;

    @Column("skill_version")
    private String skillVersion;

    private QualityAxis axis;

    @Column("total_score")
    private BigDecimal totalScore;

    /** JSONB via shared MapJsonbConverter (registered in JdbcConfiguration). */
    private Map<String, Object> dimensions;

    @Column("evaluated_at")
    private Instant evaluatedAt;

    @Column("evaluator_version")
    private String evaluatorVersion;

    @Column("source_event_id")
    private String sourceEventId;

    /** Spring Data JDBC re-hydration ctor — fields filled via reflection. */
    @PersistenceCreator
    private SkillScore() {}

    /**
     * Factory for new evaluation results. PK = UUID.nameUUIDFromBytes(skillVersionId|axis|sourceEventId).
     * Deterministic PK + ON CONFLICT DO NOTHING in saveIdempotent() ensures at-most-once semantics.
     */
    public static SkillScore of(String skillId, String skillVersionId, String skillVersion,
                                 QualityAxis axis, BigDecimal totalScore,
                                 Map<String, Object> dimensions, String evaluatorVersion,
                                 String sourceEventId) {
        var dedupKey = skillVersionId + "|" + axis.name() + "|" + sourceEventId;
        var score = new SkillScore();
        score.id = UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8)).toString();
        score.skillId = skillId;
        score.skillVersionId = skillVersionId;
        score.skillVersion = skillVersion;
        score.axis = axis;
        score.totalScore = totalScore;
        score.dimensions = dimensions;
        score.evaluatedAt = Instant.now();
        score.evaluatorVersion = evaluatorVersion;
        score.sourceEventId = sourceEventId;
        score.isNew = true;
        return score;
    }

    @Override
    public String getId() { return id; }

    @JsonIgnore
    @Override
    public boolean isNew() { return isNew; }

    public String getSkillId() { return skillId; }
    public String getSkillVersionId() { return skillVersionId; }
    public String getSkillVersion() { return skillVersion; }
    public QualityAxis getAxis() { return axis; }
    public BigDecimal getTotalScore() { return totalScore; }
    public Map<String, Object> getDimensions() { return dimensions; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public String getEvaluatorVersion() { return evaluatorVersion; }
    public String getSourceEventId() { return sourceEventId; }
}
