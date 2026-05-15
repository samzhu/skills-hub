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

## Architecture Pattern: Spring Data JDBC Rich Aggregate + Modulith Outbox (Core Domain)

> 本段於 v2.0.0（S024 ship 2026-04-29）改寫；前身為 ES + CQRS 模式（v1.4.0 為止）。
> 詳 [ADR-002 — Skill Aggregate State-Based](../grimo/adr/ADR-002-skill-aggregate-state-based.md)（Accepted 2026-04-29）。
> v1.5.0 為止仍採 ES + CQRS 模式為「過渡狀態」；S024 起 Skill domain 完成轉向。

### 策略

| 領域 | 模式 | Aggregate | 說明 |
|------|------|-----------|------|
| **skill（核心域）** | **Spring Data JDBC 充血聚合 + Modulith Outbox** | `Skill` / `SkillVersion`（獨立 aggregate） | S024 起轉向；`@Table` + `extends AbstractAggregateRoot` + `@Version` 樂觀鎖；`repo.save()` 透過 `@DomainEvents` 自動 publish events 至 outbox（同 TX） |
| **security** | **Event-driven service** | 無 | 訂閱 `SkillVersionPublishedEvent` 觸發風險評估，透過 `SkillVersion.attachRiskAssessment` 回寫；S147 起 `ScanContext.packageFiles` 掃 zip 內所有 UTF-8 文字檔，`IssueDetector` / `LlmIssueRule` 產出 issue-code findings；`SecurityReportService` + `SecurityReportController` 回傳 legacy `checks` 與新 `categories/findings` |
| **search** | **Read-side projection** | 無 | 消費 skill events 建構搜尋索引（keyword + semantic） |
| **analytics** | **Read-side projection** | 無 | 消費 download events 建構統計數據 |
| **audit** | **Cross-cutting listener** | 無 | 訂閱 Skill / Grant domain events 寫入 `domain_events` audit log（async + idempotent；S024 引入；S177 把 Grant events 納入 audit） |
| **score** | **Async LLM judge** | `SkillScore`（per-axis evaluation row） | S135a 引入；訂閱 `SkillVersionPublishedEvent` → 3-axis 品質評分（VALIDATION rule-based + IMPLEMENTATION/ACTIVATION Gemini 2.5 Flash LLM judge）→ 寫 `skill_scores` 表；獨立 `qualityExecutor` pool（corePool=1, queue=500）避免擠 `applicationTaskExecutor`；S142b 加 `SkillScoreCalculator`（composite `round(0.6 × quality + 0.4 × security)` → `skillScore` field in `/scores` response） |
| **storage** | **Infrastructure service** | 無 | 傳統 service，GCS 操作 |

### Core Concepts

**Skill aggregate**（`@Table("skills") extends AbstractAggregateRoot<Skill>`）
- `@Id` / `@Version` / `@Column` 直接綁定 PostgreSQL `skills` 表 row（1:1 mapping）
- 業務 method 充血：mutate state field + `registerEvent(domainEvent)`
- `repository.save(skill)` 自動觸發 `@DomainEvents` proxy interceptor → events 進 Modulith `event_publication` outbox（同 TX）
- 業務不變量內建（`status.suspend()` state machine guard、`aclEntries.contains` 重複檢查 etc）

**SkillVersion aggregate**（獨立 `@Table("skill_versions")`）
- 與 Skill 透過 plain `String skillId` FK 引用（**不**用 `@MappedCollection` / `AggregateReference`）
- 避開 Spring Data JDBC `WritingContext.update()` delete-and-reinsert 雷（per [deepwiki/aggregate-design.md §2](../deepwiki/spring-data-jdbc-modulith/aggregate-design.md)）
- `attachRiskAssessment(Map)` 充血方法 + `ScanOrchestrator` AFTER_COMMIT async 路徑

**Grant domain events**
- S177 實作保留既有 `skill.security` package；`SkillGrant` 是 Spring Data JDBC entity，`SkillGrantedEvent` / `SkillRevokedEvent` 由 `SkillGrantService` publish。
- 建立 skill 當下同 TX 寫入的 OWNER grant、以及 public skill 的 public VIEWER grant，也發 `SkillGrantedEvent`；private create 固定兩筆 event（SkillCreated + OWNER SkillGranted），public create 因多 public VIEWER grant 為三筆 event。`SkillCreatedEvent` 記錄 skill 已建立，`SkillGrantedEvent` 記錄 grant 已建立。
- Grant events 是跨模組可訂閱的公開 domain events，放在 `skill.security.events`。
- Grant command transaction 寫 `skill_grants` row 與強一致的 `skills.acl_entries`；`SkillAclProjectionListener` AFTER_COMMIT 回查目前 grants 與 `skills.is_public`，重建 `vector_store.is_public` / read-only `vector_store.acl_entries`。

### Aggregate State Mutation Flow

