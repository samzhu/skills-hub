package io.github.samzhu.skillshub.community;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.CommentNotFoundException;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S156c — CommentController HTTP 契約 + GlobalExceptionHandler 翻譯驗證。
 *
 * <p>{@code @WebMvcTest}（slice）+ Service mock。業務邏輯（AC-5/AC-6 happy + sad path 整合）
 * 由 {@link CommentServiceTest} 走 Testcontainers 涵蓋；controller test 只驗 routing + DTO
 * shape + status code + exception → HTTP 翻譯（對齊 CollectionControllerTest pattern）。
 */
@WebMvcTest(controllers = {CommentController.class})
class CommentControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService service;

    // CommentController ctor 需 CurrentUserProvider；@WebMvcTest slice 不載 component scan 須顯式 mock
    @MockitoBean
    private CurrentUserProvider users;

    @org.junit.jupiter.api.BeforeEach
    void wireCurrentUser() {
        Mockito.when(users.current()).thenReturn(
                CurrentUser.synthetic("u_test", java.util.List.of("user"), java.util.List.of(), null));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: POST /requests/{id}/comments happy → 201 + {id}")
    void add_happy_returns201() throws Exception {
        Mockito.when(service.addComment(ArgumentMatchers.eq("r1"), ArgumentMatchers.any(), ArgumentMatchers.eq("+1")))
                .thenReturn("c-new");

        mockMvc.perform(post("/api/v1/requests/r1/comments")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"+1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("c-new"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 sad: request 不存在 → 404 REQUEST_NOT_FOUND")
    void add_requestNotFound_returns404() throws Exception {
        Mockito.when(service.addComment(ArgumentMatchers.eq("nonexistent"),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new io.github.samzhu.skillshub.shared.api.RequestNotFoundException("nonexistent"));

        mockMvc.perform(post("/api/v1/requests/nonexistent/comments")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"+1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("REQUEST_NOT_FOUND"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 sad: 空 content → factory throws IllegalArgumentException → 400 VALIDATION_ERROR")
    void add_emptyContent_returns400() throws Exception {
        Mockito.when(service.addComment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("content_required"));

        mockMvc.perform(post("/api/v1/requests/r1/comments")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 happy: DELETE comment as author → 204")
    void delete_happy_returns204() throws Exception {
        Mockito.doNothing().when(service).deleteComment(
                ArgumentMatchers.eq("r1"), ArgumentMatchers.eq("c1"), ArgumentMatchers.any());

        mockMvc.perform(delete("/api/v1/requests/r1/comments/c1").with(jwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 sad: DELETE comment as non-author → 403 ACCESS_DENIED")
    void delete_nonAuthor_returns403() throws Exception {
        Mockito.doThrow(new AccessDeniedException("not_comment_author"))
                .when(service).deleteComment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        mockMvc.perform(delete("/api/v1/requests/r1/comments/c1").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 sad: DELETE nonexistent (or soft-deleted) comment → 404 COMMENT_NOT_FOUND")
    void delete_notFound_returns404() throws Exception {
        Mockito.doThrow(new CommentNotFoundException("c-bogus"))
                .when(service).deleteComment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        mockMvc.perform(delete("/api/v1/requests/r1/comments/c-bogus").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COMMENT_NOT_FOUND"));
    }
}
