package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S023-T04 — 驗證 {@link ScanOrchestrator#on} 對重複觸發的 idempotency 行為。
 *
 * <p>對應 S023 spec §3 AC-4 idempotency 部分：相同 {@code sourceEventId} 重投時
 * {@code SkillRiskAssessed} 不重複寫入 domain_events。
 *
 * <p>測試策略：pre-seed skill + skill_versions row，並在 risk_assessment jsonb 內
 * 預植 {@code "sourceEventId": <uuid>}。呼叫 {@code orchestrator.on(event)} 攜帶相同
 * sourceEventId，期望 listener 內部 idempotency check 觸發 early return — 整個
 * scan pipeline 不執行，domain_events 對應 SkillRiskAssessed 計數不增。
 */
/**
 * S025b T02 — {@code @SpringBootTest} → {@code @ApplicationModuleTest(DIRECT_DEPENDENCIES)}：
 * security module slice 自動載 ScanOrchestrator + 全部 SecurityAnalyzer + PackageService +
 * SarifReporter；test 直接 sync 呼叫 {@code orchestrator.on(...)} 觸發 idempotency early return，
 * 無跨 module event flow。
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class ScanOrchestratorIdempotencyTest {

	@Autowired
	private ScanOrchestrator orchestrator;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("AC-4: 重投同一 sourceEventId 不重新觸發 scan，無新 SkillRiskAssessed event")
	@Tag("AC-4")
	void duplicateSourceEventId_skippedByIdempotencyCheck() {
		var skillId = UUID.randomUUID().toString();
		var version = "1.0.0";
		var sourceEventId = UUID.randomUUID().toString();
		var ts = Timestamp.from(Instant.now());

		// 1. seed skills row
		jdbc.update("""
				INSERT INTO skills (id, name, description, author, category, status,
				                    download_count, created_at, updated_at, acl_entries)
				VALUES (?, ?, 'desc', 'tester', 'cat', 'PUBLISHED', 0, ?, ?, '[]'::jsonb)
				""", skillId, "scan-idem-test-" + skillId.substring(0, 8), ts, ts);

		// 2. seed skill_versions row with risk_assessment 已含 sourceEventId
		var riskAssessmentJson = """
				{"level":"LOW","findings":[],"notices":[],"sarif":{},
				 "scannedAt":"2026-04-29T08:00:00Z","sourceEventId":"%s"}
				""".formatted(sourceEventId);
		jdbc.update("""
				INSERT INTO skill_versions (id, skill_id, version, storage_path, file_size,
				                            frontmatter, risk_assessment, published_at, allowed_tools)
				VALUES (?, ?, ?, 'gs://bucket/skill.zip', 100,
				        '{}'::jsonb, CAST(? AS jsonb), ?, '[]'::jsonb)
				""", UUID.randomUUID().toString(), skillId, version, riskAssessmentJson, ts);

		// 3. record current SkillRiskAssessed count for this aggregate (baseline)
		var baselineCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM domain_events WHERE aggregate_id = ? AND event_type = 'SkillRiskAssessed'",
				Integer.class, skillId);

		// 4. call orchestrator.on() with the same sourceEventId
		var event = new SkillVersionPublishedEvent(
				skillId, version, "gs://bucket/skill.zip", 100,
				Map.<String, Object>of(), List.<String>of(), sourceEventId);
		orchestrator.on(event);

		// 5. verify NO new SkillRiskAssessed was appended (idempotency check fired early-return)
		var afterCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM domain_events WHERE aggregate_id = ? AND event_type = 'SkillRiskAssessed'",
				Integer.class, skillId);

		assertThat(afterCount)
				.as("idempotency 應 early return；SkillRiskAssessed 計數不增")
				.isEqualTo(baselineCount);
	}
}
