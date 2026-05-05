# S096 META — UI v2 Full Redesign (dark theme + new pages + route schema)

> **Status**: shipped ✅ (closed 2026-05-05)
> **Type**: META (8 sub-specs sequential ship; this file = planning doc only)
> **Estimate total**: ~80-100 pts (XL — mandatory split per estimation-scale.md ≥17)
> **Source**: `docs/grimo/ui/prototype/Skills Hub *.html` (16 dark-theme mockups) + Engineering Handoff (user-supplied, embedded §1-§10) + replaces old snake_case prototypes that S085-S088 + S094 used as reference

## §1 Goal

完整 v2 frontend redesign — 從 light theme（warm off-white `#FFFFFF`）遷移至 dark theme（`#08080A` 黑底）+ 加入 6 個 NEW pages（Landing / Collections / Version Diff / Request Board / Notifications / Onboarding）+ route schema 變更（`/skills/:id` UUID → `/skills/:author/:name` canonical with :id alias）。

User 偏好 prototype 內 v2 設計勝過既有 frontend，明確指示 Option A「Big META full v2」— 接受 S085-S088 + S094 sub-specs 部分 work 將在 S096-04 中 redesign 重做（trade-off explicit）。

```
[Phase 4 current]                    [S096 v2 target]
HomePage warm-white                  → /browse Skills Hub Homepage dark-theme
PublishPage 3-step warm-white        → /publish/{upload,validate,review} dark-theme
SkillDetailPage warm-white           → /skills/:author/:name dark-theme
AnalyticsPage warm-white             → /analytics dark-theme
MySkillsPage warm-white (S094a)      → /my-skills dark-theme
DocsLayout warm-white (S094d)        → /docs/:slug dark-theme
EmptyState 4 tones (S094c)           → 4 tones with dark theme tokens
                                     + 6 NEW pages: Landing / Collections /
                                       Version Diff / Request Board /
                                       Notifications / Onboarding
```

## §2 Approach

### §2.1 Sub-spec sequencing (8 specs total)

「META 先 / foundation 先 / 共享 component 抽取先 / size 小先」原則：

| # | Sub-spec ID | Title | Estimate | Backend impact | Depends on |
|---|-------------|-------|----------|----------------|-----------|
| 1 | **S096a** | ADR-003 + PRD update | XS (4) | none (docs) | — |
| 2 | **S096b** | DESIGN.md v2 + global theme migration foundation | M (12) | none | S096a |
| 3 | **S096c** | Routing schema + Risk tier 4-level (absorbs S095) | M (12) | new endpoint + RiskLevel enum | S096a |
| 4 | **S096d** | Existing pages v2 refresh (multi-page batch) | L (15-16) | minor (per-skill stats endpoints) | S096b + S096c |
| 5 | **S096e** | Landing + Onboarding | M (12) | new endpoints (stats + user prefs) | S096b |
| 6 | **S096f** | Collections | M (12) | new aggregate + 3 endpoints | S096b + S096c |
| 7 | **S096g** | Request Board | M (12) | new aggregate + 4 endpoints | S096b |
| 8 | **S096h** | Notifications + Version Diff | M (12) | domain_events projection + 2 endpoints | S096b + S096c |

Total: 4+12+12+16+12+12+12+12 = **92 pts** (vs estimate 80-100)

**Order rationale**:
- S096a docs-only first → architectural decisions locked，後續 sub-specs 對齊
- S096b theme foundation 先：global CSS migration 確保既有 page 一夜變 dark-theme（醜但功能正確）；後續 per-page refresh polish
- S096c routing + risk tier 同 spec：兩者都動 backend `Skill` aggregate + RiskBadge 元件，cohesion 高
- S096d existing pages refresh 在 b+c 之後：foundation 已 dark，只 polish per-page styling
- S096e/f/g/h NEW pages 並行可能（依 user value 排）：Landing + Onboarding（user 入口）→ Collections + Request Board + Notifications（社群功能）

### §2.2 Decisions locked (per user grill 2026-05-02)

| Q | Decision | Rationale |
|---|----------|-----------|
| Q1 PRD/ADR sequencing | a — sub-spec 1 = PRD + ADR-003 docs-only gate | 避免後續 sub-specs 漂移；scope 鎖定 |
| Q2 Routing BC | a — `/skills/:id` 永久 alias → both routes resolve same handler | 既有 bookmark / API caller 不破，新 canonical 並行 |
| Q3 S095 (Risk tier NONE) | a — Absorb into S096c | RiskBadge 整個 redesign with NONE tier 一次到位，避免 ship-then-redesign |
| Q4 Theme strategy | a — Hard cutover (no switcher) | switcher 增 1 sub-spec + 維護 2× cost；future polish 可加 |

