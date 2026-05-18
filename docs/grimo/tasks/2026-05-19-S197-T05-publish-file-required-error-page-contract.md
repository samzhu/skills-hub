# S197-T05: Publish file mode required error page contract

## 對應規格
S197：必填欄位即時提示 UX

## 這個 task 要做什麼
`/publish` 的上傳檔案 mode 目前只把 required error 做在 `FileDropZone` 元件層，頁面沒有把 `請選擇 zip 或 SKILL.md` 傳給 dropzone。這個 task 完成後，使用者填完 `技能名稱` 與 `分類` 但還沒選檔時，dropzone 區域會直接顯示缺檔案原因，發佈按鈕維持 disabled。副檔名錯誤仍由 `FileDropZone` 自己的錯誤優先顯示。

## 使用者情境（BDD）
Given（前提）使用者打開 `/publish`，停在 `上傳檔案` mode，已填 `技能名稱` 與 `分類`
When（動作）尚未選擇 zip 或 SKILL.md 檔案
Then（結果）dropzone 下方顯示 `請選擇 zip 或 SKILL.md`
And（而且）dropzone wrapper 有 `aria-invalid="true"`，`aria-describedby` 包含 `publish-file-error`
And（而且）如果使用者選了 `.txt`，畫面改顯示 `只接受 .zip / .md 檔`，不被 required error 蓋掉

## 研究來源
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md` §7.5：QA finding 指出 `PublishPage` 未傳 `error` / `describedBy` / `errorId` 給 `FileDropZone`。
- `frontend/src/components/FileDropZone.tsx`：已支援 `error`、`describedBy`、`errorId`，且內部副檔名 / 大小錯誤會優先顯示。
- `frontend/src/pages/PublishPage.tsx`：file mode 目前只 render `<FileDropZone inputId="publish-file" onFileSelect={setFile} selectedFile={file} />`。

## 先做 POC
- POC：not required — S197-T05 只串接既有 component props 與 RTL DOM assertion，不新增套件、SDK、後端 API、schema migration 或 browser-only API。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/PublishPage.tsx`
- 入口：`PublishPage` file mode form section
- 必要行為：
  - `mode === 'file'` 且 `file === null`，並且 `技能名稱` 與 `分類` 都已有值時，產生 `請選擇 zip 或 SKILL.md`。
  - 將 required error 傳給 `FileDropZone` 的 `error` prop。
  - 傳入 `describedBy="publish-file-help"` 與 `errorId="publish-file-error"`。
  - 加上 `publish-file-help` help text，讓 `aria-describedby` 有穩定 help + error id。
  - 不改 `uploadSkill` FormData shape，不改 text mode 行為。

## 單元測試 / 整合測試
- `PublishPage.test.tsx`
  - `@DisplayName("AC-S197-3: Publish file mode 未選檔顯示 inline required error")`
  - `@DisplayName("AC-S197-3: Publish file mode 副檔名錯誤優先於 required error")`

## 會改哪些檔案
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`
- `docs/grimo/specs/2026-05-18-S197-required-field-inline-cues.md`
- `docs/grimo/specs/spec-roadmap.md`

## 驗證方式
執行：`cd frontend && npm test -- PublishPage`

## 前置條件
- S197-T02 PASS — `FileDropZone` 已有 `error` / `describedBy` / `errorId` props。

## 狀態
PASS（2026-05-19）

## 實作結果
- `PublishPage` file mode 在技能名稱與分類已填、但尚未選檔時，會把 `請選擇 zip 或 SKILL.md` 傳給 `FileDropZone`。
- `FileDropZone` wrapper 的 `aria-describedby` 會包含 `publish-file-help publish-file-error`。
- 選 `.txt` 時仍顯示 `只接受 .zip / .md 檔`，內部檔案錯誤優先於 required error。

## 驗證結果
執行：`cd frontend && npm test -- PublishPage`

結果：PASS — 1 file / 23 tests。
