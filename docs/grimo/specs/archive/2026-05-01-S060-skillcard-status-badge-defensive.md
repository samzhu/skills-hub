# S060: SkillCard Status Badge — Defensive Undefined Check

> Spec: S060 | Size: XS(5) | Status: ✅ Done — target ship `v2.37.0`
> Trigger: 2026-05-01 /loop tick 32 §7.5 — Chrome E2E HomePage semantic mode 結果卡片全部顯「草稿」badge，即使 backend 結果（per S059 過濾）皆 PUBLISHED。根因：`SemanticSearchResult` JSON 沒 `status` field（vector_store metadata 寫入時沒帶 status；SemanticSearchResult record 也無）；SkillCard `skill.status !== 'PUBLISHED'` 對 undefined 評估為 truthy → 誤顯 badge。

---

## 1. Goal

`SkillCard` status badge 條件加 truthy guard：`skill.status && skill.status !== 'PUBLISHED'`。undefined / null 視為「不確定，不顯 badge」— 對齊 S059 invariant（semantic 結果皆 PUBLISHED）。

---

## 2. Approach

### 2.1 Code diff

```diff
-{skill.status !== 'PUBLISHED' && (
+{skill.status && skill.status !== 'PUBLISHED' && (
   <Badge ...>...</Badge>
 )}
```

### 2.2 為何 frontend defensive 而非 backend 補欄位

- Option A（frontend defensive）：1-line fix；對齊 S059 invariant；防 SemanticSearchResult 永遠不會帶 status 的設計
- Option B（backend 補 status）：要改 SearchProjection metadata 寫入 + SemanticSearchResult record + 重 index 既有 rows — 範圍大
- 採 A：S059 已保證 semantic 結果 PUBLISHED，undefined→不顯 badge 安全

### 2.3 為何不 default to PUBLISHED

```tsx
// 不選此寫法
{(skill.status ?? 'PUBLISHED') !== 'PUBLISHED' && (...)}
```
語意把「不知道」誤譯為「PUBLISHED」— 若未來其他 path 傳入 undefined（非 S059 場景）也會誤判。truthy guard 更保守：「不知道 → 不主張」。

---

## 3. SBE Acceptance Criteria

### AC-1: PUBLISHED skill 不顯 badge（既有不破）

```gherkin
Given skill with status="PUBLISHED"
Then  SkillCard 不顯 status badge
```

### AC-2: DRAFT skill 仍顯「草稿」badge（既有不破）

```gherkin
Given skill with status="DRAFT"
Then  SkillCard 顯「草稿」outline badge
```

### AC-3: SUSPENDED skill 仍顯「已停用」destructive badge（既有不破）

```gherkin
Given skill with status="SUSPENDED"
Then  SkillCard 顯「已停用」destructive badge
```

### AC-4: undefined status（semantic results）不顯 badge

```gherkin
Given semantic result without status field（per S059 保證為 PUBLISHED）
Then  SkillCard 不顯 status badge
```

### AC-5: 既有 frontend test 不破

```gherkin
When  npm test
Then  10 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/components/SkillCard.tsx`：status badge 條件加 truthy guard

### 5.2 Test
- 既有 vitest 不破；E2E Chrome 驗 keyword vs semantic mode

### 5.3 Docs
- CHANGELOG `v2.37.0`
- spec-roadmap M56

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | guard + Chrome E2E | AC-1~5 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.37.0`
>
> Verification: vitest 10 / 0 fail；Chrome E2E semantic mode 9 cards `draftCount=0` `suspendCount=0`，badge 不再誤顯。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 10 / 0 fail ✓ AC-5 |
| Chrome semantic mode 9 cards | 0 個 「草稿」 + 0 個「已停用」 badge ✓ AC-4 |
| Chrome keyword mode | DRAFT/SUSPENDED 仍正常顯 badge（既有不破）✓ AC-2/3 |
| Chrome PUBLISHED skills | 不顯 badge（既有不破）✓ AC-1 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/components/SkillCard.tsx`：status badge 條件加 truthy guard `skill.status &&`

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: PUBLISHED 不顯 badge | ✅ |
| AC-2: DRAFT 顯「草稿」 | ✅ |
| AC-3: SUSPENDED 顯「已停用」 | ✅ |
| AC-4: undefined 不顯 badge | ✅ |
| AC-5: 既有 test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 32 §7.5 — Chrome semantic 結果卡片誤顯「草稿」 badge；S059 已保證結果皆 PUBLISHED 但 frontend SkillCard 不知道（SemanticSearchResult 缺 status field）。

**Fix scope choice**:
- 採 frontend defensive `skill.status &&` 1-line fix
- 不改 backend SemanticSearchResult 加 status field — 範圍守 XS；S059 invariant 已保證
- truthy guard 比 `?? 'PUBLISHED'` 默認值更保守（「不知道 → 不主張」優於「不知道 → 假定健康」）

### 7.5 Pending Verification / Tech Debt

- DB 既有畸形 entries 仍待 migration（S058+ 累積）
- 暫無新 tech debt
