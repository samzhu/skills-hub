#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — GCP infra bootstrap
#
# Idempotent provisioning of:
#   - 6 Google Cloud APIs (run, artifactregistry, sqladmin, storage,
#     secretmanager, iam)
#   - Artifact Registry Docker repo
#   - Cloud SQL instance (PostgreSQL 18, ENTERPRISE edition + db-f1-micro)
#   - Cloud SQL database + 應用 user（密碼來自 ${DB_PASSWORD}）
#   - GCS bucket（skill 套件儲存）
#   - Runtime Service Account + 6 個最小必要 IAM role
#       cloudsql.client / storage.objectAdmin / secretmanager.secretAccessor
#       logging.logWriter / monitoring.metricWriter / cloudtrace.agent
#
# 注：embedding 走 Google AI Studio Gemini API（API key auth，端點
#     generativelanguage.googleapis.com），不走 Vertex AI；故無 aiplatform API /
#     roles/aiplatform.user。若未來切 Vertex AI 才需要補。
#
# 每步走 `... describe ... &>/dev/null || ... create ...` pattern，
# 重跑安全；DB 密碼變更請手動跑 `gcloud sql users set-password`。
#
# pgvector 不需 instance flag — 直接由 Flyway V1 `CREATE EXTENSION vector`
# 在 application 層處理（per official Cloud SQL extension docs）。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
CLOUDSQL_INSTANCE_NAME="${CLOUDSQL_INSTANCE_NAME:-skillshub-db}"
CLOUDSQL_EDITION="${CLOUDSQL_EDITION:-ENTERPRISE}"
CLOUDSQL_TIER="${CLOUDSQL_TIER:-db-f1-micro}"
DB_NAME="${DB_NAME:-skillshub}"
DB_USER="${DB_USER:-skillshub_app}"

echo "▸ project=${GCP_PROJECT_ID} region=${GCP_REGION}"
gcloud config set project "${GCP_PROJECT_ID}" --quiet

# 1. Enable APIs ----------------------------------------------------------------
echo "▸ Enable APIs (6 total)"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  sqladmin.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  iam.googleapis.com --quiet

# 2. Artifact Registry ----------------------------------------------------------
echo "▸ Artifact Registry repo: ${AR_REPO_NAME} (${GCP_REGION})"
gcloud artifacts repositories describe "${AR_REPO_NAME}" --location="${GCP_REGION}" &>/dev/null \
  || gcloud artifacts repositories create "${AR_REPO_NAME}" \
       --repository-format=docker \
       --location="${GCP_REGION}" \
       --description="Skills Hub container images" \
       --quiet

# 把 ${GCP_REGION}-docker.pkg.dev 加進 Docker credHelpers（一次性）
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

# 3. Cloud SQL instance ---------------------------------------------------------
echo "▸ Cloud SQL instance: ${CLOUDSQL_INSTANCE_NAME} (PostgreSQL 18, ${CLOUDSQL_EDITION}, ${CLOUDSQL_TIER})"
if ! gcloud sql instances describe "${CLOUDSQL_INSTANCE_NAME}" &>/dev/null; then
  : "${DB_PASSWORD:?need DB_PASSWORD; 應用 DB user 密碼，與 02-create-secrets.sh 共用同一變數}"
  # LAB / 開發人員自驗環境：不啟用自動 backup（資料可重建；省儲存成本）。
  # Production cut 時加回：--backup --backup-start-time=03:00（每日 03:00 UTC backup，7 天保留）
  gcloud sql instances create "${CLOUDSQL_INSTANCE_NAME}" \
    --database-version=POSTGRES_18 \
    --edition="${CLOUDSQL_EDITION}" \
    --region="${GCP_REGION}" \
    --tier="${CLOUDSQL_TIER}" \
    --storage-type=SSD \
    --storage-size=10GB \
    --storage-auto-increase \
    --quiet
  echo "  waiting for instance state=RUNNABLE..."
  until [[ "$(gcloud sql instances describe "${CLOUDSQL_INSTANCE_NAME}" --format='value(state)')" == "RUNNABLE" ]]; do
    sleep 10
    echo -n "."
  done
  echo " ready"
fi

# 4. Cloud SQL database + 應用 user ---------------------------------------------
echo "▸ Cloud SQL database: ${DB_NAME}"
gcloud sql databases describe "${DB_NAME}" --instance="${CLOUDSQL_INSTANCE_NAME}" &>/dev/null \
  || gcloud sql databases create "${DB_NAME}" --instance="${CLOUDSQL_INSTANCE_NAME}" --quiet

echo "▸ Cloud SQL user: ${DB_USER}"
if ! gcloud sql users describe "${DB_USER}" --instance="${CLOUDSQL_INSTANCE_NAME}" &>/dev/null; then
  : "${DB_PASSWORD:?need DB_PASSWORD}"
  gcloud sql users create "${DB_USER}" \
    --instance="${CLOUDSQL_INSTANCE_NAME}" \
    --password="${DB_PASSWORD}" \
    --quiet
  echo "  user created"
else
  echo "  user exists; rotate via: gcloud sql users set-password ${DB_USER} --instance=${CLOUDSQL_INSTANCE_NAME} --password=NEW"
fi

# 5. GCS bucket -----------------------------------------------------------------
echo "▸ GCS bucket: gs://${GCS_BUCKET_NAME}"
gcloud storage buckets describe "gs://${GCS_BUCKET_NAME}" &>/dev/null \
  || gcloud storage buckets create "gs://${GCS_BUCKET_NAME}" --location="${GCP_REGION}"

# 6. Service Account + IAM roles ------------------------------------------------
echo "▸ Service Account: ${SA_EMAIL}"
gcloud iam service-accounts describe "${SA_EMAIL}" &>/dev/null \
  || gcloud iam service-accounts create "${SERVICE_ACCOUNT_NAME}" \
       --display-name="Skills Hub runtime" \
       --quiet

echo "▸ Grant 6 IAM roles to ${SA_EMAIL}"
# 角色用途註解：
#   cloudsql.client            — Auth Proxy 連 Cloud SQL Admin API 換 ephemeral cert
#   storage.objectAdmin        — 上傳 / 讀取 skill 套件至 GCS
#   secretmanager.secretAccessor — spring-cloud-gcp 拉 DB password / GenAI API key
#   logging.logWriter          — 應用 stdout/stderr 寫 Cloud Logging
#   monitoring.metricWriter    — Micrometer / Actuator metric 寫 Cloud Monitoring
#   cloudtrace.agent           — OpenTelemetry trace span 寫 Cloud Trace
# 注：Google AI Studio Gemini API 走 API key auth，不需要 GCP IAM；
#     若未來切 Vertex AI 才需要補 roles/aiplatform.user
for role in \
  roles/cloudsql.client \
  roles/storage.objectAdmin \
  roles/secretmanager.secretAccessor \
  roles/logging.logWriter \
  roles/monitoring.metricWriter \
  roles/cloudtrace.agent; do
  gcloud projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="${role}" \
    --condition=None \
    --quiet >/dev/null
done

echo "✓ bootstrap done"
echo "  next: ./scripts/gcp/02-create-secrets.sh"
