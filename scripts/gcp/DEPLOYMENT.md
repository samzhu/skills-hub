# Skills Hub — Cloud Run 部署一步一步指南

> 從 GCP project 空白狀態，逐步部署 Skills Hub backend 到 Cloud Run。
> 每步可獨立執行 + 立即驗證；任一步失敗看該步「失敗時」備註。
>
> 全程約 **15–20 分鐘**（Cloud SQL instance 啟動佔大半）。

---

## 架構速覽

```
┌──────────────────────── Cloud Run instance (gen2, 2 CPU / 1280Mi) ───┐
│                                                                      │
│   ┌─ app (8080, ingress) ─────────────┐                              │
│   │      Spring Boot 4 + Modulith     │                              │
│   │      JDBC: user / password        │                              │
│   │      cpu=1, memory=1Gi            │                              │
│   │              │                    │                              │
│   │       localhost:5432              │                              │
│   │              ▼                    │                              │
│   │      cloud-sql-proxy ─────────────┼──► TLS ──► Cloud SQL         │
│   │      (sidecar, 無對外 port)       │           PostgreSQL 18      │
│   │      cpu=1, memory=256Mi          │           + pgvector         │
│   └───────────────────────────────────┘                              │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
         │                                       │
         ▼                                       ▼
     GCS bucket                            Secret Manager
     (skill packages)             (db-password / genai-api-key)
                                  (app 啟動時走 spring-cloud-gcp ${sm@...} 拉取)
```

關鍵設計：
- **Multi-container**：app + cloud-sql-proxy 兩 container 共用 localhost；`container-dependencies` annotation 確保 proxy 先 ready
- **Probes**：startup → `/actuator/health/readiness`（含 DB check，gate traffic）；liveness → `/actuator/health/liveness`（process only）
- **Secret Manager**：機敏值走 Spring Cloud GCP `${sm@<secret-id>}` placeholder，**不**進 service.yaml
- **Profile**：`lab,gcp`（封測，OAuth off）/ `prod,gcp`（正式，OAuth on）

---

# Step 0 — 前置 Checklist

確認以下工具裝好（任一缺都跑不下去）：

```bash
gcloud --version       # >= 500.0.0
docker info >/dev/null && echo OK    # daemon 必須跑著
java -version          # 25
envsubst --version | head -1   # GNU gettext
git --version
```

**失敗時**：
- `envsubst not found`：`brew install gettext`（macOS）
- `Cannot connect to the Docker daemon`：開 Docker Desktop
- `gcloud: command not found`：<https://cloud.google.com/sdk/docs/install>

GCP 端 auth — **先檢查當前登入帳號**避免誤操作到其他 project：

```bash
# 1. 看當前已登入帳號（多帳號會列多行；ACTIVE 標記目前 default）
gcloud auth list

# 2. 若有舊帳號殘留 / 不是要用的帳號，徹底清除：
gcloud auth revoke --all                          # 清掉 user credentials（所有帳號）
gcloud auth application-default revoke --quiet    # 清掉 ADC（避免本機 SDK 用到錯帳號）
rm -f ~/.config/gcloud/application_default_credentials.json   # 保險再清一次（前一行 revoke 失敗時）

# 3. 重新登入正確帳號
gcloud auth login                         # 互動 browser auth — 用要部署的 GCP 帳號
gcloud auth application-default login     # ADC — 讓本機 SDK / spring-cloud-gcp 也能用
```

GCP project 必須**已建好且啟用 billing**（本指南不重建 project）。

---

# Phase A — 一次性建立 infra（Step 1–8）

跑完一次後，後續改 code 只需 Phase B。

## Step 1 — 設定環境變數

**做什麼**：複製範本 → 填值 → source。

```bash
cp scripts/gcp/.env.example scripts/gcp/.env
$EDITOR scripts/gcp/.env
```

**必填 4 項**：
- `GCP_PROJECT_ID`
- `GCP_REGION`（建議 `asia-east1` / `us-central1`）
- `DB_PASSWORD`（24+ 字元；Step 5 + Step 8 共用）
- `SKILLSHUB_GENAI_API_KEY`（取得：<https://aistudio.google.com/apikey>）

```bash
source scripts/gcp/.env
gcloud config set project ${GCP_PROJECT_ID}
```

