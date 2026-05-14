package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.SecurityCategoryMapper.Category;
import io.github.samzhu.skillshub.security.SecurityCategoryMapper.CheckStatus;
import io.github.samzhu.skillshub.security.scan.Confidence;
import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

class SecurityCategoryMapperTest {

    private final SecurityCategoryMapper mapper = new SecurityCategoryMapper();

    // ── partition ────────────────────────────────────────────────────────────

    @Test
    void partition_dangerousCommand_goesToShell() {
        var f = finding("DANGEROUS_COMMAND_RM_RF", Severity.HIGH, "pattern");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.SHELL)).containsExactly(f);
        assertThat(result.get(Category.PATHS)).isEmpty();
        assertThat(result.get(Category.SECRETS)).isEmpty();
        assertThat(result.get(Category.DEPS)).isEmpty();
    }

    @Test
    void partition_pipeToShell_goesToShell() {
        var f = finding("PIPE_TO_SHELL_CURL", Severity.MEDIUM, "pattern");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.SHELL)).containsExactly(f);
    }

    @Test
    void partition_resourceDos_foldedIntoShell() {
        // AC-S142b-8: DoS findings 折入 shell category
        var f = finding("FORK_BOMB", Severity.HIGH, "resource-dos");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.SHELL)).containsExactly(f);
    }

    @Test
    void partition_sensitivePath_goesToPaths() {
        var f = finding("SENSITIVE_PATH_SSH", Severity.HIGH, "pattern");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.PATHS)).containsExactly(f);
        assertThat(result.get(Category.SHELL)).isEmpty();
    }

    @Test
    void partition_secret_goesToSecrets() {
        var f = finding("GITHUB_PAT", Severity.HIGH, "secret");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.SECRETS)).containsExactly(f);
    }

    @Test
    void partition_depVuln_goesToDeps() {
        var f = finding("CVE-2023-1234", Severity.MEDIUM, "dep-vuln");
        var result = mapper.partition(List.of(f));
        assertThat(result.get(Category.DEPS)).containsExactly(f);
    }

    @Test
    void partition_promptInjectionMetaLlm_notIn4Quad() {
        // AC-S142b-9: PI / META / LLM findings 不入 4-quad
        var pi = finding("PI_ROLE_JAILBREAK", Severity.HIGH, "prompt-injection");
        var meta = finding("META_OPACITY", Severity.MEDIUM, "meta");
        var llm = finding("LLM-GENERATED", Severity.HIGH, "llm-judge");
        var result = mapper.partition(List.of(pi, meta, llm));
        assertThat(result.get(Category.SHELL)).isEmpty();
        assertThat(result.get(Category.PATHS)).isEmpty();
        assertThat(result.get(Category.SECRETS)).isEmpty();
        assertThat(result.get(Category.DEPS)).isEmpty();
    }

    @Test
    @DisplayName("AC-S147-1: issueCode maps to dynamic category before legacy fallback")
    void issueCodeMapsBeforeLegacyFallback() {
        var f = new SecurityFinding(
                "W007_REVEAL_TOKEN",
                SkillIssueCode.W007,
                Severity.HIGH,
                "Skill asks the agent to print raw API tokens.",
                "Never print raw credentials.",
                Confidence.HIGH,
                "SKILL.md",
                9,
                "print API key",
                "llm-judge",
                null);

        assertThat(mapper.categoryFor(f)).isEqualTo(IssueCategory.CREDENTIALS);
        assertThat(mapper.categoryFor(f).label()).isEqualTo("Credentials");
    }

    // ── computeStatus ────────────────────────────────────────────────────────

    @Test
    void computeStatus_highSeverity_returnsFail() {
        var findings = List.of(finding("X", Severity.HIGH, "secret"));
        assertThat(mapper.computeStatus(findings)).isEqualTo(CheckStatus.FAIL);
    }

    @Test
    void computeStatus_mediumOnly_returnsWarn() {
        var findings = List.of(finding("X", Severity.MEDIUM, "secret"));
        assertThat(mapper.computeStatus(findings)).isEqualTo(CheckStatus.WARN);
    }

    @Test
    void computeStatus_emptyList_returnsPass() {
        assertThat(mapper.computeStatus(List.of())).isEqualTo(CheckStatus.PASS);
    }

    @Test
    void computeStatus_allLow_returnsPass() {
        var findings = List.of(finding("X", Severity.LOW, "dep-vuln"));
        assertThat(mapper.computeStatus(findings)).isEqualTo(CheckStatus.PASS);
    }

    // ── computeSecurityScore ─────────────────────────────────────────────────

    @Test
    void computeSecurityScore_allPass_returns100() {
        var statuses = Map.of(
            Category.SHELL, CheckStatus.PASS,
            Category.PATHS, CheckStatus.PASS,
            Category.SECRETS, CheckStatus.PASS,
            Category.DEPS, CheckStatus.PASS
        );
        assertThat(mapper.computeSecurityScore(statuses)).isEqualTo(100);
    }

    @Test
    void computeSecurityScore_oneWarn_returns75() {
        var statuses = Map.of(
            Category.SHELL, CheckStatus.WARN,
            Category.PATHS, CheckStatus.PASS,
            Category.SECRETS, CheckStatus.PASS,
            Category.DEPS, CheckStatus.PASS
        );
        assertThat(mapper.computeSecurityScore(statuses)).isEqualTo(75);
    }

    @Test
    void computeSecurityScore_twoWarn_returns50() {
        var statuses = Map.of(
            Category.SHELL, CheckStatus.WARN,
            Category.PATHS, CheckStatus.WARN,
            Category.SECRETS, CheckStatus.PASS,
            Category.DEPS, CheckStatus.PASS
        );
        assertThat(mapper.computeSecurityScore(statuses)).isEqualTo(50);
    }

    @Test
    void computeSecurityScore_threeOrMoreWarn_returns25() {
        var statuses = Map.of(
            Category.SHELL, CheckStatus.WARN,
            Category.PATHS, CheckStatus.WARN,
            Category.SECRETS, CheckStatus.WARN,
            Category.DEPS, CheckStatus.PASS
        );
        assertThat(mapper.computeSecurityScore(statuses)).isEqualTo(25);
    }

    @Test
    void computeSecurityScore_anyFail_returns25() {
        var statuses = Map.of(
            Category.SHELL, CheckStatus.FAIL,
            Category.PATHS, CheckStatus.PASS,
            Category.SECRETS, CheckStatus.PASS,
            Category.DEPS, CheckStatus.PASS
        );
        assertThat(mapper.computeSecurityScore(statuses)).isEqualTo(25);
    }

    @Test
    void computeSecurityScore_null_returnsNull() {
        assertThat(mapper.computeSecurityScore(null)).isNull();
    }

    // ── formatDetail ─────────────────────────────────────────────────────────

    @Test
    void formatDetail_emptyList_returnsNull() {
        // AC-S142b-7
        assertThat(mapper.formatDetail(List.of())).isNull();
    }

    @Test
    void formatDetail_singleFinding_formatsWithLine() {
        // AC-S142b-7: 1 finding → "{ruleId} · line {line}: {message}"
        var f = new SecurityFinding("GITHUB_PAT", Severity.HIGH, "Hardcoded GitHub PAT",
                "scripts/install.sh", 14, null, "secret", null);
        assertThat(mapper.formatDetail(List.of(f)))
                .isEqualTo("GITHUB_PAT · line 14: Hardcoded GitHub PAT");
    }

    @Test
    void formatDetail_multipleFindings_showsCountAndFirst3RuleIds() {
        // AC-S142b-7: 3+ findings → "{N} findings: {ruleId1}, {ruleId2}, {ruleId3}"
        var findings = List.of(
            new SecurityFinding("GITHUB_PAT", Severity.HIGH, "msg1", null, null, null, "secret", null),
            new SecurityFinding("AWS_SECRET_KEY", Severity.HIGH, "msg2", null, null, null, "secret", null),
            new SecurityFinding("OPENAI_KEY", Severity.HIGH, "msg3", null, null, null, "secret", null)
        );
        assertThat(mapper.formatDetail(findings))
                .isEqualTo("3 findings: GITHUB_PAT, AWS_SECRET_KEY, OPENAI_KEY");
    }

    @Test
    void formatDetail_manyFindings_limitRuleIdsToThree() {
        var findings = List.of(
            new SecurityFinding("R1", Severity.HIGH, "m", null, null, null, "secret", null),
            new SecurityFinding("R2", Severity.HIGH, "m", null, null, null, "secret", null),
            new SecurityFinding("R3", Severity.HIGH, "m", null, null, null, "secret", null),
            new SecurityFinding("R4", Severity.HIGH, "m", null, null, null, "secret", null)
        );
        String detail = mapper.formatDetail(findings);
        assertThat(detail).startsWith("4 findings: ");
        assertThat(detail).doesNotContain("R4");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SecurityFinding finding(String ruleId, Severity severity, String analyzer) {
        return new SecurityFinding(ruleId, severity, "msg", "file.sh", 1, null, analyzer, null);
    }
}
