package io.github.samzhu.skillshub.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.shared.security.testsupport.TestUserSeed;

/**
 * S170-T02 — principal context query tests for Group ancestors.
 */
@Import({
        GroupService.class,
        GroupIdGenerator.class,
        GroupMembershipService.class,
        PrincipalContextService.class
})
class PrincipalContextServiceTest extends RepositorySliceTestBase {

    @Autowired
    private GroupService groupService;

    @Autowired
    private GroupMembershipService membershipService;

    @Autowired
    private PrincipalContextService principalContextService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM group_members", Map.of());
        jdbc.update("DELETE FROM group_closure", Map.of());
        jdbc.update("DELETE FROM groups", Map.of());
        TestUserSeed.seedDefaults(jdbc);
        when(currentUserProvider.current())
                .thenReturn(CurrentUser.synthetic(TestUserSeed.BOB_ID, List.of("user"), List.of(), null));
        when(currentUserProvider.userId()).thenReturn(TestUserSeed.BOB_ID);
    }

    @Test
    @Tag("AC-5")
    @Tag("AC-6")
    @Tag("AC-14")
    @DisplayName("AC-5/AC-6/AC-14: current principals include direct groups and physical ancestors")
    void currentPrincipalKeys_returnsDirectGroupsAndAncestors() {
        var acmeId = groupService.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = groupService.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformTeamId = groupService.createGroup(cloudId, GroupKind.TEAM, "Platform Team");
        var aiEnablementId = groupService.createGroup(null, GroupKind.TEAM, "AI Enablement");
        membershipService.addMember(platformTeamId, TestUserSeed.BOB_ID);
        membershipService.addMember(aiEnablementId, TestUserSeed.BOB_ID);

        assertThat(principalContextService.currentPrincipalKeys())
                .containsExactlyInAnyOrder(
                        "user:" + TestUserSeed.BOB_ID,
                        "group:" + platformTeamId,
                        "group:" + cloudId,
                        "group:" + acmeId,
                        "group:" + aiEnablementId);

        membershipService.removeMember(aiEnablementId, TestUserSeed.BOB_ID);

        assertThat(principalContextService.currentPrincipalKeys())
                .containsExactlyInAnyOrder(
                        "user:" + TestUserSeed.BOB_ID,
                        "group:" + platformTeamId,
                        "group:" + cloudId,
                        "group:" + acmeId);
    }
}
