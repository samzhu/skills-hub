# S036: Frontend MEDIUM Risk Message（補齊三段風險說明 + Record map exhaustive）

> Spec: S036 | Size: XS(5) | Status: ✅ Done — target ship `v2.13.0`
> Date: 2026-05-01
> Depends: S028 ✅ + S035 ✅
> Trigger: 2026-05-01 /loop tick 11 — `SkillDetailPage` Risk tab 有 LOW/HIGH 段落說明，缺 MEDIUM。RiskBadge 三色齊全但 detail tab 文字不完整。

---

## 1. Goal

`SkillDetailPage` Risk tab 補齊 MEDIUM 段落說明；同時把三個 inline `if` 條件改用 `Record<RiskLevel, string>` map（mirror S028 `STATUS_LABEL` 模式），未來新增風險等級時 TypeScript exhaustive check 會強制補對應。

---

## 2. Approach

### 2.1 Risk description Record map

```typescript
const RISK_DESCRIPTION: Record<RiskLevel, string> = {
  LOW: '此技能僅含 SKILL.md，不含可執行腳本，風險等級為低。',
  MEDIUM: '此技能含可執行腳本，但未偵測到高風險模式。建議審視 scripts/ 內容後再使用。',
  HIGH: '此技能的 scripts/ 中偵測到高風險模式，請謹慎使用。',
}

// MEDIUM 與 HIGH 視覺需強調 — class 對齊 RiskBadge 色彩語意
const RISK_TEXT_CLASS: Record<RiskLevel, string> = {
  LOW: 'text-muted-foreground',
  MEDIUM: 'text-amber-700',
  HIGH: 'text-red-600',
}
```

### 2.2 SkillDetailPage Risk tab diff

```diff
 <TabsContent value="risk" className="mt-4">
   <div className="space-y-3">
     <div className="flex items-center gap-2">
       <span className="text-sm font-medium">風險等級：</span>
       <RiskBadge level={skill.riskLevel} />
     </div>
     {!skill.riskLevel && (
       <p className="text-sm text-muted-foreground">此技能尚未完成風險評估。</p>
     )}
-    {skill.riskLevel === 'LOW' && (
-      <p className="text-sm text-muted-foreground">此技能僅含 SKILL.md，不含可執行腳本，風險等級為低。</p>
-    )}
-    {skill.riskLevel === 'HIGH' && (
-      <p className="text-sm text-red-600">此技能的 scripts/ 中偵測到高風險模式，請謹慎使用。</p>
-    )}
+    {skill.riskLevel && (
+      <p className={`text-sm ${RISK_TEXT_CLASS[skill.riskLevel]}`}>
+        {RISK_DESCRIPTION[skill.riskLevel]}
+      </p>
+    )}
   </div>
 </TabsContent>
```

### 2.3 為何 Record map 而非 inline switch

- TypeScript `Record<RiskLevel, string>` 強制 exhaustive — 未來新增風險等級（如 CRITICAL）時 compiler 會 fail；inline `?:` 不會
- mirror S028 `STATUS_LABEL: Record<SkillStatus, string>` 模式，code base 一致性
- 三個值集中查找一個地方，scan 容易

### 2.4 為何 NOT 移到 RiskBadge 元件內

`RiskBadge` 是 inline 短訊（"低風險" / "中風險" / "高風險"）；段落說明 是 detail page 級別的詳述，職責分層：
- `RiskBadge` — 列表 / 詳情頁 共用的 inline visual
- `RISK_DESCRIPTION` 段落 — 詳情頁專屬的 explanatory text

未來如其他頁面要 explanatory text，再考慮抽 hook 或元件。

---

## 3. SBE Acceptance Criteria

### AC-1: MEDIUM skill 渲染 MEDIUM 說明段落

```gherkin
Given skill A riskLevel='MEDIUM'
When  渲染 <SkillDetailPage> Risk tab
Then  段落顯示「此技能含可執行腳本，但未偵測到高風險模式。建議審視 scripts/ 內容後再使用。」
And   段落 class 含 'text-amber-700'（與 RiskBadge MEDIUM 琥珀色語意對齊）
```

