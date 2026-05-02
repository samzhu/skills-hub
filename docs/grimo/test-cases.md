# E2E Test-Case Ledger

> Round catalogue for Mode B (E2E testing) per `.claude/loop.md` cron-bounded agent。每 round 三 category：positive / negative / edge。Bug found → 寫 fix-spec 入 Mode A，return Mode B 下一 tick。
>
> **Status legend**：📋 planned / 🚧 in-progress / ✅ pass / ❌ fail (link to fix spec)
>
> **Last update**: 2026-05-02 (v3.1.1 — tick 19)

---

## Round 1 — Browse skill flow（瀏覽技能流程）

| # | Category | Scenario | Expected | Status | Notes |
|---|----------|----------|----------|--------|-------|
| 1.1 | positive | Visit `/` | Landing page renders Hero h1 「你的團隊真的可以信任的技能登錄中心」+ stats band 4 cells + 6 popular SkillCards + footer | 📋 | covered by partial test |
| 1.2 | positive | Click「瀏覽技能登錄」CTA | Navigate to `/browse`；HomePage renders SearchBar + sidebar + 3-col grid | 📋 | |
| 1.3 | positive | `/browse` → 點 Skill card | Navigate to `/skills/:id`；hero + 4 metric cards + 6-tab structure | 📋 | |
| 1.4 | negative | `/skills/non-existent-uuid` | 404 message「找不到此技能」（不顯 retry hint）；500 顯「載入技能時發生錯誤」+ retry hint；返回首頁 link | ✅ | SkillDetailPage.test.tsx 3 ACs |
| 1.5 | edge | `/browse` query 1000+ skills, pagination works | 「上一頁」「下一頁」disabled when at boundary | 📋 | |

## Round 2 — Search flow（語意 + keyword 搜尋）

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 2.1 | positive | 輸入 keyword → SearchBar 觸發 | useSemanticSearch + fallback useSkillList；結果出現 in 3-col grid | 📋 |
| 2.2 | positive | 語意搜尋結果頁 → top match 顯 BeamFrame featured 變體 | SearchResultsPage 第一 result 有 BeamFrame 包裝 | 📋 |
| 2.3 | negative | 0 results query | EmptyState redirect tone「找不到符合的技能」+ 3 suggestions | 📋 |
| 2.4 | edge | Gemini API down → fallback to keyword | 仍能搜（keyword regex match） | 📋 needs runtime test |

## Round 3 — Filter / Sort flow（S098d + S098d2）

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 3.1 | positive | Click「最新」sort chip | 卡片重新排序 by createdAt DESC | 📋 |
| 3.2 | positive | Click「風險低」sort chip | 卡片排序 by NONE→LOW→MEDIUM→HIGH | 📋 |
| 3.3 | positive | Toggle RiskFilterSidebar「LOW」 | 只顯 LOW 卡片；「全部」reset 回未篩 | 📋 |
| 3.4 | edge | 多 risk filter 同時 (LOW + MEDIUM) | OR 邏輯：兩者皆顯 | 📋 |

## Round 4 — Publish flow（完整 Upload → Validate → Review → Live）

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 4.1 | positive | `/publish` → drop valid zip → 上傳 | Mutation success → navigate `/publish/validate?id=X` | 📋 |
| 4.2 | positive | `/publish/validate` polling | Stepper Upload (done) + Validate (active)；Status callout「進行中」每 2s 自動 refetch | ✅ AC-2 in PublishValidatePage.test.tsx |
| 4.3 | positive | scan 完 (riskLevel 設值) | useEffect navigate `/publish/review?id=X` | ✅ AC-3 in PublishValidatePage.test.tsx |
| 4.4 | positive | `/publish/review` → success callout 顯 + skill metadata + 風險說明 | ✅ inline render existing | 📋 |
| 4.5 | negative | 上傳 frontmatter 缺 name | Mutation onError → navigate `/publish/failed?state=A&msg=...` | 📋 |
| 4.6 | edge | scan 完 risk_level=HIGH | `/publish/review` useEffect navigate `/publish/failed?state=B&id=X` | 📋 |
| 4.7 | edge | `/publish/failed` State A msg 含特殊字元 | URL-decode 正確顯示在 pre-block | ✅ AC-1 in PublishFailedPage.test.tsx |

