#!/usr/bin/env bash
# Shared helpers for Skills Hub verification scripts.
# shellcheck shell=bash

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG=""
CRIT_FAIL=0
RESULTS=()

TS() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

init_verify() { # $1=default log basename
  local default_log="$1"
  LOG="${VERIFY_LOG:-${REPO_ROOT}/${default_log}}"
  : > "${LOG}"
}

log() { echo "$@" | tee -a "${LOG}"; }

section() {
  log ""
  log "=== $(TS) | $1 ==="
}

append_result() { # $1=ID $2=status
  RESULTS+=("$1=$2")
}

read_properties_value() { # $1=file $2=key
  local file="$1"
  local key="$2"
  awk -F= -v want="${key}" '
    /^[[:space:]]*(#|$)/ { next }
    {
      k=$1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", k)
      if (k == want) {
        sub(/^[^=]*=/, "", $0)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
        print
        exit
      }
    }
  ' "${file}"
}

load_e2e_genai_key_if_available() {
  if [[ -n "${SKILLSHUB_E2E_GENAI_API_KEY:-}" ]]; then
    log "▸ E2E: SKILLSHUB_E2E_GENAI_API_KEY already set (value redacted)"
    return 0
  fi

  local secrets_file="${REPO_ROOT}/backend/config/application-secrets.properties"
  if [[ ! -f "${secrets_file}" ]]; then
    log "▸ E2E: backend/config/application-secrets.properties not found; semantic fixture key not loaded"
    return 0
  fi

  local dev_key
  dev_key="$(read_properties_value "${secrets_file}" "skillshub.genai.api-key")"
  if [[ -z "${dev_key}" ]]; then
    log "▸ E2E: skillshub.genai.api-key missing from backend/config/application-secrets.properties"
    return 0
  fi

  export SKILLSHUB_E2E_GENAI_API_KEY="${dev_key}"
  log "▸ E2E: loaded SKILLSHUB_E2E_GENAI_API_KEY from backend/config/application-secrets.properties (value redacted)"
}

run_critical() { # $1=ID $2=desc $3=command-str
  local id="$1"
  local desc="$2"
  local cmd="$3"
  section "${id} [CRITICAL] ${desc}"
  if eval "${cmd}" >> "${LOG}" 2>&1; then
    log "▸ ${id}: PASS"
    append_result "${id}" "PASS"
  else
    local rc=$?
    log "▸ ${id}: FAILED (exit=${rc})"
    append_result "${id}" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

run_skip_if() { # $1=ID $2=desc $3=skip-test $4=cmd
  local id="$1"
  local desc="$2"
  local skip_test="$3"
  local cmd="$4"
  section "${id} [CRITICAL/skip-if-unavailable] ${desc}"
  if eval "${skip_test}" >/dev/null 2>&1; then
    log "▸ ${id}: SKIP - prerequisite not met"
    append_result "${id}" "SKIP"
    return 0
  fi
  if eval "${cmd}" >> "${LOG}" 2>&1; then
    log "▸ ${id}: PASS"
    append_result "${id}" "PASS"
  else
    local rc=$?
    log "▸ ${id}: FAILED (exit=${rc})"
    append_result "${id}" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

record_jacoco_line_coverage() {
  local jacoco_csv="${REPO_ROOT}/backend/build/reports/jacoco/test/jacocoTestReport.csv"
  section "V02 [INFO] LINE coverage from jacocoTestReport.csv"
  if [[ -f "${jacoco_csv}" ]]; then
    local cov
    cov=$(awk -F, 'NR>1 {miss+=$8; cov+=$9} END {
      if (miss+cov == 0) print "n/a";
      else printf "%.1f%% (covered=%d / total=%d)", 100.0*cov/(miss+cov), cov, miss+cov
    }' "${jacoco_csv}")
    log "▸ V02 [info]: LINE coverage = ${cov}"
    append_result "V02" "INFO"
  else
    log "▸ V02: SKIP - jacocoTestReport.csv not found"
    append_result "V02" "SKIP"
  fi
}

run_jacoco_gate() {
  section "V03 [CRITICAL/skip-if-unavailable] ./gradlew jacocoTestCoverageVerification"
  if (cd "${REPO_ROOT}/backend" && ./gradlew tasks --all 2>/dev/null \
      | grep -q "^jacocoTestCoverageVerification"); then
    if (cd "${REPO_ROOT}/backend" && ./gradlew jacocoTestCoverageVerification) >> "${LOG}" 2>&1; then
      log "▸ V03: PASS"
      append_result "V03" "PASS"
    else
      local rc=$?
      log "▸ V03: FAILED (exit=${rc})"
      append_result "V03" "FAIL"
      CRIT_FAIL=$((CRIT_FAIL + 1))
    fi
  else
    log "▸ V03: SKIP - jacocoTestCoverageVerification task not registered"
    append_result "V03" "SKIP"
  fi
}

e2e_prerequisite_missing() {
  [[ ! -d "${REPO_ROOT}/e2e/node_modules" ]] \
    || [[ ! -f "${REPO_ROOT}/e2e/playwright.config.ts" ]]
}

e2e_has_tag() { # $1=@tag
  grep -rq "$1" "${REPO_ROOT}/e2e/tests/" 2>/dev/null
}

run_e2e_risk_gate() {
  section "V07d [CRITICAL/skip-if-unavailable] E2E Risk tags"
  if e2e_prerequisite_missing; then
    log "▸ V07d: SKIP - prerequisite not met"
    append_result "V07d" "SKIP"
    return 0
  fi

  local tags=()
  local tag
  for tag in "@negative" "@edge" "@permission" "@security"; do
    if e2e_has_tag "${tag}"; then
      tags+=("${tag}")
    fi
  done

  if [[ ${#tags[@]} -eq 0 ]]; then
    log "▸ V07d: SKIP - no risk tags found"
    append_result "V07d" "SKIP"
    return 0
  fi

  local failed=0
  for tag in "${tags[@]}"; do
    log "▸ V07d: running ${tag}"
    if (cd "${REPO_ROOT}/e2e" && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --grep "${tag}") >> "${LOG}" 2>&1; then
      log "▸ V07d ${tag}: PASS"
    else
      local rc=$?
      log "▸ V07d ${tag}: FAILED (exit=${rc})"
      failed=$((failed + 1))
    fi
  done

  if [[ ${failed} -eq 0 ]]; then
    append_result "V07d" "PASS"
  else
    append_result "V07d" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

run_native_image_gate() {
  section "V08b [CRITICAL/skip-if-unavailable] ./gradlew bootBuildImage"
  if [[ "${SKIP_NATIVE:-0}" != "0" ]]; then
    log "▸ V08b: SKIP - SKIP_NATIVE=${SKIP_NATIVE} (dev opt-out)"
    append_result "V08b" "SKIP"
  elif ! docker info >/dev/null 2>&1; then
    log "▸ V08b: SKIP - Docker daemon not available"
    append_result "V08b" "SKIP"
  else
    if (cd "${REPO_ROOT}/backend" && SKILLSHUB_GENAI_API_KEY="${SKILLSHUB_AOT_GENAI_API_KEY:-aot-placeholder-key}" ./gradlew --no-daemon -x test bootBuildImage \
         --imageName=skillshub-verify:local \
         -Pspring.profiles.active=aot,local) >> "${LOG}" 2>&1; then
      log "▸ V08b: PASS"
      append_result "V08b" "PASS"
    else
      local rc=$?
      log "▸ V08b: FAILED (exit=${rc})"
      append_result "V08b" "FAIL"
      CRIT_FAIL=$((CRIT_FAIL + 1))
    fi
  fi
}

run_secret_leak_check() {
  section "V09 [CRITICAL/skip-if-unavailable] secret leak check"
  if ! command -v rg >/dev/null 2>&1; then
    log "▸ V09: SKIP - rg not available"
    append_result "V09" "SKIP"
    return 0
  fi

  local pattern='AIza[0-9A-Za-z_-]{20,}|SKILLSHUB_E2E_GENAI_API_KEY=[A-Za-z0-9_-]{20,}|skillshub\.genai\.api-key=[A-Za-z0-9_-]{20,}'
  if rg -n "${pattern}" "${LOG}" "${REPO_ROOT}/docs/grimo" >> "${LOG}" 2>&1; then
    log "▸ V09: FAILED - potential secret-like value found"
    append_result "V09" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  else
    log "▸ V09: PASS"
    append_result "V09" "PASS"
  fi
}

finalize_verify() {
  local pass=0
  local fail=0
  local skip=0
  local info=0
  local r
  for r in "${RESULTS[@]}"; do
    case "${r##*=}" in
      PASS) pass=$((pass + 1));;
      FAIL) fail=$((fail + 1));;
      SKIP) skip=$((skip + 1));;
      INFO) info=$((info + 1));;
    esac
  done

  section "Summary"
  log "▸ Log: ${LOG}"
  log "▸ Results: ${RESULTS[*]}"
  log "▸ Counts:  PASS=${pass}, FAIL=${fail}, SKIP=${skip}, INFO=${info}"

  if [[ ${CRIT_FAIL} -gt 0 ]]; then
    log "▸ Verdict: FAIL - ${CRIT_FAIL} CRITICAL failure(s); exit=1"
    exit 1
  fi
  log "▸ Verdict: PASS - all CRITICAL passed; exit=0"
  exit 0
}
