package io.github.samzhu.skillshub.notification;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.NotNotificationRecipientException;
import io.github.samzhu.skillshub.shared.api.NotificationNotFoundException;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S096h2-T03 — NotificationController HTTP 契約 + GlobalExceptionHandler 翻譯驗證。
 *
 * <p>{@code @WebMvcTest} slice 對齊 FlagControllerTest 既有 pattern；service 層 mock 後僅驗
 * routing + DTO shape + status code + exception → HTTP 翻譯。業務邏輯 ACs 由
 * {@link NotificationServiceTest} 走 Testcontainers 涵蓋。
 */
@WebMvcTest(NotificationController.class)
class NotificationControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService service;

    @MockitoBean
    private NotificationQueryService queryService;

    @Test
    @DisplayName("GET /notifications → 200 + items[]/hasNext shape")
    void list_returnsWrapper() throws Exception {
        var n = Notification.create("alice", "flags", "你的 skill X 被回報", null, "sk-1", "evt-1");
        Mockito.when(queryService.list(ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                        ArgumentMatchers.eq(20)))
                .thenReturn(new NotificationQueryService.Page(List.of(n), false));

        mockMvc.perform(get("/api/v1/notifications")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].category").value("flags"))
                .andExpect(jsonPath("$.items[0].title").value("你的 skill X 被回報"))
                .andExpect(jsonPath("$.items[0].refEventId").value("evt-1"))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /notifications?category=flags&cursor=x&limit=5 → query params 路由")
    void list_passesQueryParams() throws Exception {
        Mockito.when(queryService.list(ArgumentMatchers.eq("flags"),
                        ArgumentMatchers.eq("cursor-x"), ArgumentMatchers.eq(5)))
                .thenReturn(new NotificationQueryService.Page(List.of(), true));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("category", "flags")
                        .param("cursor", "cursor-x")
                        .param("limit", "5")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("GET /notifications/unread-count → 200 + count field")
    void unreadCount_returnsCount() throws Exception {
        Mockito.when(queryService.unreadCount()).thenReturn(7L);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(7));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: POST /{id}/read happy → 204")
    void markRead_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/n1/read")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isNoContent());
        Mockito.verify(service).markRead("n1");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: POST /{id}/read non-recipient → 403 + error code")
    void markRead_forbidden_returns403() throws Exception {
        Mockito.doThrow(new NotNotificationRecipientException()).when(service).markRead("n1");

        mockMvc.perform(post("/api/v1/notifications/n1/read")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_notification_recipient"));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: POST /{id}/read not-found → 404 + error code")
    void markRead_notFound_returns404() throws Exception {
        Mockito.doThrow(new NotificationNotFoundException("n1")).when(service).markRead("n1");

        mockMvc.perform(post("/api/v1/notifications/n1/read")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("notification_not_found"));
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: POST /read-all → 204")
    void markAllRead_returns204() throws Exception {
        Mockito.when(service.markAllRead()).thenReturn(5);

        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: DELETE /{id} happy → 204")
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications/n1")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isNoContent());
        Mockito.verify(service).delete("n1");
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: GET /preferences → 200 + 4 boolean fields")
    void getPreferences_returnsAllFlags() throws Exception {
        var pref = NotificationPreference.defaults("alice");
        pref.update(false, true, true, true);
        Mockito.when(service.getPreferences()).thenReturn(pref);

        mockMvc.perform(get("/api/v1/notifications/preferences")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags").value(false))
                .andExpect(jsonPath("$.reviews").value(true))
                .andExpect(jsonPath("$.requests").value(true))
                .andExpect(jsonPath("$.versions").value(true));
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: POST /preferences body partial → 200 + 翻 service updatePreferences args")
    void updatePreferences_routesToService() throws Exception {
        var pref = NotificationPreference.defaults("alice");
        pref.update(false, null, null, null);
        Mockito.when(service.updatePreferences(ArgumentMatchers.eq(false),
                        ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull()))
                .thenReturn(pref);

        mockMvc.perform(post("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flags":false}
                                """)
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags").value(false))
                .andExpect(jsonPath("$.reviews").value(true));
    }
}
