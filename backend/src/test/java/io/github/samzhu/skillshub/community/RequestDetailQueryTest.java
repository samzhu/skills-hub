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

}
