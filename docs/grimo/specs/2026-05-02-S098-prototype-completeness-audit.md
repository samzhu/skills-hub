# S098 META — v2 Prototype Completeness Audit

> **Status**: in-design (planning artefact only; sub-specs do actual ship)
> **Type**: META gap audit + sub-spec backlog refresh
> **Estimate**: M-L total across 8+ gap sub-specs (~50-60 pts to full coverage)
> **Source**: docs/grimo/ui/prototype/ (16 HTML mockups) cross-ref shipped spec archive

## §1 Goal

S096 META 8 sub-specs (a–h) + S097 BeamFrame swap shipped 主 surface 但**多數 prototypes 仍 partial / stub-only**。User 要 audit 全部 16 prototype HTMLs → 列出 gap → 開 sub-specs 規劃 full impl。

本 META spec 為 **planning artefact** — 不直接 ship code；只盤 gaps + 加 roadmap rows。/planning-spec 個別 sub-spec 由 user 觸發或 cron tick 自然 pick.

## §2 Audit Matrix

| # | Prototype | Route(s) | Status | Shipped Specs | Gap |
|---|-----------|----------|--------|---------------|-----|
| 1 | Skills Hub Landing.html | `/` | ✅ Full | S096e1 | — |
| 2 | Skills Hub Homepage.html | `/browse` | 🟡 Partial | S085 / S096b cosmetic / S096d1 hex | 缺：3-column grid (現 max 2) / sort chips / sidebar risk filter / live-stats inline summary |
| 3 | Skills Hub Skill Detail.html | `/skills/:author/:name` | 🟡 Partial | S087 / S096c routing / S096d1 hex | 缺：5-tab structure (overview/risk/versions/reviews/flags 現 4 tabs)；download sparkline；prototype 完整 metadata strip |
| 4 | Skills Hub Publish Step 1.html | `/publish` | ✅ Full | S086 / S096d4a navigate | — |
| 5 | Skills Hub Publish Step 2.html | `/publish/validate` | ❌ Missing | (none) | 整頁 not implemented；需 SSE event stream + 4-step stepper UI |
| 6 | Skills Hub Publish Flow.html (Step 3) | `/publish/review` | 🟡 Partial | S096d4a + S096d5a auto-poll | 缺：completed-event timeline；publish/withdraw 雙路徑 button per State A|B 邏輯 |
| 7 | Skills Hub Publish Failures.html | `/publish/failed` | ❌ Missing | (handled inline in PublishPage) | 整 page 不存在；當前 error inline display；需 dedicated /publish/failed?id route showing high-risk State B |
| 8 | Skills Hub Admin Review.html | `/admin/review` | ⏸ Blocked | (none) | post-MVP B6 backlog；需 admin role + review queue |
| 9 | Skills Hub Analytics.html | `/analytics` | ✅ Full | S088 / S096d1 hex | minor polish OK |
| 10 | Skills Hub My Skills.html | `/my-skills` | 🟡 Partial | S094a / S096d3 sparkline | 缺：rating column (0 system not impl)；open flags counter live data |
| 11 | Skills Hub Collection.html | `/collections` | 🟡 Stub | S096f1 stub | 缺：完整 aggregate / install / create endpoints / 2 domain events |
| 12 | Skills Hub Version Diff.html | `/skills/:author/:name/diff?from=&to=` | ❌ Missing | (none) | 整 page 不存在；需 backend diff endpoint + frontend side-by-side diff view |
| 13 | Skills Hub Request Board.html | `/requests` | 🟡 Stub | S096g1 stub | 缺：aggregate / voting / claim / 3 domain events / filter chips |
| 14 | Skills Hub Notifications.html | `/notifications` | 🟡 Stub | S096h1 stub | 缺：projection from domain_events / 4 mutation endpoints / WebSocket |
| 15 | Skills Hub Empty States.html | (4 inline contexts) | ✅ Full | S094c | 4 tones implemented |
| 16 | Skills Hub Docs.html | `/docs/your-first-skill` | 🟡 Partial | S094d | 缺：sidebar navigation 連結到其他 docs（目前 only `/docs/your-first-skill` 一頁；overview / spec / publishing / API 等其他 doc pages 未做） |