**驗證**：
```bash
echo "project=${GCP_PROJECT_ID} region=${GCP_REGION} sa=${SA_EMAIL}"
echo "instance_conn=${CLOUDSQL_INSTANCE_CONN}"
echo "image_base=${IMG_BASE}"
# 4 行皆非空
```

**失敗時**：`source` 必須在 cwd=repo root；shell var 名沒 dot；詳見 [README.md](./README.md) 的「`source .env` 怎麼用」段。

---

## Step 2 — 啟用 6 個 GCP API

```bash
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  sqladmin.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  iam.googleapis.com
```

**API 用途**：

| API | 用途 |
|---|---|
| `run.googleapis.com` | Cloud Run 服務本身 |
| `artifactregistry.googleapis.com` | Docker image 倉儲 |
| `sqladmin.googleapis.com` | Cloud SQL 管理 + Auth Proxy 拿 ephemeral cert |
| `storage.googleapis.com` | GCS（skill 套件存放） |
| `secretmanager.googleapis.com` | spring-cloud-gcp 拉 secret |
| `iam.googleapis.com` | Service Account / role binding（顯式 enable 利於審計） |

> 不需要 `aiplatform.googleapis.com` — embedding 走 [Google AI Studio Gemini API](https://aistudio.google.com/apikey)（API key auth，端點 `generativelanguage.googleapis.com`），**非** Vertex AI。未來若切 Vertex AI 才補。

**驗證**：
```bash
gcloud services list --enabled \
  --filter="config.name:(run.googleapis.com OR sqladmin.googleapis.com OR secretmanager.googleapis.com)" \
  --format="value(config.name)" | wc -l
# 預期：3
```

---

## Step 3 — 建 Artifact Registry Docker repo

```bash
gcloud artifacts repositories create ${AR_REPO_NAME} \
  --repository-format=docker \
  --location=${GCP_REGION} \
  --description="Skills Hub container images"

# 一次性：把 region 加進 Docker credHelpers
gcloud auth configure-docker ${GCP_REGION}-docker.pkg.dev --quiet
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `--repository-format=docker` | 倉儲類型；其他選項 `maven` / `npm` / `python` 等 |
| `--location=${GCP_REGION}` | 倉儲所在 region（建議跟 Cloud Run 同 region 降 push/pull latency） |
| `gcloud auth configure-docker` | 把 GCP credential 寫進 `~/.docker/config.json` 的 `credHelpers`；之後 `docker push` 自動帶 token |

**驗證**：
```bash
gcloud artifacts repositories describe ${AR_REPO_NAME} \
  --location=${GCP_REGION} --format="value(name)"
# 預期：projects/<proj>/locations/<region>/repositories/skillshub
```

**失敗時**：`already exists` 可忽略（idempotent）。

---

## Step 4 — 建 Cloud SQL instance（PostgreSQL 18）

> 此 step 最久 — instance 進 RUNNABLE 約 5–8 分鐘。

```bash
gcloud sql instances create ${CLOUDSQL_INSTANCE_NAME} \
  --database-version=POSTGRES_18 \
  --edition=${CLOUDSQL_EDITION} \
  --region=${GCP_REGION} \
  --tier=${CLOUDSQL_TIER} \
  --storage-type=SSD \
  --storage-size=10GB \
  --storage-auto-increase
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `--database-version=POSTGRES_18` | PostgreSQL 主版本（GA 2025-11-20） |
| `--edition=ENTERPRISE` | PG16+ 預設 ENTERPRISE_PLUS（更貴）；`db-f1-micro` 只支援 ENTERPRISE，必須顯式指定 |
| `--tier=db-f1-micro` | shared-core 0.6GB RAM；最便宜起步配額。正式可換 `db-custom-1-3840`（1 vCPU/3.75GB）等 |
| `--storage-type=SSD` | SSD vs HDD；SSD 預設值，latency 低 |
| `--storage-size=10GB` | 起步 disk 容量 |
| `--storage-auto-increase` | 用量達 ~90% 時 Cloud SQL 自動加大 disk（避免 disk full 停機）；無「不可逆地」一直長大的限制 |

> **LAB 不開自動 backup** — 開發人員自驗環境，資料可隨時重建。Production cut 時加回：
> ```
> --backup --backup-start-time=03:00
> ```
> 啟用每日自動 backup（03:00 UTC = 台北 11:00 開始），7 天保留。

**等到 RUNNABLE**：

```bash
until [[ "$(gcloud sql instances describe ${CLOUDSQL_INSTANCE_NAME} --format='value(state)')" == "RUNNABLE" ]]; do
  printf "."; sleep 10
done; echo " ready"
```

**驗證**：
```bash
gcloud sql instances describe ${CLOUDSQL_INSTANCE_NAME} \
  --format="table(state,databaseVersion,settings.tier)"
# 預期 STATE=RUNNABLE, VERSION=POSTGRES_18, TIER=db-f1-micro
```

**失敗時**：
- `--edition=ENTERPRISE_PLUS required for db-perf-optimized-*`：你的 tier 跟 edition 不匹配；db-f1-micro 必須 `--edition=ENTERPRISE`
- pgvector 不需 instance flag — Flyway V1 `CREATE EXTENSION vector` 會自動處理

---

## Step 5 — 建 database + 應用 DB user

```bash
gcloud sql databases create ${DB_NAME} --instance=${CLOUDSQL_INSTANCE_NAME}

gcloud sql users create ${DB_USER} \
  --instance=${CLOUDSQL_INSTANCE_NAME} \
  --password="${DB_PASSWORD}"
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `--instance=` | 隸屬哪個 Cloud SQL instance（一個 instance 可開多個 database） |
| `--password=` | 設密碼（PostgreSQL `MD5` 或 `SCRAM-SHA-256` 加密儲存）。雙引號包住避免 shell 解釋特殊字元 |

> 為什麼不用 `postgres` superuser？最小權限：應用層用獨立 DB user `skillshub_app`，避免有 superuser 權限亂搞 schema。

**驗證**：
```bash
gcloud sql databases list --instance=${CLOUDSQL_INSTANCE_NAME} \
  --format="value(name)" | grep -x ${DB_NAME}
gcloud sql users list --instance=${CLOUDSQL_INSTANCE_NAME} \
  --format="value(name)" | grep -x ${DB_USER}
# 兩行皆有輸出
```

---

## Step 6 — 建 GCS bucket

```bash
gcloud storage buckets create gs://${GCS_BUCKET_NAME} --location=${GCP_REGION}
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `gs://...` | bucket 名稱必須**全球唯一**（不只 project 內），所以 `.env.example` 預設前綴 `${GCP_PROJECT_ID}-` |
| `--location=${GCP_REGION}` | single-region（最便宜）；其他選項 `--location=US`（multi-region，貴但跨 region 可用） |

**驗證**：
```bash
gcloud storage buckets describe gs://${GCS_BUCKET_NAME} --format="value(name)"
# 預期：<bucket-name>
```

---

## Step 7 — 建 Service Account + 6 個 IAM role

```bash
gcloud iam service-accounts create ${SERVICE_ACCOUNT_NAME} \
  --display-name="Skills Hub runtime"

for role in \
  roles/cloudsql.client \
  roles/storage.objectAdmin \
  roles/secretmanager.secretAccessor \
  roles/logging.logWriter \
  roles/monitoring.metricWriter \
  roles/cloudtrace.agent; do
  gcloud projects add-iam-policy-binding ${GCP_PROJECT_ID} \
    --member=serviceAccount:${SA_EMAIL} \
    --role=${role} \
    --condition=None \
    --quiet >/dev/null
done
```

**參數說明**（`add-iam-policy-binding` 部分）：

| Flag | 說明 |
|---|---|
| `--member=serviceAccount:...` | 授權對象。前綴 `serviceAccount:` / `user:` / `group:` 區分主體類型 |
| `--role=roles/...` | 預定義角色名（`roles/` 前綴必加） |
| `--condition=None` | 顯式宣告「無條件 binding」；不加會跳互動 prompt |
| `--quiet` | 抑制 confirm prompt（自動化必加） |

**角色用途**：

| Role | 用途 |
|---|---|
| `cloudsql.client` | proxy 連 Cloud SQL Admin API 換 ephemeral cert |
| `storage.objectAdmin` | 上傳 / 讀取 skill 套件至 GCS |
| `secretmanager.secretAccessor` | spring-cloud-gcp 拉 DB password / GenAI API key |
| `logging.logWriter` | stdout/stderr → Cloud Logging |
| `monitoring.metricWriter` | Actuator metric → Cloud Monitoring |
| `cloudtrace.agent` | OpenTelemetry trace → Cloud Trace |

> 不需要 `roles/aiplatform.user` — embedding 走 Google AI Studio Gemini API（API key auth，**非** Vertex AI）。未來切 Vertex AI 才補。

**驗證**：
```bash
gcloud projects get-iam-policy ${GCP_PROJECT_ID} \
  --flatten="bindings[].members" \
  --filter="bindings.members:serviceAccount:${SA_EMAIL}" \
  --format="value(bindings.role)" | wc -l
# 預期：6
```

---

## Step 8 — 建 Secret Manager 2 個 secret

> DB user 不是機敏值（不進 Secret Manager），由 service.yaml env var 注入。

```bash
create_or_update_secret() {
  local name="$1" value="$2"
  if gcloud secrets describe ${name} &>/dev/null; then
    printf '%s' "${value}" | gcloud secrets versions add ${name} --data-file=- >/dev/null
  else
    printf '%s' "${value}" | gcloud secrets create ${name} \
      --data-file=- --replication-policy=automatic >/dev/null
  fi
  gcloud secrets add-iam-policy-binding ${name} \
    --member=serviceAccount:${SA_EMAIL} \
    --role=roles/secretmanager.secretAccessor \
    --quiet >/dev/null
}

create_or_update_secret skillshub-db-password    "${DB_PASSWORD}"
create_or_update_secret skillshub-genai-api-key  "${SKILLSHUB_GENAI_API_KEY}"
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `--data-file=-` | secret 內容從 stdin 讀（避免 password 進 command-line history） |
| `--replication-policy=automatic` | secret 自動 multi-region 複製；對單 region 部署也是建議值（高可用） |
| `gcloud secrets versions add` | 既有 secret 加新版本；舊版本仍可存取直到 disable |
| `gcloud secrets create` | 第一次建 secret（資源層級的命名空間） |

**驗證**：
```bash
gcloud secrets list --filter="name~skillshub" --format="value(name)"
# 預期 2 行：skillshub-db-password / skillshub-genai-api-key

gcloud secrets versions access latest --secret=skillshub-db-password | head -c 20
# 印出 password 前 20 字元（驗 SA 真的能讀）
```

> Phase A 完成 — infra 全部就位。可以開始部署。

---

# Phase B — 部署（Step 9–14；每次 release 重複）

## Step 9 — Build OCI image（本地 `./gradlew bootBuildImage`）

```bash
# TAG 可自訂版本號；不指定走 git short SHA
export TAG=$(git rev-parse --short HEAD)   # 或 export TAG=v1.2.3
export IMG=${IMG_BASE}:${TAG}

cd backend
./gradlew bootBuildImage --imageName="${IMG}"
cd ..
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `bootBuildImage` | Spring Boot Gradle plugin task；走 [Paketo Buildpack](https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html)（無需自寫 Dockerfile） |
| `--imageName=` | 指定 image URI（含 registry / repo / tag）；不指定預設 `<group>/<name>:<version>` |
| `TAG` 環境變數 | 控制 image tag；不指定走 git short SHA。release 可指定 `TAG=v1.2.3` |

**驗證**：
```bash
docker images "${IMG_BASE}" --format "{{.Repository}}:{{.Tag}} {{.Size}}"
# 預期：<image-uri>:<TAG> ~280–350MB
```

**失敗時**：
- `bootBuildImage` OOM → Docker Desktop Resources 調 ≥ 4GB
- 卡在 frontend npm build → `cd frontend && npm install` 一次

---

## Step 10 — Push 到 Artifact Registry

```bash
docker push "${IMG}"
docker tag "${IMG}" "${IMG_BASE}:latest"
docker push "${IMG_BASE}:latest"
```

**驗證**：
```bash
gcloud artifacts docker images list ${IMG_BASE} --include-tags \
  --format="table(version,tags)" --limit=5
# 預期：兩列 — :${TAG} 與 :latest
```

**失敗時**：`401 Unauthorized` → 重跑 `gcloud auth configure-docker ${GCP_REGION}-docker.pkg.dev`

---

## Step 11 — Render service.yaml（envsubst 套變數）

```bash
envsubst < scripts/gcp/service.yaml > scripts/gcp/service.rendered.yaml
```

**參數說明**：

| 命令 | 說明 |
|---|---|
| `envsubst` | GNU gettext 工具；把 stdin 內 `${VAR}` / `$VAR` 換成當前 shell exported 的 env var 值 |
| `< scripts/gcp/service.yaml` | input：模板檔 |
| `> scripts/gcp/service.rendered.yaml` | output：渲染結果（已 .gitignore） |

> 未設定的變數會被替換為**空字串**（不報錯）。Step 1 source `.env` 漏 export 任何變數會導致 yaml 出現空欄位 → Step 12 部署失敗。

**驗證**（檢查所有 placeholder 都換掉）：
```bash
grep -n '\${' scripts/gcp/service.rendered.yaml || echo "all placeholders resolved ✓"
```

肉眼確認關鍵欄位：
```bash
grep -E 'image:|serviceAccountName:|cloudsql|spring.profiles' scripts/gcp/service.rendered.yaml
```

---

## Step 12 — 部署到 Cloud Run

```bash
gcloud run services replace scripts/gcp/service.rendered.yaml \
  --region=${GCP_REGION}
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `gcloud run services replace` | 宣告式更新：完整以 yaml 為 source of truth，覆蓋 service spec（IAM binding 不會被覆蓋） |
| `<file>` | yaml 檔路徑 |
| `--region=` | service 所在 region |

> 對照 `gcloud run deploy`（命令式）：替代方案，但對 multi-container / sidecar / probe 等進階設定支援差，故此處用 `replace`。

預期過程（~30–60 秒）：
```
Applying new configuration to Cloud Run service [skillshub] in project [...] region [...]
✓ Creating Revision...
  Setting IAM Policy...
✓ Routing traffic...
Done.
```

**驗證**：
```bash
gcloud run services describe ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} \
  --format="value(status.conditions[0].status,status.latestReadyRevisionName)"
