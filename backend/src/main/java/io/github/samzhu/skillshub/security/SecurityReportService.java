package io.github.samzhu.skillshub.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.security.SecurityCategoryMapper.Category;
import io.github.samzhu.skillshub.security.SecurityCategoryMapper.CheckStatus;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.shared.api.SecurityNotScannedException;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * S142b §4.4 — 從 skill_versions.risk_assessment JSONB 建構 SecurityReport 4-quad 回應。
 * 純 read-side；不寫 DB。
 */
@Service
public class SecurityReportService {

    static final String ENGINE_VERSION = "risk-scanner v1.0";
    static final String RULESET_VERSION = "2026-05";

    private final SkillVersionRepository versionRepo;
    private final SecurityCategoryMapper mapper;
    private final ObjectMapper objectMapper;

    public SecurityReportService(SkillVersionRepository versionRepo,
            SecurityCategoryMapper mapper,
            ObjectMapper objectMapper) {
        this.versionRepo = versionRepo;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 取 latest published version 的 SecurityReport；versionId != null 時取指定版本。 */
    public SecurityReportResponse getReport(String skillId, String versionId) {
        SkillVersion version = resolveVersion(skillId, versionId);

        Map<String, Object> riskAssessment = version.getRiskAssessment();
        if (riskAssessment == null || riskAssessment.isEmpty()) {
            throw new SecurityNotScannedException(skillId);
        }

        @SuppressWarnings("unchecked")
        List<Object> rawFindings = (List<Object>) riskAssessment.get("findings");
        if (rawFindings == null) {
            throw new SecurityNotScannedException(skillId);
        }

        List<SecurityFinding> findings = rawFindings.stream()
                .map(raw -> objectMapper.convertValue(raw, SecurityFinding.class))
                .toList();

        Map<Category, List<SecurityFinding>> partitioned = mapper.partition(findings);

        Map<Category, CheckStatus> categoryStatuses = new java.util.EnumMap<>(Category.class);
        for (Category cat : Category.values()) {
            categoryStatuses.put(cat, mapper.computeStatus(partitioned.get(cat)));
        }

        String overall = computeOverall(categoryStatuses);

        Object rawScannedAt = riskAssessment.get("scannedAt");
        Instant scannedAt = rawScannedAt != null
                ? Instant.parse(rawScannedAt.toString())
                : version.getPublishedAt();

        Map<String, SecurityReportResponse.CheckDetail> checks = buildChecks(partitioned, categoryStatuses);

        return new SecurityReportResponse(
                skillId,
                version.getId(),
                version.getVersion(),
                scannedAt,
                ENGINE_VERSION,
                RULESET_VERSION,
                overall,
                checks);
    }

    private SkillVersion resolveVersion(String skillId, String versionId) {
        if (versionId != null) {
            return versionRepo.findById(versionId)
                    .filter(v -> skillId.equals(v.getSkillId()))
                    .orElseThrow(() -> new java.util.NoSuchElementException("Skill version not found: " + versionId));
        }
        return versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId)
                .stream().findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("No published version for skill: " + skillId));
    }

    private String computeOverall(Map<Category, CheckStatus> statuses) {
        boolean hasFail = statuses.values().stream().anyMatch(s -> s == CheckStatus.FAIL);
        if (hasFail) return "FAIL";
        boolean hasWarn = statuses.values().stream().anyMatch(s -> s == CheckStatus.WARN);
        return hasWarn ? "WARN" : "PASS";
    }

    private Map<String, SecurityReportResponse.CheckDetail> buildChecks(
            Map<Category, List<SecurityFinding>> partitioned,
            Map<Category, CheckStatus> statuses) {

        Map<String, SecurityReportResponse.CheckDetail> checks = new LinkedHashMap<>();
        for (Category cat : Category.values()) {
            CheckStatus status = statuses.get(cat);
            List<SecurityFinding> catFindings = partitioned.get(cat);
            String detail = mapper.formatDetail(catFindings);
            checks.put(cat.name().toLowerCase(),
                    new SecurityReportResponse.CheckDetail(status.name(), detail));
        }
        return checks;
    }
}
