# S047: Installation Guide Only Shown for PUBLISHED Skill

> Spec: S047 | Size: XS(5) | Status: ✅ Done — target ship `v2.24.0`
> Trigger: 2026-05-01 /loop tick 22 Chrome E2E — DRAFT skill 詳情頁概要 tab 顯示「安裝指引：下載 zip 後解壓...」但實際**沒下載按鈕**+「版本歷史」顯「尚無版本記錄」。安裝指引引導 user 去做不存在的事情。SUSPENDED 也類似 — 已有「無法下載」banner，install guide 顯多餘且容易誤解。

---

## 1. Goal

`SkillDetailPage` 概要 tab 只在 `status === 'PUBLISHED'` 時顯示「安裝指引」區塊；DRAFT / SUSPENDED 隱藏。對齊 download button 隱藏邏輯（PUBLISHED only）— 避免「安裝指引 vs 無法下載」UX 矛盾。

```diff
 <TabsContent value="overview" className="mt-4">
   <div className="prose max-w-none">
     <h3 className="text-lg font-semibold">描述</h3>
     <p className="text-muted-foreground">{skill.description}</p>
   </div>
+  {skill.status === 'PUBLISHED' && (
   <div className="mt-6 rounded-md border bg-muted/50 p-4">
     <h4 className="mb-2 text-sm font-semibold">安裝指引</h4>
     ...
   </div>
+  )}
 </TabsContent>
```

---

## 2. Approach

### 2.1 Code diff

```diff
 <TabsContent value="overview" className="mt-4">
   <div className="prose max-w-none">
     <h3 className="text-lg font-semibold">描述</h3>
     <p className="text-muted-foreground">{skill.description}</p>
   </div>
-  <div className="mt-6 rounded-md border bg-muted/50 p-4">
+  {/* S047: 安裝指引只對 PUBLISHED 顯示 — DRAFT 沒下載 / SUSPENDED 已 block download */}
+  {skill.status === 'PUBLISHED' && (
+  <div className="mt-6 rounded-md border bg-muted/50 p-4">
     <h4 className="mb-2 text-sm font-semibold">安裝指引</h4>
     <p className="text-sm text-muted-foreground">
       下載 zip 後解壓，將資料夾放到：
     </p>
     <code className="mt-1 block text-sm">~/.claude/skills/（系統級）</code>
     <code className="block text-sm">或 &lt;project&gt;/.claude/skills/（專案級）</code>
-  </div>
+  </div>
+  )}
 </TabsContent>
```

### 2.2 為何 NOT 換 install guide 為「請先發佈版本」hint

過度設計：
- 「版本歷史」tab 已有「尚無版本記錄」+ Add Version form 引導 user
- 概要 tab 本來就是「給 read user 看摘要」，不應 mix 起 publish workflow
- 隱藏 install guide 後 DRAFT 概要乾淨清爽 — 描述 + status 已足夠

### 2.3 為何 NOT 給 SUSPENDED 顯示「Reactivate 後可下載」hint

SUSPENDED 已有 destructive banner「此技能已被停用，無法下載」+「如需恢復請聯絡管理員」(S035)；install guide 是 user-side 動作，與 admin reactivate 無關。

---

## 3. SBE Acceptance Criteria

### AC-1: PUBLISHED 顯示安裝指引（既有行為不破）

```gherkin
When  user 進入 PUBLISHED skill 詳情頁的「概要」tab
Then  顯示「安裝指引：下載 zip 後解壓...」
```

### AC-2: DRAFT 隱藏安裝指引

```gherkin
When  user 進入 DRAFT skill 詳情頁的「概要」tab
Then  不顯示「安裝指引」區塊
And   仍顯示「描述」區塊
```

### AC-3: SUSPENDED 隱藏安裝指引

```gherkin
When  user 進入 SUSPENDED skill 詳情頁的「概要」tab
Then  不顯示「安裝指引」區塊
And   仍顯示「描述」區塊
And   仍顯示「此技能已被停用，無法下載」banner（S035 不破）
```

### AC-4: 既有 frontend 測試不破

```gherkin
When  npm test
Then  既有 vitest 全綠
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：安裝指引 wrap `{skill.status === 'PUBLISHED' && (...)}`

### 5.2 Test
- 既有 frontend test 不破即可；E2E 由 Chrome 手測（3 個 status × overview tab）

### 5.3 Docs
- CHANGELOG `v2.24.0`
- spec-roadmap M43

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | conditional render + Chrome 3 status 驗 | AC-1~4 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.24.0`
>
> Verification: vitest 10 tests / 0 fail；Chrome E2E 三狀態驗 — PUBLISHED skill 顯安裝指引 ✓；DRAFT 隱藏指引仍顯描述 ✓；SUSPENDED 隱藏指引 + 描述 + 既有「此技能已被停用」banner 不破 ✓。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 2 files / 10 tests passed |
| Chrome `eb95510a-...`（PUBLISHED）| `hasInstallGuide: true` ✓ AC-1 |
| Chrome `5ff08ab4-...`（DRAFT）| `hasInstallGuide: false` + `hasDescription: true` ✓ AC-2 |
| Chrome `0636a030-...`（SUSPENDED）| `hasInstallGuide: false` + `hasSuspendBanner: true` + `hasDescription: true` ✓ AC-3 |
| 既有 vitest | 10 / 0 fail ✓ AC-4 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：安裝指引區塊 wrap 進 `{skill.status === 'PUBLISHED' && (...)}` 條件 render

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: PUBLISHED 顯指引 | ✅ PASS | Chrome 確認 hasInstallGuide=true |
| AC-2: DRAFT 隱藏指引 | ✅ PASS | Chrome 確認 hasInstallGuide=false / 描述仍在 |
| AC-3: SUSPENDED 隱藏指引 | ✅ PASS | Chrome 確認 hasInstallGuide=false / suspend banner + 描述仍在 |
| AC-4: 既有 frontend 測試不破 | ✅ PASS | vitest 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 22 Chrome E2E 走 detail / publish / analytics 三頁面找新 bug — DRAFT skill 詳情頁概要 tab 顯示「安裝指引：下載 zip 後解壓...」但此 skill **沒下載按鈕**+「版本歷史」tab 顯「尚無版本記錄」。User 看到 install guide 卻找不到下載按鈕屬 UX 矛盾。

**Fix design rationale**:
- 條件 render 對齊 download button 隱藏邏輯（PUBLISHED only）
- DRAFT：「版本歷史」tab Add Version form 已是正確引導；概要不該再混入 user-side 安裝步驟
- SUSPENDED：destructive banner「此技能已被停用，無法下載」+「請聯絡管理員」(S035) 已足夠；install guide 是 user-side 動作，與 admin reactivate 無關

### 7.5 Pending Verification / Tech Debt

- 搜尋框 placeholder 仍未對齊 S043（仍待修）
- semantic 系統性回 0 根因待查
- PublishPage submit button 持續 disabled 但無提示 — 不嚴重，user 看到 file 必填即可推測；留 future spec
- analytics「本週新增」直接顯 25（=總數）— 可能正確（25 都是這週創）也可能算法 bug，待後續驗
