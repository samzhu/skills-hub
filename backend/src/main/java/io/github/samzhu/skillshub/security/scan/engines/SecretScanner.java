package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * Phase 1 靜態引擎 — 在 SKILL.md 與 scripts/* 內偵測 API key、token、私鑰等敏感字串。
 *
 * <p>規則設計原則：
 * <ul>
 *   <li>規則來源啟發自 gitleaks（MIT 授權，<a href="https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml">gitleaks.toml</a>）
 *       與 OWASP Agentic Skill Top 10：AST01「Prompt / Credential 注入」</li>
 *   <li>每條規則一個 ruleId 對應 SARIF {@code result.ruleId}，方便 GHAS UI 分組</li>
 *   <li>命中時 {@link SecurityFinding#evidence()} 必須遮罩，避免日誌或前端顯示時二次外洩</li>
 *   <li>嚴重度全部 HIGH —— secret 一旦進入版本控制就不再「機密」，無 MEDIUM 中間態</li>
 * </ul>
 *
 * <p>本引擎為純 regex CPU-bound 計算，可在 Phase 1 並行執行；不持有狀態。
 *
 * @see SecurityAnalyzer
 * @see #maskEvidence(String)
 */
@Component("secret")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.secret.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class SecretScanner implements SecurityAnalyzer {

	private static final String SKILL_MD_PATH = "SKILL.md";

	/** OWASP Agentic Skill Top 10：AST01 = "Credential / Prompt Injection"。 */
	private static final String OWASP_AST01 = "AST01";

	/**
	 * 10 條 secret 偵測規則，啟發自 gitleaks/config/gitleaks.toml（MIT）與 TruffleHog detectors。
	 *
	 * <p>每條規則使用 {@code Matcher.find()} 而非 {@code matches()}，以支援嵌入在腳本行
	 * 中的 secret（如 {@code export GH_TOKEN=ghp_…}）。
	 *
	 * <p>注意 GENERIC_BEARER 與 GENERIC_PASSWORD 是廣譜規則，可能與其他 ruleId 重疊命中
	 * 同一字串；目前接受重複 finding（兩筆 ruleId 不同的 SecurityFinding），方便下游
	 * 規則歸因。MetaAnalyzer (T5) 可選擇性 dedup。
	 */
	private static final List<Rule> RULES = List.of(
			// AWS Access Key ID — 標準 20-char prefix-uppercase 格式
			new Rule("AWS_ACCESS_KEY_ID",
					Pattern.compile("\\b(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|ASIA)[A-Z0-9]{16}\\b")),
			// AWS Secret Access Key — 40 chars base64-ish，必須緊跟 aws 字眼避免 FP
			new Rule("AWS_SECRET_KEY",
					Pattern.compile("(?i)aws[^\\n]{0,30}['\"]?[0-9a-zA-Z/+]{40}['\"]?")),
			// Google API Key — AIza prefix + 35 chars
			new Rule("GOOGLE_API_KEY",
					Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
			// GitHub PAT classic — ghp_ prefix + 36 chars
			new Rule("GITHUB_PAT",
					Pattern.compile("\\bghp_[A-Za-z0-9]{36}\\b")),
			// GitHub fine-grained PAT — github_pat_ + 82 chars
			new Rule("GITHUB_FINE_GRAINED_PAT",
					Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{82}\\b")),
			// OpenAI API key — sk- + 48 chars
			new Rule("OPENAI_KEY",
					Pattern.compile("\\bsk-[A-Za-z0-9]{48}\\b")),
			// JWT — 三段 base64url，由 . 分隔
			new Rule("JWT",
					Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")),
			// PEM 私鑰 BEGIN block — 涵蓋 RSA / EC / OPENSSH 各種變體
			new Rule("PEM_PRIVATE_KEY",
					Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
			// Slack incoming webhook URL
			new Rule("SLACK_WEBHOOK",
					Pattern.compile("https://hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+")),
			// 廣譜 Bearer / Authorization header — 至少 20 chars 避免 FP
			new Rule("GENERIC_BEARER",
					Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}")),

			// ── S099e4 LLM06 新增規則（2026-05-07）────────────────────────────────
			// Anthropic Claude API key — sk-ant-api{N}- + base64url payload
			new Rule("ANTHROPIC_API_KEY",
					Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
			// Stripe live / test secret key — sk_{live|test}_ + 24+ chars
			new Rule("STRIPE_API_KEY",
					Pattern.compile("\\bsk_(?:live|test)_[A-Za-z0-9]{24,}\\b")),
			// HuggingFace access token — hf_ + 30+ alphanumeric chars
			new Rule("HF_ACCESS_TOKEN",
					Pattern.compile("\\bhf_[A-Za-z0-9]{30,}\\b")),
			// npm token — npm_ + 36 chars base62
			new Rule("NPM_TOKEN",
					Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b")),
			// 資料庫連線字串含密碼 — postgresql/mysql/mongodb/redis://user:pass@host
			new Rule("DB_CONN_WITH_PASSWORD",
					Pattern.compile("(?i)(?:postgresql|mysql|mongodb|redis)(?:\\+[a-z]+)?://[^:@\\s]+:[^@\\s]{6,}@")),
			// 硬編碼密碼 / API key 賦值 — password="abc12345" / api_key: "abc12345"
			new Rule("GENERIC_HARDCODED_PASSWORD",
					Pattern.compile("(?i)(?:password|passwd|api_?key|secret_?key|access_?key)\\s*[=:]\\s*[\"'][^\"'\\s]{8,}[\"']"))
	);

	@Override
	public String name() { return "secret"; }

	@Override
	public Phase phase() { return Phase.STATIC; }

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		var findings = new ArrayList<SecurityFinding>();

		if (context.skillMd() != null && !context.skillMd().isEmpty()) {
			scanFile(SKILL_MD_PATH, context.skillMd(), findings);
		}
		for (Map.Entry<String, String> entry : context.scripts().entrySet()) {
			scanFile(entry.getKey(), entry.getValue(), findings);
		}

		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	/**
	 * 對單一檔案內容逐行套用規則集，命中即建立含遮罩 evidence 的 finding。
	 */
	private void scanFile(String filePath, String content, List<SecurityFinding> sink) {
		var lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			var line = lines[i];
			int lineNum = i + 1;
			for (Rule rule : RULES) {
				Matcher m = rule.pattern().matcher(line);
				while (m.find()) {
					sink.add(new SecurityFinding(
							rule.ruleId(),
							Severity.HIGH,
							"Detected " + rule.ruleId().toLowerCase().replace('_', ' '),
							filePath,
							lineNum,
							maskEvidence(m.group()),
							name(),
							OWASP_AST01));
				}
			}
		}
	}

	/**
	 * 對 secret 字串遮罩 — 短於 8 字元時整段以 {@code "***"} 取代（怕反推），
	 * 否則保留前 4 + {@code "…"} + 後 4，方便人類辨識規則命中但無法外洩原始值。
	 *
	 * <p>package-private visibility 讓 SecretScannerTest 可直接驗證遮罩邏輯。
	 *
	 * @param raw 原始 secret 文字
	 * @return 遮罩後字串
	 */
	static String maskEvidence(String raw) {
		if (raw == null || raw.length() < 8) {
			return "***";
		}
		return raw.substring(0, 4) + "…" + raw.substring(raw.length() - 4);
	}

	/**
	 * 規則定義 — ruleId 與對應 regex。所有 secret 規則嚴重度固定 HIGH，故省略 severity 欄位。
	 *
	 * @param ruleId  穩定的規則代碼，對應 SARIF result.ruleId
	 * @param pattern 用於 {@code Matcher.find()} 的 regex
	 */
	private record Rule(String ruleId, Pattern pattern) {}
}
