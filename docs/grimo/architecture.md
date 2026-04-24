# Skills Hub — Architecture Document

## State at Planning

- 專案目錄含一個 Spring Boot 4.0.6 模板（`backend/`，原名 `skillshub/`）
- 模板已配置：Spring Modulith, Spring AI, Spring Security OAuth2 RS, OpenTelemetry, Testcontainers, GCS, SpringDoc OpenAPI, CycloneDX SBOM
- 模板使用 htmx — 將替換為 React 19 SPA
- 無前端專案 — 需建立 `frontend/` 目錄
- 無資料庫依賴 — 需加入 Spring Data MongoDB（連接 Firestore Enterprise）
- 無業務邏輯代碼

## Packaging Target

**Single container image** — React SPA build output 放入 Spring Boot 的 `src/main/resources/static/`，打包成一個 jar，建置成一個 Docker image，部署到 GCP Cloud Run。

## Dependency Adoption Style

**Front-load（前置鎖定）** — 所有依賴在 S000 Project Init 時鎖定版本，後續 spec 直接使用。

---

## Architecture Pattern: Event Sourcing + CQRS (Core Domain)

### 策略

| 領域 | 模式 | Aggregate | 說明 |
|------|------|-----------|------|
| **skill（核心域）** | **ES + CQRS + Aggregate** | `Skill` (Aggregate Root) | 唯一使用 Aggregate 設計的模組。Skill 為 Aggregate Root，封裝 SkillVersion，維護不變量，產生 domain events |
| **security** | **Event-driven service** | 無 | 監聽 skill events 觸發風險評估，結果以 event 發佈。無自己的 aggregate |
| **search** | **Read-side projection** | 無 | 消費 skill events 建構搜尋索引（keyword + semantic） |
| **analytics** | **Read-side projection** | 無 | 消費 download events 建構統計數據 |
| **storage** | **Infrastructure service** | 無 | 傳統 service，GCS 操作 |

### Skill Aggregate 設計

```
Skill (Aggregate Root)
├── aggregateId: UUID
├── name: String (invariant: unique, lowercase-hyphen, max 64 chars)
├── description: String
├── author: String
├── category: String
├── status: SkillStatus (DRAFT → PUBLISHED → SUSPENDED)
├── versions: List<SkillVersion> (managed by aggregate)
│   └── SkillVersion (Value Object within aggregate)
│       ├── version: String (semver)
│       ├── storagePath: String
│       └── riskLevel: RiskLevel
└── Business Rules (invariants enforced by aggregate):
    - name 不可重複
    - version 必須遞增（semver）
    - SUSPENDED 狀態不可發佈新版本
    - 同一版本號不可重複發佈
```

Aggregate Root 負責：
1. **接收 Command** → 驗證業務規則
2. **產生 Domain Event** → 不可變的領域事實
3. **維護不變量** → 確保 aggregate 內部一致性

其他 module（security, search, analytics, storage）**不使用 aggregate pattern**，直接以 service / listener 形式運作。

### Event Flow

```
                    Command Side                          Event Store                     Query Side
               ┌─────────────────┐                  ┌──────────────────┐           ┌─────────────────┐
  HTTP Request │                 │   Domain Events  │                  │  Consume  │                 │
  ───────────▶ │ SkillCommand    │ ────────────────▶ │  domain_events   │ ────────▶ │  Projections    │
               │ Service         │                  │  (Firestore)     │           │                 │
               │                 │                  │                  │           │  skills (read)  │
               │ - validate      │                  │  {aggregateId,   │           │  skill_versions │
               │ - apply event   │                  │   aggregateType, │           │  search index   │
               │ - publish       │                  │   eventType,     │           │  analytics      │
               └─────────────────┘                  │   payload,       │           └────────┬────────┘
                                                    │   sequence,      │                    │
                                                    │   occurredAt}    │              ┌─────▼──────┐
                                                    └──────────────────┘   HTTP GET   │ Query      │
                                                                          ◀────────── │ Controller │
                                                                                      └────────────┘
```

### Domain Events（skill aggregate）

| Event | Trigger | Payload |
|-------|---------|---------|
| `SkillCreated` | 新 skill 建立 | name, description, author, category, tags |
| `SkillVersionPublished` | 上傳新版本 | version, storagePath, fileSize, frontmatter |
| `SkillRiskAssessed` | 風險評估完成 | version, riskLevel, findings[] |
| `SkillFlagged` | 社群回報 | flagType, description, reportedBy |
| `SkillSuspended` | 管理者停用 | reason |
| `SkillReactivated` | 管理者重新啟用 | reason |
| `SkillDownloaded` | 使用者下載 | version, metadata |

