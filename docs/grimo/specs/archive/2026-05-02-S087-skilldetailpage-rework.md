# S087 — SkillDetailPage rework

> **Status**: shipped — v2.65.0 (M83)
> **Source**: S084 META spec sub-spec roadmap

## §1 Problem
SkillDetailPage 用 shadcn Badge primitives；hero row 設計與 prototype 不一致。Status badges 用 generic shadcn variants（default/secondary/destructive），沒有 DESIGN.md 4-tier semantic palette。

## §2 Approach
1. Hero row：IconTile xl 52px + name 22px medium + author 13px tertiary + version mono pill + RiskBadge + status pill
2. Status pill 用 inline-style hex map（DRAFT amber / PUBLISHED green / SUSPENDED red）— per DESIGN.md semantic 4-tier
3. SUSPENDED 提示 banner → callout-danger：bg #FCEBEB / fg #791F1F + AlertCircle icon + structured 1+1 lines (heading + sub)
4. Download CTA 維持但用 hover:bg-foreground (per Hero CTA pattern)

## §3 Result
- 11 frontend tests / 0 fail；CSS 32.3 KB / JS 351.1 KB
- IconTile 跨 page reuse（HomePage 30px / Detail 52px）
- 移除 shadcn Badge import；hand-rolled spans 控顏色更精準
- ship v2.65.0 (M83)
