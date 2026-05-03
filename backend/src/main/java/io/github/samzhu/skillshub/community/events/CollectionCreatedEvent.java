package io.github.samzhu.skillshub.community.events;

import java.time.Instant;
import java.util.List;

/**
 * S096f2 — Collection 建立 domain event。
 *
 * <p>{@link io.github.samzhu.skillshub.community.Collection} factory 註冊；
 * `repo.save()` 透過 Modulith Event Publication Registry 自動寫入 outbox。
 *
 * <p>MVP 無 listener 訂閱（spec §4.4）— 預留 hook 給 future S101b Impact Score
 * / cross-module analytics 計算「curator 影響力」用。
 */
public record CollectionCreatedEvent(
        String collectionId,
        String name,
        String ownerId,
        List<String> skillIds,
        Instant createdAt) {}
