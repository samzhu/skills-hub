package io.github.samzhu.skillshub.shared.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.api.MissingJwtSubException;

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
 * <p>S115 graceful degradation policy（per ADR-006）：
 * <ul>
 *   <li>{@code sub} REQUIRED — 缺 / blank → {@link MissingJwtSubException} → 401（取代既有 NPE 500 路徑）</li>
 *   <li>{@code roles} / {@code groups} optional — 缺 / 型別錯 / 含非字串元素 → empty list +
 *       WARN log + {@link JwtClaimAnomalyMetrics} counter</li>
 *   <li>未知新 claim — 完全忽略不破 parsing（forward-compat IdP schema 演化）</li>
 * </ul>
 *
 * @see CurrentUser
 * @see LabSecurityFilter
 * @see SkillshubProperties.Lab
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-006-jwt-acl-safety.md">ADR-006</a>
 */
@Component
public class CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserProvider.class);

    /** 從 {@code skillshub.security.lab.user-id} 注入；fallback / LAB 情境下回傳此值。 */
    private final String labUserId;
    private final JwtClaimAnomalyMetrics anomalyMetrics;

    public CurrentUserProvider(SkillshubProperties props, JwtClaimAnomalyMetrics anomalyMetrics) {
        this.labUserId = props.security().lab().userId();
        this.anomalyMetrics = anomalyMetrics;
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
            // S115 AC-1: sub REQUIRED — 缺 / blank → 401（取代既有 jwt.getName() NPE 路徑）
            var sub = token.getSubject();
            if (sub == null || sub.isBlank()) {
                log.atError().addKeyValue("errorCode", "invalid_token")
                        .log("JWT missing or blank sub claim");
                anomalyMetrics.increment("sub", "missing");
                throw new MissingJwtSubException();
            }
            var roles = parseStringListClaim(token, "roles");
            var groups = parseStringListClaim(token, "groups");
            return new CurrentUser(sub, roles, groups);
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
            // S016: LAB / non-JWT 認證無 OIDC claim 來源，groups 預設空 list
            return new CurrentUser(auth.getName(), roles, List.of());
        }

        // (3) 安全 fallback：無 SecurityContext（背景執行緒、test 未注入）— 不丟 NPE
        return new CurrentUser(labUserId, List.of("admin"), List.of());
    }

    /** Audit 欄位常用 shortcut — 等同 {@code current().userId()}。 */
    public String userId() {
        return current().userId();
    }

    /**
     * S115 AC-2/3/4 — 從 JWT 解 List&lt;String&gt; claim；缺 / 型別錯 / 含非字串元素皆走
     * graceful empty list + WARN log + counter 路徑。
     *
     * <p>取代既有 {@code token.getClaimAsStringList(name)} 路徑：原 API 對「string 而非 list」
     * 等型別錯靜默 fallback 為 null，失去 ops 觀測能力。本 helper 顯式區分四種情境：
     * <ol>
     *   <li>claim 完全缺 → empty list（無 log，因正常 IdP 不會發此 claim 是 expected）</li>
     *   <li>claim 為 List 但型別不是 String — skip 該 element + WARN per element + counter</li>
     *   <li>claim 是 List&lt;String&gt; 純淨 — 直接回 immutable copy</li>
     *   <li>claim 不是 List（如 String / Map）— 整體 fallback empty + WARN + counter</li>
     * </ol>
     */
    List<String> parseStringListClaim(Jwt token, String claimName) {
        var raw = token.getClaim(claimName);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            log.atWarn().addKeyValue("claim", claimName)
                    .addKeyValue("actualType", raw.getClass().getSimpleName())
                    .log("JWT claim type mismatch; falling back to empty list");
            anomalyMetrics.increment(claimName, "type_mismatch");
            return List.of();
        }
        var result = new ArrayList<String>(list.size());
        for (var item : list) {
            if (item instanceof String s) {
                result.add(s);
            } else {
                log.atWarn().addKeyValue("claim", claimName)
                        .addKeyValue("element", item == null ? "null" : item.getClass().getSimpleName())
                        .log("JWT claim contains non-string element; skipping");
                anomalyMetrics.increment(claimName, "non_string_element");
            }
        }
        return List.copyOf(result);
    }
}
