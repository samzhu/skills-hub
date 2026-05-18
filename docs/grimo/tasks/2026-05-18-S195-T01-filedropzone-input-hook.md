# S195-T01: FileDropZone input hook for edit page tests

## 對應規格
S195：Skill edit upload validation UX

## 這個 task 要做什麼
`frontend/src/components/FileDropZone.tsx` 增加可選的 `inputId`，讓 edit page 可以把「Skill 套件」label 指到隱藏的 file input。完成後，`/publish` 仍會顯示「拖拽 zip 或 md 檔到此處」，edit page test 則能用 `screen.getByLabelText('Skill 套件')` 選檔，不需要 querySelector 找隱藏 input。

## 使用者情境（BDD）
Given（前提）`SkillEditPage` 的 upload mode 會用 label `Skill 套件`
When（動作）`FileDropZone` 收到 `inputId="skill-edit-file"`
Then（結果）hidden `<input type="file">` 的 `id` 是 `skill-edit-file`
And（而且）`FileDropZone` 未收到 `inputId` 時，`/publish` 既有 drag/drop 文案與 `.zip/.md` guard 不變

## 研究來源
- `frontend/src/components/FileDropZone.tsx`：已有 drag/drop、click select、`.zip,.md` 副檔名檢查、10MB inline error。
- `frontend/src/pages/PublishPage.tsx`：`/publish` 目前直接使用 `<FileDropZone onFileSelect={setFile} selectedFile={file} />`。
- `frontend/src/components/FileDropZone.test.tsx`：既有測試覆蓋 prompt、selected file、invalid extension、oversize、zip/md callback。

## 先做 POC
- POC：not required — 不新增套件、不改瀏覽器 API，只把 optional prop 傳到既有 input。
- Fixture：
  - `inputId="skill-edit-file"`：render 後 `container.querySelector('#skill-edit-file')` 存在。
  - `inputId` 省略：prompt 與 invalid extension 測試仍通過。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/components/FileDropZone.tsx`
- 入口：`FileDropZone` component props
- 必要行為：
  - `FileDropZoneProps` 增加 `inputId?: string`。
  - hidden input 加 `id={inputId}`。
  - 不改 `accept` 預設值、不改副檔名 guard、不改錯誤文案。
- Finding / response / DB 欄位：
  - N/A — 純前端 component prop。

## 單元測試 / 整合測試
- `frontend/src/components/FileDropZone.test.tsx`
  - `it("AC-S195-1: inputId wires label-compatible hidden file input", ...)`
  - 既有 `AC-1` 到 `AC-6` 不可改壞。

## 會改哪些檔案
- `frontend/src/components/FileDropZone.tsx`
- `frontend/src/components/FileDropZone.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- FileDropZone`

## 前置條件
- 無

## 實作結果

- `FileDropZoneProps` 新增 `inputId?: string`。
- hidden `<input type="file">` 會收到 `id={inputId}`，讓 edit page 可以用外部 label 指向它。
- 既有 `/publish` 預設 `accept=".zip,.md"`、副檔名 guard、大小 guard、文案都未改。

## 驗證結果

執行：`cd frontend && npm test -- FileDropZone`

結果：PASS — 1 test file / 7 tests。

## 狀態
PASS
