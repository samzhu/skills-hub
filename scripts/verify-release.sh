#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Release Verification Runner
#
# Usage:
#   ./scripts/verify-release.sh
#   SKIP_NATIVE=1 ./scripts/verify-release.sh
#
# Runs the local release gate:
#   verify-pr equivalent V01-V06
#   V07  E2E Smoke
#   V07b E2E Full app browser specs
#   V07c E2E Fixture project
#   V07d E2E Risk tags when present
#   V08a AOT processing
#   V08b native bootBuildImage
#   V09  secret-like value check
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/verify-common.sh
source "${SCRIPT_DIR}/verify-common.sh"

[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && { sed -n '3,/^# -----------------------------------------------------------------------------/p' "$0"; exit 0; }

init_verify "verify-release.log"

run_critical "V01" "cd backend && ./gradlew clean test jacocoTestReport" \
  "(cd '${REPO_ROOT}/backend' && ./gradlew clean test jacocoTestReport)"

record_jacoco_line_coverage
run_jacoco_gate

run_skip_if "V04" "cd frontend && npm test" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm test)"

run_skip_if "V05" "cd frontend && npm run verify" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm run verify)"

run_skip_if "V06" "cd frontend && npm test -- --coverage" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm test -- --coverage)"

load_e2e_genai_key_if_available

run_skip_if "V07" "cd e2e && npx playwright test --grep @happy-path" \
  "e2e_prerequisite_missing || ! e2e_has_tag '@happy-path'" \
  "(cd '${REPO_ROOT}/e2e' && npx playwright test --grep @happy-path)"

run_skip_if "V07b" "cd e2e && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --project=chromium --grep-invert @bootstrap" \
  "e2e_prerequisite_missing || [ -z \"\$(find '${REPO_ROOT}/e2e/tests' -name '*.spec.ts' -print -quit 2>/dev/null)\" ]" \
  "(cd '${REPO_ROOT}/e2e' && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --project=chromium --grep-invert @bootstrap)"

run_skip_if "V07c" "cd e2e && npx playwright test --project=\"fixture unit\"" \
  "e2e_prerequisite_missing || [ -z \"\$(find '${REPO_ROOT}/e2e/fixtures' -name '*.spec.ts' -print -quit 2>/dev/null)\" ]" \
  "(cd '${REPO_ROOT}/e2e' && npx playwright test --project='fixture unit')"

run_e2e_risk_gate

run_critical "V08a" "cd backend && ./gradlew processAot" \
  "(cd '${REPO_ROOT}/backend' && SKILLSHUB_GENAI_API_KEY=\"\${SKILLSHUB_AOT_GENAI_API_KEY:-aot-placeholder-key}\" ./gradlew processAot)"

run_native_image_gate
run_secret_leak_check

finalize_verify
