package io.github.samzhu.skillshub.shared.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me} — 回傳目前 SecurityContext 解出的 user 識別與 claim 集合。
 *
 * <p>S154 起 response 由 9 個 keys 擴為 11 個（新增 {@code userId} + {@code handle}）；
 * {@code userId} 為 platform {@code u_<6hex>}（從 {@link CurrentUserProvider#current()}
 * 取得，已內建 lazy UPSERT；參見 {@link UserUpsertService}）。
 *
 * <p>輸出欄位（OAuth + LAB 兩模式皆回 11 個 keys，shape 一致）：
 * <ul>
 *   <li>{@code userId}    — Platform user_id（{@code u_<6hex>}；S154 新增）</li>
 *   <li>{@code handle}    — Platform handle（S154 新增）</li>
 *   <li>{@code sub}       — OAuth provider raw subject（OAuth: JWT subject；LAB: lab.user-id）</li>
 *   <li>{@code email}     — OIDC {@code email} claim</li>
 *   <li>{@code name}      — OIDC {@code name} claim</li>
 *   <li>{@code picture}   — OIDC {@code picture} claim</li>
 *   <li>{@code roles}     — 角色陣列</li>
 *   <li>{@code groups}    — 群組陣列</li>
 *   <li>{@code companyId} — 公司 ID</li>
 *   <li>{@code deptId}    — 部門 ID</li>
 *   <li>{@code scope}     — OAuth scope，空格分隔</li>
 * </ul>
 *
 * <p>實作策略：所有解析路徑（JWT / OAuth2 session / LAB）統一委派 {@link CurrentUserProvider#current()}
 * 取得 platform 識別（含 lazy UPSERT），再由本 controller 補額外的 OAuth-only 欄位（picture / deptId /
 * scope — 這些不放進 {@link CurrentUser} 因為非每個 caller 都需要）。
 *
 * @see CurrentUserProvider
 * @see UserUpsertService
 * @see LabSecurityFilter
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final CurrentUserProvider users;

    MeController(CurrentUserProvider users) {
        this.users = users;
    }

    @GetMapping
    Map<String, Object> me() {
        // CurrentUserProvider 內含 OAuth path 的 lazy UPSERT — 此處 call 一次即觸發
        // users 表 sync（首次登入 INSERT；之後 UPDATE last_seen_at + email/name/picture refresh）。
        var current = users.current();

        // LinkedHashMap 維持 keys 出現順序方便 JSON debug + 容許 null value（Map.of() 不允許）
        var result = new LinkedHashMap<String, Object>();
        result.put("userId",    current.userId());
        result.put("handle",    current.handle());
        result.put("sub",       current.sub());
        result.put("email",     current.email());
        result.put("name",      current.name());
        result.put("roles",     current.roles());
        result.put("groups",    current.groups());
        result.put("companyId", current.companyId());

        // 額外 OAuth-only 欄位（picture / deptId / scope）— 不在 CurrentUser，現場讀 SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            var jwt = jwtAuth.getToken();
            result.put("picture", jwt.getClaimAsString("picture"));
            result.put("deptId",  jwt.getClaimAsString("dept_id"));
            result.put("scope",   jwt.getClaimAsString("scope"));
        } else if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
            var principal = oauth2Auth.getPrincipal();
            result.put("picture", principal.<String>getAttribute("picture"));
            result.put("deptId",  null);
            result.put("scope",   "");
        } else {
            // LAB fallback / anonymous
            result.put("picture", null);
            result.put("deptId",  null);
            result.put("scope",   "");
        }
        return result;
    }

    @SuppressWarnings("unused")
    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
