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
  log "Skills Hub release verification"
  log "完整輸出會寫到: ${LOG}"
  log "畫面只顯示每一關在檢查什麼；如果失敗，再打開 log 看細節。"
}

log() { echo "$@" | tee -a "${LOG}"; }

section() {
  log ""
  log "=== $(TS) | $1 ==="
}

step_name() { # $1=ID $2=fallback
  local id="$1"
  local fallback="$2"
  case "${id}" in
    V01) echo "後端測試 + 產生覆蓋率報表" ;;
    V02) echo "讀取後端覆蓋率數字" ;;
    V03) echo "檢查後端覆蓋率是否至少 80%" ;;
    V04) echo "前端元件 / hook / API client 測試" ;;
    V05) echo "前端 lint + TypeScript 型別檢查" ;;
    V06) echo "前端覆蓋率檢查" ;;
    V07) echo "瀏覽器核心流程測試" ;;
    V07b) echo "瀏覽器完整 app 測試" ;;
    V07c) echo "E2E 測試資料工具檢查" ;;
    V07d) echo "風險 / 反例瀏覽器測試" ;;
    V08a) echo "Spring AOT 打包前檢查" ;;
    V08b) echo "Native image 打包檢查" ;;
    V09) echo "確認 log / docs 沒有真 API key" ;;
    P00) echo "確認 curl 可用" ;;
    P01) echo "確認服務 health 是 UP" ;;
    P02) echo "確認首頁可打開" ;;
    P03) echo "確認技能列表 API 可回 JSON" ;;
    P04) echo "確認登入狀態 API 回應合理" ;;
    P05) echo "確認下載 endpoint 可回下載 response" ;;
    *) echo "${fallback}" ;;
  esac
}

step_purpose() { # $1=ID
  case "$1" in
    V01) echo "確認 Java 後端測試全過，並產生 JaCoCo 覆蓋率檔案。" ;;
    V02) echo "把 V01 產生的覆蓋率檔案讀成一個人看得懂的百分比。" ;;
    V03) echo "確認後端程式碼行覆蓋率達到 80% gate。" ;;
    V04) echo "確認前端使用者看得到、點得到、收到的錯誤訊息都符合測試。" ;;
    V05) echo "確認前端沒有 lint 問題，也沒有 TypeScript 型別錯誤。" ;;
    V06) echo "確認目前納入 coverage include 的前端檔案達到 80% gate。" ;;
    V07) echo "用真打包出的 app image 跑核心瀏覽器流程，不用 Vite dev server。" ;;
    V07b) echo "用真打包出的 app image 跑所有 app browser specs，不只 happy path。" ;;
    V07c) echo "確認 E2E seed / fixture 工具可信，避免測試資料污染或走錯 DB。" ;;
    V07d) echo "有風險標籤時，檢查空值、邊界、權限、惡意輸入等反例。" ;;
    V08a) echo "確認 Spring AOT 產生 code 時不需要真 DB、GCP credential 或真 Gemini key。" ;;
    V08b) echo "確認 native image 真的能 build 起來，抓 reflection metadata 和 container layer 問題。" ;;
    V09) echo "掃描本輪 log 和 docs，避免把真 API key 寫進檔案。" ;;
    *) echo "確認這一關要求的品質條件是否成立。" ;;
  esac
}

step_failure_hint() { # $1=ID
  case "$1" in
    V01) echo "後端測試、Spring wiring、DB/Testcontainers 或 Java compile 有問題。" ;;
    V02) echo "通常不是阻擋；表示覆蓋率報表不存在或還沒產生。" ;;
    V03) echo "後端測試覆蓋率不足，或 JaCoCo gate 設定/執行有問題。" ;;
    V04) echo "前端行為測試失敗，使用者操作或畫面結果可能壞了。" ;;
    V05) echo "前端程式格式、規則或 TypeScript 型別有問題。" ;;
    V06) echo "前端納入 gate 的檔案測試不足。" ;;
    V07) echo "核心使用者流程在 production packaged app 裡跑不起來。" ;;
    V07b) echo "某個 browser app spec 在真組裝環境壞掉。" ;;
    V07c) echo "E2E 測試資料工具不可信，後面的 browser 測試結果不能直接相信。" ;;
    V07d) echo "反例、邊界、權限或安全相關流程有問題。" ;;
    V08a) echo "Spring AOT build-time 設定有問題。" ;;
    V08b) echo "native image build 失敗，release package 還不能出。" ;;
    V09) echo "log 或 docs 可能含真 secret，需要先移除。" ;;
    *) echo "這一關失敗，請看 log 中該段落的錯誤訊息。" ;;
  esac
}

