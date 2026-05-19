# S203: Semantic Search Masonry Pagination

> 規格：S203 | 大小：S(11) | 狀態：📐 in-design
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

回應採 Spring Data `Slice<SemanticSearchResult>`。實際 JSON shape 由 POC 驗證後填入 §7；設計預期前端只依這些欄位：

```json
{
  "content": [
    { "id": "skill-1", "name": "verifying-quality", "score": 0.64 }
  ],
  "number": 0,
  "size": 10,
  "last": false,
  "first": true,
  "numberOfElements": 10
}
```

前端判斷下一頁：

```ts
const nextPage = lastPage.last ? undefined : lastPage.number + 1
```

POC 必須先回答：

- Spring Boot 4 / Jackson 3 實際 serializes `SliceImpl<SemanticSearchResult>` 的 JSON 欄位是什麼。
- `SliceImpl` 是否適合作為 controller response，不需要自訂 DTO。
- React Query `useInfiniteQuery` 能否穩定從這個 shape 算出下一頁。

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

若 POC 證明 Spring Boot 實際 JSON shape 包在 `page` 欄位（像目前 `SpringPage`），則以 POC 實際 shape 更新此 interface。

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

POC：required — 先證明 Spring Data `Slice<SemanticSearchResult>` 的 JSON shape 能被 frontend `useInfiniteQuery` 穩定使用，再正式改 `/api/v1/search/semantic` contract。

## 7. 實作結果

待 `/planning-tasks S203` 補。
