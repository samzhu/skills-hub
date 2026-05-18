# S201-T03: DESIGN 與 prototype 同步 Quality status 規則

## 對應規格
S201：Quality Score 單項狀態顯示

## 這個 task 要做什麼
把 S201 的 Quality tab 三色狀態規則寫回 UI source of truth。完成後，下一個開發者讀 `docs/grimo/ui/DESIGN.md` 就能知道 Quality tab 的圓圈不是 risk、不是 category，而是「單項分數狀態」；prototype 也保留同樣示意。

## 使用者情境（BDD）
Given（前提）開發者閱讀 `docs/grimo/ui/DESIGN.md`
When（動作）搜尋 `Quality tab` 或 `ScoreStatusIndicator`
Then（結果）文件說明綠色是通過 / 滿分、黃色是注意 / 可接受 / 提醒、紅色是需修正 / 偏弱 / 缺失
And（而且）文件明確寫出 Validation 用 `100/100`，Implementation / Activation 用 `3/3`
And（而且）文件說彩虹線只表示 axis total score

Given（前提）開發者打開 `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html`
When（動作）查看 Quality 示意
Then（結果）畫面保留 12px 綠 / 黃 / 紅圓圈 + 文字
And（而且）不使用紫色圓點表示滿分

## 研究來源
- `docs/grimo/specs/2026-05-18-S201-quality-score-status-indicators.md`
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html`
- `docs/grimo/specs/archive/2026-05-07-S142a-skill-detail-v2-frontend.md`

## 先做 POC
- POC：not required — docs/prototype sync，不涉及新 API 或 runtime 行為。

## 正式程式怎麼做
- Class / file 名稱：文件更新，無 production class。
- 入口：`docs/grimo/ui/DESIGN.md`
- 必要行為：
  - 在 component rules 中新增或補上 `quality-status-indicator` 規則。
  - 寫明 Quality status color 是「分數狀態」，不可和 `risk-pill` 或 category palette 混用。
  - 寫明圓圈大小 `12px`，文字必須一起出現；不可只靠顏色。
  - 寫明 Validation / Implementation / Activation 的 label rules。
  - 檢查 prototype 是否已含相同規則；若文字不一致，同步調整。
- Finding / response / DB 欄位：
  - 無 DB/API 變更。

## 單元測試 / 整合測試
- 文件檢查：
  - `rg -n "quality-status|ScoreStatusIndicator|通過 100/100|滿分 3/3" docs/grimo/ui/DESIGN.md`
  - `rg -n "通過 100/100|注意 80/100|滿分 3/3|12px" "docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html"`

## 會改哪些檔案
- `docs/grimo/ui/DESIGN.md`
- `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html`

## 驗證方式
執行：
```bash
rg -n "quality-status|ScoreStatusIndicator|通過 100/100|滿分 3/3" docs/grimo/ui/DESIGN.md
rg -n "通過 100/100|注意 80/100|滿分 3/3|12px" "docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html"
```

## 前置條件
- S201-T02 PASS

## 狀態
PASS

## Result
Date: 2026-05-19
Test: docs grep inspection（`docs/grimo/ui/DESIGN.md` / `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html`）
Files changed:
- `docs/grimo/ui/DESIGN.md`（modified）— 新增 `quality-status-indicator` 規則，寫明 `ScoreStatusIndicator` / `WarningStatusIndicator`、12px 圓圈、三色分數狀態、Validation 100/100 與 Implementation / Activation 3/3 尺度。
- `docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html`（modified）— prototype legend 補 `12px 圓圈 + 文字`，滿分列改用綠色 `status pass`，並明寫不使用舊版紫色圓點。
RED:
- `rg -n "quality-status|ScoreStatusIndicator|通過 100/100|滿分 3/3" docs/grimo/ui/DESIGN.md` exit=1，表示 DESIGN 尚未記錄 S201 Quality status 規則。
GREEN:
- `rg -n "quality-status|ScoreStatusIndicator|通過 100/100|滿分 3/3" docs/grimo/ui/DESIGN.md` PASS。
- `rg -n "通過 100/100|注意 80/100|滿分 3/3|12px" "docs/grimo/ui/prototype/Skills Hub Skill Detail Quality Signals Research.html"` PASS。
Notes: S201 三個 task 目前皆 PASS；下一步是 `$verifying-quality S201`。
