# Skills Hub — Cloud Build 包版指南

把 frontend + backend 打包成一個 OCI image，推到 Artifact Registry。**只負責 build + push**；deploy 到 Cloud Run 走 [DEPLOYMENT.md](./DEPLOYMENT.md) Step 11+ 的手動流程。

對應 spec：S132（`docs/grimo/specs/2026-05-04-S132-ci-cloud-build.md`）。

```
本機 cwd（repo root）           Cloud Build（GCP 端 5-8 分鐘）
─────────────────────           ─────────────────────────────────────
gcloud builds submit ───────▶   Step 1 node:22       npm ci + npm run build
  --config=cloudbuild.yaml      Step 2 alpine        cp dist → backend/static
  --substitutions=...           Step 3 jdk25         ./gradlew bootBuildImage
                                images:              push <AR>:${_TAG} 到 AR
                                                                  │
                                                                  ▼
                                Artifact Registry: <region>-docker.pkg.dev/<proj>/skillshub/skillshub:<TAG>
```

---

## 前置

```bash
# Auth（只需做一次；token 過期後 re-login）
gcloud auth login
gcloud auth application-default login

# 必填：兩個 export — 後續所有指令吃這兩個變數，不依賴 gcloud config 的 active project
export GCP_PROJECT_ID=<你的 GCP project ID>   # e.g. xxxxx
export GCP_REGION=asia-east1                  # 想換 region 改這裡

# 可選：AR repo / image 名（預設都是 skillshub；要 fork 換名才改）
export AR_REPO_NAME=skillshub
export IMAGE_NAME=skillshub
```

別人要部署時，**只要改 `GCP_PROJECT_ID` + `GCP_REGION` 兩個 export**，下面所有指令完全不變。`AR_REPO_NAME` / `IMAGE_NAME` 是 `.env.example` 既有變數，預設就 `skillshub`。

---

## 一次性 setup（每個 GCP project 第一次包版前跑一次）

```bash
# 1. 啟用 API
gcloud services enable cloudbuild.googleapis.com artifactregistry.googleapis.com \
  --project=$GCP_PROJECT_ID

# 2. 建 AR repo（|| true：idempotent，已存在會回 ALREADY_EXISTS，第二次跑不會炸）
gcloud artifacts repositories create $AR_REPO_NAME \
  --repository-format=docker \
  --location=$GCP_REGION \
  --description="Skills Hub OCI images" \
  --project=$GCP_PROJECT_ID || true

# 3. 給 Cloud Build runtime SA 補必要權限
#    GCP 2024-04 起，新建 project 的 default Cloud Build runtime 從 legacy
#    `<num>@cloudbuild.gserviceaccount.com` 換成 **Compute Engine default SA**
#    `<num>-compute@developer.gserviceaccount.com`。我們對 Compute SA 補兩個 role：
#      - cloudbuild.builds.builder: source bucket 讀取 + 寫 build log（沒這個會炸 storage.objects.get）
#      - artifactregistry.writer:    push image 到 AR（沒這個會炸 PERMISSION_DENIED）
#    --condition=None：project 既有 conditional binding 時 gcloud 會跳互動 prompt，
#    顯式宣告「無條件」避開。
PROJECT_NUMBER=$(gcloud projects describe $GCP_PROJECT_ID --format='value(projectNumber)')
COMPUTE_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

gcloud projects add-iam-policy-binding $GCP_PROJECT_ID \
  --member="serviceAccount:${COMPUTE_SA}" \
  --role="roles/cloudbuild.builds.builder" \
  --condition=None

gcloud projects add-iam-policy-binding $GCP_PROJECT_ID \
  --member="serviceAccount:${COMPUTE_SA}" \
  --role="roles/artifactregistry.writer" \
  --condition=None
```

---

## 包版（每次跑一條）

```bash
cd <repo root>          # cloudbuild.yaml 在 repo root；submit 會 tarball 當前 cwd

gcloud builds submit \
  --config=cloudbuild.yaml \
  --project=$GCP_PROJECT_ID \
  --substitutions=_REGION=$GCP_REGION,_AR_REPO=$AR_REPO_NAME,_IMAGE_NAME=$IMAGE_NAME,_TAG=$(date -u +%Y%m%d-%H%M%S)
```

