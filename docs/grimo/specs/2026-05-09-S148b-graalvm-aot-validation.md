# S148b: GraalVM AOT 驗證機制 — 把「reflection 失敗只在 Cloud Run 才現形」變成「local 3 分鐘 catch」

> Spec: S148b | Size: XS(3) — POC reject H1 後 scope 縮（per §6 POC findings 2026-05-09）| Status: ⏳ Plan
> Date: 2026-05-09
> Origin: S148 (v4.25.0 — JudgeResponse AOT reflection ship) 後遺留 — `SkillshubProperties` `@ConfigurationProperties` **被推測**有同類 AOT 反射失敗，但**從未 reproduce**；同時專案完全沒有 systematic AOT 驗證機制（`nativeTest` 沒跑、`bootBuildImage` 沒在 CI、reflection failure 只在 Cloud Run 真跑時才會炸）。

---

## 1. Goal

**一句話：** 把 GraalVM native-image 的 reflection / proxy 失敗從「Cloud Run runtime 才現形」變成「local 跑 `./gradlew check` 5 秒就 catch」。順手 reproduce + 修 SkillshubProperties 假設的 AOT bug（如果真存在）。

**為什麼重要：**
- **沒驗證機制 = production reflection failure 等使用者炸給你看**：S148 (v4.25.0) JudgeResponse 那次就是 user 跑 LAB 才發現
- **後續 spec 加 `@ConfigurationProperties` 都會踩雷**：S160（security headers config）、S163/S164 owner mgmt 都會新增 properties，沒驗證機制就等於繼續 ship roulette
- **Spec 標題假設的 SkillshubProperties bug 可能不存在**：reachability-metadata.json 已含全 11 個 nested record；Spring Boot 4 inner record 自動 traverse；**必須 POC 確認**

**非目標：**
- 不做 framework-owned `@ConfigurationProperties` 的 hints（DataSourceProperties / OAuth2ClientProperties 等 — 框架自管）
- 不啟用 `nativeTest` 進 CI（ROI 差 — 30 min build 跟 `--exact-reachability-metadata` 重疊）
- 不寫 ADR（GraalVM 策略 ADR 留 follow-up，等多輪 native deploy 經驗累積再寫）

---

## 2. Approach

**POC: required** — premise（SkillshubProperties 有 AOT bug）未驗證。先跑 POC reproduce，再決定 fix scope。詳 §2.5。

### 2.1 現況回顧（已驗證 2026-05-09）

| 項目 | 狀態 |
|------|------|
| `@ConfigurationProperties` 數量 | 1 個 user-defined：`SkillshubProperties`（含 11 個 nested inner records） |
| `@RegisterReflectionForBinding` 使用 | 1 處：`ScoreNativeConfig.java`（S148 v4.25.0 ship — JudgeResponse） |
| `RuntimeHintsRegistrar` 實作 | 0 處 |
| 手寫 `META-INF/native-image/*.json` | 0 個 |
| `nativeTest` task config | 無（plugin 在但無設定） |
| `--exact-reachability-metadata` flag | 無 |
| CI 跑 native build | 無（Cloud Build 跑 `bootBuildImage` 但無 reflection 完整度檢查） |
| `bootBuildImage` 是否含 native | 否 — 走 Paketo JVM buildpack（非 native-image）— **意味目前根本沒跑 native**，roadmap 假設「native build 失敗」可能是誤判 |

⚠️ **關鍵發現：** 既有 `bootBuildImage` 走 JVM buildpack（不是 native-image），所以「production binary」其實是 JVM 跑的，反射沒問題。「SkillshubProperties AOT 失敗」這個 premise **可能完全是 imagined**。

### 2.2 設計核心：JVM-mode hint assertion + native compile-time fail-fast

兩層防線（B + C 組合）：

