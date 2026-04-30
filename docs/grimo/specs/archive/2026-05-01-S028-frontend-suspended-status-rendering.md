# S028: Frontend SUSPENDED Status Rendering（type union + label + badge）

> Spec: S028 | Size: XS(5) | Status: ✅ Done — target ship `v2.5.0`
> Date: 2026-05-01
> Depends: S018 ✅（backend SUSPENDED state machine）+ S027 ✅
> Trigger: 2026-05-01 /loop tick 3 — frontend `SkillStatus` type 為 `'DRAFT' | 'PUBLISHED'`，缺 `'SUSPENDED'`；SkillDetailPage label fallback 直接顯示英文 raw string；SkillCard 不顯示 status badge

---

## 1. Goal

frontend `SkillStatus` type union 補齊 `'SUSPENDED'`，與 backend `SkillStatus` enum 同步（per S018 三狀態機 DRAFT → PUBLISHED → SUSPENDED）。SkillDetailPage label 完整中譯；SkillCard 對非 PUBLISHED 狀態顯示明顯 status badge（DRAFT / SUSPENDED 都該被使用者看到）。

---

## 2. Approach

### 2.1 Type sync

`frontend/src/types/skill.ts`：

```diff
-export type SkillStatus = 'DRAFT' | 'PUBLISHED'
+export type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'
```

對齊 `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillStatus.java`（DRAFT / PUBLISHED / SUSPENDED 三 enum constants）。

### 2.2 SkillDetailPage label 中譯

`frontend/src/pages/SkillDetailPage.tsx` 既有 line 73-74：

```diff
-<Badge variant={skill.status === 'DRAFT' ? 'secondary' : 'default'}>
-  {skill.status === 'DRAFT' ? '草稿' : skill.status === 'PUBLISHED' ? '已發佈' : skill.status}
+<Badge variant={statusBadgeVariant(skill.status)}>
+  {STATUS_LABEL[skill.status]}
 </Badge>
```

加常數 map：
```typescript
const STATUS_LABEL: Record<SkillStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已發佈',
  SUSPENDED: '已停用',
}

function statusBadgeVariant(s: SkillStatus): 'default' | 'secondary' | 'destructive' {
  switch (s) {
    case 'DRAFT': return 'secondary'
    case 'PUBLISHED': return 'default'
    case 'SUSPENDED': return 'destructive'
  }
}
```

### 2.3 SkillCard status badge

`frontend/src/components/SkillCard.tsx` 對 `skill.status !== 'PUBLISHED'` 顯示 status badge（避免 happy path 整個 list 都加 PUBLISHED badge 視覺噪音）：

```diff
 <div className="mt-3 flex items-center gap-3 text-xs text-muted-foreground">
   <Badge variant="secondary" className="text-xs">{skill.category}</Badge>
+  {skill.status !== 'PUBLISHED' && (
+    <Badge variant={skill.status === 'SUSPENDED' ? 'destructive' : 'outline'} className="text-xs">
+      {skill.status === 'SUSPENDED' ? '已停用' : '草稿'}
+    </Badge>
+  )}
   {skill.latestVersion && ...}
```

### 2.4 為何 NOT 過濾 SUSPENDED 從列表（記為 tech debt）

選 frontend rendering fix 而非 backend filter：
- backend filter SUSPENDED 從 list/search 改變 API 行為，影響 admin 面板（admin 需看 SUSPENDED 才能 reactivate）
- 是否「公開列表 hide SUSPENDED + admin 列表 show all」屬產品決策，需 PRD 章節
- 留待 future spec（如 S029 admin panel）整體設計

---

## 3. SBE Acceptance Criteria

### AC-1: SkillStatus type 含 SUSPENDED

```gherkin
Given frontend/src/types/skill.ts
When  TypeScript compile
Then  type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'
And   下列 statement 不報錯：const s: SkillStatus = 'SUSPENDED'
```

### AC-2: SkillDetailPage 對 SUSPENDED 顯示「已停用」+ destructive variant

```gherkin
Given Skill { status: 'SUSPENDED', ... }
When  render <SkillDetailPage>
Then  Badge text 為 "已停用"
And   Badge variant 為 'destructive'
And   不顯示英文 'SUSPENDED'
```

### AC-3: SkillCard 對非 PUBLISHED 狀態顯示 badge

