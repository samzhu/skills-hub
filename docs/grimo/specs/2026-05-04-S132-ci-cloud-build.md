# S132: CI — Cloud Build pipeline (build + push image)

> Spec: S132 | Size: XS(7) | Status: ⏸ BLOCKED（T01 ✅ shipped commit 002a111；T02 waiting user GCP Console action）
> Date: 2026-05-04
> Depends: S013 (✅ shipped — `scripts/gcp/03-build-push.sh` 是 CI step 的單機對照路徑；image / tag / repo 規則沿用)
> Research: Cloud Build 2025/2026 best practices（cloudbuild.yaml schema、Developer Connect GitHub trigger、bootBuildImage in CI、`gcr.io/google.com/cloudsdktool/cloud-sdk` builder、declarative `images:` push）

---

## 1. Goal

Push 到 `main` 後 GCP Cloud Build 自動跑：build 前端（npm）→ 複製 `frontend/dist` 到 backend `src/main/resources/static/` → `./gradlew bootBuildImage` 打包後端 OCI image → push 到 Artifact Registry（與 `scripts/gcp/03-build-push.sh` 同 repo / 同 tag 規則）。**Deploy 到 Cloud Run 不在本 spec 內**——維持手動跑 `scripts/gcp/04-deploy.sh`，自動化由後續 spec 接手。

順便把 backend Gradle 對 npm 的依賴拆掉——`./gradlew bootRun` 不再 trigger frontend build；本機 dev 改 `cd frontend && npm run dev` 獨立啟動。這同時解決今天 frontend TS error 擋住 backend `bootRun` 的痛點。

**簡單講**：`git push origin main` → 看 Cloud Build console 跑完 → AR 多一個 `:<SHORT_SHA>` image → 開發者跑 `TAG=<SHA> ./scripts/gcp/04-deploy.sh` 部署該版。

```
┌── 開發者本機 ──────────────────────────────────────────────────┐
│  cd frontend && npm run dev      （前端 hot reload @ 5173）   │
│  cd backend  && ./gradlew bootRun（後端 hot reload @ 8080）   │
└────────────────────────────────────────────────────────────────┘
                          │ git push origin main
                          ▼
┌── GCP Cloud Build (trigger: push to main) ────────────────────┐
│  Step 1  node:22                                               │
│          cd frontend && npm ci && npm run build               │
│  Step 2  alpine                                                │
│          cp -r frontend/dist/. backend/src/main/resources/static/ │
│  Step 3  eclipse-temurin:25-jdk  (Docker socket 自動 mount)   │
│          cd backend && ./gradlew bootBuildImage \              │
│              --imageName=<AR_PATH>:$SHORT_SHA                 │
│  images: [<AR_PATH>:$SHORT_SHA]   ← Cloud Build 自動 push    │
└────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌── Artifact Registry: <region>-docker.pkg.dev/<proj>/skillshub/skillshub:<sha> ┐
└────────────────────────────────────────────────────────────────────────────────┘
                          │ 開發者手動觸發（本 spec 不自動）
                          ▼
              TAG=<sha> ./scripts/gcp/04-deploy.sh
```

---

## 2. Approach

### 2.1 Scope decisions

| 決策 | 選擇 | 原因 |
|---|---|---|
| 範圍 | **build + push image only**（不含 deploy） | XS spec；deploy approval / lab+prod gate 留 follow-up；先把「push code → AR 自動產 image」這條最常用路徑接通 |
| Trigger | **單一 `push to main`**（Developer Connect GitHub App，無 approval） | 目前 single-author；tag → prod with approval / PR check 留 follow-up |
| 工具 | **Cloud Build native（cloudbuild.yaml）** | 與既有 GCP 部署棧對齊；不引入 GitHub Actions runner |
| Step 風格 | **原生 Cloud Build steps**（不直接 `bash scripts/gcp/03-build-push.sh`） | 三 step 用三種 builder（node / jdk / Cloud Build 自帶 push）— 沒有單一 builder 同時有 Java 25 + gcloud + docker CLI；自己 build 一份反而比 native steps 複雜 |
| Frontend build 位置 | **CI 跑**（從 `build.gradle.kts` 移除 `npmInstall` / `npmBuild` / `copyFrontend`） | 解開「`bootRun` 跑 npm 失敗」痛點；Gradle 純後端；npm 在 CI step 用 `node:22` 跑 |
| Image tag | **`<SHORT_SHA>` 單一 tag**（CI 不推 `:latest`） | rollback 用 SHA；deploy 顯式帶 SHA 比 `:latest` 安全；`:latest` 仍由本機 `03-build-push.sh` 維護給隨手 deploy 用 |
| Build cache | **不做** | XS 範圍；首次跑可能 5-8 分鐘可接受；cache 留 follow-up 觀察實際時間後再決定（GCS tarball / Gradle GCS plugin 兩條路備案） |

