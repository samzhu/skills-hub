# S206: Cloud Build Source Upload Pruning

> 規格：S206 | 大小：XS(8) | 狀態：⏳ Plan
> 日期：2026-05-21
> 對應：S132 Cloud Build pipeline / `cloudbuild.yaml` / `docs/grimo/development-standards.md` Build & Deploy

---

## 1. 目標

`gcloud builds submit --config=cloudbuild.yaml .` 現在會先打包 35,346 個檔案、788.4 MiB，再上傳到 Cloud Build source bucket；這裡面包含 `backend/build/`、`backend/bin/`、`backend/.gradle/`、`backend/storage-local/`，也包含本機機敏設定檔 `backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml`。

本 spec 要新增 root `.gcloudignore`，讓 Cloud Build 只上傳真正會被 `cloudbuild.yaml` 用到的檔案：

- `cloudbuild.yaml`
- backend Gradle wrapper / Gradle config / `backend/src/**`
- `backend/config/` 內已 commit 的非機敏設定檔；不包含本機未 commit 的 secret 檔
- frontend `package*.json`、Vite/TypeScript/ESLint config、`frontend/src/**`、`frontend/public/**`

修完後，開發者再跑同一個 submit command 時，開頭應該變成大約 1,000 個檔案以內、10 MiB 以內；`docs/`、`.codex/`、`.claude/`、`e2e/`、`backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml` 不會再被放進 source tarball。S206 也要清掉 `gs://cfh-vibe-lab_cloudbuild/source/**` 下面所有 live Cloud Build source objects。

相依狀態：

| Spec | 狀態 | 對 S206 的影響 |
|---|---|---|
| S132 Cloud Build pipeline | ✅ shipped v4.17.0 | `cloudbuild.yaml` 已可從 repo root build frontend + backend image；S206 不改 build steps，只改 source upload input。 |
| S202 Production E2E Fixture Runner | ✅ shipped v4.86.0 | local release gate 仍走 production packaged image；S206 不碰 E2E runner。 |

非目標：

