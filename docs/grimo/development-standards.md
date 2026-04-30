# Skills Hub — Development Standards

## Language & Runtime

- **Java 25** (toolchain managed by Gradle)
- **Node.js 20 LTS** (frontend build)
- **TypeScript ~6.0.2** (frontend strict mode)

## Code Style

### Java (Backend)

- 遵循 Spring Boot 官方 coding conventions
- Package 結構遵循 Spring Modulith — 每個 module 一個頂層 package
- Record types 用於 Command, Event, DTO / Value Objects
- `Optional` 用於可能為 null 的回傳值，不用於參數
- Constructor injection（不用 `@Autowired` field injection）
- `@RestController` + `@RequestMapping` 用於 API endpoints
- Module 之間透過 `ApplicationEvent` 通訊，不直接注入跨 module 的 service

### Spring Data JDBC Rich Aggregate + Modulith Outbox 規範（S024 起；per ADR-002）

- **Skill domain** 改用 Spring Data JDBC 充血聚合：`@Table` + `extends AbstractAggregateRoot<T>` + `@Version` 樂觀鎖；`Persistable.isNew()` 自訂以對應 client-generated UUID PK（per [deepwiki §1.@Version + §4.isNew](../deepwiki/spring-data-jdbc-modulith/aggregate-design.md)）
- Aggregate method 充血：先驗證不變量（throw `IllegalStateException` 若違規）→ mutate state field → `registerEvent(domainEvent)`
- Service 層每 command 方法 3-line orchestration：`load → mutate → save`；無 `eventStore.save` / `events.publishEvent` 直接呼叫
- `repository.save(aggregate)` 透過 Spring Data proxy interceptor `@DomainEvents` 自動 publish 至 Modulith `event_publication` outbox（同 TX）
- 跨 aggregate 一致性由 application service `@Transactional` 邊界內 `existsBy*` 預檢 + DB UNIQUE / FK constraint 兜底（如 `SkillVersionRepository.existsBySkillIdAndVersion + UNIQUE (skill_id, version)`）
- Aggregate 內**不**用 `@MappedCollection` / `AggregateReference` 引用其他 aggregate（per ADR-002 §2.3：避開 `WritingContext.update()` delete-and-reinsert 雷）；用 plain `String foreignKey` 欄位
- 高頻寫子集合（如 `acl_entries`）用 `jsonb` 欄位行內 UPDATE，不拆獨立 aggregate（per ADR-002 §2.4）
- `domain_events` 表為 **event log**（保留 ES 精神，理論上可 replay 還原任意時點 aggregate state；不主動使用）— 新事件由 `AuditEventListener` async 訂閱 outbox 統一寫入；寫入端 source of truth 改為 `skills` aggregate state；emergency replay 場景可寫 `fromHistory` factory 從 events 重建
- Domain event class 視為公開 API，**不得**任意改名（per [deepwiki §3 陷阱 8](../deepwiki/spring-data-jdbc-modulith/design-decisions.md) — 反序列化失敗）
- Domain event payload 只序列化 ID + 關鍵欄位，**不**含大型 SARIF / frontmatter（per deepwiki §3 陷阱 10 — 8191 byte limit）
- Domain Events 為不可變 Record，命名用過去式（`SkillCreatedEvent`, `SkillVersionPublishedEvent`）
- Commands 為 Record，命名用動詞（`CreateSkillCommand`, `PublishVersionCommand`）
- **非核心模組**（security `SkillFlaggedEvent` 路徑）保留簡化 sync ES write；流量低、event 簡單；無轉向計畫
- search / analytics / audit listener 用 `@ApplicationModuleListener`，冪等處理（同一 event 重複消費不疊加；UNIQUE constraint + `ON CONFLICT DO NOTHING` 或 deterministic UUID 是正解）
- Audit listener idempotency：用 `UUID.nameUUIDFromBytes(dedupKey)` 確定性映射 row id；同 aggregate 多 listener 並發以 `pg_advisory_xact_lock(hashtext('audit:' || aggregate_id)::bigint)` 序列化避免 `MAX(seq)+1` race
- Command controller 和 Query controller 分開（寫入和讀取分離）；Query response type 直接用 aggregate（`@JsonIgnore` on `@Version` 欄位避免 expose internal lock）

### Spring Modulith Outbox 規範（S023 起）

