package io.github.samzhu.skillshub.org;

import java.time.Instant;

/**
 * S170 domain event emitted when one Group subtree is archived.
 *
 * @param groupId        archived subtree root Group id
 * @param archivedCount  number of Groups marked archived
 * @param occurredAt     event timestamp
 * @see GroupService
 */
public record GroupArchivedEvent(String groupId, int archivedCount, Instant occurredAt) {
}
