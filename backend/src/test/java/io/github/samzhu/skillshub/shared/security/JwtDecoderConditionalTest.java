package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * AC-3：LAB 模式下 {@link JwtDecoder} bean 不應存在於容器，{@link SecurityFilterChain} 仍存在。
 *
 * <p>S025a-T04: extends {@link LabModeTestBase} 收斂 cache key（per spec §4.2）。
 * 雖本 test 不用 MockMvc（base class 含 {@code @AutoConfigureMockMvc} 為冗餘），但加入 base 收斂
 * 3 LabMode test 共用同一 cache entry 的收益遠大於該 customizer 成本。
 *
 * <p>驗證 SecurityConfig 透過 {@code @ConditionalOnProperty} 正確 gate 掉
 * JwtDecoder + JwtAuthenticationConverter 兩個 bean，避免 LAB 模式仍嘗試 OIDC discovery
 * 或建立未使用的 OAuth 解碼基礎設施。
 */
class JwtDecoderConditionalTest extends LabModeTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: oauth.enabled=false 時，JwtDecoder bean 不存在於容器")
    void jwtDecoder_notPresentInContextWhenOauthDisabled() {
        var jwtDecoders = context.getBeansOfType(JwtDecoder.class);
        assertThat(jwtDecoders)
            .as("LAB 模式不應建立 JwtDecoder bean，避免無謂的 OIDC discovery")
            .isEmpty();
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: SecurityFilterChain bean 仍存在於容器（LAB 分支建立）")
    void securityFilterChain_stillPresentInLabMode() {
        var chains = context.getBeansOfType(SecurityFilterChain.class);
        assertThat(chains)
            .as("LAB 模式仍需 SecurityFilterChain（內部走 permitAll + LabSecurityFilter 分支）")
            .isNotEmpty();
    }
}
