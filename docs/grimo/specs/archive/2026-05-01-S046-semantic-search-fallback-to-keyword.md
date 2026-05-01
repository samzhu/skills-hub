# S046: Semantic Search Fallback to Keyword

> Spec: S046 | Size: XS(5) | Status: ✅ Done — target ship `v2.23.0`
> Trigger: 2026-05-01 /loop tick 20 — Chrome E2E 在 HomePage 搜尋框輸入「DevOps」（既存 category）回 0 個結果，停在死巷「未找到匹配的技能 試試換個描述方式」；經查 frontend 走 semantic search mode（query 非空 → `useSemanticSearch`），但 semantic 回 0 不是 error → fallback 不觸發。`useSkillList` keyword search 同 query 後端 confirmed 25 個 result。Bug 影響 dev（embedding model disabled）+ prod（semantic 真找不到）兩場景。

---

## 1. Goal

`HomePage` 的 `isSemanticMode` 判斷加上「semantic 結果非空」條件 — semantic 回空（不論 error 或 success-but-empty）時自動 fallback 至 keyword search 模式：

```diff
- const isSemanticMode = query.trim().length > 0 && !semanticError
+ const isSemanticMode = query.trim().length > 0
+   && !semanticError
+   && (semanticResults?.length ?? 0) > 0
```

`useSkillList` 已在 query 非空時並行請求（既有 hook 有 own enabled guard）— 資料已 ready，純 render 切換。

---

## 2. Approach

### 2.1 Code diff

```diff
 // 語意搜尋模式：query 非空且語意搜尋未出錯時啟用；出錯時退回關鍵字搜尋
- const isSemanticMode = query.trim().length > 0 && !semanticError
+ // 語意搜尋模式：query 非空且 semantic 確實有 result 時啟用。
+ // semantic 回空（dev embedding 未配置 / prod 真 zero match / 任何 silent failure）也算 fallback 觸發條件。
+ const isSemanticMode = query.trim().length > 0
+   && !semanticError
+   && (semanticResults?.length ?? 0) > 0
```

### 2.2 為何 NOT 改 backend semantic 行為（如 disabled 時 503）

範圍守住 XS：
- 後端「embedding 未配置回 503」可在 future spec 處理
- 但 prod 環境 embedding 啟用、真找不到結果時 user 仍卡住 — 純 backend 改動不解這個場景
- 本 spec 走 frontend single source of truth：semantic 空 → keyword fallback；對 dev / prod 一致

### 2.3 為何 NOT 顯示「Tried semantic search, no result. Showing keyword results:」提示

過度設計：
- semantic / keyword 結果都是 skill list；user 看到「找到 X 個」+ 卡片即可
- 不對 user 揭示內部模式切換（user mental model 是「我搜了一個東西，看到結果」）
- 兩 mode 視覺差別僅 score badge（semantic mode 顯示）— 用 `isSemanticMode` 已正確切換
- future 可加「沒語意結果，已自動切到關鍵字」的 inline hint，但 MVP 不需要

### 2.4 為何 NOT 同時 trim placeholder 文字

`placeholder="搜尋技能名稱或描述..."` 不含 category — 屬另一個微 UI copy 修正（S043 後 keyword 已涵 category）。範圍維持 XS；留 placeholder 為下輪 follow-up。

---

## 3. SBE Acceptance Criteria

### AC-1: semantic 回 0 + keyword 有結果 → 顯示 keyword 結果

```gherkin
Given backend semantic search 對任何 query 回 [] (空陣列)
And   keyword "DevOps" 對應 25 個 skill
When  user 在 HomePage 搜尋框輸入「DevOps」
Then  顯示「共 25 個技能」+ 25 張卡片（keyword mode）
And   不顯示「未找到匹配的技能 試試換個描述方式」
```

### AC-2: semantic 回 0 + keyword 也 0 → 顯示「找到 0 個」+ 友善訊息

```gherkin
Given backend semantic + keyword 對 query "blahblah999" 都回 []
When  user 輸入「blahblah999」
Then  顯示「共 0 個技能」（keyword mode 0 結果，不是 semantic mode dead-end）
And   左側 CategorySidebar 仍顯示（keyword mode）
```

### AC-3: semantic 有結果 → 仍走 semantic mode（不 regress）

```gherkin
Given backend semantic 對 "deploy container" 回 5 個結果
When  user 輸入「deploy container」
Then  顯示「找到 5 個相關技能」（semantic mode；含 score badge）
And   隱藏 CategorySidebar
And   不顯示分頁
```

