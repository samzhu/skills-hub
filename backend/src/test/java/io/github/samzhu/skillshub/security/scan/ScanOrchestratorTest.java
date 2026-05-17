package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.samzhu.skillshub.security.scan.sarif.SarifReporter;
import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S010 多引擎安全掃描編排測試（Spring Data JDBC 版）。
 *
 * <p>S024 T05B 重寫：移除 v1.5.0 read-model + 直接 eventStore.save 路徑。新驗證模型：
 * <ul>
 *   <li>{@code skillRepo.updateRiskLevel(skillId, level, ts)} — cross-aggregate projection</li>
 *   <li>{@code versionRepo.save(SkillVersion)} — 攔截 aggregate state（attachRiskAssessment 後 riskAssessment field 寫入）</li>
 * </ul>
 * SkillRiskAssessed 進 audit log 由 {@link io.github.samzhu.skillshub.shared.events.audit.AuditEventListener}
 * 訂閱事件後 async 處理 — 屬於整合測試範疇（{@code AuditEventListenerTest} 覆蓋），本 unit test 不驗。
 */
class ScanOrchestratorTest {

	private static final SkillVersionPublishedEvent EVT =
			SkillVersionPublishedEvent.of("agg-1", "1.0.0", "gs://b/x.zip", 100,
					Map.of("name", "demo", "description", "x"), java.util.List.of());

	private static SecurityAnalyzer fakeAnalyzer(String name, Phase phase, AnalysisOutput output) {
		return new SecurityAnalyzer() {
			@Override public String name() { return name; }
			@Override public Phase phase() { return phase; }
			@Override public AnalysisOutput analyze(ScanContext ctx) { return output; }
		};
	}

	private static SecurityAnalyzer slowAnalyzer(String name, Phase phase, long delayMs) {
		return new SecurityAnalyzer() {
			@Override public String name() { return name; }
			@Override public Phase phase() { return phase; }
			@Override public AnalysisOutput analyze(ScanContext ctx) {
				try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
				return AnalysisOutput.empty();
			}
		};
	}

	private static SecurityAnalyzer throwingAnalyzer(String name, Phase phase) {
		return new SecurityAnalyzer() {
			@Override public String name() { return name; }
			@Override public Phase phase() { return phase; }
			@Override public AnalysisOutput analyze(ScanContext ctx) { throw new RuntimeException("boom"); }
		};
	}

	private static SecurityAnalyzer errorAnalyzer(String name, Phase phase) {
		return new SecurityAnalyzer() {
			@Override public String name() { return name; }
			@Override public Phase phase() { return phase; }
			@Override public AnalysisOutput analyze(ScanContext ctx) { throw new AssertionError("native metadata unavailable"); }
		};
	}

	private record Mocks(
			SkillRepository skillRepo,
			SkillVersionRepository versionRepo) {}

	private ScanOrchestrator buildOrchestrator(List<SecurityAnalyzer> analyzers, Mocks m) throws IOException {
		return buildOrchestrator(analyzers, m, Map.of());
	}

	private ScanOrchestrator buildOrchestrator(
			List<SecurityAnalyzer> analyzers,
			Mocks m,
			Map<String, String> scripts) throws IOException {
		var storage = mock(StorageService.class);
		when(storage.download(any())).thenReturn(new byte[0]);
		var pkg = mock(PackageService.class);
		when(pkg.extractSkillMd(any())).thenReturn("# md");
		when(pkg.extractScripts(any())).thenReturn(scripts);
		when(pkg.extractTextFiles(any())).thenReturn(Map.of());
		when(pkg.listEntryNames(any())).thenReturn(List.of("SKILL.md"));
		var sarif = new SarifReporter();
		return new ScanOrchestrator(analyzers, storage, pkg,
				m.skillRepo, m.versionRepo, sarif);
	}

	private Mocks newMocks() {
		var skillRepo = mock(SkillRepository.class);
		var versionRepo = mock(SkillVersionRepository.class);
		// 預設不曾掃描過此事件 — let pipeline run
		when(versionRepo.hasRiskAssessmentFromEvent(anyString(), anyString(), anyString())).thenReturn(false);
		// findBySkillIdAndVersion 回傳一個真實 SkillVersion aggregate（透過 publish factory 建立）
		var sv = SkillVersion.publish(new PublishVersionCommand(
				"agg-1", "1.0.0", "gs://b/x.zip", 100, 0, Map.of("name", "demo")));
		when(versionRepo.findBySkillIdAndVersion(eq("agg-1"), eq("1.0.0")))
				.thenReturn(Optional.of(sv));
		// save 直接 echo argument（aggregate state mutation 在傳入物件上完成）
		when(versionRepo.save(any(SkillVersion.class))).thenAnswer(inv -> inv.getArgument(0));
		return new Mocks(skillRepo, versionRepo);
	}

