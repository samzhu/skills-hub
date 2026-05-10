package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * S016 AC-6 — {@link DelegatingPermissionEvaluator} routing + anonymous 短路。
 *
 * <p>對應 spec §4.4 / §2.4 Challenge #8：
 * <ul>
 *   <li>routing：按 strategy.supports(targetType) 找第一個匹配；無匹配 → false</li>
 *   <li>anonymous 短路：null / AnonymousAuthenticationToken 直接 false（HTTP layer
 *       由 ExceptionTranslationFilter 區分 401/403）</li>
 *   <li>null target / null targetType → false（防 NPE，per spec §4.4 注釋）</li>
 * </ul>
 *
 * <p>純 unit test — 不啟 Spring 容器；用 stub PermissionStrategy。
 */
class DelegatingPermissionEvaluatorTest {

    private final Authentication aliceAuth = new UsernamePasswordAuthenticationToken(
            "alice",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_user")));

    /**
     * S154-T06：dispatcher 走 {@code currentUserProvider.userId()} 取平台 user_id；
     * 既有 stub-routing 測試只驗 routing/short-circuit 行為，不測 principal 字串內容，
     * 用 mock 讓 userId() 回 sentinel 值即可，避免 NPE。
     */
    private final CurrentUserProvider currentUserStub = lenientUserStub("alice");

    private static CurrentUserProvider lenientUserStub(String userId) {
        var stub = mock(CurrentUserProvider.class);
        lenient().when(stub.userId()).thenReturn(userId);
        return stub;
    }

    @Test
    @DisplayName("AC-6: 找到 supports() 的 strategy → 委派並回傳其結果")
    @Tag("AC-6")
    void hasPermission_routesToMatchingStrategy() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        var allowed = evaluator.hasPermission(aliceAuth, "abc-1", "Skill", "read");

        assertThat(allowed).isTrue();
        assertThat(skillStub.lastInvokedPermission).isEqualTo("read");
        assertThat(skillStub.lastInvokedTarget).isEqualTo("abc-1");
    }