### 2.2 為何不直接 reuse `03-build-push.sh` 在 CI

| Path | Pros | Cons |
|---|---|---|
| **A: cloudbuild.yaml 直接 `bash scripts/gcp/03-build-push.sh`** | 一份邏輯 DRY；script + CI 同步 | 需要一個同時有 JDK 25 + gcloud + docker CLI 的 builder image — 目前無現成；自建一份 image 維護成本（Dockerfile + 推到 AR + 版本管理）反而比 native steps 多 |
| **B: cloudbuild.yaml 三 step 各用標準 builder（chosen）** | 每 step 用對應官方 image（`node:22` / `eclipse-temurin:25-jdk` / Cloud Build declarative `images:` 自動 push）；無自訂 builder | 兩處實作（local script 與 cloudbuild.yaml）；image / tag / repo 命名須對齊（透過 substitutions） |

**Drift mitigation**：兩處共用同一 image path 與 SHA tag 規則。`scripts/gcp/README.md` 補一節「local 與 CI 兩條路徑同 image」指向 cloudbuild.yaml 的 `_IMG_PATH` substitution 與 `03-build-push.sh` line 14-22 的同名變數。

### 2.3 Key design decisions

1. **Frontend npm chain 從 `build.gradle.kts` 移除** — 刪 `npmInstall` / `npmBuild` / `copyFrontend` 三 task；`processResources` 只留 `config/application-{prod,lab}.yaml` 拷貝。本機 dev 改 `cd frontend && npm run dev`；需要打包 image 時：CI 自動 / 本機跑修改後的 `03-build-push.sh`（已內建 `npm ci && npm run build && cp` 三步前置）。

2. **bootBuildImage 在 Cloud Build 跑得起來** — Cloud Build 預設每個 step 容器都掛 `/var/run/docker.sock`；用 `eclipse-temurin:25-jdk` 容器跑 `./gradlew bootBuildImage` 即可（Spring Boot 的 `docker-java` client 直接走 Unix socket，不需要 docker CLI 安裝在 image 內）。設 `DOCKER_HOST=unix:///var/run/docker.sock` 為保險。

3. **Image push 用 Cloud Build declarative `images:`** — 不寫 step 4 跑 `docker push`；改在 cloudbuild.yaml 頂層加 `images: [<path>:$SHORT_SHA]`，build 結束時 Cloud Build 自動把該 tag 從本地 daemon push 到 AR。Cloud Build runtime SA 已預設有 AR docker auth，不需 `gcloud auth configure-docker`。

4. **Substitutions 對齊 `.env.example`** — `_REGION` / `_GCP_PROJECT_ID`（trigger 上設）；`_AR_REPO=skillshub` / `_IMAGE_NAME=skillshub`（cloudbuild.yaml 內預設值，與 `03-build-push.sh:14-15` 同預設）；`$SHORT_SHA` 是 Cloud Build built-in，與 `git rev-parse --short HEAD` 在大多 commit 一致（皆 7 chars）。

5. **Cloud Build service account 最小權限** — 不用 default Compute SA（IAM 過寬）；建一個 `skillshub-cloudbuild@${PROJECT}.iam.gserviceaccount.com`，授權：
   - `roles/artifactregistry.writer` — push image
   - `roles/logging.logWriter` — Cloud Build log 寫入

