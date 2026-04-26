package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

class SecretScannerTest {

	private final SecretScanner scanner = new SecretScanner();

	private static ScanContext ctx(String skillMd, Map<String, String> scripts) {
		return new ScanContext("skill-1", "1.0.0", Map.of(), skillMd, scripts, List.of());
	}

	@Test
	@DisplayName("AC-4.1: GitHub PAT 在 scripts/deploy.sh 偵測 + evidence 已遮罩")
	@Tag("AC-4")
	void detectsGitHubPatWithMaskedEvidence() {
		var token = "ghp_1234567890abcdef1234567890abcdef1234"; // 40 chars
		var script = "export GH_TOKEN=" + token + "\n";
		var output = scanner.analyze(ctx("", Map.of("scripts/deploy.sh", script)));

		var match = output.findings().stream()
				.filter(f -> "GITHUB_PAT".equals(f.ruleId()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected GITHUB_PAT finding"));

		assertThat(match.severity()).isEqualTo(Severity.HIGH);
		assertThat(match.analyzer()).isEqualTo("secret");
		assertThat(match.filePath()).isEqualTo("scripts/deploy.sh");

		// evidence must be masked: not contain the original token
		assertThat(match.evidence()).isNotNull();
		assertThat(match.evidence()).doesNotContain(token);
		assertThat(match.evidence()).contains("…");
		// 前 4 + … + 後 4 → contains prefix and suffix
		assertThat(match.evidence()).startsWith("ghp_");
		assertThat(match.evidence()).endsWith("1234");
	}

	@Test
	@DisplayName("AC-4.2: SKILL.md 內 Google API key 觸發 GOOGLE_API_KEY finding")
	@Tag("AC-4")
	void detectsGoogleApiKeyInSkillMd() {
		// Google API key: AIza + exactly 35 chars from [A-Za-z0-9_-] = 39 total chars
		// Real keys follow: "AIza" + 35-char suffix matching [0-9A-Za-z_-]
		var key = "AIza" + "x".repeat(35); // 39 chars total — matches \bAIza[…]{35}\b
		var skillMd = "Use this key:\n" + key + "\n";
		var output = scanner.analyze(ctx(skillMd, Map.of()));

		assertThat(output.findings())
				.anyMatch(f -> "GOOGLE_API_KEY".equals(f.ruleId())
						&& f.severity() == Severity.HIGH
						&& "secret".equals(f.analyzer())
						&& "SKILL.md".equals(f.filePath()));
	}

	@Test
	@DisplayName("AC-4.3: AWS access key 偵測")
	@Tag("AC-4")
	void detectsAwsAccessKey() {
		var output = scanner.analyze(ctx("AKIAIOSFODNN7EXAMPLE\n", Map.of()));
		assertThat(output.findings())
				.anyMatch(f -> "AWS_ACCESS_KEY_ID".equals(f.ruleId()) && f.severity() == Severity.HIGH);
	}

	@Test
	@DisplayName("AC-4.3: JWT token 偵測（eyJ…eyJ…sig）")
	@Tag("AC-4")
	void detectsJwt() {
		// JWT 三段，每段 base64
		var jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
		var output = scanner.analyze(ctx("token=" + jwt + "\n", Map.of()));
		assertThat(output.findings())
				.anyMatch(f -> "JWT".equals(f.ruleId()));
	}

	@Test
	@DisplayName("AC-4.3: PEM private key block 偵測")
	@Tag("AC-4")
	void detectsPemPrivateKey() {
		var pem = "-----BEGIN RSA PRIVATE KEY-----\n";
		var output = scanner.analyze(ctx("", Map.of("scripts/secrets.sh", pem)));
		assertThat(output.findings())
				.anyMatch(f -> "PEM_PRIVATE_KEY".equals(f.ruleId()));
	}

	@Test
	@DisplayName("AC-4.3: Slack webhook URL 偵測")
	@Tag("AC-4")
	void detectsSlackWebhook() {
		var url = "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX";
		var output = scanner.analyze(ctx(url + "\n", Map.of()));
		assertThat(output.findings())
				.anyMatch(f -> "SLACK_WEBHOOK".equals(f.ruleId()));
	}

	@Test
	@DisplayName("乾淨的 skill 無 finding")
	void cleanInputReturnsEmpty() {
		var output = scanner.analyze(ctx("# Hello\nThis is a clean readme.\n",
				Map.of("scripts/run.sh", "#!/bin/bash\necho hello\n")));
		assertThat(output.findings()).isEmpty();
	}

	@Test
	@DisplayName("Mask helper：短 secret (<8) 整段 ***，長 secret 顯示前 4 + … + 後 4")
	void maskBehaviour() {
		// Through GitHub PAT case (40 chars) we already verified前 4 + … + 後 4 in detectsGitHubPatWithMaskedEvidence.
		// Here we test a short-secret edge case via the GENERIC_BEARER pattern with an 8-char tail.
		// Bearer …{20+} regex requires ≥20 chars after Bearer，因此另透過呼叫 SecretScanner.maskHelper(testValue) 直接驗證。
		// 為了避免擴大測試表面，這裡只驗證遮罩邏輯（package-private mask 工具）：
		assertThat(SecretScanner.maskEvidence("abcdef")).isEqualTo("***");          // length < 8 整段
		assertThat(SecretScanner.maskEvidence("abcd1234ef56")).isEqualTo("abcd…ef56"); // 前 4 + … + 後 4
	}

	@Test
	@DisplayName("SecretScanner 是 SecurityAnalyzer，phase=STATIC, name=\"secret\"")
	void implementsSecurityAnalyzerContract() {
		assertThat(scanner.name()).isEqualTo("secret");
		assertThat(scanner.phase()).isEqualTo(Phase.STATIC);
	}

	@Test
	@DisplayName("AC-5.4: secret bean 啟用時建立")
	void beanCreatedWhenEnabled() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
				.withUserConfiguration(SecretReg.class)
				.withPropertyValues("skillshub.scanner.engines.secret.enabled=true")
				.run(ctx -> assertThat(ctx).hasBean("secret"));
	}

	@Test
	@DisplayName("AC-5.4: secret bean 關閉時不建立")
	void beanAbsentWhenDisabled() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
				.withUserConfiguration(SecretReg.class)
				.withPropertyValues("skillshub.scanner.engines.secret.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean("secret"));
	}

	@Configuration
	static class SecretReg {
		@Bean("secret")
		@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
				name = "skillshub.scanner.engines.secret.enabled",
				havingValue = "true",
				matchIfMissing = true)
		SecurityAnalyzer secretScanner() { return new SecretScanner(); }
	}
}
