# S206-T01: root `.gcloudignore` source allowlist

## 對應規格
S206：Cloud Build Source Upload Pruning

## 這個 task 要做什麼
這個 task 完成後，開發者在 repo root 跑 `gcloud builds submit` 時，Cloud Build source tarball 只會包含 build 真正需要的檔案。`backend/build/`、`backend/.gradle/`、`frontend/node_modules/`、`docs/`、`.codex/`、`.claude/`、`e2e/` 不會再被打包。本機 secret 檔 `backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml` 也不會再進 Cloud Build source bucket。

## 使用者情境（BDD）
Given（前提）repo root 有 S206 新增的 `.gcloudignore`，且本機存在 build/cache/local output 與本機 secret config  
When（動作）執行 `gcloud meta list-files-for-upload .`  
Then（結果）輸出檔案數 ≤ 1000  
And（而且）輸出不含 `docs/`、`.codex/`、`.claude/`、`e2e/`、`backend/build/`、`backend/bin/`、`backend/.gradle/`、`backend/storage-local/`、`frontend/node_modules/`、`frontend/dist/`  
And（而且）輸出不含 `backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml`  
And（而且）輸出仍包含 `cloudbuild.yaml`、backend Gradle wrapper/build files/source、已 commit 的 `backend/config/*` 非機敏檔、frontend package/config/source

## 研究來源
- `docs/grimo/specs/2026-05-21-S206-cloud-build-source-upload-pruning.md`
- Google Cloud Build submit docs: https://cloud.google.com/build/docs/running-builds/submit-build-via-cli-api
- Google Cloud SDK `gcloud topic gcloudignore`: https://cloud.google.com/sdk/gcloud/reference/topic/gcloudignore
- `cloudbuild.yaml`
- `git ls-files backend/config`

## 先做 POC
- POC：not required — 這個 task 使用 gcloud 內建的 `gcloud meta list-files-for-upload .` 直接驗證實際上傳清單，不需要另外建立 isolated POC。

## 正式程式怎麼做
- Class / file 名稱：`.gcloudignore`
- 入口：`gcloud builds submit --config=cloudbuild.yaml .` 讀 repo root `.gcloudignore`
- 必要行為：
  - 採 allowlist：預設 `*` 排除全部，再明確 unignore Cloud Build build input。
  - 保留 `cloudbuild.yaml`。
  - 保留 backend Gradle wrapper/build files/source：`backend/gradlew`、`backend/gradlew.bat`、`backend/settings.gradle.kts`、`backend/build.gradle.kts`、`backend/gradle.properties`、`backend/gradle/**`、`backend/src/**`。
  - 保留已 commit 的 backend config：`application-dev.yaml`、`application-lab.yaml`、`application-prod.yaml`、`application-real-oauth.yaml.example`、`application-secrets.properties.example`、`oauth-mock-config.json`。
  - 保留 frontend build input：`frontend/package.json`、`frontend/package-lock.json`、`frontend/index.html`、`frontend/vite.config.ts`、`frontend/tsconfig*.json`、`frontend/eslint.config.js`、`frontend/components.json`、`frontend/public/**`、`frontend/src/**`。
  - 不使用 `!backend/config/**`，避免未 commit 的 secret 檔被放進 tarball。
  - 不 unignore `docs/`、`.codex/`、`.claude/`、`.agents/`、`e2e/`、`temp/`、`.worktrees/`、generated output 或 local cache。
- Finding / response / DB 欄位：N/A — 這是 source upload 設定檔。

## 單元測試 / 整合測試
- No JUnit/Vitest file — 以 gcloud upload-list command 驗證。
- Evidence 要寫回 S206 §7：
  - `AC-S206-1`: file count ≤ 1000。
  - `AC-S206-2`: forbidden generated/local path grep 沒有輸出。
  - `AC-S206-3`: secret config grep 沒有輸出。
  - `AC-S206-4`: required build input 每個都存在於 upload list。

## 會改哪些檔案
- `.gcloudignore`

## 驗證方式
執行：

```bash
gcloud meta list-files-for-upload . | wc -l
gcloud meta list-files-for-upload . | rg '^(docs|\.codex|\.claude|e2e|backend/(build|bin|\.gradle|storage-local)|frontend/(node_modules|dist))/'
gcloud meta list-files-for-upload . | rg '^backend/config/(application-secrets\.properties|application-real-oauth\.yaml)$'
```

上面第二、第三個 `rg` 預期 exit code 是 1。

再用暫存 upload list 逐一確認必要檔案存在：

```bash
upload_list="$(mktemp)"
gcloud meta list-files-for-upload . > "$upload_list"
for file in \
  cloudbuild.yaml \
  backend/gradlew \
  backend/build.gradle.kts \
  backend/settings.gradle.kts \
  backend/gradle/wrapper/gradle-wrapper.jar \
  backend/src/main/resources/application.yaml \
  backend/config/application-dev.yaml \
  backend/config/application-lab.yaml \
  backend/config/application-prod.yaml \
  backend/config/application-real-oauth.yaml.example \
  backend/config/application-secrets.properties.example \
  backend/config/oauth-mock-config.json \
  frontend/package-lock.json \
  frontend/package.json \
  frontend/index.html \
  frontend/vite.config.ts \
  frontend/src/App.tsx
do
  rg -qx "$file" "$upload_list"
done
```

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-21
Test: `gcloud meta list-files-for-upload .` upload-list contract
Files changed:
- `.gcloudignore` (new)
Notes:
- RED: `.gcloudignore` did not exist; `gcloud meta list-files-for-upload . | wc -l` returned `35386`; forbidden path check listed `frontend/dist`, `.claude`, `frontend/node_modules`, `backend/storage-local`; secret check listed `backend/config/application-secrets.properties` and `backend/config/application-real-oauth.yaml`.
- GREEN: after adding `.gcloudignore`, upload-list count is `837`; forbidden path check returned no matches; secret config check returned no matches; required build inputs all matched exactly.
- First GREEN attempt failed because `*` plus direct `!frontend/` / `!backend/` unignored whole subtrees. Final rule keeps directory traversal open with `!*/`, explicitly re-ignores local/generated trees, then allowlists exact build input files and subtrees.
- Root cause confirmed against official docs: `gcloud builds submit` respects only the top-level `.gcloudignore`; `.gcloudignore` pattern order is last-match-wins; directory contents must be ignored with directory patterns such as `qux/**`; `gcloud meta list-files-for-upload` is the official inspection command.
- Official docs checked: https://cloud.google.com/build/docs/running-builds/submit-build-via-cli-api and https://docs.cloud.google.com/sdk/gcloud/reference/topic/gcloudignore.
- Re-run after official-doc checkpoint: upload-list count `837`; forbidden path check no matches; secret config check no matches; required build inputs all matched exactly.
