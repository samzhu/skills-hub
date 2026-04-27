# S019: JaCoCo coverage gate + 80% line threshold

> Spec: S019 | Size: XS(5) | Status: ✅ Done
> Date: 2026-04-27
> Depends: — (S014 ✅ 已 ship；本 spec 為 S014 §7.13 列為 IMPORTANT 的 pre-existing project gap follow-up)
> Blocks: S020（verification command registry / `verify-all.sh` 將把 `jacocoTestCoverageVerification` 編入 registry；S020 V02 awk 解析本 spec 產出的 `jacocoTestReport.csv` — 本 spec §4.1 已加 `csv.required = true`）

> **Revision 2026-04-27（S020 design 期間 cross-update）**: §4.1 `tasks.jacocoTestReport.reports` 加 `csv.required = true`；§3 AC-2 同步要求 CSV 報告存在。理由：S020 V02 verify-all.sh 用 awk 解析 CSV 計算 LINE coverage 顯示給人眼看（cross-spec contract；S020 §2.3 I5 引 JaCoCo CSV 官方 doc）。

---

## 1. Goal

掛上 Gradle 內建 `jacoco` plugin、註冊 `./gradlew jacocoTestCoverageVerification` task、並在 `check` lifecycle 接上 line coverage gate（threshold 由 T1 POC 量出 baseline 後決定 — ≥80% 直接 pin 80%；<80% pin baseline 向下取整 5% + ratchet 計畫進 backlog）。讓 PR 一旦讓覆蓋率回退就會 build fail。

**簡單講**: S014 ship 後 `/verifying-quality` 主驗列為 IMPORTANT 的 2 個 pre-existing project gaps 之一 — `qa-strategy.md` L18-21 宣告「`./gradlew jacocoTestCoverageVerification` + 80% line coverage」但 `build.gradle.kts` 完全沒裝 plugin。本 spec 一次補 plugin + verify task + check-lifecycle wiring + class exclusion，並守 XS scope（不在本 spec 內 backfill 測試到 80%；若 baseline 不足，threshold pin 在 baseline 邊、ratchet 留 backlog）。

