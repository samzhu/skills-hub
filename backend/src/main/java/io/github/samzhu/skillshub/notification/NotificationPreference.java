package io.github.samzhu.skillshub.notification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S096h2 — Notification subscription preferences per user（4 categories on/off）。
 *
 * <p>Listener (T02) 在 INSERT notification 前查 {@code prefRepo.findById(userId)}：
 * <ul>
 *   <li>無 row → 預設全 ON（{@link #defaults} factory）— user 從未顯式設定 = full opt-in</li>
 *   <li>有 row → 對應 category boolean 為 false 則 skip notification</li>
 * </ul>
 *
 * <p>{@code @Version} 與 {@link Notification} 同 pattern — 區分 INSERT(defaults factory)
 * vs UPDATE(loaded mutate) 兩 save 路徑。
 */
@Table("notification_preferences")
public class NotificationPreference {

    @Id
    @Column("user_id")
    private String userId;
    @Column("flags_enabled")
    private boolean flagsEnabled;
    @Column("reviews_enabled")
    private boolean reviewsEnabled;
    @Column("requests_enabled")
    private boolean requestsEnabled;
    @Column("versions_enabled")
    private boolean versionsEnabled;
    @Column("updated_at")
    private Instant updatedAt;
    @Version
    @JsonIgnore
    private Long version;

    @PersistenceCreator
    private NotificationPreference() {}

    /** S096h2 — 全 ON 預設（user 無 preferences row 時 listener 用此判斷 enabled）。 */
    public static NotificationPreference defaults(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        var p = new NotificationPreference();
        p.userId = userId;
        p.flagsEnabled = true;
        p.reviewsEnabled = true;
        p.requestsEnabled = true;
        p.versionsEnabled = true;
        p.updatedAt = Instant.now();
        p.version = null; // INSERT path
        return p;
    }

    /**
     * S096h2 AC-9 — 部分 update；T03 service 端用 partial body 設定後 save()。
     * 任一參數 null → 該欄位不動。
     */
    public void update(Boolean flags, Boolean reviews, Boolean requests, Boolean versions) {
        if (flags != null) this.flagsEnabled = flags;
        if (reviews != null) this.reviewsEnabled = reviews;
        if (requests != null) this.requestsEnabled = requests;
        if (versions != null) this.versionsEnabled = versions;
        this.updatedAt = Instant.now();
    }

    /**
     * Listener 用 — 給 category 名稱回對應 boolean。
     * Unknown category（V11 schema 外）→ 保守回 true（不 block 未知通知類別）。
     */
    public boolean isEnabled(String category) {
        return switch (category) {
            case "flags" -> flagsEnabled;
            case "reviews" -> reviewsEnabled;
            case "requests" -> requestsEnabled;
            case "versions" -> versionsEnabled;
            default -> true;
        };
    }

    public String getUserId() { return userId; }
    public boolean isFlagsEnabled() { return flagsEnabled; }
    public boolean isReviewsEnabled() { return reviewsEnabled; }
    public boolean isRequestsEnabled() { return requestsEnabled; }
    public boolean isVersionsEnabled() { return versionsEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
