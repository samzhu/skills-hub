package io.github.samzhu.skillshub.skill.security;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillRevokedEvent;
import tools.jackson.databind.ObjectMapper;

/**
 * S114a/S169 — Materializes ACL JSONB from source-of-truth {@code skill_grants}.
 *
 * <p>Listens to three events:
 * <ul>
 *   <li>{@link SkillCreatedEvent} — rebuilds the ACL projection from existing grants.</li>
 *   <li>{@link SkillGrantedEvent} — rebuilds ACL projections for the skill.</li>
 *   <li>{@link SkillRevokedEvent} — rebuilds ACL projections for the skill.</li>
 * </ul>
 *
 * <p>All three events are within the same {@code skill} Modulith module, so no
 * cross-module dependency is introduced.
 *
 * <p>rebuildAcl uses a PostgreSQL advisory lock ({@code pg_advisory_xact_lock}) to
 * prevent race conditions when concurrent grants fire simultaneously.
 *
 * @see SkillGrantRepository
 * @see SkillGrantService
 */
@Component
public class SkillAclProjectionListener {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillGrantRepository grantRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SkillAclProjectionListener(SkillGrantRepository grantRepo,
                                      NamedParameterJdbcTemplate jdbc,
                                      ObjectMapper objectMapper) {
        this.grantRepo = grantRepo;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Rebuild ACL after skill creation.
     */
    @ApplicationModuleListener
    public void onSkillCreated(SkillCreatedEvent event) {
        rebuildAcl(event.aggregateId());
    }

    /** Rebuild ACL projection after a grant is added. */
    @ApplicationModuleListener
    public void onGranted(SkillGrantedEvent event) {
        rebuildAcl(event.skillId());
    }

    /** Rebuild ACL projection after a grant is revoked. */
    @ApplicationModuleListener
    public void onRevoked(SkillRevokedEvent event) {
        rebuildAcl(event.skillId());
    }

    /**
     * Rebuild ACL projections from all current grants for the skill.
     *
     * <p>Uses pg_advisory_xact_lock to serialize concurrent rebuilds for the same
     * skill, preventing lost-update race conditions when multiple grants fire within
     * the same short window.
     */
    private void rebuildAcl(String skillId) {
        // advisory lock scoped to this skill — prevents concurrent rebuild races
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtext('acl:' || :id)::bigint)",
                Map.of("id", skillId));

        var grants = grantRepo.findBySkillId(skillId);
        var skillEntries = aclEntries(grants, false);
        var vectorEntries = aclEntries(grants, true);

        var isPublic = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_public FROM skills WHERE id = :id",
                Map.of("id", skillId),
                Boolean.class));

        var skillAcl = writeAclEntries(skillEntries);
        var vectorAcl = writeAclEntries(vectorEntries);
        var params = new HashMap<String, Object>();
        params.put("id", skillId);
        params.put("skillAcl", skillAcl);
        params.put("vectorAcl", vectorAcl);
        params.put("isPublic", isPublic);
        var skillRows = jdbc.update("UPDATE skills SET acl_entries = :skillAcl::jsonb WHERE id = :id", params);
        var vectorRows = jdbc.update(
                "UPDATE vector_store SET acl_entries = :vectorAcl::jsonb, is_public = :isPublic WHERE skill_id = :id",
                params);
        log.atInfo()
                .addKeyValue("skillId", skillId)
                .addKeyValue("skillEntryCount", skillEntries.size())
                .addKeyValue("vectorReadEntryCount", vectorEntries.size())
                .addKeyValue("publicSkill", isPublic)
                .addKeyValue("skillsRows", skillRows)
                .addKeyValue("vectorRows", vectorRows)
                .log("ACL projections rebuilt");
    }

    private List<String> aclEntries(List<SkillGrant> grants, boolean readOnly) {
        return grants.stream()
                .filter(g -> !"public".equals(g.getPrincipalType()))
                .flatMap(g -> Role.valueOf(g.getRole()).permissions().stream()
                        .filter(perm -> !readOnly || "read".equals(perm))
                        .map(perm -> g.getPrincipalType() + ":" + g.getPrincipalId() + ":" + perm))
                .distinct()
                .toList();
    }

    private String writeAclEntries(List<String> entries) {
        try {
            return objectMapper.writeValueAsString(entries);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ACL entries", e);
        }
    }
}