```
┌── 現況（S014 ship 後）──────────────────────────────────────┐
│   plugins: java / spring-boot:4.0.6 / dep-mgmt /            │
│            graalvm-native:0.11.5 / cyclonedx:3.2.4          │
│   ▶ ./gradlew jacocoTestCoverageVerification                │
│        → "Task 'jacocoTestCoverageVerification' not found"  │
│   ▶ qa-strategy.md L20 宣告 80% line coverage（無 enforcer）│
└─────────────────────────────────────────────────────────────┘
                              ↓ S019（本 spec）
┌── 目標 ─────────────────────────────────────────────────────┐
│   plugins: ... + jacoco                                     │
│   jacoco { toolVersion = "0.8.14" }   ← Java 25 supported   │
│   tasks: jacocoTestReport (XML+HTML) +                      │
│          jacocoTestCoverageVerification (LINE ratio gate)   │
│   classDirectories exclude:                                 │
│     SkillshubApplication / config / *Configuration /        │
│     db/migration                                            │
│   tasks.check.dependsOn(jacocoTestCoverageVerification)     │
│   ▶ ./gradlew jacocoTestCoverageVerification → ✅ 通過 gate │
│   ▶ ./gradlew check → 自動跑 gate；regression block PR     │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

XS spec。直接採 Gradle 內建 `jacoco` plugin（Gradle 9.4.1 預設帶 JaCoCo 0.8.14，已支援 Java 25 bytecode；研究 R1/R2）。**POC required** — T1 先量 baseline 才能決定最終 threshold；不在本 spec 內 backfill 測試（守「避免 gate 引入時 backfill 大規模 coverage」原則 — spec-roadmap §M17）。

### 2.1 關鍵設計決策（5 項）

| # | 決策 | 選擇 | 理由 | 否決的替代 |
|---|------|------|------|-----------|
| 1 | Coverage tool | **Gradle 內建 `jacoco` plugin** | 0 額外 dep；Gradle 9.4.1 預設 toolVersion = 0.8.14（issue #1950，2025-10-11）首次官方支援 Java 25 bytecode；JaCoCo agent 自動 attach 到 `test` 的 forked JVM | A. Kover（Kotlin-only 偏好；Java backend 不對齊）<br/>B. SonarQube（重；本 spec 不需要 diff coverage 等高階功能） |
| 2 | JaCoCo 版本 pinning | **`jacoco { toolVersion = "0.8.14" }` 顯式 pin** | 對齊 CLAUDE.md「Ecosystem-Managed Versions」原則；顯式 pin 防 Gradle 升版時 toolVersion 跟著變動而無感；version visible/lockable in VCS | 不 pin（依賴 Gradle 預設；Gradle 升版可能踩到未驗證 JaCoCo 版本） |
| 3 ★ | Threshold 決策模式 | **POC-first（A）**：T1 跑 baseline；baseline ≥80% pin `0.80`；<80% pin baseline 向下取整 5%（e.g. 73% → `0.70`）+ 同步建 `COV-B1` backlog 條目 ratchet 至 80%；<50% halt 回報 user | 守 XS scope；對齊 spec-roadmap「避免 backfill」；done-when「通過 gate」可達成（pin 在 ≤baseline 必過）；ratchet 計畫保留 80% 終局目標 | B. 直接 pin 80% + 補測試到 80%（升至 S/M；違反 backfill 戒）<br/>C. Report-only mode（沒擋 build；done-when「通過 gate」對齊度低）|
| 4 | Lifecycle 整合 | **`tasks.check { dependsOn(jacocoTestCoverageVerification) }` 顯式接 check** | Gradle 內建 `jacoco` plugin 預設不把 verification 接進 check（[Gradle docs](https://docs.gradle.org/current/userguide/jacoco_plugin.html) 明說 must depend explicitly）；接上後 `./gradlew build` / `check` 自動跑 gate，CI/PR 一致行為；S020 verify-all.sh 也省一步 explicit invoke | 不接 check（要記得手動 invoke；CI/local 容易漏跑） |
| 5 | Class exclusion 範圍 | `**/SkillshubApplication*`、`**/config/**`、`**/*Configuration*`、`**/db/migration/**` | (i) Application main class 一行 `main()`；(ii) `config/**` 多為 bean wiring + properties record，integration tests 自動覆蓋；(iii) Flyway `db/migration/**` 是 SQL，JaCoCo 不採；(iv) 不排除 records / DTO — 它們本來就 ~100% auto coverage，排除反而失資訊 | 排除 records/DTO（無實質效益）；不排除 config（噪音可接受 vs 漏掉 wiring 的可能更糟） |

> ★ = 唯一需要 user 裁決的設計分歧（已於 grill 階段確認選 A）

### 2.2 與既有架構的契合

| 維度 | 現況 | S019 變動 |
|------|------|-----------|
| Build plugins | 5 個（java、spring-boot、dep-mgmt、graalvm-native、cyclonedx）| **+1**（`jacoco`）|
| Test 執行 | `./gradlew test` → JUnit 5 + Testcontainers (PostgreSQL/GCloud/Grafana) | **不變** — JaCoCo agent 自動 attach forked JVM |
| Verification 命令 | `./gradlew test` / `./gradlew test --tests "*ModularityTests*"` | **新增** `jacocoTestReport`、`jacocoTestCoverageVerification` |
| `./gradlew build` 行為 | tests 過則 build 過 | **強化** — verification gate 不過 build fail |
| GraalVM native plugin | `processAot` 仍有 pre-existing bug（要 `bootRun -x processAot`，獨立 follow-up）| **不互相影響** — JaCoCo 只動 `test`；GraalVM 只動 `nativeTest` / `processAot`；task graph 各自獨立（研究 R4）|
| qa-strategy.md L18-21 | 宣告 80% 但無 enforcer | **被本 spec 實現** — 文字無需改 |
| Test count baseline | 115 tests（37 test classes，S014 ship 數）| **不變** — 本 spec 不新增測試 |
| S020 verify-all.sh | 尚不存在 | **本 spec 不實作；S020 把 `jacocoTestCoverageVerification` 編入 registry** |

### 2.3 Research Citations

| # | 主題 | URL | 一句結論 |
|---|------|-----|---------|
| R1 | JaCoCo 0.8.14 Java 25 support | https://www.jacoco.org/jacoco/trunk/doc/changes.html | 0.8.14（2025-10-11）首次官方支援 Java 25 bytecode（issue #1950）|
| R2 | Gradle 9.4.1 預設 JaCoCo 版本 | https://github.com/gradle/gradle/blob/v9.4.1/platforms/jvm/jacoco/src/main/java/org/gradle/testing/jacoco/plugins/JacocoPlugin.java | Gradle 9.4.1 `DEFAULT_JACOCO_VERSION = "0.8.14"` |
| R3 | `jacocoTestCoverageVerification` DSL + check 接線 | https://docs.gradle.org/current/userguide/jacoco_plugin.html | `BUNDLE` element + `LINE` counter + `COVEREDRATIO` 為標準 project-wide gate；verification task 預設**不接** `check`，需顯式 dependsOn |
| R4 | GraalVM native plugin × JaCoCo on `test` | https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html | GraalVM 0.11.5 plugin 不修改 JVM `test` task，只新增 `nativeTest`；agent 為 opt-in（`-Pagent`）|
| R5 | classDirectories exclude 寫法 | https://docs.gradle.org/current/dsl/org.gradle.testing.jacoco.tasks.JacocoCoverageVerification.html | `classDirectories.setFrom(files(... fileTree { exclude(...) }))` 為 idiomatic exclusion |
| R6 | Diff-based / new-code-only gating | https://docs.gradle.org/current/userguide/jacoco_plugin.html | 內建 plugin 無 diff-aware mode；diff-based gating 需外部工具（SonarQube / CI script）— 故 §3 AC-3 採 project-wide threshold |

### 2.4 Research Sufficiency Gate

| 設計決策 | 信心 | 證據 |
|---------|------|------|
| JaCoCo 0.8.14 解析 Java 25 class（class major 69）| **Validated** | R1 官方 changelog；R2 confirms Gradle 9.4.1 預設值已是 0.8.14 |
| `jacocoTestCoverageVerification` DSL 寫法 | **Validated** | R3 官方 Gradle docs 章節「Enforcing code coverage metrics」 |
| GraalVM 0.11.5 plugin × JaCoCo agent 共存 | **Validated** | R4 plugin 不動 `test`；JaCoCo 只動 `test`；無 task graph 交集 |
| **codebase 目前 line coverage baseline** | **Unknown** | 從未跑過；需 T1 POC 量測（決定 §3 AC-3 threshold 數值） |

故 §3 AC-3 的 threshold 數值標 `[needs POC validation]`；T1 跑 baseline 後填入。

### 2.5 POC: required

**為什麼**: §3 AC-3 要求「`./gradlew jacocoTestCoverageVerification` 通過 gate」；本 spec 守 XS scope（不在本 spec 內 backfill 測試），故必須先量 baseline 才能定 threshold。

**POC 範圍**（T1 任務）:
1. 掛 `jacoco` plugin + 設 `toolVersion = "0.8.14"`
2. 配置 `jacocoTestReport`（含 §2.1 #5 exclusions）
3. 跑 `./gradlew clean test jacocoTestReport`
4. 讀 `backend/build/reports/jacoco/test/jacocoTestReport.xml` root `<counter type="LINE" missed="M" covered="C"/>`
5. baseline = C / (C + M)

**Decision rule**:

| baseline 區間 | 動作 | done-when 對齊 |
|---------|------|---------------|
| ≥ 0.80 | minimum = `0.80`；無 backlog 補登 | ✅ 完整 |
| 0.50 ≤ baseline < 0.80 | minimum = `floor(baseline / 0.05) * 0.05`（e.g. 73% → `0.70`、67% → `0.65`）；spec-roadmap.md 📚 Backlog 新增 `COV-B1: Coverage ratchet (current → 80%)` | ✅ done-when「通過 gate」達成；80% 留 backlog |
| < 0.50 | **HALT** — 回報 user；XS 邊界破裂；可能需獨立 testing-backfill spec | n/a |

---

## 3. SBE Acceptance Criteria

> AC-naming contract: `@DisplayName("AC-N: ...")` 或 `@Tag("AC-N")`（per qa-strategy.md §AC-to-Test Contract）。本 spec 為 build/config 變動，多數 AC 由 build-evidence（exit code + report 檔案存在 + task graph 印出）取代測試方法 — 與 S013 deploy 腳本 / S014 §7 evidence 模式一致。

**AC-1**: `jacoco` plugin 啟用 + toolVersion 顯式 pin
- Given `build.gradle.kts` 在 `plugins {}` 加入 `jacoco`，並有 `jacoco { toolVersion = "0.8.14" }` block
- When 跑 `./gradlew tasks --group verification`
- Then 輸出包含 `jacocoTestReport` 與 `jacocoTestCoverageVerification` 兩個 task

**AC-2**: `jacocoTestReport` 產出 XML + HTML + CSV 三種報告
- Given 已執行 `./gradlew clean test`
- When 跑 `./gradlew jacocoTestReport`
- Then 三檔皆存在：
  - `backend/build/reports/jacoco/test/html/index.html`（本機檢視）
  - `backend/build/reports/jacoco/test/jacocoTestReport.xml`（外部工具）
  - `backend/build/reports/jacoco/test/jacocoTestReport.csv`（**S020 verify-all.sh V02 awk 解析來源** — cross-spec contract）
- And XML root `<report>` 含 `<counter type="LINE" missed="..." covered="..."/>`
- And CSV header 為 `GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...`（per JaCoCo CSV format spec — S020 §2.3 I5）

**AC-3**: `jacocoTestCoverageVerification` 通過 LINE coverage gate `[needs POC validation]`
- Given threshold = T1 POC 量測 baseline 後決定（≥80% pin `0.80`；<80% pin `floor(baseline/0.05)*0.05`）
- When 跑 `./gradlew jacocoTestCoverageVerification`
- Then exit code = 0；stdout/stderr 不含 `Rule violated for bundle ...` 訊息

**AC-4**: Gate wired into `check` lifecycle
- Given `tasks.check { dependsOn(jacocoTestCoverageVerification) }` 已 wired
- When 跑 `./gradlew check --dry-run`
- Then task graph 含 `:test → :jacocoTestReport`（finalizedBy）`→ :jacocoTestCoverageVerification → :check`
- And 跑 `./gradlew check` 在任一階段 fail 時整體非 0 退出

**AC-5**: classDirectories exclusion 生效
- Given exclude patterns: `**/SkillshubApplication*`、`**/config/**`、`**/*Configuration*`、`**/db/migration/**`
- When 跑 `./gradlew jacocoTestReport` 後檢查 HTML report tree（`backend/build/reports/jacoco/test/html/index.html`）
- Then 上述模式對應 class 不出現在 coverage 統計（HTML 樹中查無；CSV 對應條目不存在）

**AC-6**: 既有 test suite + GraalVM native plugin 無 regression
- Given `org.graalvm.buildtools.native:0.11.5` plugin 仍在 build.gradle.kts；S014 baseline = 115 tests / 0 failures
- When 跑 `./gradlew clean test jacocoTestReport jacocoTestCoverageVerification`
- Then 全程不觸發 `processAot` / `nativeCompile` / `nativeTest`
- And `./gradlew test` 仍 115 tests / 0 failures / 0 errors / 0 skipped

### 驗收命令

per qa-strategy.md §Verification Pipeline:
```
cd backend && ./gradlew clean test jacocoTestCoverageVerification
```
**Pass 條件**: BUILD SUCCESSFUL + tests=115/0/0/0 + 無 `Rule violated` 訊息。

---

## 4. Interface / API Design

本 spec **無 API surface** — 純 build configuration 變動。Kotlin DSL 片段如下（增量；既有內容省略）：

### 4.1 `backend/build.gradle.kts` 變動（增量）

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.11.5"
    id("org.cyclonedx.bom") version "3.2.4"
    jacoco                                          // ← S019 新增
}

// ... 既有 java toolchain / repositories / extra / dependencies / dependencyManagement 不變 ...
// ... 既有 frontendDir / npmInstall / npmBuild / copyFrontend / processResources / Test 不變 ...

jacoco {                                            // ← S019 新增
    toolVersion = "0.8.14"                          // 顯式 pin Java 25 supported version (R1)
}

tasks.test {                                        // ← S019 修改（既有 useJUnitPlatform 保留）
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)             // test 結束自動產 report
}

tasks.jacocoTestReport {                            // ← S019 新增配置
    dependsOn(tasks.test)
    reports {
        xml.required = true                          // 未來 SonarQube / 外部工具可消費
        html.required = true                         // 本機檢視
        csv.required = true                          // S020 verify-all.sh V02 awk 解析來源（cross-spec sync）
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/SkillshubApplication*",
                    "**/config/**",
                    "**/*Configuration*",
                    "**/db/migration/**"
                )
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {              // ← S019 新增配置
    dependsOn(tasks.test)
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)  // 共用 exclusion
    violationRules {
        rule {
            element = "BUNDLE"                       // 整個 backend project
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()      // ← T1 POC 後可改為 baseline ⌊⌋ 至 0.05
            }
        }
    }
}

