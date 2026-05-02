# S096f1 — Collections Read-Only Stub (S096 META 6b/8)

> **Status**: shipped
> **Type**: stub backend endpoint + frontend page placeholder + nav entry
> **Estimate**: M(12) → trim 至 XS(5)
> **Source**: PRD §P7 + Engineering Handoff §2.11 + prototype `Skills Hub Collection.html`

## §1 Goal

Skills Hub 缺 curated bundle 機制 — 使用者只能 1-by-1 install skill。完整 S096f 含 Collection aggregate + install + create + 4 endpoints + 2 domain events，scope M(12)。

本 stub 先 ship 「nav entry + 空 list page」讓 feature 對外 visible，正式 aggregate / install / create 留 S096f2。

模式同 S096g1：disabled 「建立集合」 CTA + EmptyState invite tone「目前還沒人建立集合」。

## §2 Approach

### §2.1 Trim from M(12) → XS(5)

**Defer S096f2**:
- `Collection` aggregate domain class
- `collection_skills` join table + Flyway migration
- `POST /api/v1/collections` (create) + `POST /api/v1/collections/:id/install` + `GET /api/v1/collections/:id` (single)
- 2 domain events: `CollectionCreated` / `CollectionInstalled`

**Ship in f1**:
- Backend: `GET /api/v1/collections` returning `[]` stub
- Frontend: `/collections` route + `CollectionsPage` with EmptyState
- AppShell 「集合」 nav link
- `community/CollectionController` shares package with `RequestController` (S096g1)

### §2.2 community module pre-aggregation

Both Collections (S096f) + Requests (S096g) 屬 community feature 群組；S096f1 + S096g1 共用 `community/` package（Modulith pre-aggregation）。S096f2 + S096g2 ship 後統一加 `@ApplicationModule(displayName = "Community")` + `package-info.java` 正式註冊邊界。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /api/v1/collections` | 200 + `[]` (empty list stub) |
| AC-2 | Frontend `/collections` route | renders CollectionsPage |
| AC-3 | CollectionsPage 0 results | EmptyState invite tone「目前還沒人建立集合」 |
| AC-4 | CollectionsPage with data | grid card layout: name + category + description + skill count + installs |
| AC-5 | AppShell 「集合」 nav link | links to `/collections`, highlights when on it |
| AC-6 | 「建立集合」 button | disabled with tooltip explaining S096f2 status |
| AC-7 | Tests 28/28 PASS | regression check |
| AC-8 | Build ≤ 405KB JS | budget |
| AC-9 | Backend compileJava | success |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/community/
└── CollectionController.java    ← NEW (1 endpoint stub + CollectionSummary record)

frontend/src/
├── api/skills.ts                ← + fetchCollections + SkillCollection interface
├── pages/CollectionsPage.tsx    ← NEW (~80 LOC; EmptyState + CollectionCard)
├── App.tsx                      ← + /collections route
└── components/AppShell.tsx      ← + 集合 nav link
```

## §5 Test plan

- `./gradlew compileJava` ✓
- `npm test` — 28/28 PASS
- `npm run build` — JS ≤ 405KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/collections` → `[]`
  - 瀏覽器 `/collections` → CollectionsPage 顯 EmptyState

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓
- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 395.66 → 398.40KB (+2.74KB；CollectionsPage + CollectionCard)
- **CSS bundle**: 37.83 → 37.93KB (+0.10KB)
- **Build time**: 181ms（無 regression）
- **Files touched**: 5 (1 backend + 4 frontend) + 1 spec doc
- **AC coverage**:
  - AC-1~6 impl ✓ (manual smoke pending live restart)
  - AC-7 28/28 ✓
  - AC-8 398.40 < 405 ✓
  - AC-9 compileJava ✓

ship as **v2.81.0** (M90f1 / S096 META sub-spec 6b/8)。

## §8 Notes for downstream sub-specs

- **S096f2 (defer)**: full Collection aggregate + 3 mutation endpoints + 2 domain events + `@ApplicationModule(community)` registration（與 S096g2 共同註冊）
- **S096e2 unblock 條件 update**: S096f1 stub 雖 ship，「Step 4 starter pack install」依賴 S096f2 install endpoint；S096e2 仍 ⏸ 等 S096f2 + Onboarding prototype.
- **Live :8080 caveat**: backend 仍跑 ship 前舊 code；`/collections` page 在 live 顯 fetch error；S093 graceful restart 後即正常
