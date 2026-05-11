package io.github.samzhu.skillshub.community.events;

import java.time.Instant;
import java.util.List;

/**
 * S164 — Collection metadata 被 owner 更新。
 *
 * <p>Payload 內含 *完整新狀態* — listeners 不需要 diff，直接覆蓋 derived state（如 search
 * projection 用 name + description 重建 embedding；analytics 直接覆寫 row 不算 delta）。
 *
 * @param collectionId  被更新的 collection id
 * @param name          新 name（trim 後）
 * @param description   新 description（trim 後；可能 null）
 * @param category      新 category（trim 後）
 * @param skillIds      新 skillIds（整段覆蓋，保留 list 順序）
 * @param updatedBy     發起更新的 platform user id（= owner）
 * @param updatedAt     更新時間
 */
public record CollectionUpdatedEvent(
        String collectionId,
        String name,
        String description,
        String category,
        List<String> skillIds,
        String updatedBy,
        Instant updatedAt) {}
