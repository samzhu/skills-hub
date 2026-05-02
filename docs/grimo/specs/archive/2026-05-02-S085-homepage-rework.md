# S085 — HomePage rework + reusable components 抽取

> **Status**: shipped — v2.63.0 (M81-spec-rework)
> **Source**: S084 META spec sub-spec roadmap

## §1 Problem

HomePage 用 shadcn 預設 Card / Badge primitives，視覺與 `skills_hub_homepage_mockup.html` 設計稿不對齊。缺少：
- Hero row（H1 + sub-text + 「發布技能」primary CTA）
- IconTile（30px category-tinted square + 2-letter initial）
- 半徑 / spacing 對齊 DESIGN.md scale
- Card foot 含 version mono pill + category badge + download stat

## §2 Approach

抽取 reusable component + 重寫 SkillCard + HomePage hero row：
1. **`IconTile.tsx`**：6-category-tinted（devops/infra/testing/docs/data/security + default）+ size sm/md/lg/xl
2. **`SkillCard.tsx` 重寫**：hairline border + IconTile + RiskBadge pill + 2-line desc + foot with category/version/download
3. **`HomePage.tsx` hero row**：H1 22px + 13px sub-text + black primary CTA per prototype `.sh-hero-row`

不重寫 AppShell / CategorySidebar — 留下次 iteration（focus 限縮）。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | HomePage load | Hero row + sub-text + 「發布技能」CTA + BeamFrame SearchBar visible |
| AC-2 | SkillCard 6 category 顯示 | IconTile color 對應 category（DevOps purple / Testing teal / etc.） |
| AC-3 | SkillCard initials | name 取 1-2 字母 capital（hyphen 名取兩段首字母） |
| AC-4 | SkillCard risk pills | LOW soft-green / MEDIUM soft-amber / HIGH soft-red per DESIGN.md semantic |
| AC-5 | npm test | 11 / 0 fail |
| AC-6 | DESIGN.md tokens 套用 | 無 hard-coded shadcn defaults；使用 token variable / category palette hex |

## §4 Result

- 11 frontend tests / 0 fail；npm build 成功（CSS 32.4 KB / JS 349.4 KB）
- Chrome smoke：HomePage 顯 hero row + 「發布技能」黑色 CTA + BeamFrame search bar + 8 sidebar categories + 2-col card grid with IconTile (RD/RH/RG/RW etc.) + 低風險 pill in accent-soft green + version mono pill + download stat
- ship v2.63.0 (M81-spec-rework)
- IconTile reusable for SkillDetailPage (S087) / AnalyticsPage (S088) lists
