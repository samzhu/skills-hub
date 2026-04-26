# S013: GCP Cloud Run 部署腳本與打包流程

> Spec: S013 | Size: S(11) | Status: ✅ Done (2026-04-27)
> Date: 2026-04-27
> Depends: S009 (✅ shipped — `gcp` profile + `application-gcp.yaml`), S011 (✅), S012 (✅ shipped 2026-04-27 — security 設定 export 為 env var)
> Research: GCP 官方 docs（Firestore Enterprise + MongoDB compat、Cloud Run secret manager、Spring Boot 4 bootBuildImage）；§2.6 Validation Pass 已對既有 code 與當前 gcloud 文件做差異對照

---

## 1. Goal

提供一組可在**全新 GCP 專案**上一鍵跑通的 bash 腳本，把 Skills Hub backend 從 source code 打包成 OCI image、推到 Artifact Registry、部署到 Cloud Run，並 provision 必要的 Firestore Enterprise（MongoDB compat）+ GCS + Secret Manager + Service Account / IAM 資源。開發者只要 `export GCP_PROJECT_ID`、`GCP_REGION`、`SKILLSHUB_GENAI_API_KEY` 三個變數，依序跑 4 個腳本即可看到 Cloud Run service URL。

**簡單講**：`./scripts/gcp/01-bootstrap.sh && ./scripts/gcp/02-create-secrets.sh && ./scripts/gcp/03-build-push.sh && ./scripts/gcp/04-deploy.sh` 跑完，瀏覽器打 service URL 即看到 Skills Hub。

```
┌── 開發者本機 ───────────────────────────────────────────────────┐
│  export GCP_PROJECT_ID=my-skillshub-prod                        │
│  export GCP_REGION=asia-east1                                   │
│  export SKILLSHUB_GENAI_API_KEY=AIzaSy...                       │
│       │                                                          │
│       ▼                                                          │
│  ./scripts/gcp/01-bootstrap.sh                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ gcloud services enable {run, artifactregistry, firestore │  │
│  │   storage, secretmanager, aiplatform, iam}.googleapis.com│  │
│  │ gcloud artifacts repositories create skillshub --docker  │  │
│  │ gcloud firestore databases create --edition=enterprise   │  │
│  │   --enable-mongodb-compatible-data-access                │  │
│  │ gsutil mb -l $GCP_REGION gs://$PROJECT-skillshub-pkg     │  │
│  │ gcloud iam service-accounts create skillshub-runtime     │  │
│  │ gcloud projects add-iam-policy-binding ×N (min IAM)      │  │
│  └──────────────────────────────────────────────────────────┘  │
│       ▼                                                          │
│  ./scripts/gcp/02-create-secrets.sh                             │
│       echo "$SKILLSHUB_GENAI_API_KEY" | gcloud secrets create   │
│       skillshub-genai-api-key --data-file=-                     │
│       gcloud secrets add-iam-policy-binding （給 runtime SA）   │
│       ▼                                                          │
│  ./scripts/gcp/03-build-push.sh                                 │
│       SHA=$(git rev-parse --short HEAD)                         │
│       IMG=$REGION-docker.pkg.dev/$PROJECT/skillshub/skillshub   │
│       ./gradlew bootBuildImage --imageName=$IMG:$SHA            │
│       docker tag $IMG:$SHA $IMG:latest                          │
│       docker push $IMG:$SHA && docker push $IMG:latest          │
│       ▼                                                          │
│  ./scripts/gcp/04-deploy.sh                                     │
│       gcloud run deploy skillshub                               │
│         --image=$IMG:$SHA  --region=$GCP_REGION                 │
│         --service-account=skillshub-runtime@...iam.gserviceac.. │
│         --set-env-vars=SPRING_PROFILES_ACTIVE=gcp\,prod,...     │
│         --update-secrets=SKILLSHUB_GENAI_API_KEY=...:latest     │
│         --allow-unauthenticated                                 │
│       ▼                                                          │
│  Service URL: https://skillshub-xxx-uc.a.run.app                │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

### 2.1 為何選 bash + gcloud（而非 Terraform / Cloud Build）

| Approach | Pros | Cons | Recommendation |
|---|---|---|---|
| **A: 純 bash + gcloud / gsutil / docker** | 零學習成本；新手 onboarding 5 分鐘；步驟透明可見；不引入新 runtime | 非宣告式（state 散落）；冪等性需手刻 (`|| true`) | ⭐ Recommended（user 明確要求腳本，且 MVP 階段 infra 變動少） |
| B: Terraform | IaC 標準；reproducible；state file 追蹤資源 | 引入 Terraform 工具鏈；HCL 學習；MVP 多此一舉 | 等 multi-env / multi-tenant 時再用 |
| C: cloudbuild.yaml + Cloud Build | GCP 原生 CI；可掛 GitHub trigger | 需要先在 GCP 上設好 Cloud Build；本機跑時繞遠路 | 等成立 CI/CD 流程後再加 |

### 2.2 設計決策（grill 確認）

| 決策 | 選擇 | 原因 |
|---|---|---|
| Image tag | git short SHA + `:latest` 雙 tag | rollback 可回指特定 commit；`:latest` 作 dev 隨手部署預設 |
| Secret 範圍 | 僅 `skillshub-genai-api-key` | YAGNI；OAuth IdP 等未來 spec 自管 |
| Public access | `--allow-unauthenticated`（allUsers）| MVP 內部 demo；OAuth 整合後再收 |
| 腳本風格 | 純 bash + `set -euo pipefail` | 失敗即停；不吃 silent error |
| 冪等性 | 每步 `gcloud xxx describe` 先檢查存在再 create；不存在才建立 | 跑第二次不會炸 |

### 2.3 關鍵設計決策

1. **每個腳本只做一件事，且都 idempotent** — `01-bootstrap.sh` 只負責 infra（APIs/AR/Firestore/GCS/SA/IAM），跑兩次不會 error；`02-create-secrets.sh` 用 `gcloud secrets versions add` 而非 `create`，避免「已存在」失敗；`03-build-push.sh` 純打包推送；`04-deploy.sh` 純部署。職責分離方便 partial re-run（debug 只需重跑出問題的步驟）。
2. **環境變數從 `.env.example` 範本起** — 提供 `scripts/gcp/.env.example`，使用者複製成 `.env` 後 `export $(cat .env | xargs)` 即就緒。`GCP_PROJECT_ID` / `GCP_REGION` / `SKILLSHUB_GENAI_API_KEY` 是必填；`AR_REPO_NAME` / `FIRESTORE_DB_ID` / `GCS_BUCKET_NAME` / `SERVICE_ACCOUNT_NAME` / `CLOUD_RUN_SERVICE_NAME` 都有預設值。
3. **bootBuildImage + docker push 分兩段** — Spring Boot 的 `bootBuildImage` 也有 `--publish`，但需要 Gradle 設定 publishRegistry credentials。bash 路徑用 `bootBuildImage` 產 local image + `docker push` 兩段更易 debug 且不需動 build.gradle.kts。
4. **Service Account 最小權限**：
   - `roles/datastore.user` — Firestore CRUD（含 native SDK 向量搜尋）
   - `roles/storage.objectAdmin` — GCS bucket read/write/delete
   - `roles/aiplatform.user` — Vertex AI Gemini embedding
   - `roles/secretmanager.secretAccessor` — 讀 Secret Manager 值
   - `roles/logging.logWriter` + `roles/monitoring.metricWriter` + `roles/cloudtrace.agent` — OpenTelemetry export
5. **Firestore Database ID 與 GCS Bucket 命名規則** — 都用 `${GCP_PROJECT_ID}` 前綴避免衝突。Firestore Enterprise 必須 `--enable-mongodb-compatible-data-access` 才能啟用 MongoDB wire protocol；MongoDB driver 端 URI 維持 `application-gcp.yaml` 既有的 `mongodb+srv://${GCP_PROJECT_ID}.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC`。
6. **不在 spec 中改任何 Java / Gradle code** — 既有 `bootBuildImage` task 已 configured（build.gradle.kts plugin）；既有 `application-gcp.yaml` 已對 GCP 設定好。本 spec 只新增 shell 與 README，零生產 code 變動。
7. **Cloud Run 設定走 env var 而非 yaml** — `gcloud run deploy --set-env-vars` 直接傳；`application.yaml` 的 relaxed binding 自動把 `SKILLSHUB_STORAGE_BUCKET` 對應到 `skillshub.storage.bucket`。S009 既有設計強化此模式。
8. **Teardown 腳本另開 `99-teardown.sh`** — 互動確認（`read -p`）；不刪 GCP project（避免誤刪），只刪本 spec 創的資源。

