# S195-T03: Edit upload mode uses FileDropZone

## 對應規格
S195：Skill edit upload validation UX

## 這個 task 要做什麼
`frontend/src/pages/SkillEditPage.tsx` 的 upload mode 改用 `FileDropZone`，讓編輯頁和 `/publish` 一樣顯示「拖拽 zip 或 md 檔到此處」與「或點擊選取檔案」。完成後，使用者在 edit page 選 `handover.zip` 再按「儲存新版本」，frontend 會發 `PUT /api/v1/skills/{id}/versions`，FormData 裡的 `file.name` 是 `handover.zip`；若選 `handover.txt`，畫面顯示「只接受 .zip / .md」，且不送 PUT。

## 使用者情境（BDD）
Given（前提）使用者在 `/skills/skill-docker/edit`
When（動作）點擊「上傳檔案」
Then（結果）頁面顯示「拖拽 zip 或 md 檔到此處」
And（而且）頁面顯示「或點擊選取檔案」

Given（前提）使用者位於 upload mode
And（而且）已選取 `handover.zip`
When（動作）點擊「儲存新版本」
Then（結果）frontend 發出 `PUT /api/v1/skills/skill-docker/versions`
And（而且）FormData 內 `file.name == "handover.zip"`

Given（前提）使用者位於 upload mode
When（動作）選取 `handover.txt`
Then（結果）頁面顯示「只接受 .zip / .md」
And（而且）未發出 `PUT /api/v1/skills/skill-docker/versions`

## 研究來源
- `frontend/src/pages/SkillEditPage.tsx`：目前 `UploadMode` 自建 `<input type="file">`。
- `frontend/src/components/FileDropZone.tsx`：已有副檔名 guard 與 selected filename 顯示。
- `frontend/src/pages/SkillEditPage.test.tsx`：S187 upload test 已捕捉 `capturedForm` 並驗 `file/version`。
- `docs/grimo/specs/archive/2026-05-16-S187-skill-md-edit-page.md`：edit page 建立新版本的資料流已 ship。

## 先做 POC
- POC：not required — 這是既有 component 取代自建 input；S187 已驗證 edit page `addVersion` path，FileDropZone 已驗證 extension guard。
- Fixture：
  - `handover.zip`: callback 設定 `selectedFile`，按「儲存新版本」送 PUT。
  - `handover.txt`: `FileDropZone` 顯示 inline error，`selectedFile` 維持 null，save button disabled 或 click 後不送 PUT。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/SkillEditPage.tsx`
- 入口：`UploadMode`
- 必要行為：
  - import `FileDropZone`。
  - `UploadMode` 保留 `Skill 套件` label，label `htmlFor="skill-edit-file"`。
  - render `<FileDropZone inputId="skill-edit-file" onFileSelect={setSelectedFile} selectedFile={selectedFile} />`。
  - 不再保留普通 file input 的 `accept=".zip,.md,text/markdown,application/zip"` 寫法；accept 由 `FileDropZone` 預設 `.zip,.md` 處理。
  - `canSaveUpload` 繼續只看 `selectedFile != null`。
- Finding / response / DB 欄位：
  - FormData `file`: 使用者選到的原始 `File`。
  - FormData `version`: 若版本欄空白，仍由 S188 行為省略。

## 單元測試 / 整合測試
- `frontend/src/pages/SkillEditPage.test.tsx`
  - `it("AC-S195-1: edit upload mode shows drag/drop dropzone", ...)`
  - `it("AC-S195-2: edit upload mode sends selected zip to PUT versions", ...)`
  - `it("AC-S195-5: invalid extension shows inline error and does not PUT", ...)`
  - 既有 `AC-S187-4` 測試可改成 S195/S187 共同覆蓋，但不要移除 S187 成功導向驗證頁 assertion。

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage`

## 前置條件
- S195-T01 PASS

## 狀態
PASS（2026-05-18）

## 實作結果
- `frontend/src/pages/SkillEditPage.tsx`：`UploadMode` 改用 `FileDropZone`，並用 `inputId="skill-edit-file"` 讓 `Skill 套件` label 指向隱藏 file input。
- `frontend/src/pages/SkillEditPage.test.tsx`：新增 `AC-S195-1` 測試，確認 upload mode 顯示「拖拽 zip 或 md 檔到此處」與「或點擊選取檔案」。
- 同檔新增 `AC-S195-2` 測試，確認選 `handover.zip` 後按「儲存新版本」會送 `PUT /api/v1/skills/skill-docker/versions`，且 FormData 的 `file.name` 是 `handover.zip`。
- 同檔新增 `AC-S195-5` 測試，確認選 `handover.txt` 會顯示「只接受 .zip / .md 檔，目前是 handover.txt」，且不送 PUT。

## 驗證結果
- PASS：`cd frontend && npm test -- SkillEditPage`
- 結果：1 test file passed；9 tests passed。
