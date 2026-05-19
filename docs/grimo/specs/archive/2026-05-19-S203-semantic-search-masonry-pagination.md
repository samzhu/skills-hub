# S203: Semantic Search Masonry Pagination

> 規格：S203 | 大小：S(11) | 狀態：✅ QA PASS（ready for `$shipping-release S203`）
> 日期：2026-05-19
> 對應：PRD P1 / P5；S090 semantic limit；S189 Browse search entry point；S193 score transparency；CONTEXT.md Infinite Semantic Results

---

## 1. 目標

`/browse` 輸入 `qa` 後不應只停在 10 筆；使用者往下捲到底時，要在同一個語意搜尋結果中自動載入下一頁，並以真正的 masonry 瀑布流排列卡片。

目前正式站證據：

```bash
curl 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=qa'
# -> 10 rows

curl 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=qa&limit=50'
# -> 15 rows
```

現有 `/browse` 有搜尋字串時只打 `GET /api/v1/search/semantic?q=...`（S189），而這條 API 目前回 array，預設 `limit=10`。S203 要把 semantic search 正式改成分頁檢索：

```text
使用者在 /browse 輸入 qa
  -> GET /api/v1/search/semantic?q=qa&page=0&size=10
  -> 畫面以 masonry 瀑布流顯示第 1 批結果
  -> 使用者捲到底部
  -> GET /api/v1/search/semantic?q=qa&page=1&size=10
  -> append 下一批結果，不清掉前面已看的卡片
```

不做：

- 不恢復 `/search` route 或 intent summary。
- 不把 semantic zero/error fallback 到 `/api/v1/skills?keyword=...`。
- 不在 UI 顯示 `totalElements` / `totalPages`。
- 不改 semantic score 算法、threshold、visibility / ACL SQL。
- 不重做空白 **Skill Browsing** 的 catalog pagination；空白瀏覽仍走 `/api/v1/skills?page=&size=`。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `CONTEXT.md` | 空白輸入是 **Skill Browsing**；非空輸入是 **Semantic Search**；新增 **Infinite Semantic Results** 表示同一 query 繼續載入更多結果。 | S203 只設計 semantic result 的分頁瀑布流；不能改成 keyword fallback 或 catalog mode。 |
| `frontend/src/pages/HomePage.tsx` | semantic mode 現在用 `useSemanticSearch(...)` 回 array，顯示 `找到 {length} 個相關技能`，並用兩欄 grid render `SkillCard`。 | 前端要改為 infinite query + masonry layout + bottom sentinel。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchController.java` | `GET /api/v1/search/semantic` 目前收 `q` 與 `limit`，default 10，cap 50，回 `List<SemanticSearchResult>`。 | 同一 endpoint 要改成 `q/page/size`，固定回 Spring Data `Slice<SemanticSearchResult>`；`limit` 不再是新 contract。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | SQL 先取 `topK * OVERSAMPLE_FACTOR`，再 `stream().limit(topK)`；score mapping 與 log 已由 S193 固定。 | 新分頁要在同一排名序列上做 offset/limit；不可打亂 score desc 排序。 |
| `docs/grimo/specs/archive/2026-05-02-S090-semantic-search-limit-param.md` | S090 只是讓 `limit` 可設定；當時目標是 show more，不是正式分頁。 | S203 取代 S090 的 `/search/semantic` client contract，文件與 tests 要改成 `page/size`。 |
| `docs/grimo/specs/archive/2026-05-16-S189-browse-search-entry-point-verify-ship.md` | `/browse` 有字只打 semantic API，不打 `/skills?keyword=`；semantic empty/error 不 fallback。 | S203 必須保留 S189 request routing。 |
| `docs/grimo/specs/archive/2026-05-17-S193-semantic-search-score-transparency.md` | semantic cards 顯示 `% 相符`；response 以 score desc 排序。 | masonry 卡片仍要顯示 `% 相符`；新增頁 append 後整體視覺仍要維持排名可讀性。 |
| [Spring Data Commons `Slice` API](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/Slice.html) / [Spring Data paging reference](https://docs.spring.io/spring-data/commons/docs/3.2.1/reference/html/) | `Slice` 表示有沒有下一批資料；reference table 說 `Slice<T>` 取 `pageSize + 1`，`Page<T>` 可能需要額外 `COUNT(...)` 查總數。 | S203 先 POC `Slice<SemanticSearchResult>`；不算總數，符合瀑布流。 |
| [TanStack Query v5 `useInfiniteQuery`](https://tanstack.com/query/v5/docs/react/reference/useInfiniteQuery) / [Infinite Queries guide](https://tanstack.com/query/v5/docs/framework/react/guides/infinite-queries) | `useInfiniteQuery` 提供 `fetchNextPage`、`hasNextPage`、`isFetchingNextPage`，`getNextPageParam` 回 `undefined/null` 表示沒有下一頁。 | 前端用 `useInfiniteQuery` 包 semantic API，捲到底時呼叫 `fetchNextPage`。 |
| [MDN CSS masonry layout](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_grid_layout/Masonry_layout) / [MDN multi-column breaks](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Multicol_layout/Handling_content_breaks) | CSS Grid masonry 仍需注意瀏覽器支援；Multi-column layout 可配 `break-inside: avoid` 防卡片被切開。 | 不直接依賴 experimental `grid-template-rows: masonry`；POC/設計要選穩定方案。 |

### 2.2 架構設計

#### API contract

S203 後 `GET /api/v1/search/semantic` 固定回分頁型結果，不再回 array。

```http
GET /api/v1/search/semantic?q=qa&page=0&size=10
```

回應採 Spring Data `Slice<SemanticSearchResult>`。Phase 1 POC 已用 Spring MVC slice test 驗出實際 JSON shape；前端只依這些欄位：

```json
{
  "content": [
    { "id": "skill-1", "name": "verifying-quality", "score": 0.64 }
  ],
  "first": true,
  "last": false,
  "number": 0,
  "size": 10,
  "numberOfElements": 10
}
```

前端判斷下一頁：

```ts
const nextPage = lastPage.last ? undefined : lastPage.number + 1
```

POC 已回答：

- Spring Boot 4 / Jackson 3 將 `SliceImpl<SemanticSearchResult>` serializes 成 top-level `content` / `first` / `last` / `number` / `numberOfElements` / `pageable` / `size` / `sort`。
- response 沒有 `totalElements` / `totalPages`，符合「UI 不顯示總數」。
- 前端可用 `lastPage.last ? undefined : lastPage.number + 1` 計算下一頁；不需要自訂 DTO 才能判斷下一頁。

#### Backend paging behavior

`SemanticSearchService` 新增 paged method：

```java
Slice<SemanticSearchResult> search(String query, Pageable pageable)
```

SQL 要抓 `pageSize + 1` 筆，用第 `pageSize + 1` 筆判斷 `hasNext`：

```text
requested = pageable.pageSize
offset = pageable.offset
sql limit = requested + 1

