package io.github.samzhu.skillshub.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.security.SecurityCategoryMapper.Category;
import io.github.samzhu.skillshub.security.SecurityCategoryMapper.CheckStatus;
import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;
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
        List<SecurityReportResponse.CategorySummary> categories = buildCategories(findings);
        List<SecurityReportResponse.FindingSummary> findingSummaries = buildFindingSummaries(findings);
        List<SecurityReportResponse.RiskReason> riskReasons = buildRiskReasons(
                riskAssessment, findings, version.getAllowedTools());

        return new SecurityReportResponse(
                skillId,
                version.getId(),
                version.getVersion(),
                scannedAt,
                ENGINE_VERSION,
                RULESET_VERSION,
                overall,
                checks,
                categories,
                findingSummaries,
                riskReasons);
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

    private List<SecurityReportResponse.CategorySummary> buildCategories(List<SecurityFinding> findings) {
        Map<IssueCategory, List<SecurityFinding>> byCategory = new java.util.EnumMap<>(IssueCategory.class);
        for (IssueCategory category : IssueCategory.values()) {
            byCategory.put(category, new java.util.ArrayList<>());
        }
        for (SecurityFinding finding : findings) {
            IssueCategory category = mapper.categoryFor(finding);
            if (category != null) {
                byCategory.get(category).add(finding);
            }
        }
        return byCategory.entrySet().stream()
                .map(entry -> {
                    List<SecurityFinding> categoryFindings = entry.getValue();
                    return new SecurityReportResponse.CategorySummary(
                            entry.getKey().key(),
                            entry.getKey().label(),
                            mapper.computeStatus(categoryFindings).name(),
                            categoryFindings.size(),
                            highestSeverity(categoryFindings));
                })
                .toList();
    }

    private List<SecurityReportResponse.FindingSummary> buildFindingSummaries(List<SecurityFinding> findings) {
        return findings.stream()
                .map(finding -> new SecurityReportResponse.FindingSummary(
                        finding.ruleId(),
                        finding.issueCode() == null ? null : finding.issueCode().code(),
                        finding.severity() == null ? null : finding.severity().name(),
                        finding.message(),
                        finding.remediation(),
                        finding.confidence() == null ? null : finding.confidence().name(),
                        finding.filePath(),
                        finding.line(),
                        finding.evidence()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<SecurityReportResponse.RiskReason> buildRiskReasons(
            Map<String, Object> riskAssessment,
            List<SecurityFinding> findings,
            List<String> allowedTools) {

        Object rawReasons = riskAssessment.get("riskReasons");
        if (rawReasons instanceof List<?> reasons && !reasons.isEmpty()) {
            return reasons.stream()
                    .map(raw -> objectMapper.convertValue(raw, SecurityReportResponse.RiskReason.class))
                    .toList();
        }

        if (findings != null && !findings.isEmpty()) {
            return List.of(findingReason(findings));
        }

        var tools = allowedTools == null ? List.<String>of() : allowedTools;
        if (!tools.isEmpty()) {
            return List.of(allowedToolsReason("LEGACY_ALLOWED_TOOLS", tools));
        }

        return List.of(new SecurityReportResponse.RiskReason(
                "NO_FINDINGS_NO_CAPABILITIES",
                "沒有工具宣告或 scripts/",
                "未發現安全問題，且這個技能沒有工具宣告或 scripts/。這不代表 100% 安全，只表示 scanner 沒有找到已知問題。",
                "NONE",
                List.of(),
                "DOWNLOAD_OK"));
    }

    private SecurityReportResponse.RiskReason allowedToolsReason(String code, List<String> tools) {
        var joinedTools = String.join("、", tools);
        return new SecurityReportResponse.RiskReason(
                code,
                "這個技能可以要求 AI 使用工具",
                "掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用工具：" + joinedTools
                        + "，所以使用前請先確認你接受這些能力。",
                "LOW",
                tools,
                "REVIEW_FIRST");
    }

    private SecurityReportResponse.RiskReason findingReason(List<SecurityFinding> findings) {
        var impact = highestSeverity(findings);
        var evidence = findings.stream()
                .map(f -> f.issueCode() == null ? f.ruleId() : f.issueCode().code())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new SecurityReportResponse.RiskReason(
                "FINDINGS_PRESENT",
                "掃描找到需要查看的項目",
                "掃描找到需要查看的項目；請先看掃描發現中的檔案、行號與修法。",
                impact == null ? "LOW" : impact,
                evidence,
                "FIX_REQUIRED");
    }

    private String highestSeverity(List<SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        if (findings.stream().anyMatch(f -> f.severity() == Severity.HIGH)) return Severity.HIGH.name();
        if (findings.stream().anyMatch(f -> f.severity() == Severity.MEDIUM)) return Severity.MEDIUM.name();
        return Severity.LOW.name();
    }
}
