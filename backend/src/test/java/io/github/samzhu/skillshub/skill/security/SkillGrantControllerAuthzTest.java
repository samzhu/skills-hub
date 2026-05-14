package io.github.samzhu.skillshub.skill.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
}
