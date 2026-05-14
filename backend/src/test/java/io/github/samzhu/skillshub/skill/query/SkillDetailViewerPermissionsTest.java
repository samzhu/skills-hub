package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

@Import({SkillQueryService.class, ViewerPermissionService.class, JdbcSkillAclReadEvaluator.class})
class SkillDetailViewerPermissionsTest extends RepositorySliceTestBase {

    @Autowired
    private SkillQueryService queryService;

    @Autowired
    private SkillRepository skillRepo;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private PrincipalContextService principalContextService;

    @BeforeEach
    void clean() {
        skillRepo.deleteAll();
    }

    @Test
    @DisplayName("S169 AC-6: owner detail 回全 action 權限")
    void ownerGetsAllViewerPermissions() {
        var skillId = seedSkill("alice", "PUBLISHED",
                List.of("user:alice:read", "user:alice:write", "user:alice:delete"));
        when(currentUserProvider.userId()).thenReturn("alice");
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of("user:alice"));

        var permissions = queryService.findById(skillId).getViewerPermissions();

        assertThat(permissions.isOwner()).isTrue();
        assertThat(permissions.canView()).isTrue();
        assertThat(permissions.canDownload()).isTrue();
        assertThat(permissions.canEdit()).isTrue();
        assertThat(permissions.canDelete()).isTrue();
        assertThat(permissions.canShare()).isTrue();
        assertThat(permissions.canManageGrants()).isTrue();
    }

    @Test
    @DisplayName("S169 AC-7: editor 可編輯但不可刪除/分享/管理 grants")
    void editorCannotDeleteShareOrManageGrants() {
        var skillId = seedSkill("alice", "PUBLISHED",
                List.of("user:alice:read", "user:alice:write", "user:alice:delete",
                        "user:bob:read", "user:bob:write"));
        when(currentUserProvider.userId()).thenReturn("bob");
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of("user:bob"));

        var permissions = queryService.findById(skillId).getViewerPermissions();

        assertThat(permissions.isOwner()).isFalse();
        assertThat(permissions.canView()).isTrue();
        assertThat(permissions.canDownload()).isTrue();
        assertThat(permissions.canEdit()).isTrue();
        assertThat(permissions.canDelete()).isFalse();
        assertThat(permissions.canShare()).isFalse();
        assertThat(permissions.canManageGrants()).isFalse();
    }

    @Test
    @DisplayName("S169 AC-8: group viewer 只能檢視/下載")
    void groupViewerCanOnlyViewAndDownload() {
        var skillId = seedSkill("alice", "PUBLISHED",
                List.of("user:alice:read", "user:alice:write", "user:alice:delete",
                        "group:g_d4e5f6:read"));
        when(currentUserProvider.userId()).thenReturn("bob");
        when(principalContextService.currentPrincipalKeys())
                .thenReturn(Set.of("user:bob", "group:g_d4e5f6"));

        var permissions = queryService.findById(skillId).getViewerPermissions();

        assertThat(permissions.isOwner()).isFalse();
        assertThat(permissions.canView()).isTrue();
        assertThat(permissions.canDownload()).isTrue();
        assertThat(permissions.canEdit()).isFalse();
        assertThat(permissions.canDelete()).isFalse();
        assertThat(permissions.canShare()).isFalse();
        assertThat(permissions.canManageGrants()).isFalse();
    }

    private String seedSkill(String ownerId, String status, List<String> aclEntries) {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(skillId, "detail-permissions-" + skillId.substring(0, 8),
                "fixture", ownerId, "devops", "1.0.0", null, status, 0L, now, now,
                aclEntries, null));
        return skillId;
    }
}
