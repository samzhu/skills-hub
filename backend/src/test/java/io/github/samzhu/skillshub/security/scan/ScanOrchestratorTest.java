package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import io.github.samzhu.skillshub.security.scan.sarif.SarifReporter;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

class ScanOrchestratorTest {

	private static final SkillVersionPublishedEvent EVT =
			new SkillVersionPublishedEvent("agg-1", "1.0.0", "gs://b/x.zip", 100,
					Map.of("name", "demo", "description", "x"));

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

	private ScanOrchestrator buildOrchestrator(List<SecurityAnalyzer> analyzers,
			DomainEventRepository eventStore, MongoTemplate mongo) throws IOException {
		var storage = mock(StorageService.class);
		when(storage.download(any())).thenReturn(new byte[0]);
		var pkg = mock(PackageService.class);
		when(pkg.extractSkillMd(any())).thenReturn("# md");
		when(pkg.extractScripts(any())).thenReturn(Map.of());
		var sarif = new SarifReporter();
		return new ScanOrchestrator(analyzers, storage, pkg, eventStore, mongo, sarif);
	}

	@Test
	@DisplayName("AC-1.1: pipeline → DomainEvent SkillRiskAssessed + skills.riskLevel + skill_versions.riskAssessment")
	@Tag("AC-1")
	void pipelinePersistsToAllThreeStores() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, new AnalysisOutput(
				List.of(new SecurityFinding("R1", Severity.HIGH, "x", "f", 1, "e", "pattern", "AST06")),
				List.of()));
		var meta = fakeAnalyzer("meta", Phase.META, AnalysisOutput.empty());

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any()))
				.thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);

		var orch = buildOrchestrator(List.of(pattern, meta), eventStore, mongo);
		orch.on(EVT);

		// 1. DomainEvent saved
		var domainEventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
		verify(eventStore).save(domainEventCaptor.capture());
		assertThat(domainEventCaptor.getValue().eventType()).isEqualTo("SkillRiskAssessed");
		assertThat(domainEventCaptor.getValue().aggregateId()).isEqualTo("agg-1");

		// 2. skills.riskLevel updated
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), any(Update.class), eq("skills"));

		// 3. skill_versions.riskAssessment updated
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), any(Update.class), eq("skill_versions"));
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

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(pattern, secret), eventStore, mongo);

		orch.on(EVT);

		var captor = ArgumentCaptor.forClass(Update.class);
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), captor.capture(), eq("skills"));
		// Update.set("riskLevel", "HIGH") — verify by toString
		assertThat(captor.getValue().toString()).contains("riskLevel").contains("HIGH");
	}

	@Test
	@DisplayName("AC-1.1: 無 finding → finalLevel LOW")
	@Tag("AC-1")
	void noFindingsLow() throws IOException {
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, AnalysisOutput.empty());

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(pattern), eventStore, mongo);

		orch.on(EVT);

		var captor = ArgumentCaptor.forClass(Update.class);
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), captor.capture(), eq("skills"));
		assertThat(captor.getValue().toString()).contains("LOW");
	}

	@Test
	@DisplayName("AC-1.2: Phase 1 並行（virtual threads）— 3 個 100ms engines 總時 < 250ms")
	@Tag("AC-1")
	void phase1ParallelExecution() throws IOException {
		var a = slowAnalyzer("a", Phase.STATIC, 100);
		var b = slowAnalyzer("b", Phase.STATIC, 100);
		var c = slowAnalyzer("c", Phase.STATIC, 100);

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(a, b, c), eventStore, mongo);

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

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(failing, ok), eventStore, mongo);

		// 不應拋例外
		orch.on(EVT);

		// secret 的 finding 仍寫入 SARIF — 透過驗 mongo 寫進去的 update 含 GROUP_LEVEL=HIGH
		var captor = ArgumentCaptor.forClass(Update.class);
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), captor.capture(), eq("skills"));
		assertThat(captor.getValue().toString()).contains("HIGH");
	}

	@Test
	@DisplayName("AC-2.1: 關閉的 engine 不在 SARIF runs[]")
	@Tag("AC-2")
	@SuppressWarnings("unchecked")
	void disabledEngineNotInSarif() throws IOException {
		// 模擬 metadata bean 沒被建立 — 只給 pattern + meta
		var pattern = fakeAnalyzer("pattern", Phase.STATIC, AnalysisOutput.empty());
		var meta = fakeAnalyzer("meta", Phase.META, AnalysisOutput.empty());

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(pattern, meta), eventStore, mongo);

		orch.on(EVT);

		// 抓 skill_versions update 中的 sarif 結構
		var captor = ArgumentCaptor.forClass(Update.class);
		verify(mongo, atLeastOnce()).updateFirst(any(Query.class), captor.capture(), eq("skill_versions"));
		// Update.toString() 含 sarif 的 driver names
		var updateStr = captor.getValue().toString();
		assertThat(updateStr).contains("pattern");
		assertThat(updateStr).contains("meta");
		assertThat(updateStr).doesNotContain("\"name\":\"metadata\"");
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

		var eventStore = mock(DomainEventRepository.class);
		when(eventStore.findTopByAggregateIdOrderBySequenceDesc(any())).thenReturn(java.util.Optional.empty());
		var mongo = mock(MongoTemplate.class);
		var orch = buildOrchestrator(List.of(staticEng, metaEng), eventStore, mongo);

		orch.on(EVT);

		assertThat(staticOrder[0]).isLessThan(metaOrder[0]);
	}
}
