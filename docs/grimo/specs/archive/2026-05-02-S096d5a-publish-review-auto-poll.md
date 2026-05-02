# S096d5a — Auto-poll on /publish/review while Scanning

> **Status**: shipped
> **Type**: micro polish (5-line change in single component)
> **Estimate**: M(10) → trim 至 XS(3)
> **Source**: prototype `Skills Hub Publish Step 2.html` polling spirit + Engineering Handoff §2.5

## §1 Goal

S096d4a 的 PublishReviewPage 在 risk scan async 跑時 user 必須手動重新整理才看到最新 risk_level。本 spec narrowest trim — 改用 react-query `refetchInterval` 2s auto-poll 直到 scan 完成，免手動 refresh.

S096d5 原 M(10) 含 Step 2 dedicated poll page + 3 domain events + SSE backend；本 narrow trim 只 ship auto-poll 核心 UX。其他 defer 至 S096d6.

## §2 Approach

### §2.1 Trim from M(10) → XS(3)

**Ship (this)**:
- PublishReviewPage 用 `useQuery` 取代 `useSkill`，加 `refetchInterval` callback：
  - `riskLevel == null` → 2000ms 重抓
  - `riskLevel` 設值（任一 NONE/LOW/MEDIUM/HIGH） → return false 停 poll
- 既有「掃描中」callout 加 spinner + 文字改「每 2 秒自動更新」

**Defer S096d6**:
- Dedicated `/publish/validate?id=X` Step 2 poll page (separate URL pre-review)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)
- SSE event stream backend (poll-based for now)
- Per-event UI animation (each event landing → push to event stream UI)

### §2.2 Why poll not SSE

SSE 為 Spring Boot 4 新 surface，需要 SseEmitter + event subscription wiring + frontend EventSource consumer + reconnect logic — 整合 risk medium。  
Poll 2s 簡單 + 無 framework risk + dev/prod 都跑。  
Step 2 dedicated page 也 defer — 目前 /publish/review 已 cover 核心需求（顯結果、auto-update、跳 detail page）。

### §2.3 react-query refetchInterval pattern

`refetchInterval` callback signature `(query) => number | false`：
- return number → continue polling
- return false → stop polling

Per react-query v5 docs；不需手動 setInterval / cleanup.

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | PublishReviewPage with risk_level=null | refetches every 2s |
| AC-2 | PublishReviewPage when risk_level transitions to LOW | poll stops automatically |
| AC-3 | PublishReviewPage when risk_level transitions to NONE/MEDIUM/HIGH | poll stops automatically |
| AC-4 | Scanning callout | shows spinner icon + 「每 2 秒自動更新」 text |
| AC-5 | Tests 28/28 PASS | regression check |
| AC-6 | Build ≤ 410KB JS | budget |

## §4 Implementation file plan

```
frontend/src/pages/PublishReviewPage.tsx ← swap useSkill → useQuery with refetchInterval
```

1 file change，~10 LOC. Imports cleanup + new fetchSkillById direct usage.

## §5 Test plan

- `npm test` — 28/28 PASS
- `npm run build` — JS ≤ 410KB
- Manual smoke: upload skill → /publish/review → callout 顯 spinner，Network tab 看到 2s 一次 fetch；scan 完成後 stop

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 28 → 28 PASS / 0 fail
- **JS bundle**: 405.64 → 405.86KB (+0.22KB；refetchInterval callback inline)
- **CSS bundle**: 38.25 → 38.25KB (無變)
- **Build time**: 189ms（無 regression）
- **Files touched**: 1 + 1 spec doc

ship as **v2.84.0** (M90d5a / S096 META 8b/8)。

## §8 Notes for downstream sub-specs

- **S096d6 (defer)**: dedicated /publish/validate Step 2 page + 3 new domain events + SSE backend + per-event UI animation
- **Live :8080 caveat**: backend 不變；前端 polling pattern 在 live 即生效（既有 GET /skills/{id} 持續可用）
