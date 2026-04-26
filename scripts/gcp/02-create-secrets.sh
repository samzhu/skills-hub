#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Secret Manager setup (S013)
#
# 把 SKILLSHUB_GENAI_API_KEY 存到 Secret Manager，並授權 runtime SA 讀取。
# Idempotent：若 secret 已存在則 add new version；不存在則 create（AC-5）。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${SKILLSHUB_GENAI_API_KEY:?need SKILLSHUB_GENAI_API_KEY (取得：https://aistudio.google.com/app/apikey)}"

SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
SECRET_NAME="skillshub-genai-api-key"

echo "▸ Secret: ${SECRET_NAME}"
if gcloud secrets describe "${SECRET_NAME}" &>/dev/null; then
  echo "  exists; adding new version (idempotent)"
  printf '%s' "${SKILLSHUB_GENAI_API_KEY}" \
    | gcloud secrets versions add "${SECRET_NAME}" --data-file=- >/dev/null
else
  echo "  not found; creating with first version"
  printf '%s' "${SKILLSHUB_GENAI_API_KEY}" \
    | gcloud secrets create "${SECRET_NAME}" \
        --data-file=- \
        --replication-policy=automatic
fi

echo "▸ Grant roles/secretmanager.secretAccessor to ${SA_EMAIL}"
# 對單一 secret 的 binding；不影響其他 secrets
gcloud secrets add-iam-policy-binding "${SECRET_NAME}" \
  --member="serviceAccount:${SA_EMAIL}" \
  --role=roles/secretmanager.secretAccessor \
  --quiet >/dev/null

echo "✓ secrets done"
echo "  next: ./scripts/gcp/03-build-push.sh"
