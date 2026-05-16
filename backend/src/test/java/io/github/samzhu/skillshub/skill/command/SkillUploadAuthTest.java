package io.github.samzhu.skillshub.skill.command;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.security.SkillGrantService;

/**
 * S139 AC-6 — Anonymous {@code POST /api/v1/skills/upload} 與
 * {@code POST /api/v1/skills}（create）必須回 401，避免未登入用戶
 * 發佈技能（per S139 spec §2.4 + §3 AC-6）。
 *
 * <p>採 path-based {@code .requestMatchers(...).authenticated()} 對齊
 * {@link io.github.samzhu.skillshub.shared.security.SecurityConfig} 既有
 * /me /notifications /admin /dev pattern，由 SecurityFilterChain
 * 統一管控；OAuth2 Resource Server 預設 entry point 對未認證請求回 401。
 *
 * <p>對照 {@link SkillCommandControllerSecurityTest}（已驗 ACL hasPermission gate）：
 * 該 test 走 jwt-authenticated 路徑驗 PUT /skills/{id}/versions；本 test 補
 * anonymous 路徑驗 POST upload / create endpoint。
 */
@WebMvcTest(SkillCommandController.class)
class SkillUploadAuthTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private SkillGrantService skillGrantService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("AC-6: anonymous POST /api/v1/skills/upload → 401")
    @Tag("AC-6")
    void anonymousUpload_returns401() throws Exception {
        mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", new byte[]{1, 2, 3}))
                .param("skillName", "auth-test")
                .param("version", "1.0.0")
                .param("author", "alice")
                .param("category", "devops"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-6: anonymous POST /api/v1/skills → 401")
    @Tag("AC-6")
    void anonymousCreate_returns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/skills")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"test\",\"author\":\"alice\",\"category\":\"DevOps\"}"))
            .andExpect(status().isUnauthorized());
    }
}