```
Command → Service.@Transactional method:
  1. skillRepo.findById(id).orElseThrow()       # O(1) row read（vs ES O(events) replay）
  2. skill.businessMethod(cmd)                   # mutate state + registerEvent
  3. skillRepo.save(skill)                       # @DomainEvents proxy interceptor:
                                                 #   ① UPDATE skills SET ... WHERE id=? AND version=?
                                                 #   ② publish events → event_publication outbox INSERT 同 TX
  TX commit:
    - skills row UPDATED
    - event_publication row INSERTED（completion_date NULL → 等待 listener）
  AFTER_COMMIT async（@ApplicationModuleListener）：
    - SearchProjection 訂閱 → vector_store INSERT/UPDATE
    - SkillAclProjectionListener 訂閱 Grant events → vector_store read scope UPDATE
    - AnalyticsProjection 訂閱 → download_events INSERT (idempotent via eventId)
    - AuditEventListener 訂閱 Skill / Grant events → domain_events INSERT (audit log；deterministic UUID + ON CONFLICT DO NOTHING)
    - ScanOrchestrator 訂閱（SkillVersionPublishedEvent only）→ multi-engine scan pipeline
    - 各 listener 完成 → event_publication.completion_date = now()
```

### Public Domain Events（Skill domain；7 個）

Skill events 是跨模組可訂閱的公開 domain events，放在 `skill.domain`。

| Event | Trigger | Payload |
|-------|---------|---------|
| `SkillCreatedEvent` | `Skill.create(cmd)` | name, description, author, category, isPublic |
| `SkillVersionPublishedEvent` | `SkillVersion.publish(cmd)` | version, storagePath, fileSize, allowedTools, sourceEventId |
| `SkillVersionPublishedFromAggregate` | `Skill.recordVersionPublished(version)`（state-change marker） | version |
| `SkillSuspendedEvent` | `Skill.suspend(cmd)` | reason, suspendedBy |
| `SkillReactivatedEvent` | `Skill.reactivate(cmd)` | reason |
| `SkillDownloadedEvent` | `Skill.recordDownload()` | version, eventId |
| `SkillRiskAssessedEvent` | `SkillVersion.attachRiskAssessment(map)` | skillId, version, level, findings |

### Public Domain Events（Grant domain；S177 後）

Grant events 是跨模組可訂閱的公開 domain events，放在 `skill.security.events`。

| Event | Trigger | Payload | Known subscribers |
|-------|---------|---------|-------------------|
| `SkillGrantedEvent` | `SkillGrantService.grant(...)` saves any `skill_grants` role row, including create-time OWNER and public VIEWER | skillId, grantId | `SkillAclProjectionListener`回查目前 grants 與 `skills.is_public`，更新 `vector_store.is_public`，並從 user/group/company roles 產生 read-only `vector_store.acl_entries` |
| `SkillRevokedEvent` | `SkillGrantService.revoke(...)` hard-deletes grant row | skillId, grantId | `SkillAclProjectionListener`回查目前 grants 與 `skills.is_public`，更新 `vector_store.is_public`，並從 user/group/company roles 產生 read-only `vector_store.acl_entries` |

> **S177 重設（2026-05-15）**：`skill` 是大模組，內含 Skill 與 Grant 兩個緊密連動的子領域模型。
> Grant 負責 `skill_grants` 的授權對象與角色；Skill 負責 skill 本身資料與 public visibility。建立 skill 時，
> `SkillCreatedEvent` 與初始 `SkillGrantedEvent` 在同一個 transaction 內 publish；Grant event 觸發 search read-scope projection。
> public toggle 不另發 visibility event；同一個 transaction 內更新 `skills.is_public` 與 public VIEWER grant row，
> 對外只發 `SkillGranted(isPublic=true, public/* VIEWER)` / `SkillRevoked(isPublic=false, public/* VIEWER)`，
> 訂閱方依 payload 自行判斷是否處理。

> **S169 更新（2026-05-14 v4.57.0；S177 前狀態）**：`skill_grants.role` 支援 `OWNER` / `EDITOR` / `VIEWER`。
> 當時 listener 會把 role grant 展成 `principal:verb` ACL entries，並同步寫入 `skills.acl_entries`
> 與 `vector_store.acl_entries`；S177 後這個 listener 職責被拆成同 TX `skills.acl_entries` writer
> 與 async `SkillAclProjectionListener`。API 不輸出 `aclEntries`；Skill detail 改輸出 `viewerPermissions`
> 給前端顯示編輯、刪除、分享、管理 grants 按鈕。多筆讀取與 semantic search 都先用 S170
> `PrincipalContextService.currentPrincipalKeys()` 產生 `user:<id>` / `group:<id>` principal keys，
> 再在 SQL JSONB ACL clause 過濾。

> **S177 計畫更新（2026-05-15）**：public visibility 從 ACL 拆出成 `skills.is_public`
> / `vector_store.is_public`；`skills.acl_entries` 與 `vector_store.acl_entries` 只保留 user/group/company
> explicit read/write/delete scope。`skills.acl_entries` 由 grant application service 在同一個 transaction
> 內重建；`SkillAclProjectionListener` async 訂閱 Grant events 維護 `vector_store.is_public` / `vector_store.acl_entries`。
> S177 在 `skill` 大模組內保留 Skill / Grant 兩個子領域模型：`Skill` 管理 skill 本身資料與 public visibility；
> `Grant` 管理授權對象與角色。Grant 角色變動時，同 TX 寫入/刪除 `skill_grants` row 並重建
> `skills.acl_entries` 作為 browse/detail/read/write/delete 判斷優化；projection 再依 Grant event
> 非同步更新 `vector_store.acl_entries` 的 read scope。

