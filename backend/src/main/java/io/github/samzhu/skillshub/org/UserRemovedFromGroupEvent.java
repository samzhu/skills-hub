package io.github.samzhu.skillshub.org;

import java.time.Instant;

/**
 * S170 domain event emitted when a platform user leaves one direct Group membership.
 *
 * @param groupId    {@code groups.id} that lost the member
 * @param userId     platform {@code users.id} that was removed
 * @param occurredAt event timestamp
 * @see GroupMembershipService
 */
public record UserRemovedFromGroupEvent(String groupId, String userId, Instant occurredAt) {
}
