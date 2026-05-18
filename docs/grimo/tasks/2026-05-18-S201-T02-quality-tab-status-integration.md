# S201-T02: QualityTabV2 接上三色狀態列

## 對應規格
S201：Quality Score 單項狀態顯示

## 這個 task 要做什麼
把 `QualityTabV2` 裡每個小項右側的孤立 `ScoreDot` 換成 T01 的 `ScoreStatusIndicator`。使用者打開 Quality tab 時，Validation 小項會看到 `通過 100/100`、`注意 80/100`、`提醒 1`；Implementation / Activation 小項會看到 `滿分 3/3` 等 0-3 文案。彩虹線與 axis 總分保留不變。

## 使用者情境（BDD）
Given（前提）`/scores` 回 `validation.totalScore = 97`
And（而且）`validation.dimensions.lineCount.score = 100`
And（而且）`validation.dimensions.frontmatterOfficialFormat.score = 80`
And（而且）`validation.dimensions.warnings = ["frontmatter_official_format: allowed-tools uses YAML list"]`
When（動作）使用者打開 Quality tab
Then（結果）`規格驗證` header 仍顯示 `97`
And（而且）彩虹進度線寬度仍是 `97%`
And（而且）`Line Count` 顯示 `通過 100/100`
And（而且）`Frontmatter Official Format` 顯示 `注意 80/100`
And（而且）`Warnings` 顯示 `提醒 1`
And（而且）warning 文字顯示在 reasoning 區，不出現紅色錯誤點或 `undefined/3`

Given（前提）`implementation.dimensions` 含 `score = 3, 2`
And（而且）`activation.dimensions` 含 `score = 1, 0`
When（動作）使用者打開 Quality tab
Then（結果）畫面顯示 `滿分 3/3`、`可接受 2/3`、`偏弱 1/3`、`缺失 0/3`

## 研究來源
- `docs/grimo/specs/2026-05-18-S201-quality-score-status-indicators.md`
- `frontend/src/components/v2/tabs/QualityTabV2.tsx`
- `frontend/src/components/v2/tabs/QualityTabV2.test.tsx`
- `frontend/src/api/scores.ts`
- `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx`（T01）

## 先做 POC
- POC：not required — 這是既有 component 的 render 分支與 RTL 測試，不新增 browser-only API。

## 正式程式怎麼做
- Class / file 名稱：`QualityTabV2.tsx`
- 入口：`AxisSection({ axisKey, axis })`
- 必要行為：
  - 移除 Quality tab 對 `ScoreDot` 的使用；Quality tab 改用 `ScoreStatusIndicator`。
  - `Object.entries(axis.dimensions)` render 前先判斷 value：
    - `isDimensionScore(value)`：照原本 dimension row 顯示名稱、reasoning、status indicator。
    - `Array.isArray(value)` 且 key 是 `warnings`：顯示 `Warnings` row，reasoning 區顯示 warning strings，status 顯示 `提醒 {count}`。
  - warning strings 可用 `; ` 串接，或每筆一行；但不能只顯示 `顯示較少` 而沒有 warning 內容。
  - `expanded` state 保留；不要讓一個 row 的展開切換破壞整個 section 的可讀性。如果維持既有全 section shared `expanded`，測試要覆蓋 warning 內容仍可見。
  - Axis header 分數與彩虹線 width 仍使用 `axis.totalScore`。
  - Mobile layout 不可水平爆版；狀態文字可以換行，但不能蓋到 reasoning。
- Finding / response / DB 欄位：
  - 不改 `/scores` response；只修 frontend rendering。

## 單元測試 / 整合測試
- `QualityTabV2.test.tsx`
  - `@DisplayName("AC-S201-1: validation 100 renders 通過 100/100")`
  - `@DisplayName("AC-S201-2: validation 80 renders 注意 80/100")`
  - `@DisplayName("AC-S201-3: validation warnings render 提醒 row")`
  - `@DisplayName("AC-S201-4: implementation and activation render 0-3 status labels")`
  - `@DisplayName("AC-S201-5: warnings array never renders undefined score")`
  - `@DisplayName("AC-S201-6: axis total score and progress bar remain driven by totalScore")`

## 會改哪些檔案
- `frontend/src/components/v2/tabs/QualityTabV2.tsx`
- `frontend/src/components/v2/tabs/QualityTabV2.test.tsx`
- 視 T01 實作結果，可能移除 `frontend/src/components/v2/shared/ScoreDot.tsx` 的 Quality tab dependency；若檔案已無引用，可刪除並同步測試。

## 驗證方式
執行：`cd frontend && npm test -- QualityTabV2`

## 前置條件
- S201-T01 PASS

## 狀態
PASS

## Result
Date: 2026-05-19
Test: `QualityTabV2.test.tsx`（`frontend/src/components/v2/tabs/QualityTabV2.test.tsx`）
Files changed:
- `frontend/src/components/v2/tabs/QualityTabV2.tsx`（modified）— Quality tab 移除 `ScoreDot` dependency；score rows 改用 `ScoreStatusIndicator`，warnings array 改成 `Warnings` + `提醒 {count}` row。
- `frontend/src/components/v2/tabs/QualityTabV2.test.tsx`（modified）— 覆蓋 AC-S201-1 / 2 / 3 / 4 / 5 / 6，確認 0-100、0-3、warnings 與 axis progress 行為。
RED:
- `cd frontend && npm test -- QualityTabV2` failed：6 個 S201 AC 失敗；找不到 `通過 100/100`、`注意 80/100`、`Warnings`、`滿分 3/3`、`axis-progress-validation`，且畫面仍有 6 個 `score-dot`。
GREEN:
- `cd frontend && npm test -- QualityTabV2` PASS：1 file / 11 tests。
- `cd frontend && npm test -- QualityTabV2 ScoreStatusIndicator` PASS：2 files / 15 tests。
- `cd frontend && npm run verify` PASS：ESLint `--max-warnings 0` + `tsc -b`。
Notes: T02 已讓 Quality tab 顯示 status text 與 warning 內容；T03 仍需同步 `docs/grimo/ui/DESIGN.md` 與 prototype 規則。
