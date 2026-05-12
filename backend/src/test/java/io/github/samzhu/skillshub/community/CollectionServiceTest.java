package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.api.CollectionNotFoundException;
import io.github.samzhu.skillshub.shared.api.SkillNotPublishableException;

/**
 * S096f2-T02 — CollectionService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>對齊 NotificationServiceTest / RequestServiceTest 既驗 pattern。涵蓋 AC：
 * 1（create happy）/ 2（empty skillIds）/ 3（含 SUSPENDED + non-existent skill）/
 * 4（name too long）/ 7（install happy → install_count + 回 download URLs 對齊 skill order）/
 * 8（install non-existent → 404 CollectionNotFoundException）。
 *
 * <p>Auth 透過 SecurityContextHolder 直接 inject UsernamePasswordAuthenticationToken
 * 模擬「當前 user」— CurrentUserProvider 第二 branch 路徑。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CollectionServiceTest {

    @Autowired
    private CollectionService service;

    @Autowired
    private CollectionRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM collection_skills");
        jdbc.update("DELETE FROM collections");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
        loginAs("alice");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: create happy → DB collection 1 + collection_skills 3 (position 0/1/2)")
    void create_happy() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var sk2 = insertSkill("alice", "PUBLISHED");
        var sk3 = insertSkill("alice", "PUBLISHED");

        var id = service.create("DevOps Starter", "k8s tooling", "devops",
                List.of(sk1, sk2, sk3));

        var c = repo.findById(id).orElseThrow();
        assertThat(c.getName()).isEqualTo("DevOps Starter");
        assertThat(c.getCategory()).isEqualTo("devops");
        assertThat(c.getOwnerId()).isEqualTo("alice");
        assertThat(c.getInstallCount()).isZero();
        assertThat(c.skillIds()).containsExactly(sk1, sk2, sk3);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: skillIds 空 list → IllegalArgumentException collection_must_have_skills（factory level）")
    void create_emptySkillIds() {
        // Service 端不預檢空（factory rejects 早於 SkillRepository 查詢）
        assertThatThrownBy(() -> service.create("X", null, "Misc", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection_must_have_skills");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: 含 SUSPENDED + non-existent skill → SkillNotPublishableException with invalidSkillIds list")
    void create_invalidSkillIds() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var sk2 = insertSkill("alice", "SUSPENDED");
        var bogus = "bogus-skill-id";

        assertThatThrownBy(() -> service.create("X", null, "Misc", List.of(sk1, sk2, bogus)))
                .isInstanceOf(SkillNotPublishableException.class)
                .extracting(e -> ((SkillNotPublishableException) e).getInvalidSkillIds())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder(sk2, bogus);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: name 201 字元 → IllegalArgumentException name_too_long")
    void create_nameTooLong() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var longName = "x".repeat(201);
        assertThatThrownBy(() -> service.create(longName, null, "Misc", List.of(sk1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name_too_long");
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: install happy → install_count +1，回 download URLs 對齊 skill order")
    void install_happy() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var sk2 = insertSkill("alice", "PUBLISHED");
        var sk3 = insertSkill("alice", "PUBLISHED");

        var id = service.create("Pack", null, "Misc", List.of(sk1, sk2, sk3));
        var beforeCount = repo.findById(id).orElseThrow().getInstallCount();

        loginAs("bob"); // installer 異於 owner，模擬一般使用者下載
        var urls = service.install(id);

        assertThat(urls).containsExactly(
                "/api/v1/skills/" + sk1 + "/download",
                "/api/v1/skills/" + sk2 + "/download",
                "/api/v1/skills/" + sk3 + "/download");
        assertThat(repo.findById(id).orElseThrow().getInstallCount()).isEqualTo(beforeCount + 1);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: install 不存在 collection → CollectionNotFoundException")
    void install_notFound() {
        assertThatThrownBy(() -> service.install("non-existent-id"))
                .isInstanceOf(CollectionNotFoundException.class)
                .hasMessageContaining("collection_not_found");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: list 預設 createdAt desc + category filter")
    void list_defaultAndFilter() throws InterruptedException {
        var sk1 = insertSkill("alice", "PUBLISHED");
        service.create("DevOps Pack", null, "devops", List.of(sk1));
        Thread.sleep(5);
        var fePack = service.create("Frontend Pack", null, "Frontend", List.of(sk1));
        Thread.sleep(5);
        var feTools = service.create("FE Tools", null, "Frontend", List.of(sk1));

        var all = service.list(null);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getId()).isEqualTo(feTools); // newest first

        var feOnly = service.list("Frontend");
        assertThat(feOnly).hasSize(2)
                .extracting(Collection::getId)
                .containsExactly(feTools, fePack);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: get + getCollectionSkills → 順序保留 + SUSPENDED skill 仍能 read")
    void get_withSkillsDetail() {
        var sk1 = insertSkill("alice", "PUBLISHED");
        var sk2 = insertSkill("alice", "PUBLISHED");
        var id = service.create("Pack", null, "Misc", List.of(sk1, sk2));

        // 模擬 sk1 被 SUSPENDED — collection skills detail 仍能 read（historical reference）
        jdbc.update("UPDATE skills SET status = 'SUSPENDED' WHERE id = ?", sk1);

        var collection = service.get(id);
        var skills = service.getCollectionSkills(collection);

        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(s -> s.getId()).containsExactly(sk1, sk2);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: get 不存在 collection → CollectionNotFoundException")
    void get_notFound() {
        assertThatThrownBy(() -> service.get("non-existent-id"))
                .isInstanceOf(CollectionNotFoundException.class);
    }

    private String insertSkill(String author, String status) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, '測試 skill', ?, 'test', ?, 0, ?, ?, ?)
                """,
                id, "skill-" + id.substring(0, 8), author, status,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), author);
        return id;
    }

    private void loginAs(String userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_user")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
