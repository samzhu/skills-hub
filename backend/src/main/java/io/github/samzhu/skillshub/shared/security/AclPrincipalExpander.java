package io.github.samzhu.skillshub.shared.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * {@link CurrentUser} → {@code type:principal:permission} patterns（S016；spec §4.6）。
 *
 * <p>Skills Hub 使用 flat string array JSONB schema：每個 ACL entry 形如
 * {@code "user:alice:read"} / {@code "role:admin:write"} / {@code "group:eng:delete"}；
 * 配合 GIN(jsonb_ops) 的 {@code ?|} operator 實現 row-level 過濾。
 *
 * <p>Component 而非 static helper：
 * <ul>
 *   <li>方便單元測試 mock（DI 注入點清晰）</li>
 *   <li>未來加 {@code org:} / {@code dept:} / {@code room:} 命名空間時不破壞 caller
 *       — 邏輯集中在此一處，PRD B7（Workspace）/ B8（WarRoom）擴展時零外部 mod</li>
 * </ul>
 *
 * @see DelegatingPermissionEvaluator
 */
@Component
public class AclPrincipalExpander {

    /**
     * 完整展開 — user + roles + groups 三命名空間。
     *
     * <p>用於需要從 {@link CurrentUser} 直接生 patterns 的場景（API layer / 非 dispatcher 路徑）。
     */
    public List<String> expand(CurrentUser user, String permission) {
        var patterns = new ArrayList<String>();
        patterns.add("user:" + user.userId() + ":" + permission);
        for (var role : user.roles()) {
            patterns.add("role:" + role + ":" + permission);
        }
        for (var group : user.groups()) {
            patterns.add("group:" + group + ":" + permission);
        }
        return patterns;
    }

    /**
     * 僅展 group 命名空間 — 給 strategy 補 {@link DelegatingPermissionEvaluator}
     * 沒展開的部分（dispatcher 為避免循環依賴 {@link CurrentUserProvider} 而不接 group）。
     */
    public List<String> expandGroups(List<String> groups, String permission) {
        return groups.stream().map(g -> "group:" + g + ":" + permission).toList();
    }
}
