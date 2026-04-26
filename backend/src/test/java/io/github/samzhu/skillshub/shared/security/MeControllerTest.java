package io.github.samzhu.skillshub.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
 * AC-4 + AC-5：{@code /api/v1/me} 的 JWT 驗證行為。
 *
 * <p>用 {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt() jwt()}
 * post-processor 注入 stub JWT，避免依賴真實 mock-oauth2-server（那是 T2 的 E2E 測試範圍）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: /api/v1/me 帶 admin token → 200 + 完整 claims JSON")
    void me_withAdminJwt_returnsAllClaims() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                .with(jwt().jwt(j -> j
                        .subject("admin-001")
                        .claim("roles", List.of("admin"))
                        .claim("groups", List.of("platform-admins", "skills-curators"))
                        .claim("company_id", "skills-hub-corp")
                        .claim("dept_id", "engineering")
                        .claim("scope", "skills:admin skills:read skills:write"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("admin-001"))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            .andExpect(jsonPath("$.groups[0]").value("platform-admins"))
            .andExpect(jsonPath("$.groups[1]").value("skills-curators"))
            .andExpect(jsonPath("$.companyId").value("skills-hub-corp"))
            .andExpect(jsonPath("$.deptId").value("engineering"))
            .andExpect(jsonPath("$.scope").value("skills:admin skills:read skills:write"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: /api/v1/me 無 token → 401 + WWW-Authenticate header")
    void me_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            // Spring Security RFC 6750 預設回傳 WWW-Authenticate: Bearer (realm 等)
            .andExpect(header().exists("WWW-Authenticate"));
    }
}
