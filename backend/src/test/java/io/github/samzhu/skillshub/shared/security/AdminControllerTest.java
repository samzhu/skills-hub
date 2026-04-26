package io.github.samzhu.skillshub.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * AC-6 + AC-7：{@code /api/v1/admin/echo} 的 method-level role 授權判斷。
 *
 * <p>{@code @PreAuthorize("hasRole('admin')")} 比對的 authority 來自 {@code roles} claim 透過
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
 * JwtGrantedAuthoritiesConverter} 自動轉換成 {@code ROLE_admin}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // 注意：MockMvc 的 `.with(jwt())` post-processor **不會跑自訂的 JwtAuthenticationConverter** —
    // 它直接合成 JwtAuthenticationToken 並由 .authorities(...) 指定 GrantedAuthority。
    // 這裡明確帶入「JwtGrantedAuthoritiesConverter 在生產路徑會產出的相同結果」（ROLE_xxx）。
    // E2E 測試（T2 OAuthMockE2ETest）才會走真實的 converter 路徑。

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: /api/v1/admin/echo viewer token → 403 Forbidden")
    void adminEcho_withViewerJwt_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("viewer-007").claim("roles", List.of("viewer")))
                    .authorities(new SimpleGrantedAuthority("ROLE_viewer"))))
            .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: /api/v1/admin/echo admin token → 200 + {echo, by}")
    void adminEcho_withAdminJwt_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/echo")
                .param("msg", "hello")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.echo").value("hello"))
            .andExpect(jsonPath("$.by").value("admin-001"));
    }
}
