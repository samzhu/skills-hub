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
 * <p>用途：開發者用 {@code curl} 帶 {@code Authorization: Bearer <token>} 請求即可
 * 一眼確認 mock-oauth2-server 簽發的 JWT 內容；LAB 模式（S012）下不帶 token 也能拿到
 * 預設 lab user 的識別資料，方便純功能測試。
 *
 * <p>輸出欄位（OAuth + LAB 兩模式皆回 9 個 keys，shape 一致）：
 * <ul>
 *   <li>{@code sub}        — 主體 ID（OAuth: JWT subject；LAB: lab.user-id）</li>
 *   <li>{@code email}      — OIDC {@code email} claim（S141；LAB: "{sub}@lab.skillshub.local"）</li>
 *   <li>{@code name}       — OIDC {@code name} claim（S141；LAB: "LAB User"）</li>
 *   <li>{@code picture}    — OIDC {@code picture} claim（S141；LAB: null）</li>
 *   <li>{@code roles}      — 角色陣列（OAuth: JWT claim；LAB: ["admin"]）</li>
 *   <li>{@code groups}     — 群組陣列（OAuth: JWT claim；LAB: 空陣列）</li>
 *   <li>{@code companyId}  — 公司 ID（OAuth: claim {@code company_id}；LAB: null）</li>
 *   <li>{@code deptId}     — 部門 ID（OAuth: claim {@code dept_id}；LAB: null）</li>
 *   <li>{@code scope}      — OAuth scope，空格分隔（OAuth: claim；LAB: 空字串）</li>
 * </ul>
 *
 * <p>實作分支策略：三個 Authentication 型別各自處理：
 * (1) {@link JwtAuthenticationToken} — Bearer JWT（Resource Server）抽 JWT claims；
 * (2) {@code OAuth2AuthenticationToken} — oauth2Login session（LAB Google OIDC）從
 *     {@code OAuth2User} attributes 取真實 email/name/picture；
 * (3) 其他（含 LAB 注入的 {@code UsernamePasswordAuthenticationToken}）退回
 *     {@link CurrentUserProvider} 取 sub + roles，其餘欄合成空值。
 *
 * @see CurrentUserProvider
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
        // 用 LinkedHashMap 而非 Map.of()：(a) 鍵順序穩定方便 JSON debug
        // (b) 允許部分 claim 為 null 而不丟 NPE（Map.of() 不允許 null value）
        var result = new LinkedHashMap<String, Object>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            // Bearer JWT（Resource Server mode）
            var jwt = jwtAuth.getToken();
            result.put("sub",       jwt.getSubject());
            result.put("email",     jwt.getClaimAsString("email"));
            result.put("name",      jwt.getClaimAsString("name"));
            result.put("picture",   jwt.getClaimAsString("picture"));
            result.put("roles",     orEmpty(jwt.getClaimAsStringList("roles")));
            result.put("groups",    orEmpty(jwt.getClaimAsStringList("groups")));
            result.put("companyId", jwt.getClaimAsString("company_id"));
            result.put("deptId",    jwt.getClaimAsString("dept_id"));
            result.put("scope",     jwt.getClaimAsString("scope"));
        } else if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
            // oauth2Login session（LAB Google OIDC）— 從 OAuth2User attributes 取真實 profile
            var principal = oauth2Auth.getPrincipal();
            result.put("sub",       principal.getName());
            result.put("email",     principal.getAttribute("email"));
            result.put("name",      principal.getAttribute("name"));
            result.put("picture",   principal.getAttribute("picture"));
            result.put("roles",     List.of());
            result.put("groups",    List.of());
            result.put("companyId", null);
            result.put("deptId",    null);
            result.put("scope",     "");
        } else {
            // LAB fallback（LabSecurityFilter 注入的 UsernamePasswordAuthenticationToken）
            var u = users.current();
            result.put("sub",       u.userId());
            result.put("email",     u.userId() + "@lab.skillshub.local");
            result.put("name",      "LAB User");
            result.put("picture",   null);
            result.put("roles",     u.roles());
            result.put("groups",    List.of());
            result.put("companyId", null);
            result.put("deptId",    null);
            result.put("scope",     "");
        }
        return result;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
