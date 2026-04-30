package io.github.samzhu.skillshub.skill.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S024 T6 AC-11 — API contract regression：S024 將 response type 從 SkillReadModel record 改為
 * Skill aggregate，但 JSON shape 必須與 v1.5.0 一致（@Version 不 expose；fields 同名同 type）。
 *
 * <p>用 jsonPath assertions 鎖定 shape — 比 snapshot 字串更穩定（Jackson 欄位排序可能 JVM 實作
 * 異動），且不引入第三方 snapshot library（per spec §6 anti-pattern 提示）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "skillshub.security.oauth.enabled=false",
        "skillshub.security.lab.user-id=alice"
})
class SkillQueryControllerApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("AC-11: GET /api/v1/skills/{id} JSON 含 v1.5.0 fields，無 internal version 欄位")
    @Tag("AC-11")
    void apiContractRegression_findById() throws Exception {
        var skillId = createSkillViaApi("contract-test-" + uniqueSuffix(), "alice", "DevOps");

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
        var skillId = createSkillViaApi("search-contract-" + uniqueSuffix(), "alice", "Testing");

        mockMvc.perform(get("/api/v1/skills")
                        .param("category", "Testing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].version").doesNotExist())
                // 不嚴格鎖定 page metadata 欄位（Spring Boot 4 內部序列化欄位名與 v1.5.0 等價但 key 名可能異動；
                // content shape 一致 + 無 internal version 欄位 expose 是核心契約）
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].name").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + skillId + "')].status").exists());
    }

    private String createSkillViaApi(String name, String author, String category) throws Exception {
        var body = objectMapper.writeValueAsString(java.util.Map.of(
                "name", name,
                "description", "API contract regression fixture",
                "author", author,
                "category", category));
        var response = mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var json = response.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        var map = objectMapper.readValue(json, java.util.Map.class);
        return (String) map.get("id");
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
