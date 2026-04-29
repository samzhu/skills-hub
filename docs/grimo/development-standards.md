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

### Event Sourcing + CQRS 規範

- **核心域（skill）** 使用 Aggregate + ES + CQRS：Aggregate Root 封裝業務規則 → 產生 domain event → 存入 event store → publish → projection 更新 read model
- **非核心模組**（security, search, analytics, storage）**不使用 Aggregate**，直接以 service / listener 形式運作
- Domain Events 為不可變 Record，命名用過去式（`SkillCreated`, `SkillVersionPublished`）
- Commands 為 Record，命名用動詞（`CreateSkillCommand`, `PublishVersionCommand`）
- Aggregate Root 負責驗證不變量（uniqueness, semver 遞增, 狀態轉換合法性）
- 每個 event 必須存入 `domain_events` collection，再透過 Spring Modulith publish
- Projection listener 用 `@ApplicationModuleListener`，冪等處理（同一 event 重複消費不會產生副作用）
- Command controller 和 Query controller 分開（寫入和讀取分離）
- Event payload 必須自包含（不依賴外部查詢即可建構 read model）
- security, search, analytics 的 listener 直接操作自己的 read model，不經過 aggregate

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