- **Outbox 機制**：所有 domain event 透過 `event_publication` 表（Modulith managed）做 transactional outbox；publisher TX rollback → outbox row 同 rollback；at-least-once 投遞語義
- **Listener Annotation**：`@ApplicationModuleListener` = `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener(AFTER_COMMIT)`；publish 必須在 `@Transactional` 內否則 listener silently drop
- **Hybrid migration 規則**：FK target row 創建者（如 `SkillProjection.onSkillCreated/onVersionPublished`）保留 sync `@EventListener`；其他 listener 用 async `@ApplicationModuleListener`
- **`@Transactional` 對 private method 無效**：Spring AOP 不 proxy private；publish path 上 `@Transactional` 必須加在 public method 入口
- **async listener 失去 SecurityContext**：必須以 `DelegatingSecurityContextAsyncTaskExecutor` wrap `applicationTaskExecutor`，否則新 thread 內 `SecurityContextHolder.getContext()` 為空
- **AOP proxy field 不透明**：test 不可直接讀 bean field（拿到 proxy class 同名未初始化 field）；用 method-level access (`getValue()`) 走 proxy delegation
- **`applicationTaskExecutor` 容量設計**：`corePoolSize=2 / maxPoolSize=2 / queueCapacity=200`，對齊 GCP HikariCP `maximum-pool-size=3`（留 1 connection 給主請求 thread）；bean name 必須 `applicationTaskExecutor`（Spring `@Async` 預設查找名稱）
- **YAML profile override = replace（不是 merge）**：`management.endpoints.web.exposure.include` 等屬性在 `config/application-dev.yaml` 完全覆蓋 base；要新增端點需兩處同改
- **PostgreSQL JDBC `Instant` binding**：`JdbcTemplate.update(...)` 不接受直接 bind `Instant`；必須轉 `Timestamp.from(now)`（production code 走 Spring Data converter chain 不受影響）
- **idempotency 設計**：async 並行 listener 順序未定；不可依賴另一 listener 先寫 row 做 dedup 子查詢；用 UNIQUE constraint + `ON CONFLICT DO NOTHING` 是嚴格冪等的正解
- **multi-instance retry 互斥**：用 ShedLock + `JdbcTemplateLockProvider.usingDbTime()` 規避 cluster clock skew；GCP Cloud Run 多 instance 部署為必要設定

### TypeScript (Frontend)

- Strict mode enabled (`"strict": true`)
- Functional components only（no class components）
- Custom hooks 放 `hooks/` 目錄
- API calls 統一透過 TanStack Query + fetch wrapper
- 狀態管理：server state 用 TanStack Query，client state 用 Zustand
- 元件檔案：PascalCase（`SkillCard.tsx`）
- hooks/utilities：camelCase（`useSkillSearch.ts`）
- UI 語言：繁體中文（zh-TW）— 所有頁面標題、按鈕文字、提示訊息、空狀態文案皆使用繁體中文
- 程式碼中的變數、函式名稱維持英文

## API Standards

- REST API prefix: `/api/v1/`
- 回傳格式：JSON
- 錯誤回傳統一格式：
  ```json
  {
    "error": "SKILL_NOT_FOUND",
    "message": "Skill with id xxx not found",
    "timestamp": "2026-04-24T12:00:00Z"
  }
  ```
- HTTP status codes 遵循 RFC 9110
- Pagination: `?page=0&size=20&sort=name,asc`
- API 文件由 SpringDoc 自動產生，endpoint: `/swagger-ui.html`

## Version Control

- Branch naming: `feature/S001-skill-crud`, `fix/S002-upload-validation`
- Commit message: conventional commits（`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`）
- 每個 spec 一個 feature branch，完成後 merge to main

## Dependency Management

- **前置鎖定** — 所有依賴在 S000 鎖定版本
- Backend: Gradle BOM 管理版本（Spring Boot, Spring Cloud GCP, Spring AI, Spring Modulith）
- Frontend: `package.json` 用 exact versions（不用 `^` prefix）
- 新增依賴需記錄到 architecture.md Framework Dependency Table

## Testing Standards

- 每個 spec 的 SBE acceptance criteria 對應至少一個測試
- 測試命名：`@DisplayName("AC-1: 用關鍵字搜尋技能")` 或 `@Tag("AC-1")`
- Backend: JUnit 5 + Spring Boot Test + Testcontainers
- Frontend: Vitest + React Testing Library
- Module 邊界測試：Spring Modulith `@ApplicationModuleTest`

