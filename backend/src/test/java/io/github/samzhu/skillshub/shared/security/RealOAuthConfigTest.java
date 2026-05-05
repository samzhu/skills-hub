package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S134 AC-8 — 固化 issuer-uri 設計約束，防止後續修改誤加 trailing slash。
 *
 * <p>背景：IdP {@code https://auth-dev.omnihubs.cloud} discovery metadata `issuer` 欄位
 * 為 {@code "https://auth-dev.omnihubs.cloud"}（**無** trailing slash）。Spring Security 7
 * {@code JwtDecoderProviderConfigurationUtils.validateIssuer()} 與 {@code JwtIssuerValidator}
 * 兩處皆走 {@code String.equals} 嚴格比對；trailing slash 不一致會在 discovery 或第一個 JWT
 * decode 時拋 {@code IllegalStateException}。本 test 讀
 * {@code config/application-real-oauth.yaml.example} template，掃所有 {@code issuer-uri:}
 * 行確保結尾無 {@code /}，避免 future copy-paste 誤入 slash 變體。
 *
 * @see io.github.samzhu.skillshub.shared.security.SecurityConfig
 */
class RealOAuthConfigTest {

    private static final Path TEMPLATE = Path.of("config/application-real-oauth.yaml.example");

    @Test
    @DisplayName("AC-8: issuer-uri 必須與 IdP metadata `issuer` 欄位嚴格相等（無 trailing slash）")
    void issuerUriShouldNotHaveTrailingSlash() throws Exception {
        var template = Files.readString(TEMPLATE);
        // 掃 yaml 中所有 `issuer-uri:` 行（OIDC client provider + Resource Server）
        var issuerLines = template.lines()
                .filter(line -> line.trim().startsWith("issuer-uri:"))
                .toList();
        // 至少要有一條 issuer-uri 設定，否則 template 缺 OAuth provider 配置
        assertThat(issuerLines)
                .as("template 必須包含至少一條 issuer-uri 設定")
                .isNotEmpty();
        for (var line : issuerLines) {
            // line 形如 `            issuer-uri: https://auth-dev.omnihubs.cloud  # comment`
            // 取第一個 `:` 後 substring（key/value 分隔），再切 inline comment
            var value = line.substring(line.indexOf(':') + 1).trim();
            if (value.contains("#")) {
                value = value.substring(0, value.indexOf('#')).trim();
            }
            assertThat(value)
                    .as("issuer-uri value '%s' 不可帶 trailing slash（會與 IdP metadata `issuer` 欄位 String.equals 失敗）", value)
                    .doesNotEndWith("/");
        }
    }
}