## Round 5 — Skill Detail（5-tab + sparkline + version diff）

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 5.1 | positive | `/skills/:id` PUBLISHED → 30d sparkline 顯 | SkillHero + Sparkline 120×32 purple polyline | 📋 |
| 5.2 | positive | DRAFT/SUSPENDED → sparkline 不顯 | Conditional hidden | 📋 |
| 5.3 | positive | 切 Reviews tab | EmptyState invite tone「評論系統即將推出」 | 📋 |
| 5.4 | positive | 切 Flags tab | EmptyState clear tone「目前沒有任何回報」 | 📋 |
| 5.5 | positive | Version Diff entry — VersionList ≥ 2 versions → 顯「比較版本變化」連結 | href = `/skills/:skillId/diff`；單版本不顯；空版本顯「尚無版本記錄」；最新 badge + download href 一併驗 | ✅ VersionList.test.tsx 5 ACs |
| 5.6 | positive | `/skills/:id/diff` default | Compares latest 2 versions | ✅ AC-1 in VersionDiffPage.test.tsx |
| 5.7 | edge | `?from=&to=` 同版本 | Should still render（delta = 0）| 📋 |
| 5.8 | negative | versions < 2 | Fallback message「技能版本不足 2 個」| ✅ AC-3 in VersionDiffPage.test.tsx |

## Round 6 — Docs IA（11 個 active link）

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 6.1 | positive | DocsSidebar 全 11 link 點擊 | 各別 page render（H1 + breadcrumb 對應 group/item） | 📋 |
| 6.2 | edge | `/docs/your-first-skill` H2 anchors | 各 H2 element 有 anchor id 可 deep link | 📋 |

## Round 7 — Notification / Empty state polish

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 7.1 | positive | `/notifications` 0 results | EmptyState clear tone + 3 stats 「本週新通知 / 未讀 / 上次接收」+ h1「通知中心」+ non-empty branch render | ✅ NotificationsPage.test.tsx 3 ACs |
| 7.2 | positive | `/collections` 0 results | h1「精選技能集合」+「建立集合」disabled CTA + 不 crash | ✅ CollectionsPage.test.tsx 3 ACs |
| 7.3 | positive | 4 EmptyState tones 各別 render | seed/invite/redirect/clear 結構不同 | ✅ EmptyState.test.tsx 5 ACs |

---

## Bug Ledger（依 letter 編號）

| Bug ID | Discovered | Round / Test | Symptom | Fix Spec | Resolved Version |
|--------|-----------|--------------|---------|----------|------------------|
| (尚未發現 bugs in 本 session) | — | — | — | — | — |

> 編號規則：A → Z → AA → AB → ...；跨 session monotonic。發現新 bug 時 append + 寫 fix-spec 入 Mode A。

---

## Saturation Check

per `.claude/loop.md` EXIT: SATURATED 條件：「Backlog is empty AND ≥3 consecutive ticks found 0 bugs」。

當前狀態：
- Backlog 非空（S098e2 / S096f2/g2/h2 等 backend specs 待 ship）
- 連續 0-bug ticks: ≥18（since session 開始未發現 bug；test 33→44 全 PASS）

**結論**：未達 SATURATED（backlog 非空）— 但 cron-bound tick wall 對 backend specs 不夠。建議 /schedule 雲端 agent 接手 backend work，或停 cron 等 user 手動。

---

## Round Coverage Summary

| Round | Total | ✅ Done | 📋 Planned |
|-------|-------|---------|------------|
| 1 Browse | 5 | 1 | 4 |
| 2 Search | 4 | 0 | 4 |
| 3 Filter/Sort | 4 | 0 | 4 |
| 4 Publish | 7 | 3 | 4 |
| 5 Skill Detail | 8 | 3 | 5 |
| 6 Docs IA | 2 | 0 | 2 |
| 7 Empty state | 3 | 3 | 0 |
| **Total** | **33** | **10** | **23** |

current component test count: 44（cover ~6 ledger ACs + 38 unit-level invariants）。E2E browser-level scenarios（27 planned）需 Playwright / Cypress —— defer until backend stabilizes 或 cloud-scheduled E2E run。
