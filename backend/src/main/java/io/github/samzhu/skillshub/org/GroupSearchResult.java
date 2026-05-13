package io.github.samzhu.skillshub.org;

import java.util.List;

public record GroupSearchResult(
        String id,
        String principalKey,
        GroupKind kind,
        String displayName,
        List<String> path,
        int memberCount) {
}
