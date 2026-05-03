package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.api.MissingJwtSubException;

/**
 * AC-4 + AC-5：{@link CurrentUserProvider} 三 branch Authentication 行為（既有 S012）。
 *
 * S115 AC-1/2/3 補充：JWT graceful degradation policy（sub null check / roles
 * type mismatch / non-string element skip / Micrometer counter）— 對齊
 * spec §2.2 fallback matrix + §2.6 observability。
 *
 * <p>Pure unit test（不啟動 Spring 容器）— 直接 SecurityContextHolder 注入 + 用
 * SimpleMeterRegistry 收 counter 驗 anomaly increment。
 */
class CurrentUserProviderTest {

    private static final String LAB_USER_ID = "lab-user";

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final JwtClaimAnomalyMetrics anomalyMetrics = new JwtClaimAnomalyMetrics(registry);
    private final CurrentUserProvider provider =
            new CurrentUserProvider(propsWithLabUser(LAB_USER_ID), anomalyMetrics);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: JwtAuthenticationToken → 回 JWT subject 與 roles claim")
    void current_jwtAuthentication_returnsJwtSubjectAndRoles() {
        // Given: SecurityContext 含 JwtAuthenticationToken (sub=alice, roles=[admin, viewer])
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("admin", "viewer"))
                .build();
        var auth = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // When + Then
        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo("alice");
        assertThat(user.roles()).containsExactly("admin", "viewer");
        assertThat(user.groups()).as("無 groups claim 時應回 empty list").isEmpty();
        assertThat(provider.userId()).isEqualTo("alice");
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: JwtAuthenticationToken with groups claim → 回完整三段（subject + roles + groups）")
    void current_jwtWithGroups_returnsAllNamespaces() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("user"))
                .claim("groups", List.of("engineering", "platform"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo("alice");
        assertThat(user.roles()).containsExactly("user");
        assertThat(user.groups())
                .as("OIDC standard groups claim 應原樣抽出")
                .containsExactly("engineering", "platform");
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: UsernamePasswordAuthenticationToken (LAB 模式) → 回 lab user 與剝去 ROLE_ 前綴的 roles")
    void current_labAuthentication_stripsRolePrefix() {
        var auth = new UsernamePasswordAuthenticationToken(
                LAB_USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_admin")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.groups()).isEmpty();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: SecurityContext.getAuthentication() == null → 安全 fallback 不丟 NPE")
    void current_nullAuthentication_returnsLabUserFallback() {
        SecurityContextHolder.clearContext();

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.groups()).isEmpty();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: anonymous Authentication → 回 lab user fallback")
    void current_anonymousAuthentication_returnsLabUserFallback() {
        var auth = new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin");
    }

    // ---- S115 graceful degradation tests ----

    @Test
    @Tag("AC-1")
    @DisplayName("S115 AC-1: JWT 缺 sub claim → MissingJwtSubException + counter sub:missing +1")
    void s115_missingSubClaim_throwsAndIncrementsCounter() {
        // Given: 沒設 .subject() — Spring Security Jwt builder 對 sub 不強制
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("roles", List.of("admin"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThatThrownBy(() -> provider.current())
                .isInstanceOf(MissingJwtSubException.class)
                .hasMessageContaining("missing sub claim");
        assertThat(anomalyCount("sub", "missing")).isEqualTo(1);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("S115 AC-1: sub claim 為 blank string → 同 missing 路徑")
    void s115_blankSubClaim_throwsSamePath() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("   ")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThatThrownBy(() -> provider.current())
                .isInstanceOf(MissingJwtSubException.class);
        assertThat(anomalyCount("sub", "missing")).isEqualTo(1);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("S115 AC-2: roles claim 為 String 不是 List → fallback [] + counter type_mismatch +1")
    void s115_rolesTypeMismatch_returnsEmptyListAndCounter() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", "admin")  // 應為 List<String>，但 IdP 發成 String
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo("alice");
        assertThat(user.roles()).as("型別錯 → empty list fail-closed").isEmpty();
        assertThat(anomalyCount("roles", "type_mismatch")).isEqualTo(1);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("S115 AC-3: roles 含 non-string element → skip 該 element + counter per element")
    void s115_rolesContainsNonStringElement_skipsAndIncrements() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("admin", 42, Map.of("nested", "object"), "viewer"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        CurrentUser user = provider.current();
        // 純 string element 保留；int + map element skip
        assertThat(user.roles()).containsExactly("admin", "viewer");
        assertThat(anomalyCount("roles", "non_string_element")).isEqualTo(2);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("S115 AC-4: groups null → fallback empty + 不 increment counter（缺 != type mismatch）")
    void s115_groupsClaimNull_emptyListNoCounter() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("user"))
                // groups 完全缺
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        CurrentUser user = provider.current();
        assertThat(user.groups()).isEmpty();
        assertThat(anomalyCount("groups", "missing")).isZero(); // 缺非 anomaly
        assertThat(anomalyCount("groups", "type_mismatch")).isZero();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("S115 AC-5: 未知新 claim（如 tenant_id） → 完全忽略不破 parsing")
    void s115_unknownClaim_doesNotBreakParsing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("user"))
                .claim("tenant_id", "tenant-42")  // 未來 IdP 新加 claim
                .claim("scope_v2", List.of("read", "write"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo("alice");
        assertThat(user.roles()).containsExactly("user");
        // 任何 anomaly counter 都不 increment（unknown claim 不應 raise alarm）
        assertThat(registry.find("jwt_claim_anomaly_total").counters())
                .as("未知 claim 不該 emit anomaly counter")
                .allSatisfy(c -> assertThat(c.count()).isZero());
    }

    // ---- helpers ----

    private double anomalyCount(String claim, String reason) {
        var counter = registry.find("jwt_claim_anomaly_total")
                .tag("claim", claim).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private static SkillshubProperties propsWithLabUser(String labUserId) {
        return new SkillshubProperties(
                new SkillshubProperties.Storage("skillshub-packages", "./storage-local"),
                new SkillshubProperties.Search("simple", "skill_embeddings"),
                new SkillshubProperties.GenAI("gemini-embedding-2", 768, null),
                new SkillshubProperties.Scanner(new SkillshubProperties.Engines(
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(false),
                        new SkillshubProperties.Engine(true))),
                new SkillshubProperties.Security(
                        new SkillshubProperties.OAuth(true),
                        new SkillshubProperties.Lab(labUserId)));
    }
}