# 預期：True  skillshub-00001-xxx
```

**失敗時**：
- `Container failed to start` → Step 14 logs 指令查根因
- `connection refused localhost:5432` → sidecar 沒 ready；確認 service.yaml 內 container-dependencies + 雙 startupProbe 都在

---

## Step 13 — 開放公開存取（首次部署需要）

```bash
gcloud run services add-iam-policy-binding ${CLOUD_RUN_SERVICE_NAME} \
  --member=allUsers \
  --role=roles/run.invoker \
  --region=${GCP_REGION}
```

**參數說明**：

| Flag | 說明 |
|---|---|
| `--member=allUsers` | 特殊主體：任何**未認證**請求；對等 `allAuthenticatedUsers` = 任何 Google 帳號 |
| `--role=roles/run.invoker` | Cloud Run 內建角色：可呼叫 service URL；不能改 service 設定 |

> 若 GCP org 有 Domain Restricted Sharing 政策，`allUsers` 會被擋；改用 Identity-Aware Proxy 或 Cloud Load Balancer 前置。

**驗證**：
```bash
gcloud run services get-iam-policy ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} \
  --format="value(bindings.members)" | grep allUsers
# 預期：allUsers
```

> 此 step 只第一次需要；後續 `gcloud run services replace` 不會清掉 IAM binding。

---

## Step 14 — 取得 URL + 煙霧測試

```bash
export SERVICE_URL=$(gcloud run services describe ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} --format='value(status.url)')
echo "deployed: ${SERVICE_URL}"
```

**3 個必驗 endpoint**：

```bash
# 1. Liveness（process 活著）
curl -fsS ${SERVICE_URL}/actuator/health/liveness
# 預期：{"status":"UP"}

