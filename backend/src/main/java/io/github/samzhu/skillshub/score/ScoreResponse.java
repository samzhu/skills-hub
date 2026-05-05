package io.github.samzhu.skillshub.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;

/**
 * S135a §4.3 — GET /api/v1/skills/{id}/scores 回應 DTO。
 *
 * <p>total = round(0.2 × V + 0.4 × I + 0.4 × A)（AC-S135a-11）。
 */
public record ScoreResponse(
        String skillId,
        String skillVersionId,
        String skillVersion,
        Instant evaluatedAt,
        String evaluatorVersion,
        AxisScore validation,
        AxisScore implementation,
        AxisScore activation,
        int total) {

    public record AxisScore(int totalScore, Map<String, Object> dimensions) {}

    public static ScoreResponse from(List<SkillScore> rows) {
        var v = find(rows, QualityAxis.VALIDATION);
        var i = find(rows, QualityAxis.IMPLEMENTATION);
        var a = find(rows, QualityAxis.ACTIVATION);

        int total = BigDecimal.valueOf(0.2).multiply(v.getTotalScore())
                .add(BigDecimal.valueOf(0.4).multiply(i.getTotalScore()))
                .add(BigDecimal.valueOf(0.4).multiply(a.getTotalScore()))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        return new ScoreResponse(
                v.getSkillId(),
                v.getSkillVersionId(),
                v.getSkillVersion(),
                v.getEvaluatedAt(),
                v.getEvaluatorVersion(),
                toAxisScore(v),
                toAxisScore(i),
                toAxisScore(a),
                total);
    }

    private static SkillScore find(List<SkillScore> rows, QualityAxis axis) {
        return rows.stream()
                .filter(r -> r.getAxis() == axis)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing axis: " + axis));
    }

    private static AxisScore toAxisScore(SkillScore s) {
        return new AxisScore(s.getTotalScore().setScale(0, RoundingMode.HALF_UP).intValue(), s.getDimensions());
    }
}
