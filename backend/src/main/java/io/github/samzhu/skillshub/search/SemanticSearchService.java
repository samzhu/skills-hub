package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * 語意搜尋服務 — 接收自然語言查詢，透過 VectorStore 找出語意相近的技能。
 *
 * <p>呼叫 {@link VectorStore#similaritySearch} 並將回傳的 {@link Document} 清單映射至
 * {@link SemanticSearchResult}。VectorStore 後端由 {@link SearchConfig} 根據
 * {@code skillshub.search.vector-store} 屬性決定（SimpleVectorStore 或 FirestoreVectorStore）。
 *
 * <p>此 service 不負責 embedding 計算（由 VectorStore 實作內部處理），
 * 也不負責 embedding 的寫入（由 {@link SearchProjection} 負責）。
 *
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

    private final VectorStore vectorStore;

    SemanticSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
        var results = vectorStore.similaritySearch(request)
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
                // getScore() 在 SimpleVectorStore 總是有值；FirestoreVectorStore 也回傳 1.0-distance
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
