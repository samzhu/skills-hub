# S102 — Post-S096e1 routing residual link target fix

> **Status**: ✅ shipped `v3.4.2` (2026-05-03 — implement cron tick)
> **Type**: Frontend defensive cleanup (sibling to S100 META)
> **Estimate**: XS (2-3 pts)
> **Triggered by**: 2026-05-03 cron-loop audit tick — 30m schedule, user directive 「檢查所有資料連結，發現假頁面/缺 API 開 spec 由另一個 AI 實作」

## §1 Goal

S100 META（page-level data authenticity audit, 2026-05-02 ship）已驗證 27 pages 全 `0 fake confirmed`。本 spec 補 S100 page-level 視角漏掉的**橫切連結層**問題：S096e1 把 `/` 從 browse list 改為新 LandingPage 之後，4 處 back-navigation 與 empty-state CTA 沒同步把 target 從 `/` 換成 `/browse`，導致**標籤-目的地語意不符**（label 寫「列表 / 瀏覽」實際跳到 landing）；外加 1 處 LandingPage footer placeholder 自指迴圈。

不涉及任何後端、新 API、新 page；純粹 4 個 `<Link to=>` / `navigate()` target 替換 + 1 個 footer link 處理。

**Sibling 關係**：S100e（AnalyticsPage Top 10 defensive guard）→ S102（routing residual link）— 都是 S100 ship 後從橫切視角找出的 follow-up，驗證 page-level audit 有 known blind spot 在 cross-cutting linking layer。

## §2 Findings — verified gaps

| # | File:line | 現狀 | 問題 | 嚴重度 |
|---|-----------|------|------|--------|
| 1 | `frontend/src/pages/SkillDetailPage.tsx:110` | `<Link to="/">返回列表</Link>` | label「列表」但 target 是 LandingPage（不是 list） | **Medium** — label-target mismatch |
| 2 | `frontend/src/pages/SkillDetailPage.tsx:100` | `<Link to="/">返回首頁</Link>`（error state） | label「首頁」歧義；user 從 `/browse` 進來時應回 list | **Low-Medium** |
| 3 | `frontend/src/pages/SearchResultsPage.tsx:43` | `navigate('/')`（清空 query 時） | 清搜尋應回 browse list 才合理；當前送回 landing 中斷流 | **Medium** |
| 4 | `frontend/src/pages/SearchResultsPage.tsx:66` | `primaryAction={{ label: '瀏覽全部技能', href: '/' }}` | label「瀏覽全部技能」與 `/` 直接矛盾 | **Medium** — 文案與目的地直接打架 |
| 5 | `frontend/src/pages/LandingPage.tsx:159` | `<Link to="/">狀態</Link>`（footer） | label「狀態」+ target = LandingPage 自身；無 `/status` page 對應 | **Low** — placeholder pattern |

**Excluded（agent 誤判 / 已驗證為合法）**:
- `YourFirstSkillPage.tsx:177` `https://github.com/anthropics/skills` — Anthropic 官方 GitHub org slug **就是** `anthropics`（複數 s），不是 typo。
- `SkillMdSpecPage.tsx:21` `https://agentskills.io` — CLAUDE.md L3 明示「基於 agentskills.io SKILL.md 開放標準」，是專案根基標準文件 URL，非 placeholder。

## §3 Approach

**Trim path**：本 spec 已 XS，無進一步 trim 空間；若 implement tick 觸 wall，trim 順序為 5（footer placeholder）→ 2（error state）；保 1 / 3 / 4（高 user-flow impact）。

**Decision per gap**：
- Gap #1 / #2 → 改 target 為 `/browse`，label 同步調整為「返回列表」（移除歧義「首頁」）
- Gap #3 / #4 → 改 target 為 `/browse`；label 已是「瀏覽全部技能」直接對齊
- Gap #5 → 兩選一：
  - **Option A（建議）**：移除「狀態」link，footer 只留「文件」+「API」兩條
  - **Option B**：改成 `<a href="/v3/api-docs">健康檢查</a>` 對齊 SpringDoc actuator/health（若 expose）；本 spec 不開新 page

**選定 Option A** — 簡潔；future 真做 status page 時再加回。