### Spring Modulith Event Integration

```java
// Aggregate Root — 封裝業務規則、產生 events
public class Skill {
    private UUID id;
    private String name;
    private SkillStatus status;
    private List<SkillVersion> versions;

    // 業務方法 → 驗證不變量 → 回傳 domain event
    public SkillVersionPublished publishVersion(PublishVersionCommand cmd) {
        if (this.status == SkillStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot publish to suspended skill");
        }
        if (versions.stream().anyMatch(v -> v.version().equals(cmd.version()))) {
            throw new IllegalArgumentException("Version already exists: " + cmd.version());
        }
        return new SkillVersionPublished(this.id, cmd.version(), cmd.storagePath(), ...);
    }

    // Factory method — 建立新 aggregate
    public static SkillCreated create(CreateSkillCommand cmd) {
        // validate name format, description length, etc.
        return new SkillCreated(UUID.randomUUID(), cmd.name(), cmd.description(), ...);
    }
}

// Command Service — 協調 aggregate + event store + publish
@Service
public class SkillCommandService {
    private final ApplicationEventPublisher events;
    private final DomainEventRepository eventStore;

    public UUID createSkill(CreateSkillCommand cmd) {
        var event = Skill.create(cmd);           // Aggregate 驗證 + 產生 event
        eventStore.append(event);                 // 持久化到 domain_events
        events.publishEvent(event);               // 通知 projections
        return event.aggregateId();
    }

    public void publishVersion(UUID skillId, PublishVersionCommand cmd) {
        var skill = loadAggregate(skillId);        // 從 event store 重建 aggregate
        var event = skill.publishVersion(cmd);     // Aggregate 驗證不變量
        eventStore.append(event);
        events.publishEvent(event);
    }
}

// Query side — projection listener（無 Aggregate，直接更新 read model）
@Component
class SkillProjection {
    private final SkillReadModelRepository readModelRepo;

    @ApplicationModuleListener
    void on(SkillCreated event) {
        // 直接寫入 read model document（不經過 aggregate）
        readModelRepo.save(new SkillReadModel(event));
    }

    @ApplicationModuleListener
    void on(SkillVersionPublished event) {
        readModelRepo.updateLatestVersion(event.aggregateId(), event.version());
    }
}
```

**注意：只有 skill module 的 command side 使用 Aggregate。** security, search, analytics, storage 都是普通的 service / listener，不走 aggregate pattern。

### MVP 範圍 vs Backlog

| 功能 | MVP | Backlog |
|------|-----|---------|
| 儲存 domain events | V | |
| 更新 projection (read model) | V | |
| Event replay（從 events 重建 read model） | | V |
| Snapshot（aggregate 快照） | | V |
| Event upcasting（事件版本遷移） | | V |
| Saga / Process Manager | | V |

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     GCP Cloud Run (single container)         │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │    Spring Boot 4.0.6 + Spring Modulith + ES/CQRS      │  │
│  │                                                        │  │
│  │  React 19 SPA → src/main/resources/static/             │  │
│  │  (Vite 8 + shadcn/ui + Beam + Tailwind 4)             │  │
│  │                                                        │  │
│  │  ┌─ Command Side ──────────────────────────────────┐   │  │
│  │  │  skill (command)  │  security (risk assess)     │   │  │
│  │  │  - validate       │  - scan scripts             │   │  │
│  │  │  - apply event    │  - evaluate risk            │   │  │
│  │  │  - publish        │  - flag management          │   │  │
│  │  └─────────────────────────────────────────────────┘   │  │
│  │                                                        │  │
│  │  ┌─ Query Side ────────────────────────────────────┐   │  │
│  │  │  skill (query)  │ search      │ analytics       │   │  │
│  │  │  - read model   │ - keyword   │ - download stats│   │  │
│  │  │  - projections  │ - semantic  │ - trends        │   │  │
│  │  └─────────────────────────────────────────────────┘   │  │
│  │                                                        │  │
│  │  ┌─ Infrastructure ────────────────────────────────┐   │  │
│  │  │  storage (GCS upload/download, zip packaging)   │   │  │
│  │  │  shared/events (DomainEvent, EventStore)        │   │  │
│  │  └─────────────────────────────────────────────────┘   │  │
│  └────────────────────┬───────────────────────────────────┘  │
└───────────────────────┼──────────────────────────────────────┘
                        │
         ┌──────────────┼──────────────┐
         │              │              │
   ┌─────▼──────┐ ┌─────▼─────┐ ┌─────▼─────┐
   │ Firestore   │ │   GCS     │ │ Vertex AI │
   │ Enterprise  │ │ (skill    │ │ (Gemini   │
   │             │ │  packages)│ │  via      │
   │ Event Store:│ │ - zip/tar │ │  Spring   │
   │ - domain_   │ └───────────┘ │  AI)      │
   │   events    │               └───────────┘
   │             │
   │ Read Models:│
   │ - skills    │
   │ - skill_    │
   │   versions  │
   │ - flags     │
   │ - download_ │
   │   events    │
   │             │
   │ Native SDK: │
   │ - vectors   │
   │ - KNN search│
   └─────────────┘