DB 回 11 筆
  -> content = 前 10 筆
  -> hasNext = true
```

排序仍由 DB 的 `ORDER BY distance` 決定；page 1 是同一個排序序列的下一段，不是前端重排。

#### Frontend infinite semantic flow

```text
/browse query = qa
  useInfiniteSemanticSearch({ q: "qa", size: 10 })
  pages.flatMap(page.content)
  render masonry columns
  bottom sentinel visible + hasNextPage
    -> fetchNextPage()
```

UI 文案：

| 狀態 | 文案 |
|---|---|
| 第一頁載入中 | `載入中...` |
| 已有結果 | `已載入 {loadedCount} 個相關技能` |
| 底部載入下一頁 | `載入更多相關技能...` |
| 沒有下一頁 | `已顯示全部相關技能` |
| zero result | 沿用 S189 empty state：「這個描述還沒有匹配的技能。」 |

#### Masonry layout

設計目標是真正 masonry，不是等高 grid。第一版採「穩定可測」優先：

- mobile：1 column。
- tablet：2 columns。
- desktop：3 columns。
- 每張卡片不可被切開。
- 卡片 DOM 順序仍是 API 排名順序；鍵盤 tab / screen reader 順序不應因視覺排版變亂。

實作候選：

1. CSS multi-column + `break-inside: avoid`。
2. JS column balancer：依 index 或量測高度分配到 columns。
3. experimental CSS Grid masonry。

S203 不引入外部 masonry dependency。POC / T02 要確認 CSS multi-column 是否滿足 rank readability；若不滿足，用 JS column balancer，但 DOM/keyboard order 風險要在 §7 記錄。

### 2.3 做法比較

| 做法 | 採用 | 實際行為 | 成本 / 風險 |
|---|---|---|---|
| A. `List + limit=50`，前端自己切頁 | no | 最多看到 50 筆，沒有真正下一頁。 | 無法解決資料超過 50 的檢索問題；total/hasNext 不可靠。 |
| B. `Page<SemanticSearchResult>` + 顯示總數 | no | UI 可顯示 `找到 37 個相關技能`。 | semantic distance + ACL + threshold 需要額外 count；使用者選擇不顯示總數。 |
| C. `Slice<SemanticSearchResult>` + masonry infinite scroll | yes | UI 顯示已載入數量；捲到底自動載入下一頁。 | 需 POC Spring `Slice` JSON shape；masonry 排序可讀性需驗證。 |
| D. 新增 `/semantic/page`，舊 `/semantic` 保持 array | no | 兩條 endpoint 共存。 | user 已確認 `/api/v1/search/semantic` 是搜尋專用，可以固定改成分頁；雙 contract 反而增加維護面。 |

採用 C。

### 2.4 Low-Fidelity UI Sketches

這不是 final pixels，不新增裝飾或新設計系統；只鎖定 layout、文案、載入狀態與互動 loop。

Desktop：

```text
/browse

探索 Agent 技能                                      [發布技能]
為團隊發現、評估與安裝可信任的 AI agent 技能

[ 搜尋 qa                                             x ]

已載入 10 個相關技能

