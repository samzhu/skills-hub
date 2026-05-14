package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

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
    private final SkillRepository skillRepo;

    SemanticSearchService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
            PrincipalContextService principalContextService,
            SkillRepository skillRepo,
            @Value("${skillshub.search.semantic-similarity-threshold:0.3}") double similarityThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.principalContextService = principalContextService;
        this.skillRepo = skillRepo;
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
        // S169：S170 PrincipalContextService 從 platform user_id + group_members/group_closure
        // 建 principal keys；semantic search 只加 read verb 後交給 vector_store JSONB ACL filter。
        var principalKeys = principalContextService.currentPrincipalKeys();
        var aclPatterns = readPatterns(principalKeys);

        var request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();

        var docs = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .aclPatterns(aclPatterns)
                .build()
                .similaritySearch(request);

        // S107: source author/category/riskLevel 從 canonical Skill aggregate（vector_store
        // metadata 在歷史 row 含 stale empty 值 — onSkillCreated 早期 path 沒寫齊全；onVersionPublished
        // 還把 riskLevel 硬塞 null per line 147）。Per-result lookup 不額外 N+1：用 findAllById batch
        // 一次撈，topK ≤ 50 對 PK index 是 O(1) 級。
        var skillIds = docs.stream()
                .map(d -> getString(d.getMetadata(), "skillId"))
                .toList();
        var skillsById = skillRepo.findAllById(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, Function.identity()));

        var results = docs.stream()
                .map(doc -> toResult(doc, skillsById.get(getString(doc.getMetadata(), "skillId"))))
                .toList();

        log.atInfo()
                .addKeyValue("query", query)
                .addKeyValue("principalCount", principalKeys.size())
                .addKeyValue("patternsCount", aclPatterns.size())
                .addKeyValue("resultsCount", results.size())
                .log("ACL-aware semantic search 完成");
        return results;
    }

    private static List<String> readPatterns(java.util.Set<String> principalKeys) {
        var patterns = principalKeys.stream().map(key -> key + ":read").collect(Collectors.toCollection(java.util.ArrayList::new));
        patterns.add("public:*:read");
        return patterns;
    }

    /**
     * S107: 將 VectorStore Document + canonical Skill aggregate 映射至 SemanticSearchResult。
     *
     * <p>Skill 為 source of truth — author / category / riskLevel / latestVersion / downloadCount
     * 從 aggregate 取，不依賴 vector_store metadata（projection 寫入歷史不一致）。
     * 若 skill 已刪除（race condition：vector_store 仍在但 skill 被 delete），fallback 到 metadata
     * + empty defaults 維持 graceful，不 throw。
     *
     * @param doc   VectorStore Document（含 skillId / name / description / score）
     * @param skill canonical Skill aggregate；race condition 下可能為 null
     */
    private SemanticSearchResult toResult(Document doc, Skill skill) {
        var meta = doc.getMetadata();
        double score = doc.getScore() != null ? doc.getScore() : 0.0;
        if (skill == null) {
            // graceful fallback: skill row gone but vector_store row still exists
            return new SemanticSearchResult(
                    getString(meta, "skillId"),
                    getString(meta, "name"),
                    getString(meta, "description"),
                    "", "", null, null, null, 0L, score
            );
        }
        return new SemanticSearchResult(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getAuthor(),
                skill.getCategory(),
                skill.getCategoryDisplay(),  // S159b Round 2 — dual-write display value
                skill.getLatestVersion(),
                skill.getRiskLevel(),
                skill.getDownloadCount(),
                score
        );
    }

    /** 安全地從 metadata 取得字串值，null 或缺少的鍵回傳空字串。 */
    private static String getString(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v != null ? v.toString() : "";
    }

}
