package io.github.samzhu.skillshub.org;

import java.lang.invoke.MethodHandles;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S170 application service for direct user-to-Group membership rows.
 *
 * @see UserAddedToGroupEvent
 * @see UserRemovedFromGroupEvent
 */
@Service
public class GroupMembershipService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final GroupRepository groupRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public GroupMembershipService(GroupRepository groupRepository,
                                  NamedParameterJdbcTemplate jdbc,
                                  ApplicationEventPublisher events) {
        this.groupRepository = groupRepository;
        this.jdbc = jdbc;
        this.events = events;
    }

    /**
     * Adds one direct Group membership. Duplicate requests are idempotent at the DB boundary.
     */
    @Transactional
    public void addMember(String groupId, String userId) {
        requireGroup(groupId);
        requireUser(userId);
        var inserted = jdbc.update("""
                INSERT INTO group_members (group_id, user_id, created_at)
                VALUES (:groupId, :userId, :createdAt)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("userId", userId)
                .addValue("createdAt", Timestamp.from(Instant.now())));
        if (inserted > 0) {
            events.publishEvent(new UserAddedToGroupEvent(groupId, userId, Instant.now()));
            log.atInfo()
                    .addKeyValue("groupId", groupId)
                    .addKeyValue("userId", userId)
                    .log("Group member added");
        }
    }

    /**
     * Removes one direct Group membership and leaves memberships in other Groups untouched.
     */
    @Transactional
    public void removeMember(String groupId, String userId) {
        var removed = jdbc.update("""
                DELETE FROM group_members
                WHERE group_id = :groupId
                  AND user_id = :userId
                """, Map.of("groupId", groupId, "userId", userId));
        if (removed > 0) {
            events.publishEvent(new UserRemovedFromGroupEvent(groupId, userId, Instant.now()));
            log.atInfo()
                    .addKeyValue("groupId", groupId)
                    .addKeyValue("userId", userId)
                    .log("Group member removed");
        }
    }

    private void requireGroup(String groupId) {
        if (!groupRepository.existsById(groupId)) {
            log.atWarn().addKeyValue("groupId", groupId).log("Rejected missing group membership target");
            throw new IllegalArgumentException("group_not_found: " + groupId);
        }
    }

    private void requireUser(String userId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM users
                WHERE id = :userId
                """, Map.of("userId", userId), Integer.class);
        if (count == null || count == 0) {
            log.atWarn().addKeyValue("userId", userId).log("Rejected missing group member user");
            throw new IllegalArgumentException("user_not_found: " + userId);
        }
    }
}
