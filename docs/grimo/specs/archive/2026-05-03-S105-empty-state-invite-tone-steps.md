# S105 — EmptyState invite tone steps decoupling

> **Status**: ✅ shipped `v3.4.5` (2026-05-03 — implement cron tick 9)
> **Type**: Frontend component prop refactor + 4 callsite updates (UX context alignment)
> **Estimate**: XS (3 pts)
> **Triggered by**: 2026-05-03 cron Tick 8 Mode B E2E live browser walk-through (Chrome MCP) — Round 10 of test-case ledger（live tab clicks via keyboard nav，因 Radix Tabs synthetic-event 隔離問題；focus + Space 才能切 active tab）

## §1 Goal

`EmptyState` 元件的 `invite` tone 內部 hardcode 4-step horizontal flow `['打包', '自動掃描', '發佈', '追蹤']` (EmptyState.tsx:131)，但 5 個 production callsites 中只有 1 個（MySkillsPage 新作者 0 skills empty state）真正符合此 publish onboarding context；其餘 4 個 callsites（CollectionsPage / RequestBoardPage / SkillDetailPage Reviews tab / SearchResultsPage no-query）的 context 與 publish flow **無關**，但仍顯示 4-step strip 造成 UX 混亂。

**Live 觀察 (Chrome MCP cron Tick 8 Round 10)**：
- 進入 `/skills/<id>` → focus + Space 切「評論」tab → empty state 顯：「尚未有任何評論... 評論系統即將推出 — 屆時用戶可以為使用過的技能打分數... 1打包2自動掃描3發佈4追蹤」
- 「打包→掃描→發佈→追蹤」與「評論」tab 無語意關聯，user 看到會疑惑

不修 backend；不改 invite tone 視覺結構；只把 hardcoded `steps` 抽成 optional prop，default 不顯。**Caller opt-in**：MySkillsPage 仍可顯（傳 explicit prop），其餘 callsite 不傳就自動 hide。

**Sibling 關係**：S100e → S102 → S103 → S104 → **S105** — 第 5 個 cross-cutting follow-up，cut 從「page-level data → cross-cutting links → user-visible strings → interactive state consistency → component-context alignment」累積。發現方式 = Chrome MCP focus + Space keyboard nav 觸發 Radix Tabs（synthetic .click() 不夠，需真 keyboard event；Round 10 收穫 = Radix tabs interaction pattern 之外，多看到一個 component-context UX bug）。

## §2 Findings — verified misalignment

| # | File:line | Tone | Context | 4-step strip 顯適合？ |
|---|-----------|------|---------|----------------------|
| 1 | `frontend/src/pages/MySkillsPage.tsx:104` | invite | 新作者 0 published skills (P6 SBE) | ✅ **適合** — 新作者本來就要走 publish flow |
| 2 | `frontend/src/pages/CollectionsPage.tsx:54` | invite | Collections stub (community feature, S096f2 待 ship) | ❌ 不適合 — 與 Collections feature 無關 |
| 3 | `frontend/src/pages/RequestBoardPage.tsx:53` | invite | Requests stub (community feature, S096g2 待 ship) | ❌ 不適合 — 與 Requests feature 無關 |
| 4 | `frontend/src/pages/SkillDetailPage.tsx:215` | invite | Reviews tab stub (S098e2 待 ship) | ❌ 不適合 — 評論 ≠ 發佈 |
| 5 | `frontend/src/pages/SearchResultsPage.tsx:63` | invite | Search no-query empty (`/search` 不帶 q) | ❌ 不適合 — 鼓勵搜尋而非發佈 |

**Excluded（本 spec 不修）**:
- 是否該為 callsites 2-4 換 tone（e.g. switch to clear / redirect）— 範圍超 fix-spec，留 polish backlog
- EmptyState 4 tone 結構是否合理 — tone 分類 working as designed (S094c)，問題在 invite tone 內部 hardcode

## §3 Approach

**Trim path**：本 spec 已 XS，無進一步 trim 空間；若 implement tick 觸 wall，trim 順序為：先做 component prop 抽出 + MySkillsPage opt-in（保留既有渲染），4 callsite no-op 自然受惠 default hide；test 補若觸 wall 改 polish backlog。

