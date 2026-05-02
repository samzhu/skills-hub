# E2E Test-Case Ledger

> Round catalogue for Mode B (E2E testing) per `.claude/loop.md` cron-bounded agent。
>
> **Coverage 要求**（per user directive 2026-05-02）：每 round 必須含**正例 + 反例 + 邊界**三大類；**反例至少 3-5 個**防使用者奇怪操作。Round 不夠強的 fail QA gate。
>
> **反例 minimum checklist**（每 round 至少 cover 3 項）：
> 1. **Empty / null input** — 缺值 / 空字串 / null
> 2. **Boundary violation** — 超過字數限制 / 檔案大小 / 數值範圍
> 3. **Type / format mismatch** — 預期 string 給 number / 格式錯誤
> 4. **State conflict** — DRAFT skill 嘗試下載 / SUSPENDED 嘗試新增版本 / 同名 publish
> 5. **Auth / permission denied** — 無權限 user 嘗試 admin action / cross-tenant 訪問
> 6. **Malicious input** — XSS payload / SQL injection / path traversal / unicode 攻擊
> 7. **Concurrent / race** — 同時 publish 同 version / 過期 version 操作
>
> **Status legend**：📋 planned / 🚧 in-progress / ✅ pass / ❌ fail (link to fix spec)
>
> **Last update**: 2026-05-02 (v3.2.5 — tick 39 + S099 META queued; methodology upgraded per user directive)

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
| 3.3 | positive | Toggle RiskFilterSidebar「LOW」 | onToggle('LOW') called；selected tier 顯 active | ✅ RiskFilterSidebar.test.tsx AC-3+5 |
| 3.4 | edge | 多 risk filter Set 同時包多 tier | count breakdown 反映各 tier；click「全部」call onClear | ✅ RiskFilterSidebar.test.tsx AC-1+4 |

## Round 4 — Publish flow（完整 Upload → Validate → Review → Live）

需強化 negative coverage 至 ≥3 反例（per 2026-05-02 methodology upgrade）。

| # | Category | Scenario | Expected | Status |
|---|----------|----------|----------|--------|
| 4.1 | positive | `/publish` → drop valid zip → 上傳 | Mutation success → navigate `/publish/validate?id=X` | 📋 |
| 4.2 | positive | `/publish/validate` polling | Stepper Upload (done) + Validate (active)；Status callout「進行中」每 2s 自動 refetch | ✅ AC-2 in PublishValidatePage.test.tsx |
| 4.3 | positive | scan 完 (riskLevel 設值) | useEffect navigate `/publish/review?id=X` | ✅ AC-3 in PublishValidatePage.test.tsx |
| 4.4 | positive | `/publish/review` → success callout 顯 + skill metadata + 風險說明 | ✅ inline render existing | 📋 |
| 4.5 | negative (empty) | 上傳 frontmatter 缺 name | Mutation onError → navigate `/publish/failed?state=A&msg=...` | 📋 |
| 4.6 | negative (boundary) | name 超過 64 字元 | Validation reject + 「name 超過 64 字元限制」error msg | 📋 |
| 4.7 | negative (format) | name 含大寫 / 特殊字元（非 [a-z0-9-]） | Validation reject + 「name 必須為小寫 + hyphen」 | 📋 |
| 4.8 | negative (state conflict) | 同 (author, name) 已存在 + 上傳同 version | 409 Conflict + msg「版本已存在」 | 📋 |
| 4.9 | negative (boundary) | zip 超過 5MB | Frontend FileDropZone 攔截 + size error 顯示前不打 backend | ✅ AC-4 in FileDropZone.test.tsx |
| 4.10 | negative (format) | 上傳 .exe 等非 zip/.md | FileDropZone 攔截 + 「只接受 .zip / .md」error | ✅ AC-3 in FileDropZone.test.tsx |
| 4.11 | negative (malicious) | SKILL.md 含 path-traversal `../../../etc/passwd` | Backend zip extractor reject + 400 | 📋 |
| 4.12 | edge | scan 完 risk_level=HIGH | `/publish/review` useEffect navigate `/publish/failed?state=B&id=X` | 📋 |
| 4.13 | edge | `/publish/failed` State A msg 含特殊字元 | URL-decode 正確顯示在 pre-block | ✅ AC-1 in PublishFailedPage.test.tsx |
| 4.14 | edge | `/publish/validate?id=` 空 query | Error callout「缺少 skill id 參數」 | ✅ AC-1 in PublishValidatePage.test.tsx |

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
| **A** | tick 55 (2026-05-02) | S099b3 dev — MiniMarkdown parser | `##NoSpace` 等 lines 不符 heading regex 也不符 paragraph collector（首字 `#` 排除）→ outer while loop 不 advance `i` → infinite loop。Vitest workers 卡 96-97% CPU 直到 manual kill。發現於寫 negative case AC-9 試跑時。 | inline fix in S099b3 commit 17c432a — paragraph fallback 強制 push 當前 line + i++ 避免 infinite loop；任意 line 都 guarantee i 推進。 | v3.4.0 |
| **B** | tick 55 (2026-05-02) | S099b3 test setup — JSX attribute string | JSX `<Component content="...\n..." />` 的 attribute 字串 form **不解** `\n` escape（HTML attribute 慣例 `\n` 為 literal backslash + n，非 LF）。原 6 tests 寫成 `content="\n"` form → 5 個 fail（empty content + paragraph 那 1 沒 `\n` 倖免）。 | inline fix in S099b3 — 用 JSX expression form `content={"\n"}` 或 template literal。Lessons 寫入 mini-markdown.test.tsx 註解供未來 reference。 | v3.4.0 |