### 測試金字塔規範（S025a 起；per spec §3 + qa-strategy.md §Layer 1 細則）

**Async listener test → `@ApplicationModuleTest + Scenario` 為首選**：

- `@SpringBootTest + Awaitility 30s` 為 S023-T07 cache key 爆炸時的 timing race band-aid → **禁用**
- `@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)` 自動載入 cross-module 依賴；`Scenario` 注入透過 `ScenarioParameterResolver`（`@ApplicationModuleTest` 內建；`@SpringBootTest` 需顯式 `@EnableScenarios`）
- 全域 timeout default 5s（`TestcontainersConfiguration.@Bean ScenarioCustomizer scenarioTimeout()`）；個別 expensive listener 用 `.andWaitAtMost(Duration.ofSeconds(N))` override

**`@MockitoBean` 散佈為 anti-pattern**：

每個 `@MockitoBean` 經 `BeanOverrideContextCustomizer.handlers` Set 進入 Spring TestContext cache key；不同 file 的同名 mock field 因 reflection `Field` 物件不等 → customizer 不等 → cache key 不等 → context cache 大量 evict + container churn。修法：

- **Stub 邏輯一致** → lift 至 `TestcontainersConfiguration.@Bean @Primary`（per S025a-T01 EmbeddingModel 範例）
- **依賴 SecurityContext 的 mock**（如 `CurrentUserProvider`）→ 改 `@WithMockUser` + 真 Spring Security context（per S025a-T03 模式；`AsyncListenerConfig` 用 `DelegatingSecurityContextAsyncTaskExecutor` 自動 propagate 至 async thread）
- **共用 properties 的 test class**（如 LabMode）→ 抽 abstract base class（per S025a-T04 `LabModeTestBase` 模式）

**`@Async` bean alias 規則**（S025a-T03 production fix）：

`AsyncListenerConfig.applicationTaskExecutor()` 必須以 alias `taskExecutor` 註冊：

```java
@Bean(name = {"applicationTaskExecutor", "taskExecutor"})
public TaskExecutor applicationTaskExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    // ...
    return new DelegatingSecurityContextAsyncTaskExecutor(executor);
}
```

理由：Spring 7.0 `AsyncExecutionInterceptor` 的 `DEFAULT_TASK_EXECUTOR_BEAN_NAME = "taskExecutor"`；多 `TaskExecutor` bean（`applicationTaskExecutor` + `taskScheduler`）造成 by-type lookup 失敗 → fallback `SimpleAsyncTaskExecutor` → `DelegatingSecurityContextAsyncTaskExecutor` 包裝旁路 → SecurityContext 不 propagate 到 async listener thread。

**Test cache key 上限**（per S025a + S025b roadmap）：
- S025a ship 後：≤ 25 distinct（baseline 53）
- S025b ship 後（slice 重組）：~18（pgvector container 啟動 18 次/run；indirect measurement，per S025b §7 deviation）
- S025c 目標：≤ 10（進一步 consolidate CONFIG bucket `@SpringBootTest`）
- 量測指令：`./gradlew clean test > test-cache.log 2>&1; grep -c "Container pgvector/pgvector:pg16 started" test-cache.log`（indirect — `-Dlogging.level.org.springframework.test.context.cache=DEBUG` 在 Gradle test fork JVM 不 propagate；改數 Testcontainer 啟動次數）

### REPO slice via `RepositorySliceTestBase`（S025b 起）

`@DataJdbcTest` slice 共用 base class 收斂 cache key — 14 個 REPO test 共用同一 Spring TestContext cache entry：

```java
@Import(MyService.class)   // service 依賴 — slice 不掃 @Service
class MyServiceTest extends RepositorySliceTestBase {
    @Autowired private MyService service;
    @Autowired private MyRepository repo;
    // 驗 sync TX state（aggregate 寫入後立即可讀）；
    // async audit log（@ApplicationModuleListener）不在 slice 啟用 — 屬 module test / e2e 範圍
}
```

