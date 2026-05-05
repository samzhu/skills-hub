package io.github.samzhu.skillshub.shared.security.dev;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S134 AC-4 + AC-5 (mock unit) — {@link AuthDebugController} 雙 path 行為驗證。
 *
 * <p>Path 1：OAuth2 Login session（{@code .with(oauth2Login())}）→ {@link
 * org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken}
 * 注入 → 觸發 controller 的 oidc 分支，dump principal name + authorities + oidc attributes。
 *
 * <p>Path 2：Bearer token（{@code .with(jwt())}）→
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}
 * 注入 → 觸發 bearer 分支，dump access_token claims（為 AC-5 真實 bearer dual-path 做 mock 預驗）。
 *
 * <p>{@code @ActiveProfiles({"test", "real-oauth"})}：{@link AuthDebugController} 標
 * {@code @Profile("real-oauth")}，需顯式啟用該 profile bean 才註冊（{@code "test"} profile
 * 維持既有 test infra 對齊）。
 */
@WebMvcTest(AuthDebugController.class)
@ActiveProfiles({"test", "real-oauth"})
class AuthDebugControllerTest extends WebMvcSliceTestBase {

    @Autowired
    MockMvc mockMvc;

    /** AuthDebugController 注入此 service；slice 不掃 @Service，需 mock 滿足 ctor。 */
    @MockitoBean
    OAuth2AuthorizedClientService clientService;

    @Test
    @DisplayName("AC-4: OAuth2 Login session — dump principal_name + authorities + oidc_user_attributes")
    void oauth2LoginPath_returnsClaimDump() throws Exception {
        // Given: OAuth2 Login session with OidcUser containing standard claims
        // .with(oauth2Login()) 預設創建 OidcUser (OAuth2AuthenticationToken)；attributes 模擬真 IdP claim shape
        // clientService.loadAuthorizedClient(...) 傳 null 即可 — controller 對 null 安全處理（不 dump access_token block）
        when(clientService.loadAuthorizedClient(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(get("/api/v1/dev/auth-debug")
                .with(oauth2Login()
                        .attributes(a -> {
                            a.put("sub", "test-sub-001");
                            a.put("name", "Test User");
                        })))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.principal_name").exists())
            .andExpect(jsonPath("$.authorities").isArray())
            .andExpect(jsonPath("$.oidc_user_attributes.sub").value("test-sub-001"));
    }

    @Test
    @DisplayName("AC-5 (mock): bearer-token path — dump access_token_claims (預備真實 IdP bearer 測試)")
    void bearerPath_returnsAccessTokenClaims() throws Exception {
        // Given: bearer JWT in SecurityContext (no session) — JwtAuthenticationToken path
        mockMvc.perform(get("/api/v1/dev/auth-debug")
                .with(jwt().jwt(j -> j.subject("bearer-sub")
                        .claim("aud", List.of("skills-hub-api")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.principal_name").value("bearer-sub"))
            .andExpect(jsonPath("$.access_token_claims.sub").value("bearer-sub"));
    }
}
