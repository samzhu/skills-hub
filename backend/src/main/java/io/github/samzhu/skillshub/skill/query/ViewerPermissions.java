package io.github.samzhu.skillshub.skill.query;

public record ViewerPermissions(
        boolean isOwner,
        boolean canView,
        boolean canDownload,
        boolean canEdit,
        boolean canDelete,
        boolean canShare,
        boolean canManageGrants
) {
}
