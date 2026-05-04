#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Secret Manager setup
#
# 建立 / 更新 2 個 secret 並授權 runtime SA 讀取（純機敏值才進 Secret Manager）：
#   skillshub-db-password    ← ${DB_PASSWORD}
#   skillshub-genai-api-key  ← ${SKILLSHUB_GENAI_API_KEY}
#
# DB user 不是機敏，由 service.yaml 的 skillshub.db.user env var 注入（04-deploy.sh envsubst）。
#
# 取用機制：app 啟動時走 spring-cloud-gcp-starter-secretmanager 的
# ${sm@<secret-id>} placeholder（在 application-gcp.yaml）— 不再用 Cloud Run 的
# valueFrom.secretKeyRef block。runtime SA 透過 ADC 自動 auth，無需額外設定。
#
# Idempotent：secret 已存在則 add new version；不存在則 create + add first version。
# 02 跑完後可清空 .env 內 DB_PASSWORD / SKILLSHUB_GENAI_API_KEY（已落 Secret Manager）。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${DB_PASSWORD:?need DB_PASSWORD}"
: "${SKILLSHUB_GENAI_API_KEY:?need SKILLSHUB_GENAI_API_KEY (取得：https://aistudio.google.com/app/apikey)}"

SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"

create_or_update_secret() {
  local name="$1" value="$2"
  if gcloud secrets describe "${name}" &>/dev/null; then
    echo "  ${name}: exists, adding new version"
    printf '%s' "${value}" | gcloud secrets versions add "${name}" --data-file=- >/dev/null
  else
    echo "  ${name}: creating"
    printf '%s' "${value}" | gcloud secrets create "${name}" \
      --data-file=- --replication-policy=automatic >/dev/null
  fi
  gcloud secrets add-iam-policy-binding "${name}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role=roles/secretmanager.secretAccessor \
    --quiet >/dev/null
}

echo "▸ Secrets → grant accessor to ${SA_EMAIL}"
create_or_update_secret skillshub-db-password    "${DB_PASSWORD}"
create_or_update_secret skillshub-genai-api-key  "${SKILLSHUB_GENAI_API_KEY}"

echo "✓ secrets done"
echo "  next: ./scripts/gcp/03-build-push.sh"