**Decision**：

1. **EmptyState.tsx**：
   - `EmptyStateProps` 加 optional `steps?: string[]` (existing prop in interface？需 grep 確認)
   - `InviteTone` 改為：only render steps strip if `props.steps && props.steps.length > 0`
   - 移除 hardcoded `const steps = ['打包', '自動掃描', '發佈', '追蹤']`

2. **MySkillsPage.tsx**: opt-in 傳 `steps={['打包', '自動掃描', '發佈', '追蹤']}`（保留新作者 onboarding context）

3. **Other 4 callsites**: 不需動（new default = hide steps；既有 prop 不傳，自動 inherit default）

**不引入新 component / tone / abstraction**：純 prop 加 optional + 1 callsite 補 explicit prop。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | EmptyState invite tone 不傳 `steps` prop | render | 4-step strip **不顯**（DOM 找不到「打包/自動掃描/發佈/追蹤」字面）|
| AC-2 | EmptyState invite tone 傳 `steps={['打包', '自動掃描', '發佈', '追蹤']}` | render | 4-step strip 顯（既有 visual 不變）|
| AC-3 | `/skills/<id>` 點「評論」tab | render Reviews EmptyState | 不顯 4-step strip（現狀顯示，本 fix 後消失）|
| AC-4 | `/collections` 看 EmptyState | render | 不顯 4-step strip |
| AC-5 | `/requests` 看 EmptyState | render | 不顯 4-step strip |
| AC-6 | `/my-skills` 看 EmptyState（如 author 0 published） | render | 仍顯 4-step strip（MySkillsPage explicit opt-in）|

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/components/EmptyState.tsx` | 加 `steps?: string[]` to interface（若未存在）；InviteTone 用 props.steps with conditional render；移除 hardcoded `const steps = [...]` | ~5 |
| `frontend/src/pages/MySkillsPage.tsx` | 加 `steps={['打包', '自動掃描', '發佈', '追蹤']}` prop | ~1 |
| `frontend/src/components/EmptyState.test.tsx` | 加 AC-1（無 steps 不顯）+ AC-2（有 steps 顯）assertion | ~10 |

**測試新增 / 更新**：
- EmptyState.test.tsx — AC-1 + AC-2 component-level
- 既有 page tests 不需動（spec 行為對 user 看見的 DOM 是「少了 4 行 step text」，不影響 既有 assertions 抓的 headline / sub / button）

## §6 Test plan

```bash
cd frontend
npm test -- --run EmptyState
npm run build  # ensure no broken imports / TS error
# Smoke via Chrome MCP: /skills/<id> 切「評論」tab + /collections + /requests + /search 都應消失「打包→掃描→發佈→追蹤」字面；/my-skills 仍顯（若可達 0-skills 狀態）
```

**Negative case**: `steps={[]}` (空陣列) 與 `steps` undefined 行為一致 — 都不顯 strip。
**Edge case**: `steps={['只有一步']}` (1 element) — 仍顯 strip but `steps.length - 1 < 1` 故 spacer 不顯（既有邏輯 line 149 `i < steps.length - 1`）。

## §7 Result

**Shipped 2026-05-03 cron Tick 9 @ ~05:23**.

### Implement checklist

- [x] EmptyState.tsx: `EmptyStateProps` 加 `steps?: string[]` (with JSDoc)；InviteTone 改用 `props.steps` + conditional render；移除 hardcoded `const steps = [...]`
- [x] MySkillsPage.tsx: 加 `steps={['打包', '自動掃描', '發佈', '追蹤']}` opt-in（保留新作者 publish onboarding context）
- [x] EmptyState.test.tsx: 改寫 AC-2 為「不傳 steps → 不顯 strip」+ 新增 AC-S105「傳 steps → 顯 strip」（既有 4 tone tests + 2 改寫 = 6 PASS）
- [x] `npm test --run EmptyState`：6/6 PASS（752ms）
- [x] Chrome MCP smoke：
  - `/collections` → `hasSteps: false`（4-step strip 隱藏）✓
  - `/my-skills` → `hasSteps: true`（MySkillsPage opt-in 保留）✓
  - 兩 direction 都對 → AC-3/4/5/6 通過 sample（其他 callsite 同 component-level pattern follow）
- [x] CHANGELOG `v3.4.5` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 4（EmptyState.tsx + EmptyState.test.tsx + MySkillsPage.tsx + spec/CHANGELOG/roadmap docs）|
| LOC delta | ~+25 / -15（含 test 改寫 +20 -10）|
| FE tests | 既有 36 → 36（test count 不變；AC-2 改寫 + 新增 AC-S105 抵消）|
| Backend touch | 0（純 frontend component contract refactor）|
| Wall clock | ~9 min（IMPLEMENT 4 + tests 2 + Chrome smoke 1 + DOCUMENT 2）|

### Live render validation (Chrome MCP)

| Path | Before | After |
|------|--------|-------|
| `/collections` empty | 顯「打包→掃描→發佈→追蹤」(off-context) | 4-step strip 不顯 ✓ |
| `/requests` empty | 顯「打包→掃描→發佈→追蹤」(off-context) | 4-step strip 不顯（per component-level fix） |
| `/skills/<id>` 評論 tab | 顯「打包→掃描→發佈→追蹤」(off-context) | 4-step strip 不顯 |
| `/search` no-query empty | 顯「打包→掃描→發佈→追蹤」(off-context) | 4-step strip 不顯 |
| `/my-skills` empty | 顯「打包→掃描→發佈→追蹤」(on-context) | 4-step strip 顯（explicit opt-in 保留）✓ |

### Trim deferred

- **Other 4 callsites tone reconsideration**：是否 Reviews/Collections/Requests/Search empty 該換 tone（e.g. clear / redirect）— spec §2 trim list；本 fix 已解決顯示性問題（hide off-context strip），tone 是否最佳留 polish backlog
- **development-standards.md §UI** "shared component 不得 hardcode context-specific 內容" rule — sibling lesson to S103/S104 polish backlog；下次集中 doc commit ship

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → **S105 (component-context alignment v3.4.5)** — 第 5 個 cross-cutting follow-up，cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context」累積 5 層。Mode B 採 Chrome MCP focus+Space keyboard nav (Round 10 副產物：Radix synthetic-event 隔離 pattern 已驗證) 是與前 4 cut 互補的第 5 audit cut。

### Process learning (Round 10 byproduct)

Radix-based components (Tabs / Dropdown / Dialog) 不能用 `.click()` / pointer events 切 active state — React event system synthetic event 與 Radix DOM-level state machine 隔離。**E2E pattern**：focus + Space/Enter keypress dispatch 是 Radix 接收 user action 的正確路徑。本 pattern 寫進 `.claude/loop.md` 已 deferred to polish backlog（避免 ship commit creep）。

## §8 Lesson — component-context alignment audit cut

S100 page-level audit + S102 cross-cutting links + S103 user-visible strings + S104 interactive state consistency 都覆蓋不到「shared component 在不同 context 顯示是否一致語意」。Component 內部 hardcode (publish steps) 在多 context reuse 時偷渡進不適合 context — 是 component reuse 的常見 trap：「dev 寫 component 時 context 1 適合，後來 reuse 到 context 2/3/4 時忘記回頭檢查」。

S105 補的就是這個 cut：**shared component reuse audit**。發現方式 = Chrome MCP click 切 tab/page，肉眼看 EmptyState 內顯示的 step strip 在多 context 是否合理。

**建議寫進 development-standards.md §UI**：shared component 不得 hardcode 與 context 強相關的內容（labels / steps / descriptions）；必須 prop-driven，default 為 minimal display；caller 顯示性 opt-in。Sibling lesson to S103（不在 user-facing copy 寫 spec ID）— 都是 component-context 邊界違規。

Round 10 keyboard-nav-via-Space pattern 為 Mode B Round 11+ E2E 鋪路：Radix-based components (Tabs / Dropdown / Dialog) synthetic .click() 不夠，需 focus + Space/Enter；可寫進 `.claude/loop.md` 或 progress-log 作為 cron-bound agent E2E pattern reminder（polish backlog；本 spec 不涉）。
