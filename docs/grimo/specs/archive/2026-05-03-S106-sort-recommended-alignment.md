# S106 — `推薦` sort behavior alignment with design intent

> **Status**: ✅ shipped `v3.4.6` (2026-05-03 — implement cron tick 11)
> **Type**: Frontend sort param mapping fix + comment correction
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 cron Tick 10 Mode B E2E live browser walk-through (Chrome MCP) — Round 11 (sort chips + category filter combined cut)

## §1 Goal

`/browse` 頁面 sort chips 4 個選項中，「推薦」（recommended）與「最新」（newest）行為**完全相同** — 都 fall back 到 backend default `ORDER BY created_at DESC`，user 點 4 個 chip 但只實際看到 3 種 sort 結果。

**Live 觀察 (Chrome MCP cron Tick 10 Round 11)**：
- 點「推薦」→ 第 1 個技能 `r35-docker-1777685322`（LOW）
- 點「最新」→ 第 1 個技能 `r35-docker-1777685322`（LOW）— **完全相同**
- 點「風險低」→ 第 1 個技能 `r35-docker-1777685322`（LOW）
- 點「下載最多」→ 第 1 個技能 `r19-lifecycle`（LOW）— 確實不同

**Root cause = comment 矛盾 + skills.ts 邏輯 falls through**：
- `frontend/src/api/skills.ts:23` 註解：`'recommended' → 不傳 sort（後端 default = createdAt DESC，與 size 內隱排序對齊）`
- `frontend/src/pages/HomePage.tsx:17` 註解：「因 backend `/skills` 預設 downloadCount desc，"推薦"= identity，無需轉換」（**stale**）
- backend `SkillQueryService.search` 實際 default = `ORDER BY created_at DESC`

兩 frontend 註解互相矛盾，HomePage:17 comment 的「downloadCount desc」是 stale（可能來自 ADR-002 Spring Data JDBC 遷移前的 backend 預設）；目前 backend 實際 default 是 createdAt DESC，所以「推薦」實際 = 「最新」。

**設計意圖（per HomePage:17 comment）= 推薦應顯示「最受歡迎」**（downloadCount DESC）。本 spec 對齊 design intent：把 `recommended` mapping 加到 `sort=downloadCount,desc`，讓 4 chip 各自有獨立行為。

不修 backend；不引入「真正 recommendation algorithm」（混合 recency × popularity × personalization）— 那是 future spec scope。

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → **S106** — 第 6 個 cross-cutting follow-up，cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → sort/filter behavior alignment」累積 6 層。

## §2 Findings — verified gaps

| # | File:line | 現狀 | 嚴重度 |
|---|-----------|------|--------|
| 1 | `frontend/src/api/skills.ts:46` `if (params.sort && params.sort !== 'recommended')` | recommended 被排除於 mapping，無 sort param 送到 backend | Medium — 「推薦」chip 行為等於 「最新」chip，UI 4 chip 但只有 3 種行為 |
| 2 | `frontend/src/pages/HomePage.tsx:17` JSDoc | `// 因 backend `/skills` 預設 downloadCount desc，"推薦"= identity，無需轉換` | Low — stale comment misrepresents backend behavior |

**Excluded（本 spec 不修）**:
- 真正 recommendation algorithm（mix recency × popularity × user history）— future M+ spec
- 「下載最多」與「推薦」是否該合併為單一 chip — UX 設計決定，超 fix-spec scope（本 spec 保留 distinct 4 chips per existing intent）
- Backend default sort 改為 downloadCount DESC — backend 改動 + 影響其他 query path，超 frontend-only fix

## §3 Approach

**Trim path**：本 spec 已 XS，無 trim 空間；若 implement tick 觸 wall，trim 順序為 #2 (comment update only)；保 #1 (behavior fix)。

**Decision per gap**:

