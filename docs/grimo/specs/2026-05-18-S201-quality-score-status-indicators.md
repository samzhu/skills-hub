# S201: Quality Score 單項狀態顯示

> 規格：S201 | 大小：XS(5) | 狀態：⏳ Dev
> 日期：2026-05-18
> 對應：PRD P1「技能詳情頁」、S135a Backend Quality Score、S142a SkillDetailPage v2、S198 Recommendations Not Hard Errors

---

## 1. 目標

`/skills/1d935458-69e5-450c-9438-5c91c02a048e` 的 Quality tab 現在每個小項右側只顯示一顆圓點。使用者看到橘色 / 紅色點，不知道它代表「分數」、「警告」、「錯誤」還是「只是分類」。

這張 spec 要把 Quality tab 的小項狀態改成可讀的三色狀態：

```
規格驗證
  - 通過 100/100       綠色圓圈
  - 注意 80/100        黃色圓圈
  - 提醒 1             黃色圓圈
  - 需修正 0/100       紅色圓圈

實作品質 / 觸發能力
  - 滿分 3/3           綠色圓圈
  - 可接受 2/3         黃色圓圈
  - 偏弱 1/3           紅色圓圈
  - 缺失 0/3           紅色圓圈
```

彩虹線保留：它表示整個軸的總分，例如 `規格驗證 97`、`實作品質 100`、`觸發能力 100`。

### Scope

| In | Out |
|---|---|
| `QualityTabV2` 小項右側由孤立 `ScoreDot` 改成「圓圈 + 文字」 | 不改 LLM judge prompt |
| 依 axis 選擇分數尺度：Validation 用 0-100；Implementation / Activation 用 0-3 | 不改 `skill_scores` DB schema |
| `warnings` 陣列顯示為提醒列，不再丟給 0-3 dot 元件 | 不改 publish validation 後端邏輯；S198 負責 |
| 新增可重用 `ScoreStatusIndicator` 或同等 helper | 不新增第三方 UI 套件 |
| 更新 prototype / DESIGN 記錄 Quality status rule | 不重做整個 Skill Detail Page layout |

### Dependency