### Code Pattern

```java
// Aggregate — Spring Data JDBC 充血聚合
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> implements Persistable<String> {
    @Id private String id;
    @Version @JsonIgnore private Long version;       // 樂觀鎖；不 expose API JSON
    private SkillStatus status;
    private List<String> aclEntries;                  // JSONB projection; @JsonIgnore in API

    public static Skill create(CreateSkillCommand cmd) {
        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        // ... mutate state
        skill.registerEvent(new SkillCreatedEvent(...));
        return skill;
    }

    public void suspend(SuspendCommand cmd) {
        this.status = this.status.suspend();          // state machine guard
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }
    // ... reactivate / recordDownload / etc — 同樣 mutate + register pattern
}

// Service — 3-line orchestration
@Service
public class SkillCommandService {
    @Transactional
    public void suspend(SuspendCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.suspend(cmd);
        skillRepo.save(skill);   // @DomainEvents → outbox → AFTER_COMMIT listeners
    }
}

// AuditEventListener — 訂閱事件寫 audit log（async）
@Component
public class AuditEventListener {
    @ApplicationModuleListener
    void on(SkillSuspendedEvent event) {
        // 確定性 UUID + per-aggregate advisory lock + ON CONFLICT DO NOTHING
        recordAudit(event.aggregateId(), "SkillSuspended", payload, dedupKey);
    }
}
```

### v1.5.0 ES path 殘留 / 仍保留的能力

- **歷史 events 完整保留**：v1.4.0 之前的 `domain_events` row 仍在 DB；ES 精神不變 — 序列化的 events 理論上仍可 replay 出任何時點的 aggregate state
- **不再主動 replay**：S024 起寫入端走 aggregate state，平時 `repo.findById()` 即足夠（O(1) row read，小專案 read-heavy 場景下顯著快於 events fold）
- **ES path API 已移除**：`Skill(String, List<DomainEvent>)` replay constructor、`create(String,...)` event-returning factory、deprecated `publishVersion/suspend/...` overloads 於 S024 T05B 完整刪除（如需 emergency replay，可在後續 spec 補回 `Skill.fromHistory(events)` 私有 factory，從 `domain_events` 重建 state）
- **security domain 簡化 ES**：`SkillFlaggedEvent` 由 `FlagService` 直接寫 `domain_events`（流量低、event 簡單；無轉向計畫）

### MVP 範圍 vs 機制取捨（updated v2.0.0）

實務小專案不為 hypothetical 場景預先建構 ES 重型機制；當需求真實出現再評估：

