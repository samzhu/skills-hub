#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_NAME="${SKILLSHUB_E2E_IMAGE:-skillshub:e2e-local}"
STATIC_DIR="${ROOT_DIR}/backend/src/main/resources/static"
BACKUP_DIR="$(mktemp -d)"
HAD_STATIC=0
RESTORED=0

restore_static() {
  if [[ "${RESTORED}" == "1" ]]; then
    return
  fi
  rm -rf "${STATIC_DIR}"
  if [[ "${HAD_STATIC}" == "1" ]]; then
    mkdir -p "$(dirname "${STATIC_DIR}")"
    cp -a "${BACKUP_DIR}/static" "${STATIC_DIR}"
  fi
  rm -rf "${BACKUP_DIR}"
  RESTORED=1
}

trap restore_static EXIT

if [[ -d "${STATIC_DIR}" ]]; then
  cp -a "${STATIC_DIR}" "${BACKUP_DIR}/static"
  HAD_STATIC=1
fi

(
  cd "${ROOT_DIR}/frontend"
  npm ci
  npm run verify
  npm run build
)

rm -rf "${STATIC_DIR}"
mkdir -p "${STATIC_DIR}"
cp -a "${ROOT_DIR}/frontend/dist/." "${STATIC_DIR}/"

(
  cd "${ROOT_DIR}/backend"
  SKILLSHUB_QUALITY_JUDGE_ENABLED=false \
  SKILLSHUB_SCANNER_ENGINES_LLM_ENABLED=false \
  ./gradlew --no-daemon -x test bootBuildImage \
    --imageName="${IMAGE_NAME}" \
    -Pspring.profiles.active=aot,local
)

restore_static

if git -C "${ROOT_DIR}" status --short -- backend/src/main/resources/static | grep -q .; then
  git -C "${ROOT_DIR}" status --short -- backend/src/main/resources/static
  echo "backend/src/main/resources/static is dirty after image build" >&2
  exit 1
fi

echo "Built ${IMAGE_NAME}"
