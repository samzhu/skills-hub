# S086 — PublishPage rework

> **Status**: shipped — v2.64.0 (M82)
> **Source**: S084 META spec sub-spec roadmap

## §1 Problem
PublishPage 用 shadcn Card primitive；視覺與 `skill_publish_upload_flow.html` 設計稿不一致。Success/error callouts 用 generic green/red bg，不符 DESIGN.md semantic 4-tier。

## §2 Approach
1. Hero row（H1 22px medium + 13px sub-text）解釋自動驗證/掃描/索引
2. Card 從 shadcn primitive → hairline border + 14px padding (per `.sh-card`)
3. Form labels uppercase 12px tracking-wide muted（prototype convention）
4. Version input 加 `font-mono` (technical 字串應 mono)
5. Success callout 用 success-soft (#EAF3DE) + success-deep (#27500A) + CheckCircle2 icon
6. Error callout 用 danger-soft (#FCEBEB) + danger-deep (#791F1F) + AlertCircle icon

## §3 Result
- 11 frontend tests / 0 fail；CSS 32.4 KB / JS 350.7 KB
- semantic colors 對齊 DESIGN.md 4-tier；不用 generic green/red
- callout 加 lucide-react icons (CheckCircle2 / AlertCircle / ArrowRight)
- ship v2.64.0 (M82)