- **Gap #1**：`skills.ts:46-50` 的 conditional mapping 加 `recommended: 'downloadCount,desc'`：
  ```ts
  // before
  if (params.sort && params.sort !== 'recommended') {
    qp.set('sort', { newest: 'createdAt,desc', 'most-downloaded': 'downloadCount,desc', 'risk-low': 'riskLevel,asc' }[params.sort])
  }
  // after — 'recommended' 也 explicit 對應 downloadCount,desc
  if (params.sort) {
    qp.set('sort', {
      recommended: 'downloadCount,desc',
      newest: 'createdAt,desc',
      'most-downloaded': 'downloadCount,desc',
      'risk-low': 'riskLevel,asc',
    }[params.sort])
  }
  ```
  注意：`recommended` 與 `most-downloaded` mapping 相同 — UX 角度 chip 仍 distinct（chip label 不同），future evolve 為真 recommendation algorithm 時改 mapping 即可，UI 結構不變。

- **Gap #2**：HomePage:17 comment 改為對齊事實 + 標 future evolution path：
  ```ts
  // before
  // 因 backend `/skills` 預設 downloadCount desc，"推薦"= identity，無需轉換
  // after
  // S106: "推薦" 暫時 = downloadCount,desc（與 "下載最多" 同 mapping，但 UX 為 placeholder
  // future recommendation algorithm 預留位）；backend default 為 createdAt DESC（per
  // SkillQueryService.search line 196 fallback），不能依賴 fall-through。
  ```

