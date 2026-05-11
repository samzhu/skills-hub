package io.github.samzhu.skillshub.skill.security;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S114a — Source-of-truth grant row in {@code skill_grants} table.
 *
 * <p>Each row represents a single principal-to-skill access grant.
 * Grants are created by {@code SkillGrantService} and consumed by
 * {@code SkillAclProjectionListener} to rebuild the denormalized
 * {@code skills.acl_entries} JSONB array.
 *
 * <p>Not an {@code AbstractAggregateRoot} — domain events are published
 * directly by {@code SkillGrantService} via {@code ApplicationEventPublisher}.
 *
 * @see Role
 * @see SkillGrantRepository
 */
@Table("skill_grants")
public class SkillGrant implements Persistable<String> {

    @Id
    private String id;

    @Column("skill_id")
    private String skillId;

    @Column("principal_type")
    private String principalType;

    @Column("principal_id")
    private String principalId;

    // role stored as VARCHAR("OWNER"/"VIEWER") — enum name, not ordinal
    private String role;

    @Column("granted_by")
    private String grantedBy;

    @Column("granted_at")
    private Instant grantedAt;

    /**
     * S154b T04 — transient enriched display name from {@code users.name/email/handle}
     * via 5-layer fallback resolver. Populated only for {@code principalType=user};
     * {@code null} for public / group / company principals or when users row missing.
     */
    @org.springframework.data.annotation.Transient
    private String displayName;

    /**
     * S154b T04 — transient enriched handle from {@code users.handle}. Populated only
     * for {@code principalType=user}; {@code null} for non-user principals.
     */
    @org.springframework.data.annotation.Transient
    private String handle;

    /** Spring Data JDBC entity creator — fields filled via reflection. */
    @PersistenceCreator
    private SkillGrant() {}

    /**
     * Factory method for new grant — generates UUID id and sets timestamp.
     *
     * @param skillId       target skill
     * @param principalType principal namespace: user / group / company / public
     * @param principalId   principal identifier within namespace
     * @param role          access level (OWNER or VIEWER)
     * @param grantedBy     user-id of the granting actor
     */
    public static SkillGrant create(String skillId, String principalType,
                                    String principalId, Role role, String grantedBy) {
        var grant = new SkillGrant();
        grant.id = UUID.randomUUID().toString();
        grant.skillId = skillId;
        grant.principalType = principalType;
        grant.principalId = principalId;
        // store enum name for human-readable DB column
        grant.role = role.name();
        grant.grantedBy = grantedBy;
        grant.grantedAt = Instant.now();
        return grant;
    }

    @Override
    public String getId() { return id; }

    /** version=null → isNew()=true → INSERT path on first save. */
    @Override
    @JsonIgnore
    public boolean isNew() { return true; }

    public String getSkillId() { return skillId; }
    public String getPrincipalType() { return principalType; }
    public String getPrincipalId() { return principalId; }
    public String getRole() { return role; }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }

    /** S154b T04 — enriched display name; {@code null} until {@code SkillGrantService.listGrants} enrich step runs. */
    public String getDisplayName() { return displayName; }

    /** S154b T04 — enriched handle; {@code null} for non-user principals or missing users row. */
    public String getHandle() { return handle; }

    /**
     * S154b T04 — mutator for read-side enrich. Called by {@code SkillGrantService.listGrants}
     * after LEFT JOIN users by {@code principal_id}. Not for write-side use.
     */
    public void enrichDisplay(String displayName, String handle) {
        this.displayName = displayName;
        this.handle = handle;
    }
}
