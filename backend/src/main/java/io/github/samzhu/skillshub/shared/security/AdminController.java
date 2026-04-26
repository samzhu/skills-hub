package io.github.samzhu.skillshub.shared.security;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/echo} — 示範 {@code @PreAuthorize("hasRole('admin')")} 的 method-level 授權判斷。
 *
 * <p>本 controller 唯一目的是讓開發者驗證「JWT 內 {@code roles} claim → Spring Security
 * GrantedAuthority → method security 判斷」這段路徑。任何 JWT 即可通過 SecurityFilterChain
 * 的 {@code authenticated()}，但只有 {@code roles} 含 {@code admin} 的 token 才能進到 method body
 * （否則 {@code @PreAuthorize} 會擋下並回 403 Forbidden）。
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {

    @GetMapping("/echo")
    @PreAuthorize("hasRole('admin')")
    Map<String, String> echo(@RequestParam(defaultValue = "hi") String msg,
                             @AuthenticationPrincipal Jwt jwt) {
        return Map.of("echo", msg, "by", jwt.getSubject());
    }
}