> 編號規則：A → Z → AA → AB → ...；跨 session monotonic。發現新 bug 時 append + 寫 fix-spec 入 Mode A。
>
> **Bug 來源觀察**：兩個 bug 都被 negative case test 抓到（per 2026-05-02 methodology「3-5 反例 / round」生效）— 證明 negative test 投資高效。Bug A 是 production bug；Bug B 是 test infra bug（不影響 production behavior 但 block test pass）。

---

## Saturation Check

per `.claude/loop.md` EXIT: SATURATED 條件：「Backlog is empty AND ≥3 consecutive ticks found 0 bugs」。

當前狀態：
- Backlog 非空（S098e2 / S096f2/g2/h2 等 backend specs 待 ship）
- 連續 0-bug ticks: ≥18（since session 開始未發現 bug；test 33→44 全 PASS）

**結論**：未達 SATURATED（backlog 非空）— 但 cron-bound tick wall 對 backend specs 不夠。建議 /schedule 雲端 agent 接手 backend work，或停 cron 等 user 手動。

---

## Round Coverage Summary

| Round | Total | ✅ Done | 📋 Planned | Negative count |
|-------|-------|---------|------------|----------------|
| 1 Browse | 5 | 1 | 4 | 1 |
| 2 Search | 4 | 0 | 4 | 1 |
| 3 Filter/Sort | 4 | 2 | 2 | 0 |
| 4 Publish | **14** (+7 reinforced) | 5 | 9 | **6** ✅ |
| 5 Skill Detail | 8 | 3 | 5 | 1 |
| 6 Docs IA | 2 | 0 | 2 | 0 |
| 7 Empty state | 3 | 3 | 0 | 0 |
| **Total** | **40** | **14** | **26** | **9** |

> Per 2026-05-02 methodology upgrade：每 round 至少 3-5 反例。Round 4 已強化（6 反例 cover empty/boundary/format/state-conflict/malicious 五類）；其餘 rounds 待 backfill 至同樣強度。

current component test count: 44（cover ~6 ledger ACs + 38 unit-level invariants）。E2E browser-level scenarios（27 planned）需 Playwright / Cypress —— defer until backend stabilizes 或 cloud-scheduled E2E run。
