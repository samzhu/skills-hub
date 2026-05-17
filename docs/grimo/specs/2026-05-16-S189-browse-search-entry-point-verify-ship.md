# S189: Browse 搜尋入口驗證與收版

> 規格：S189 | 大小：S(9) | 狀態：📐 in-design
> 日期：2026-05-16
> 對應：S178 superseded carry-over / S186 semantic search 同表化 / S187 Skill SKILL.md 編輯頁
> 執行前置：S186 先 ship；S187 接著做。S189 不阻塞 S186/S187，等主線完成後再用乾淨 spec lifecycle 承接搜尋入口整理。

---

## 1. 目標

[docs/grimo/specs/archive/2026-05-15-S178-browse-search-request-routing.md](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-15-S178-browse-search-request-routing.md:1) 已經寫到 §7，還記錄 T01-T05 與 `./scripts/verify-all.sh` PASS；[frontend/src/pages/HomePage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/HomePage.tsx:54) 也已經有 S178 的 `/browse` mode contract。S189 不重新設計搜尋入口；它在 S186/S187 完成後，重新驗證目前 `/browse` 的 Search Entry Point 行為，將 S178 evidence tag 遷移到 S189，並用乾淨工作樹完成 release。

S189 的產品行為仍是：

```text
/browse 搜尋框空白
  -> 只打 GET /api/v1/skills?page=...
  -> 顯示 catalog、分類、風險篩選、排序、分頁

/browse 搜尋框有字
  -> 只打 GET /api/v1/search/semantic?q=...
  -> 不打 GET /api/v1/skills?keyword=...
  -> 顯示 semantic result / semantic empty state

/search 或 /search?q=...
  -> 不再是產品入口，不 redirect 到 /browse
  -> 走現有 NotFoundPage

/api/v1/search/intent
  -> 跟 dedicated /search page 一起移除
```

設計邊界：

| 項目 | S189 決定 |
|---|---|
| S178 關係 | S178 已 superseded 並移到 archive；S189 不沿用 S178 task files。舊 §7 PASS 是歷史證據，S189 開工時要重新驗證當下程式碼與測試 tag。 |
| S186 關係 | S186 會改 semantic search 從 `skills.embedding` 查資料。S189 不改 semantic SQL，但會依賴 S186 ship 後的 API 行為做瀏覽頁請求驗證。 |
| S187 關係 | S187 是當前下一條主線。S189 是 ordering-only，排在 S187 後，避免搜尋 UI 與 edit page 同時改 frontend routing/tests。 |
| `/api/v1/skills?keyword=` | API 能力保留；S189 只規定 `/browse` 搜尋框不把它當 hidden fallback。 |
| `/browse` URL query | 不做 URL source-of-truth；搜尋字串是否同步到 URL 另開 spec。 |

## 2. 研究與設計

### 2.1 現況掃描

