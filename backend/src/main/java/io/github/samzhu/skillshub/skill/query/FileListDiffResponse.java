package io.github.samzhu.skillshub.skill.query;

import java.util.List;

/** S098c3 — 兩版本 zip 包中檔案列表的結構化差異。 */
public record FileListDiffResponse(
        String skillId,
        String fromVersion,
        String toVersion,
        int addedCount,
        int removedCount,
        int modifiedCount,
        int unchangedCount,
        List<FileDiffEntry> entries) {

    /** 單一檔案差異條目；unchanged 不輸出至 entries。 */
    public record FileDiffEntry(
            String path,
            String changeType,  // "added" | "removed" | "modified"
            Long fromSize,
            Long toSize) {}
}
