#!/usr/bin/env bash
# ensure-latest.sh — install or upgrade @playwright/test to the latest
# version published on the npm registry, then install browser binaries
# using the lightweight headless shell variant (--only-shell) which
# avoids the multi-hundred-MB full Chromium download.
#
# Usage:
#   ensure-latest.sh --e2e-dir <path> [--upgrade]
#
# Behaviour matrix (per intent (b) — opt-in upgrade):
#   first install (no node_modules)        → install latest
#   already installed, no --upgrade flag   → keep current version, exit 0
#   already installed, --upgrade flag      → install latest if newer
#
# Exit codes:
#   0  success (installed, upgraded, or already latest)
#   2  argument error
#   3  network / npm registry unreachable
#   4  package install failure
#   5  browser install failure
#
# Diagnostics print to stderr; success summary prints to stdout as JSON.

set -euo pipefail

E2E_DIR=""
UPGRADE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --e2e-dir) E2E_DIR="$2"; shift 2 ;;
    --upgrade) UPGRADE=1; shift ;;
    -h|--help)
      sed -n '2,17p' "$0" >&2; exit 0 ;;
    *) echo "ARG ERROR: unknown flag '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$E2E_DIR" ] || { echo "ARG ERROR: --e2e-dir required" >&2; exit 2; }
[ -d "$E2E_DIR" ] || { echo "ARG ERROR: e2e dir '$E2E_DIR' does not exist" >&2; exit 2; }

cd "$E2E_DIR"

# Idempotent .gitignore — prevents committing artefacts. Marker line lets
# subsequent runs detect that this block already exists. Names follow
# `npm init playwright@latest` (test-results, playwright-report,
# blob-report, playwright/.cache) plus the auth path documented at
# playwright.dev/docs/auth (playwright/.auth) and the JSON results
# folder produced by this skill's reporter config.
GITIGNORE_MARKER="# playwright-expert managed (do not edit between markers)"
GITIGNORE_BODY="$GITIGNORE_MARKER
node_modules/
test-results/
playwright-report/
blob-report/
playwright/.cache/
playwright/.auth/
results/
# end playwright-expert managed"

if [ ! -f .gitignore ]; then
  printf '%s\n' "$GITIGNORE_BODY" > .gitignore
elif ! grep -qF "$GITIGNORE_MARKER" .gitignore; then
  printf '\n%s\n' "$GITIGNORE_BODY" >> .gitignore
fi

# Probe latest from npm registry. Fail fast if offline.
LATEST="$(npm view @playwright/test version 2>/dev/null || true)"
if [ -z "$LATEST" ]; then
  echo "NETWORK ERROR: cannot reach npm registry to resolve @playwright/test latest" >&2
  exit 3
fi

# Probe currently installed version (empty if not installed yet).
LOCAL=""
if [ -f node_modules/@playwright/test/package.json ]; then
  LOCAL="$(node -p "require('./node_modules/@playwright/test/package.json').version" 2>/dev/null || true)"
fi

ACTION="noop"
if [ -z "$LOCAL" ]; then
  ACTION="install"
elif [ "$UPGRADE" -eq 1 ] && [ "$LOCAL" != "$LATEST" ]; then
  ACTION="upgrade"
fi

if [ "$ACTION" != "noop" ]; then
  if ! npm install --save-dev "@playwright/test@$LATEST" --no-audit --no-fund >&2; then
    echo "INSTALL ERROR: npm install @playwright/test@$LATEST failed" >&2
    exit 4
  fi
  # --only-shell installs chromium_headless_shell variant only (~80 MB)
  # instead of full Chromium (~300 MB) plus headless shell. Default
  # chromium project resolves to headless shell binary in v1.49+.
  if ! npx playwright install --with-deps --only-shell chromium >&2; then
    echo "BROWSER ERROR: npx playwright install --only-shell chromium failed" >&2
    exit 5
  fi
fi

INSTALLED="$(node -p "require('./node_modules/@playwright/test/package.json').version" 2>/dev/null || echo "$LATEST")"

printf '{"action":"%s","installed":"%s","latest":"%s","upgrade_flag":%s}\n' \
  "$ACTION" "$INSTALLED" "$LATEST" "$([ "$UPGRADE" -eq 1 ] && echo true || echo false)"
