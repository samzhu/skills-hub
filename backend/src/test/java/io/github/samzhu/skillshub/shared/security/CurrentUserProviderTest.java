package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

/**
 * AC-4 + AC-5：{@link CurrentUserProvider} 在三種 Authentication 情境下的行為。
 *
 * <p>不啟動 Spring 容器（pure unit test）— 直接操作 {@link SecurityContextHolder} 注入
 * 不同型別的 Authentication，驗證 provider 抽象正確。
 */
class CurrentUserProviderTest {

    private static final String LAB_USER_ID = "lab-user";

    private final CurrentUserProvider provider =
            new CurrentUserProvider(propsWithLabUser(LAB_USER_ID));

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
        // Given: LabSecurityFilter 注入的 token
        var auth = new UsernamePasswordAuthenticationToken(
                LAB_USER_ID,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // When + Then
        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin"); // ROLE_ 前綴已被剝除
        assertThat(user.groups())
                .as("LAB 模式無 JWT claim 來源，groups 必為空")
                .isEmpty();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: SecurityContext.getAuthentication() == null → 安全 fallback 不丟 NPE")
    void current_nullAuthentication_returnsLabUserFallback() {
        // Given: 無 Authentication（如背景執行緒未繼承 SecurityContext）
        SecurityContextHolder.clearContext();

        // When + Then
        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.groups()).isEmpty();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: anonymous Authentication → 回 lab user fallback（避免 anonymous 被誤判為合法 user）")
    void current_anonymousAuthentication_returnsLabUserFallback() {
        // Given: principal = "anonymousUser"（Spring Security 預設 anonymous 標記）
        var auth = new UsernamePasswordAuthenticationToken(
                "anonymousUser", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // When + Then
        CurrentUser user = provider.current();
        assertThat(user.userId()).isEqualTo(LAB_USER_ID);
        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.groups()).isEmpty();
    }

    // ---- helpers ----

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
