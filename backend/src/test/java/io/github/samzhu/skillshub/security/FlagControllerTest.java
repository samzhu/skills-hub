package io.github.samzhu.skillshub.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * AC-4：社群回報 endpoint 契約驗證 — {@code POST /api/v1/skills/{id}/flags} +
 * {@code GET /api/v1/skills/{id}/flags}。
 *
 * <p>S025b T03 — {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate} → {@code @WebMvcTest}
 * slice + extends {@link WebMvcSliceTestBase} + {@code @MockitoBean FlagService}：原 test
 * 透過真 POST skill row + flag service 驗 SkillFlagged event store 屬 E2E（service + event store），
 * slice 後僅驗 controller HTTP 契約 + service 注入；FlagService 業務邏輯（含 SkillFlagged event）
 * 由 service 層 unit/integration test 涵蓋。
 */
@WebMvcTest(FlagController.class)
class FlagControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlagService flagService;

    @Test
    @DisplayName("AC-4: 社群回報 — POST /flags → 201 + flag id")
    void createFlag() throws Exception {
        Mockito.when(flagService.createFlag(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("flag-id-123");

        mockMvc.perform(post("/api/v1/skills/some-skill-id/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"SECURITY","description":"可疑的外部連線"}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("flag-id-123"));
    }

    @Test
    @DisplayName("AC-4: 查詢 — GET /flags → 200 + 對應 read model list")
    void getFlags() throws Exception {
        var fixture = new FlagReadModel(
                "flag-1", "skill-1", "SECURITY", "可疑連線", "anonymous", Instant.now(), "OPEN");
        Mockito.when(flagService.getFlagsBySkillId(ArgumentMatchers.any()))
                .thenReturn(List.of(fixture));

        mockMvc.perform(get("/api/v1/skills/skill-1/flags"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("flag-1"))
            .andExpect(jsonPath("$[0].type").value("SECURITY"))
            .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("S072: type 不在白名單 → service 拋 IllegalArgumentException → 400")
    void rejectInvalidType() throws Exception {
        Mockito.when(flagService.createFlag(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Flag type must be one of [...] (got: bogus)"));

        mockMvc.perform(post("/api/v1/skills/some-skill-id/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"bogus","description":"x"}
                        """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("S075: GET /flags 回應 JSON 不應出現 Persistable.isNew() 的 'new' 欄位")
    void getFlagsExcludesIsNewArtifact() throws Exception {
        var fixture = new FlagReadModel(
                "flag-1", "skill-1", "SECURITY", "test", "anonymous", Instant.now(), "OPEN");
        Mockito.when(flagService.getFlagsBySkillId(ArgumentMatchers.any()))
                .thenReturn(List.of(fixture));

        mockMvc.perform(get("/api/v1/skills/skill-1/flags"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].new").doesNotExist())
            .andExpect(jsonPath("$[0].id").value("flag-1"));
    }

    @Test
    @DisplayName("S072: description 超 500 字元 → service 拋 IllegalArgumentException → 400")
    void rejectLongDescription() throws Exception {
        Mockito.when(flagService.createFlag(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Flag description exceeds 500 characters"));

        mockMvc.perform(post("/api/v1/skills/some-skill-id/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"security","description":"x"}
                        """))
            .andExpect(status().isBadRequest());
    }
}
