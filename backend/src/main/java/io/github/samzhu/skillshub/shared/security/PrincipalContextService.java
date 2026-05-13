package io.github.samzhu.skillshub.shared.security;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * S170 query service that builds ACL principal keys from the current platform user and Group tree.
 *
 * @see CurrentUserProvider
 */
@Service
public class PrincipalContextService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final CurrentUserProvider currentUserProvider;
    private final NamedParameterJdbcTemplate jdbc;

    public PrincipalContextService(CurrentUserProvider currentUserProvider,
                                   NamedParameterJdbcTemplate jdbc) {
        this.currentUserProvider = currentUserProvider;
        this.jdbc = jdbc;
    }

    /**
     * Returns {@code user:<id>} plus direct Group principals and all active ancestor Group principals.
     */
    public Set<String> currentPrincipalKeys() {
        return principalKeysForUser(currentUserProvider.userId());
    }

    /**
     * Builds principal keys for one platform user id; exposed for callers that already resolved user id.
     */
    public Set<String> principalKeysForUser(String userId) {
        var principals = new LinkedHashSet<String>();
        principals.add("user:" + userId);
        principals.addAll(groupPrincipalKeys(userId));
        log.atInfo()
                .addKeyValue("userId", userId)
                .addKeyValue("principalCount", principals.size())
                .log("Principal context built");
        return Set.copyOf(principals);
    }

    private List<String> groupPrincipalKeys(String userId) {
        // S170: closure table converts direct memberships into direct + ancestor group principals.
        return jdbc.queryForList("""
                SELECT DISTINCT 'group:' || c.ancestor_id
                FROM group_members m
                JOIN group_closure c ON c.descendant_id = m.group_id
                JOIN groups g ON g.id = c.ancestor_id
                WHERE m.user_id = :userId
                  AND g.status = 'ACTIVE'
                """, new MapSqlParameterSource().addValue("userId", userId), String.class);
    }
}
