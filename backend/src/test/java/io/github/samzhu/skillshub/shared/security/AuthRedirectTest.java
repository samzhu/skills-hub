package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
