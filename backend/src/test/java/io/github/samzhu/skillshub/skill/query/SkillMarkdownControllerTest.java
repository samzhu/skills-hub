package io.github.samzhu.skillshub.skill.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.SkillSuspendedException;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.query.FileBrowserService.FilePreview;

@WebMvcTest(SkillMarkdownController.class)
class SkillMarkdownControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileBrowserService fileBrowserService;

    private static final UUID SKILL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] SKILL_MD_BYTES = "# My Skill\nThis is the SKILL.md content.".getBytes();

    @Test
    @DisplayName("AC-1: 200 OK with text/markdown body + Cache-Control public max-age=60")
    @Tag("AC-1")
    void getSkillMarkdown_returnsMarkdownWithCacheControl() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(fileBrowserService.readFile(SKILL_ID.toString(), "SKILL.md"))
                .thenReturn(new FilePreview(SKILL_MD_BYTES, "text/markdown"));

        mockMvc.perform(get("/api/v1/skills/{id}/skill.md", SKILL_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().bytes(SKILL_MD_BYTES))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
    }

    @Test
    @DisplayName("AC-2: 401 when anonymous (no JWT)")
    @Tag("AC-2")
    void getSkillMarkdown_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/skills/{id}/skill.md", SKILL_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-3: 403 SKILL_SUSPENDED when skill is suspended")
    @Tag("AC-3")
    void getSkillMarkdown_suspended_returns403() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(fileBrowserService.readFile(eq(SKILL_ID.toString()), eq("SKILL.md")))
                .thenThrow(new SkillSuspendedException(SKILL_ID.toString()));

        mockMvc.perform(get("/api/v1/skills/{id}/skill.md", SKILL_ID).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-4: 404 when skill id does not exist")
    @Tag("AC-4")
    void getSkillMarkdown_notFound_returns404() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(fileBrowserService.readFile(eq(SKILL_ID.toString()), eq("SKILL.md")))
                .thenThrow(new NoSuchElementException("Skill not found: " + SKILL_ID));

        mockMvc.perform(get("/api/v1/skills/{id}/skill.md", SKILL_ID).with(jwt()))
                .andExpect(status().isNotFound());
    }
}
