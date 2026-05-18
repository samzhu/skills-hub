# S197-T03: SkillEdit 分類與 upload mode 缺欄原因

## 對應規格
S197：必填欄位即時提示 UX

## 這個 task 要做什麼
`/skills/:id/edit` 裡，分類清空時不只讓 `儲存分類` disabled，還要在分類欄位下方說 `分類不可空白`。切到 upload mode 且未選檔時，dropzone 要顯示 `請選擇 zip 或 SKILL.md`，讓使用者知道為什麼 `儲存新版本` 不能按。

## 使用者情境（BDD）
Given（前提）owner 打開 `/skills/skill-docker/edit`
When（動作）清空 `分類` 欄位並 blur
Then（結果）`儲存分類` disabled，欄位下方顯示 `分類不可空白`
And（而且）分類 input 有 `aria-invalid="true"` 並用 `aria-describedby` 指到錯誤文字

Given（前提）owner 切到 `上傳檔案` mode
When（動作）沒有選擇檔案
Then（結果）`儲存新版本` disabled，dropzone 區域顯示 `請選擇 zip 或 SKILL.md`
And（而且）正在讀取 SKILL.md 的 text mode 不誤顯 upload mode 的 required error

## 研究來源
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md`
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- S195 shipped：upload mode 與 validation finding display pattern

## 先做 POC
- POC：not required — 沿用 T02 的 `FileDropZone` error prop 與現有 edit page state。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/SkillEditPage.tsx`
- 入口：`SkillEditPage`、`UploadMode`、`TextModeEditor`
- 必要行為：
  - 分類 input label 加 required mark；版本號仍不加 required mark。
  - 用 `categoryTouched` / blur state 控制 `分類不可空白`，不要一進頁面就誤顯錯誤。
  - `UploadMode` 接收 required error 並傳給 `FileDropZone`。
  - 若切回 text mode，upload required error 不顯示。
  - `addVersionMutation` 的 structured findings 顯示不可被改壞。

## 單元測試 / 整合測試
- `frontend/src/pages/SkillEditPage.test.tsx`
  - `AC-S197-6: 清空分類顯示分類不可空白且儲存分類 disabled`
  - `AC-S197-6: upload mode 未選檔顯示請選擇 zip 或 SKILL.md`
  - `AC-S197-7: SkillEdit required errors connect aria-invalid and aria-describedby`

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage`

## 前置條件
- S197-T02 PASS

## 狀態
pending（待做）