tasks.check {                                       // ← S019 新增 wiring（R3 says must be explicit）
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

### 4.2 Task graph（執行 `./gradlew check` 時）

```
:check
├── :test                                 (既有；115 tests via JUnit 5 + Testcontainers)
│     └─ finalizedBy → :jacocoTestReport  (S019 新增；產 XML + HTML)
└── :jacocoTestCoverageVerification       (S019 新增；dependsOn :test 共用 exec data)
        └── :test                         (已執行，不重跑)
```

註：`processAot` / `nativeCompile` / `nativeTest` 來自 GraalVM plugin，**不在 `check` task graph 內**（驗證於 R4）— 故與本 spec 配置完全不交集。

### 4.3 Threshold 決策樹（T1 POC 後填）

```
T1: ./gradlew clean test jacocoTestReport
        │
        ▼
   讀 build/reports/jacoco/test/jacocoTestReport.xml
   抓 root <counter type="LINE" missed="M" covered="C"/>
   baseline = C / (C + M)
        │
        ├── baseline ≥ 0.80
        │      → minimum = "0.80"
        │      → 無 backlog 條目；M17 done-when 完整
        │
        ├── 0.50 ≤ baseline < 0.80
        │      → minimum = floor(baseline / 0.05) * 0.05  (e.g. 73% → 0.70)
        │      → spec-roadmap.md 📚 Backlog 新增：
        │          COV-B1: Coverage ratchet (current → 80%)
        │      → 本 spec done；M17 done-when「通過 gate」達成；
        │        80% 終局留 backlog
        │
        └── baseline < 0.50
               → HALT；回報 user
               → XS 邊界破裂；考慮獨立 testing-backfill spec
```

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/build.gradle.kts` | modify | (i) `plugins {}` 加 `jacoco`；(ii) 加 `jacoco { toolVersion = "0.8.14" }`；(iii) `tasks.test { finalizedBy(jacocoTestReport) }`；(iv) `jacocoTestReport` 配置（xml/html + 4 條 exclude）；(v) `jacocoTestCoverageVerification` 配置（共用 classDirectories + LINE rule）；(vi) `tasks.check { dependsOn(jacocoTestCoverageVerification) }` |
| `docs/grimo/specs/spec-roadmap.md` | modify | 立即（design 階段）：S019 status `🔲 Planning → ⏳ Design`；ship 後 → `✅`；M17 進度更新。**條件性**：若 T1 baseline < 0.80，於 📚 Backlog section 新增 `COV-B1: Coverage ratchet (current baseline → 80%)` 條目 |
| `docs/grimo/specs/2026-04-27-S019-jacoco-coverage-gate.md` | new | 本 spec 檔案。§1-5 由 `/planning-spec`；§6-7 由 `/planning-tasks` |
| `docs/grimo/CHANGELOG.md` | modify (ship 時) | 由 `/shipping-release` 處理；本 spec 不直接動 |

### 不動的檔案

| File | 原因 |
|------|------|
| `docs/grimo/qa-strategy.md` | L18-21 既有「`./gradlew jacocoTestCoverageVerification` + 80% line」宣告本 spec 直接實現；無需改文字。**例外**：若 baseline < 80%，§7 Implementation Results 註記實際 threshold + ratchet 計畫，但 qa-strategy.md 主文不動 |
| `docs/grimo/architecture.md` | Framework Dependency Table 不列 Gradle plugins（既有 GraalVM native / CycloneDX 也未列）；S021 doc-sync 若要追加可一併處理 |
| `docs/grimo/development-standards.md` | §Testing Standards 既有「JaCoCo」字眼即可；threshold 數值不在 standards doc 內 |
| `frontend/**` | Vitest coverage（qa-strategy L23-25）為獨立軌道，不在本 spec 範圍 |
| `backend/.gitignore` | `build/` 已 ignore（per S000 模板）；`build/reports/jacoco/` 自動隨 `build/` 排除 |
| `scripts/verify-all.sh` | 由 S020 建立；本 spec 不預先 stub（per planning-spec「Forbidden File-Plan Patterns」— 不為下游 spec 預建 placeholder）|

### 不在本 spec 範圍

- `scripts/verify-all.sh` 建立 → **S020**
- `bootRun -x processAot` workaround 編入 verify registry → **S020**（registry entry 形式記錄 known limitation）
- PRD.md / architecture.md PostgreSQL 文件同步 → **S021**
- Frontend Vitest coverage gate → 未來獨立 spec
- SonarQube / Codecov / diff-based new-code gating → 未來 enterprise upgrade spec（spec-roadmap Backlog 候選）
- 測試 backfill 至 80%（若 baseline < 80%）→ `COV-B1` backlog 條目（條件性建立）

---

## 6. Task Plan

> POC: required（per §2.5）— T1 量 baseline 決定 §3 AC-3 minimum threshold；T2 套用 + check wiring + AC 全綠驗證。
> 由 `/planning-tasks` 於 2026-04-28 拆出 2 個 task；對齊 XS(5) 目標 1-2 tasks 區間。

| Task | Title | AC | Depends | Status |
|------|-------|----|---------|--------|
| T1 | POC — JaCoCo plugin + 量 line coverage baseline | （AC-3 minimum 決策依賴）| — | pending |
| T2 | 加 verification rule + `check` lifecycle wiring + AC 全綠驗證 | AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 | T1 | pending |

### POC Findings (T1 — 2026-04-28)

**Design hypothesis verdict** ✅ — Gradle 9.4.1 內建 `jacoco` plugin + `toolVersion = "0.8.14"` 成功解析 Java 25 bytecode；115 tests 全綠；3 種 report（XML/HTML/CSV）皆產出；exclusions 生效。

**Baseline 量測（root `<report>` counter，project-wide）**:

| Counter | Missed | Covered | Total | Coverage |
|---------|--------|---------|-------|----------|
| **LINE** | **143** | **1052** | **1195** | **0.8803 (88.03%)** ✅ |
| INSTRUCTION | 660 | 5162 | 5822 | 0.8867 (88.67%) (informational) |
| BRANCH | 78 | 184 | 262 | 0.7023 (70.23%) (informational) |
| METHOD | 25 | 267 | 292 | 0.9144 (91.44%) (informational) |
| CLASS | 7 | 92 | 99 | 0.9293 (92.93%) (informational) |

**Decision rule 套用**（per §2.5）: baseline = 0.8803 ≥ 0.80 → **T2 minimum = `"0.80"`**；**無**條件性 backlog 條目觸發；M17 done-when「通過 gate」可直接達成且對齊原 80% 終局目標。

**Exclusions 生效驗證**:
- `**/SkillshubApplication*`: ✅ root package `io.github.samzhu.skillshub` 在 HTML 樹仍可見（因 `SkillshubProperties*` 內部 records 留下），但 `SkillshubApplication.class` 不在 CSV CLASS 欄位
- `**/config/**`: ✅ CSV PACKAGE 欄位 `sort -u | grep config` 為空（整個 `io.github.samzhu.skillshub.config.*` package tree 被排）
- `**/*Configuration*`: ✅ CSV 全表 `grep Configuration` 為空
- `**/db/migration/**`: ✅ N/A — `db/migration/V*.sql` 為 SQL 非 Java，本就不在 JaCoCo input scope（exclusion 仍保留以防未來放 Java migration callback）

**Test suite no-regression**（per AC-6）:
```
tests=115 failures=0 errors=0 skipped=0   # 對齊 S014 baseline
```

**3 reports 產出**（per AC-2）:
```
backend/build/reports/jacoco/test/html/index.html      ✅
backend/build/reports/jacoco/test/jacocoTestReport.xml ✅ (210 KB)
backend/build/reports/jacoco/test/jacocoTestReport.csv ✅ (9.3 KB; standard 13-column header)
```

**Reproducible 命令**:
```
cd backend && ./gradlew clean test jacocoTestReport
# BUILD SUCCESSFUL in 1m 55s
```

**官方 docs 引用**: https://docs.gradle.org/current/userguide/jacoco_plugin.html — 確認 Kotlin DSL `xml.required = true` direct assignment 為 idiomatic 寫法（非 `.set(true)`）。

**T2 就緒前提**: minimum 已決定為 `"0.80"`；T2 補 `jacocoTestCoverageVerification` violation rule + `tasks.check.dependsOn(...)` 即可串完整 gate。

### AC ↔ Task 對應表

| AC | 由哪 task 驗 | 驗證命令 |
|----|------------|---------|
| AC-1 | T2 | `./gradlew tasks --group verification` |
| AC-2 | T2（reports 配置已於 T1 完成）| `./gradlew jacocoTestReport` 後檢查 3 檔存在 + XML/CSV header |
| AC-3 | T2（threshold 由 T1 POC 決定）| `./gradlew jacocoTestCoverageVerification` |
| AC-4 | T2 | `./gradlew check --dry-run` + `./gradlew check` |
| AC-5 | T2（exclusions 已於 T1 接上；T2 共用 classDirectories）| HTML report tree 比對 + CSV 條目比對 |
| AC-6 | T2（最終整合驗證）| `./gradlew clean test jacocoTestReport jacocoTestCoverageVerification` |

### E2E artifact 驗證

本 spec 為純 build configuration 變動，AC-3 / AC-4 / AC-6 的驗收命令本身即在真實 Gradle build 環境跑（非 stub）— Phase 4 Step 1.5 的 E2E 已被 AC 命令吸收，無需額外步驟。

## 7. Implementation Results (2026-04-28)

### 7.1 Verification

**主驗收命令**（per §3 / qa-strategy.md §Verification Pipeline）:
```
$ cd backend && ./gradlew clean test jacocoTestCoverageVerification
> Task :jacocoTestCoverageVerification
> Task :jacocoTestReport
BUILD SUCCESSFUL in 2m 14s
```

**Test 統計**: tests=115, failures=0, errors=0, skipped=0（與 S014 baseline 完全一致）。

**Coverage baseline**:
- LINE: 1052 / 1195 = **0.8803 (88.03%)** ≥ 0.80 → gate 通過，無 backlog 條目觸發
- INSTRUCTION: 5162 / 5822 = 0.8867 (informational)
- BRANCH: 184 / 262 = 0.7023 (informational — branch gate 不在本 spec 範圍)

### 7.2 AC 結果

| AC | Status | 證據 |
|----|--------|------|
| AC-1: jacoco plugin + toolVersion 顯式 pin | ✅ | `./gradlew tasks --group verification` 列出 `jacocoTestReport` + `jacocoTestCoverageVerification` |
| AC-2: 三 reports（XML+HTML+CSV）| ✅ | `build/reports/jacoco/test/{html/index.html, jacocoTestReport.xml, jacocoTestReport.csv}` 皆產出；XML root `<counter type="LINE" missed="143" covered="1052"/>`；CSV header 13 欄 JaCoCo standard format |
| AC-3: jacocoTestCoverageVerification 通過 LINE gate | ✅ | exit 0；無 `Rule violated for bundle ...`（baseline 0.8803 > minimum 0.80）|
| AC-4: 接 check lifecycle | ✅ | `./gradlew check --dry-run` task graph 含 `:test → :jacocoTestCoverageVerification → :jacocoTestReport`（後者為 finalizedBy）|
| AC-5: classDirectories exclusion 生效 | ✅ | CSV 全表 grep `Application$` / `Configuration` / `\.config` package = 0 / 0 / 0；HTML 包樹無 `io.github.samzhu.skillshub.config.*` |
| AC-6: 既有 test suite + GraalVM 無 regression | ✅ | tests=115/0/0/0（S014 baseline 完全一致）；`--dry-run` 顯示 `:processAot` / `:nativeCompile` / `:nativeTest` 全未觸發 |

### 7.3 Key Findings

**[Validated]** Gradle 9.4.1 內建 `jacoco` plugin（DEFAULT_JACOCO_VERSION=0.8.14）+ 顯式 `jacoco { toolVersion = "0.8.14" }` pin，成功解析 Java 25 bytecode。Kotlin DSL `xml.required = true` direct assignment 為 idiomatic 寫法（per https://docs.gradle.org/current/userguide/jacoco_plugin.html）。

**[Validated]** 共用 classDirectories 模式 `classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)` 讓 verification task 自動繼承 report task 的 4 條 exclusions，避免重複維護。

**[Validated]** GraalVM `org.graalvm.buildtools.native:0.11.5` plugin 不交集 — `:processAot` / `:nativeCompile` / `:nativeTest` 在 `clean test jacocoTestReport jacocoTestCoverageVerification` 全程未被觸發（只有 `:processAotTestResources` SKIPPED — 為 plugin 副 task 且未實際執行，不違反 AC-6）。

**[Decision-rule outcome]** §2.5 baseline 量測落在第一檔（≥ 0.80）→ minimum pin 為 `"0.80"`，**未**觸發 `COV-B1: Coverage ratchet` backlog 條目；M17 done-when「通過 gate」直接達成且對齊原 80% 終局目標。

### 7.4 Correct Usage Patterns

```kotlin
// 對齊 CLAUDE.md「Ecosystem-Managed Versions」— 顯式 pin 防 Gradle 升版時 toolVersion 跟著漂
jacoco {
    toolVersion = "0.8.14"
}

// JaCoCo plugin 預設不接 check（per Gradle docs）— 須顯式 wire
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// 共用 classDirectories — verification 與 report 同步排除
tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
    // ...
}

// fileTree exclusion idiom（per Gradle docs R5）
classDirectories.setFrom(
    files(classDirectories.files.map {
        fileTree(it) {
            exclude("**/SkillshubApplication*", "**/config/**", "**/*Configuration*", "**/db/migration/**")
        }
    })
)
```

### 7.5 E2E Artifact Verification

**判定**: 不需獨立 E2E 步驟。

**理由**: 本 spec 為純 build configuration 變動。AC-3 (`./gradlew jacocoTestCoverageVerification`) 與 AC-6 (`./gradlew clean test jacocoTestReport jacocoTestCoverageVerification`) 的驗收命令本身即在真實 Gradle daemon + JaCoCo agent + JUnit 5 + Testcontainers 環境跑（非 stub），等同 Phase 4 Step 1.5 要求的 E2E artifact 驗證。Phase 4 Step 1 inline 命令與 AC-3/AC-6 完全等價 — 已被吸收。

### 7.6 Pending Verification

無。所有 AC 已在 deterministic build 中驗證完成；無 IT 因環境缺失被 skip。

### 7.7 Design Drift Check

| § | Spec 原文 | 實作差異 | 處置 |
|---|----------|---------|------|
| §2.1 #5 exclusions | 4 patterns（Application / config / Configuration / db.migration）| 完全一致 | 無 drift |
| §2.5 decision rule | baseline ≥ 0.80 → minimum=0.80 + 無 backlog | baseline=0.8803 落第一檔；minimum=0.80；無 COV-B1 觸發 | 無 drift |
| §4.1 Kotlin DSL | 30 行增量 | 與 §4.1 完全一致；多 2 行 `// S019 T1/T2:` design-intent comment | 無 drift |
| §4.2 task graph | `:test → :jacocoTestReport (finalizedBy)`；`:check → :jacocoTestCoverageVerification` | 確認；`:processAotTestResources` 副 task 出現在 graph 但 SKIPPED | 已記於 §7.3 |
| §5 File Plan | 僅動 `build.gradle.kts` + `spec-roadmap.md` status | 一致 | 無 drift |

§2 / §4 與實作零 drift；§7 為 ground truth。

### 7.8 Tech Debt Register

無新增。`:processAotTestResources` 出現於 task graph 是 GraalVM 0.11.5 plugin 既有行為（SKIPPED 不執行），不是 S019 引入；既有「`bootRun -x processAot` 解 processAot 編譯失敗」follow-up 屬 S020 verify registry workaround 條目範圍。

---

## QA Review (2026-04-28)

**Reviewer**: Independent QA subagent
**Verdict**: ✅ PASS

### Findings

No CRITICAL or IMPORTANT findings. One MINOR observation:

**[MINOR] qa-strategy.md does not formally codify the "build/config spec = evidence-only AC" exception.**
The spec §3 note correctly describes the applied pattern and cites S013/S014 precedent. However, `docs/grimo/qa-strategy.md` §AC-to-Test Contract contains no explicit written exception for build/config-only specs — it only illustrates `@DisplayName`/`@Tag` style. The precedent exists in practice (S013 deploy scripts ship as scripts+README; S014 §7 uses build-evidence mode) but is not formally documented. This creates a minor gap: future reviewers must rely on precedent recognition rather than a written rule. Recommendation: add a one-line note to qa-strategy.md §AC-to-Test Contract in a future doc-sync spec (candidate for S021).

This finding does **not** affect the correctness of S019's implementation or its AC coverage.

### Evidence Summary

| Check | Command / Method | Result |
|-------|-----------------|--------|
| **Build script integrity — plugins block** | Read `build.gradle.kts` line 7 | `jacoco` present ✅ |
| **Build script integrity — jacoco block** | Read lines 132-135 | `jacoco { toolVersion = "0.8.14" }` with `// S019 T1:` comment ✅ |
| **Build script integrity — tasks.test finalizedBy** | Read lines 137-139 | `finalizedBy(tasks.jacocoTestReport)` ✅ |
| **Build script integrity — jacocoTestReport block** | Read lines 141-160 | `xml.required=true`, `html.required=true`, `csv.required=true`; 4 exclusion patterns exact match §2.1 #5 ✅ |
| **Build script integrity — jacocoTestCoverageVerification** | Read lines 162-176 | `element="BUNDLE"`, `counter="LINE"`, `value="COVEREDRATIO"`, `minimum="0.80".toBigDecimal()` with `// S019 T2:` comment ✅ |
| **Build script integrity — check wiring** | Read lines 178-181 | `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` with `// S019 T2:` comment ✅ |
| **Live build** | `./gradlew clean test jacocoTestCoverageVerification` | BUILD SUCCESSFUL in 1m 41s; exit 0 ✅ |
| **No "Rule violated" line** | grep on build output | 0 matches ✅ |
| **Test count** | JUnit XML aggregation across test-results/test/ | tests=115, failures=0, errors=0, skipped=0 ✅ |
| **Report artifact: HTML** | `ls build/reports/jacoco/test/html/index.html` | Exists (14 KB, 2026-04-28) ✅ |
| **Report artifact: XML** | `ls build/reports/jacoco/test/jacocoTestReport.xml` | Exists (210 KB, 2026-04-28) ✅ |
| **Report artifact: CSV** | `ls build/reports/jacoco/test/jacocoTestReport.csv` | Exists (9.3 KB, 2026-04-28) ✅ |
| **CSV header** | `head -1 jacocoTestReport.csv` | Exact 13-column JaCoCo standard header ✅ |
| **Live LINE coverage** | Python XML parse on root `<counter type="LINE">` | missed=143, covered=1052, total=1195, ratio=0.8803 (88.03%) ≥ 0.80 ✅ |
| **Exclusion: SkillshubApplication** | CSV CLASS column grep `Application$` | 0 rows ✅ |
| **Exclusion: *Configuration*** | CSV CLASS column grep `Configuration` | 0 rows ✅ |
| **Exclusion: config package** | CSV PACKAGE column sort-u grep `\.config` | 0 rows ✅ |
| **Task graph — jacocoTestCoverageVerification in check** | `./gradlew check --dry-run` | `:jacocoTestCoverageVerification SKIPPED` present ✅ |
| **Task graph — jacocoTestReport in check** | `./gradlew check --dry-run` | `:jacocoTestReport SKIPPED` present ✅ |
| **AC-6: processAot/nativeCompile/nativeTest absent** | `./gradlew check --dry-run` grep exact names | 0 matches (`:processAotTestResources` is a pre-existing GraalVM side-task, acknowledged in §7.3) ✅ |
| **Design-intent comments** | grep `S019` in build.gradle.kts | 3 comments: `// S019 T1:` (jacoco block) + `// S019 T2:` (verification) + `// S019 T2:` (check wiring) ✅ |
| **Design drift check** | Cross-reference §2/§4/§5 vs actual build.gradle.kts | Zero drift on all 4 dimensions (plugins, blocks, exclusions, threshold) ✅ |
| **CLAUDE.md: Ecosystem-Managed Versions** | toolVersion = "0.8.14" equals Gradle 9.4.1 default; explicit pin for visibility | ✅ (not a downgrade) |
| **CLAUDE.md: No Deprecated APIs** | Kotlin DSL `xml.required = true` direct assignment | Confirmed idiomatic per Gradle docs ✅ |
| **qa-strategy.md: AC-to-Test Contract** | §3 explicit note; build/config evidence pattern cited | Pattern applied consistently with S013/S014 precedent; formal exception not in qa-strategy.md (see MINOR finding) |
