package io.github.samzhu.skillshub.skill.security;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/**
 * S114a — Repository for {@link SkillGrant} source-of-truth rows.
 *
 * <p>Provides the query methods used by {@code SkillGrantService} (write side)
 * and {@code SkillAclProjectionListener} (projection rebuild).
 *
 * @see SkillGrant
 */
public interface SkillGrantRepository extends CrudRepository<SkillGrant, String> {

    /** All grants for a given skill — used by projection listener to rebuild acl_entries. */
    List<SkillGrant> findBySkillId(String skillId);

    /** Check if at least one OWNER grant exists for a skill — used by grant service guard. */
    boolean existsBySkillIdAndRole(String skillId, String role);

    /** Look up a specific principal's grant — used for duplicate detection. */
    Optional<SkillGrant> findBySkillIdAndPrincipalTypeAndPrincipalId(
            String skillId, String principalType, String principalId);
}
