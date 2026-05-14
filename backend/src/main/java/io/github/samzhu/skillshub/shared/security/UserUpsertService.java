package io.github.samzhu.skillshub.shared.security;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S154 — OIDC /me 觸發的 platform user UPSERT。
 *
 * <p>第一查詢點為 {@code (oauth_provider, sub)}（V18 schema 的 UNIQUE pair）；
 * 找到 → {@link User#refreshFromOidc} + UPDATE；找不到 → 產生 user_id + handle + INSERT。
 *
 * <h2>Collision retry 策略</h2>
 *
 * <ul>
 *   <li><b>user_id</b>（{@code u_<6hex>}）— 6 個 hex char 共 16^6 = 16,777,216 種；
 *       生日攻擊式碰撞機率 1% 大約在 580 user 時發生（{@code √(2 · 16M · 0.02) ≈ 800}）。
 *       撞到時 retry 至多 5 次，仍撞 → throw（後續 spec 加 ID 長度可解）。</li>
 *   <li><b>handle</b> — slugify email local-part；撞名加 {@code -2}, {@code -3}, ...
 *       至多嘗試 100 次（連續 100 個 alice-N 都撞極不可能；real-world 同一 mailbox local-part
 *       超過數十不同 user 通常意味著惡意註冊）。</li>
 * </ul>
 *
 * <h2>Test seam</h2>
 *
 * <p>{@link #userIdSupplier} 預設為 {@link #defaultUserIdSupplier}（{@code UUID.randomUUID}），
 * 測試可透過 visible-for-test ctor 注入 stub supplier 強制 collision。
 *
 * @see User
 * @see UserRepository
 */
@Service
public class UserUpsertService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^u_[0-9a-f]{6}$");
    private static final Pattern HANDLE_NON_SLUG_CHARS = Pattern.compile("[^a-z0-9-]");
    private static final Pattern HANDLE_DIGITS_ONLY = Pattern.compile("\\d+");
    private static final int HANDLE_MAX_LENGTH = 32;
    private static final int USER_ID_MAX_RETRY = 5;
    private static final int HANDLE_MAX_RETRY = 100;

    private final UserRepository repo;
    private final Supplier<String> userIdSupplier;
    private final Supplier<String> handleFallbackSupplier;

    /** Production constructor — Spring 注入；使用 UUID-based supplier。*/
    @Autowired
    public UserUpsertService(UserRepository repo) {
        this(repo, UserUpsertService::defaultUserIdSupplier,
                UserUpsertService::defaultHandleFallbackSupplier);
    }

    /** Visible-for-test ctor — 允許注入 stub supplier 強制 collision retry path。*/
    UserUpsertService(UserRepository repo,
                      Supplier<String> userIdSupplier,
                      Supplier<String> handleFallbackSupplier) {
        this.repo = repo;
        this.userIdSupplier = userIdSupplier;
        this.handleFallbackSupplier = handleFallbackSupplier;
    }

    /**
     * UPSERT user from OIDC claims — /me 主入口（T03 hook）。
     *
     * @param oauthProvider OAuth provider 識別字（MVP: {@code "google"}）
     * @param sub           OIDC {@code sub} claim
     * @param email         OIDC {@code email} claim
     * @param name          OIDC {@code name} claim（nullable）
     * @param avatarUrl     OIDC {@code picture} claim（nullable）
     * @return 已 persist 的 User（INSERT 或 UPDATE 後的 row）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User upsertFromOidc(String oauthProvider, String sub, String email,
                               @Nullable String name, @Nullable String avatarUrl) {
        var existing = repo.findByOauthProviderAndSub(oauthProvider, sub);
        if (existing.isPresent()) {
            var user = existing.get();
            user.refreshFromOidc(email, name, avatarUrl, Instant.now());
            log.info("user refreshed userId={} provider={}", user.getId(), oauthProvider);
            return repo.save(user);
        }
        var now = Instant.now();
        var userId = generateUniqueUserId();
        var handle = generateUniqueHandle(email);
        var user = User.createNew(userId, oauthProvider, sub, email, name, handle, avatarUrl, now);
        log.info("user created userId={} handle={} provider={}", userId, handle, oauthProvider);
        return repo.save(user);
    }

    /**
     * 產生 unique user_id — 撞 PK 時 retry 至多 {@value #USER_ID_MAX_RETRY} 次；仍撞 throw。
     */
    private String generateUniqueUserId() {
        for (int attempt = 1; attempt <= USER_ID_MAX_RETRY; attempt++) {
            String candidate = userIdSupplier.get();
            if (repo.findById(candidate).isEmpty()) {
                return candidate;
            }
            log.warn("user_id collision retry attempt={} candidate={}", attempt, candidate);
        }
        throw new IllegalStateException(
                "Failed to generate unique user_id after " + USER_ID_MAX_RETRY + " retries");
    }

    /**
     * 從 email 推導 handle，撞名加 {@code -N} 後綴。
     *
     * <p>Slug 規則（spec §2.6）：
     * <ol>
     *   <li>取 email local-part（@ 前段），lowercase</li>
     *   <li>移除非 {@code [a-z0-9-]} 字元</li>
     *   <li>截到 {@value #HANDLE_MAX_LENGTH} 字元</li>
     *   <li>純數字 / 空字串 → fallback {@code user-<6hex>}</li>
     * </ol>
     */
    String generateUniqueHandle(String email) {
        String base = slugify(email);
        if (repo.findByHandle(base).isEmpty()) {
            return base;
        }
        // 撞名 retry：alice → alice-2 → alice-3 → ...
        for (int suffix = 2; suffix <= HANDLE_MAX_RETRY; suffix++) {
            String candidate = base + "-" + suffix;
            if (repo.findByHandle(candidate).isEmpty()) {
                return candidate;
            }
        }
        // 連續 100 個 alice-N 都撞 — 退回 user-<6hex> 格式（與 user_id 後綴 share）
        return handleFallbackSupplier.get();
    }

    /**
     * Email → handle slug — pure（無 DB 依賴），visible for unit test。
     */
    String slugify(@Nullable String email) {
        if (email == null || !email.contains("@")) {
            return handleFallbackSupplier.get();
        }
        String localPart = email.substring(0, email.indexOf('@')).toLowerCase();
        String slug = HANDLE_NON_SLUG_CHARS.matcher(localPart).replaceAll("");
        if (slug.isEmpty() || HANDLE_DIGITS_ONLY.matcher(slug).matches()) {
            // 純數字 (如 "1234@x.com") 或全部被濾光 → fallback user-<6hex>
            return handleFallbackSupplier.get();
        }
        return slug.length() > HANDLE_MAX_LENGTH ? slug.substring(0, HANDLE_MAX_LENGTH) : slug;
    }

    /** Production user_id supplier — UUID-derived 6 hex。Format 對齊 V18 PK 格式。*/
    static String defaultUserIdSupplier() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** Production handle fallback — user-<6hex>，與 user_id 後綴 share UUID source。*/
    static String defaultHandleFallbackSupplier() {
        return "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** Test helper：assert 字串符合 V18 user_id 格式。*/
    static boolean isValidUserId(String s) {
        return USER_ID_PATTERN.matcher(s).matches();
    }
}
