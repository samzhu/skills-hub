package io.github.samzhu.skillshub.shared.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me} — 回傳目前 JWT 解出的 claim 集合。
 *
 * <p>用途：開發者用 {@code curl} 帶 {@code Authorization: Bearer <token>} 請求即可
 * 一眼確認 mock-oauth2-server 簽發的 JWT 內容是否符合預期，並驗證 Spring Security
 * Resource Server 確實有解析該 token。
 *
 * <p>輸出欄位（皆為 String 或 List&lt;String&gt;，缺值時回空字串/空陣列）：
 * <ul>
 *   <li>{@code sub}        — 主體 ID（RFC 7519）</li>
 *   <li>{@code roles}      — 角色陣列（RFC 9068 + SCIM）</li>
 *   <li>{@code groups}     — 群組陣列（RFC 9068 + SCIM；跨部門邏輯集合）</li>
 *   <li>{@code companyId}  — 公司 ID（自訂，camelCase 對應 {@code company_id}）</li>
 *   <li>{@code deptId}     — 部門 ID（自訂，對應 SCIM Enterprise User extension {@code department}）</li>
 *   <li>{@code scope}      — OAuth scope，空格分隔（RFC 8693）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    @GetMapping
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        // 用 LinkedHashMap 而非 Map.of()：(a) 鍵順序穩定方便 JSON debug
        // (b) 允許部分 claim 為 null 而不丟 NPE（Map.of() 不允許 null value）
        var result = new LinkedHashMap<String, Object>();
        result.put("sub",       jwt.getSubject());
        result.put("roles",     orEmpty(jwt.getClaimAsStringList("roles")));
        result.put("groups",    orEmpty(jwt.getClaimAsStringList("groups")));
        result.put("companyId", jwt.getClaimAsString("company_id"));
        result.put("deptId",    jwt.getClaimAsString("dept_id"));
        result.put("scope",     jwt.getClaimAsString("scope"));
        return result;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
