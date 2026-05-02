# S100 META — Page-by-Page Data Authenticity Audit

> **Status**: ✅ shipped (META complete, 5/5 sub-specs shipped 2026-05-02)
> **Type**: META audit + impl roadmap consolidation
> **Estimate**: 5 sub-specs ≈ 12 pts (all XS); supplementary backlog 9 sub-specs queued in S096/S098
> **Triggered by**: 2026-05-02 user directive — 「自己看頁面規劃 spec 實作，才不會都是假頁面」

## §1 Goal

對每個 user-facing page 做 explicit「資料源真實度」分類，確認：
1. ✅ Real — page 顯示真實 DB / projection 數據
2. 🟡 Stub — backend 存在但回 [] 或 0（後端 stub state；frontend EmptyState 處理 OK）
3. ❌ Fake — frontend 硬寫死數據 / mock object（**不可接受**）
4. ⏸ Inert — pure markup pages 不取數據（docs / static info）

不可接受 fake pages 開新 sub-spec 接 backend；stub backends 確認 sub-spec 已存在。

## §2 Page-by-Page Audit Matrix

| # | Route | Page | Data Source | State | Sub-spec |
|---|-------|------|-------------|-------|----------|
| 1 | `/` | LandingPage | fetchPublicStats() + fetchSkills(size=6) | ✅ Real | — |
| 2 | `/browse` | HomePage | fetchSkills + fetchCategories | ✅ Real | — |
| 3 | `/skills/:id` | SkillDetailPage | fetchSkillById + useVersions + useSkillFiles + fetchSkillStats(30d) | ✅ Real | Reviews/Flags tabs stub: S098e2/e3 |
| 4 | `/skills/:author/:name` | SkillDetailPage (canonical) | useSkillByAuthorAndName | ✅ Real | — |
| 5 | `/skills/:id/diff` | VersionDiffPage | fetchSkillById + fetchVersions（metadata only） | 🟡 Frontend-only | Backend `/diff`: S098c2/c3 |
| 6 | `/publish` | PublishPage | POST /skills/upload | ✅ Real | Text mode: S099b |
| 7 | `/publish/validate?id=` | PublishValidatePage | fetchSkillById（2s poll） | ✅ Real | upload-strip metadata: S099a3-2 |
| 8 | `/publish/review?id=` | PublishReviewPage | fetchSkillById（2s poll until risk） | ✅ Real | — |
| 9 | `/publish/failed?state=&msg=` | PublishFailedPage | query string only | ✅ Stateless | structured findings: S098b3-2 |
| 10 | `/analytics` | AnalyticsPage | fetchOverview()（real DB query） | ✅ Real | Top 10 link: S100a ✅ shipped v3.2.6 |
| 11 | `/my-skills` | MySkillsPage | fetchSkills(?author=) | ✅ Real | — |
| 12 | `/search` | SearchResultsPage | fetchSemanticSearch + fallback fetchSkills | ✅ Real | — |
| 13 | `/notifications` | NotificationsPage | fetchNotifications() returns [] | 🟡 Stub | S096h2 (full projection) |
| 14 | `/collections` | CollectionsPage | fetchCollections() returns [] | 🟡 Stub | S096f2 (aggregate + endpoints) |
| 15 | `/requests` | RequestBoardPage | fetchRequests() returns [] | 🟡 Stub | S096g2 (aggregate + voting) |
| 16 | AppShell bell badge | (cross-page) | fetchUnreadCount() returns {count:0} | 🟡 Stub | S096h2 |
| 17 | `/docs/your-first-skill` | YourFirstSkillPage | (none) | ⏸ Inert | — |
| 18 | `/docs/overview` | OverviewPage | (none) | ⏸ Inert | — |
| 19 | `/docs/risk-tiers` | RiskTiersPage | (none) | ⏸ Inert | — |
| 20 | `/docs/skill-md-spec` | SkillMdSpecPage | (none) | ⏸ Inert | — |
| 21 | `/docs/frontmatter` | FrontmatterPage | (none) | ⏸ Inert | — |
| 22 | `/docs/bundle` | BundleStructurePage | (none) | ⏸ Inert | — |
| 23 | `/docs/upload-validate` | UploadValidatePage | (none) | ⏸ Inert | — |
| 24 | `/docs/versioning` | VersioningPage | (none) | ⏸ Inert | — |
| 25 | `/docs/semantic-search` | SemanticSearchPage | (none) | ⏸ Inert | — |
| 26 | `/docs/rest-api` | RestApiPage | (none) | ⏸ Inert | — |
| 27 | `/docs/event-payload` | EventPayloadPage | (none) | ⏸ Inert | — |

**Summary count**:
- ✅ Real (12 pages) — 都有真實 backend 數據；無 fake
- 🟡 Stub backend (3 pages + 1 cross-page) — backend 回 [] 或 0；frontend EmptyState 處理；real impl 待 sub-spec
- ⏸ Inert (11 docs pages) — 純 markup，不取數據

**❌ Fake pages**: **零** — 沒有 frontend 硬寫死數據的 page。

## §3 Conclusion

**全站無 fake page**。User 看到的 LandingPage 數字 (103 / 165 / 78% / 33) 是真實 PostgreSQL DB query；AnalyticsPage Top 10 也是真 download_events table aggregate。

唯一「不真實」感來源是 3 個 community module stubs（Notifications / Collections / Requests）— 這些後端 controller 確實存在（GET endpoint）但實作為 `return []`（per S096f1/g1/h1 ship 時的 stub-and-defer pattern），等 S096f2/g2/h2 做 aggregate 完整功能。

