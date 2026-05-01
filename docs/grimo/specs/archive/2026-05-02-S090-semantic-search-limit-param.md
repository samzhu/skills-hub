# S090 — Semantic search `?limit=` configurable

> **Status**: in-flight
> **Type**: polish (close tick 68 R25.7 missing-feature observation)
> **Estimate**: XS / 2 pts

## §1 Problem

`GET /api/v1/search/semantic?q={query}` only accepts `q`. `SemanticSearchService.TOP_K = 10` is hardcoded. Client `?limit=20` is silently dropped per Spring's unknown-param rule (tick 68 R25.7 observation).

For browse / pagination UX (e.g., 「show me more semantic matches」 button), a configurable limit is needed.

## §2 Approach

Add `@RequestParam(defaultValue = "10") int limit`，cap at 50 to prevent abuse；pass to service。Service replaces hardcoded TOP_K with parameter。Default 10 = pre-existing behavior（backward compatible）。

Cap 50 reasoning：
- Default 10：既有行為
- 50：足夠 「show more」 case；防止 client 提巨量值打爆 vector store

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `?q=docx` (no limit) | 回 ≤ 10 results (既有 default behavior) |
| AC-2 | `?q=docx&limit=3` | 回 ≤ 3 results |
| AC-3 | `?q=docx&limit=50` | 回 ≤ 50 results |
| AC-4 | `?q=docx&limit=999` | 自動 cap 到 50 |
| AC-5 | `?q=docx&limit=0` | 400 VALIDATION_ERROR（disallow 0 / negative） |
| AC-6 | `?q=docx&limit=-1` | 400 VALIDATION_ERROR |
| AC-7 | `?q=docx&limit=abc` | 400 VALIDATION_ERROR (Spring type conversion already handles) |

## §4 Implementation

`SearchController.semanticSearch`:
```java
@GetMapping("/semantic")
List<SemanticSearchResult> semanticSearch(
        @RequestParam String q,
        @RequestParam(defaultValue = "10") int limit) {
    if (limit < 1) {
        throw new IllegalArgumentException("limit must be >= 1 (got: " + limit + ")");
    }
    return searchService.search(q, Math.min(limit, MAX_LIMIT));
}
```

`SemanticSearchService.search` adds `int topK` parameter, replaces hardcoded `TOP_K`：
```java
List<SemanticSearchResult> search(String query, int topK) {
    var request = SearchRequest.builder().query(query).topK(topK)...
}
```

Default cap as `MAX_LIMIT = 50` constant in controller.

## §5 Test plan

- `npm test` (frontend) 不受影響
- backend test：既有 SemanticSearch tests 沿用 (default 10)
- smoke：5 curl probes 對應 7 個 AC

## §6 Verification

`./gradlew test` PASS + smoke 7/7 AC ✓.

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 regression）
- Smoke 7/7 AC PASS：
  - AC-1 default → 10 results ✓
  - AC-2 limit=3 → 3 results (docx/xlsx/pdf) ✓
  - AC-3 limit=50 → 50 results ✓
  - AC-4 limit=999 → cap 50 ✓
  - AC-5 limit=0 → 400「limit must be >= 1 (got: 0)」 ✓
  - AC-6 limit=-1 → 400「limit must be >= 1 (got: -1)」 ✓
  - AC-7 limit=abc → 400「For input string: "abc"」（Spring `MethodArgumentTypeMismatchException` 走 GlobalExceptionHandler 標準 shape）✓
- 對 R25.7 missing-feature observation 收尾完成
- ship v2.60.0 (M80)
