#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — GCP teardown (S013, AC-9)
#
# 刪除 01~04 創的所有 GCP 資源；GCP project 本身保留（避免誤刪）。
# 互動 yes/no 確認；嚴格匹配 "yes"，避免 typo（如 "y"、"YES"）誤觸發。
#
# 失敗的 delete 用 `|| true` 吞掉（資源可能本就不存在；不該因 partial state 卡住）。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

read -r -p "Delete ALL skillshub resources in ${GCP_PROJECT_ID}? Type 'yes' to confirm: " CONFIRM
# 嚴格比對小寫 yes，避免 'y' / 'Y' / 'YES' 等變體誤觸發
[[ "${CONFIRM}" == "yes" ]] || { echo "abort"; exit 1; }

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
FIRESTORE_DB_ID="${FIRESTORE_DB_ID:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
CLOUD_RUN_SERVICE_NAME="${CLOUD_RUN_SERVICE_NAME:-skillshub}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

echo "▸ Cloud Run service: ${CLOUD_RUN_SERVICE_NAME}"
gcloud run services delete "${CLOUD_RUN_SERVICE_NAME}" \
  --region="${GCP_REGION}" --quiet || true

echo "▸ Artifact Registry repo: ${AR_REPO_NAME}"
gcloud artifacts repositories delete "${AR_REPO_NAME}" \
  --location="${GCP_REGION}" --quiet || true

echo "▸ GCS bucket: gs://${GCS_BUCKET_NAME}"
# --recursive 順便清掉 bucket 內容（否則非空 bucket 不能刪）
gcloud storage rm --recursive "gs://${GCS_BUCKET_NAME}" --quiet || true

echo "▸ Firestore database: ${FIRESTORE_DB_ID}"
gcloud firestore databases delete --database="${FIRESTORE_DB_ID}" --quiet || true

echo "▸ Secret: skillshub-genai-api-key"
gcloud secrets delete skillshub-genai-api-key --quiet || true

echo "▸ Service Account: ${SA_EMAIL}"
gcloud iam service-accounts delete "${SA_EMAIL}" --quiet || true

echo "✓ teardown done"
echo "  GCP project ${GCP_PROJECT_ID} 保留（手動 gcloud projects delete 才會刪）"