```

---

## Spring Modulith Module Design

```
io.github.samzhu.skillshub
│
├── shared/                     ← 共用基礎設施
│   ├── events/                 ← Domain Event 基底類別、Event Store
│   │   ├── DomainEvent.java    (abstract base: aggregateId, eventType, occurredAt, sequence)
│   │   ├── DomainEventRepository.java (append, findByAggregateId)
│   │   └── EventStoreConfig.java
│   └── api/                    ← 共用 API 錯誤處理
│       └── ErrorResponse.java
│
├── skill/                      ← 核心域（ES + CQRS + Aggregate）
│   ├── domain/                 ← Aggregate + Domain Events
│   │   ├── Skill.java          (Aggregate Root — 封裝業務規則、產生 events)
│   │   ├── SkillVersion.java   (Value Object — aggregate 內部)
│   │   ├── SkillStatus.java    (enum: DRAFT, PUBLISHED, SUSPENDED)
│   │   ├── SkillCreated.java   (domain event)
│   │   ├── SkillVersionPublished.java
│   │   ├── SkillDownloaded.java
│   │   └── SkillFlagged.java
│   ├── command/                ← Command Side
│   │   ├── CreateSkillCommand.java
│   │   ├── PublishVersionCommand.java
│   │   ├── SkillCommandService.java (載入 aggregate → 執行 command → 存 event)
│   │   └── SkillCommandController.java (POST, PUT)
│   ├── query/                  ← Query Side
│   │   ├── SkillReadModel.java (read projection document)
│   │   ├── SkillVersionReadModel.java
│   │   ├── SkillQueryService.java
│   │   ├── SkillQueryController.java (GET)
│   │   └── SkillProjection.java (@ApplicationModuleListener → 更新 read model)
│   └── validation/             ← SKILL.md 驗證
│       └── SkillValidator.java (agentskills.io 規範)
│
├── security/                   ← Event-driven service（無 Aggregate）
│   ├── RiskLevel.java          (enum: LOW, MEDIUM, HIGH)
│   ├── RiskScanner.java        (靜態分析引擎)
│   ├── RiskAssessmentListener.java (@ApplicationModuleListener on SkillVersionPublished)
│   ├── SkillRiskAssessed.java  (domain event — 由 listener 發佈)
│   ├── FlagService.java        (直接 CRUD，不走 aggregate)
│   └── FlagController.java
│
├── search/                     ← Read-side projection（無 Aggregate）
│   ├── SearchService.java      (keyword search via MongoDB query on read model)
│   ├── SemanticSearchService.java (Spring AI + Gemini + Firestore vector)
│   ├── SearchController.java
│   └── SearchProjection.java   (@ApplicationModuleListener — 更新搜尋索引/embedding)
│
├── analytics/                  ← Read-side projection（無 Aggregate）
│   ├── AnalyticsService.java   (aggregation queries on read model)
│   ├── AnalyticsController.java
│   └── AnalyticsProjection.java (@ApplicationModuleListener on SkillDownloaded)
│
└── storage/                    ← Infrastructure service（無 Aggregate）
    ├── StorageService.java     (GCS upload/download)
    └── PackageService.java     (zip 壓縮/解壓/驗證)
```

### Module Event Flow

```
SkillCommandService
  │ publish SkillCreated
  ├──────────▶ SkillProjection (update read model)
  ├──────────▶ SearchProjection (update search index)
  │
  │ publish SkillVersionPublished
  ├──────────▶ SkillProjection (update latestVersion)
  ├──────────▶ RiskAssessmentListener (trigger scan)
  │                │ publish SkillRiskAssessed
  │                └──────────▶ SkillProjection (update riskLevel)
  │
  │ publish SkillDownloaded
  ├──────────▶ SkillProjection (increment downloadCount)
  └──────────▶ AnalyticsProjection (record download event)
