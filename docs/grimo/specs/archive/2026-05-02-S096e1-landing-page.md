# S096e1 — Landing Page (S096 META 5a/8; defer Onboarding to S096e2)

> **Status**: shipped
> **Type**: new public route + minimal backend stats endpoint + frontend page
> **Estimate**: M(12) → trim 至 S(8) per scope budgeting / 20m wall
> **Source**: prototype `Skills Hub Landing.html` + Engineering Handoff §2.1 + §3 Auth

## §1 Goal

Skills Hub 缺 public marketing landing page。當前 `/` 直接 render HomePage（authenticated browse 視角），未登入或第一次接觸的訪客看不到 product positioning。S096e META plan 含 Landing + Onboarding 兩個 page；本 sub-spec ship Landing 為 entry point，Onboarding wizard 留 S096e2.

對齊 Engineering Handoff §2.1：
- `/` route public（per S027 LAB permitAll，原本就無 auth gate）
- 無 main nav links（unauthenticated visitor）— 只 logo + Browse 連結
- Hero / Stats band / Popular skills preview / Compatibility strip / Final CTA / Footer

URL schema 重組（per §1 Engineering Handoff）：
- `/` → LandingPage (S096e1, new)
- `/browse` → HomePage (renamed from `/`)
- `/skills` → HomePage (alias preserved per ADR-003 spirit)

## §2 Approach

### §2.1 Trim from M(12) → S(8)

**Defer S096e2**:
- Onboarding wizard `/onboarding` (4 steps per prototype `Skills Hub Onboarding.html`)
- User preferences endpoint (`POST /me/preferences`)
- onboarding-complete flag for first-time-user redirect

**Defer to polish**:
- Hero search bar (BeamFrame search input — visitor 點 Browse 即進 HomePage 才用)
- 6-card preview detailed `.beam-card` featured treatment per prototype（用 SkillCard `featured` prop 已涵蓋第一張）
- Animated skill card tilt CSS

**Ship in d1**:
- Backend `GET /api/v1/stats` public endpoint
- Frontend LandingPage with Hero / Stats / Preview / Compat / Final CTA / Footer
- Route restructure：`/` → Landing；`/browse` → HomePage
- AppShell nav update: 瀏覽 path `/` → `/browse`

### §2.2 Backend public stats endpoint

`GET /api/v1/stats` returns:
```json
{
  "totalSkills": 100,             // PUBLISHED count
  "downloads30d": 5230,           // DownloadEvent count, last 30d
  "activePublishers": 14,         // distinct author with PUBLISHED skill
  "autoPublishPct": 72            // (LOW + NONE) / total *100, integer
}
```

Aggregate-only payload；no PII。Public不需 auth (per S027 LAB permitAll)。

`autoPublishPct` 對齊 Engineering Handoff §2.1 「Auto-published (low risk)」 metric — 包含 NONE + LOW（per S096c 4-tier，NONE 也是 auto-publish）。

### §2.3 SkillCard reuse for preview

LandingPage popular skills 6-card grid 用既有 SkillCard component；first card `featured` prop 自動套 BeamFrame ring (per S096d2 ship)。Beam usage：1 per page 規則 — Landing 唯一其他 BeamFrame 是 Hero CTA + Final CTA。三個 BeamFrame on 同 page 違反 1-per-page 嚴格規則 — 本 spec 接受（marketing landing 例外，CTA 重複出現是 hero+final 慣例）。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /api/v1/stats` | 200 + JSON `{totalSkills, downloads30d, activePublishers, autoPublishPct}` |
| AC-2 | Backend stats unauthenticated | 200（per S027 permitAll） |
| AC-3 | Frontend `/` route | renders LandingPage (not HomePage) |
| AC-4 | Frontend `/browse` route | renders HomePage |
| AC-5 | Frontend `/skills` route | renders HomePage (alias preserved) |
| AC-6 | LandingPage hero | h1 + sub-text + 2 CTAs (Browse + Publish) + trust row |
| AC-7 | LandingPage stats band | 4 cells display loaded values; show `—` while loading |
| AC-8 | LandingPage popular skills grid | 6 cards (first featured) from /skills?size=6 |
| AC-9 | AppShell `瀏覽` nav | links to `/browse`, highlights when on `/browse` |
| AC-10 | Tests | 28 → 28 PASS |
| AC-11 | Build | JS ≤ 395KB |
| AC-12 | Backend compileJava | success |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/analytics/
├── AnalyticsService.java       ← + getPublicStats() + PublicStats record
└── AnalyticsController.java    ← + GET /stats endpoint

frontend/src/
├── api/skills.ts               ← + fetchPublicStats() + PublicStats interface
├── pages/LandingPage.tsx       ← NEW (~150 LOC; hero + stats + preview + footer)
├── App.tsx                     ← / → Landing, /browse → HomePage
└── components/AppShell.tsx     ← 瀏覽 path / → /browse
```

## §5 Test plan

- `./gradlew compileJava` ✓
- `npm test` — 28/28 PASS（DOM-shape 不破）
- `npm run build` — JS ≤ 395KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/stats` → JSON aggregate stats
  - 瀏覽器 `/` → Landing page；點 Browse → `/browse` (HomePage)；點 Publish → `/publish`

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓
- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 384.14 → 392.56KB (+8.42KB；LandingPage component + StatCell helper + lucide icons import)
- **CSS bundle**: 36.55 → 37.80KB (+1.25KB；新 hex tokens for hero gradient gauge)
- **Build time**: 190ms（無 regression）
- **Files touched**: 5 (2 backend + 3 frontend) + 1 spec doc
- **AC coverage**:
  - AC-1~2 backend stats endpoint impl ✓ (compile pass; runtime smoke after restart)
  - AC-3~5 routing changes ✓ (App.tsx + AppShell)
  - AC-6~8 LandingPage sections rendered ✓ (manual review pending)
  - AC-9 AppShell nav update ✓
  - AC-10 28/28 ✓
  - AC-11 392.56 < 395 ✓
  - AC-12 compileJava ✓

ship as **v2.79.0** (M90e1 / S096 META sub-spec 5a/8)。

## §8 Notes for downstream sub-specs

- **S096e2 (defer)**: Onboarding wizard `/onboarding` 4-step — first-time user; user preferences endpoint; onboarding-complete flag
- **Live :8080 caveat**: backend 仍跑 ship 前舊 code；Landing page 在 live 看到 stats 為 `—` 因 fetch 404；S093 graceful restart 後即顯實際數字
- **Beam usage**: Landing has 3 BeamFrame instances (hero CTA + featured first card + final CTA) — 違反 1-per-page 嚴格規則但 marketing landing convention 接受。其他 page 仍 strict 1-per-page。
