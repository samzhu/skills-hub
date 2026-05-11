package io.github.samzhu.skillshub.community.events;

import java.time.Instant;

/**
 * S164 — Collection 被 owner 永久刪除。
 *
 * <p>Mirror S144 {@code SkillDeletedEvent}：在 repository.delete() 同 TX 註冊；listener
 * 接到時 row 已不存在，故 payload 自帶必要 audit 欄位（name / ownerId）。
 *
 * <p>FK-backed {@code collection_skills} 由 PostgreSQL {@code ON DELETE CASCADE} 連動清除；
 * 本 spec 不刪 inner skill aggregates（per spec §2.3）。
 *
 * @param collectionId 被刪除的 collection id
 * @param name         deleted collection name（給 listener / log display 用）
 * @param ownerId      原 owner platform user id
 * @param deletedBy    執行刪除的 platform user id（一般 = ownerId）
 * @param deletedAt    刪除時間
 */
public record CollectionDeletedEvent(
        String collectionId,
        String name,
        String ownerId,
        String deletedBy,
        Instant deletedAt) {}
