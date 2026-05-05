package io.github.samzhu.skillshub.shared.security.dev;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * S134 — dev-only endpoint：dump 當前 OAuth2 session / bearer JWT 的所有可見資訊，
 * 給開發者對齊真實 IdP claim shape 與 Spring Security 解析結果。
 *
 * <p>啟用條件：{@code @Profile("real-oauth")} —— 僅當 {@code SPRING_PROFILES_ACTIVE} 含
 * {@code real-oauth} 時 bean 才註冊。其他 profile（dev / lab / prod）下此 bean 不存在，
 * 路徑回 401（{@code SecurityConfig} 把 {@code /api/v1/dev/**} 設 authenticated()）或 404
 * （bean 不註冊時 controller 路徑也不存在）。
 *
 * <p>支援雙路 Authentication 來源：
 * <ul>
 *   <li><b>Session-based</b>（{@link OAuth2AuthenticationToken}）— OAuth2 Login flow
 *       完成後 session 帶；dump principal name、authorities、OAuth2User attributes、
 *       OIDC ID token claims（如為 {@link OidcUser}）、access_token claims（從
 *       {@link OAuth2AuthorizedClientService} 取）。</li>
 *   <li><b>Bearer-token</b>（{@link JwtAuthenticationToken}）— Authorization: Bearer
 *       header 帶；dump principal name、authorities、access_token claims（直接從
 *       JwtAuthenticationToken 抽，不需 OAuth2AuthorizedClientService）。為 S134 AC-5
 *       預期的 dual-path（同 endpoint session 路徑 + bearer 路徑都通）做支撐。</li>
 * </ul>
 *
 * <p><b>安全聲明</b>：本 controller 純 dev debug 用途；JSON body 含 access_token 完整字串
 * （於 {@code access_token_value} 欄位）。**禁止**啟用 real-oauth profile 部署到 LAB / prod
 * 環境；profile-gated bean 已自動防呆，加上此 Javadoc 警示供 reviewer 警覺。
 *
 * @see io.github.samzhu.skillshub.shared.security.SecurityConfig#filterChain — `/api/v1/dev/**` authenticated() rule
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html">Spring Security OAuth2 Login</a>
 */
@Profile("real-oauth")
@RestController
@RequestMapping("/api/v1/dev")
class AuthDebugController {

    private static final Logger log = LoggerFactory.getLogger(AuthDebugController.class);

    private final OAuth2AuthorizedClientService clientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AuthDebugController(OAuth2AuthorizedClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * Dump 當前 SecurityContext 內的 OAuth2 / JWT 識別資訊為 JSON map。
     *
     * <p>回傳 keys 因 Authentication 類型不同而異 — 詳 class Javadoc 雙路說明。
     * 共通必有 keys：{@code principal_name} / {@code authorities}。
     */
    @GetMapping("/auth-debug")
    Map<String, Object> authDebug(Authentication auth) {
        // 此 endpoint 只在 SecurityConfig `/api/v1/dev/**` authenticated() 通過後才會抵達；
        // 理論上 auth 不會 null。defensive check 保留以防未來 SecurityConfig 改動疏漏。
        if (auth == null) {
            return Map.of("error", "no_authentication",
                          "hint", "Hit /oauth2/authorization/skillshub to start login flow");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("principal_name", auth.getName());
        result.put("authorities", auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());

        if (auth instanceof OAuth2AuthenticationToken oauth) {
            populateOAuth2Login(result, oauth);
        } else if (auth instanceof JwtAuthenticationToken jwt) {
            populateBearerJwt(result, jwt);
        } else {
            // LAB filter 或其他自訂 Authentication（不該進來，因 real-oauth profile 走 OAuth 路徑）；
            // 紀錄類型供 debug
            result.put("auth_type", auth.getClass().getSimpleName());
        }
        log.info("auth-debug dump for principal={}", auth.getName());
        return result;
    }

    /**
     * Session 路徑 — 從 OAuth2 Login 結果抽 OidcUser attributes、id_token claims，
     * 再從 OAuth2AuthorizedClientService 拿 access_token（含 JWT decoded payload）。
     */
    private void populateOAuth2Login(Map<String, Object> result, OAuth2AuthenticationToken oauth) {
        var principal = oauth.getPrincipal();
        result.put("oidc_user_attributes", principal.getAttributes());

        if (principal instanceof OidcUser oidc) {
            // OidcUser 有 ID token；attributes 通常與 id_token claims 重疊但兩者來源不同
            // （attributes 可能合併了 userinfo endpoint 回應），各保留一份方便對照
            result.put("id_token_claims", oidc.getIdToken().getClaims());
        }

        // OAuth2AuthorizedClientService 預設 in-memory；按 (registrationId, principalName) 索引
        var client = clientService.loadAuthorizedClient(
                oauth.getAuthorizedClientRegistrationId(), oauth.getName());
        if (client != null) {
            var token = client.getAccessToken();
            result.put("access_token_value", token.getTokenValue());
            // access_token 也可能為 JWT 格式（依 IdP 設定）；嘗試 decode payload 給開發者看 claim 結構
            result.put("access_token_claims", decodeJwtPayload(token.getTokenValue()));
            result.put("access_token_expires_at", token.getExpiresAt());
            result.put("scopes", token.getScopes());
        }
    }

    /**
     * Bearer 路徑 — 從 JwtAuthenticationToken 抽 claims（已被 Spring Security
     * Resource Server 驗證過簽章 + 標準 claims）。
     */
    private void populateBearerJwt(Map<String, Object> result, JwtAuthenticationToken jwt) {
        var token = jwt.getToken();
        result.put("access_token_claims", token.getClaims());
        result.put("access_token_expires_at", token.getExpiresAt());
        result.put("issuer", token.getIssuer());
        result.put("audience", token.getAudience());
    }

    /**
     * 拆 JWT body 部分（middle segment）為 Map — 不驗簽，純 debug 顯示用。
     *
     * <p>Spring Security {@code JwtDecoder} 在 SecurityFilterChain 階段已完成簽章驗證；
     * 本方法僅 base64url-decode payload 給開發者讀 claim 結構（access_token 可能為 opaque
     * 或 JWT 格式，依 IdP 設定；非 JWT 則 split 段數不足，回 error map）。
     */
    private Map<String, Object> decodeJwtPayload(String jwt) {
        try {
            var parts = jwt.split("\\.");
            if (parts.length < 2) {
                // opaque token（無 . 分隔）→ 不可 decode；回 hint 而非拋例外
                return Map.of("error", "not_a_jwt_or_opaque_token", "segments", parts.length);
            }
            var payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            // payload base64 decode 或 JSON parse 失敗 — IdP 給的 token 不符 JWT spec
            return Map.of("error", e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage()));
        }
    }
}
