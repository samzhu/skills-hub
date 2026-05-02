# S088 — AnalyticsPage rework + MetricCard

> **Status**: shipped — v2.66.0 (M84) — **S084 META 全 ✅ closing spec**
> **Source**: S084 META spec sub-spec roadmap（5/5 last sub-spec）

## §1 Problem
AnalyticsPage 用 shadcn Card + bg-primary progress bars；視覺與 prototype `.sh-metric` `.sh-rank` 不對齊。MetricCard 用 generic `text-2xl font-bold` 不合 DESIGN.md typography scale。

## §2 Approach
1. MetricCard 重寫：hairline border + label-caps style（11px uppercase tracking 0.05em）+ value 22px medium
2. Hero row：H1 + sub-text
3. Top skills card：15px medium 標題 + 「依下載次數」 label-caps subhead
4. Rank 數字 mono tabular-nums
5. Progress bar：DESIGN.md accent #7F77DD（取代 generic primary）+ bg surface-secondary #F5F4ED + 1.5px 高
6. Download counts mono tabular-nums
7. Error state 用 callout-danger pattern (per S086/S087)

## §3 Result
- 11 frontend tests / 0 fail；CSS 32.6 KB / JS 351.2 KB
- 視覺對齊 prototype `platform_analytics_dashboard_admin_view.html`
- Chrome smoke：4-up metric strip + accent purple bars + mono counts + label-caps style 全到位
- ship v2.66.0 (M84)

## §4 S084 META completion
S084 META spec 5 sub-specs 全 ✅：
- S089 BeamFrame v2.62.0
- S085 HomePage + IconTile v2.63.0
- S086 PublishPage v2.64.0
- S087 SkillDetailPage v2.65.0
- S088 AnalyticsPage v2.66.0 ← 本 spec

UI rework backlog 全清空。
