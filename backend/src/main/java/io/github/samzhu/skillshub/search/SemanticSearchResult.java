package io.github.samzhu.skillshub.search;

import org.jspecify.annotations.Nullable;

/**
 * 語意搜尋結果 DTO — 包含技能 metadata 與語意相似度分數。
 *
 * <p>由 {@link SemanticSearchService#search} 回傳，
 * 每筆記錄對應 {@code skills} 表中的一個技能 embedding。
 * score 為 cosine similarity（0.0–1.0），越高表示與查詢語意越相近。
 *
 * @param id            技能唯一識別碼（UUID）
 * @param name          技能名稱
 * @param description   技能功能描述
 * @param author        作者平台識別碼；保留給 API/filter/route fallback，不當人名顯示
 * @param authorDisplayName 作者 user-facing 顯示名；不可等於 raw {@code u_<id>}
 * @param authorHandle  作者 handle；可作為 route/install segment
 * @param category        技能分類 canonical lowercase（如 "devops"、"testing"；search/filter key）
 * @param categoryDisplay S159b Round 2 — 分類 display 名稱保留原 CamelCase（如 "DevOps"），nullable
 * @param latestVersion   最新發布版本（SemVer），尚未發布時為 {@code null}
 * @param riskLevel       安全評估等級（LOW/MEDIUM/HIGH），尚未評估時為 {@code null}
 * @param downloadCount   累計下載次數
 * @param score           與查詢的語意相似度（0.0–1.0）
 */
public record SemanticSearchResult(
        String id,
        String name,
        String description,
        String author,
        @Nullable String authorDisplayName,
        @Nullable String authorHandle,
        String category,
        String categoryDisplay,
        String latestVersion,
        String riskLevel,
        long downloadCount,
        double score
) {}
