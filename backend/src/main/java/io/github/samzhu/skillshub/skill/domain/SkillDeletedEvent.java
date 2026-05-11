package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;
import java.util.List;

/**
 * S144 — Published when a skill owner permanently deletes a skill from the registry.
 *
 * @param aggregateId  deleted skill id
 * @param name         deleted skill name for audit/log display after the row is gone
 * @param deletedBy    platform user id that requested deletion
 * @param deletedAt    deletion request timestamp
 * @param storagePaths package object paths to delete asynchronously after commit
 */
public record SkillDeletedEvent(
        String aggregateId,
        String name,
        String deletedBy,
        Instant deletedAt,
        List<String> storagePaths
) {}