	private Map<String, Object> savedRiskAssessment(Mocks m) {
		var captor = ArgumentCaptor.forClass(SkillVersion.class);
		verify(m.versionRepo, atLeastOnce()).save(captor.capture());
		return captor.getValue().getRiskAssessment();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> riskReasons(Map<String, Object> riskAssessment) {
		return (List<Map<String, Object>>) riskAssessment.get("riskReasons");
	}

	@Test
	@DisplayName("AC-1.1: pipeline → skills.risk_level + SkillVersion.attachRiskAssessment 充血路徑")
	@Tag("AC-1")
	void pipelinePersistsRiskLevelAndAggregateState() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, new AnalysisOutput(
				List.of(new SecurityFinding("R1", Severity.HIGH, "x", "f", 1, "e", "pattern", "AST06")),
				List.of()));
		var meta = fakeAnalyzer("meta", Phase.META, AnalysisOutput.empty());

		var m = newMocks();
		var orch = buildOrchestrator(List.of(pattern, meta), m);
		orch.on(EVT);

		// 1. skills.risk_level updated
		verify(m.skillRepo, atLeastOnce()).updateRiskLevel(eq("agg-1"), anyString(), any(Instant.class));

		// 2. SkillVersion aggregate 充血 — versionRepo.save 攔截後 riskAssessment field 寫入 + SkillRiskAssessedEvent registered
		var captor = ArgumentCaptor.forClass(SkillVersion.class);
		verify(m.versionRepo, atLeastOnce()).save(captor.capture());
		var saved = captor.getValue();
		assertThat(saved.getRiskAssessment()).isNotNull();
		assertThat(saved.getRiskAssessment().get("level")).isEqualTo("HIGH");
		assertThat(saved.getRiskAssessment().get("sourceEventId")).isEqualTo(EVT.sourceEventId());
	}

	@Test
	@DisplayName("AC-S190-7: package with scripts persists SCRIPTS_INCLUDED risk reason")
	void packageWithScriptsPersistsScriptsIncludedRiskReason() throws IOException {
		var m = newMocks();
		var orch = buildOrchestrator(List.of(), m, Map.of(
				"scripts/check_deps.sh", "#!/bin/sh\nexit 0\n",
				"scripts/transcribe.py", "print('ok')\n"));

		orch.on(EVT);

		var riskAssessment = savedRiskAssessment(m);
		assertThat(riskAssessment.get("level")).isEqualTo("LOW");
		assertThat(riskReasons(riskAssessment)).anySatisfy(reason -> {
			assertThat(reason.get("code")).isEqualTo("SCRIPTS_INCLUDED");
			assertThat((List<String>) reason.get("evidence"))
					.containsExactlyInAnyOrder("scripts/check_deps.sh", "scripts/transcribe.py");
			assertThat(reason.get("detail").toString()).contains("scripts/");
		});
	}

	@Test
	@DisplayName("AC-S190-1b: allowed-tools-only scan persists ALLOWED_TOOLS_DECLARED risk reason")
	void allowedToolsOnlyPersistsAllowedToolsRiskReason() throws IOException {
		var event = SkillVersionPublishedEvent.of("agg-1", "1.0.0", "gs://b/x.zip", 100,
				Map.of("name", "demo", "allowed-tools", List.of("Read", "Glob", "Bash", "Write")),
				java.util.List.of());
		var m = newMocks();
		var orch = buildOrchestrator(List.of(), m);

		orch.on(event);

		var riskAssessment = savedRiskAssessment(m);
		assertThat(riskAssessment.get("level")).isEqualTo("LOW");
		assertThat(riskReasons(riskAssessment)).anySatisfy(reason -> {
			assertThat(reason.get("code")).isEqualTo("ALLOWED_TOOLS_DECLARED");
			assertThat(reason.get("detail").toString()).contains("這個技能可以要求 AI 使用工具");
			assertThat((List<String>) reason.get("evidence"))
					.containsExactly("Read", "Glob", "Bash", "Write");
		});
	}

	@Test
	@DisplayName("AC-S190-3: pure docs scan persists NO_FINDINGS_NO_CAPABILITIES reason")
	void pureDocsPersistsNoFindingsNoCapabilitiesReason() throws IOException {
		var m = newMocks();
		var orch = buildOrchestrator(List.of(), m);

		orch.on(EVT);

		var riskAssessment = savedRiskAssessment(m);
		assertThat(riskAssessment.get("level")).isEqualTo("NONE");
		assertThat(riskReasons(riskAssessment)).singleElement().satisfies(reason -> {
			assertThat(reason.get("code")).isEqualTo("NO_FINDINGS_NO_CAPABILITIES");
			assertThat(reason.get("detail").toString()).contains("未發現安全問題");
			assertThat(reason.get("action")).isEqualTo("DOWNLOAD_OK");
		});
	}

	@Test
	@DisplayName("AC-S190-4: findings scan persists FINDINGS_PRESENT reason")
	void findingsPersistFindingsPresentRiskReason() throws IOException {
		var finding = new SecurityFinding(
				"W008_HARDCODED_SECRET",
				SkillIssueCode.W008,
				Severity.HIGH,
				"Hardcoded secret",
				"Remove the secret.",
				Confidence.HIGH,
				"SKILL.md",
				7,
				"ghp_...1234",
				"secret",
				"AST01");
		var analyzer = fakeAnalyzer("secret", Phase.STATIC, new AnalysisOutput(List.of(finding), List.of()));

		var m = newMocks();
		var orch = buildOrchestrator(List.of(analyzer), m);

		orch.on(EVT);

		var riskAssessment = savedRiskAssessment(m);
		assertThat(riskAssessment.get("level")).isEqualTo("HIGH");
		assertThat(riskReasons(riskAssessment)).anySatisfy(reason -> {
			assertThat(reason.get("code")).isEqualTo("FINDINGS_PRESENT");
			assertThat(reason.get("impact")).isEqualTo("HIGH");
			assertThat((List<String>) reason.get("evidence")).contains("W008");
			assertThat(reason.get("action")).isEqualTo("FIX_REQUIRED");
		});
	}

	@Test
	@DisplayName("AC-1.1: max severity rule — 任一 HIGH → finalLevel HIGH")
	@Tag("AC-1")
	void maxSeverityHigh() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, new AnalysisOutput(
				List.of(new SecurityFinding("R1", Severity.MEDIUM, "x", "f", 1, "e", "pattern", "AST06")),
				List.of()));
		var secret = fakeAnalyzer("secret", Phase.STATIC, new AnalysisOutput(
				List.of(new SecurityFinding("R2", Severity.HIGH, "x", "f", 2, "e", "secret", "AST01")),
				List.of()));

		var m = newMocks();
		var orch = buildOrchestrator(List.of(pattern, secret), m);
		orch.on(EVT);

		verify(m.skillRepo, atLeastOnce()).updateRiskLevel(eq("agg-1"), eq("HIGH"), any(Instant.class));
	}

	@Test
	@DisplayName("AC-1.1: 無 finding → finalLevel NONE（per S096c 4-tier RiskLevel）")
	@Tag("AC-1")
	void noFindingsLow() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, AnalysisOutput.empty());

		var m = newMocks();
		var orch = buildOrchestrator(List.of(pattern), m);
		orch.on(EVT);

		verify(m.skillRepo, atLeastOnce()).updateRiskLevel(eq("agg-1"), eq("NONE"), any(Instant.class));
	}

	@Test
	@DisplayName("AC-1.2: Phase 1 並行（virtual threads）— 3 個 100ms engines 總時 < 250ms")
	@Tag("AC-1")
	void phase1ParallelExecution() throws IOException {
		var a = slowAnalyzer("a", Phase.STATIC, 100);
		var b = slowAnalyzer("b", Phase.STATIC, 100);
		var c = slowAnalyzer("c", Phase.STATIC, 100);

		var m = newMocks();
		var orch = buildOrchestrator(List.of(a, b, c), m);

		long start = System.currentTimeMillis();
		orch.on(EVT);
		long elapsed = System.currentTimeMillis() - start;

		// 並行：應接近 100ms（max + overhead）；序列會 ~300ms
		assertThat(elapsed).isLessThan(250);
	}

	@Test
	@DisplayName("AC-1.3: 一個 engine 拋例外，其他 engine 結果不丟 + 不向上拋")
	@Tag("AC-1")
	void engineFailureIsolated() throws IOException {
		var failing = throwingAnalyzer("pattern", Phase.STATIC);
		var ok = fakeAnalyzer("secret", Phase.STATIC, new AnalysisOutput(
				List.of(new SecurityFinding("R", Severity.HIGH, "x", "f", 1, "e", "secret", "AST01")),
				List.of()));

		var m = newMocks();
		var orch = buildOrchestrator(List.of(failing, ok), m);

		// 不應拋例外
		orch.on(EVT);

		// secret 的 finding 仍寫入 — 透過驗 skill_repo 寫進 HIGH
		verify(m.skillRepo, atLeastOnce()).updateRiskLevel(eq("agg-1"), eq("HIGH"), any(Instant.class));
	}

	@Test
	@DisplayName("AC-S173-2: analyzer Error is isolated and does not abort scan pipeline")
	@Tag("AC-S173-2")
	void analyzerErrorIsolated() throws IOException {
		var failing = errorAnalyzer("llm-judge", Phase.LLM);
		var ok = fakeAnalyzer("meta", Phase.META, new AnalysisOutput(
				List.of(new SecurityFinding("R", Severity.HIGH, "x", "f", 1, "e", "meta", "AST08")),
				List.of()));

		var m = newMocks();
		var orch = buildOrchestrator(List.of(failing, ok), m);

		orch.on(EVT);

		verify(m.skillRepo, atLeastOnce()).updateRiskLevel(eq("agg-1"), eq("HIGH"), any(Instant.class));
	}

	@Test
	@DisplayName("AC-2.1: 關閉的 engine 不在 SARIF runs[]（透過攔截 SkillVersion aggregate riskAssessment 驗證）")
	@Tag("AC-2")
	void disabledEngineNotInSarif() throws IOException {
		// 只啟用 pattern + meta；模擬 metadata bean 未建立
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, AnalysisOutput.empty());
		var meta = fakeAnalyzer("meta", Phase.META, AnalysisOutput.empty());

		var m = newMocks();
		var orch = buildOrchestrator(List.of(pattern, meta), m);
		orch.on(EVT);

		var captor = ArgumentCaptor.forClass(SkillVersion.class);
		verify(m.versionRepo, atLeastOnce()).save(captor.capture());
		var saved = captor.getValue();
		var sarif = (Map<?, ?>) saved.getRiskAssessment().get("sarif");
		assertThat(sarif).isNotNull();
		var runs = (List<?>) sarif.get("runs");
		assertThat(runs).hasSize(2);  // pattern + meta only — metadata not present
	}

	@Test
	@DisplayName("AC-2: idempotency — sourceEventId 已掃描 → skip pipeline（versionRepo.save 不被呼叫）")
	@Tag("AC-2")
	void idempotencySkipsAlreadyScanned() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, AnalysisOutput.empty());

		var m = newMocks();
		when(m.versionRepo.hasRiskAssessmentFromEvent(eq("agg-1"), eq("1.0.0"), anyString()))
				.thenReturn(true);  // 已掃描過
		var orch = buildOrchestrator(List.of(pattern), m);

		orch.on(EVT);

		// pipeline 應跳過 — skillRepo / versionRepo.save 都不被呼叫
		verify(m.skillRepo, never()).updateRiskLevel(anyString(), anyString(), any(Instant.class));
		verify(m.versionRepo, never()).save(any(SkillVersion.class));
	}

	@Test
	@DisplayName("AnalyzerOrder: STATIC 並行 → LLM 序列 → META 序列；META 看到 phase1 + phase2 findings")
	void analyzerExecutionOrder() throws IOException {
		var visitOrder = new AtomicInteger();
		var staticOrder = new int[1];
		var metaOrder = new int[1];

		var staticEng = new SecurityAnalyzer() {
			@Override public String name() { return "pattern"; }
			@Override public Phase phase() { return Phase.STATIC; }
			@Override public AnalysisOutput analyze(ScanContext ctx) {
				staticOrder[0] = visitOrder.incrementAndGet();
				return new AnalysisOutput(List.of(
						new SecurityFinding("R", Severity.LOW, "x", "f", 1, "e", "pattern", null)
				), List.of());
			}
		};
		var metaEng = new SecurityAnalyzer() {
			@Override public String name() { return "meta"; }
			@Override public Phase phase() { return Phase.META; }
			@Override public AnalysisOutput analyze(ScanContext ctx) {
				metaOrder[0] = visitOrder.incrementAndGet();
				// META 應看到 phase1 finding
				assertThat(ctx.phase1Findings()).hasSizeGreaterThan(0);
				return AnalysisOutput.empty();
			}
		};

		var m = newMocks();
		var orch = buildOrchestrator(List.of(staticEng, metaEng), m);
		orch.on(EVT);

		assertThat(staticOrder[0]).isLessThan(metaOrder[0]);
	}
}
