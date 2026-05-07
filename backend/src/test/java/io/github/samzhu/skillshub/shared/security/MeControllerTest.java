package io.github.samzhu.skillshub.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


/**
 * AC-4 + AC-5：{@code /api/v1/me} 的 JWT 驗證行為。
 *
 * <p>S025b T03 — pilot：{@code @SpringBootTest + @AutoConfigureMockMvc} → {@code @WebMvcTest}
 * slice extends {@link WebMvcSliceTestBase}（提供 {@code SecurityConfig + JwtDecoder mock +
 * PermissionEvaluator mock + ConfigProperties + AOT fix}）。子類僅宣告 {@code @WebMvcTest(MeController.class)}
 * + controller-specific deps mock（{@code CurrentUserProvider}）。
 *
 * <p>用 {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt() jwt()}
 * post-processor 注入 stub JWT — 直接旁路 JwtDecoder（per Spring Security 7 OAuth2 MockMvc reference）。
 */
@WebMvcTest(MeController.class)
class MeControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Tag("AC-4")
    @Tag("AC-S141-1")
    @DisplayName("AC-S141-1 / AC-4: /api/v1/me 帶 admin token → 200 + 9 keys 含 email/name/picture")
    void me_withAdminJwt_returnsAllClaims() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                .with(jwt().jwt(j -> j
                        .subject("admin-001")
                        .claim("email", "admin-001@example.com")
                        .claim("name", "Admin User")
                        .claim("picture", "https://example.com/admin.png")
                        .claim("roles", List.of("admin"))
                        .claim("groups", List.of("platform-admins", "skills-curators"))
                        .claim("company_id", "skills-hub-corp")
                        .claim("dept_id", "engineering")
                        .claim("scope", "skills:admin skills:read skills:write"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("admin-001"))
            .andExpect(jsonPath("$.email").value("admin-001@example.com"))
            .andExpect(jsonPath("$.name").value("Admin User"))
            .andExpect(jsonPath("$.picture").value("https://example.com/admin.png"))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            .andExpect(jsonPath("$.groups[0]").value("platform-admins"))
            .andExpect(jsonPath("$.groups[1]").value("skills-curators"))
            .andExpect(jsonPath("$.companyId").value("skills-hub-corp"))
            .andExpect(jsonPath("$.deptId").value("engineering"))
            .andExpect(jsonPath("$.scope").value("skills:admin skills:read skills:write"));
    }

    @Test
    @Tag("AC-S141-2")
    @DisplayName("AC-S141-2: /api/v1/me LAB branch → 200 + 合成 email=<sub>@lab.skillshub.local + name=LAB User")
    void me_labBranch_returnsSynthesizedClaims() throws Exception {
        when(currentUserProvider.current()).thenReturn(
                new CurrentUser("lab-user-001", List.of("admin"), List.of(), null));

        mockMvc.perform(get("/api/v1/me")
                .with(user("lab-user-001").roles("admin")))  // UsernamePasswordAuthenticationToken → else branch
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("lab-user-001"))
            .andExpect(jsonPath("$.email").value("lab-user-001@lab.skillshub.local"))
            .andExpect(jsonPath("$.name").value("LAB User"))
            .andExpect(jsonPath("$.picture").doesNotExist());
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