┌────────────────────────┐  ┌────────────────────────┐  ┌────────────────────────┐
│ verifying-quality       │  │ defining-product        │  │ planning-spec           │
│ Sam Zhu       中風險     │  │ Sam Zhu       低風險     │  │ Sam Zhu       低風險     │
│ Independent QA review...│  │ Defines product...       │  │ Analyzes and designs... │
│ DEV  v1  ↓0  64% 相符   │  │ DEV  v1  ↓1  61% 相符   │  │ DEV  v1  ↓1  57% 相符   │
└────────────────────────┘  └────────────────────────┘  └────────────────────────┘
┌────────────────────────┐                              ┌────────────────────────┐
│ shipping-release        │                              │ skill-author            │
│ ... taller card ...      │                              │ ...                     │
└────────────────────────┘                              └────────────────────────┘

                      載入更多相關技能...
```

Mobile：

```text
[ 搜尋 qa                                       x ]

已載入 10 個相關技能

┌──────────────────────────────┐
│ verifying-quality             │
└──────────────────────────────┘
┌──────────────────────────────┐
│ defining-product              │
└──────────────────────────────┘

載入更多相關技能...
```

沒有下一頁：

```text
已載入 15 個相關技能
... cards ...
已顯示全部相關技能
```

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T00 | `SearchController`, `SemanticSearchService`, minimal test | Spring Data `Slice` / current semantic SQL | `page=0&size=2` 回 Slice JSON，`last=false`；`page=1` 回剩餘資料，`last=true` | `size=0` / `size=101` 走 pageable validation 或明確 400 | required |
| T01 | `SearchController`, `SemanticSearchService`, backend tests | S090 / S186 / S193 | `/api/v1/search/semantic?q=qa&page=0&size=10` 回 `Slice<SemanticSearchResult>`，排序仍 score desc | `limit` 不再是新 contract；unknown/legacy behavior 依 T00 POC 定案 | depends on POC |
| T02 | `frontend/src/api/search.ts`, `useSemanticSearch`, new `useInfiniteSemanticSearch` | TanStack Query v5 | query `qa` 會 fetch page 0，再用 `fetchNextPage` fetch page 1 | 空 query 不 fetch；快速輸入只送 debounce 後的新 query | not required |
| T03 | `HomePage.tsx`, new/modified masonry component | UI sketch / MDN masonry findings | semantic cards 以 masonry 顯示，底部 sentinel visible 時自動載入下一頁 | zero result 不顯載入更多；error 不 fallback keyword | depends on T02 |
| T04 | `HomePage.test.tsx`, `S140/S193` E2E, docs pages | S189/S193 evidence | E2E request log 看到 `page=0&size=10` 與下一頁 request；畫面顯示相符度與全部載入文案 | request log 不含 `/skills?keyword=`、不含 `limit=` | depends on T03 |

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd backend && ./gradlew test --tests '*SemanticSearch*'`
通過條件：S203 backend Slice / page-size / ordering tests 綠燈。

執行：`cd frontend && npm test -- HomePage useSemanticSearch`
通過條件：S203 frontend infinite semantic tests 綠燈。

執行：`cd e2e && npx playwright test --grep @S203`
通過條件：瀏覽器在 `/browse` semantic mode 捲到底會自動送下一頁 request，且不打 keyword fallback。

執行：`./scripts/verify-all.sh`
通過條件：V01/V03/V04/V05/V06/V07/V08a/V08b 全 PASS。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S203-1 | 必做 | POC/Test | Spring Data `Slice<SemanticSearchResult>` JSON shape 可被前端判斷下一頁 |
| AC-S203-2 | 必做 | Backend test | semantic API 使用 `page/size` 回下一頁 |
| AC-S203-3 | 必做 | Backend test | semantic 分頁維持 score desc 排序與 ACL filter |
| AC-S203-4 | 必做 | Frontend test | `/browse` semantic mode 捲到底自動載入下一頁並 append |
| AC-S203-5 | 必做 | Frontend test | masonry 瀑布流顯示 semantic cards 與相符度 |
| AC-S203-6 | 必做 | Frontend/E2E | semantic zero/error 不 fallback 到 keyword list |
| AC-S203-7 | 必做 | Docs/inspection | semantic API docs 改為 `q/page/size`，不再教前端使用 `limit` |
| AC-S203-8 | 必做 | Repo gate | `./scripts/verify-all.sh` exit=0 |

**AC-S203-1: Spring Data `Slice<SemanticSearchResult>` JSON shape 可被前端判斷下一頁**
- Given（前提）測試 DB 有 3 筆 public skills 的 embedding 都能命中 query `qa`
- When（動作）呼叫 `GET /api/v1/search/semantic?q=qa&page=0&size=2`
- Then（結果）HTTP 200 response 含 `content` 2 筆
- And（而且）response 含可判斷下一頁的欄位（例如 `last=false` 或等價欄位；實際欄位由 POC 寫回 §7）

**AC-S203-2: semantic API 使用 `page/size` 回下一頁**
- Given（前提）測試 DB 有 3 筆 public skills，排序後 id 依序是 `skill-a`, `skill-b`, `skill-c`
- When（動作）呼叫 `GET /api/v1/search/semantic?q=qa&page=1&size=2`
- Then（結果）HTTP 200 response 的 `content` 只含 `skill-c`
- And（而且）response 表示沒有下一頁

