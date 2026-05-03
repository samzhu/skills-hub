package io.github.samzhu.skillshub.skill.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * service 層由 SkillVersionRepositoryTest / SkillAclQueryServiceTest 涵蓋。
 */
@WebMvcTest(SkillQueryController.class)
class SkillQueryControllerApiContractTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillQueryService skillQueryService;

    // S098a3-2 ship 後 SkillQueryController ctor 多了 BundleInfoQueryService dep；
    // @WebMvcTest slice 不掃 @Service，須顯式 @MockitoBean。本 test 僅驗 GET /skills/{id}
    // + GET /skills (search) JSON shape，不 cover bundle-info endpoint，stub return 不需。
    @MockitoBean
    private BundleInfoQueryService bundleInfoQueryService;

    @Test
    @DisplayName("AC-11: GET /api/v1/skills/{id} JSON 含 v1.5.0 fields，無 internal version 欄位")
    @Tag("AC-11")
    void apiContractRegression_findById() throws Exception {
        var skillId = "contract-" + uniqueSuffix();
        var fixture = Skill.fromRow(skillId, "contract-test", "fixture", "alice", "DevOps",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now(),
                List.of("user:alice:read", "user:alice:write", "user:alice:delete"), null);
        Mockito.when(skillQueryService.findById(skillId)).thenReturn(fixture);

        mockMvc.perform(get("/api/v1/skills/{id}", skillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(skillId))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.description").exists())
                .andExpect(jsonPath("$.author").value("alice"))
                .andExpect(jsonPath("$.category").value("DevOps"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.downloadCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.aclEntries").isArray())
                // S016 owner ACL auto-seeded — alice 三條 read/write/delete
                .andExpect(jsonPath("$.aclEntries[?(@ == 'user:alice:read')]").exists())
                .andExpect(jsonPath("$.aclEntries[?(@ == 'user:alice:write')]").exists())
                .andExpect(jsonPath("$.aclEntries[?(@ == 'user:alice:delete')]").exists())
                // @Version internal optimistic lock 欄位不 expose
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    @DisplayName("AC-11: GET /api/v1/skills (search) Page<Skill> JSON shape — content[].id / pageable / totalElements")
    @Tag("AC-11")
    void apiContractRegression_search() throws Exception {
        var skillId = "search-" + uniqueSuffix();
        var fixture = Skill.fromRow(skillId, "search-test", "fixture", "alice", "Testing",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now(),
                List.of(), null);
        Page<Skill> page = new PageImpl<>(List.of(fixture));
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.eq("Testing"),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/skills").param("category", "Testing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].version").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].name").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].status").exists());
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
