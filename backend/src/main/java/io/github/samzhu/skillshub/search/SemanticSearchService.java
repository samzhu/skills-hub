package io.github.samzhu.skillshub.search;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;

import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.shared.security.UserDisplayService;

/**
 * 語意搜尋服務 — 接收自然語言查詢，直接從 {@code skills.embedding} 找出語意相近的技能。
 *
 * <p>S186-T02: result card 欄位、visibility/ACL、cosine distance 都來自同一筆
 * {@code skills} row；不再查舊獨立向量表，也不再用 {@code skillRepo.findAllById}
 * 對結果做二次補資料。
 *
 * @see SearchProjection
 * @see SearchConfig
 */
@Service
class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    static final String SEMANTIC_SEARCH_SQL_FROM_SKILLS = """
            SELECT id, name, description, author, category, category_display,
                   latest_version, risk_level, download_count, embedding <=> ? AS distance
              FROM skills
             WHERE status = 'PUBLISHED'
               AND embedding IS NOT NULL
               AND (is_public = TRUE OR acl_entries ??| ?::text[])
               AND embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;
    private static final int OVERSAMPLE_FACTOR = 5;

    /**
     * Cosine similarity 最低門檻值（cosine 範圍 [-1, 1]）。
     * 低於此值的結果被視為與查詢無關，不回傳給前端。
     * 0.3 為實務上適合一般語意搜尋的寬鬆門檻（Gemini 真 embedding）。
     *
     * <p>S140 (e2e profile)：deterministic stub embedder cosine 範圍 ±0.1
     * （per POC poc/S140/StubEmbeddingPoc.java），需 override 為 -1.0 才能讓所有
     * skill 通過，allowing happy-path E2E 驗 deterministic ranking 而非 semantic 質量。
     * 預設值維持 0.3 fail-safe（production 行為不變）。
     */
    private final double similarityThreshold;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final PrincipalContextService principalContextService;
    private final UserDisplayService userDisplayService;

    SemanticSearchService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
            PrincipalContextService principalContextService, UserDisplayService userDisplayService,
            @Value("${skillshub.search.semantic-similarity-threshold:0.3}") double similarityThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.principalContextService = principalContextService;
        this.userDisplayService = userDisplayService;
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 執行語意搜尋，回傳與查詢語意相關的技能清單（按 score 遞減排序）。
     *
     * <p>S090: 新增 {@code topK} 參數讓 client 控制結果筆數；caller 應已 cap (e.g., MAX_LIMIT=50)。
     * 沒帶 topK 的 caller 走 {@link #search(String)} 兼容性 overload。
     *
     * @param query 使用者輸入的自然語言查詢
     * @param topK  最多回傳結果數（≥ 1；caller 自行 cap）
     * @return 語意相關的技能清單，若無符合結果則回傳空清單（不拋出例外）
     */
    List<SemanticSearchResult> search(String query, int topK) {
        // S186-T02：ACL filter 與 result card 都直接讀 skills row，避免舊索引 metadata stale。
        var principalKeys = principalContextService.currentPrincipalKeys();
        var aclPatterns = readPatterns(principalKeys);
        var queryEmbedding = new PGvector(embeddingModel.embed(query));

        var hits = jdbcTemplate.query(connection -> {
            var ps = connection.prepareStatement(SEMANTIC_SEARCH_SQL_FROM_SKILLS);
            bind(ps, 1, queryEmbedding);
            bind(ps, 2, pgArrayLiteral(aclPatterns));
            bind(ps, 3, queryEmbedding);
            bind(ps, 4, 1.0d - similarityThreshold);
            bind(ps, 5, topK * OVERSAMPLE_FACTOR);
            return ps;
        }, (rs, rowNum) -> new SkillSemanticHit(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("author"),
                rs.getString("category"),
                rs.getString("category_display"),
                rs.getString("latest_version"),
                rs.getString("risk_level"),
                rs.getLong("download_count"),
                rs.getDouble("distance")));

        var topHits = hits.stream().limit(topK).toList();
        var authorDisplays = userDisplayService.resolveAll(topHits.stream()
                .map(SkillSemanticHit::author)
                .toList(), false);

        var results = topHits.stream()
                .map(hit -> hit.toResult(authorDisplays.get(hit.author())))
                .toList();

        log.atInfo()
                .addKeyValue("query", query)
                .addKeyValue("principalCount", principalKeys.size())
                .addKeyValue("patternsCount", aclPatterns.size())
                .addKeyValue("resultsCount", results.size())
                .log("ACL-aware semantic search 完成");
        return results;
    }

    private static List<String> readPatterns(Set<String> principalKeys) {
        return principalKeys.stream().map(key -> key + ":read").toList();
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        StatementCreatorUtils.setParameterValue(ps, index, SqlTypeValue.TYPE_UNKNOWN, value);
    }

    private static String pgArrayLiteral(List<String> items) {
        return items.isEmpty() ? "{}" : "{" + String.join(",", items) + "}";
    }

}
