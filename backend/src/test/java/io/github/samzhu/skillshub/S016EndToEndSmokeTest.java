package io.github.samzhu.skillshub;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * S016 端到端 smoke — 跨模組驗證完整 ACL flow（upload → grant → list → revoke）。
 *
 * <p>對應 spec §6 E2E Smoke：覆蓋 ApplicationContext wiring + 真實 Spring Security
 * filter chain + Testcontainer JSONB。每個 step 對 HTTP / event store / read model 三層做斷言，
 * 確保 spec 整體交付（而非單獨 AC 對 single test）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class S016EndToEndSmokeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DomainEventRepository eventStore;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });
    }

    @Test
    @DisplayName("AC-1~15: end-to-end smoke — upload → grant → list → revoke 跨模組驗證")
    @Tag("AC-1")
    @Tag("AC-7")
    @Tag("AC-9")
    @Tag("AC-10")
    @Tag("AC-11")
    void e2e_uploadGrantListRevoke_acrossModules() throws Exception {
        var skillName = "e2e-smoke-" + UUID.randomUUID().toString().substring(0, 8);
        var zipBytes = createValidSkillZip(skillName);

        // (1) alice upload skill — multipart → SkillCreated + SkillVersionPublished events
        var uploadResponse = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", zipBytes))
                .param("version", "1.0.0")
                .param("author", "alice")
                .param("category", "Testing")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        var body = new tools.jackson.databind.json.JsonMapper()
                .readValue(uploadResponse.getResponse().getContentAsString(), java.util.Map.class);
        var skillId = (String) body.get("id");
        assertThat(skillId).isNotBlank();

        // 驗 event store 有 SkillCreated + SkillVersionPublished
        var afterUploadEvents = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(afterUploadEvents).extracting("eventType")
                .contains("SkillCreated", "SkillVersionPublished");

        // 驗 vector_store row 含 acl_entries 衍生自 author
        var vectorAcl = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE skill_id = ?",
                String.class, skillId);
        assertThat(vectorAcl).contains("user:alice:read");

        // 驗 skills.acl_entries 已含 author 三條（read/write/delete）
        var skillAcl = jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = ?",
                String.class, skillId);
        assertThat(skillAcl)
                .contains("user:alice:read")
                .contains("user:alice:write")
                .contains("user:alice:delete");

        // (2) alice grant ACL group:engineering:read → 201 + SkillAclGranted event
        mockMvc.perform(post("/api/v1/skills/" + skillId + "/acl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"group\",\"principal\":\"engineering\",\"permission\":\"read\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isCreated());

        // skills.acl_entries 已 append group:engineering:read
        var afterGrantSkillAcl = jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = ?",
                String.class, skillId);
        assertThat(afterGrantSkillAcl).contains("group:engineering:read");

        // (3) carol（groups=["engineering"]）GET /acl → 200（透過 group: principal pattern 命中）
        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("carol")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.of("engineering")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='group' && @.principal=='engineering' && @.permission=='read')]").exists());

        // (4) bob（無權）GET /acl → 403
        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());

        // (5) alice revoke group:engineering:read → 204
        mockMvc.perform(delete("/api/v1/skills/" + skillId + "/acl")
                .param("type", "group")
                .param("principal", "engineering")
                .param("permission", "read")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNoContent());

        // 驗最終狀態：skills.acl_entries 不含 group:engineering:read，但仍含 alice 三條
        var finalSkillAcl = jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = ?",
                String.class, skillId);
        assertThat(finalSkillAcl)
                .doesNotContain("group:engineering:read")
                .contains("user:alice:read")
                .contains("user:alice:write")
                .contains("user:alice:delete");

        // event store sequence 連續遞增（無 hardcoded 衝突）— SkillCreated(1) + SkillVersionPublished(2)
        // + SkillAclGranted(3) + SkillAclRevoked(4) = 4 events (含 PUT 路徑可能多 SkillVersionPublished)
        var allEvents = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        for (int i = 0; i < allEvents.size(); i++) {
            assertThat(allEvents.get(i).sequence()).as("sequence at idx " + i).isEqualTo((long) (i + 1));
        }
        // 至少含 4 個事件類型
        assertThat(allEvents).extracting("eventType")
                .contains("SkillCreated", "SkillVersionPublished",
                        "SkillAclGranted", "SkillAclRevoked");
    }

    @Test
    @DisplayName("AC-7: PUT /skills/{id}/versions 對 alice owner 通過 @PreAuthorize；對 bob 非 owner 403")
    @Tag("AC-7")
    void e2e_putVersion_acl_gate() throws Exception {
        var skillName = "put-acl-" + UUID.randomUUID().toString().substring(0, 8);
        var initialZip = createValidSkillZip(skillName);

        // alice upload v1.0.0
        var uploadResponse = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", initialZip))
                .param("version", "1.0.0")
                .param("author", "alice")
                .param("category", "Testing")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isCreated())
                .andReturn();
        var body = new tools.jackson.databind.json.JsonMapper()
                .readValue(uploadResponse.getResponse().getContentAsString(), java.util.Map.class);
        var skillId = (String) body.get("id");

        var v2Zip = createValidSkillZip(skillName);

        // alice PUT v1.1.0 — 通過 ACL gate
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", v2Zip))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk());   // QA finding fix：硬斷 200，避免 500 silently pass

        // bob PUT — 403 Forbidden
        var v3Zip = createValidSkillZip(skillName);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", v3Zip))
                .param("version", "2.0.0")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    private byte[] createValidSkillZip(String skillName) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var content = "---\nname: " + skillName + "\ndescription: e2e smoke test\n---\n# " + skillName;
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static float[] randomVector(int dim) {
        var v = new float[dim];
        var r = new Random(42);
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextFloat() * 2 - 1;
        }
        return v;
    }
}
