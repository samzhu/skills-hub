# Skills Hub — GCP 部署腳本

把 Skills Hub backend 部署到 GCP Cloud Run（multi-container：Spring Boot + cloud-sql-proxy sidecar）。
資料庫 = Cloud SQL PostgreSQL 18 + pgvector，DB 認證走帳密（Secret Manager 注入）。

## 完整指南

詳見 [DEPLOYMENT.md](./DEPLOYMENT.md) — 架構圖、前置、infra 建立、build/push、部署、煙霧測試、Troubleshooting、Teardown。

## 快速啟動

```bash
# 0. 前置（首次）
brew install gettext            # envsubst（macOS；Linux: apt-get install gettext-base）
gcloud auth login
gcloud auth application-default login

# 1. 設定環境變數（cwd 必須在 repo root；下面所有路徑皆 relative to root）
cp scripts/gcp/.env.example scripts/gcp/.env
$EDITOR scripts/gcp/.env        # 改 GCP_PROJECT_ID / GCP_REGION / DB_PASSWORD / SKILLSHUB_GENAI_API_KEY
source scripts/gcp/.env         # 把變數注入「當前 shell」— 詳見下節

# 2. 一次性 infra（~8 分鐘，含 Cloud SQL instance 啟動）
./scripts/gcp/01-bootstrap.sh
./scripts/gcp/02-create-secrets.sh

# 3. Build + push image（本地 ./gradlew bootBuildImage）
./scripts/gcp/03-build-push.sh

# 4. 部署（envsubst + gcloud run services replace）
./scripts/gcp/04-deploy.sh
```

## `source .env` 怎麼用 / 為什麼

`.env` 是純 shell 檔案，每行 `export VAR=value`。`source` 命令把這些 `export` **跑在當前 shell**，變數就留在 shell session 內，後續 `01-04-*.sh` 跑起來才看得到（每個 .sh 是 child process，**繼承**父 shell 的 env）。

```bash
# 兩種等價寫法（從 repo root 跑）：
source scripts/gcp/.env
.      scripts/gcp/.env          # POSIX 點命令；功能完全一樣

# 驗證：
echo $GCP_PROJECT_ID             # 應顯示你設的值
env | grep -E 'GCP_|SKILLSHUB|DB_|CLOUD_RUN' | sort
```

**常見錯誤**：

| 寫法 | 為什麼不行 |
|---|---|
| `bash scripts/gcp/.env` | bash 在 subshell 跑檔案，export 留在 subshell，回到當前 shell 變數消失 |
| `./scripts/gcp/.env` | 同上（且 .env 沒 +x，會直接報錯） |
| `sh scripts/gcp/.env` | 同 bash 的問題 |
| 在 zsh 內 `source .env` 但 cwd 不是 repo root | `.env` 找不到；要嘛 `cd` 進 root，要嘛用絕對路徑 `source ~/path/to/skills-hub/scripts/gcp/.env` |

**Tip — 換 shell session 後重新 source**：每次開新 terminal 或重新 ssh 進機器，shell session 重起、env vars 清空，要再 `source` 一次。可以加進 `~/.zshrc` / `~/.bashrc`（不建議，會污染所有 session），或習慣每次進 repo 先 `source scripts/gcp/.env`。

**Tip — 一條龍**：
```bash
source scripts/gcp/.env && \
  ./scripts/gcp/03-build-push.sh && \
  ./scripts/gcp/04-deploy.sh
```

## 後續部署

修 code → commit → 跑 `03-build-push.sh && 04-deploy.sh`。

## 拆除

```bash
./scripts/gcp/99-teardown.sh
```

## 檔案總覽

| File | 用途 |
|---|---|
| `.env.example` | 環境變數範本（執行前 cp 為 .env 編輯） |
| `01-bootstrap.sh` | 啟用 API、建 Cloud SQL（PG18）/ GCS / SA + 7 IAM role / AR repo |
| `02-create-secrets.sh` | 把 DB user / DB password / GenAI key 存 Secret Manager |
| `03-build-push.sh` | `./gradlew bootBuildImage` + push 到 Artifact Registry |
| `04-deploy.sh` | envsubst 渲染 service.yaml + `gcloud run services replace` |
| `99-teardown.sh` | 刪除所有 skillshub 資源（保留 project） |
| `service.yaml` | Cloud Run multi-container 範本（含 sidecar、probes、resources） |
| `DEPLOYMENT.md` | 完整部署指南 |

## 關鍵設計

- **Resources**：app 1 CPU / 1Gi（per `CLOUD_RUN_MEMORY`） + cloud-sql-proxy sidecar 1 CPU / 256Mi → 整 instance 2 CPU / 1280Mi。
- **Probes**：app 走 `/actuator/health/readiness`（startup）+ `/actuator/health/liveness`（liveness）；proxy 走 9090 port `/startup` 與 `/liveness`。
- **Sidecar 啟動順序**：`run.googleapis.com/container-dependencies` annotation 確保 proxy 先 ready 主 app 才啟動。
- **IAM roles**（runtime SA `skillshub-runtime`，6 個）：`cloudsql.client` / `storage.objectAdmin` / `secretmanager.secretAccessor` / `logging.logWriter` / `monitoring.metricWriter` / `cloudtrace.agent`。
  - 注：embedding 走 Google AI Studio Gemini API（API key auth，**非** Vertex AI），故無需 `roles/aiplatform.user`。
- **Cloud SQL Auth Proxy**：釘版本 `gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.21.3`；不用 `:latest`。