| Dependency | 類型 | 判斷 |
|---|---|---|
| S135a | shipped | `/scores` 已回 `validation / implementation / activation` 三軸，且 Implementation / Activation 小項是 0-3。 |
| S142a | shipped | `QualityTabV2` 已渲染 Quality tab；本 spec 是該 tab 的訊號呈現修正。 |
| S198 | parallel design | S198 會調整 validator warning / quality penalty；S201 不 import S198 新型別，可平行設計。實作若 S198 已先 ship，S201 需讀新的 warning shape。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `curl https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/1d935458-69e5-450c-9438-5c91c02a048e/scores` | production 回 `validation.lineCount.score=100`、`frontmatterOfficialFormat.score=80`、`warnings` 是字串陣列；`implementation.*.score=3`、`activation.*.score=3`。 | Validation 不能用 3/3 dot；warnings 不能當 `DimensionScore`。 |
| [ScoreDot.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/v2/shared/ScoreDot.tsx:2) | 現行元件寫死：滿分 3/3 紫色、>=60% 橘色、<60% 紅色。 | `100/100` 進來會被當成 `100 / 3`，顏色與實際意義脫節。 |
| [QualityTabV2.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/v2/tabs/QualityTabV2.tsx:38) | `Object.entries(axis.dimensions)` 對所有 value 都假設有 `dim.score`。 | `warnings: string[]` 被錯誤送入 `ScoreDot score={undefined}`。 |
| [QualityScoreService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java:118) | Validation dimensions 用 0 或 100；frontmatter compatibility warning 用 80；warnings 額外放字串陣列。 | Validation UI 需要 0-100 formatter + warning row formatter。 |
| [QualityScoreService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java:143) | Implementation 取 SKILL.md body，LLM judge 每項 0-3，換算 totalScore。 | Implementation UI 可以顯 `滿分 3/3`、`可接受 2/3`。 |
| [QualityScoreService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java:150) | Activation 取 frontmatter `description`，LLM judge 每項 0-3，換算 totalScore。 | Activation UI 同 Implementation，用 0-3 label。 |
| [Tessl evaluating skills](https://tessl.io/registry/tessl-master/tessl-master/files/docs/evaluate/evaluating-skills.md) | Tessl 把 skill review 拆成 Validation Checks、Implementation Score、Activation Score，並用 Review Score 區間說明 `Excellent / Good / Needs Improvement`。 | 三軸可保留，但小項需要文字狀態，不應只靠顏色。 |
| [agentskills.io specification](https://agentskills.io/specification) | `name` / `description` 是 SKILL.md frontmatter 核心欄位；progressive disclosure 建議主檔保持精簡。 | Validation 是格式 / 規格檢查；Activation 是 description 可觸發性，兩者要分開說明。 |
| [GitHub Marketplace badges](https://docs.github.com/en/apps/github-marketplace/github-marketplace-overview/about-marketplace-badges) | badge 有 tooltip，且文件明確說 badge 不代表 GitHub 檢查第三方程式碼。 | 狀態符號需要文字或 tooltip 限定意義，避免 user 把顏色解讀成安全背書。 |
| [JetBrains Verified Vendor Badge](https://plugins.jetbrains.com/docs/marketplace/verified-vendor-badge.html) | Verified Vendor badge 代表廠商身分驗證，文件另說 plugin 品質不是同一件事。 | Quality 狀態要明確說「小項分數」，不要混成 verified / security 狀態。 |

### 2.2 問題根因

[QualityTabV2.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/v2/tabs/QualityTabV2.tsx:43) 對所有 dimension 都呼叫：

```tsx
<ScoreDot score={dim.score} max={3} />
```

但 `/scores` 的資料其實有兩種尺度：

```json
{
  "validation": {
    "dimensions": {
      "lineCount": { "score": 100, "reasoning": "136 / 500 lines" },
      "frontmatterOfficialFormat": { "score": 80, "reasoning": "..." },
      "warnings": ["frontmatter_official_format: ..."]
    }
  },
  "implementation": {
    "dimensions": {
      "conciseness": { "score": 3, "reasoning": "..." }
    }
  }
}
```

所以現在畫面錯在兩件事：

1. `lineCount.score=100` 被拿去除以 `max=3`，圓點顏色不是使用者以為的 100 分通過。
2. `warnings` 不是分數物件，卻被當成有 `score` 的 dimension。

### 2.3 UI sketch

低保真版：

```
規格驗證                                          97
Specification compliance & completeness
[彩虹進度線 97%]

┌──────────────────────────────────────────────────────────────┐
│ Warnings        allowed-tools uses YAML list...      ● 提醒 1 │
├──────────────────────────────────────────────────────────────┤
│ Line Count      136 / 500 lines                     ● 通過 100/100 │
├──────────────────────────────────────────────────────────────┤
│ Name Format     valid name format                   ● 通過 100/100 │
├──────────────────────────────────────────────────────────────┤
│ Frontmatter...  allowed-tools accepted but...        ● 注意 80/100 │
└──────────────────────────────────────────────────────────────┘

實作品質                                          100
[彩虹進度線 100%]

┌──────────────────────────────────────────────────────────────┐
│ Conciseness     The skill body is focused...         ● 滿分 3/3 │
│ Actionability   Exact patterns and commands...       ● 滿分 3/3 │
└──────────────────────────────────────────────────────────────┘
```

手機版：

```
Line Count
136 / 500 lines
● 通過 100/100
```

### 2.4 狀態規則

| Axis | Input shape | Label rule | Color |
|---|---|---|---|
| Validation | `{ score: 100 }` | `通過 100/100` | green |
| Validation | `{ score: 1..99 }` | `注意 {score}/100` | amber |
| Validation | `{ score: 0 }` | `需修正 0/100` | red |
| Validation | `warnings: string[]` | `提醒 {count}` | amber |
| Implementation / Activation | `{ score: 3 }` | `滿分 3/3` | green |
| Implementation / Activation | `{ score: 2 }` | `可接受 2/3` | amber |
| Implementation / Activation | `{ score: 1 }` | `偏弱 1/3` | red |
| Implementation / Activation | `{ score: 0 }` | `缺失 0/3` | red |

說明：
- 使用者要求「綠色 / 黃色 / 紅色，大一點點的圓圈」，所以 implementation / activation 滿分也改用綠色，不再使用紫色代表滿分。
- 圓圈大小使用 `12px`，比目前 `7px` 更容易看到。
- 文字永遠跟著圓圈出現；不能只靠顏色。

### 2.5 做法比較

| 做法 | 採用 | 理由 |
|---|---|---|
| A: 新增 `ScoreStatusIndicator`，依 axis + raw dimension value 決定 label/color | yes | 解掉尺度混用；元件可以單獨測試。 |
| B: 改後端把 Validation 也轉成 0-3 | no | 會破壞既有 `/scores` contract；Validation 本來就是 rule-based 0-100。 |
| C: 保留 `ScoreDot`，只加 tooltip | no | tooltip 在手機不好用，而且 user 第一眼仍看不懂。 |
| D: 把 `warnings` 從 UI 隱藏 | no | warnings 是目前唯一能解釋為什麼 97 不是 100 的線索。 |

### 2.6 Task 邊界提示

| Task 候選 | File | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|
| T01 | `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx` | `validation + 100` 顯 `通過 100/100`；`implementation + 3` 顯 `滿分 3/3` | `warnings` 不進 0-3 score formatter | not required |
| T02 | `frontend/src/components/v2/tabs/QualityTabV2.tsx` | Quality tab 對 `warnings` 渲染提醒列；其他小項顯圓圈 + 文字 | 不再 render `ScoreDot` for validation 100/80 | not required |
| T03 | `frontend/src/components/v2/tabs/QualityTabV2.test.tsx` | 測 validation 100/80/warnings、implementation 3/2/1/0、activation 3 | 不用 CSS color assertion 當唯一驗證；至少驗 label text | not required |
| T04 | `docs/grimo/ui/DESIGN.md` + prototype | 記錄 Quality tab 的三色狀態規則 | 不改 risk-pill、category palette | not required |

### 2.7 Planning-Spec Readiness Check

| Check | Result |
|---|---|
| Spec overlap scan | PASS — S198 改 backend validator / quality penalty；S201 改 frontend Quality tab status indicator。S151 / S142a / S135a 已 shipped，只是歷史來源。 |
| PRD alignment | PASS — PRD P1 要 skill detail 顯示完整資訊與評分；這次讓評分原因可讀。 |
| Existing stack audit | PASS — 現有 React + inline style + RTL test 足夠；不需要新 dependency。 |
| Research citation | PASS — current production API + source code + agentskills/Tessl/GitHub/JetBrains 文件已足夠。 |
| POC need | not required — 純前端資料呈現，prototype 已存在：[Skills Hub Skill Detail Quality Signals Research.html](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/ui/prototype/Skills%20Hub%20Skill%20Detail%20Quality%20Signals%20Research.html:1)。 |

Design verdict：sections 1-5 are concrete enough for `$planning-tasks S201`; no POC needed.

## 3. 驗收條件（SBE）

驗證命令：

```bash
cd frontend && npm test -- QualityTabV2
cd frontend && npm run verify
```

| AC | 優先級 | 驗證方式 | 標題 |
|----|---|---|---|
| AC-S201-1 | 必做 | Test | Validation 100 分顯示綠色通過 100/100 |
| AC-S201-2 | 必做 | Test | Validation 80 分顯示黃色注意 80/100 |
| AC-S201-3 | 必做 | Test | Validation warnings 顯示黃色提醒列 |
| AC-S201-4 | 必做 | Test | Implementation / Activation 用 0-3 文案 |
| AC-S201-5 | 必做 | Test | Quality tab 不再把 warnings 當 DimensionScore |
| AC-S201-6 | 必做 | Inspection | 彩虹線保留為 axis total score |
| AC-S201-7 | 必做 | Inspection | DESIGN / prototype 記錄三色狀態規則 |

**AC-S201-1: Validation 100 分顯示綠色通過 100/100**
- Given `/scores` 回 `validation.dimensions.lineCount.score = 100`
- When 使用者打開 Quality tab
- Then `Line Count` 右側顯示 `通過 100/100`
- And 狀態前方有 12px 綠色圓圈

**AC-S201-2: Validation 80 分顯示黃色注意 80/100**
- Given `/scores` 回 `validation.dimensions.frontmatterOfficialFormat.score = 80`
- When 使用者打開 Quality tab
- Then 該列右側顯示 `注意 80/100`
- And 狀態前方有 12px 黃色圓圈

**AC-S201-3: Validation warnings 顯示黃色提醒列**
- Given `/scores` 回 `validation.dimensions.warnings = ["frontmatter_official_format: allowed-tools uses YAML list"]`
- When 使用者打開 Quality tab
- Then 畫面顯示 `Warnings`
- And 右側顯示 `提醒 1`
- And reasoning 區顯示 warning 文字
- And 不顯示紅色錯誤點

**AC-S201-4: Implementation / Activation 用 0-3 文案**
- Given `/scores` 回 `implementation.dimensions.conciseness.score = 3`
- Then `Conciseness` 顯示 `滿分 3/3`
- Given `/scores` 回 `implementation.dimensions.actionability.score = 2`
- Then `Actionability` 顯示 `可接受 2/3`
- Given `/scores` 回 `activation.dimensions.distinctiveness.score = 1`
- Then `Distinctiveness` 顯示 `偏弱 1/3`
- Given `/scores` 回 `activation.dimensions.triggerTermQuality.score = 0`
- Then `Trigger Term Quality` 顯示 `缺失 0/3`

**AC-S201-5: Quality tab 不再把 warnings 當 DimensionScore**
- Given `/scores` 的 `warnings` value 是字串陣列
- When `QualityTabV2` render
- Then 不呼叫 0-3 score formatter 處理該陣列
- And 不出現 `undefined/3`、空白狀態、或錯誤顏色點

**AC-S201-6: 彩虹線保留為 axis total score**
- Given `validation.totalScore = 97`
- When Quality tab render
- Then `規格驗證` header 仍顯示 `97`
- And 進度線寬度仍為 `97%`

**AC-S201-7: DESIGN / prototype 記錄三色狀態規則**
- Given 開發者閱讀 `docs/grimo/ui/DESIGN.md`
- Then Quality tab status indicator 規則記錄 green / amber / red 的意義
- And prototype 檔保留相同示意

## 4. 介面與檔案設計

### 4.1 Frontend helper

建議新增：

```tsx
type QualityAxisKey = 'validation' | 'implementation' | 'activation'
type StatusTone = 'pass' | 'warn' | 'fail'

interface ScoreStatusIndicatorProps {
  axisKey: QualityAxisKey
  score: number
}

function scoreStatus(axisKey: QualityAxisKey, score: number): {
  tone: StatusTone
  label: string
}
```

行為：

```ts
scoreStatus('validation', 100)       // { tone: 'pass', label: '通過 100/100' }
scoreStatus('validation', 80)        // { tone: 'warn', label: '注意 80/100' }
scoreStatus('validation', 0)         // { tone: 'fail', label: '需修正 0/100' }
scoreStatus('implementation', 3)     // { tone: 'pass', label: '滿分 3/3' }
scoreStatus('implementation', 2)     // { tone: 'warn', label: '可接受 2/3' }
scoreStatus('implementation', 1)     // { tone: 'fail', label: '偏弱 1/3' }
scoreStatus('implementation', 0)     // { tone: 'fail', label: '缺失 0/3' }
```

`warnings` 不是 score：

```tsx
function WarningStatusIndicator({ count }: { count: number }) {
  return <StatusIndicator tone="warn" label={`提醒 ${count}`} />
}
```

### 4.2 File plan

| File | Action | 說明 |
|---|---|---|
| `frontend/src/components/v2/shared/ScoreStatusIndicator.tsx` | add | 新增 12px 圓圈 + label；封裝 axis score formatter。 |
| `frontend/src/components/v2/shared/ScoreDot.tsx` | keep or remove if unused | 若沒有其他引用，可刪；若仍有引用，保留但 Quality tab 不再用。 |
| `frontend/src/components/v2/tabs/QualityTabV2.tsx` | modify | 區分 score dimension 與 warnings array；小項右側改用 `ScoreStatusIndicator`。 |
| `frontend/src/components/v2/tabs/QualityTabV2.test.tsx` | modify | 補 AC-S201-1~5 測試。 |
| `docs/grimo/ui/DESIGN.md` | modify | Quality tab status indicator 規則。 |
| `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html` | already created | 保留研究示意；實作後可視情況微調。 |

### 4.3 Type guard

`AxisScore.dimensions` 目前型別是 `Record<string, DimensionScore>`，但 production response 已有 `warnings: string[]`。S201 實作應調整 frontend type，避免 TypeScript 型別騙過 runtime：

```ts
export type DimensionValue = DimensionScore | string[]

export interface AxisScore {
  totalScore: number
  dimensions: Record<string, DimensionValue>
}

function isDimensionScore(value: DimensionValue): value is DimensionScore {
  return typeof value === 'object' && value !== null && !Array.isArray(value) && typeof value.score === 'number'
}
```

這是必要修正，不是 cosmetic：不改型別，`warnings` 的 bug 會繼續被 TypeScript 隱藏。

## 5. QA / Ship 計畫

本 spec 是 frontend 顯示修正，主要驗證：

```bash
cd frontend && npm test -- QualityTabV2
cd frontend && npm run verify
```

可選手動檢查：

```bash
curl -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/1d935458-69e5-450c-9438-5c91c02a048e/scores
```

把 response fixture 放進 `QualityTabV2.test.tsx`，確認：
- `lineCount.score=100` 顯 `通過 100/100`
- `frontmatterOfficialFormat.score=80` 顯 `注意 80/100`
- `warnings` 顯 `提醒 1`
- `implementation.score=3` / `activation.score=3` 顯 `滿分 3/3`

Browser E2E 不列為必做：此 spec 不改 route、不改 API、不改互動流程。若實作時調整響應式 row layout，使用 Browser 或 Playwright 補 desktop/mobile screenshot 檢查。

## 6. Task Plan

### POC Decision

POC：not required。

理由：S201 不新增 package、SDK、DB schema 或 API endpoint；所有行為都在既有 React component、TypeScript type、RTL test、docs/prototype 內完成。設計假設已由 production `/scores` response 與現有 source code 驗證：Validation 是 0-100 + `warnings: string[]`，Implementation / Activation 是 0-3。

### Task Order

| Task | File | 目的 | AC Mapping | Depends |
|---|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-18-S201-T01-score-status-indicator-contract.md` | 建立 `ScoreStatusIndicator` 與 `DimensionValue` / `isDimensionScore` contract | AC-S201-1, AC-S201-2, AC-S201-4, AC-S201-5 | — |
| T02 | `docs/grimo/tasks/2026-05-18-S201-T02-quality-tab-status-integration.md` | `QualityTabV2` 接上三色狀態列，正確渲染 warnings 與 axis progress | AC-S201-1, AC-S201-2, AC-S201-3, AC-S201-4, AC-S201-5, AC-S201-6 | T01 |
| T03 | `docs/grimo/tasks/2026-05-18-S201-T03-design-quality-status-rule-sync.md` | 同步 DESIGN 與 prototype 的 Quality status 規則 | AC-S201-7 | T02 |

### Verification Chain

1. T01 後執行：`cd frontend && npm test -- ScoreStatusIndicator`
2. T02 後執行：`cd frontend && npm test -- QualityTabV2`
3. T03 後執行：
   ```bash
   rg -n "quality-status|ScoreStatusIndicator|通過 100/100|滿分 3/3" docs/grimo/ui/DESIGN.md
   rg -n "通過 100/100|注意 80/100|滿分 3/3|12px" "docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html"
   ```
4. 全部 task PASS 後執行：
   ```bash
   cd frontend && npm test -- QualityTabV2 ScoreStatusIndicator
   cd frontend && npm run verify
   ```

## 7. Results

待實作後填寫。
