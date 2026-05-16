package io.github.samzhu.skillshub.skill.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.NotSkillOwnerException;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

@WebMvcTest(SkillGrantController.class)
class SkillGrantControllerAuthzTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillGrantService service;

    @Test
    @DisplayName("S169 AC-10: owner GET /grants 回 200")
    void ownerCanListGrants() throws Exception {
        var skillId = "skill-1";
        Mockito.when(permissionEvaluator.hasPermission(
                        Mockito.any(), Mockito.eq(skillId), Mockito.eq("Skill"), Mockito.eq("read")))
                .thenReturn(true);
        Mockito.when(service.listGrants(skillId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills/{id}/grants", skillId)
                        .with(jwt()
                                .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S169 AC-10: editor GET /grants 回 403")
    void editorCannotListGrants() throws Exception {
        var skillId = "skill-1";
        Mockito.when(permissionEvaluator.hasPermission(
                        Mockito.any(), Mockito.eq(skillId), Mockito.eq("Skill"), Mockito.eq("read")))
                .thenReturn(true);
        Mockito.when(service.listGrants(skillId)).thenThrow(new NotSkillOwnerException());

        mockMvc.perform(get("/api/v1/skills/{id}/grants", skillId)
                        .with(jwt()
                                .jwt(j -> j.subject("bob").claim("roles", List.of("user")))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_SKILL_OWNER"));
    }

    @Test
    @DisplayName("AC-S184-4: owner DELETE /grants/{grantId} 回 204")
    void ownerRevokeGrant_returns204() throws Exception {
        var skillId = "skill-1";
        var grantId = "grant-1";

        mockMvc.perform(delete("/api/v1/skills/{id}/grants/{grantId}", skillId, grantId)
                        .with(jwt()
                                .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNoContent());

        Mockito.verify(service).revoke(skillId, grantId);
    }

    @Test
    @DisplayName("AC-S184-9: POST /grants principalType=public 回 400 並提示用 visibility endpoint")
    void publicGrantThroughGrantApi_returns400() throws Exception {
        var skillId = "skill-1";
        Mockito.when(service.grant(Mockito.eq(skillId), Mockito.any()))
                .thenThrow(new IllegalArgumentException(
                        "public grants must be changed through PUT /api/v1/skills/" + skillId + "/visibility"));

        mockMvc.perform(post("/api/v1/skills/{id}/grants", skillId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principalType\":\"public\",\"principalId\":\"*\",\"role\":\"VIEWER\"}")
                        .with(jwt()
                                .jwt(j -> j.subject("alice").claim("roles", List.of("user")))
                                .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "public grants must be changed through PUT /api/v1/skills/" + skillId + "/visibility"));
    }
}
