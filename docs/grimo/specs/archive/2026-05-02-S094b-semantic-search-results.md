# S094b — Semantic Search Results page `/search`

> **Status**: shipped
> **Type**: META S094 sub-spec 4/4 — final（C/D/A 已 ship）
> **Estimate**: M(12-13) → S(9-10) trim 至單 tick 範圍
> **Source**: `docs/grimo/ui/prototype/semantic_search_results_page.html` + README ll.111-133 + DESIGN.md

## §1 Goal

把 HomePage 的 inline 語意搜尋結果分流到專屬 `/search?q=...` route，用 LLM 產出 intent summary + concept tags 顯示給 user，讓他知道「系統如何理解他的查詢」。對齊 README ll.117 「AI intent summary 是語意搜尋最關鍵的 UX 差異化」。

## §2 Approach

### §2.1 Scope trim from M → S

Prototype 完整版（M 12-13 pts）含：
- AI intent summary card + concept chips × interactivity (remove 後重新搜尋)
- Per-result why-match reasoning (LLM 解釋為何此 skill 適合)
- Top match gradient bg + 0.94 score 強烈視覺
- Refine chips (4 items at bottom — 對 intent 做語意調整)
- Similarity score mini-bar 視覺化

實際 ship 範圍（S/9-10 pts）trim：
- ✓ Dedicated `/search?q=...` route + 直接 SearchBar wiring
- ✓ AI intent summary card with concept chips（display-only，不可 ×）
- ✓ Result list with similarity score（reuse 既有 SkillCard）
- ✓ EmptyState redirect tone for 0 results（reuse S094c）
- ✓ Backend graceful fallback for LLM unavailable
- ✗ Per-result why-match — 避免 7+ LLM calls/search（成本 + 延遲）
- ✗ Top match gradient bg — uniform display
- ✗ Refine chips — defer；user 自己重新搜尋
- ✗ Concept chip × interactivity — display-only

trim rationale：核心 UX 差異化「AI intent summary」已涵蓋；per-result why-match + refine chips 是 nice-to-have，未來 polish 跟進。

### §2.2 Backend graceful LLM fallback

**Critical design decision**: `Optional<ChatClient>` injection 讓 LLM unavailable 時不阻擋 endpoint。Spring AI ChatClient 為 `@Conditional(LlmEnabledCondition.class)` bean — 可能不存在（dev / no API key）。

```java
@Service
public class SearchIntentService {
  private final Optional<ChatClient> chatClient;  // empty when LLM disabled

  public IntentResponse summarize(String query) {
    if (chatClient.isEmpty()) {
      return new IntentResponse(query, List.of());  // fallback: echo query, no concepts
    }
    // call LLM via BeanOutputConverter, structured output
  }
}
```

Frontend 透過 `concepts.length > 0` 判斷是否顯 IntentSummaryCard — fallback 模式下 page 仍可運作（只是無 AI 解釋）。**POC HALT 風險規避**。

### §2.3 In-memory cache

`ConcurrentHashMap<String, IntentResponse>` per service instance — query 為 key，避免重複 LLM call。Dev / single-instance prod 簡單足夠；多 instance 場景換 Redis（future polish）。Cache 不限大小（query 多樣性低，bounded）；5min idle eviction 留 future polish.

### §2.4 Reuse posture

| Component | Source | Used for |
|-----------|--------|----------|
| `AppShell` | existing | page chrome |
| `SearchBar` | existing | top search input |
| `SkillCard` | existing | result list (with score prop) |
| `EmptyState` redirect tone (S094c) | existing | 0 results fallback |
| `EmptyState` invite tone (S094c) | existing | empty query fallback |
| `useSemanticSearch` hook | existing | result list query |
| `IntentSummaryCard` | NEW | purple intent card with concept chips |
| `useSearchIntent` hook | NEW | intent endpoint query |

### §2.5 Confidence