`RepositorySliceTestBase` 設計理由：
- `@DataJdbcTest` 預設 `@AutoConfigureTestDatabase(replace=NON_TEST)`（SB 4 起；SB 3 是 `Replace.ANY`）— `@ServiceConnection` 容器自動 detected，無需顯式 `replace=NONE`
- Flyway V1-V6 自動啟用（via `spring-boot-flyway` jar `AutoConfigureDataSourceInitialization.imports`）
- `JdbcConfiguration extends AbstractJdbcConfiguration` 由 `DataJdbcTypeExcludeFilter.KNOWN_INCLUDES` 自動 picked up — JSONB converters 自動可用
- `management.tracing.enabled=false` 解 Spring Modulith AOT blocker — `ModuleObservabilityAutoConfiguration` 由獨立 `AutoConfiguration.imports` 載入（不受 `@DataJdbcTest @OverrideAutoConfiguration` 控制）；其 `@ConditionalOnProperty(name="management.tracing.enabled" havingValue="true" matchIfMissing=true)` 設 false 後整個 class 不啟用 → 不需要 `ApplicationModulesRuntime` bean
- `@ImportAutoConfiguration` (bare) + `META-INF/spring/<class>.imports` file 帶 `SpringModulithRuntimeAutoConfiguration` FQN — 解第二條 AOT path（`spring-modulith-runtime` `aot.factories` classpath-level 註冊 `ApplicationModulesFileGeneratingProcessor` 對 `ApplicationModulesRuntime` hard dep）
- `@Transactional(propagation=NOT_SUPPORTED)` — 反 `@DataJdbcTest` 預設 meta-annotated `@Transactional`（auto-wrap test method 為 TX）；REPO slice test 對齊 既有 `@SpringBootTest` 既有語意（test 自管 TX、helper 自 commit；async listener AFTER_COMMIT 觸發必須 commit）

### WEB slice via `WebMvcSliceTestBase`（S025b 起）

`@WebMvcTest` slice 共用 base class — 11 個 controller test 大幅收斂 cache key：

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

OAuth2 Resource Server controller test：
- **用 `.with(jwt())` post-processor**，**不**用 `@WithMockUser`（後者注入 `UsernamePasswordAuthenticationToken` 走 form-login path，繞過 OAuth2 RS filter chain；`jwt()` 注入 `JwtAuthenticationToken`，但不過 `JwtAuthenticationConverter`，需顯式 `.authorities(...)` 對齊 production）
- `@Import(SecurityConfig.class)` — slice 不掃 `@Configuration`；測 OAuth2 RS filter chain 必須引入 prod `SecurityConfig`
- `@MockitoBean JwtDecoder` + `@MockitoBean PermissionEvaluator` — base 已宣告；無需子類重複
- `@MockitoBean CurrentUserProvider`（如 controller 注入）— 子類自行宣告 + stub `userId()` / `current()`

### 移除 `Duration.ofSeconds(30)` Awaitility timeout（S025a-T04）

全 backend test 中 `Duration.ofSeconds(30) + Awaitility` 為禁用 anti-pattern。Standard timeout 為 5s（搭配 Scenario default）。Infra 計數類（`HikariPoolUnderLoadTest`）保留 Awaitility 但 timeout ≤ 5s。

## Configuration Best Practices (S009)

- **Pure values in YAML** — `skillshub.*` properties must not use `${...}` placeholder indirection. Relaxed binding handles env var override automatically (e.g., `SKILLSHUB_GENAI_API_KEY`).
- **Spring AI Manual Configuration** — Never mix auto-config and Manual Config. Always declare `spring.ai.model.embedding.text: none` + exclude `GoogleGenAiEmbeddingConnectionAutoConfiguration` in `application.yaml`. Build EmbeddingModel beans explicitly with `@ConditionalOnProperty`.
- **Fixed values in `@ConfigurationProperties`** — Centralise constant defaults (model name, dimensions, collection name) in the `SkillshubProperties` record using `@DefaultValue`. Don't hardcode them in `@Bean` methods.
- **springdoc off by default** — `application.yaml` (packaged into Docker image) must set `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false`. Enable only in `config/application-dev.yaml`.
- **secrets dot-notation** — `config/application-secrets.properties` keys use dot-notation (`skillshub.genai.api-key=...`), not SCREAMING_SNAKE_CASE.
- **`autoconfigure.exclude` list is replaced (not merged) by profile YAML** — Profile YAML must repeat any base exclusions it still needs, plus its own additions.

## Build & Deploy

- Build: `./gradlew build`（含前端 build + 後端 build + test）
- Container: `./gradlew bootBuildImage` 或 Dockerfile
- Deploy target: GCP Cloud Run
- CI pipeline: build → test → container build → deploy
