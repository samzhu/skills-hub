package io.github.samzhu.skillshub.skill.command;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.query.SkillAclQueryService;

/**
 * S016 T4 — POST/DELETE/GET {@code /api/v1/skills/{id}/acl} endpoints 行為驗證。
 *
 * <p>對應 spec §4.12：grant 端點 201 Created；revoke 端點 204 No Content；
 * 無 write 權限呼叫者 → 403 Forbidden（{@code @PreAuthorize} gate）。
 *
 * <p>S025b T03 split — {@code @SpringBootTest + DB seed + Awaitility audit} → {@code @WebMvcTest}
 * slice 拆解（per spec §2.3）：保留 HTTP route + auth + {@code @PreAuthorize} gate；
 * SkillAclGranted/Revoked event store assertion 移除（已 covered by SkillAclCommandServiceTest
 * @SpringBootTest deviation 整合測試 + AuditEventListenerTest MODULE）。
 */
@WebMvcTest(SkillAclController.class)
class SkillAclControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService commandService;

    @MockitoBean
    private SkillAclQueryService queryService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("AC-9: alice (write) POST /skills/{id}/acl → 201")
    @Tag("AC-9")
    void grantAcl_ownerPost_returns201() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(true);
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/acl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"group\",\"principal\":\"engineering\",\"permission\":\"read\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-10: alice (write) DELETE /skills/{id}/acl?... → 204")
    @Tag("AC-10")
    void revokeAcl_ownerDelete_returns204() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(true);
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");

        mockMvc.perform(delete("/api/v1/skills/" + skillId + "/acl")
                .param("type", "group")
                .param("principal", "engineering")
                .param("permission", "read")
                .with(jwt()
                        .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-11: alice (read) GET /skills/{id}/acl → 200 + 解析後 entry list")
    @Tag("AC-11")
    void listAcl_owner_returns200WithEntries() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("read")))
                .thenReturn(true);
        Mockito.when(queryService.listEntries(skillId))
                .thenReturn(List.of(
                        new io.github.samzhu.skillshub.skill.query.AclEntryResponse(
                                "user", "alice", "read"),
                        new io.github.samzhu.skillshub.skill.query.AclEntryResponse(
                                "user", "alice", "write"),
                        new io.github.samzhu.skillshub.skill.query.AclEntryResponse(
                                "group", "engineering", "read")));

        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
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
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("read")))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/skills/" + skillId + "/acl")
                .with(jwt()
                        .jwt(j -> j.subject("carol").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-7: bob (無 write) POST /skills/{id}/acl → 403 Forbidden")
    @Tag("AC-7")
    void grantAcl_nonOwner_returns403() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(false);

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/acl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"group\",\"principal\":\"engineering\",\"permission\":\"read\"}")
                .with(jwt()
                        .jwt(j -> j.subject("bob").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }
}
