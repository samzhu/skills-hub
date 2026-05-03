# S110 — MySkillsPage zh-TW label compliance

> **Status**: ✅ shipped `v3.4.10` (2026-05-03 — full-ship cron tick 17，no Spec-Only-Handoff)
> **Type**: Frontend UX i18n compliance (no behavior / API change)
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 cron Tick 17 Mode B Round 15 — MySkillsPage `/my-skills` live walk-through (Chrome MCP)

## §1 Goal

`/my-skills` 4 個 MetricCard label 與 status subtitle 仍英文（`Total skills` / `Total downloads` / `Avg rating` / `Open flags` + `0 published · 0 draft · 0 suspended`），違反 CLAUDE.md「UI 語言: 繁體中文（zh-TW）— 所有前端頁面、按鈕、提示訊息、錯誤訊息皆使用繁體中文」。

**Live 觀察 (Chrome MCP cron Tick 17 Round 15)**：
- 進入 `/my-skills` → MetricCard titles 英文 + sub-labels（「累積下載」「評分系統未啟用」「MVP 暫缺」）已 zh-TW
- TabPill labels（line 117-128）已 zh-TW（「全部」「已發布」「草稿」「已停用」）— 同 page 內 i18n 不一致

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → S109 → **S110** — 第 10 個 cross-cutting follow-up；同 S103 (UX copy hygiene) 軸的延伸 — S103 修「stub copy 不含 spec ID」，S110 修「label 不混 English/zh-TW」；都是 user-facing string compliance audit cut。

## §2 Findings — verified gaps

| # | File:line | 現狀 | 嚴重度 |
|---|-----------|------|--------|
| 1 | `MySkillsPage.tsx:88` | `label="Total skills"` | Medium — user-visible card title 英文 |
| 2 | `MySkillsPage.tsx:90` | `subtitle="...published · ...draft · ...suspended"` | Medium — status breakdown English tokens |
| 3 | `MySkillsPage.tsx:93` | `label="Total downloads"` | Medium |
| 4 | `MySkillsPage.tsx:97` | `label="Avg rating"` | Medium |
| 5 | `MySkillsPage.tsx:98` | `label="Open flags"` | Medium |

**Excluded**:
- JSDoc comments (line 23/30) — non-user-facing dev reference 保留 English OK
- TabPill labels (line 117-128) — already zh-TW，不需動

## §3 Approach

**Replacement strategy**：直接 inline 替換 5 處 string，不抽 i18n constants（per NEVER add abstraction for hypothetical second caller；CLAUDE.md 註明「API 錯誤訊息: 英文」是另一場景，不影響本 fix）。

| Original (English) | Replacement (zh-TW) |
|--------------------|---------------------|
| `Total skills` | `技能總數` |
| `${published} published · ${drafts} draft · ${suspended} suspended` | `已發布 ${published} · 草稿 ${drafts} · 已停用 ${suspended}` |
| `Total downloads` | `下載總數` |
| `Avg rating` | `平均評分` |
| `Open flags` | `待處理回報` |

