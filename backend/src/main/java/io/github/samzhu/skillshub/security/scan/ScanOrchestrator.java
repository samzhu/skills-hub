package io.github.samzhu.skillshub.security.scan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.RiskLevel;
import io.github.samzhu.skillshub.security.scan.sarif.SarifReporter;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S010 多引擎安全掃描編排器 — 監聽 {@link SkillVersionPublishedEvent}，依
 * {@link Phase} 順序串接所有 {@link SecurityAnalyzer}，最後寫入 SARIF 報告與 read model。
 *
 * <p>取代 S005 的 {@code RiskAssessmentListener}（單一 regex scanner），
 * 升級為三階段 pipeline：
 * <ol>
 *   <li><b>Phase 1 (STATIC)</b> — PatternScanner / SecretScanner / MetadataValidator
 *       並行執行，使用 {@code Executors.newVirtualThreadPerTaskExecutor()}（Boot 4 已啟用
 *       {@code spring.threads.virtual.enabled=true}）</li>
 *   <li><b>Phase 2 (LLM)</b> — LlmJudge 序列執行，可選（bean 缺席時自動跳過）</li>
 *   <li><b>Phase 3 (META)</b> — MetaAnalyzer 序列執行，跨引擎彙整規則</li>
 * </ol>
 *
 * <p>關鍵設計（per spec §2.3）：
 * <ul>
 *   <li>引擎被 {@code @ConditionalOnProperty} 關掉時 bean 不存在 → 不在 list 中 → 不執行
 *       → 不出現在 SARIF runs[]，無 null 處理</li>
 *   <li>單一引擎拋例外不影響其他引擎；統一以 WARN log 記錄</li>
 *   <li>嚴重度合併採 max severity 規則</li>
 *   <li>持久化：直接透過 Spring Data JDBC repository 的 @Modifying @Query 寫
 *       skills.risk_level + skill_versions.risk_assessment（per S005 §7 finding：
 *       避免循環依賴；不發第二個 SkillRiskAssessed application event）</li>
 * </ul>
 *
 * <p>S024 T05B：S014 的 {@code SkillReadModelRepository} / {@code SkillVersionReadModelRepository}
 * 已刪除；寫入路徑改走 {@link SkillRepository#updateRiskLevel}（@Modifying @Query）+
 * {@link SkillVersionRepository#findBySkillIdAndVersion} → {@code SkillVersion.attachRiskAssessment} → {@code versionRepo.save}。
 *
 * @see SecurityAnalyzer
 * @see SarifReporter
 */
@Component
class ScanOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(ScanOrchestrator.class);

	private final List<SecurityAnalyzer> analyzers;
	private final StorageService storageService;
	private final PackageService packageService;
	// S024 T05B: aggregate repositories（取代 v1.5.0 read-model repositories）
	private final SkillRepository skillRepo;
	private final SkillVersionRepository versionRepo;
	private final SarifReporter sarifReporter;

	/**
	 * 虛擬執行緒 executor — 由 Boot 4 + Spring 6.2 在 {@code spring.threads.virtual.enabled=true}
	 * 時使用 {@code Thread.ofVirtual()}。Phase 1 並行的成本接近於零，無需平台執行緒池。
	 */
	private final Executor virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

	ScanOrchestrator(List<SecurityAnalyzer> analyzers,
			StorageService storageService,
			PackageService packageService,
			SkillRepository skillRepo,
			SkillVersionRepository versionRepo,
			SarifReporter sarifReporter) {
		this.analyzers = analyzers;
		this.storageService = storageService;
		this.packageService = packageService;
		this.skillRepo = skillRepo;
		this.versionRepo = versionRepo;
		this.sarifReporter = sarifReporter;
	}

	/**
	 * S023：升級為 {@link ApplicationModuleListener}（async + AFTER_COMMIT + REQUIRES_NEW
	 * + outbox 追蹤）。原 {@code @Order(LOWEST_PRECEDENCE)} 移除 — async 跨 listener 無
	 * 順序保證，FK target row（{@code skill_versions}）由 {@code SkillCommandService} 在 publisher TX
	 * 內已直接寫入（S024 後 SkillProjection 已刪除）；commit 後本 async listener 才觸發，FK 必滿足。
	 *
	 * <p><b>Idempotency</b>（S023）：以 {@code SkillVersionPublishedEvent.sourceEventId}
	 * 為 key 檢查 {@code risk_assessment->>'sourceEventId'}；若已掃描過則 early return，
	 * 避免重投時重新呼叫 LLM（成本）+ 重新寫 SkillRiskAssessed event（一致性）。
	 */
	@ApplicationModuleListener
	void on(SkillVersionPublishedEvent event) {
		// S023 idempotency check — retry 重投時若已掃描過此事件，跳過完整 pipeline
		if (versionRepo.hasRiskAssessmentFromEvent(
				event.aggregateId(), event.version(), event.sourceEventId())) {
			log.atDebug()
					.addKeyValue("skillId", event.aggregateId())
					.addKeyValue("version", event.version())
					.addKeyValue("sourceEventId", event.sourceEventId())
					.log("Skipping duplicate scan trigger (already scanned for this sourceEventId)");
			return;
		}

		log.info("Multi-engine scan triggered for skill {} version {} ({} engines)",
				event.aggregateId(), event.version(), analyzers.size());

		try {
			var initialContext = buildContext(event);

			// Phase 1: STATIC engines parallel
			var phase1Outputs = runParallel(byPhase(Phase.STATIC), initialContext);
			var phase1Findings = mergeFindings(phase1Outputs);

			// Enrichment: Phase 2/3 接收 phase 1 findings
			var enrichedAfterPhase1 = initialContext.withPhase1Findings(phase1Findings);

			// Phase 2: LLM sequential
			var phase2Outputs = runSequential(byPhase(Phase.LLM), enrichedAfterPhase1);
			var phase2Findings = mergeFindings(phase2Outputs);

			// Phase 3: META sequential — 看到 phase 1 + phase 2 findings
			var combinedSoFar = new ArrayList<SecurityFinding>(phase1Findings);
			combinedSoFar.addAll(phase2Findings);
			var enrichedAfterPhase2 = initialContext.withPhase1Findings(List.copyOf(combinedSoFar));
			var phase3Outputs = runSequential(byPhase(Phase.META), enrichedAfterPhase2);

			// 合併三階段 — 每個 engine 的輸出 keyed by name 給 SARIF reporter
			Map<String, AnalysisOutput> perEngine = mergeAllByEngine(phase1Outputs, phase2Outputs, phase3Outputs);

			// 全部 findings 用於最終 severity 計算
			var allFindings = new ArrayList<SecurityFinding>(combinedSoFar);
			for (var output : phase3Outputs.values()) allFindings.addAll(output.findings());
			RiskLevel finalLevel = classifyRiskLevel(allFindings, initialContext);

			// 渲染 SARIF
			Map<String, Object> sarif = sarifReporter.render(analyzers, perEngine, event);

			// 持久化
			persist(event, finalLevel, allFindings, perEngine, sarif);

			log.info("Scan completed for skill {} v{}: level={}, findings={}",
					event.aggregateId(), event.version(), finalLevel, allFindings.size());
		} catch (Exception e) {
			// 整體 pipeline 失敗 — 記錄但不拋；上游 publish flow 不應被掃描錯誤拖垮
			log.error("Scan pipeline failed for skill {} v{}: {}",
					event.aggregateId(), event.version(), e.toString(), e);
		}
	}

	/** 從 storage 下載 zip + 解壓出 SKILL.md / scripts，組成初始 ScanContext。 */
	private ScanContext buildContext(SkillVersionPublishedEvent event) throws Exception {
		var zipBytes = storageService.download(event.storagePath());
		var skillMd = packageService.extractSkillMd(zipBytes);
		var scripts = packageService.extractScripts(zipBytes);
		return new ScanContext(
				event.aggregateId(),
				event.version(),
				event.frontmatter(),
				skillMd == null ? "" : skillMd,
				scripts == null ? Map.of() : scripts,
				List.of());
	}

	private List<SecurityAnalyzer> byPhase(Phase phase) {
		return analyzers.stream().filter(a -> a.phase() == phase).toList();
	}

	/**
	 * 並行執行同一階段的所有引擎，回傳 engine name → AnalysisOutput map。
	 * 單一引擎拋例外時轉成 empty output，不影響其他引擎。
	 */
	private Map<String, AnalysisOutput> runParallel(List<SecurityAnalyzer> phaseAnalyzers, ScanContext ctx) {
		if (phaseAnalyzers.isEmpty()) return Map.of();

		var futures = phaseAnalyzers.stream()
				.map(a -> CompletableFuture.supplyAsync(() -> safeAnalyze(a, ctx), virtualExecutor)
						.thenApply(out -> Map.entry(a.name(), out)))
				.toList();

		// allOf 等所有並行任務完成；join() 不會拋（safeAnalyze 已吞例外）
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

		var result = new LinkedHashMap<String, AnalysisOutput>();
		for (var f : futures) {
			var entry = f.join();
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	/** 序列執行 — Phase 2/3 不需要並行（單一 engine 或 deterministic）。 */
	private Map<String, AnalysisOutput> runSequential(List<SecurityAnalyzer> phaseAnalyzers, ScanContext ctx) {
		if (phaseAnalyzers.isEmpty()) return Map.of();

		var result = new LinkedHashMap<String, AnalysisOutput>();
		for (SecurityAnalyzer a : phaseAnalyzers) {
			result.put(a.name(), safeAnalyze(a, ctx));
		}
		return result;
	}

	/** 包覆 analyze() 呼叫 — 任何例外轉成 empty output，避免單一引擎癱瘓 pipeline。 */
	private AnalysisOutput safeAnalyze(SecurityAnalyzer analyzer, ScanContext ctx) {
		try {
			var output = analyzer.analyze(ctx);
			return output == null ? AnalysisOutput.empty() : output;
		} catch (Exception e) {
			log.warn("Analyzer {} failed: {}", analyzer.name(), e.toString());
			return AnalysisOutput.empty();
		}
	}

	private List<SecurityFinding> mergeFindings(Map<String, AnalysisOutput> outputs) {
		var all = new ArrayList<SecurityFinding>();
		for (AnalysisOutput o : outputs.values()) all.addAll(o.findings());
		return List.copyOf(all);
	}

	private Map<String, AnalysisOutput> mergeAllByEngine(
			Map<String, AnalysisOutput>... outputMaps) {
		var combined = new LinkedHashMap<String, AnalysisOutput>();
		for (Map<String, AnalysisOutput> map : outputMaps) {
			combined.putAll(map);
		}
		return combined;
	}

	/**
	 * S096c 4-tier classification rule（per ADR-future + PRD D27 + Cisco Skill Scanner alignment）:
	 * <ol>
	 *   <li>0 findings AND scripts 為空 AND frontmatter 無 allowed-tools → {@link RiskLevel#NONE}
	 *       （純文件 skill，scanner 找不到 known patterns）</li>
	 *   <li>0 findings 但 has scripts OR has allowed-tools → {@link RiskLevel#LOW}
	 *       （capability declared 但 0 demonstrated risk）</li>
	 *   <li>有 findings → max(severity) → HIGH / MEDIUM / LOW</li>
	 * </ol>
	 *
	 * NONE ≠ certified safe，僅表示 scanner 未抓到威脅指紋（per ADR-future §1）。
	 * tooltip 與 UX 訊息需明確標示此 caveat。
	 */
	private RiskLevel classifyRiskLevel(List<SecurityFinding> findings, ScanContext ctx) {
		if (findings.isEmpty()) {
			boolean hasScripts = ctx.scripts() != null && !ctx.scripts().isEmpty();
			boolean hasAllowedTools = ctx.frontmatter() != null && ctx.frontmatter().get("allowed-tools") != null;
			return (hasScripts || hasAllowedTools) ? RiskLevel.LOW : RiskLevel.NONE;
		}
		var maxSev = findings.stream()
				.map(SecurityFinding::severity)
				.min(Comparator.comparingInt(Severity::ordinal))
				.orElse(Severity.LOW);
		// Severity name 對齊 RiskLevel name（HIGH/MEDIUM/LOW），直接 valueOf 安全
		return RiskLevel.valueOf(maxSev.name());
	}

	/**
	 * S024 T05B：兩路寫入（audit log 由 AuditEventListener 訂閱事件處理，本 method 不直接寫）：
	 * <ol>
	 *   <li>{@code skills.risk_level} 更新（cross-aggregate projection；per ADR-002 §2.6）</li>
	 *   <li>{@code skill_versions.{...}.risk_assessment} 透過 SkillVersion aggregate 充血方法
	 *       {@code attachRiskAssessment + versionRepo.save} 寫入 + register
	 *       {@code SkillRiskAssessedEvent}。Spring Data JDBC 透過 {@code @DomainEvents}
	 *       自動 publish 至 Modulith outbox；{@link io.github.samzhu.skillshub.shared.events.audit.AuditEventListener}
	 *       訂閱後 async 寫 {@code domain_events} audit row（dedupKey idempotent）</li>
	 * </ol>
	 */
	private void persist(SkillVersionPublishedEvent event,
			RiskLevel finalLevel,
			List<SecurityFinding> allFindings,
			Map<String, AnalysisOutput> perEngine,
			Map<String, Object> sarif) {

		// 1. skills.risk_level — cross-aggregate projection（per SkillRepository.updateRiskLevel Javadoc）
		skillRepo.updateRiskLevel(event.aggregateId(), finalLevel.name(), Instant.now());

		// 2. skill_versions.risk_assessment — 完整 SARIF + findings + notices；
		//    SkillVersion aggregate 充血路徑（attachRiskAssessment + save 觸發 SkillRiskAssessedEvent publish）
		var allNotices = new ArrayList<ScanNotice>();
		for (var output : perEngine.values()) allNotices.addAll(output.notices());

		var riskAssessment = new HashMap<String, Object>();
		riskAssessment.put("level", finalLevel.name());
		riskAssessment.put("findings", allFindings);
		riskAssessment.put("notices", allNotices);
		riskAssessment.put("sarif", sarif);
		riskAssessment.put("scannedAt", Instant.now());
		// S023 idempotency key — retry 透過此值知道 scan 已完成（per ScanOrchestrator.on Javadoc）
		riskAssessment.put("sourceEventId", event.sourceEventId());

		var sv = versionRepo.findBySkillIdAndVersion(event.aggregateId(), event.version())
				.orElseThrow(() -> new IllegalStateException(
						"SkillVersion not found for scan persist: " + event.aggregateId() + " v" + event.version()));
		sv.attachRiskAssessment(riskAssessment);
		versionRepo.save(sv);
	}
}