6. **GitHub trigger 用 Developer Connect (2nd gen)** — repo 透過 Cloud Console → Developer Connect 一次性 OAuth 連線（產生 `gitRepositoryLink`），trigger CLI 帶 `--branch-pattern='^main$'` + `--build-config=cloudbuild.yaml`。比 1st gen Cloud Source Repos mirroring 路徑乾淨。

7. **`03-build-push.sh` 同步調整** — Gradle 不再幫忙跑 npm；script 自己 `npm ci && npm run build && cp -r frontend/dist/. backend/src/main/resources/static/` 後再 `bootBuildImage`。本機跑兩條路徑（local script vs CI）行為一致。

8. **Out of scope（明確列）** — 自動 deploy 到 Cloud Run、tag → prod approval gate、PR check trigger、build cache、SBOM publish（CycloneDX 已 wired 但尚未上 CI）、Native Image build（GraalVM；不在 CI 跑）、test gate（`./gradlew test` 在 CI 跑與否）。

### 2.4 Challenges Considered

1. **Java 25 + Gradle 9.4 builder image** — `eclipse-temurin:25-jdk` from Docker Hub 已有 Java 25；Gradle 用 wrapper（`./gradlew`）下載 9.4.1，不依賴 image 內 Gradle 版本。
2. **Docker socket access** — Cloud Build 預設每 step 都掛 `/var/run/docker.sock`，無需手動 `volumes:` 設定（Cloud Build docs 2025）。
3. **Step 之間檔案共享** — Cloud Build 把 `/workspace` 在 step 之間共享；step 1 寫 `frontend/dist/`、step 2 讀並複製到 `backend/src/main/resources/static/`、step 3 從那裡讀進 jar，皆走 `/workspace`。
4. **跑兩次的副作用** — Cloud Build 對同一 commit 重 trigger → 推同 SHA tag（idempotent，AR 接受 overwrite，digest 通常一致）。
5. **Trigger SA 不重用 `skillshub-runtime`** — 後者 IAM 含 datastore / aiplatform / secretmanager 等 runtime 權限，與 build 無關；新建 `skillshub-cloudbuild` 隔離。
6. **如果 frontend build 失敗** — Step 1 fail，後續 step skip，整 build 標 FAIL；image 不會 push。Console 看 step 1 stderr 即可定位。
7. **`HELP.md` 殘留在 `backend/src/main/resources/static/`** — 目前該檔（Gradle 拷頻產出的 leftover）對 image 無影響但語義上應清掉；step 2 用 `cp -r dist/.` 不會清舊檔，必要時改 `rm -rf static/* && cp` 即可（已寫進 §4.1）。

### 2.5 Research Citations

