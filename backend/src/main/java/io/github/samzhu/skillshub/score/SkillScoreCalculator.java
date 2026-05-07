package io.github.samzhu.skillshub.score;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.security.SecurityCategoryMapper;
import io.github.samzhu.skillshub.security.SecurityCategoryMapper.Category;
import io.github.samzhu.skillshub.security.SecurityCategoryMapper.CheckStatus;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * S142b §4.3 — 組合 qualityTotal + securityScore 計算 skillScore composite。
 * formula: round(0.6 × qualityTotal + 0.4 × securityScore)
 * Security not scanned → securityScore = null → skillScore = null。
 */
@Service
public class SkillScoreCalculator {

    private final SkillVersionRepository versionRepo;
    private final SecurityCategoryMapper securityMapper;
    private final ObjectMapper objectMapper;

    public SkillScoreCalculator(SkillVersionRepository versionRepo,
            SecurityCategoryMapper securityMapper,
            ObjectMapper objectMapper) {
        this.versionRepo = versionRepo;
        this.securityMapper = securityMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Compute skillScore composite. Returns null if security not yet scanned.
     * qualityTotal: already computed 0-100 int from QualityScoreController.
     */
    public Integer compute(String skillId, int qualityTotal) {
        Integer securityScore = lookupSecurityScore(skillId);
        return compute(qualityTotal, securityScore);
    }

    /** Pure computation — used by unit tests and controller. */
    public Integer compute(int qualityTotal, Integer securityScore) {
        if (securityScore == null) return null;
        return (int) Math.round(0.6 * qualityTotal + 0.4 * securityScore);
    }

    private Integer lookupSecurityScore(String skillId) {
        return versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId)
                .stream().findFirst()
                .map(v -> {
                    Map<String, Object> ra = v.getRiskAssessment();
                    if (ra == null || ra.isEmpty()) return null;

                    @SuppressWarnings("unchecked")
                    List<Object> rawFindings = (List<Object>) ra.get("findings");
                    if (rawFindings == null) return null;

                    List<SecurityFinding> findings = rawFindings.stream()
                            .map(raw -> objectMapper.convertValue(raw, SecurityFinding.class))
                            .toList();

                    Map<Category, List<SecurityFinding>> partitioned = securityMapper.partition(findings);
                    Map<Category, CheckStatus> statuses = new java.util.EnumMap<>(Category.class);
                    for (Category cat : Category.values()) {
                        statuses.put(cat, securityMapper.computeStatus(partitioned.get(cat)));
                    }
                    return securityMapper.computeSecurityScore(statuses);
                })
                .orElse(null);
    }
}
