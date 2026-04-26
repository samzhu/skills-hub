package io.github.samzhu.skillshub.shared.security;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * 統一抽象「當前使用者識別」— OAuth 模式回 JWT subject、LAB 模式回預設 lab user（S012）。
 *
 * <p>三種 Authentication 情境分別處理：
 * <ol>
 *   <li>{@link JwtAuthenticationToken}（OAuth 模式）— 從 JWT 抽 {@code sub} 與 {@code roles} claim</li>
 *   <li>其他已認證 {@link Authentication}（如 {@link LabSecurityFilter} 注入的
 *       {@code UsernamePasswordAuthenticationToken}）— 從 principal name 與 authorities 取值，
 *       並剝去 {@code ROLE_} 前綴回到業務語意</li>
 *   <li>無 Authentication 或 anonymous — 回傳安全 fallback {@code (labUserId, ["admin"])}，
 *       避免背景執行緒未繼承 SecurityContext 時呼叫端 NPE</li>
 * </ol>
 *
 * <p>未來任何需要記錄 {@code createdBy} / {@code updatedBy} 的 audit 欄位，
 * constructor 注入本 provider 並呼叫 {@link #userId()} 即可，不需各自處理 JWT vs LAB 差異。
 *
 * @see CurrentUser
 * @see LabSecurityFilter
 * @see SkillshubProperties.Lab
 */
@Component
public class CurrentUserProvider {

    /** 從 {@code skillshub.security.lab.user-id} 注入；fallback / LAB 情境下回傳此值。 */
    private final String labUserId;

    public CurrentUserProvider(SkillshubProperties props) {
        this.labUserId = props.security().lab().userId();
    }

    /**
     * 抽出當前 SecurityContext 內的 user 識別。
     *
     * <p>三種情境的判斷順序固定：先看 JWT、再看其他已認證 token、最後 fallback。
     */
    public CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // (1) OAuth 模式：JwtAuthenticationToken — 直接讀 JWT claims，避免兜回 authorities
        if (auth instanceof JwtAuthenticationToken jwt) {
            var token = jwt.getToken();
            var roles = token.getClaimAsStringList("roles");
            return new CurrentUser(jwt.getName(), roles == null ? List.of() : roles);
        }

        // (2) LAB / 其他認證模式：principal 非 anonymous 且已認證
        // 過濾 anonymousUser 是因 Spring Security 的 AnonymousAuthenticationFilter 也會
        // 設一個「已認證」的 anonymous token；那不算合法 user，應走 fallback。
        if (auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            var roles = auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .toList();
            return new CurrentUser(auth.getName(), roles);
        }

        // (3) 安全 fallback：無 SecurityContext（背景執行緒、test 未注入）— 不丟 NPE
        return new CurrentUser(labUserId, List.of("admin"));
    }

    /** Audit 欄位常用 shortcut — 等同 {@code current().userId()}。 */
    public String userId() {
        return current().userId();
    }
}
