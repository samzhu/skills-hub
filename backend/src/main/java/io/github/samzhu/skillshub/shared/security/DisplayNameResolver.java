package io.github.samzhu.skillshub.shared.security;

import org.jspecify.annotations.Nullable;

/**
 * S154 §2.4 — 顯示名解析鏈（DisplayName Resolver）：5-layer fallback。
 *
 * <p>用於把 OIDC claims + platform 欄位（user_id / handle）轉成可讀的 display name；
 * frontend `lib/displayName.ts` 同邏輯（S154b 對齊）。
 *
 * <p>優先序（從最具體到最 fallback）：
 * <ol>
 *   <li>OIDC {@code name} claim — Google 提供 full name（"Alice Chen"）</li>
 *   <li>{@code given_name + " " + family_name} — Google 偶爾只提供分離欄位</li>
 *   <li>email local-part 首字大寫 — "alice@example.com" → "Alice"</li>
 *   <li>handle — "alice"</li>
 *   <li>user_id — "u_a3f9c1"</li>
 * </ol>
 *
 * <p><b>Invariant</b>：永不 fall through 到 raw OAuth {@code sub}（21 位 Google ID
 * 對 user 完全 unreadable — S154 spec 的核心動機）。
 */
public final class DisplayNameResolver {

    private DisplayNameResolver() {}

    /**
     * 計算 display name — 5-layer fallback；保證回傳非 null 字串。
     *
     * @param name        OIDC {@code name} claim（nullable）
     * @param givenName   OIDC {@code given_name} claim（nullable）
     * @param familyName  OIDC {@code family_name} claim（nullable）
     * @param email       OIDC {@code email} claim（nullable）
     * @param handle      platform handle（nullable）
     * @param userId      platform user_id；最終 fallback 必須非 null
     * @return 第一個非空白的 layer 結果
     */
    public static String resolve(@Nullable String name,
                                 @Nullable String givenName,
                                 @Nullable String familyName,
                                 @Nullable String email,
                                 @Nullable String handle,
                                 String userId) {
        // Layer 1：OIDC standard claim — 直接可用就用，避免 substring / capitalize 損精度
        if (isNonBlank(name)) {
            return name.trim();
        }
        // Layer 2：given_name + family_name 拼接 — 兩者只要一邊有就拼，缺的那邊 trim 掉空白
        if (isNonBlank(givenName) || isNonBlank(familyName)) {
            String combined = (orEmpty(givenName) + " " + orEmpty(familyName)).trim();
            if (!combined.isEmpty()) {
                return combined;
            }
        }
        // Layer 3：email local-part 首字大寫 — RFC 5321 email 必有 @；split + capitalize
        if (isNonBlank(email)) {
            int at = email.indexOf('@');
            if (at > 0) {
                String localPart = email.substring(0, at);
                if (!localPart.isBlank()) {
                    return Character.toUpperCase(localPart.charAt(0))
                            + localPart.substring(1).toLowerCase();
                }
            }
        }
        // Layer 4：handle — 平台 username slug
        if (isNonBlank(handle)) {
            return handle.trim();
        }
        // Layer 5：user_id — 最終 fallback；schema 保證非 null（V18 PRIMARY KEY）
        return userId;
    }

    private static boolean isNonBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s.trim();
    }
}
