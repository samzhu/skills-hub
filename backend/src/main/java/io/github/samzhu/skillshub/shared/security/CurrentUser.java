package io.github.samzhu.skillshub.shared.security;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 當前請求的使用者識別 — 從 {@link org.springframework.security.core.context.SecurityContext}
 * 抽出的最小欄位集合。
 *
 * <h2>S154 breaking change（2026-05-10 ship）</h2>
 *
 * <p>{@link #userId} 語意改變：從「OAuth sub raw」改為「platform user_id `u_<6hex>`」。
 * 所有 ACL 比對、{@code skills.author}、{@code skills.owner_id} 都用 {@code user_id} 作 principal；
 * OAuth {@link #sub} 只在 users 表保留作 OIDC 登入 lookup key。
 *
 * <p>新增 4 個 fields（{@link #sub} / {@link #name} / {@link #email} / {@link #handle}）—
 * 由 {@link CurrentUserProvider} 在 JWT path 從 OIDC claim + users 表查詢時注入；LAB / fallback
 * 模式以 {@code "lab-user"} 等 sentinel 填補。
 *
 * <h2>來源對應</h2>
 *
 * <ul>
 *   <li>OAuth Resource Server（Bearer JWT）— {@link CurrentUserProvider} branch 1：
 *       JWT {@code sub} 找 users 表 → 取 {@code user_id} / {@code handle}；
 *       {@code email} / {@code name} 從 JWT 同名 claim 取</li>
 *   <li>OAuth2 Login session — {@link CurrentUserProvider} branch 1（合併處理）：
 *       同 JWT path，{@code email} / {@code name} 從 OAuth2 attributes 取</li>
 *   <li>LAB / non-JWT 認證 — {@link CurrentUserProvider} branch 2：
 *       {@code userId = sub = handle = labUserId}；{@code email = "<labUserId>@lab.skillshub.local"}；
 *       {@code name = "LAB User"}；{@code roles = auth.getAuthorities()}；其餘 {@link List#of()} / null</li>
 * </ul>
 *
 * <h2>S016 ACL principal 展開</h2>
 *
 * <p>{@link AclPrincipalExpander} 用 {@link #userId} + {@link #groups} + {@link #companyId} 展開
 * row-level {@code acl_entries}（per S016 Row-Level ACL）；展開值對齊 V18 schema 後的 user_id 格式。
 *
 * @param userId    Platform user_id（{@code u_<6hex>}）；LAB / fallback = {@code labUserId} string
 * @param sub       OAuth provider sub raw；LAB / fallback = 同 userId
 * @param name      OIDC {@code name} claim；LAB = "LAB User"；fallback = "Anonymous"
 * @param email     OIDC {@code email} claim；LAB = synthetic
 * @param handle    Platform handle；LAB / fallback = 同 userId
 * @param roles     角色清單；已剝去 Spring Security 的 {@code ROLE_} 前綴
 * @param groups    OIDC {@code groups} claim 原樣抽出；LAB / fallback = {@link List#of()}
 * @param companyId JWT {@code company_id} claim；null 表示 user 無 company context
 */
public record CurrentUser(String userId,
                          String sub,
                          String name,
                          String email,
                          String handle,
                          List<String> roles,
                          List<String> groups,
                          @Nullable String companyId) {

    /**
     * 產生 synthetic CurrentUser — LAB 模式 + test 共用。
     *
     * <p>合成欄位：{@code sub = handle = userId}；{@code email = "<userId>@lab.skillshub.local"}；
     * {@code name = "LAB User"}。S154 前的 4-field test 場景遷移後改用此 factory（呼叫端只關心
     * {@link #userId} / {@link #roles} / {@link #groups} / {@link #companyId} 對 ACL / permission
     * 行為的影響，新增的 4 個 OIDC 欄位給安全 default 即可）。
     *
     * <p><b>注意</b>：production 真實 OAuth 路徑必須用 canonical ctor 帶入完整 OIDC claims；
     * 此 factory 僅限 LAB 模式 / test fixture 使用。
     */
    public static CurrentUser synthetic(String userId,
                                        List<String> roles,
                                        List<String> groups,
                                        @Nullable String companyId) {
        return new CurrentUser(userId,
                userId,                              // sub = userId（synthetic）
                "LAB User",                          // name
                userId + "@lab.skillshub.local",     // email
                userId,                              // handle = userId
                roles, groups, companyId);
    }
}
