# S020: Verification Command Registry + `scripts/verify-all.sh`

> Spec: S020 | Size: S(10) | Status: ⏳ Design
> Date: 2026-04-27
> Depends: — (S019 為 ordering-only — V03 引用 task name 字串 `jacocoTestCoverageVerification`，無 Java import；S019 未 ship 時 V03 graceful SKIP)
> Blocks: 後續 spec 的 `/verifying-quality` Step 0.5 — QA review 直接 `./scripts/verify-all.sh` 拿結構化結果

> **跨 spec cross-update**：本 spec 同 commit 補 S019 §4.1 加 `csv.required = true`（V02 解析來源）；S019 spec 仍為 ⏳ Design 故可直接編輯，不需新建 ADR。

---

## 1. Goal

建立 `/verifying-quality` Step 0.5 protocol 期望的兩個 artifact：(1) 結構化 Verification Command Registry table（住 `docs/grimo/qa-strategy.md` 新章節）；(2) deterministic shell script `scripts/verify-all.sh` — 一鍵跑完 5 條 critical gate 並回報結構化結果（含 CSV-based coverage 顯示）。

**簡單講**: S014 ship 後 `/verifying-quality` 主驗列為 IMPORTANT 的另一個 pre-existing project gap — Step 0.5 protocol（`SKILL.md` L83-124）強制 registry table + `verify-all.sh`，但本 project 兩者都沒有；過去 4 輪 QA review 須手動從 qa-strategy.md + build.gradle 推導命令。本 spec 一次補 5 條 verify command（V01-V05；Java + Vitest 雙軌）+ 1 條 known limitation（`bootRun -x processAot` workaround）+ shell glue（CRITICAL fail → exit 非 0；SKIP-if-unavailable → which-detect graceful skip；timestamped log → `backend/build/verify-all.log`）+ CSV-based coverage display（shell 解析 `jacocoTestReport.csv` 顯示 % — defense-in-depth：Gradle `jacocoTestCoverageVerification` 仍是真 gate，shell 顯示是人眼可見層）。

