# S035: Frontend Suspended Detail Page UX（隱藏下載 + 顯示提示橫幅）

> Spec: S035 | Size: XS(5) | Status: ✅ Done — target ship `v2.12.0`
> Date: 2026-05-01
> Depends: S028 ✅ + S029 ✅
> Trigger: 2026-05-01 /loop tick 10 — SUSPENDED skill 詳情頁仍渲染下載按鈕（按 `latestVersion` 條件渲染）；user click 後 navigate 至後端 raw JSON 403 SKILL_SUSPENDED，不是友善 UI 訊息。

---

## 1. Goal

`SkillDetailPage`：
1. SUSPENDED 狀態時 **不渲染下載按鈕**（避免 user 點擊後落到 raw 403 JSON 頁面）
2. SUSPENDED 狀態時 **顯示提示橫幅**：「此技能已被停用，無法下載」（zh-TW，per CLAUDE.md UI 語言慣例）
3. SUSPENDED 狀態時 **隱藏「新增版本」表單**（PUT version 對 SUSPENDED 可能拋 409；防 user 走死路）

DRAFT 與 PUBLISHED 行為不變；S028 既有 status badge 渲染保留。

---

## 2. Approach

### 2.1 SkillDetailPage diff

```diff
-{skill.latestVersion && (
+{skill.latestVersion && skill.status === 'PUBLISHED' && (
   <a href={`/api/v1/skills/${skill.id}/download`} className="...">
     <Download /> 下載
   </a>
 )}

+{skill.status === 'SUSPENDED' && (
+  <div className="mb-6 rounded-md border border-destructive/40 bg-destructive/10 p-4">
+    <p className="text-sm font-medium text-destructive">此技能已被停用，無法下載</p>
+    <p className="mt-1 text-xs text-muted-foreground">
+      被停用的技能仍會保留紀錄，但下載端點已停用。如需恢復請聯絡管理員。
+    </p>
+  </div>
+)}

 <Tabs defaultValue="overview">
   ...
   <TabsContent value="versions" className="mt-4">
     <VersionList versions={versions ?? []} />
-    <AddVersionForm skillId={id ?? ''} />
+    {skill.status !== 'SUSPENDED' && <AddVersionForm skillId={id ?? ''} />}
   </TabsContent>
```

### 2.2 為何 NOT 在 download anchor onClick fetch

考慮過：保留按鈕，onClick 用 fetch 取代 native link，403 時 toast 顯示錯誤。否決：
- 增加 React state、需 toast 元件、複雜度高
- SUSPENDED 是已知 server-side 拒絕；client 可用 status 判斷預先 hide，比 request 後再處理乾淨

### 2.3 為何提示用 destructive variant 而非 warning

SUSPENDED 的政策 / 安全意涵明確（per S018 / S029 設計），用 destructive 讓 user 認知這是 hard block，不是 soft warning。對齊 S028 SkillDetailPage 既有 SUSPENDED Badge variant=destructive。

### 2.4 為何 SUSPENDED 也要隱藏「新增版本」表單

對 SUSPENDED skill 嘗試 PUT 新版本：
- backend `addVersion` 內部 `skill.recordVersionPublished(version)` → SkillStatus state machine：SUSPENDED 沒有 `publish()` transition → 拋 IllegalStateException → S030 mapping → 409 STATE_CONFLICT
- user 在 UI 上提交後得到失敗訊息，UX 差

預先在 UI 隱藏表單是更明確的 affordance — admin 必須先 reactivate 才能加版本。

---

## 3. SBE Acceptance Criteria

### AC-1: SUSPENDED skill detail page 不渲染下載按鈕

```gherkin
Given alice 已上傳 skill A 並 suspend
When  渲染 <SkillDetailPage skill={A}>
Then  DOM 不含 `<a href="/api/v1/skills/{A}/download">`
```

### AC-2: SUSPENDED skill detail page 顯示「已停用」提示橫幅

```gherkin
Given suspended skill A
When  渲染 detail page
Then  存在含 "此技能已被停用，無法下載" 的 banner
And   banner 為 destructive variant（border / bg destructive 色）
```

### AC-3: SUSPENDED skill detail page 隱藏新增版本表單

