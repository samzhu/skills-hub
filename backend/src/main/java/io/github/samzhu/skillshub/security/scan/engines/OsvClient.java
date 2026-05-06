package io.github.samzhu.skillshub.security.scan.engines;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OSV.dev /v1/querybatch HTTP client — 免費、無 API key，P90 ≤ 4s。
 *
 * <p>單次批次查詢所有解析出的依賴套件；結果 index 對齊輸入 PURL list。
 *
 * @see DependencyVulnScanner
 */
@Component
class OsvClient {

	private static final Logger log = LoggerFactory.getLogger(OsvClient.class);
	private static final String OSV_BASE_URL = "https://api.osv.dev";

	private final RestClient restClient;

	OsvClient(RestClient.Builder builder) {
		this.restClient = builder.baseUrl(OSV_BASE_URL).build();
	}

	/**
	 * 批次查詢 PURL list 的已知漏洞。
	 *
	 * @param purls PURL 列表（e.g. {@code "pkg:pypi/requests@2.28.1"}）
	 * @return index 對齊的漏洞列表（無漏洞的套件為 empty list）
	 * @throws Exception 網路或解析失敗時拋出，由 caller 做 safeAnalyze
	 */
	List<List<OsvVuln>> querybatch(List<String> purls) {
		if (purls.isEmpty()) return List.of();

		var queries = purls.stream()
				.map(p -> new OsvQuery(new OsvPackage(p)))
				.toList();
		var response = restClient.post()
				.uri("/v1/querybatch")
				.body(new OsvBatchRequest(queries))
				.retrieve()
				.body(OsvBatchResponse.class);

		if (response == null || response.results() == null) {
			log.warn("[dep-vuln] OSV.dev returned null/empty querybatch response for {} purls", purls.size());
			return List.of();
		}
		return response.results().stream()
				.map(r -> r.vulns() != null ? r.vulns() : List.<OsvVuln>of())
				.toList();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Request / Response DTOs (package-private for test access)
	// ─────────────────────────────────────────────────────────────────────────

	record OsvBatchRequest(List<OsvQuery> queries) {}

	record OsvQuery(@JsonProperty("package") OsvPackage pkg) {}

	record OsvPackage(String purl) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OsvBatchResponse(List<OsvQueryResult> results) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OsvQueryResult(List<OsvVuln> vulns) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OsvVuln(String id, List<OsvSeverity> severity) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OsvSeverity(String type, String score) {}
}