> 用預設 `AR_REPO_NAME=skillshub` + `IMAGE_NAME=skillshub` 的話，`_AR_REPO` 和 `_IMAGE_NAME` 兩個 substitution 可以省略不傳（cloudbuild.yaml 內已有同預設值）。

**Substitutions 解釋**：

| 名稱 | 來源 | 預設 | 用途 |
|---|---|---|---|
| `$PROJECT_ID` | Cloud Build built-in（active project） | — | 組 AR image path |
| `_REGION` | 你 export | `asia-east1` | AR repo region；組 image path |
| `_TAG` | 你帶 timestamp / 版號 | `latest` | image tag（流水號 / release 版） |
| `_AR_REPO` | cloudbuild.yaml 預設 | `skillshub` | AR repo 名 |
| `_IMAGE_NAME` | cloudbuild.yaml 預設 | `skillshub` | image 名 |

**Tag 慣例**：

```bash
# 流水號（日常包版；UTC timestamp）
_TAG=$(date -u +%Y%m%d-%H%M%S)

# Release 版號
_TAG=v1.2.3

# Latest（測試覆寫，不建議生產用）
_TAG=latest   # 不傳 _TAG 時的預設值
```

---

## 驗證

```bash
# 看最近一次 build 結果
gcloud builds list --project=$GCP_PROJECT_ID --limit=1

# 列剛推上去的 image
gcloud artifacts docker images list \
  $GCP_REGION-docker.pkg.dev/$GCP_PROJECT_ID/$AR_REPO_NAME/$IMAGE_NAME \
  --project=$GCP_PROJECT_ID \
  --filter="tags:<你的 _TAG>"
```

成功 = build SUCCESS + image 出現在 AR。

---

## 後續 deploy

```bash
# 接 DEPLOYMENT.md Step 11+；用剛 build 出來的 _TAG 當 deploy target
TAG=<剛包好的 tag> ./scripts/gcp/04-deploy.sh
```

---

## 失敗 Troubleshooting

| 症狀 | 原因 | 修法 |
|---|---|---|
| Step 1 `npm ci` fail：`ENOTSUP` / lockfile mismatch | `package-lock.json` 與 `package.json` 不同步 | 本機跑 `cd frontend && npm install`；commit 新 lockfile；再 submit |
| Step 3 bootBuildImage `Cannot connect to Docker daemon` | Cloud Build step 沒拿到 Docker socket | cloudbuild.yaml step 3 加顯式 `volumes: [{name: docker-sock, path: /var/run/docker.sock}]`；再 submit |
| `INVALID_ARGUMENT: could not resolve source: ... storage.objects.get denied` | Compute SA 沒 `roles/cloudbuild.builds.builder`（GCP 2024-04 起新 project 的 default runtime SA） | 跑「一次性 setup」第 3 步 |
| `images:` push 階段 `PERMISSION_DENIED` | Compute SA 沒 `roles/artifactregistry.writer` | 跑「一次性 setup」第 3 步 |
| `images:` push 階段 `Repository not found` | AR repo 不存在或 region 不對 | 跑「一次性 setup」第 2 步；確認 `_REGION` 對齊 repo region |
| Build timeout 25 分鐘炸 | npm install 太慢 / image build 太慢 | cloudbuild.yaml `timeout: 1500s` 提到 `2400s`；或評估加 build cache（GCS tarball / Gradle GCS plugin）|

---

## 與本機 `03-build-push.sh` 的關係

| 項目 | 本機 `03-build-push.sh` | Cloud Build（本指南） |
|---|---|---|
| 跑在哪 | 開發者本機 Docker daemon | GCP Cloud Build（拿 cloud 端的 daemon） |
| 需要本機 | Java 25 / Node / Docker / gcloud | 只要 gcloud（其他在 cloud 跑） |
| Tag 預設 | git short SHA | UTC timestamp（流水號） |
| 推 `:latest` | 是（雙 tag） | 否（單 tag；要 latest 就傳 `_TAG=latest`） |
| 用途 | 個人開發隨手 push image | 包版 / release / 沒裝 Java 的機器也能跑 |

兩條路徑寫到 AR 的是同一個 image path（`<region>-docker.pkg.dev/<proj>/skillshub/skillshub`），04-deploy.sh 對任一 tag 都能 deploy。