新 status template 與 TabPill labels 用詞一致（「已發布」/「草稿」/「已停用」）— 同 page 內 terminology consistency。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入 `/my-skills` | render 4 MetricCard | screen 顯「技能總數」「下載總數」「平均評分」「待處理回報」zh-TW labels |
| AC-2 | 同 AC-1 | render status subtitle | 不含 `published · draft · suspended` 英文 pattern；改顯「已發布 · 草稿 · 已停用」zh-TW pattern |
| AC-3 | regression guard | render | 4 個 English labels (`Total skills` etc.) 完全消失於 DOM |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/MySkillsPage.tsx` | 5 處 string 替換 (lines 88/90/93/97/98) | ~5 |
| `frontend/src/pages/MySkillsPage.test.tsx` | 新建 — AC-1/2/3 zh-TW compliance assertions | ~40 |

## §6 Test plan

```bash
cd frontend
npm test -- --run MySkillsPage
# expect 3/3 PASS
# Chrome MCP smoke: /my-skills → grep DOM 4 zh-TW labels present + 5 English leftover absent
```

## §7 Result

**Shipped 2026-05-03 cron Tick 17 @ ~09:24**.

### Implement checklist

- [x] MySkillsPage.tsx 5 處 string 替換（labels + status subtitle）
- [x] MySkillsPage.test.tsx 新建（3 ACs：zh-TW labels present + English subtitle absent + English label regression guard）
- [x] `npm test --run MySkillsPage`：3/3 PASS（1.03s）
- [x] Chrome MCP live smoke `/my-skills`：4 zh-TW labels render（「技能總數」「下載總數」「平均評分」「待處理回報」）；5 English leftover (`Total skills` / `Total downloads` / `Avg rating` / `Open flags` / `published ·` pattern) 全 removed ✓
- [x] CHANGELOG `v3.4.10` patch entry
- [x] roadmap row → ✅
- [x] spec doc archive 直接（同 S109 full-ship pattern）

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 3（MySkillsPage.tsx + MySkillsPage.test.tsx + spec/CHANGELOG/roadmap docs）|
| LOC delta | +45 / -5（含 test 新檔 +40）|
| FE tests | 既有 40 → 43（+3 MySkillsPage.test.tsx 新建）|
| Backend touch | 0（純 frontend i18n compliance）|
| Wall clock | ~7 min（PLAN 1 + IMPLEMENT 2 + tests 2 + smoke 1 + DOCUMENT 1）|

### Live render validation (Chrome MCP)

| Element | Before (Tick 17 audit) | After (Tick 17 ship) |
|---------|----------------------|---------------------|
| MetricCard 1 label | `Total skills` | `技能總數` ✓ |
| MetricCard 1 subtitle | `0 published · 0 draft · 0 suspended` | `已發布 0 · 草稿 0 · 已停用 0` ✓ |
| MetricCard 2 label | `Total downloads` | `下載總數` ✓ |
| MetricCard 3 label | `Avg rating` | `平均評分` ✓ |
| MetricCard 4 label | `Open flags` | `待處理回報` ✓ |

### Trim deferred

- **Other pages i18n audit** — 本 spec 只修 MySkillsPage；其他 pages 是否還有 English label 需單獨 audit；polish backlog（建議 future Mode B Round audit cut: i18n compliance grep）
- **i18n abstraction** — 不抽 constants per scope 範圍小

### Sibling chain validation

S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → S109 → **S110** — 第 10 個 cross-cutting follow-up；cut 累積 10 層（S103 同軸延伸：user-visible string compliance）。發現方式 = Chrome MCP 跑 MySkillsPage 直接看到 mixed English/zh-TW 不一致；前 9 cut 沒覆蓋此 page。

### Process learning

第 2 個 single-tick full-ship 案例（首例 S109 vite proxy actuator）。Pattern 持續驗證：
1. XS scope (5 string replace + minimal test)
2. CLAUDE.md rule clear (no design ambiguity)
3. Sibling pattern proven (S103 同樣 string-replace approach)
4. Smoke < 30s via Chrome MCP

對 i18n compliance / copy polish / dev-config 類 micro fix，single-tick full-ship 比 Spec-Only-Handoff 高效（省 1 個 cron tick round trip）。

## §8 Lesson — i18n compliance audit cut

S100~S109 cut 軸沒系統性 audit user-facing string i18n compliance — S103 是 ad-hoc 抓 stub copy spec ID leak，S110 是 ad-hoc 抓 MySkillsPage English leftover。**建議 future Mode B Round audit cut**：

```bash
# 全 frontend pages grep 可疑 English-only user-visible string patterns
grep -rnE 'label="[A-Z][a-z]+ [a-z]+|placeholder="[A-Z]' frontend/src/pages/
grep -rnE '>(Total|Avg|Open|Draft|Published|Pending) ' frontend/src/pages/
```

寫進 development-standards.md §UI checklist：「PR review 必須 grep 上述 patterns 確認無 user-facing English-only string」。Sibling lesson 與 S103/S104/S105/S106/S107/S108/S109 集中 doc-side rules 處理；polish backlog。