### §2.3 Trade-offs accepted by user

User 已在 Option A 對話確認接受：

- S085-S088 + S094 sub-specs 的 light-theme work **大半 redesign 為 dark theme**（particularly S096d existing pages refresh）— 約 30-40% of recent ship work 重做
- S094c EmptyState 4 tones 元件 retained**但 tokens 改 dark**（structure intact）
- S094d Docs page retained**但 theme 改 dark**（content + IA 不變）
- S094a MySkills retained**但 theme + ?author= filter 路徑統一新 routing schema**

捨棄方案（Option B/C/D 均拒絕）：
- B（NEW pages first defer theme）: 兩 theme 並存維護 cost 高，user 不接受
- C（Theme migration first 不 add new pages）: User value 偏低
- D（PRD update 先做完整 RFC 流程）: scope-creep prevention 但已 a 選項涵蓋

### §2.4 Risk register

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| 8 sub-specs ship 期間發現 prototype 與 Engineering Handoff data contract 不一致 | Med | 每 sub-spec §3 AC 對 prototype HTML + handoff 雙 reference；發現 mismatch 屬 spec design issue 升 ADR |
| Backend WebSocket 引入（Notifications）為 Spring Boot 4 新 surface | Med | Spring Boot 4 supports `spring-boot-starter-websocket`；existing Spring Modulith 7 modules 加新 `notification` module 對齊既有 architecture |
| 8 sub-specs 過程中 user 提其他需求 | High | Per Finish-Current-First / stacked-request rule；queue 為 backlog row，不打斷當前 sub-spec |
| Dark theme 對 既有 frontend tests (vitest assert 顏色) 大量 break | Med | tests 對齊 DOM 結構而非具體 hex；S096b foundation ship 時跑 full test 看 break 範圍 |

## §3 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | 8 sub-specs all shipped | spec-roadmap 顯 8 個 ✅ + archive 8 個 spec doc |
| AC-META-2 | DESIGN.md v2 tokens reflect handoff §7 | 16 colors + bg/ink layered system + accent purple `#7F77DD` 等 |
| AC-META-3 | All existing pages render in dark theme | 既有 9 pages（Home/Detail/Publish×3/Analytics/MySkills/Docs/Empty）全 dark |
| AC-META-4 | 6 NEW pages routes accessible | /landing /collections /skills/:author/:name/diff /requests /notifications /onboarding |
| AC-META-5 | Route schema migration backwards-compatible | `/skills/:id` 仍 200（resolves to same handler），新 `/skills/:author/:name` canonical |
| AC-META-6 | Risk tier 4-level | NONE / LOW / MEDIUM / HIGH 全可顯；既有 LOW 經 SQL migration 部分 → NONE |
| AC-META-7 | PRD reflects new feature scope | PRD includes Collections / Request Board / Notifications descriptions + SBE |
| AC-META-8 | ADR-003 ratified | docs/grimo/adr/ADR-003-route-schema-author-name.md exists |

## §4 Sub-spec snapshots

### S096a — ADR-003 + PRD update (XS / 4)

**Scope**: docs-only gate; no code.

- ADR-003 `route-schema-author-name.md`: 規範 `/skills/:author/:name` 為 canonical，`/skills/:id` 為 永久 alias；backend `findByAuthorAndName` 查 `(author, name)` 組合（既有 `name` UNIQUE per-org）
- PRD 加 P7/P8/P9 sections: Collections（curated bundles）/ Request Board（社群需求）/ Notifications（事件 feed + bell badge）
- PRD 更新 D-decisions table 加新 features 對應 design rationale
- glossary.md 加新 terms（collection / request / notification / fulfillment）

**AC**: ADR-003 ratified + PRD 7 sections updated + glossary 4 entries

### S096b — DESIGN.md v2 + global theme migration foundation (M / 12)

**Scope**: token system swap; auto-render existing pages in dark theme even before per-page polish.

- 完整 replace `DESIGN.md` 為 dark token system（per handoff §7：`--bg-#08080A` 4-tier surface + `--ink` 3-tier text + accent purple `#7F77DD` etc.）
- `frontend/src/index.css` global CSS 改用新 tokens；既有 inline-style hex 仍對齊（per S087/S088 pattern 不破）
- 既有 9 pages 視覺自動 update（some hex 仍 inline 需 sub-spec d 處理）
- BorderBeam tokens 對齊 v2 conic-gradient（per handoff §8 — 5-color stops vs current 2-color）
- vitest tests 大概率 break — DOM-shape assertions OK，hex-string assertions 需更新；估 5-10 個 test patch
- backend 0 changes

