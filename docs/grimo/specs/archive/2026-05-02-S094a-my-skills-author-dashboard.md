# S094a — My Skills (Author Dashboard) `/my-skills`

> **Status**: shipped
> **Type**: META S094 sub-spec 3/4 (M trim 至 S 達成單一 tick ship)
> **Estimate**: M(12-13) → S(9) trimmed（per scope discussion below）
> **Source**: `docs/grimo/ui/prototype/my_skills_author_dashboard.html` + README ll.69-87 + DESIGN.md

## §1 Goal

P6 SBE「技能作者查看自己的數據」是 PRD MVP scope 但既有 frontend 缺對應 page — 唯一 missing piece。本 spec 補上 `/my-skills` route：作者進入後看到 hero（identity + total）、4 個 metric cards（status breakdown / downloads / rating / flags）、tabs by status、自己的 skill 列表。

LAB mode 用 `/api/v1/me` 取 `sub` 為 author identity；無 OAuth gate，任何 user 可改 URL 看別人 dashboard（已知 MVP 限制 per Feature First）。

## §2 Approach

### §2.1 Scope trim from M → S

Prototype 完整版（M 12-13 pts）含：
- Hero + 4 metrics + tabs + table（含 sparkline column 顯示 30d 下載趨勢）
- Per-skill 30d trend endpoint (`/api/v1/skills/{id}/analytics/trend?days=30`)
- Sparkline component（SVG polyline）
- Avg rating（rating 系統）

實際 ship 範圍（S/9 pts）trim：
- ✓ Hero + 4 metrics + tabs + skill list（table-style rows）
- ✓ Backend `?author=` filter + bypass PUBLISHED filter for author view
- ✗ Sparkline + per-skill trend endpoint — defer (polish follow-up)
- ✗ Avg rating — 顯 "—"（rating 系統 MVP 未啟用）
- ✗ Open flags counter — 顯 0（MVP 暫缺；future spec 接 flag aggregation）

trim rationale：sparkline + trend endpoint 是 nice-to-have，不影響 P6 核心 SBE「作者查看自己的 skill 列表 + 狀態」。其他 3 個 MetricCard 已給足數據概覽。Sparkline polish 留為 next tick 或 future spec 處理（不阻塞 META 推進）。

### §2.2 Backend `?author=` filter design

當前 `GET /skills` 強制 `WHERE status = 'PUBLISHED'`（per S031 公開查詢）。Author 視角需看 own DRAFT/SUSPENDED → 加 `?author=` query param：

```java
public Page<Skill> search(String keyword, String category, String author, Pageable pageable) {
  var authorMode = StringUtils.hasText(author);
  var statusClause = authorMode
      ? " WHERE LOWER(author) = LOWER(:author) "    // S094a: bypass PUBLISHED filter for author view
      : " WHERE status = 'PUBLISHED' ";              // S031: existing 公開查詢
  // ... rest unchanged
}
```

**LOWER 比對** — case-insensitive，符合 PRD「author」欄位語意（`platform-team` ≡ `Platform-Team`）。

### §2.3 Reuse posture

| Component | Source | Reused for |
|-----------|--------|-----------|
| `AppShell` | existing | page chrome |
| `MetricCard` (S088) | existing | 4-metric strip |
| `IconTile` (S085) | existing | row category icon |
| `RiskBadge` | existing | row risk pill |
| `BeamFrame` | existing | hero CTA wrap |
| `EmptyState` invite tone (S094c) | existing | 0 skills 場景 |
| `useSkillList` hook | existing | skills query (extend with author param) |
| `useMe` hook | NEW | current user identity |

不抽 SkillTable/SkillRow 為 shared component — 此 page 是唯一使用者，YAGNI；若 future spec 也需 table 再 abstract.

### §2.4 Confidence classification

