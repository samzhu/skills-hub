# S103 — Stub-page user-facing copy spec ID leak (Collections / Requests)

> **Status**: ✅ shipped `v3.4.3` (2026-05-03 — implement cron tick 5)
> **Type**: Frontend UX copy polish (user-facing string sanitization)
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 cron Tick 4 Mode B E2E live browser walk-through (Chrome MCP) — Round 8 of test-case ledger（live DOM cut，前 7 rounds 是 component test cut）

## §1 Goal

`/collections` 與 `/requests` 兩個 stub 頁面（背後 backend feature 待 S096f2 / S096g2 ship）的「即將開放」狀態 copy 把 internal spec ID（`S096f2` / `S096g2`）露在 user-facing label / subtext。Production 用戶不應看到內部 milestone 編號 — 只應該看到 functional 描述（"後續版本將推出" 之類）。

NotificationsPage（同類 stub，背後待 S096h2）的 copy **沒有** spec ID 洩漏 — 證明此 leak 是 per-page sloppy copy 不是系統性 pattern；可以單點修不必抽 component。

不涉及任何後端改動、新 API、新 page；純粹 6 處 user-facing 字串替換。

**Sibling 關係**：S100e (Top 10 defensive) → S102 (routing residual) → **S103 (spec ID leak)** — 都是 Mode B audit 的 surface。S100e 是 page-level, S102 是 cross-cutting linking, S103 是 i18n / UX copy hygiene cut。

## §2 Findings — verified leaks

| # | File:line | 現狀 string | 嚴重度 |
|---|-----------|------------|--------|
| 1 | `frontend/src/pages/CollectionsPage.tsx:41` | `title="即將開放 — S096f2 完成 aggregate/install 後啟用"` | Low-Medium — 可見於 disabled button hover tooltip |
| 2 | `frontend/src/pages/CollectionsPage.tsx:45` | `建立集合（S096f2 功能即將開放）` | Medium — 可見於 disabled button label（end users 直接看到） |
| 3 | `frontend/src/pages/CollectionsPage.tsx:56` | EmptyState `sub="...S096f2 完成後..."` | Medium — 空狀態主敘述 |
| 4 | `frontend/src/pages/RequestBoardPage.tsx:41` | `title="即將開放 — S096g2 完成 voting/claim 後啟用"` | Low-Medium |
| 5 | `frontend/src/pages/RequestBoardPage.tsx:44` | `發起新需求（S096g2 功能即將開放）` | Medium |
| 6 | `frontend/src/pages/RequestBoardPage.tsx:55` | EmptyState `sub="當 S096g2 功能啟用後..."` | Medium |

**Excluded（comments / test fixtures，非 user-facing）**:
- `CollectionsPage.tsx:15` `* Defer S096f2: ...` — JSDoc comment for dev
- `RequestBoardPage.tsx:15` `* Defer S096g2: ...` — JSDoc comment for dev
- `NotificationsPage.tsx:15` `* Defer S096h2: ...` — JSDoc comment for dev
- `CollectionsPage.test.tsx:10` `* (S096f1 stub；S096f2 完成後啟用)。` — test file JSDoc

NotificationsPage **runtime copy 已 clean**（Chrome MCP 跑 /notifications 確認 0 spec ID 在 main render tree）— 不需動。

## §3 Approach

**Trim path**：本 spec 已 XS，無進一步 trim 空間；若 implement tick 觸 wall，trim 順序為 1/4（disabled button title attr，hover only 才見）；保 2/3/5/6（直接可見）。

**Replacement strategy** — keep "即將開放" + functional description, drop spec ID：

| # | 原 | 新 |
|---|----|----|
| 1 | `即將開放 — S096f2 完成 aggregate/install 後啟用` | `即將開放 — 集合建立功能後續版本推出` |
| 2 | `建立集合（S096f2 功能即將開放）` | `建立集合（即將開放）` |
| 3 | `集合（Collection）讓你把多個技能一次安裝。S096f2 完成後可從這裡建立 / 瀏覽 / 一鍵安裝。` | `集合（Collection）讓你把多個技能一次安裝。後續版本推出後可從這裡建立 / 瀏覽 / 一鍵安裝。` |
| 4 | `即將開放 — S096g2 完成 voting/claim 後啟用` | `即將開放 — 投票與認領功能後續版本推出` |
| 5 | `發起新需求（S096g2 功能即將開放）` | `發起新需求（即將開放）` |
| 6 | `當 S096g2 功能啟用後，可以從這裡發起「我希望某種 skill 存在」的需求；社群投票推升優先級，作者認領後實作。` | `後續版本推出後，可以從這裡發起「我希望某種 skill 存在」的需求；社群投票推升優先級，作者認領後實作。` |

**不引入 i18n 抽象** — 6 個字串直接 inline 替換，無 third caller 出現前不抽 i18n constants（per NEVER add abstraction for hypothetical second caller）。

