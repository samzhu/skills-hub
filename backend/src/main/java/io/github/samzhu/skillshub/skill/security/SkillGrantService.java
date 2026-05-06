package io.github.samzhu.skillshub.skill.security;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.CannotRevokeOwnOwnerException;
import io.github.samzhu.skillshub.shared.api.GrantNotFoundException;
import io.github.samzhu.skillshub.shared.api.NotSkillOwnerException;
import io.github.samzhu.skillshub.shared.api.OwnerAlreadyExistsException;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillRevokedEvent;

/**
 * S114a — Application service for managing skill access grants.
 *
 * <p>grant() saves a new {@link SkillGrant} row and publishes {@link SkillGrantedEvent};
 * revoke() deletes the row and publishes {@link SkillRevokedEvent}.
 * Both events are consumed by {@code SkillAclProjectionListener} (T04) to rebuild
 * the {@code skills.acl_entries} JSONB column.
 *
 * <p>Owner identity check is enforced at the service layer ({@code ownerId} comparison),
 * not at the {@code @PreAuthorize} level, so the controller only needs
 * {@code isAuthenticated()} guard.
 *
 * @see SkillGrantRepository
 * @see SkillGrantController
 */
@Service
public class SkillGrantService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillRepository skillRepo;
    private final SkillGrantRepository grantRepo;
    private final ApplicationEventPublisher events;
    private final CurrentUserProvider users;

    public SkillGrantService(SkillRepository skillRepo,
                             SkillGrantRepository grantRepo,
                             ApplicationEventPublisher events,
                             CurrentUserProvider users) {
        this.skillRepo = skillRepo;
        this.grantRepo = grantRepo;
        this.events = events;
        this.users = users;
    }

    /**
     * Grant access to a skill for a principal.
     *
     * @param skillId target skill
     * @param req     grant request (principalType, principalId, role)
     * @return new grant id
     * @throws NoSuchElementException        if skill not found
     * @throws NotSkillOwnerException        if actor is not the skill owner
     * @throws OwnerAlreadyExistsException   if OWNER role requested but one already exists
     */
    @Transactional
    public String grant(String skillId, GrantRequest req) {
        var actor = users.current().userId();
        var skill = skillRepo.findById(skillId).orElseThrow(() -> new NoSuchElementException(skillId));
        if (!actor.equals(skill.getOwnerId())) {
            log.atWarn().addKeyValue("skillId", skillId).addKeyValue("actor", actor)
                    .log("Grant denied: actor is not the skill owner");
            throw new NotSkillOwnerException();
        }
        // only one OWNER grant per skill allowed
        if (req.role() == Role.OWNER && grantRepo.existsBySkillIdAndRole(skillId, "OWNER")) {
            log.atWarn().addKeyValue("skillId", skillId).log("Grant denied: OWNER already exists");
            throw new OwnerAlreadyExistsException();
        }
        var grant = SkillGrant.create(skillId, req.principalType(), req.principalId(), req.role(), actor);
        grantRepo.save(grant);
        events.publishEvent(new SkillGrantedEvent(skillId, grant.getId()));
        log.atInfo().addKeyValue("skillId", skillId).addKeyValue("grantId", grant.getId())
                .addKeyValue("role", req.role()).addKeyValue("actor", actor)
                .log("Skill grant created");
        return grant.getId();
    }

    /**
     * Revoke an existing skill grant.
     *
     * @param skillId target skill
     * @param grantId grant row id to delete
     * @throws NoSuchElementException           if skill not found
     * @throws NotSkillOwnerException           if actor is not the skill owner
     * @throws GrantNotFoundException           if grant row not found
     * @throws CannotRevokeOwnOwnerException    if actor tries to revoke their own OWNER grant
     */
    @Transactional
    public void revoke(String skillId, String grantId) {
        var actor = users.current().userId();
        var skill = skillRepo.findById(skillId).orElseThrow(() -> new NoSuchElementException(skillId));
        if (!actor.equals(skill.getOwnerId())) {
            log.atWarn().addKeyValue("skillId", skillId).addKeyValue("actor", actor)
                    .log("Revoke denied: actor is not the skill owner");
            throw new NotSkillOwnerException();
        }
        var grant = grantRepo.findById(grantId).orElseThrow(GrantNotFoundException::new);
        // prevent owner from locking themselves out
        if ("OWNER".equals(grant.getRole()) && actor.equals(grant.getPrincipalId())) {
            throw new CannotRevokeOwnOwnerException();
        }
        grantRepo.deleteById(grantId);
        events.publishEvent(new SkillRevokedEvent(skillId, grantId));
        log.atInfo().addKeyValue("skillId", skillId).addKeyValue("grantId", grantId)
                .addKeyValue("actor", actor).log("Skill grant revoked");
    }

    /**
     * List all grants for a skill.
     *
     * @param skillId target skill id
     * @return list of grants
     */
    public List<SkillGrant> listGrants(String skillId) {
        return grantRepo.findBySkillId(skillId);
    }

    /**
     * Inbound request for creating a grant.
     *
     * @param principalType namespace: user / group / company / public
     * @param principalId   identifier within the namespace
     * @param role          access level
     */
    public record GrantRequest(String principalType, String principalId, Role role) {}
}
