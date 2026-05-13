package io.github.samzhu.skillshub.org;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * S170 domain event emitted when one Group subtree moves to a new parent.
 *
 * @param groupId     moved Group id
 * @param oldParentId parent id before the move; null means root
 * @param newParentId parent id after the move; null means root
 * @param occurredAt  event timestamp
 * @see GroupService
 */
public record GroupMovedEvent(String groupId,
                              @Nullable String oldParentId,
                              @Nullable String newParentId,
                              Instant occurredAt) {
}
