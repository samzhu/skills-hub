# S008: 數據分析儀表板（Event-driven projection）

> Spec: S008 | Size: M(11) | Status: ✅ Done
> Date: 2026-04-25

---

## 1. Goal

提供平台數據儀表板 — 總覽統計（總 skill 數、總下載、熱門排行）和單一 skill 統計。讓管理者掌握平台健康度，讓作者了解自己的 skill 表現。

依賴 S006（✅ shipped）— 使用 `DownloadEventReadModel`、`DownloadEventRepository`、`AnalyticsProjection`。

## 2. Approach

Backend: `analytics` module 的 `AnalyticsService` + `AnalyticsController` 用 MongoTemplate aggregation 查詢 `download_events` + `skills` collections。
Frontend: AnalyticsPage 顯示 4 個 metric cards + Top 10 排行 + 近期活動。

### Key Decisions

1. **Aggregation queries** — MongoTemplate + `Aggregation.newAggregation()` on `download_events` and `skills` collections. No new dependencies.
2. **Frontend route** — `/analytics` page with AppShell. Nav link「數據」。
3. **Per-skill stats already exist** — `downloadCount` on `SkillReadModel` (updated by SkillProjection in S006). No new backend needed for basic per-skill stats.

## 3. SBE Acceptance Criteria

Verification command:

    Backend: cd backend && ./gradlew test
    Frontend: cd frontend && npx tsc --noEmit && npm run build

**AC-1: 平台總覽 API**

```
Given 平台有 10 skills, 500 downloads
When  GET /api/v1/analytics/overview
Then  200 { totalSkills: 10, totalDownloads: 500, newSkillsThisWeek: 3, topSkills: [{name, downloads}, ...top10] }
```

**AC-2: 前端儀表板頁面**

```
Given /analytics 頁面
Then  顯示 4 個 metric cards（總技能數、總下載、本週新增、風險分佈）
And   顯示 Top 10 熱門技能排行
```

## 4. Interface / API Design

```
GET /api/v1/analytics/overview
  Response: {
    totalSkills: 10,
    totalDownloads: 500,
    newSkillsThisWeek: 3,
    topSkills: [ { name: "docker-helper", downloads: 200 }, ... ]
  }
```

## 5. File Plan

| # | File | Action |
|---|------|--------|
| 1 | `.../analytics/AnalyticsService.java` | new — aggregation queries |
| 2 | `.../analytics/AnalyticsController.java` | new — GET /api/v1/analytics/overview |
| 3 | `.../analytics/OverviewStats.java` | new — response record |
| 4 | `frontend/src/pages/AnalyticsPage.tsx` | new — dashboard |
| 5 | `frontend/src/hooks/useAnalytics.ts` | new — TanStack Query hook |
| 6 | `frontend/src/api/analytics.ts` | new — fetchOverview |
| 7 | `frontend/src/App.tsx` | modify — add /analytics route |
| 8 | `frontend/src/components/AppShell.tsx` | modify — add 「數據」nav |
| 9 | `.../analytics/AnalyticsControllerTest.java` | new — AC-1 test |
