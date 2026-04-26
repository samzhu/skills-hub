package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

class MetaAnalyzerTest {

	private final MetaAnalyzer analyzer = new MetaAnalyzer();

	private static ScanContext withFindings(Map<String, Object> frontmatter,
			Map<String, String> scripts, List<SecurityFinding> phase1) {
		return new ScanContext("s", "1.0.0", frontmatter, "", scripts, phase1);
	}

	@Test
	@DisplayName("META_EXFIL_PATTERN: 同檔同時被 secret + dangerous-command 命中 → HIGH AST06")
	void exfilPatternRule() {
		var phase1 = List.of(
				new SecurityFinding("DANGEROUS_COMMAND_RM_RF", Severity.HIGH, "x",
						"scripts/setup.sh", 3, "rm -rf", "pattern", "AST06"),
				new SecurityFinding("GITHUB_PAT", Severity.HIGH, "x",
						"scripts/setup.sh", 5, "ghp_…1234", "secret", "AST01"));

		var output = analyzer.analyze(withFindings(Map.of(), Map.of("scripts/setup.sh", ""), phase1));

		assertThat(output.findings()).anyMatch(f ->
				"META_EXFIL_PATTERN".equals(f.ruleId())
						&& f.severity() == Severity.HIGH
						&& "AST06".equals(f.owaspAst())
						&& "scripts/setup.sh".equals(f.filePath()));
	}

	@Test
	@DisplayName("META_MULTI_ENGINE_SIGNAL: 同檔被 ≥3 不同 engine 提及 → HIGH")
	void multiEngineSignalRule() {
		var phase1 = List.of(
				new SecurityFinding("R1", Severity.MEDIUM, "x",
						"scripts/x.sh", 1, "e", "pattern", "AST06"),
				new SecurityFinding("R2", Severity.MEDIUM, "x",
						"scripts/x.sh", 2, "e", "secret", "AST01"),
				new SecurityFinding("R3", Severity.LOW, "x",
						"scripts/x.sh", 3, "e", "metadata", null));

		var output = analyzer.analyze(withFindings(Map.of(), Map.of(), phase1));

		assertThat(output.findings()).anyMatch(f ->
				"META_MULTI_ENGINE_SIGNAL".equals(f.ruleId())
						&& f.severity() == Severity.HIGH
						&& "scripts/x.sh".equals(f.filePath()));
	}

	@Test
	@DisplayName("META_OPACITY: frontmatter 缺 description + scripts 含外部 URL → MEDIUM")
	void opacityRule() {
		var frontmatter = Map.<String, Object>of("name", "x");  // 沒 description
		var scripts = Map.of("scripts/run.sh", "curl https://external.example.com/data\n");

		var output = analyzer.analyze(withFindings(frontmatter, scripts, List.of()));

		assertThat(output.findings()).anyMatch(f ->
				"META_OPACITY".equals(f.ruleId())
						&& f.severity() == Severity.MEDIUM);
	}

	@Test
	@DisplayName("乾淨情境（單一 engine、frontmatter 完整、無外部 URL）→ 無 meta finding")
	void cleanReturnsEmpty() {
		var phase1 = List.of(
				new SecurityFinding("R1", Severity.HIGH, "x",
						"scripts/x.sh", 1, "e", "pattern", "AST06"));
		var frontmatter = Map.<String, Object>of("name", "x", "description", "y");

		var output = analyzer.analyze(withFindings(frontmatter, Map.of(), phase1));

		assertThat(output.findings()).noneMatch(f -> f.ruleId().startsWith("META_"));
	}

	@Test
	@DisplayName("MetaAnalyzer 是 SecurityAnalyzer，phase=META, name=\"meta\"")
	void contract() {
		assertThat(analyzer.name()).isEqualTo("meta");
		assertThat(analyzer.phase()).isEqualTo(Phase.META);
	}
}
