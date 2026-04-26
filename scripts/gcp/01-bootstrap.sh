#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — GCP infra bootstrap (S013)
#
# Idempotent provisioning of:
#   - 7 Google Cloud APIs (run, artifactregistry, firestore, storage,
#     secretmanager, aiplatform, iam)
#   - Artifact Registry Docker repo (skillshub by default)
#   - Firestore Enterprise database with MongoDB compatibility
#   - GCS bucket (${GCP_PROJECT_ID}-skillshub-pkg by default)
#   - Service Account (skillshub-runtime) + 7 minimum-permission IAM roles
#
# Each step uses `... describe ... &>/dev/null || ... create ...` pattern,
# so re-running the script on an already-bootstrapped project is safe (AC-4).
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
FIRESTORE_DB_ID="${FIRESTORE_DB_ID:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

echo "▸ project=${GCP_PROJECT_ID} region=${GCP_REGION}"
gcloud config set project "${GCP_PROJECT_ID}" --quiet

echo "▸ Enable APIs (7 total)"
# 注：iam.googleapis.com 雖然 SA / IAM binding 操作隱含啟用，仍顯式 enable 以利快取與審計。
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  aiplatform.googleapis.com \
  iam.googleapis.com --quiet

echo "▸ Artifact Registry repo: ${AR_REPO_NAME} (${GCP_REGION})"
gcloud artifacts repositories describe "${AR_REPO_NAME}" --location="${GCP_REGION}" &>/dev/null \
  || gcloud artifacts repositories create "${AR_REPO_NAME}" \
       --repository-format=docker \
       --location="${GCP_REGION}" \
       --description="Skills Hub container images"

echo "▸ Firestore database: ${FIRESTORE_DB_ID} (Enterprise + MongoDB compatible)"
# --edition=enterprise 是啟用 MongoDB wire protocol 的必要條件
# --enable-mongodb-compatible-data-access 開啟 MONGODB-OIDC auth
gcloud firestore databases describe --database="${FIRESTORE_DB_ID}" &>/dev/null \
  || gcloud firestore databases create \
       --database="${FIRESTORE_DB_ID}" \
       --location="${GCP_REGION}" \
       --edition=enterprise \
       --enable-mongodb-compatible-data-access

echo "▸ GCS bucket: gs://${GCS_BUCKET_NAME}"
gcloud storage buckets describe "gs://${GCS_BUCKET_NAME}" &>/dev/null \
  || gcloud storage buckets create "gs://${GCS_BUCKET_NAME}" --location="${GCP_REGION}"

echo "▸ Service Account: ${SA_EMAIL}"
gcloud iam service-accounts describe "${SA_EMAIL}" &>/dev/null \
  || gcloud iam service-accounts create "${SERVICE_ACCOUNT_NAME}" \
       --display-name="Skills Hub runtime"

echo "▸ Grant 7 IAM roles to ${SA_EMAIL}"
# --condition=None 在無條件 binding 下需顯式宣告，避免 prompt
# 7 個角色皆是 service runtime 的最小必要集合（無 admin / owner 等過權）
for role in \
  roles/datastore.user \
  roles/storage.objectAdmin \
  roles/aiplatform.user \
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
