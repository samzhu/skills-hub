package io.github.samzhu.skillshub.shared.security.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * S154-T06 — RBAC test fixture helper：pre-seed {@code users} row with stable platform user_id
 * matching JWT sub。
 *
 * <p>解決 T03/T04 後 RBAC 測試 fixture 過時問題：JWT auth 觸發 {@code UserUpsertService.upsertFromOidc}
 * 產生 random {@code u_<6hex>} 不可預期 → 測試 hardcoded {@code "user:alice:..."} 對不上。
 *
 * <p>使用：在 {@code @BeforeEach} 預先 seed 已知 (sub, user_id) 對。後續 JWT auth 走
 * {@code findByOauthProviderAndSub("google", sub)} 找到既有 row 直接返 stable user_id —
 * acl_entries 等 hardcoded 字串對得上。
 *
 * <p>Idempotent：{@code ON CONFLICT DO NOTHING} 容許重複跑（test re-run / 多 test 共享 fixture）。
 */
public final class TestUserSeed {

    /** Stable test user_id mapping — JWT sub → platform user_id。各 RBAC test 共用此 mapping。 */
    public static final String ALICE_ID = "u_alice1";
    public static final String BOB_ID = "u_bob111";
    public static final String CAROL_ID = "u_carol1";
    public static final String TESTER_ID = "u_tstr01";
    public static final String DEV042_ID = "u_dev042";
    public static final String VIEWER007_ID = "u_view07";

    private TestUserSeed() {}

    /** Seed 6 個常用 RBAC test fixture user。 */
    public static void seedDefaults(JdbcTemplate jdbc) {
        seed(jdbc, ALICE_ID, "alice");
        seed(jdbc, BOB_ID, "bob");
        seed(jdbc, CAROL_ID, "carol");
        seed(jdbc, TESTER_ID, "tester");
        seed(jdbc, DEV042_ID, "dev-042");
        seed(jdbc, VIEWER007_ID, "viewer-007");
    }

    /** Same as {@link #seedDefaults(JdbcTemplate)} 但走 NamedParameterJdbcTemplate。 */
    public static void seedDefaults(NamedParameterJdbcTemplate jdbc) {
        seedDefaults(jdbc.getJdbcTemplate());
    }

    /** Seed 單一 user — idempotent ON CONFLICT DO NOTHING。 */
    public static void seed(JdbcTemplate jdbc, String userId, String oauthSub) {
        // Handle 取 user_id 後綴避免跨 user UNIQUE 衝突；ON CONFLICT DO NOTHING 兜底任何 UNIQUE 違反
        var handle = "u-" + userId.substring(2);
        jdbc.update("""
                INSERT INTO users (id, oauth_provider, sub, email, name, handle, created_at, last_seen_at)
                VALUES (?, 'google', ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT DO NOTHING
                """, userId, oauthSub, oauthSub + "@test.local", "Test " + oauthSub, handle);
    }
}
