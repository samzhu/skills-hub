# Skills Hub — Development Standards

## Language & Runtime

- **Java 25** (toolchain managed by Gradle)
- **Node.js 22 LTS** (frontend build)
- **TypeScript 6.0.3** (frontend strict mode)

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

## Build & Deploy

- Build: `./gradlew build`（含前端 build + 後端 build + test）
- Container: `./gradlew bootBuildImage` 或 Dockerfile
- Deploy target: GCP Cloud Run
- CI pipeline: build → test → container build → deploy
