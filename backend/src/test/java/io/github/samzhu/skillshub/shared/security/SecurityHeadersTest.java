package io.github.samzhu.skillshub.shared.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S160 Phase 1：security headers regression。
 *
 * <p>Spring Security filter chain 在 routing 之前先寫 header；用任何 GET endpoint（這裡借
 * {@code /api/v1/admin/echo}，給足 admin JWT 確保 200 而不被 403 蓋 header 路徑）即可驗。
 *
 * <p>本 spec **不啟 CSRF**（屬 S160b 範疇），故無 CSRF post-processor 需要。
 */
@WebMvcTest(AdminController.class)
class SecurityHeadersTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: CSP Report-Only header — default-src 'self' / frame-ancestors none / form-action self")
    void cspReportOnlyHeader() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        mockMvc.perform(get("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(header().string("Content-Security-Policy-Report-Only",
                    containsString("default-src 'self'")))
            .andExpect(header().string("Content-Security-Policy-Report-Only",
                    containsString("frame-ancestors 'none'")))
            .andExpect(header().string("Content-Security-Policy-Report-Only",
                    containsString("form-action 'self'")));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: HSTS — max-age=31536000; includeSubDomains")
    void strictTransportSecurityHeader() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        mockMvc.perform(get("/api/v1/admin/echo")
                .secure(true)
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(header().string("Strict-Transport-Security",
                    startsWith("max-age=31536000")))
            .andExpect(header().string("Strict-Transport-Security",
                    containsString("includeSubDomains")));
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: Referrer-Policy — strict-origin-when-cross-origin")
    void referrerPolicyHeader() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        mockMvc.perform(get("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: Permissions-Policy — camera / microphone / geolocation / interest-cohort all deny")
    void permissionsPolicyHeader() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        mockMvc.perform(get("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
            .andExpect(header().string("Permissions-Policy", containsString("microphone=()")))
            .andExpect(header().string("Permissions-Policy", containsString("geolocation=()")))
            .andExpect(header().string("Permissions-Policy", containsString("interest-cohort=()")));
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: 既有 X-Frame-Options DENY + X-Content-Type-Options nosniff 維持")
    void existingHeadersPreserved() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        mockMvc.perform(get("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
