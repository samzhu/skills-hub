package io.github.samzhu.skillshub.skill.query;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

@Service
public class ViewerPermissionService {

    private final SkillAclReadEvaluator aclReadEvaluator;
    private final CurrentUserProvider currentUserProvider;

    public ViewerPermissionService(SkillAclReadEvaluator aclReadEvaluator,
                                   CurrentUserProvider currentUserProvider) {
        this.aclReadEvaluator = aclReadEvaluator;
        this.currentUserProvider = currentUserProvider;
    }

    public ViewerPermissions viewerPermissions(Skill skill) {
        var isOwner = currentUserProvider.userId().equals(skill.getOwnerId());
        var canView = aclReadEvaluator.canRead(skill.getId());
        var canEdit = aclReadEvaluator.canWrite(skill.getId());
        var canDelete = aclReadEvaluator.canDelete(skill.getId());
        return new ViewerPermissions(
                isOwner,
                canView,
                canView && skill.getStatus() != SkillStatus.SUSPENDED,
                canEdit,
                canDelete,
                isOwner,
                isOwner);
    }
}