## §4 Action Items

### Immediate ship（已完成）

- ✅ **S100a** AnalyticsPage Top 10 link — backend OverviewStats.TopSkill +author, frontend wrap Link → `/skills/:author/:name` (commit 57b84ad, v3.2.6)

### Existing backlog（指向已 queued sub-specs）

| Page Issue | Sub-spec | Estimate |
|------------|----------|----------|
| Reviews tab on SkillDetailPage | S098e2 | M(8) |
| Flags tab on SkillDetailPage | S098e3 | M(7) |
| Notifications real projection | S096h2 | M(10-12) |
| Collections aggregate + endpoints | S096f2 | M(10-12) |
| Request Board aggregate + voting | S096g2 | M(10-12) |
| Backend /diff endpoint | S098c2 | M(8) |
| File content line-level diff | S098c3 | L(12) |
| Validation breakdown structured payload | S098b3-2 | M(6) |
| upload-strip backend bundle-info | S099a3-2 | XS(2) |

### Newly identified gaps（本 audit 發現）

| Gap | Why | Sub-spec |
|-----|-----|----------|
| HomePage 「正在使用中」section heading 對應 backend 實作 | Section 顯 popular skills；目前 fetchSkills size=6 預設 backend order — 但是否真為「正在使用中」(active usage) 不明，可能應對齊 last-30-days-download-rank 而非 createdAt | **S100b** S(5) — backend 加 `?sort=hot` query param to fetchSkills，依 30d download count 排序 |
| MySkillsPage 顯 author 自己的 skill — 但要登入後拿 `/me`.userId | 目前 `?author=...` query 是 user 手寫；應走 auth-based filter | **S100c** S(5) — MySkillsPage 改 fetch `/me` → 派生 author → fetch自己的 skills；MVP 階段 mock auth 期間沿用 query |
| 各 page error state 的 fallback UX 不一致 | 有些是 inline error callout，有些是 redirect | **S100d** XS(3) — 統一 error UX guideline doc + 抽 ErrorState component |

## §5 Sub-spec Roadmap Updates

新增 3 行至 spec-roadmap.md（M93/Phase 5 後）：
- S100a ✅ shipped v3.2.6
- S100b S(5) HomePage hot-sort backend
- S100c S(5) MySkillsPage auth-based filter
- S100d XS(3) ErrorState 抽 component + UX guideline

## §6 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | 每 page 有 explicit data-source state | ✅ §2 audit table 完整 |
| AC-META-2 | 0 ❌ fake pages confirmed | ✅ §3 結論 |
| AC-META-3 | Stub backends 都有對應 sub-spec | ✅ §4 mapping table |

## §7 Result

**META 結論**：全站 27 pages，0 ❌ fake — audit 假設成立。3 個 stub backends（Notifications / Collections / Requests）走 EmptyState graceful path，frontend 無硬寫死資料。

**Sub-specs shipped (5/5)**：

| Sub-spec | Page Issue | Estimate | Shipped | Highlights |
|----------|------------|----------|---------|-----------|
| S100a | AnalyticsPage Top 10 link | XS(2) | v3.2.6 | backend OverviewStats.TopSkill +author；frontend wrap Link → /skills/:author/:name canonical route per ADR-003 |
| S100b | HomePage server-side sort | XS(3) trim from S(5) | v3.3.5 | backend SkillQueryService SORTABLE_PROPERTIES +riskLevel；frontend fetchSkills 跨頁全域 sort；defer hot-30d-rank polish |
| S100c | PublishPage author prefill | XS(2) reframed | v3.3.7 | useMe + useEffect prefill；authorTouched state 防 overwrite。Reframed 因 MySkillsPage 早已用 useMe (S094a) |
| S100d | ErrorState component | XS(3) | v3.3.2 | inline/centered variants + danger-soft palette；6 ACs tests；AnalyticsPage/PublishPage migrations |
| S100e | AnalyticsPage Top 10 defensive guard | XS(2) | v3.4.1 | typeof + length + "undefined" 字串三重 guard；row 退回非 link `<div>`；validates Spec-Only-Handoff principle (user 提需求 → agent 寫 spec → cron tick implement) |

**Cumulative**: 12 pts shipped over v3.2.6 → v3.4.1（5 versions, 1 day calendar）

**Existing backlog（觸發 audit 但未本 META 範疇）**：S096f2/g2/h2 (community modules) + S098c2/c3/b3-2/e2/e3 (SkillDetail tabs/diff) — 9 sub-specs 持續 queued in roadmap，由各自 META 領動。

**Lessons learned**:
- **Audit-first 設計奏效**：先掃 27 pages、確認 0 fake，再 spawn fix-spec — 避免「先寫實作、後發現實際無 bug」的 wasted work（e.g., S100c 經 audit 發現 MySkillsPage 已有 useMe，省 reframe S100c 為 PublishPage 真正 gap）
- **XS estimate 蹭 META 速度**：5 sub-specs 全 XS(2-3)，1 calendar day 全收 — META 應傾向碎片化 sub-spec，避免 M+ block 整 META timeline
- **Defensive sibling pattern (S100a → S100e)**：feature 加欄位（S100a backend）+ frontend 同期 ship 不夠；stale runtime risk 真實存在；下次設計 backend payload 變更類 spec，spec 內就應加 frontend defensive guard 條目，不另開 fix-spec
- **Stub-and-defer pattern 透明化價值**：community module stubs (Notifications/Collections/Requests) 在 audit table 明確標 🟡 + 對應 sub-spec，比硬塞 fake data 後續清理省工
