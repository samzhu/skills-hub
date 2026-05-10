package io.github.samzhu.skillshub.shared.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.api.MissingJwtSubException;

/**
 * 統一抽象「當前使用者識別」— 從 SecurityContext 取出 + 跟 users 表 lookup/upsert 後組成
 * 8-field {@link CurrentUser}。
 *
 * <h2>S154 breaking change（2026-05-10 ship）</h2>
 *
 * <p>{@link CurrentUser#userId} 改為 platform {@code user_id}（從 OAuth sub lookup users 表得來）。
 * 缺 row 時 lazy upsert（避免「先 publish skill 才 /me」的 race 條件）。所有下游 caller 收到的
 * {@code userId} 必為 {@code u_<6hex>} 格式（除 LAB 模式回 labUserId string）。
 *
 * <h2>三種 Authentication 情境</h2>
 *
 * <ol>
 *   <li>{@link JwtAuthenticationToken} / {@link OAuth2AuthenticationToken}（OAuth/OIDC 模式）—
 *       extract sub + claims → {@link UserUpsertService#upsertFromOidc} → 回 platform user_id +
 *       OIDC name/email + handle + roles/groups</li>
 *   <li>其他已認證 {@link Authentication}（LAB filter 注入的 {@link
 *       org.springframework.security.authentication.UsernamePasswordAuthenticationToken}）— principal
 *       name 直當 userId / sub / handle；不查 users 表（LAB 是 dev-only 合成識別）</li>
 *   <li>無 Authentication 或 anonymous — 安全 fallback {@code (labUserId, ["admin"])}</li>
 * </ol>
 *
 * <h2>S115 graceful degradation policy（per ADR-006）</h2>
 *
 * <ul>
 *   <li>{@code sub} REQUIRED — 缺 / blank → {@link MissingJwtSubException} → 401</li>
 *   <li>{@code roles} / {@code groups} optional — 缺 / 型別錯 / 含非字串元素 → empty list +
 *       WARN log + {@link JwtClaimAnomalyMetrics} counter</li>
 * </ul>
 *
 * @see CurrentUser
 * @see UserUpsertService
 * @see LabSecurityFilter
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-006-jwt-acl-safety.md">ADR-006</a>
 */
@Component
public class CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserProvider.class);

    /** MVP 只支援 Google OAuth；未來加 GitHub 等需擴 enum 並從 ClientRegistration 推導 provider 字串。*/
    private static final String OAUTH_PROVIDER_GOOGLE = "google";

    /** LAB / fallback 路徑 synthetic 顯示名稱（對齊原 {@link MeController} 既有約定）。*/
    private static final String LAB_DISPLAY_NAME = "LAB User";

    /** 從 {@code skillshub.security.lab.user-id} 注入；fallback / LAB 情境下回傳此值。 */
    private final String labUserId;
    private final JwtClaimAnomalyMetrics anomalyMetrics;
    private final UserUpsertService userUpsertService;

    public CurrentUserProvider(SkillshubProperties props,
                               JwtClaimAnomalyMetrics anomalyMetrics,
                               UserUpsertService userUpsertService) {
        this.labUserId = props.security().lab().userId();
        this.anomalyMetrics = anomalyMetrics;
        this.userUpsertService = userUpsertService;
    }

    /** 抽出當前 SecurityContext 內的 user 識別。三情境判斷順序固定。*/
    public CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // (1) OAuth 模式（Bearer JWT）— 主要路徑；解 JWT claims + lazy upsert
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return fromJwt(jwtAuth.getToken());
        }
        // (1b) OAuth2 Login session — oauth2Login 流程；從 OAuth2User attributes 取等價欄位
        if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
            return fromOAuth2User(oauth2Auth.getPrincipal());
        }
        // (2) LAB / 其他已認證 token — 不走 users 表（dev-only 合成身份）
        if (auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            var roles = auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .toList();
            return labLikeCurrentUser(auth.getName(), roles);
        }
        // (3) 安全 fallback：無 SecurityContext（背景執行緒、test 未注入）
        return labLikeCurrentUser(labUserId, List.of("admin"));
    }

    /** Audit 欄位常用 shortcut — 等同 {@code current().userId()}。 */
    public String userId() {
        return current().userId();
    }

    /**
     * JWT path — 從 OIDC standard claims（sub/email/name/picture）+ lazy upsert 取得 platform user。
     */
    private CurrentUser fromJwt(Jwt token) {
        // S115 AC-1: sub REQUIRED — 缺 / blank → 401（取代既有 jwt.getName() NPE 路徑）
        var sub = token.getSubject();
        if (sub == null || sub.isBlank()) {
            log.atError().addKeyValue("errorCode", "invalid_token")
                    .log("JWT missing or blank sub claim");
            anomalyMetrics.increment("sub", "missing");
            throw new MissingJwtSubException();
        }
        var email = token.getClaimAsString("email");
        var name = token.getClaimAsString("name");
        var picture = token.getClaimAsString("picture");
        var roles = parseStringListClaim(token, "roles");
        var groups = parseStringListClaim(token, "groups");
        var companyId = token.getClaimAsString("company_id");

        var user = userUpsertService.upsertFromOidc(OAUTH_PROVIDER_GOOGLE, sub,
                email != null ? email : sub + "@unknown.local", name, picture);
        return new CurrentUser(user.getId(), sub, name, email, user.getHandle(),
                roles, groups, companyId);
    }

    /**
     * OAuth2 Login path — 從 OAuth2User attributes 取等價 OIDC 欄位 + 同 lazy upsert。
     */
    private CurrentUser fromOAuth2User(OAuth2User principal) {
        var sub = principal.getName();
        if (sub == null || sub.isBlank()) {
            log.atError().addKeyValue("errorCode", "invalid_token")
                    .log("OAuth2User missing principal name (sub)");
            anomalyMetrics.increment("sub", "missing");
            throw new MissingJwtSubException();
        }
        String email = principal.getAttribute("email");
        String name = principal.getAttribute("name");
        String picture = principal.getAttribute("picture");

        var user = userUpsertService.upsertFromOidc(OAUTH_PROVIDER_GOOGLE, sub,
                email != null ? email : sub + "@unknown.local", name, picture);
        // OAuth2Login session 沒帶 JWT roles/groups/company_id claims（spring-security 預設不解 ID token）
        // → 給 fallback 空值。production 若需要走 Bearer JWT 路徑（branch 1）。
        return new CurrentUser(user.getId(), sub, name, email, user.getHandle(),
                List.of(), List.of(), null);
    }

    /**
     * LAB / fallback synthetic CurrentUser — 不查 users 表；委派 {@link CurrentUser#synthetic}
     * 確保 LAB / test fixture 同步。
     */
    private CurrentUser labLikeCurrentUser(String labLikeUserId, List<String> roles) {
        return CurrentUser.synthetic(labLikeUserId, roles, List.of(), null);
    }

    /**
     * S115 AC-2/3/4 — 從 JWT 解 List&lt;String&gt; claim；缺 / 型別錯 / 含非字串元素皆走
     * graceful empty list + WARN log + counter 路徑。
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
