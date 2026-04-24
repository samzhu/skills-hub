# S000: Project Init — 前後端骨架、ES 基礎設施

> Spec: S000 | Size: S(10) | Status: ⏳ Design
> Date: 2026-04-24

---

## 1. Goal

在現有 Spring Boot 模板基礎上完成專案初始化：目錄重組（`skillshub/` → `backend/`）、建立 React 19 前端專案、加入 Firestore/MongoDB 依���、建立 ES+CQRS 基礎設施、清除 htmx，讓前後端 build 和 test 都能跑。

S000 是所有 spec 的基礎，無上游依賴。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Native build — 前後端各用原生工具編譯，Gradle 用 Exec task 呼叫 npm | ⭐ yes | 符合 CLAUDE.md native tooling 原則，簡單可控，不依賴第三方 Gradle plugin |
| B: Gradle Node plugin（com.github.node-gradle.node） | no | 多一層 plugin 依賴，Gradle 9.4.1 相容性未驗證，增加維護成本 |

### Key Decisions

1. **Node.js 24 LTS**（從 22 LTS 升級）— 使用者要求最新穩定版
2. **前後端獨立編譯** — `npm run build` + `./gradlew build`，Gradle 透��� Exec task 串接
3. **shadcn/ui 只做 init** — 不預裝元件，元件在 S002 按需加入
4. **border-beam** — `npm install border-beam`，在首頁 shell 展示效果
5. **DomainEvent 用 record** — `@Document("domain_events")` 直接映射 Firestore/MongoDB
6. **Spring Modulith module skeleton** — 只建 package-info.java，無業務代碼
7. **依賴前置鎖定** — 所有依賴（含後續 spec 用的）在 S000 鎖定版本

### Challenges Considered

- **Firestore MongoDB 相容不支援 retryWrites** — application.yaml 必須設 `retryWrites=false`，開發用本地 MongoDB（Testcontainers），不影響 S000
- **前端 build output 放哪** — copy 到 `build/` 目錄下（非 `src/`），避免汙染 source tree，`.gitignore` 排除

### 2.3 Research Citations

