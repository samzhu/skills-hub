package io.github.samzhu.skillshub.org;

/**
 * Lifecycle state for a {@link Group}; archived groups stay in history but leave active queries.
 */
public enum GroupStatus {
    ACTIVE,
    ARCHIVED
}
