# Skills Hub — QA Strategy

## Verification Pipeline

### PR Gate

```bash
# Backend
./gradlew test                    # JUnit 5 + Testcontainers
./gradlew modulithTest            # Spring Modulith module boundary verification

# Frontend
cd frontend && npm test           # Vitest
```

### Coverage

```bash
# Backend (JaCoCo)
./gradlew jacocoTestCoverageVerification
# Threshold: 80% line coverage on new code

# Frontend (Vitest coverage)
cd frontend && npm test -- --coverage
# Threshold: 80% line coverage on new code（漸進加入 gate — `coverage.include` 鎖定有對應 test 的 source 檔；S022 落地）
```

> Backend 由 V03（`./gradlew jacocoTestCoverageVerification`，S019 ship）執行 80% line coverage gate；Frontend 由 V06（`npm test -- --coverage`，S022 ship）執行同 80% line coverage gate。Cross-stack 相同 LINE coverage 標準（80%）；不同實作（JaCoCo BUNDLE / vitest project-wide aggregate over include whitelist）。

### Architecture / Boundary

```bash
# Spring Modulith 自動驗證 module 依賴邊界
./gradlew test --tests "*ModularityTests*"
```

Spring Modulith 的 `ApplicationModules.verify()` 確保：
- Module 之間沒有非法的直接依賴
- 跨 module 通訊只透過 public API 或事件
- 沒有循環依賴

### API Contract

```bash
# SpringDoc OpenAPI 產生 + 驗證
# API spec 自動從 code 產生，endpoint: /v3/api-docs
```

### SBOM

```bash
# CycloneDX 自動產生 Software Bill of Materials
./gradlew cyclonedxBom
```

---

## Verification Command Registry

`/verifying-quality` Step 0.5 protocol 期望此 table 為 verify command 的唯一 source of truth。**新增** verify task 須同步更新 `scripts/verify-all.sh`；**移除** 須兩處同刪。

| ID | Command | Severity | Skip-if | Notes |
|----|---------|----------|---------|-------|
| V01 | `./gradlew clean test jacocoTestReport` | CRITICAL | — | 含 ModularityTests 與 compileTestJava；產 jacoco XML/HTML/CSV 三 report |
| V02 | parse `backend/build/reports/jacoco/test/jacocoTestReport.csv` (awk LINE_MISSED + LINE_COVERED) | INFO（顯示用） | CSV 不存在 | 顯示 LINE coverage %；非 gate（gate 由 V03 負責）|
| V03 | `./gradlew jacocoTestCoverageVerification` | CRITICAL | task 未註冊（S019 未 ship 之歷史環境） | Threshold 在 `build.gradle.kts` 為 single source；`./gradlew check` 同 gate |
| V04 | `cd frontend && npm test` | CRITICAL | `frontend/node_modules` 不存在 | Vitest run；frontend test gate |
| V05 | `cd frontend && npm run lint` | CRITICAL | `frontend/node_modules` 不存在 | ESLint；frontend lint gate |
| V06 | `cd frontend && npm test -- --coverage` | CRITICAL | `frontend/node_modules` 不存在 | vitest `coverage.thresholds.lines: 80` gate；text reporter inline 印 coverage table 到 stdout；`coverage.include` whitelist 鎖定有對應 test 的 source 檔（漸進加入 gate）；S022 落地 |

### Known Limitations

| Item | Workaround | Why not enroll |
|------|-----------|----------------|
| `./gradlew bootRun` 觸發 `:processAot` 失敗（GraalVM `org.graalvm.buildtools.native:0.11.5` plugin pre-existing bug）| `./gradlew bootRun -x processAot` | bootRun smoke 慢且非 PR gate；待獨立 spec 處理 AOT 配置或切換 OpenTelemetry |

### 不 enroll 的命令

| 命令 | 為何不入 registry |
|------|------------------|
| `./gradlew test --tests "*ModularityTests*"` | V01 `./gradlew test` 已含 modularity tests；單跑 redundant |
| `./gradlew compileTestJava` | V01 已含 compile（`test` task 自動 depends）|
| `./gradlew cyclonedxBom` | SBOM 為 ship artifact，非 quality gate |
| `./gradlew bootBuildImage` | 容器 image build；慢、非 PR gate |
| `cd frontend && npm run coverage` | `package.json` 無此 script + `@vitest/coverage-v8` 未裝；待獨立 frontend coverage spec（即 §Verification Pipeline §Coverage L23-25 宣告但尚未實作部分）|

