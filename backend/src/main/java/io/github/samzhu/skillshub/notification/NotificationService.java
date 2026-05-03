package io.github.samzhu.skillshub.notification;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.NotNotificationRecipientException;
import io.github.samzhu.skillshub.shared.api.NotificationNotFoundException;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S096h2-T03 — Notification mutation 服務（per spec §4.5）。
 *
 * <p>對應 4 個 endpoints：mark-read / mark-all-read / delete / update-preferences；
 * ownership 守則由 service 端驗：actor != recipient → throw
 * {@link NotNotificationRecipientException}（{@code GlobalExceptionHandler} 翻 403）。
 *
 * <p><b>mark-all-read 走 @Modifying SQL UPDATE ... WHERE recipient AND read_at IS NULL</b>，
 * 不走「N 次 load + save aggregate」迴圈 — 對齊 spec §4.5 設計（避 N round-trip 與 N 個
 * @Version 競賽）。partial index {@code idx_notifications_recipient_unread} 加速本路徑。
 *
 * <p><b>updatePreferences upsert path</b>：無 row 時走 {@link NotificationPreference#defaults}
 * factory 建 default-on entity 再 update + save（INSERT path，version=null）；有 row 時 load + update
 * + save（UPDATE path，version=N）。
 *
 * <p><b>getPreferences 不 INSERT</b>：純讀；無 row 回 in-memory defaults，不持久化以避免
 * 「user 點開 settings 但沒 save」也產生 row 的副作用（spec AC-9 為 update 後才 row 出現）。
 */
@Service
public class NotificationService {

    private final NotificationRepository notifRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final CurrentUserProvider users;

    NotificationService(NotificationRepository notifRepo,
                        NotificationPreferenceRepository prefRepo,
                        CurrentUserProvider users) {
        this.notifRepo = notifRepo;
        this.prefRepo = prefRepo;
        this.users = users;
    }

    /** S096h2 AC-6 — 標記單筆已讀；非 recipient → 403；不存在 → 404。 */
    @Transactional
    public void markRead(String id) {
        var actor = users.userId();
        var n = notifRepo.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));
        if (!n.isOwnedBy(actor)) {
            throw new NotNotificationRecipientException();
        }
        n.markRead();
        notifRepo.save(n);
    }

    /** S096h2 AC-7 — 一鍵 mark-all-read；返回真正更新的列數（debug 用，controller 走 204）。 */
    @Transactional
    public int markAllRead() {
        var actor = users.userId();
        return notifRepo.markAllReadForUser(actor, Instant.now());
    }

    /** S096h2 AC-8 — 硬刪除單筆；ownership 同 mark-read。 */
    @Transactional
    public void delete(String id) {
        var actor = users.userId();
        var n = notifRepo.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));
        if (!n.isOwnedBy(actor)) {
            throw new NotNotificationRecipientException();
        }
        notifRepo.deleteById(id);
    }

    /**
     * S096h2 AC-9 — Partial update preferences（null 表「該欄位不動」）；無 row 時走 upsert path。
     */
    @Transactional
    public NotificationPreference updatePreferences(Boolean flags, Boolean reviews,
                                                    Boolean requests, Boolean versions) {
        var actor = users.userId();
        var pref = prefRepo.findById(actor).orElseGet(() -> NotificationPreference.defaults(actor));
        pref.update(flags, reviews, requests, versions);
        return prefRepo.save(pref);
    }

    /** S096h2 AC-9 GET — 純讀；無 row 回 in-memory defaults（不 INSERT）。 */
    public NotificationPreference getPreferences() {
        var actor = users.userId();
        return prefRepo.findById(actor).orElseGet(() -> NotificationPreference.defaults(actor));
    }
}
