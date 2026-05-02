# S096b — DESIGN.md v2 + Global Theme Migration Foundation (S096 META sub-spec 2/8)

> **Status**: shipped
> **Type**: theme foundation (frontend-only; backend 0 changes)
> **Estimate**: M / 12 pts → trim 至 ~8 pts（既有 inline-hex polish 推 S096d）
> **Source**: DESIGN.md v2 (commit `132dfd8` user-imported) + Engineering Handoff §7 token reference + §8 BorderBeam usage rules

## §1 Goal

把 Skills Hub frontend 從 light theme（warm off-white `#FFFFFF`）一夜變 dark theme（`#08080A`），透過 global CSS token swap 自動套到所有 shadcn-based components。**Foundation 而非 polish** — per-page 細節調整推到 S096d existing pages refresh。

```
[現]                              [S096b 後]
bg-background → #FFFFFF          → #08080A
bg-card        → #FFFFFF          → #0F0F12
bg-muted       → #F5F4ED          → #171719
text-foreground→ #181818          → #EEECEA
text-muted-fg  → #5C5C5C          → #A8A49C
border         → #E0DDD3 hairline → rgba(255,255,255,0.06)
```

shadcn primitives (`<Card>`, `<Badge>`, `<Input>`, `<Button>` etc.) 全自動套新 tokens；既有 inline-style hex（S087 status pill / S088 progress bar / S094c EmptyState 等）暫顯舊色，由 S096d 統一更新.

## §2 Approach

### §2.1 Trim from M(12) → ~8 pts

Original M scope 含：
- ✓ Global CSS tokens swap
- ✓ shadcn integration（透過 `@theme inline` 自動）
- ✓ BeamFrame 5-color rewrite
- ✗ 既有 inline-hex 全 audit + replace — defer S096d
- ✗ vitest 大量 hex-string assertion patch — 不需要（測試已 pass）

實際 trim 後 8 pts：CSS 一份檔 + BeamFrame 一份檔 + spec doc。可單 tick ship。

### §2.2 Why DOM-shape tests survive

既有 28 vitest tests 全用 `screen.getByText(...)` / `getByRole(...)` 等 DOM-shape API，**不 assert hex 顏色字串**。token swap 不破 test，純視覺改動 — 符合 「test code structure, not pixels」原則。

### §2.3 Legacy `:root` variables sync

S081 ship 時加了一組 `--color-text-primary` / `--color-background-primary` 等 legacy 變數給 prototype-ported plain CSS 用。S096b 同步 swap 為 dark values，避免 v1/v2 token 並存造成混色。

### §2.4 BeamFrame 5-color rewrite

Per Handoff §8 specifies new beam pattern：

| Aspect | S089 (light) | S096b (dark per Handoff §8) |
|--------|-------------|------------------------------|
| Padding | 1px | 1.5px (dark bg 上 ring 更可見) |
| Inner bg | `#FFFFFF` light | `#08080A` dark |
| Wrapper bg | `--color-border-tertiary` (`#E0DDD3`) | `#1A1A1E` (slightly above page bg) |
| Color stops | 2-color (purple → blue) | 5-color (purple → magenta → amber → green → blue) |
| Animation | 4s linear | 1.96s linear |
| Glow layer | none | `::after` blur(10px) opacity 0.5 — halo on dark |
| Z-index | (none) | `isolation: isolate` + inner z-index:1 |

新 beam 視覺重量更顯眼，配合 Handoff §8「scarce motion primitive — ONE per page」規則。S096d existing pages refresh 階段 audit 既有 BeamFrame 使用點，多餘的（如 S094c EmptyState SeedTone primary action）需移除維持 1-per-page。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `frontend/src/index.css` `@theme inline` block 改用 dark tokens | bg #08080A, fg #EEECEA, card #0F0F12 等 |
| AC-2 | Legacy `:root` block 同步 dark | `--color-text-primary: #EEECEA` 等 |
| AC-3 | BeamFrame 5-color conic-gradient | purple/magenta/amber/green/blue stops + 1.96s + blur ::after |
| AC-4 | Frontend tests 28 → 28 PASS | DOM-shape assertions 不破 |
| AC-5 | Build OK ≤ 385KB JS | 既有 + 0 new module |
| AC-6 | shadcn primitives 自動 dark | `<Card>` / `<Badge>` 等渲染為 dark surface |
| AC-7 | Existing inline-hex 不更新 | S087/S088/S094 等 page 仍顯舊 hex 顏色（暫時混色） — defer S096d 統一 polish |

## §4 Implementation file plan

```
frontend/src/
├── index.css              ← MODIFIED (dark tokens swap)
└── components/
    └── BeamFrame.tsx      ← MODIFIED (5-color + dark inner bg)
```

不動 backend；不動既有 component code（除 BeamFrame）；不動 inline-hex 的 S087/S088/S094 components — defer S096d.

## §5 Test plan

- `npm test` — 預期 28/28 PASS（DOM-shape assertions）
- `npm run build` — JS ≤ 385KB / CSS ≤ 38KB
- Manual smoke: dev server `localhost:5173` 開既有 page 應渲染為 dark theme（除 inline-hex 部分仍混色）

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 28 → 28 PASS / 0 fail（DOM-shape 不破）
- **JS bundle**: 381.66 → 381.91KB (+0.25KB；BeamFrame 5-color CSS 增 inline `<style>` 字串)
- **CSS bundle**: 37.09 → 37.21KB (+0.12KB；rgba alpha string vs hex 略長)
- **Build time**: 162ms（無 regression）
- **Files touched**: 2 (`index.css` + `BeamFrame.tsx`)
- **AC coverage**:
  - AC-1/2 dark tokens swap ✓
  - AC-3 BeamFrame 5-color ✓
  - AC-4 28/28 PASS ✓
  - AC-5 build 381.91 < 385 ✓
  - AC-6 shadcn auto dark — verified by component render path（既有 SkillCard/MetricCard 套 `bg-card text-foreground` 即自動 dark）
  - AC-7 inline-hex 暫保 — verified git diff（無修改 S087/S088/S094 components）

ship as **v2.74.0** (M90b / S096 META sub-spec 2/8 完成)。

**META progress**: S096 2/8 ✓ — next ship S096c Routing schema + Risk tier 4-level (M / 12 pts; absorbs S095).

## §8 Notes for downstream sub-specs

- **S096c**: RiskBadge dark theme rewrite 對齊新 tokens（既有 RiskBadge 用 shadcn `<Badge>` 已自動 dark；4-tier color reshuffle 用新 semantic tokens）
- **S096d existing pages refresh**: 全 audit 既有 inline-hex（S087 status pill / S088 progress bar / S094a/b/c/d 內 hex strings）→ 改用 v2 dark token，並 audit BeamFrame 使用點維持 1-per-page rule
- **S096e Landing**: BeamFrame 用於 hero search bar；**Landing 不能再用 BeamFrame 在其他位置**
- **既有 :8080 backend** 不受影響（純 frontend change）；user 重啟 backend 與否不阻塞 S096b 生效
