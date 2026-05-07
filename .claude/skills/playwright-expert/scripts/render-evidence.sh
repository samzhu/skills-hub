#!/usr/bin/env bash
# render-evidence.sh — translate Playwright JSON reporter output into the
# stable contract file documented in references/caller-protocol.md so
# upstream skills (verifying-quality, planning-tasks) can read evidence
# without parsing Playwright internals.
#
# Usage:
#   render-evidence.sh --e2e-dir <path> --spec-id <id> [--exit-code <n>]
#
# Reads:    <e2e-dir>/results/report.json   (Playwright JSON reporter)
# Writes:   <e2e-dir>/results/evidence.json (stable schema — see caller-protocol.md)
#
# Exit codes:
#   0  evidence written successfully
#   2  argument error
#   3  jq missing
#   4  Playwright JSON report missing or malformed
#   5  evidence write failure

set -euo pipefail

E2E_DIR=""
SPEC_ID=""
EXIT_CODE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --e2e-dir)   E2E_DIR="$2"; shift 2 ;;
    --spec-id)   SPEC_ID="$2"; shift 2 ;;
    --exit-code) EXIT_CODE="$2"; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0" >&2; exit 0 ;;
    *) echo "ARG ERROR: unknown flag '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$E2E_DIR" ] || { echo "ARG ERROR: --e2e-dir required" >&2; exit 2; }
[ -n "$SPEC_ID" ] || { echo "ARG ERROR: --spec-id required" >&2; exit 2; }

command -v jq >/dev/null 2>&1 || {
  echo "DEP ERROR: jq required (brew install jq)" >&2; exit 3;
}

REPORT="$E2E_DIR/results/report.json"
EVIDENCE="$E2E_DIR/results/evidence.json"

[ -f "$REPORT" ] || {
  echo "REPORT ERROR: '$REPORT' not found — did playwright test run with json reporter?" >&2
  exit 4
}

# Validate Playwright JSON shape (must have .suites and .stats).
jq -e '.suites and .stats' "$REPORT" >/dev/null 2>&1 || {
  echo "REPORT ERROR: '$REPORT' missing .suites or .stats — not a Playwright JSON report" >&2
  exit 4
}

RUN_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Aggregate per-AC results. Each test() title encodes "AC-N: ..." per
# spec-test-template.ts; tags include @<spec-id> for filtering.
EVIDENCE_JSON="$(jq -n \
  --arg spec_id "$SPEC_ID" \
  --arg run_at "$RUN_AT" \
  --arg exit_code "${EXIT_CODE:-0}" \
  --slurpfile r "$REPORT" '
  ($r[0]) as $report |
  {
    spec_id: $spec_id,
    run_at: $run_at,
    exit_code: ($exit_code | tonumber),
    playwright_version: ($report.config.version // "unknown"),
    stats: {
      total: ($report.stats.expected + $report.stats.unexpected + $report.stats.flaky + $report.stats.skipped),
      passed: $report.stats.expected,
      failed: $report.stats.unexpected,
      flaky: $report.stats.flaky,
      skipped: $report.stats.skipped,
      duration_ms: $report.stats.duration
    },
    tests: [
      $report.suites[]?
      | .. | objects
      | select(.specs?)
      | .specs[]?
      | {
          title: .title,
          ok: .ok,
          tags: (.tags // []),
          duration_ms: ([.tests[]?.results[]?.duration] | add // 0),
          trace_paths: [.tests[]?.results[]?.attachments[]? | select(.name == "trace") | .path],
          error: ([.tests[]?.results[]? | select(.status == "failed") | .error.message] | first // null)
        }
    ],
    report_json: "results/report.json",
    html_report: "playwright-report/index.html"
  }
')"

mkdir -p "$E2E_DIR/results"
if ! printf '%s\n' "$EVIDENCE_JSON" > "$EVIDENCE"; then
  echo "WRITE ERROR: failed to write '$EVIDENCE'" >&2
  exit 5
fi

# Echo a one-line summary to stdout for caller convenience.
jq -r '
  "spec_id=\(.spec_id) run_at=\(.run_at) " +
  "passed=\(.stats.passed) failed=\(.stats.failed) " +
  "flaky=\(.stats.flaky) skipped=\(.stats.skipped) " +
  "duration_ms=\(.stats.duration_ms)"
' "$EVIDENCE"
