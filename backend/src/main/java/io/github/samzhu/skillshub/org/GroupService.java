package io.github.samzhu.skillshub.org;

import java.lang.invoke.MethodHandles;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S170 application service for creating Groups and maintaining closure rows.
 *
 * @see Group
 * @see GroupRepository
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9-]");
    private static final Pattern REPEATED_DASH = Pattern.compile("-+");
    private static final int GROUP_ID_MAX_RETRY = 5;

    private final GroupRepository repo;
    private final NamedParameterJdbcTemplate jdbc;
    private final GroupIdGenerator ids;

    public GroupService(GroupRepository repo, NamedParameterJdbcTemplate jdbc, GroupIdGenerator ids) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /**
     * Creates one Group and writes its self + ancestor closure rows in the same transaction.
     */
    @Transactional
    public String createGroup(@Nullable String parentId, GroupKind kind, String displayName) {
        if (parentId != null && !repo.existsById(parentId)) {
            throw new IllegalArgumentException("group_parent_not_found: " + parentId);
        }
        var now = Instant.now();
        var group = Group.create(generateUniqueGroupId(), parentId, kind, displayName, slugify(displayName), now);
        repo.save(group);
        insertClosureRows(group.getId(), parentId);
        log.atInfo()
                .addKeyValue("groupId", group.getId())
                .addKeyValue("parentId", parentId)
                .addKeyValue("kind", kind)
                .log("Group created");
        return group.getId();
    }

    /**
     * Moves one Group after checking cycle invariants; subtree closure rebuild lands in S170-T03.
     */
    @Transactional
    public void moveGroup(String groupId, @Nullable String newParentId) {
        var group = repo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("group_not_found: " + groupId));
        if (newParentId != null && !repo.existsById(newParentId)) {
            throw new IllegalArgumentException("group_parent_not_found: " + newParentId);
        }
        if (newParentId != null && isDescendantOf(newParentId, groupId)) {
            log.atWarn()
                    .addKeyValue("groupId", groupId)
                    .addKeyValue("newParentId", newParentId)
                    .log("Rejected group cycle");
            throw new IllegalStateException("group_cycle: cannot move a group under its descendant");
        }
        group.moveTo(newParentId, Instant.now());
        repo.save(group);
    }

    private String generateUniqueGroupId() {
        for (int attempt = 1; attempt <= GROUP_ID_MAX_RETRY; attempt++) {
            var candidate = ids.nextCandidate();
            if (!repo.existsById(candidate)) {
                return candidate;
            }
            log.atWarn()
                    .addKeyValue("attempt", attempt)
                    .addKeyValue("candidate", candidate)
                    .log("group_id collision retry");
        }
        throw new IllegalStateException("Failed to generate unique group_id after " + GROUP_ID_MAX_RETRY + " retries");
    }

    private void insertClosureRows(String groupId, @Nullable String parentId) {
        jdbc.update(
                "INSERT INTO group_closure (ancestor_id, descendant_id, depth) VALUES (:groupId, :groupId, 0)",
                Map.of("groupId", groupId));
        if (parentId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO group_closure (ancestor_id, descendant_id, depth)
                SELECT ancestor_id, :groupId, depth + 1
                FROM group_closure
                WHERE descendant_id = :parentId
                """, Map.of("groupId", groupId, "parentId", parentId));
    }

    private boolean isDescendantOf(String possibleDescendantId, String ancestorId) {
        var params = new MapSqlParameterSource()
                .addValue("ancestorId", ancestorId)
                .addValue("descendantId", possibleDescendantId);
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM group_closure
                WHERE ancestor_id = :ancestorId
                  AND descendant_id = :descendantId
                """, params, Integer.class);
        return count != null && count > 0;
    }

    /**
     * S170 slug rule: lowercase display name, remove accents, collapse non-slug chars to single dashes.
     */
    private static String slugify(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("group_display_name_required");
        }
        var normalized = Normalizer.normalize(displayName.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        var slug = REPEATED_DASH.matcher(NON_SLUG_CHARS.matcher(normalized).replaceAll("-"))
                .replaceAll("-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("group_slug_empty");
        }
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }
}
