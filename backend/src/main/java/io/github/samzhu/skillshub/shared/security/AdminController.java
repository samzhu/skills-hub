package io.github.samzhu.skillshub.shared.security;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/echo} — 示範 {@code @PreAuthorize("hasRole('admin')")}
 * 的 method-level 授權判斷（S011 + S012）。
 *
 * <p>本 controller 唯一目的是讓開發者驗證「JWT/LAB Authentication → Spring Security
 * GrantedAuthority → method security 判斷」這段路徑。
 *
 * <p>OAuth 模式：任何 JWT 即可通過 SecurityFilterChain 的 {@code authenticated()}，
 * 但只有 {@code roles} 含 {@code admin} 的 token 才能進到 method body
 * （否則 {@code @PreAuthorize} 會擋下並回 403 Forbidden）。
 *
 * <p>LAB 模式（S012）：{@link LabSecurityFilter} 注入帶 {@code ROLE_admin} 的
 * {@code UsernamePasswordAuthenticationToken}，{@code @PreAuthorize} 通過，
 * {@code by} 欄位為 lab user。{@link CurrentUserProvider#userId()} 統一抽象兩模式，
 * 避免 LAB 下 {@code @AuthenticationPrincipal Jwt} 因 principal 型別不符而 NPE。
 *
 * @see CurrentUserProvider
 * @see LabSecurityFilter
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {

    private final CurrentUserProvider users;

    AdminController(CurrentUserProvider users) {
        this.users = users;
    }

    @GetMapping("/echo")
    @PreAuthorize("hasRole('admin')")
    Map<String, String> echo(@RequestParam(defaultValue = "hi") String msg) {
        return Map.of("echo", msg, "by", users.userId());
    }
}