# 2. Readiness（DB / 全部 dep ready）
curl -fsS ${SERVICE_URL}/actuator/health/readiness
# 預期：{"status":"UP"}

# 3. 應用 endpoint（DB 真的 query 得到）
curl -fsS ${SERVICE_URL}/api/v1/skills | head -c 200
# 預期：JSON list（首次部署可能空：[] 或 {"content":[],...}）
```

**進階驗證**：

```bash
# 看完整 health（含每個 component）
curl -fsS ${SERVICE_URL}/actuator/health | jq

# Cloud Run logs（含 sidecar）
gcloud run services logs read ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} --limit=50

# 確認 lab profile 有套到（log 應該看到 OAuth 走 LAB 分支）
gcloud run services logs read ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} --limit=200 | grep -i "lab\|oauth\|ready"
```

> Phase B 完成 — 部署 success。後續改 code 重複 Step 9–12 即可（Step 13 不用，Step 14 視需要）。

---

# 運維操作

## Rotation — 換 DB password

```bash
# 1. Cloud SQL 端改密碼
gcloud sql users set-password ${DB_USER} \
  --instance=${CLOUDSQL_INSTANCE_NAME} \
  --password='new-stronger-password'

# 2. Secret Manager add new version
printf '%s' 'new-stronger-password' | \
  gcloud secrets versions add skillshub-db-password --data-file=-

