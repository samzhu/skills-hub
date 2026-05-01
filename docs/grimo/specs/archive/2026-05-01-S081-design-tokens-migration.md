# S081 — Design token migration（DESIGN.md → frontend/src/index.css）

> **Status**: in-flight
> **Type**: feature (UI foundation)
> **Estimate**: S / 5 pts
> **User-driven**: 「參考 DESIGN.md 設計語言優化畫面」

## §1 Problem

`frontend/src/index.css` 仍是 shadcn 預設 monochrome oklch tokens；DESIGN.md 定義了一套溫和、含 purple accent / 6-tint category palette / 完整 semantic 4-tier (success/warning/danger × soft/mid/deep/text) 的 token system。沒 token 對齊，後續任何 page rework（HomePage / PublishPage / SkillDetailPage / AnalyticsPage）都會用錯顏色。

`docs/prototype/*.html` 12 個設計師畫的 mockup 頁面引用 `var(--color-text-primary)` / `var(--color-background-primary)` / `var(--color-border-tertiary)` 等變數，但 prototype 自身沒定義 — 假設 host 環境提供。

## §2 Approach

**保留 shadcn-convention 的 token 命名**（`--color-primary` / `--color-foreground` / 等）給既有 components；**值改用 DESIGN.md hex codes**。同時 ADD：
- 完整 accent palette（`accent` / `accent-soft` / `accent-mid` / `accent-deep` / `accent-text`）
- 完整 semantic 4-tier（success / warning / danger × soft/mid/deep/text）
- 6 category tints（devops / infra / testing / docs / data / security × bg + deep）
- info palette（links / k8s）
- 細化的 radius（xs / sm / md / lg / xl / pill）

Tailwind v4 `@theme inline` 自動把 `--color-X` 生成 `bg-X` / `text-X` / `border-X` utility — 不需手動維護 utility class table。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | 既有 component（Button / Card / Input）渲染 | 顏色從 oklch monochrome 變成 DESIGN.md 暖色（warm off-white #FFFFFF + 灰 ink #181818） |
| AC-2 | 新增 utility class | `bg-accent-soft` / `text-success-deep` / `bg-category-devops` / `border-warning-mid` 等 Tailwind 自動可用 |
| AC-3 | frontend build & test 全 PASS | `npm run build` + `npm test` 不破 |
| AC-4 | 視覺 regression check | 主要 page (HomePage / PublishPage) 渲染不破，整體偏暖 |
| AC-5 | Inter / JetBrains Mono / Source Serif Pro 字體 | 設為 sans / mono / serif fallback chain |

## §4 Implementation

`frontend/src/index.css` 替換 `@theme inline` block：

- 14 個 shadcn baseline token → 替換值為 DESIGN.md hex
- 新增 5 group × 3-5 tier = ~25 個 semantic tokens（accent / success / warning / danger / info）
- 新增 6 × 2 = 12 個 category palette tokens
- 新增 6 個 radius scale + Inter/JetBrainsMono/SourceSerif 字體 stack

## §5 Verification

- `npm run build` 成功
- `npm test` 11 tests / 0 fail
- Chrome 開 frontend → 視覺 sanity check（主要 layout 不破）
- 抽樣檢查：`bg-accent-soft text-accent-deep` utility 正確渲染

## §6 Result

待 ship 後填。

## §7 Follow-up

S081 只是 token foundation。後續 specs：
- S082: HomePage rework 對齊 `skills_hub_homepage_mockup.html`
- S083: PublishPage rework 對齊 `skill_publish_upload_flow.html`
- S084: SkillDetailPage rework + Files tab UI（接 S074 backend API；對齊 `skill_detail_page_docker_compose_helper.html`）
- S085: AnalyticsPage rework 對齊 `platform_analytics_dashboard_admin_view.html`
- 其他 prototype pages 視 user 優先序決定是否做（landing / onboarding / docs / admin queue / empty states / failure states）

**Result（填於 ship 後）**：
- 11 frontend tests / 0 fail
- `npm run build` 成功（dist/index.js 389KB / dist/index.css 30KB / 250ms）
- token coverage：13 shadcn baseline (alias) + 5 accent tier + 3 info + 5 success + 5 warning + 5 danger + 12 category (6 × 2) + 7 prototype-compat aliases = **55 個 color tokens**
- 6 radius scale (xs / sm / md / lg / xl / pill)
- 3 font stack (Inter / JetBrains Mono / Source Serif Pro)
- ship v2.58.0 (M77)
- Visual check 需 Chrome extension（本 tick 連線中斷，留 user 或下 tick 驗）
- 後續 specs S082-S085 per page rework 已列在本 spec §7
