package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 語意搜尋服務 — 接收自然語言查詢，透過 {@link SkillshubPgVectorStore} 找出語意相近的技能。
 *
 * <p>呼叫 {@code SkillshubPgVectorStore.builder(jdbc, em).build().similaritySearch(...)}
 * 並將回傳的 {@link Document} 清單映射至 {@link SemanticSearchResult}。
 *
 * <p><strong>Per-request VectorStore 模式（T8）</strong>：每次搜尋用 builder 建構新 instance，
 * 操作完即可被 GC；讀取場景不需 owner / skillId（builder 略過）。VectorStore 不註冊為
 * Spring Bean，與寫入路徑（{@link SearchProjection}）共用同一份 {@link SkillshubPgVectorStore}
 * 實作（一次 SQL 走 cosine distance；§4.14）。
 *
 * <p>此 service 不負責 embedding 計算（{@link SkillshubPgVectorStore#doSimilaritySearch}
 * 內部處理），也不負責 embedding 的寫入（由 {@link SearchProjection} 負責）。
 *
 * @see SkillshubPgVectorStore
 * @see SearchProjection
 * @see SearchConfig
 */
@Service
class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    /** 每次搜尋最多回傳的結果數 */
    private static final int TOP_K = 10;

    /**
     * Cosine similarity 最低門檻值（0–1）。
     * 低於此值的結果被視為與查詢無關，不回傳給前端。
     * 0.3 為實務上適合一般語意搜尋的寬鬆門檻。
     */
    private static final double SIMILARITY_THRESHOLD = 0.3;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    SemanticSearchService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 執行語意搜尋，回傳與查詢語意相關的技能清單（按 score 遞減排序）。
     *
     * @param query 使用者輸入的自然語言查詢
     * @return 語意相關的技能清單，若無符合結果則回傳空清單（不拋出例外）
     */
    List<SemanticSearchResult> search(String query) {
        log.info("Semantic search query={}", query);
        var request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();
        // Per-request：搜尋場景 owner / skillId 不需要，builder 預設跳過
        var results = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .build()
                .similaritySearch(request)
                .stream()
                .map(this::toResult)
                .toList();
        log.info("Semantic search query={} results={}", query, results.size());
        return results;
    }

    /**
     * 將 VectorStore Document 映射至 SemanticSearchResult。
     * metadata 中的欄位對應由 {@link SearchProjection} 寫入時設定的鍵名。
     */
    private SemanticSearchResult toResult(Document doc) {
        var meta = doc.getMetadata();
        return new SemanticSearchResult(
                getString(meta, "skillId"),
                getString(meta, "name"),
                getString(meta, "description"),
                getString(meta, "author"),
                getString(meta, "category"),
                (String) meta.get("latestVersion"),   // nullable — skill 可能尚未發布版本
                (String) meta.get("riskLevel"),        // nullable — 可能尚未完成風險評估
                toLong(meta.get("downloadCount")),
                // SkillshubPgVectorStore.DocumentRowMapper 設 score = 1 - cosine distance
                doc.getScore() != null ? doc.getScore() : 0.0
        );
    }

    /** 安全地從 metadata 取得字串值，null 或缺少的鍵回傳空字串。 */
    private static String getString(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v != null ? v.toString() : "";
    }

    /** 安全地將 metadata 值轉為 long，支援 Number 型別與字串格式。 */
    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
