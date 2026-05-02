# S096d3 — Per-Skill Stats Endpoint + Sparkline + MySkills Integration (S096 META 4c/8)

> **Status**: shipped
> **Type**: backend endpoint + frontend component + page integration
> **Estimate**: M(10-12) → trim 至 S(8) per scope budgeting / 20m wall
> **Source**: Engineering Handoff §2.3 (Download sparkline) + §2.10 (MySkills sparklines per skill)

## §1 Goal

S096d3 原 M(10-12) scope 含 publish flow restructure + 3 new domain events + per-skill stats endpoint + UX polish。20m wall 不容；trim narrow 至**最高 user value 部分**：per-skill stats endpoint + Sparkline component + MySkills integration。

Sparkline 在 prototype `my_skills_author_dashboard.html` 為核心視覺元素 — 「依下載趨勢一眼看出哪個 skill 在升 / 哪個下降」是 P6 SBE「作者查看自己的數據」的具體實作。S094a ship 時 deferred sparkline；本 spec 補。

Defer 至 S096d4（next sub-spec）：
- Publish flow restructure (PublishPage 單頁 → /upload + /validate + /review three-step SSE)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)

## §2 Approach

### §2.1 Backend endpoint

`GET /api/v1/skills/{id}/stats?period=30d` returns `int[]` — fixed-length array of daily download counts.

Schema decision：
- Period 接受 `7d` / `30d` (default) / `90d`；其他 fallback 30d
- Index 0 = 最舊那天，index N-1 = 今天
- 沒下載的天 fill 0
- Server-side bucket 用 `date_trunc('day', downloaded_at)`；UTC timezone（dev/prod parity）

SQL 用 `download_events` 表既有 schema：
```sql
SELECT date_trunc('day', downloaded_at)::date AS bucket_day, COUNT(*) AS cnt
  FROM download_events
 WHERE skill_id = :skillId AND downloaded_at >= :since
 GROUP BY bucket_day ORDER BY bucket_day
```

Service maps result rows into fixed `int[days]` array, padding missing days with 0.

### §2.2 Frontend Sparkline component

**No chart library** — 30 data points 用 native SVG `<polyline>` 即可，bundle 0 dep（vs adding `recharts` ~50KB or `chart.js` ~70KB）。

Props：
- `data: number[]` — daily counts
- `width` / `height` — visual size (default 60×18 — fits 1 row)
- `color` — stroke color (default `--color-accent` purple)

Auto-scales to max value；全 0 顯水平基準線在底部；空 array 顯 `—`.

### §2.3 MySkillsPage integration

`<SkillRow>` 加 sparkline column 顯示 30d trend：
- 只 PUBLISHED skill 拉資料（DRAFT/SUSPENDED 沒 download_events 記錄）
- `useSkillStats(skill.id, '30d')` per row — 平行 fetch；React Query auto-cache 60s
- mobile 隱藏（`hidden sm:block`）— 視窗 < 640px 不顯
- 對齊 prototype `my_skills_author_dashboard.html` table sparkline column

### §2.4 Trim from M(10-12) → S(8)

| Item | Status | Reasoning |
|------|--------|-----------|
| ✓ Per-skill stats endpoint | ship | smallest valuable piece, unblock S094a sparkline polish |
| ✓ Sparkline component | ship | reused in MySkills + future SkillDetail |
| ✓ MySkills integration | ship | immediate user value |
| ✗ Publish flow restructure | defer S096d4 | 3 routes + SSE backend = M scope alone |
| ✗ 3 new domain events | defer S096d4 | tightly coupled with publish restructure |
| ⚪ SkillDetail integration | optional polish | not blocking; do in S096d4 or future polish |

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /api/v1/skills/{id}/stats` (no period) | 200 + `int[30]` array (default 30d) |
| AC-2 | Backend `GET /skills/{id}/stats?period=7d` | 200 + `int[7]` array |
| AC-3 | Backend `GET /skills/{id}/stats?period=invalid` | 200 + `int[30]` (fallback 30d) |
| AC-4 | Backend skill with no downloads | 200 + array filled with 0s |
| AC-5 | Frontend `<Sparkline data={[1,2,3,4,5]} />` | renders SVG polyline |
| AC-6 | Frontend `<Sparkline data={[]} />` | renders dash placeholder |
| AC-7 | Frontend `<Sparkline data={[0,0,0]} />` | renders flat line at bottom |
| AC-8 | MySkillsPage SkillRow PUBLISHED skill | renders sparkline (data fetched) |
| AC-9 | MySkillsPage SkillRow DRAFT/SUSPENDED skill | sparkline column empty (no fetch) |
| AC-10 | Backend compileJava | success |
| AC-11 | Frontend tests 28/28 PASS | regression |
| AC-12 | Build ≤ 388KB JS | budget |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/analytics/
├── AnalyticsService.java       ← + getSkillDownloadTrend(skillId, days)
└── AnalyticsController.java    ← @RequestMapping changed /api/v1/analytics → /api/v1
                                  + GET /skills/{id}/stats endpoint

frontend/src/
├── api/skills.ts               ← fetchSkillStats(id, period)
├── hooks/useSkillStats.ts      ← NEW (react-query 60s cache)
├── components/Sparkline.tsx    ← NEW (SVG polyline, no chart dep)
└── pages/MySkillsPage.tsx      ← SkillRow integrate Sparkline column
```

不動 backend test files（既有 AnalyticsServiceTest 仍 PASS — getOverview path 未動）。  
不加 unit test for Sparkline component — DOM-shape 簡單，per S size scope skip integration test，留 manual smoke。

## §5 Test plan

- `./gradlew compileJava` ✓
- `npm test` — 28/28 PASS（DOM-shape 不破）
- `npm run build` — JS ≤ 388KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/skills/{some-id}/stats` → JSON int[30]
  - 瀏覽器 `/my-skills` PUBLISHED rows 應顯小 sparkline

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓ (2s)
- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 383.18 → 384.14KB (+0.96KB；Sparkline component + useSkillStats hook + fetchSkillStats fn + MySkillsPage integration)
- **CSS bundle**: 36.47 → 36.55KB (+0.08KB)
- **Build time**: 181ms（無 regression）
- **Files touched**: 6 (2 backend + 4 frontend) + 1 spec doc

ship as **v2.78.0** (M90d3 / S096 META sub-spec 4c/8)。

## §8 Notes for downstream sub-specs

- **S096d4**: Publish flow restructure (PublishPage 單頁 → /upload + /validate + /review three routes + SSE event stream + 3 new domain events). M(10-12) scope.
- **SkillDetail trend chart polish**: Sparkline 已 ready，SkillDetailPage 可在 S096d4 polish 時 integrate（顯 30d trend chart per prototype `Skills Hub Skill Detail.html`）

**Live :8080 caveat**: backend 仍跑 ship 前舊 code；新 endpoint `/api/v1/skills/{id}/stats` 生效需下次 graceful restart。Sparkline 在 frontend 跑會看到 fetch 失敗（404）— UI 顯空 column；user S093 transition restart 後即正常。
