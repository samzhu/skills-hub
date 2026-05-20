package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * S139 — {@link AuthRedirectConfig#safeReturnTo(String)} unit tests。
 *
 * <p>OAuth login 完成後 SuccessHandler 從 session 讀 RETURN_TO 屬性決定 redirect 目的地；
 * 必須擋下 open-redirect attack：使用者可控的 returnTo 不能 redirect 到外部網域，
 * 否則 attacker 可拼一條 {@code /oauth2/authorization/skillshub?returnTo=https://evil.com}
 * 的 URL 騙受害者點擊，登入完成後跳到 evil.com（誤以為仍在本站）。
 *
 * <p>White-list 規則：必須 startsWith {@code /} 且 NOT startsWith {@code //}（同源 path-only）。
 */
class AuthRedirectTest {

    private static String logValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing log key: " + key));
    }

    @ParameterizedTest
    @DisplayName("AC-3: same-origin path 通過")
    @Tag("AC-3")
    @CsvSource({
        "/publish",
        "/browse",
        "/skills/foo-bar",
        "/my-skills?tab=draft",
        "/"
    })
    void safeReturnTo_acceptsSameOriginPath(String input) {
        assertThat(AuthRedirectConfig.safeReturnTo(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @DisplayName("AC-3: open-redirect attack patterns 一律 fallback /")
    @Tag("AC-3")
    @CsvSource({
        "//evil.com",                       // protocol-relative
        "//evil.com/path",
        "https://evil.com",                 // absolute external
        "http://localhost:8080/safe",       // 同 host 但帶 scheme — 拒絕，path-only 規則
        "javascript:alert(1)",              // pseudo-protocol
        "ftp://evil.com"
    })
    void safeReturnTo_rejectsExternalAndMalicious(String input) {
        var actual = AuthRedirectConfig.safeReturnTo(input);
        assertThat(actual)
            .as("input '%s' 應 fallback 到 / 但實得 '%s'", input, actual)
            .isEqualTo("/");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AC-3: leading whitespace bypass → fallback /")
    @Tag("AC-3")
    void safeReturnTo_leadingWhitespaceBypassFallsBack() {
        // 直接寫死字面量避免 CSV 來源 trim
        assertThat(AuthRedirectConfig.safeReturnTo("  /publish")).isEqualTo("/");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AC-3: empty string → fallback /")
    @Tag("AC-3")
    void safeReturnTo_emptyFallsBackToRoot() {
        assertThat(AuthRedirectConfig.safeReturnTo("")).isEqualTo("/");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AC-3: null input → fallback /")
    @Tag("AC-3")
    void safeReturnTo_nullFallsBackToRoot() {
        assertThat(AuthRedirectConfig.safeReturnTo(null)).isEqualTo("/");
    }

    @Test
    @DisplayName("AC-S204-1: OAuth callback failure redirects to /auth/error safe reason")
    @Tag("AC-S204-1")
    void oauthFailureHandlerRedirectsToAuthErrorWithSafeReason() throws Exception {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/skillshub");
        request.setQueryString("state=abc&code=secret-code");
        request.getSession().setAttribute(AuthRedirectConfig.SESSION_RETURN_TO, "/publish?draftToken=abc");
        var response = new MockHttpServletResponse();

        new AuthRedirectConfig().oauthFailureHandler().onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied"),
                        "access_denied: code=secret-code token=secret-token"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?reason=access_denied");
        assertThat(response.getRedirectedUrl())
                .doesNotContain("code")
                .doesNotContain("secret-code")
                .doesNotContain("token")
                .doesNotContain("returnTo")
                .doesNotContain("access_denied:");
        assertThat(request.getSession().getAttribute(AuthRedirectConfig.SESSION_RETURN_TO)).isNull();
    }

    @Test
    @DisplayName("AC-S204-1: OAuth state/session mismatch maps to session_expired")
    @Tag("AC-S204-1")
    void oauthFailureHandlerMapsStateMismatchToSessionExpired() throws Exception {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/skillshub");
        var response = new MockHttpServletResponse();

        new AuthRedirectConfig().oauthFailureHandler().onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_state_parameter")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?reason=session_expired");
    }

    @Test
    @DisplayName("AC-S204-6: failure log uses safe fields and strips returnTo query")
    @Tag("AC-S204-6")
    void oauthFailureHandlerLogsSafeDiagnosticFields() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthRedirectConfig.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/skillshub");
        request.setQueryString("state=abc&code=secret-code");
        request.getSession().setAttribute(AuthRedirectConfig.SESSION_RETURN_TO, "/publish?draftToken=abc");
        var response = new MockHttpServletResponse();

        try {
            new AuthRedirectConfig().oauthFailureHandler().onAuthenticationFailure(
                    request,
                    response,
                    new OAuth2AuthenticationException(
                            new OAuth2Error("invalid_grant"),
                            "token endpoint leaked secret-token"));

            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("OAuth login failed");
            assertThat(logValue(event, "oauthErrorCode")).isEqualTo("token_exchange_failed");
            assertThat(logValue(event, "exceptionClass")).isEqualTo("OAuth2AuthenticationException");
            assertThat(logValue(event, "path")).isEqualTo("/login/oauth2/code/skillshub");
            assertThat(logValue(event, "method")).isEqualTo("GET");
            assertThat(logValue(event, "returnToPath")).isEqualTo("/publish");
            assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                    .doesNotContain("secret-code")
                    .doesNotContain("secret-token")
                    .doesNotContain("draftToken=abc")
                    .doesNotContain("invalid_grant");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("AC-S204-7: OAuth success still redirects to stored returnTo")
    @Tag("AC-S204-7")
    void oauthSuccessHandlerStillRedirectsToStoredReturnTo() throws Exception {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/skillshub");
        request.getSession().setAttribute(AuthRedirectConfig.SESSION_RETURN_TO, "/browse?category=DevOps");
        var response = new MockHttpServletResponse();

        new AuthRedirectConfig().oauthSuccessHandler().onAuthenticationSuccess(
                request,
                response,
                new TestingAuthenticationToken("alice", "n/a"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/browse?category=DevOps");
        assertThat(request.getSession().getAttribute(AuthRedirectConfig.SESSION_RETURN_TO)).isNull();
    }
}
