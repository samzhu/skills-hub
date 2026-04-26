package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * AC-8：驗證 OAuth2 Resource Server starter 加入後，S001~S010 既有匿名 endpoint 仍可訪問。
 *
 * <p>遵循 CLAUDE.md「Feature First, Security Later」：本 spec 不應打斷既有 API 的匿名可達性。
 * SecurityConfig 用 {@code anyRequest().permitAll()} 兜底，確保只有 {@code /api/v1/me} 與
 * {@code /api/v1/admin/**} 受 JWT 驗證影響。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SkillsApiAnonymousTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: GET /api/v1/skills 無 token → 200 (S001 行為保留)")
    void skillsApi_withoutJwt_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isOk());
    }
}