**Decision: 用「後續版本推出」泛詞** — 不指定確切 milestone（避免下次 milestone 重組又要改一次）；user-facing 只承諾「未來會做」。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入 `/collections` 看 disabled button | hover 顯 tooltip / 視覺確認 button label | 完整 string 不含「S096f2」字面 |
| AC-2 | 進入 `/collections` 看 EmptyState subtext | render | 完整 string 不含「S096f2」字面 |
| AC-3 | 進入 `/requests` 看 disabled button | hover / 視覺 | 完整 string 不含「S096g2」字面 |
| AC-4 | 進入 `/requests` 看 EmptyState subtext | render | 完整 string 不含「S096g2」字面 |
| AC-5 | grep `S096[fgh]2` frontend/src/pages/*.tsx | 排除 JSDoc comments (line 15) | 0 hits in JSX render tree（user-visible content） |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/CollectionsPage.tsx` | 3 string replacements (lines 41 / 45 / 56) | ~3 |
| `frontend/src/pages/RequestBoardPage.tsx` | 3 string replacements (lines 41 / 44 / 55) | ~3 |

**測試新增 / 更新**：
- `CollectionsPage.test.tsx` 既有 — 加 AC-1/2 string assertion（`expect(screen.queryByText(/S096f2/)).not.toBeInTheDocument()`）
- `RequestBoardPage.test.tsx` 若不存在 → 新建（AC-3/4 同 pattern）

## §6 Test plan

```bash
cd frontend
npm test -- --run CollectionsPage RequestBoardPage
npm run build  # ensure no broken imports
# Smoke: 開 /collections + /requests 用 Chrome MCP 確認 grep 不到 S096?2 字面
```

**Negative case**: AC-5 grep 全 frontend/src/pages 應 0 user-visible hits（comments only allowed）。
**Edge case**: 不影響 NotificationsPage（已 clean，不該有 collateral 改動）。

## §7 Result

**Shipped 2026-05-03 cron Tick 5 @ ~03:23**.

### Implement checklist

- [x] 6 處 string replacement 完成（CollectionsPage 3 + RequestBoardPage 3）
- [x] CollectionsPage.test.tsx 補 `AC-S103` assertion（visible text + button title attr 不含 `S096f2`）
- [x] RequestBoardPage.test.tsx 新建（AC-3 button label/title + AC-4 EmptyState subtext 不含 `S096g2`）
- [x] `npm test` 全綠：CollectionsPage 4 PASS（既有 3 + 新加 1）+ RequestBoardPage 2 PASS = 6/6（857ms）
- [x] AC-5 grep verify：`grep -nE 'S096[fgh]2' frontend/src/pages/*.tsx | grep -vE '^\S+:[0-9]+:\s*\*'` → 0 hits（只剩 JSDoc comment lines，allowed）
- [x] Smoke via Chrome MCP：navigate /collections + /requests → DOM accessibility tree 確認 spec ID 字面 0 出現
- [x] CHANGELOG `v3.4.3` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 4（2 page tsx + 1 test update + 1 new test）|
| LOC delta | +30 / -8（+27 是 RequestBoardPage.test.tsx 整檔新建）|
| FE tests | 既有 30 → 32（+1 CollectionsPage AC-S103 + 2 RequestBoardPage = +3 tests in 2 files；既有 3 + 1 + 2 = 6 PASS in scope）|
| Backend touch | 0（純 frontend copy fix）|
| Wall clock | ~10 min（PLAN N/A spec 已寫；IMPLEMENT 4 + tests 3 + verify 2 + Chrome smoke 1）|

### Live render validation (Chrome MCP)

| Path | Before | After |
|------|--------|-------|
| `/collections` button label | `建立集合（S096f2 功能即將開放）` | `建立集合（即將開放）` |
| `/collections` button title attr | `即將開放 — S096f2 完成 aggregate/install 後啟用` | `即將開放 — 集合建立功能後續版本推出` |
| `/collections` EmptyState sub | `...S096f2 完成後可從這裡建立...` | `...後續版本推出後可從這裡建立...` |
| `/requests` button label | `發起新需求（S096g2 功能即將開放）` | `發起新需求（即將開放）` |
| `/requests` button title attr | `即將開放 — S096g2 完成 voting/claim 後啟用` | `即將開放 — 投票與認領功能後續版本推出` |
| `/requests` EmptyState sub | `當 S096g2 功能啟用後...` | `後續版本推出後...` |

### Trim deferred

- **development-standards.md §UI copy convention 增「stub copy 不含 internal spec ID」rule**：spec §8 lesson 提的 doc-side polish；列為 polish backlog（避免本 ship doc-side scope creep，per NEVER bundle drive-by refactors）

### Sibling validation

S100e (defensive guard) → S102 (routing residual) → **S103 (UX copy hygiene)** — 3 個 S100 META post-ship cross-cutting follow-up，全在 v3.4.x patch series。本 ship 後 v3.4.3 標誌 S100 META cross-cutting audit 第三輪 follow-up complete。Mode B audit 用 Chrome MCP live render 視角是與 page-by-page data audit / static grep audit 互補的第三 cut。

## §8 Lesson — stub-page copy hygiene

Build process 中 stub page 寫 copy 時，dev 自然把 internal milestone ID 當 reference 寫進去（"等 S096f2 ship 就 enable"），易遺留到 production。**建議寫進 development-standards.md §UI copy convention**：

> Stub page 的 user-facing copy **不該** 含 internal spec / milestone ID（`SXXX`、`SXXXa-h`）。可用「即將開放」/「後續版本推出」這類 functional 字面。Internal references 只放 JSDoc / commit message / spec doc。

加進 development-standards.md 列為 polish backlog（避免本 ship 拉進 doc-side scope，per NEVER bundle drive-by refactors）。

S100 META audit 採 page-by-page data 視角（每頁 fetch 哪個 endpoint，是否假），對「user-visible string 是否含 internal jargon」是另一個盲點 — Mode B Round 8 的 Chrome MCP live render 視角才看得見。Test-case ledger 之後可加 cut: **每 Round 跑 final 字串檢查**「render 出的 text 不含 SXXX / TODO / FIXME / DEBUG / placeholder」。
