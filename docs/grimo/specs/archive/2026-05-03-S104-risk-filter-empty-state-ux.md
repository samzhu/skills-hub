# S104 — Client-side risk filter empty state + pagination context fix

> **Status**: ✅ shipped `v3.4.4` (2026-05-03 — implement cron tick 7)
> **Type**: Frontend UX defensive fix (no backend change required)
> **Estimate**: XS (3 pts)
> **Triggered by**: 2026-05-03 cron Tick 6 Mode B E2E live browser walk-through — clicked「無風險」filter on `/browse`, 觀察到 mismatched UI state

## §1 Goal

`/browse` 風險過濾器（HomePage `RiskFilterSidebar`）是 **client-side filter**（per HomePage.tsx:92 explicit comment「保留 client-side risk-filter — 因 backend 沒有 multi-tier filter 支援」），但 UI 對「filter 啟用 + current page 0 hits」狀態處理不全：

**Live 觀察 (Chrome MCP cron Tick 6)**：
- 點擊「無風險」filter（DB 有 0 個 NONE-tier skills）
- EmptyState 顯示：「技能庫等著被開啟。第一個發布的人定下基調...」（seed-empty tone — 暗示整個 registry 是空的）
- Header 仍顯：「共 103 個技能」（unfiltered total — 與 EmptyState 自相矛盾）
- Pagination 仍顯：「第 1 / 6 頁」可點「下一頁」（暗示有更多頁，但每頁仍 0 hits）

**3 處 UI signal 互相矛盾**：「103 skills exist」 ⊕ 「empty state shown」 ⊕ 「6 pages of results」. User 認知負擔高且易誤判。

不修 backend filter 架構（S100b spec 已 deliberate decision；轉 server-side filter 會牽動 fetchSkills 簽名 + API contract test + cross-page sort consistency，超出 Mode B fix-spec scope）。**只修 frontend UX**：filter 啟用 + 當前 page 0 hits 時，幫 user 收斂 UI 狀態。

**Sibling 關係**：S100e (defensive guard) → S102 (routing residual) → S103 (UX copy hygiene) → **S104 (filter UX consistency)** — 第 4 個 cross-cutting follow-up，從 page-level data → cross-cutting links → user-visible string → interactive state consistency 的累積。

## §2 Findings — verified gaps

| # | File:line | 現狀 | 嚴重度 |
|---|-----------|------|--------|
| 1 | `frontend/src/pages/HomePage.tsx` (filter active + 0 hits 時 EmptyState section) | 顯 generic seed-empty tone「技能庫等著被開啟」 | Medium — 文案誤導：暗示整個 registry 空，實際只是 filter 0 hits |
| 2 | HomePage 計數區「共 N 個技能」 | 顯 `skillsPage.totalElements`（unfiltered 103） | Medium — filter 啟用時 user 期待看到 filtered count |
| 3 | HomePage pagination footer | 顯 `totalPages: 6` 可繼續翻 | Medium — 0 hits 時還能翻，跨頁仍 0 hits（client-side filter） |

**Excluded（不在 scope）**:
- Backend `/skills?riskLevel=` 不接受 filter param — per S100b deliberate decision，doc-side limitation；本 spec 不轉 server-side
- Pagination metadata reform — 同上
- 無風險 (NONE) tier DB 0 records — 是 S096c 的 deferred Flyway migration（runtime classify only）；不修

## §3 Approach

**Trim path**：本 spec 已 XS，無進一步 trim 空間；若 implement tick 觸 wall，trim 順序為 #2（count display）；保 #1 / #3（user-flow impact 較大）。

**Decision per gap**：

- **Gap #1 (EmptyState tone)** — 改用 **redirect tone** + context-aware copy：
  - Headline: `沒有「{selected risk labels}」的技能`
  - Sub: `目前沒有符合此風險篩選的技能。試試其他風險等級或清除篩選看全部 {totalElements} 個技能。`
  - Primary action: button「清除篩選」(call onClear)
  - 不再顯示「發布第一個技能」CTA（filter 0 hits 不該推 publish）

- **Gap #2 (count display)** — 計數區改 conditional：
  - filter active：「{filteredCount} 個技能（共 {totalElements}）」
  - no filter：「共 {totalElements} 個技能」（既有）

- **Gap #3 (pagination)** — filter active + filteredCount === 0 時 hide pagination footer（既有條件 render skip mat：`{filteredSkills.length > 0 && <Pagination />}`）

