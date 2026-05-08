# S151: Quality Score 訊息一致性修正

> Spec: S151 | Size: XS(2) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 同一個 skill detail page 顯示三種不同字面：hero `QualityHeroCard` 顯「評分計算中」、Quality tab `QualityTabV2` 顯「此版本尚未評分」、`SkillScoreBadge` 顯「評分計算中」。語意分歧（transient「計算中」vs. 中性「尚未評分」），user 不知道是「等一下會好」還是「永遠不會評分」。

---

## 1. Goal

統一三處對「scores=null」狀態的文案。User 看 hero / tab / badge 任一處應該得到一致心智模型：分數「正在算 / 還沒算完」（暫時性）。

**為什麼重要：**
- LLM judge 是 async listener；publish 後通常幾秒內完成評分，但 listener 故障 / GraalVM AOT bug（per S148 ship 前案例）會卡住 → null 狀態可能是 transient 也可能是 stuck
- 兩種字面同頁出現 → user 困惑該不該等
- 一致性是 trust signal — 平台對自己「沒分數」的描述都不齊，使用者怎麼相信評分

**非目標：**
- 不改評分邏輯本身
- 不改 LLM judge prompt
- 不改 stuck-evaluation 監控（屬 META 議題；本 spec 純 UI 文案）

---

## 2. Approach

### 2.1 三處現況

| 位置 | source | 字面 | 條件 |
|------|--------|------|------|
| Hero metric card | `frontend/src/components/v2/QualityHeroCard.tsx:44` | 「評分計算中」 | `pct === null` |
| Quality tab empty state | `frontend/src/components/v2/tabs/QualityTabV2.tsx:95` | 「此版本尚未評分」 | `scores === null` |
| Hero score badge sub-label | `frontend/src/components/v2/SkillScoreBadge.tsx:74` | 「評分計算中」 | `!haScore` |
| 舊 v1 QualitySection（仍有檔但 SkillDetailPage v2 後可能不渲染） | `frontend/src/components/QualitySection.tsx:46` | 「評分計算中，請稍後重新整理」 | `null` 路徑 |
| 舊 v1 QualityTab（同上） | `frontend/src/components/QualityTab.tsx:80` | 「此版本尚未評分，請發布後稍候片刻再重新整理。」 | `null` 路徑 |

### 2.2 設計選項

| 方案 | 字面 | Pros | Cons |
|------|------|------|------|
| **A: 統一「評分計算中」** | 「評分計算中，請稍後重新整理」 | 暗示 transient；user 知道等就好；對齊既有 hero card | 實際 stuck 時誤導 |
| B: 統一「尚未評分」 | 「此版本尚未評分」 | 中性不假設成因 | user 不知該等還是該動作 |
| C: 雙態 | publish 後 X 分鐘內顯 A，超過顯 B + 「請聯絡管理員」 | 更精確 | 需要時間戳 + 邏輯；spec 越界 |

**選 A**（統一「評分計算中，請稍後重新整理」）：
- 90% case 是 transient；A 對主場景準確
- B「尚未評分」中性但 user 反應不清「我該做什麼」
- C 帶 logic 偏 M-sized；本 spec 範圍只是文案統一
- Stuck case（< 10%）的 fix 屬監控議題，另開 spec

### 2.3 sub-label 風格 trim

`QualityHeroCard:44` 在 metric block 內顯小字 sub-label，空間有限 → 用短版「評分計算中」（不帶「請稍後重新整理」）。
`QualityTabV2:95` 是 tab empty state 中央顯示 → 用完整版「評分計算中，請稍後重新整理」。
`SkillScoreBadge:74` 是 badge 內部小字 → 用短版「評分計算中」。
`QualitySection / QualityTab` v1 元件已被 v2 取代不渲染（per S142a）— **drive-by 觀察**：應確認 v1 已不在 SkillDetailPage 載入；若是，純粹刪檔；若仍 load，留 v1 原文案不動避免 drive-by 範圍擴大。**本 spec 範圍不含 v1 元件**，由 follow-up audit 跟進。

---

## 3. Acceptance Criteria

```
AC-1: Hero metric card sub-label 顯短版「評分計算中」
  Given skill 有 scores=null（尚未評分 / 評分中）
  When SkillDetailPage v2 hero 區渲染 QualityHeroCard
  Then sub-label 顯「評分計算中」
  And 字串原本可能是其他變體 → 統一為短版

AC-2: Quality tab empty state 顯完整版「評分計算中，請稍後重新整理」
  Given skill scores=null 切到品質 tab
  When QualityTabV2 渲染 empty state
  Then 顯「評分計算中，請稍後重新整理」
  And 不再顯「此版本尚未評分」字面

AC-3: SkillScoreBadge sub-label 短版「評分計算中」（既有）
  Given skill 無 score
  When SkillScoreBadge render 在 hero
  Then sub-label 顯「評分計算中」（已 ship；本 spec 不動但驗 regression）

AC-4: 既有測試同步
  Given QualityTabV2.test.tsx「scores=null → 此版本尚未評分」case
  When 跑 vitest
  Then assertion 改驗「評分計算中，請稍後重新整理」
```

驗證指令：`cd frontend && npx vitest run src/components/v2/QualityHeroCard.test.tsx src/components/v2/tabs/QualityTabV2.test.tsx`

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/components/v2/tabs/QualityTabV2.tsx:95` | 「此版本尚未評分」→「評分計算中，請稍後重新整理」 |
| `frontend/src/components/v2/tabs/QualityTabV2.test.tsx` | empty state assertion 同步 |
| `frontend/src/components/v2/QualityHeroCard.tsx:44` | 字面確認為「評分計算中」（已是；本 spec 不改但走 verify） |
| `frontend/src/components/v2/SkillScoreBadge.tsx:74` | 字面確認為「評分計算中」（已是；不改） |
| ~~`frontend/src/components/QualitySection.tsx:46`~~ | 不在範圍 — v1 元件 drive-by 觀察留 follow-up |
| ~~`frontend/src/components/QualityTab.tsx:80`~~ | 不在範圍 — 同上 |

實際只動 1 source + 1 test。其他純 verify。

---

## 5. Test Plan

### 5.1 自動化（vitest）

- 執行：`npx vitest run QualityTabV2.test.tsx QualityHeroCard.test.tsx`
- 期望：既有「scores=null → empty state」case 對 assertion 文字改後 PASS
- 新增 negative assertion：`expect(screen.queryByText('此版本尚未評分')).toBeNull()`

### 5.2 手動煙霧測試（dev / LAB）

- [ ] 上傳新 skill；publish 後立刻訪問 detail
- [ ] hero card 顯「評分計算中」
- [ ] 切到品質 tab 顯「評分計算中，請稍後重新整理」
- [ ] 三處字面一致無「尚未評分」字眼

---

## 6. 後續 follow-up

- v1 `QualitySection` / `QualityTab` 元件 audit：是否仍渲染？若否則刪檔；若是則本 spec 完工後也對齊新文案（單獨 spec or 本 spec 擴大範圍視 audit 結果決定）
- Stuck score 監控（async listener fail → score 卡 null）— 另開 spec 處理 admin alerting
