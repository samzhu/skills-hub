#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Build OCI image + push to Artifact Registry (S013)
#
# 使用 Spring Boot bootBuildImage 產 OCI image（Paketo buildpack），
# tag 雙標：git short SHA（rollback 用）+ latest（dev 隨手部署用）。
# 兩個 tag 都 push 到 Artifact Registry。(AC-6)
# -----------------------------------------------------------------------------
set -euo pipefail

: "${GCP_PROJECT_ID:?need GCP_PROJECT_ID}"
: "${GCP_REGION:?need GCP_REGION}"

AR_REPO_NAME="${AR_REPO_NAME:-skillshub}"
IMAGE_NAME="${IMAGE_NAME:-skillshub}"
SHA="$(git rev-parse --short HEAD)"
IMG_BASE="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${AR_REPO_NAME}/${IMAGE_NAME}"

echo "▸ Configure docker auth for ${GCP_REGION}-docker.pkg.dev"
# 每次跑 idempotent；gcloud 會把 credHelpers entry 寫進 ~/.docker/config.json
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

echo "▸ Build OCI image: ${IMG_BASE}:${SHA}"
# 切到 backend/ 跑 gradle，因 build.gradle.kts 在 backend 子目錄
( cd backend && ./gradlew bootBuildImage --imageName="${IMG_BASE}:${SHA}" )

echo "▸ Tag latest"
docker tag "${IMG_BASE}:${SHA}" "${IMG_BASE}:latest"

echo "▸ Push ${IMG_BASE}:${SHA}"
docker push "${IMG_BASE}:${SHA}"

echo "▸ Push ${IMG_BASE}:latest"
docker push "${IMG_BASE}:latest"

echo "✓ build-push done"
echo "  image: ${IMG_BASE}:${SHA}"
echo "  next:  ./scripts/gcp/04-deploy.sh"
