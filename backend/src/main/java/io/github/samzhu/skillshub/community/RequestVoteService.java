package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.community.events.RequestVotedEvent;
import io.github.samzhu.skillshub.shared.api.RequestNotFoundException;

/**
 * S096g2-T02 — Request vote toggle service。
 *
 * <p>Atomic SQL：
 * <ol>
 *   <li>{@code INSERT INTO request_votes ... ON CONFLICT (request_id, user_id) DO NOTHING}
 *       — 新 vote 寫成功 (rowsAffected=1) / 已 vote 過 (rowsAffected=0)</li>
 *   <li>若 0：{@code DELETE} 既有 vote row → toggle off</li>
 *   <li>UPDATE {@code requests.vote_count} +1 / -1 (with GREATEST 防負數)</li>
 *   <li>publish {@link RequestVotedEvent} via ApplicationEventPublisher（不走 aggregate
 *       outbox 因 vote_count 是 @ReadOnlyProperty + atomic SQL，aggregate save() 會
 *       覆蓋並發 increment；對齊 Skill downloadCount S076 同 pattern）</li>
 * </ol>
 *
 * <p>Race safety：DB 層 PRIMARY KEY (request_id, user_id) 防雙寫；ON CONFLICT DO NOTHING
 * 是 idempotent；UPDATE 走 atomic +1/-1，並發安全。
 */
@Service
public class RequestVoteService {

    private final NamedParameterJdbcTemplate jdbc;
    private final RequestRepository repo;
    private final ApplicationEventPublisher events;

    public RequestVoteService(NamedParameterJdbcTemplate jdbc,
                              RequestRepository repo,
                              ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
        this.repo = repo;
    }

    /**
     * Toggle user's vote on a request。
     *
     * @return new {@link VoteResult} with current voted state and post-toggle vote_count
     * @throws RequestNotFoundException request 不存在
     */
    @Transactional
    public VoteResult toggle(String requestId, String userId) {
        // AC-5/6 pre-check：request 存在
        if (!repo.existsById(requestId)) {
            throw new RequestNotFoundException(requestId);
        }

        // 嘗試 INSERT；ON CONFLICT 表已 vote 過
        int inserted = jdbc.update(
                "INSERT INTO request_votes (request_id, user_id, voted_at) VALUES (:r, :u, :t) "
                        + "ON CONFLICT (request_id, user_id) DO NOTHING",
                Map.of("r", requestId, "u", userId, "t", java.sql.Timestamp.from(Instant.now())));

        boolean voted;
        if (inserted == 1) {
            // 新 vote
            jdbc.update("UPDATE requests SET vote_count = vote_count + 1 WHERE id = :id",
                    Map.of("id", requestId));
            voted = true;
        } else {
            // 已 vote → toggle off
            jdbc.update("DELETE FROM request_votes WHERE request_id = :r AND user_id = :u",
                    Map.of("r", requestId, "u", userId));
            // GREATEST(... , 0) 防 race 出負數（理論上 schema CHECK >= 0 + UNIQUE 已 enforce，
            // 此處為 application-level defense-in-depth）
            jdbc.update("UPDATE requests SET vote_count = GREATEST(vote_count - 1, 0) WHERE id = :id",
                    Map.of("id", requestId));
            voted = false;
        }

        Long newCount = jdbc.queryForObject(
                "SELECT vote_count FROM requests WHERE id = :id",
                Map.of("id", requestId), Long.class);
        long count = newCount == null ? 0L : newCount;

        // S156c T03 — eventId UUID 為 AuditEventListener dedupKey（mirror SkillDownloadedEvent pattern；
        // 用戶可重複 toggle vote，每次寫 1 筆 audit row，UUID 確保 retry 安全）
        events.publishEvent(new RequestVotedEvent(
                UUID.randomUUID(), requestId, userId, voted, count, Instant.now()));
        return new VoteResult(voted, count);
    }

    public record VoteResult(boolean voted, long voteCount) {}
}
