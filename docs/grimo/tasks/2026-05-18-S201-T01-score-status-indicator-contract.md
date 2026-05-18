# S201-T01: ScoreStatusIndicator contract 與 score type guard

## 對應規格
S201：Quality Score 單項狀態顯示

## 這個 task 要做什麼
建立一個可重用的 `ScoreStatusIndicator`，讓 Quality tab 可以用「12px 圓圈 + 文字」表達小項狀態。這個 task 只做 shared contract：把 Validation 的 `100/100` 與 Implementation / Activation 的 `3/3` 規則集中在同一個 helper，並修正 `AxisScore.dimensions` 型別，讓 `warnings: string[]` 不再被 TypeScript 偽裝成 `DimensionScore`。

## 使用者情境（BDD）
Given（前提）`validation.dimensions.lineCount.score = 100`
When（動作）`scoreStatus("validation", 100)` 被呼叫
Then（結果）回傳 `label = "通過 100/100"`
And（而且）回傳 `tone = "pass"`

Given（前提）`validation.dimensions.frontmatterOfficialFormat.score = 80`
When（動作）`scoreStatus("validation", 80)` 被呼叫
Then（結果）回傳 `label = "注意 80/100"`
And（而且）回傳 `tone = "warn"`

Given（前提）`implementation.dimensions.conciseness.score = 3`
When（動作）`scoreStatus("implementation", 3)` 被呼叫
Then（結果）回傳 `label = "滿分 3/3"`
And（而且）回傳 `tone = "pass"`

Given（前提）`activation.dimensions.triggerTermQuality.score = 0`
When（動作）`scoreStatus("activation", 0)` 被呼叫
Then（結果）回傳 `label = "缺失 0/3"`
And（而且）回傳 `tone = "fail"`

Given（前提）`AxisScore.dimensions.warnings` 是 `string[]`
When（動作）frontend TypeScript 編譯
Then（結果）`warnings` 的型別不是 `DimensionScore`
And（而且）呼叫方必須先用 type guard 判斷，不能直接讀 `value.score`

## 研究來源
- `docs/grimo/specs/2026-05-18-S201-quality-score-status-indicators.md`
- `frontend/src/api/scores.ts`
- `frontend/src/components/v2/shared/ScoreDot.tsx`
- `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java`
- production `/api/v1/skills/1d935458-69e5-450c-9438-5c91c02a048e/scores` response：Validation 0-100、Implementation/Activation 0-3、warnings 為字串陣列

## 先做 POC
- POC：not required — 這是現有 React/TypeScript helper 與型別收斂，不新增 package、不碰外部 SDK、不改 API contract。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx`（新增）
  - `frontend/src/api/scores.ts`（修改型別）
- 入口：
  - `scoreStatus(axisKey, score)` 回傳 `{ tone, label }`
  - `ScoreStatusIndicator` render 12px 圓圈 + label
  - `isDimensionScore(value)` type guard
- 必要行為：
  - `validation + 100` → `pass / 通過 100/100`
  - `validation + 1..99` → `warn / 注意 {score}/100`
  - `validation + 0` → `fail / 需修正 0/100`
  - `implementation|activation + 3` → `pass / 滿分 3/3`
  - `implementation|activation + 2` → `warn / 可接受 2/3`
  - `implementation|activation + 1` → `fail / 偏弱 1/3`
  - `implementation|activation + 0` → `fail / 缺失 0/3`
  - 圓圈直徑固定 `12px`，文字必須同時顯示；不要只靠顏色。
  - 滿分改用綠色，不沿用 `ScoreDot` 的紫色滿分語意。
- Finding / response / DB 欄位：
  - 不改 API response；只讓 frontend type 能表達 `Record<string, DimensionScore | string[]>`。

## 單元測試 / 整合測試
- `ScoreStatusIndicator.test.tsx`
  - `@DisplayName("AC-S201-1: validation 100 renders pass label")`
  - `@DisplayName("AC-S201-2: validation 80 renders warning label")`
  - `@DisplayName("AC-S201-4: implementation and activation render 0-3 labels")`
  - `@DisplayName("AC-S201-5: warnings array is not treated as DimensionScore")`

## 會改哪些檔案
- `frontend/src/api/scores.ts`
- `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx`
- `frontend/src/components/v2/shared/ScoreStatusIndicator.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- ScoreStatusIndicator`

## 前置條件
- 無

## 狀態
PASS

## Result
Date: 2026-05-19
Test: `ScoreStatusIndicator.test.tsx`（`frontend/src/components/v2/shared/ScoreStatusIndicator.test.tsx`）
Files changed:
- `frontend/src/api/scores.ts`（modified）— `AxisScore.dimensions` 改成 `Record<string, DimensionScore | string[]>`，讓 `warnings` 不再偽裝成 score object。
- `frontend/src/components/v2/shared/scoreStatus.ts`（new）— `scoreStatus(...)` 與 `isDimensionScore(...)` contract。
- `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx`（new）— 12px 圓圈 + 文字狀態 component。
- `frontend/src/components/v2/shared/ScoreStatusIndicator.test.tsx`（new）— AC-S201-1 / 2 / 4 / 5。
- `frontend/src/components/QualityTab.tsx`（modified）— 用 `isDimensionScore(...)` 過濾 score rows，避免舊 tab 把 `warnings` 當 score。
- `frontend/src/components/v2/tabs/QualityTabV2.tsx`（modified）— 用 `isDimensionScore(...)` 過濾 score rows；T02 會接上 warnings row 與三色狀態列。
RED:
- `cd frontend && npm test -- ScoreStatusIndicator` failed：`Failed to resolve import "./ScoreStatusIndicator"`，證明 helper/component 尚未存在。
GREEN:
- `cd frontend && npm test -- ScoreStatusIndicator` PASS：1 file / 4 tests。
- `cd frontend && npm test -- ScoreStatusIndicator QualityTabV2` PASS：2 files / 10 tests。
- `cd frontend && npm run verify` PASS：ESLint `--max-warnings 0` + `tsc -b`。
Notes: T01 只建立 status contract 與 type guard；Quality tab 的 `ScoreStatusIndicator` 接線、warnings row 顯示與 axis progress 驗證留在 S201-T02。
