package io.github.samzhu.skillshub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S023-T03 — 驗證 {@link DownloadEventRepository#saveIdempotent} 的 ON CONFLICT 行為。
 *
 * <p>對應 S023 spec §3 AC-3 idempotency 部分：相同 {@code eventId} 重投只產生 1 筆 row。
 * 此 test 直接驗證 SQL 層 idempotency；async listener pipeline 由 T05/T06 的 outbox
 * 整合測試覆蓋。
 *
 * <p>S025b T02 — extends {@link RepositorySliceTestBase}：pure repo test。
 * Note: skills row seed via raw {@code JdbcTemplate} 走 V1 schema FK 不需 cleanup（test-scoped UUID）。
 */
class DownloadEventRepositoryIdempotencyTest extends RepositorySliceTestBase {

	@Autowired
	private DownloadEventRepository repo;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("AC-3: 同一 eventId 連續 saveIdempotent 兩次只產生 1 筆 row")
	@Tag("AC-3")
	void duplicateEventId_onlyOneRowInserted() {
		var skillId = UUID.randomUUID().toString();
		var eventId = UUID.randomUUID().toString();
		var now = Instant.now();

		// 先 seed skills row 滿足 FK
		// PostgreSQL JDBC driver 不接受直接 bind Instant；轉 Timestamp
		var ts = Timestamp.from(now);
		jdbc.update("""
				INSERT INTO skills (id, name, description, author, category, status, download_count,
				                    created_at, updated_at, acl_entries, owner_id)
				VALUES (?, ?, 'desc', 'test', 'cat', 'PUBLISHED', 0, ?, ?, '[]'::jsonb, 'test')
				""", skillId, "idem-test-" + skillId.substring(0, 8), ts, ts);

		var firstAttempt = repo.saveIdempotent(
				UUID.randomUUID().toString(), skillId, "1.0.0", now, eventId);
		var secondAttempt = repo.saveIdempotent(
				UUID.randomUUID().toString(), skillId, "1.0.0", now, eventId);

		assertThat(firstAttempt).as("第一次寫入應 affected row=1").isEqualTo(1);
		assertThat(secondAttempt).as("第二次（同 eventId）應 affected row=0 — ON CONFLICT skip").isEqualTo(0);

		var rowCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM download_events WHERE event_id = ?",
				Integer.class, eventId);
		assertThat(rowCount).as("download_events 應只有 1 筆對應該 eventId 的 row").isEqualTo(1);
	}

	@Test
	@DisplayName("AC-3: 不同 eventId 連續 saveIdempotent 各產生 1 筆 row")
	@Tag("AC-3")
	void differentEventIds_independentRows() {
		var skillId = UUID.randomUUID().toString();
		var now = Instant.now();

		var ts = Timestamp.from(now);
		jdbc.update("""
				INSERT INTO skills (id, name, description, author, category, status, download_count,
				                    created_at, updated_at, acl_entries, owner_id)
				VALUES (?, ?, 'desc', 'test', 'cat', 'PUBLISHED', 0, ?, ?, '[]'::jsonb, 'test')
				""", skillId, "diff-evid-test-" + skillId.substring(0, 8), ts, ts);

		var rowsA = repo.saveIdempotent(UUID.randomUUID().toString(),
				skillId, "1.0.0", now, UUID.randomUUID().toString());
		var rowsB = repo.saveIdempotent(UUID.randomUUID().toString(),
				skillId, "1.0.0", now, UUID.randomUUID().toString());

		assertThat(rowsA).isEqualTo(1);
		assertThat(rowsB).isEqualTo(1);

		var totalForSkill = jdbc.queryForObject(
				"SELECT COUNT(*) FROM download_events WHERE skill_id = ?",
				Integer.class, skillId);
		assertThat(totalForSkill).as("不同 eventId 各自獨立 row").isEqualTo(2);
	}
}