### AC-2: LOW / HIGH 行為等價（regression check）

```gherkin
Given skill B riskLevel='LOW'
When  渲染 Risk tab
Then  段落顯示「此技能僅含 SKILL.md，不含可執行腳本，風險等級為低。」
And   段落 class 'text-muted-foreground'

Given skill C riskLevel='HIGH'
When  渲染 Risk tab
Then  段落顯示「此技能的 scripts/ 中偵測到高風險模式，請謹慎使用。」
And   段落 class 'text-red-600'
```

### AC-3: null riskLevel 行為不變

```gherkin
Given skill D riskLevel=null
When  渲染 Risk tab
Then  段落顯示「此技能尚未完成風險評估。」
```

### AC-4: TypeScript exhaustive check

```gherkin
Given S036 改動完成
When  npx tsc -b
Then  `Record<RiskLevel, string>` 三鍵齊全；未來加 CRITICAL 必 compile fail
```

### AC-5: 既有 frontend test 不破

```gherkin
Given S036 改動完成
When  cd frontend && npm test && npm run lint
Then  全 PASS
```

---

## 4. Interface

詳 §2.1 + §2.2。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：加 `RISK_DESCRIPTION` + `RISK_TEXT_CLASS` 兩個 Record map，重寫 Risk tab paragraph 條件渲染

### 5.2 Docs
- CHANGELOG `v2.13.0`
- spec-roadmap M32

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SkillDetailPage Record map + paragraph 改寫 + npm test/tsc/lint | AC-1~5 | 🔲 |

POC: not required（純 conditional rendering 改寫；既有 RiskLevel type 已 cover MEDIUM）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.13.0`
>
> Verification: `npm test` 10/10 PASS；`tsc -b` 0 errors；`npm run lint` 0 warnings。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `cd frontend && npm test` | 10/10 PASS |
| `cd frontend && npx tsc -b` | 0 type errors（exhaustive `Record<RiskLevel, string>`）|
| `cd frontend && npm run lint` | 0 errors / 0 warnings |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/pages/SkillDetailPage.tsx`：
  - import 加 `RiskLevel`
  - 加 `RISK_DESCRIPTION: Record<RiskLevel, string>` 三段中文說明
  - 加 `RISK_TEXT_CLASS: Record<RiskLevel, string>` 對應視覺色階
  - Risk tab paragraph：3 inline `{... === 'X' && (...)}` → 1 `{skill.riskLevel && (...)}` 統一從 map 取

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: MEDIUM 渲染段落（含 amber 色階）| ✅ PASS | `RISK_DESCRIPTION.MEDIUM` + `RISK_TEXT_CLASS.MEDIUM='text-amber-700'` |
| AC-2: LOW / HIGH regression | ✅ PASS | wording 完整保留；class 從 inline 移到 RISK_TEXT_CLASS |
| AC-3: null riskLevel 行為不變 | ✅ PASS | 既有 `!skill.riskLevel` 早於 map 渲染分支 |
| AC-4: TypeScript exhaustive | ✅ PASS | `Record<RiskLevel, string>` 三鍵齊全；compiler 強制 |
| AC-5: 既有 frontend test 不破 | ✅ PASS | 10/10 tests + 0 type errors + 0 lint warnings |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 11 — `RiskBadge` 三色齊全（LOW/MEDIUM/HIGH），但 `SkillDetailPage` Risk tab 只有 LOW/HIGH 段落說明；MEDIUM skill user 看到 badge 卻沒詳述。

**Fix design rationale**:
- 補 MEDIUM 段落 — 符合既有 LOW/HIGH 對稱性；user 看 badge 後該找到對應說明
- 改用 `Record<RiskLevel, string>` map — TypeScript exhaustive 防止未來新增等級漏改；mirror S028 `STATUS_LABEL: Record<SkillStatus, string>` pattern
- amber-700 色階對齊 `RiskBadge` 既有 amber-100 bg / amber-800 text 系統 — 視覺語意一致

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 仍待設計（與本 spec 無關）。
