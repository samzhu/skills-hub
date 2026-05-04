#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — GCP teardown
#
# 刪除順序（dependency-aware）：
#   Cloud Run service → Cloud SQL instance → Artifact Registry repo
#   → GCS bucket → 3 secrets → Service Account
#
# 互動 yes/no 確認；嚴格匹配 "yes"，避免 typo（'y'/'Y'/'YES'）誤觸發。
# 失敗的 delete 用 `|| true` 吞掉（資源可能本就不存在；不該因 partial state 卡住）。
# Project 本身不刪；要連 project 一起砍：gcloud projects delete ${GCP_PROJECT_ID}
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

read -r -p "Delete ALL skillshub resources in ${GCP_PROJECT_ID}? Type 'yes' to confirm: " CONFIRM
[[ "${CONFIRM}" == "yes" ]] || { echo "abort"; exit 1; }

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
CLOUDSQL_INSTANCE_NAME="${CLOUDSQL_INSTANCE_NAME:-skillshub-db}"
CLOUD_RUN_SERVICE_NAME="${CLOUD_RUN_SERVICE_NAME:-skillshub}"

echo "▸ Cloud Run service: ${CLOUD_RUN_SERVICE_NAME}"
gcloud run services delete "${CLOUD_RUN_SERVICE_NAME}" \
  --region="${GCP_REGION}" --quiet || true

echo "▸ Cloud SQL instance: ${CLOUDSQL_INSTANCE_NAME}"
gcloud sql instances delete "${CLOUDSQL_INSTANCE_NAME}" --quiet || true

echo "▸ Artifact Registry repo: ${AR_REPO_NAME}"
gcloud artifacts repositories delete "${AR_REPO_NAME}" \
  --location="${GCP_REGION}" --quiet || true

echo "▸ GCS bucket: gs://${GCS_BUCKET_NAME}"
# --recursive 順便清掉 bucket 內容（否則非空 bucket 不能刪）
gcloud storage rm --recursive "gs://${GCS_BUCKET_NAME}" --quiet || true

echo "▸ Secrets (2)"
for name in skillshub-db-password skillshub-genai-api-key; do
  gcloud secrets delete "${name}" --quiet || true
done

echo "▸ Service Account: ${SA_EMAIL}"
gcloud iam service-accounts delete "${SA_EMAIL}" --quiet || true

echo "✓ teardown done"
echo "  GCP project ${GCP_PROJECT_ID} 保留（手動 gcloud projects delete 才會刪）"
