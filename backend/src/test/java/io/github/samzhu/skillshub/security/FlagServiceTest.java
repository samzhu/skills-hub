package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S112 AC-6 + AC-7 — {@code FlagService.countOpenFlagsForAuthor} 跨表 SQL 計數行為。
 *
 * <p>整合測試（Testcontainers + 真實 PostgreSQL）：用 raw SQL 種 skill + flag fixture，
 * 驗 query 同時過濾 user（{@code skills.author=:author}）與 status（{@code flags.status='OPEN'}
 * + {@code skills.status='PUBLISHED'}）兩條件。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlagServiceTest {

	@Autowired
	private FlagService flagService;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void cleanup() {
		// 清舊資料（其他測試可能殘留）— flags ON DELETE CASCADE skill，故 skills 刪先後會帶清 flags
		jdbc.update("DELETE FROM flags");
		jdbc.update("DELETE FROM skill_versions");
		jdbc.update("DELETE FROM skills");
	}

	@Test
	@Tag("AC-6")
	@DisplayName("AC-6: countOpenFlagsForAuthor 過濾 user + PUBLISHED status")
	void countOpenFlagsForAuthor_filtersUserAndPublishedStatus() {
		// alice：1 個 PUBLISHED skill 含 1 個 OPEN flag（**應計入**）
		var aliceSkillId = insertSkill("alice", "PUBLISHED");
		insertFlag(aliceSkillId, "OPEN");
		// bob：1 個 PUBLISHED skill 含 5 個 OPEN flags（**不應計入** — 不同 author）
		var bobSkillId = insertSkill("bob", "PUBLISHED");
		for (int i = 0; i < 5; i++) {
			insertFlag(bobSkillId, "OPEN");
		}
		// alice：1 個 DRAFT skill 含 3 個 OPEN flags（**不應計入** — DRAFT status）
		var aliceDraftId = insertSkill("alice", "DRAFT");
		for (int i = 0; i < 3; i++) {
			insertFlag(aliceDraftId, "OPEN");
		}

		long count = flagService.countOpenFlagsForAuthor("alice");

		assertThat(count).isEqualTo(1L);
	}

	@Test
	@Tag("AC-7")
	@DisplayName("AC-7: 無 PUBLISHED skill 回 0 不丟 error")
	void countOpenFlagsForAuthor_zeroSkills_returnsZero() {
		// alice 只有 DRAFT skill（無 PUBLISHED），仍 query 不該丟 NPE / SQL error
		var draftId = insertSkill("alice", "DRAFT");
		insertFlag(draftId, "OPEN");

		long count = flagService.countOpenFlagsForAuthor("alice");

		assertThat(count).isEqualTo(0L);
	}

	@Test
	@Tag("AC-7")
	@DisplayName("AC-7: 無資料 author 回 0 不丟 error")
	void countOpenFlagsForAuthor_unknownAuthor_returnsZero() {
		// 完全沒有 carol 的 skill — 預期 0 而非 null / NPE
		long count = flagService.countOpenFlagsForAuthor("carol");

		assertThat(count).isEqualTo(0L);
	}

	private String insertSkill(String author, String status) {
		var id = UUID.randomUUID().toString();
		// name UNIQUE — 用 id 後綴避免衝突
		jdbc.update("""
				INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at)
				VALUES (?, ?, '測試 skill', ?, 'Test', ?, 0, ?, ?)
				""",
				id,
				"skill-" + id.substring(0, 8),
				author,
				status,
				java.sql.Timestamp.from(Instant.now()),
				java.sql.Timestamp.from(Instant.now()));
		return id;
	}

	private void insertFlag(String skillId, String status) {
		jdbc.update("""
				INSERT INTO flags (id, skill_id, type, description, reported_by, created_at, status)
				VALUES (?, ?, 'spam', '測試 flag', 'anonymous', ?, ?)
				""",
				UUID.randomUUID().toString(),
				skillId,
				java.sql.Timestamp.from(Instant.now()),
				status);
	}
}
