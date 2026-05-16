package io.github.samzhu.skillshub.skill.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Visibility;
import io.github.samzhu.skillshub.skill.security.SkillGrantService;

/**
 * S163 AC-1 / AC-2 / AC-3：PUT /api/v1/skills/{id} update metadata 行為驗證。
 *
 * <p>S025b T03 pattern — {@code @WebMvcTest} slice 走 mocked {@link
 * org.springframework.security.access.PermissionEvaluator}（{@code WebMvcSliceTestBase} 提供）
 * 控制 200 vs 403；DB / aggregate 行為由 unit / 整合測涵蓋。
 */
@WebMvcTest(SkillCommandController.class)
class SkillUpdateControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private SkillGrantService skillGrantService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("AC-1: owner PUT /skills/{id} body={description:'new'} → 200 + service 收到 cmd")
    @Tag("AC-1")
    void ownerUpdate_returns200() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");
        Mockito.when(permissionEvaluator.hasPermission(
                        any(), eq(skillId), eq("Skill"), eq("write")))
                .thenReturn(true);

        mockMvc.perform(put("/api/v1/skills/" + skillId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"new desc\",\"category\":\"security\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
            .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateSkillCommand.class);
        Mockito.verify(skillCommandService).updateSkill(eq(skillId), captor.capture(), eq("alice"));
        var cmd = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(cmd.description()).isEqualTo("new desc");
        org.assertj.core.api.Assertions.assertThat(cmd.category()).isEqualTo("security");
    }

    @Test
    @DisplayName("AC-2: 非 owner (write permission denied) PUT → 403 Forbidden")
    @Tag("AC-2")
    void nonOwnerUpdate_returns403() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(permissionEvaluator.hasPermission(
                        any(), eq(skillId), eq("Skill"), eq("write")))
                .thenReturn(false);

        mockMvc.perform(put("/api/v1/skills/" + skillId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"hijack\"}")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
            .andExpect(status().isForbidden());

        Mockito.verify(skillCommandService, Mockito.never())
                .updateSkill(Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    @DisplayName("AC-3: PUT body 含 {name, version} → Jackson silently drop；DTO 只剩 description/category 為 null")
    @Tag("AC-3")
    void nameAndVersionAreImmutableViaDto() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(currentUserProvider.userId()).thenReturn("alice");
        Mockito.when(permissionEvaluator.hasPermission(
                        any(), eq(skillId), eq("Skill"), eq("write")))
                .thenReturn(true);

        mockMvc.perform(put("/api/v1/skills/" + skillId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"hijack-name\",\"version\":\"99.0.0\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
            .andExpect(status().isOk());

        // 證明 name / version 沒滲入 service — captured cmd 兩個欄位皆 null
        var captor = ArgumentCaptor.forClass(UpdateSkillCommand.class);
        Mockito.verify(skillCommandService).updateSkill(eq(skillId), captor.capture(), eq("alice"));
        var cmd = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(cmd.description()).isNull();
        org.assertj.core.api.Assertions.assertThat(cmd.category()).isNull();
    }

    @Test
    @DisplayName("AC-S184-10: owner PUT /skills/{id}/visibility → 200 + service 收到 PRIVATE")
    @Tag("AC-S184-10")
    void ownerSetVisibility_returns200() throws Exception {
        var skillId = "test-skill-id";
        Mockito.when(skillGrantService.setVisibility(eq(skillId), eq(Visibility.PRIVATE)))
                .thenReturn(new SkillGrantService.VisibilityResult(
                        skillId, Visibility.PRIVATE, java.time.Instant.parse("2026-05-16T00:00:00Z")));

        mockMvc.perform(put("/api/v1/skills/" + skillId + "/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visibility\":\"PRIVATE\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
            .andExpect(status().isOk());

        Mockito.verify(skillGrantService).setVisibility(eq(skillId), eq(Visibility.PRIVATE));
    }
}
