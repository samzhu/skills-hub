# S096g1 — Request Board Read-Only Stub (S096 META 6a/8)

> **Status**: shipped
> **Type**: stub backend endpoint + frontend page placeholder + nav entry
> **Estimate**: M(12) → trim 至 XS(4-5) per scope budgeting / 20m wall
> **Source**: PRD §P8 Request Board + Engineering Handoff §2.13 + prototype `Skills Hub Request Board.html`

## §1 Goal

Skills Hub 缺 community needs feedback loop — 使用者沒找到合適 skill 時無法 broadcast 需求。完整 S096g 含 Request aggregate + voting + claim + 4 endpoints + domain events，scope M(12)。

本 stub 先 ship 「nav entry + 空 list page」讓 feature 對外 visible，正式 aggregate / voting / claim 留 S096g2。

對齊 Engineering Handoff §10「Disable, don't hide, blocked actions」：「發起新需求」 button 顯但 disabled with tooltip「即將開放」— 不隱藏，告訴 user 流程存在但暫未啟用。

## §2 Approach

### §2.1 Trim from M(12) → XS(5)

**Defer S096g2**:
- `Request` aggregate domain class
- `request_votes` join table + Flyway migration
- `POST /api/v1/requests` (create)
- `POST /api/v1/requests/:id/vote` (upvote)
- `POST /api/v1/requests/:id/claim` (author 認領)
- 3 domain events: `RequestPosted` / `RequestVoted` / `RequestFulfilled`
- Filter chips (All / High / Medium / Mine)

**Ship in g1**:
- Backend: `GET /api/v1/requests` returning `[]` stub
- Frontend: `/requests` route + `RequestBoardPage` with EmptyState
- AppShell 「需求」 nav link
- New `community/` package（Modulith new module pre-registration; `@ApplicationModule` 標註留 g2）

### §2.2 Modulith new module placement

新 package `backend/src/main/java/io/github/samzhu/skillshub/community/` 為 future 8th Modulith module（per S096 META §5.1 mention `notification` 模組，現再加 community 為 9th）。

S096g1 stub 不加 `@ApplicationModule` 標註 — root-level module default 與既有 7 modules 並列。S096g2 ship 時加 module registration + 邊界檢查。

### §2.3 S096e2 blocking note

S096e2 Onboarding wizard 同時 mark ⏸ blocked，原因：
- prototype 16 mockups 中**無 Onboarding HTML**（Engineering Handoff §2.14 描述但未交設計稿）
- Step 4 「install starter pack」依賴未 ship 的 S096f Collections aggregate
- 兩個 dependency 滿足前先 mark ⏸，避免 cron tick pick 時無 design source 摸黑

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /api/v1/requests` | 200 + `[]` (empty list stub) |
| AC-2 | Frontend `/requests` route | renders RequestBoardPage |
| AC-3 | RequestBoardPage 0 results | EmptyState invite tone「目前還沒人發起需求」 |
| AC-4 | RequestBoardPage with data | rows show vote count + title + status pill + date |
| AC-5 | AppShell 「需求」 nav link | links to `/requests`, highlights when on it |
| AC-6 | 「發起新需求」 button | disabled with tooltip explaining S096g2 status |
| AC-7 | Tests 28/28 PASS | regression check |
| AC-8 | Build ≤ 400KB JS | budget |
| AC-9 | Backend compileJava | success (no Modulith violation) |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/community/
└── RequestController.java       ← NEW (1 endpoint stub + RequestSummary record)

frontend/src/
├── api/skills.ts                ← + fetchRequests + SkillRequest interface
├── pages/RequestBoardPage.tsx   ← NEW (~85 LOC; EmptyState + RequestRow + StatusPill)
├── App.tsx                      ← + /requests route
└── components/AppShell.tsx      ← + 需求 nav link

docs/grimo/specs/spec-roadmap.md
- S096e2 → ⏸ blocked-on-prototype-and-dep
- S096g → S096g1 ✅ + S096g2 📋
```

## §5 Test plan

- `./gradlew compileJava` ✓
- `npm test` — 28/28 PASS
- `npm run build` — JS ≤ 400KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/requests` → `[]`
  - 瀏覽器 `/requests` → RequestBoardPage 顯 EmptyState

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓
- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 392.56 → 395.66KB (+3.10KB；RequestBoardPage + StatusPill helper)
- **CSS bundle**: 37.80 → 37.83KB (+0.03KB)
- **Build time**: 177ms（無 regression）
- **Files touched**: 5 (1 backend + 4 frontend) + 1 spec doc
- **AC coverage**:
  - AC-1~6 impl ✓ (manual smoke pending live restart)
  - AC-7 28/28 ✓
  - AC-8 395.66 < 400 ✓
  - AC-9 compileJava ✓

ship as **v2.80.0** (M90g1 / S096 META sub-spec 6a/8)。

## §8 Notes for downstream sub-specs

- **S096g2 (defer)**: full Request aggregate + voting + claim + 4 endpoints + 3 domain events + Modulith @ApplicationModule registration for `community` package
- **S096e2 (blocked)**: Onboarding wizard — needs prototype HTML + S096f Collections dep ship first
- **Live :8080 caveat**: backend 仍跑 ship 前舊 code；`/requests` page 在 live 顯 fetch error；S093 graceful restart 後即正常
