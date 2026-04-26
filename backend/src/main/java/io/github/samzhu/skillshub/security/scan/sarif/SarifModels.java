package io.github.samzhu.skillshub.security.scan.sarif;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SARIF 2.1.0 文件結構（手寫 Jackson POJO，per spec §2.3 決策 #3）。
 *
 * <p>不採用第三方 Java SARIF 函式庫的原因：研究發現 contrast-security/java-sarif、
 * de-jcup/sarif-java、JetBrains/qodana-sarif 三家全部長期低活動（最新 release 2023-03），
 * 且我們只需 ~10% schema 表面，手寫 5 個 record 比管理 dead lib 風險低。
 *
 * <p>所有 record 加 {@code @JsonInclude(NON_NULL)} 確保 nullable 欄位（line、owaspAst 等）
 * 不出現在 JSON / Map 中，符合 SARIF 規範「optional 欄位不應為 null」的 OASIS 建議。
 *
 * <p>共用通則：每個 SARIF 文件 = 1 個 {@link SarifLog}；每個啟用引擎 = 1 個 {@link Run}；
 * 每個 finding = 1 個 {@link Result}；每個 notice = 1 個 {@link ToolNotification}（位於
 * {@code invocations[].toolExecutionNotifications[]}）。
 *
 * @see SarifReporter
 * @see <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html">SARIF v2.1.0 spec</a>
 */
final class SarifModels {

	private SarifModels() {}

	/**
	 * SARIF 文件根結構。
	 *
	 * @param schema  指向 OASIS 公開的 JSON schema URI
	 * @param version 固定為 {@code "2.1.0"}
	 * @param runs    每個啟用引擎一個 Run（per OASIS §3.1）
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SarifLog(
			@JsonProperty("$schema") String schema,
			String version,
			List<Run> runs) {}

	/**
	 * 單一引擎的執行紀錄。{@link #invocations} 為 list 但本實作只用單一 invocation 收 notices。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Run(
			Tool tool,
			List<Result> results,
			List<Invocation> invocations) {}

	/** Tool wrapper — SARIF 規範 tool 必含 driver。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Tool(Driver driver) {}

	/**
	 * 引擎自我描述。
	 *
	 * @param name            引擎名稱（對應 {@code SecurityAnalyzer.name()}）
	 * @param semanticVersion 引擎語意化版本（用於 GHAS UI 標示版本，可為 null）
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Driver(String name, String semanticVersion) {}

	/**
	 * 單一 finding。
	 *
	 * @param ruleId     規則代碼（與 {@code SecurityFinding.ruleId} 一致）
	 * @param level      SARIF 嚴重等級：{@code "error"} / {@code "warning"} / {@code "note"} / {@code "none"}
	 * @param message    人類可讀訊息（{@link #wrapText(String)}）
	 * @param locations  位置陣列（檔案 + 行號）；nullable
	 * @param properties 自訂欄位 bag — 含 {@code "security-severity"} 浮點字串供 GHAS 嚴重度 banding
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Result(
			String ruleId,
			String level,
			Map<String, String> message,
			List<Location> locations,
			Map<String, Object> properties) {}

	/** SARIF 規範要求 message 包成物件（{ "text": "..." }），即使只有純文字。 */
	public static Map<String, String> wrapText(String text) {
		return Map.of("text", text == null ? "" : text);
	}

	/** Location wrapper — 規範要求至少一個 physicalLocation 或 logicalLocation。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Location(PhysicalLocation physicalLocation) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PhysicalLocation(ArtifactLocation artifactLocation, Region region) {}

	/**
	 * 檔案位置 — uri 採相對路徑（GHAS 規範要求；絕對路徑會被剝錯）。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ArtifactLocation(String uri) {}

	/** 行號 + 欄號區段；本實作只使用 startLine。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Region(Integer startLine) {}

	/**
	 * 引擎執行調用 — SARIF 規範要求至少一個 invocation 才能放 notifications。
	 *
	 * @param toolExecutionNotifications 引擎執行期間的 informational 訊息（per spec §2.3 決策 #4）
	 * @param executionSuccessful        本引擎是否成功完成（true = 沒拋例外）
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Invocation(
			Boolean executionSuccessful,
			List<ToolNotification> toolExecutionNotifications) {}

	/** 引擎發出的單一 notice — 對應 {@code ScanNotice}。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolNotification(String level, Map<String, String> message) {}
}