**AC-S203-3: semantic 分頁維持 score desc 排序與 ACL filter**
- Given（前提）DB 有 2 筆 public skill、1 筆 private skill；private skill 沒授權給匿名使用者
- When（動作）匿名呼叫 `GET /api/v1/search/semantic?q=qa&page=0&size=10`
- Then（結果）response 不含 private skill
- And（而且）所有 `content[].score` 由高到低排列

**AC-S203-4: `/browse` semantic mode 捲到底自動載入下一頁並 append**
- Given（前提）semantic API page 0 回 10 筆且還有下一頁，page 1 回 5 筆且沒有下一頁
- When（動作）使用者在 `/browse` 輸入 `qa`，接著捲到底部
- Then（結果）前端先送 `GET /api/v1/search/semantic?q=qa&page=0&size=10`
- And（而且）sentinel 進入 viewport 後送 `GET /api/v1/search/semantic?q=qa&page=1&size=10`
- And（而且）畫面最後顯示 15 張 skill card，不清掉前 10 張

**AC-S203-5: masonry 瀑布流顯示 semantic cards 與相符度**
- Given（前提）semantic API 回多筆 description 長短不同的 results，每筆都有 `score`
- When（動作）`/browse` semantic results render 完成
- Then（結果）desktop layout 顯示 3 欄 masonry，tablet 顯示 2 欄，mobile 顯示 1 欄
- And（而且）每張卡片仍顯示 `XX% 相符`
- And（而且）卡片不被切成上下兩段

**AC-S203-6: semantic zero/error 不 fallback 到 keyword list**
- Given（前提）`GET /api/v1/search/semantic?q=qa&page=0&size=10` 回 `content=[]` 或非 2xx
- When（動作）使用者搜尋 `qa`
- Then（結果）畫面留在 semantic mode，顯示 semantic empty/error 文案
- And（而且）沒有送出 `/api/v1/skills?keyword=qa`

**AC-S203-7: semantic API docs 改為 `q/page/size`，不再教前端使用 `limit`**
- Given（前提）S203 implementation 完成
- When（動作）檢查 docs quick reference 與 frontend API client
- Then（結果）`/api/v1/search/semantic` 文件列出 `q / page / size`
- And（而且）`frontend/src/api/search.ts` 不再組出 `limit=` query param

**AC-S203-8: repo gate 全綠**
- Given（前提）S203 implementation 完成
- When（動作）執行 `./scripts/verify-all.sh`
- Then（結果）所有 critical gates PASS，exit code 是 0

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S203-1, AC-S203-2 | 用 Slice 查 `size + 1` 判斷下一頁，不為 UI 不顯示的總數做 count query；`size>100` 不允許。 |
| Security | AC-S203-3, AC-S203-6 | 延續 S177/S186 的 `is_public OR acl_entries` filter；不可先分頁再用 Java 過濾。 |
| Reliability | AC-S203-2, AC-S203-4 | page 0/page 1 append 行為固定；error 不換資料源。 |
| Usability | AC-S203-4, AC-S203-5 | 使用者只要往下捲就看到更多相關技能；masonry 卡片仍顯示相符度。 |
| Maintainability | AC-S203-7 | Semantic search API 使用標準 `page/size`，移除前端 `limit` path，減少雙參數 contract。 |

## 4. 介面與 API 設計

### 4.1 Backend signatures

```java
@GetMapping("/semantic")
Slice<SemanticSearchResult> semanticSearch(
        @RequestParam String q,
        @PageableDefault(size = 10) Pageable pageable)
```

規則：

- `page` 0-based。
- `size` default 10。
- `size > 100` / `size <= 0` / `page < 0` 走 pageable validation 400。
- `sort` 不開放給 semantic endpoint；排序固定為 score desc（distance asc）。
- `limit` 不再是新 contract。若 POC 發現 UnknownQueryParamInterceptor 尚未套用 `/api/v1/search/**`，T01 要決定是讓 `limit` 被忽略、400，或只在 docs/frontend 移除；建議 400，避免舊 caller 以為仍生效。

Service：

```java
Slice<SemanticSearchResult> search(String query, Pageable pageable)
```

SQL draft：

```sql
SELECT id, name, description, author, category, category_display,
       latest_version, risk_level, download_count, embedding <=> ? AS distance
  FROM skills
 WHERE status = 'PUBLISHED'
   AND embedding IS NOT NULL
   AND (is_public = TRUE OR acl_entries ??| ?::text[])
   AND embedding <=> ? < ?
 ORDER BY distance
 OFFSET ?
 LIMIT ?
```

`LIMIT = pageable.pageSize + 1`。

### 4.2 Frontend API types

`frontend/src/types/skill.ts` 新增或調整：

```ts
export interface SpringSlice<T> {
  content: T[]
  number: number
  size: number
  numberOfElements: number
  first: boolean
  last: boolean
}
```

POC 實際結果是 Slice metadata 在 top-level，不包 `page`：

`frontend/src/api/search.ts`：

```ts
export interface SemanticSearchParams {
  q: string
  page?: number
  size?: number
}

export function fetchSemanticSearch(params: SemanticSearchParams): Promise<SpringSlice<SemanticSearchResult>>
```

`frontend/src/hooks/useSemanticSearch.ts` 可改名或新增：

