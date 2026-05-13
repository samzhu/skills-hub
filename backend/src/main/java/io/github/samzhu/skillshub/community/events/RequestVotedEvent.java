package io.github.samzhu.skillshub.community.events;

import java.time.Instant;
import java.util.UUID;

/**
 * S096g2 AC-5/6 → S156c T03 — Request vote 切換事件（toggle on/off）。
 *
 * <p>由 {@code RequestVoteService} 直接 publish via ApplicationEventPublisher（vote
 * 走 atomic SQL，不經 aggregate save outbox path；對齊 S076 download_count 同 pattern）。
 *
 * <p>S156c T03 加 {@code eventId UUID} field — vote toggle 可重複觸發（mirror
 * {@code SkillDownloadedEvent} 用戶可重複下載 pattern）；給 {@code AuditEventListener}
 * 用作 dedupKey 確保 retry 安全 + 每次 vote 寫 1 筆 audit row。
 *
 * @param eventId   事件唯一識別 UUID（用作 audit dedupKey）
 * @param requestId 投票目標 request id
 * @param userId    投票者 user_id
 * @param voted     toggle 後該 user 是否持有 vote（true=投出 / false=取消）
 * @param voteCount toggle 後 request 的票數（atomic GREATEST(0, ±1) 後值）
 * @param votedAt   事件時間
 */
public record RequestVotedEvent(
        UUID eventId,
        String requestId,
        String userId,
        boolean voted,
        long voteCount,
        Instant votedAt
) {}