```gherkin
Given Skill { status: 'SUSPENDED', ... }
When  render <SkillCard skill={skill} />
Then  存在 Badge 顯示 "已停用"

Given Skill { status: 'DRAFT', ... }
When  render <SkillCard skill={skill} />
Then  存在 Badge 顯示 "草稿"

Given Skill { status: 'PUBLISHED', ... }
When  render <SkillCard skill={skill} />
Then  不顯示 status badge（避免 happy path 視覺噪音）
```

### AC-4: 既有 frontend test (SkillCard.test.tsx) 不破

```gherkin
Given S028 改動完成
When  cd frontend && npm test
Then  既有 test 全 PASS
```

---

## 4. Interface

詳 §2 各 diff。

---

## 5. File Plan

### 5.1 Frontend (3 files)
- `frontend/src/types/skill.ts`（type union 加 SUSPENDED）
- `frontend/src/pages/SkillDetailPage.tsx`（label map + variant function）
- `frontend/src/components/SkillCard.tsx`（status badge for non-PUBLISHED）

### 5.2 Docs
- `docs/grimo/CHANGELOG.md`：v2.5.0 entry
- `docs/grimo/specs/spec-roadmap.md`：S028 ✅ + M24 entry

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | 三 file 改 + npm test 驗證 | AC-1~4 | 🔲 |

POC: not required（純 type union + label sync；無新 dependency）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.5.0`
>
> Verification: `npm test` 10/10 PASS；`npx tsc -b` 0 type errors；`npm run lint` 0 errors。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `cd frontend && npm test` | 10 tests / 0 fail（既有 SkillCard.test.tsx + smoke test 含新 type union 不破）|
| `cd frontend && npx tsc -b` | 0 type errors（TypeScript build 通過 — `Record<SkillStatus, string>` exhaustive；switch case exhaustive）|
| `cd frontend && npm run lint` | 0 errors / 0 warnings |

### 7.2 Files Changed

#### Frontend (3 files)
- `frontend/src/types/skill.ts`（type union 加 SUSPENDED + Javadoc 同步 backend SkillStatus enum 註解）
- `frontend/src/pages/SkillDetailPage.tsx`（加 `STATUS_LABEL` Record map + `statusBadgeVariant` switch；replace 既有 inline ternary）
- `frontend/src/components/SkillCard.tsx`（對 `skill.status !== 'PUBLISHED'` 顯示 status badge — DRAFT outline / SUSPENDED destructive）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: SkillStatus type 含 SUSPENDED | ✅ PASS | `tsc -b` 通過；`Record<SkillStatus, string>` 三鍵 exhaustive |
| AC-2: SkillDetailPage 對 SUSPENDED 顯示「已停用」+ destructive variant | ✅ PASS | code review confirmed；`STATUS_LABEL[SUSPENDED] = '已停用'`；`statusBadgeVariant('SUSPENDED') === 'destructive'` |
| AC-3: SkillCard 對非 PUBLISHED 狀態顯示 badge | ✅ PASS | code review confirmed；conditional `{skill.status !== 'PUBLISHED' && ...}` block |
| AC-4: 既有 frontend test 不破 | ✅ PASS | 10/10 tests PASS |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 3 — 系統測試發現 frontend `SkillStatus` type 與 backend `SkillStatus` enum 不同步。Backend 三狀態（DRAFT / PUBLISHED / SUSPENDED；per S018 state machine），frontend 只宣告兩狀態。SkillDetailPage 既有 line 73-74 的 ternary fallback `: skill.status` 對 SUSPENDED 會直接顯示英文 raw string；SkillCard 完全不顯示 status。

**Fix design**:
- Type 補齊 — 對齊 backend enum；TypeScript exhaustive switch 防漏
- `Record<SkillStatus, string>` map + `statusBadgeVariant` switch — 取代 inline ternary，新增狀態時 compiler 強制要求補對應
- SkillCard 只對 non-PUBLISHED 顯示 badge — 大多數 skill 是 PUBLISHED 屬 happy path，不加視覺噪音；DRAFT/SUSPENDED 是 minority 應引起注意

### 7.5 Pending Verification / Tech Debt

**Tech debt — list filtering by status**：backend `SkillQueryService.search` 不過濾 status，DRAFT 與 SUSPENDED skills 都出現在公開 list。是否「公開列表 hide SUSPENDED + admin 列表 show all」屬產品決策，需 PRD 章節定義。本 spec 採 frontend rendering fix 而非 backend filter（後者改 API 行為影響 admin 面板）。留待 future spec（S029 admin panel 設計時整體決定）。
