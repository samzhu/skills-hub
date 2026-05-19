package io.github.samzhu.skillshub.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 語意搜尋 REST 控制器 — 提供自然語言技能搜尋端點。
 *
 * <p>端點：{@code GET /api/v1/search/semantic?q={query}&page={page}&size={size}}
 *
 * <p>使用 GET 方法（符合 REST 語意：讀取操作）。
 * 注意：architecture.md 原始版本記載為 POST，但 spec §4.1 以此 GET 設計為準（已修正）。
 *
 * @see SemanticSearchService
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private final SemanticSearchService searchService;

    SearchController(SemanticSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 以自然語言查詢技能，回傳語意相似度排序的結果清單。
     *
     * @param q 使用者輸入的自然語言查詢（例如「幫我部署 Docker 容器應用」）
     * @param pageable page/size 分頁參數；sort 不支援，排序固定由 pgvector distance 決定
     * @return 語意相關的技能切片（按 score 遞減）；無結果時回傳空切片（HTTP 200）
     */
    @GetMapping("/semantic")
    Slice<SemanticSearchResult> semanticSearch(
            @RequestParam String q,
            @PageableDefault(size = 10) Pageable pageable) {
        // S203-T01：semantic 排序只能由 SQL distance 決定，避免 client sort 打亂 score order。
        if (pageable.getSort().isSorted()) {
            throw new IllegalArgumentException("sort is not supported for semantic search");
        }
        return searchService.search(q, pageable);
    }
}
