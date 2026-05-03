package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/**
 * S096f2 — Collection 安裝 domain event。
 *
 * <p>{@link io.github.samzhu.skillshub.community.Collection#recordInstall} 註冊；
 * `repo.save()` 透過 Modulith Event Publication Registry 自動寫入 outbox。
 *
 * <p>MVP 無 listener 訂閱（spec §4.4）— 預留 hook 給 future S101b Impact Score
 * 計算「skill 被 N 個 collection 引用、整體 install reach」用。
 *
 * <p>單獨 skill 的 download_count 由 frontend orchestration 觸發 N 個
 * `GET /skills/{id}/download` 自然累計（per spec §1 Approach C），與本 event 解耦。
 */
public record CollectionInstalledEvent(
        String collectionId,
        String installerId,
        Instant installedAt) {}
