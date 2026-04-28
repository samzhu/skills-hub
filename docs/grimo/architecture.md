# Skills Hub — Architecture Document

## State at Planning

- 專案目錄含一個 Spring Boot 4.0.6 模板（`backend/`，原名 `skillshub/`）
- 模板已配置：Spring Modulith, Spring AI, Spring Security OAuth2 RS, OpenTelemetry, Testcontainers, GCS, SpringDoc OpenAPI, CycloneDX SBOM
- 模板使用 htmx — 將替換為 React 19 SPA
- 無前端專案 — 需建立 `frontend/` 目錄
- 資料庫依賴：Spring Data JDBC + PostgreSQL 16 + pgvector（GCP 部署採 Cloud SQL Auth Proxy sidecar；本機 Docker Compose 用 `pgvector/pgvector:pg16`；同一條 JDBC URL — dev/prod parity）。Phase 1 已從前期儲存層遷移完成（詳 ADR-001 + S014 archived spec）
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
               │ Service         │                  │  (PostgreSQL)    │           │                 │
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
   │ PostgreSQL  │ │   GCS     │ │ Vertex AI │
   │ 16 +        │ │ (skill    │ │ (Gemini   │
   │ pgvector    │ │  packages)│ │  via      │
   │             │ │ - zip/tar │ │  Spring   │
   │ (Cloud SQL  │ └───────────┘ │  AI)      │
   │  Auth Proxy │               └───────────┘
   │  sidecar)   │
   │             │
   │ Event Store:│
   │ - domain_   │
   │   events    │
   │   (JSONB)   │
   │             │
   │ Read Models:│
   │ - skills    │
   │ - skill_    │
   │   versions  │
   │ - flags     │
   │ - download_ │
   │   events    │
   │             │
   │ Vector:     │
   │ - vector_   │
   │   store     │
   │   (HNSW +   │
   │    cosine)  │
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
│   ├── SearchService.java      (keyword search via Spring Data JDBC SQL query on read model)
│   ├── SemanticSearchService.java (Spring AI + Gemini + 自訂 SkillshubPgVectorStore HNSW)
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

## Data Model (PostgreSQL)

> Schema 由 Flyway V1 migration 建立（`backend/src/main/resources/db/migration/V1__initial_schema.sql`）；6 張表 + 2 個 extensions（`vector` for pgvector + `uuid-ossp`）+ HNSW 索引；S016/S017 將以 V2/V3 增量加 `acl_entries` JSONB + GIN 索引。

### Event Store

```sql
CREATE TABLE domain_events (
    id              VARCHAR(36) PRIMARY KEY,
    aggregate_id    VARCHAR(36) NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    sequence        BIGINT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (aggregate_id, sequence)
);
CREATE INDEX idx_domain_events_aggregate ON domain_events(aggregate_id, sequence);
```

`(aggregate_id, sequence)` UNIQUE 強制 per-aggregate 嚴格遞增；JSONB payload 存 event-specific schema；transaction 內與 read model 同 commit。

### Read Model Tables (Projections)

4 張 read model 表 — `skills` / `skill_versions` / `flags` / `download_events`，皆以 Spring Data JDBC `@Table` record 表達 + `Persistable<String>.isNew()=true` 強制 INSERT 路徑（避開預設 SELECT-then-UPDATE）；`Map<String,Object>` 欄位（如 `frontmatter`、`risk_findings`）透過 `MapJsonbConverter` 雙向 round-trip JSONB 並保留 nested 型別（per S014 archived §2.1 決策 #5/#6）。

```
skills (PK: id; UNIQUE: name)
├── name (lowercase-hyphen) / description / author / category / tags(JSONB)
├── latest_version (semver) / risk_level / status / download_count
├── created_at / updated_at TIMESTAMPTZ
└── INDEX (category, status)

skill_versions (PK: id; FK → skills)
├── skill_id / version / storage_path (GCS) / file_size
├── risk_findings JSONB / frontmatter JSONB
├── published_at / changelog
└── INDEX (skill_id, published_at DESC)

flags (PK: id; FK → skills)
├── skill_id / type (SECURITY|QUALITY|MISMATCH|OTHER)
├── description / reported_by / status
├── created_at
└── INDEX (skill_id)

download_events (PK: id; FK → skills)
├── skill_id / version / downloaded_at / metadata JSONB
└── INDEX (skill_id, downloaded_at)
```

### Vector Store

`vector_store` 由自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（Spring AI 2.0.0-M4 core artifact）控制；6 欄 atomic INSERT — `id` / `content` / `metadata` JSONB / `embedding` vector(768) / `owner` / `skill_id`；`owner` 為 S016 row-level ACL 鋪路；`ON CONFLICT (id) DO UPDATE` 冪等；HNSW 索引 + cosine distance（`embedding <=> query` operator）。

