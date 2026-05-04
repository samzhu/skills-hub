#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Cloud Run deployment
#
# 流程：
#   1. envsubst 把 scripts/gcp/service.yaml 內 ${...} placeholder 渲染成
#      scripts/gcp/service.rendered.yaml（注入當前 git SHA / SA / instance / 等）
#   2. gcloud run services replace 部署 multi-container service
#      （app + cloud-sql-proxy sidecar，含 container-dependencies + probes）
#   3. 授權 allUsers → roles/run.invoker（公開 service URL）
#
# `services replace` 是宣告式更新：service.yaml 是 single source of truth；
# 任何 gcloud-CLI 改動會被下次 replace 蓋掉。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
CLOUD_RUN_SERVICE_NAME="${CLOUD_RUN_SERVICE_NAME:-skillshub}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-skillshub-runtime}"
GCS_BUCKET_NAME="${GCS_BUCKET_NAME:-${GCP_PROJECT_ID}-skillshub-pkg}"
CLOUDSQL_INSTANCE_NAME="${CLOUDSQL_INSTANCE_NAME:-skillshub-db}"
DB_NAME="${DB_NAME:-skillshub}"
DB_USER="${DB_USER:-skillshub_app}"
CLOUD_RUN_CPU="${CLOUD_RUN_CPU:-1}"
CLOUD_RUN_MEMORY="${CLOUD_RUN_MEMORY:-1Gi}"
CLOUD_RUN_MAX_INSTANCES="${CLOUD_RUN_MAX_INSTANCES:-10}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-lab,gcp}"

# envsubst 是 GNU gettext 的 cli；macOS 預設沒有，需 `brew install gettext`
if ! command -v envsubst &>/dev/null; then
  echo "ERROR: envsubst not found"
  echo "  install (macOS): brew install gettext"
  echo "  install (Linux): apt-get install gettext-base / dnf install gettext"
  exit 1
fi

# TAG 對齊 03-build-push.sh：未指定則用 git short SHA。
# 指定特定版本部署：TAG=v1.2.3 ./scripts/gcp/04-deploy.sh
TAG="${TAG:-$(git rev-parse --short HEAD)}"

# 全部 export 給 envsubst 用
# 注：service.yaml 不引用 ${GCP_PROJECT_ID}（project-id 由 Cloud Run metadata server 自動提供給 SDK）；
# GCP_PROJECT_ID 仍在上方用來組 IMG / SA_EMAIL / CLOUDSQL_INSTANCE_CONN
export GCP_REGION CLOUD_RUN_SERVICE_NAME GCS_BUCKET_NAME DB_NAME DB_USER
export CLOUD_RUN_CPU CLOUD_RUN_MEMORY CLOUD_RUN_MAX_INSTANCES SPRING_PROFILES_ACTIVE
export IMG="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}:${TAG}"
export SA_EMAIL="${SERVICE_ACCOUNT_NAME}@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
export CLOUDSQL_INSTANCE_CONN="${GCP_PROJECT_ID}:${GCP_REGION}:${CLOUDSQL_INSTANCE_NAME}"

TMPL="scripts/gcp/service.yaml"
RENDERED="scripts/gcp/service.rendered.yaml"

echo "▸ Render ${TMPL} → ${RENDERED}"
# Whitelist envsubst：只替換我們 own 的變數；service.yaml 內 ${sm@<secret-id>}
# 等 spring-cloud-gcp 語法保留不被 envsubst 吃掉，交給 Spring runtime 遞迴 resolve。
envsubst \
  '$GCP_REGION $CLOUD_RUN_SERVICE_NAME $CLOUD_RUN_CPU $CLOUD_RUN_MEMORY $CLOUD_RUN_MAX_INSTANCES $SPRING_PROFILES_ACTIVE $IMG $SA_EMAIL $CLOUDSQL_INSTANCE_CONN $DB_NAME $DB_USER $GCS_BUCKET_NAME' \
  < "${TMPL}" > "${RENDERED}"

echo "▸ Deploy ${CLOUD_RUN_SERVICE_NAME} → ${IMG}"
gcloud run services replace "${RENDERED}" \
  --region="${GCP_REGION}" \
  --quiet

echo "▸ Allow public access (allUsers → roles/run.invoker)"
gcloud run services add-iam-policy-binding "${CLOUD_RUN_SERVICE_NAME}" \
  --member=allUsers \
  --role=roles/run.invoker \
  --region="${GCP_REGION}" \
  --quiet >/dev/null

URL="$(gcloud run services describe "${CLOUD_RUN_SERVICE_NAME}" \
        --region="${GCP_REGION}" \
        --format='value(status.url)')"

echo "✓ deployed: ${URL}"
echo "  health:    curl ${URL}/actuator/health"
echo "  readiness: curl ${URL}/actuator/health/readiness"
echo "  liveness:  curl ${URL}/actuator/health/liveness"
echo "  skills:    curl ${URL}/api/v1/skills"