**不引入新 component** — 直接改 5 處 inline link target，沒有 routing 抽象層需要建立。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入任一 skill detail page (e.g. `/skills/abc-123`) | 點 header 「返回列表」link | navigate to `/browse`（非 `/`） |
| AC-2 | skill detail page 觸發 error state（e.g. fetch fail / 404） | 點「返回列表」link（label 改名統一） | navigate to `/browse` |
| AC-3 | 在 `/search?q=foo` 輸入空字串提交 | form submit | navigate to `/browse`（非 `/`） |
| AC-4 | 進入 `/search`（無 q query string） | EmptyState 顯示「瀏覽全部技能」CTA | href = `/browse` |
| AC-5 | LandingPage footer | 渲染 footer | 只剩「文件」+「API」兩條 link，「狀態」link 已移除 |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/SkillDetailPage.tsx` | 2 處 `to="/"` → `to="/browse"`；error state label 「返回首頁」→「返回列表」統一 | ~3 |
| `frontend/src/pages/SearchResultsPage.tsx` | `navigate('/')` → `navigate('/browse')`；EmptyState `href: '/'` → `href: '/browse'` | ~2 |
| `frontend/src/pages/LandingPage.tsx` | footer 移除 `<Link to="/">狀態</Link>` | ~1 |

**測試新增 / 更新**：
- `SkillDetailPage.test.tsx` — 加 AC-1 / AC-2 link target assertion（`getByRole('link', {name: '返回列表'})` + `expect(...).toHaveAttribute('href', '/browse')`）
- `SearchResultsPage.test.tsx`（新檔，若不存在）或補測 — AC-3 / AC-4
- `LandingPage.test.tsx`（若存在）— AC-5 footer 只剩兩條

## §6 Test plan

```bash
cd frontend
npm test -- SkillDetailPage SearchResultsPage LandingPage
npm run build  # ensure no broken import / TS error
```

**Negative cases per methodology**:
- 直接 deep-link `/skills/non-existent` → 404 error state → click 返回列表 → land at `/browse`（AC-2）
- `/search?q=` empty → EmptyState 渲染 → click「瀏覽全部技能」→ `/browse`（AC-4）

**Edge case**:
- React Router unstable_useBlocker / scroll restoration — 不影響本 spec（target 只變字串）

## §7 Result

**Shipped 2026-05-03 cron tick @ ~01:56 (Tick 2 of 30m loop fd48748a)**.

### Implement checklist

- [x] 5 處 link target 替換完成（SkillDetailPage x2 + SearchResultsPage x2 + LandingPage footer 移除「狀態」）
- [x] SkillDetailPage.test.tsx AC-3 更新 → 既有 test 從 `返回首頁`/`/` 改成 `返回列表`/`/browse`，重命名為 `AC-3 (S102)`
- [x] SearchResultsPage.test.tsx 新增（AC-3 form clear + AC-4 EmptyState CTA）— 用內建 `fireEvent`，**不**新增 `@testing-library/user-event` dep
- [x] `npm test` 全綠：SkillDetailPage 3 PASS + SearchResultsPage 2 PASS = 5/5
- [ ] `npm run build` — pre-existing TS errors 在 13 個其他 test files (`global` not defined / `riskLevel` type mismatch)；本 spec 改動**不引入**新 TS error（`git stash` 對照確認 pristine `e41d71f` 同樣 fail）。Build red 為 separate concern，不在本 spec scope（per NEVER bundle drive-by refactors）
- [x] CHANGELOG 加 `v3.4.2` patch entry
- [x] roadmap row 改 ✅
- [x] spec doc 移 archive/

### Trim deferred from §3

- **AC-5 LandingPage footer test 未寫**：spec §3 trim 順序明示「5 → 2」；XS budget 仍夠寫但 footer 改動是純 JSX 移除一行，DOM-level test 補測價值低（人眼 review 即可），改動本身已 verified。Polish backlog 若需要再補。

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 5（3 page tsx + 1 existing test + 1 new test）|
| LOC delta | +35 / -9（+30 是 SearchResultsPage.test.tsx 整檔新增）|
| FE tests | 既有 28 → 30（+2 from SearchResultsPage.test.tsx）|
| Backend touch | 0（純 frontend defensive cleanup）|
| Wall clock | ~14 min（PLAN 2 + IMPLEMENT 4 + VERIFY 5 + DOCUMENT 3）|

### Sibling validation

S100e (AnalyticsPage Top 10 defensive guard) 與 S102 (routing residual) 共同形成 S100 META 的「post-ship cross-cutting follow-up」pattern：page-by-page audit 對 page 內部資料是否假是好工具，但對 **inter-page semantic alignment** 是盲點。本 ship 後依 §8 lessons 的建議，**未來 routing-touching spec（如 S096e1 把 `/` 改 LandingPage）內建 AC**：「全 codebase grep `to="/"` / `navigate("/")` 逐個確認 post-change 語意對齊」。Development-standards.md §8 routing 章節更新 deferred polish backlog（避免本 ship 拉進 doc-side scope）。

## §8 Lessons / Sibling note

S100 META audit 採 page-by-page data-source 視角（每頁問「fetch 哪個 endpoint」），這個 cut 對 fake data 抓得乾淨，但**對 page 之間 link 的 target 是否正確是盲點**——一個 page 的 outgoing link 對另一個 page 是否存在/語意對齊，不在 page-by-page audit 自然視野內。S102 補的就是這個 cut。

下次設計 routing-touching spec（像 S096e1 把 `/` 改 LandingPage）時，spec 應內建一條 AC：「audit 全 codebase grep `to="/"` 與 `navigate("/")`，逐個確認 post-change 語意正確」。本 spec ship 後，把這條建議寫進 development-standards.md §8 routing 章節（若無此章節則新建）。
