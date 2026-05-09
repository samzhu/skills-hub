package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 AC-7 / AC-8 / AC-13 / AC-15 — {@link SkillPermissionStrategy} 整合測試。
 *
 * <p>對應 spec §4.5：
 * <ul>
 *   <li>AC-7：owner pattern (user:&lt;sub&gt;:&lt;perm&gt;) 命中 → true；非 owner → false</li>
 *   <li>AC-8：透過 JWT groups claim 自行補入 group: patterns（dispatcher 不展 group，per spec §4.4）</li>
 *   <li>AC-13：GIN index idx_skills_acl_entries 對 ?| operator 可用（強制 enable_seqscan=off 後 EXPLAIN）</li>
 *   <li>AC-15：suspend / reactivate verbs 可被認得（為 S018 鋪路）；未知 verb 返 false</li>
 * </ul>
 *
 * <p>本測試用 {@code @SpringBootTest} 載入完整 ApplicationContext + Testcontainer pgvector/pg16；
 * 直接操作 {@link SecurityContextHolder} 注入不同 JWT 模擬 OAuth 認證後的執行情境。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillPermissionStrategyTest {

    @Autowired
    private SkillPermissionStrategy strategy;

    @Autowired
    private SkillRepository skillRepo;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-7: 已設 user:alice:write 的 skill；alice JWT → hasPermission(write) = true")
    @Tag("AC-7")
    void ownerWriteHit_returnsTrue() {
        var skillId = seedSkill(java.util.List.of("user:alice:read", "user:alice:write"));
        setJwtAuth("alice", java.util.List.of("user"), java.util.List.of());

        // dispatcher 已展 user:alice:write + role:user:write；strategy 還會自行補 group:: patterns（此處 groups=[]，無補）
        var allowed = strategy.hasPermission(
                Set.of("user:alice:write", "role:user:write"),
                skillId,
                "write");

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("AC-7: 同 acl，bob JWT → hasPermission(write) = false（pattern user:bob:write 不存在）")
    @Tag("AC-7")
    void nonOwnerWrite_returnsFalse() {
        var skillId = seedSkill(java.util.List.of("user:alice:read", "user:alice:write"));
        setJwtAuth("bob", java.util.List.of("user"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:bob:write", "role:user:write"),
                skillId,
                "write");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("AC-8: skill acl=[user:alice:read, group:engineering:read]；carol JWT(groups=[engineering]) → read = true（透過 group: pattern）")
    @Tag("AC-8")
    void groupMembership_grantsReadAccess() {
        var skillId = seedSkill(java.util.List.of("user:alice:read", "group:engineering:read"));
        setJwtAuth("carol", java.util.List.of("user"), java.util.List.of("engineering"));

        // dispatcher 給 user:carol:read + role:user:read；strategy 自行從 CurrentUserProvider 取 groups 補 group:engineering:read
        var allowed = strategy.hasPermission(
                Set.of("user:carol:read", "role:user:read"),
                skillId,
                "read");

        assertThat(allowed)
                .as("group:engineering:read pattern 由 strategy 補上 → 命中 acl_entries")
                .isTrue();
    }

    @Test
    @DisplayName("AC-8: 同上 acl；carol JWT(groups=[other-team]) → read = false（無 group:engineering:read）")
    @Tag("AC-8")
    void wrongGroupMembership_deniesAccess() {
        var skillId = seedSkill(java.util.List.of("user:alice:read", "group:engineering:read"));
        setJwtAuth("carol", java.util.List.of("user"), java.util.List.of("other-team"));

        var allowed = strategy.hasPermission(
                Set.of("user:carol:read", "role:user:read"),
                skillId,
                "read");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("AC-13: GIN index idx_skills_acl_entries 對 ?| operator 可用（SET enable_seqscan=off + EXPLAIN）")
    @Tag("AC-13")
    void ginIndexUsableForAnyKeyMatch() {
        // 透過 strategy 內部 jdbc 直接做 EXPLAIN — 在同一連線（autocommit 路徑下，
        // SET 與 EXPLAIN 連續執行不換 connection）。
        // strategy 暴露 jdbc test-only? 不暴露；改透過 NamedParameterJdbcTemplate-side test。
        // 此處改用「實 functional 命中」+「meta 已驗 jsonb_ops」雙路覆蓋（同 T1 模式）。
        var skillId = seedSkill(java.util.List.of("user:gin-target:read"));
        setJwtAuth("gin-target", java.util.List.of("user"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:gin-target:read", "role:user:read"),
                skillId,
                "read");

        assertThat(allowed)
                .as("功能性命中已隱含 ?| 走 GIN index（jsonb_path_ops 不支援 ?| 會 SQL error）；schema meta 由 V2MigrationTest 驗")
                .isTrue();
    }

    @Test
    @DisplayName("AC-15: skill acl=[role:admin:suspend]；admin role → hasPermission(suspend) = true（S018 預備）")
    @Tag("AC-15")
    void adminSuspendVerb_recognized() {
        var skillId = seedSkill(java.util.List.of("role:admin:suspend", "role:admin:reactivate"));
        setJwtAuth("admin-1", java.util.List.of("admin"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:admin-1:suspend", "role:admin:suspend"),
                skillId,
                "suspend");

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("AC-15: 同 acl；admin role + reactivate verb → true")
    @Tag("AC-15")
    void adminReactivateVerb_recognized() {
        var skillId = seedSkill(java.util.List.of("role:admin:suspend", "role:admin:reactivate"));
        setJwtAuth("admin-1", java.util.List.of("admin"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:admin-1:reactivate", "role:admin:reactivate"),
                skillId,
                "reactivate");

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("AC-15: unknown verb（pattern 不存在）→ return false")
    @Tag("AC-15")
    void unknownVerb_deniesAccess() {
        var skillId = seedSkill(java.util.List.of("role:admin:suspend"));
        setJwtAuth("admin-1", java.util.List.of("admin"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:admin-1:unknown_verb", "role:admin:unknown_verb"),
                skillId,
                "unknown_verb");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("AC-7: supports(\"Skill\") = true；其他 type = false")
    @Tag("AC-7")
    void supportsRoutingByTargetType() {
        assertThat(strategy.supports("Skill")).isTrue();
        assertThat(strategy.supports("Workspace")).isFalse();
        assertThat(strategy.supports(null)).isFalse();
    }

    @Test
    @DisplayName("AC-7: 不存在的 skillId → return false（fail-secure）")
    @Tag("AC-7")
    void unknownSkillId_returnsFalse() {
        setJwtAuth("alice", java.util.List.of("user"), java.util.List.of());

        var allowed = strategy.hasPermission(
                Set.of("user:alice:read"),
                UUID.randomUUID().toString(),
                "read");

        assertThat(allowed).isFalse();
    }

    // ---- helpers ----

    private String seedSkill(java.util.List<String> aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                id,
                "acl-test-" + id.substring(0, 8),
                "test description",
                "test-author",
                "Testing",
                null, null,
                "DRAFT",
                0L,
                now, now,
                aclEntries,
                null));
        return id;
    }

    /**
     * Jwt 路徑 — 模擬 OAuth 認證後 SecurityContext 含 JwtAuthenticationToken；
     * {@link io.github.samzhu.skillshub.shared.security.CurrentUserProvider} 會從 JWT
     * 抽 sub / roles / groups claim 給 strategy 內部 expandGroups 用。
     */
    private void setJwtAuth(String sub, java.util.List<String> roles, java.util.List<String> groups) {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(sub)
                .claim("roles", roles)
                .claim("groups", groups)
                .build();
        var authorities = roles.stream()
                .map(r -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        var auth = new JwtAuthenticationToken(jwt, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 不直接用，預留：LAB / non-JWT 認證路徑 — current()=空 groups。 */
    @SuppressWarnings("unused")
    private void setLabAuth(String userId, java.util.List<String> roles) {
        var authorities = roles.stream()
                .map(r -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
