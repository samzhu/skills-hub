# S111 — RiskTiersPage zh-TW tier title compliance

> **Status**: ✅ shipped `v3.4.11` (2026-05-03 — full-ship cron tick 18，no Spec-Only-Handoff)
> **Type**: Frontend UX i18n compliance (no behavior / API change)
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 cron Tick 18 Mode B Round 16 — systematic i18n grep across frontend pages per S110 §8 audit cut

## §1 Goal

`/docs/risk-tiers` 頁面 4 個 Tier title (`Pure documentation` / `Auto-published` / `Auto-published, with warning badge` / `Blocked until reviewer approves`) 仍英文，違反 CLAUDE.md「UI 語言: 繁體中文」rule。Tier body 段落已 zh-TW（含偶發 English 技術 term 如 `prompt-only` 是合理 loanword），是 same-component i18n inconsistency。

**Live 觀察 (Chrome MCP cron Tick 18 Round 16)**：
- 進入 `/docs/risk-tiers` → 4 個 Tier card titles 全英文
- 同 page H1「風險層級」+ body 段落 + nav links 都已 zh-TW

**Discovery method**：S110 §8 lesson 提議的 i18n grep cut — `grep -rnE 'title="[A-Z][a-z]+' frontend/src/pages/` 直接命中此 page。

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → S109 → S110 → **S111** — 第 11 個 cross-cutting follow-up；S110 同軸延伸（user-visible string compliance），驗證 S110 §8 提議的「systematic i18n grep」audit cut 確實能找出更多 leftover。

## §2 Findings — verified gaps

| # | File:line | 現狀 | 嚴重度 |
|---|-----------|------|--------|
| 1 | `RiskTiersPage.tsx:33` (NONE tier) | `title="Pure documentation"` | Medium — user-visible card title 英文 |
| 2 | `RiskTiersPage.tsx:44` (LOW tier) | `title="Auto-published"` | Medium |
| 3 | `RiskTiersPage.tsx:54` (MEDIUM tier) | `title="Auto-published, with warning badge"` | Medium |
| 4 | `RiskTiersPage.tsx:65` (HIGH tier) | `title="Blocked until reviewer approves"` | Medium |

**Excluded（保留 English 技術 term）**:
- Tier body 內 `scripts/` / `allowed-tools` / `SKILL.md` / `prompt-only` / `RCE` / `~/.ssh` 等技術術語 — 保留（codebase identifier / standard term）
- Tier `level="NONE"|LOW|MEDIUM|HIGH` enum — risk level identifier，與 backend 對齊，保留
- `PublishFailedPage.tsx:113` `title="Bundle 結構"` — 「Bundle」是技術 loanword，body 已是 zh-TW，可接受

## §3 Approach

**Replacement strategy**：直接 inline 替換 4 處 string，與 S110 同 pattern。

| Original (English) | Replacement (zh-TW) |
|--------------------|---------------------|
| `Pure documentation` | `純文件` |
| `Auto-published` | `自動上架` |
| `Auto-published, with warning badge` | `自動上架（顯警示標）` |
| `Blocked until reviewer approves` | `暫不上架，待審核員核准` |

選詞對齊既有 zh-TW 用詞：「上架」(per browse / search results 用詞) + 「審核員」(per `reviewer` business term in body)。

