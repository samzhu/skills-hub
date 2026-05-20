#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — PR Verification Runner
#
# Usage:
#   ./scripts/verify-pr.sh
#
# Runs the fast local gate for a spec/task:
#   V01 backend tests + JaCoCo report
#   V02 backend LINE coverage display
#   V03 backend JaCoCo 80% gate
#   V04 frontend tests
#   V05 frontend lint + typecheck
#   V06 frontend coverage gate
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/verify-common.sh
source "${SCRIPT_DIR}/verify-common.sh"

[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && { sed -n '3,/^# -----------------------------------------------------------------------------/p' "$0"; exit 0; }

init_verify "verify-pr.log"

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

finalize_verify
