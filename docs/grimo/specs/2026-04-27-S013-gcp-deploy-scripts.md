# S013: GCP Cloud Run 部署腳本與打包流程

> Spec: S013 | Size: S(11) | Status: ⏳ Design
> Date: 2026-04-27
> Depends: S009 (✅ shipped — `gcp` profile + `application-gcp.yaml`), S011/S012 (✅/in-design — security 設定 export 為 env var)
> Research: GCP 官方 docs（Firestore Enterprise + MongoDB compat、Cloud Run secret manager、Spring Boot 4 bootBuildImage）

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
  --set-env-vars="SPRING_PROFILES_ACTIVE=gcp\,prod,GCP_PROJECT_ID=${GCP_PROJECT_ID},SKILLSHUB_STORAGE_BUCKET=${GCS_BUCKET_NAME}" \
  --update-secrets="SKILLSHUB_GENAI_API_KEY=skillshub-genai-api-key:latest"

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
| `scripts/gcp/04-deploy.sh` | A | gcloud run deploy 帶 env / secret / SA / unauth |
| `scripts/gcp/99-teardown.sh` | A | 互動確認 + 刪所有 spec 創的資源 |
| `scripts/gcp/README.md` | A | 三步啟動 quick start |
| `.gitignore` | M | 新增 `scripts/gcp/.env`（防 commit 真 key） |

**檔案總數：1 modify + 7 add = 8** — 全部是部署 infra script，零 Java code 變動。

---

## 6. Task Plan

> 由 `/planning-tasks S013` 產生。

---

## 7. Implementation Results

> 由 `/planning-tasks S013` 完成所有 tasks 後彙整。

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
