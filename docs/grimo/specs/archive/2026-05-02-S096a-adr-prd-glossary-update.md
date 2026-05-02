# S096a — ADR-003 + PRD update + Glossary (S096 META sub-spec 1/8)

> **Status**: shipped
> **Type**: docs-only gate (no code)
> **Estimate**: XS / 4 pts
> **Source**: S096 META spec §4 sub-spec 1 + Engineering Handoff §1, §9, §10

## §1 Goal

S096 META 的第一個 sub-spec — **docs-only gate**，把 v2 redesign 的關鍵架構決定先寫進文件，避免後續 sub-spec 漂移：

- ADR-003 鎖定 route schema migration（`/skills/:id` UUID → `/skills/:author/:name` canonical with permanent alias）
- PRD 加 P7/P8/P9 三個新 SBE feature sections（Collections / Request Board / Notifications）
- PRD Decision Log 加 D25-D27（URL schema / dark theme / 4-tier risk）
- Glossary 加 4 個新 domain terms（Collection / Request / Notification / Subscription）+ update RiskLevel entry

不寫 code；不改 frontend / backend；只動 docs。後續 sub-specs reference 這些 docs 為 source of truth。

## §2 Approach

純 docs change。Decisions 已在 S096 META `§2.2 Decisions locked` user grill 階段確認（a/a/a/a defaults），本 spec 只把 decisions 寫成正式 ADR + PRD 章節 + glossary entries.

不創新內容；只 transcribe + reference。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `docs/grimo/adr/ADR-003-route-schema-author-name.md` exists | Status Accepted；Context/Decision/Consequences/Implementation/Verification 完整 |
| AC-2 | PRD §P7 Collections 含 ≥3 SBE Scenarios | 創建 / 一鍵安裝 / 分類篩選 三個 scenarios |
| AC-3 | PRD §P8 Request Board 含 ≥3 SBE Scenarios | 發起 / 投票 / 認領與實作 三個 scenarios |
| AC-4 | PRD §P9 Notifications 含 ≥3 SBE Scenarios | 新版本通知 / 分類過濾 / 全部已讀 三個 scenarios |
| AC-5 | PRD Decision Log 加 D25/D26/D27 | URL schema / UI 主題 / Risk tier 階數 |
| AC-6 | Glossary 加 4 entries | Collection / Request / Notification / Subscription |
| AC-7 | Glossary RiskLevel entry 更新 | NONE / LOW / MEDIUM / HIGH (was 3-tier) |
| AC-8 | 不動任何 code | git diff 不含 backend/* frontend/* 任何 file |

## §4 Implementation file plan

```
docs/grimo/
├── adr/ADR-003-route-schema-author-name.md   ← NEW
├── PRD.md                                     ← MODIFIED (P7/P8/P9 + D25-D27)
└── glossary.md                                ← MODIFIED (4 add + 1 update)
```

不動任何 backend/* frontend/*。spec ship 後 cron 自然接 S096b（DESIGN.md v2 + theme migration foundation）。

## §5 Test plan

無 code = 無 unit/integration test. Manual verification:
- ADR-003 markdown render OK
- PRD P7-P9 SBE Scenarios 寫成 Given-When-Then 正確語法
- glossary 4 entries 對齊既有 table column（中/EN/code/說明）
- 跨檔案 reference 一致（PRD D25 引用 ADR-003；ADR-003 §6 引用 glossary）

## §6 Verification

實際結果 §7。

## §7 Result

- **ADR-003 ratified**: `docs/grimo/adr/ADR-003-route-schema-author-name.md` 8 sections（Context/Decision/Consequences/Implementation Plan/Sub-routes/Glossary impact/Verification/Open Questions）
- **PRD P7-P9 added**: 3 new feature sections, 9 SBE scenarios total（每 P 3 scenarios）
- **PRD D25-D27 added**: URL schema / dark theme / Risk tier 階數
- **Glossary**: +4 entries（Collection / Request / Notification / Subscription）+ 1 update (RiskLevel → 4-tier)
- **No code changes**: git diff 限於 docs/grimo/* — verified
- **Files touched**: 4 (ADR-003 new + PRD + glossary + spec doc)

ship as **v2.73.0** (M90a / S096 META sub-spec 1/8 完成)。

**META progress**: S096 1/8 ✓ — next ship S096b DESIGN.md v2 + global theme migration foundation (M / 12 pts).

## §8 Notes for downstream sub-specs

- **S096b** reference DESIGN.md v2 token system（per Engineering Handoff §7）— 完整 replace 既有 light-theme tokens
- **S096c** reference ADR-003 §4 Implementation Plan 為 backend route + frontend route 改動 spec；reference D27 為 RiskLevel enum 4-tier 定義
- **S096d/e/f/g/h** reference 對應 P7/P8/P9 SBE 為 AC source（feature page 各自實作）
- **S096h** notification feature reference D25 為 URL schema for notification 內 skill 連結（用 canonical author/name）
