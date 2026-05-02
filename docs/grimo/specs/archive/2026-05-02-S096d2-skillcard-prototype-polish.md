# S096d2 — SkillCard Prototype Polish + Featured Variant (S096 META 4b/8)

> **Status**: shipped
> **Type**: shared component polish + new `featured` variant
> **Estimate**: M(10-12) → trim 至 S(7) per scope budgeting / 20m wall
> **Source**: prototype `Skills Hub Homepage.html` `.sc` / `.sc.featured` design

## §1 Goal

S096d2 原 M(10-12) 含 publish flow restructure + 3 new domain events + per-skill stats endpoint + prototype polish。20m tick wall 不容；trim narrow 至**最高 leverage 改動**：SkillCard component polish。

SkillCard 為 3 page reused component (HomePage / MySkills / SearchResults)，1 polish 同時提升 3 page 視覺品質。Cherry-pick prototype design：
- Border radius 12px → 16px (xl per DESIGN.md)
- Author 文字 mono font 對齊 prototype
- 4 hex 殘留 inline-style migrate dark tokens（S096d1 sed 漏 SkillCard.tsx）
- **新加 `featured` variant**：top-match in search results 套 BeamFrame（per Engineering Handoff §8 BorderBeam usage rule — 「Featured/top-match skill card」屬合法 4 個 beam usage 之一）

Defer 至 S096d3+:
- Publish flow restructure (PublishPage 單頁 → /upload + /validate + /review)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)
- Per-skill stats endpoint (/api/v1/skills/{id}/stats?period=30d)
- 其他 page 細節 polish

## §2 Approach

### §2.1 Trim from M(10-12) → S(7)

Original scope 5 items；只保 1 (SkillCard polish)。

**Why narrow trim 不 cut**：原 M(10-12) 任一 item 各自 1+ tick；組合超出 4× 20m wall。SkillCard polish 是「reused component → 3 page benefit」高 leverage；其他 4 items 各自獨立 sub-spec ship 更乾淨。

### §2.2 Featured BeamFrame integration

`featured` boolean prop wrap SkillCard `<article>` in BeamFrame。SearchResultsPage 為 first result (best match) 啟用，符合 Engineering Handoff §8 「ONE per page」rule（SearchResults 沒其他 BeamFrame）。

Featured card 不要 hairline border（被 BeamFrame ring 取代）— 透過 conditional className 處理。

### §2.3 Hex migration belated patch

S096d1 sed list 漏了 SkillCard.tsx。本 spec 順手 patch 4 hex 殘留：
- `#FCEBEB` / `#791F1F` (SUSPENDED status pill) → rgba(226,75,74,0.14) / `#F2A6A6`
- `#FAEEDA` / `#633806` (DRAFT status pill) → rgba(239,159,39,0.14) / `#FAC775`
- `#EAF3DE` / `#27500A` (similarity badge) → rgba(29,158,117,0.14) / `#9FE1CB`

Inline-style hex 改 inline `style={}` 物件對齊 S087 既有 pattern。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | SkillCard radius `xl` (16px) | 對齊 prototype `.sc` r-xl |
| AC-2 | Author 用 mono font | 對齊 prototype `.sc-author` font-family:var(--mono) |
| AC-3 | SUSPENDED/DRAFT status pill / similarity badge | 用 dark theme rgba alpha + light text variants |
| AC-4 | `<SkillCard featured />` | wraps in BeamFrame；hairline border 移除 |
| AC-5 | `<SkillCard />` (default) | 維持 border-border hairline；hover 加深至 line-2 |
| AC-6 | SearchResultsPage first result `featured=true` | top-match beam ring visible |
| AC-7 | Frontend tests 28 → 28 PASS | DOM-shape unchanged (SkillCard.test 既有 cases) |
| AC-8 | Build ≤ 385KB JS | regression check |

## §4 Implementation file plan

```
frontend/src/components/SkillCard.tsx   ← polish + featured prop + hex migration
frontend/src/pages/SearchResultsPage.tsx ← featured={i === 0} for top match
```

不動 backend；不動其他 component。

## §5 Test plan

- `npm test` — 28/28 PASS（SkillCard.test DOM-shape 不破）
- `npm run build` — JS ≤ 385KB
- 手動 smoke：dev server 開 `/search?q=docker` 觀察 first result beam ring；HomePage / MySkills 看 SkillCard radius 視覺對齊 prototype

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 382.91 → 383.18KB (+0.27KB；featured branching + BeamFrame import)
- **CSS bundle**: 36.70 → 36.47KB (**-0.23KB**；舊 status pill `bg-[#FCEBEB]` etc. tailwind utility class 移除，改 inline style 不生 utility)
- **Build time**: 190ms（無 regression）
- **Files touched**: 2 source + 1 spec doc
- **AC coverage**:
  - AC-1~6 component impl ✓
  - AC-7 28/28 ✓
  - AC-8 build 383.18 < 385 ✓
- **Hex migration belated**: SkillCard 6 inline hex 全 swap ✓ (S096d1 sed 漏掉的)

ship as **v2.77.0** (M90d2 / S096 META sub-spec 4b/8)。

**META progress**: S096 4b/8 ✓ — next ship one of {S096d3 (publish restructure + new events) / S096e (Landing+Onboarding) / S096f (Collections) / S096g (Requests) / S096h (Notifications+Diff)} — by selection priority "size 小先" they're all M(10-12)；META plan order pick S096d3 (continue d sub-tree).

## §8 Notes for downstream sub-specs

- **S096d3 (next probable)**: Publish flow restructure into /upload + /validate + /review three-step + SSE event stream + 3 new domain events. M(10-12) scope.
- **Other deferred d items**: Per-skill stats endpoint can fold into S096d3 or S096e (Analytics 也用)
