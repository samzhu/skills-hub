package io.github.samzhu.skillshub.search;

/**
 * 語意搜尋結果 DTO — 包含技能 metadata 與語意相似度分數。
 *
 * <p>由 {@link SemanticSearchService#search} 回傳，
 * 每筆記錄對應 VectorStore 中的一個技能 embedding document。
 * score 為 cosine similarity（0.0–1.0），越高表示與查詢語意越相近。
 *
 * @param id            技能唯一識別碼（UUID）
 * @param name          技能名稱
 * @param description   技能功能描述
 * @param author        作者名稱
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
        String category,
        String categoryDisplay,
        String latestVersion,
        String riskLevel,
        long downloadCount,
        double score
) {}
