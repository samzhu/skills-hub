package io.github.samzhu.skillshub.skill.security;

import java.util.List;

/**
 * S114a — RBAC role for skill access control.
 *
 * <p>OWNER has full management permissions; VIEWER has read-only access.
 * Role determines the set of permission verbs granted to a principal
 * in the {@code skill_grants} source-of-truth table.
 *
 * @see SkillGrant
 */
public enum Role {

    /** Full access: read + write + delete. Typically the skill author. */
    OWNER,

    /** Read-only access. Granted to external collaborators or public. */
    VIEWER;

    /**
     * Permission verbs this role confers.
     * Aligns with the verb set used in {@code acl_entries} 3-segment format.
     */
    public List<String> permissions() {
        return switch (this) {
            case OWNER  -> List.of("read", "write", "delete");
            case VIEWER -> List.of("read");
        };
    }
}
