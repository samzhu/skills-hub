package io.github.samzhu.skillshub.skill.command;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 T4 — POST/DELETE {@code /api/v1/skills/{id}/acl} endpoints 行為驗證。
 *
 * <p>對應 spec §4.12：grant 端點 201 Created；revoke 端點 204 No Content；
 * 無 write 權限呼叫者 → 403 Forbidden（{@code @PreAuthorize} gate）。
 *
 * <p>S024 T05B：seed 改直接 save Skill aggregate（取代 read-model + event store dual seed）；
 * audit event 斷言加 Awaitility wrap（AuditEventListener async 寫入 domain_events）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SkillAclControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private DomainEventRepository eventStore;

    @Test
    @DisplayName("AC-9: alice (write) POST /skills/{id}/acl → 201 + SkillAclGranted event 寫入")
    @Tag("AC-9")
    void grantAcl_ownerPost_returns201AndPersistsEvent() throws Exception {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/acl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"group\",\"principal\":\"engineering\",\"permission\":\"read\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isCreated());

        // S024 T05B: AuditEventListener async 寫 domain_events row — Awaitility 等
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    var grantedExists = events.stream()
                            .anyMatch(e -> "SkillAclGranted".equals(e.eventType())
                                    && "engineering".equals(e.payload().get("principal")));
                    org.assertj.core.api.Assertions.assertThat(grantedExists).isTrue();
                });
    }

    @Test
    @DisplayName("AC-10: alice (write) DELETE /skills/{id}/acl?type=...&principal=...&permission=... → 204 + SkillAclRevoked event")
    @Tag("AC-10")
    void revokeAcl_ownerDelete_returns204AndPersistsEvent() throws Exception {
        // seed skill 含目標 entry — controller 端 revoke 會找到
        var skillId = seedSkill(List.of(
                "user:alice:read", "user:alice:write", "group:engineering:read"));

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

        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    var revokedExists = events.stream()
                            .anyMatch(e -> "SkillAclRevoked".equals(e.eventType())
                                    && "engineering".equals(e.payload().get("principal")));
                    org.assertj.core.api.Assertions.assertThat(revokedExists).isTrue();
                });
    }

    @Test
    @DisplayName("AC-11: alice (read) GET /skills/{id}/acl → 200 + 解析後的 entry list")
    @Tag("AC-11")
    void listAcl_owner_returns200WithEntries() throws Exception {
        var skillId = seedSkill(List.of(
                "user:alice:read", "user:alice:write", "group:engineering:read"));

        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.type=='user' && @.principal=='alice' && @.permission=='read')]").exists())
                .andExpect(jsonPath("$[?(@.type=='group' && @.principal=='engineering' && @.permission=='read')]").exists());
    }

    @Test
    @DisplayName("AC-11: carol（無任何 ACL）GET /skills/{id}/acl → 403 Forbidden")
    @Tag("AC-11")
    void listAcl_nonReader_returns403() throws Exception {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));

        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("carol")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-7: bob (無 write) POST /skills/{id}/acl → 403 Forbidden")
    @Tag("AC-7")
    void grantAcl_nonOwner_returns403() throws Exception {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/acl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"group\",\"principal\":\"engineering\",\"permission\":\"read\"}")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    /**
     * Seed Skill aggregate 含目標 acl_entries — S024 T05B 取代 read-model + event store dual seed。
     */
    private String seedSkill(List<String> aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                id,
                "acl-ctrl-" + id.substring(0, 8),
                "ACL controller test fixture",
                "alice",
                "Testing",
                "1.0.0",
                "LOW",
                "PUBLISHED",
                0L,
                now, now,
                aclEntries,
                null));
        return id;
    }
}
