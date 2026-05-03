package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.community.events.CollectionCreatedEvent;
import io.github.samzhu.skillshub.community.events.CollectionInstalledEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

/**
 * S096f2-T01 — Collection aggregate / V12 schema / community @ApplicationModule
 * 三層基礎建設 smoke test（Testcontainers + 真 PostgreSQL）。
 *
 * <p>對齊 NotificationModuleSmokeTest 既驗 pattern；本 task scope 僅驗
 * schema/aggregate/repo round-trip + @MappedCollection list ordering + UNIQUE 守則
 * + recordInstall counter increment + outbox event publish；service / controller
 * AC 由 T02 涵蓋；frontend AC 由 T04 涵蓋。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class CollectionModuleSmokeTest {

    @Autowired
    private CollectionRepository repo;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM collection_skills");
        jdbc.update("DELETE FROM collections");
        jdbc.update("DELETE FROM domain_events");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @DisplayName("Collection.create + save → INSERT collection + N collection_skills with position 0..n")
    void create_persist_roundTrip() {
        var c = Collection.create("DevOps Starter", "k8s tooling pack",
                "DevOps", "alice", List.of("sk-1", "sk-2", "sk-3"));
        var saved = repo.save(c);

        var loaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("DevOps Starter");
        assertThat(loaded.getCategory()).isEqualTo("DevOps");
        assertThat(loaded.getOwnerId()).isEqualTo("alice");
        assertThat(loaded.getInstallCount()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        // 順序保留（@MappedCollection keyColumn=position）
        assertThat(loaded.skillIds()).containsExactly("sk-1", "sk-2", "sk-3");
    }

    @Test
    @DisplayName("Collection.recordInstall + save → install_count 增量；二次 save 走 UPDATE path（@Version）")
    void recordInstall_incrementsCounter() {
        var c = repo.save(Collection.create("Pack A", null, "Misc", "alice",
                List.of("sk-1", "sk-2")));

        var loaded = repo.findById(c.getId()).orElseThrow();
        loaded.recordInstall("bob");
        repo.save(loaded);

        var reread = repo.findById(c.getId()).orElseThrow();
        assertThat(reread.getInstallCount()).isEqualTo(1);
        // 同一 collection 第二次 install 不報 OptimisticLock（@Version null→0→1→2）
        var loaded2 = repo.findById(c.getId()).orElseThrow();
        loaded2.recordInstall("carol");
        repo.save(loaded2);
        assertThat(repo.findById(c.getId()).orElseThrow().getInstallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ON DELETE CASCADE — 刪 collection 時 collection_skills 自動清")
    void deleteCollection_cascades() {
        var c = repo.save(Collection.create("A", null, "Misc", "alice",
                List.of("sk-1", "sk-2", "sk-3")));

        repo.deleteById(c.getId());

        var rows = jdbc.queryForList(
                "SELECT skill_id FROM collection_skills WHERE collection_id = ?", c.getId());
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("UNIQUE (collection_id, skill_id) — 同 collection 內 skill 不重複")
    void factoryRejectsDuplicateSkillIds() {
        assertThatThrownBy(() -> Collection.create("dup", null, "Misc", "alice",
                List.of("sk-1", "sk-2", "sk-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection_must_have_unique_skills");
    }

    @Test
    @DisplayName("factory rejection — name too long / empty skillIds / blank category")
    void factoryRejections() {
        assertThatThrownBy(() -> Collection.create("x".repeat(201), null, "Misc", "alice",
                List.of("sk-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name_too_long");

        assertThatThrownBy(() -> Collection.create("ok", null, "Misc", "alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection_must_have_skills");

        assertThatThrownBy(() -> Collection.create("ok", null, "  ", "alice", List.of("sk-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category_required");
    }

    @Test
    @DisplayName("CollectionRepository.findAllByCategoryOrderByCreatedAtDesc — category filter + sort")
    void list_categoryFilter() throws InterruptedException {
        repo.save(Collection.create("DevOps Pack", null, "DevOps", "alice", List.of("sk-1")));
        Thread.sleep(5);
        repo.save(Collection.create("Frontend Pack", null, "Frontend", "alice", List.of("sk-2")));
        Thread.sleep(5);
        var fe2 = repo.save(Collection.create("FE Tools", null, "Frontend", "bob", List.of("sk-3")));

        var all = repo.findAllByOrderByCreatedAtDesc();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getId()).isEqualTo(fe2.getId()); // newest first

        var feOnly = repo.findAllByCategoryOrderByCreatedAtDesc("Frontend");
        assertThat(feOnly).hasSize(2).allMatch(c -> c.getCategory().equals("Frontend"));
        assertThat(feOnly.get(0).getId()).isEqualTo(fe2.getId());
    }

    @Test
    @DisplayName("SkillRepository.findAllByIdInAndStatus — 給 CollectionService.create 預檢用")
    void skillRepo_findAllByIdInAndStatus() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var sk2 = insertSkill("alice", "PUBLISHED");
        var sk3 = insertSkill("alice", "SUSPENDED");

        var found = skillRepo.findAllByIdInAndStatus(
                List.of(sk1, sk2, sk3, "non-existent"), SkillStatus.PUBLISHED);

        assertThat(found).hasSize(2)
                .extracting(s -> s.getId())
                .containsExactlyInAnyOrder(sk1, sk2);
    }

    @Test
    @DisplayName("repo.save() 透過 AbstractAggregateRoot.domainEvents publish 至 ApplicationEventPublisher")
    void domainEvents_publishedToApplicationContext() {
        var c = repo.save(Collection.create("Pack", null, "Misc", "alice", List.of("sk-1")));
        var loaded = repo.findById(c.getId()).orElseThrow();
        loaded.recordInstall("bob");
        repo.save(loaded);

        // @RecordApplicationEvents 攔截 ApplicationEventPublisher.publishEvent — 對齊
        // Spring Test 慣例。Modulith event_publication 表只追蹤「有 listener 訂閱」的
        // (event, listener_id) pair；本 spec MVP 無 listener 故 outbox 表空（per spec §4.4
        // — 預留 hook 給 future S101b Impact Score）。
        var created = applicationEvents.stream(CollectionCreatedEvent.class).toList();
        var installed = applicationEvents.stream(CollectionInstalledEvent.class).toList();

        assertThat(created).hasSize(1);
        assertThat(created.get(0).collectionId()).isEqualTo(c.getId());
        assertThat(created.get(0).skillIds()).containsExactly("sk-1");

        assertThat(installed).hasSize(1);
        assertThat(installed.get(0).collectionId()).isEqualTo(c.getId());
        assertThat(installed.get(0).installerId()).isEqualTo("bob");
    }

    @Test
    @DisplayName("DB UNIQUE (collection_id, skill_id) — bypass factory 直接 INSERT 同 skill_id 攔截")
    void uniqueConstraint_blocksDuplicateAtDbLevel() {
        var c = repo.save(Collection.create("Pack", null, "Misc", "alice", List.of("sk-1")));

        // Bypass factory 防護直接 INSERT — 模擬 race condition
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO collection_skills (collection_id, skill_id, position) VALUES (?, ?, ?)",
                c.getId(), "sk-1", 99))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private String insertSkill(String author, String status) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at)
                VALUES (?, ?, '測試 skill', ?, 'Test', ?, 0, ?, ?)
                """,
                id, "skill-" + id.substring(0, 8), author, status,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }
}