- [Node.js 24 LTS](https://nodejs.org/en/about/previous-releases) — 24.15.0, supported through 2028-04
- [React 19.2.5](https://www.npmjs.com/package/react) — latest stable, published 2026-04-08
- [Vite 8](https://vite.dev/blog/announcing-vite8) — Rolldown-powered, 10-30x faster builds
- [TypeScript 6.0.3](https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/) — final JS-based compiler release
- [Tailwind CSS 4.2.4](https://www.npmjs.com/package/tailwindcss) — CSS-first config, zero-config
- [shadcn/ui + React 19 + Tailwind 4](https://ui.shadcn.com/docs/tailwind-v4) — fully compatible
- [border-beam](https://beam.jakubantalik.com/) — animated border beam component for React

## 3. SBE Acceptance Criteria

Verification command:

    Run: cd backend && ./gradlew test
    Pass: all tests carrying S000 AC ids are green.

---

**AC-1: Gradle build 成功**

```
Given 專案已完成初始化（skillshub/ 已 rename 為 backend/）
When  執行 cd backend && ./gradlew build
Then  build 成功，無錯誤
And   產出的 jar 包含 static/ 目錄下的前端 build output
And   build.gradle.kts 不含 htmx 依賴
And   build.gradle.kts 含 spring-boot-starter-data-mongodb 依賴
```

**AC-2: Frontend dev server 啟動**

```
Given frontend/ 目錄已建立，含 React 19 + Vite + TypeScript + Tailwind + shadcn/ui + border-beam
When  執行 cd frontend && npm run dev
Then  Vite dev server 在 localhost:5173 啟動
And   頁面顯示基本的 React app（含 border-beam 效果）
```

**AC-3: Spring Modulith module 結構驗證**

```
Given 所有 module packages 已建立（shared, skill, security, search, analytics, storage）
When  執行 Spring Modulith ApplicationModules.verify()
Then  無模組邊界違規
```

**AC-4: Event Store 基礎設施可用**

```
Given DomainEvent record 和 DomainEventRepository 已建立
When  透過 repository 寫入一筆測試 event 到 domain_events collection
Then  可用 findByAggregateIdOrderBySequenceAsc 成功讀回該 event
And   所有欄位（aggregateId, eventType, payload, sequence, occurredAt）正確保存
```

## 4. Interface / API Design

### 4.1 Event Store (shared/events/)

```java
// Stored domain event — maps to domain_events collection
@Document("domain_events")
public record DomainEvent(
    @Id String id,
    String aggregateId,
    String aggregateType,
    String eventType,
    Map<String, Object> payload,
    long sequence,
    Instant occurredAt,
    Map<String, String> metadata
) {}

// Repository — Spring Data MongoDB
public interface DomainEventRepository extends MongoRepository<DomainEvent, String> {
    List<DomainEvent> findByAggregateIdOrderBySequenceAsc(String aggregateId);
    Optional<DomainEvent> findTopByAggregateIdOrderBySequenceDesc(String aggregateId);
}
```

**Example data (2 rows after creating a skill and publishing v1.0.0):**

| id | aggregateId | aggregateType | eventType | payload | sequence | occurredAt |
|----|-------------|---------------|-----------|---------|----------|------------|
| `evt-001` | `skill-abc` | `Skill` | `SkillCreated` | `{"name":"docker-helper","author":"sam"}` | 1 | `2026-04-24T10:00:00Z` |
| `evt-002` | `skill-abc` | `Skill` | `SkillVersionPublished` | `{"version":"1.0.0","storagePath":"gs://..."}` | 2 | `2026-04-24T10:01:00Z` |

### 4.2 Gradle Frontend Integration (backend/build.gradle.kts)

```
./gradlew build flow:

  npmInstall (Exec: npm install in ../frontend)
      ↓
  npmBuild (Exec: npm run build in ../frontend)
      ↓
  copyFrontend (Copy: ../frontend/dist → build/resources/main/static)
      ↓
  processResources (includes frontend output)
      ↓
  bootJar (jar contains static/)
```

Frontend 獨立開發：`cd frontend && npm run dev`（不經 Gradle）。

### 4.3 Frontend Entry (frontend/src/App.tsx)

```tsx
import { BorderBeam } from 'border-beam'

function App() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <BorderBeam>
        <div className="p-8 rounded-xl border bg-card text-card-foreground">
          <h1 className="text-3xl font-bold">Skills Hub</h1>
          <p className="text-muted-foreground mt-2">
            AI Agent Skills Registry
          </p>
        </div>
      </BorderBeam>
    </div>
  )
}
```

### 4.4 Application Config (application.yaml)

```yaml
spring:
  application:
    name: skillshub
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://localhost:27017/skillshub}
      database: skillshub
```

Production (env var): `MONGODB_URI=mongodb+srv://<project>.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC`

### 4.5 Testcontainers Config

```java
// Add MongoDB container to existing TestcontainersConfiguration
@Bean
@ServiceConnection
MongoDBContainer mongoContainer() {
    return new MongoDBContainer(DockerImageName.parse("mongo:8"));
}
```

## 5. File Plan

Package base: `io.github.samzhu.skillshub` (abbreviated as `...` below)

| # | File | Action | Description |
|---|------|--------|-------------|
| **Directory rename** |||
| 1 | `skillshub/` → `backend/` | rename | `git mv skillshub backend` |
| **Backend — build config** |||
| 2 | `backend/build.gradle.kts` | modify | 移除 htmx；加 data-mongodb, google-cloud-firestore, testcontainers-mongodb；加 frontend Exec/Copy tasks |
| 3 | `backend/src/main/resources/application.yaml` | modify | 加 MongoDB 連線設定 |
| 4 | `backend/.gitignore` | modify | 加 `src/main/resources/static/` |
| **Backend — ES infrastructure** |||
| 5 | `.../shared/events/DomainEvent.java` | new | @Document record — event store 文件 |
| 6 | `.../shared/events/DomainEventRepository.java` | new | MongoRepository interface |
| **Backend — Modulith module skeletons** |||
| 7 | `.../shared/package-info.java` | new | shared module 宣告 |
| 8 | `.../skill/package-info.java` | new | skill module（核心域） |
| 9 | `.../security/package-info.java` | new | security module |
| 10 | `.../search/package-info.java` | new | search module |
| 11 | `.../analytics/package-info.java` | new | analytics module |
| 12 | `.../storage/package-info.java` | new | storage module |
| **Backend — tests** |||
| 13 | `.../TestcontainersConfiguration.java` | modify | 加 MongoDBContainer bean |
| 14 | `.../shared/events/DomainEventRepositoryTest.java` | new | AC-4: event store 讀寫測試 |
| 15 | `.../ModularityTests.java` | new | AC-3: ApplicationModules.verify() |
| **Frontend — new project** |||
| 16 | `frontend/package.json` | new | 所有依賴鎖定版本（React 19, Vite 8, TS 6, Tailwind 4, zustand, tanstack-query, border-beam, vitest） |
| 17 | `frontend/vite.config.ts` | new | Vite + React + Tailwind plugin |
| 18 | `frontend/tsconfig.json` | new | TypeScript strict mode |
| 19 | `frontend/tsconfig.app.json` | new | App TS config |
| 20 | `frontend/tsconfig.node.json` | new | Node/Vite TS config |
| 21 | `frontend/index.html` | new | Vite entry HTML |
| 22 | `frontend/src/main.tsx` | new | React entry point |
| 23 | `frontend/src/App.tsx` | new | App shell + BorderBeam demo |
| 24 | `frontend/src/app.css` | new | `@import "tailwindcss"` |
| 25 | `frontend/src/vite-env.d.ts` | new | Vite type declarations |
| 26 | `frontend/components.json` | new | shadcn/ui init config |
| 27 | `frontend/.gitignore` | new | node_modules, dist |
| **Docs sync** |||
| 28 | `docs/grimo/development-standards.md` | modify | Node.js 22 → 24 LTS |
| 29 | `docs/grimo/architecture.md` | modify | Frontend dependency table Node.js version |
| 30 | `CLAUDE.md` | modify | `skillshub/` → `backend/` path references |
| 31 | `docs/grimo/specs/spec-roadmap.md` | modify | S000 status 🔲 → ⏳ |

### Frontend Dependency Versions (package.json)

| Package | Version | Note |
|---------|---------|------|
| `react` | 19.2.5 | latest stable |
| `react-dom` | 19.2.5 | |
| `react-router` | 7.14.2 | |
| `vite` | ^8.0.0 | latest 8.x |
| `typescript` | 6.0.3 | |
| `tailwindcss` | 4.2.4 | |
| `@tailwindcss/vite` | 4.2.4 | |
| `zustand` | 5.0.12 | |
| `@tanstack/react-query` | 5.100.1 | |
| `border-beam` | latest | |
| `vitest` | ^4.0.0 | latest 4.x |
| `@testing-library/react` | ^16.0.0 | |

### Backend Dependency Changes (build.gradle.kts)

| Change | Package |
|--------|---------|
| **ADD** | `org.springframework.boot:spring-boot-starter-data-mongodb` |
| **ADD** | `com.google.cloud:google-cloud-firestore:3.31.6` |
| **ADD** | `org.testcontainers:mongodb` (test) |
| **REMOVE** | `io.github.wimdeblauwe:htmx-spring-boot:5.1.0` |