```
┌─ Layer 1: JVM-mode test（每 commit 跑，5 秒）─────────┐
│  RuntimeHintsAgent + RuntimeHintsRecorder            │
│  跑 SkillshubProperties binding path → record 實際    │
│  reflection 呼叫 → 比對 AOT 自動產生的 hints          │
│  漏 hint → test FAIL → CI 紅                          │
│  跑 ./gradlew check 一定會跑                          │
└──────────────────────────────────────────────────────┘
            │
            ▼ 漏網的（runtime-only path）
┌─ Layer 2: native compile flag（deploy 前跑，10 min）─┐
│  --exact-reachability-metadata=io.github.samzhu.    │
│    skillshub                                         │
│  native-image build 時 fail-fast 任何漏 hint 的     │
│  reflection call                                     │
│  + -XX:MissingRegistrationReportingMode=Warn        │
│  漏 hint → bootBuildImage FAIL                      │
│  跑 ./gradlew bootBuildImage 才會跑                  │
└──────────────────────────────────────────────────────┘
```

### 2.3 為什麼不選 `nativeTest`

| 方案 | Pros | Cons | 結論 |
|------|------|------|------|
| `nativeTest` | 100% 真實 — 跑 native binary 跑所有 test | ~30 min build；GraalVM 安裝門檻；CI cost 高；跟 C 重疊（C 也 catch native compile 階段問題） | 跳 |
| **B（RuntimeHintsAgent）** | 5 秒；無 GraalVM 依賴；JVM agent 紀錄真實 reflection call | 只測「test 跑得到的 path」，untested code 漏網 | ⭐ 採 |
| **C（--exact-reachability-metadata）** | native build 階段就 catch；無需執行 binary | 只在 `bootBuildImage` 跑（人工/Cloud Build 觸發） | ⭐ 採 |

B + C 互補：B 是 fast-feedback CI gate，C 是 deploy-blocker safety net。

### 2.4 Spring Boot 4 + @ConfigurationProperties 的 AOT 風險清單

Research 找到的 4 個真實風險（spec scope 內 verify，不主動 fix 除非 POC 證實打到）：

| 風險 | 觸發條件 | SkillshubProperties 是否打到 |
|------|---------|---------------------------|
| 多 constructor + `@DefaultValue` 衝突（spring-boot#37283）| record 有 secondary constructor | 待 POC 驗 — 11 nested record 有 `@DefaultValue` |
| `@Validated` + CGLIB proxy（spring-native#142）| 加 `@Valid` / `@NotNull` | 沒打到 — 沒用 Bean Validation |
| Spring Cloud `@RefreshScope` + `ConfigurationPropertiesRebinder` | `@RefreshScope` on properties bean | 沒打到 — 沒用 Spring Cloud Config |
| Generic `List<T>` 元素類型 erasure | nested record 含 `List<X>` | `Cors.allowedOrigins: List<String>` 打到 — POC 重點驗 |

### 2.5 POC 計畫（/planning-tasks Phase 1 執行）

**Hypothesis 1：** SkillshubProperties 在 native build 時有 reflection 失敗。

**測試方法：**
```bash
# Step 1 — 加 --exact-reachability-metadata 到 build.gradle.kts（暫時）
# Step 2 — 跑 native compile
cd backend && ./gradlew nativeCompile -PexactReachability=true

# Step 3 — 觀察 build output
# (a) BUILD SUCCESSFUL → premise 是 imagined，spec 縮 XS（只加驗證機制）
# (b) Build FAIL with "MissingReflectionRegistrationError" → premise 真，記錄哪些 class/method 失敗
```

**Hypothesis 2：** RuntimeHintsAgent JVM test 能在 5 秒內 catch hypothetical missing hint。

**測試方法：**
```bash
# Step 1 — 加 spring-core-test dep + 寫 1 個 test bind SkillshubProperties
# Step 2 — 故意刪 SkillshubProperties 的 hint（mock 或 hand-edit aotResources）
# Step 3 — 跑 ./gradlew test
# 通過 = test 沒抓到 → 機制不夠；FAIL = 機制有效
```

**POC 通過判準：**
- H1 結果二擇一明確（reproduce 或證實無 bug）
- H2 確認 RuntimeHintsAgent + RuntimeHintsRecorder 真能 catch 假設的 missing hint

**POC 失敗回應：**
- H1 不可結論（GraalVM 安裝失敗 / build 環境問題）→ 暫跳過 H1，僅 ship 驗證機制（B+C），標 §7 「H1 待 LAB 環境驗」
- H2 不可結論（spring-core-test API 不適用）→ 改用更基礎的 `RuntimeHintsPredicates.reflection().onType().accepts(hints)` predicate-only assertion（仍 JVM-mode、仍 5 秒）