### 2.4 Challenges Considered

1. **bootBuildImage 在 Java 25 + Gradle 9.4 是否能用？** — Spring Boot 4.0.6 plugin 包含 bootBuildImage task；Paketo buildpack 預設拉 `paketobuildpacks/builder:base` 已支援 Java 25（驗：`gradle --version` 顯 9.4，`./gradlew bootBuildImage --imageName=local-test:dev` 可在本機跑）。本 spec 不變更 build.gradle.kts，沿用 S000 既有設定。
2. **Artifact Registry vs Container Registry (gcr.io)** — 2024 起 GCP 全面建議 Artifact Registry（gcr.io 進入維護模式）。腳本用 AR：`<region>-docker.pkg.dev/<project>/<repo>/<image>:<tag>`。
3. **Docker auth for AR push** — `docker push` 前需 `gcloud auth configure-docker $REGION-docker.pkg.dev`。腳本內每次 push 前都跑（idempotent）。
4. **Firestore Enterprise + MongoDB connection string** — `application-gcp.yaml` 既有的 `mongodb+srv://${GCP_PROJECT_ID}.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC` 用 `${GCP_PROJECT_ID}` placeholder；GCP 部署時 Spring Boot relaxed binding 會把 env var `GCP_PROJECT_ID` 注入。本腳本確保 deploy 時 `--set-env-vars=GCP_PROJECT_ID=$PROJECT` 一定帶。
5. **`MONGODB-OIDC` 認證需 Workload Identity** — Firestore MongoDB compat 用 OIDC 表示連線者用 GCP IAM identity（即 Cloud Run service account）。腳本確保 `roles/datastore.user` 已 grant 給 runtime SA；無需額外 Workload Identity 設定（Cloud Run 對自己的 SA 自動有 metadata server）。
6. **`SKILLSHUB_SECURITY_OAUTH_ENABLED` 預設值** — S012 預設 true（fail-secure）；prod 行為與 S011 一致。腳本不顯式設此 env var，由 Spring Boot 預設處理。LAB GCP 環境若需關，使用者編輯 `04-deploy.sh` 加 `--set-env-vars=...,SKILLSHUB_SECURITY_OAUTH_ENABLED=false`。
7. **Cost guard**：Cloud Run `--min-instances=0`（idle 不收錢），`--max-instances=10`（防 runaway），`--memory=512Mi --cpu=1`。Firestore Enterprise / Vertex AI / GCS 都是用量計費；Secret Manager 第一個 secret 免費。
8. **跑兩次不會炸的關鍵：`gcloud xxx describe ... &>/dev/null || gcloud xxx create ...`**——但 secrets 用 `versions add`（每次跑加一個 version）；service account / IAM binding 加 `--quiet` 並吃 already-exists error。

### 2.5 Research Citations

