# S197-T01: Publish 必填欄位 inline 提示

## 對應規格
S197：必填欄位即時提示 UX

## 這個 task 要做什麼
`/publish` 頁面載入後，使用者要直接看得出 `技能名稱`、目前模式的 `Skill 套件` 或 `SKILL.md 內容`、`分類` 是必填。使用者碰過欄位或嘗試送出後，欄位下方要顯示繁中錯誤文字，不要只靠 browser native required bubble。`版本號` 仍可留白，不可被標成必填。

## 使用者情境（BDD）
Given（前提）使用者打開 `/publish`
When（動作）頁面載入完成
Then（結果）`技能名稱`、目前模式的 upload/text 欄位、`分類` label 旁顯示 required mark，且 required mark 有 `aria-hidden="true"` 與 `sr-only` 的 `必填`
And（而且）`版本號` label 旁沒有 required mark，input 沒有 `required` attribute

Given（前提）使用者切到 `貼上文本` mode，已填 `技能名稱` 與合法 SKILL.md，但 `分類` 空白
When（動作）分類欄位 blur 或使用者嘗試送出
Then（結果）分類欄位下方顯示 `請填寫分類`
And（而且）不送 `POST /api/v1/skills/upload`

Given（前提）使用者切到 `貼上文本` mode
When（動作）SKILL.md textarea 空白
Then（結果）顯示 `請貼上 SKILL.md 內容`
And（而且）輸入缺 `description` 的 frontmatter 後，畫面顯示既有 `缺必填欄位：description`

## 研究來源
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`
- MDN/W3C WAI required / `aria-describedby` research 已記在 S197 §2.1

## 先做 POC
- POC：not required — 只改既有 React state、label/error markup 與 RTL tests，不新增 package、SDK、framework SPI 或 browser-only API。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/PublishPage.tsx`
- 入口：`PublishPage` form render 與 `handleSubmit`
- 必要行為：
  - 建立輕量 helper，例如 `RequiredMark` / `FieldMessage`，避免每個 label 重複寫 `aria-hidden` + `sr-only`。
  - 補 `category.trim().length === 0` 到 `submitDisabled` 或 submit guard；若保留 disabled button，仍要在 blur/touched 時顯示原因。
  - 加 touched/submitted state，讓 `技能名稱`、`分類`、text mode textarea 的空白錯誤能在 blur 或 submit attempt 後顯示。
  - `aria-invalid` 只在有錯誤時為 `true`；`aria-describedby` 要包含 help text id 與 error text id。
  - `version` 欄位維持 optional，不 append 空白 `version` 的既有行為不可破壞。

## 單元測試 / 整合測試
- `frontend/src/pages/PublishPage.test.tsx`
  - `AC-S197-1: Publish 必填欄位一開始就有 required mark`
  - `AC-S197-2: Publish 分類空白顯示請填寫分類且不送 upload`
  - `AC-S197-4: Text mode 空白顯示請貼上 SKILL.md 內容且 frontmatter 錯誤維持原訊息`
  - `AC-S197-5: 版本號不顯示 required mark 且空白時 FormData 不含 version`
  - `AC-S197-7: Publish required fields connect aria-invalid and aria-describedby`

## 會改哪些檔案
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- PublishPage`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-18
Test: `AC-S197-1`, `AC-S197-2`, `AC-S197-4`, `AC-S197-5`, `AC-S197-7` (`frontend/src/pages/PublishPage.test.tsx`)
Files changed:
- `frontend/src/pages/PublishPage.tsx` (modified)
- `frontend/src/pages/PublishPage.test.tsx` (modified)
Notes: RED confirmed with `cd frontend && npm test -- PublishPage` failing 4 new S197 cases; GREEN confirmed with `cd frontend && npm test -- PublishPage` passing 21 tests.