**不引入新 component / props / test 抽象**：純 4 string replace。RiskTiersPage 無既有 test，不新建 test（doc-only page，replace 範圍小且 Chrome MCP smoke 可立即驗）— per S109 process pattern「smoke 充足 + sibling pattern proven」full-ship。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入 `/docs/risk-tiers` | render 4 Tier cards | screen 顯「純文件」「自動上架」「自動上架（顯警示標）」「暫不上架，待審核員核准」zh-TW titles |
| AC-2 | 同 AC-1 | regression check | 4 個 English original strings (`Pure documentation` / `Auto-published` / `Blocked until reviewer approves`) 完全消失於 DOM |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/docs/RiskTiersPage.tsx` | 4 處 `title=` string 替換 | ~4 |

無 test 新建（rationale 同 §3 — page 無既有 test，scope XS，Chrome MCP smoke 已足夠）。

## §6 Test plan

```bash
# Chrome MCP smoke: /docs/risk-tiers → grep DOM 4 zh-TW titles present + 3 English leftover absent
```

無 npm test scope（page 無 test file）。

## §7 Result

**Shipped 2026-05-03 cron Tick 18 @ ~09:53**.

### Implement checklist

- [x] RiskTiersPage.tsx 4 處 title 替換（lines 33/44/54/65）
- [x] Chrome MCP live smoke `/docs/risk-tiers`：
  - 4 zh-TW titles render（「純文件」「自動上架」「自動上架（顯警示標）」「暫不上架，待審核員核准」）✓
  - 3 English leftover 全 removed（`Pure documentation` / `Auto-published` / `Blocked until reviewer`）✓
- [x] CHANGELOG `v3.4.11` patch entry
- [x] roadmap row → ✅
- [x] spec doc archive 直接（S109 pattern 第 3 次）

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 2（RiskTiersPage.tsx + spec/CHANGELOG/roadmap docs）|
| LOC delta | +4 / -4（純 string replace）|
| FE tests | 既有 43（不變；page 無 test file）|
| Backend touch | 0 |
| Wall clock | ~5 min（PLAN 1 + IMPLEMENT 2 + smoke 1 + DOCUMENT 1）|

### Live render validation (Chrome MCP)

| Tier | Before (Tick 18 audit) | After (Tick 18 ship) |
|------|----------------------|---------------------|
| NONE | `Pure documentation` | `純文件` ✓ |
| LOW | `Auto-published` | `自動上架` ✓ |
| MEDIUM | `Auto-published, with warning badge` | `自動上架（顯警示標）` ✓ |
| HIGH | `Blocked until reviewer approves` | `暫不上架，待審核員核准` ✓ |

### Trim deferred

- **Other docs sub-pages i18n audit** — 只抓 `/docs/risk-tiers`；其他 `/docs/*` sub-pages 是否有同類 leftover 需單獨 audit；polish backlog（建議 future Mode B Round 17 跑 full-doc i18n grep）
- **Test for RiskTiersPage** — page 是 doc 性質 reference page，無 dynamic data，新建 test 對 unit invariant 收益低；polish backlog

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → S105 (component-context alignment v3.4.5) → S106 (control-behavior alignment v3.4.6) → S107 (API projection field completeness v3.4.7) → S108 (dev proxy SpringDoc v3.4.8) → S109 (dev proxy actuator v3.4.9) → S110 (MySkillsPage zh-TW labels v3.4.10) → **S111 (RiskTiersPage zh-TW titles v3.4.11)** — 第 11 個 cross-cutting follow-up；cut 累積 11 層；S110 同軸延伸並驗證 systematic i18n grep audit cut 有效性。

### Process learning (S109 pattern 第 3 次驗證)

第 3 個 single-tick full-ship 案例（前例 S109 vite proxy actuator + S110 MySkillsPage labels）。Pattern 持續驗證：
1. XS scope (4 string replace, no behavior change)
2. CLAUDE.md rule clear (zh-TW)
3. Sibling pattern proven (S103/S110 同 string-replace approach)
4. Smoke < 30s via Chrome MCP

對 i18n compliance / copy polish 類 micro fix，single-tick full-ship 已驗證為 cron-bound agent 高效 pattern。

## §8 Lesson — i18n grep audit cut effectiveness

S110 §8 提議 systematic i18n grep audit cut，S111 是首次 systematic application — 一條 grep 直接命中 1 個未發現的 page（RiskTiersPage 4 處 leftover）。**Audit cut effectiveness confirmed**。

**建議 future cron tick Mode B Round 17 cut**: 對 `/docs/*` 全 sub-pages 跑 i18n grep（DocsLayout / Reference pages 多為高 doc-content 比重，可能還有 leftover）。

**Cumulative i18n compliance lessons across S103/S110/S111**：
- S103: 修 stub copy 不含 internal spec ID
- S110: 修 page label 不混 English/zh-TW (mixed-page audit)
- S111: 修 doc page tier title 不混 English/zh-TW (doc-page audit via grep)

合併寫入 development-standards.md §UI checklist：
1. PR review 必跑 `grep -rnE 'label="[A-Z][a-z]+|title="[A-Z][a-z]+' frontend/src/pages/` 排除誤判 (aria-label / spec ID)
2. User-facing string 不含 internal spec ID (S103)
3. Same-page label terminology consistency (S110 — TabPill vs MetricCard 對齊)

Polish backlog 與 S103-S110 sibling rules 集中 ship。
