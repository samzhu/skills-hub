# S206-T02: Cloud Build source upload docs

## 對應規格
S206：Cloud Build Source Upload Pruning

## 這個 task 要做什麼
這個 task 完成後，專案文件會明確說明 Cloud Build manual submit 的 source upload 由 repo root `.gcloudignore` 控制。未來有人新增 build input 時，會知道要同步 `.gcloudignore`。文件也會說明 nested `.gitignore` 不會被 gcloud 遞迴 include，所以不能只靠 `backend/.gitignore` 或 `frontend/.gitignore` 擋上傳。

## 使用者情境（BDD）
Given（前提）開發者正在看 `docs/grimo/development-standards.md` 的 Build & Deploy 段  
When（動作）搜尋 `Cloud Build source upload`  
Then（結果）文件說明 root `.gcloudignore` 是 `gcloud builds submit` 的 source upload allowlist  
And（而且）文件明講 nested `.gitignore` 不會被 gcloud 遞迴 include  
And（而且）`docs/grimo/qa-strategy.md` 有一個檢查項說明如何用 `gcloud meta list-files-for-upload .` 驗證 source tarball 清單

## 研究來源
- `docs/grimo/specs/2026-05-21-S206-cloud-build-source-upload-pruning.md`
- `docs/grimo/development-standards.md` Build & Deploy
- `docs/grimo/qa-strategy.md` Verification Command Registry
- Google Cloud SDK `gcloud topic gcloudignore`: https://cloud.google.com/sdk/gcloud/reference/topic/gcloudignore

## 先做 POC
- POC：not required — 文件同步，不涉及新 API 或 runtime 行為。

## 正式程式怎麼做
- Class / file 名稱：N/A
- 入口：專案文件
- 必要行為：
  - 在 `docs/grimo/development-standards.md` Build & Deploy / package rules 附近補一段 `Cloud Build source upload`。
  - 說明 `.gcloudignore` 是 build source allowlist，新增 Cloud Build build input 時必須同步更新。
  - 說明不應上傳 local build output、cache、local storage、agent runtime dirs、docs-only dirs、未 commit secret config。
  - 在 `docs/grimo/qa-strategy.md` 的 verification registry 或 script strategy 補 source upload inspection。不要把真 Cloud Build submit 加成每次 `verify-release.sh` 都要跑的固定 command；S206 這次有一次性的 deploy evidence，但日常 release gate仍以 `verify-release.sh` 為主。
  - 如果文件提到缺 `gcloud` 的情況，要說明普通本機 PR gate 不因缺 CLI 失敗；S206 本身需要在有 gcloud 的環境補 evidence。
- Finding / response / DB 欄位：N/A — docs-only。

## 單元測試 / 整合測試
- No JUnit/Vitest file — 以 `rg` inspection 驗證文件內容。
- Evidence 要寫回 S206 §7：
  - `AC-S206-7`: 文件中存在 `Cloud Build source upload`、`.gcloudignore`、`gcloud meta list-files-for-upload`、nested `.gitignore` 不遞迴 include 的說明。

## 會改哪些檔案
- `docs/grimo/development-standards.md`
- `docs/grimo/qa-strategy.md`

## 驗證方式
執行：

```bash
rg -n "Cloud Build source upload|\\.gcloudignore|gcloud meta list-files-for-upload|nested .*\\.gitignore|遞迴 include" docs/grimo/development-standards.md docs/grimo/qa-strategy.md
```

## 前置條件
- S206-T01 PASS 或同一輪已確定 `.gcloudignore` 最終規則

## Status
PASS

## Result
Date: 2026-05-21
Test: `rg -n "Cloud Build source upload|\\.gcloudignore|gcloud meta list-files-for-upload|nested .*\\.gitignore|遞迴 include" docs/grimo/development-standards.md docs/grimo/qa-strategy.md`
Files changed:
- `docs/grimo/development-standards.md` (modified)
- `docs/grimo/qa-strategy.md` (modified)
Notes:
- RED: the inspection command returned exit code `1`; neither file documented the Cloud Build source upload rule.
- GREEN: the same inspection command returned exit code `0`; `development-standards.md` now says root `.gcloudignore` is the Cloud Build source upload allowlist and nested `.gitignore` is not recursively included; `qa-strategy.md` now documents `gcloud meta list-files-for-upload .` as a supplemental source upload inspection, not a fixed `verify-release.sh` command.
- Official docs checked: https://docs.cloud.google.com/sdk/gcloud/reference/topic/gcloudignore.
