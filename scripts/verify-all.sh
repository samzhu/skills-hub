#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Deterministic Verification Runner (S020)
# /verifying-quality Step 0.5 protocol：
#   - CRITICAL fail → exit !=0
#   - SKIP-if-unavailable → which-detect / dir-detect graceful skip
#   - timestamped log → backend/build/verify-all.log
#
# Usage:
#   ./scripts/verify-all.sh            # 跑全部 V01-V08b（含 native image build）
#   SKIP_NATIVE=1 ./scripts/verify-all.sh   # 跳 V08b（dev fast loop）；CI 留空
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
# Skip-if 三條件任一成立：
#   1. e2e/ 未 BOOTSTRAP（node_modules 缺）
#   2. playwright.config.ts 缺
#   3. tests/ 內無 @happy-path tag（grep 0 match）
# 第 3 條件關鍵：避免 0 match 時 Playwright 仍啟 webServer（Spring Boot bootRun
# 90-150s cold start）才回報 zero tests — 等於每次 verify-all 浪費 2 分鐘。
# S140 ship 6 個 critical-path spec 後，每個都帶 @happy-path tag，grep match
# 自然走 V07 active path；ship 前 placeholder smoke 標 @bootstrap，不 match
# → V07 skip。managed by /playwright-expert skill BOOTSTRAP / DESIGN / VERIFY。
run_skip_if "V07" "cd e2e && npx playwright test --grep @happy-path" \
  "[ ! -d '${REPO_ROOT}/e2e/node_modules' ] || [ ! -f '${REPO_ROOT}/e2e/playwright.config.ts' ] || ! grep -rq '@happy-path' '${REPO_ROOT}/e2e/tests/' 2>/dev/null" \
  "(cd '${REPO_ROOT}/e2e' && npx playwright test --grep @happy-path)"

# V08a: AOT processing smoke — fast (~30s) catch AOT-bake-time bugs（如 S158
# Jackson default-view-inclusion）。always run；不依 Docker / GraalVM。
# 與 V07 重複跑 processAot 是 cache hit，cost 接近 0。
run_critical "V08a" "./gradlew processAot" \
  "(cd '${REPO_ROOT}/backend' && ./gradlew processAot)"

# V08b: Full native image build — 抓 GraalVM native-image static analysis、
# reflection metadata 缺、Paketo container layer 失敗。預設 ON（完整防線）；
# dev 快迭代時 SKIP_NATIVE=1 opt-out（明示風險而非偷偷跳）。
# imageName 用 skillshub-verify:local 避免污染 cloudbuild 產的 latest tag。
#
# Profile = aot,local（不用 cloudbuild.yaml 的 gcp,aot,lab）：
#   gcp profile 觸發 SecretManagerConfigDataLocationResolver，需 ADC（本機普遍沒設
#   `gcloud auth application-default login`）+ 真打 SM API 產生 GCP 計費 / audit log。
#   `application-aot.yaml` L41-44 明確設計為 aot profile 本地 disable SM。
#   gcp-profile-only AOT bug（SM import 解析、GCP autoconfig）由 cloudbuild.yaml
#   step 3（gcp,aot,lab）在 CI push 時擔當 canonical gate；V08b 抓 90% prod-only
#   bug（native-image / reflection metadata / Paketo container），剩 10% 由 CI 擋。
section "V08b [CRITICAL/skip-if-unavailable] ./gradlew bootBuildImage"
if [[ "${SKIP_NATIVE:-0}" != "0" ]]; then
  log "▸ V08b: SKIP - SKIP_NATIVE=${SKIP_NATIVE} (dev opt-out)"; RESULTS+=("V08b=SKIP")
elif ! docker info >/dev/null 2>&1; then
  log "▸ V08b: SKIP - Docker daemon not available"; RESULTS+=("V08b=SKIP")
else
  if (cd "${REPO_ROOT}/backend" && ./gradlew --no-daemon -x test bootBuildImage \
       --imageName=skillshub-verify:local \
       -Pspring.profiles.active=aot,local) >> "${LOG}" 2>&1; then
    log "▸ V08b: PASS"; RESULTS+=("V08b=PASS")
  else
    rc=$?; log "▸ V08b: FAILED (exit=${rc})"; RESULTS+=("V08b=FAIL"); CRIT_FAIL=$((CRIT_FAIL+1))
  fi
fi

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
