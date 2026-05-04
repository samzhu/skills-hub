#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Build OCI image + push to Artifact Registry
#
# `./gradlew bootBuildImage` 走 Paketo buildpack 產 OCI image。
# Tag 雙標：git short SHA（rollback 用）+ latest（dev 隨手部署用）。
# 兩個 tag 都 push 到 Artifact Registry。
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID; source scripts/gcp/.env first}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
# TAG 可從環境變數覆蓋；未指定則用 git short SHA：
#   TAG=v1.2.3 ./scripts/gcp/03-build-push.sh   # release 版號
#   ./scripts/gcp/03-build-push.sh              # → tag = git short SHA
# 注意：之後跑 04-deploy.sh 必須帶相同 TAG（或由 shell 共用 export）
TAG="${TAG:-$(git rev-parse --short HEAD)}"
IMG_BASE="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}"
IMG="${IMG_BASE}:${TAG}"

echo "▸ Build frontend (npm ci + npm run build)"
# S132：Gradle 不再 invoke npm；script 自己跑前端 build 後拷進 backend static，
# 維持與 CI cloudbuild.yaml step 1+2 同行為（同一份 dist 餵進 bootBuildImage）。
( cd frontend && npm ci && npm run build )

echo "▸ Copy frontend/dist → backend/src/main/resources/static"
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/. backend/src/main/resources/static/

echo "▸ Configure docker auth for ${GCP_REGION}-docker.pkg.dev"
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

echo "▸ Build OCI image: ${IMG}"
( cd backend && ./gradlew bootBuildImage --imageName="${IMG}" )

echo "▸ Tag latest"
docker tag "${IMG}" "${IMG_BASE}:latest"

echo "▸ Push ${IMG}"
docker push "${IMG}"

echo "▸ Push ${IMG_BASE}:latest"
docker push "${IMG_BASE}:latest"

echo "✓ build-push done"
echo "  image: ${IMG}"
echo "  next:  ./scripts/gcp/04-deploy.sh"
