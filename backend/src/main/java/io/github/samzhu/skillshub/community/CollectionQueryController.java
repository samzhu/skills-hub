package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;

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

    private final CollectionService service;

    CollectionQueryController(CollectionService service) {
        this.service = service;
    }

    /** AC-5 — 全 collection 列表；optional category filter。 */
    @GetMapping
    List<CollectionSummary> list(@RequestParam(required = false) String category) {
        return service.list(category).stream().map(CollectionSummary::from).toList();
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
     * 不含 ownerId / installCount detail（list view 不需）。
     */
    record CollectionSummary(
            String id,
            String name,
            String description,
            String category,
            int skillCount,
            int installs,
            Instant createdAt) {
        static CollectionSummary from(Collection c) {
            return new CollectionSummary(c.getId(), c.getName(), c.getDescription(),
                    c.getCategory(), c.getSkills().size(), c.getInstallCount(), c.getCreatedAt());
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