    @Test
    @DisplayName("AC-6: 無 strategy supports targetType → 直接 false（不丟 exception）")
    @Tag("AC-6")
    void hasPermission_noMatchingStrategy_returnsFalse() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        // 請求的 type 是 Workspace，但只註冊了 Skill strategy
        var allowed = evaluator.hasPermission(aliceAuth, "ws-1", "Workspace", "read");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("AC-6: 多個 strategies → 取第一個 supports() 為 true 的（ordering by DI 註冊順序）")
    @Tag("AC-6")
    void hasPermission_multipleStrategies_picksFirstMatch() {
        var skillStub = new StubStrategy("Skill", true);
        var workspaceStub = new StubStrategy("Workspace", false);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub, workspaceStub), currentUserStub);

        // dispatch Workspace → 應命中 workspaceStub（Skill stub 不應被呼叫）
        var allowed = evaluator.hasPermission(aliceAuth, "ws-1", "Workspace", "read");

        assertThat(allowed).isFalse();
        assertThat(skillStub.lastInvokedTarget)
                .as("Skill strategy 不該被叫到（workspace request 不命中 supports)")
                .isNull();
        assertThat(workspaceStub.lastInvokedTarget).isEqualTo("ws-1");
    }

    @Test
    @DisplayName("AC-6: AnonymousAuthenticationToken + non-read permission → 直接 false 不 dispatch")
    @Tag("AC-6")
    void hasPermission_anonymous_shortCircuits() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        // Spring Security AnonymousAuthenticationFilter 注入此型別
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        // S122: anonymous + "read" 改走 *:read strategy fallback（不再短路 false）；
        // write 仍走 fail-secure 短路，用 write 驗 anonymous 短路行為。
        var allowed = evaluator.hasPermission(anon, "abc-1", "Skill", "write");

        assertThat(allowed).isFalse();
        assertThat(skillStub.lastInvokedTarget)
                .as("anonymous 非 read permission 應於 dispatcher 短路；strategy 不應收到請求")
                .isNull();
    }

    @Test
    @DisplayName("AC-6: null Authentication + non-read permission → 直接 false（fail-secure）")
    @Tag("AC-6")
    void hasPermission_nullAuthentication_returnsFalse() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        // S122: null auth + "write" 仍走 fail-secure 短路（read 改走 *:read strategy）
        var allowed = evaluator.hasPermission(null, "abc-1", "Skill", "write");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("AC-6: null targetId / null targetType → 直接 false（防 NPE）")
    @Tag("AC-6")
    void hasPermission_nullTargetIdOrType_returnsFalse() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        assertThat(evaluator.hasPermission(aliceAuth, null, "Skill", "read")).isFalse();
        assertThat(evaluator.hasPermission(aliceAuth, "abc-1", null, "read")).isFalse();
    }

    @Test
    @DisplayName("AC-6: 無 strategies 註冊 → 一律 false（boot 早期 / 純測試環境）")
    @Tag("AC-6")
    void hasPermission_noStrategiesRegistered_returnsFalse() {
        var evaluator = new DelegatingPermissionEvaluator(List.of(), currentUserStub);

        assertThat(evaluator.hasPermission(aliceAuth, "abc-1", "Skill", "read")).isFalse();
    }

    @Test
    @DisplayName("AC-6: 物件型 hasPermission(auth, target, perm) overload → 依 target.getClass().getSimpleName() routing")
    @Tag("AC-6")
    void hasPermission_objectOverload_usesSimpleClassName() {
        var skillStub = new StubStrategy("Sample", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        var sample = new Sample("xyz");
        // 三參數 overload — 由 target.getClass().getSimpleName() = "Sample" 衍生 targetType
        var allowed = evaluator.hasPermission(aliceAuth, sample, "read");

        assertThat(allowed).isTrue();
        assertThat(skillStub.lastInvokedTarget).isSameAs(sample);
    }

    @Test
    @DisplayName("AC-6: 物件型 overload + null target → 直接 false")
    @Tag("AC-6")
    void hasPermission_objectOverloadNullTarget_returnsFalse() {
        var skillStub = new StubStrategy("Skill", true);
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);

        assertThat(evaluator.hasPermission(aliceAuth, null, "read")).isFalse();
    }

    @Test
    @DisplayName("AC-6 / S027: ROLE_admin authority → 短路 true，不 dispatch 至 strategy")
    @Tag("AC-6")
    void hasPermission_adminRole_bypassesStrategy() {
        var skillStub = new StubStrategy("Skill", false);  // strategy 即使回 false 也應被 bypass
        var evaluator = new DelegatingPermissionEvaluator(List.of(skillStub), currentUserStub);
        var adminAuth = new UsernamePasswordAuthenticationToken(
                "lab-user",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_admin")));

        // admin 對任何 permission 都 true（write/delete/suspend/reactivate 等 mutation 也通過）
        assertThat(evaluator.hasPermission(adminAuth, "abc-1", "Skill", "read")).isTrue();
        assertThat(evaluator.hasPermission(adminAuth, "abc-1", "Skill", "write")).isTrue();
        assertThat(evaluator.hasPermission(adminAuth, "abc-1", "Skill", "suspend")).isTrue();

        assertThat(skillStub.lastInvokedTarget)
                .as("ROLE_admin bypass 應於 evaluator 層短路；strategy 不應收到請求")
                .isNull();
    }

    // ---- helpers ----

    /** Test stub — 紀錄最後一次呼叫；可控制 supports / hasPermission 回傳值。 */
    private static final class StubStrategy implements PermissionStrategy {
        private final String supportedType;
        private final boolean returnValue;
        Object lastInvokedTarget;
        String lastInvokedPermission;

        StubStrategy(String supportedType, boolean returnValue) {
            this.supportedType = supportedType;
            this.returnValue = returnValue;
        }

        @Override
        public boolean supports(String targetType) {
            return supportedType.equals(targetType);
        }

        @Override
        public boolean hasPermission(Set<String> principals, Object target, String permission) {
            this.lastInvokedTarget = target;
            this.lastInvokedPermission = permission;
            return returnValue;
        }
    }

    /** Sample target type — 用於 object-overload routing 測試（target.getClass().getSimpleName()）。 */
    private record Sample(String id) {}
}
