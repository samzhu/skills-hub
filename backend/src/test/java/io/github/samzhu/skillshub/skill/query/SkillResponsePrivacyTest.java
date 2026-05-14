package io.github.samzhu.skillshub.skill.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;

@WebMvcTest(SkillQueryController.class)
class SkillResponsePrivacyTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillQueryService skillQueryService;

    @MockitoBean
    private BundleInfoQueryService bundleInfoQueryService;

    @MockitoBean
    private SkillDiffQueryService skillDiffQueryService;

    @MockitoBean
    private SkillFileDiffService skillFileDiffService;

    @MockitoBean
    private io.github.samzhu.skillshub.shared.security.UserResolver userResolver;

    @BeforeEach
    void allowAllPermissions() {
        Mockito.when(permissionEvaluator.hasPermission(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("S169 AC-9: detail JSON 不含 aclEntries")
    void detailDoesNotExposeAclEntries() throws Exception {
        var skillId = UUID.randomUUID().toString();
        Mockito.when(skillQueryService.findById(skillId)).thenReturn(skillWithAcl(skillId));

        mockMvc.perform(get("/api/v1/skills/{id}", skillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aclEntries").doesNotExist())
                .andExpect(jsonPath("$.viewerPermissions.canView").value(true));
    }

    @Test
    @DisplayName("S169 AC-9: list JSON 不含 aclEntries")
    void listDoesNotExposeAclEntries() throws Exception {
        var skillId = UUID.randomUUID().toString();
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(skillWithAcl(skillId))));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].aclEntries").doesNotExist());
    }

    private static Skill skillWithAcl(String skillId) {
        var now = Instant.now();
        return Skill.fromRow(skillId, "privacy-fixture", "fixture", "alice", "devops",
                        "1.0.0", null, "PUBLISHED", 0L, now, now,
                        List.of("user:alice:read", "user:alice:write", "user:alice:delete"), null)
                .withViewerPermissions(new ViewerPermissions(true, true, true, true, true, true, true));
    }
}
