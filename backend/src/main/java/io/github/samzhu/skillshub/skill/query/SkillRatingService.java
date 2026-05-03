package io.github.samzhu.skillshub.skill.query;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S098e2-T02 — Skill rating projection helper：基於 reviews 表 AGG/COUNT 重算
 * skills.average_rating + skills.review_count。
 *
 * <p>Cross-module SPI：暴露於 {@code skill :: query} NamedInterface（per
 * {@code skill/query/package-info.java}）讓 review module 的
 * SkillRatingProjectionListener 跨模組呼叫。
 *
 * <p>實作對齊 S076 download_count 同 pattern：raw SQL UPDATE 而非 aggregate
 * mutation method —{@link io.github.samzhu.skillshub.skill.domain.Skill}
 * 上對應欄位標 {@code @ReadOnlyProperty} 防 aggregate save() 覆蓋。
 *
 * <p>Idempotent：每次 refresh 都重算 SUM/COUNT，不依賴 increment 上次狀態。
 * 適合 AFTER_COMMIT listener 重試（Modulith outbox at-least-once delivery）。
 */
@Service
public class SkillRatingService {

    private final NamedParameterJdbcTemplate jdbc;

    public SkillRatingService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 重算指定 skill 的 average_rating + review_count，UPDATE skills 表。
     *
     * @param skillId 目標 skill UUID
     */
    @Transactional
    public void refresh(String skillId) {
        // COALESCE 處理 0-review case：AVG 為 NULL 時填 0；COUNT 自然為 0
        // ROUND 至 2 位小數對齊 NUMERIC(3,2) schema。
        var sql = """
                UPDATE skills s
                SET average_rating = COALESCE((
                        SELECT ROUND(AVG(rating)::numeric, 2)
                        FROM reviews
                        WHERE skill_id = :skillId
                    ), 0.00),
                    review_count = (
                        SELECT COUNT(*)
                        FROM reviews
                        WHERE skill_id = :skillId
                    )
                WHERE s.id = :skillId
                """;
        jdbc.update(sql, Map.of("skillId", skillId));
    }
}