**AC**: DESIGN.md v2 ratified + global CSS swap + frontend tests pass after patches

### S096c — Routing schema + Risk tier 4-level (M / 12; absorbs S095)

**Scope**: backend route + enum + frontend RiskBadge v2.

- Backend: 加 `GET /api/v1/skills/{author}/{name}` endpoint resolves to same `Skill` aggregate；既有 `/skills/{id}` 不動（alias）
- Backend: `RiskLevel` enum 加 `NONE` value；`ScanOrchestrator.classifyTier` 加 NONE 條件 (`0 findings + no scripts + no allowed-tools`)
- Backend: Flyway SQL migration 既有 87 LOW 部分 → NONE
- Frontend: routing `/skills/:author/:name` 為 canonical；既有 `/skills/:id` 仍 valid（route both to SkillDetailPage）
- Frontend: RiskBadge 4-tier with dark theme tokens（NONE green / LOW blue / MEDIUM amber / HIGH red per handoff §7）
- Tests: backend +5 RiskLevel tests + 3 routing tests；frontend RiskBadge 4-tier render

**AC**: 4-tier risk render + routing both schemes 200 + migration idempotent

### S096d — Existing pages v2 refresh (L / 15-16)

**Scope**: 9 pages re-styled per pascalcase prototypes.

- HomePage → `Skills Hub Homepage.html`
- SkillDetailPage → `Skills Hub Skill Detail.html`
- PublishPage 3-step refactor: split into `/publish/upload`, `/publish/validate`, `/publish/review` per `Skills Hub Publish Step 1/2/Flow.html`
- Publish failures → `Skills Hub Publish Failures.html`（既有 callout 改 dark theme）
- AnalyticsPage → `Skills Hub Analytics.html`
- MySkillsPage → `Skills Hub My Skills.html`
- DocsLayout / YourFirstSkillPage → `Skills Hub Docs.html`
- EmptyState component 4 tones tokens dark-theme

**AC**: 9 pages 1:1 對齊 prototype；vitest tests pass after assert updates

**Note**: 此 sub-spec L 接近 XL 邊界；於 /planning-tasks 階段可能需 split 為 d1/d2/d3。

### S096e — Landing + Onboarding (M / 12)

**Scope**: public landing + first-time user wizard.

- Landing route `/`（unauthenticated）→ `Skills Hub Landing.html`；marketing-tier hero + 4 stats band + 6 sample cards + dual-path CTAs
- Backend: `GET /api/v1/stats` (public, aggregate stats — total skills / downloads / publishers / risk distribution)
- Auth gate: authenticated `/` redirect to `/browse`；unauthenticated to Landing
- Onboarding `/onboarding`（authenticated, first-time）→ 4-step wizard per `Skills Hub Onboarding.html`
- Backend: user preferences endpoint（POST /me/preferences {role, tools, interests}）
- Onboarding-complete flag: set on user record, redirect to /browse on subsequent login

**AC**: /landing public + /onboarding 4-step + redirect rules + user prefs persist

### S096f — Collections (M / 12)

**Scope**: curated skill bundles.

- Backend: new `Collection` aggregate + `collection_skills` join table (skillId, version)
- Endpoints: `GET /api/v1/collections` / `POST /api/v1/collections` / `POST /api/v1/collections/:id/install` / `GET /api/v1/collections/:id`
- Frontend: `/collections` list page + `/collections/:id` detail page per `Skills Hub Collection.html`
- Domain event: `CollectionCreated` / `CollectionInstalled`

**AC**: 4 endpoints + frontend list + install flow

### S096g — Request Board (M / 12)

**Scope**: 社群需求看板.

- Backend: new `Request` aggregate + `request_votes` join table
- Endpoints: `GET /api/v1/requests` / `POST /api/v1/requests` / `POST /api/v1/requests/:id/vote` / `POST /api/v1/requests/:id/claim`
- Frontend: `/requests` page per `Skills Hub Request Board.html`
- Domain event: `RequestPosted` / `RequestVoted` / `RequestFulfilled`

**AC**: 4 endpoints + frontend list + voting + claim

### S096h — Notifications + Version Diff (M / 12)

**Scope**: bell badge + diff view.

