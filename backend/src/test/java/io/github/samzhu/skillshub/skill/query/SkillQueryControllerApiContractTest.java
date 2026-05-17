package io.github.samzhu.skillshub.skill.query;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;

/**
 * S024 T6 AC-11 — API contract regression：S024 將 response type 從 SkillReadModel record 改為
 * Skill aggregate，但 JSON shape 必須與 v1.5.0 一致（@Version 不 expose；fields 同名同 type）。
 *
 * <p>用 jsonPath assertions 鎖定 shape — 比 snapshot 字串更穩定（Jackson 欄位排序可能 JVM 實作
 * 異動），且不引入第三方 snapshot library（per spec §6 anti-pattern 提示）。
 *
 * <p>S025b T03 — {@code @SpringBootTest + @AutoConfigureMockMvc + lab profile} → {@code @WebMvcTest}
 * slice + extends {@link WebMvcSliceTestBase} + {@code @MockitoBean SkillQueryService}：
 * 原 test 透過 POST 真 skill row 走 SkillCommandController → DB → 查 SkillQueryController 屬 E2E；
 * slice 後僅驗 query controller JSON contract（同 v1.5.0 shape + 無 internal version 欄位 expose），
 * service 層由 SkillVersionRepositoryTest 等 repo / service test 涵蓋（S167b 後 SkillAclQueryService 已刪）。
 */
