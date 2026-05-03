package io.github.samzhu.skillshub.notification;

import org.springframework.data.repository.CrudRepository;

/**
 * S096h2 — User notification preferences。
 *
 * <p>Listener (T02) {@code findById(userId).map(p -> p.isEnabled(cat)).orElse(true)} —
 * 預設全 ON（無 row = 完全 opt-in）。Service (T03) updatePreferences upsert flow。
 */
interface NotificationPreferenceRepository extends CrudRepository<NotificationPreference, String> {
}
