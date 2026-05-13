# S148f: cyclonedx-bom 3.2.4 nativeCompile 衝突修復 — 還原 SBOM 生成 + 不擋 native build

> Spec: S148f | Size: XS(3) — 可能因 plugin upgrade 結果漲 S | Status: 🗄️ Archived 2026-05-13（先不處理）｜原 ⏸ Deferred 2026-05-10；reactivate 觸發條件見下方第一段
> Date: 2026-05-09
> Origin: S148b POC v1+v2 失敗發現 — cyclonedx-bom 3.2.4 plugin 與 Gradle 9.4.1 nativeCompile task graph 互衝
> Depends On: S148b ✅（POC workaround 在位）

---

## ⏸ Deferred 2026-05-10 — POC Phase 1 H1 結果 + 戰略 reframe

### POC H1 結果（path A）

`org.cyclonedx.bom` plugin 最新版 = **3.2.4**（同既有版本）。Gradle Plugin Portal + Maven Central 都查過，**無 4.x release**。
- 上游 issue：[CycloneDX/cyclonedx-gradle-plugin#821](https://github.com/CycloneDX/cyclonedx-gradle-plugin/issues/821)（2026-04-06 開）— 同根因（Gradle 9.4.1 + maven-publish 變體 `Cannot mutate the artifacts`），狀態 open，無修復計畫
- **H1 verdict: REJECTED — 無上游可升路徑**

### 戰略 reframe — 為何 H2/H3 也不跑

`backend/build.gradle.kts` line 12 cyclonedx 註解狀態的實際影響：

```bash
$ grep -rn "cyclonedx\|sbom\|bom\.json" /Users/samzhu/workspace/github-samzhu/skills-hub/scripts/ /Users/samzhu/workspace/github-samzhu/skills-hub/cloudbuild.yaml /Users/samzhu/workspace/github-samzhu/skills-hub/.github/
# (無 output — 0 個 pipeline 在讀 bom.json)
```

```yaml
# cloudbuild.yaml — production 跑 Paketo JVM buildpack（非 native）
# BP_NATIVE_IMAGE=false
```

**`bom.json` 沒人讀；native binary 也沒在 production 跑。** 兩個都是「未來才需要」，今天沒卡到任何人。

POC §6 risk row 3 已早預警：「若觸發 path C wrapper script，spec scope 漲 S；考慮拖到 native deploy 真正啟用時再做」。H1 reject 後實質剩 path B/C，都漲 S，cost-benefit 不對齊。

### Reactivate 觸發條件（任一）

1. 上游 cyclonedx 4.x 發布（issue #821 解，重做 path A 即可）
2. 新 spec 要做 SBOM upload（Snyk / Dependency Track / etc）— 那時需求清楚會是「要送 X format 給 Y service」
3. 切 native production deploy（BP_NATIVE_IMAGE=true）— 那 spec 會 inherit 此 blocker

### 同步更新項

- `docs/grimo/architecture.md` (d) 段：「blocker → S148f」改成 「⏸ deferred + 三條 reactivate 觸發條件」
- `docs/grimo/specs/spec-roadmap.md` S148f row 狀態 📐 → ⏸ deferred
- 本 spec 檔 status line → ⏸ Deferred

---

---

## 1. Goal

**一句話：** 還原 `cyclonedx-bom` plugin（恢復 SBOM 生成）同時讓 `./gradlew nativeCompile` 仍能跑 — 兩者目前互斥，這 spec 解。

**為什麼重要：**
- **SBOM 缺檔**：S148b POC 期間 cyclonedx 暫註解 → supply chain audit / dependency vulnerability scanning（Snyk SBOM upload 等）目前無法跑
- **native deploy blocker**：cyclonedx 還原會立即擋住未來任何 nativeCompile 嘗試（POC 已驗）
- 不修 = 兩者擇一永久維持半殘狀態

**非目標：**
- 不換掉 SBOM 整個生態（CycloneDX 本身是業界標準）
- 不引入新 BOM 格式（SPDX 等）— 先試最小改動

---

## 2. Approach

**POC: required** — 三條候選 path，需先驗證才能挑。

### 2.1 既知症狀（S148b POC v1/v2 log）

```
V1（plugin enable, 跑 nativeCompile）:
> Could not create task ':cyclonedxDirectBom'.
  Cannot mutate the artifacts of configuration ':cyclonedxDirectBom'
  after the configuration was consumed as a variant.

V2（-x cyclonedxBom -x cyclonedxDirectBom）:
> Querying the mapped value of task ':cyclonedxBom' property 'jsonOutput'
  before task ':cyclonedxBom' has completed is not supported
```

Root cause（推測）：cyclonedx-bom 3.2.4 自動 hook 進 `processResources`，建 lazy task dependency；nativeCompile task graph 觸發配置時走錯順序，要 mutate 已 consume 的 variant artifact。

### 2.2 三條候選 path

| Path | 動作 | Pros | Cons |
|------|------|------|------|
| **A** | 升級 cyclonedx-gradle-plugin 4.x（若 release） | 最少改動；仍是 CycloneDX 標準 | 需 verify 4.x exists + 確實修了 issue |
| **B** | 換 SPDX SBOM gradle plugin | SPDX 格式也是業界標準（NTIA 推） | 換工具學習；既有 supply chain pipeline 若預期 CycloneDX 格式要重設 |
| **C** | 保留 cyclonedx 3.2.4 + 隔離 task graph | 不改 dep；用 Gradle 命令分離（nativeCompile 跑前先 disable cyclonedx；或 bash wrapper script 控） | dirty workaround；CI 配置複雜 |

### 2.3 POC 計畫（/planning-tasks Phase 1 執行）

**Hypothesis 1：** cyclonedx-gradle-plugin 4.x 已 release 且修了這 issue。

**測試方法：**
```bash
# Step 1 — 查 Maven Central / GitHub
# https://github.com/CycloneDX/cyclonedx-gradle-plugin/releases
# 確認最新版本 (預期 4.x 或更新 3.x)

# Step 2 — 升級 build.gradle.kts plugin version
id("org.cyclonedx.bom") version "<latest>"

# Step 3 — 還原 plugin enable
# Step 4 — 跑 nativeCompile
cd backend && JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.1-graalce \
  ./gradlew nativeCompile -PexactReachability=true --no-daemon
# Pass = 升級即解（A path）
# Fail = 仍同 error → POC H2 (path B 或 C)
```

**Hypothesis 2（H1 fail 時）：** SPDX gradle plugin 跟 nativeCompile 不衝突。

**測試方法：** 加 `org.spdx.sbom-gradle-plugin` plugin → 跑 nativeCompile → 觀察是否 BUILD SUCCESSFUL。

**Hypothesis 3（H1+H2 都 fail）：** 隔離 task graph workaround 可行。

**測試方法：** Gradle script 條件性 disable cyclonedx 在 nativeCompile 跑時。

### 2.4 驗證 path 後的 ship 範圍

per POC verdict 決定範圍：
- H1 PASS → 單純 plugin 升級 + 還原 enable → XS(2)
- H1 FAIL + H2 PASS → 換 SPDX plugin + 既有 pipeline 對齊（如果有）→ S(5)
- 全 FAIL → C path workaround → S(4) + 留長期 tech debt

---

## 3. Acceptance Criteria

```
AC-1: POC 確認 path（A/B/C 擇一）
  Given /planning-tasks Phase 1 跑 POC H1-H3
  When 找到 BUILD SUCCESSFUL 的 path
  Then 寫進 spec §6 POC Findings；剩餘 AC 按該 path 設定

AC-2: build.gradle.kts cyclonedx plugin 還原 enable
  Given S148b 暫註解 line 10 `id("org.cyclonedx.bom")`
  When 套用 chosen path（A/B/C）
  Then plugin/task 重新生效（line 10 uncomment 或 換新 plugin id）

AC-3: ./gradlew nativeCompile 仍可跑
  Given AC-2 套用後
  When `cd backend && ./gradlew nativeCompile -PexactReachability=true`
  Then BUILD SUCCESSFUL（如 S148b POC v4 結果）
  And native binary `backend/build/native/nativeCompile/skillshub` 產出

AC-4: SBOM artifact 產出
  Given chosen path 跑完
  When `./gradlew cyclonedxBom`（path A）or `./gradlew spdxSbom`（path B）or wrapper script（path C）
  Then `backend/build/reports/bom.json` 或對應路徑出 SBOM file
  And SBOM 內容含 spring-boot / spring-modulith / postgresql 等 main deps

AC-5: 一般 build 不影響
  Given 跑 `./gradlew build`（normal 流程）
  Then BUILD SUCCESSFUL，無新 warning / error

AC-6: docs/grimo/architecture.md 更新
  Given S148b T01 已加「GraalVM AOT Strategy」段含「cyclonedx-bom 3.2.4 衝突 — 追蹤 S148f」
  When S148f ship
  Then 更新該段，移除 blocker 標記，記錄 chosen path
```

**驗證指令：** `cd backend && ./gradlew build && ./gradlew nativeCompile -PexactReachability=true`

---

## 4. Files to Change

per POC chosen path：

### Path A（升級 plugin）

| 檔案 | 變動 |
|------|------|
| `backend/build.gradle.kts` line 10 | `id("org.cyclonedx.bom") version "<new>"` 還原 + 升版 |
| `docs/grimo/architecture.md` GraalVM AOT 段 | 移 cyclonedx blocker 標記 |

### Path B（換 SPDX）

| 檔案 | 變動 |
|------|------|
| `backend/build.gradle.kts` line 10 | 移除 cyclonedx plugin；加 `id("org.spdx.sbom-gradle-plugin") version "<x>"` |
| `backend/build.gradle.kts` 末段 | 加 `spdxSbom { ... }` 配置 |
| 既有 SBOM consumer pipeline（若有 — Snyk upload step etc）| 改 SBOM 路徑 + 格式 |
| `docs/grimo/architecture.md` | 同上 + dep table 更新 plugin row |

### Path C（隔離 task graph）

| 檔案 | 變動 |
|------|------|
| `backend/build.gradle.kts` line 10 | 還原 cyclonedx；加 conditional disable for nativeCompile |
| 或 `scripts/gcp/native-build.sh` | wrapper script `./gradlew :backend:cyclonedxBom :backend:nativeCompile`（分兩 invoke）|
| `docs/grimo/architecture.md` | 記錄 known limitation + workaround 用法 |

---

## 5. Test Plan

### 5.1 自動化

無 — Gradle build 本身即 test（AC-3 + AC-5 驗 build pass）。

### 5.2 手動

| AC | 驗證方式 |
|----|---------|
| AC-1 | POC 跑完，verdict 寫 §6 |
| AC-2 | grep `org.cyclonedx.bom\|spdx` build.gradle.kts |
| AC-3 | `./gradlew nativeCompile -PexactReachability=true` BUILD SUCCESSFUL |
| AC-4 | `find backend/build -name "*.bom*" -o -name "*sbom*"` 有檔 |
| AC-5 | `./gradlew build` BUILD SUCCESSFUL |
| AC-6 | reviewer 確認 architecture.md 更新 |

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| cyclonedx 4.x 不存在或仍有同 bug | POC H2 切 SPDX；H3 backup 走 C path workaround |
| SPDX 格式 downstream pipeline 不接受 | 確認既有 pipeline；若 lock-in CycloneDX 則 path B 不可行，回 path C |
| Path C wrapper script CI 配置複雜 | 若觸發此 path，spec scope 漲 S；考慮拖到 native deploy 真正啟用時再做 |
| 升級 plugin 引入其他 incompat | POC 期間 catch；若觸發改 conservative 升小步（3.2.4 → 3.3 → 4.x）|

---

## 7. 後續（不在本 spec）

- ship native production deploy（依賴本 spec ship；目前 BP_NATIVE_IMAGE=false）— 獨立 spec 評估
- SBOM 自動 upload 到 supply chain registry（如 Snyk / Dependency Track）— 若既有無，開新 spec
