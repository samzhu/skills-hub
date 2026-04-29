package io.github.samzhu.skillshub.security.scan.sarif;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

class SarifReporterTest {

	private final SarifReporter reporter = new SarifReporter();

	private static SecurityAnalyzer stub(String name, Phase phase) {
		return new SecurityAnalyzer() {
			@Override public String name() { return name; }
			@Override public Phase phase() { return phase; }
			@Override public AnalysisOutput analyze(ScanContext context) { return AnalysisOutput.empty(); }
		};
	}

	private static SkillVersionPublishedEvent evt() {
		return SkillVersionPublishedEvent.of("agg-1", "1.0.0", "gs://bucket/x.zip", 100, Map.of(), java.util.List.of());
	}

	@Test
	@DisplayName("AC-7.1: SARIF schema 必填欄位 + 多 runs[] 對應每個啟用 engine")
	@Tag("AC-7")
	@SuppressWarnings("unchecked")
	void schemaCompliant() {
		var pattern = stub("pattern", Phase.STATIC);
		var secret = stub("secret", Phase.STATIC);
		var meta = stub("meta", Phase.META);

		// 每個 engine 各自的 findings
		var perEngine = Map.of(
				"pattern", new AnalysisOutput(
						List.of(new SecurityFinding("DANGEROUS_COMMAND_RM_RF", Severity.HIGH,
								"rm -rf /home", "scripts/clean.sh", 5, "rm -rf /home", "pattern", "AST06")),
						List.of()),
				"secret", new AnalysisOutput(
						List.of(new SecurityFinding("GITHUB_PAT", Severity.MEDIUM,
								"github pat", "scripts/deploy.sh", 12, "ghp_…1234", "secret", "AST01")),
						List.of()),
				"meta", new AnalysisOutput(
						List.of(new SecurityFinding("META_OPACITY", Severity.LOW,
								"description missing + external URLs", null, null, null, "meta", null)),
						List.of(new ScanNotice("meta", "metadata frontmatter incomplete"))));

		var sarif = reporter.render(List.of(pattern, secret, meta), perEngine, evt());

		assertThat((String) sarif.get("$schema"))
				.isEqualTo("https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.json");
		assertThat((String) sarif.get("version")).isEqualTo("2.1.0");

		var runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat(runs).hasSize(3);

		// 每個 run 對應一個 engine
		var driverNames = runs.stream()
				.map(r -> (Map<String, Object>) r.get("tool"))
				.map(t -> (Map<String, Object>) t.get("driver"))
				.map(d -> (String) d.get("name"))
				.toList();
		assertThat(driverNames).containsExactlyInAnyOrder("pattern", "secret", "meta");
	}

	@Test
	@DisplayName("AC-7.1: HIGH→error, MEDIUM→warning, LOW→note")
	@Tag("AC-7")
	@SuppressWarnings("unchecked")
	void severityToLevelMapping() {
		var pattern = stub("pattern", Phase.STATIC);
		var perEngine = Map.of("pattern", new AnalysisOutput(List.of(
				new SecurityFinding("R1", Severity.HIGH, "h", "f", 1, "e", "pattern", "AST06"),
				new SecurityFinding("R2", Severity.MEDIUM, "m", "f", 2, "e", "pattern", "AST06"),
				new SecurityFinding("R3", Severity.LOW, "l", "f", 3, "e", "pattern", "AST06")
		), List.of()));

		var sarif = reporter.render(List.of(pattern), perEngine, evt());

		var run = (Map<String, Object>) ((List<?>) sarif.get("runs")).get(0);
		var results = (List<Map<String, Object>>) run.get("results");
		assertThat(results).hasSize(3);
		assertThat((String) results.get(0).get("level")).isEqualTo("error");
		assertThat((String) results.get(1).get("level")).isEqualTo("warning");
		assertThat((String) results.get(2).get("level")).isEqualTo("note");
	}