# 3. 強制 cold restart（既存 instance 仍持舊值，需重啟拿新值）
gcloud run services update ${CLOUD_RUN_SERVICE_NAME} \
  --region=${GCP_REGION} \
  --update-labels=rotated-at=$(date +%s)
```

> Production 建議把 `${sm@skillshub-db-password}` 改 `${sm@skillshub-db-password/N}` pin 版本號，rotation 流程：先 add new version → 改 yaml pin 新版本 → redeploy → 舊版本 disable。

## Troubleshooting

| 症狀 | 可能原因 | 解法 |
|---|---|---|
| Step 12 revision `Container failed to start` | DB 連不到 / Flyway 失敗 / secret 注入失敗 | `gcloud run services logs read ${CLOUD_RUN_SERVICE_NAME} --region=${GCP_REGION} --limit=200` 看 stack trace |
| Step 14 health 503 / cold start 超時 | 首次 Flyway migration 慢 | service.yaml 已給 startup probe 5 min；若仍不夠改 `failureThreshold: 90` |
| `connection refused localhost:5432` | sidecar 沒 ready | 確認 service.yaml `container-dependencies` annotation + 雙 startupProbe 都在 |
| `pgvector extension not found` | DB user 缺 CREATE EXTENSION 權限 | `gcloud sql connect ${CLOUDSQL_INSTANCE_NAME} --user=postgres` 手動 `CREATE EXTENSION vector;` 一次 |
| `gcloud run services replace` validation: CPU sum | 整 instance CPU 必須 1/2/4/6/8 | 本 yaml app=1 + proxy=1 = 2 ✓；改 yaml 時注意 |
| Step 10 push 401 | Docker 沒拿 AR credentials | 重跑 `gcloud auth configure-docker ${GCP_REGION}-docker.pkg.dev` |
| Step 14 readiness UP 但 API 500 | App 起來但 query fail | logs 看；可能 DB user 缺 schema 權限 |

## Teardown — 全清

```bash
gcloud run services delete ${CLOUD_RUN_SERVICE_NAME} --region=${GCP_REGION} --quiet
gcloud sql instances delete ${CLOUDSQL_INSTANCE_NAME} --quiet
gcloud storage rm -r gs://${GCS_BUCKET_NAME} --quiet
gcloud artifacts repositories delete ${AR_REPO_NAME} --location=${GCP_REGION} --quiet
gcloud secrets delete skillshub-db-password --quiet
gcloud secrets delete skillshub-genai-api-key --quiet
gcloud iam service-accounts delete ${SA_EMAIL} --quiet
```

> Project 本身不刪。要連 project 一起：`gcloud projects delete ${GCP_PROJECT_ID}`。

或一鍵：`./scripts/gcp/99-teardown.sh`（互動 yes 確認）。

---

# 開發者參考

## Spring property ↔ Cloud Run env var 命名慣例

| 物件 | 允許字元 | 命名 |
|---|---|---|
| Spring property | `[a-z0-9.-]` | `skillshub.db.password` |
| Cloud Run env name | `[A-Za-z0-9_.-]`（K8s `RelaxedEnvironmentVariableValidation` GA 2025-06-28） | `skillshub.db.password`（dot 對齊 property） |
| Secret Manager secret-id | `[A-Za-z0-9_-]`（**無 dot**） | `skillshub-db-password`（hyphen） |
| Shell var（.env） | POSIX C-identifier | `DB_PASSWORD` |

3 個 namespace 獨立，`secretKeyRef.name` 跟 env `name` 不需相同。

## Secret Manager 整合機制（Option B：spring-cloud-gcp `sm@`）

機敏值**不**走 Cloud Run `valueFrom.secretKeyRef`，改由 `spring-cloud-gcp-starter-secretmanager` 在 app startup 從 Secret Manager 拉：

```
build.gradle.kts                     application-gcp.yaml                    Secret Manager
─────────────────                    ──────────────────────                  ──────────────
spring-cloud-gcp-starter             spring.config.import: sm@               skillshub-db-password
  -secretmanager        ──→          datasource.password:           ──HTTPS──►  (latest)
                                       ${sm@skillshub-db-password}
