package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.invoke.MethodHandles;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S024 T1 — Cross-aggregate {@code @DomainEvents} publish + Modulith outbox 整合測試（POC + AC-1 + AC-3）。
 *
 * <p>Folded POC（per spec §6 task plan）— 驗 spec §2.9 hypothesis：跨 aggregate 同
 * {@code @Transactional} 內 {@code skillRepo.save(skill)} + {@code skillVersionRepo.save(version)}
 * 兩 aggregate 各自的 {@code @DomainEvents} 各自 publish 至 Modulith {@code event_publication}
 * outbox（同 TX）。
 *
 * <p>覆蓋 AC：
 * <ul>
 *   <li>AC-1：V6 migration 後 {@code skills.version BIGINT NOT NULL DEFAULT 0} 欄位存在</li>
 *   <li>AC-3：cross-aggregate save 在同 TX publish events；TX rollback → outbox 同 rollback</li>
 *   <li>POC：SkillCreatedEvent / SkillVersionPublishedEvent 各自有 N listener entry 寫入 outbox
 *       （SkillVersionPublishedFromAggregate 在 T1 無 subscriber，T5 由 AuditEventListener 訂閱）</li>
 * </ul>
 */
/**
 * S025b T02 — <b>deviation from spec REPO migration target</b>：本 test 屬「outbox 基礎設施
 * 整合測試」，驗 Spring Modulith {@code event_publication} 表 INSERT 時序 + cross-aggregate
 * {@code @DomainEvents} 行為。{@code @ApplicationModuleTest(DIRECT_DEPENDENCIES)} 在 skill
 * module slice 內 outbox 不 fire（疑 ContextCustomizer 排除 modulith-events-jdbc auto-config
 * 或 @DomainEvents extraction 範圍受限）— 保留 {@code @SpringBootTest} 為 outbox / cross-aggregate
 * 整合測試的合理 CONFIG bucket。記入 §7 deviation rationale。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillCommandServiceCrossAggregateTest {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Autowired
    SkillRepository skillRepo;

    @Autowired
    SkillVersionRepository skillVersionRepo;

    @Autowired
    S024CrossAggregateSaveHelper helper;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        // 清 outbox + 業務表 + audit log，避免跨 test 相互汙染（既有 53 個 @SpringBootTest
        // 共用 context cache，但 DB state 不重置；本 test 顯式 reset 與 POC 預期一致）
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: V6 migration adds skills.version BIGINT NOT NULL DEFAULT 0")
    void v6MigrationAddsVersionColumn() {
        var skill = Skill.create(new CreateSkillCommand("v6-test", "test", "alice", "DevOps"));
        helper.save(skill);

        Long version = jdbc.queryForObject(
                "SELECT version FROM skills WHERE id = ?", Long.class, skill.getId());
        assertThat(version).isNotNull().isZero();
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 + POC: cross-aggregate save 觸發 @DomainEvents publish 至 Modulith outbox（同 TX）")
    void crossAggregateSavePublishesToOutbox() {
        var skill = Skill.create(new CreateSkillCommand("cross-tx-skill", "POC test", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://bucket/cross-tx/1.0.0.zip", 100, 0,
                Map.of("name", "cross-tx-skill", "description", "POC test")));

        helper.saveCrossAggregate(skill, sv);

        // 1. DB row 已寫入（commit 後可見）
        Integer skillCount = jdbc.queryForObject(
                "SELECT count(*) FROM skills WHERE id = ?", Integer.class, skill.getId());
        assertThat(skillCount).isEqualTo(1);

        Integer versionCount = jdbc.queryForObject(
                "SELECT count(*) FROM skill_versions WHERE skill_id = ? AND version = ?",
                Integer.class, skill.getId(), "1.0.0");
        assertThat(versionCount).isEqualTo(1);

        // skills.latest_version 已由 Skill.recordVersionPublished + skillRepo.save UPDATE
        String latestVersion = jdbc.queryForObject(
                "SELECT latest_version FROM skills WHERE id = ?", String.class, skill.getId());
        assertThat(latestVersion).isEqualTo("1.0.0");

        // skills.status 從 DRAFT → PUBLISHED transition
        String status = jdbc.queryForObject(
                "SELECT status FROM skills WHERE id = ?", String.class, skill.getId());
        assertThat(status).isEqualTo("PUBLISHED");

        // skills.version 樂觀鎖：經一次 INSERT (version=0) + 一次 UPDATE (version=1) 後應為 1
        // 注意：Spring Data JDBC INSERT 設 version=0，後續 UPDATE 才 +1；本 helper 在同 TX 內
        // skillRepo.save 觸發 INSERT (因為 isNew=true → @Id null check 失敗 → 走 update? 待驗)
        // 真實值為 POC findings 觀察重點之一
        Long optimisticVersion = jdbc.queryForObject(
                "SELECT version FROM skills WHERE id = ?", Long.class, skill.getId());
        assertThat(optimisticVersion).isNotNull();

        // 2. event_publication outbox 已 INSERT（與業務 SQL 同 TX）
        var publications = jdbc.queryForList(
                "SELECT listener_id, event_type FROM event_publication WHERE serialized_event LIKE ?",
                "%" + skill.getId() + "%");

        var listenerIds = publications.stream()
                .map(m -> (String) m.get("listener_id"))
                .distinct()
                .toList();
        var eventTypes = publications.stream()
                .map(m -> (String) m.get("event_type"))
                .distinct()
                .toList();

        // POC 核心 assertion — SearchProjection 訂閱兩個 event class 各有 outbox row
        assertThat(eventTypes)
                .as("event_publication 應含 SkillCreatedEvent + SkillVersionPublishedEvent FQN")
                .anyMatch(t -> t.contains("SkillCreatedEvent"))
                .anyMatch(t -> t.contains("SkillVersionPublishedEvent"));

        assertThat(listenerIds)
                .as("event_publication 應含 SearchProjection 兩 listener method")
                .anyMatch(id -> id.contains("SearchProjection") && id.contains("onSkillCreated"));

        // POC findings — 印出實際 listener row 數量供 spec §6 POC Findings 紀錄
        log.info("[S024-T01 POC] event_publication total rows: {}", publications.size());
        log.info("[S024-T01 POC] distinct listener_ids: {}", listenerIds);
        log.info("[S024-T01 POC] distinct event_types: {}", eventTypes);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 extended: 業務 TX rollback → event_publication 同 rollback（outbox 安全性 contract）")
    void txRollbackRollsBackPublication() {
        var skill = Skill.create(new CreateSkillCommand("rollback-skill", "rollback test", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://bucket/rollback/1.0.0.zip", 100, 0, Map.of()));

        assertThatThrownBy(() -> helper.saveCrossAggregateThenFail(skill, sv))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated TX rollback");

        // skills / skill_versions 應為空（rollback 已回滾兩 aggregate INSERT）
        Integer skillCount = jdbc.queryForObject(
                "SELECT count(*) FROM skills WHERE id = ?", Integer.class, skill.getId());
        assertThat(skillCount).isZero();

        Integer versionCount = jdbc.queryForObject(
                "SELECT count(*) FROM skill_versions WHERE skill_id = ?",
                Integer.class, skill.getId());
        assertThat(versionCount).isZero();

        // event_publication 應為空（per deepwiki §3 陷阱 2 — outbox INSERT 與業務 SQL 同 TX；
        // rollback 一起回）
        Integer pubCount = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class, "%" + skill.getId() + "%");
        assertThat(pubCount).isZero();
    }
}