- 不改 Cloud Build step 順序、machine type、substitution、Artifact Registry path。
- 不改 `scripts/gcp/03-build-push.sh` 或 Cloud Run deploy manifest。
- 不刪本機 `backend/build/`、`backend/.gradle/`、`frontend/node_modules/` 等目錄；只是不讓它們進 Cloud Build source tarball。
- 不輪替 OAuth / DB / Gemini secrets；S206 只清理已上傳的 Cloud Build source objects，是否輪替 secret 另走人工 ops 判斷。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `gcloud` log `/Users/samzhu/.config/gcloud/logs/2026.05.21/10.09.57.063687.log` | gcloud 使用預設 ignore：`.gcloudignore`, `.git`, `.gitignore`, `#!include:.gitignore`；最後建立 35,346 files / 788.4 MiB archive。 | root 沒有 `.gcloudignore`；目前只吃 root `.gitignore`，沒有吃 `backend/.gitignore` 的 `build/`, `.gradle`, `bin/`, `storage-local/*` 規則。 |
| `gcloud meta list-files-for-upload .` | 目前列出 35,382 個檔案；top-level 分布是 `backend=34,648`, `docs=343`, `frontend=264`, `.claude=59`, `e2e=39`。 | 主要問題在 backend generated/local files；docs/agent runtime 雖小，但 build 不需要，也應排除。 |
| `gcloud meta list-files-for-upload . | rg 'backend/config/application-(secrets|real-oauth)'` | 會上傳 `backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml`。 | `.gcloudignore` 不能只排 build/cache；必須讓機敏 config 預設不上傳。 |
| [Google Cloud Build submit docs](https://cloud.google.com/build/docs/running-builds/submit-build-via-cli-api) | `gcloud builds submit` 會壓縮目前目錄的檔案並上傳；若要排除檔案，top-level upload directory 可放 `.gcloudignore`。如果沒有 `.gcloudignore` 但有 `.gitignore`，gcloud 會產生 Git-compatible ignore。Cloud Build 會用 build bucket 儲存 source code，且不會自動刪除 bucket 內容。 | 正確控制點是 repo root `.gcloudignore`，不是改 `cloudbuild.yaml` step；舊 source tarball 要另外清。 |
| `gcloud storage ls gs://cfh-vibe-lab_cloudbuild/source/ --project=cfh-vibe-lab \| wc -l` | 目前 `source/` 底下有 118 個 `.tgz` objects。 | 清理範圍定為 `gs://cfh-vibe-lab_cloudbuild/source/**` live objects；刪除前後要記錄數量。 |
| [Google Cloud SDK `gcloud topic gcloudignore`](https://cloud.google.com/sdk/gcloud/reference/topic/gcloudignore) | `.gcloudignore` 只在 top-level upload directory 生效；`#!include:.gitignore` 不遞迴 include nested `.gitignore`；可用 `gcloud meta list-files-for-upload` 看實際會上傳哪些檔案。 | 不能期待 `backend/.gitignore` 自動生效；S206 要在 root `.gcloudignore` 明列 build input allowlist。 |
| `cloudbuild.yaml` | Step 1 在 `frontend/` 跑 `npm ci`, `npm run verify`, `npm run build`；Step 3 在 `backend/` 跑 `./gradlew --no-daemon -x test bootBuildImage ... -Pspring.profiles.active=gcp,aot,lab`。 | source tarball 只需要 frontend source/config 和 backend source/config/Gradle wrapper；不需要 docs、E2E reports、local Gradle cache、local storage。 |
| `backend/.gitignore` / `frontend/.gitignore` / `e2e/.gitignore` | nested ignores 已經列出多數 generated dirs，但 gcloud 預設不遞迴 include 它們。 | root `.gcloudignore` 要吸收 nested ignores 的重要規則，或改成 build-input allowlist。 |
| `git ls-files backend/config` | 已 commit 的 config 是 `application-dev.yaml`, `application-lab.yaml`, `application-prod.yaml`, `application-real-oauth.yaml.example`, `application-secrets.properties.example`, `oauth-mock-config.json`。 | `.gcloudignore` 明列這 6 個檔案；不放 `backend/config/**` 萬用規則，避免本機 secret 跟著上傳。 |
| `rg oauth-mock-config` | `cloudbuild.yaml` 不讀 `oauth-mock-config.json`；但 `backend/compose.yaml`, `e2e/compose.e2e.yaml`, `SkillsHubAuthE2ETest`, `OAuthMockE2ETest` 會用。 | 依「backend/config 已 commit 檔案要上傳」規則保留它；它不是 Cloud Build image build 必要檔，但仍是 repo 的非機敏 config。 |

### 2.2 現況

實際 command：

```bash
gcloud meta list-files-for-upload .
```

目前會上傳的最大目錄：

| Path | 會上傳檔案數 | 本機大小 | 應不應上傳 |
|---|---:|---:|---|
| `backend/build/` | 23,564 | 271 MiB | 不應上傳；Cloud Build 會重新 build。 |
| `backend/bin/` | 6,924 | 42 MiB | 不應上傳；IDE / Gradle generated output。 |
| `backend/storage-local/` | 3,587 | 40 MiB | 不應上傳；本機檔案儲存。 |
| `backend/.gradle/` | 16 | 509 MiB | 不應上傳；本機 Gradle cache。 |
| `docs/`, `.claude/`, `.codex/`, `e2e/` | 446 | 約 8 MiB | Cloud Build image build 不需要。 |
| `backend/config/application-secrets.properties` | 1 | unknown | 絕對不應上傳；本機 secret。 |
| `backend/config/application-real-oauth.yaml` | 1 | unknown | 絕對不應上傳；本機 OAuth secret。 |

已確認 scope：S206 排除 `docs/`、`.codex/`、`.claude/`、`e2e/`；並刪除 `gs://cfh-vibe-lab_cloudbuild/source/**` 下面所有 live source objects。Secret 輪替不進本 spec。

Cloud Build 實際需要的 input 很小：

```text
repo root
├── cloudbuild.yaml
├── backend/
│   ├── gradlew, gradle/, build.gradle.kts, settings.gradle.kts, gradle.properties
│   ├── src/**
│   └── config/<git-tracked non-secret files>
└── frontend/
    ├── package.json, package-lock.json
    ├── index.html, vite.config.ts, tsconfig*.json, eslint.config.js, components.json
    ├── public/**
    └── src/**
```

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|----------|--------|-----------|
| A. 在 root `.gcloudignore` 補 generated/local/secrets exclude list | no | 最快，但未來新增新的本機輸出或 secret path 時，可能又被上傳；docs/agent runtime 也還會進 tarball。 |
| B. 在 root `.gcloudignore` 用 build-input allowlist | yes | 預設全部不上傳，只打開 Cloud Build 實際會讀的檔案；能同時縮小 tarball 和防止本機 secret 混入。未來新增 build input 時要同步 `.gcloudignore`，但漏掉會在 Cloud Build step 直接 fail，問題明顯。 |
| C. 改 `gcloud builds submit backend/` 並讓 Cloud Build checkout frontend | no | 現有 pipeline 是 repo root 同時 build frontend + backend；改 source root 會重寫 pipeline，不是本問題的最小修法。 |
| D. 改 Developer Connect trigger，不用 local `gcloud builds submit` | no | 可以避開本機 cache upload，但目前 S132 明確保留 LAB manual submit；而且 manual submit 仍需要安全的 source selection。 |

選 B。`.gcloudignore` 採 allowlist：用 `*` 預設排除、`!*/` 保留目錄 traversal，接著用官方 last-match-wins 規則重新排除 local/generated/secret trees，最後只 allowlist Cloud Build input files。

### 2.4 `.gcloudignore` 設計

草稿：

```gitignore
# S206: Cloud Build source upload allowlist.
# gcloud reads only the top-level .gcloudignore for builds submit.
*
!*/

.agents/**
.claude/**
.codex/**
.git/**
.worktrees/**
docs/**
e2e/**
temp/**
backend/.gradle/**
backend/bin/**
backend/build/**
backend/storage-local/**
backend/config/application-secrets.properties
backend/config/application-real-oauth.yaml
frontend/dist/**
frontend/node_modules/**

!.gcloudignore
!cloudbuild.yaml

!backend/gradlew
!backend/gradlew.bat
!backend/settings.gradle.kts
!backend/build.gradle.kts
!backend/gradle.properties
!backend/gradle/**
!backend/src/**
!backend/config/application-dev.yaml
!backend/config/application-lab.yaml
!backend/config/application-prod.yaml
!backend/config/application-real-oauth.yaml.example
!backend/config/application-secrets.properties.example
!backend/config/oauth-mock-config.json

!frontend/package.json
!frontend/package-lock.json
!frontend/index.html
!frontend/vite.config.ts
!frontend/tsconfig.json
!frontend/tsconfig.app.json
!frontend/tsconfig.node.json
!frontend/eslint.config.js
!frontend/components.json
!frontend/public/**
!frontend/src/**
```

明確不上傳：

- `backend/config/application-secrets.properties`
- `backend/config/application-real-oauth.yaml`
- `backend/build/**`, `backend/bin/**`, `backend/.gradle/**`, `backend/storage-local/**`
- `frontend/node_modules/**`, `frontend/dist/**`
- `e2e/**`, `docs/**`, `.claude/**`, `.codex/**`, `.agents/**`, `temp/**`, `.worktrees/**`

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `.gcloudignore` | Google `gcloudignore` docs + local `cloudbuild.yaml` | `gcloud meta list-files-for-upload .` 只列出 build input files + tracked backend config，總數 ≤ 1,000 | output 不含 `docs/`, `.codex/`, `.claude/`, `e2e/`, `backend/build/`, `backend/bin/`, `backend/.gradle/`, `backend/storage-local/`, `frontend/node_modules/`, `frontend/dist/`, `backend/config/application-secrets.properties`, `backend/config/application-real-oauth.yaml` | not required |
| T02 | `docs/grimo/development-standards.md` Build & Deploy checklist | S206 finding | 文件寫明 Cloud Build source upload 由 root `.gcloudignore` allowlist 控制 | 後續 reviewer 不會以為 nested `.gitignore` 會自動被 gcloud 讀取 | not required |
| T03 | `docs/grimo/qa-strategy.md` Verification Command Registry | Google `gcloud meta list-files-for-upload` | 新增 Cloud Build source upload inspection command，說明缺 `gcloud` 時 skip | 不把真 Cloud Build submit 當每次 release gate，避免每次 QA 都上傳/收費 | not required |
| T04 | GCS source object cleanup evidence | Cloud Build source bucket | `source/` object count 從目前 118 變成 0 | 只刪 `gs://cfh-vibe-lab_cloudbuild/source/**` live objects，不刪 build logs bucket 或 Artifact Registry image | not required |
| T05 | Manual Cloud Build submit + Cloud Run deploy evidence | Cloud Build + Cloud Run | `.gcloudignore` 生效後重新 submit 一次，Cloud Build build 成功，接著 Cloud Run service replace 成功，latest ready revision 是新 image，`/actuator/health` 回 200，新 revision 部署後 `severity>=ERROR` log 0 rows | spec 只記錄 build id、image tag、deploy revision/result、health result、ERROR log count、file-count evidence，不記錄本機 export 指令或完整 submit / replace command | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-pr.sh`
通過條件：既有 V01-V06 都 PASS；本 spec 的 upload source evidence 另以 `gcloud meta list-files-for-upload .` 記錄在 §7。

Ship 前正式 gate：

執行：`./scripts/verify-release.sh`
通過條件：V01-V09 都 PASS；若 gcloud CLI 可用，§7 同時記錄 `gcloud meta list-files-for-upload .` 的 count 與 forbidden-path grep 結果。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S206-1 | 必做 | Demo | Cloud Build upload file count 降到 1,000 以內 |
| AC-S206-2 | 必做 | Demo | generated/local 目錄不進 source tarball |
| AC-S206-3 | 必做 | Demo | 本機機敏 config 不進 source tarball |
| AC-S206-4 | 必做 | Demo | Cloud Build 必要 input 仍會上傳 |
| AC-S206-5 | 必做 | Demo | 已上傳 Cloud Build source object 被清掉 |
| AC-S206-6 | 必做 | Demo | 裁剪後 source 能 build 並部署成功 |
| AC-S206-7 | 建議 | Inspection | Build & Deploy 文件寫明 source upload allowlist |

**AC-S206-1: Cloud Build upload file count 降到 1,000 以內**
- Given（前提）repo root 有 S206 新增的 `.gcloudignore`
- When（動作）在 repo root 執行 `gcloud meta list-files-for-upload . | wc -l`
- Then（結果）輸出數字 ≤ 1000
- And（而且）下一次 `gcloud builds submit --config=cloudbuild.yaml .` 開頭不再顯示 35,000+ files / 788 MiB

**AC-S206-2: generated/local 目錄不進 source tarball**
- Given（前提）本機存在 `backend/build/`、`backend/bin/`、`backend/.gradle/`、`backend/storage-local/`、`frontend/node_modules/`、`frontend/dist/`
- When（動作）執行：

```bash
gcloud meta list-files-for-upload . | rg '^(docs|\\.codex|\\.claude|e2e|backend/(build|bin|\\.gradle|storage-local)|frontend/(node_modules|dist))/'
```

- Then（結果）command exit code 是 1，代表沒有任何匹配檔案會被上傳

**AC-S206-3: 本機機敏 config 不進 source tarball**
- Given（前提）本機存在 `backend/config/application-secrets.properties` 和 `backend/config/application-real-oauth.yaml`
- When（動作）執行：

```bash
gcloud meta list-files-for-upload . | rg '^backend/config/(application-secrets\\.properties|application-real-oauth\\.yaml)$'
```

- Then（結果）command exit code 是 1，代表這兩個檔案不會被上傳到 Cloud Build source bucket
- And（而且）已 commit 的 `backend/config/application-secrets.properties.example` 和 `backend/config/application-real-oauth.yaml.example` 允許上傳，因為它們是模板，不含真 secret

**AC-S206-4: Cloud Build 必要 input 仍會上傳**
- Given（前提）repo root 有 S206 新增的 `.gcloudignore`
- When（動作）執行：

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

- Then（結果）每個 `rg -qx` 都回 exit code 0，代表上列每個必要檔案都會上傳
- And（而且）Cloud Build step 仍能在 `frontend/` 跑 `npm ci && npm run verify && npm run build`，並在 `backend/` 跑 `./gradlew bootBuildImage`

**AC-S206-5: 已上傳 Cloud Build source objects 被清掉**
- Given（前提）`gcloud storage ls gs://cfh-vibe-lab_cloudbuild/source/ --project=cfh-vibe-lab | wc -l` 目前看到 118 個 source objects
- When（動作）執行：

```bash
gcloud storage rm 'gs://cfh-vibe-lab_cloudbuild/source/**' --project=cfh-vibe-lab
gcloud storage ls gs://cfh-vibe-lab_cloudbuild/source/ --project=cfh-vibe-lab | wc -l
```

- Then（結果）第二個 command 輸出 0
- And（而且）不刪 Cloud Build logs bucket 或 Artifact Registry image

**AC-S206-6: 裁剪後 source 能 build 並部署成功**
- Given（前提）`.gcloudignore` 已套用，且 `gcloud meta list-files-for-upload .` 不再列出 local cache / generated / secret paths
- When（動作）開發者在本機用既有 Cloud Build manual submit path 重新送一次 build，並把同一個 image tag 部署到 Cloud Run
- Then（結果）Cloud Build result 是 SUCCESS
- And（而且）Cloud Run service replace 成功，latest ready revision 指向新部署的 image
- And（而且）新 revision 的 `/actuator/health` 回 HTTP 200
- And（而且）查詢新 revision 部署後時間窗的 Cloud Run `severity>=ERROR` logs，結果是 0 rows
- And（而且）submit 開頭顯示的 source file count ≤ 1,000，且 source archive size ≤ 10 MiB
- And（而且）spec §7 只記錄 build id、image tag、deploy revision/result、health result、ERROR log count、source file count / size，不記錄本機環境變數 export 指令、完整 submit command 或完整 deploy command

**AC-S206-7: Build & Deploy 文件寫明 source upload allowlist**
- Given（前提）開發者看 `docs/grimo/development-standards.md` 的 Build & Deploy 段
- When（動作）搜尋 `Cloud Build source upload`
- Then（結果）文件說明 root `.gcloudignore` 是 `gcloud builds submit` 的唯一 source upload allowlist
- And（而且）文件明講 nested `.gitignore` 不會被 gcloud 遞迴 include

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S206-1, AC-S206-2 | 本機 submit 前打包從 35k+ files / 788 MiB 降到 ≤ 1,000 files；減少等待與上傳時間。 |
| Security | AC-S206-3, AC-S206-5 | 本機 secret config 不進 Cloud Build source bucket；已上傳的 source object 也被刪除。 |
| Reliability | AC-S206-4, AC-S206-6 | allowlist 不可漏掉 Cloud Build 真正需要的 source/config；裁剪後必須重新 submit、成功編譯，並成功部署到 Cloud Run。 |
| Usability | AC-S206-1 | 開發者看 submit output 會看到檔案數和大小明顯降低。 |
| Maintainability | AC-S206-7 | build input allowlist 的維護位置寫進 standards，避免之後誤以為 nested ignore 會生效。 |

## 4. 介面與 API 設計

沒有 HTTP API、DB schema 或 frontend UI 變更。

新增的唯一 runtime-facing contract 是 source upload contract：

```text
gcloud builds submit --config=cloudbuild.yaml .
  reads repo root .gcloudignore
  uploads only Cloud Build input files
  never uploads local caches, build outputs, local storage, or secrets
```

驗證用 command：

```bash
gcloud meta list-files-for-upload .
```

必須保留的檔案類型：

| Build step | 必要 source | 來源 |
|---|---|---|
| `frontend-build` | `frontend/package.json`, `frontend/package-lock.json`, `frontend/src/**`, `frontend/public/**`, Vite/TS/ESLint config | `cloudbuild.yaml` step `dir: frontend` |
| `copy-static` | `frontend/dist` 不上傳；由前一步產生 | `cloudbuild.yaml` step `cp -r frontend/dist/. ...` |
| `boot-build-image` | `backend/gradlew`, `backend/gradle/**`, Gradle build files, `backend/src/**`, tracked `backend/config/*` except local secret files | `cloudbuild.yaml` step `dir: backend` + repo config convention |

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `.gcloudignore` | new | Root source upload allowlist；只允許 Cloud Build build inputs。 |
| `docs/grimo/development-standards.md` | modify | Build & Deploy 段補 Cloud Build source upload allowlist 規則。 |
| `docs/grimo/qa-strategy.md` | modify | Verification registry 補 `gcloud meta list-files-for-upload .` inspection；缺 `gcloud` 時不擋一般 PR gate，但 release evidence 要記錄可用/不可用。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S206 active row。 |
| GCS objects `gs://cfh-vibe-lab_cloudbuild/source/**` | delete | 清掉過去所有 `gcloud builds submit` 上傳的 live source tarballs；目前觀察為 118 個 objects。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task 規劃

POC：not required — S206 不導入新 dependency / SDK，也不包裝 framework SPI；設計假設可用 `gcloud meta list-files-for-upload .`、`gcloud storage`、Cloud Build、Cloud Run 實際 evidence 直接驗證。

| # | Task | AC | 狀態 |
|---|------|----|--------|
| T01 | `docs/grimo/tasks/2026-05-21-S206-T01-gcloudignore-source-allowlist.md` — root `.gcloudignore` source allowlist | AC-S206-1, AC-S206-2, AC-S206-3, AC-S206-4 | PASS |
| T02 | `docs/grimo/tasks/2026-05-21-S206-T02-docs-source-upload-rule.md` — Build & Deploy / QA 文件同步 | AC-S206-7 | PASS |
| T03 | `docs/grimo/tasks/2026-05-21-S206-T03-gcs-cleanup-cloud-run-evidence.md` — GCS cleanup + Cloud Build / Cloud Run evidence | AC-S206-5, AC-S206-6 | pending（待做） |

執行順序：T01 → T02 → T03。

### POC Findings

- POC 不需要；T01 的 upload list、T03 的實際 Cloud Build / Cloud Run deploy 是本 spec 的行為驗證。
- `oauth-mock-config.json` 不被 `cloudbuild.yaml` 直接讀取，但它是已 commit 的 backend config，且被 backend compose、E2E compose、OAuth tests 使用；依使用者決策保留在 `.gcloudignore` allowlist。
- 本機未 commit 的 `backend/config/application-secrets.properties` 與 `backend/config/application-real-oauth.yaml` 必須維持不上傳。

## 7. 實作結果

<!-- Added after implementation -->

### T01: root `.gcloudignore` source allowlist

Date: 2026-05-21

Files changed:
- `.gcloudignore`

Evidence:
- `gcloud meta list-files-for-upload .` count: `837` files.
- Forbidden path grep for `docs/`, `.codex/`, `.claude/`, `e2e/`, `backend/build/`, `backend/bin/`, `backend/.gradle/`, `backend/storage-local/`, `frontend/node_modules/`, `frontend/dist/`: no matches.
- Secret config grep for `backend/config/application-secrets.properties` and `backend/config/application-real-oauth.yaml`: no matches.
- Required inputs check: `cloudbuild.yaml`, backend Gradle wrapper/build files/source, tracked backend config, frontend package/config/source all matched exactly.

Root cause checkpoint:
- Official Google Cloud SDK docs say `gcloud builds submit` respects the top-level `.gcloudignore`, pattern order is last-match-wins, and `gcloud meta list-files-for-upload` displays the files that will be uploaded.
- The failed attempt unignored broad directories too early. Final rule keeps traversal open with `!*/`, re-ignores forbidden local/generated/secret trees, then allowlists exact Cloud Build inputs.

### T02: Cloud Build source upload docs

Date: 2026-05-21

Files changed:
- `docs/grimo/development-standards.md`
- `docs/grimo/qa-strategy.md`

Evidence:
- RED inspection: `rg -n "Cloud Build source upload|\\.gcloudignore|gcloud meta list-files-for-upload|nested .*\\.gitignore|遞迴 include" docs/grimo/development-standards.md docs/grimo/qa-strategy.md` returned exit code `1`.
- GREEN inspection: same command returned exit code `0`.
- `docs/grimo/development-standards.md` now documents that root `.gcloudignore` is the Cloud Build source upload allowlist and nested `.gitignore` is not recursively included by gcloud.
- `docs/grimo/qa-strategy.md` now documents `gcloud meta list-files-for-upload .` as a supplemental source upload inspection, not a fixed `verify-release.sh` command.
