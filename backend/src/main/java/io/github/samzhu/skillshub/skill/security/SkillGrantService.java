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
import io.github.samzhu.skillshub.shared.security.DisplayNameResolver;
import io.github.samzhu.skillshub.shared.security.UserRepository;
import io.github.samzhu.skillshub.shared.security.UserResolver;
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
 * including grants list. Controller guards only authenticate/read the request; service decides
 * whether the actor may manage grant metadata.
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
    /** S154b T04 — email/handle → user_id 解析；user principal grant 寫入前必走。 */
    private final UserResolver userResolver;
    /** S154b T04 — listGrants 讀側 enrich displayName/handle 查詢來源。 */
    private final UserRepository userRepo;

    public SkillGrantService(SkillRepository skillRepo,
                             SkillGrantRepository grantRepo,
                             ApplicationEventPublisher events,
                             CurrentUserProvider users,
                             UserResolver userResolver,
                             UserRepository userRepo) {
        this.skillRepo = skillRepo;
        this.grantRepo = grantRepo;
        this.events = events;
        this.users = users;
        this.userResolver = userResolver;
        this.userRepo = userRepo;
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
        // S154b T04 — user principal 接受 email / handle / user_id 任一形式，後端統一解析成 user_id 寫入 ACL。
        // Trust path：input 以 "u_" 開頭視為 user_id 直接 trust，不查 users 表存在性 — 允許 ACL 預先 grant
        // 給未登入的 user_id（registry semantics：ACL row 寫入時不要求 principal 一定已存在 users 表，
        // align v3.x 既有 E2E fixture 行為 + 真實 platform user_id `u_<6hex>` 與 test fixture `u_view07` 等
        // 非嚴格 hex 變體都被涵蓋）。
        // Resolve path：非 "u_" 前綴 → email / handle → 必須解析成功否則拒。
        // public principal 寫入 "*"，不走 resolver（resolver 不識別 "*"）。
        String resolvedPrincipalId;
        if ("user".equals(req.principalType())) {
            var input = req.principalId();
            if (input != null && input.startsWith("u_")) {
                resolvedPrincipalId = input;
            } else {
                resolvedPrincipalId = userResolver.resolveByEmailHandleOrId(input)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "user_not_found: cannot resolve principal '" + input + "' to user_id"));
            }
        } else {
            resolvedPrincipalId = req.principalId();
        }
        var grant = SkillGrant.create(skillId, req.principalType(), resolvedPrincipalId, req.role(), actor);
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
     * List all grants for a skill, with display-name enrichment for {@code user} principals.
     *
     * <p>S154b T04 — for each {@code principalType=user} row, LEFT JOIN to {@code users} by
     * {@code principal_id} and populate transient {@code displayName} (5-layer fallback) +
     * {@code handle}. Public / group / company rows skip enrichment (no users row to join).
     * Users row missing (deleted account) → transient fields stay {@code null}; frontend
     * {@code getDisplayName} falls back to raw user_id.
     *
     * @param skillId target skill id
     * @return list of grants with display-name enrichment applied in-place
     */
    public List<SkillGrant> listGrants(String skillId) {
        var actor = users.current().userId();
        var skill = skillRepo.findById(skillId).orElseThrow(() -> new NoSuchElementException(skillId));
        if (!actor.equals(skill.getOwnerId())) {
            log.atWarn().addKeyValue("skillId", skillId).addKeyValue("actor", actor)
                    .log("List grants denied: actor is not the skill owner");
            throw new NotSkillOwnerException();
        }
        var grants = grantRepo.findBySkillId(skillId);
        // Pattern align SkillQueryService.enrichAuthorIdentity — same DisplayNameResolver
        // 5-layer fallback chain；email expose policy 此處不需要（list 只給 owner 看，且
        // share modal 不顯 email，只顯 displayName + handle）。
        for (var g : grants) {
            if (!"user".equals(g.getPrincipalType())) continue;
            userRepo.findById(g.getPrincipalId()).ifPresent(u -> {
                var dn = DisplayNameResolver.resolve(
                        u.getName(), null, null, u.getEmail(), u.getHandle(), u.getId());
                g.enrichDisplay(dn, u.getHandle());
            });
        }
        return grants;
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
