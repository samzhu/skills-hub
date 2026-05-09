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
| V07 | `cd e2e && npx playwright test --grep @happy-path` | CRITICAL | `e2e/node_modules` 不存在 / `e2e/playwright.config.ts` 不存在 | Playwright happy-path E2E gate；by `/playwright-expert` skill；artefacts → `e2e/test-results/` + `e2e/playwright-report/`（gitignored，managed block by `ensure-latest.sh`）；trace `on-first-retry`（official default per playwright.dev/docs/ci-intro）；本機看 `npx playwright show-trace <trace.zip>` 或拖到 trace.playwright.dev；CI 用 `actions/upload-artifact@v5` + `if: ${{ !cancelled() }}` 上傳 |

### Known Limitations

| Item | Workaround | Why not enroll |
|------|-----------|----------------|
| _（無）— S148e + S166a 後 `processAot` / `processTestAot` 全綠（驗證 2026-05-09：`./gradlew processAot` BUILD SUCCESSFUL）；歷史「GraalVM 0.11.5 plugin pre-existing bug + `bootRun -x processAot`」工作流已過時，**不要再用 `-x processAot`** — AOT 是部署到 Cloud Run native binary 的核心特色（per CLAUDE.md），跳過會把 prod-only 失敗（如 S158 Jackson default-view-inclusion）藏到上線才爆。_ | — | — |

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
| Async Listener Test | Spring Modulith `Scenario` API | async event listener 行為驗證（取代 Awaitility 30s） |
| API Test | MockMvc / WebTestClient | REST API 請求/回應驗證 |
| Frontend Component Test | Vitest + React Testing Library | React 元件渲染 + 互動 |

#### Async Listener 驗證標準 pattern（S025a 起）

`@ApplicationModuleListener` async listener 驗證**首選 Spring Modulith `Scenario` API**，**禁用 30s Awaitility band-aid**。

**標準 pattern**：

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios   // @SpringBootTest 不自動 include；@ApplicationModuleTest 已內建
class FooListenerTest {

    @Test
    void publishEvent_triggersListener_writesRow(Scenario scenario) {
        scenario.publish(new SkillCreatedEvent(...))
                .andWaitForStateChange(() -> repo.findById(id).orElse(null))
                .andVerify(row -> assertThat(row.getX()).isEqualTo(...));
    }
}
```

**全域 timeout default 5s**（per `TestcontainersConfiguration.scenarioTimeout()` `@Bean ScenarioCustomizer`）；個別 listener 需更長（如 ScanOrchestrator 完整 SARIF pipeline）顯式 `.andWaitAtMost(Duration.ofSeconds(N))` override。

**何時改用 Awaitility**：infra 計數類測試（`HikariPoolUnderLoadTest` 等待 connection counter 達標、outbox row status 變化）— Scenario 不直接適用。timeout 上限 **5s**（同步於 Scenario default）。

**Anti-pattern**：
- `Awaitility.await().atMost(Duration.ofSeconds(30))` — S023-T07 cache key 爆炸時的 timing race band-aid，per S025a-T01 POC validated（async listener p95 < 1s）已不需要
- 個別 file 散佈 `@MockitoBean EmbeddingModel` / `@MockitoBean CurrentUserProvider` — Spring TestContext customizer 變異 → cache key 爆炸；改 lift 至 `TestcontainersConfiguration.@Bean @Primary` 或 `@WithMockUser`

**測試金字塔目標**（per S025a + S025b roadmap）：
- 純 unit（JUnit 5 / Vitest，無 Spring context）：≥ 50%
- Slice（`@DataJdbcTest` / `@WebMvcTest` / `@ApplicationModuleTest`）：~30%
- E2E `@SpringBootTest(WebEnvironment=RANDOM_PORT)`：≤ 3（S025b 落地）
- Cache key 上限：baseline ~42 → S025b ship 後 ~18（pgvector container 啟動 18 次/run）；目標 ≤ 10 留 S025c
- JVM heap：S023-T07 quick-win 設 3g；S025b T01 移除 cache.maxSize=8；T05 降至 2g（仍需 — 18 個 CONFIG bucket `@SpringBootTest` 各自獨立 customizer set）；default 還原留 S025c

#### REPO slice via `RepositorySliceTestBase`（S025b 起）

純 repo / service 整合測試（無 HTTP、無 async listener 斷言）首選 `@DataJdbcTest` slice + 共用 base class 收斂 cache key：

```java
@Import(MyService.class)   // service 依賴 — slice 不掃 @Service
class MyServiceTest extends RepositorySliceTestBase {
    @Autowired private MyService service;
    @Autowired private MyRepository repo;
    // 驗 sync TX state；async audit log 屬 module test / e2e 範圍
}
```

`RepositorySliceTestBase` 已綁 `@DataJdbcTest + @Import(TestcontainersConfiguration) + @TestPropertySource("management.tracing.enabled=false") + @ImportAutoConfiguration`（解 Spring Modulith AOT blocker；詳 base class Javadoc）+ `@Transactional(propagation=NOT_SUPPORTED)`。14 個 REPO slice 共用同一 cache entry。

#### WEB slice via `WebMvcSliceTestBase`（S025b 起）

純 controller HTTP / auth gate 測試（無 DB seed、無 async event 斷言）首選 `@WebMvcTest` slice + 共用 base class：

```java
@WebMvcTest(MyController.class)
class MyControllerTest extends WebMvcSliceTestBase {
    @Autowired MockMvc mockMvc;
    @MockitoBean MyService service;   // controller-specific dep

    @Test
    void getEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/foo")
                .with(jwt().jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
            .andExpect(status().isOk());
    }
}
```

`WebMvcSliceTestBase` 已綁 `@Import(SecurityConfig) + @EnableConfigurationProperties(SkillshubProperties) + @MockitoBean JwtDecoder + @MockitoBean PermissionEvaluator + @ImportAutoConfiguration + @TestPropertySource("management.tracing.enabled=false")`。OAuth2 RS 用 `.with(jwt())` post-processor，**不**用 `@WithMockUser`（後者注入 `UsernamePasswordAuthenticationToken` 走錯 path）。

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
| UI 功能驗證 | Playwright via `/playwright-expert`（VERIFY mode）；3 個 happy-path E2E spec；evidence 寫至 `e2e/results/evidence.json`；本機 trace `npx playwright show-trace` 或 trace.playwright.dev（皆免費 + offline） |
| 跨瀏覽器 | Playwright 預設 chromium（headless shell）；新增 Firefox / WebKit project 待規模需要時加 |
| 上傳/下載流程 | 由 happy-path spec 涵蓋；大檔案 / 異常格式邊界由 backend integration test（Testcontainers）涵蓋，不重複放 E2E |
| 風險評估準確度 | 準備已知危險/安全的 skill 樣本驗證（仍含人工抽驗，非全自動） |

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