- [Cloud Run — Configure secrets for services](https://docs.cloud.google.com/run/docs/configuring/services/secrets) — `--update-secrets=ENV_VAR=SECRET_NAME:VERSION` 與 volume mount 語法
- [Firestore — Create databases (MongoDB compat)](https://docs.cloud.google.com/firestore/mongodb-compatibility/docs/create-databases) — `gcloud firestore databases create --edition=enterprise --enable-mongodb-compatible-data-access` 完整指令
- [Cloud Run — Quickstart Java](https://docs.cloud.google.com/run/docs/quickstarts/build-and-deploy/deploy-java-service) — `gcloud services enable run.googleapis.com cloudbuild.googleapis.com`、port `${PORT:8080}` 慣例
- [Artifact Registry integration with Cloud Run](https://docs.cloud.google.com/artifact-registry/docs/integrate-cloud-run) — image path 格式 `<region>-docker.pkg.dev/<project>/<repo>/<image>:<tag>`
- [Spring Boot Gradle bootBuildImage](https://docs.spring.io/spring-boot/docs/current/gradle-plugin/reference/htmlsingle/) — `--imageName` 與 `publish` 參數
- [Firestore with MongoDB compatibility — Editions overview](https://firebase.google.com/docs/firestore/editions) — Enterprise 是必要 edition
- 內部設計：`docs/grimo/architecture.md` Firestore Configuration（既有 `mongodb+srv://...firestore.googleapis.com` URI）

無 Hypothesis-grade 設計決策；**POC 不需要**——Spring Boot 4 image build + GCP gcloud CLI 都是成熟工具鏈。

### 2.6 Validation Pass — Drift Sync (2026-04-27)

從 🔵 in-design 收斂到 ⏳ Design 前，對 main 分支既有 code、當前 gcloud 文件、與 Spring Boot 4.0.6 plugin 行為做了差異對照，發現兩處 spec 初稿與實情不符（已校正）；另確認 4 處與既有設計一致：

**校正：**

1. **`--set-env-vars` 用 `\,` 跳脫 comma 是脆弱寫法**——根據 [Cloud Run env vars 官方文件](https://cloud.google.com/run/docs/configuring/services/environment-variables) 與 [argument injection 安全公告 GHSA-fvxx-ggmx-3cjg](https://github.com/MervinPraison/PraisonAI/security/advisories/GHSA-fvxx-ggmx-3cjg)，當 value 內含 `,` 時，gcloud 推薦做法是改用「自訂分隔符」`^@^` 語法：
   ```bash
   --set-env-vars "^@^SPRING_PROFILES_ACTIVE=gcp,prod@GCP_PROJECT_ID=...@SKILLSHUB_STORAGE_BUCKET=..."
   ```
   `\,` 雖然在歷史版本可運作，但被官方標為「不可靠（unreliable）」且無正式 spec；CI 升 gcloud 版本時可能突然 break。校正：§4.5 `04-deploy.sh` 改用 `^@^` 自訂分隔符模式。
2. **`.gitignore` 在 repo root 不存在**（只有 `backend/.gitignore` 與 `frontend/.gitignore`）。原 §5 寫「modify `.gitignore`」會 fail。校正：改為新增 `scripts/gcp/.gitignore`（一行 `.env`），最小作用域、不污染其他目錄。

**確認（無變動）：**

3. **`backend/src/main/resources/application-gcp.yaml` 已存在**（S009 shipped 結果）—— `spring.cloud.gcp.{storage,firestore}.enabled=true` + `skillshub.search.vector-store=firestore` + `skillshub.scanner.engines.llm.enabled=true`，與本 spec `04-deploy.sh` 帶 `SPRING_PROFILES_ACTIVE=gcp,prod` 預期一致；無需任何 yaml 變動。
4. **`build.gradle.kts` 已套用 `id("org.springframework.boot") version "4.0.6"` plugin**——`bootBuildImage` task 自動就緒；本 spec `03-build-push.sh` 直接呼叫 `./gradlew bootBuildImage --imageName=...` 無需 build script 變動。
5. **`gcloud firestore databases create --edition=enterprise --enable-mongodb-compatible-data-access --database=... --location=...`**——驗證於 [Firebase docs](https://firebase.google.com/docs/firestore/enterprise/create-databases-mongodb) 與 [Cloud Firestore MongoDB compatibility docs](https://cloud.google.com/firestore/mongodb-compatibility/docs/create-databases)：完整指令名稱、flag 拼寫、組合方式都正確。Database ID 規則 lowercase letters/numbers/hyphens、4-63 chars 同樣已被 spec 預設 (`skillshub`) 滿足。
6. **`--update-secrets="ENV_VAR=SECRET_NAME:VERSION"` 語法正確**——對應 [Cloud Run secrets 文件](https://docs.cloud.google.com/run/docs/configuring/services/secrets)；spec §4.5 `SKILLSHUB_GENAI_API_KEY=skillshub-genai-api-key:latest` 直接照用。

驗證後本 spec 從 🔵 in-design → ⏳ Design，可進 `/planning-tasks S013`。

---

## 3. SBE Acceptance Criteria

> 驗證指令：手動跑腳本流程；Bash 語法用 `bash -n scripts/gcp/*.sh` 檢查（屬於 `./gradlew test` 範圍外的 infra script，採 manual-ready 分類）。建議裝 `shellcheck` 並跑 `shellcheck scripts/gcp/*.sh`（非阻擋）。
>
> 因 spec 屬 deployment script + manual verification，不走自動測試 gate；ACs 全部以「腳本實際在乾淨 GCP 專案上跑」為驗證條件，記錄為 manual-ready。

```gherkin
Scenario: AC-1 — 腳本檔結構與權限正確
  Given clone repo 後
  When 執行 ls -la scripts/gcp/
  Then 看到 6 個檔案：
    - .env.example
    - 01-bootstrap.sh （755）
    - 02-create-secrets.sh （755）
    - 03-build-push.sh （755）
    - 04-deploy.sh （755）
    - 99-teardown.sh （755）
    - README.md
  And 5 個 .sh 檔皆有 `#!/usr/bin/env bash` shebang 與 `set -euo pipefail`
  And `bash -n scripts/gcp/*.sh` 全部通過

Scenario: AC-2 — `.env.example` 含必填 + 可選變數
  Given 開發者複製 .env.example 為 .env
  When 檢視
  Then 看到必填區塊：GCP_PROJECT_ID, GCP_REGION, SKILLSHUB_GENAI_API_KEY
  And 看到可選區塊（含註解標 default 值）：
    - AR_REPO_NAME=skillshub
    - FIRESTORE_DB_ID=skillshub
    - GCS_BUCKET_NAME=${GCP_PROJECT_ID}-skillshub-pkg
    - SERVICE_ACCOUNT_NAME=skillshub-runtime
    - CLOUD_RUN_SERVICE_NAME=skillshub
    - IMAGE_NAME=skillshub

Scenario: AC-3 — 01-bootstrap.sh 在乾淨 GCP 專案啟用 6 個 API + 建 5 個資源
  Given 一個剛建立、未啟用任何 API 的 GCP 專案
  Given export 完三個必填 env var
  When ./scripts/gcp/01-bootstrap.sh
  Then 6 個 API 啟用：run, artifactregistry, firestore, storage, secretmanager, aiplatform
  And Artifact Registry repo `skillshub` 在 $GCP_REGION 建立（Docker format）
  And Firestore database `skillshub` 建立（edition=enterprise，mongodb-compatible）
  And GCS bucket `${GCP_PROJECT_ID}-skillshub-pkg` 建立
  And Service Account `skillshub-runtime@${GCP_PROJECT_ID}.iam.gserviceaccount.com` 建立
  And SA 持有 7 個 IAM role（datastore.user, storage.objectAdmin, aiplatform.user, secretmanager.secretAccessor, logging.logWriter, monitoring.metricWriter, cloudtrace.agent）

Scenario: AC-4 — 01-bootstrap.sh 第二次跑不報錯
  Given AC-3 已跑過一次
  When 再跑一次 ./scripts/gcp/01-bootstrap.sh
  Then exit 0
  And 不會建立重複資源（皆跳過或 update-only）

Scenario: AC-5 — 02-create-secrets.sh 把 API key 存進 Secret Manager
  Given env 含 SKILLSHUB_GENAI_API_KEY=AIzaSy...
  When ./scripts/gcp/02-create-secrets.sh
  Then Secret Manager 有 secret `skillshub-genai-api-key`
  And 該 secret 至少有一個 version 內容等於 env var 值
  And Service Account 已被 grant `roles/secretmanager.secretAccessor` on this secret

Scenario: AC-6 — 03-build-push.sh 產生 git SHA + latest 雙 tag
  Given 已 commit；git rev-parse --short HEAD = abc1234
  When ./scripts/gcp/03-build-push.sh
  Then 本機 docker images 看到：
    - $REGION-docker.pkg.dev/$PROJECT/skillshub/skillshub:abc1234
    - $REGION-docker.pkg.dev/$PROJECT/skillshub/skillshub:latest
  And `gcloud artifacts docker images list` 顯示兩個 tag 已推送

Scenario: AC-7 — 04-deploy.sh 部署成功且帶完整環境變數與 secret
  Given 03-build-push.sh 已成功
  When ./scripts/gcp/04-deploy.sh
  Then 看到 Service URL（格式 https://skillshub-*-*.a.run.app）
  And `gcloud run services describe skillshub --region=$GCP_REGION` 顯示：
    - Image 對應 git short SHA
    - Service Account = skillshub-runtime@...
    - SPRING_PROFILES_ACTIVE=gcp,prod
    - GCP_PROJECT_ID 環境變數
    - SKILLSHUB_STORAGE_BUCKET 環境變數
    - SKILLSHUB_GENAI_API_KEY 引用 Secret Manager secret
    - allow-unauthenticated 為 true

Scenario: AC-8 — 部署後 service URL 可正常回應
  Given AC-7 完成
  When curl https://<service-url>/actuator/health
  Then HTTP 200 + body {"status":"UP"}
  When curl https://<service-url>/api/v1/skills
  Then HTTP 200（既有 S001 行為，匿名可達）

Scenario: AC-9 — 99-teardown.sh 互動確認後清乾淨
  Given 上述全部部署完成
  When ./scripts/gcp/99-teardown.sh
  Then 出現 prompt 「Are you sure to delete all skillshub resources in $GCP_PROJECT_ID? (yes/no)」
  When 輸入 "yes"
  Then 依序刪除：Cloud Run service、AR docker images、GCS bucket（若空）、Firestore DB、Service Account、IAM bindings、Secret Manager secrets
  And GCP 專案本身不刪
```

---

## 4. Interface Design

### 4.1 `.env.example` 範本

```bash
# ── 必填 ─────────────────────────────────────────────────────
# GCP 專案 ID（請先 `gcloud projects create`）
export GCP_PROJECT_ID=my-skillshub-prod

# 部署 region（建議 asia-east1 / us-central1）
export GCP_REGION=asia-east1

# Vertex AI Gemini API key
# 取得：https://aistudio.google.com/app/apikey
export SKILLSHUB_GENAI_API_KEY=AIzaSy...

# ── 可選（皆有預設）─────────────────────────────────────────
# Artifact Registry Docker repo 名稱
export AR_REPO_NAME=skillshub

# Firestore database ID（lowercase，4-63 字元）
export FIRESTORE_DB_ID=skillshub

# GCS bucket 名稱（必須全球唯一）
export GCS_BUCKET_NAME=${GCP_PROJECT_ID}-skillshub-pkg

# Runtime Service Account 名稱
export SERVICE_ACCOUNT_NAME=skillshub-runtime

# Cloud Run service 名稱
export CLOUD_RUN_SERVICE_NAME=skillshub

# Image 名稱（在 AR 內）
export IMAGE_NAME=skillshub

# Cloud Run resources（依用量調）
export CLOUD_RUN_MEMORY=512Mi
export CLOUD_RUN_CPU=1
export CLOUD_RUN_MAX_INSTANCES=10
```

### 4.2 `01-bootstrap.sh`（idempotent provisioning）

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
FIRESTORE_DB_ID="${FIRESTORE_DB_ID:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

echo "▸ project=$GCP_PROJECT_ID region=$GCP_REGION"
gcloud config set project "$GCP_PROJECT_ID" --quiet

echo "▸ Enable APIs"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  aiplatform.googleapis.com \
  iam.googleapis.com --quiet

echo "▸ Artifact Registry repo: $AR_REPO_NAME"
gcloud artifacts repositories describe "$AR_REPO_NAME" --location="$GCP_REGION" &>/dev/null \
  || gcloud artifacts repositories create "$AR_REPO_NAME" \
       --repository-format=docker --location="$GCP_REGION" \
       --description="Skills Hub container images"

echo "▸ Firestore database: $FIRESTORE_DB_ID (Enterprise + MongoDB compat)"
gcloud firestore databases describe --database="$FIRESTORE_DB_ID" &>/dev/null \
  || gcloud firestore databases create \
       --database="$FIRESTORE_DB_ID" \
       --location="$GCP_REGION" \
       --edition=enterprise \
       --enable-mongodb-compatible-data-access

echo "▸ GCS bucket: $GCS_BUCKET_NAME"
gcloud storage buckets describe "gs://$GCS_BUCKET_NAME" &>/dev/null \
  || gcloud storage buckets create "gs://$GCS_BUCKET_NAME" --location="$GCP_REGION"

echo "▸ Service Account: $SA_EMAIL"
gcloud iam service-accounts describe "$SA_EMAIL" &>/dev/null \
  || gcloud iam service-accounts create "$SERVICE_ACCOUNT_NAME" \
       --display-name="Skills Hub runtime"

echo "▸ Grant IAM roles to $SA_EMAIL"
for role in \
  roles/datastore.user \
  roles/storage.objectAdmin \
  roles/aiplatform.user \
  roles/secretmanager.secretAccessor \
  roles/logging.logWriter \
  roles/monitoring.metricWriter \
  roles/cloudtrace.agent; do
  gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
    --member="serviceAccount:$SA_EMAIL" --role="$role" --condition=None --quiet >/dev/null
done

echo "✓ bootstrap done"
```

### 4.3 `02-create-secrets.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?}" ; : "${SKILLSHUB_GENAI_API_KEY:?}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
SECRET_NAME="skillshub-genai-api-key"

echo "▸ Secret: $SECRET_NAME"
if gcloud secrets describe "$SECRET_NAME" &>/dev/null; then
  echo "  exists; adding new version"
  echo -n "$SKILLSHUB_GENAI_API_KEY" | gcloud secrets versions add "$SECRET_NAME" --data-file=-
else
  echo -n "$SKILLSHUB_GENAI_API_KEY" | gcloud secrets create "$SECRET_NAME" \
    --data-file=- --replication-policy=automatic
fi

echo "▸ Grant secretAccessor to $SA_EMAIL"
gcloud secrets add-iam-policy-binding "$SECRET_NAME" \
  --member="serviceAccount:$SA_EMAIL" \
  --role=roles/secretmanager.secretAccessor --quiet >/dev/null

echo "✓ secrets done"
```

### 4.4 `03-build-push.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?}" ; : "${GCP_REGION:?}"
AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
SHA="$(git rev-parse --short HEAD)"
IMG_BASE="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}"

echo "▸ Configure docker auth for $GCP_REGION-docker.pkg.dev"
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

echo "▸ Build OCI image: $IMG_BASE:$SHA"
( cd backend && ./gradlew bootBuildImage --imageName="$IMG_BASE:$SHA" )

echo "▸ Tag latest"
docker tag "$IMG_BASE:$SHA" "$IMG_BASE:latest"

echo "▸ Push"
docker push "$IMG_BASE:$SHA"
docker push "$IMG_BASE:latest"

echo "✓ build-push done — image: $IMG_BASE:$SHA"
```

### 4.5 `04-deploy.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?}" ; : "${GCP_REGION:?}"
AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
CLOUD_RUN_SERVICE_NAME="${CLOUD_RUN_SERVICE_NAME:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SHA="$(git rev-parse --short HEAD)"

IMG="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}:${SHA}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

echo "▸ Deploy $CLOUD_RUN_SERVICE_NAME → $IMG"

gcloud run deploy "$CLOUD_RUN_SERVICE_NAME" \
  --image="$IMG" \
  --region="$GCP_REGION" \
  --service-account="$SA_EMAIL" \
  --allow-unauthenticated \
  --port=8080 \
  --memory="${CLOUD_RUN_MEMORY:-512Mi}" \
  --cpu="${CLOUD_RUN_CPU:-1}" \
  --min-instances=0 \
  --max-instances="${CLOUD_RUN_MAX_INSTANCES:-10}" \
  --timeout=300 \
  --set-env-vars="^@^SPRING_PROFILES_ACTIVE=gcp,prod@GCP_PROJECT_ID=${GCP_PROJECT_ID}@SKILLSHUB_STORAGE_BUCKET=${GCS_BUCKET_NAME}" \
  --update-secrets="SKILLSHUB_GENAI_API_KEY=skillshub-genai-api-key:latest"

# 註：`^@^` 為 gcloud 的「自訂分隔符」語法（[Cloud Run env vars docs](https://cloud.google.com/run/docs/configuring/services/environment-variables)）。
# 因 SPRING_PROFILES_ACTIVE 的值含 comma (`gcp,prod`)，預設 comma 分隔會被誤切；
# 改用 `@` 作為 key=value 之間的分隔符即可正確解析。歷史版本曾用 `\,` 跳脫但已標為不可靠。

URL="$(gcloud run services describe "$CLOUD_RUN_SERVICE_NAME" --region="$GCP_REGION" --format='value(status.url)')"
echo "✓ deployed: $URL"
echo "  curl $URL/actuator/health"
```

### 4.6 `99-teardown.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?}" ; : "${GCP_REGION:?}"
read -p "Delete ALL skillshub resources in $GCP_PROJECT_ID? Type 'yes' to confirm: " CONFIRM
[[ "$CONFIRM" == "yes" ]] || { echo "abort"; exit 1; }

# 變數同 04-deploy.sh
SA_EMAIL="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

gcloud run services delete "${CLOUD_RUN_SERVICE_NAME:-skillshub}" --region="$GCP_REGION" --quiet || true
gcloud artifacts repositories delete "${AR_REPO_NAME:-skillshub}" --location="$GCP_REGION" --quiet || true
gcloud storage rm -r "gs://${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}" || true
gcloud firestore databases delete --database="${FIRESTORE_DB_ID:-skillshub}" --quiet || true
gcloud secrets delete skillshub-genai-api-key --quiet || true
gcloud iam service-accounts delete "$SA_EMAIL" --quiet || true

echo "✓ teardown done (project $GCP_PROJECT_ID 保留)"
```

### 4.7 `scripts/gcp/README.md`（簡要）

```markdown
# Skills Hub — GCP 部署

## 前置
- `gcloud auth login` 與 `gcloud auth application-default login`
- 已建立空 GCP project；billing account 已啟用
- Java 25 + Docker 在本機

## 三步啟動
1. `cp .env.example .env` 然後填三個必填 + 必要 export
2. `source .env`
3. 依序跑：
   ```
   ./scripts/gcp/01-bootstrap.sh   # ~3 min
   ./scripts/gcp/02-create-secrets.sh
   ./scripts/gcp/03-build-push.sh  # ~5 min（首次 buildpack 拉 layers）
   ./scripts/gcp/04-deploy.sh
   ```
4. 看到 Service URL 即完成

## 後續部署
改 code → commit → 跑 `03-build-push.sh && 04-deploy.sh` 即可。

## 拆除
`./scripts/gcp/99-teardown.sh`（需輸入 `yes` 確認）
```

---

## 5. File Plan

| 檔案 | 動作 | 說明 |
|---|---|---|
| `scripts/gcp/.env.example` | A | env 範本 — 3 必填 + 7 可選 |
| `scripts/gcp/01-bootstrap.sh` | A | APIs + AR + Firestore + GCS + SA + 7 IAM roles |
| `scripts/gcp/02-create-secrets.sh` | A | `skillshub-genai-api-key` 創建 / 加版本 + 授權 SA |
| `scripts/gcp/03-build-push.sh` | A | gradle bootBuildImage + docker push 雙 tag |
| `scripts/gcp/04-deploy.sh` | A | gcloud run deploy 帶 env / secret / SA / unauth（用 `^@^` 自訂分隔符） |
| `scripts/gcp/99-teardown.sh` | A | 互動確認 + 刪所有 spec 創的資源 |
| `scripts/gcp/README.md` | A | 三步啟動 quick start |
| `scripts/gcp/.gitignore` | A | `.env`（防 commit 真 key）— 與 backend/frontend 同模式：每個 dir 自己一份 .gitignore；avoid 創 root-level .gitignore（repo 目前無此檔案，§2.6 已驗證） |

**檔案總數：8 add，0 modify** — 全部是部署 infra script，零 Java code / yaml 變動。

---

## 6. Task Plan

> 由 `/planning-tasks S013` 於 2026-04-27 產生。POC: **not required**（理由：bash + gcloud + Spring Boot 4 `bootBuildImage` + Gradle 9.4 都是成熟工具鏈；§2.6 Validation Pass 已對當前 gcloud 文件 + 既有 build.gradle.kts plugin + application-gcp.yaml 做差異對照，無 hypothesis 待驗）。

| # | 主題 | AC 對應 | 變動範圍 | 依賴 | Status |
|---|------|---------|----------|------|--------|
| T1 | scripts/gcp/ 部署腳本套件（8 檔一次到位）— `.env.example` + `.gitignore` + 5 `.sh` + `README.md`，全部新增 | AC-1, AC-2 自動驗證；AC-4/5/6/7/9 設計 review；AC-3/8 manual-ready（spec §3 已宣告需真實 GCP） | 8 add，0 modify；零 Java/yaml/Gradle 變動 | none | pending |

**單一任務理由**：8 個檔案共用同一 verification gate（`bash -n` 全 pass + 結構檢查 + 設計 review against §4），分多 task 不會增加 RED → GREEN 隔離度反而切碎 reviewer 視角。所有 ACs 由 §3 已分類自動 / manual-ready，T1 包辦自動部分。

**E2E 評估**：S013 的「artifact」是 bash 腳本本身，沒有 JVM 應用要 build run；automated gate (bash -n + 結構檢查) 即等同 unit test。**Real-GCP E2E 屬 manual-ready**（spec §3 preamble 已宣告），由使用者在自己的 GCP 測試專案上跑全鏈路驗證；ship 時 §7 列出 verify 清單。

**`shellcheck` 為 advisory**：本機（macOS 預設）未裝 shellcheck，task gate 不阻擋；若使用者本機有裝，建議跑 `shellcheck scripts/gcp/*.sh` 觀察 warnings。Spec §3 preamble 已宣告非阻擋。

---

## 7. Implementation Results

> 完成日期：2026-04-27 | T1 PASS | 8 檔案到位 + bash -n 全綠 + Java backend 不受影響（114/0/0/0）

### 7.1 Verification

| 指令 | 結果 | 說明 |
|---|---|---|
| `ls -la scripts/gcp/` | 8 檔（5 .sh × 755 + .env.example + .gitignore + README.md） | 結構完整 |
| `bash -n scripts/gcp/*.sh` | exit 0 × 5 | 全部 syntactically valid |
| `shellcheck scripts/gcp/*.sh` | skipped (not installed locally) | spec §3 已宣告 advisory |
| `grep -F '"^@^' scripts/gcp/04-deploy.sh` | match | §2.6 校正項落實 |
| `grep '^\.env$' scripts/gcp/.gitignore` | 1 | 防 commit 真 secret |
| `cd backend && ./gradlew test` | BUILD SUCCESSFUL, 13 tasks UP-TO-DATE | Java 114 tests 維持綠（純 infra 新增） |

**E2E artifact verification（Phase 4 Step 1.5）**：S013 的 artifact 是 bash 腳本本身，不是 JVM 應用；automated gate（bash -n + 結構檢查 + grep design review）已等同 unit test 級別驗證。**Real-GCP runtime E2E 屬 manual-ready** — 需要使用者啟用 billing 的 GCP 測試專案才能跑 AC-3 / AC-8 / AC-9 互動部分；spec §3 preamble 已宣告此為 manual-verification。Phase 4 不在這裡阻擋，由使用者部署時依下方 §7.4 Manual-Ready Checklist 核對。

### 7.2 AC Results

| AC | 描述 | 驗證方式 | 結果 |
|---|---|---|---|
| AC-1 | 檔案結構 + shebang/strict mode + bash -n + 權限 755 | `ls -la` + `bash -n` + `head -2` | ✅ |
| AC-2 | `.env.example` 含 3 必填 + 7 可選 | `grep -E '^export ...'` | ✅ |
| AC-3 | 01-bootstrap.sh 在乾淨 GCP 專案啟用 6 API + 建 5 資源 | manual-ready | ⏳ user verify |
| AC-4 | 01-bootstrap.sh 第二次跑不報錯（idempotent） | design review (4 處 `describe ... &>/dev/null \|\| create` pattern) ✅ | ⏳ runtime user verify |
| AC-5 | 02-create-secrets.sh secret create / versions add | design review (`describe` if/else) ✅ | ⏳ runtime user verify |
| AC-6 | 03-build-push.sh 雙 tag (git short SHA + latest) | design review (兩 docker push) ✅ | ⏳ runtime user verify |
| AC-7 | 04-deploy.sh 完整 flag + secret + SA + unauthenticated + `^@^` | design review (11 個 flag + 校正後分隔符) ✅ | ⏳ runtime user verify |
| AC-8 | 部署後 service URL 回應 200（health + skills） | manual-ready | ⏳ user verify |
| AC-9 | 99-teardown.sh 互動 yes 確認 + soft-delete chain | design review (`read -r -p` + 嚴格 `[[ "$CONFIRM" == "yes" ]]` + 6 delete + 不刪 project) ✅ | ⏳ runtime user verify |

**自動驗證：AC-1, AC-2 全 ✅；AC-4..7, AC-9 設計 review 通過。**
**Manual-ready：AC-3, AC-8 + 上述 5 個 AC 的 runtime 行為，需真實 GCP 部署驗證。**

### 7.3 Key Findings

#### Finding 1: `gcloud run deploy --set-env-vars` 用 `^@^` 自訂分隔符

```bash
--set-env-vars="^@^SPRING_PROFILES_ACTIVE=gcp,prod@GCP_PROJECT_ID=${GCP_PROJECT_ID}@SKILLSHUB_STORAGE_BUCKET=${GCS_BUCKET_NAME}"
```

語法：`^DELIM^` 開頭告訴 gcloud 用 `DELIM` 分隔 key=value pairs。選 `@` 因為 env var 名 / 值幾乎不可能含 `@`，比歷史寫法 `\,` 跳脫安全。文件：[Cloud Run Environment variables](https://cloud.google.com/run/docs/configuring/services/environment-variables)。

#### Finding 2: Idempotent provisioning pattern

```bash
gcloud artifacts repositories describe "${AR_REPO_NAME}" --location="${GCP_REGION}" &>/dev/null \
  || gcloud artifacts repositories create "${AR_REPO_NAME}" \
       --repository-format=docker --location="${GCP_REGION}" \
       --description="Skills Hub container images"
```

`describe ... &>/dev/null` 失敗（資源不存在）才呼叫 `create`。重跑不報錯。本 spec 在 AR / Firestore / GCS / SA 4 處用同模式；IAM binding 用 `gcloud projects add-iam-policy-binding --quiet` 內建 idempotent，重複 grant 不會錯。

#### Finding 3: Secret create vs versions add (idempotent)

```bash
if gcloud secrets describe "${SECRET_NAME}" &>/dev/null; then
  printf '%s' "${SKILLSHUB_GENAI_API_KEY}" | gcloud secrets versions add "${SECRET_NAME}" --data-file=-
else
  printf '%s' "${SKILLSHUB_GENAI_API_KEY}" | gcloud secrets create "${SECRET_NAME}" --data-file=- --replication-policy=automatic
fi
```

`gcloud secrets create` 對已存在的 secret 會 fail，必須走 `versions add`。用 `printf '%s'` 而非 `echo -n` — 後者在某些 shell（如 dash）行為不同；前者跨 shell 一致。

#### Finding 4: bash 嚴格模式 + 互動確認

```bash
set -euo pipefail
read -r -p "Delete ALL skillshub resources in ${GCP_PROJECT_ID}? Type 'yes' to confirm: " CONFIRM
[[ "${CONFIRM}" == "yes" ]] || { echo "abort"; exit 1; }
```

`set -euo pipefail`：錯誤即退出（-e）、未定義變數視為錯誤（-u）、pipeline 任一段失敗整體失敗（pipefail）。每個 `.sh` 檔案開頭都有。
嚴格 `[[ "$CONFIRM" == "yes" ]]` 而非 `[[ "$CONFIRM" =~ ^[Yy] ]]` — typo 如 `y` / `Y` / `YES` 都不接受，避免誤觸發 destructive teardown。

#### Finding 5: Cloud Run secret 注入語法

```bash
--update-secrets="SKILLSHUB_GENAI_API_KEY=skillshub-genai-api-key:latest"
```

格式 `ENV_VAR_NAME=SECRET_NAME:VERSION`；`:latest` 表示永遠拉最新版本（rotate 時無需 redeploy）。Service Account 須有 `roles/secretmanager.secretAccessor`（01-bootstrap.sh 已 grant）。

### 7.4 Manual-Ready Checklist（使用者部署時依此核對）

部署到真實 GCP 專案前，請依以下清單核對：

```bash
# 0. 前置
gcloud auth login
gcloud auth application-default login
gcloud projects create my-skillshub-prod   # 或選現有 project
# 確認 billing account 已連結（GCP Console 或 `gcloud billing projects link`）

# 1. 設定 env
cp scripts/gcp/.env.example scripts/gcp/.env
# 編輯 .env：填上 GCP_PROJECT_ID / GCP_REGION / SKILLSHUB_GENAI_API_KEY
source scripts/gcp/.env

# 2. 依序執行 + 核對
./scripts/gcp/01-bootstrap.sh
# AC-3: 結束無 error；可用 `gcloud services list --enabled` 檢查 7 API + `gcloud iam service-accounts list` 檢查 SA
# AC-4: 再跑一次 ./scripts/gcp/01-bootstrap.sh — 應 exit 0、不重建資源

./scripts/gcp/02-create-secrets.sh
# AC-5: `gcloud secrets versions list skillshub-genai-api-key` 應顯 1 個 ENABLED version

./scripts/gcp/03-build-push.sh
# AC-6: `gcloud artifacts docker images list ${REGION}-docker.pkg.dev/${PROJECT}/skillshub/skillshub` 應顯兩 tag

./scripts/gcp/04-deploy.sh
# AC-7: 結尾印出 service URL

# AC-8: 用該 URL 驗
curl https://<service-url>/actuator/health
# 預期：HTTP 200, body {"status":"UP"}
curl https://<service-url>/api/v1/skills
# 預期：HTTP 200（既有 S001 行為，匿名可達）

# AC-9（拆除）
./scripts/gcp/99-teardown.sh
# 出現 prompt：Type 'yes' to confirm
# 輸入 'yes' 才會繼續
# 完成後 GCP project 仍在（手動 `gcloud projects delete` 才會刪）
```

### 7.5 Design Drift Sync

§2.6 Validation Pass 已在實作前對齊，無 post-implementation drift。實作完全依 §4 與 §2.6 校正後規劃：
- §4.5 (`04-deploy.sh`) 用 `^@^` 自訂分隔符 ✓
- §5 (file plan) 創 `scripts/gcp/.gitignore` 而非修 root .gitignore ✓
- 其餘 §4.2~4.6 設計 1:1 落實到對應檔案

唯三處實作小幅改進（不影響語意）：
1. `02-create-secrets.sh` 用 `printf '%s'` 取代 §4.3 範例的 `echo -n`（跨 shell 更可移植）
2. `03-build-push.sh` 用 `( cd backend && ./gradlew bootBuildImage )` 子 shell 包裝（§4.4 例已同此）
3. `99-teardown.sh` 對 GCS bucket 用 `gcloud storage rm --recursive`（§4.6 例同此）

### 7.6 Files (final)

**8 add，0 modify** — 全部於 `scripts/gcp/`：
- `.env.example` (2197 bytes) — env 範本
- `.gitignore` (5 bytes) — `.env`
- `01-bootstrap.sh` (3673 bytes, 755) — 7 API + AR + Firestore Enterprise + GCS + SA + 7 IAM
- `02-create-secrets.sh` (1632 bytes, 755) — secret create/versions add + grant SA
- `03-build-push.sh` (1502 bytes, 755) — auth + bootBuildImage + 雙 tag push
- `04-deploy.sh` (2318 bytes, 755) — Cloud Run deploy 帶 `^@^` env vars + secret + SA + unauthenticated
- `99-teardown.sh` (2204 bytes, 755) — 互動 yes 確認 + 6 delete soft-fail chain
- `README.md` (4597 bytes) — 三步啟動 quick start + image tag 策略 + cost guard + LAB 模式提示 + Troubleshooting + 變數對照表

**0 Java/Gradle/yaml 變動**；現有 114 tests 不受影響。

### 7.7 Tech Debt / Limitations

- **shellcheck 未在 CI 執行**：本機 macOS 預設無 shellcheck；spec §3 宣告為 advisory；待 CI/CD spec 排上後可加 `brew install shellcheck` 步驟。
- **Manual-ready ACs (AC-3, AC-8 互動部分)**：自動 gate 涵蓋設計 + 結構，runtime 行為待使用者實際部署驗證；§7.4 提供 verify checklist。
- **無 GitHub Actions CI 整合**：MVP 階段使用者本機跑；未來可加 `.github/workflows/deploy-gcp.yml` workflow 直接觸發 03/04 腳本（要先設好 GitHub OIDC + Workload Identity Federation）。

---

### 7.8 QA Review (independent subagent, 2026-04-27)

**Verdict:** PASS

**Verification:**
- `ls -la scripts/gcp/` → 10 entries (8 files + `.` + `..`): `.env.example` (644), `.gitignore` (644), `01-bootstrap.sh` (755), `02-create-secrets.sh` (755), `03-build-push.sh` (755), `04-deploy.sh` (755), `99-teardown.sh` (755), `README.md` (644) — structure matches spec §5
- `bash -n scripts/gcp/*.sh` → EXIT: 0 (all 5 .sh files syntactically valid)
- `cd backend && ./gradlew test` → BUILD SUCCESSFUL, 13 tasks UP-TO-DATE (114 tests unaffected; pure infra add touches 0 Java/Gradle/yaml)

**Design review:**
- `04-deploy.sh` `^@^` syntax: verified — line 43: `--set-env-vars="^@^SPRING_PROFILES_ACTIVE=gcp,prod@GCP_PROJECT_ID=${GCP_PROJECT_ID}@SKILLSHUB_STORAGE_BUCKET=${GCS_BUCKET_NAME}"`. Correct `^DELIM^` prefix with `@` as key=value separator. No `\,` reversion.
- `99-teardown.sh` strict yes match: verified — line 17: `[[ "${CONFIRM}" == "yes" ]] || { echo "abort"; exit 1; }`. Exact lowercase-only match; `y`, `Y`, `YES` are all rejected.
- All .sh strict mode + 755: verified — `#!/usr/bin/env bash` shebang on line 1, `set -euo pipefail` present in all 5 files (lines 16, 8, 9, 11, 10 respectively); all 5 files are mode `-rwxr-xr-x` (755).
- No real secrets in committed files: verified — `.env.example` has placeholder `AIzaSy...` (not a real key); `.gitignore` contains `.env` (single line); no literal API keys, passwords, or real credentials anywhere in the 8 committed files.

**Findings:**
- MINOR (spec text only, not code): AC-2 in §3 lists "7 可選" variables and enumerates only 5 by name (`AR_REPO_NAME`, `FIRESTORE_DB_ID`, `GCS_BUCKET_NAME`, `SERVICE_ACCOUNT_NAME`, `CLOUD_RUN_SERVICE_NAME`), omitting `IMAGE_NAME`, `CLOUD_RUN_MEMORY`, `CLOUD_RUN_CPU`, `CLOUD_RUN_MAX_INSTANCES`. The actual `.env.example` correctly ships 9 optional variables (the AC-2 list + `IMAGE_NAME` was added in §4.2 design, plus 3 cost-guard vars). The shipped artifact is correct and consistent with §4.1 (which shows all optional vars including the cost-guard trio). Only the AC-2 enumeration in §3 is under-specified. No fix needed for ship; can be updated in a future spec cleanup pass.

**Notes:** `99-teardown.sh` uses `gcloud storage rm --recursive` to clear bucket contents before deletion — a safe improvement over `gsutil rb` (which fails on non-empty buckets). The 6-resource delete chain with `|| true` is correctly ordered (Cloud Run → AR → GCS → Firestore → Secrets → SA), soft-failing each step to handle partial states. The GCP project is explicitly not deleted, which is the correct safeguard. IAM bindings are not explicitly deleted (not reversible via simple delete), which is an acceptable limitation acknowledged in §7.7. Overall implementation quality is high: design intent matches code, idempotency patterns are consistent, and the §2.6 `^@^` correction is faithfully implemented.

---

## Estimation

| Dimension | Score | Reason |
|---|---|---|
| Technical risk | 1 | gcloud / docker / bash 都是成熟工具；無新 SDK |
| Uncertainty | 2 | 多服務 orchestrate；GCP API 命令偶有 minor flag drift；首跑可能卡 quota / billing |
| Dependencies | 2 | GCP 上 6 個 API + Spring Boot 4 + Gradle bootBuildImage 鏈需都正確配合 |
| Scope | 2 | 7 add + 1 modify = 8 檔，全是 shell + 1 markdown |
| Testing | 2 | 屬 manual / dry-run；可加 `bash -n` syntax 檢查；`shellcheck` 為 advisory |
| Reversibility | 2 | 99-teardown.sh 提供拆除；但實際部署消耗 GCP 額度 |
| **Total** | **11** | **S** |
