# Skills Hub — GCP 部署腳本（S013）

把 Skills Hub backend 從 source code 一鍵部署到 GCP Cloud Run，包含 Firestore Enterprise（MongoDB compat）、GCS、Secret Manager、Service Account 與最小 IAM 權限。

## 前置

- `gcloud auth login` 與 `gcloud auth application-default login` 已登入
- 已建立空 GCP project（`gcloud projects create my-skillshub-prod`）；billing account 已啟用
- 本機已裝 Java 25、Docker、Git
- 取得 Vertex AI Gemini API key：<https://aistudio.google.com/app/apikey>

## 三步啟動

```bash
# 1. 複製範本並編輯填入 3 個必填值
cp scripts/gcp/.env.example scripts/gcp/.env
$EDITOR scripts/gcp/.env

# 2. 載入環境變數
source scripts/gcp/.env

# 3. 依序執行（每個腳本皆 idempotent，跑兩次不報錯）
./scripts/gcp/01-bootstrap.sh        # ~3 分鐘：6 API + AR + Firestore + GCS + SA + 7 IAM
./scripts/gcp/02-create-secrets.sh   # 把 GENAI API key 存進 Secret Manager
./scripts/gcp/03-build-push.sh       # ~5 分鐘（首次）：bootBuildImage + 雙 tag push
./scripts/gcp/04-deploy.sh           # 部署到 Cloud Run，輸出 service URL
```

成功後輸出形如 `https://skillshub-xxx-uc.a.run.app` 的 URL，瀏覽器即可開。

## 後續部署

修 code → commit → 跑 `03-build-push.sh && 04-deploy.sh` 即可（不需重跑 bootstrap / secrets）。

## Image tag 策略

每次 `03-build-push.sh` 會 push 兩個 tag：

- `<region>-docker.pkg.dev/<project>/skillshub/skillshub:<git-short-sha>` — 對應特定 commit，rollback 用
- `<region>-docker.pkg.dev/<project>/skillshub/skillshub:latest` — 隨時最新，方便 dev

`04-deploy.sh` 部署的是 `<git-short-sha>` tag（精確版本），確認當下部署對應哪個 commit。

## Cost guard

- Cloud Run `--min-instances=0`：閒置不收錢
- Cloud Run `--max-instances=10`：防 runaway 流量爆量
- Cloud Run `--memory=512Mi --cpu=1`：起步配額，按實際用量在 `.env` 調
- Firestore Enterprise / Vertex AI / GCS：用量計費
- Secret Manager：第一個 secret 免費

## 環境變數覆寫

3 個必填皆 export 於 `.env`；7 個可選變數在 `.env.example` 內已標 default 值。若需自訂（例如多個 region 部署），編輯 `.env` 再 `source` 即可。

LAB 模式（S012）：若想在 GCP 上跑 LAB 模式（OAuth 關閉），可手動編輯 `04-deploy.sh` 在 `--set-env-vars` 內加 `@SKILLSHUB_SECURITY_OAUTH_ENABLED=false`。

## 拆除

```bash
./scripts/gcp/99-teardown.sh
# 出現 prompt：Delete ALL skillshub resources in <project>? Type 'yes' to confirm:
# 輸入 'yes' 才會繼續
```

刪除順序：Cloud Run service → AR repo → GCS bucket → Firestore DB → Secret → Service Account。GCP project 本身**不刪**（手動 `gcloud projects delete` 才會刪）。

## Troubleshooting

| 症狀 | 可能原因 | 解法 |
|---|---|---|
| `permission denied` 跑腳本 | 檔案 mode 不對 | `chmod +x scripts/gcp/*.sh` |
| `gcloud: command not found` | gcloud SDK 未裝 | <https://cloud.google.com/sdk/docs/install> |
| Firestore create 失敗 `already exists` | 之前已建過 | 腳本本就 idempotent；忽略即可 |
| `bootBuildImage` OOM | 本機 Docker memory 不夠 | Docker Desktop → Resources → 調至 ≥4GB |
| Cloud Run 部署後 503 | 容器 startup 太慢 | `04-deploy.sh` 的 `--timeout=300`；若仍不夠加 `--cpu-boost` |

## 變數對照表

| Script | 讀取的環境變數 |
|---|---|
| `01-bootstrap.sh` | GCP_PROJECT_ID, GCP_REGION, AR_REPO_NAME, FIRESTORE_DB_ID, GCS_BUCKET_NAME, SERVICE_ACCOUNT_NAME |
| `02-create-secrets.sh` | GCP_PROJECT_ID, SKILLSHUB_GENAI_API_KEY, SERVICE_ACCOUNT_NAME |
| `03-build-push.sh` | GCP_PROJECT_ID, GCP_REGION, AR_REPO_NAME, IMAGE_NAME |
| `04-deploy.sh` | GCP_PROJECT_ID, GCP_REGION, AR_REPO_NAME, IMAGE_NAME, SERVICE_ACCOUNT_NAME, CLOUD_RUN_SERVICE_NAME, GCS_BUCKET_NAME, CLOUD_RUN_{MEMORY,CPU,MAX_INSTANCES} |
| `99-teardown.sh` | GCP_PROJECT_ID, GCP_REGION, 所有可選變數 |

## 參考

- [Cloud Run — Configure secrets](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Cloud Run — Environment variables](https://cloud.google.com/run/docs/configuring/services/environment-variables)
- [Firestore — Create databases (MongoDB compat)](https://cloud.google.com/firestore/mongodb-compatibility/docs/create-databases)
- [Artifact Registry integration with Cloud Run](https://cloud.google.com/artifact-registry/docs/integrate-cloud-run)
- [Spring Boot Gradle bootBuildImage](https://docs.spring.io/spring-boot/docs/current/gradle-plugin/reference/htmlsingle/)
