package io.github.samzhu.skillshub.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * S139 — OAuth login returnTo state plumbing.
 *
 * <p>讓前端能在登入流程內保留「使用者點按鈕的當前頁面 URL」，登入完成後 redirect
 * 回該頁。對應 spec §2.6（Spring SavedRequestAware 不適用 SPA 內 frontend-side
 * action 的場景）。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>Frontend 拼 {@code /oauth2/authorization/skillshub?returnTo=/publish}</li>
 *   <li>{@link #authorizationRequestResolver} 把 {@code returnTo} 寫進 HttpSession
 *       attribute {@value #SESSION_RETURN_TO}</li>
 *   <li>使用者完成 Google 登入後 callback {@code /login/oauth2/code/skillshub}</li>
 *   <li>{@link #oauthSuccessHandler} 從 session 讀 RETURN_TO，經
 *       {@link #safeReturnTo(String)} same-origin 校驗後 sendRedirect</li>
 * </ol>
 *
 * <h2>Open-redirect 防護</h2>
 * <p>Attacker 可拼一條 {@code /oauth2/authorization/skillshub?returnTo=https://evil.com}
 * 騙受害者點擊；登入完成後 SuccessHandler redirect 到 evil.com，因仍帶來自 skillshub
 * 的 referer/cookie，下游中介 proxy / 嵌入 webview 會誤判為「仍在本站」進而 leak。
 * {@link #safeReturnTo(String)} 嚴格白名單：必須 startsWith {@code /} 且 NOT
 * startsWith {@code //}（拒 protocol-relative），其餘 fallback {@code /}。
 *
 * <h2>條件啟用</h2>
 * <p>{@code @ConditionalOnProperty(skillshub.security.oauth.login.enabled=true)} —
 * 與 {@link SecurityConfig} oauth2Login chain toggle 同步；{@code real-oauth} /
 * LAB OAuth 部署 profile 設 true 才啟用，本機 mock-oauth2 路徑不動。
 *
 * @see SecurityConfig#filterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity,
 *      org.springframework.web.cors.CorsConfigurationSource,
 *      org.springframework.beans.factory.ObjectProvider,
 *      org.springframework.beans.factory.ObjectProvider)
 */
@Configuration
@ConditionalOnProperty(
        name = "skillshub.security.oauth.login.enabled",
        havingValue = "true")
class AuthRedirectConfig {

    static final String SESSION_RETURN_TO = "skillshub.return_to";
    private static final String DEFAULT_RETURN_TO = "/";
    private static final String AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";

    private static final Logger log = LoggerFactory.getLogger(AuthRedirectConfig.class);

    /**
     * Validate user-supplied returnTo against open-redirect vectors.
     * 接受 same-origin path（startsWith {@code /} 且 NOT {@code //}），其餘一律 fallback。
     *
     * <p>package-private for {@code AuthRedirectTest} unit coverage（本檔 same package）。
     */
    static String safeReturnTo(String input) {
        if (input == null || input.isEmpty()) {
            return DEFAULT_RETURN_TO;
        }
        if (!input.startsWith("/")) {
            return DEFAULT_RETURN_TO;
        }
        if (input.startsWith("//")) {
            // protocol-relative URL（//evil.com）— 瀏覽器會視為 //protocol://evil.com
            return DEFAULT_RETURN_TO;
        }
        return input;
    }

    /**
     * Wraps Spring 預設的 {@link DefaultOAuth2AuthorizationRequestResolver}：
     * 在 resolve 階段攔截 query param {@code returnTo}，存進 HttpSession 給 SuccessHandler。
     *
     * <p>不修改 OAuth state param —— state 由 Spring 內部簽 + 驗，碰它會壞掉
     * authorization code flow 的 CSRF 保護。
     */
    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, AUTHORIZATION_REQUEST_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                captureReturnTo(request);
                return delegate.resolve(request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(
                    HttpServletRequest request, String clientRegistrationId) {
                captureReturnTo(request);
                return delegate.resolve(request, clientRegistrationId);
            }

            private void captureReturnTo(HttpServletRequest request) {
                String returnTo = request.getParameter("returnTo");
                if (returnTo != null && !returnTo.isEmpty()) {
                    // session 一律建立（getSession() 而非 getSession(false)）—
                    // OAuth2 Login chain 後續需要 session 持 SecurityContext
                    request.getSession().setAttribute(SESSION_RETURN_TO, returnTo);
                    log.atDebug()
                            .addKeyValue("returnTo", returnTo)
                            .log("Captured OAuth login returnTo");
                }
            }
        };
    }

    /**
     * 登入成功後 redirect handler — 從 session 讀 RETURN_TO，經白名單校驗後 sendRedirect。
     * Session attribute 用後立即清掉避免污染下次 login flow。
     */
    @Bean
    AuthenticationSuccessHandler oauthSuccessHandler() {
        return (request, response, authentication) -> {
            var session = request.getSession(false);
            String stored = (session != null)
                    ? (String) session.getAttribute(SESSION_RETURN_TO)
                    : null;
            if (session != null) {
                session.removeAttribute(SESSION_RETURN_TO);
            }
            String target = safeReturnTo(stored);
            log.atInfo()
                    .addKeyValue("user", authentication.getName())
                    .addKeyValue("returnTo", target)
                    .log("OAuth login success — redirecting");
            response.sendRedirect(target);
        };
    }
}
