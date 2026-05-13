package io.github.samzhu.skillshub.org;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * S170 Group aggregate: one tree node that can represent a company, department, team, or other group.
 */
@Table("groups")
public class Group implements Persistable<String> {

    private static final int DISPLAY_NAME_MAX = 160;

    @Id
    private String id;

    @Column("parent_id")
    @Nullable
    private String parentId;

    private GroupKind kind;

    @Column("display_name")
    private String displayName;

    private String slug;

    private GroupStatus status;

    @Column("sort_order")
    private int sortOrder;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Version
    @JsonIgnore
    private Long version;

    @Transient
    @JsonIgnore
    private boolean isNew;

    @PersistenceCreator
    private Group() {}

    /**
     * Creates an active Group row; parent and closure invariants are completed by {@link GroupService}.
     */
    public static Group create(String id, @Nullable String parentId, GroupKind kind, String displayName, String slug,
                               Instant now) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("group_id_required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("group_kind_required");
        }
        var name = normalizeDisplayName(displayName);
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("group_slug_required");
        }
        var group = new Group();
        group.id = id;
        group.parentId = parentId;
        group.kind = kind;
        group.displayName = name;
        group.slug = slug;
        group.status = GroupStatus.ACTIVE;
        group.sortOrder = 0;
        group.createdAt = now;
        group.updatedAt = now;
        group.version = null;
        group.isNew = true;
        return group;
    }

    /**
     * Updates parent id after the service has checked cycle and parent existence invariants.
     */
    void moveTo(@Nullable String newParentId, Instant now) {
        this.parentId = newParentId;
        this.updatedAt = now;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("group_display_name_required");
        }
        var trimmed = displayName.trim();
        if (trimmed.length() > DISPLAY_NAME_MAX) {
            throw new IllegalArgumentException("group_display_name_too_long");
        }
        return trimmed;
    }

    @Override
    public String getId() { return id; }

    @Override
    @JsonIgnore
    public boolean isNew() { return isNew; }

    @Nullable public String getParentId() { return parentId; }
    public GroupKind getKind() { return kind; }
    public String getDisplayName() { return displayName; }
    public String getSlug() { return slug; }
    public GroupStatus getStatus() { return status; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
