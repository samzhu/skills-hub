package io.github.samzhu.skillshub.security.scan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.sarif.SarifReporter;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
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
 *   <li>持久化：直接 MongoTemplate.updateFirst 寫 skills + skill_versions（per S005 §7
 *       finding：避免循環依賴；不發第二個 SkillRiskAssessed application event）</li>
 * </ul>
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
	private final DomainEventRepository eventStore;
	private final MongoTemplate mongoTemplate;
	private final SarifReporter sarifReporter;

	/**
	 * 虛擬執行緒 executor — 由 Boot 4 + Spring 6.2 在 {@code spring.threads.virtual.enabled=true}
	 * 時使用 {@code Thread.ofVirtual()}。Phase 1 並行的成本接近於零，無需平台執行緒池。
	 */
	private final Executor virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

	ScanOrchestrator(List<SecurityAnalyzer> analyzers,
			StorageService storageService,
			PackageService packageService,
			DomainEventRepository eventStore,
			MongoTemplate mongoTemplate,
			SarifReporter sarifReporter) {
		this.analyzers = analyzers;
		this.storageService = storageService;
		this.packageService = packageService;
		this.eventStore = eventStore;
		this.mongoTemplate = mongoTemplate;
		this.sarifReporter = sarifReporter;
	}

	/**
	 * 必須在 SkillProjection (skill::query) 寫入 skill_versions document 之後才執行 —
	 * ScanOrchestrator 用 {@code MongoTemplate.updateFirst} 寫 riskAssessment，需要該 document 已存在。
	 * Spring 同步 @EventListener 預設順序由 bean scan 決定，不保證；用 LOWEST_PRECEDENCE 強制最後跑。
	 */
	@EventListener
	@Order(Ordered.LOWEST_PRECEDENCE)
	void on(SkillVersionPublishedEvent event) {
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
			Severity finalLevel = aggregateMaxSeverity(allFindings);

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
	 * Max severity 規則：HIGH > MEDIUM > LOW。無 finding → LOW（與 S005 行為相容）。
	 */
	private Severity aggregateMaxSeverity(List<SecurityFinding> findings) {
		return findings.stream()
				.map(SecurityFinding::severity)
				.min(Comparator.comparingInt(Severity::ordinal))   // ordinal: HIGH=0, MEDIUM=1, LOW=2 → min = highest
				.orElse(Severity.LOW);
	}

	/**
	 * 三路寫入：
	 * <ol>
	 *   <li>{@code domain_events} 新增一筆 SkillRiskAssessed event（aggregate 內 sequence 自增）</li>
	 *   <li>{@code skills.{aggregateId}.riskLevel} 更新</li>
	 *   <li>{@code skill_versions.{...}.riskAssessment} 寫入完整 sarif + findings + notices</li>
	 * </ol>
	 * 直接走 MongoTemplate（per S005 §7：避免發第二個 application event 造成循環依賴）。
	 */
	private void persist(SkillVersionPublishedEvent event,
			Severity finalLevel,
			List<SecurityFinding> allFindings,
			Map<String, AnalysisOutput> perEngine,
			Map<String, Object> sarif) {

		// 1. domain_events
		long nextSequence = eventStore.findTopByAggregateIdOrderBySequenceDesc(event.aggregateId())
				.map(e -> e.sequence() + 1).orElse(1L);
		var payload = Map.<String, Object>of(
				"version", event.version(),
				"riskLevel", finalLevel.name(),
				"findingsCount", allFindings.size());
		eventStore.save(new DomainEvent(
				UUID.randomUUID().toString(),
				event.aggregateId(),
				"Skill",
				"SkillRiskAssessed",
				payload,
				nextSequence,
				Instant.now(),
				Map.of()));

		// 2. skills.riskLevel — 與 S005 行為相容（read model 同一欄位）
		mongoTemplate.updateFirst(
				Query.query(Criteria.where("_id").is(event.aggregateId())),
				Update.update("riskLevel", finalLevel.name()).set("updatedAt", Instant.now()),
				"skills");

		// 3. skill_versions.riskAssessment — 完整 SARIF + findings + notices
		var allNotices = new ArrayList<ScanNotice>();
		for (var output : perEngine.values()) allNotices.addAll(output.notices());

		var riskAssessment = new HashMap<String, Object>();
		riskAssessment.put("level", finalLevel.name());
		riskAssessment.put("findings", allFindings);
		riskAssessment.put("notices", allNotices);
		riskAssessment.put("sarif", sarif);
		riskAssessment.put("scannedAt", Instant.now());

		// skill_versions 是 keyed by id (UUID per row)，需依 skillId + version 找到正確版本
		mongoTemplate.updateFirst(
				Query.query(Criteria.where("skillId").is(event.aggregateId())
						.and("version").is(event.version())),
				new Update().set("riskAssessment", riskAssessment),
				"skill_versions");
	}
}
