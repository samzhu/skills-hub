package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.NoSuchElementException;
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
import io.github.samzhu.skillshub.shared.api.BundleNotPublishedException;

/**
 * S098a3-2 — BundleInfoQueryService Testcontainers 整合測試。
 *
 * <p>對齊 NotificationServiceTest / CollectionServiceTest 既驗 pattern。涵蓋 AC：
 * 1（happy path 真值對齊）/ 2（skill 不存在 → NoSuchElementException → 404）/
 * 3（無 published version → BundleNotPublishedException → 404 distinct error code）/
 * 5（既有 row file_count=0 fallback path）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BundleInfoQueryServiceTest {

    @Autowired
    private BundleInfoQueryService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: bundle-info happy path → filename derive + fileSize + fileCount + uploadedAt")
    void get_happyPath() {
        var skillId = insertSkill("auth-helper", "PUBLISHED", "1.2.0");
        var publishedAt = insertSkillVersion(skillId, "1.2.0", 12853L, 5);

        var result = service.get(skillId);

        assertThat(result.filename()).isEqualTo("auth-helper-1.2.0.zip");
        assertThat(result.fileSize()).isEqualTo(12853L);
        assertThat(result.fileCount()).isEqualTo(5);
        assertThat(result.uploadedAt()).isEqualTo(publishedAt);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: skill 不存在 → NoSuchElementException → 既有 NOT_FOUND 路徑")
    void get_skillNotFound() {
        assertThatThrownBy(() -> service.get("non-existent-id"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Skill not found");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: skill DRAFT 無 latestVersion → BundleNotPublishedException distinct error")
    void get_skillDraftNoVersion() {
        var skillId = insertSkillNoVersion("draft-skill", "DRAFT");

        assertThatThrownBy(() -> service.get(skillId))
                .isInstanceOf(BundleNotPublishedException.class)
                .hasMessageContaining("bundle_not_published");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 corner: latestVersion 為 blank string → 同 DRAFT 路徑")
    void get_blankLatestVersion() {
        var skillId = insertSkillNoVersion("blank-version", "PUBLISHED");
        // 顯式設 latestVersion 為空字串（極端 corner case）
        jdbc.update("UPDATE skills SET latest_version = '' WHERE id = ?", skillId);

        assertThatThrownBy(() -> service.get(skillId))
                .isInstanceOf(BundleNotPublishedException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: legacy row file_count=0 → 仍回應；frontend 據 0 hide 該欄")
    void get_legacyRowFileCountZero() {
        var skillId = insertSkill("legacy-skill", "PUBLISHED", "1.0.0");
        insertSkillVersion(skillId, "1.0.0", 5000L, 0);  // file_count=0 模擬 V13 migration default

        var result = service.get(skillId);

        assertThat(result.fileCount()).isZero();
        assertThat(result.fileSize()).isEqualTo(5000L);
        assertThat(result.filename()).isEqualTo("legacy-skill-1.0.0.zip");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: skill latestVersion 指向不存在的 SkillVersion row → BundleNotPublishedException")
    void get_orphanLatestVersionPointer() {
        var skillId = insertSkill("orphan-pointer", "PUBLISHED", "9.9.9");
        // skill 標 latestVersion=9.9.9 但 skill_versions 沒對應 row（資料漂移情境）

        assertThatThrownBy(() -> service.get(skillId))
                .isInstanceOf(BundleNotPublishedException.class);
    }

    private String insertSkill(String name, String status, String latestVersion) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, latest_version,
                                    download_count, created_at, updated_at)
                VALUES (?, ?, '測試 skill', 'alice', 'Test', ?, ?, 0, ?, ?)
                """,
                id, name, status, latestVersion,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private String insertSkillNoVersion(String name, String status) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status,
                                    download_count, created_at, updated_at)
                VALUES (?, ?, '測試 skill', 'alice', 'Test', ?, 0, ?, ?)
                """,
                id, name, status,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private Instant insertSkillVersion(String skillId, String version, long fileSize, int fileCount) {
        var id = UUID.randomUUID().toString();
        var publishedAt = Instant.parse("2026-04-30T10:00:00Z");
        jdbc.update("""
                INSERT INTO skill_versions (id, skill_id, version, storage_path, file_size, file_count,
                                            frontmatter, published_at, allowed_tools)
                VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, '[]'::jsonb)
                """,
                id, skillId, version, "skills/" + skillId + "/" + version + "/skill.zip",
                fileSize, fileCount, java.sql.Timestamp.from(publishedAt));
        return publishedAt;
    }
}
