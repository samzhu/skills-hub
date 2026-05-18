# S197-T02: FileDropZone required error contract

## 對應規格
S197：必填欄位即時提示 UX

## 這個 task 要做什麼
`FileDropZone` 要支援 caller 傳入「未選檔」這種外部 required error，並把錯誤接到 dropzone wrapper 的 a11y attributes。既有副檔名與檔案大小錯誤仍要照原本行為顯示，不能被外部 required error 蓋掉。

## 使用者情境（BDD）
Given（前提）`FileDropZone` 沒有 selected file，caller 傳入 `error="請選擇 zip 或 SKILL.md"`
When（動作）元件 render
Then（結果）dropzone 下方顯示 `請選擇 zip 或 SKILL.md`
And（而且）dropzone wrapper 有 `aria-invalid="true"`，`aria-describedby` 包含 error id

Given（前提）caller 傳入 required error
When（動作）使用者選擇 `handover.txt`
Then（結果）顯示既有副檔名錯誤 `只接受 .zip / .md 檔，目前是 handover.txt`
And（而且）不呼叫 `onFileSelect`

## 研究來源
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md`
- `frontend/src/components/FileDropZone.tsx`
- `frontend/src/components/FileDropZone.test.tsx`
- S195 shipped：edit upload mode 已重用 `FileDropZone`

## 先做 POC
- POC：not required — 只擴充現有 component props 與 RTL tests，不新增套件或瀏覽器專屬 API。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/components/FileDropZone.tsx`
- 入口：`FileDropZone` props
- 必要行為：
  - 新增 props，例如 `error?: string | null`、`describedBy?: string`、`errorId?: string`。
  - 外部 required error 和內部 `sizeError` / extension error 分清楚；內部錯誤優先顯示。
  - wrapper 加 `role` 或可測 attributes 時，不要破壞隱藏 file input 的 `label` / `inputId` contract。
  - 選到合法檔案後清掉內部錯誤，caller 可透過 `selectedFile` 清掉外部錯誤。

## 單元測試 / 整合測試
- `frontend/src/components/FileDropZone.test.tsx`
  - `AC-S197-3: FileDropZone shows required error from caller`
  - `AC-S197-3: invalid extension still shows extension error instead of required error`
  - `AC-S197-7: FileDropZone exposes aria-invalid and aria-describedby when error exists`

## 會改哪些檔案
- `frontend/src/components/FileDropZone.tsx`
- `frontend/src/components/FileDropZone.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- FileDropZone`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-18
Test: `AC-S197-3`, `AC-S197-7` (`frontend/src/components/FileDropZone.test.tsx`)
Files changed:
- `frontend/src/components/FileDropZone.tsx` (modified)
- `frontend/src/components/FileDropZone.test.tsx` (modified)
Notes: RED confirmed with `cd frontend && npm test -- FileDropZone` failing 2 new S197 cases; GREEN confirmed with `cd frontend && npm test -- FileDropZone` passing 10 tests.
