package io.github.samzhu.skillshub.security.scan.sarif;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * 把每引擎的 {@link AnalysisOutput} 渲染成 SARIF 2.1.0 結構，並透過 Jackson
 * {@code convertValue} 轉成 {@code Map<String, Object>} 給 MongoDB 直接序列化進
 * Firestore document（避免引入額外的 BSON codec）。
 *
 * <p>SARIF 文件結構：每個啟用引擎一個 {@code runs[]} entry；每個 finding 一個 {@code result}；
 * 每個 notice 進到 {@code invocations[0].toolExecutionNotifications}（per spec §2.3 決策 #4）。
 *
 * <p>嚴重度映射（per spec §2.3 決策 #4）：
 * <ul>
 *   <li>{@link Severity#HIGH} → SARIF level {@code "error"} + {@code security-severity = "8.5"}</li>
 *   <li>{@link Severity#MEDIUM} → SARIF level {@code "warning"} + {@code security-severity = "5.0"}</li>
 *   <li>{@link Severity#LOW} → SARIF level {@code "note"} + {@code security-severity = "2.5"}</li>
 * </ul>
 *
 * @see SarifModels
 * @see <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html">SARIF v2.1.0 spec</a>
 */
@Component
public class SarifReporter {

	/** OASIS schema URI — 固定值，per SARIF 2.1.0 規範。 */
	private static final String SARIF_SCHEMA =
			"https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.json";

	/**
	 * 內部 ObjectMapper — 不從 Spring 容器注入，避免與專案 Jackson 客制化（如
	 * PageSerializationMode VIA_DTO、Spring AI mix-ins）相互影響。SARIF 序列化只用到
	 * record → Map 的基礎轉換，無需任何客制 module，獨立 ObjectMapper 行為更可預測。
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * 渲染 SARIF 文件為 {@code Map<String, Object>}（給 Mongo 直接序列化用）。
	 *
	 * @param analyzers  本次掃描啟用的所有引擎（依 List 順序對應到 SARIF runs[]）
	 * @param perEngine  每個引擎的輸出，key = engine name；缺席視為 {@link AnalysisOutput#empty()}
	 * @param event      觸發掃描的 SkillVersionPublishedEvent（用於版本標籤；目前未使用但保留擴充點）
	 * @return SARIF 2.1.0 結構序列化後的 Map
	 */
	public Map<String, Object> render(
			List<SecurityAnalyzer> analyzers,
			Map<String, AnalysisOutput> perEngine,
			SkillVersionPublishedEvent event) {

		var runs = new ArrayList<SarifModels.Run>();
		for (SecurityAnalyzer engine : analyzers) {
			var output = perEngine.getOrDefault(engine.name(), AnalysisOutput.empty());
			runs.add(toRun(engine, output));
		}
		var sarifLog = new SarifModels.SarifLog(SARIF_SCHEMA, "2.1.0", runs);

		// Jackson convertValue: typed POJO → Map<String, Object>
		// MongoDB driver 可直接序列化 Map 進 BSON Document，無需自訂 codec
		@SuppressWarnings("unchecked")
		var asMap = objectMapper.convertValue(sarifLog, Map.class);
		return asMap;
	}

	/** 把單一引擎的輸出轉成一個 SARIF Run。 */
	private SarifModels.Run toRun(SecurityAnalyzer engine, AnalysisOutput output) {
		// driver: 引擎名稱即 SARIF tool 身份
		var driver = new SarifModels.Driver(engine.name(), null);
		var tool = new SarifModels.Tool(driver);

		// findings → results[]
		var results = output.findings().stream().map(this::toResult).toList();

		// notices → invocations[0].toolExecutionNotifications[]
		// 即使無 notice 仍輸出 invocations 標示 executionSuccessful=true，幫助 GHAS 理解引擎跑過
		var notifications = output.notices().stream().map(this::toNotification).toList();
		var invocations = List.of(new SarifModels.Invocation(true, notifications.isEmpty() ? null : notifications));

		return new SarifModels.Run(tool, results, invocations);
	}

	/** SecurityFinding → SARIF Result。 */
	private SarifModels.Result toResult(SecurityFinding finding) {
		// SARIF level — error/warning/note 對應嚴重度
		var level = severityToLevel(finding.severity());

		// security-severity — GHAS UI 嚴重度 banding（浮點字串）
		var securitySeverity = severityToScore(finding.severity());
		var properties = new java.util.LinkedHashMap<String, Object>();
		properties.put("security-severity", securitySeverity);
		// 把 analyzer / owaspAst 也放進 properties，方便下游消費
		if (finding.analyzer() != null) properties.put("analyzer", finding.analyzer());
		if (finding.owaspAst() != null) properties.put("owaspAst", finding.owaspAst());

		// 位置：filePath + line 都存在才有 location
		List<SarifModels.Location> locations = null;
		if (finding.filePath() != null) {
			var artifact = new SarifModels.ArtifactLocation(finding.filePath());
			var region = (finding.line() != null) ? new SarifModels.Region(finding.line()) : null;
			var physical = new SarifModels.PhysicalLocation(artifact, region);
			locations = List.of(new SarifModels.Location(physical));
		}

		return new SarifModels.Result(
				finding.ruleId(),
				level,
				SarifModels.wrapText(finding.message()),
				locations,
				properties);
	}

	private SarifModels.ToolNotification toNotification(ScanNotice notice) {
		// notices 一律 level="note"（informational，per spec §2.3 #4）
		return new SarifModels.ToolNotification("note", SarifModels.wrapText(notice.message()));
	}

	private static String severityToLevel(Severity s) {
		return switch (s) {
			case HIGH -> "error";
			case MEDIUM -> "warning";
			case LOW -> "note";
		};
	}

	/**
	 * 嚴重度映射為 GHAS security-severity 浮點字串。
	 * GHAS banding：≥ 9.0 Critical / 7.0-8.9 High / 4.0-6.9 Medium / 0.1-3.9 Low。
	 */
	private static String severityToScore(Severity s) {
		return switch (s) {
			case HIGH -> "8.5";
			case MEDIUM -> "5.0";
			case LOW -> "2.5";
		};
	}
}
