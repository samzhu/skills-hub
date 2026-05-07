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
 * <p>GET /api/v1/skills/{skillId}/scores：回 3-axis breakdown + total + skillScore composite。
 * 未評算（rows 空）→ throw QualityNotEvaluatedException → GlobalExceptionHandler 映射 404。
 * S142b: skillScore = round(0.6 × qualityTotal + 0.4 × securityScore)；未掃描則 null。
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/scores")
class QualityScoreController {

    private final SkillScoreRepository scoreRepo;
    private final SkillScoreCalculator skillScoreCalculator;

    QualityScoreController(SkillScoreRepository scoreRepo,
            SkillScoreCalculator skillScoreCalculator) {
        this.scoreRepo = scoreRepo;
        this.skillScoreCalculator = skillScoreCalculator;
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

        var response = ScoreResponse.from(rows);
        Integer skillScore = skillScoreCalculator.compute(skillId, response.total());
        return ResponseEntity.ok(ScoreResponse.from(rows, skillScore));
    }
}
