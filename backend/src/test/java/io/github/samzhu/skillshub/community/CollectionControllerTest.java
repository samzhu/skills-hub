package io.github.samzhu.skillshub.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.CollectionNotFoundException;
import io.github.samzhu.skillshub.shared.api.SkillNotPublishableException;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S096f2-T02 — Collection Command/Query controllers HTTP 契約 + GlobalExceptionHandler 翻譯驗證。
 *
 * <p>{@code @WebMvcTest(controllers = ...)} 同時拉兩個 controller（共 mapping `/api/v1/collections`）；
 * 對齊 NotificationControllerTest / FlagControllerTest 既驗 pattern。Service 層 mock 後僅驗
 * routing + DTO shape + status code + exception → HTTP 翻譯。業務邏輯 ACs 由 CollectionServiceTest
 * 走 Testcontainers 涵蓋。
 */
@WebMvcTest(controllers = {CollectionCommandController.class, CollectionQueryController.class})
class CollectionControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CollectionService service;

    // CollectionQueryController ctor 需 NamedParameterJdbcTemplate；slice 不載 JDBC 須顯式 mock
    @MockitoBean
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: POST /collections happy → 201 + {id}")
    void create_returns201() throws Exception {
        Mockito.when(service.create(ArgumentMatchers.eq("devops"), ArgumentMatchers.any(),
                        ArgumentMatchers.eq("devops"), ArgumentMatchers.eq(List.of("sk-1", "sk-2"))))
                .thenReturn("col-123");

        mockMvc.perform(post("/api/v1/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"devops","description":"k8s","category":"devops","skillIds":["sk-1","sk-2"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("col-123"));
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: skillIds 空 list → factory throws IllegalArgumentException → 400 VALIDATION_ERROR")
    void create_emptySkillIds_returns400() throws Exception {
        Mockito.when(service.create(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.eq(List.of())))
                .thenThrow(new IllegalArgumentException("collection_must_have_skills"));

        mockMvc.perform(post("/api/v1/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","description":null,"category":"X","skillIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: skillIds 含 SUSPENDED → SkillNotPublishableException → 400 SKILL_NOT_PUBLISHABLE")
    void create_invalidSkill_returns400() throws Exception {
        Mockito.when(service.create(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new SkillNotPublishableException(List.of("sk-bogus")));

        mockMvc.perform(post("/api/v1/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","description":null,"category":"X","skillIds":["sk-bogus"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SKILL_NOT_PUBLISHABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sk-bogus")));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: GET /collections → 200 + array shape；category query param 路由")
    void list_returnsArray() throws Exception {
        var c = makeCollection("DevOps Pack", "devops");
        Mockito.when(service.list(ArgumentMatchers.eq("devops"))).thenReturn(List.of(c));

        mockMvc.perform(get("/api/v1/collections").param("category", "devops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("DevOps Pack"))
                .andExpect(jsonPath("$[0].category").value("devops"))
                .andExpect(jsonPath("$[0].skillCount").value(1))
                // S118: rename installs → installCount 對齊 CollectionDetail 既驗
                .andExpect(jsonPath("$[0].installCount").value(0));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: GET /collections/{id} → 200 + skills detail array")
    void get_returnsDetail() throws Exception {
        var c = makeCollection("Pack", "Misc");
        Mockito.when(service.get(ArgumentMatchers.anyString())).thenReturn(c);
        Mockito.when(service.getCollectionSkills(c)).thenReturn(List.of()); // empty for slice mock simplicity

        mockMvc.perform(get("/api/v1/collections/" + c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pack"))
                .andExpect(jsonPath("$.ownerId").value("alice"))
                .andExpect(jsonPath("$.skills").isArray());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: POST /{id}/install → 200 + downloadUrls array")
    void install_returnsUrls() throws Exception {
        Mockito.when(service.install("c1")).thenReturn(List.of(
                "/api/v1/skills/sk-1/download", "/api/v1/skills/sk-2/download"));

        mockMvc.perform(post("/api/v1/collections/c1/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrls[0]").value("/api/v1/skills/sk-1/download"))
                .andExpect(jsonPath("$.downloadUrls[1]").value("/api/v1/skills/sk-2/download"));
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: POST install on non-existent → 404 COLLECTION_NOT_FOUND")
    void install_notFound_returns404() throws Exception {
        Mockito.when(service.install("c-bogus"))
                .thenThrow(new CollectionNotFoundException("c-bogus"));

        mockMvc.perform(post("/api/v1/collections/c-bogus/install"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COLLECTION_NOT_FOUND"));
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: GET /collections/{id} on non-existent → 404 COLLECTION_NOT_FOUND")
    void get_notFound_returns404() throws Exception {
        Mockito.when(service.get("c-bogus"))
                .thenThrow(new CollectionNotFoundException("c-bogus"));

        mockMvc.perform(get("/api/v1/collections/c-bogus"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COLLECTION_NOT_FOUND"));
    }

    /**
     * Slice test fixture — Collection ctor 私有，factory 自帶 random UUID；test 不依賴
     * 特定 id，改用 name/category 等業務欄位做 assertion，path param 用實際 c.getId()。
     */
    private static Collection makeCollection(String name, String category) {
        return Collection.create(name, null, category, "alice", List.of("sk-1"));
    }
}
