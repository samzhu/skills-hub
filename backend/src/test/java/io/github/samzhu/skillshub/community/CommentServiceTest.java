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
import io.github.samzhu.skillshub.shared.api.CommentNotFoundException;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S156c — CommentService 業務邏輯整合測試（Testcontainers + 真 PostgreSQL）。
 *
 * <p>涵蓋 AC：5（add comment → row + event）/ 6（soft delete owner-only；非 author 403；
 * 已 soft-deleted 視為 404）。Notification 副作用由 {@link
 * io.github.samzhu.skillshub.notification.NotificationProjectionListenerTest} 涵蓋。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CommentServiceTest {

    @Autowired
    private RequestService requestService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RequestCommentRepository commentRepo;

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
    @Tag("AC-5")
    @DisplayName("AC-5: addComment → request_comments 多 1 row + content/author 對齊")
    void addComment_happyPath() {
        var requestId = requestService.createRequest("a", "x", "alice");

        var commentId = commentService.addComment(requestId, "bob", "+1 我也要");

        var rows = commentRepo.findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc(requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(commentId);
        assertThat(rows.get(0).getAuthorId()).isEqualTo("bob");
        assertThat(rows.get(0).getContent()).isEqualTo("+1 我也要");
        assertThat(rows.get(0).isDeleted()).isFalse();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 sad: addComment 對不存在 request → RequestNotFoundException")
    void addComment_requestNotFound() {
        assertThatThrownBy(() -> commentService.addComment("nonexistent", "bob", "+1"))
                .isInstanceOf(RequestNotFoundException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 sad: 空 content → factory IllegalArgumentException")
    void addComment_emptyContent() {
        var requestId = requestService.createRequest("a", "x", "alice");

        assertThatThrownBy(() -> commentService.addComment(requestId, "bob", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content_required");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 happy: deleteComment as author → soft delete (deleted_at set)")
    void deleteComment_byAuthor() {
        var requestId = requestService.createRequest("a", "x", "alice");
        var commentId = commentService.addComment(requestId, "bob", "+1");

        commentService.deleteComment(requestId, commentId, "bob");

        var raw = commentRepo.findById(commentId).orElseThrow();
        assertThat(raw.isDeleted()).isTrue();
        assertThat(raw.getDeletedAt()).isNotNull();
        // 查詢端過濾 — 不出現在 list
        assertThat(commentService.listByRequest(requestId)).isEmpty();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 sad: deleteComment as non-author → AccessDeniedException (→ 403)")
    void deleteComment_nonAuthor() {
        var requestId = requestService.createRequest("a", "x", "alice");
        var commentId = commentService.addComment(requestId, "bob", "+1");

        assertThatThrownBy(() -> commentService.deleteComment(requestId, commentId, "charlie"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not_comment_author");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 sad: deleteComment on already-soft-deleted → CommentNotFoundException (→ 404)")
    void deleteComment_alreadyDeleted() {
        var requestId = requestService.createRequest("a", "x", "alice");
        var commentId = commentService.addComment(requestId, "bob", "+1");
        commentService.deleteComment(requestId, commentId, "bob"); // 先刪一次

        // 再刪同一個 → CommentNotFoundException（已 soft-deleted 視為不存在）
        assertThatThrownBy(() -> commentService.deleteComment(requestId, commentId, "bob"))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 sad: deleteComment on nonexistent commentId → CommentNotFoundException")
    void deleteComment_notFound() {
        var requestId = requestService.createRequest("a", "x", "alice");

        assertThatThrownBy(() -> commentService.deleteComment(requestId, "bogus-comment-id", "bob"))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 helper: listByRequest 按 createdAt ASC + 過濾 soft-deleted")
    void listByRequest_ascAndFilter() throws InterruptedException {
        var requestId = requestService.createRequest("a", "x", "alice");
        var c1 = commentService.addComment(requestId, "bob", "first");
        Thread.sleep(5);
        var c2 = commentService.addComment(requestId, "charlie", "second");
        Thread.sleep(5);
        var c3 = commentService.addComment(requestId, "dave", "third");

        // 刪 c2 → list 應只剩 c1, c3 (ASC by createdAt)
        commentService.deleteComment(requestId, c2, "charlie");

        var rows = commentService.listByRequest(requestId);
        assertThat(rows).extracting(RequestComment::getId).containsExactly(c1, c3);
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7 cascade: hard delete request → request_comments row 自動消失 (ON DELETE CASCADE)")
    void requestHardDelete_cascadesComments() {
        var requestId = requestService.createRequest("a", "x", "alice");
        commentService.addComment(requestId, "bob", "+1");
        commentService.addComment(requestId, "charlie", "+1");
        assertThat(commentService.listByRequest(requestId)).hasSize(2);

        requestService.deleteRequest(requestId, "alice");

        // requests row 沒了 + comments rows 也沒了（CASCADE）
        Integer remainingComments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_comments WHERE request_id = ?", Integer.class, requestId);
        assertThat(remainingComments).isZero();
    }

    @Test
    @Tag("AC-12")
    @DisplayName("AC-12 ES 永存: hard delete request 後 domain_events 對應 row 仍存在（events 不可變）")
    void requestHardDelete_domainEventsPersist() throws InterruptedException {
        var requestId = requestService.createRequest("a", "x", "alice");
        commentService.addComment(requestId, "bob", "+1");

        // 等 AFTER_COMMIT async listener 完成寫入 domain_events
        // （RequestPostedEvent + RequestCommentedEvent 由 AuditEventListener 寫入）
        var deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM domain_events WHERE aggregate_id = ?",
                    Integer.class, requestId);
            if (count != null && count >= 2) break;
            Thread.sleep(50);
        }

        // delete request → requests / request_comments 物理消失
        requestService.deleteRequest(requestId, "alice");

        Integer requestsRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requests WHERE id = ?", Integer.class, requestId);
        Integer commentsRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_comments WHERE request_id = ?", Integer.class, requestId);
        assertThat(requestsRow).isZero();
        assertThat(commentsRow).isZero();

        // 但 domain_events 對應 row 永存（ES 不可變原則 per spec §2.8）
        Integer eventsRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM domain_events WHERE aggregate_id = ?",
                Integer.class, requestId);
        assertThat(eventsRow).isGreaterThanOrEqualTo(2);
    }
}