- Backend: `notifications` projection from `domain_events`（per-user filter by subscription）
- Endpoints: `GET /api/v1/notifications` / `POST /api/v1/notifications/read-all` / `GET /api/v1/notifications/unread-count` / `GET /api/v1/skills/{author}/{name}/diff?from=&to=`
- Frontend: `/notifications` page per `Skills Hub Notifications.html` + bell badge in AppShell（poll 30s OR WebSocket future polish）
- Frontend: `/skills/:author/:name/diff` page per `Skills Hub Version Diff.html` showing risk delta + frontmatter diff + file list diff

**AC**: notification list + diff view rendered correctly

## §5 Cross-cutting concerns

### §5.1 Spring Modulith new module

`notification` module（per S096h）— 加進 7-module set 為 8th module；對齊 既有 architecture（shared / skill / security / search / analytics / storage / audit / **notification**）。

### §5.2 BorderBeam usage rules (handoff §8)

5-color conic-gradient（vs current 2-color light-theme version）— S096b 重新 implement BeamFrame 對齊。Beam usage 嚴格限制：
- 1 hero search bar
- 1 primary CTA per page
- FileDropZone (S086 publish step 1 already has)
- Featured / top-match skill card

S096d existing pages refresh 時 audit 既有 BeamFrame 使用點；多餘的（如 S094c EmptyState SeedTone primary action）需移除維持 1-per-page 規則。

### §5.3 Domain event naming alignment

Handoff §4 列 10 events，與當前 backend `domain_events.event_type` 列舉對齊：

| Handoff event | Backend equivalent | Action |
|---------------|-------------------|--------|
| SkillCreated | SkillCreated | ✓ matches |
| SkillBundleExtracted | (not yet) | NEW S096d publish step 2 emit |
| SkillFrontmatterValidated | (not yet) | NEW S096d emit |
| SkillRiskScanStarted | (not yet) | NEW S096d emit |
| SkillRiskAssessed | SkillRiskAssessed | ✓ matches |
| SkillVersionPublished | SkillVersionPublished | ✓ matches |
| SkillDownloaded | SkillDownloaded | ✓ matches |
| SkillFlagged | SkillFlagged | ✓ matches |
| SkillReviewCompleted | (admin scope post-MVP) | S094e（deferred） |
| SkillVersionDeprecated | (not yet) | NEW future spec |

S096d 加 3 個 publish-pipeline events（BundleExtracted / FrontmatterValidated / RiskScanStarted）— per handoff §2.5 Validation 期間 SSE event stream 需要這些。

## §6 Acceptance verification

Per `qa-strategy.md` 標準 pipeline；每 sub-spec 自己 §3 AC verify command。META 層 AC 透過 sub-spec ship cumulative 達成。

## §7 Result

**Closed 2026-05-05.** 核心目標全部達成：

- AC-META-2 ✅ DESIGN.md v2 dark token system（S096b）
- AC-META-3 ✅ 全部既有頁面 dark theme（S096b + d1/d2/d3/d4a/d5a）
- AC-META-4 ✅ 5/6 新頁面上線（/browse / /collections / /skills/:id/diff / /requests / /notifications）；`/onboarding` 主動取消（見下）
- AC-META-5 ✅ `/skills/:id` alias + `/skills/:author/:name` canonical 並行（S096c）
- AC-META-6 ✅ Risk tier 4-level NONE/LOW/MEDIUM/HIGH（S096c + RiskBadge）
- AC-META-7 ✅ PRD P7/P8/P9 + glossary（S096a）
- AC-META-8 ✅ ADR-003 ratified（S096a）

**S096e2 Onboarding — 主動取消（2026-05-05）：** prototype HTML 缺失（16 mockups 未含 Onboarding），MVP 階段不補做；個人化首頁功能暫不需要。若未來有需求以新 spec 啟動。

**S096f3 Collections risk filter** 仍在 roadmap 為 📋 planned（polish level；不影響 META 關閉）。

---

## §8 Open questions for /planning-tasks per sub-spec

1. **S096b**: BorderBeam impl rewrite 是否需要 POC 驗 conic-gradient 5-color performance（vs current 2-color）？
2. **S096c**: `findByAuthorAndName` SQL — 既有 `name` UNIQUE constraint 需擴 `(author, name)` UNIQUE 還是只 `name`？影響：跨 author 不能用 `name=docker-helper` 兩次（既有 1 author 1 name）
3. **S096e**: Landing 為 public route — 既有 `LabSecurityFilter` permitAll 是否覆蓋 unauthenticated `/`？
4. **S096f/g/h**: 3 個 NEW aggregates 引入需 ADR for new domain modules？或 S096a ADR-003 涵蓋？

待 sub-spec ship 階段釐清。
