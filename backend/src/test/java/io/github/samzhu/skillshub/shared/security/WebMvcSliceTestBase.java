package io.github.samzhu.skillshub.shared.security;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S025b T03 — {@code @WebMvcTest} slice 共用 base class，收斂 10+ controller test 為單一
 * Spring TestContext cache key（per spec §4.2 後行決定 — pilot {@link MeControllerTest}
 * 顯示 5+ 行 boilerplate 重複，抽 base 收益顯著）。
 *
 * <p>本 base class 位於 {@code shared.security} package — {@link SecurityConfig} 為 package-private，
 * 同 package 才能直接 {@code @Import} class-reference。
 *
 * <p><b>設計理由</b>（mirror T01 {@link
 * io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase} pattern）：
 * <ol>
 *   <li>{@code @ImportAutoConfiguration} (bare) +
 *       {@code META-INF/spring/io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase.imports}
 *       帶 {@code SpringModulithRuntimeAutoConfiguration} 字串 FQN — 解
 *       {@code ApplicationModulesFileGeneratingProcessor}（{@code spring-modulith-runtime}
 *       {@code aot.factories} classpath-level 註冊；非 auto-config，無屬性可關）對
 *       {@code ApplicationModulesRuntime} bean 的 hard dep。Slice 不在 {@code @WebMvcTest}
 *       whitelist 故預設不載 {@code SpringModulithRuntimeAutoConfiguration}（package-private
 *       無法 class-reference）。</li>
 *   <li>{@code @TestPropertySource("management.tracing.enabled=false")} — 同 T01 對
 *       {@code ModuleObservabilityAutoConfiguration} 的 {@code @ConditionalOnProperty} 守 tracing flag</li>
 *   <li>{@code @Import(SecurityConfig.class)} — slice 不掃 {@code @Configuration}；測 OAuth2 RS
 *       filter chain 必須引入 prod {@link SecurityConfig}（per spec §2.5 OAuth2 RS reference）</li>
 *   <li>{@code @EnableConfigurationProperties(SkillshubProperties.class)} —
 *       {@link SecurityConfig} ctor 注入 {@code SkillshubProperties}（{@code @ConfigurationProperties}
 *       slice 預設不掃）</li>
 *   <li>{@code @MockitoBean JwtDecoder} — {@link SecurityConfig#jwtDecoder} 為
 *       {@code @ConditionalOnProperty(oauth.enabled)} lazy {@code SupplierJwtDecoder}；
 *       mock 避免 OIDC discovery 真實連線；{@code .with(jwt())} post-processor 旁路 decoder 不需 stub return</li>
 *   <li>{@code @MockitoBean PermissionEvaluator} — {@link SecurityConfig#methodSecurityExpressionHandler}
 *       的 ctor dep（static @Bean 破 circular dep；S016）；{@code @PreAuthorize("hasPermission(...)")}
 *       評估器；test 端 mock {@code hasPermission(...)} return true/false 控制 200 vs 403</li>
 * </ol>
 *
 * <p><b>Cache key 收斂機制</b>（per S025a {@link LabModeTestBase} pattern）：base class 持有共同
 * annotations + {@code @MockitoBean} field declarations → 子類 {@code MergedContextConfiguration}
 * 從 base 繼承 customizer set；所有 child {@code @WebMvcTest(SpecificController.class)} 因 controller
 * target 不同各自一個 customizer，但 {@code @Import + @MockitoBean} 共用 set → 大幅收斂 cache key
 * （從 10 → 1-2）。
 *
 * <p><b>使用範例</b>：
 * <pre>{@code
 * @WebMvcTest(MeController.class)
 * class MeControllerTest extends WebMvcSliceTestBase {
 *     @Autowired MockMvc mockMvc;
 *     @MockitoBean CurrentUserProvider currentUserProvider;  // controller-specific dep
 *
 *     @Test
 *     void getMe_returnsUser() throws Exception {
 *         mockMvc.perform(get("/api/v1/me")
 *                 .with(jwt().jwt(j -> j.subject("alice"))))
 *             .andExpect(status().isOk());
 *     }
 * }
 * }</pre>
 *
 * <p><b>注意</b>：子類仍須宣告 {@code @WebMvcTest(SpecificController.class)} —
 * slice target 必須在子類，base class 無法替代。
 *
 * @see io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase T01 同型 base class
 * @see LabModeTestBase S025a base class precedent
 */
@ImportAutoConfiguration
@Import(SecurityConfig.class)
@EnableConfigurationProperties(SkillshubProperties.class)
@TestPropertySource(properties = "management.tracing.enabled=false")
public abstract class WebMvcSliceTestBase {

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected PermissionEvaluator permissionEvaluator;
}
