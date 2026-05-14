package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.Severity;

class ResourceDoSScannerTest {

	private final ResourceDoSScanner scanner = new ResourceDoSScanner();

	private static ScanContext ctx(String skillMd, Map<String, String> scripts) {
		return new ScanContext("skill-1", "1.0.0", Map.of(), skillMd, scripts, List.of());
	}

	private static ScanContext ctxFiles(Map<String, String> packageFiles) {
		return new ScanContext("skill-1", "1.0.0", Map.of(), "# SKILL.md",
				Map.of(), packageFiles, List.copyOf(packageFiles.keySet()), List.of());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// HIGH patterns
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("AC-S099e2-1: FORK_BOMB — :(){ :|:& };: 觸發 HIGH finding")
	@Tag("AC-S099e2")
	void detectsForkBomb() {
		var script = "echo start\n:(){ :|:& };:\necho end\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/run.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "FORK_BOMB".equals(f.ruleId())
						&& f.severity() == Severity.HIGH
						&& "resource-dos".equals(f.analyzer())
						&& "AST04".equals(f.owaspAst())
						&& f.line() == 2);
	}

	@Test
	@DisplayName("AC-S099e2-2: DEV_ZERO_READ — cat /dev/zero 觸發 HIGH finding")
	@Tag("AC-S099e2")
	void detectsDevZeroRead() {
		var script = "cat /dev/zero | head -c 1G > /tmp/bigfile\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/fill.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "DEV_ZERO_READ".equals(f.ruleId())
						&& f.severity() == Severity.HIGH);
	}

	@Test
	@DisplayName("AC-S099e2-3: DEV_ZERO_READ — cat /dev/random 觸發 HIGH finding")
	@Tag("AC-S099e2")
	void detectsDevRandomRead() {
		var output = scanner.analyze(ctx("cat /dev/random > /tmp/entropy\n", Map.of()));
		assertThat(output.findings())
				.anyMatch(f -> "DEV_ZERO_READ".equals(f.ruleId()) && f.severity() == Severity.HIGH);
	}

	@Test
	@DisplayName("AC-S099e2-4: DD_ZERO_FLOOD — dd if=/dev/zero 觸發 HIGH finding")
	@Tag("AC-S099e2")
	void detectsDdZeroFlood() {
		var script = "dd if=/dev/zero of=/dev/null bs=1M count=1000000\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/ddflood.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "DD_ZERO_FLOOD".equals(f.ruleId()) && f.severity() == Severity.HIGH);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// MEDIUM patterns
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("AC-S099e2-5: SLEEP_OVERFLOW — sleep infinity 觸發 MEDIUM finding")
	@Tag("AC-S099e2")
	void detectsSleepInfinity() {
		var script = "sleep infinity\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/daemon.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "SLEEP_OVERFLOW".equals(f.ruleId())
						&& f.severity() == Severity.MEDIUM);
	}

	@Test
	@DisplayName("AC-S099e2-5: SLEEP_OVERFLOW — sleep 9999999 觸發 MEDIUM finding")
	@Tag("AC-S099e2")
	void detectsSleepVeryLong() {
		var output = scanner.analyze(ctx("sleep 9999999\n", Map.of()));
		assertThat(output.findings())
				.anyMatch(f -> "SLEEP_OVERFLOW".equals(f.ruleId()) && f.severity() == Severity.MEDIUM);
	}

	@Test
	@DisplayName("AC-S099e2-6: TAIL_FOLLOW_FOREVER — tail -f /var/log/app.log 觸發 MEDIUM finding")
	@Tag("AC-S099e2")
	void detectsTailFollow() {
		var script = "tail -f /var/log/app.log\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/monitor.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "TAIL_FOLLOW_FOREVER".equals(f.ruleId())
						&& f.severity() == Severity.MEDIUM);
	}

	@Test
	@DisplayName("AC-S099e2-7: INFINITE_WHILE_SHELL — while true; 觸發 MEDIUM finding")
	@Tag("AC-S099e2")
	void detectsInfiniteWhileTrue() {
		var script = "while true;\ndo\n  echo polling\ndone\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/poll.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "INFINITE_WHILE_SHELL".equals(f.ruleId())
						&& f.severity() == Severity.MEDIUM);
	}

	@Test
	@DisplayName("AC-S099e2-7: INFINITE_WHILE_SHELL — while : ; 觸發 MEDIUM finding")
	@Tag("AC-S099e2")
	void detectsInfiniteWhileColon() {
		var script = "while : ;\ndo echo loop; done\n";
		var output = scanner.analyze(ctx("# skill", Map.of("scripts/loop.sh", script)));

		assertThat(output.findings())
				.anyMatch(f -> "INFINITE_WHILE_SHELL".equals(f.ruleId()));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Clean content
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("AC-S099e2-8: 安全腳本 — 無 findings / notices")
	@Tag("AC-S099e2")
	void cleanScriptReturnsEmpty() {
		var script = """
				#!/bin/bash
				set -euo pipefail
				echo "running analysis..."
				python3 analyze.py --input "$1" --output "$2"
				echo "done"
				""";
		var output = scanner.analyze(ctx("# safe skill\n", Map.of("scripts/analyze.sh", script)));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: resource DoS scanner scans non-script package files")
	@Tag("AC-S147-PACKAGE-FILES")
	void scansNonScriptPackageFiles() {
		var output = scanner.analyze(ctxFiles(Map.of("assets/loop.txt", "sleep infinity\n")));

		assertThat(output.findings())
				.anyMatch(f -> "SLEEP_OVERFLOW".equals(f.ruleId())
						&& "assets/loop.txt".equals(f.filePath()));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Contract
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("ResourceDoSScanner 實作 SecurityAnalyzer：phase=STATIC, name=resource-dos")
	void implementsSecurityAnalyzerContract() {
		assertThat(scanner.name()).isEqualTo("resource-dos");
		assertThat(scanner.phase()).isEqualTo(Phase.STATIC);
	}

	@Test
	@DisplayName("SKILL.md 內的 fork bomb 也被掃描")
	void scansSkillMdContent() {
		var skillMd = "# evil skill\n:(){ :|:& };:\n";
		var output = scanner.analyze(ctx(skillMd, Map.of()));

		assertThat(output.findings())
				.anyMatch(f -> "FORK_BOMB".equals(f.ruleId())
						&& "SKILL.md".equals(f.filePath()));
	}
}
