package io.github.samzhu.skillshub.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.github.samzhu.skillshub.SkillshubProperties;

import java.util.List;

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
    SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        // S128：啟用 CORS（per Mode B Round 40 Bug AZ fix）— allowlist 由 SkillshubProperties.Cors 管理
        http.cors(cors -> cors.configurationSource(corsConfigurationSource));

        if (props.security().oauth().enabled()) {
            // ── OAuth 模式（S011 行為）──
            // S130 (Mode B Round 41 Bug BB fix)：personal endpoints 全 require auth；anonymous 不再
            // 透過 CurrentUserProvider lab-user fallback 共享 state（subscriptions / notifications）。
            // - /api/v1/me + /api/v1/me/** (含 /me/subscriptions)
            // - /api/v1/notifications + /api/v1/notifications/** (含 /unread-count / /{id}/read / /preferences)
            // - /api/v1/admin/** (既驗 S011)
            // - /api/v1/dev/** (S134：real-oauth profile 唯一啟用的 dev debug endpoint)
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                    .requestMatchers("/api/v1/notifications", "/api/v1/notifications/**").authenticated()
                    .requestMatchers("/api/v1/admin/**").authenticated()
                    .requestMatchers("/api/v1/dev/**").authenticated()
                    .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

            // S134：real-oauth profile path — 加上 OAuth2 Login (Client) chain。
            // 由 skillshub.security.oauth.login.enabled toggle（預設 false）；real-oauth profile yaml
            // 顯式設 true 才啟用。Login chain 負責 redirect URI `/login/oauth2/code/{registrationId}`
            // 與 authorization initiation `/oauth2/authorization/{registrationId}`；session-based
            // OAuth2AuthenticationToken 由 HttpSessionSecurityContextRepository 持久化。
            // defaultSuccessUrl("/", true) — 登入成功後 land 首頁（搜尋頁），第二參數 true 強制
            // override 任何 saved request（避免 IdP 預設 GET /oauth2/authorization/skillshub 被當成
            // saved request 又 redirect 回去造成 loop）。
            if (props.security().oauth().login().enabled()) {
                http.oauth2Login(login -> login.defaultSuccessUrl("/", true));
            }
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
     * S128：CORS 設定來源 bean（per Mode B Round 40 Bug AZ fix — LAB 跨 origin 部署 unblock）。
     *
     * <p>由 {@link SkillshubProperties.Security#cors()} 提供 allowlist；預設 dev vite (localhost:5173)
     * + backend self (localhost:8080)。Production / LAB 部署透過 env var
     * {@code SKILLSHUB_SECURITY_CORS_ALLOWED_ORIGINS} 顯式覆蓋 allowlist（逗號分隔多 origin）。
     *
     * <p>{@code allowedMethods} 含 GET/POST/PUT/PATCH/DELETE/OPTIONS — 對齊既有 RestController 用的 method set；
     * {@code allowedHeaders} 走 {@code "*"} (allow all request headers including Authorization)；
     * {@code allowCredentials=true} 預設支援 OAuth bearer token 跨 origin 流程。
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(props.security().cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(props.security().cors().allowCredentials());
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
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
    /**
     * 注入自訂 {@link PermissionEvaluator}（S016 {@link DelegatingPermissionEvaluator}）給
     * {@code @PreAuthorize("hasPermission(...)")} SpEL 評估器使用。
     *
     * <p><b>{@code static} 必要</b>：Spring Security 7 的
     * {@code PrePostMethodSecurityConfiguration} 用 {@code @Autowired(required=false)} 注入
     * {@link MethodSecurityExpressionHandler}；若此 bean 是 instance method，會與
     * {@link EnableMethodSecurity} import 的 config 形成 circular dep（spec §2.4 Challenge #4
     * 已 raw source verified）。{@code static} 讓 bean 在 instance config 完成前可用，破環。
     *
     * <p>{@link DefaultMethodSecurityExpressionHandler} 是 Spring Security 文件指定的擴充點 —
     * 不直接 {@code @Bean PermissionEvaluator}（Spring Security 7 不會 auto-detect）。
     *
     * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html#custom-permission-evaluator">Custom Permission Evaluator</a>
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            PermissionEvaluator permissionEvaluator) {
        var handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }

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