**不引入新 component**：所有改動 inline 在 HomePage.tsx 既有 conditional render 區。EmptyState component 已支援 redirect tone (S094c ship)，直接 reuse。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入 `/browse` 點「無風險」filter（DB 0 NONE-tier skills） | filter 啟用，current page 過濾後 0 hits | EmptyState headline 含「無風險」字面 + 顯「清除篩選」button |
| AC-2 | 同 AC-1 setup | 點「清除篩選」button | filter 清空，list 回到完整 103 skills |
| AC-3 | filter 啟用 + 0 hits | render 計數區 | 顯 `0 個技能（共 103）` 不再顯 unfiltered 「共 103」 |
| AC-4 | filter 啟用 + 0 hits | render pagination | pagination footer 隱藏（不可見「第 X / Y 頁」+「下一頁」按鈕）|
| AC-5 | filter 啟用 + 部分 hits（e.g.「高風險」filter，假設 page 1 有 5 高風險 skills） | render | EmptyState 不顯，5 hits 正常 list；pagination 仍顯（client-side filter 不知後續頁面數量，保留以利 user 繼續翻找） |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/HomePage.tsx` | 3 處 conditional：(a) EmptyState branch 加 filter-active sub-branch；(b) 計數區加 filter-active 變體；(c) Pagination 加 `&& filteredSkills.length > 0` guard | ~25 |

**測試新增 / 更新**：
- `frontend/src/pages/HomePage.test.tsx`（若不存在 → 新建）— AC-1 / AC-2 / AC-3 / AC-4
- 既有 RiskFilterSidebar test 不需動（component 內部行為未變）

## §6 Test plan

```bash
cd frontend
npm test -- --run HomePage
npm run build  # ensure no broken imports
# Smoke via Chrome MCP: /browse → click「無風險」→ 確認 3 個 UI state 一致
```

**Negative case**: filter 啟用但 0 hits 時 user 點「清除篩選」可正常 reset。
**Edge case**: 同時選多個 tier（e.g.「無風險」+「中風險」）filter 全部 0 hits 時，EmptyState headline 應列出全部 selected tier 名稱（not just 第一個）。

## §7 Result

**Shipped 2026-05-03 cron Tick 7 @ ~04:24**.

### Implement checklist

- [x] HomePage.tsx 3 處 conditional 改完（count display + EmptyState branch + pagination guard）+ TIER labels const
- [x] EmptyState.tsx RedirectTone 補 primaryAction render（filter-active 0-hits 場景需 escape hatch button；既有 EmptyStateProps interface 早已 declare 但 RedirectTone 未 wire — 是 missing-feature gap 不是 signature change）
- [x] HomePage.test.tsx 新建 — 4 ACs（baseline + AC-1/3 combined + AC-2 + AC-4）
- [x] `npm test --run HomePage` 全綠：4/4 PASS（1.29s）
- [x] Chrome MCP smoke 跑 /browse 點「無風險」filter，DOM 確認 4 signal 一致：
  - headline = `沒有「無風險」的技能` ✓
  - count = `0 個技能（共 103）` ✓
  - pagination = hidden ✓
  - 「清除篩選」button present ✓
- [x] CHANGELOG `v3.4.4` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 4（HomePage.tsx + HomePage.test.tsx 新建 + EmptyState.tsx primaryAction render gap fix + spec/CHANGELOG/roadmap docs）|
| LOC delta | +50 / -3（約 +95 含 test 新檔，-3 既有 inline render）|
| FE tests | 既有 32 → 36（+4 in new HomePage.test.tsx）|
| Backend touch | 0（純 frontend defensive UX fix）|
| Wall clock | ~12 min（IMPLEMENT 6 + tests 3 + Chrome smoke 1 + DOCUMENT 2）|

### Live render validation (Chrome MCP)

| Signal | Before (Tick 6 audit) | After (Tick 7 ship) |
|--------|----------------------|---------------------|
| EmptyState headline | `技能庫等著被開啟。` (seed tone, misleading) | `沒有「無風險」的技能` (redirect tone, context-aware) |
| EmptyState sub | `第一個發布的人定下基調...` (推 publish CTA) | `目前沒有符合此風險篩選的技能。試試其他風險等級或清除篩選看全部 103 個技能。` |
| EmptyState primary action | 「發布第一個技能」 → `/publish` (off-target) | 「清除篩選」 → onClick clear filter (on-target escape) |
| Count display | `共 103 個技能` (unfiltered) | `0 個技能（共 103）` (filtered + total context) |
| Pagination footer | `第 1 / 6 頁` 可點翻 (misleading) | hidden |

### Trim deferred

- **development-standards.md §UI** "interactive page filter / pagination / count / empty-state 4 signal 應 single source of truth" rule：spec §8 lesson；列為 polish backlog（doc-side scope；避免 ship commit creep per NEVER bundle drive-by refactors）
- **Edge case multi-tier select** (e.g. 同時選 NONE + MEDIUM)：headline 用 `[...riskFilter].map(...).join('、')` 已支援，但 test 沒補；polish backlog（核心 0-hits UX 已對齊，多 tier 是 secondary）

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → **S104 (interactive state consistency v3.4.4)** — 第 4 個 cross-cutting follow-up，cut 從「page-level data → cross-cutting links → user-visible strings → interactive state consistency」累積層次。本 ship 後 v3.4.x patch series 4 個全 land；S100 META post-ship cross-cutting audit 第 4 輪 follow-up complete。Mode B Round 9 (Chrome MCP click interaction) 是與前三輪互補的第 4 cut。

## §8 Lesson — interactive state consistency audit cut

S100 META page-by-page audit 對「同 page 上 N 個 UI signal 是否互相一致」是盲點 — page 對外是「正確 fetch + 正確 render」，但 page 內 filter / pagination / count / empty-state 4 個 signal **同時** 處理同一個 user action（click filter）時的對齊性，需要 interactive E2E 才看得見（static grep / DOM snapshot 看不到）。

S104 補的就是這個 cut：**page interactive state 一致性**。發現方式 = Chrome MCP click 互動 + 對比多個 UI signal。

**建議寫進 development-standards.md §UI**：interactive page filter / pagination / count / empty-state 4 signal 應 single source of truth — 改動 filter 須同步處理全 4 個 signal，不能讓 unfiltered backend metadata 與 client-side filtered list 並存於同一畫面。

This 也是 cron-loop Mode B Round 9 (interactive E2E) 的首個產出 — Round 8 的 read-only navigation 看 24 routes 都 OK，但「click 後 state 變化是否一致」是 Round 9 才補上的 cut。後續 Round 10+ 應持續探索 interactive flows（form submit / tab switch / sort change）。
