package io.github.samzhu.skillshub.org;

import java.time.Instant;

/**
 * S170 domain event emitted when a platform user becomes a direct member of one Group.
 *
 * @param groupId    {@code groups.id} that received the member
 * @param userId     platform {@code users.id} that was added
 * @param occurredAt event timestamp
 * @see GroupMembershipService
 */
public record UserAddedToGroupEvent(String groupId, String userId, Instant occurredAt) {
}
