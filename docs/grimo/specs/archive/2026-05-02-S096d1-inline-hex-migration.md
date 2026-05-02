# S096d1 — Inline-hex Token Migration to Dark Theme (S096 META sub-spec 4a/8)

> **Status**: shipped
> **Type**: bulk hex token migration (frontend-only; deterministic sed)
> **Estimate**: S / 8 pts (split from S096d L 15-16)
> **Source**: DESIGN.md v2 dark token system (per S096b foundation)

## §1 Goal

S096d「Existing pages v2 refresh」原 L(15-16) 拆為 d1 + d2 per scope budgeting / 20m tick wall：

- **S096d1 (this)**: bulk migrate inline-style hex strings 從 v1 light tokens → v2 dark tokens
- **S096d2 (next)**: publish flow restructure (single → Step1/2/3 SSE) + 3 new domain events + per-skill stats endpoint + prototype-design polish

S096b foundation 已 swap 全 `--color-*` CSS variables，但 component 內 inline-style hex 字串（S087 status pill / S088 progress bar / S094 sub-specs）仍寫死 v1 light 值，造成既有 frontend dark + light 混色。本 spec 機械式 bulk replace 完成 visual 一致性。

## §2 Approach

### §2.1 Mechanical bulk sed

20-color replacement map（v1 light → v2 dark equivalents per DESIGN.md v2）：

```
#FCEBEB → rgba(226,75,74,0.14)    # danger-soft
#791F1F → #F2A6A6                  # danger-text light
#FAEEDA → rgba(239,159,39,0.14)   # warning-soft
#633806 → #FAC775                  # warning-text light
#EAF3DE → rgba(29,158,117,0.14)   # success-soft
#085041 → #6FD8B0                  # success-text light
#27500A → #9FE1CB                  # success-mid
#E1F5EE → rgba(29,158,117,0.14)
#EEEDFE → rgba(127,119,221,0.18)  # accent-soft
#3C3489 → #C9C5F2                  # accent-text light
#E6F1FB → rgba(55,138,221,0.14)   # info-soft
#0C447C → #B0D5F2                  # info-text light
#F5F4ED → #171719                  # bg-3 (was warm secondary)
#5C5C5C → #A8A49C                  # ink-2 (was secondary text)
#5C5751 → #A8A49C
#F5C2C2 → rgba(226,75,74,0.20)    # danger-mid
+ 4 category palette light → dark rgba (docs/security)
```

跨 8 files mechanical 套用：IconTile / IntentSummaryCard / RiskBadge / EmptyState / AnalyticsPage / PublishPage / SkillDetailPage / MySkillsPage / YourFirstSkillPage.

### §2.2 Trim from L(15-16) → S(8)

**Defer to S096d2**:
- Publish flow restructure (PublishPage 單頁 → /upload + /validate + /review three pages with SSE event stream)
- 3 new domain events (`SkillBundleExtracted` / `SkillFrontmatterValidated` / `SkillRiskScanStarted`)
- Per-skill stats endpoint (`/api/v1/skills/{id}/stats?period=30d` for sparkline)
- Cherry-pick prototype design polish per user direction（每 page 細節對齊 prototype）

**Keep in d1**:
- Mechanical hex token migration (8 files)
- Build / test regression check
- Spec doc + commit

### §2.3 Why DOM-shape tests survive

既有 28 vitest tests 用 `screen.getByText(...)` / `getByRole(...)` 等 DOM API，**不 assert hex color strings**。bulk hex swap 純視覺改 — 同 S096b 經驗。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | grep `'#FCEBEB'` etc v1 hex 全 zero hits | 8 files 完全清掉 v1 light hex |
| AC-2 | Frontend tests 28 → 28 PASS | DOM-shape 不破 |
| AC-3 | Build 成功 ≤ 385KB JS | regression check |
| AC-4 | RiskBadge / StatusPill / EmptyState semantic colors 對齊 dark theme | NONE 綠 / LOW 藍 / MEDIUM 琥珀 / HIGH 紅；rgba alpha overlays |
| AC-5 | category IconTile palette migrate dark | 6 categories rgba alpha + light text variants |

## §4 Implementation file plan

```
sed -i '' 'multi-pattern' \
  frontend/src/components/IconTile.tsx \
  frontend/src/components/IntentSummaryCard.tsx \
  frontend/src/components/RiskBadge.tsx \
  frontend/src/components/EmptyState.tsx \
  frontend/src/pages/AnalyticsPage.tsx \
  frontend/src/pages/PublishPage.tsx \
  frontend/src/pages/SkillDetailPage.tsx \
  frontend/src/pages/MySkillsPage.tsx \
  frontend/src/pages/docs/YourFirstSkillPage.tsx
```

20 sed expressions in single command；deterministic；reversible via git.

## §5 Test plan

- `npm test` — 預期 28/28 PASS
- `npm run build` — JS ≤ 385KB

## §6 Verification

實際結果 §7。

## §7 Result

- **grep v1 hex**: zero hits across 8 affected files ✓
- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 382.54 → 382.91KB (+0.37KB rgba string vs hex 略長)
- **CSS bundle**: 36.47 → 36.70KB (+0.23KB)
- **Build time**: 216ms（無 regression）
- **Files touched**: 9 (8 source + 1 spec doc)

ship as **v2.76.0** (M90d1 / S096 META sub-spec 4a/8)。

## §8 Notes for downstream sub-specs

- **S096d2 (next)**: publish flow Step1/2/3 split + SSE event stream + 3 new domain events + per-skill stats endpoint + cherry-pick prototype design polish per user mid-tick request
- **Other inline-hex 殘留**: 若有遺漏（例如某些 `text-[#XXXXXX]` Tailwind arbitrary syntax 被 sed 漏掉），下次 tick / pages refresh 時補
