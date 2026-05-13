package io.github.samzhu.skillshub.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.testsupport.TestUserSeed;

/**
 * S170-T02 — direct Group membership command tests.
 */
@Import({GroupService.class, GroupIdGenerator.class, GroupMembershipService.class})
class GroupMembershipServiceTest extends RepositorySliceTestBase {

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService membershipService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM group_members", Map.of());
        jdbc.update("DELETE FROM group_closure", Map.of());
        jdbc.update("DELETE FROM groups", Map.of());
        TestUserSeed.seedDefaults(jdbc);
    }

    @Test
    @Tag("AC-3")
    @Tag("AC-4")
    @DisplayName("AC-3/AC-4: one user can belong to physical and root team groups")
    void addMember_allowsMultipleDirectGroups() {
        var acmeId = groupService.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = groupService.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformTeamId = groupService.createGroup(cloudId, GroupKind.TEAM, "Platform Team");
        var aiEnablementId = groupService.createGroup(null, GroupKind.TEAM, "AI Enablement");

        membershipService.addMember(platformTeamId, TestUserSeed.BOB_ID);
        membershipService.addMember(aiEnablementId, TestUserSeed.BOB_ID);

        assertThat(memberCount(platformTeamId, TestUserSeed.BOB_ID)).isEqualTo(1);
        assertThat(memberCount(aiEnablementId, TestUserSeed.BOB_ID)).isEqualTo(1);
    }

    @Test
    @Tag("AC-14")
    @DisplayName("AC-14: removing one direct membership keeps the other membership")
    void removeMember_deletesOnlySelectedGroupMembership() {
        var platformTeamId = groupService.createGroup(null, GroupKind.TEAM, "Platform Team");
        var aiEnablementId = groupService.createGroup(null, GroupKind.TEAM, "AI Enablement");
        membershipService.addMember(platformTeamId, TestUserSeed.BOB_ID);
        membershipService.addMember(aiEnablementId, TestUserSeed.BOB_ID);

        membershipService.removeMember(aiEnablementId, TestUserSeed.BOB_ID);

        assertThat(memberCount(platformTeamId, TestUserSeed.BOB_ID)).isEqualTo(1);
        assertThat(memberCount(aiEnablementId, TestUserSeed.BOB_ID)).isZero();
    }

    private Integer memberCount(String groupId, String userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM group_members
                WHERE group_id = :groupId
                  AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("userId", userId), Integer.class);
    }
}
