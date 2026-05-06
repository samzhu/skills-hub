package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.Severity;

class DependencyVulnScannerTest {

	private OsvClient mockOsvClient;
	private DependencyVulnScanner scanner;

	@BeforeEach
	void setUp() {
		mockOsvClient = mock(OsvClient.class);
		scanner = new DependencyVulnScanner(mockOsvClient);
	}

	private static ScanContext ctxWithScript(String path, String content) {
		return new ScanContext("skill-1", "1.0.0", Map.of(), "# SKILL.md", Map.of(path, content), List.of());
	}

	private static ScanContext ctxWithScripts(Map<String, String> scripts) {
		return new ScanContext("skill-1", "1.0.0", Map.of(), "# SKILL.md", scripts, List.of());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requirements.txt parsing
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("AC-S099e3-1: requirements.txt pinned 版本產生漏洞 finding（HIGH severity）")
	@Tag("AC-S099e3")
	void detectsVulnInRequirementsTxt() {
		var req = "requests==2.28.1\n# comment\nnumpy>=1.24.0\n";
		var vuln = new OsvClient.OsvVuln("GHSA-1234-abcd",
				List.of(new OsvClient.OsvSeverity("CVSS_V3",
						"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")));
		when(mockOsvClient.querybatch(List.of("pkg:pypi/requests@2.28.1")))
				.thenReturn(List.of(List.of(vuln)));

		var output = scanner.analyze(ctxWithScript("scripts/requirements.txt", req));

		assertThat(output.findings()).hasSize(1);
		var f = output.findings().get(0);
		assertThat(f.ruleId()).isEqualTo("DEP_VULN_PYPI_GHSA-1234-ABCD");
		assertThat(f.severity()).isEqualTo(Severity.HIGH);
		assertThat(f.analyzer()).isEqualTo("dep-vuln");
		assertThat(f.owaspAst()).isEqualTo("AST05");
		assertThat(f.filePath()).isEqualTo("scripts/requirements.txt");
		// numpy (not pinned with ==) is skipped
		assertThat(output.findings()).hasSize(1);
	}

	@Test
	@DisplayName("AC-S099e3-2: package.json dependencies 觸發漏洞 finding（MEDIUM severity）")
	@Tag("AC-S099e3")
	void detectsVulnInPackageJson() throws Exception {
		var pkgJson = """
				{
				  "dependencies": {
				    "lodash": "^4.17.20",
				    "express": "4.18.2"
				  }
				}
				""";
		var vuln = new OsvClient.OsvVuln("GHSA-lodash-001",
				List.of(new OsvClient.OsvSeverity("CVSS_V3",
						"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N")));
		// OSV called with lodash + express (order follows LinkedHashMap insertion)
		when(mockOsvClient.querybatch(anyList()))
				.thenReturn(List.of(List.of(vuln), List.of()));

		var output = scanner.analyze(ctxWithScript("scripts/package.json", pkgJson));

		assertThat(output.findings()).hasSize(1);
		assertThat(output.findings().get(0).ruleId()).startsWith("DEP_VULN_NPM_GHSA-LODASH-001");
		assertThat(output.findings().get(0).severity()).isEqualTo(Severity.MEDIUM);
	}

	@Test
	@DisplayName("AC-S099e3-3: 無漏洞套件 → empty findings/notices")
	@Tag("AC-S099e3")
	void cleanDepsReturnEmpty() {
		var req = "requests==2.31.0\n";
		when(mockOsvClient.querybatch(anyList()))
				.thenReturn(List.of(List.of()));

		var output = scanner.analyze(ctxWithScript("scripts/requirements.txt", req));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("AC-S099e3-4: 無 manifest 檔案 → 不呼叫 OSV，empty findings")
	@Tag("AC-S099e3")
	void noManifestFilesSkipsOsvCall() {
		var ctx = new ScanContext("skill-1", "1.0.0", Map.of(), "# md", Map.of("scripts/run.sh", "#!/bin/bash"), List.of());
		var output = scanner.analyze(ctx);

		assertThat(output.findings()).isEmpty();
		verify(mockOsvClient, never()).querybatch(anyList());
	}

	@Test
	@DisplayName("AC-S099e3-5: OSV.dev 網路不通 → safeAnalyze，log warning，不拋例外")
	@Tag("AC-S099e3")
	void osvNetworkFailureIsNonBlocking() {
		var req = "requests==2.28.1\n";
		when(mockOsvClient.querybatch(anyList()))
				.thenThrow(new RuntimeException("Connection refused: api.osv.dev"));

		var output = scanner.analyze(ctxWithScript("scripts/requirements.txt", req));

		// no exception thrown — findings may be empty (skipped)
		assertThat(output.findings()).isEmpty();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Version parsing helpers
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("cleanNpmVersion 正確剝除 ^, ~, >=, > 前綴")
	void cleanNpmVersionStripsRangePrefix() {
		assertThat(DependencyVulnScanner.cleanNpmVersion("^4.17.20")).isEqualTo(Optional.of("4.17.20"));
		assertThat(DependencyVulnScanner.cleanNpmVersion("~1.2.3")).isEqualTo(Optional.of("1.2.3"));
		assertThat(DependencyVulnScanner.cleanNpmVersion(">=4.18.2")).isEqualTo(Optional.of("4.18.2"));
		assertThat(DependencyVulnScanner.cleanNpmVersion(">2.0.0")).isEqualTo(Optional.of("2.0.0"));
		assertThat(DependencyVulnScanner.cleanNpmVersion("1.0.0")).isEqualTo(Optional.of("1.0.0"));
	}

	@Test
	@DisplayName("cleanNpmVersion 對 latest/*/x 回傳 empty")
	void cleanNpmVersionRejectsNonPinnedAliases() {
		assertThat(DependencyVulnScanner.cleanNpmVersion("latest")).isEqualTo(Optional.empty());
		assertThat(DependencyVulnScanner.cleanNpmVersion("*")).isEqualTo(Optional.empty());
		assertThat(DependencyVulnScanner.cleanNpmVersion("x")).isEqualTo(Optional.empty());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// CVSS severity mapping
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("mapSeverity: C:H → HIGH")
	void mapSeverityHighOnConfidentialityHigh() {
		var vuln = new OsvClient.OsvVuln("TEST",
				List.of(new OsvClient.OsvSeverity("CVSS_V3",
						"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N")));
		assertThat(DependencyVulnScanner.mapSeverity(vuln)).isEqualTo(Severity.HIGH);
	}

	@Test
	@DisplayName("mapSeverity: A:L → MEDIUM")
	void mapSeverityMediumOnAvailabilityLow() {
		var vuln = new OsvClient.OsvVuln("TEST",
				List.of(new OsvClient.OsvSeverity("CVSS_V3",
						"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L")));
		assertThat(DependencyVulnScanner.mapSeverity(vuln)).isEqualTo(Severity.MEDIUM);
	}

	@Test
	@DisplayName("mapSeverity: 無 severity info → MEDIUM（conservative default）")
	void mapSeverityDefaultsMediumWhenNoCvss() {
		var vuln = new OsvClient.OsvVuln("TEST", List.of());
		assertThat(DependencyVulnScanner.mapSeverity(vuln)).isEqualTo(Severity.MEDIUM);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Contract
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("DependencyVulnScanner 實作 SecurityAnalyzer：phase=STATIC, name=dep-vuln")
	void implementsSecurityAnalyzerContract() {
		assertThat(scanner.name()).isEqualTo("dep-vuln");
		assertThat(scanner.phase()).isEqualTo(Phase.STATIC);
	}

	@Test
	@DisplayName("scoped npm packages (@scope/name) 被跳過（V1 defer）")
	void scopedNpmPackagesAreSkipped() throws Exception {
		var pkgJson = """
				{
				  "dependencies": {
				    "@angular/core": "^15.0.0",
				    "express": "4.18.2"
				  }
				}
				""";
		when(mockOsvClient.querybatch(anyList()))
				.thenReturn(List.of(List.of()));

		var output = scanner.analyze(ctxWithScript("scripts/package.json", pkgJson));

		// only express is queried; @angular/core is skipped
		verify(mockOsvClient).querybatch(List.of("pkg:npm/express@4.18.2"));
		assertThat(output.findings()).isEmpty();
	}
}
