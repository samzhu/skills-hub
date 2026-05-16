package io.github.samzhu.skillshub.skill.command;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import io.github.samzhu.skillshub.skill.security.SkillGrantService;

/**
 * S018 T4 — POST {@code /api/v1/skills/{id}/suspend} 與 {@code /reactivate} 端點安全行為驗證。
 *
 * <p>對應 spec §3 AC-12：admin（acl_entries 含 role:admin:suspend）→ 200；alice（無 verb）→ 403。
 *
 * <p>S025b T03 split — {@code @SpringBootTest + DB seed + Awaitility audit assertion} →
 * {@code @WebMvcTest} slice 拆解（per spec §2.3）：保留 HTTP route + auth + {@code @PreAuthorize} gate
 * （mock {@link org.springframework.security.access.PermissionEvaluator}）；DB state assertion +
 * async SkillSuspended/SkillReactivated audit assertion 移除（已 covered by SkillSuspendReactivateTest
 * @SpringBootTest deviation 整合測試 + S016EndToEndSmokeTest E2E）。
 */
@WebMvcTest(SkillCommandController.class)
class SkillSuspendControllerSecurityTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private SkillGrantService skillGrantService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("AC-12: alice (無 suspend verb) POST /skills/{id}/suspend → 403 Forbidden")
    @Tag("AC-12")
    void aliceSuspend_returns403() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("suspend")))
                .thenReturn(false);

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"my own\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:suspend) POST /skills/{id}/suspend → 200 OK")
    @Tag("AC-12")
    void adminSuspend_returns200() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("suspend")))
                .thenReturn(true);

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"policy violation\"}")
                .with(jwt()
                        .jwt(j -> j.subject("admin-user").claim("roles", List.of("admin")))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-12: alice (無 reactivate verb) POST /skills/{id}/reactivate → 403")
    @Tag("AC-12")
    void aliceReactivate_returns403() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("reactivate")))
                .thenReturn(false);

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/reactivate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"i want it back\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:reactivate) POST /skills/{id}/reactivate → 200 OK")
    @Tag("AC-12")
    void adminReactivate_returns200() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("reactivate")))
                .thenReturn(true);

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/reactivate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual review approved\"}")
                .with(jwt()
                        .jwt(j -> j.subject("admin-user").claim("roles", List.of("admin")))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk());
    }
}