### AC-4: semantic 拋 error → 走 keyword fallback（既有行為不破）

```gherkin
Given backend semantic 拋 500
And   keyword 有結果
When  user 輸入「test」
Then  顯示 keyword 結果（既有 fallback 行為不破）
```

### AC-5: query 為空 → keyword/list 模式（既有預設行為不破）

```gherkin
When  user 進入 HomePage 不輸入 query
Then  顯示完整 CategorySidebar + 分頁 + 全部 PUBLISHED skills
```

### AC-6: 既有 frontend 測試不破

```gherkin
When  npm test
Then  既有 vitest 全綠
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/pages/HomePage.tsx`：`isSemanticMode` 判斷加 `(semanticResults?.length ?? 0) > 0`

### 5.2 Test
- 既有 frontend test 不破即可；E2E 由 Chrome 手測（4 個 AC）

### 5.3 Docs
- CHANGELOG `v2.23.0`
- spec-roadmap M42

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | HomePage isSemanticMode 加 length > 0 + Chrome E2E | AC-1~6 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.23.0`
>
> Verification: vitest 10 tests / 0 fail；Chrome E2E 在 LAB 環境（semantic 系統性回 0）三段驗 AC-1/AC-2/AC-5 全綠 — 「DevOps」回 25 keyword 卡片、「blahblah999」回 0 keyword empty state、清空 query 回 default browse 25。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 2 files / 10 tests passed |
| Chrome `keyword=DevOps` | 共 25 個技能 + sidebar + 25 cards（keyword mode）✓ AC-1 |
| Chrome `keyword=blahblah999nonexistent` | 共 0 個技能 + sidebar + 「找不到符合的技能 試試不同的關鍵字或分類」（keyword empty）✓ AC-2 |
| Chrome 清空 query | 預設瀏覽 25 + sidebar 不變 ✓ AC-5 |
| AC-3 (semantic 有結果走 semantic mode) | logic 不變 — `(semanticResults?.length ?? 0) > 0` 觸發 |
| AC-4 (semantic error → fallback) | logic 不變 — `!semanticError` 條件保留 |
| 既有 vitest | 10 tests / 0 fail ✓ AC-6 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/pages/HomePage.tsx`：`isSemanticMode` 判斷加 `&& (semanticResults?.length ?? 0) > 0`（line 43-46）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: semantic 0 + keyword 有 → 顯 keyword | ✅ PASS | Chrome E2E「DevOps」25 cards |
| AC-2: 兩個都 0 → 顯 keyword empty state | ✅ PASS | Chrome「blahblah999」 sidebar 留 + 0 cards 友善訊息 |
| AC-3: semantic 有結果走 semantic mode | ✅ PASS（logic）| `length > 0` 條件保留 semantic mode trigger |
| AC-4: semantic error 走 keyword fallback | ✅ PASS（logic）| `!semanticError` 條件保留 |
| AC-5: query 空走預設 browse | ✅ PASS | Chrome 清空 → 25 default + sidebar |
| AC-6: 既有 frontend 測試不破 | ✅ PASS | vitest 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 20 第一次 Chrome E2E HomePage 即觸發 — 任何文字 query 都死巷在「未找到匹配的技能 試試換個描述方式」；經查 backend semantic 系統性回 0（vector 內容跟 query 詞語意距離過大、similarity threshold 過濾掉所有；real prod cause 待查）。但無論 backend 為何回 0，frontend UX 都應 graceful。

**Fix design rationale**:
- Single source of truth — frontend 用「semantic 結果是否非空」一條件判 mode 切換；不依賴 backend 改 503 / disabled flag 等 contract
- 對 dev / prod 一致：prod 環境真找不到語意相關 skill 時 user 仍可自動看到 keyword 結果
- 視覺差別僅 score badge — 不對 user 揭示內部模式切換（user mental model 是「我搜了一個東西」）

### 7.5 Pending Verification / Tech Debt

- **placeholder 對齊**：`placeholder="搜尋技能名稱或描述..."` 未含 category（S043 後）— 微 UI copy 修正，留下一輪
- **semantic 系統性回 0 根因**：vector_store 38 rows + ACL 對得上 lab-user，但所有 query 命中 0 — 可能 similarity threshold 太嚴 / embedding API 沒實際 call / vector 與 query embedding model 不一致；屬獨立 backend bug，不影響本 fix（fix 對 prod 真 zero match 場景仍有意義）
- S031 §7.5 admin panel endpoint 仍待設計