```
┌── 現況（S014 ship 後）──────────────────────────────────────┐
│   /verifying-quality SKILL.md L83-124 期望:                 │
│     (1) Verification Command Registry table                 │
│     (2) scripts/verify-all.sh (executable, 嚴重度感知)      │
│   ▶ 兩個都不存在                                            │
│   ▶ 每輪 QA 手動 cherry-pick；命令清單漂移風險              │
└─────────────────────────────────────────────────────────────┘
                              ↓ S020（本 spec）
┌── 目標 ─────────────────────────────────────────────────────┐
│   docs/grimo/qa-strategy.md                                 │
│     ## Verification Command Registry  ← 新章節              │
│       table: ID | Command | Severity | Skip-if | Notes      │
│       ### Known Limitations  (bootRun AOT bug)              │
│       ### 不 enroll 的命令  (rationale 列表)                │
│                                                              │
│   scripts/verify-all.sh                                     │
│     V01 ./gradlew clean test jacocoTestReport     CRITICAL  │
│     V02 parse jacocoTestReport.csv → 顯示 LINE %  INFO      │
│     V03 ./gradlew jacocoTestCoverageVerification  CRITICAL* │
│     V04 cd frontend && npm test                   CRITICAL* │
│     V05 cd frontend && npm run lint               CRITICAL* │
│       (* = SKIP-if-unavailable)                             │
│                                                              │
│   backend/build/verify-all.log（timestamped sections）      │
│   ▶ ./scripts/verify-all.sh → exit 0 only if all CRITICAL ✓│
│   ▶ /verifying-quality Step 1 直接 invoke 此 script         │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

S spec — 純 shell + Markdown table。**Phase 2 Research SKIP** — 純 standard tooling（bash + Gradle CLI + npm CLI）+ Step 0.5 contract 預先定義在 in-repo skill；無外部 framework API 需研究。

### 2.1 關鍵設計決策（6 項）

| # | 決策 | 選擇 | 理由 | 否決 |
|---|------|------|------|------|
| 1 | Glue 形式 | **純 shell `scripts/verify-all.sh`** | 對齊 planning-spec「Native Tooling Preference」+ Step 0.5 protocol predefined name + 既有 `scripts/gcp/*.sh` 風格（I3）| A. Gradle aggregator task（`./gradlew verifyAll`）— npm wrap awkward；違 Step 0.5 預期<br/>B. Hybrid shell + Gradle — 雙 surface 維護、雙倍 doc-sync 負擔 |
| 2 | Registry 落腳 | **擴 `docs/grimo/qa-strategy.md` 新增 `## Verification Command Registry` section** | Single source of truth；Step 0.5 文字「in QA strategy or test documentation」直對齊；近 §Verification Pipeline 內容相連 | 新建 `docs/grimo/verification-registry.md`（多檔案、易漂移）|
| 3 | 嚴重度模型 | **Step 0.5 protocol 二層**（`CRITICAL` / `SKIP-if-unavailable`）+ shell 內 `INFO` 純顯示前綴 | Protocol native；不發明三/四層；`/verifying-quality` 已知此語意 | 自定 `IMPORTANT` 中間級（偏離 protocol）|
| 4 ★ | Coverage 顯示與 gate | **雙層**：shell V02 解析 CSV awk 加總 LINE 顯示 `nn.n%`（人眼可見）+ Gradle V03 `jacocoTestCoverageVerification` 為實際 gate（threshold 在 build.gradle 單一 source）| 用戶顯式偏好 CSV 計算；defense-in-depth — `./gradlew check` 仍含 V03 gate；shell 提供 visible % 不靠 Gradle 端 verbose 輸出；CSV awk-friendly（XML 要 XPath） | A. 只 shell CSV gate（loses `./gradlew check` 防線；threshold 散兩處）<br/>B. 只 jacocoTestCoverageVerification（無 visible %）|
| 5 | Log 檔位置 | **`backend/build/verify-all.log`** 每跑覆寫；內含 ISO timestamp section header + 各 V0N stdout + 末尾 verdict | `build/` 已 gitignore；CI archive 容易；單檔不污染 worktree | `.verify-runs/<ts>.log` 多檔（gitignore + 累積）；`/tmp/...`（CI 易丟）|
| 6 | Bash 相容性 | **bash 3.2 portable**（macOS 預設）— indexed array、`[[ ]]`、`command -v`、`awk -F,`；無 associative array / `mapfile` / `${var^^}` | 既有 `scripts/gcp/*.sh` 同 idiom；macOS 開發者直接 `bash` 無需升 5.x | bash 4+ 專屬語法（限制 macOS 預設環境執行）|

> ★ = 用戶顯式裁決點（CSV-based coverage display）

### 2.2 與既有架構的契合

| 維度 | 現況 | S020 變動 |
|------|------|-----------|
| `docs/grimo/qa-strategy.md` | §Verification Pipeline + §Three-Layer Verification + §AC-to-Test Contract + §Development Environment | **新增** `## Verification Command Registry` section（緊接 §Verification Pipeline 之後）|
| `scripts/` 目錄 | `scripts/gcp/01-...05-...sh` + README（S013 部署）| **新增** `scripts/verify-all.sh` 與 `scripts/gcp/` 平輩；不同用途 — verify-all.sh 為 PR/QA gate，gcp/ 為部署 |
| Build commands（CLAUDE.md §Build commands）| `./gradlew test` / `bootRun` / `build` | **新增** `./scripts/verify-all.sh` — 與 `./gradlew check` 並列：Gradle 端 gate（精確）+ shell 端 glue（含 frontend + visible coverage）|
| `/verifying-quality` Step 1 流程 | 手動 cherry-pick | **解除耦合** — Step 1 自動 invoke verify-all.sh |
| S019 `jacocoTestReport.reports` | xml + html | **跨 spec 同步** — S019 §4.1 加 `csv.required = true`（本 spec V02 解析來源；同 commit 處理）|
| Frontend coverage（qa-strategy L23-25 宣告 80%）| aspirational — `package.json` 無 `coverage` script、`@vitest/coverage-v8` 未裝 | **不在本 spec 範圍** — V05 跑 `npm run lint`、V04 跑 `npm test`；coverage 留未來 spec；qa-strategy.md 文字 L23-25 暫不動，於本 spec ship 時於「不 enroll 的命令」sub-table 註明等待 |
| GraalVM `processAot` bug | pre-existing；`bootRun -x processAot` workaround | **記錄不執行** — registry `### Known Limitations` sub-table 條目；不在 verify-all 內執行（慢 + 非 PR gate）|

### 2.3 Internal Citations

無外部研究（純 standard tooling）。Contract 來源 in-repo / official:

| # | 主題 | 來源 / Anchor | 結論 |
|---|------|--------------|------|
| I1 | Step 0.5 protocol contract | `.claude/skills/verifying-quality/SKILL.md` L83-L124 | Registry table 必含 command + severity + env prereq；script 必須 CRITICAL fail 非 0、SKIP-if-unavailable 走 detect、timestamped log；exit 0 only if all CRITICAL pass |
| I2 | qa-strategy.md current state | `docs/grimo/qa-strategy.md` L1-L132 | §Verification Pipeline / §Three-Layer / §AC-to-Test Contract 既存；無 registry section |
| I3 | 既有 shell 風格 | `scripts/gcp/01-bootstrap.sh` L1-L40 | `#!/usr/bin/env bash` + `set -euo pipefail` + `: "${VAR:?...}"` + `▸` echo 前綴 + 冪等 |
| I4 | Frontend test 設定 | `frontend/package.json` L11 | `"test": "vitest run"` + `"lint": "eslint ."` 已存在；`coverage` script 不存在；coverage tooling 未裝 |
| I5 | JaCoCo CSV 欄位 | https://www.jacoco.org/jacoco/trunk/doc/csv-format.html | 欄位順序：`GROUP, PACKAGE, CLASS, INSTRUCTION_MISSED, INSTRUCTION_COVERED, BRANCH_MISSED, BRANCH_COVERED, LINE_MISSED, LINE_COVERED, COMPLEXITY_*, METHOD_*` — V02 awk `$8` = LINE_MISSED、`$9` = LINE_COVERED |

### 2.4 Research Sufficiency Gate

| 設計決策 | 信心 | 證據 |
|---------|------|------|
| Step 0.5 protocol 兩 artifact contract | **Validated** | I1 raw skill source |
| Bash 3.2 portable idioms | **Validated** | 既有 `scripts/gcp/*.sh` 已用此 idioms（I3）|
| JaCoCo CSV 欄位 8/9 = LINE_MISSED/COVERED | **Validated** | I5 官方 doc |
| Vitest run + ESLint exit code 規範 | **Validated** | vitest@4.1.5 + eslint@10 預設 fail → exit 1 |
| `jacocoTestReport.csv` 由 S019 產出 | **Validated by cross-update** | 本 spec ship 同 commit 補 S019 §4.1 加 `csv.required = true` |

**POC: not required** — 全部 Validated。

---

## 3. SBE Acceptance Criteria

> AC-naming contract: `@DisplayName("AC-N: ...")` 或 `@Tag("AC-N")`（per qa-strategy.md §AC-to-Test Contract）。本 spec 為 shell + docs 變動，多數 AC 由 build evidence（exit code + log 檔內容 + registry table 完整性）取代測試方法。

**AC-1**: Verification Command Registry table 入駐 qa-strategy.md
- Given `docs/grimo/qa-strategy.md` 加 `## Verification Command Registry` section
- When `grep -n "## Verification Command Registry" docs/grimo/qa-strategy.md`
- Then 找到該 heading
- And 該 section 下含 markdown table，欄位 `ID | Command | Severity | Skip-if | Notes`
- And 至少 5 條 row（V01-V05）
- And 同 section 下兩個 sub-table：`### Known Limitations`（含 `bootRun -x processAot`）+ `### 不 enroll 的命令`（含 cyclonedxBom / bootBuildImage / npm coverage 之 rationale）

**AC-2**: `scripts/verify-all.sh` 可執行 + bash 3.2 portable
- Given `scripts/verify-all.sh` 存在 + `chmod +x`
- When 跑 `./scripts/verify-all.sh --help`
- Then exit 0 + stdout 印 usage（10+ 行 doc）
- And 跑 `bash --version`（system bash）顯示 3.2 也能執行（`grep -E "declare -A|mapfile|\\\$\\{[a-zA-Z_][a-zA-Z0-9_]*\\^\\^"` 在 script 中查無 — 確認無 bash 4+ 專屬語法）

**AC-3**: 全 CRITICAL 通過 → exit 0 + 結構化 verdict
- Given 所有 V01-V05 預期通過（含 S019 ship 後 V03 啟用）
- When 跑 `./scripts/verify-all.sh`
- Then exit code = 0
- And stdout / log 末尾含三行：
  - `▸ Results: V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS`
  - `▸ Counts:  PASS=4, FAIL=0, SKIP=0`
  - `▸ Verdict: ✅ all CRITICAL passed; exit=0`
- And `backend/build/verify-all.log` 含每 V0N section + ISO timestamp（`YYYY-MM-DDTHH:MM:SSZ`）+ 各 stdout

**AC-4**: 任一 CRITICAL 失敗 → exit 非 0 + 失敗位置可見
- Given 故意把 backend code 弄壞（e.g. throw RuntimeException in @Bean wiring）讓 V01 fail
- When 跑 `./scripts/verify-all.sh`
- Then exit code ≠ 0
- And stdout 含 `▸ V01: FAILED (exit=...)`
- And log 檔保留完整 stack trace
- And summary 末行 `▸ Verdict: ❌ N CRITICAL failure(s); exit=1`

**AC-5**: SKIP-if-unavailable 邏輯生效
- Given 把 `frontend/node_modules` 暫時 rename 模擬未裝
- When 跑 `./scripts/verify-all.sh`
- Then V04 + V05 標 `SKIP`（not failure）
- And exit code 由其他 CRITICAL 結果決定（V01-V03 通過則 exit 0）
- And log 含 `▸ V04: SKIP - prerequisite not met`

**AC-6**: V02 CSV-based coverage 顯示
- Given S019 已 ship，`./gradlew clean test jacocoTestReport` 已產出 `backend/build/reports/jacoco/test/jacocoTestReport.csv`
- When 跑 `./scripts/verify-all.sh`
- Then stdout 含 `▸ V02 [info]: LINE coverage = NN.N% (covered=X / total=Y)`
- And 該百分比由 awk 從 CSV `$8`（LINE_MISSED）+ `$9`（LINE_COVERED）對所有 row 加總後計算（per I5）

**AC-7**: V03 task-not-found graceful skip（S019 ordering edge case）
- Given S019 尚未 ship 的環境（無 `jacocoTestCoverageVerification` task）
- When 跑 `./scripts/verify-all.sh`
- Then V03 標 `SKIP - jacocoTestCoverageVerification task not registered`
- And 不影響其他 V0N 執行
- And exit code 由 V01/V04/V05 結果決定（不因 V03 SKIP 變 FAIL）

### 驗收命令

per qa-strategy.md（本 spec 自身為 verify-all.sh 之建立者，故驗收命令即運行自己 + 互動式破壞性測試覆蓋 AC-4/5/7）：
```
./scripts/verify-all.sh
```
**Pass 條件**: AC-3 全 CRITICAL 通過時 exit 0 + log 檔 verdict 行匹配。AC-4/5/7 由手動構造邊界情境一次性驗證（spec ship 前操作 + 截圖入 §7）。

---

## 4. Interface / API Design

### 4.1 `docs/grimo/qa-strategy.md` 新章節（附在 §Verification Pipeline 之後）

```markdown
## Verification Command Registry

`/verifying-quality` Step 0.5 protocol 期望此 table 為唯一 source of truth。
新增 verify task 須同步更新 `scripts/verify-all.sh`；移除須兩處同刪。

| ID | Command | Severity | Skip-if | Notes |
|----|---------|----------|---------|-------|
| V01 | `./gradlew clean test jacocoTestReport` | CRITICAL | — | 含 ModularityTests；產 jacoco XML/HTML/CSV |
| V02 | parse `backend/build/reports/jacoco/test/jacocoTestReport.csv` (awk LINE_MISSED + LINE_COVERED) | INFO（顯示用）| CSV 不存在 | 顯示 LINE coverage %；非 gate（gate 由 V03 負責）|
| V03 | `./gradlew jacocoTestCoverageVerification` | CRITICAL | task 未註冊（S019 未 ship）| Threshold 在 `build.gradle.kts`；`./gradlew check` 同 gate |
| V04 | `cd frontend && npm test` | CRITICAL | `frontend/node_modules` 不存在 | Vitest run；frontend test gate |
| V05 | `cd frontend && npm run lint` | CRITICAL | `frontend/node_modules` 不存在 | ESLint；frontend lint gate |

### Known Limitations

| Item | Workaround | Why not enroll |
|------|-----------|----------------|
| `./gradlew bootRun` 觸發 `:processAot` 失敗（GraalVM `org.graalvm.buildtools.native:0.11.5` plugin pre-existing bug）| `./gradlew bootRun -x processAot` | bootRun smoke 慢且非 PR gate；待獨立 spec 處理 AOT 配置或切 OpenTelemetry |

### 不 enroll 的命令

| 命令 | 為何不入 registry |
|------|------------------|
| `./gradlew test --tests "*ModularityTests*"` | V01 已含；單跑 redundant |
| `./gradlew compileTestJava` | V01 已含 compile |
| `./gradlew cyclonedxBom` | SBOM 為 ship artifact，非 quality gate |
| `./gradlew bootBuildImage` | 容器 build，慢；非 PR gate |
| `cd frontend && npm run coverage` | `package.json` 無此 script + `@vitest/coverage-v8` 未裝；待 frontend coverage spec |
```

### 4.2 `scripts/verify-all.sh`（structure；完整實作於 §6 Task）

```bash
#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Skills Hub — Deterministic Verification Runner (S020)
# /verifying-quality Step 0.5 protocol：
#   - CRITICAL fail → exit !=0
#   - SKIP-if-unavailable → which-detect / dir-detect graceful skip
#   - timestamped log → backend/build/verify-all.log
#
# Usage:
#   ./scripts/verify-all.sh            # 跑全部
#   ./scripts/verify-all.sh --help     # 印 usage
#
# Exit:
#   0  全部 CRITICAL 通過
#   1  任一 CRITICAL 失敗
# -----------------------------------------------------------------------------
set -uo pipefail   # 注：不用 -e，逐 V0N 收結果

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${REPO_ROOT}/backend/build/verify-all.log"
mkdir -p "$(dirname "${LOG}")"
: > "${LOG}"

TS()      { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
log()     { echo "$@" | tee -a "${LOG}"; }
section() { log ""; log "=== $(TS) | $1 ==="; }

CRIT_FAIL=0
RESULTS=()   # bash 3.2 indexed array

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

# V02: parse jacoco CSV → display LINE coverage
JACOCO_CSV="${REPO_ROOT}/backend/build/reports/jacoco/test/jacocoTestReport.csv"
section "V02 [INFO] LINE coverage from jacocoTestReport.csv"
if [[ -f "${JACOCO_CSV}" ]]; then
  COV=$(awk -F, 'NR>1 {miss+=$8; cov+=$9} END {
    if (miss+cov == 0) print "n/a";
    else printf "%.1f%% (covered=%d / total=%d)", 100.0*cov/(miss+cov), cov, miss+cov
  }' "${JACOCO_CSV}")
  log "▸ V02 [info]: LINE coverage = ${COV}"; RESULTS+=("V02=INFO")
else
  log "▸ V02: SKIP - jacocoTestReport.csv not found"; RESULTS+=("V02=SKIP")
fi

# V03: jacoco coverage verification gate (skip if S019 not yet shipped)
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
log "▸ Results: $(printf '%s ' "${RESULTS[@]}")"
log "▸ Counts:  PASS=${PASS}, FAIL=${FAIL}, SKIP=${SKIP}"

if [[ ${CRIT_FAIL} -gt 0 ]]; then
  log "▸ Verdict: ❌ ${CRIT_FAIL} CRITICAL failure(s); exit=1"; exit 1
fi
log "▸ Verdict: ✅ all CRITICAL passed; exit=0"; exit 0
```

### 4.3 Log file format example（成功路徑）

```
=== 2026-04-27T08:30:01Z | V01 [CRITICAL] ./gradlew clean test jacocoTestReport ===
> Task :compileJava UP-TO-DATE
> Task :test
...
115 tests, 0 failures
BUILD SUCCESSFUL in 1m 32s
▸ V01: PASS

=== 2026-04-27T08:31:35Z | V02 [INFO] LINE coverage from jacocoTestReport.csv ===
▸ V02 [info]: LINE coverage = 84.2% (covered=2103 / total=2497)

=== 2026-04-27T08:31:35Z | V03 [CRITICAL/skip-if-unavailable] ./gradlew jacocoTestCoverageVerification ===
> Task :jacocoTestCoverageVerification
BUILD SUCCESSFUL in 12s
▸ V03: PASS

=== 2026-04-27T08:31:48Z | V04 [CRITICAL/skip-if-unavailable] cd frontend && npm test ===
> frontend@0.0.0 test
> vitest run
...
▸ V04: PASS

=== 2026-04-27T08:32:03Z | V05 [CRITICAL/skip-if-unavailable] cd frontend && npm run lint ===
...
▸ V05: PASS

=== 2026-04-27T08:32:10Z | Summary ===
▸ Results: V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS
▸ Counts:  PASS=4, FAIL=0, SKIP=0
▸ Verdict: ✅ all CRITICAL passed; exit=0
```

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `scripts/verify-all.sh` | new | per §4.2；`chmod +x`；bash 3.2 portable |
| `docs/grimo/qa-strategy.md` | modify | 緊接 §Verification Pipeline（line ~14）之後新增 `## Verification Command Registry` section（per §4.1）；含主 table + Known Limitations sub-table + 不 enroll 命令 sub-table |
| `docs/grimo/specs/spec-roadmap.md` | modify | S020 status `🔲 Planning → ⏳ Design`；ship 時 → `✅`；M17 進度更新 |
| `docs/grimo/specs/2026-04-27-S020-verify-registry-and-script.md` | new | 本 spec 檔案 |
| **跨 spec 同步**：`docs/grimo/specs/2026-04-27-S019-jacoco-coverage-gate.md` §4.1 + §3 AC-2 | modify（本 spec 寫入時即時補）| §4.1 加 `csv.required = true` 到 `tasks.jacocoTestReport.reports` block；§3 AC-2 expected 加上 `csv` 報告也存在 |

### 不動的檔案

| File | 原因 |
|------|------|
| `backend/build.gradle.kts` | 本 spec 不動；S019 處理 JaCoCo 配置（含 csv.required） |
| `frontend/package.json` | 本 spec 不動；frontend coverage script 留未來 spec |
| `CLAUDE.md` §Build commands | 不阻擋 ship；建議 ship 時 documentary update 加 `./scripts/verify-all.sh`，但不在本 spec AC 範圍 |
| `.gitignore` | `build/verify-all.log` 自動隨 `build/` ignore（既有 pattern） |

### 不在本 spec 範圍

- Frontend coverage tooling 安裝（`@vitest/coverage-v8` + `npm run coverage` script）→ 未來獨立 spec
- CI integration（`.github/workflows/`）→ 未來獨立 spec；本 spec 只建 verify-all.sh，CI YAML 是包裝層
- AOT bug 修復 / OpenTelemetry observability 切換 → 獨立 spec
- Backend lint（Checkstyle / Spotless）→ 未來獨立 spec（目前無 backend lint config）

---

<!-- §6 Task Plan / §7 Implementation Results 由 /planning-tasks 補入 -->
