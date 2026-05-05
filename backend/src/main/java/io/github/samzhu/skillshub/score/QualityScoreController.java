package io.github.samzhu.skillshub.score;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.shared.api.QualityNotEvaluatedException;

/**
 * S135a §4.2 — 品質評分讀取端 API。
 *
 * <p>GET /api/v1/skills/{skillId}/scores：回 3-axis breakdown + total。
 * 未評算（rows 空）→ throw QualityNotEvaluatedException → GlobalExceptionHandler 映射 404。
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/scores")
class QualityScoreController {

    private final SkillScoreRepository scoreRepo;

    QualityScoreController(SkillScoreRepository scoreRepo) {
        this.scoreRepo = scoreRepo;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#skillId, 'Skill', 'read')")
    ResponseEntity<ScoreResponse> getScores(
            @PathVariable String skillId,
            @RequestParam(required = false) String versionId) {

        var rows = versionId != null
                ? scoreRepo.findLatestBySkillVersionId(versionId)
                : scoreRepo.findLatestBySkillId(skillId);

        if (rows.isEmpty()) {
            throw new QualityNotEvaluatedException(skillId);
        }

        return ResponseEntity.ok(ScoreResponse.from(rows));
    }
}
