package io.github.samzhu.skillshub.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * Spring Security 7 SecurityFilterChain 設定 — 「顯式 permitAll + 局部收緊」策略，
 * 並支援 OAuth 開關 + LAB 模式（S011 + S012）。
 *
 * <p>遵循 CLAUDE.md「Feature First, Security Later」原則：
 * S001~S010 既有 endpoints 全部維持匿名可達；只有 {@code /api/v1/me} 與
 * {@code /api/v1/admin/**} 在 OAuth 模式下要 JWT。LAB 模式（S012）將整條 OAuth
 * 鏈路關掉、注入預設 lab user，所有 endpoint 皆可訪問且帶 admin 權限。
 *
 * <p>{@link SkillshubProperties.Security#oauth()} 切換兩種模式：
 * <ul>
 *   <li>{@code enabled=true}（預設） — OAuth 路徑：JwtDecoder + JwtAuthenticationConverter beans
 *       透過 {@code @ConditionalOnProperty} 建立；SecurityFilterChain 啟用
 *       {@code oauth2ResourceServer().jwt(...)}；{@code /api/v1/me} + {@code /api/v1/admin/**}
 *       須帶 JWT。</li>
 *   <li>{@code enabled=false} — LAB 路徑：JwtDecoder bean 不建立；SecurityFilterChain
 *       全 permitAll、{@link LabSecurityFilter} 注入預設 lab user。</li>
 * </ul>
 *
 * <p>JWT 驗證採 OIDC discovery（{@code spring.security.oauth2.resourceserver.jwt.issuer-uri}），
 * 對應 backend/compose.yaml 啟動的 mock-oauth2-server。Spring Security 7 lazy discovery
 * 機制讓 bootRun 不依賴 mock 容器立即 ready。
 *
 * <p>{@code roles} claim → GrantedAuthority 對應：透過 {@link JwtGrantedAuthoritiesConverter}
 * 把 JSON 陣列 {@code ["admin"]} 映射為 {@code ROLE_admin}，配合
 * {@code @PreAuthorize("hasRole('admin')")} 即可運作。LAB 模式則由
 * {@link LabSecurityFilter} 直接注入 {@code [ROLE_admin]} authority。
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html">Spring Security 7 OAuth2 Resource Server JWT</a>
 * @see io.github.samzhu.skillshub.shared.security.MeController
 * @see io.github.samzhu.skillshub.shared.security.AdminController
 * @see LabSecurityFilter
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private final SkillshubProperties props;

    SecurityConfig(SkillshubProperties props) {
        this.props = props;
    }

    /**
     * SecurityFilterChain — 內部 branch on {@code skillshub.security.oauth.enabled}。
     *
     * <p>單一 bean、內部分支策略：避免兩個 SecurityFilterChain bean 競爭（{@code @Order} 顯式管理
     * 與 {@code @ConditionalOnProperty} 互斥都可，但會增加閱讀成本）；保持 S011 行為等價。
     *
     * <p>{@code csrf().disable()} 因 RestController + JWT bearer 的 stateless 模型不需要 CSRF token。
     * LAB 模式雖無 JWT，但同樣為 stateless API（curl/前端），不啟用 CSRF。
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (props.security().oauth().enabled()) {
            // ── OAuth 模式（S011 行為）──
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/me").authenticated()
                    .requestMatchers("/api/v1/admin/**").authenticated()
                    .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        } else {
            // ── LAB 模式（S012）──
            // anyRequest permitAll：任何 endpoint 不需 JWT；@PreAuthorize 仍可運作
            // （由 LabSecurityFilter 注入帶 ROLE_admin 的 Authentication）
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                    new LabSecurityFilter(props.security().lab().userId()),
                    UsernamePasswordAuthenticationFilter.class);
        }
        http.csrf(AbstractHttpConfigurer::disable);
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
     * <p>{@code @ConditionalOnProperty} (S012)：LAB 模式（{@code oauth.enabled=false}）下不建立此 bean，
     * 避免無謂的 OIDC discovery 連線意圖殘留。{@code matchIfMissing=true} 確保 yaml 沒寫
     * 該 property 時走 production 預設（OAuth 啟用），fail-secure。
     */
    @Bean
    @ConditionalOnProperty(prefix = "skillshub.security.oauth", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
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
     *
     * <p>{@code @ConditionalOnProperty} (S012)：與 JwtDecoder 同步 gate，LAB 模式不建立。
     */
    @Bean
    @ConditionalOnProperty(prefix = "skillshub.security.oauth", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
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
