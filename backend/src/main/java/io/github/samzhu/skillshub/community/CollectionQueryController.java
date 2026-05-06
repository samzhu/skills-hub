package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.skill.domain.Skill;

/**
 * S096f2-T02 — Collection query endpoints（取代 S096f1 stub {@code CollectionController}；per spec §4.1）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/collections?category=}  — list（預設 createdAt desc + optional category filter）</li>
 *   <li>{@code GET /api/v1/collections/{id}}        — single + skills detail</li>
 * </ul>
 *
 * <p>Single endpoint 走 {@code service.get} + {@code service.getCollectionSkills} 兩 query
 * — 純 read 不需 @Transactional；對齊 RequestQueryController 既驗 pattern。SUSPENDED skill
 * 的 historical reference 由 service 端 filter（防外洩）。
 */
@RestController
@RequestMapping("/api/v1/collections")
class CollectionQueryController {

    // S096f3: batch SQL — 每個 collection 的最高 risk level（NONE/LOW/MEDIUM/HIGH 排序；避免 N+1）
    private static final String MAX_RISK_SQL = """
            SELECT cs.collection_id,
                   CASE MAX(CASE s.risk_level
                           WHEN 'HIGH'   THEN 4
                           WHEN 'MEDIUM' THEN 3
                           WHEN 'LOW'    THEN 2
                           WHEN 'NONE'   THEN 1
                           ELSE 0 END)
                        WHEN 4 THEN 'HIGH'
                        WHEN 3 THEN 'MEDIUM'
                        WHEN 2 THEN 'LOW'
                        WHEN 1 THEN 'NONE'
                        ELSE NULL END AS max_risk_level
            FROM collection_skills cs
            JOIN skills s ON s.id = cs.skill_id
            WHERE cs.collection_id IN (:ids)
            GROUP BY cs.collection_id
            """;

    private final CollectionService service;
    private final NamedParameterJdbcTemplate jdbc;

    CollectionQueryController(CollectionService service, NamedParameterJdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    /** AC-5 — 全 collection 列表；optional category filter。S096f3: 批次加 maxRiskLevel。 */
    @GetMapping
    List<CollectionSummary> list(@RequestParam(required = false) String category) {
        var collections = service.list(category);
        if (collections.isEmpty()) return List.of();

        var ids = collections.stream().map(Collection::getId).toList();
        var params = new MapSqlParameterSource("ids", ids);
        Map<String, String> maxRiskLevels = jdbc.query(MAX_RISK_SQL, params, rs -> {
            var map = new java.util.HashMap<String, String>();
            while (rs.next()) {
                map.put(rs.getString("collection_id"), rs.getString("max_risk_level"));
            }
            return map;
        });

        return collections.stream()
                .map(c -> CollectionSummary.from(c, maxRiskLevels != null ? maxRiskLevels.get(c.getId()) : null))
                .toList();
    }

    /** AC-6 — single + skills detail（對應 collection 順序保留）。 */
    @GetMapping("/{id}")
    CollectionDetail get(@PathVariable String id) {
        var collection = service.get(id);
        var skills = service.getCollectionSkills(collection).stream()
                .map(CollectionSkillSummary::from)
                .toList();
        return CollectionDetail.from(collection, skills);
    }

    /**
     * Public list DTO — 對齊既有 frontend `SkillCollection` interface（skills.ts）。
     * 不含 ownerId detail（list view 不需）。
     *
     * <p>S118 (2026-05-04): rename `installs` → `installCount` 對齊 {@link CollectionDetail}
     * 既驗欄位命名（per Mode B Round 36 finding Bug AQ — 同 entity 跨 endpoint field name
     * 一致性）。S096f2 ship 時 oversight；本 fix breaking change 但 chain 收尾可一次完整 ship。
     * <p>S096f3: 加 maxRiskLevel — 集合內所有 skill 最高風險等級（batch SQL 計算；null = 尚未掃描或空集合）。
     */
    record CollectionSummary(
            String id,
            String name,
            String description,
            String category,
            int skillCount,
            int installCount,
            String maxRiskLevel,
            Instant createdAt) {
        static CollectionSummary from(Collection c, String maxRiskLevel) {
            return new CollectionSummary(c.getId(), c.getName(), c.getDescription(),
                    c.getCategory(), c.getSkills().size(), c.getInstallCount(), maxRiskLevel, c.getCreatedAt());
        }
    }

    /** AC-6 — 單筆 detail 含 skills summary list。 */
    record CollectionDetail(
            String id,
            String name,
            String description,
            String category,
            String ownerId,
            int installCount,
            Instant createdAt,
            List<CollectionSkillSummary> skills) {
        static CollectionDetail from(Collection c, List<CollectionSkillSummary> skills) {
            return new CollectionDetail(c.getId(), c.getName(), c.getDescription(),
                    c.getCategory(), c.getOwnerId(), c.getInstallCount(), c.getCreatedAt(), skills);
        }
    }

    /**
     * AC-6 — Skill summary（對齊 spec §4.1）：id / name / category / riskLevel / latestVersion。
     * SUSPENDED skill 也照樣顯（caller 顯 status 提示 — 但 MVP 不送 status 欄位，UI 暫不區分）。
     */
    record CollectionSkillSummary(
            String id,
            String name,
            String category,
            String riskLevel,
            String latestVersion) {
        static CollectionSkillSummary from(Skill s) {
            return new CollectionSkillSummary(s.getId(), s.getName(), s.getCategory(),
                    s.getRiskLevel(), s.getLatestVersion());
        }
    }
}