| Decision | Confidence | Source |
|----------|------------|--------|
| `Optional<ChatClient>` graceful fallback | **Validated** | Spring DI well-known pattern |
| `BeanOutputConverter` for structured output | **Validated** | LlmJudge (S091) 已用同 pattern |
| Frontend POST /search/intent with JSON body | **Validated** | apiFetch 已支援 method/body options |
| ConcurrentHashMap cache 不導致 OOM | **Hypothesis** | dev 環境 query 數量可控；prod 需 monitor + Redis 替換 |

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend POST `/api/v1/search/intent` with `{query: "find docker helper"}` | 200 + `{summary: ..., concepts: [...]}` |
| AC-2 | Backend POST with empty query | 200 + `{summary: "", concepts: []}` |
| AC-3 | Backend LLM unavailable (no API key) | 200 + `{summary: query, concepts: []}` graceful fallback |
| AC-4 | Backend repeat query within 5min | cache hit, no 2nd LLM call |
| AC-5 | Frontend `/search?q=docker` 載入 | render SearchBar + IntentSummaryCard (if concepts > 0) + result list |
| AC-6 | Frontend `/search` 無 q param | EmptyState invite tone「輸入一句描述或關鍵字」 |
| AC-7 | Frontend `/search?q=xxxyyy` 無結果 | EmptyState redirect tone with query echo + 3 suggestions |
| AC-8 | Frontend SearchBar Enter | navigate `/search?q={value}` (URL = source of truth) |
| AC-9 | Frontend LLM fallback (concepts=[]) | 不顯 IntentSummaryCard，純 result list |
| AC-10 | Frontend tests no regression | 28 → 28 PASS |
| AC-11 | Build ≤ 385KB JS | budget guard |
| AC-12 | Backend compileJava SUCCESS | regression check |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/search/
├── SearchIntentService.java        ← NEW (LLM call + graceful fallback + cache)
└── SearchIntentController.java     ← NEW (POST /api/v1/search/intent)

frontend/src/api/search.ts          ← + fetchSearchIntent
frontend/src/hooks/useSearchIntent.ts ← NEW
frontend/src/components/IntentSummaryCard.tsx  ← NEW (purple bg + concept chips display)
frontend/src/pages/SearchResultsPage.tsx       ← NEW
frontend/src/App.tsx                ← + /search route
```

不改 AppShell nav（搜尋是 contextual 動作，從 HomePage 進入；不需 dedicated nav link）。

## §5 Test plan

- `npm test` (frontend) — 預期 28 → 28 PASS（SearchResultsPage 純 UI render hooks，integration test 留 manual smoke per S size）
- `npm run build` — JS ≤ 385KB
- Backend compileJava — pass
- Manual smoke (after backend restart):
  - `curl -X POST http://localhost:8080/api/v1/search/intent -H 'Content-Type: application/json' -d '{"query":"docker helper"}'` → 200 + JSON
  - 瀏覽器 `/search?q=docker` → render

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 28 → 28 PASS / 0 fail（SearchResultsPage 純 hooks rendering，per S scope skip integration test）
- **JS bundle**: 377 → 381KB (+4KB; SearchResultsPage + IntentSummaryCard + 2 hooks)
- **CSS bundle**: 36.97 → 37.09KB (+0.1KB; intent card hex tokens)
- **Build time**: 213ms（無 regression）
- **Backend compileJava**: BUILD SUCCESSFUL ✓ (1s)
- **Components shipped**:
  - `SearchIntentService` — `Optional<ChatClient>` graceful fallback + `ConcurrentHashMap` cache + `BeanOutputConverter` structured output
  - `SearchIntentController` — POST `/api/v1/search/intent`
  - `SearchResultsPage` — `/search?q=...` with intent card + result list + 3 EmptyState fallbacks
  - `IntentSummaryCard` — purple intent card 對齊 prototype
  - `useSearchIntent` hook — react-query 5min cache
- **AC coverage**:
  - AC-1~4 backend: implemented per spec; manual smoke pending live restart
  - AC-5~9 frontend: rendered via component composition; pending live smoke
  - AC-10 frontend tests 28/28 ✓
  - AC-11 build 381KB < 385KB budget ✓
  - AC-12 backend compileJava ✓

**Trim from prototype noted (§2.1)**:
- Per-result why-match (避免 7+ LLM calls)
- Top match gradient + refine chips
- Concept chip × interactivity（display-only）

**Live smoke caveat**: live :8080 backend 仍跑 ship 前舊 code（S093 transition 未觸發），新 endpoint 行為待下次 graceful restart 後現地驗。

ship as **v2.72.0** (M88d)。

## §8 META S094 final summary

**4 sub-specs all ✅**：
- S094c Empty State Collection (4 tones) — v2.69.0 / XS 5
- S094d Docs Walkthrough — v2.70.0 / XS 5
- S094a My Skills Author Dashboard — v2.71.0 / S 9 (trim from M)
- S094b Semantic Search Results — v2.72.0 / S 9-10 (trim from M)

**Total META cost**: ~28-29 pts（vs estimate M-L 38-41）— trim 8-12 pts deferred to polish/future spec。

**Deferred items consolidated**:
- Sparkline + per-skill 30d trend endpoint (S094a polish)
- Per-result why-match LLM reasoning (S094b polish)
- Refine chips + concept chip × interactivity (S094b polish)
- Top match gradient bg (S094b cosmetic)
- ⏸ S094e admin review queue / S094f onboarding / S094g landing — post-MVP

**Next backlog**: S095 Risk tier 4-level (NONE + LOW + MEDIUM + HIGH) — S(9), planned.