| 來源 | 查到什麼 | 對 S189 的影響 |
|---|---|---|
| [S178 archived spec](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-15-S178-browse-search-request-routing.md:13) | S178 已經定義 `/browse` 空白走 catalog、有字走 semantic、移除 `/search` 與 intent summary。 | S189 沿用產品目標，但重開 lifecycle，避免舊 task/result 跟新主線混在一起。 |
| [HomePage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/HomePage.tsx:54) | 目前程式已有 `trimmedQuery`、`debouncedQuery`、`isSemanticMode`、`useSkillList(..., { enabled: isCatalogMode })`。 | S189 task 一開始要先跑檢查；若程式已符合，工作重點是測試與 ship，而不是重寫。 |
| [App.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/App.tsx:36) | route table 沒有 `/search`；未知路徑走 `NotFoundPage`。 | S189 要保住這個行為，避免後續 route cleanup 又把 `/search` 加回來。 |
| Source scan | `rg "SearchResultsPage|IntentSummaryCard|useSearchIntent|/api/v1/search/intent" frontend/src backend/src/main/java backend/src/test/java` 目前沒有 match。 | intent summary 已不在 production/test source；S189 仍要把這個 scan 放入驗收，防止殘留功能回來。 |
| [S140 semantic E2E](/Users/samzhu/workspace/github-samzhu/skills-hub/e2e/tests/S140-critical-path-semantic-search.spec.ts:17) | E2E 已標 `@S178`，並從 `/browse` 搜尋框觸發 `GET /api/v1/search/semantic?q=`。 | S189 要更新 tag/說明，或新增 S189 對應 test，讓未來證據掛到新 spec。 |
| [TanStack Query v5 disabling queries](https://tanstack.com/query/v5/docs/framework/react/guides/disabling-queries) | `enabled` 可讓 query 在條件成立前不自動執行。 | `/browse` 有搜尋字串時，用 `enabled:false` 停掉 catalog query 是正確方向。 |
| [TanStack Query v5 paginated queries](https://tanstack.com/query/latest/docs/framework/react/guides/paginated-queries) | `placeholderData: keepPreviousData` 適合 page query key 變更時保留上一頁資料。 | 這個行為只留在 catalog mode；semantic mode 不沿用上一頁 catalog 結果。 |

### 2.2 做法比較

| 做法 | 採用 | 實際行為 | 成本 / 風險 |
|---|---|---|---|
| A. 讓 S178 繼續掛 Active | no | roadmap 仍顯示 S178 in-progress，S186/S187/S188 旁邊多一條已跑過但未 ship 的舊線。 | 最少修改，但 user 已指出會造成錯亂。 |
| B. 直接把 S178 標 shipped | no | 路線圖看起來乾淨，但 S178 沒走 `$shipping-release`，changelog/archive/release record 不完整。 | 會偽造 lifecycle；不符合 repo 的 spec ship 流程。 |
| C. S178 superseded，S189 做 carry-over verification & ship | yes | S178 進 archive 保留歷史；S189 在 Active 排到 S187 後，未來用新 spec id 重新驗證、遷移 evidence tag、完成 release。 | 需要搬文件與刪 task 檔，但最不會污染當前主線。 |

### 2.3 UI 草圖

Catalog mode：

```text
/browse

探索 Agent 技能                              [發布技能]
[ 描述你想完成的任務或搜尋技能...             ]

左側：風險篩選 / 分類
右側：共 103 個技能  [推薦][最新][風險低][下載最多]
      SkillCard grid
      [上一頁] 第 1 / 6 頁 [下一頁]
```

Semantic mode：

```text
/browse

探索 Agent 技能                              [發布技能]
[ dd                                      x ]

找到 2 個相關技能
SkillCard grid with score badges
```

Semantic zero-result：

```text
[ dd                                      x ]

這個描述還沒有匹配的技能。
現有技能與你描述的概念相似度都偏低。可以調整描述、清除搜尋並瀏覽分類，或邀請團隊發布。
[清除描述並瀏覽全部技能] [發布這個技能]
```

這不是新視覺設計；只鎖定使用者看到的 mode、文案與請求路徑。

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd frontend && npm test -- HomePage App SearchBar SemanticSearchPage`
通過條件：S189 相關 frontend 測試綠燈。

執行：`cd e2e && npx playwright test --grep @S189`
通過條件：browser request log 有 semantic endpoint，沒有 `/skills?keyword=`。

執行：`./scripts/verify-all.sh`
通過條件：V01/V03/V04/V05/V06/V07/V08a/V08b 全 PASS。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S189-1 | 必做 | Frontend test | `/browse` 初始載入只打 catalog API |
| AC-S189-2 | 必做 | Frontend test | 搜尋框有字只打 semantic API |
| AC-S189-3 | 必做 | Frontend test | 快速輸入只送最後一次 debounced query |
| AC-S189-4 | 必做 | Frontend test | semantic zero/error 不 fallback 到 keyword list |
| AC-S189-5 | 必做 | Frontend test | 清空搜尋回到未篩選 catalog |
| AC-S189-6 | 必做 | Frontend/inspection | `/search` route 與 intent summary 不存在 |
| AC-S189-7 | 必做 | Docs/frontend test | docs semantic CTA 指向 `/browse` |
| AC-S189-8 | 必做 | Playwright | browser E2E 驗證 `/browse` semantic-only request contract |
| AC-S189-9 | 必做 | Repo gate | `./scripts/verify-all.sh` exit=0 |

**AC-S189-1: `/browse` 初始載入只打 catalog API**
- Given（前提）使用者開啟 `/browse`，搜尋框是空白
- When（動作）頁面完成初始載入
- Then（結果）前端送出 `GET /api/v1/skills?page=0&size=20&sort=downloadCount%2Cdesc`
- And（而且）沒有送出 `/api/v1/search/semantic`
- And（而且）畫面顯示分類、風險篩選、排序與分頁

**AC-S189-2: 搜尋框有字只打 semantic API**
- Given（前提）使用者在 `/browse`
- When（動作）使用者輸入 `dd` 並等 debounce 完成
- Then（結果）前端送出 `GET /api/v1/search/semantic?q=dd`
- And（而且）沒有送出 `GET /api/v1/skills?keyword=dd`
- And（而且）畫面不顯示分類、風險篩選與分頁

**AC-S189-3: 快速輸入只送最後一次 debounced query**
- Given（前提）使用者在 `/browse`
- When（動作）使用者 300ms 內先輸入 `d` 再輸入 `dd`
- Then（結果）前端沒有送出 `GET /api/v1/search/semantic?q=d`
- And（而且）300ms 後只送一次 `GET /api/v1/search/semantic?q=dd`

**AC-S189-4: semantic zero/error 不 fallback 到 keyword list**
- Given（前提）`GET /api/v1/search/semantic?q=dd` 回傳 `[]` 或非 2xx
- When（動作）使用者搜尋 `dd`
- Then（結果）畫面留在 semantic mode，顯示 semantic empty/error 文案
- And（而且）沒有送出 `/api/v1/skills?keyword=dd`

**AC-S189-5: 清空搜尋回到未篩選 catalog**
- Given（前提）使用者曾選分類或風險篩選，接著輸入 `dd` 進 semantic mode
- When（動作）使用者清空搜尋框
- Then（結果）前端送出 `GET /api/v1/skills?page=0&size=20&sort=downloadCount%2Cdesc`
- And（而且）request 不帶 `keyword`、`category` 或隱藏 risk filter state

**AC-S189-6: `/search` route 與 intent summary 不存在**
- Given（前提）使用者直接開 `/search?q=dd`
- When（動作）React router resolve path
- Then（結果）畫面顯示現有 NotFoundPage，不 redirect 到 `/browse`
- And（而且）`rg "SearchResultsPage|IntentSummaryCard|useSearchIntent|fetchSearchIntent|SearchIntent|/api/v1/search/intent" frontend/src backend/src/main/java backend/src/test/java` 沒有 match

**AC-S189-7: docs semantic CTA 指向 `/browse`**
- Given（前提）使用者開啟 `/docs/semantic-search`
- When（動作）頁面顯示 semantic search trial CTA
- Then（結果）CTA label 是 `前往瀏覽頁試試語意搜尋 →`
- And（而且）link target 是 `/browse`

**AC-S189-8: browser E2E 驗證 `/browse` semantic-only request contract**
- Given（前提）Playwright 開啟 `/browse`
- When（動作）輸入 `images and containers in CI`
- Then（結果）browser request log 包含 `/api/v1/search/semantic?q=`
- And（而且）browser request log 不包含 `/api/v1/skills?keyword=`

**AC-S189-9: repo gate 全綠**
- Given（前提）S186 與 S187 主線已收斂，工作樹沒有不相關 release 變更
- When（動作）執行 `./scripts/verify-all.sh`
- Then（結果）V01/V03/V04/V05/V06/V07/V08a/V08b 全 PASS
- And（而且）exit code 是 0

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S189-3 | 快速輸入只送最後 debounced query，避免每個字都打 semantic endpoint。 |
| Security | AC-S189-2, AC-S189-8 | S189 不做前端安全過濾；讀取權限仍由 S186/S177 後端 semantic/list API 決定。 |
| Reliability | AC-S189-4, AC-S189-9 | semantic empty/error 不偷偷換資料源；最後用完整 repo gate 收斂。 |
| Usability | AC-S189-1, AC-S189-5, AC-S189-7 | 使用者只看到 catalog 或 semantic 其中一種 mode；清空搜尋回完整瀏覽。 |
| Maintainability | AC-S189-6 | 移除沒有產品入口的 `/search` 與 intent summary 支線，減少 route/API/doc/test 維護面。 |

## 4. 介面與 API 設計

### 4.1 Frontend mode contract

S189 task 不預設要改這段 code；先 inspect 當下實作，若仍符合以下 contract，task 只補測試 tag / docs / release evidence。

```ts
const trimmedQuery = query.trim()
const debouncedQuery = useDebouncedValue(trimmedQuery, 300)
const hasSearchInput = trimmedQuery.length > 0
const isSemanticMode = hasSearchInput
const isCatalogMode = !hasSearchInput

const semantic = useSemanticSearch(isSemanticMode ? debouncedQuery : '')
const list = useSkillList(
  { category: category ?? undefined, page, size: 20, sort: sortMode },
  { enabled: isCatalogMode },
)
```

實作規則：

- `isSemanticMode` 只能由 `query.trim().length > 0` 決定，不能由 `semanticResults.length > 0` 決定。
- `useSkillList` 在 semantic mode 必須 `enabled:false`。
- semantic mode 不傳 `keyword` 給 `/api/v1/skills`。
- clearing search 必須清掉 category / risk filter / page。

### 4.2 Route/API removal contract

```text
frontend route table:
  no /search route
  "*" -> NotFoundPage

backend:
  no POST /api/v1/search/intent
  no SearchIntentController
  no SearchIntentService
  no searchIntentChatClient bean
```

### 4.3 Test tag migration

S178 的 Playwright / frontend tests 若仍存在 tag，S189 implementation 要改成 `@S189` 或同時保留 `@S178 @S189` 一輪，最後 ship 前以 S189 為主證據。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `frontend/src/pages/HomePage.tsx` | inspect/modify | 確認 catalog/semantic mode contract；若已符合只補測試 tag/文件。 |
| `frontend/src/hooks/useDebouncedValue.ts` | inspect/modify | 保留 debounce hook；測試用 fake timers 驗快速輸入。 |
| `frontend/src/hooks/useSkillList.ts` | inspect/modify | `enabled` option 預設 true；semantic mode 停掉 list query。 |
| `frontend/src/App.tsx` | inspect/modify | `/search` 不存在；unknown route 仍走 `NotFoundPage`。 |
| `frontend/src/api/search.ts` | inspect/modify | 只保留 semantic search client；不恢復 intent summary API。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/*` | inspect/modify | 確認沒有 SearchIntent controller/service/native config。 |
| `frontend/src/pages/docs/SemanticSearchPage.tsx` | inspect/modify | CTA 指向 `/browse`。 |
| `frontend/src/pages/docs/RestApiPage.tsx` | inspect/modify | 不列 `/api/v1/search/intent`。 |
| `docs/grimo/architecture.md` | inspect/modify | search API 文件維持 `GET /api/v1/search/semantic?q=`；不提 intent summary。 |
| `e2e/tests/S140-critical-path-semantic-search.spec.ts` | modify | 將 S178 evidence tag 遷移到 S189；開 `/browse` 並檢查 request log。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | S178 superseded；S189 排在 S187 後。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
