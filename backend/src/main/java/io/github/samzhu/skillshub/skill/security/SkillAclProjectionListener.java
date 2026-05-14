package io.github.samzhu.skillshub.skill.security;

import java.lang.invoke.MethodHandles;
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
 *   <li>{@link SkillCreatedEvent} — auto-seeds an OWNER grant for the author,
 *       then rebuilds the ACL projection.</li>
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
     * Auto-seed OWNER grant for the skill author on creation, then rebuild ACL.
     *
     * <p>For PUBLIC visibility skills (initial {@code acl_entries} contains
     * {@code "public:*:read"}), also seeds a public VIEWER grant so {@link #rebuildAcl}
     * preserves public read access after overwriting the aggregate-managed column.
     *
     * <p>If author is null (e.g. test fixtures without an owner), skip seeding —
     * the skill starts with an empty ACL and an explicit grant must be made later.
     */
    @ApplicationModuleListener
    public void onSkillCreated(SkillCreatedEvent event) {
        var skillId = event.aggregateId();
        var author = event.author();
        if (author == null || author.isBlank()) {
            log.atWarn().addKeyValue("skillId", skillId)
                    .log("SkillCreatedEvent has no author — skipping OWNER grant auto-seed");
            return;
        }
        // idempotent: only seed if no existing grant for this author
        var existing = grantRepo.findBySkillIdAndPrincipalTypeAndPrincipalId(skillId, "user", author);
        if (existing.isEmpty()) {
            grantRepo.save(SkillGrant.create(skillId, "user", author, Role.OWNER, author));
            log.atInfo().addKeyValue("skillId", skillId).addKeyValue("author", author)
                    .log("OWNER grant auto-seeded from SkillCreatedEvent");
        }
        // S116: preserve PUBLIC visibility — Skill.create() adds "public:*:read" to acl_entries for
        // PUBLIC skills; rebuildAcl() rebuilds from skill_grants, so a public VIEWER grant must also
        // be seeded here. Read is_public (GENERATED column) BEFORE rebuildAcl() to capture the intent.
        var isPublic = Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT is_public FROM skills WHERE id = :id",
                        Map.of("id", skillId), Boolean.class));
        if (isPublic) {
            var publicExists = grantRepo.findBySkillIdAndPrincipalTypeAndPrincipalId(skillId, "public", "*");
            if (publicExists.isEmpty()) {
                grantRepo.save(SkillGrant.create(skillId, "public", "*", Role.VIEWER, author));
                log.atInfo().addKeyValue("skillId", skillId)
                        .log("Public VIEWER grant auto-seeded for PUBLIC skill");
            }
        }
        rebuildAcl(skillId);
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
        var entries = grants.stream()
                .flatMap(g -> Role.valueOf(g.getRole()).permissions().stream()
                        .map(perm -> g.getPrincipalType() + ":" + g.getPrincipalId() + ":" + perm))
                .distinct()
                .toList();

        var json = writeAclEntries(entries);
        var params = Map.of("id", skillId, "acl", json);
        var skillRows = jdbc.update("UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id", params);
        var vectorRows = jdbc.update("UPDATE vector_store SET acl_entries = :acl::jsonb WHERE skill_id = :id", params);
        log.atInfo()
                .addKeyValue("skillId", skillId)
                .addKeyValue("entryCount", entries.size())
                .addKeyValue("skillsRows", skillRows)
                .addKeyValue("vectorRows", vectorRows)
                .log("ACL projections rebuilt");
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