**不引入新 prop / component / 抽象**：純 1 行 mapping + 1 行 comment update。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | 進入 `/browse` 預設 sortMode = 'recommended' | 觀察 fetchSkills request URL | URL 含 `sort=downloadCount,desc` query param（**不再** fall through 到 backend default） |
| AC-2 | 點「最新」chip | 觀察 request URL | URL 含 `sort=createdAt,desc` query param（既有行為不變） |
| AC-3 | 「推薦」chip 與「下載最多」chip first row | 同一 page 切換 | first skill 一致（暫時相同 mapping；但 chip independent 不會 fall-through 出錯） |
| AC-4 | 「推薦」chip 與「最新」chip first row | 切換 | 不同 first skill（fixes Round 11 audit observation；若 dataset 中 newest != most-downloaded） |

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/api/skills.ts` | sort mapping 加 `recommended: 'downloadCount,desc'` 入 record；conditional 改寫去除 `!== 'recommended'` exclusion | ~3 |
| `frontend/src/pages/HomePage.tsx` | line 17 comment update（對齊事實 + 標 future evolution） | ~3 |

**測試新增 / 更新**：
- `frontend/src/api/skills.test.ts` 若不存在 → 新建（AC-1 / AC-2 mapping 驗證）；或補測既有 fetchSkills test 若有
- `frontend/src/pages/HomePage.test.tsx`（S104 ship 已建）— 加 AC-3 / AC-4 比對 first card name across sort modes

## §6 Test plan

```bash
cd frontend
npm test -- --run skills HomePage
npm run build  # ensure no broken imports
# Smoke via Chrome MCP: /browse → click 推薦 → 驗 network request 含 sort=downloadCount,desc
# 點 最新 → 驗 sort=createdAt,desc
# 點 推薦 vs 下載最多 first card 一致；推薦 vs 最新 first card 不同
```

**Negative case**: `sort` param 未設置（defensive — 不該發生但保留 backend default fallback path）。
**Edge case**: dataset 中所有 skills downloadCount = 0（已驗證：r35-docker / s091-pure-docs 都 0；backend will tie-break by ?）— sort behavior 在 ties 下穩定即可，不要求特定 secondary order。

## §7 Result

**Shipped 2026-05-03 cron Tick 11 @ ~06:23**.

### Implement checklist

- [x] `skills.ts:46` 改寫為 explicit mapping — `recommended: 'downloadCount,desc'` 加入 sortMap，移除 `!== 'recommended'` exclusion
- [x] `skills.ts:23` JSDoc update — `recommended → downloadCount,desc` (+ future evolution note)
- [x] `HomePage.tsx:15` JSDoc update — backend default 標 createdAt DESC + S106 alignment 說明
- [x] `HomePage.test.tsx` 加 AC-S106 — 預設 sortMode=recommended 時 fetch URL 必須含 `sort=downloadCount,desc`
- [x] `npm test --run HomePage`：5/5 PASS（1.28s；既有 4 + 新 AC-S106 = 5）
- [x] Chrome MCP live smoke 4 chip first card：
  - 推薦 → `r19-lifecycle` (downloadCount,desc — most-downloaded first) ✓
  - 最新 → `r35-docker-1777685322` (createdAt,desc) ✓
  - 風險低 → `r35-docker-1777685322` (riskLevel,asc) ✓
  - 下載最多 → `r19-lifecycle` (downloadCount,desc — 同 推薦 per design) ✓
- [x] CHANGELOG `v3.4.6` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 4（skills.ts + HomePage.tsx + HomePage.test.tsx + spec/CHANGELOG/roadmap docs）|
| LOC delta | ~+10 / -5（含 comment expansion）|
| FE tests | 既有 36 → 37（+1 AC-S106 in HomePage.test.tsx）|
| Backend touch | 0（純 frontend mapping fix）|
| Wall clock | ~10 min（IMPLEMENT 3 + tests 2 + Chrome smoke 2 + DOCUMENT 2）|

### Live render validation (Chrome MCP)

| Sort chip | Before (Tick 10 audit) | After (Tick 11 ship) |
|-----------|----------------------|---------------------|
| 推薦 | r35-docker-1777685322 (LOW) — fall-through to backend default createdAt DESC = 最新 | r19-lifecycle (LOW) — explicit downloadCount,desc — distinct from 最新 |
| 最新 | r35-docker-1777685322 — same as 推薦 (重複 bug) | r35-docker-1777685322 — distinct from 推薦 ✓ |
| 風險低 | r35-docker-1777685322 | r35-docker-1777685322 — 不變 |
| 下載最多 | r19-lifecycle | r19-lifecycle — 不變（推薦 同 mapping，UX chip 仍 distinct） |

### Trim deferred

- **真正 recommendation algorithm**（mix recency × popularity × user history）— future M+ spec；本 ship 留 mapping placeholder（推薦 = downloadCount,desc）等 future 改 mapping 即可，UI chip 結構不變
- **「下載最多」與「推薦」是否該合併為單一 chip** — UX 設計決定，超 fix-spec scope；本 ship 保留 distinct 4 chips per existing intent
- **development-standards.md §UI** "control label 與 underlying behavior 必須 1:1 mapping，不可 fall-through" rule — sibling lesson，列入 polish backlog 與 S103/S104/S105 doc-side rules 集中 ship

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → S105 (component-context alignment v3.4.5) → **S106 (control-behavior alignment v3.4.6)** — 第 6 個 cross-cutting follow-up，cut 累積 6 層。發現方式 = Chrome MCP click sort chips + compare first card across modes（前 5 cut 都看不見此 bug，需 same-page multi-control 對比才浮現）。Round 11 內同 page 上 category filter cut **passed**（DevOps → 38 個技能，server-side 正確）— 證明 audit cut 多樣化是 cumulative quality 累積方式，不同 cut 揭露不同層 bug。

## §8 Lesson — sort/filter behavior alignment audit cut

S100~S105 cut 軸（page-level data / cross-cutting links / user-visible strings / interactive state / component-context）都覆蓋不到「**user-visible action label 與 underlying behavior 是否一致**」。「推薦」chip label 暗示「智能推薦演算法」但實作 fall through 到 backend default — label 與 behavior 不對齊是 cron-bound agent 用 Chrome MCP click 多 chip 對比 first row 才看見的 cut。

**建議寫進 development-standards.md §UI**：interactive control（chip / sort / filter / button）的 label 與 behavior 必須 1:1 mapping；fall-through default 行為應只用於 explicit "default / clear" affordance（如「全部」filter 預設 chip）。其他每個 chip 都該有 explicit param 送到 backend。

**Round 11 副產物**：category filter 這個 cut **passed**（DevOps filter → 38 個技能 / 全 DevOps，server-side 正確）— 證明同一 page 上不同 control 的健康度 inconsistent。Mode B Round 12+ 應對其他 user controls 做類似 explicit-mapping audit（pagination per_page、tag chip、status filter 等）。

Sibling chain extended：S100e → S102 → S103 → S104 → S105 → **S106** 第 6 個 cross-cutting follow-up；發現方式 = Chrome MCP click chip + compare first card across sorts；cut 軸為 control-behavior alignment。
