package io.github.samzhu.skillshub.community;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S156c T04 — GET /api/v1/requests/{id} detail endpoint integration test
 * （Testcontainers + 真 PostgreSQL + 真實 controller / service / comment 拼裝）。
 *
 * <p>對應 spec AC-4（detail + comments + canDelete）+ AC-11（404 nonexistent）。
 *
 * <p>{@code @SpringBootTest + MockMvcBuilders.webAppContextSetup} 對齊 backend 既有
 * full-stack 整合測試風格 — 不 mock service/repo，直接走 controller → service →
 * Testcontainers PostgreSQL，驗 JSON shape 與真實業務流程一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RequestDetailQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestService requestService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RequestCommentRepository commentRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM request_comments");
        jdbc.update("DELETE FROM request_votes");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-S192-6")
    @DisplayName("AC-S192-6: request comment returns author display data while delete still uses authorId")
    void detail_commentIncludesAuthorDisplayFields() throws Exception {
        seedUser("u_f7eb3a", "sam@example.com", "Sam Zhu", "samzhu");
        var requestId = requestService.createRequest("字幕工具", "需要 srt helper", "alice");
        var commentId = commentService.addComment(requestId, "u_f7eb3a", "+1 我也需要");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].id").value(commentId))
                .andExpect(jsonPath("$.comments[0].authorId").value("u_f7eb3a"))
                .andExpect(jsonPath("$.comments[0].authorDisplayName").value("Sam Zhu"))
                .andExpect(jsonPath("$.comments[0].authorHandle").value("samzhu"));
    }

    @Test
    @Tag("AC-S200-1")
    @DisplayName("AC-S200-1: request detail API 回 requester display companion")
    void detail_requestIncludesRequesterDisplayFields() throws Exception {
        seedUser("u_aa1111", "alice@example.com", "Alice Chen", "alice");
        var requestId = requestService.createRequest("字幕工具", "需要 srt helper", "u_aa1111");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("u_aa1111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requesterId").value("u_aa1111"))
                .andExpect(jsonPath("$.requesterDisplayName").value("Alice Chen"))
                .andExpect(jsonPath("$.requesterHandle").value("alice"));
    }

    @Test
    @Tag("AC-S200-2")
    @DisplayName("AC-S200-2: request list API 回 requester display companion")
    void list_requestIncludesRequesterDisplayFields() throws Exception {
        seedUser("u_bb2222", "bob@example.com", "Bob Lin", "bob");
        var requestId = requestService.createRequest("部署協助", "需要 cloud run helper", "u_bb2222");

        mockMvc.perform(get("/api/v1/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].requesterId").value("u_bb2222"))
                .andExpect(jsonPath("$[0].requesterDisplayName").value("Bob Lin"))
                .andExpect(jsonPath("$[0].requesterHandle").value("bob"));
    }

    @Test
    @Tag("AC-S200-4")
    @DisplayName("AC-S200-4: canDelete 仍用 requesterId 不用 display name")
    void detail_canDeleteUsesRequesterIdNotDisplayName() throws Exception {
        seedUser("u_aa1111", "alice@example.com", "Visible Name", "visible");
        var requestId = requestService.createRequest("刪除權限", "display 欄位不參與權限", "u_aa1111");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("u_aa1111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requesterDisplayName").value("Visible Name"))
                .andExpect(jsonPath("$.canDelete").value(true));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 happy: GET as requester (alice) → 200 + comments ASC + canDelete=true")
    void detail_asRequester_canDeleteTrue() throws Exception {
        var requestId = requestService.createRequest("k8s autoscaler", "需要 HPA 自動建議", "alice");
        var c1 = commentService.addComment(requestId, "bob", "+1 我也要");
        Thread.sleep(5);
        var c2 = commentService.addComment(requestId, "charlie", "有人在做？");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId))
                .andExpect(jsonPath("$.title").value("k8s autoscaler"))
                .andExpect(jsonPath("$.requesterId").value("alice"))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.comments", org.hamcrest.Matchers.hasSize(2)))
                // ASC by createdAt — c1 first, c2 second
                .andExpect(jsonPath("$.comments[0].id").value(c1))
                .andExpect(jsonPath("$.comments[0].authorId").value("bob"))
                .andExpect(jsonPath("$.comments[1].id").value(c2))
                .andExpect(jsonPath("$.comments[1].authorId").value("charlie"))
                // 移除的 field 不應出現
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.claimerId").doesNotExist())
                .andExpect(jsonPath("$.fulfilledSkillId").doesNotExist());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: GET as non-requester (bob) → canDelete=false")
    void detail_asNonRequester_canDeleteFalse() throws Exception {
        var requestId = requestService.createRequest("a", "x", "alice");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: GET unauthenticated → canDelete=false (public read 允許但 canDelete 不暴露)")
    void detail_unauth_canDeleteFalse() throws Exception {
        var requestId = requestService.createRequest("a", "x", "alice");

        // 不加 .with(jwt()) → anonymous → canDelete 應 false
        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: soft-deleted comment 不出現在 comments[] 中")
    void detail_softDeletedComment_excluded() throws Exception {
        var requestId = requestService.createRequest("a", "x", "alice");
        commentService.addComment(requestId, "bob", "first");
        var c2 = commentService.addComment(requestId, "charlie", "second");

        // 手動 soft delete c2
        var comment = commentRepo.findById(c2).orElseThrow();
        comment.softDelete();
        commentRepo.save(comment);

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.comments[0].authorId").value("bob"));
    }

    @Test
    @Tag("AC-11")
    @DisplayName("AC-11: GET nonexistent → 404 REQUEST_NOT_FOUND")
    void detail_nonexistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requests/nonexistent-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("REQUEST_NOT_FOUND"));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: detail shape — voteCount / createdAt / updatedAt 對齊 entity")
    void detail_baseFields() throws Exception {
        var requestId = requestService.createRequest("基礎欄位驗", "描述", "alice");

        mockMvc.perform(get("/api/v1/requests/" + requestId).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voteCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        // Verify hard delete request → next GET 404
        requestService.deleteRequest(requestId, "alice");
        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isNotFound());
    }

    private void seedUser(String id, String email, String name, String handle) {
        jdbc.update("""
                INSERT INTO users (id, oauth_provider, sub, email, name, handle, created_at, last_seen_at)
                VALUES (?, 'google', ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (id) DO UPDATE
                SET email = EXCLUDED.email,
                    name = EXCLUDED.name,
                    handle = EXCLUDED.handle,
                    last_seen_at = NOW()
                """, id, id + "-sub", email, name, handle);
    }

}
