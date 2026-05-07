#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Deterministic Verification Runner (S020)
# /verifying-quality Step 0.5 protocol：
#   - CRITICAL fail → exit !=0
#   - SKIP-if-unavailable → which-detect / dir-detect graceful skip
#   - timestamped log → backend/build/verify-all.log
#
# Usage:
#   ./scripts/verify-all.sh            # 跑全部 V01-V06
#   ./scripts/verify-all.sh --help     # 印 usage
#
# Exit:
#   0  全部 CRITICAL 通過（V02 INFO / SKIP 不影響）
#   1  任一 CRITICAL 失敗
# -----------------------------------------------------------------------------
set -uo pipefail   # 注：不用 -e，逐 V0N 收結果不中斷

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# S020 Round 2 (2026-04-28): log 搬離 backend/build/，因 V01 './gradlew clean'
# 會 rm -rf backend/build/，連同 log + V01 stdout 一起摧毀（QA REJECT-IMPORTANT
# bug fix — spec §2.1 #5 revised；§7.7/7.8 design drift + tech debt 登記）。
# 改寫 repo root；用 .gitignore 排除避免污染 worktree。
LOG="${REPO_ROOT}/verify-all.log"
: > "${LOG}"

TS()      { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
log()     { echo "$@" | tee -a "${LOG}"; }
section() { log ""; log "=== $(TS) | $1 ==="; }

CRIT_FAIL=0
RESULTS=()   # bash 3.2 indexed array（避用 associative）

run_critical() {  # $1=ID  $2=desc  $3=command-str
  section "$1 [CRITICAL] $2"
  if eval "$3" >> "${LOG}" 2>&1; then
    log "▸ $1: PASS"; RESULTS+=("$1=PASS")
  else
    rc=$?; log "▸ $1: FAILED (exit=${rc})"; RESULTS+=("$1=FAIL"); CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

run_skip_if() {   # $1=ID  $2=desc  $3=skip-test  $4=cmd
  section "$1 [CRITICAL/skip-if-unavailable] $2"
  if eval "$3" >/dev/null 2>&1; then
    log "▸ $1: SKIP - prerequisite not met"; RESULTS+=("$1=SKIP"); return 0
  fi
  if eval "$4" >> "${LOG}" 2>&1; then
    log "▸ $1: PASS"; RESULTS+=("$1=PASS")
  else
    rc=$?; log "▸ $1: FAILED (exit=${rc})"; RESULTS+=("$1=FAIL"); CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && { sed -n '3,17p' "$0"; exit 0; }

# V01: gradle test + jacoco report (xml/html/csv)
run_critical "V01" "./gradlew clean test jacocoTestReport" \
  "(cd '${REPO_ROOT}/backend' && ./gradlew clean test jacocoTestReport)"

# V02: parse jacoco CSV → display LINE coverage（INFO，非 gate）
JACOCO_CSV="${REPO_ROOT}/backend/build/reports/jacoco/test/jacocoTestReport.csv"
section "V02 [INFO] LINE coverage from jacocoTestReport.csv"
if [[ -f "${JACOCO_CSV}" ]]; then
  # awk 對 CSV 欄位 8/9 加總（per JaCoCo CSV format spec：$8=LINE_MISSED, $9=LINE_COVERED）
  COV=$(awk -F, 'NR>1 {miss+=$8; cov+=$9} END {
    if (miss+cov == 0) print "n/a";
    else printf "%.1f%% (covered=%d / total=%d)", 100.0*cov/(miss+cov), cov, miss+cov
  }' "${JACOCO_CSV}")
  log "▸ V02 [info]: LINE coverage = ${COV}"; RESULTS+=("V02=INFO")
else
  log "▸ V02: SKIP - jacocoTestReport.csv not found"; RESULTS+=("V02=SKIP")
fi

# V03: jacoco coverage verification gate（skip if S019 ordering edge case：task 未註冊）
section "V03 [CRITICAL/skip-if-unavailable] ./gradlew jacocoTestCoverageVerification"
if (cd "${REPO_ROOT}/backend" && ./gradlew tasks --all 2>/dev/null \
    | grep -q "^jacocoTestCoverageVerification"); then
  if (cd "${REPO_ROOT}/backend" && ./gradlew jacocoTestCoverageVerification) >> "${LOG}" 2>&1; then
    log "▸ V03: PASS"; RESULTS+=("V03=PASS")
  else
    rc=$?; log "▸ V03: FAILED (exit=${rc})"; RESULTS+=("V03=FAIL"); CRIT_FAIL=$((CRIT_FAIL+1))
  fi
else
  log "▸ V03: SKIP - jacocoTestCoverageVerification task not registered"; RESULTS+=("V03=SKIP")
fi

# V04: frontend npm test (skip if no node_modules)
run_skip_if "V04" "cd frontend && npm test" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm test)"

# V05: frontend npm lint
run_skip_if "V05" "cd frontend && npm run lint" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm run lint)"

# V06: frontend coverage gate (S022) — vitest threshold lines:80 → exit 1 if below
# include whitelist 鎖定有對應 test 的 source 檔（漸進加入 gate；spec §2.1 #2）
run_skip_if "V06" "cd frontend && npm test -- --coverage" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm test -- --coverage)"

# V07: Playwright happy-path E2E gate (per ADR-007 + S140 critical-path backfill)
# - skip if e2e/ not bootstrapped (no node_modules) 或 playwright.config.ts 缺
# - 跑時 cd e2e && npx playwright test --grep @happy-path
# Note: 至 S140 ship 6 個 critical-path spec 之前，--grep @happy-path 0 match
# （placeholder smoke 標 @bootstrap，不在 happy-path gate 內）。Playwright default
# 「0 tests run」exit 0 → V07 PASS as no-op；S140 ship 後才有真正 enforcement。
# managed by /playwright-expert skill BOOTSTRAP / DESIGN / VERIFY 流程。
run_skip_if "V07" "cd e2e && npx playwright test --grep @happy-path" \
  "[ ! -d '${REPO_ROOT}/e2e/node_modules' ] || [ ! -f '${REPO_ROOT}/e2e/playwright.config.ts' ]" \
  "(cd '${REPO_ROOT}/e2e' && npx playwright test --grep @happy-path)"

# Summary
PASS=0; FAIL=0; SKIP=0
for r in "${RESULTS[@]}"; do
  case "${r##*=}" in
    PASS) PASS=$((PASS+1));;
    FAIL) FAIL=$((FAIL+1));;
    SKIP) SKIP=$((SKIP+1));;
  esac
done

section "Summary"
log "▸ Results: ${RESULTS[*]}"
log "▸ Counts:  PASS=${PASS}, FAIL=${FAIL}, SKIP=${SKIP}"

if [[ ${CRIT_FAIL} -gt 0 ]]; then
  log "▸ Verdict: ❌ ${CRIT_FAIL} CRITICAL failure(s); exit=1"; exit 1
fi
log "▸ Verdict: ✅ all CRITICAL passed; exit=0"; exit 0