```ts
export function useInfiniteSemanticSearch(query: string, size = 10)
```

### 4.3 Masonry component sketch

候選 component：

```tsx
function SemanticMasonryGrid({
  results,
}: {
  results: SemanticSearchResult[]
}) {
  return (
    <div data-testid="semantic-masonry-grid" className="semantic-masonry">
      {results.map((result) => (
        <div key={result.id} className="semantic-masonry-item">
          <SkillCard skill={result as unknown as Skill} score={result.score} />
        </div>
      ))}
    </div>
  )
}
```

CSS draft（POC/T03 可調整）：

```css
.semantic-masonry {
  column-count: 1;
  column-gap: 1rem;
}

@media (min-width: 640px) {
  .semantic-masonry { column-count: 2; }
}

@media (min-width: 1280px) {
  .semantic-masonry { column-count: 3; }
}

.semantic-masonry-item {
  break-inside: avoid;
  margin-bottom: 1rem;
}
```

POC/T03 必須驗證：卡片不被切開、desktop 欄數正確、keyboard/DOM 順序沒有因 masonry 方案變成不可接受。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `CONTEXT.md` | modify | 新增 Infinite Semantic Results 詞彙。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchController.java` | modify | `/semantic` 改收 `Pageable`，回 `Slice<SemanticSearchResult>`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | modify | 新增 `search(query, pageable)`，SQL 用 offset + size+1。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchFromSkillsTest.java` | modify/add | 覆蓋 Slice shape、page 0/page 1、排序、ACL。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchServiceVisibilityTest.java` | modify | 既有 `search(query, 10)` 測試改成 pageable 或保留 service overload。 |
| `frontend/src/types/skill.ts` | modify | 新增 `SpringSlice<T>` 或依 POC 實際 shape 更新。 |
| `frontend/src/api/search.ts` | modify | `fetchSemanticSearch` 改收 `q/page/size`，回 Slice；移除 `limit` 組參。 |
| `frontend/src/hooks/useSemanticSearch.ts` | modify | 改為 infinite semantic hook，或新增 hook 並更新 callers。 |
| `frontend/src/pages/HomePage.tsx` | modify | semantic mode 改用 pages flatMap；底部 sentinel 自動載入下一頁；文案改「已載入」。 |
| `frontend/src/components/SemanticMasonryGrid.tsx` | new/optional | 拆出 masonry semantic card grid。 |
| `frontend/src/index.css` | modify/optional | 加 semantic masonry CSS utilities。 |
| `frontend/src/pages/HomePage.test.tsx` | modify | 更新 S189/S193 assumptions，新增 S203 infinite scroll / masonry tests。 |
| `frontend/src/hooks/useSemanticSearch.test.tsx` | modify | 更新 hook query key / page behavior tests。 |
| `frontend/src/pages/docs/RestApiPage.tsx` | modify | `/api/v1/search/semantic` note 改 `q / page / size`。 |
| `frontend/src/pages/docs/SemanticSearchPage.tsx` | modify | 移除 `top-k default k` 舊說法，改 semantic page/size。 |
| `e2e/tests/S140-critical-path-browse-search.spec.ts` | modify | semantic response 從 array 改 Slice content，request assertion 包含 page/size。 |
| `e2e/tests/S140-critical-path-semantic-search.spec.ts` | modify | 同上。 |
| `e2e/tests/S193-semantic-search-score.spec.ts` | modify | score 從 `content[0].score` 取；保留相符度證據。 |
| `e2e/tests/S203-semantic-masonry-pagination.spec.ts` | new | 捲到底自動載入下一頁，確認不打 keyword fallback。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 S203 row。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task 規劃

POC: required — PASS（2026-05-19）

### POC Findings

執行：

```bash
cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.search.S203SliceSerializationPocTest' -x processTestAot -x compileAotTestJava -x processAotTestResources
```

結果：

```text
BUILD SUCCESSFUL in 6s
```

POC 暫時 test controller 回 `SliceImpl<SemanticSearchResult>`，Spring MVC 實際 response body：

```json
{
  "content": [
    {
      "id": "skill-a",
      "name": "verifying-quality",
      "description": "Independent QA review",
      "author": "u_s203",
      "authorDisplayName": "Sam Zhu",
      "authorHandle": null,
      "category": "dev",
      "categoryDisplay": "DEV",
      "latestVersion": "v1",
      "riskLevel": "MEDIUM",
      "downloadCount": 0,
      "score": 0.64
    }
  ],
  "empty": false,
  "first": true,
  "last": false,
  "number": 0,
  "numberOfElements": 1,
  "pageable": {
    "offset": 0,
    "pageNumber": 0,
    "pageSize": 2,
    "paged": true,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "unpaged": false
  },
  "size": 2,
  "sort": { "empty": true, "sorted": false, "unsorted": true }
}
```

結論：

- `SliceImpl` 可直接作為 controller response；不需要為了前端判斷下一頁先自訂 response DTO。
- 前端 type 要新增 `SpringSlice<T>`，metadata 使用 top-level `number` / `size` / `numberOfElements` / `first` / `last`。
- response 不含 `totalElements` / `totalPages`；這點符合 S203 不顯示總數的 UI 決策。
- Temporary POC test 已刪除；正式實作時要用 `SearchController` / `SemanticSearchService` production path 補永久測試。

### Task Plan

| 順序 | Task file | 覆蓋 AC | 狀態 | 驗證 |
|---|---|---|---|---|
| 1 | `docs/grimo/tasks/2026-05-19-S203-T01-backend-slice-api.md` | AC-S203-1, AC-S203-2, AC-S203-3 | PASS | `cd backend && ./gradlew test --tests '*SemanticSearch*' --tests '*SearchController*'` |
| 2 | `docs/grimo/tasks/2026-05-19-S203-T02-frontend-infinite-semantic-query.md` | AC-S203-4, AC-S203-6 | PASS | `cd frontend && npm test -- useSemanticSearch HomePage`; `cd frontend && npm run typecheck` |
| 3 | `docs/grimo/tasks/2026-05-19-S203-T03-masonry-results-and-docs.md` | AC-S203-5, AC-S203-7 | PASS | `cd frontend && npm test -- HomePage SemanticSearchPage RestApiPage`; `cd frontend && npm run typecheck` |
| 4 | `docs/grimo/tasks/2026-05-19-S203-T04-e2e-pagination-gate.md` | AC-S203-4, AC-S203-6, AC-S203-8 | PASS | `cd e2e && npx playwright test --grep @S203`；`./scripts/verify-all.sh` |
| 5 | `docs/grimo/tasks/2026-05-20-S203-T05-release-preflight-webserver-timeout.md` | AC-S203-8 | PASS | `cd e2e && npm run compose:down && npx playwright test --grep @happy-path` |

執行順序：

1. T01 先改 backend contract；沒有 `Slice` response，前端沒有穩定資料 shape 可接。
2. T02 接上 `useInfiniteQuery` 與 `/browse` append 行為，但先不把 layout 風險混進 API/hook task。
3. T03 做 masonry 瀑布流與文件同步；這一層不改 semantic API 行為。
4. T04 補 browser request evidence，並修既有 S140/S193 E2E 從 array response 改讀 `content`。

## 7. 實作結果

### 2026-05-20 — S203-T01 Backend Semantic Search Slice API

- `GET /api/v1/search/semantic?q=qa&page=0&size=10` 現在回 Spring Data `Slice<SemanticSearchResult>`，response 有 `content` / `number` / `size` / `numberOfElements` / `first` / `last`，沒有 `totalElements` / `totalPages`。
- `SemanticSearchService.search(query, Pageable)` 用 `OFFSET` + `LIMIT pageSize + 1` 查同一條 score 排序序列；第 `pageSize + 1` 筆只用來算 `hasNext`，`UserDisplayService.resolveAll(...)` 只吃實際回傳的 `content`。
- `/api/v1/search/**` 套上既有 unknown-param / pageable validation；`limit=50` 會回 400，`sort=name,asc` 也會回 400，semantic 排序固定由 SQL `ORDER BY distance` 決定。
- 驗證：`cd backend && ./gradlew test --tests '*SemanticSearch*' --tests '*SearchController*'` PASS（`BUILD SUCCESSFUL in 2m 19s`）。

### 2026-05-20 — S203-T02 Frontend Infinite Semantic Query

- `frontend/src/types/skill.ts` 新增 `SpringSlice<T>`；`SemanticSearchResult` 現在是 `/api/v1/search/semantic?q=...&page=...&size=...` 的 `content[]` item。
- `frontend/src/api/search.ts` 改成 `fetchSemanticSearch({ q, page, size })`，URL 只組 `q/page/size`，不再組 `limit=`。
- `frontend/src/hooks/useSemanticSearch.ts` 新增 `useInfiniteSemanticSearch(query, size = 10)`，用 TanStack Query v5 `data.pages`、`fetchNextPage`、`hasNextPage`、`isFetchingNextPage` 接 Spring Slice。
- `frontend/src/pages/HomePage.tsx` 在 semantic mode 用 `data.pages.flatMap((p) => p.content)` render cards；底部 sentinel 進 viewport 時載入下一頁，第二頁 append 到第一頁後面。
- S189 行為保留：有搜尋字時不送 `/api/v1/skills?keyword=...`；semantic zero/error 不 fallback 到 catalog keyword。
- 驗證：`cd frontend && npm test -- useSemanticSearch HomePage` PASS（2 files / 19 tests）；`cd frontend && npm run typecheck` PASS。

### 2026-05-20 — S203-T03 Masonry Results And Docs

- `frontend/src/components/SemanticMasonryGrid.tsx` 新增 semantic-only masonry component；`/browse` semantic results 使用 `columns-1 sm:columns-2 xl:columns-3`，item 使用 `break-inside-avoid`，card 仍顯示 API `score` 轉出的 `% 相符`。
- `frontend/src/pages/HomePage.tsx` semantic branch 改用 `SemanticMasonryGrid`；T02 的 Slice append / sentinel 文案保持不變。
- `frontend/src/pages/docs/RestApiPage.tsx` 與 `frontend/src/pages/docs/SemanticSearchPage.tsx` 同步新 contract：`q/page/size` + `Spring Slice content`，移除舊 `k/top-k/default k=20` 說法。
- 驗證：`cd frontend && npm test -- HomePage SemanticSearchPage RestApiPage` PASS（3 files / 19 tests）；`cd frontend && npm run typecheck` PASS。
- T04 已接續完成：E2E request log 看到 `page=0&size=10` 與下一頁 request；repo gate `./scripts/verify-all.sh` PASS。

### 2026-05-20 — S203-T04 E2E Pagination Gate

- `e2e/tests/S203-semantic-masonry-pagination.spec.ts` 新增 `@S203 @ac-4 @ac-6 @happy-path` browser flow：`/browse` 搜尋 `docker` 後，request log 看到 `/api/v1/search/semantic?q=docker&page=0&size=10`，捲到底部 sentinel 後看到 `page=1&size=10`，畫面顯示更多 card、`% 相符`、`已顯示全部相關技能`，且沒有 `/api/v1/skills?keyword=` 或 `limit=`。
- `e2e/fixtures/setup.fixtures.ts` paged profile 擴到 13 筆，讓 production-image E2E 可以產生第二頁 Slice；fixture 仍由 production upload API 建 aggregate data，semantic embedding 只透過 guarded projection seed 寫 disposable DB。
- `e2e/package.json` 的 `compose:webserver` 現在先建 `skillshub:e2e-local` 再啟 Compose，避免 Playwright 測到 stale image；這讓 S203 的 frontend static build 與 backend Slice API 都會進 production packaged image。
- S140 / S172 / S193 E2E 已改讀 `Slice.content`，並同步 `/browse` 文案為 `已載入 N 個相關技能`。
- 驗證：`cd e2e && npx playwright test --grep @S203` PASS（7 tests）；`cd e2e && npx playwright test --grep @happy-path` PASS（17 tests）；`./scripts/verify-all.sh` PASS（V01 PASS、V02 87.5%、V03 PASS、V04 PASS、V05 PASS、V06 PASS、V07 PASS、V08a PASS、V08b PASS；exit=0）。
- 下一步（已被後續 release preflight 覆寫）：所有 S203 task 與獨立 QA 已 PASS；ship 前仍須看本節後面的 release preflight 記錄。

### 2026-05-20 — Independent QA Review

Verdict: PASS — S203 的獨立 QA 層通過；ship 前仍須通過本 tick 的 release preflight。

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | PASS | `./scripts/verify-all.sh`：V01=PASS、V03=PASS、V04=PASS、V05=PASS、V06=PASS、V08a=PASS、V08b=PASS。 |
| Coverage / integration | PASS | V02 line coverage 87.5%（covered=4775 / total=5459）；V07 production packaged image browser path PASS。 |
| Manual verification | N/A | AC-S203-1..8 都有 automated command / test / docs assertion；不需要人工操作才能驗證。 |
| Testability gate | CLEAR | 每個 AC 都可從現有 backend/frontend/docs/E2E/verify-all evidence 對回。 |

AC coverage:

- AC-S203-1 / AC-S203-2：`backend/src/test/java/io/github/samzhu/skillshub/search/SearchControllerTest.java` 驗 `Slice` JSON shape、page 1、legacy `limit` 400。
- AC-S203-3：`backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchFromSkillsTest.java` 驗 ACL 先在 SQL filter 後分頁，以及跨頁 score desc。
- AC-S203-4 / AC-S203-6：`frontend/src/pages/HomePage.test.tsx` 與 `e2e/tests/S203-semantic-masonry-pagination.spec.ts` 驗 page 0/page 1 append、沒有 `/api/v1/skills?keyword=`、沒有 `limit=`。
- AC-S203-5：`frontend/src/pages/HomePage.test.tsx` 驗 `semantic-masonry-grid` / `semantic-masonry-item` 與 `% 相符`。
- AC-S203-7：`frontend/src/pages/docs/RestApiPage.test.tsx` 與 `frontend/src/pages/docs/SemanticSearchPage.test.tsx` 驗 semantic docs 顯示 `q / page / size`，不再教 `q / k`、`top-k`、`default k=20`。
- AC-S203-8：`./scripts/verify-all.sh` 本輪重跑 PASS；V01/V03/V04/V05/V06/V07/V08a/V08b 都過，exit=0。

QA code/document checks:

- `backend/config/application-secrets.properties` 由 `.gitignore` 排除；V07 log 只印 `value redacted`，沒有把 Gemini key 寫入 tracked file。
- S203 production code 沒新增 dependency；`e2e/package.json` 只改 `compose:webserver` 先 build `skillshub:e2e-local`，讓 browser tests 不會測 stale image。
- Release ledger 還沒收：spec 仍在 `docs/grimo/specs/`、`docs/grimo/tasks/2026-05-19-S203-*.md` 仍存在、CHANGELOG 尚無 v4.87.0、git tag `v4.87.0` 尚未建立。

### 2026-05-20 — Release Pre-flight Attempt

Verdict: FAIL — S203 還不能收 release ledger。

- `cd backend && ./gradlew test --tests '*SemanticSearch*' --tests '*SearchController*'` PASS（`BUILD SUCCESSFUL in 2m 24s`）。
- `./scripts/verify-all.sh` FAIL：V07 `cd e2e && npx playwright test --grep @happy-path` 在測試開始前等 `webServer` 逾時，錯誤是 `Error: Timed out waiting 240000ms from config.webServer.`。
- `e2e/results/report.json` 顯示 `expected=0`、`failedTests=[]`，代表沒有任何 browser assertion 實際跑到。
- `verify-all.log` 顯示 `npm run image:build` 先建出 `skillshub:e2e-local`，Gradle `bootBuildImage` 花 `3m 29s`；接著 `docker compose -f compose.e2e.yaml up -d --wait --wait-timeout 240` 才開始等 DB / mock OAuth / app health。Playwright `webServer.timeout` 仍是 `240_000`，時間不夠涵蓋 image build 加 Compose health check。

下一步：回 `$planning-tasks S203` 補一個 release-preflight 修復 task，讓 V07 啟動預算覆蓋 `image:build + compose:up`，修完後再跑 `$verifying-quality S203`。

### 2026-05-20 — S203-T05 Release Preflight WebServer Timeout

- `e2e/playwright.config.ts` 的 `webServer.timeout` 從 `240_000` 改成 `600_000`，並標註 S203-T05：`compose:webserver` 的等待時間包含 `image:build + compose:up`，不是只等 app boot。
- `compose:up --wait-timeout 240` 保持不變；上一輪失敗是 Playwright 包住整段 command 的 240 秒先到，Compose 的 health check timeout 還沒機會完整跑完。
- 驗證：`rg -n "timeout: 600_000|S203-T05" e2e/playwright.config.ts docs/grimo/tasks/2026-05-20-S203-T05-release-preflight-webserver-timeout.md` PASS。
- 驗證：`cd e2e && npm run compose:down && npx playwright test --grep @happy-path` PASS（17 tests）。這次輸出顯示 `bootBuildImage` 花 `3m 24s`，接著 Compose app/db/mock OAuth 皆 Healthy，browser tests 才開始執行並全部通過。

下一步：S203 release-preflight 修復已 PASS，但這是 implementation fix；依 dev loop 規則下一輪必須跑 `$verifying-quality S203`，不能直接 ship。

### 2026-05-20 — Independent QA Re-Review After S203-T05

Verdict: PASS — S203-T05 後重新跑完整 repo gate，全數通過；S203 可交給 `$shipping-release S203` 收 release ledger。

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | PASS | `./scripts/verify-all.sh` exit=0；V01=PASS、V03=PASS、V04=PASS、V05=PASS、V06=PASS、V08a=PASS、V08b=PASS。 |
| Coverage / integration | PASS | V02 line coverage 87.5%（covered=4775 / total=5459）；V07 `cd e2e && npx playwright test --grep @happy-path` PASS，確認 production packaged image browser path 在 `webServer.timeout=600_000` 後不再於測試開始前逾時。 |
| Manual verification | N/A | AC-S203-1..8 都有 backend/frontend/docs/E2E/verify-all evidence；不需要人工操作才能驗證。 |
| Testability gate | CLEAR | S203 每個 AC 都可對回既有 test 或 docs assertion；T05 只改 Playwright `webServer.timeout`，AC-S203-8 已由 V07 與完整 `verify-all` 覆蓋。 |

QA evidence:

- `./scripts/verify-all.sh` summary：`Results: V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS`；`Counts: PASS=8, FAIL=0, SKIP=0`；`Verdict: all CRITICAL passed; exit=0`。
- V07 log 仍只印 `loaded SKILLSHUB_E2E_GENAI_API_KEY ... (value redacted)`；沒有把 Gemini key 寫進 tracked file 或測試輸出。
- Release ledger 尚未收：spec 仍在 `docs/grimo/specs/`、`docs/grimo/tasks/2026-05-19-S203-*.md` 與 `docs/grimo/tasks/2026-05-20-S203-T05-*.md` 仍存在、CHANGELOG 尚無 v4.87.0、git tag `v4.87.0` 尚未建立。下一步必須是 `$shipping-release S203`。

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 2 | 2 | Spring `Slice` JSON shape 先用 POC 驗過；實作沒有新增 production dependency。 |
| Uncertainty | 2 | 2 | S203 主要不確定點是 Slice shape 與 production-image E2E 啟動時間；T05 已用完整 V07/verify-all 驗證。 |
| Dependencies | 2 | 2 | 依賴既有 Spring Data、TanStack Query、Playwright、Compose；沒有引入新第三方套件。 |
| Scope | 2 | 3 | 實際改到 backend API/service/tests、frontend hook/page/docs、E2E fixtures/specs/config，超過原 S 級單層改動。 |
| Testing | 2 | 3 | 同時覆蓋 backend slice/integration、frontend Vitest/typecheck、production packaged app V07、AOT/native V08。 |
| Reversibility | 1 | 2 | API contract 從 array/limit 改成 Slice/page/size，回復時需同步 frontend、docs、E2E fixture 與既有 request assumptions。 |
| **Total** | **11 / S** | **14 / M** | Bucket shift S→M；主因是 production-image E2E 與 release preflight timeout 修補納入同一 spec。 |
