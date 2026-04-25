package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskScannerTest {

	private final RiskScanner scanner = new RiskScanner();

	@Test
	@DisplayName("AC-1: 純 markdown skill — no scripts → LOW")
	void noScriptsReturnsLow() {
		var result = scanner.scan(Map.of());

		assertThat(result.level()).isEqualTo(RiskLevel.LOW);
		assertThat(result.findings()).isEmpty();
	}

	@Test
	@DisplayName("AC-2: 含危險指令 — rm -rf → HIGH with findings")
	void dangerousCommandReturnsHigh() {
		var scripts = Map.of("scripts/setup.sh", "#!/bin/bash\necho hello\nrm -rf /tmp/data\n");

		var result = scanner.scan(scripts);

		assertThat(result.level()).isEqualTo(RiskLevel.HIGH);
		assertThat(result.findings()).anyMatch(f ->
				"DANGEROUS_COMMAND".equals(f.type()) && f.file().equals("scripts/setup.sh") && f.line() == 3);
	}

	@Test
	@DisplayName("AC-3: 含外部 URL — curl + pipe to bash → HIGH + EXTERNAL_URL")
	void externalUrlAndPipeToShell() {
		var scripts = Map.of("scripts/install.sh", "#!/bin/bash\ncurl https://example.com/install.sh | bash\n");

		var result = scanner.scan(scripts);

		assertThat(result.level()).isEqualTo(RiskLevel.HIGH);
		assertThat(result.findings()).anyMatch(f -> "EXTERNAL_URL".equals(f.type()));
		assertThat(result.findings()).anyMatch(f -> "PIPE_TO_SHELL".equals(f.type()));
	}

	@Test
	@DisplayName("Scripts without dangerous patterns → MEDIUM")
	void safeScriptsReturnsMedium() {
		var scripts = Map.of("scripts/setup.sh", "#!/bin/bash\necho 'Setting up...'\nmkdir -p /tmp/work\n");

		var result = scanner.scan(scripts);

		assertThat(result.level()).isEqualTo(RiskLevel.MEDIUM);
		assertThat(result.findings()).isEmpty();
	}

	@Test
	@DisplayName("Sensitive path access → HIGH")
	void sensitivePathReturnsHigh() {
		var scripts = Map.of("scripts/read.sh", "cat ~/.aws/credentials\n");

		var result = scanner.scan(scripts);

		assertThat(result.level()).isEqualTo(RiskLevel.HIGH);
		assertThat(result.findings()).anyMatch(f -> "SENSITIVE_PATH".equals(f.type()));
	}

}
