package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S096g2-T01 → S156c T01 — RequestService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>S156c voting-board pivot：移除原 AC-7~12 claim/release/fulfill 測試案例（state machine 拆除）；
 * AC-13 delete 簡化為「requester only」（無 status guard）— renamed AC-7 對齊新 spec AC 編號。
 *
 * <p>涵蓋 AC：1（create happy）/ 2（title 上限）/ 3（list votes desc）/ 7（delete requester-only）。
 * Comment / detail / events 永存 由 T02 / T03 / T04 涵蓋。
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
        jdbc.update("DELETE FROM request_comments");
        jdbc.update("DELETE FROM request_votes");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: create happy path → vote_count=0, requester_id 對齊")
    void create_happyPath() {
        var id = service.createRequest("k8s autoscaler", "需要 autoscaler 範本", "alice");

        var request = repo.findById(id).orElseThrow();
        assertThat(request.getTitle()).isEqualTo("k8s autoscaler");
        assertThat(request.getVoteCount()).isZero();
        assertThat(request.getRequesterId()).isEqualTo("alice");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: title 超 200 字元拒絕")
    void create_titleTooLong_rejected() {
        var longTitle = "x".repeat(201);
        assertThatThrownBy(() -> service.createRequest(longTitle, "ok", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title_too_long");
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 (regression): listRequests 預設 votes desc")
    void list_votesDesc() throws InterruptedException {
        var r1 = service.createRequest("low", "x", "u1");
        Thread.sleep(5);
        var r2 = service.createRequest("high", "x", "u2");
        Thread.sleep(5);
        var r3 = service.createRequest("mid", "x", "u3");

        // 直接 SQL 設 vote_count（避開 RequestVoteService 並發）
        jdbc.update("UPDATE requests SET vote_count = 3 WHERE id = ?", r1);
        jdbc.update("UPDATE requests SET vote_count = 12 WHERE id = ?", r2);
        jdbc.update("UPDATE requests SET vote_count = 5 WHERE id = ?", r3);

        var result = service.listRequests(null);
        assertThat(result).extracting(Request::getId).containsExactly(r2, r3, r1);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 alt: sort=created → createdAt desc")
    void list_sortByCreated() throws InterruptedException {
        var r1 = service.createRequest("first", "x", "u1");
        Thread.sleep(10);
        var r2 = service.createRequest("second", "x", "u2");

        var result = service.listRequests("created");
        assertThat(result).extracting(Request::getId).containsExactly(r2, r1);
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: delete requester-only — own → ok；非 requester → 403 (AccessDeniedException)")
    void delete_requesterOnly() {
        var id = service.createRequest("a", "x", "alice");

        // 非 requester — Spring Security handler routes AccessDeniedException → 403 (per spec AC-7)
        assertThatThrownBy(() -> service.deleteRequest(id, "bob"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not_request_requester");

        // requester 自己刪
        service.deleteRequest(id, "alice");
        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("getRequest on non-existent → RequestNotFoundException")
    void getRequest_notFound() {
        assertThatThrownBy(() -> service.getRequest("non-existent-uuid"))
                .isInstanceOf(RequestNotFoundException.class);
    }
}