```

---

## Data Model (Firestore / MongoDB)

### Event Store

```
domain_events
├── _id: String (UUID)
├── aggregateId: String (skill UUID)
├── aggregateType: String ("Skill")
├── eventType: String ("SkillCreated" | "SkillVersionPublished" | ...)
├── payload: Document (event-specific data, JSON)
├── sequence: Long (per-aggregate sequential number)
├── occurredAt: Instant
└── metadata: Map<String, String> (optional: userId, correlationId)
```

### Read Model Collections (Projections)

```
skills (read model — projected from events)
├── _id: String (UUID, same as aggregateId)
├── name: String (unique, lowercase-hyphen)
├── description: String
├── author: String
├── category: String
├── tags: [String]
├── latestVersion: String (semver)
├── riskLevel: String (LOW|MEDIUM|HIGH)
├── status: String (DRAFT|PUBLISHED|SUSPENDED)
├── downloadCount: Long
├── createdAt: Instant
├── updatedAt: Instant
└── embedding: [Float] (768-dim, for vector search via native SDK)

skill_versions (read model — projected from SkillVersionPublished + SkillRiskAssessed)
├── _id: String (UUID)
├── skillId: String (ref → skills)
├── version: String (semver)
├── storagePath: String (GCS object key)
├── fileSize: Long
├── riskAssessment: {
│     level: String,
│     findings: [{type, message, file, line}],
│     scannedAt: Instant
│   }
├── frontmatter: Map<String, Object> (raw YAML frontmatter)
├── publishedAt: Instant
└── changelog: String

flags (read model — projected from SkillFlagged)
├── _id: String (UUID)
├── skillId: String (ref → skills)
├── type: String (SECURITY|QUALITY|MISMATCH|OTHER)
├── description: String
├── reportedBy: String (anonymous until auth)
├── createdAt: Instant
└── status: String (OPEN|REVIEWED|RESOLVED)

