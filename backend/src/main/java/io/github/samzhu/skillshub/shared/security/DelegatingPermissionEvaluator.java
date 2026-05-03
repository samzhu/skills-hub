package io.github.samzhu.skillshub.shared.security;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 路由 {@code @PreAuthorize("hasPermission(...)")} SpEL 到對應 aggregate 的
 * {@link PermissionStrategy}（S016；spec §4.4）。
 *
 * <p>DI 自動注入所有 {@link PermissionStrategy} 實作；以
 * {@link PermissionStrategy#supports(String)} 找第一個匹配。新 aggregate 進場時
 * 只需新增一個 {@code @Component PermissionStrategy} 實作，不需修改 dispatcher
 * （Open/Closed Principle）。
 *
 * <h2>Anonymous 短路（S016 + S122 修訂）</h2>
 * <ul>
 *   <li>{@code permission != "read"}：anonymous / null Authentication 直接拒絕（per spec §2.4
 *       Challenge #8）— mutation 嚴格守。</li>
 *   <li>{@code permission == "read"}：anonymous 走 {@code [*:read]} pseudo-principal
 *       評估 strategy（per S026：read 預設開放給所有 user，含 anonymous）。S122 加此
 *       特例修補：在 read endpoint 加 {@code @PreAuthorize} 後，anonymous 對 PUBLIC skill
 *       仍可訪問；對 PRIVATE skill 走 strategy 拒絕後 ExceptionTranslationFilter
 *       區分 401（未認證）/ 403。</li>
 * </ul>
 * 短路避免 dispatcher 把 anonymous 當合法 principal 展開，造成誤命中 {@code user:anonymous:read}
 * 等病態 entry；read 走 {@code *:read} 是「不展 user 命名空間」的 principle-respecting 設計。
 *
 * <h2>Principal 展開分工</h2>
 * dispatcher 僅展開 {@code user:} / {@code role:} 兩命名空間（從 {@link Authentication}
 * 直接取，避免循環依賴 {@link CurrentUserProvider}）；{@code group:} 由各 strategy 自行
 * 透過 {@link CurrentUserProvider#current()}.{@code groups()} 取得後補上 — 為了讓 strategy
 * 同時能用真實 SecurityContext 與測試 stub，不強耦合 dispatcher 與 user-context 抽象。
 *
 * @see PermissionStrategy
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html">Spring Security ACLs Reference</a>
 */
@Component
public class DelegatingPermissionEvaluator implements PermissionEvaluator {

    /** S122: anonymous read fallback — 對齊 S026 「{@code *:read} read 預設公開」設計。 */
    private static final Set<String> ANONYMOUS_READ_PRINCIPALS = Set.of("*:read");

    private final List<PermissionStrategy> strategies;

    public DelegatingPermissionEvaluator(List<PermissionStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 物件型 overload — {@code @PostAuthorize("hasPermission(returnObject, 'read')")} 路徑；
     * 由 {@code target.getClass().getSimpleName()} 衍生 targetType。
     */
    @Override
    public boolean hasPermission(@Nullable Authentication auth,
                                 @Nullable Object target,
                                 Object permission) {
        if (target == null) {
            return false;
        }
        var targetType = target.getClass().getSimpleName();
        return evaluate(auth, target, targetType, permission.toString());
    }

    /**
     * ID 型 overload — {@code @PreAuthorize("hasPermission(#id, 'Skill', 'read')")} 主路徑。
     */
    @Override
    public boolean hasPermission(@Nullable Authentication auth,
                                 @Nullable Serializable targetId,
                                 @Nullable String targetType,
                                 Object permission) {
        if (targetId == null || targetType == null) {
            return false;
        }
        return evaluate(auth, targetId, targetType, permission.toString());
    }

    /**
     * fail-secure 認證檢查 — null / anonymous / unauthenticated 一律 false。
     * 邊界判斷集中在此，避免兩個 overload 各自漏一條路徑。
     */
    private boolean authenticated(@Nullable Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    private boolean evaluate(@Nullable Authentication auth, Object target, String targetType, String permission) {
        // S122: anonymous + read 走 *:read public principal — 對齊 S026 read 預設開放給所有
        // user 設計；@PreAuthorize 對 read endpoint 啟用後 anonymous 對 PUBLIC skill 仍可
        // 通過 strategy 評估（acl_entries 含 *:read 命中），對 PRIVATE skill 走 strategy 拒
        // 絕後由 ExceptionTranslationFilter 翻 401。其他 permission（write/delete/...）維持
        // S016 §2.4 #8 anonymous fail-secure（短路 false）。
        if (!authenticated(auth)) {
            if ("read".equals(permission)) {
                return strategies.stream()
                        .filter(s -> s.supports(targetType))
                        .findFirst()
                        .map(s -> s.hasPermission(ANONYMOUS_READ_PRINCIPALS, target, permission))
                        .orElse(false);
            }
            return false;
        }

        // S027: ROLE_admin 全 permission bypass — admin 為 organization-level super-admin role
        // （RBAC 慣例如 GitHub org admin / Atlassian site admin）；對所有 aggregate 都有完整
        // read/write/delete/suspend/reactivate 權限，不查 ACL strategy。
        // dev 模式（local profile LAB mode）lab-user 預設帶 ROLE_admin → 自動通過 @PreAuthorize；
        // prod 模式只有 OIDC claim roles=["admin"] 的真實 user 才會帶此 authority（無法 spoof）。
        if (hasAdminRole(auth)) {
            return true;
        }
        var principals = expandPrincipals(auth, permission);
        return strategies.stream()
                .filter(s -> s.supports(targetType))
                .findFirst()
                .map(s -> s.hasPermission(principals, target, permission))
                .orElse(false);
    }

    private boolean hasAdminRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
    }

    /**
     * Authentication → {@code user:} / {@code role:} patterns。
     *
     * <p>不展 {@code group:} — group 來源是 OIDC claim / LAB property，dispatcher 為了
     * 與 {@link CurrentUserProvider} 解耦不接觸 user context；strategy 端各自補。
     */
    private Set<String> expandPrincipals(Authentication auth, String permission) {
        var p = new HashSet<String>();
        p.add("user:" + auth.getName() + ":" + permission);
        auth.getAuthorities().forEach(a -> {
            // 剝去 ROLE_ 前綴回到業務語意（與 CurrentUserProvider 的處理一致）
            var role = a.getAuthority().replaceFirst("^ROLE_", "");
            p.add("role:" + role + ":" + permission);
        });
        return p;
    }
}
