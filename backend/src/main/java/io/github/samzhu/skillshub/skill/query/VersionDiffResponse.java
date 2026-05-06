package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;
import java.util.List;

/** S098c2 — GET /skills/{id}/diff?from=&to= 的結構化回應 DTO。 */
public record VersionDiffResponse(
        String skillId,
        VersionSnapshot from,
        VersionSnapshot to,
        List<DiffField> fields
) {

    /** 版本快照元資料（不含 frontmatter 完整 dump）。 */
    public record VersionSnapshot(
            String version,
            Instant publishedAt,
            long fileSize,
            int fileCount
    ) {}

    /**
     * 一個有變化的欄位 diff — V1 只含 name / description / riskLevel / allowedTools / fileSize / fileCount。
     * {@code fromValue} / {@code toValue} 為字串化值；null 表示欄位不存在於該版本。
     */
    public record DiffField(
            String field,
            String fromValue,
            String toValue,
            String changeType  // "added" | "removed" | "changed"
    ) {}
}