---

## Three-Layer Verification

### Layer 1: Automated Tests

| 類型 | 工具 | 說明 |
|------|------|------|
| Unit Test | JUnit 5 / Vitest | 單一 class/function 的邏輯驗證 |
| Integration Test | Spring Boot Test + Testcontainers | 跨元件整合（DB, GCS） |
| Module Test | Spring Modulith `@ApplicationModuleTest` | 單一 module 的完整測試（含 DB） |
| API Test | MockMvc / WebTestClient | REST API 請求/回應驗證 |
| Frontend Component Test | Vitest + React Testing Library | React 元件渲染 + 互動 |

### Layer 2: Integration Verification

| 驗證項目 | 方式 |
|----------|------|
| PostgreSQL + pgvector | Testcontainers `pgvector/pgvector:pg16`（開發 + CI 一致；本機 Docker Compose 用同 image） |
| GCS 整合 | Testcontainers + GCS emulator |
| Spring Modulith 邊界 | `ApplicationModules.verify()` |
| API 文件一致性 | SpringDoc 自動產生，人工抽驗 |

### Layer 3: Manual / E2E

| 驗證項目 | 方式 |
|----------|------|
| UI 功能驗證 | 啟動 dev server，手動操作 golden path |
| 跨瀏覽器 | Chrome + Safari（主要目標） |
| 上傳/下載流程 | 端對端手動測試（含大檔案、異常格式） |
| 風險評估準確度 | 準備已知危險/安全的 skill 樣本驗證 |

---

## AC-to-Test Contract

每個 spec 的 acceptance criteria（AC）必須對應測試：

```java
// Backend example
@Test
@DisplayName("AC-1: 用關鍵字搜尋技能 - 回傳 name 或 description 含關鍵字的 skills")
@Tag("AC-1")
void searchByKeyword_returnsMatchingSkills() {
    // ...
}
```

```typescript
// Frontend example
describe('AC-1: 用關鍵字搜尋技能', () => {
  it('should return skills matching keyword in name or description', () => {
    // ...
  });
});
```

一個 spec 被視為 "covered" 的條件：每個 AC id 至少有一個對應的測試。

### Build / Config Spec — Evidence-Only AC 例外（S019 + S020 + S022 共識）

純 build / config / docs spec（無 production code 變動）可採 **evidence-only AC** 不需 `@DisplayName` / `describe` 對應 test 方法，AC 由以下 evidence 證明：

- `grep` / `cat` / `ls` 等檔案存在性與內容檢查
- `./gradlew tasks --all` / `npm test -- --version` 等 task / binary 註冊檢查
- `./scripts/verify-all.sh` exit code + Summary 輸出
- live build / live run 產出的 stdout 證據（`BUILD SUCCESSFUL` / `Tests N passed` 等）
- 人工 review（檔案內容對齊 ground truth）

範例 spec：S019（JaCoCo gate；6 AC 全 build-evidence）/ S020（verify-all.sh；7 AC 全 evidence）/ S021（PostgreSQL doc-sync；7 AC 全 grep + human review）。

此例外僅適用「無 production code 變動」spec；任何加新 service / listener / projection / component / hook 的 spec 必須回到本 §AC-to-Test Contract 主規則（每個 AC 至少對應 1 個 test）。

---

## Development Environment

### Local Dev

```bash
# 啟動後端 + 依賴服務（Grafana LGTM via Docker Compose）
./gradlew bootTestRun    # 使用 TestcontainersConfiguration

# 啟動前端 dev server（hot reload）
cd frontend && npm run dev
```

### Testing with PostgreSQL

- 開發階段：使用 Testcontainers + `pgvector/pgvector:pg16` image（與 `backend/compose.yaml` 同 image；dev/test 行為一致）
- CI 階段：同 Testcontainers image（無 emulator 替代品；OS-portable、無 macOS/CI flakiness）
- Staging / GCP Production：Cloud SQL（PostgreSQL 18 + `cloudsql.enable_pgvector` instance flag）+ Cloud SQL Auth Proxy sidecar；JDBC URL 與本機相同（dev/prod parity）— 詳 [`architecture.md` §PostgreSQL Configuration](./architecture.md#postgresql-configuration)
