package io.github.samzhu.skillshub.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 7 SecurityFilterChain 設定 — 「顯式 permitAll + 局部收緊」策略。
 *
 * <p>遵循 CLAUDE.md 「Feature First, Security Later」原則：
 * S001~S010 既有 endpoints 全部維持匿名可達；只有本 spec 新增的 demo
 * 端點要 JWT。如此引入 OAuth2 Resource Server starter 後不會打掉
 * 既有功能與測試。
 *
 * <p>路由規則（順序敏感）：
 * <ol>
 *   <li>{@code /api/v1/me}              → authenticated()（任何有效 JWT）</li>
 *   <li>{@code /api/v1/admin/**}        → authenticated() + method-level 用 {@code @PreAuthorize} 判 role</li>
 *   <li>{@code anyRequest()}            → permitAll()（既有 API 不受影響）</li>
 * </ol>
 *
 * <p>JWT 驗證：採 OIDC discovery（{@code spring.security.oauth2.resourceserver.jwt.issuer-uri}），
 * 對應 backend/compose.yaml 啟動的 mock-oauth2-server。Spring Security 7 lazy discovery
 * 機制讓 bootRun 不依賴 mock 容器立即 ready。
 *
 * <p>{@code roles} claim → GrantedAuthority 對應：透過 {@link JwtGrantedAuthoritiesConverter}
 * 把 JSON 陣列 {@code ["admin"]} 映射為 {@code ROLE_admin}，配合 {@code @PreAuthorize("hasRole('admin')")} 即可運作。
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security 7 OAuth2 Resource Server JWT</a>
 * @see io.github.samzhu.skillshub.shared.security.MeController
 * @see io.github.samzhu.skillshub.shared.security.AdminController
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /**
     * SecurityFilterChain — 同時為 JWT-protected 路徑與 permitAll 既有路徑生效。
     *
     * <p>{@code csrf().disable()} 因 RestController + JWT bearer 的 stateless 模型不需要 CSRF token
     * （瀏覽器 cookie session 才需要）。
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/me").authenticated()
                .requestMatchers("/api/v1/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Lazy JwtDecoder — 啟動時不打 network，避免測試環境（無真實 mock 容器）context load 失敗。
     *
     * <p>Spring Boot 4 / Spring Security 7 的 OAuth2 Resource Server auto-config 在某些情境下
     * 不會自動建立 JwtDecoder bean（觀察：classpath 上加入 starter 後，{@code issuer-uri} 已設定但
     * 容器仍報「No qualifying bean of type JwtDecoder available」）。為此明確宣告 bean，並用
     * {@link SupplierJwtDecoder} 包裝確保 lazy 行為——首個 JWT 請求才會做 OIDC discovery，
     * 維持「mock 容器後啟動不擋 bootRun」的設計目標。
     *
     * <p>單元測試用 {@code SecurityMockMvcRequestPostProcessors.jwt()} 直接注入 Authentication，
     * 不會觸發此 decoder；E2E 測試（T2）才會走真實解碼路徑。
     */
    @Bean
    JwtDecoder jwtDecoder(
            // Default 對應 backend/compose.yaml 啟動的 mock-oauth2-server。
            // 測試 classpath 的 application.yaml 會完全覆蓋 main 的（非合併），
            // 帶 default 值即可同時滿足 prod（main yaml 會覆寫）與 unit test
            // （`.with(jwt())` 不觸發 decoder，URL 對錯不影響行為）。
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:9000/skills-hub-dev}")
            String issuerUri) {
        return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(issuerUri));
    }

    /**
     * JwtAuthenticationConverter — 從 {@code roles} claim 抽出 GrantedAuthority。
     *
     * <p>JWT 中的 {@code roles: ["admin"]} 會被映射為 {@code ROLE_admin} authority，
     * 配合 {@code @PreAuthorize("hasRole('admin')")} 自然運作（hasRole() 會自動加 ROLE_ 前綴比對）。
     *
     * <p>遵循 RFC 9068 + SCIM RFC 7643 對 {@code roles} claim 的命名慣例。
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