```gherkin
Given suspended skill A
When  切到「版本歷史」tab
Then  顯示既有 VersionList
And   不顯示 AddVersionForm（h4 "新增版本" + form）
```

### AC-4: PUBLISHED skill detail page 行為不變

```gherkin
Given PUBLISHED skill B
When  渲染 detail page
Then  下載按鈕渲染（既有行為）
And   無「已停用」提示橫幅
And   AddVersionForm 顯示
```

### AC-5: DRAFT skill detail page 行為部分變化

```gherkin
Given DRAFT skill C（無 version）
When  渲染 detail page
Then  下載按鈕不渲染（既有行為：`skill.latestVersion` 為 null；新加 `status==='PUBLISHED'` 條件不影響）
And   無「已停用」提示橫幅
And   AddVersionForm 顯示（DRAFT 可加版本以 promote 至 PUBLISHED）
```

### AC-6: 既有 frontend test 不破

```gherkin
Given S035 改動完成
When  cd frontend && npm test && npx tsc -b && npm run lint
Then  全 PASS
```

---

## 4. Interface

詳 §2.1 diff。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：
  - 下載 anchor 條件加 `&& skill.status === 'PUBLISHED'`
  - 加 SUSPENDED banner（Card / Tailwind divs）
  - AddVersionForm 條件加 `skill.status !== 'SUSPENDED'`

### 5.2 Docs
- CHANGELOG `v2.12.0`
- spec-roadmap M31

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SkillDetailPage 三處改動 + npm test/tsc/lint 驗證 | AC-1~6 | 🔲 |

POC: not required（純 conditional rendering；既有 status type 已 cover SUSPENDED per S028）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.12.0`
>
> Verification: `npm test` 10/10 PASS；`tsc -b` 0 errors；`npm run lint` 0 warnings。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `cd frontend && npm test` | 10/10 PASS |
| `cd frontend && npx tsc -b` | 0 type errors |
| `cd frontend && npm run lint` | 0 errors / 0 warnings |
| Visual code review | 三處 conditional rendering 對齊 S028/S029 backend 行為 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：
  - 下載 anchor 條件加 `&& skill.status === 'PUBLISHED'`
  - 加 SUSPENDED banner（destructive variant；含「此技能已被停用，無法下載」+ admin 聯絡指示）
  - AddVersionForm 條件加 `skill.status !== 'SUSPENDED'`

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: SUSPENDED 不渲染下載按鈕 | ✅ PASS | conditional rendering 改為 `latestVersion && status === 'PUBLISHED'` |
| AC-2: SUSPENDED 顯示「已停用」提示橫幅 | ✅ PASS | banner with destructive variant + 中文 wording |
| AC-3: SUSPENDED 隱藏新增版本表單 | ✅ PASS | conditional `status !== 'SUSPENDED'` 包 AddVersionForm |
| AC-4: PUBLISHED 行為不變 | ✅ PASS | conditional logic 對 PUBLISHED 等價於既有 `latestVersion` 條件 |
| AC-5: DRAFT 部分行為變化 | ✅ PASS | 下載按鈕仍不渲染（latestVersion 為 null）；AddVersionForm 仍顯示 |
| AC-6: 既有 frontend test 不破 | ✅ PASS | 10/10 tests + 0 type errors + 0 lint warnings |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 10 — SUSPENDED skill 詳情頁仍渲染下載按鈕（基於 `latestVersion`）；user click 後直接 navigate 到後端 raw 403 JSON 頁面，無友善 UI 提示。

**Fix design rationale**:
- 純 conditional rendering — 既有 status type union（per S028）已 cover SUSPENDED，無新型別需求
- 三層 affordance 鎖定：下載按鈕、提示橫幅、新增版本表單；user 在每個入口都有清楚提示
- destructive variant 對齊 S028 既有 SUSPENDED Badge 視覺語意（hard block，不是 soft warning）
- AddVersionForm 預先隱藏優於 user 提交後得 409 錯誤訊息 — UI affordance principle: don't let users start what they can't finish

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint（用於管理員 reactivate 操作）仍待設計；當前 admin 須直接呼叫 API 或在 detail page 看到 SUSPENDED banner 後手動聯絡。
