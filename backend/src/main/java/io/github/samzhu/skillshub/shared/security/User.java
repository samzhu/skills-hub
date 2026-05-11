package io.github.samzhu.skillshub.shared.security;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S154 — Platform user identity backed by {@code users} table（V18 schema）.
 *
 * <p>Decouples platform {@code userId} (`u_<6hex>`) from OAuth provider {@code sub}
 * — same identity 跨 provider lookup 走 {@code (oauth_provider, sub)} UNIQUE pair；
 * {@code skills.author} / {@code skills.owner_id} / ACL principal 全部使用此 user_id。
 *
 * <p>Upsert lifecycle（per spec §2.6 / §2.8）：
 * <ul>
 *   <li>{@link #createNew} — 第一次 OIDC /me 觸發；user_id collision retry + handle slugify by
 *       {@link UserUpsertService}；INSERT 路徑（{@code isNew = true}）</li>
 *   <li>{@link #refreshFromOidc} — 每次 /me 觸發 sync OIDC claim；UPDATE 路徑
 *       （loaded by {@code @PersistenceCreator}，{@code isNew = false}）</li>
 * </ul>
 *
 * <p><b>Mutability rule</b>（spec §2.6）：
 * <ul>
 *   <li>{@link #id} ({@code u_<6hex>}) — 永遠不變（內部 PK + ACL principal）</li>
 *   <li>{@link #handle} — 使用者可改（本 spec 不做改 handle UI；OIDC refresh 不動 handle）</li>
 *   <li>{@link #email} / {@link #name} / {@link #avatarUrl} — 每次 OIDC refresh 同步</li>
 * </ul>
 *
 * @see UserRepository
 * @see UserUpsertService
 */
@Table("users")
public class User implements Persistable<String> {

    @Id
    private String id;

    @Column("oauth_provider")
    private String oauthProvider;

    private String sub;

    private String email;

    @Nullable
    private String name;

    private String handle;

    @Column("avatar_url")
    @Nullable
    private String avatarUrl;

    /**
     * S168 — wrapper {@link Boolean} (not primitive {@code boolean}) 規避
     * <a href="https://github.com/oracle/graal/issues/5672">oracle/graal#5672</a>
     * GraalVM SubstrateVM MethodHandle adaptation bug — primitive boolean field 的
     * AOT-generated setter 會在 unboxing adapter 階段 corrupt Boolean → Integer
     * 拋 IAE；wrapper 走 {@code UnsafeObjectFieldAccessor} 純 reference cast 不踩 bug。
     * Per JobRunr PR #1501 production-shipped fix。DB 端為 NOT NULL DEFAULT FALSE，
     * 讀回必有值，getter auto-unbox 安全。
     */
    @Column("contact_email_public")
    private Boolean contactEmailPublic;

    @Column("created_at")
    private Instant createdAt;

    @Column("last_seen_at")
    private Instant lastSeenAt;

    /** Transient flag — 區分 INSERT vs UPDATE 路徑（@PersistenceCreator 載入時為 false）。*/
    @Transient
    @JsonIgnore
    private boolean isNew;

    /** Spring Data JDBC 載入路徑 — fields 由 reflection 填入；{@code isNew = false} → UPDATE 路徑。*/
    @PersistenceCreator
    private User() {}

    /**
     * Factory 建立全新 user — 由 {@link UserUpsertService} 在第一次 /me 時呼叫。
     *
     * @param userId             platform user_id（{@code u_<6hex>}；由 service 層 collision retry 產生）
     * @param oauthProvider      OAuth provider 識別字（MVP: {@code "google"}）
     * @param sub                OAuth provider 的 sub claim
     * @param email              OIDC {@code email} claim
     * @param name               OIDC {@code name} claim（nullable）
     * @param handle             由 service 層 slugify + retry 產生（MVP 自 email local-part 推導）
     * @param avatarUrl          OIDC {@code picture} claim（nullable）
     * @param now                {@code created_at} 與 {@code last_seen_at} 初始值
     */
    public static User createNew(String userId, String oauthProvider, String sub, String email,
                                 @Nullable String name, String handle,
                                 @Nullable String avatarUrl, Instant now) {
        var u = new User();
        u.id = userId;
        u.oauthProvider = oauthProvider;
        u.sub = sub;
        u.email = email;
        u.name = name;
        u.handle = handle;
        u.avatarUrl = avatarUrl;
        u.contactEmailPublic = false;  // V18 default false — fail-secure（spec §2.7）
        u.createdAt = now;
        u.lastSeenAt = now;
        u.isNew = true;
        return u;
    }

    /**
     * 同步 OIDC claim 到既有 user — 由 {@link UserUpsertService} 在後續 /me 時呼叫。
     *
     * <p>Null-safe semantics：incoming null 不覆寫既有值（避免 OIDC 偶爾缺 claim 而資料倒退）。
     * Email 永遠 sync（OIDC email claim 是 Google strict required；缺值代表 token 異常）。
     * Handle 不在此處更新（spec §2.6 mutability rule — 改 handle 走專屬 endpoint，不混在 OIDC refresh）。
     */
    public void refreshFromOidc(String email, @Nullable String name,
                                @Nullable String avatarUrl, Instant lastSeenAt) {
        this.email = email;
        if (name != null) {
            this.name = name;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl;
        }
        this.lastSeenAt = lastSeenAt;
    }

    @Override
    public String getId() { return id; }

    @Override
    @JsonIgnore
    public boolean isNew() { return isNew; }

    public String getOauthProvider() { return oauthProvider; }
    public String getSub() { return sub; }
    public String getEmail() { return email; }
    @Nullable public String getName() { return name; }
    public String getHandle() { return handle; }
    @Nullable public String getAvatarUrl() { return avatarUrl; }
    public boolean isContactEmailPublic() { return contactEmailPublic; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
