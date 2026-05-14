package io.github.samzhu.skillshub.security;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * S142b — 將 risk_findings JSONB 中的 SecurityFinding 清單依 analyzer + ruleId 分組至 4-quad。
 * PI / META / LLM-judge outlier 不進入 4-quad，由既有 riskLevel pipeline 處理。
 */
@Component
public class SecurityCategoryMapper {

    public enum Category { SHELL, PATHS, SECRETS, DEPS }

    public enum CheckStatus { PASS, WARN, FAIL }

    /** analyzer + ruleId prefix → 4-quad; outliers 不在 map 中，呼叫端可忽略 */
    public Map<Category, List<SecurityFinding>> partition(List<SecurityFinding> findings) {
        Map<Category, List<SecurityFinding>> result = new EnumMap<>(Category.class);
        for (Category c : Category.values()) result.put(c, new ArrayList<>());

        for (SecurityFinding f : findings) {
            Category cat = classify(f);
            if (cat != null) result.get(cat).add(f);
        }
        return result;
    }

    /** S147-T01：新版 issue-code finding 優先走 dynamic category；舊資料才 fallback 到 4-quad。 */
    public IssueCategory categoryFor(SecurityFinding finding) {
        if (finding.issueCode() != null) {
            return finding.issueCode().category();
        }
        Category legacy = classify(finding);
        if (legacy == null) return null;
        return switch (legacy) {
            case SHELL -> IssueCategory.DESTRUCTIVE_ACTIONS;
            case PATHS -> IssueCategory.SENSITIVE_DATA;
            case SECRETS -> IssueCategory.CREDENTIALS;
            case DEPS -> IssueCategory.DOWNLOADS_DEPENDENCIES;
        };
    }

    /** HIGH → FAIL; MEDIUM (no HIGH) → WARN; empty / all LOW → PASS */
    public CheckStatus computeStatus(List<SecurityFinding> findings) {
        boolean hasMedium = false;
        for (SecurityFinding f : findings) {
            if (f.severity() == Severity.HIGH) return CheckStatus.FAIL;
            if (f.severity() == Severity.MEDIUM) hasMedium = true;
        }
        return hasMedium ? CheckStatus.WARN : CheckStatus.PASS;
    }

    /**
     * pass=100 / 1warn=75 / 2warn=50 / 3+warn=25 / anyFail=25.
     * Returns null if categoryStatuses is null (security not yet scanned).
     */
    public Integer computeSecurityScore(Map<Category, CheckStatus> categoryStatuses) {
        if (categoryStatuses == null) return null;
        int warns = 0;
        for (CheckStatus s : categoryStatuses.values()) {
            if (s == CheckStatus.FAIL) return 25;
            if (s == CheckStatus.WARN) warns++;
        }
        return switch (warns) {
            case 0 -> 100;
            case 1 -> 75;
            case 2 -> 50;
            default -> 25;
        };
    }

    /**
     * 0 findings → null;
     * 1 finding → "{ruleId} · line {line}: {message}";
     * 2+ findings → "{N} findings: {ruleId1}, {ruleId2}, ..." (first 3 distinct ruleIds).
     */
    public String formatDetail(List<SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        if (findings.size() == 1) {
            SecurityFinding f = findings.get(0);
            return f.ruleId() + " · line " + f.line() + ": " + f.message();
        }
        SequencedSet<String> ruleIds = new LinkedHashSet<>();
        for (SecurityFinding f : findings) {
            ruleIds.add(f.ruleId());
            if (ruleIds.size() == 3) break;
        }
        return findings.size() + " findings: " + String.join(", ", ruleIds);
    }

    private Category classify(SecurityFinding f) {
        String analyzer = f.analyzer();
        String ruleId = f.ruleId() != null ? f.ruleId() : "";

        if ("resource-dos".equals(analyzer)) return Category.SHELL;
        if ("pattern".equals(analyzer)) {
            if (ruleId.startsWith("DANGEROUS_COMMAND_") || ruleId.startsWith("PIPE_TO_SHELL_"))
                return Category.SHELL;
            if (ruleId.startsWith("SENSITIVE_PATH_")) return Category.PATHS;
            return null; // other pattern rules → outlier
        }
        if ("secret".equals(analyzer)) return Category.SECRETS;
        if ("dep-vuln".equals(analyzer)) return Category.DEPS;
        // prompt-injection / meta / llm-judge → outlier
        return null;
    }
}
