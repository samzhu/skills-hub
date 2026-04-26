package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * AC-3：LAB 模式下 {@link JwtDecoder} bean 不應存在於容器，{@link SecurityFilterChain} 仍存在。
 *
 * <p>驗證 SecurityConfig 透過 {@code @ConditionalOnProperty} 正確 gate 掉
 * JwtDecoder + JwtAuthenticationConverter 兩個 bean，避免 LAB 模式仍嘗試 OIDC discovery
 * 或建立未使用的 OAuth 解碼基礎設施。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "skillshub.security.oauth.enabled=false")
class JwtDecoderConditionalTest {

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
