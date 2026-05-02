# S096d4a — `/publish/review` Post-Upload Result Page (S096 META 8a/8)

> **Status**: shipped
> **Type**: route split (frontend) + minor type widening
> **Estimate**: M(10-12) → trim 至 XS(5)
> **Source**: prototype `Skills Hub Publish Flow.html` Step 3 + Engineering Handoff §2.6

## §1 Goal

S096d4 原 M(10-12) 含 publish flow 3-route split + SSE event stream + 3 new domain events。本 narrow trim 只 ship 「Step 3 review URL split」— 把既有 PublishPage inline success card 改成 navigate 到 `/publish/review?id=X` 獨立頁面。

User value: 上傳成功後 URL 變更，可分享 / bookmark；後續 user 可重新打開 URL 看 risk 結果（per Engineering Handoff §2.6 「Pre-filled frontmatter from validation」對應的「結果可重訪」spirit）。

Defer S096d5:
- /publish/validate?id=X poll page (Step 2 between upload + review)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)
- SSE event stream backend
- Polling on /publish/review for live risk_level update

## §2 Approach

### §2.1 Trim from M(10-12) → XS(5)

**Ship**:
- New `/publish/review?id={skillId}` route + `PublishReviewPage`
- PublishPage on upload success: `navigate('/publish/review?id=' + data.id)` 取代 inline success card
- Result page 用 existing `useSkill(id)` hook fetch + 顯 risk_level / metadata / 下載 / detail 跳轉
- RiskLevel type widened `'LOW' | 'MEDIUM' | 'HIGH'` → 加 `'NONE'`（per S096c shipped backend；前端 type 補齊）
- SkillDetailPage RISK_DESCRIPTION + RISK_TEXT_CLASS 加 NONE 條目（exhaustive Record 防漏）

**Defer**:
- Step 2 /publish/validate poll page
- 3 new domain events
- SSE event stream
- Auto-poll on /publish/review for live risk update

### §2.2 RiskLevel type alignment

Backend RiskLevel enum 已 4-tier (S096c) 但 frontend type still `'LOW' | 'MEDIUM' | 'HIGH'`. 本 spec 順便補齊（per ALWAYS rule 「verify a public signature change against every caller」 — backend 加 NONE 應該觸發 frontend type 同步，S096c 漏了）。

Patches:
- `types/skill.ts` RiskLevel union 加 NONE
- `SkillDetailPage` RISK_DESCRIPTION + RISK_TEXT_CLASS 加 NONE 條目（exhaustive Record 觸發 TS error 強制處理）

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Frontend `/publish/review?id=X` route | renders PublishReviewPage |
| AC-2 | PublishReviewPage with valid id | shows skill metadata + risk badge + 下載 CTA + detail navigate link |
| AC-3 | PublishReviewPage without id param | shows error callout + link back to /publish |
| AC-4 | PublishReviewPage with risk_level=null | shows「掃描中」 callout encouraging refresh |
| AC-5 | PublishReviewPage with risk_level=HIGH | shows danger callout「進入人工審核」 |
| AC-6 | PublishReviewPage with risk_level=NONE | shows success callout 「未發現任何 risk patterns — auto-published」 |
| AC-7 | PublishPage upload success | navigate to `/publish/review?id=X` (no inline card) |
| AC-8 | RiskLevel type 4-tier | TS compiles with NONE in union |
| AC-9 | Tests 28/28 PASS | regression check |
| AC-10 | Build ≤ 410KB JS | budget |

## §4 Implementation file plan

```
frontend/src/
├── pages/PublishReviewPage.tsx   ← NEW (~115 LOC)
├── pages/PublishPage.tsx          ← navigate on success (replace inline card)
├── App.tsx                        ← + /publish/review route
├── types/skill.ts                 ← RiskLevel +NONE
└── pages/SkillDetailPage.tsx      ← RISK_DESCRIPTION/CLASS exhaustive +NONE
```

不動 backend；不動 AppShell.

## §5 Test plan

- `npm test` — 28/28 PASS
- `npm run build` — TS strict compile + JS ≤ 410KB
- Manual smoke: upload skill → redirect to /publish/review?id=X；URL bookmark works

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 401.54 → 405.64KB (+4.10KB；PublishReviewPage)
- **CSS bundle**: 38.08 → 38.25KB (+0.17KB)
- **Build time**: 178ms（無 regression）
- **Files touched**: 5 + 1 spec doc
- **AC coverage**:
  - AC-1~8 impl ✓
  - AC-9 28/28 ✓
  - AC-10 405.64 < 410 ✓

ship as **v2.83.0** (M90d4a / S096 META sub-spec 8a/8)。

## §8 Notes for downstream sub-specs

- **S096d5 (defer)**: /publish/validate?id=X poll page (Step 2) + 3 new domain events + SSE event stream backend + auto-poll integration on /publish/review
- **S096 META progress**: 8a/8 ✅ 主 sub-spec 全 ship；剩 stub→full feature 升級（S096d5 / e2 / f2 / g2 / h2）持續陸續推
- **Live :8080 caveat**: backend 不變；前端 type widening 純 compile-time 改動