@WebMvcTest(SkillQueryController.class)
class SkillQueryControllerApiContractTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillQueryService skillQueryService;

    @MockitoBean
    private BundleInfoQueryService bundleInfoQueryService;

    @MockitoBean
    private SkillDiffQueryService skillDiffQueryService;

    @MockitoBean
    private SkillFileDiffService skillFileDiffService;

    /** S154-T05: SkillQueryController 注入 UserResolver — slice 不 scan service，需 mock。 */
    @MockitoBean
    private io.github.samzhu.skillshub.shared.security.UserResolver userResolver;

    /**
     * S174: GET /skills/{id} 改為 @PostAuthorize；多數 JSON contract test 不關心 ACL 邏輯，
     * 預設放行避免 anonymous mock 的 hasPermission=false 讓 response 被 401 取代。
     */
    @BeforeEach
    void allowAllPermissions() {
        Mockito.when(permissionEvaluator.hasPermission(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("AC-11: GET /api/v1/skills/{id} JSON 含 v1.5.0 fields，無 internal version 欄位")
    @Tag("AC-11")
    void apiContractRegression_findById() throws Exception {
        // S126: @PathVariable UUID id — 需用合法 UUID 格式避免 MethodArgumentTypeMismatchException 400
        var skillId = UUID.randomUUID().toString();
        var fixture = Skill.fromRow(skillId, "contract-test", "fixture", "alice", "devops",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now(),
                List.of("user:alice:read", "user:alice:write", "user:alice:delete"), null);
        Mockito.when(skillQueryService.findById(skillId)).thenReturn(fixture);

        mockMvc.perform(get("/api/v1/skills/{id}", skillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(skillId))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.description").exists())
                .andExpect(jsonPath("$.author").value("alice"))
                .andExpect(jsonPath("$.category").value("devops"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.downloadCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.aclEntries").doesNotExist())
                // @Version internal optimistic lock 欄位不 expose
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    @DisplayName("AC-11: GET /api/v1/skills (search) Page<Skill> JSON shape — content[].id / pageable / totalElements")
    @Tag("AC-11")
    void apiContractRegression_search() throws Exception {
        var skillId = "search-" + uniqueSuffix();
        var fixture = Skill.fromRow(skillId, "search-test", "fixture", "alice", "testing",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now(),
                List.of(), null);
        Page<Skill> page = new PageImpl<>(List.of(fixture));
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.eq("testing"),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/skills").param("category", "testing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].version").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].name").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].status").exists());
    }

    @Test
    @DisplayName("AC-S174-1: anonymous GET missing UUID returns 404 NOT_FOUND before permission deny")
    @Tag("AC-S174-1")
    void s174_missingUuidReturns404BeforePermissionDeny() throws Exception {
        var skillId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(skillQueryService.findById(skillId.toString()))
                .thenThrow(new NoSuchElementException("Skill not found: " + skillId));

        mockMvc.perform(get("/api/v1/skills/{id}", skillId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Skill not found: " + skillId));
    }

    @Test
    @DisplayName("AC-S174-2: anonymous GET private existing skill still returns 401 without skill JSON")
    @Tag("AC-S174-2")
    void s174_privateExistingSkillStillReturns401WithoutSkillJson() throws Exception {
        var skillId = UUID.randomUUID();
        var fixture = Skill.fromRow(skillId.toString(), "private-s174", "private description",
                "alice", "testing", null, null, "PUBLISHED", 0L, Instant.now(), Instant.now(),
                List.of(), null);
        Mockito.when(skillQueryService.findById(skillId.toString())).thenReturn(fixture);
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/skills/{id}", skillId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("private-s174"))))
                .andExpect(content().string(not(containsString("private description"))))
                .andExpect(content().string(not(containsString("viewerPermissions"))));
    }

    @Test
    @DisplayName("AC-S185-4: list JSON keeps privacy contract while exposing filled list fields")
    @Tag("AC-S185-4")
    void listJsonKeepsPrivacyContractWhileExposingFilledListFields() throws Exception {
        var skillId = "s185-contract-" + uniqueSuffix();
        var fixture = Skill.fromRow(skillId, "s185-contract", "fixture", "alice", "testing",
                "Testing", "1.0.0", "LOW", "PUBLISHED", 0L, Instant.parse("2026-05-15T21:00:00Z"),
                Instant.parse("2026-05-15T21:01:00Z"), List.of("user:alice:read"), null,
                0.0, 0L, true)
                .withDetail(true, Instant.parse("2026-05-15T21:06:42Z"), "MIT",
                        List.of("codex"), 1L, 0L)
                .withViewerPermissions(new ViewerPermissions(true, true, true, true, true, true, true));
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(fixture)));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].verified").value(true))
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].latestVersionPublishedAt").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].versionCount").value(1))
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].ownerId").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].aclEntries").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].viewerPermissions").doesNotExist());
    }

    @Test
    @DisplayName("S159b AC-4: GET /skills?category=Testing controller 起手 lowercase → service 收到 'testing'")
    @Tag("AC-4")
    void s159b_searchCategoryParam_lowercasedBeforeServiceCall() throws Exception {
        // Mockito stub 僅在 service 收到 lowercase "testing" 時回 page；若 controller 沒 normalize
        // service 收到的會是 "Testing"，stub 不匹配 → 回 null → NPE / 500，test fail
        var skillId = "ac4-" + uniqueSuffix();
        var fixture = Skill.fromRow(skillId, "ac4-search", "fixture", "alice", "testing",
                null, null, "PUBLISHED", 0L, Instant.now(), Instant.now(),
                List.of(), null);
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.eq("testing"),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(fixture)));

        // Caller 送大寫 "Testing"；controller normalize 後 service 端拿到 "testing" → stub 命中
        mockMvc.perform(get("/api/v1/skills").param("category", "Testing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')]").exists());

        // Captor 驗 service 接到 normalize 後的值
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(skillQueryService).search(
                ArgumentMatchers.isNull(), captor.capture(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .as("controller 須 trim + toLowerCase 後傳 service")
                .isEqualTo("testing");
    }

    @Test
    @DisplayName("S159b AC-4: ?category= 帶 leading/trailing space + 大寫 → trim + lowercase")
    @Tag("AC-4")
    void s159b_searchCategoryParam_trimmedAndLowercased() throws Exception {
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.eq("devops"),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/skills").param("category", "  DEVOPS  "))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(skillQueryService).search(
                ArgumentMatchers.isNull(), captor.capture(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo("devops");
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
