package io.github.samzhu.skillshub.community;

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
import io.github.samzhu.skillshub.shared.api.NotRequestClaimerException;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;
import io.github.samzhu.skillshub.shared.api.SkillNotPublishableException;

/**
 * S096g2-T01 — RequestService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>涵蓋 AC：1（create happy）/ 2（title 上限）/ 3（list votes desc）/ 4（status filter）/
 * 7（claim happy）/ 8（dup claim 409）/ 9（release，claimer-only）/ 10（fulfill happy）/
 * 11（fulfill 非 claimer）/ 12（fulfill 非 PUBLISHED skill）/ 13（delete own OPEN，
 * non-requester 拒，non-OPEN 拒）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RequestServiceTest {

    @Autowired
    private RequestService service;

    @Autowired
    private RequestRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM request_votes");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM domain_events");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: create happy path → status=OPEN, vote_count=0")
    void create_happyPath() {
        var id = service.createRequest("k8s autoscaler", "需要 autoscaler 範本", "alice");

        var request = repo.findById(id).orElseThrow();
        assertThat(request.getTitle()).isEqualTo("k8s autoscaler");
        assertThat(request.getStatus()).isEqualTo("OPEN");
        assertThat(request.getVoteCount()).isZero();
        assertThat(request.getRequesterId()).isEqualTo("alice");
        assertThat(request.getClaimerId()).isNull();
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: title 超 200 字元拒絕")
    void create_titleTooLong_rejected() {
        var longTitle = "x".repeat(201);
        assertThatThrownBy(() -> service.createRequest(longTitle, "ok", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title_too_long");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: listRequests 預設 votes desc")
    void list_votesDesc() throws InterruptedException {
        var r1 = service.createRequest("low", "x", "u1");
        Thread.sleep(5);
        var r2 = service.createRequest("high", "x", "u2");
        Thread.sleep(5);
        var r3 = service.createRequest("mid", "x", "u3");

        // 直接 SQL 設 vote_count（避開 T02 vote service）
        jdbc.update("UPDATE requests SET vote_count = 3 WHERE id = ?", r1);
        jdbc.update("UPDATE requests SET vote_count = 12 WHERE id = ?", r2);
        jdbc.update("UPDATE requests SET vote_count = 5 WHERE id = ?", r3);

        var result = service.listRequests(null, null);
        assertThat(result).extracting(Request::getId).containsExactly(r2, r3, r1);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 alt: sort=created → createdAt desc")
    void list_sortByCreated() throws InterruptedException {
        var r1 = service.createRequest("first", "x", "u1");
        Thread.sleep(10);
        var r2 = service.createRequest("second", "x", "u2");

        var result = service.listRequests(null, "created");
        assertThat(result).extracting(Request::getId).containsExactly(r2, r1);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: status=OPEN filter")
    void list_statusFilter() {
        var r1 = service.createRequest("a", "x", "u1");
        service.createRequest("b", "x", "u2");
        service.claim(r1, "claimer-1"); // r1 → IN_PROGRESS

        var openOnly = service.listRequests("OPEN", null);
        assertThat(openOnly).hasSize(1);
        assertThat(openOnly.getFirst().getStatus()).isEqualTo("OPEN");
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: claim OPEN → IN_PROGRESS + claimer_id")
    void claim_happy() {
        var id = service.createRequest("a", "x", "alice");

        service.claim(id, "bob");

        var r = repo.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(r.getClaimerId()).isEqualTo("bob");
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: 已 claim 重 claim → 409 IllegalStateException")
    void claim_alreadyClaimed_rejected() {
        var id = service.createRequest("a", "x", "alice");
        service.claim(id, "bob");

        assertThatThrownBy(() -> service.claim(id, "carol"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request_already_claimed");
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: claimer release → status=OPEN; 非 claimer release → NotRequestClaimerException")
    void release_byClaimer_andNonClaimer() {
        var id = service.createRequest("a", "x", "alice");
        service.claim(id, "bob");

        assertThatThrownBy(() -> service.release(id, "carol"))
                .isInstanceOf(NotRequestClaimerException.class);

        service.release(id, "bob");

        var r = repo.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo("OPEN");
        assertThat(r.getClaimerId()).isNull();
    }

    @Test
    @Tag("AC-10")
    @DisplayName("AC-10: fulfill happy → FULFILLED + fulfilled_skill_id")
    void fulfill_happy() {
        var id = service.createRequest("a", "x", "alice");
        service.claim(id, "bob");
        var skillId = insertSkill("bob", "PUBLISHED");

        var r = service.fulfill(id, "bob", skillId);

        assertThat(r.getStatus()).isEqualTo("FULFILLED");
        assertThat(r.getFulfilledSkillId()).isEqualTo(skillId);
    }

    @Test
    @Tag("AC-11")
    @DisplayName("AC-11: 非 claimer fulfill → NotRequestClaimerException")
    void fulfill_nonClaimer_rejected() {
        var id = service.createRequest("a", "x", "alice");
        service.claim(id, "bob");
        var skillId = insertSkill("alice", "PUBLISHED");

        assertThatThrownBy(() -> service.fulfill(id, "alice", skillId))
                .isInstanceOf(NotRequestClaimerException.class);
    }

    @Test
    @Tag("AC-12")
    @DisplayName("AC-12: 非 PUBLISHED skill fulfill → 400 SkillNotPublishableException（S096f2-T02 caller migration）")
    void fulfill_nonPublishedSkill_rejected() {
        var id = service.createRequest("a", "x", "alice");
        service.claim(id, "bob");
        var draftSkillId = insertSkill("bob", "DRAFT");

        assertThatThrownBy(() -> service.fulfill(id, "bob", draftSkillId))
                .isInstanceOf(SkillNotPublishableException.class)
                .hasMessageContaining("skill_not_publishable");
    }

    @Test
    @Tag("AC-13")
    @DisplayName("AC-13: delete own OPEN → ok；非 requester → 拒；非 OPEN → 409")
    void delete_ownOpenAndGuards() {
        var id = service.createRequest("a", "x", "alice");

        // 非 requester
        assertThatThrownBy(() -> service.deleteRequest(id, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not_request_requester");

        // claim → 變 IN_PROGRESS → 不可 delete
        service.claim(id, "bob");
        assertThatThrownBy(() -> service.deleteRequest(id, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot_delete_active_request");

        // release 回 OPEN → alice 可 delete
        service.release(id, "bob");
        service.deleteRequest(id, "alice");

        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("getRequest on non-existent → RequestNotFoundException")
    void getRequest_notFound() {
        assertThatThrownBy(() -> service.getRequest("non-existent-uuid"))
                .isInstanceOf(RequestNotFoundException.class);
    }

    private String insertSkill(String author, String status) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO skills (id, name, description, author, category, status, download_count, created_at, updated_at, owner_id)
                VALUES (?, ?, '測試 skill', ?, 'Test', ?, 0, ?, ?, ?)
                """,
                id, "skill-" + id.substring(0, 8), author, status,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), author);
        return id;
    }
}
