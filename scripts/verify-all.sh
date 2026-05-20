#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Compatibility Verification Runner
#
# Usage:
#   ./scripts/verify-all.sh
#   SKIP_NATIVE=1 ./scripts/verify-all.sh
#
# This legacy entry point now runs the release gate while preserving the
# historical log path `verify-all.log`.
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && { sed -n '3,/^# -----------------------------------------------------------------------------/p' "$0"; exit 0; }

VERIFY_LOG="${VERIFY_LOG:-$(cd "${SCRIPT_DIR}/.." && pwd)/verify-all.log}" \
  "${SCRIPT_DIR}/verify-release.sh" "$@"