	@Test
	@DisplayName("AC-7.1: properties.security-severity 是合法浮點字串")
	@Tag("AC-7")
	@SuppressWarnings("unchecked")
	void securitySeverityAsFloatString() {
		var pattern = stub("pattern", Phase.STATIC);
		var perEngine = Map.of("pattern", new AnalysisOutput(List.of(
				new SecurityFinding("R1", Severity.HIGH, "h", "f", 1, "e", "pattern", "AST06"),
				new SecurityFinding("R2", Severity.MEDIUM, "m", "f", 2, "e", "pattern", "AST06"),
				new SecurityFinding("R3", Severity.LOW, "l", "f", 3, "e", "pattern", "AST06")
		), List.of()));

		var sarif = reporter.render(List.of(pattern), perEngine, evt());
		var run = (Map<String, Object>) ((List<?>) sarif.get("runs")).get(0);
		var results = (List<Map<String, Object>>) run.get("results");

		assertThat(((Map<String, Object>) results.get(0).get("properties")).get("security-severity"))
				.isEqualTo("8.5");
		assertThat(((Map<String, Object>) results.get(1).get("properties")).get("security-severity"))
				.isEqualTo("5.0");
		assertThat(((Map<String, Object>) results.get(2).get("properties")).get("security-severity"))
				.isEqualTo("2.5");
	}

	@Test
	@DisplayName("AC-7.1: notices 進到 invocations.toolExecutionNotifications，不在 results")
	@Tag("AC-7")
	@SuppressWarnings("unchecked")
	void noticesGoToInvocations() {
		var meta = stub("meta", Phase.META);
		var perEngine = Map.of("meta", new AnalysisOutput(
				List.of(),
				List.of(new ScanNotice("meta", "frontmatter description missing"))));

		var sarif = reporter.render(List.of(meta), perEngine, evt());
		var run = (Map<String, Object>) ((List<?>) sarif.get("runs")).get(0);

		// results 應為空（沒 finding）
		assertThat((List<?>) run.get("results")).isEmpty();

		// invocations[0].toolExecutionNotifications[] 含 1 筆 level="note"
		var invocations = (List<Map<String, Object>>) run.get("invocations");
		assertThat(invocations).hasSize(1);
		var notifications = (List<Map<String, Object>>) invocations.get(0).get("toolExecutionNotifications");
		assertThat(notifications).hasSize(1);
		assertThat((String) notifications.get(0).get("level")).isEqualTo("note");
		assertThat((Map<String, Object>) notifications.get(0).get("message"))
				.containsEntry("text", "frontmatter description missing");
	}

	@Test
	@DisplayName("AC-7.2: result.locations 含 physicalLocation.artifactLocation.uri + region.startLine")
	@Tag("AC-7")
	@SuppressWarnings("unchecked")
	void resultLocationFields() {
		var pattern = stub("pattern", Phase.STATIC);
		var perEngine = Map.of("pattern", new AnalysisOutput(List.of(
				new SecurityFinding("R1", Severity.HIGH, "h",
						"scripts/clean.sh", 5, "rm -rf", "pattern", "AST06")
		), List.of()));

		var sarif = reporter.render(List.of(pattern), perEngine, evt());
		var run = (Map<String, Object>) ((List<?>) sarif.get("runs")).get(0);
		var result = (Map<String, Object>) ((List<?>) run.get("results")).get(0);
		var locations = (List<Map<String, Object>>) result.get("locations");
		assertThat(locations).hasSize(1);
		var physical = (Map<String, Object>) locations.get(0).get("physicalLocation");
		assertThat(((Map<String, Object>) physical.get("artifactLocation")).get("uri"))
				.isEqualTo("scripts/clean.sh");
		assertThat(((Map<String, Object>) physical.get("region")).get("startLine"))
				.isEqualTo(5);
	}

	@Test
	@DisplayName("nullable filePath/line — locations 省略不報錯")
	@SuppressWarnings("unchecked")
	void nullableLocationFields() {
		var meta = stub("meta", Phase.META);
		var perEngine = Map.of("meta", new AnalysisOutput(List.of(
				new SecurityFinding("R1", Severity.LOW, "h", null, null, null, "meta", null)
		), List.of()));

		var sarif = reporter.render(List.of(meta), perEngine, evt());
		var run = (Map<String, Object>) ((List<?>) sarif.get("runs")).get(0);
		var result = (Map<String, Object>) ((List<?>) run.get("results")).get(0);
		// locations 可為 null/empty list（不應為缺欄）
		// SARIF 規範允許 result 沒有 locations
		Object locs = result.get("locations");
		assertThat(locs == null || ((List<?>) locs).isEmpty()).isTrue();
	}
}
