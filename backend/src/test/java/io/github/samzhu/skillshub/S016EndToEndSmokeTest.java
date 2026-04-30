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
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    // S025a-T03: 移除 @MockitoBean EmbeddingModel — TestcontainersConfiguration.@Bean @Primary
    // mockEmbeddingModel() 提供共用 stub。本檔的 disabled e2e test 不直接 inject embeddingModel；
    // 若日後重啟用此 test，可改 @Autowired 注入 lifted mock。

    @org.junit.jupiter.api.Disabled("""
            S023-T07 + S025a-T03 deferred to S025b: MockMvc + @ApplicationModuleListener async 行為
            在 SpringBootTest WebEnvironment.MOCK 下不可靠。S025a-T03 已移除 EmbeddingModel @MockitoBean
            （cache key 收斂）但本 e2e test 的完整重寫（含跨 module 整合 + 多 step + JWT auth）需要
            較大改動，per spec §3 AC-6 allow deferral：屬 S025b WebEnv refactor 範圍。
            S023 outbox + listener migration 的功能已由以下 test 分散覆蓋：
            - EventPublicationOutboxBehaviorTest (TX rollback + listener fail → status=FAILED)
            - IncompleteEventRepublishTaskWiringTest (retry 機制 wiring)
            - SearchProjectionListenerAnnotationsTest / SkillProjectionListenerAnnotationsTest
              / AnalyticsProjectionListenerAnnotationsTest / ScanOrchestratorListenerAnnotationsTest
              (annotation reflection)
            - HikariPoolUnderLoadTest (50 並發 listener 不耗盡 pool)
            - RiskAssessmentIntegrationTest (S025a-T02 改 Scenario，e2e ScanOrchestrator pipeline)
            - SemanticSearchAclTest (HTTP + ACL e2e via MockMvc + JWT — S025a-T03 移除 mock)
            S025b 計畫：改 @ApplicationModuleTest + Scenario 模式驗 e2e flow，去掉 MockMvc + async race。
            """)
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

        // 驗 event store 有 SkillCreated + SkillVersionPublished — S024 T05B 改 async via AuditEventListener
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var afterUploadEvents = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    assertThat(afterUploadEvents).extracting("eventType")
                            .contains("SkillCreated", "SkillVersionPublished");
                });

        // 驗 vector_store row 含 acl_entries 衍生自 author
        // S023-T07: SearchProjection 改 @ApplicationModuleListener async；用 Awaitility 等
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var vectorAcl = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM vector_store WHERE skill_id = ?",
                            String.class, skillId);
                    assertThat(vectorAcl).contains("user:alice:read");
                });

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

        // skills.acl_entries 已 append group:engineering:read（async listener）
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var afterGrantSkillAcl = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM skills WHERE id = ?",
                            String.class, skillId);
                    assertThat(afterGrantSkillAcl).contains("group:engineering:read");
                });

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

        // 驗最終狀態：skills.acl_entries 不含 group:engineering:read，但仍含 alice 三條（async）
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var finalSkillAcl = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM skills WHERE id = ?",
                            String.class, skillId);
                    assertThat(finalSkillAcl)
                            .doesNotContain("group:engineering:read")
                            .contains("user:alice:read")
                            .contains("user:alice:write")
                            .contains("user:alice:delete");
                });

        // S024 T05B: AuditEventListener async 寫入，sequence 順序不再嚴格遞增（可能 race）；
        // 改驗各 event_type 至少出現一次
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var allEvents = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    assertThat(allEvents).extracting("eventType")
                            .contains("SkillCreated", "SkillVersionPublished",
                                    "SkillAclGranted", "SkillAclRevoked");
                });
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

    // S025a-T03: randomVector helper removed — lifted to TestcontainersConfiguration.
}
