package io.github.samzhu.skillshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.github.samzhu.skillshub.shared.api.FlagNotFoundException;
import io.github.samzhu.skillshub.shared.api.InvalidStatusTransitionException;

/**
 * S098e3-T01 — FlagService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>涵蓋 AC：
 * <ul>
 *   <li>AC-1：createFlag with reporter identity</li>
 *   <li>AC-2：anonymous fallback when reporter null/blank</li>
 *   <li>AC-6：updateStatus OPEN → RESOLVED happy path</li>
 *   <li>AC-7：invalid transition (RESOLVED → OPEN; unknown status)</li>
 *   <li>AC-8：updateStatus on non-existent flag → FlagNotFoundException</li>
 * </ul>
 *
 * <p>AC-3/4/5 cross-skill list / per-skill status filter 由 controller slice
 * test (FlagControllerTest + FlagAdminQueryControllerTest) 涵蓋；service 層 query
 * methods 純 derived query 無 business logic。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlagServiceTest {

    @Autowired
    private FlagService service;

    @Autowired
    private FlagReadModelRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM flags");
        jdbc.update("DELETE FROM domain_events");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: createFlag 帶 reporter → DB reportedBy 為 alice")
    void createFlag_withReporter_persistsAlice() {
        var skillId = insertSkill("alice");

        var flagId = service.createFlag(skillId, "malicious", "含後門", "alice");

        var flag = repo.findById(flagId).orElseThrow();
        assertThat(flag.reportedBy()).isEqualTo("alice");
        assertThat(flag.status()).isEqualTo("OPEN");
        assertThat(flag.type()).isEqualTo("malicious");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: createFlag reporter null/blank → anonymous fallback")
    void createFlag_nullReporter_fallsBackAnonymous() {
        var skillId = insertSkill("alice");

        var flagId1 = service.createFlag(skillId, "spam", "x", null);
        var flagId2 = service.createFlag(skillId, "spam", "y", "  ");

        assertThat(repo.findById(flagId1).orElseThrow().reportedBy()).isEqualTo("anonymous");
        assertThat(repo.findById(flagId2).orElseThrow().reportedBy()).isEqualTo("anonymous");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: updateStatus OPEN → RESOLVED 成功 + DB 更新")
    void updateStatus_openToResolved_success() {
        var skillId = insertSkill("alice");
        var flagId = service.createFlag(skillId, "malicious", "x", "alice");

        service.updateStatus(flagId, "RESOLVED", "reviewer-1");

        assertThat(repo.findById(flagId).orElseThrow().status()).isEqualTo("RESOLVED");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: updateStatus OPEN → DISMISSED 成功")
    void updateStatus_openToDismissed_success() {
        var skillId = insertSkill("alice");
        var flagId = service.createFlag(skillId, "malicious", "x", "alice");

        service.updateStatus(flagId, "DISMISSED", "reviewer-1");

        assertThat(repo.findById(flagId).orElseThrow().status()).isEqualTo("DISMISSED");
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: updateStatus RESOLVED → OPEN 拒絕（terminal 不可逆）")
    void updateStatus_resolvedToOpen_rejected() {
        var skillId = insertSkill("alice");
        var flagId = service.createFlag(skillId, "malicious", "x", "alice");
        service.updateStatus(flagId, "RESOLVED", "reviewer-1");

        assertThatThrownBy(() -> service.updateStatus(flagId, "OPEN", "reviewer-2"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: updateStatus 未知 status name 拒絕")
    void updateStatus_unknownStatus_rejected() {
        var skillId = insertSkill("alice");
        var flagId = service.createFlag(skillId, "malicious", "x", "alice");

        assertThatThrownBy(() -> service.updateStatus(flagId, "BOGUS", "reviewer-1"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("unknown flag status");
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: updateStatus on 不存在 flag → FlagNotFoundException")
    void updateStatus_notFound_throws() {
        assertThatThrownBy(() -> service.updateStatus("non-existent-uuid", "RESOLVED", "reviewer-1"))
                .isInstanceOf(FlagNotFoundException.class);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 / AC-4: listAllFlags filter by status / no filter")
    void listAllFlags_filterAndAll() {
        var sk1 = insertSkill("alice");
        var sk2 = insertSkill("bob");
        var f1 = service.createFlag(sk1, "spam", "a", "u1");
        service.createFlag(sk2, "spam", "b", "u2");
        // 把 f1 移為 RESOLVED
        service.updateStatus(f1, "RESOLVED", "reviewer-1");

        // AC-3: status=OPEN 過濾 → 只剩 sk2 那筆
        var openFlags = service.listAllFlags("OPEN");
        assertThat(openFlags).hasSize(1);
        assertThat(openFlags.getFirst().skillId()).isEqualTo(sk2);

        // AC-4: 無 filter → 全部 2 筆
        var allFlags = service.listAllFlags(null);
        assertThat(allFlags).hasSize(2);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: getFlagsBySkillId 加 status filter")
    void getFlagsBySkillId_withStatusFilter() {
        var sk1 = insertSkill("alice");
        var f1 = service.createFlag(sk1, "spam", "a", "u1");
        service.createFlag(sk1, "spam", "b", "u2");
        service.updateStatus(f1, "RESOLVED", "reviewer-1");

        // 只 OPEN
        var openFlags = service.getFlagsBySkillId(sk1, "OPEN");
        assertThat(openFlags).hasSize(1);

        // 無 filter
        var allFlags = service.getFlagsBySkillId(sk1, null);
        assertThat(allFlags).hasSize(2);

        // 既有 single-arg 行為相容
        assertThat(service.getFlagsBySkillId(sk1)).hasSize(2);
    }

    private String insertSkill(String author) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, '測試 skill', ?, 'Test', 'PUBLISHED', 0, ?, ?, ?)
                """,
                id, "skill-" + id.substring(0, 8), author,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), author);
        return id;
    }
}