download_events (read model — projected from SkillDownloaded)
├── _id: String (UUID)
├── skillId: String (ref → skills)
├── version: String
├── downloadedAt: Instant
└── metadata: Map<String, String> (anonymous tracking)
```

---

## Frontend Architecture

```
frontend/
├── src/
│   ├── components/        ← shadcn/ui 元件 + Beam
│   │   ├── ui/            ← shadcn/ui 複製的元件
│   │   └── beam/          ← border-beam 效果包裝
│   ├── pages/
│   │   ├── HomePage.tsx        ← 技能瀏覽、搜尋
│   │   ├── SkillDetailPage.tsx ← 技能詳情、版本歷史
│   │   ├── PublishPage.tsx     ← 技能上傳發佈
│   │   └── AnalyticsPage.tsx   ← 數據儀表板
│   ├── hooks/             ← 自定義 React hooks
│   ├── api/               ← TanStack Query + API client
│   ├── store/             ← Zustand stores
│   ├── App.tsx
│   └── main.tsx
├── package.json
├── vite.config.ts
├── tsconfig.json
└── tailwind.css
```

### Build Integration

Gradle task `buildFrontend` 在 Spring Boot build 之前執行：

```
./gradlew build
  → npm install (frontend/)
  → npm run build (frontend/ → dist/)
  → copy dist/* → backend/src/main/resources/static/
  → Spring Boot jar build
  → Docker image
```

---

## API Design

所有 API prefix: `/api/v1/`

### Command API (Write)

| Method | Path | 說明 | Module |
|--------|------|------|--------|
| POST | `/api/v1/skills` | 建立新 skill（multipart upload） | skill.command |
| PUT | `/api/v1/skills/{id}/versions` | 上傳新版本 | skill.command |
| POST | `/api/v1/skills/{id}/flags` | 提交社群回報 | security |

### Query API (Read)

| Method | Path | 說明 | Module |
|--------|------|------|--------|
| GET | `/api/v1/skills` | 瀏覽/搜尋 skills（keyword, category, tag） | search |
| GET | `/api/v1/skills/{id}` | 取得 skill 詳情 | skill.query |
| GET | `/api/v1/skills/{id}/versions` | 取得版本歷史 | skill.query |
| GET | `/api/v1/skills/{id}/versions/{ver}/download` | 下載指定版本 zip | storage |
| GET | `/api/v1/skills/{id}/download` | 下載最新版本 zip | storage |
| GET | `/api/v1/skills/{id}/risk` | 取得風險評估結果 | skill.query |
| GET | `/api/v1/skills/{id}/flags` | 取得回報列表 | skill.query |
| POST | `/api/v1/search/semantic` | 語意搜尋（自然語言） | search |
| GET | `/api/v1/analytics/overview` | 平台總覽統計 | analytics |
| GET | `/api/v1/analytics/skills/{id}` | 單一 skill 統計 | analytics |
| GET | `/api/v1/categories` | 取得所有分類 | skill.query |

---

## Framework Dependency Table

### Backend (Gradle / Java)

| Package | Version | Primary Import / Module | Verified |
|---------|---------|----------------------|----------|
| `org.springframework.boot:spring-boot-starter-parent` | 4.0.6 | — (BOM) | yes (template) |
| Java JDK | 25 | — | yes (template) |
| Gradle | 9.4.1 | — | yes (template) |
| `org.springframework.boot:spring-boot-starter-webmvc` | BOM | `org.springframework.web.bind.annotation.*` | yes (template) |
| `org.springframework.boot:spring-boot-starter-data-mongodb` | BOM | `org.springframework.data.mongodb.repository.*` | yes (Spring Boot BOM) |
| `com.google.cloud:spring-cloud-gcp-starter` | 8.0.2 BOM | `com.google.cloud.spring.*` | yes (template) |
| `com.google.cloud:spring-cloud-gcp-starter-storage` | 8.0.2 BOM | `com.google.cloud.storage.*` | yes (template) |
| `com.google.cloud:google-cloud-firestore` | 3.31.6 | `com.google.cloud.firestore.*` | yes (Maven Central) |
| `com.google.cloud:google-cloud-vertexai` | 1.24.0 | `com.google.cloud.vertexai.*` | yes (Maven Central) |
| `org.springframework.ai:spring-ai-*` | 2.0.0-M4 BOM | `org.springframework.ai.*` | yes (template) |
| `org.springframework.modulith:spring-modulith-*` | 2.0.6 BOM | `org.springframework.modulith.*` | yes (template) |
| `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` | BOM | `org.springframework.security.*` | yes (template) |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.0.2 | `org.springdoc.*` | yes (template) |
| `io.github.wimdeblauwe:htmx-spring-boot` | 5.1.0 | — | **移除**（改用 React） |
| `org.cyclonedx:cyclonedx-gradle-plugin` | 3.2.4 | — (Gradle plugin) | yes (template) |
| `org.graalvm.buildtools.native` | 0.11.5 | — (Gradle plugin) | yes (template) |

### Frontend (npm / Node.js)

| Package | Version | Primary Import | Verified |
|---------|---------|---------------|----------|
| `react` + `react-dom` | 19.2.5 | `import { useState } from 'react'` | yes (npm) |
| `react-router` | 7.14.2 | `import { BrowserRouter, Routes } from 'react-router'` | yes (npm) |
| `vite` | 8.0.10 | `import { defineConfig } from 'vite'` | yes (npm) |
| `typescript` | 6.0.3 | — | yes (npm) |
| `tailwindcss` | 4.2.4 | `@import "tailwindcss"` | yes (npm) |
| `@tailwindcss/vite` | 4.2.4 | Vite plugin | yes (npm) |
| `zustand` | 5.0.12 | `import { create } from 'zustand'` | yes (npm) |
| `@tanstack/react-query` | 5.100.1 | `import { useQuery } from '@tanstack/react-query'` | yes (npm) |
| `border-beam` | latest | `import { BorderBeam } from 'border-beam'` | yes (npm) |
| `vitest` | 4.1.5 | `import { describe, it, expect } from 'vitest'` | yes (npm) |
| `@testing-library/react` | 16.x | `import { render, screen } from '@testing-library/react'` | yes (npm) |

---

## Firestore Configuration

### Enterprise Edition + MongoDB Compatibility

```yaml
# application.yaml
spring:
  data:
    mongodb:
      uri: mongodb+srv://${GCP_PROJECT_ID}.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC
      database: skillshub
```

### Native SDK (for Vector Search)

```java
// 透過 Application Default Credentials 自動認證
FirestoreOptions options = FirestoreOptions.getDefaultInstance().toBuilder()
    .setProjectId(projectId)
    .setDatabaseId("skillshub")
    .build();
Firestore firestore = options.getService();
```

### Key Constraints

- `retryWrites=false` 必須設定（Firestore MongoDB compat 不支援 retryable writes）
- Change Streams 不支援 — 不能用 MongoDB driver 做即時事件
- Vector indexes 只能透過 Firestore 原生 SDK 建立和查詢
- 需要 Firestore **Enterprise** edition（非 Standard）
- Domain events 設計為 idempotent（因為 retryable writes 關閉，需應用層處理重試）
