#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Cloud Run deployment (S013)
#
# 部署當前 git short SHA 對應的 image 到 Cloud Run。
# Env vars 用 `^@^` 自訂分隔符語法（§2.6 校正項），因 SPRING_PROFILES_ACTIVE
# 的值含 comma 會被預設 comma 分隔誤切。
# Secret 透過 --update-secrets 注入為 env var（不直接寫進 service config）。
# (AC-7)
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
CLOUD_RUN_SERVICE_NAME="${CLOUD_RUN_SERVICE_NAME:-skillshub}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
SHA="$(git rev-parse --short HEAD)"

IMG="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}:${SHA}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

echo "▸ Deploy ${CLOUD_RUN_SERVICE_NAME} → ${IMG}"

# --set-env-vars 用 `^@^` 自訂分隔符（gcloud 標準寫法）：
#   - `^DELIM^` 開頭告訴 gcloud 用 `DELIM` 分隔 key=value pairs
#   - 用 `@` 是因 env var 名 / 值幾乎不可能含 `@`，比 `,` / `;` 更安全
#   - 文件：https://cloud.google.com/run/docs/configuring/services/environment-variables
gcloud run deploy "${CLOUD_RUN_SERVICE_NAME}" \
  --image="${IMG}" \
  --region="${GCP_REGION}" \
  --service-account="${SA_EMAIL}" \
  --allow-unauthenticated \
  --port=8080 \
  --memory="${CLOUD_RUN_MEMORY:-512Mi}" \
  --cpu="${CLOUD_RUN_CPU:-1}" \
  --min-instances=0 \
  --max-instances="${CLOUD_RUN_MAX_INSTANCES:-10}" \
  --timeout=300 \
  --set-env-vars="^@^SPRING_PROFILES_ACTIVE=gcp,prod@GCP_PROJECT_ID=${GCP_PROJECT_ID}@SKILLSHUB_STORAGE_BUCKET=${GCS_BUCKET_NAME}" \
  --update-secrets="SKILLSHUB_GENAI_API_KEY=skillshub-genai-api-key:latest"

URL="$(gcloud run services describe "${CLOUD_RUN_SERVICE_NAME}" \
        --region="${GCP_REGION}" \
        --format='value(status.url)')"

echo "✓ deployed: ${URL}"
echo "  health check:  curl ${URL}/actuator/health"
echo "  skills API:    curl ${URL}/api/v1/skills"