| 機制 | MVP | 狀態 |
|------|-----|------|
| 充血聚合 + Modulith outbox | V | S024 ship v2.0.0 |
| Audit log / event log（domain_events 表）— ES 精神保留 | V | S024 by AuditEventListener；理論上可 replay |
| Event replay（重建 state） | — | **不主動使用** — `repo.findById()` O(1) 取代；events 序列保留，需要時可寫 `fromHistory` factory |
| Aggregate Snapshot | — | **不需** — replay 不在熱路徑，無快照優化必要 |
| Event Upcasting | — | **不需** — 小專案 schema 演化用 Flyway migration + 反序列化 fallback；不引入 upcaster 框架 |
| Saga / Process Manager | — | **延後** — 若未來企業級組織管理需求出現再評估獨立技術選型 |

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     GCP Cloud Run (single container)         │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │    Spring Boot 4.0.6 + Spring Modulith + 充血聚合      │  │
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
   │ PostgreSQL  │ │   GCS     │ │ Google AI │
   │ 16 +        │ │ (skill    │ │ (Gemini   │
   │ pgvector    │ │  packages)│ │  via      │
   │             │ │ - zip/tar │ │  Spring   │
   │ (Cloud SQL  │ └───────────┘ │  AI)      │
   │  Auth Proxy │               └───────────┘
   │  sidecar)   │
   │             │
   │ Aggregates  │
   │ + Audit Log:│
   │ - skills    │
   │ - skill_    │
   │   versions  │
   │ - domain_   │
   │   events    │
   │   (audit)   │
   │ - event_    │
   │   publication│
   │   (outbox)  │
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
│   ├── events/                 ← Domain Event 基礎 + audit log repository
│   │   ├── DomainEvent.java    (record: aggregateId, eventType, occurredAt, sequence, payload jsonb)
│   │   ├── DomainEventRepository.java (CRUD + saveAuditIdempotent for AuditEventListener)
│   │   └── (audit log；S024 起非 source of truth)
│   └── api/                    ← 共用 API 錯誤處理
│       └── ErrorResponse.java
│
├── audit/                      ← S024 引入：audit log async 寫入（避開 shared → skill cycle）
│   └── AuditEventListener.java  (@ApplicationModuleListener — 訂閱 Skill / Grant domain events
│                                  寫 domain_events row；deterministic UUID + ON CONFLICT)
│
├── skill/                      ← 核心域（Spring Data JDBC 充血聚合 + Modulith outbox）
│   ├── domain/                 ← Aggregate + Repository + Domain Events
│   │   ├── Skill.java          (@Table extends AbstractAggregateRoot — 充血方法 mutate state + registerEvent)
│   │   ├── SkillVersion.java   (獨立 aggregate；plain String skillId FK)
│   │   ├── SkillRepository.java (Spring Data JDBC ListCrudRepository + updateRiskLevel @Modifying @Query)
│   │   ├── SkillVersionRepository.java (CRUD + 4 derived queries: existsBySkillIdAndVersion etc)
│   │   ├── SkillStatus.java    (enum: DRAFT → PUBLISHED → SUSPENDED；state machine guard)
│   │   ├── SkillCreatedEvent / SkillVersionPublishedEvent / SkillVersionPublishedFromAggregate
│   │   ├── SkillSuspendedEvent / SkillReactivatedEvent
│   │   ├── SkillDownloadedEvent / SkillRiskAssessedEvent
│   ├── command/                ← Command Side（3-line orchestration）
│   │   ├── CreateSkillCommand / PublishVersionCommand / SuspendCommand / etc
│   │   ├── SkillCommandService.java (load → mutate → save；無 eventStore.save 直接寫)
│   │   └── SkillCommandController.java (POST, PUT)
│   ├── query/                  ← Query Side（直打 aggregate repositories；S024 起無 read-model 中介）
│   │   ├── SkillQueryService.java (skillRepo.findById / search via NamedParameterJdbcTemplate + Skill.fromRow)
│   │   ├── SkillQueryController.java (GET — response type Skill / SkillVersion；@JsonIgnore version)
│   │   └── SkillAclQueryService.java (skillRepo.findById → ACL entry 拆解)
│   ├── grant/                  ← Grant 子領域（skill_grants 現況表 + Grant aggregate events）
│   │   ├── SkillGrant.java     (@Table extends AbstractAggregateRoot — create/revoke register Grant events)
│   │   ├── SkillGrantRepository.java (CRUD + findBySkillId / findBySkillIdAndPrincipal...)
│   │   ├── SkillGrantService.java (grant/revoke orchestration；同 TX 重建 skills.acl_entries)
│   │   ├── SkillGrantController.java (POST/GET/DELETE /api/v1/skills/{id}/grants)
│   │   ├── SkillAclEntriesBuilder.java / SkillAclEntryWriter.java
│   │   └── events/             (public domain events: SkillGrantedEvent / SkillRevokedEvent)
│   ├── validation/             ← SKILL.md 驗證
│   │   └── SkillValidator.java (agentskills.io 規範)
│   └── testsupport/            ← S140：E2E fixture seeding endpoints（@Profile-gated, non-prod only）
│       ├── TestDataController.java   (@RestController @Profile({"local","dev","e2e"})；
│       │                              POST /internal/test/{reset,seed/skill,seed/download-event}）
│       ├── SeedSkillRequest.java / SeedDownloadEventRequest.java (DTO records)
│       └── E2EEmbeddingConfig.java   (@Configuration @Profile("e2e")；
│                                      @Primary deterministic 768-dim stub EmbeddingModel
│                                      取代 NoOp / Google bean，e2e 跑無 GenAI 依賴)
│
├── security/                   ← Event-driven service（無 Aggregate）
│   ├── RiskLevel.java          (enum: LOW, MEDIUM, HIGH)
│   ├── SecurityCategoryMapper.java (S142b: 4-quad partition + scoring；SHELL/PATHS/SECRETS/DEPS)
│   ├── SecurityReportService.java  (S142b: GET /security-report — 讀 riskAssessment JSONB → 4-quad response)
│   ├── SecurityReportController.java (S142b: GET /api/v1/skills/{id}/security-report)
│   ├── SecurityReportResponse.java   (S142b: 4-quad response DTO)
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

### Event Log（domain_events 表）

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

> **S024 ship 後（v2.0.0）的角色**：`domain_events` 仍保留完整 ES 精神 — 每筆 row 為不可變 domain event，`(aggregate_id, sequence)` 嚴格遞增，**理論上可從 events replay 還原任意時點的 aggregate state**。但寫入路徑改變：不再是「業務寫入端的 source of truth」，而是 [`AuditEventListener`](../../backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java) async 訂閱 Modulith outbox 後統一寫入。詳 [ADR-002](./adr/ADR-002-skill-aggregate-state-based.md)。
>
> **為何拆寫入端**：Skill aggregate state 改由 `skills` 表直接持有（`@Table` mapping + `@Version` 樂觀鎖；`repo.findById()` O(1) 取代 ES O(events) replay）— **平時不 replay**（小專案規模 read-heavy；O(1) row read 顯著快於 events fold）。但事件序列保留 → emergency replay / audit trail / 未來 read model 重建仍可用 events 做（當需求出現再做，現階段不為 hypothetical 場景投入快照、upcaster、saga 等重型機制）。

**冪等性設計**：AuditEventListener 用 `UUID.nameUUIDFromBytes(dedupKey)` 確定性映射 row id + `INSERT ... ON CONFLICT (id) DO NOTHING` — Modulith retry 同事件不產生 duplicate row。同一 aggregate 上多 listener 並發以 `pg_advisory_xact_lock(hashtext('audit:' || aggregate_id))` 序列化，避免 `MAX(sequence) + 1` race（`FlagService` 寫 `SkillFlaggedEvent` 同樣加入此 lock 避免跨 listener 衝突）。

**security domain 簡化路徑**：`SkillFlaggedEvent` 由 `FlagService` 直接寫 `domain_events`（不走 aggregate；流量低、event 簡單；S024 T05B 後保留無變動）。歷史 v1.4.0 之前 ES write events row 仍在表中；新 events 由 AuditEventListener 補齊統一格式，整段序列保持完整可 replay。

### Spring Modulith Outbox（S023 起）

V4 migration 加入 `event_publication` + `event_publication_archive` 表（per Spring Modulith 2.0.6 V2 schema）— transactional outbox 取代手動 `ApplicationEventPublisher.publishEvent()` 的 best-effort 投遞：

- `event_publication` INSERT 與業務 entity SQL **同 transaction**（atomic outbox）
- 失敗 listener 留 `status='FAILED'` 可由 `IncompleteEventRepublishTask`（@Scheduled + @SchedulerLock）排程重投
- `applicationTaskExecutor`（`AsyncListenerConfig`）為有界 `ThreadPoolTaskExecutor`（corePool=2 / maxPool=2 / queue=200）— 對齊 GCP Cloud SQL `db-f1-micro` HikariCP `maximum-pool-size: 3`
- 觀測：`event_publication.failed.count` + `event_publication.incomplete.count` Micrometer gauge；`/actuator/modulith` 列模組依賴

V5 migration 加 `shedlock` 表 — 多 Cloud Run instance 排程 retry 互斥（ShedLock 7.7.0 + `JdbcTemplateLockProvider` + `usingDbTime()` 規避 cluster clock skew）。

S023 採 hybrid listener migration — 11 個 listener 中 9 個改 `@ApplicationModuleListener`（async + AFTER_COMMIT + REQUIRES_NEW），2 個 FK target row 創建者（`SkillProjection.on(SkillCreatedEvent / SkillVersionPublishedEvent)`）保留 `@EventListener`（同步寫 row 給後續 async listener FK 參考），S024 廢除。詳見 S023 spec §2.2 hybrid migration 表 + ADR-002 §5。

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

`vector_store` 由自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（Spring AI 2.0.0-M6 core artifact）控制；7 欄 atomic INSERT — `id` / `content` / `metadata` JSONB / `embedding` vector(768) / `owner` / `skill_id` / `acl_entries`；`owner` / `skill_id` / `acl_entries` 支援 S016/S017 ACL-aware similarity search；`ON CONFLICT (id) DO UPDATE` 冪等；HNSW 索引 + cosine distance（`embedding <=> query` operator）。

詳 S014 archived spec §4 + §2.1 決策 #2 / #12（再修訂 — 採 core artifact + 自寫子類，不用官方 starter 因其 4-欄 INSERT 不支援 owner 自訂欄位）。

### AI Model Wiring

Spring AI 2.0.0-M6 採 manual config。`shared.ai.AiModelConfig` 是唯一可直接使用 `com.google.genai.Client`、`GoogleGenAiChatModel`、`GoogleGenAiTextEmbeddingModel` 的 production config；`shared.ai` 以 Modulith `shared :: ai` named interface 對 search / security 開放相容 factory。provider builder 在這裡建立 concrete implementation，但 bean return type / runtime dependency 走 Spring AI 抽象：chat provider 是 `ChatModel`，use-case client 是具名 `ChatClient`（`qualityJudgeChatClient` / `scannerChatClient` / `searchIntentChatClient`），embedding provider 是 `EmbeddingModel`。

Spring AI auto-config 保持關閉：base config 設 `spring.ai.model.chat=none`、`spring.ai.model.embedding.text=none`、`spring.ai.chat.client.enabled=false`。`SkillshubPgVectorStore` 不改成官方 `PgVectorStore`；它是專案 ACL schema / SQL 的客製 vector store，只消費 `EmbeddingModel` 產生向量。

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

Gradle 不 invoke npm（S132 起解耦）。前端 build 與後端 image build 在 CI 兩條 lane
串接；本機 dev 兩端獨立啟動，避免前端 TS 錯誤擋住後端 `bootRun`。

```
本機 dev（並行兩 terminal）
─────────────────────────────────────────────
  cd frontend && npm run dev          ← Vite hot reload @ 5173
  cd backend  && ./gradlew bootRun    ← Spring Boot @ 8080（純後端）

CI（Cloud Build trigger: push to main）— S132
─────────────────────────────────────────────
  Step 1  node:22                    npm ci && npm run build
  Step 2  alpine                     cp -r frontend/dist/. backend/src/main/resources/static/
  Step 3  eclipse-temurin:25-jdk     ./gradlew bootBuildImage --imageName=<AR>:$SHORT_SHA
  images: <AR>:$SHORT_SHA            ← Cloud Build 自動 push 到 Artifact Registry

本機 manual deploy（保留路徑；scripts/gcp/03-build-push.sh）
─────────────────────────────────────────────
  Script 自帶 npm ci + npm run build + cp 三步前置，後接 bootBuildImage + docker push（SHA + :latest 雙 tag）
```

### E2E Workspace（per ADR-007；S140 critical-path backfill ✅）

`e2e/` 為獨立 Playwright workspace（與 `backend/` / `frontend/` 並列 repo root），不屬任一側。`playwright-expert` skill 統一管理 BOOTSTRAP / DESIGN / VERIFY 三個流程節點，跨 skill 透過 `e2e/results/evidence.json` 契約檔互通。

S140 ship 後 V07（`--grep @happy-path`）入帶 6 支 critical-path spec（PRD P1-P6 + Quality Score），對應 `skill.testsupport` backend 三個 fixture seeding endpoint（per Pattern 1 / fixtures-patterns.md）。`backend/src/main/resources/application-e2e.yaml` 提供 e2e 行為配置：`oauth.enabled=false` LAB mode + `scanner.engines.llm.enabled=false` 關 LlmJudge 避開 Gemini 5-15s scan（per S157 §7.6）+ `semantic-similarity-threshold=0.1` 過濾雜訊但保留 word-overlap signal + `testsupport.E2EEmbeddingConfig` word-overlap biased 768-dim stub `EmbeddingModel` `@Primary`（同 token doc/query → cosine ≈ 0.2；無 overlap → ±0.05 random noise）。`TestDataController /reset` 先 poll `event_publication.completion_date IS NULL` 排空（15s budget）等 AFTER_COMMIT listener 釋 row lock 再 TRUNCATE，避開 deadlock。Cloud Build 不啟用 e2e profile（per S132 §8 baked profile），production binary 完全不含 testsupport bean / e2e yaml。

```
e2e/
├── .gitignore                  ← managed marker block by ensure-latest.sh
├── package.json                ← @playwright/test ^1.59.1
├── playwright.config.ts        ← Recipe A: Spring Boot bootRun + Vite webServer
├── tests/                      ← spec test files（per spec-id；@<spec-id> @ac-N @happy-path tags）
├── results/                    ← gitignored: report.json + evidence.json（cross-skill contract）
├── playwright-report/          ← gitignored: HTML report + 內嵌 trace.zip 連結
└── test-results/               ← gitignored: per-test trace.zip + screenshot + video（only-on-failure）
```

執行：

```bash
cd e2e && npx playwright test --grep @<spec-id>     # spec-targeted run
cd e2e && npx playwright test --grep @happy-path    # V07 critical-path gate
npx playwright show-trace e2e/test-results/.../trace.zip   # 本機 trace viewer（純 local）
```

或 trace.zip 拖到 trace.playwright.dev — 官方靜態 PWA，純前端不上傳資料。詳 ADR-007 + `playwright-expert/references/caller-protocol.md`。

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
| GET | `/api/v1/skills/{id}/security-report` | 取得安全報告：legacy checks + issue-code categories/findings（S147） | security |
| GET | `/api/v1/skills/{id}/scores` | 取得品質評分（含 skillScore composite） | score |
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
| `org.springframework.ai:spring-ai-pgvector-store` | 2.0.0-M6 BOM | `org.springframework.ai.vectorstore.pgvector.*`（core artifact；自寫 `SkillshubPgVectorStore` 子類）| yes (S014 / S171) |
| `org.springframework.boot:spring-boot-flyway` | BOM | — | yes (S014) |
| `org.flywaydb:flyway-core` | BOM-managed | `org.flywaydb.core.*` | yes (S014) |
| `org.flywaydb:flyway-database-postgresql` | runtime | — | yes (S014) |
| `org.postgresql:postgresql` | runtime | JDBC driver | yes (S014) |
| `com.google.cloud:spring-cloud-gcp-starter` | 8.0.2 BOM | `com.google.cloud.spring.*` | yes (template) |
| `com.google.cloud:spring-cloud-gcp-starter-storage` | 8.0.2 BOM | `com.google.cloud.storage.*` | yes (template) |
| `com.google.cloud:google-cloud-vertexai` | 1.24.0 | `com.google.cloud.vertexai.*` | yes (Maven Central) |
| `org.springframework.ai:spring-ai-*` | 2.0.0-M6 BOM | `org.springframework.ai.*` | yes (S171 POC compile) |
| `org.springframework.ai:spring-ai-google-genai` | 2.0.0-M6 BOM | `org.springframework.ai.google.genai.*` (Gemini Chat + Embedding via Google AI Studio direct API; **not** Vertex AI; provider classes only in `AiModelConfig`) | yes (S007 / S010 / S135a / S171) |
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

### E2E（Playwright workspace；per ADR-007）

| Package | Version | Primary Import | Verified |
|---------|---------|---------------|----------|
| `@playwright/test` | ^1.59.1 | `import { test, expect } from '@playwright/test'` | yes (npm + bootstrap 2026-05-07 commit 31727db) |
| Chromium Headless Shell | 1217 (Chrome 147.0.7727.15) | `--only-shell` install variant; cdn.playwright.dev | yes (smoke verified 2026-05-07) |

---

## GraalVM AOT Strategy

> Source: S148b POC v4 (2026-05-09) — `nativeCompile` BUILD SUCCESSFUL in 3m 17s 出 223 MB native binary，證實 reflection metadata 完整度足夠。本段記錄目前 AOT 編譯機制現況與啟用 native production deploy 的升級路徑。

### (a) Production deploy mode：GraalVM native image

Production 跑 **GraalVM native image**（Paketo `paketo-buildpacks/native-image` buildpack 自動觸發 — `org.graalvm.buildtools.native` 0.11.5 plugin 在 META-INF 寫入 GraalVM metadata 後 Paketo `noble-java-tiny` order group 優先選 `java-native-image` buildpack；`cloudbuild.yaml` E2_HIGHCPU_32 + 32GB heap 即為配合 native compile 之配置）。Cloud Run image 為 ELF executable，cold start 顯著低於 JVM mode。

```bash
cd backend && ./gradlew nativeCompile               # 手動產 native binary（local 約 3m）
ls backend/build/native/nativeCompile/skillshub      # ~223 MB ELF executable
```

**已知 native image 限制 + workaround**：

| Bug 來源 | 現況 | 我們的處置 |
|---|---|---|
| [oracle/graal#5672 GR-45258](https://github.com/oracle/graal/issues/5672) — SubstrateVM MethodHandle adaptation 把 BOOLEAN column 讀回的 Boolean corrupt 成 Integer，AOT-generated entity accessor 灌進 primitive `boolean` field 拋 IAE | 上游 open，無 fix release | **S168** — `JdbcConfiguration.IntegerToBooleanConverter`（@ReadingConverter）在 Spring Data JDBC mapping pipeline 攔截 Integer → Boolean，繞過 GraalVM bug；scope 全域 entity primitive boolean field |
| [spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186) | duplicate of MongoDB #5101，Spring 認定 GraalVM 上游 bug | 同上 — 等上游修後可拔 workaround（追蹤 checkpoint 見 development-standards.md「Upstream Issue Tracking」段） |

降回 JVM buildpack 的條件見 (e)。

### (b) AOT processing 啟用機制

`processAot` task 由 `org.graalvm.buildtools.native` 0.11.5 plugin 觸發（見 Framework Dependency Table 上游）。完整 AOT pipeline 由 4 個元件協作 — 對應 Spring Boot 4 AOT processing 的已知限制各自做 workaround：

| 元件 | 路徑 | 作用 |
|------|------|------|
| `AotStubConfig` | `backend/src/main/java/.../shared/aot/AotStubConfig.java` | `@Profile("aot")` 提供 DataSource bean — 走 `System.getenv()` 直連 process env vars，繞過 Spring Boot 4 AOT 對 `@ConfigurationProperties` eager-bean binding 失效（`DataSourceProperties.determineDriverClassName()` 需 URL 不為空）；同時提供 `FlywayMigrationStrategy` bean，build time 檢查 `SPRING_DATASOURCE_URL` env var，無值則 skip migrate |
| `JdbcConfiguration.jdbcDialect()` | `backend/src/main/java/.../shared/persistence/JdbcConfiguration.java` | Override 預設 dialect 偵測（會跑 connection metadata query），改顯式回 `JdbcPostgresDialect.INSTANCE` — 我們 100% PostgreSQL，不需 auto-detect；同時解決 AOT 階段（無真實 DB 連線）這條 path 會炸 |
| `application-aot.yaml` | `backend/src/main/resources/application-aot.yaml` | Modulith autoconfig 排除（`ApplicationModulesEndpointConfiguration` / `ModuleObservabilityAutoConfiguration` / `SpringDataRestModuleObservabilityAutoConfiguration` — 觸發 ArchUnit `ClassFileImporter` 在 native image 階段 ClassNotFoundException，issue spring-modulith#735/#1556 未修）；`spring.cloud.gcp.secretmanager.enabled=false`（AOT 階段沒 creds 不該試呼 API；runtime 由 `application-gcp.yaml` 顯式 enable 蓋回）；OAuth2 client stub credentials（`OAuth2ClientProperties.validate()` 在 AOT binding 階段強制 client-id 非空，runtime env var 蓋回真值） |
| `ProcessAot` task config | `backend/build.gradle.kts` line 129–132 | `args("--spring.profiles.active=$profiles")` 預設 `aot,local`；換環境用 `-Pspring.profiles.active=aot,gcp,{lab\|prod}` 覆蓋。AOT 階段 active profile 會被 baked 進 `__ApplicationContextInitializer.addActiveProfile()`，runtime `SPRING_PROFILES_ACTIVE` 不能移除已 baked 的（per spring-boot#41562 / #48408） |

### (c) Reflection hint fast-fail 機制

```bash
./gradlew nativeCompile -PexactReachability=true
```

`backend/build.gradle.kts` line 200–212 的 `graalvmNative {}` block 在 `-PexactReachability=true` Gradle property 存在時加 `--exact-reachability-metadata=io.github.samzhu.skillshub` flag，限制 scope 只檢查專案 package（不 cover framework code 漏 hint）。

預設 reporting mode = `Throw` — 任何 `io.github.samzhu.skillshub.*` 內 missing reflection registration 在 build 階段直接 fail（不等到 Cloud Run runtime 才 throw `MissingReflectionRegistrationError`）。平常 `nativeCompile` 不開 flag 不影響；POC 與 deploy-day 開啟做 fast-fail safety net。

POC v4 (2026-05-09) 驗證 flag 生效 — BUILD SUCCESSFUL 等同確認 SkillshubProperties 11 個 nested record + 全 production package 反射 metadata 涵蓋完整。

### (d) 既知 blocker：cyclonedx-bom 3.2.4 vs nativeCompile

`org.cyclonedx.bom` 3.2.4 plugin 與 Gradle 9.4.1 + nativeCompile task graph 互相衝突（`processResources` ↔ `cyclonedxBom` 互依賴 race）：

```
V1（plugin 啟用）         : Cannot mutate the artifacts of configuration ':cyclonedxDirectBom' after the configuration was consumed as a variant
V2（excludeTask cyclonedxBom）: Querying the mapped value of task ':cyclonedxBom' property 'jsonOutput' before task ':cyclonedxBom' has completed is not supported
V3+（plugin 整個註解）     : ✅ 通過
```

**現況（2026-05-10）：** `build.gradle.kts` line 12 cyclonedx-bom plugin 註解狀態維持。`./gradlew cyclonedxBom` 跑不出 `backend/build/reports/bom.json`；無實際使用者受阻（`scripts/` / `cloudbuild.yaml` / `.github/` 全 grep 過，無任何 SBOM 上傳 / 安全掃描 pipeline 在讀此檔）。

**為何不修（S148f deferred）：**
- 上游 cyclonedx-gradle-plugin 最新版仍是 3.2.4（同我們版本；無 4.x release）— [GitHub issue #821](https://github.com/CycloneDX/cyclonedx-gradle-plugin/issues/821)（2026-04-06 開，狀態 open，無修復計畫）
- 換 SPDX 工具或寫 wrapper script 都會把 spec 從 XS 漲到 S，cost-benefit 不划算（沒 SBOM 消費者今天受阻）

**何時 reactivate**（任一觸發）：
1. 上游 cyclonedx 4.x 發布（issue #821 解）
2. 新 spec 要做 SBOM upload（Snyk / Dependency Track / etc）
3. 切 native production deploy（BP_NATIVE_IMAGE=true）— 那時得先解此衝突

追蹤：**S148f ⏸ deferred**（spec 仍在 backlog，等觸發條件之一）。

### (e) 降回 JVM buildpack 的條件（目前不適用）

Production 已在 native image。降回 JVM buildpack 只在以下情境會考慮：

1. GraalVM upstream regression 大量 reflection metadata 漏 — 短期 hotfix 路徑（CI 改 `BP_NATIVE_IMAGE=false` 或註解 `org.graalvm.buildtools.native` plugin），補完 metadata 後切回
2. native compile time（local ~3 分鐘 / CI 25–35 分鐘 cold cache）成為 dev velocity 瓶頸 — 評估 trade-off

`nativeTest` 進 nightly CI 仍未啟（ROI 待評估；目前 (c) build-time `-PexactReachability=true` fast-fail 涵蓋率夠 MVP 階段）。

### Reviewer 自檢

讀完本段應能回答：

- 「目前 production 跑 native 還是 JVM？」 → **GraalVM native image**（`org.graalvm.buildtools.native` plugin metadata 觸發 Paketo `paketo-buildpacks/native-image` buildpack 自動選擇）。
- 「誰的 reflection metadata 是手動加 vs auto-register？」 → 手動：`ScoreNativeConfig.java` 用 `@RegisterReflectionForBinding(JudgeResponse.class)`（S148 v4.25.0）。其餘走 Spring Boot 4 auto-registration + `org.graalvm.buildtools.native` plugin scan。
- 「想跑 native compile 怎麼跑？fast-fail 怎麼開？」 → `./gradlew nativeCompile`（一般），`-PexactReachability=true` 加上去開 fast-fail。
- 「native image 已知會踩什麼 GraalVM bug？我們怎麼處理？」 → BOOLEAN column 讀回 MethodHandle adaptation 把 Boolean corrupt 成 Integer（oracle/graal#5672）— S168 註冊 `IntegerToBooleanConverter` 全域攔截。新加 entity 的 primitive boolean field 自動受保護，但需在升 Spring Boot / GraalVM 版本時查上游 issue 狀態（development-standards.md「Upstream Issue Tracking」段）。

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