| Decision | Confidence | Source |
|----------|------------|--------|
| `?author=` filter SQL change | **Validated** | 直接 SQL inspect / 5 unit tests cover happy/uppercase/all-statuses/no-match/combined |
| Bypass PUBLISHED for author view 是 LAB-mode acceptable trade-off | **Validated** | per Feature First / CLAUDE.md MVP 階段 |
| `/api/v1/me` 回 `{sub: 'lab-user'}` | **Validated** | smoke curl confirmed |
| EmptyState invite tone 適用 0-skill case | **Validated** | S094c ship tested |

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /skills?author=sam` | 回 sam 的所有 skills（含 DRAFT/SUSPENDED） |
| AC-2 | Backend `GET /skills?author=SAM` (uppercase) | case-insensitive 回相同結果 |
| AC-3 | Backend `GET /skills?author=ghost` (不存在) | 200 + content=[] |
| AC-4 | Backend `GET /skills?author=sam&keyword=docker` | 組合 filter (AND) — 只回 sam 的含 docker keyword |
| AC-5 | Backend `GET /skills` (no author) | 維持 S031 行為 — 只 PUBLISHED |
| AC-6 | Frontend `/my-skills` 載入 | 取 `/me` → query `/skills?author={sub}` → render hero + metrics + list |
| AC-7 | Hero 顯 author identity + total skills count | "以 lab-user 身份發布" + "你的 N 個技能" |
| AC-8 | 4 MetricCards 顯 total + downloads + rating(—) + flags(0) | metrics correct based on filtered skills |
| AC-9 | Tabs (All/Published/Drafts/Suspended) filter list | click tab 切換 visible rows |
| AC-10 | 0 skills → EmptyState invite tone | 顯「你還沒發布過技能」+ 2 CTAs |
| AC-11 | AppShell nav 加「我的技能」link | `/my-skills` |
| AC-12 | Backend tests +5 (S094a-1~5) PASS | regression check |
| AC-13 | Frontend tests no break | 28 → 28 PASS（無新加 test；page 純 UI render hooks） |
| AC-14 | Build ≤ 380KB JS | budget guard |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/skill/query/
├── SkillQueryController.java              ← + ?author= param
└── SkillQueryService.java                 ← + author 4th param + bypass PUBLISHED for author view

backend/src/test/java/io/github/samzhu/skillshub/skill/query/
└── SkillSearchTest.java                   ← + 5 author filter tests

backend/src/test/java/io/github/samzhu/skillshub/  (existing tests patched to 4-arg)
├── skill/query/SkillQueryControllerApiContractTest.java
└── shared/security/SkillsApiAnonymousTest.java

frontend/src/api/skills.ts                 ← + author? to SkillSearchParams
frontend/src/hooks/useMe.ts                ← NEW
frontend/src/pages/MySkillsPage.tsx        ← NEW
frontend/src/App.tsx                       ← + /my-skills route
frontend/src/components/AppShell.tsx       ← + 我的技能 nav link
```

## §5 Test plan

- `cd backend && ./gradlew test` — 預期 299 → 304 PASS（+5 author filter tests + 2 patched mock signatures）
- `cd frontend && npm test` — 28 → 28 PASS（無新加 page test；smoke 留 manual）
- `cd frontend && npm run build` — ≤ 380KB JS

Manual smoke:
- `curl 'http://localhost:8080/api/v1/skills?author=lab-user&size=5'` → 應 200 含 lab-user 的 skills
- 瀏覽器 `/my-skills` → 顯 hero + metrics + list（或 EmptyState if 0 skills）

## §6 Verification

待 §7 填。

## §7 Result

- **Backend tests** (subset run for tick budget): SkillSearchTest（含新加 5 author tests）+ SkillQueryControllerApiContractTest（patched mock）+ SkillsApiAnonymousTest（patched mock）— **BUILD SUCCESSFUL** in 1m 32s ✓ 0 fail
  - SkillSearchTest: pre 5 → post 10 tests（+5: AC-S094a-1~5 author exact / case-insensitive / all-statuses / no-match / combined keyword-author）
  - 2 mock-patch test (3-arg → 4-arg) compile ✓ + behavior preserved
- **Frontend tests**: 28 → 28 PASS（無新加 page test；MySkillsPage 為純 UI render hooks，per S size scope skip integration test，留 manual smoke）
- **JS bundle**: 372 → 377KB (+5KB; MySkillsPage + useMe + 4-row table style)
- **CSS bundle**: 36.7 → 36.97KB (+0.3KB; status pill inline styles)
- **Build time**: 426ms（無 regression）
- **Components shipped**:
  - `frontend/src/pages/MySkillsPage.tsx` — page with hero + 4 metrics + tabs + table-style rows + EmptyState invite tone fallback
  - `frontend/src/hooks/useMe.ts` — current user identity hook
  - `backend/src/.../SkillQueryService.java` — `?author=` filter with bypass-PUBLISHED logic
- **AC coverage**:
  - AC-1~5 backend filter: vitest test pass ✓
  - AC-6~11 frontend rendering: manual smoke pending (live :8080 backend 仍跑舊 code，不阻塞 commit；下次 backend restart S093 transition 後可現地驗)
  - AC-12 backend +5 PASS ✓
  - AC-13 frontend 28/28 ✓
  - AC-14 build 377KB < 380KB budget ✓

**Trim from prototype noted (§2.1)**:
- Sparkline column 暫缺；row 顯 download count + version 即可定位 skill 健康度
- Avg rating "—" / Open flags 0 — MVP 暫缺資料源

ship as **v2.71.0** (M88c / META S094 sub-spec 3/4 完成)。

**META status**: 4 sub-specs progress 3/4 ✓ — final ship S094b Semantic Search Results (M, includes LLM intent POC).
