package io.github.samzhu.skillshub.security;

import java.time.Instant;

/**
 * S098e3 — Flag 狀態改變事件。
 *
 * <p>由 {@link FlagService#updateStatus} 在 {@code OPEN → RESOLVED/DISMISSED}
 * transition 後 publish；本 spec 無 listener — 預留 future audit module 訂閱
 * 寫 domain_events log（per S024 既有 cross-cutting audit pattern）。
 */
public record FlagStatusChangedEvent(
        String flagId,
        String skillId,
        FlagStatus oldStatus,
        FlagStatus newStatus,
        String actor,
        Instant changedAt
) {}