**Summary count**:
- ✅ Full ship: 4 (Landing / Publish Step 1 / Analytics / Empty States)
- 🟡 Partial / Stub ship: 8 (Homepage / Skill Detail / Publish Flow Step 3 / My Skills / Collection / Request Board / Notifications / Docs)
- ❌ Missing: 3 (Publish Step 2 / Publish Failures / Version Diff)
- ⏸ Blocked: 1 (Admin Review — PRD B6 backlog)

## §3 Sub-spec backlog plan

優先級分為 P0 (P1 SBE 阻塞 / 高 user value) / P1 (功能完整性) / P2 (polish)：

### P0 — Critical user-flow gap

| Spec | Title | Estimate | Source |
|------|-------|----------|--------|
| **S098a** | Publish Step 2 `/publish/validate` page (poll-based) | M (10) | prototype #5 |
| **S098b** | Publish Failures `/publish/failed` page | S (8) | prototype #7 |
| **S098c** | Version Diff `/skills/:author/:name/diff` page + backend endpoint | M (12) | prototype #12 |
| **S098g** | i18n 繁中化 audit — 所有 user-facing 英文字串改繁中 | M (10) | per CLAUDE.md「UI 語言: 繁體中文」原則 + user mid-tick directive |
| **S098h** | YourFirstSkillPage 配色 / 對比修復 — 卡片 / inputs / code blocks 在 dark theme 上 black-on-black | S (6) | user 截圖 mid-tick 比對 prototype #16 light theme 範本 |

#### S098g 範圍 — 違反 CLAUDE.md 規約 page 列表
- LandingPage Hero「The skills registry your team can actually trust」、stats labels（Skills published / Downloads · 30 days 等）、CTA「Browse the registry」/「Publish your first skill」、final CTA「Start sharing skills like libraries.」
- NotificationsPage 標題「Notifications」、CategoryDot aria-label
- IntentSummaryCard「Understood your intent」標籤
- YourFirstSkillPage Docs 整頁 mixed 英文 (intro / section heading 等)
- DocsLayout DocsSidebar group labels (Getting started / Reference / Publishing / API & webhooks)
- HomePage / SkillCard / 其他既有 partial 英文殘留 audit

評估 grep `[A-Z]\\w+\\s+[a-z]\\w+` 看 user-facing string 數量 → 估 ~60-80 字串，~6-8 file 改動。

### P1 — Feature completeness (stub→full upgrades)

| Spec | Title | Estimate | Source |
|------|-------|----------|--------|
| **S096f2** (existing) | Collections full feature (aggregate + install + create) | M (12) | prototype #11 |
| **S096g2** (existing) | Request Board full feature (aggregate + voting + claim) | M (12) | prototype #13 |
| **S096h2** (existing) | Notifications full projection from domain_events | M (12) | prototype #14 |

### P2 — Polish / partial-ship completeness

| Spec | Title | Estimate | Source |
|------|-------|----------|--------|
| **S098d** | Homepage 3-column grid + sort chips + risk filter sidebar | S (8) | prototype #2 |
| **S098e** | Skill Detail 5-tab full structure + download sparkline | S (8-9) | prototype #3 |
| **S098f** | Docs sidebar 4-group navigation (full IA) | M (10) | prototype #16 |

### Deferred

| Spec | Title | Reason |
|------|-------|--------|
| S094e Admin Review | (post-MVP) | PRD B6 backlog — auth + role required |
| S096e2 Onboarding | ⏸ blocked | prototype 缺 + 依賴 S096f2 |
| S096d6 publish events + SSE | low priority | poll-based S096d5a 已 cover 80% UX |