詳 S014 archived spec §4 + §2.1 決策 #2 / #12（再修訂 — 採 core artifact + 自寫子類，不用官方 starter 因其 4-欄 INSERT 不支援 owner 自訂欄位）。

---

## Frontend Architecture

### UI Design References

設計稿位於 `docs/grimo/ui/`，含互動 HTML mockups。
前端 spec（S002, S004 等）設計前必須先讀 `docs/grimo/ui/README.md`，了解設計決策、頁面清單與元件規範。

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
| `org.springframework.boot:spring-boot-starter-data-jdbc` | BOM | `org.springframework.data.jdbc.*` | yes (S014) |
| `org.springframework.boot:spring-boot-starter-jdbc` | BOM | `org.springframework.jdbc.core.*` | yes (S014) |
| `org.springframework.ai:spring-ai-pgvector-store` | 2.0.0-M4 BOM | `org.springframework.ai.vectorstore.pgvector.*`（core artifact；自寫 `SkillshubPgVectorStore` 子類）| yes (S014) |
| `org.springframework.boot:spring-boot-flyway` | BOM | — | yes (S014) |
| `org.flywaydb:flyway-core` | BOM-managed | `org.flywaydb.core.*` | yes (S014) |
| `org.flywaydb:flyway-database-postgresql` | runtime | — | yes (S014) |
| `org.postgresql:postgresql` | runtime | JDBC driver | yes (S014) |
| `com.google.cloud:spring-cloud-gcp-starter` | 8.0.2 BOM | `com.google.cloud.spring.*` | yes (template) |
| `com.google.cloud:spring-cloud-gcp-starter-storage` | 8.0.2 BOM | `com.google.cloud.storage.*` | yes (template) |
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

## PostgreSQL Configuration

### Local Development（Docker Compose）

```yaml
# backend/compose.yaml — pgvector image
services:
  postgres:
    image: pgvector/pgvector:pg16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: skillshub
      POSTGRES_USER: skillshub
      POSTGRES_PASSWORD: skillshub
```

### GCP Production（Cloud SQL Auth Proxy sidecar）

Cloud Run multi-container：main container + `gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest` sidecar；共享 localhost network；應用端透過 `localhost:5432` 連線，**無需 socket-factory dep**。

```yaml
# scripts/gcp/service.yaml (S013 follow-up spec 實作)
spec:
  template:
    spec:
      containers:
        - name: skillshub
          image: <region>-docker.pkg.dev/<project>/skillshub/skillshub:<tag>
          env:
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://localhost:5432/skillshub
        - name: cloud-sql-proxy
          image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest
          args: ["--private-ip", "<project>:<region>:<instance>"]
```

**dev/prod parity**：本機 + GCP 同條 JDBC URL `jdbc:postgresql://localhost:5432/<db>`（只差 env var 帶不同 db name / user / password）。

### Connection Pool（HikariCP）

| Env | maximum-pool-size | minimum-idle | 理由 |
|-----|-------------------|--------------|------|
| GCP（db-f1-micro） | 3 | 1 | DB `max_connections=25` − 5 reserved = 20 ÷ ~5 instances ≈ 4/inst → 取 3 留 buffer |
| Local dev | 10 | 2 | 寬鬆 |

### Schema Migration（Flyway）

`backend/src/main/resources/db/migration/V1__initial_schema.sql` 建 6 張表（`domain_events` / `skills` / `skill_versions` / `flags` / `download_events` / `vector_store`）+ `CREATE EXTENSION IF NOT EXISTS vector` + `CREATE EXTENSION IF NOT EXISTS uuid-ossp` + HNSW 索引（vs_emb_idx）；S016/S017 增量 V2/V3 加 `acl_entries` JSONB + GIN(`jsonb_path_ops`)。

### Key Constraints

- pgvector extension 啟用：Cloud SQL 須在 instance 建立時配置 `cloudsql.enable_pgvector = on`（PostgreSQL 18 支援；V1 migration 含 `CREATE EXTENSION` 但 instance flag 必須先在 GCP 端啟用）
- IAM 授權自動化：sidecar 用 Cloud Run service account 連 Cloud SQL；無需 DB password 進 Secret Manager（Auth Proxy 自動 IAM token 換 DB token）
- Domain events 寫入與 read model projection 同 transaction（Spring Modulith `@ApplicationModuleListener` outbox 規劃中 — 詳 Backlog S023）

詳 ADR-001 §4.4。