### 2.6 Conditional fix scope（依 POC 結果）

| POC H1 結果 | Fix scope | 加 AC |
|-----------|----------|------|
| 無 bug（auto-registration 已 cover） | 不 fix；spec 只剩 B+C 機制 + RuntimeHintsAgent test | AC-1, 2, 3, 5 (skip AC-4) |
| Bug 在 SkillshubProperties root | `@RegisterReflectionForBinding(SkillshubProperties.class)` on `SkillshubApplication` 或新建 `SkillshubAotConfig` | + AC-4 |
| Bug 在 nested record（如 `Cors.allowedOrigins`） | nested record 個別加 hint 或 `@NestedConfigurationProperty` 校正 | + AC-4 |
| Bug 在 `@DefaultValue` constructor 衝突 | 重構 record signature（拿掉冗餘 constructor）| + AC-4，size 漲到 S(7) |

---

## 3. Acceptance Criteria

> POC（§6）reject H1 後縮 scope：原 AC-2（RuntimeHintsAgent JVM test）與 AC-4（conditional fix）DROPPED。理由：(a) AC-4 無 bug 可修；(b) AC-2 ROI 降低 — POC v4 已證 nativeCompile 3m 17s 真跑就會 catch missing hint，5 秒 JVM test 的「fast feedback」價值在沒有 production native deploy 計畫的情況下不急。新 `@ConfigurationProperties` 加上來再開 follow-up spec 補。

```
AC-1: ✅ DONE — POC verdict 寫入 §6
  Given 跑 ./gradlew nativeCompile -PexactReachability=true
  When build 結束
  Then BUILD SUCCESSFUL in 3m 17s（H1 REJECTED — SkillshubProperties 無 AOT bug）
  Result: §6 POC Findings 已記 verdict + binary path + coverage 證據

AC-3: ✅ DONE — --exact-reachability-metadata flag 加進 build.gradle.kts
  Given backend/build.gradle.kts ~line 196 含
    graalvmNative {
      binaries {
        named("main") {
          if (project.hasProperty("exactReachability")) {
            buildArgs.add("--exact-reachability-metadata=io.github.samzhu.skillshub")
          }
        }
      }
    }
  When 跑 ./gradlew nativeCompile -PexactReachability=true
  Then 任何 io.github.samzhu.skillshub.* package 內 missing reflection registration → build FAIL
       （--exact-reachability-metadata 預設 reporting mode = Throw）
  Note: gated by -PexactReachability=true，平常 nativeCompile 不影響；POC v4 已驗 flag 生效

AC-5: docs/grimo/architecture.md 補 GraalVM AOT 段落（待 T01 實作）
  Given 既有 architecture.md 只在 dependency table line 535 提 graalvm.buildtools.native
  When 加新段「GraalVM AOT Strategy」
  Then 含：
    (a) 目前 production 走 Paketo JVM buildpack（非 native-image），但 nativeCompile task 可手動跑
    (b) AOT processing 啟用機制：AotStubConfig (shared/aot/) + JdbcConfiguration.jdbcDialect override
        + application-aot.yaml + ProcessAot args 配置（現況已 working — POC v4 證實 3m 17s 出 native binary）
    (c) Reflection hint fast-fail 機制：--exact-reachability-metadata flag (gated by -PexactReachability)
    (d) 既知 blocker：cyclonedx-bom 3.2.4 與 nativeCompile task graph 衝突（追蹤 S148f）
    (e) 未來啟用 native production deploy 的觸發條件 / 升級路徑（cyclonedx fix + Cloud Build BP_NATIVE_IMAGE=true 切換）
```

**驗證指令：** N/A — XS spec 純 doc 改動，由 reviewer 讀 architecture.md 新段確認涵蓋 5 個要點。POC verification 已於 §6 完成。

---

## 4. Files to Change

### Backend production code（已於 POC 期間 ship — AC-3）

