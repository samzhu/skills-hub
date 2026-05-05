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
| **security** | **Event-driven service** | 無 | 訂閱 `SkillVersionPublishedEvent` 觸發風險評估，透過 `SkillVersion.attachRiskAssessment` 回寫 |
| **search** | **Read-side projection** | 無 | 消費 skill events 建構搜尋索引（keyword + semantic） |
| **analytics** | **Read-side projection** | 無 | 消費 download events 建構統計數據 |
| **audit** | **Cross-cutting listener** | 無 | 訂閱所有 9 個 Skill domain events 寫入 `domain_events` audit log（async + idempotent；S024 引入） |
| **score** | **Async LLM judge** | `SkillScore`（per-axis evaluation row） | S135a 引入；訂閱 `SkillVersionPublishedEvent` → 3-axis 品質評分（VALIDATION rule-based + IMPLEMENTATION/ACTIVATION Gemini 2.5 Flash LLM judge）→ 寫 `skill_scores` 表；獨立 `qualityExecutor` pool（corePool=1, queue=500）避免擠 `applicationTaskExecutor` |
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
    - AnalyticsProjection 訂閱 → download_events INSERT (idempotent via eventId)
    - AuditEventListener 訂閱 → domain_events INSERT (audit log；deterministic UUID + ON CONFLICT DO NOTHING)
    - ScanOrchestrator 訂閱（SkillVersionPublishedEvent only）→ multi-engine scan pipeline
    - 各 listener 完成 → event_publication.completion_date = now()
```

### Domain Events（skill domain；9 個）

| Event | Trigger | Payload |
|-------|---------|---------|
| `SkillCreatedEvent` | `Skill.create(cmd)` | name, description, author, category |
| `SkillVersionPublishedEvent` | `SkillVersion.publish(cmd)` | version, storagePath, fileSize, allowedTools, sourceEventId |
| `SkillVersionPublishedFromAggregate` | `Skill.recordVersionPublished(version)`（state-change marker） | version |
| `SkillSuspendedEvent` | `Skill.suspend(cmd)` | reason, suspendedBy |
| `SkillReactivatedEvent` | `Skill.reactivate(cmd)` | reason |
| `SkillAclGrantedEvent` | `Skill.grantAcl(cmd)` | type, principal, permission, grantedBy |
| `SkillAclRevokedEvent` | `Skill.revokeAcl(cmd)` | type, principal, permission, revokedBy |
| `SkillDownloadedEvent` | `Skill.recordDownload()` | version, eventId |
| `SkillRiskAssessedEvent` | `SkillVersion.attachRiskAssessment(map)` | skillId, version, level, findings |

### Code Pattern

```java
// Aggregate — Spring Data JDBC 充血聚合
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> implements Persistable<String> {
    @Id private String id;
    @Version @JsonIgnore private Long version;       // 樂觀鎖；不 expose API JSON
    private SkillStatus status;
    private List<String> aclEntries;                  // JSONB column

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
    // ... grantAcl / revokeAcl / recordDownload / etc — 同樣 mutate + register pattern
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
│   └── AuditEventListener.java  (@ApplicationModuleListener × 9 — 訂閱所有 Skill domain events
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
│   │   ├── SkillAclGrantedEvent / SkillAclRevokedEvent
│   │   ├── SkillDownloadedEvent / SkillRiskAssessedEvent
│   ├── command/                ← Command Side（3-line orchestration）
│   │   ├── CreateSkillCommand / PublishVersionCommand / SuspendCommand / etc
│   │   ├── SkillCommandService.java (load → mutate → save；無 eventStore.save 直接寫)
│   │   └── SkillCommandController.java (POST, PUT)
│   ├── query/                  ← Query Side（直打 aggregate repositories；S024 起無 read-model 中介）
│   │   ├── SkillQueryService.java (skillRepo.findById / search via NamedParameterJdbcTemplate + Skill.fromRow)
│   │   ├── SkillQueryController.java (GET — response type Skill / SkillVersion；@JsonIgnore version)
│   │   └── SkillAclQueryService.java (skillRepo.findById → ACL entry 拆解)
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

`vector_store` 由自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（Spring AI 2.0.0-M5 core artifact）控制；6 欄 atomic INSERT — `id` / `content` / `metadata` JSONB / `embedding` vector(768) / `owner` / `skill_id`；`owner` 為 S016 row-level ACL 鋪路；`ON CONFLICT (id) DO UPDATE` 冪等；HNSW 索引 + cosine distance（`embedding <=> query` operator）。

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
| `org.springframework.ai:spring-ai-pgvector-store` | 2.0.0-M5 BOM | `org.springframework.ai.vectorstore.pgvector.*`（core artifact；自寫 `SkillshubPgVectorStore` 子類）| yes (S014) |
| `org.springframework.boot:spring-boot-flyway` | BOM | — | yes (S014) |
| `org.flywaydb:flyway-core` | BOM-managed | `org.flywaydb.core.*` | yes (S014) |
| `org.flywaydb:flyway-database-postgresql` | runtime | — | yes (S014) |
| `org.postgresql:postgresql` | runtime | JDBC driver | yes (S014) |
| `com.google.cloud:spring-cloud-gcp-starter` | 8.0.2 BOM | `com.google.cloud.spring.*` | yes (template) |
| `com.google.cloud:spring-cloud-gcp-starter-storage` | 8.0.2 BOM | `com.google.cloud.storage.*` | yes (template) |
| `com.google.cloud:google-cloud-vertexai` | 1.24.0 | `com.google.cloud.vertexai.*` | yes (Maven Central) |
| `org.springframework.ai:spring-ai-*` | 2.0.0-M5 BOM | `org.springframework.ai.*` | yes (template) |
| `org.springframework.ai:spring-ai-google-genai` | 2.0.0-M5 BOM | `org.springframework.ai.google.genai.*` (Gemini Chat + Embedding via Google AI Studio direct API; **not** Vertex AI) | yes (S007 / S010 / S135a) |
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