```

優缺點：

| 維度 | Option B（`${sm@}`）  | 對照：Cloud Run `secretKeyRef` |
|---|---|---|
| service.yaml 複雜度 | 低 — env 全 plain `value` | 高 — 每 secret 一 secretRef block |
| 改 secret 需重 deploy | 否（cold restart 拿新值） | `key: latest` 同；pin version 要重 deploy |
| App 啟動成本 | +50–200ms / secret | 0 |
| 失敗模式 | secret 不存在 → app startup fail | secret 不存在 → revision deploy fail |

## 加新 config / secret 時 — 開發者 checklist

### Case A：新增**機敏值** property（會走 Secret Manager）

| 步驟 | 改動位置 | 動作 |
|---|---|---|
| 1 | `SkillshubProperties.java` | 加欄位 |
| 2 | `application-gcp.yaml` | 加 `skillshub.foo.token: ${sm@skillshub-foo-token}` |
| 3 | `02-create-secrets.sh` | 加 `create_or_update_secret skillshub-foo-token "${FOO_TOKEN}"` |
| 4 | `.env.example` | 加 `export FOO_TOKEN='change-me'` |
| 5 | `DEPLOYMENT.md` Step 1 必填項 | 加說明 |
| 6 | PR description | 「部署人員需在 .env 加 `FOO_TOKEN`，重跑 Step 8 + Step 9–14」 |

### Case B：新增**非機敏** config（走 Cloud Run env var）

| 步驟 | 改動位置 | 動作 |
|---|---|---|
| 1 | `SkillshubProperties.java` | 加欄位 |
| 2 | `service.yaml` env block | 加 `- name: skillshub.foo.bar` / `value: ${SOMETHING}` |
| 3 | `04-deploy.sh` export 列表 | 加 `SOMETHING` |
| 4 | `.env.example` | 加 `export SOMETHING=default-value` |
| 5 | PR description | 「部署人員需在 .env 加 `SOMETHING`，重跑 Step 9–14」 |

### 部署人員拉到 PR 後的標準流程

```bash
git pull
diff scripts/gcp/.env.example scripts/gcp/.env   # 看新增 vars
$EDITOR scripts/gcp/.env                         # 補實際值
source scripts/gcp/.env