## §4 Sequencing recommendation

對 user value 優先：
1. **P0 first** (S098a/b/c) — completing P2 SBE「發佈流程」 + 新 P-未編號 「version diff」
2. **P1** (S096f2/g2/h2) — feature 從 stub 變 full
3. **P2** (S098d/e/f) — polish existing pages

≈ 60-70 pts 總工 to full v2 prototype parity.

## §5 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | Each P0/P1/P2 sub-spec ships | Roadmap row → ✅ + spec doc archived |
| AC-META-2 | Each sub-spec aligns 1:1 with prototype | spec §1 references corresponding HTML file |
| AC-META-3 | Frontend builds + tests stay green per sub-spec | regression check |

## §6 Sub-spec snapshots (light-weight; full /planning-spec when user picks)

### S098a — Publish Step 2 validate page (M / 10)
Route `/publish/validate?id={id}`. Poll `/skills/{id}` like S096d5a but 顯**event stream timeline**（per prototype #5 4-step stepper：Upload → Validate → Review → Live）。Auto-navigate `/publish/review?id=X` 當 risk_level 設值。Bridge between Step 1 upload + Step 3 review.

### S098b — Publish Failures page (S / 8)
Route `/publish/failed?id={id}`. Show failure detail per prototype #7 State A (frontmatter error: red, blocked) + State B (high risk: amber, submit-for-review)。從 PublishPage upload error path / risk_level=HIGH path navigate 進.

### S098c — Version Diff page (M / 12)
Route `/skills/:author/:name/diff?from={v1}&to={v2}`. Backend new `GET /api/v1/skills/{id}/diff?from=&to=` returns metadata diff + risk delta. Frontend side-by-side display per prototype #12.

### S098d — Homepage v2 polish (S / 8)
3-column grid (xl breakpoint)；sort chips (Popular / Newest / Risk-low)；risk filter sidebar with count breakdown.

### S098e — Skill Detail v2 polish (S / 8-9)
5-tab structure (Overview / Risk / Versions / Reviews / Flags) — current 4 tabs (overview/files/versions/risk)；加 Reviews + Flags tabs；hero strip 加 download sparkline (reuse S096d3 component).

### S098f — Docs IA expansion (M / 10)
Add 3-4 docs pages (Overview / SKILL.md spec / Frontmatter fields / Bundle structure / Risk tiers) per prototype #16 sidebar IA. Each page minimal markdown render.

### S098h — YourFirstSkillPage 配色對比修復 (S / 6)
**症狀**：dark theme 下卡片 / required-field labels / Optional fields chips / code block bg 過接近頁面 `#08080A`，文字幾乎看不見（user 截圖確認）。
**對比 prototype #16**：prototype 為 light theme 配 white card + 細邊；當前 v2 dark 套用後直接拿 prototype CSS 但 token 沒換 → 元件 bg 都是 near-black。
**修復方向**：
- 卡片 bg 改為 v2 dark theme `--card` token (e.g., `#13131A` 比 `--bg` 提亮 +5%)；border 用 `--ink/8`
- inputs / chips / code blocks 同套 `--card` 或 `--surface-elevated`
- code block 文字色改 `--ink-secondary` 配色保持 syntax highlight 可讀
**out of scope**：内容文字本身（已 mixed 英文，由 S098g 處理）
**file plan**：`frontend/src/pages/YourFirstSkillPage.tsx` 或對應 docs CSS module / Tailwind class 替換

## §7 Result

待 sub-specs ship 累積後填。

**Plan summary**:
- 8 new sub-specs (S098a–h) + 3 existing (S096f2/g2/h2) = 11 sub-specs
- Estimated ~85 pts total
- ⏸ 2 deferred (Admin Review post-MVP / Onboarding blocked)
- P0 mid-tick adds: S098g (i18n) + S098h (配色對比) per 連續 user directives