describe_step() { # $1=ID $2=command
  local id="$1"
  local command="$2"
  log "檢查目的: $(step_purpose "${id}")"
  log "執行指令: ${command}"
  log "失敗意思: $(step_failure_hint "${id}")"
  log "完整細節: ${LOG}"
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
  section "${id} [CRITICAL] $(step_name "${id}" "${desc}")"
  describe_step "${id}" "${cmd}"
  if eval "${cmd}" >> "${LOG}" 2>&1; then
    log "結果: PASS - ${id} 通過"
    append_result "${id}" "PASS"
  else
    local rc=$?
    log "結果: FAIL - ${id} 失敗，exit=${rc}。請看上方 log path 裡的這一段錯誤。"
    append_result "${id}" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

run_skip_if() { # $1=ID $2=desc $3=skip-test $4=cmd
  local id="$1"
  local desc="$2"
  local skip_test="$3"
  local cmd="$4"
  section "${id} [CRITICAL/skip-if-unavailable] $(step_name "${id}" "${desc}")"
  describe_step "${id}" "${cmd}"
  if eval "${skip_test}" >/dev/null 2>&1; then
    log "結果: SKIP - ${id} 沒有跑，原因是本機缺少這一關需要的前置條件。"
    append_result "${id}" "SKIP"
    return 0
  fi
  if eval "${cmd}" >> "${LOG}" 2>&1; then
    log "結果: PASS - ${id} 通過"
    append_result "${id}" "PASS"
  else
    local rc=$?
    log "結果: FAIL - ${id} 失敗，exit=${rc}。請看上方 log path 裡的這一段錯誤。"
    append_result "${id}" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  fi
}

record_jacoco_line_coverage() {
  local jacoco_csv="${REPO_ROOT}/backend/build/reports/jacoco/test/jacocoTestReport.csv"
  section "V02 [INFO] $(step_name "V02" "LINE coverage from jacocoTestReport.csv")"
  describe_step "V02" "parse backend/build/reports/jacoco/test/jacocoTestReport.csv"
  if [[ -f "${jacoco_csv}" ]]; then
    local cov
    cov=$(awk -F, 'NR>1 {miss+=$8; cov+=$9} END {
      if (miss+cov == 0) print "n/a";
      else printf "%.1f%% (covered=%d / total=%d)", 100.0*cov/(miss+cov), cov, miss+cov
    }' "${jacoco_csv}")
    log "結果: INFO - 後端 LINE coverage = ${cov}"
    append_result "V02" "INFO"
  else
    log "結果: SKIP - 找不到 JaCoCo CSV，通常表示 V01 沒有成功產生報表。"
    append_result "V02" "SKIP"
  fi
}

run_jacoco_gate() {
  section "V03 [CRITICAL/skip-if-unavailable] $(step_name "V03" "./gradlew jacocoTestCoverageVerification")"
  describe_step "V03" "cd backend && ./gradlew jacocoTestCoverageVerification"
  if (cd "${REPO_ROOT}/backend" && ./gradlew tasks --all 2>/dev/null \
      | grep -q "^jacocoTestCoverageVerification"); then
    if (cd "${REPO_ROOT}/backend" && ./gradlew jacocoTestCoverageVerification) >> "${LOG}" 2>&1; then
      log "結果: PASS - V03 通過"
      append_result "V03" "PASS"
    else
      local rc=$?
      log "結果: FAIL - V03 失敗，exit=${rc}。請看上方 log path 裡的這一段錯誤。"
      append_result "V03" "FAIL"
      CRIT_FAIL=$((CRIT_FAIL + 1))
    fi
  else
    log "結果: SKIP - Gradle 沒有註冊 jacocoTestCoverageVerification task。"
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
  section "V07d [CRITICAL/skip-if-unavailable] $(step_name "V07d" "E2E Risk tags")"
  describe_step "V07d" "cd e2e && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --grep <risk-tag>"
  if e2e_prerequisite_missing; then
    log "結果: SKIP - V07d 沒有跑，原因是本機缺少 E2E 前置條件。"
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
    log "結果: SKIP - 目前沒有 @negative / @edge / @permission / @security 測試標籤。"
    append_result "V07d" "SKIP"
    return 0
  fi

  local failed=0
  for tag in "${tags[@]}"; do
    log "正在跑風險標籤: ${tag}"
    if (cd "${REPO_ROOT}/e2e" && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --grep "${tag}") >> "${LOG}" 2>&1; then
      log "結果: PASS - ${tag} 通過"
    else
      local rc=$?
      log "結果: FAIL - ${tag} 失敗，exit=${rc}"
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
  section "V08b [CRITICAL/skip-if-unavailable] $(step_name "V08b" "./gradlew bootBuildImage")"
  describe_step "V08b" "cd backend && ./gradlew --no-daemon -x test bootBuildImage --imageName=skillshub-verify:local -Pspring.profiles.active=aot,local"
  if [[ "${SKIP_NATIVE:-0}" != "0" ]]; then
    log "結果: SKIP - SKIP_NATIVE=${SKIP_NATIVE}，本輪明確跳過 native image build。"
    append_result "V08b" "SKIP"
  elif ! docker info >/dev/null 2>&1; then
    log "結果: SKIP - Docker daemon 不可用，所以無法 build native image。"
    append_result "V08b" "SKIP"
  else
    if (cd "${REPO_ROOT}/backend" && SKILLSHUB_GENAI_API_KEY="${SKILLSHUB_AOT_GENAI_API_KEY:-aot-placeholder-key}" ./gradlew --no-daemon -x test bootBuildImage \
         --imageName=skillshub-verify:local \
         -Pspring.profiles.active=aot,local) >> "${LOG}" 2>&1; then
      log "結果: PASS - V08b 通過"
      append_result "V08b" "PASS"
    else
      local rc=$?
      log "結果: FAIL - V08b 失敗，exit=${rc}。請看上方 log path 裡的這一段錯誤。"
      append_result "V08b" "FAIL"
      CRIT_FAIL=$((CRIT_FAIL + 1))
    fi
  fi
}

run_secret_leak_check() {
  section "V09 [CRITICAL/skip-if-unavailable] $(step_name "V09" "secret leak check")"
  describe_step "V09" "rg secret-like pattern against verify-release.log and docs/grimo"
  if ! command -v rg >/dev/null 2>&1; then
    log "結果: SKIP - 找不到 rg，無法掃描 secret-like value。"
    append_result "V09" "SKIP"
    return 0
  fi

  local pattern='AIza[0-9A-Za-z_-]{20,}|SKILLSHUB_E2E_GENAI_API_KEY=[A-Za-z0-9_-]{20,}|skillshub\.genai\.api-key=[A-Za-z0-9_-]{20,}'
  if rg -n "${pattern}" "${LOG}" "${REPO_ROOT}/docs/grimo" >> "${LOG}" 2>&1; then
    log "結果: FAIL - 找到疑似真 API key 的內容，請先移除。"
    append_result "V09" "FAIL"
    CRIT_FAIL=$((CRIT_FAIL + 1))
  else
    log "結果: PASS - 沒找到疑似真 API key 的內容。"
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
  log "完整 log: ${LOG}"
  log "各關結果: ${RESULTS[*]}"
  log "統計: PASS=${pass}, FAIL=${fail}, SKIP=${skip}, INFO=${info}"

  if [[ ${CRIT_FAIL} -gt 0 ]]; then
    log "總結: FAIL - 有 ${CRIT_FAIL} 個必要檢查失敗；不能 release。"
    log "Verdict: FAIL - ${CRIT_FAIL} CRITICAL failure(s); exit=1"
    exit 1
  fi
  log "總結: PASS - 所有必要檢查都通過；可以進入 shipping-release。"
  log "Verdict: PASS - all CRITICAL passed; exit=0"
  exit 0
}