- [Cloud Build — Build configuration file schema](https://docs.cloud.google.com/build/docs/build-config-file-schema) — `steps` / `images` / `substitutions` / `options.machineType` / `automapSubstitutions` / `serviceAccount` 完整 key set；`automapSubstitutions: true` 是 2024+ 推薦寫法
- [Cloud Build — Build repos from GitHub (Developer Connect)](https://docs.cloud.google.com/build/docs/automating-builds/github/build-repos-from-github) — 2nd gen GitHub App OAuth + `gitRepositoryLink` + `--branch-pattern` trigger
- [Cloud Build — Build and containerize Java](https://docs.cloud.google.com/build/docs/building/build-containerize-java) — 推薦用 Gradle wrapper（`./gradlew`），避開 `gcr.io/cloud-builders/gradle` 版本落後
- [Spring Boot Cloud Native Buildpacks](https://docs.spring.io/spring-boot/reference/packaging/container-images/cloud-native-buildpacks.html) — `bootBuildImage --imageName=...` + Docker daemon 互動 + `DOCKER_HOST` 行為
- [Cloud Build — `images:` declarative push](https://docs.cloud.google.com/build/docs/build-config-file-schema#images) — 自動把 step 結束時 local Docker daemon 的指定 image push 到 AR
- [Artifact Registry integration with Cloud Run](https://docs.cloud.google.com/artifact-registry/docs/integrate-cloud-run) — image path 格式 `<region>-docker.pkg.dev/<project>/<repo>/<image>:<tag>`（與 `03-build-push.sh` 一致）

無 Hypothesis-grade 設計決策；**POC 不需要**——研究階段已對每條 step 的 builder image / Docker socket 行為驗證過。

---

## 3. SBE Acceptance Criteria

> 驗證指令：建 trigger 後 `git push origin main`，看 Cloud Build console 跑完。屬於 infra 設定 + manual smoke，不走 `./gradlew test`。`bash -n cloudbuild.yaml` 不適用（YAML 不是 bash）；改用 `gcloud builds submit --config=cloudbuild.yaml --no-source --substitutions=_GCP_PROJECT_ID=$PROJECT,_REGION=$REGION` dry-run（會實際 schedule 但 step 因無 source 立即失敗，仍可驗 YAML schema）。

```gherkin
Scenario: AC-1 — cloudbuild.yaml schema 通過 Cloud Build 驗證
  Given clone repo
  When 執行 gcloud builds submit --config=cloudbuild.yaml --no-source \
        --substitutions=_GCP_PROJECT_ID=$GCP_PROJECT_ID,_REGION=$GCP_REGION
  Then 不出 "Failed to parse YAML" / "unknown field" 等 schema error
  And Cloud Build CLI 接受 substitutions 並 schedule build（步驟跑時因無 source 失敗屬預期）

Scenario: AC-2 — Gradle 移除 npm chain 後 bootRun 純後端啟動
  Given backend/build.gradle.kts 不含 npmInstall / npmBuild / copyFrontend 三個 task
  Given frontend 有 TS 編譯錯誤的 test 檔（如目前狀態）
  When 執行 cd backend && ./gradlew bootRun
  Then Gradle 不執行 :npmInstall / :npmBuild / :copyFrontend（task 不存在）
  And Spring Boot 啟動成功（log 出現 "Started SkillshubApplication"）
  And frontend TS 錯誤不影響 backend 啟動

Scenario: AC-3 — push 到 main 觸發 Cloud Build
  Given Cloud Build trigger `skillshub-main-push` 已建立（branch=^main$, config=cloudbuild.yaml, SA=skillshub-cloudbuild）
  Given 開發者把 trivial commit push 到 origin/main
  When Cloud Build 自動觸發
  Then console.cloud.google.com/cloud-build/builds 看到 build 進入 RUNNING（trigger source = skillshub-main-push）

Scenario: AC-4 — Build pipeline 三 step + declarative push 全 SUCCESS
  Given AC-3 觸發的 build
  When 等 build 跑完（hard cap 25 分鐘）
  Then Step 1 "frontend-build"  SUCCESS（node:22；npm ci + npm run build；產出 frontend/dist/）
  And Step 2 "copy-static"      SUCCESS（alpine；cp -r 到 backend/src/main/resources/static/）
  And Step 3 "boot-build-image" SUCCESS（eclipse-temurin:25-jdk；./gradlew bootBuildImage 寫進 local Docker daemon）
  And Cloud Build declarative push（images: 段落）SUCCESS — image 出現在 AR
  And 整 build 標 SUCCESS

Scenario: AC-5 — Artifact Registry 收到對應 SHA 的 image
  Given AC-4 完成；commit SHA 短碼 = abc1234
  When gcloud artifacts docker images list \
        $_REGION-docker.pkg.dev/$_GCP_PROJECT_ID/skillshub/skillshub --filter="tags:abc1234"
  Then 輸出含一個 image with tag `abc1234`
  And digest 與 `gcloud artifacts docker images describe` 一致（可被 04-deploy.sh 透過 TAG=abc1234 引用）

Scenario: AC-6 — 既有 03-build-push.sh 仍可本機跑（自帶前端 build）
  Given Gradle 已移除 npm chain；03-build-push.sh 已加上 npm ci + npm run build + cp 三行前置
  When 開發者跑 source scripts/gcp/.env && ./scripts/gcp/03-build-push.sh
  Then script exit 0
  And AR 多一個 image（與 CI 同 path、同 image name、相應 SHA + :latest 雙 tag — 本機保留 :latest 行為）
  And 與 CI 推上去的 image 等價（以同 SHA 比 digest，因 frontend/dist 內容應一致）
```

---

## 4. Interface Design

### 4.1 `cloudbuild.yaml`（repo root，新檔）

```yaml
# Cloud Build pipeline — build frontend + backend image, push to Artifact Registry.
# Deploy 到 Cloud Run 不在本 pipeline 內（手動跑 scripts/gcp/04-deploy.sh）。
#
# Trigger setup：見 scripts/gcp/README.md "CI Cloud Build trigger" 段。

substitutions:
  _REGION: asia-east1                # trigger 上覆蓋；預設僅供文件
  _GCP_PROJECT_ID: my-skillshub-prod # trigger 上覆蓋
  _AR_REPO: skillshub                # 與 03-build-push.sh:14 AR_REPO_NAME 同預設
  _IMAGE_NAME: skillshub             # 與 03-build-push.sh:15 IMAGE_NAME 同預設
  _IMG_PATH: ${_REGION}-docker.pkg.dev/${_GCP_PROJECT_ID}/${_AR_REPO}/${_IMAGE_NAME}

options:
  automapSubstitutions: true   # 把 substitutions 自動注入 step env
  machineType: E2_HIGHCPU_8    # 8 vCPU；npm + Gradle 同時最舒適
  logging: CLOUD_LOGGING_ONLY  # 不要求 GCS log bucket（Cloud Build SA 不需要 storage role）

timeout: 1500s   # 25 分鐘 hard cap

steps:
  # 1. Frontend build
  - id: frontend-build
    name: node:22
    dir: frontend
    entrypoint: bash
    args:
      - -ceu
      - |
        npm ci
        npm run build

  # 2. Copy frontend dist into backend static dir
  - id: copy-static
    name: alpine
    waitFor: [frontend-build]
    entrypoint: sh
    args:
      - -ceu
      - |
        rm -rf backend/src/main/resources/static
        mkdir -p backend/src/main/resources/static
        cp -r frontend/dist/. backend/src/main/resources/static/

  # 3. Backend OCI image (Paketo bootBuildImage → local Docker daemon)
  - id: boot-build-image
    name: eclipse-temurin:25-jdk
    waitFor: [copy-static]
    dir: backend
    entrypoint: bash
    args:
      - -ceu
      - |
        ./gradlew bootBuildImage --imageName=${_IMG_PATH}:${SHORT_SHA}
    env:
      - DOCKER_HOST=unix:///var/run/docker.sock

# Cloud Build 自動把以下 image 從 local daemon push 到 AR
images:
  - ${_IMG_PATH}:${SHORT_SHA}

tags: [skillshub, ci, build-push]
```

### 4.2 `backend/build.gradle.kts` 變更

**刪除** lines 103-127（`val frontendDir` / `npmInstall` / `npmBuild` / `copyFrontend` 三 task 與相關宣告）。

**修改** lines 129-137（`processResources` 區塊）— 移除 `dependsOn(copyFrontend)`，保留 `from(projectDir.resolve("config")) { ... }`：

```kotlin
tasks.named<Copy>("processResources") {
    // 把 backend/config/application-{prod,lab}.yaml 拷進 image classpath（同 S013）。
    // 前端 dist 由 CI cloudbuild.yaml step 2（或本機 03-build-push.sh）拷入
    // src/main/resources/static/，不再走 Gradle —— 本機 bootRun 純後端，
    // frontend hot reload 用 `cd frontend && npm run dev`。
    from(projectDir.resolve("config")) {
        include("application-prod.yaml", "application-lab.yaml")
    }
}
```

### 4.3 `scripts/gcp/03-build-push.sh` 變更（接手 frontend build）

在 line 24（`echo "▸ Configure docker auth ..."`）之前插入：

```bash
echo "▸ Build frontend (npm ci + npm run build)"
( cd frontend && npm ci && npm run build )

echo "▸ Copy frontend/dist → backend/src/main/resources/static"
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/. backend/src/main/resources/static/
```

其餘維持原狀（`bootBuildImage` + 雙 tag push 不變）。

### 4.4 Cloud Build trigger 一次性 setup

```bash
# 預設 GCP_PROJECT_ID / GCP_REGION 已 export（同 .env）

# 1. 建 Cloud Build runtime SA
gcloud iam service-accounts create skillshub-cloudbuild \
  --display-name="Skills Hub Cloud Build runtime" \
  --project=$GCP_PROJECT_ID

CB_SA="skillshub-cloudbuild@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

gcloud projects add-iam-policy-binding $GCP_PROJECT_ID \
  --member="serviceAccount:${CB_SA}" \
  --role="roles/artifactregistry.writer"
gcloud projects add-iam-policy-binding $GCP_PROJECT_ID \
  --member="serviceAccount:${CB_SA}" \
  --role="roles/logging.logWriter"

# 2. Developer Connect GitHub App 連線（Cloud Console UI 一次性 OAuth）
#    產出：projects/$PROJECT/locations/$REGION/connections/github/gitRepositoryLinks/skills-hub

# 3. 建 trigger
gcloud builds triggers create developer-connect \
  --name=skillshub-main-push \
  --git-repository-link=projects/$GCP_PROJECT_ID/locations/$GCP_REGION/connections/github/gitRepositoryLinks/skills-hub \
  --branch-pattern='^main$' \
  --build-config=cloudbuild.yaml \
  --substitutions=_REGION=$GCP_REGION,_GCP_PROJECT_ID=$GCP_PROJECT_ID \
  --service-account=projects/$GCP_PROJECT_ID/serviceAccounts/$CB_SA \
  --region=$GCP_REGION
```

---

## 5. File Plan

| File | Action | Description |
|---|---|---|
| `cloudbuild.yaml` | new | Repo root；3 step pipeline + declarative `images:` push。Per §4.1。 |
| `backend/build.gradle.kts` | modify | 刪 `npmInstall` / `npmBuild` / `copyFrontend` 三 task；簡化 `processResources` 只保留 `config/` yaml 拷貝。Per §4.2。 |
| `scripts/gcp/03-build-push.sh` | modify | Line 24 之前加 npm ci + npm run build + cp 三步。Per §4.3。 |
| `scripts/gcp/README.md` | modify | 加一節「CI Cloud Build trigger（自動 build + push）」貼 §4.4 setup 指令；說明本機 `03-build-push.sh` 與 CI 兩條路徑同 image / 同 SHA tag、deploy 仍手動。 |
| `scripts/gcp/DEPLOYMENT.md` | modify | build/push 段補「or wait for Cloud Build to push automatically」；deploy 段保持手動跑 `04-deploy.sh`。 |
| `docs/grimo/architecture.md` | modify | Lines 415-426 "Build Integration" 段更新——從 `./gradlew build → npm install → ...` 改為兩條路徑（本機 dev：`bootRun` + `npm run dev` 並行；CI：cloudbuild.yaml 串接），Gradle 不再 invoke npm。 |

無新增 source code（純 infra + config）。

無 SQL migration。

無 schema / API contract / Glossary 變動。

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

**POC: not required (formal)** — spec §2.5 已標明 research 階段對每條 step 的 builder image / Docker socket 行為驗證過。**但**第一次實際 Cloud Build run（T02）同時也是 spec §2.4 challenge #2 的 hypothesis live validation（`bootBuildImage` 在 `eclipse-temurin:25-jdk` Cloud Build container + auto-mounted Docker socket 真的能跑）。完整 GCP-side POC 需要 bootstrap S013 整套 infra，cost = T02 本身；故將 T02 首次 run 視為 production POC。三次 iteration 仍失敗 → escalate `/planning-spec` 重新評估 design hypothesis。

### Tasks

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Local atomic edit — cloudbuild.yaml + Gradle 解耦 + 03-build-push.sh 自帶 frontend build + 三份 docs sync | AC-1, AC-2, AC-6 | ✅ DONE (commit 002a111 + a0d90e6) |
| T02 | GCP-side trigger setup（Cloud Build SA + Developer Connect link + trigger）+ push smoke 驗第一次自動 build | AC-3, AC-4, AC-5 | ⏸ BLOCKED — 等 user 執行 §4.4 GCP Console SA + Developer Connect + trigger 建立步驟 |

**Execution order:** T01 → T02（T02 depends on T01 — cloudbuild.yaml 須先 commit 進 repo，trigger 才有 build-config 可讀）

### AC Coverage

| AC | T01 | T02 |
|----|-----|-----|
| AC-1 cloudbuild.yaml schema 通過 | ✓ (gcloud builds submit --no-source dry-run) | — |
| AC-2 bootRun 純後端啟動 | ✓ (./gradlew bootRun manual) | — |
| AC-3 push 觸發 build | — | ✓ (Cloud Build console RUNNING) |
| AC-4 三 step + images: push 全 SUCCESS | — | ✓ (gcloud builds describe SUCCESS) |
| AC-5 AR 收到 SHA image | — | ✓ (gcloud artifacts docker images list) |
| AC-6 03-build-push.sh 本機仍可跑 | ✓ (manual run + AR 驗證 SHA + :latest 雙 tag) | — |

### Notes

- T01 屬於本機 atomic edit，可在無 GCP credentials 環境下完成；AC-1 / AC-6 verification 步驟若 user 環境暫無 gcloud auth + Docker daemon，可標 deferred-manual 連 T02 一起跑。
- T02 為 manual GCP 任務，不走 `./gradlew test`；驗證證據（build log / AR image digest）合併寫入 spec §7。

---

## 8. ProcessAot baked profile 機制（2026-05-05 補述）

> Native image enablement 工作（commit `3b48bc2` `b82eeb3` `a0d90e6` `e91dc91`）的核心 design rationale。`build.gradle.kts` ProcessAot block 跟 `cloudbuild.yaml` `-Pspring.profiles.active` flag 都引用本節。

### 8.1 為什麼 AOT 階段就要列齊 profile

Spring Boot 4 AOT processor 透過 `ApplicationContextInitializer` hardcode `addActiveProfile(...)` 把 build-time profile **baked 進 native binary**（per spring-boot Issue [#41562](https://github.com/spring-projects/spring-boot/issues/41562) / [#48408](https://github.com/spring-projects/spring-boot/issues/48408)）。Native runtime `SPRING_PROFILES_ACTIVE` 只能「**加**」profile 不能「**移除**」baked 的 — runtime 設定無法 swap 出 build-time 已 freeze 的 `@Profile` bean definitions。

**含意**：AOT processing 階段就要列齊 native runtime 想用的 profile。沒列到的 profile 在 native runtime 不存在對應的 bean definitions（即使 runtime 設了 SPRING_PROFILES_ACTIVE 也救不回）。

### 8.2 為什麼用 Gradle property 而非 hardcode

ProcessAot task 預設沒設 active profile → `AotStubConfig`（`@Profile("aot")`）不啟用 → DataSource autoconfig 試 eager init 但 AOT 跳過 `@ConfigurationProperties` binding → 無 driver class 炸掉。

ProcessAot block 必須 inject `--spring.profiles.active=...` args 給 forked JVM。三個方案 trade-off：

| 方案 | 優 | 劣 |
|---|---|---|
| Hardcode `args("--spring.profiles.active=aot,local")` | 最簡單 | 不彈性，CI 不同環境（lab/prod）要改 build.gradle.kts |
| Env var `SPRING_PROFILES_ACTIVE=...` ./gradlew | build.gradle.kts 全乾淨 | 不算「gradle 參數」，跟 runtime env var 同名易混 |
| **`-Pspring.profiles.active=...` Gradle property（採用）** | gradle 風格 + 跨層命名一致 + 預設值寫在 build.gradle.kts | 須 build.gradle.kts 留 minimal 2-line block 讀 property |

### 8.3 為什麼命名對齊 `spring.profiles.active`

跨層命名一致 = 零認知負擔：

| 層 | Key |
|---|---|
| Spring yaml | `spring.profiles.active` |
| Runtime env var | `SPRING_PROFILES_ACTIVE` |
| Spring CLI args | `--spring.profiles.active=...` |
| Gradle property | `-Pspring.profiles.active=...` |

不管在哪一層看到都是同個名字。Gradle property name 支援 dotted form（`-Pspring.profiles.active=...`）— 直接複用 Spring 標準名，避免自創 `aotProfiles` 這類 key 造成跨層斷裂。

### 8.4 為什麼 bootBuildImage 一定跑 native

Paketo builder `noble-java-tiny`（Spring Boot 4 預設）的第一個 `[[order]]` group 是 `paketo-buildpacks/java-native-image` meta-buildpack，內含 `paketo-buildpacks/native-image` buildpack（**non-optional / required**，per [builder.toml](https://github.com/paketo-buildpacks/builder-noble-java-tiny/blob/main/builder.toml)）。

CNB lifecycle detect phase 依序試 order group，第一個 pass 的就被選中。Spring AOT 產生 `META-INF/native-image/` metadata（由 `org.graalvm.buildtools.native` plugin 貢獻）→ `spring-boot` buildpack 寫 plan entry `native-image: true` → `java-native-image` chain 整條 pass → `native-image` buildpack 執行。

**含意**：`BP_NATIVE_IMAGE=true` env var **無需顯式設**。`BP_NATIVE_IMAGE` 預設無值（per [native-image detect.go](https://github.com/paketo-buildpacks/native-image/blob/main/native/detect.go)），但保留 graalvm plugin = 強制 native build。要切回 JVM image 模式需顯式設 `BP_NATIVE_IMAGE=false` 或換 builder。

### 8.5 為什麼 AotStubConfig 用 System.getenv 而非 Spring Environment

Spring Boot 4 AOT processor 對 eager bean 跳過 `@ConfigurationProperties` binding（連續 4 次 build 驗證 yaml/system property/CLI args 都失效）。`AotStubConfig.dataSource()` 必須用純 JVM API `System.getenv(...)` 直連 process env vars，繞開 framework phase 限制。

副效益：AOT processing 階段（build time）env vars 沒設 → 用 stub URL（HikariCP lazy connect 不真連 DB）；native runtime 階段 BeanInstanceSupplier 重新 invoke 同方法 → env vars 有值 → 用真實連線資訊。同個 bean 跨 build/runtime 共用，runtime 由 env vars 切換真實值。

### 8.6 baked profile vs runtime profile 範例

當前 setup（`-Pspring.profiles.active=aot,local`）：
- **Build time**：baked `aot,local` → `AotStubConfig` + `application-local.yaml` modulith excludes 都 freeze 進 native binary
- **Cloud Run runtime**：設 `SPRING_PROFILES_ACTIVE=gcp,prod` → 總 active = `aot, local, gcp, prod`
  - `aot` profile 觸發 `AotStubConfig` 但 `dataSource()` 從 env var 讀真實 DB url（非 stub）
  - `gcp` profile 啟用 GCP autoconfig (secretmanager 等)
  - `prod` profile 提供行為（INFO log / 限縮 actuator）

未來想為 lab/prod 分別 baked 不同 native binary：CI 改 `-Pspring.profiles.active=aot,gcp,lab` 或 `aot,gcp,prod`，build 出兩種 image 分別 deploy 對應環境。

### 8.7 References

- Spring Boot AOT how-to: <https://docs.spring.io/spring-boot/how-to/aot.html>
- spring-boot Issue #41562 — AOT addActiveProfile baked into binary
- spring-boot Issue #48408 — runtime SPRING_PROFILES_ACTIVE cannot remove baked
- Paketo builder-noble-java-tiny: <https://github.com/paketo-buildpacks/builder-noble-java-tiny>
- Paketo native-image buildpack: <https://github.com/paketo-buildpacks/native-image>