# Case A 才需要：
./scripts/gcp/02-create-secrets.sh

# 部署：
./scripts/gcp/03-build-push.sh
./scripts/gcp/04-deploy.sh

# 驗證：
curl -fsS ${SERVICE_URL}/actuator/health
gcloud run services logs read ${CLOUD_RUN_SERVICE_NAME} --region=${GCP_REGION} --limit=100 \
  | grep -iE "secret|sm@|UNAUTHENTICATED|UnsatisfiedDependency"
```

---

# 參考連結

- [Cloud SQL — PostgreSQL 18 editions intro](https://docs.cloud.google.com/sql/docs/postgres/editions-intro)
- [Cloud SQL — PostgreSQL extensions（含 pgvector）](https://docs.cloud.google.com/sql/docs/postgres/extensions)
- [Cloud Run — Configure containers (multi-container)](https://docs.cloud.google.com/run/docs/configuring/services/containers)
- [Cloud Run — Healthchecks (startup / liveness / readiness)](https://docs.cloud.google.com/run/docs/configuring/healthchecks)
- [Cloud Run — Startup CPU boost](https://docs.cloud.google.com/run/docs/configuring/services/cpu)
- [Cloud Run — Configure secrets](https://docs.cloud.google.com/run/docs/configuring/services/secrets)
- [Spring Cloud GCP — Secret Manager](https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/v8.0.2/docs/src/main/asciidoc/secretmanager.adoc)
- [cloud-sql-proxy releases](https://github.com/GoogleCloudPlatform/cloud-sql-proxy/releases)
- [Spring Boot bootBuildImage（Paketo buildpack）](https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html)
