# S197-T04: DESIGN 必填表單規則同步

## 對應規格
S197：必填欄位即時提示 UX

## 這個 task 要做什麼
把 Publish/Edit 表單的 required mark、inline error、a11y attributes 規則寫進 `docs/grimo/ui/DESIGN.md`。文件要能讓下一個 UI task 知道：必填提示不是只靠紅色，也不是只靠 disabled button。

## 使用者情境（BDD）
Given（前提）開發者閱讀 `docs/grimo/ui/DESIGN.md`
When（動作）搜尋 Publish/Edit form 或 required fields
Then（結果）文件說明 required mark 要有視覺符號與 `sr-only` 的 `必填`
And（而且）inline error 要用繁中短句，並由 `aria-describedby` 連到欄位
And（而且）required mark 顏色不得混進 category palette / risk palette 規則

## 研究來源
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md`
- `docs/grimo/ui/DESIGN.md`
- W3C WAI form instructions / validation research 已記在 S197 §2.1

## 先做 POC
- POC：not required — docs-only sync。

## 正式程式怎麼做
- Class / file 名稱：`docs/grimo/ui/DESIGN.md`
- 入口：UI design source of truth
- 必要行為：
  - 在 PublishPage / SkillEditPage 或 shared form rules 附近補 required field rule。
  - 明確記錄 `RequiredMark` pattern：visible `*` 或小紅點 + `aria-hidden`，旁邊 `sr-only` 文字。
  - 明確記錄 error text 與 help text 要透過 `aria-describedby` 串接。
  - 不改 category / risk palette 的語意。

## 單元測試 / 整合測試
- `frontend/src/pages/PublishPage.test.tsx` 或 docs source test（若已有同類 pattern）
  - `AC-S197-8: DESIGN documents Publish/Edit required mark and inline message rule`
- 若不新增 docs test，Phase 4 至少用 source inspection 記錄：`rg -n "required|必填|aria-describedby" docs/grimo/ui/DESIGN.md`

## 會改哪些檔案
- `docs/grimo/ui/DESIGN.md`
- 可選：`frontend/src/pages/PublishPage.test.tsx` 或其他 docs assertion test

## 驗證方式
執行：`rg -n "必填|aria-describedby|required" docs/grimo/ui/DESIGN.md`

## 前置條件
- S197-T01 PASS
- S197-T02 PASS
- S197-T03 PASS

## Status
PASS

## Result
Date: 2026-05-18
Test: `AC-S197-8` (`docs/grimo/ui/DESIGN.md`)
Files changed:
- `docs/grimo/ui/DESIGN.md` (modified)
Notes: RED confirmed with `rg -n "必填|aria-describedby|required" docs/grimo/ui/DESIGN.md` returning no matches; GREEN confirmed with the same command returning the `required-field` rule, `必填`, `aria-invalid`, `aria-describedby`, and the palette separation rule.
