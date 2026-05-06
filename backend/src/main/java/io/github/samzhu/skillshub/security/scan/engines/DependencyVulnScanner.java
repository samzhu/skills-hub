package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

/**
 * S099e3 — OWASP LLM05 Supply Chain Vulnerabilities 依賴漏洞掃描引擎。
 *
 * <p>解析 skill 腳本目錄中的 {@code requirements.txt}（pinned {@code ==}）與
 * {@code package.json}（dependencies + devDependencies），批次查詢
 * <a href="https://api.osv.dev/v1/querybatch">OSV.dev /v1/querybatch</a>（免費、無 API key）；
 * 有漏洞的套件輸出 {@link SecurityFinding}，CVSS 分數映射 HIGH/MEDIUM/LOW。
 *
 * <p>網路不通時 safeAnalyze 包覆：log warning + 回傳空 findings，不阻斷 scan pipeline。
 *
 * @see SecurityAnalyzer
 */
@Component("dep-vuln")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.dep-vuln.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class DependencyVulnScanner implements SecurityAnalyzer {

	private static final Logger log = LoggerFactory.getLogger(DependencyVulnScanner.class);

	/** requirements.txt pinned version pattern: name[extras]==version */
	private static final Pattern REQ_PINNED = Pattern.compile(
			"^([A-Za-z0-9_.-]+)(?:\\[.*?\\])?==([A-Za-z0-9._-]+)");

	private static final String OWASP_AST05 = "AST05";

	private final ObjectMapper objectMapper;
	private final OsvClient osvClient;

	DependencyVulnScanner(ObjectMapper objectMapper, OsvClient osvClient) {
		this.objectMapper = objectMapper;
		this.osvClient = osvClient;
	}

	@Override
	public String name() {
		return "dep-vuln";
	}

	@Override
	public Phase phase() {
		return Phase.STATIC;
	}

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		// collect (purl → filePath) from all supported manifest files
		var purlToFile = new LinkedHashMap<String, String>();

		for (Map.Entry<String, String> entry : context.scripts().entrySet()) {
			var path = entry.getKey();
			var content = entry.getValue();
			if (path.endsWith("requirements.txt")) {
				parseRequirements(content, path, purlToFile);
			} else if (path.endsWith("package.json")) {
				parsePackageJson(content, path, purlToFile);
			}
		}

		if (purlToFile.isEmpty()) {
			return new AnalysisOutput(List.of(), List.of());
		}

		var purls = new ArrayList<>(purlToFile.keySet());
		var findings = new ArrayList<SecurityFinding>();

		try {
			var vulnResults = osvClient.querybatch(purls);
			for (int i = 0; i < Math.min(purls.size(), vulnResults.size()); i++) {
				var purl = purls.get(i);
				var filePath = purlToFile.get(purl);
				for (var vuln : vulnResults.get(i)) {
					findings.add(toFinding(purl, vuln, filePath));
				}
			}
		} catch (Exception e) {
			log.warn("[dep-vuln] OSV.dev query failed — skipping dep scan (non-blocking): {}", e.getMessage());
		}

		return new AnalysisOutput(List.copyOf(findings), List.<ScanNotice>of());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Parsing helpers
	// ─────────────────────────────────────────────────────────────────────────

	private void parseRequirements(String content, String filePath,
			Map<String, String> sink) {
		for (var line : content.split("\n")) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			var m = REQ_PINNED.matcher(line);
			if (m.find()) {
				// normalize: lowercase, underscore→hyphen (PyPI canonical)
				var name = m.group(1).toLowerCase().replace('_', '-');
				var version = m.group(2);
				sink.put("pkg:pypi/" + name + "@" + version, filePath);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void parsePackageJson(String content, String filePath,
			Map<String, String> sink) {
		try {
			Map<String, Object> pkg = objectMapper.readValue(content, new TypeReference<>() {});
			for (var mapKey : List.of("dependencies", "devDependencies")) {
				Object depsObj = pkg.get(mapKey);
				if (!(depsObj instanceof Map<?, ?> depsMap)) continue;
				for (Map.Entry<?, ?> entry : depsMap.entrySet()) {
					var name = String.valueOf(entry.getKey());
					// skip scoped packages (@scope/name) — V1 defer
					if (name.startsWith("@")) continue;
					var rawVersion = String.valueOf(entry.getValue());
					cleanNpmVersion(rawVersion).ifPresent(version ->
							sink.put("pkg:npm/" + name + "@" + version, filePath));
				}
			}
		} catch (Exception e) {
			log.warn("[dep-vuln] failed to parse {}: {}", filePath, e.getMessage());
		}
	}

	/** Strip npm semver range prefixes (^, ~, >=, >, <=, <) and take first token. */
	static Optional<String> cleanNpmVersion(String raw) {
		if (raw == null) return Optional.empty();
		var v = raw.strip().replaceAll("^[~^>=<|* ]+", "");
		if (v.isEmpty() || v.equals("latest") || v.equals("*") || v.equals("x")) {
			return Optional.empty();
		}
		// take only the first version in a range (e.g. ">=1.0.0 <2.0.0")
		v = v.split("\\s+")[0];
		return Optional.of(v);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Finding construction
	// ─────────────────────────────────────────────────────────────────────────

	private SecurityFinding toFinding(String purl, OsvClient.OsvVuln vuln, String filePath) {
		var severity = mapSeverity(vuln);
		var ruleId = buildRuleId(purl, vuln.id());
		var message = "Vulnerable dependency: " + purl + " — " + vuln.id();
		return new SecurityFinding(ruleId, severity, message, filePath, null, purl, name(), OWASP_AST05);
	}

	private static String buildRuleId(String purl, String vulnId) {
		// DEP_VULN_{ECOSYSTEM}_{VULN_ID} — e.g. DEP_VULN_PYPI_GHSA-1234
		var ecosystem = purl.startsWith("pkg:pypi/") ? "PYPI" : "NPM";
		var safeId = vulnId.replaceAll("[^A-Za-z0-9_-]", "_").toUpperCase();
		return "DEP_VULN_" + ecosystem + "_" + safeId;
	}

	/**
	 * CIA-based heuristic from CVSS vector string (no external library needed for V1).
	 * Defers numeric scoring to S099e3-2.
	 *
	 * <p>C:H or I:H or A:H → HIGH; any L impact → MEDIUM; all N or unknown → MEDIUM (conservative).
	 */
	static Severity mapSeverity(OsvClient.OsvVuln vuln) {
		if (vuln.severity() == null || vuln.severity().isEmpty()) return Severity.MEDIUM;
		for (var sev : vuln.severity()) {
			var score = sev.score();
			if (score == null) continue;
			if (score.contains("/C:H") || score.contains("/I:H") || score.contains("/A:H")) {
				return Severity.HIGH;
			}
			if (score.contains("/C:L") || score.contains("/I:L") || score.contains("/A:L")) {
				return Severity.MEDIUM;
			}
		}
		return Severity.MEDIUM;
	}
}
