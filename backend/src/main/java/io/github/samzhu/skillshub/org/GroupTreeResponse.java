package io.github.samzhu.skillshub.org;

import java.util.List;

import org.jspecify.annotations.Nullable;

public record GroupTreeResponse(
        String id,
        @Nullable String parentId,
        GroupKind kind,
        String displayName,
        String principalKey,
        List<GroupTreeResponse> children) {
}
