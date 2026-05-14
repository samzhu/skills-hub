package io.github.samzhu.skillshub.skill.command;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S016 AC-7 — {@link SkillCommandController#addVersion} 加 {@code @PreAuthorize}
 * 之後的 row-level ACL gate 行為驗證。
 *
 * <p>對應 spec §4.13：PUT {@code /api/v1/skills/{id}/versions} 需 {@code hasPermission(#id, 'Skill', 'write')}；
 * acl_entries 含 {@code user:alice:write} 的 skill 對 alice 開放、對 bob 拒絕。
 *
 * <p>S025b T03 split — {@code @SpringBootTest + DB seed} → {@code @WebMvcTest} slice 拆解（per spec §2.3）：
 * 保留 HTTP route + auth filter (JWT decode) + {@code @PreAuthorize} gate（mock {@link
 * org.springframework.security.access.PermissionEvaluator} return → 200 vs 403）；DB seed +
 * async event assertion 移除（已 covered by S016EndToEndSmokeTest E2E + SkillAclCommandServiceTest 整合）。
 *
 * <p>採 MockMvc {@code .with(jwt())} 合成 {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}；
 * 此 path 不過 {@code JwtAuthenticationConverter}（生產 path 由 E2E 測試覆蓋），
 * 故須顯式 set {@code .authorities(ROLE_xxx)} 對齊 production 行為。
 */
@WebMvcTest(SkillCommandController.class)
class SkillCommandControllerSecurityTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("AC-7: alice (user:alice:write 已 grant) PUT /skills/{id}/versions → 通過 @PreAuthorize gate（非 403）")
    @Tag("AC-7")
    void ownerPutVersion_passesAuthorizationGate() throws Exception {
        var skillId = "test-skill-id";
        // mock evaluator → 'write' permission granted；@PreAuthorize gate 通過
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(true);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", new byte[]{1, 2, 3}))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 403) {
                        throw new AssertionError(
                                "alice 應通過 @PreAuthorize gate 但實得 403 — ACL evaluator 路由錯誤");
                    }
                });
    }

    @Test
    @DisplayName("AC-7: bob (無 user:bob:write) PUT /skills/{id}/versions → 403 Forbidden")
    @Tag("AC-7")
    void nonOwnerPutVersion_returns403() throws Exception {
        var skillId = "test-skill-id";
        // mock evaluator → 'write' permission denied；@PreAuthorize gate 擋下回 403
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(false);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", new byte[]{1, 2, 3}))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                        .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());

        Mockito.verify(skillCommandService, Mockito.never())
                .addVersion(ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("S169 AC-15: write 通過後版本重複 → 409 VERSION_EXISTS")
    @Tag("S169-AC-15")
    void authorizedDuplicateVersion_returns409() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(currentUserProvider.current()).thenReturn(
                new io.github.samzhu.skillshub.shared.security.CurrentUser(
                        "alice", "alice-sub", "Alice", "alice@example.com", "alice",
                        List.of("user"), List.of(), null));
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(true);
        Mockito.doThrow(new VersionExistsException("Version 1.1.0 already exists"))
                .when(skillCommandService)
                .addVersion(ArgumentMatchers.eq(skillId), ArgumentMatchers.any(byte[].class),
                        ArgumentMatchers.eq("1.1.0"), ArgumentMatchers.eq("Alice"));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", new byte[]{1, 2, 3}))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_EXISTS"));
    }

    @Test
    @DisplayName("AC-S144-2: alice (user:alice:delete 已 grant) DELETE /skills/{id} → 204 No Content")
    @Tag("AC-S144-2")
    void ownerDelete_returns204() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("delete")))
                .thenReturn(true);

        mockMvc.perform(delete("/api/v1/skills/" + skillId)
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNoContent());

        Mockito.verify(skillCommandService).deleteSkill(skillId, "alice");
    }

    @Test
    @DisplayName("AC-S144-2: bob (無 delete verb) DELETE /skills/{id} → 403 Forbidden")
    @Tag("AC-S144-2")
    void nonOwnerDelete_returns403() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("delete")))
                .thenReturn(false);

        mockMvc.perform(delete("/api/v1/skills/" + skillId)
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());

        Mockito.verify(skillCommandService, Mockito.never())
                .deleteSkill(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("S169 AC-11: editor 有 write permission 可 PUT /skills/{id}")
    @Tag("S169-AC-11")
    void editorUpdate_returns200() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(currentUserProvider.userId()).thenReturn("bob");
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("write")))
                .thenReturn(true);

        mockMvc.perform(put("/api/v1/skills/" + skillId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"updated by editor\",\"category\":\"devops\"}")
                        .with(jwt()
                                .jwt(j -> j.subject("bob")
                                        .claim("roles", List.of("user"))
                                        .claim("groups", List.<String>of()))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk());

        Mockito.verify(skillCommandService).updateSkill(
                ArgumentMatchers.eq(skillId), ArgumentMatchers.any(UpdateSkillCommand.class), ArgumentMatchers.eq("bob"));
    }

    @Test
    @DisplayName("AC-S144-3: DELETE /skills/{id} target 不存在 → 404 NOT_FOUND")
    @Tag("AC-S144-3")
    void deleteMissingSkill_returns404() throws Exception {
        var skillId = "missing-skill-id";
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");
        Mockito.when(permissionEvaluator.hasPermission(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(skillId),
                        ArgumentMatchers.eq("Skill"), ArgumentMatchers.eq("delete")))
                .thenReturn(true);
        Mockito.doThrow(new NoSuchElementException("Skill not found: " + skillId))
                .when(skillCommandService).deleteSkill(skillId, "alice");

        mockMvc.perform(delete("/api/v1/skills/" + skillId)
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
