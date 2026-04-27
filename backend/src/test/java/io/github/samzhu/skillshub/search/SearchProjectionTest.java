package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.query.SkillReadModel;
import io.github.samzhu.skillshub.skill.query.SkillReadModelRepository;

/**
 * AC-3, AC-4 驗證：SearchProjection 監聽領域事件並透過 {@link SkillshubPgVectorStore}
 * 寫入 vector_store 表（owner / skill_id 隨 6-欄 INSERT 一次寫入）。
 *
 * <p>T8：因 SearchProjection 改為 inline 建構 {@link SkillshubPgVectorStore}（per-request
 * builder pattern），unit-level mock 困難 → 改用 @SpringBootTest + Testcontainers
 * 真實 PgVector container 整合驗證；斷言 SQL state 而非 mock 互動。
 *
 * <p>**FK 前置**：vector_store.skill_id REFERENCES skills(id) ON DELETE CASCADE。
 * 每個 test 用 SkillReadModelRepository 寫一筆 skills row 滿足 FK。
 *
 * @see SearchProjection
 * @see SkillshubPgVectorStore
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchProjectionTest {

    @Autowired private SearchProjection projection;
    @Autowired private SkillReadModelRepository skillRepo;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentUserProvider currentUserProvider;

    private String skillId;
    private String skillName;

    @BeforeEach
    void setUp() {
        // 每個 test 用獨立 UUID + 隨機 name（skills.name 有 UNIQUE constraint，避免跨測試衝突）
        skillId = UUID.randomUUID().toString();
        skillName = "docker-helper-" + UUID.randomUUID();
        var now = Instant.now();
        // FK skill_id → skills.id 前置
        skillRepo.save(new SkillReadModel(
                skillId, skillName, "管理 Docker 容器", "sam", "DevOps",
                null, null, "DRAFT", 0L, now, now));
        when(currentUserProvider.userId()).thenReturn("test-owner");
    }

    @Test
    @DisplayName("AC-3: SkillCreatedEvent → vector_store row 含正確 metadata + owner + skill_id")
    @Tag("AC-3")
    void onSkillCreated_writesRowWithMetadataOwnerAndSkillId() {
        var event = new SkillCreatedEvent(skillId, skillName, "管理 Docker 容器", "sam", "DevOps");

        projection.onSkillCreated(event);

        var row = jdbc.queryForMap(
                "SELECT content, owner, skill_id FROM vector_store WHERE id = ?::uuid", skillId);
        assertThat(row.get("content")).isEqualTo(skillName + " 管理 Docker 容器");
        assertThat(row.get("owner")).isEqualTo("test-owner");
        assertThat(row.get("skill_id")).isEqualTo(skillId);
    }

    @Test
    @DisplayName("AC-4: SkillVersionPublishedEvent → delete+add 重寫 row 為新版本 frontmatter")
    @Tag("AC-4")
    void onVersionPublished_deletesAndReWritesWithFrontmatter() {
        // 先用 onSkillCreated seed 一筆初版
        projection.onSkillCreated(
                new SkillCreatedEvent(skillId, skillName, "舊描述", "sam", "DevOps"));

        var frontmatter = Map.<String, Object>of(
                "name", skillName,
                "description", "新版：管理 Docker 容器與 Compose",
                "author", "sam",
                "category", "DevOps");
        var event = new SkillVersionPublishedEvent(
                skillId, "2.0.0", "skills/" + skillId + "/2.0.0.zip", 1024L, frontmatter);

        projection.onVersionPublished(event);

        // delete-then-add 後：應該只有 1 row（同 id）但 content 已更新為新版描述
        var rows = jdbc.queryForList(
                "SELECT content, owner, skill_id FROM vector_store WHERE id = ?::uuid", skillId);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("content")).isEqualTo(skillName + " 新版：管理 Docker 容器與 Compose");
        assertThat(row.get("owner")).isEqualTo("test-owner");
        assertThat(row.get("skill_id")).isEqualTo(skillId);
    }

    @Test
    @DisplayName("AC-3: 多個 SkillCreatedEvent → 各自獨立 row（per-request instance 不共用 owner state）")
    @Tag("AC-3")
    void onSkillCreated_multipleSkillsHaveIndependentOwnerState() {
        // 第二個 skill 用不同 owner（驗證 per-request builder 的 owner 真的鎖在 instance 裡，
        // 不會被前一次寫入污染）
        var skillId2 = UUID.randomUUID().toString();
        var skillName2 = "k8s-helper-" + UUID.randomUUID();
        skillRepo.save(new SkillReadModel(
                skillId2, skillName2, "管理 K8s", "jane", "DevOps",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now()));

        // 第 1 次寫入：owner=test-owner（setUp 預設）
        projection.onSkillCreated(new SkillCreatedEvent(skillId, skillName, "...", "sam", "DevOps"));

        // 第 2 次寫入：mock 切換 owner
        when(currentUserProvider.userId()).thenReturn("other-owner");
        projection.onSkillCreated(new SkillCreatedEvent(skillId2, skillName2, "...", "jane", "DevOps"));

        // 驗證兩個 row 各自帶不同 owner（per-request isolation）
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM vector_store WHERE id = ?::uuid", String.class, skillId))
                .isEqualTo("test-owner");
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM vector_store WHERE id = ?::uuid", String.class, skillId2))
                .isEqualTo("other-owner");
    }
}