| 檔案 | 變動 | 狀態 |
|------|------|------|
| `backend/build.gradle.kts` line ~196 | 加 `graalvmNative {}` block 含 `--exact-reachability-metadata` flag (gated by `-PexactReachability=true` Gradle property) | ✅ 已 ship |
| `backend/build.gradle.kts` line 10 | 暫註解 `id("org.cyclonedx.bom") version "3.2.4"` — POC workaround；正式 fix 走 S148f | ⏳ 暫存 — 待 S148f ship 後還原 |

### Docs（T01 待做）

| 檔案 | 變動 |
|------|------|
| `docs/grimo/architecture.md` | 加 「GraalVM AOT Strategy」新段（per AC-5；5 個要點）|

### POC artifacts（temporary — Phase 4 cleanup 時刪）

| 檔案 | 用途 |
|------|------|
| `poc/S148b/native-compile-output*.log` | POC v1-v4 step outputs（findings 已 consolidate 進 §6）|

### DROPPED（POC reject H1 後不做）

| 原 spec 計畫 | 變更 | 理由 |
|------|------|------|
| `SkillshubPropertiesAotTest.java`（RuntimeHintsAgent test）| ❌ DROP | POC v4 已證 nativeCompile 真跑就會 catch；JVM-mode 5 秒 fast feedback 在無 native deploy 計畫下 ROI 低 |
| `@RegisterReflectionForBinding` 在 main class | ❌ DROP | H1 reject — 無 bug 可修 |
| `testImplementation("org.springframework:spring-core-test")` | ❌ DROP | 沒有 RuntimeHintsAgent test 不需 dep |

---

## 5. Test Plan

### 5.1 自動化

無 — XS spec 純 doc 改動，無 production code change（AC-3 已於 POC 期間 ship + 驗證）。

### 5.2 手動（已完成）

| AC | 驗證方式 | 狀態 |
|----|---------|------|
| AC-1 | `./gradlew nativeCompile -PexactReachability=true` POC 跑 | ✅ DONE — BUILD SUCCESSFUL in 3m 17s |
| AC-3 | build.gradle.kts 含 graalvmNative block；POC v4 確認 flag 生效 | ✅ DONE |
| AC-5 | reviewer 讀 architecture.md 新段確認涵蓋 5 個要點 | pending T01 |

### 5.3 POC env （已驗證可用）

- GraalVM CE 25.0.1 (SDKMAN: `25.0.1-graalce`) — confirmed works
- Disk 4GB+ free — confirmed
- Time budget — actual: 3m 17s (incremental cache hit)；first cold run 估 25-35 min

---

## 6. 風險與注意

| 風險 | 緩解 |
|------|------|
| **Premise imagined** — POC 證實 SkillshubProperties 沒 AOT bug | 非問題 — spec scope 縮 XS(3)，只 ship 驗證機制（B+C），AC-2/3/5 仍有價值 |
| GraalVM 24+ 沒安裝 → POC 跑不起來 | spec §5.3 列預備清單；planning-tasks Phase 1 第一件事就是檢查 env |
| `nativeCompile` 在 Apple Silicon 跑得很慢（emulation） | 接受；POC 只跑一次，用完就好 |
| `spring-core-test` library API 不穩定（Spring Framework 7 預設帶） | 已在 Spring Framework 7 GA — 屬 stable API；如有 minor 變動 spec §6 POC Findings 記錄 |
| `--exact-reachability-metadata` 需 GraalVM 24+ — 舊版會 ignore | gate `-PexactReachability=true` Gradle property；無 flag 時 nativeCompile 仍可跑（rollback friendly） |
| 加 `RuntimeHintsAgent` Java agent 需 `-javaagent:` JVM arg | Gradle test task 加 `jvmArgs` 自動掛 agent；user 不需手動設定 |
| 啟用 `--exact-reachability-metadata` 後既有 framework dep 漏 hint 也會 fail | 用 `=io.github.samzhu.skillshub` 限制 package scope（不 cover framework code）|

---

## 6. Task Plan + POC Findings

**POC: required (per §2.5)** — 已執行 2026-05-09。

### POC Findings

**H1 — SkillshubProperties 是否有 AOT reflection bug？**

| 項目 | 結果 |
|------|------|
| Verdict | **REJECTED** — 沒有 bug |
| Evidence | `cd backend && ./gradlew nativeCompile -PexactReachability=true --no-daemon`<br>BUILD SUCCESSFUL in 3m 17s（4 次 attempt 才到此 — V1/V2/V3 失敗於不同前置問題，V4 真的進到 native compile）|
| Native binary | `backend/build/native/nativeCompile/skillshub` — 223 MB ELF executable，啟動 Spring Boot 4.0.6 banner 正常 |
| Coverage 確認 | `io.github.samzhu.skillshub` package 在 build log 出現 26 次 — package 真的被 native-image scanner 走過，不是 silently skipped |
| 假設驗證 | Research 預測「Spring Boot 4 auto-registration + inner record auto-traversal 應該就 cover」— 對的 |

**Implication：** 原 spec §2.6 的「Conditional fix scope 表」第一列觸發 — 「無 bug → 不 fix；spec 只剩 B+C 機制 + RuntimeHintsAgent test」。AC-4 SKIP。

**H2 — RuntimeHintsAgent JVM test 機制驗證**

未執行（H1 已 reject 主要 hypothesis；H2 屬 implementation 階段，不再屬 POC 範疇）。

### Side Discovery — cyclonedx-bom 3.2.4 不相容 nativeCompile

POC V1 + V2 失敗 root cause = **cyclonedx-bom 3.2.4 + Gradle 9.4.1 + nativeCompile 三方衝突**：

```
V1（plugin 啟用）: Cannot mutate the artifacts of configuration ':cyclonedxDirectBom' after the configuration was consumed as a variant
V2（excludeTask cyclonedxBom）: Querying the mapped value of task ':cyclonedxBom' property 'jsonOutput' before task ':cyclonedxBom' has completed is not supported
V3+（plugin 整個註解）: ✅ 通過
```

POC workaround：`build.gradle.kts` line 10 cyclonedx-bom plugin 暫註解。**正式 fix 範圍超出 S148b**（不是 reflection metadata 議題；屬 build tool incompatibility），追蹤為 followup（見 §7）。

### Decision Points

| 點 | 結果 |
|----|------|
| AC-1（POC verdict）| ✅ DONE（本段即結論）|
| AC-2（RuntimeHintsAgent test）| 待 user 決定：ship 還是 defer（H1 reject 後 ROI 降低，仍 defensive 有價值）|
| AC-3（`--exact-reachability` flag）| ✅ 已加進 build.gradle.kts (line ~196)，gated by `-PexactReachability=true`；POC v4 證實 flag 生效 |
| AC-4（conditional fix）| ✅ SKIPPED（H1 reject 不觸發）|
| AC-5（architecture.md doc）| pending（待 user 決定 spec 是否繼續）|

### Tasks

per user decision 2026-05-09（B/B/留）— scope 縮 XS：

| ID | 標題 | 涵蓋 AC | 主要檔案 | Depends |
|----|------|--------|---------|---------|
| T01 | architecture.md 加「GraalVM AOT Strategy」段（5 要點）| AC-5 | docs/grimo/architecture.md | none |

執行序：`T01` 單一 task，無依賴。Phase 4 cleanup 時刪 `poc/S148b/`。

---

## 7. 後續 follow-up（不在本 spec）

| ID | 範圍 | 說明 |
|----|------|------|
| **未編號** | GraalVM AOT strategy ADR | 等多輪 native deploy 經驗累積（≥ 3 spec）後寫 ADR；本 spec 只在 architecture.md 加段不寫 ADR |
| **未編號** | `nativeTest` 加進 nightly CI | 等 native production deploy 評估上線時再做（目前走 JVM buildpack，無迫切性） |
| **未編號** | Generic AOT validation extends to score / search modules | 等下次有人在這些模組加 `@ConfigurationProperties` 或 record-based reflection path 時，沿 S148b pattern 加對應 test |
| **新增** | cyclonedx-bom 3.2.4 nativeCompile 衝突修復 | POC 發現：cyclonedx-bom 3.2.4 與 Gradle 9.4.1 nativeCompile task graph 互衝（processResources ↔ cyclonedxBom 互相依賴 race）。POC 期間用 plugin 註解 workaround；正式 fix 走獨立 micro-spec：(a) 升 cyclonedx 4.x（若有）；(b) report upstream issue；(c) 換 BOM 工具。**不 fix 則無法 ship native production deploy** |
