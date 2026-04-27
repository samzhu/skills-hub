# S014: PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清

> Spec: S014 | Size: L(20) | Status: ✅ Done — 待 QA subagent 重新驗證
> Date: 2026-04-27（**revised T2 ship 後 / T5+T7+T6 完成 15:44 / T8 design refinement 完成 16:30**）
> Depends: ADR-001（PostgreSQL 路線決策） — `docs/grimo/adr/ADR-001-postgresql-migration.md`
> Blocks: S016（Row-Level ACL 基礎建設）→ S017（ACL-Aware 搜尋）

> **本 spec 自包含**：所有載重設計決策的研究結論已內聯於 §2.4 / §2.5；不依賴 `docs/deepwiki/` 路徑留存（該目錄為規劃期臨時研究產物）。

> **修訂紀錄（2026-04-27, T2 mega ship 後）**：
> - **S015 併入 S014** — 原計畫 S015 接管 `PgVectorStore` 寫入；T2 ship 後決策一次拆 Firestore 死碼，避免 `SearchConfig` 雙條件分支與 `google-cloud-firestore` 持續耦合（ADR-001 §4.5）。Spec 規模從 M-L(15) 升至 **L(20)**。
> - **GCP 連線方式改 Cloud SQL Auth Proxy sidecar** — 取代原「Private IP + VPC Connector」。本機 Docker Compose `pgvector/pgvector:pg16` 與 GCP 用同條 JDBC URL `jdbc:postgresql://localhost:5432/<db>`（dev/prod parity；ADR-001 §4.4）。
> - 影響範圍：§1 Goal、§2.1 決策 #2 / #9 / #10、§2.4 #10、§3 AC-10 / AC-12（重寫）+ AC-13（新增）、§4.1 deps、§4.9 yaml、§4.11 SearchConfig（新增）、§5 file plan、§6 task plan（T7 新增）。已 ship 的 T1/T2 不受影響（PostgreSQL data layer 變動已完成）。

---

## 1. Goal

把 Skills Hub 的 **整套儲存層**（CRUD 讀模型 + Event Store + 向量搜尋）從 Firestore Enterprise（MongoDB driver + native SDK）一次遷到 PostgreSQL（Spring Data JDBC + 官方 `spring-ai-starter-vector-store-pgvector`），**業務行為等同 v1.0.0，零使用者可見變動**。同步建立含 `owner` 欄位的 **vector_store 表**（本 spec 直接啟用，由 PgVectorStore 接管寫入；S016 加 ACL 欄位、S017 用 ACL filter 查詢）。

**簡單講**：把 4 個 `@Document` read model + 1 個 event store + `FirestoreVectorStore` 一次換成 PostgreSQL `@Table` + Flyway V1 migration（同檔含 vector_store 表 + pgvector extension）+ Spring AI 官方 `PgVectorStore`。`FirestoreVectorStore.java` 與 `google-cloud-firestore` dep 一併移除（原 S015 scope 併入）。所有既有測試（37 個檔案 / 100+ 個方法）在 Testcontainers `pgvector/pgvector:pg16` 全綠 + 上傳後 vector_store 有資料就算成功。

> **為什麼 S015 併進來**：T2 mega ship 後，Mongo deps 已乾淨；若再分一輪 S015 處理 Firestore，`SearchConfig` 雙條件分支 + `google-cloud-firestore` 會持續存活整輪 spec。一次拆乾淨後 `SearchConfig` 只剩兩個 bean（pgvector + simple/local），dep tree 更窄。詳 ADR-001 §4.5。

```
┌── v1.0.0（current）──────────────────────────────────────────────┐
│   Spring Data MongoDB ──▶ Firestore Enterprise (MongoDB wire)    │
│   ├── domain_events / skills / skill_versions / flags            │
│   └── download_events                                            │
│   Firestore native SDK ──▶ Firestore (vector search)             │
│   └── skill_embeddings  — FirestoreVectorStore                   │
└──────────────────────────────────────────────────────────────────┘
                              ↓ S014（本 spec）
┌── 過渡狀態（S014 完成）──────────────────────────────────────────┐
│   Spring Data JDBC ──▶ PostgreSQL（pgvector image）              │
│   ├── domain_events / skills / skill_versions / flags            │
│   ├── download_events                                            │
│   └── vector_store  ← 表已建（含 owner 欄位）但 S014 期間未使用  │
│                                                                  │
│   Firestore native SDK ──▶ Firestore (vector search) ← 暫不動    │
│   └── FirestoreVectorStore  ← S015 才取代                         │
└──────────────────────────────────────────────────────────────────┘
                              ↓ S015 接續
┌── 終態（S015 完成）──────────────────────────────────────────────┐
│   Spring Data JDBC ──▶ PostgreSQL（單一儲存）                    │
│   ├── domain_events / skills / skill_versions / flags            │
│   ├── download_events                                            │
│   └── vector_store  ← Spring AI 官方 PgVectorStore + owner       │
└──────────────────────────────────────────────────────────────────┘
```

### 事件驅動架構（v1.0.0 既有，S014 維持不變）

S014 不改任何 event 流；確認既有 listener 結構：

```
SkillCommandService.uploadSkill()
   │
   ├─▶ Skill.create() → SkillCreatedEvent
   │      │ saveAndPublish (sequence=1)
   │      └─▶ ApplicationEventPublisher.publish
   │              ├─▶ SkillProjection.on(SkillCreatedEvent)        ← 寫 skills 表
   │              └─▶ SearchProjection.onSkillCreated(...)         ← 寫 vector store（embedding）
   │
   └─▶ SkillVersionPublishedEvent
          │ saveAndPublish (sequence=2)
          └─▶ ApplicationEventPublisher.publish
                  ├─▶ SkillProjection.on(SkillVersionPublishedEvent)   ← 寫 skill_versions 表
                  ├─▶ SearchProjection.onVersionPublished(...)         ← 重新 embed（frontmatter 可能更新）
                  └─▶ ScanOrchestrator(...)                            ← S010 多引擎安全掃描
                              │
                              └─▶ jdbc.update("UPDATE skill_versions SET risk_assessment = ?::jsonb WHERE ...")
```

**SearchProjection（向量寫入）+ ScanOrchestrator（安全掃描）+ SkillProjection（read model）= 三個獨立 listener 訂閱同一 SkillVersionPublished 事件**，透過 Spring `ApplicationEventPublisher` 解耦。S014 完整保留此模型。

> 注意：v1.0.0 沒有 `SkillDeletedEvent`。**Skill 刪除事件由 S016 引入**（搭配 `@PreAuthorize("hasPermission(... 'delete')")`）；S014 不在本 spec 範圍內新增刪除流程。

---

## 2. Approach

### 2.1 關鍵設計決策（共 12 項；2026-04-27 T2 ship 後修訂 #2 / #9 / #10，新增 #12；2026-04-27 T8 design refinement 再修訂 #2 / #12）

| # | 決策 | 選擇 | 理由 | 否決的替代 |
|---|------|------|------|-----------|
| 1 | 資料存取技術 | **Spring Data JDBC 為主、`NamedParameterJdbcTemplate` 為輔**（`spring-boot-starter-data-jdbc`） | Skill CRUD 全走 Spring Data JDBC：`@Table` record + `ListCrudRepository` 處理 90% 操作（findById / save / findAll / derived query / `@Modifying @Query` UPDATE）；**`NamedParameterJdbcTemplate` 僅用於唯一一處真正動態 SQL — `SkillQueryService.search(keyword, category, pageable)` 因有 optional filters + 動態排序，無法用固定 `@Query` 表達** | JPA/Hibernate（重）、純 raw `JdbcTemplate`（5 個 read model 各要寫 RowMapper + 自家 SQL，工程量倍增） |
| 2 ✱✱ | 向量儲存 — **本 spec 接管寫入**（再修訂 T8）| **`spring-ai-pgvector-store` 官方 core artifact**（非 auto-config starter）；自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`，在 `SearchConfig` Manual Configuration；`FirestoreVectorStore.java` 刪除 | 因 vector_store schema 含 owner / skill_id 自訂欄位（S016/S017 ACL 鋪路），官方 PgVectorStore 的 4-欄 INSERT 不夠用；自寫子類在 `doAdd` 中走 6-欄 INSERT 一次寫完（atomic、單 round-trip）。`AbstractObservationVectorStore` 父類提供 Micrometer observation 等同效果；維護成本與 starter 接近，但消除 add+UPDATE 兩步驟 workaround + `instanceof` guard。對齊 CLAUDE.md「Spring AI Manual Configuration」原則 — 與 S007 `GoogleGenAiTextEmbeddingModel` 同模式 | A. starter 自動 wiring + 兩步驟 add+UPDATE（**T7 ship 過此版**；中間視窗有 owner=NULL observable；兩個 round-trip）<br/>B. 自訂繼承 `PgVectorStore` 並 override `doAdd`（PgVectorStore 多數內部欄位 `private`，不易乾淨 override；不如直接繼承 `AbstractObservationVectorStore`）<br/>C. 把 owner/skill_id 塞 metadata JSON（無 GIN，S017 ACL 路徑不對齊；決策 #12 否決）|
| 3 | Vector store schema 控制 | **`spring.ai.vectorstore.pgvector.initialize-schema=false` + `schema-validation=false` + 自寫 V1 SQL** | 必須在 vector_store 加 `owner` + `skill_id` 欄位（row-level ACL 必要；S016 開始用）；starter auto-init 不支援自訂欄位；schema-validation 關閉避免「多欄位 fail starter」 | 讓 starter auto-init 後再 `ALTER TABLE ADD COLUMN owner`（多一次 migration；schema drift 風險） |
| 4 | Schema 遷移工具 | **Flyway**（`spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql`） | Spring Boot 4 把 Flyway auto-config 拆到獨立 `spring-boot-flyway` artifact，需顯式引入；S016/S017 加欄位 / index 用 V2/V3 增量；社群成熟 | Liquibase（YAML 學習曲線）、`schema.sql` built-in（無版本管理；不利後續增量） |
| 5 | JSONB Map 持久化 | **自訂 Jackson 雙向 Converter**（`Map<String, Object> ↔ JSONB`） | Spring Data JDBC `userConverters()` SPI 標準路徑；Jackson 3.x（`tools.jackson.*`）為 Spring Boot 4 primary ObjectMapper | 第三方庫（額外依賴）、序列化成 String 存 TEXT（失去 JSONB 查詢能力） |
| 6 | id 欄位型別 | **`VARCHAR(36)` 存 UUID 字串**（含連字號形式） | 既有 read model `String id` 維持不變；不需要任何 type converter；遷移風險最低；vector_store 仍用 `UUID` 型別（Spring AI starter 預設） | PostgreSQL 原生 `UUID` 型別於業務表（要加 UUID converter；無實質效益） |
| 7 | 連線池 | **HikariCP**（Spring Boot 4 預設）— GCP `maximum-pool-size: 3` / `minimum-idle: 1`；本機 dev 寬鬆設 10 / 2 | 0 設定；業界標準。**db-f1-micro `max_connections = 25` 約束**：[Cloud SQL flags 文件](https://docs.cloud.google.com/sql/docs/postgres/flags)列出 ~0.5 GB RAM 機型預設 max=25。預算公式：`25 - 5 reserved (Cloud SQL admin/monitoring) = 20 available ÷ Cloud Run max instances (假設 5) = 4/instance → 取 3 留 buffer`。Cloud Run 端理論上限 100 connections/DB（[Cloud SQL quotas](https://docs.cloud.google.com/sql/docs/quotas)）— 受 DB 端 25 主導 | 其他 pool（Spring Boot 4 已不預設） |
| 8 | 本機 Docker Compose image | **`pgvector/pgvector:pg16`** | 同時做 CRUD + vector store；pg16 是 pgvector 官方目前主推 LTS；GCP Cloud SQL 端啟用 `cloudsql.enable_pgvector=on` flag 即可同等支援 | `postgres:17-alpine`（無 vector extension，要再 `CREATE EXTENSION` 自管 build；S015 還要再換）、`pgvector/pgvector:pg17`（壓力測試尚不成熟） |
| 9 ✱ | GCP 部署連線（修訂） | **Cloud SQL Auth Proxy sidecar**（Cloud Run multi-container：main container + `gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest` sidecar；共享 localhost network；JDBC URL `jdbc:postgresql://localhost:5432/<db>`） | **dev/prod parity**：本機 Docker Compose `pgvector/pgvector:pg16` 暴露 localhost:5432，GCP sidecar 也 listen localhost:5432，**同一條 JDBC URL** 跨環境（只差 env var 帶不同 db name / user / password）；應用端**無 socket-factory dep**，不觸發 `spring-cloud-gcp-autoconfigure.CloudSqlEnvironmentPostProcessor` 啟動驗證；IAM 授權自動化（sidecar 用 Cloud Run service account 連 Cloud SQL）；Cloud Run 多容器已 GA（[Cloud Run sidecars docs](https://docs.cloud.google.com/run/docs/deploying#sidecars)）| Private IP + VPC Connector（JDBC URL 跟本機不同；dev/prod parity 弱）、Cloud SQL Java Connector + `socketFactory`（多 dep + post-processor 干擾）、Public IP（安全風險） |
| 10 ✱ | Mongo + Firestore 處置（修訂） | **本 spec 一次移除：`spring-boot-starter-data-mongodb` + `testcontainers-mongodb` + `google-cloud-firestore` + `FirestoreVectorStore.java` + `SearchConfig.firestoreVectorStore @Bean`** | T1/T2 已移 Mongo；T7（新增）順勢移 Firestore — 保留會在 `SearchConfig` 雙條件分支、`google-cloud-firestore` dep tree、`application-local.yaml` / `application-gcp.yaml` 的 firestore.enabled 設定上產生持續耦合（ADR-001 §4.5） | 留 Firestore 至 S015（雙 bean / 多輪 PR review） |
| 11 | Aggregation pipeline 遷移 | **MongoDB `Aggregation.group()` → 純 SQL `GROUP BY`** | 既有唯一案例 `getCategoryCounts()` 是 simple `SELECT category, COUNT(*) GROUP BY category ORDER BY count DESC` | Spring Data JDBC `@Query` annotation（其實就是 native SQL，沒省什麼） |
| 12 ★★ | owner / skill_id 寫入策略（再修訂 T8）| **單次 6-欄 INSERT**：`SkillshubPgVectorStore.doAdd` 直接走 `INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id) VALUES (?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET ...`；owner / skill_id 由 builder 注入（`SkillshubPgVectorStore.builder(jdbc, em).owner(...).skillId(...).build()`）；`SearchProjection` per-request 建構 instance、操作完 GC | 自寫子類後可控制 INSERT SQL，不再受官方 4-欄限制；單次 round-trip + atomic（無 add→UPDATE 中間視窗 owner=NULL observable）；observation tracing 由 `AbstractObservationVectorStore.add(...)` 統一 wrap → 等同官方體驗；冪等由 `ON CONFLICT DO UPDATE` 保證 | A. **兩步驟 add+UPDATE**（**T7 ship 過此版**；workaround 味重；保留 starter 但維護兩處邏輯）<br/>B. 把 owner 塞 metadata JSON（同決策 #2 否決理由）<br/>C. trigger-based 寫入（DB 端 trigger 從 metadata 抽取，無 Java 介入）— PostgreSQL trigger 維護成本 + 跨 environment 一致性問題 |

> ✱ = 修訂（2026-04-27 T2 ship 後）；✱✱ = 二次修訂（2026-04-27 T8 design refinement）；★ = 新增；★★ = 新增後再修訂

### 2.2 與既有架構的契合

| 維度 | 現況（v1.0.0） | S014 變動 |
|------|---------------|-----------|
| **儲存層** | Firestore Enterprise + MongoDB driver | PostgreSQL + Spring Data JDBC + Spring AI pgvector starter |
| **Event Store** | Mongo `domain_events` collection；`payload: Map`、`metadata: Map` | PostgreSQL `domain_events` 表；`payload jsonb`、`metadata jsonb` |
| **Read Models** | 4 個 `@Document` records | 4 個 `@Table` records（同名稱、同欄位、同 access pattern） |
| **Repository** | 4 個 `MongoRepository` 介面 + `MongoTemplate` 動態查詢 | 4 個 `ListCrudRepository` 介面 + `NamedParameterJdbcTemplate` 動態查詢 |
| **Domain Events** | `SkillCreated` / `SkillVersionPublished` / `SkillDownloaded` / `SkillFlagged` | **不變**（沒有新事件；`SkillDeleted` 在 S016 才新增） |
| **Event listeners 解耦** | `SkillProjection` + `SearchProjection` + `ScanOrchestrator` 三個獨立 listener，皆訂閱 `SkillVersionPublished` | **不變** — `@EventListener` 同步 publish 模式保留；只有 `ScanOrchestrator` 內部 `MongoTemplate.updateFirst` 改為 `JdbcTemplate.update` |
| **Spring Modulith 邊界** | shared / skill / security / search / analytics / storage 模組邊界 | **不變** — `ApplicationModules.verify()` 仍綠 |
| **CQRS + ES 模式** | Aggregate Root → events → projection listener 更新 read model | **不變** — 只換儲存後端，事件流順序、`@Order` 不動 |
| **API 行為** | 所有 REST endpoint 行為與 response shape | **完全不變** — 既有 controller 測試零修改 |
| **Frontend** | React 19 SPA | **完全不動** |
| **JWT / OAuth (S011/S012)** | OAuth2 RS + LAB 模式 | **不變** |
| **GCS 儲存** | Spring Cloud GCP Storage | **不變** |
| **VectorStore SPI** | `SearchConfig` 用 `@ConditionalOnProperty` 切換 `simple` / `firestore` | **S014 接管**：移除 `firestoreVectorStore @Bean` + `FirestoreVectorStore.java`；新增 `pgvectorVectorStore @Bean` 透過 `spring-ai-starter-vector-store-pgvector` auto-config 取得 `PgVectorStore` instance + 補 owner/skill_id 寫入；預設 `vector-store=pgvector`（既有 `simple` 條件保留為本機無 DB 時的 fallback）|
| **Observability** | Brave tracing（`micrometer-tracing-bridge-brave`） | **本 spec 不改** — Brave 與 OpenTelemetry 切換是另一獨立議題（user 提供的 build.gradle template 含 `spring-boot-starter-opentelemetry`，但屬不同範疇）；本 spec 暫保留 Brave，OpenTelemetry 切換建議獨立 spec 處理 |

### 2.3 Schema 設計（V1 migration）

完整 V1 schema 涵蓋 6 張表 + pgvector extension。所有業務表用 `VARCHAR(36)` UUID 字串、JSONB 欄位用 `Map<String, Object>` Converter；vector_store 仍循 Spring AI 官方型別（`UUID` PK + `vector(768)`），但**加 `owner VARCHAR(255)` 欄位**為 S016/S017 鋪路。

#### 0. PostgreSQL extensions

```sql
CREATE EXTENSION IF NOT EXISTS "vector";          -- pgvector
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";       -- vector_store id 預設
```

#### 1. `domain_events`（Event Store）

```sql
CREATE TABLE domain_events (
    id              VARCHAR(36)  PRIMARY KEY,
    aggregate_id    VARCHAR(36)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sequence        BIGINT       NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX idx_domain_events_aggregate_seq
    ON domain_events (aggregate_id, sequence);
```

範例 row（`SkillCreated` 事件）：

```json
{
  "id": "8f3a-...",
  "aggregate_id": "abc-123",
  "aggregate_type": "Skill",
  "event_type": "SkillCreated",
  "payload": { "name": "docker-compose-helper", "description": "...", "author": "sam", "category": "DevOps" },
  "sequence": 1,
  "occurred_at": "2026-04-27T10:23:45Z",
  "metadata": {}
}
```

#### 2. `skills`（SkillReadModel）

```sql
CREATE TABLE skills (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL UNIQUE,
    description     TEXT,
    author          VARCHAR(255) NOT NULL,
    category        VARCHAR(50),
    latest_version  VARCHAR(20),
    risk_level      VARCHAR(10),
    status          VARCHAR(20)  NOT NULL,
    download_count  BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_skills_category ON skills (category);
CREATE INDEX idx_skills_status   ON skills (status);
```

#### 3. `skill_versions`（SkillVersionReadModel）

```sql
CREATE TABLE skill_versions (
    id                VARCHAR(36)  PRIMARY KEY,
    skill_id          VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version           VARCHAR(20)  NOT NULL,
    storage_path      VARCHAR(500) NOT NULL,
    file_size         BIGINT       NOT NULL,
    frontmatter       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    risk_assessment   JSONB,
    published_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (skill_id, version)
);

CREATE INDEX idx_skill_versions_skill_published
    ON skill_versions (skill_id, published_at DESC);
```

#### 4. `flags`（FlagReadModel）

```sql
CREATE TABLE flags (
    id           VARCHAR(36)  PRIMARY KEY,
    skill_id     VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    type         VARCHAR(20)  NOT NULL,
    description  TEXT,
    reported_by  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
);

CREATE INDEX idx_flags_skill ON flags (skill_id);
```

#### 5. `download_events`（DownloadEventReadModel）

```sql
CREATE TABLE download_events (
    id             VARCHAR(36)  PRIMARY KEY,
    skill_id       VARCHAR(36)  NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version        VARCHAR(20)  NOT NULL,
    downloaded_at  TIMESTAMPTZ  NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_download_events_skill_time
    ON download_events (skill_id, downloaded_at DESC);
```

#### 6. `vector_store`（Spring AI PgVectorStore，加 owner 欄位）

> S014 建立此表但**不啟用**（FirestoreVectorStore 仍在用）；S015 翻 `vector-store=pgvector` 開關後接管寫入；S016 加 `acl_entries` JSONB 欄位 + GIN index；S017 用 ACL filter 查詢。

```sql
CREATE TABLE vector_store (
    id          UUID         DEFAULT uuid_generate_v4() PRIMARY KEY,
    content     TEXT,
    metadata    JSON,                                    -- Spring AI 預設用 json（不是 jsonb）
    embedding   VECTOR(768),                             -- Gemini text-embedding-2 = 768 dims
    owner       VARCHAR(255),                            -- ★ 文件擁有者（S015 寫入；S016 用於授權）
    skill_id    VARCHAR(36)  REFERENCES skills(id) ON DELETE CASCADE  -- ★ 反向關聯
);

CREATE INDEX vs_emb_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);

CREATE INDEX idx_vector_store_owner
    ON vector_store (owner);                             -- S016 ACL 階段才會用上
```

> **為什麼 S014 就建 owner 欄位？** 用戶（你）的明確指示：「轉向量資料庫應該包含紀錄 owner 是誰，他就可以新增修改刪除，所以 schema 必須自己實作」。把 owner 欄位放在 V1 而非 S015 加入，省一次 ALTER TABLE migration、避免 v1.0.0 → S015 → S016 三輪 schema drift。

> **為什麼 metadata 用 JSON 不是 JSONB？** Spring AI 官方 `PgVectorStore` 建表預設用 `json` 型別，但 INSERT/WHERE 時 cast 成 `::jsonb`（驗證：[官方原始碼 PgVectorStore.java](https://github.com/spring-projects/spring-ai/blob/main/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java)）。我們不偏離官方型別，因 S017 用 `acl_entries` 而非 `metadata` 做 ACL filter（acl_entries 會在 S016 加為獨立 JSONB 欄位 + GIN index）。

> **加 owner / skill_id 欄位對官方 PgVectorStore 是否安全？** **是**，已從原始碼驗證：
>
> 1. **`schema_validation` 預設 `false`**（`PgVectorStore.DEFAULT_SCHEMA_VALIDATION = false`）— starter `afterPropertiesSet()` 不會檢查 vector_store 表的欄位是否完全等同 `(id, content, metadata, embedding)`，多餘欄位不會 fail
> 2. **INSERT SQL 只寫 4 個欄位**：`INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (id) DO UPDATE SET content=?, metadata=?::jsonb, embedding=?` — 多餘欄位（owner / skill_id）的初次 INSERT 值為 NULL，UPDATE 不動它們
> 3. **doSimilaritySearch SELECT \*** — 多欄位會被 SELECT 出來，但 PgVectorStore 內部只用到 4 欄；多餘欄位不影響 search
>
> **影響 S015 設計**：因為官方 `vectorStore.add(Document)` 不會寫 owner 欄位，S015 必須在 `SearchProjection` 寫入時：要嘛兩步驟（`add()` 後接 `getNativeClient().update("UPDATE vector_store SET owner=?, skill_id=? WHERE id=?")`），要嘛完全繞過 `add()` 自寫 INSERT SQL（透過 `getNativeClient()`）。本 spec 不決定此細節 — 由 S015 spec 設計階段選定；S014 只負責讓 schema 能容納兩種策略。

### 2.4 Challenges Considered

> **本節內聯所有對載重決策有貢獻的研究結論**，不依賴外部目錄留存。

1. **Spring Boot 4.0.6 + Spring Data JDBC 4.x + Spring AI 2.0.0-M4 三方相容性？**
   - Spring Boot 4 BOM 綁 Spring Data JDBC 4.x；無 starter 衝突
   - `spring-ai-starter-vector-store-pgvector` 在 Spring AI 2.0.0-M4 BOM 已對齊 Spring Boot 4
   - `@Table` / `@Id` / `@Column` 是 `org.springframework.data.annotation` + `org.springframework.data.relational.core.mapping`
   - **公開驗證**：[samzhu/spring-acl-jsonb](https://github.com/samzhu/spring-acl-jsonb) 已實證 Spring Boot 4.0.0-M3 + Java 25 + Spring Data JDBC + JSONB 全套可用；本專案 4.0.6 是 minor +0.0.6
   - Confidence：**Validated**

2. **`Map<String, Object>` 對 JSONB 雙向 Converter 的型別保留？**
   - Spring Data JDBC `AbstractJdbcConfiguration.userConverters()` SPI 接受任意 `@WritingConverter` / `@ReadingConverter` bean
   - Jackson `ObjectMapper.writeValueAsString(Map)` 為標準序列化
   - 反序列化用 `objectMapper.readValue(jsonString, new TypeReference<Map<String, Object>>(){})`
   - **疑點**：nested `List<String>` / `Map<String, Object>` 反序列化後型別保留正確？Jackson 預設行為會把 nested object 還原成 `LinkedHashMap`、nested array 還原成 `ArrayList<Object>`；若呼叫端用 `instanceof List<?>` 仍可正常工作，但 erased type
   - 對 Skills Hub：`DomainEvent.payload` / `metadata`、`SkillVersionReadModel.frontmatter` / `riskAssessment`、`DownloadEventReadModel.metadata` 全是 `Map<String, Object>` 結構，呼叫端只用 `getString(key)` / 強轉 `(Long)`，erased type 不影響
   - Confidence：**Hypothesis（小型）** — POC 由 T1 第一個 task 的 unit test 級別覆蓋

3. **vector_store 表已建立但未啟用，會與 Spring AI auto-config 衝突嗎？**
   - 加上 `spring-ai-starter-vector-store-pgvector` 後，Spring AI auto-config 會嘗試建立 `VectorStore` bean（具體 class：`org.springframework.ai.vectorstore.pgvector.PgVectorStore`）
   - 但 auto-config 上有 `@ConditionalOnMissingBean(VectorStore.class)` — Skills Hub `SearchConfig` 已顯式 `@Bean VectorStore simpleVectorStore` / `firestoreVectorStore`（透過 `@ConditionalOnProperty` 二選一）— 故 auto-config bean **不會被建立**，無 bean 衝突
   - `spring.ai.vectorstore.pgvector.initialize-schema=false` 確保 starter 不嘗試 auto-create vector_store 表（因為我們的 V1 migration 已建）
   - Confidence：**Validated** — Spring AI 文件明文 `initialize-schema` 預設 false（4.x BOM 起）

4. **MongoTemplate 動態查詢的改寫策略？**
   - 既有唯一複雜案例：`SkillQueryService.search(keyword, category, pageable)` — 用 `Criteria.orOperator + .regex` 做 keyword 模糊比對
   - 改成 `NamedParameterJdbcTemplate` + `MapSqlParameterSource` 動態組 SQL：`WHERE (LOWER(name) LIKE LOWER(:kw) OR LOWER(description) LIKE LOWER(:kw)) AND (:cat IS NULL OR category = :cat)`
   - LIKE wildcard escape：把使用者輸入的 `%` `_` `\` 字元 escape 成 `\%` `\_` `\\`（防 SQL 通配符注入）
   - 排序欄位用白名單比對（防 SQL 注入；`buildOrderByClause(Sort)` helper）
   - **公開範例**：[samzhu/spring-acl-jsonb `ProjectRepositoryCustomImpl`](https://github.com/samzhu/spring-acl-jsonb) 同手法已開源
   - Confidence：**Validated**

5. **Aggregation pipeline 對應 SQL？**
   - 唯一案例：`SkillQueryService.getCategoryCounts()` 用 `Aggregation.group("category").count().as("count")` + sort
   - 對應純 SQL：`SELECT category AS name, COUNT(*) AS count FROM skills WHERE category IS NOT NULL GROUP BY category ORDER BY count DESC`
   - Java 端用 `RowMapper<CategoryCount>` 即可
   - 其他全部單表查詢，無複雜 aggregation
   - Confidence：**Validated**

6. **既有 `MongoTemplate.updateFirst` 改寫？**
   - 唯一案例：`ScanOrchestrator` 在 S010 多引擎掃描完成後寫 `risk_assessment` JSON 到 `skill_versions`
   - 對應：`jdbc.update("UPDATE skill_versions SET risk_assessment = :ra::jsonb WHERE skill_id = :sid AND version = :ver", params)`
   - 其中 `:ra` 為 `objectMapper.writeValueAsString(riskMap)` 後的字串；`::jsonb` cast 由 PostgreSQL 完成
   - Confidence：**Validated**

7. **download_count 並發更新（順手修 race condition）？**
   - 既有 `SkillProjection.on(SkillDownloadedEvent)` 用 read-modify-write 三步驟；MongoDB 上 race condition 已存在（兩個並發 download 可能丟一次計數）
   - SQL 原生 atomic：`UPDATE skills SET download_count = download_count + 1, updated_at = :ts WHERE id = :id`
   - 用 `@Modifying @Query` annotation 加在 `SkillReadModelRepository`
   - Confidence：**Validated**（Spring Data JDBC `@Modifying @Query` 標準用法）

8. **Testcontainers MongoDB → pgvector 遷移細節？**
   - 既有 `TestcontainersConfiguration.java` 用 `org.testcontainers.containers.MongoDBContainer`
   - 改成 `PostgreSQLContainer<>("pgvector/pgvector:pg16")` — 使用 pgvector 鏡像而非純 postgres，與本機 Docker Compose 對齊；S015/S016/S017 不需再改
   - 連線資訊用 `@ServiceConnection` annotation 自動注入到 `spring.datasource.url`/`username`/`password`，無需 `@DynamicPropertySource`
   - dependency：`testcontainers-mongodb` → `testcontainers-postgresql`
   - Confidence：**Validated**（Spring Boot 4 `@ServiceConnection` 是標準支援；`PostgreSQLContainer` 接受任意 postgres 鏡像名）

9. **既有 37 個測試檔的影響範圍？**
   - 純 SecurityContext 測試（`MeControllerTest` / `AdminControllerTest` / `LabModeMeControllerTest` / `LabModeAdminControllerTest` / `CurrentUserProviderTest` / `SkillshubSecurityPropertiesTest` / `JwtDecoderConditionalTest` / `OAuthMockE2ETest` / `SkillsApiAnonymousTest`）= 9 個檔不需改
   - 純掃描引擎 unit test（`PatternScannerTest` / `SecretScannerTest` / `MetadataValidatorTest` / `MetaAnalyzerTest` / `LlmJudgeTest` / `SarifReporterTest` / `ScannerAiConfigTest`）= 7 個檔不需改
   - `SkillValidatorTest` / `SearchProjectionTest`（用 mock VectorStore）= 2 個檔不需改
   - 整合測試（會讀 / 寫 read model）= **約 19 個檔需小修**：把 `@DataMongoTest` → `@DataJdbcTest`；測試碼業務邏輯不變（因 `findById/save/findAll` API 一致）
   - 最高風險點：`RiskAssessmentIntegrationTest`（直接驗 `MongoTemplate.updateFirst` 寫入 risk_assessment）— 改驗 JDBC 寫入結果

10. **GCP Cloud SQL 部署規格 + 連線方式 + Secrets？（修訂 2026-04-27）**
    - **規格**：Cloud SQL **Enterprise edition** + **PostgreSQL 18** + **db-f1-micro**（shared core / 1 vCPU / 0.614 GB RAM）— GCP 最小規格、dev/staging 夠用；上 production 需評估升級
    - **連線**：**Cloud SQL Auth Proxy sidecar**（修訂；取代原 Private IP + VPC Connector）— Cloud Run 部署 multi-container：
        - **Main container**：Skills Hub Spring Boot app；JDBC URL `jdbc:postgresql://localhost:5432/${SKILLSHUB_DB_NAME}`
        - **Sidecar container**：`gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest`；參數 `--port=5432 --auto-iam-authn ${INSTANCE_CONNECTION_NAME}`；listen on localhost:5432
        - 兩 container 共享 Cloud Run instance 的 localhost network namespace
    - JDBC URL 格式：`jdbc:postgresql://localhost:5432/${SKILLSHUB_DB_NAME:skillshub}` — 與本機 Docker Compose `pgvector/pgvector:pg16` **同一條 URL**；**不需要** `postgres-socket-factory` 依賴
    - 部署 CLI 範例（含 sidecar 配置）：
      ```bash
      gcloud run services replace service.yaml  # 用 YAML manifest 因 sidecar 配置 CLI flag 受限
      # service.yaml 範本：
      # spec.template.spec.containers:
      #   - name: app  # main
      #     image: gcr.io/$PROJECT/skillshub:latest
      #     ports: [containerPort: 8080]
      #     env: [SKILLSHUB_DB_NAME, SKILLSHUB_DB_USER, SKILLSHUB_DB_PASSWORD]
      #   - name: cloud-sql-proxy  # sidecar
      #     image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest
      #     args: ["--port=5432", "--auto-iam-authn", "$PROJECT:$REGION:skillshub-db"]
      ```
    - **Secrets**：延續 S013 Secret Manager 模式 — GCP profile 從 env var 讀 `SKILLSHUB_DB_USER` / `SKILLSHUB_DB_PASSWORD` / `SKILLSHUB_DB_NAME`（無 `SKILLSHUB_DB_HOST`，因為固定為 localhost）
    - **本機**：固定 `myuser:secret`（pgvector container 啟動時 `POSTGRES_USER` / `POSTGRES_PASSWORD` 環境變數設定）；JDBC URL 由 Spring Boot Docker Compose 整合自動注入（service-connection label）或寫死 localhost
    - 安全性：sidecar 用 Cloud Run service account 連 Cloud SQL（IAM auth）、流量限制在 Cloud Run instance 內、**完全無公網暴露**；對應 [Cloud Run sidecars docs](https://docs.cloud.google.com/run/docs/deploying#sidecars) + [Cloud SQL Auth Proxy docs](https://docs.cloud.google.com/sql/docs/postgres/sql-proxy)

15. **PgVectorStore owner / skill_id 寫入策略（新增 — S015 absorbed）**
    - 官方 `PgVectorStore.doAdd()` SQL：`INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (id) DO UPDATE SET content=?, metadata=?::jsonb, embedding=?` — **只動 4 欄**
    - 多餘的 `owner` / `skill_id` 欄位 INSERT 時為 NULL；`ON CONFLICT DO UPDATE` 不會清除（驗證自原始碼 §2.4 #13）
    - 寫入策略（決策 #12）：**兩步驟 add + UPDATE**
      ```java
      // SearchProjection.onSkillCreated / onVersionPublished
      var docs = List.of(new Document(skillId.toString(), content, metadata));
      vectorStore.add(docs);  // 官方路徑（含 observation tracing）
      jdbcTemplate.update(
          "UPDATE vector_store SET owner = ?, skill_id = ? WHERE id = ?",
          ownerValue, skillId, vectorDocId
      );
      ```
    - 取得 JdbcTemplate：透過 `((PgVectorStore) vectorStore).getNativeClient().orElseThrow()` （官方暴露 API；§2.4 #13 已驗證）
    - **冪等性**：同一 skill 多次 ingest（version publish）— 第一步 `add` 是 upsert（同 id DO UPDATE 4 欄）、第二步 UPDATE 也是 idempotent（同 owner/skill_id 重寫同值）
    - **trade-off**：兩步驟意味兩次 DB round-trip；對 skill 上傳不是熱路徑（每秒 < 1 次），可接受。S017 ACL 上線後若觀察到 latency 問題，可改 `getNativeClient()` 自寫 INSERT（決策 #12 已備案）
    - Confidence: **Validated**（官方原始碼）

16. **vector_store 文件 id 與 skill 的對應關係（新增）**
    - 官方 `Document.id` 預設由 `IdGenerator` 生成（UUID）；S014 的 `SearchProjection` 既有用 `skillId.toString()` 當 `Document.id`，本 spec 維持
    - 因此 `vector_store.id` 與 `skills.id` 字串值相同（雖然型別分別是 `UUID` 與 `VARCHAR(36)`），**便於對映**
    - skill 刪除時（將來 S016 引入 `SkillDeletedEvent`）：`vectorStore.delete(skillId.toString())` 或 ON DELETE CASCADE 由 `vector_store.skill_id REFERENCES skills(id) ON DELETE CASCADE` 自動處理 — 兩條路都可用，本 spec 不選定（無刪除流程）

14. **db-f1-micro 連線池預算 + pgvector extension on Cloud SQL（新發現）**
    - **`max_connections` 預設值**：依 [Cloud SQL flags](https://docs.cloud.google.com/sql/docs/postgres/flags) 文件，~0.5 GB RAM 機型預設 `max_connections = 25`；db-f1-micro（0.614 GB）落在此級別
    - **連線預算公式**：
      ```
      25 (max_connections)
        - 5 (Cloud SQL admin / monitoring / replication 保留)
        = 20 (app available)
        ÷ 5 (Cloud Run max instances 假設)
        = 4 (per instance)
        → maximum-pool-size: 3（取 3 留 buffer，避免 burst scale 時連線爭奪）
      ```
    - **Cloud Run 端限制**：[Cloud SQL quotas](https://docs.cloud.google.com/sql/docs/quotas) 提到 Cloud Run container instances 限 100 connections per Cloud SQL database — 但這是 Cloud Run 端硬限制，**db-f1-micro 25 是先卡的瓶頸**
    - **本機 dev 不受限**：本機 pgvector container 預設 `max_connections = 100`（PostgreSQL 標準預設），HikariCP 設 10 / 2 即可
    - **pgvector extension 啟用**：Cloud SQL 須在 instance 建立時啟用 `cloudsql.enable_pgvector` flag（部署腳本範圍；S014 V1 migration 含 `CREATE EXTENSION IF NOT EXISTS vector` — instance flag 沒啟用時 migration 會 fail）
    - **production 升級路徑**：若使用者規模成長，升 db-custom-2-7680（dedicated, 2 vCPU / 7.5 GB → max=200）或 Enterprise Plus C4A 機型（PG 13+ 支援、性能更好）；HikariCP `maximum-pool-size` 隨之調高（GCP profile env var override，無需改 yaml）

11. **既有 v1.0.0 production data 怎麼搬？**
    - **不搬** — Skills Hub 還沒上正式生產（CLAUDE.md 明文「Feature First, Security Later」），無使用者資料風險
    - 本 spec 不含 data migration 腳本；DEV / STAGING 採取「乾淨啟動」（drop database → V1 Flyway migration），各自重新上傳 skill 即可

12. **`spring-ai-advisors-vector-store` 是否需要？**
    - 用於 Spring AI ChatClient 的 RAG advisor（retrieval-augmented generation）
    - Skills Hub 目前 semantic search 不走 ChatClient（只是純 embedding 比對），不需要 RAG advisor
    - 將來若 LLM judge / RAG 搜尋擴展再加（不影響本 spec scope）

13. **官方 `PgVectorStore` 原始碼驗證（從 [spring-ai/PgVectorStore.java](https://github.com/spring-projects/spring-ai/blob/main/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java) 讀取）**
    - **預設常量**：`DEFAULT_TABLE_NAME = "vector_store"`、`DEFAULT_SCHEMA_NAME = "public"`、`DEFAULT_ID_TYPE = PgIdType.UUID`、`DEFAULT_SCHEMA_VALIDATION = false`
    - **Auto-create schema 模板**：`CREATE TABLE IF NOT EXISTS %s (id %s PRIMARY KEY, content text, metadata json, embedding vector(%d))` — 印證我們 §2.3 vector_store schema 的 4 個基底欄位型別正確
    - **doAdd 用 upsert（`ON CONFLICT (id) DO UPDATE`）** — 同一 skill_id 多次 ingest 不會破壞 owner / skill_id 欄位
    - **doSimilaritySearch SQL 模板**：
      ```
      SELECT *, embedding <=> ? AS distance FROM %s
       WHERE embedding <=> ? < ? %s ORDER BY distance LIMIT ?
      ```
      `%s` 位置注入內建 metadata jsonpath filter（`AND metadata::jsonb @@ '...'::jsonpath`）；S017 ACL filter 不走此路徑（因 ACL 不在 metadata 內），會用 `getNativeClient()` 完全自寫 SQL（不影響 S014）
    - **三種距離運算子**：COSINE_DISTANCE (`<=>`)、EUCLIDEAN_DISTANCE (`<->`)、NEGATIVE_INNER_PRODUCT (`<#>`)；S014 設 COSINE
    - **Builder 完整選項**：`schemaName / vectorTableName / idType / vectorTableValidationsEnabled / dimensions / distanceType / removeExistingVectorStoreTable / indexType / initializeSchema / maxDocumentBatchSize`
    - **`getNativeClient()` 暴露 `JdbcTemplate`**：`return Optional.of((T) this.jdbcTemplate)` — S017 ACL filter 與 S015 owner 欄位寫入都靠這個介面
    - **dimensions 預設 -1**（從 EmbeddingModel 拿；若拿不到則 1536）— 我們明設 768 對齊 Gemini text-embedding-2
    - Confidence: **Validated**（讀過原始碼）

### 2.5 Research Citations

> **本 spec 自包含**：所有對載重決策有貢獻的事實已內聯於 §2.4。下列為驗證來源 URL，未來可重複驗證；無一條依賴 `docs/deepwiki/` 路徑留存。

| 來源 | 對本 spec 的支撐點 |
|------|-------------------|
| [Spring AI PgVector reference (2.0)](https://docs.spring.io/spring-ai/reference/2.0/api/vectordbs/pgvector.html) | `spring-ai-starter-vector-store-pgvector` 用法（決策 #2）；`initialize-schema=false` 可控（決策 #3）；HNSW + cosine 預設（決策 #2）；vector_store 預設 schema（§2.3 #6） |
| [Spring AI 官方 `PgVectorStore.java` 原始碼](https://github.com/spring-projects/spring-ai/blob/main/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java) | **載重決策驗證來源**：`schema_validation` 預設 false（§2.4 #13）；INSERT SQL 只動 4 欄（§2.4 #13）；upsert 不破壞多餘欄位（§2.3 #6）；`getNativeClient()` 暴露 JdbcTemplate（§2.3 #6 + S015/S017 設計基礎）；distance 運算子三種；Builder 完整選項列表 |
| [Spring Data JDBC reference 4.0 § Mapping](https://docs.spring.io/spring-data/relational/reference/4.0/jdbc/mapping.html) | `@Table` / `@Id` / `@Column` annotation；`AbstractJdbcConfiguration.userConverters()` SPI（決策 #5） |
| [Spring Boot 4 Flyway auto-config](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.migration.flyway) | 預設掃 `db/migration/V*.sql`；`spring.flyway.enabled` toggle（決策 #4） |
| [HikariCP defaults in Spring Boot 4](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.connection-pool) | 預設池大小 = `cpu * 2`；Cloud Run 1 vCPU 配置匹配（決策 #7） |
| [Connect Cloud Run to Cloud SQL (PostgreSQL)](https://docs.cloud.google.com/sql/docs/postgres/connect-run) | 官方推薦：Private IP + Direct VPC egress / Serverless VPC Access connector；標準 JDBC URL `jdbc:postgresql://PRIVATE_IP:5432/db`（決策 #9） |
| [Configure Cloud SQL Private IP](https://docs.cloud.google.com/sql/docs/postgres/configure-private-ip) | Private IP 啟用步驟（VPC、Service Networking、IP range）；流量限制 VPC 內、無公網暴露（決策 #9） |
| [Cloud SQL Machine Series Overview](https://docs.cloud.google.com/sql/docs/postgres/machine-series-overview) | db-f1-micro 規格（shared core、0.614 GB RAM）；Enterprise edition 支援 shared/dedicated core（§2.4 #10/#14） |
| [Cloud SQL Quotas](https://docs.cloud.google.com/sql/docs/quotas) | Cloud Run instances 限 100 connections per Cloud SQL database；max_connections 由實例記憶體決定（§2.4 #14） |
| [Cloud SQL PostgreSQL Flags](https://docs.cloud.google.com/sql/docs/postgres/flags) | max_connections 預設值表（~0.5 GB RAM → 25）；pgvector extension 須 `CREATE EXTENSION` + instance flag（§2.4 #14） |
| [PostgreSQL JSONB Indexing](https://www.postgresql.org/docs/current/datatype-json.html#JSON-INDEXING) | `jsonb_path_ops` GIN — S016 才用；S014 先建純 JSONB 欄位無 GIN |
| [Testcontainers PostgreSQLContainer + Spring Boot @ServiceConnection](https://java.testcontainers.org/modules/databases/postgres/) | `@ServiceConnection` 自動注入連線；接受任意 postgres-compatible image（pgvector/pgvector:pg16） |
| [pgvector/pgvector Docker Hub](https://hub.docker.com/r/pgvector/pgvector) | `pg16` tag 是當前主推版本（決策 #8） |
| [samzhu/spring-acl-jsonb](https://github.com/samzhu/spring-acl-jsonb) | Spring Boot 4.0.0-M3 + Java 25 + Spring Data JDBC + JSONB 公開實證（§2.4 #1）；JSONB Map Converter pattern（決策 #5）；動態 SQL + LIKE escape pattern（§2.4 #4） |

**既有 codebase 錨點**（git 永久留存）：
- `backend/src/main/java/.../search/FirestoreVectorStore.java` — 自訂 VectorStore 實作，S014 不動，S015 取代
- `backend/src/main/java/.../shared/security/CurrentUserProvider.java` — S012 user 抽象，S014 不動，S016 擴充
- `backend/src/main/java/.../skill/query/SkillReadModel.java` — read model 範本，S014 改為 `@Table` JDBC 標注

### 2.6 Confidence Classification

| 設計決策 | Confidence | 證據 / POC 計畫 |
|---------|-----------|-----------------|
| Spring Data JDBC + record + JSONB Map converter 在 Spring Boot 4.0.6 可用 | **Validated** | spring-acl-jsonb 公開實證（4.0.0-M3）+ Spring Data JDBC reference docs |
| `spring-ai-starter-vector-store-pgvector` 與既有 `SearchConfig` 不衝突（`@ConditionalOnMissingBean(VectorStore.class)`） | **Validated** | Spring AI 文件 + Spring Boot auto-config 標準 pattern |
| `initialize-schema=false` 可禁止 starter auto-create vector_store 表 | **Validated** | Spring AI 文件 + 原始碼 `afterPropertiesSet()` 條件分支 |
| 加 owner / skill_id 欄位不會讓 `PgVectorStore.afterPropertiesSet()` fail | **Validated** | 官方原始碼：`DEFAULT_SCHEMA_VALIDATION = false` + INSERT 只動 4 欄 + UPSERT 不動多餘欄位（§2.4 #13） |
| Flyway V1 migration 在啟動時執行；後續 V2/V3 可由 S016/S017 加 | **Validated** | Spring Boot 4 auto-config 標準 |
| `@ServiceConnection` 注入 PostgreSQLContainer 至 `spring.datasource` | **Validated** | Spring Boot Testcontainers 文件 |
| HikariCP 預設池大小適合 Cloud Run 1 vCPU | **Validated** | Spring Boot 4 預設 + S013 Cloud Run 配置 |
| Cloud SQL Java Connector 在 Cloud Run 用 IAM auth + Unix socket | **Validated** | GCP 官方文件 |
| `Map<String, Object>` Converter 經 Jackson `TypeReference` 反序列化保留 nested 結構 | **Hypothesis（小）** | spring-acl-jsonb 用 `List<String>`；本 spec 用 Map<String, Object>，行為已知但無直接背書 → 列為 **POC required（unit test 級）** |

**POC: required（小型）** — 1 個 hypothesis（Map JSONB Converter）由 T1 第一個 task 的 `MapJsonbConverterTest` 覆蓋。範圍：餵 `Map.of("name", "x", "version", "1.0.0", "tags", List.of("a", "b"), "nested", Map.of("k", "v"))` 進 → JSONB → 出，斷言型別保留（String、List、Map）。失敗代價低（fallback：把複雜 metadata 改為 wrapper DTO），但寫進 spec 才能讓 `/planning-tasks` 正確排程。

### 2.7 Validation Pass — pre-handoff drift check

從現況 read 確認：
- ✅ 既有 5 個 `@Document` records 全為 record；遷移到 `@Table` record 不需大改
- ✅ `SkillQueryService.search()` 是唯一動態 query；其他都是 derived query method
- ✅ 既有 `@CompoundIndex` 兩處（`DomainEvent` + `SkillVersionReadModel`）；schema.sql 有對應 SQL `CREATE INDEX`
- ✅ 既有 `MongoTemplate.updateFirst` 一處（`ScanOrchestrator`）；JDBC `UPDATE` 對應
- ✅ 既有 `Aggregation.group` 一處（`getCategoryCounts`）；純 SQL `GROUP BY` 對應
- ✅ 既有 event listener 結構（`SkillProjection` + `SearchProjection` + `ScanOrchestrator` 各自獨立 `@EventListener`）— S014 全部保留，僅 `ScanOrchestrator` 內部改 SQL
- ✅ 既有 `CurrentUserProvider`（S012）— S014 不動，S016 擴充 groups/orgs/depts
- ✅ vector_store schema 加 `owner` + `skill_id` 是用戶明確指示
- ⚠️ `application-gcp.yaml` 的 Firestore mongo URI 需替換為 Cloud SQL JDBC URL — File Plan 已涵蓋
- ⚠️ `compose.yaml` 的 mongodb service 替換為 `pgvector/pgvector:pg16` — File Plan 已涵蓋

無 design drift；可進 §3。

---

## 3. SBE Acceptance Criteria

> 驗證指令：`cd backend && ./gradlew test`（既有 QA strategy 標準入口）
> 測試類別：既有 37 個測試檔保留（其中約 19 個小修），加 3 個新 unit test

```gherkin
Scenario: AC-1 — Flyway V1 schema migration 啟動時自動執行
  Given Spring Boot 應用以 Testcontainers pgvector/pgvector:pg16 啟動
  When ApplicationContext 完成初始化
  Then 6 張表都存在（domain_events, skills, skill_versions, flags,
                       download_events, vector_store）
  And vector_store 含 owner 與 skill_id 欄位
  And vector 與 uuid-ossp extensions 已啟用
  And HNSW index vs_emb_idx 存在於 vector_store(embedding)

Scenario: AC-2 — Map<String, Object> JSONB Converter 雙向保留 nested 結構
  Given 一個 Map.of(
            "name", "test-skill",
            "version", "1.0.0",
            "tags", List.of("docker", "k8s"),
            "metadata", Map.of("author", "sam", "license", "MIT")
        )
  When 寫入 DomainEvent.payload 後再讀回
  Then String / List / Map 三層型別均正確還原（用 instanceof 斷言）
  And 內容值零失真（深度比較）

Scenario: AC-3 — Aggregate Event Replay 順序正確且 sequence UNIQUE 約束生效
  Given 同一 aggregateId 已存 3 個 DomainEvent (sequence=1,2,3)
  When DomainEventRepository.findByAggregateIdOrderBySequenceAsc(aggregateId)
  Then 回傳 3 筆，sequence 升冪排列
  And 嘗試插入 (aggregateId, sequence=2) 第二次會拋 DataIntegrityViolationException
       （UNIQUE 約束防重）

Scenario: AC-4 — Skill Search keyword/category 動態 query 行為等同 v1.0.0
  Given skills 表有 5 筆資料（name 含 "docker", "kubernetes" 等）
  When SkillQueryService.search("docker", null, Pageable.of(0, 20))
  Then 回傳所有 name 或 description 含 "docker" 的 row
  And case-insensitive
  And LIKE 通配符（% _ \）已 escape 防 SQL 注入

Scenario: AC-5 — getCategoryCounts() 與 v1.0.0 結果一致
  Given skills 表有 categories: ["DevOps"×3, "Testing"×2, "Docs"×1]
  When SkillQueryService.getCategoryCounts()
  Then 回傳 [{"name":"DevOps","count":3}, {"name":"Testing","count":2},
              {"name":"Docs","count":1}]（按 count 遞減）

Scenario: AC-6 — download_count 並發更新原子化（修正既有 race condition）
  Given skill 的 download_count = 0
  When 同時觸發 100 次 SkillDownloadedEvent（並發 thread pool）
  Then download_count 最終值 = 100
  And 不會丟失任何更新（atomic UPDATE ... = download_count + 1）

Scenario: AC-7 — Spring Modulith 模組邊界仍綠
  Given S014 完成
  When ApplicationModules.verify() 執行
  Then 通過（shared / skill / security / search / analytics / storage 邊界不變）
  And shared.persistence module 為新增、被 skill / security / analytics 引用

Scenario: AC-8 — bootRun 在 local profile 啟動成功
  Given 環境 SPRING_PROFILES_ACTIVE=local,dev（預設）
  When ./gradlew bootRun
  Then 啟動成功
  And Docker Compose 自動啟動 pgvector/pgvector:pg16 容器
  And Flyway 自動執行 V1 migration
  And vector_store 表存在但無資料（FirestoreVectorStore 仍主導向量寫入）
  And 任一 GET /api/v1/skills 回 200（即使 skills 表是空）

Scenario: AC-9 — 三個 listener 解耦保留（事件流不變）
  Given Skill 上傳完整流程
  When SkillCommandService.uploadSkill(zipBytes, ...)
  Then SkillCreatedEvent 觸發 SkillProjection.on(SkillCreatedEvent)
                           + SearchProjection.onSkillCreated(...)
  And SkillVersionPublishedEvent 觸發 SkillProjection.on(SkillVersionPublishedEvent)
                                    + SearchProjection.onVersionPublished(...)
                                    + ScanOrchestrator.scan(...)
  And ScanOrchestrator 完成後寫 risk_assessment 到 skill_versions（jdbc.update）
  And 三個 listener 互不依賴（測試各自可單獨 mock 驗證）

Scenario: AC-10 — Custom SkillshubPgVectorStore 接管寫入；單次 6-欄 INSERT（T8 修訂）
  Given S014 完成、build.gradle 用 `spring-ai-pgvector-store`（core artifact，非 starter）
        + 無 `spring.ai.vectorstore.pgvector.*` yaml 設定
        + 無 `skillshub.search.vector-store` 屬性
        + 無 VectorStore @Bean 註冊
  When 上傳一個合法的 skill（POST /api/v1/skills/upload）
  Then SearchProjection.onSkillCreated 用 SkillshubPgVectorStore.builder(jdbc, em)
        .owner(CurrentUserProvider.userId()).skillId(event.aggregateId()).build().add(...)
       單次走 INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id) VALUES (...) ON CONFLICT DO UPDATE
  And `SELECT COUNT(*) FROM vector_store` = 1
  And `SELECT id, content, metadata, embedding, owner, skill_id FROM vector_store WHERE id = :skillId`
       回傳所有 6 欄非 NULL（id = UUID, embedding = vector(768), owner = CurrentUser.id, skill_id = aggregateId）
  And SQL trace 只看到 1 個 INSERT（沒有 add+UPDATE 兩個 SQL — 對比 T7 兩步驟版本的關鍵差異）
  And ApplicationContext 啟動時無 google-cloud-firestore 相關 bean
       + 無 PgVectorStore (官方) bean（dep 改 core，無 auto-config）

Scenario: AC-11 — 既有 SBE acceptance（S001–S013）回歸通過
  Given S014 完成、./gradlew test
  When 跑全套既有測試
  Then 既有 100+ 測試全綠（業務行為等同 v1.0.0）
  And 新增 unit test（MapJsonbConverterTest, DomainEventSequenceUniquenessTest,
                    AtomicDownloadCountTest, PgVectorStoreOwnerWriteTest）也綠

Scenario: AC-12 — GCP Cloud SQL Auth Proxy sidecar 連線可解析（修訂）
  Given application-gcp.yaml 用 jdbc:postgresql://localhost:5432/${SKILLSHUB_DB_NAME}
        + 環境變數 SKILLSHUB_DB_NAME / SKILLSHUB_DB_USER / SKILLSHUB_DB_PASSWORD
  When @SpringBootTest 用 gcp profile + mock env vars 載入
  Then ApplicationContext 啟動不報錯
  And HikariDataSource.jdbcUrl == "jdbc:postgresql://localhost:5432/skillshub"
  And HikariDataSource 連線屬性「不」含 socketFactory / cloudSqlInstance
       （因採 sidecar Auth Proxy，應用端為標準 JDBC 客戶端）
  And HikariDataSource maximumPoolSize == 3 / minimumIdle == 1 /
      leakDetectionThreshold == 60000
  And spring-cloud-gcp-autoconfigure CloudSqlEnvironmentPostProcessor
       不會啟動驗證（因無 postgres-socket-factory dep）
  And 本機 application-local.yaml 使用同條 JDBC URL（dev/prod parity）

Scenario: AC-13 — Firestore 完全移除（新增；S015 absorbed scope）
  Given S014 完成
  When ./gradlew dependencies | grep firestore
  Then 0 筆結果（google-cloud-firestore dep 已從 build.gradle 移除）
  And `find src -name FirestoreVectorStore*` 無結果（class 已刪除）
  And `grep -rn "firestoreVectorStore" src` 0 筆（@Bean 已從 SearchConfig 移除）
  And application-local.yaml / application-gcp.yaml 無 spring.cloud.gcp.firestore 設定
  And ./gradlew test 全綠（既有 SemanticSearchTest 改用 PgVectorStore mock 通過）
```

---

## 4. Interface / API Design

### 4.1 build.gradle.kts 依賴變動（修訂 — Firestore 一次清）

```diff
 dependencies {
     implementation("org.springframework.boot:spring-boot-starter-actuator")
-    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
+    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
+    implementation("org.springframework.boot:spring-boot-starter-jdbc")
     implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
     implementation("org.springframework.boot:spring-boot-starter-validation")
     implementation("org.springframework.boot:spring-boot-starter-webmvc")
-    implementation("com.google.cloud:google-cloud-firestore:3.31.6")
     implementation("com.google.cloud:spring-cloud-gcp-starter")
     implementation("com.google.cloud:spring-cloud-gcp-starter-storage")
+    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
     implementation("io.micrometer:micrometer-tracing-bridge-brave")
     implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
     implementation("org.springframework.ai:spring-ai-markdown-document-reader")
     implementation("org.springframework.ai:spring-ai-google-genai-embedding")
     implementation("org.springframework.ai:spring-ai-google-genai")
     implementation("org.springframework.ai:spring-ai-client-chat")
     implementation("org.springframework.ai:spring-ai-vector-store")
     implementation("org.springframework.modulith:spring-modulith-starter-core")
+    // S014: Spring Boot 4 Flyway auto-config 拆成獨立 artifact，需顯式引入
+    implementation("org.springframework.boot:spring-boot-flyway")
+    implementation("org.flywaydb:flyway-core")
+    runtimeOnly("org.flywaydb:flyway-database-postgresql")
+    runtimeOnly("org.postgresql:postgresql")
+    // 不引入 com.google.cloud.sql:postgres-socket-factory（決策 #9：sidecar Auth Proxy 路線無需）
     // ... 其他不動 ...
-    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
+    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
-    testImplementation("org.testcontainers:testcontainers-mongodb")
+    testImplementation("org.testcontainers:testcontainers-postgresql")
 }
```

> 說明：`spring-ai-starter-vector-store-pgvector` 會 transitively 帶入 `spring-boot-starter-jdbc` + `spring-ai-pgvector-store`；本 spec 額外明寫 `spring-boot-starter-data-jdbc`（提供 `@Table` / `CrudRepository` SPI）+ `spring-boot-flyway`（Spring Boot 4 split auto-config）+ `flyway-core` + `flyway-database-postgresql` + `org.postgresql:postgresql`（明確 runtime dep）。

> `spring-ai-advisors-vector-store` **本 spec 不引入** — 為 ChatClient RAG advisor 用，Skills Hub 目前 semantic search 不走 ChatClient。將來若有 LLM judge / RAG 擴展再加。

> ✱ **修訂（2026-04-27 T2 ship 後）**：`google-cloud-firestore` 由 T7 一次移除（決策 #10）。原 spec 預留至 S015 才移；T2 ship 後 Mongo 已乾淨，不再分批拆 Firestore。`spring-cloud-gcp-starter-storage` **保留**（GCS skill package 儲存仍走它）。`spring-cloud-gcp-starter` 也保留（其他 GCP 整合 — Secret Manager / IAM）。

### 4.2 SkillReadModel 改寫範例

```java
// before (Spring Data MongoDB)
@Document("skills")
public record SkillReadModel(
        @Id String id,
        String name,
        ...
) {}

// after (Spring Data JDBC)
@Table("skills")
public record SkillReadModel(
        @Id String id,
        @Column("name") String name,
        @Column("description") String description,
        @Column("author") String author,
        @Column("category") String category,
        @Column("latest_version") String latestVersion,
        @Column("risk_level") String riskLevel,
        @Column("status") String status,
        @Column("download_count") long downloadCount,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt
) {}
```

`@Table` 從 `org.springframework.data.relational.core.mapping`，`@Id` / `@Column` 從同 package。Spring Data JDBC 自動 snake_case ↔ camelCase mapping，理論上 `@Column` 多數可省，但**顯式宣告較不脆弱**。其他 4 個 read model 同樣改寫。

### 4.3 Repository 介面範例

```java
// before
public interface SkillReadModelRepository extends MongoRepository<SkillReadModel, String> {}

// after
public interface SkillReadModelRepository extends ListCrudRepository<SkillReadModel, String> {

    @Modifying
    @Query("UPDATE skills SET download_count = download_count + 1, updated_at = :ts WHERE id = :id")
    int incrementDownloadCount(@Param("id") String id, @Param("ts") Instant ts);
}

// SkillVersionReadModelRepository — derived query + @Modifying @Query 直接放 repo（Spring Data JDBC 慣例）
public interface SkillVersionReadModelRepository
        extends ListCrudRepository<SkillVersionReadModel, String> {

    List<SkillVersionReadModel> findBySkillIdOrderByPublishedAtDesc(String skillId);

    /**
     * S010 ScanOrchestrator 完成多引擎掃描後寫入 risk_assessment。
     * 用 @Modifying @Query 而非 ScanOrchestrator 內 raw jdbc.update —
     * 原則：CRUD / single-row UPDATE 走 Spring Data JDBC、僅動態 query 才下 JdbcTemplate。
     *
     * @param riskJson Jackson 序列化後的 risk assessment JSON 字串；CAST(... AS jsonb) 由 PostgreSQL 完成
     */
    @Modifying
    @Query("""
        UPDATE skill_versions
           SET risk_assessment = CAST(:riskJson AS jsonb)
         WHERE skill_id = :skillId AND version = :version
        """)
    int updateRiskAssessment(
            @Param("skillId") String skillId,
            @Param("version") String version,
            @Param("riskJson") String riskJson);
}
```

### 4.4 JdbcConfiguration（自訂 Map JSONB Converter）

```java
package io.github.samzhu.skillshub.shared.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Spring Data JDBC 配置 — 註冊 Map ↔ PostgreSQL JSONB 雙向 converter。
 *
 * <p>用於 {@code DomainEvent.payload}、{@code DomainEvent.metadata}、
 * {@code SkillVersionReadModel.frontmatter}、{@code SkillVersionReadModel.riskAssessment}、
 * {@code DownloadEventReadModel.metadata} 等 Map 欄位的 JSONB 持久化。
 *
 * <p>採 Jackson ObjectMapper 序列化（共用 Spring Boot auto-config bean）。
 */
@Configuration
class JdbcConfiguration extends AbstractJdbcConfiguration {

    private final ObjectMapper objectMapper;

    JdbcConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected List<?> userConverters() {
        return List.of(
                new MapToPGobjectConverter(objectMapper),
                new PGobjectToMapConverter(objectMapper)
        );
    }

    @WritingConverter
    static final class MapToPGobjectConverter implements Converter<Map<String, Object>, PGobject> {
        private final ObjectMapper mapper;
        MapToPGobjectConverter(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public PGobject convert(Map<String, Object> source) {
            try {
                var pgo = new PGobject();
                pgo.setType("jsonb");
                pgo.setValue(source == null ? "{}" : mapper.writeValueAsString(source));
                return pgo;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize Map to JSONB", e);
            }
        }
    }

    @ReadingConverter
    static final class PGobjectToMapConverter implements Converter<PGobject, Map<String, Object>> {
        private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};
        private final ObjectMapper mapper;
        PGobjectToMapConverter(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public Map<String, Object> convert(PGobject source) {
            try {
                var v = source.getValue();
                return v == null || v.isBlank() ? Map.of() : mapper.readValue(v, TYPE);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize JSONB to Map", e);
            }
        }
    }
}
```

> `DomainEvent.metadata` 既有型別 `Map<String, String>` — 遷移時提升為 `Map<String, Object>`（向下相容；使用方都已用泛型 erased type，無風險）。

### 4.5 SkillQueryService.search 改寫

```java
public Page<SkillReadModel> search(String keyword, String category, Pageable pageable) {
    var sql = new StringBuilder("""
        SELECT id, name, description, author, category,
               latest_version, risk_level, status, download_count,
               created_at, updated_at
          FROM skills
         WHERE 1=1
        """);
    var params = new MapSqlParameterSource();

    if (StringUtils.hasText(keyword)) {
        sql.append("""
              AND (LOWER(name) LIKE LOWER(:kw) OR LOWER(description) LIKE LOWER(:kw))
            """);
        params.addValue("kw", "%" + sanitizeLikePattern(keyword) + "%");
    }
    if (StringUtils.hasText(category)) {
        sql.append(" AND category = :cat");
        params.addValue("cat", category);
    }

    var orderBy = buildOrderByClause(pageable.getSort());   // 白名單驗證
    sql.append(" ").append(orderBy)
       .append(" LIMIT :limit OFFSET :offset");
    params.addValue("limit", pageable.getPageSize())
          .addValue("offset", pageable.getOffset());

    var rows = jdbc.query(sql.toString(), params, SKILL_ROW_MAPPER);
    long total = countMatching(keyword, category);
    return new PageImpl<>(rows, pageable, total);
}

/** Escape LIKE wildcard 字元（% _ \）防 SQL 通配符注入。 */
private static String sanitizeLikePattern(String input) {
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
}
```

### 4.6 ScanOrchestrator 寫 risk_assessment 改寫（走 Repository @Modifying @Query）

```java
// before (MongoTemplate)
mongoTemplate.updateFirst(
    Query.query(Criteria.where("skillId").is(skillId).and("version").is(version)),
    Update.update("riskAssessment", riskMap),
    "skill_versions"
);

// after (Spring Data JDBC — 走 repository 方法，ScanOrchestrator 不再持有 JdbcTemplate)
@Service
class ScanOrchestrator {
    private final SkillVersionReadModelRepository versionRepo;
    private final ObjectMapper objectMapper;
    // ... 其他 deps（既有不變）...

    void onVersionPublished(SkillVersionPublishedEvent event) {
        var riskMap = scan(...);
        var json = objectMapper.writeValueAsString(riskMap);
        versionRepo.updateRiskAssessment(event.aggregateId(), event.version(), json);
    }
}
```

> 設計原則：CRUD / single-row UPDATE 走 Spring Data JDBC `ListCrudRepository` + `@Modifying @Query`；只有 `SkillQueryService.search()` 因 dynamic optional filters 必須下 `NamedParameterJdbcTemplate`。

### 4.7 SkillProjection — atomic download_count 更新

```java
@EventListener
void on(SkillDownloadedEvent event) {
    repo.incrementDownloadCount(event.aggregateId(), Instant.now());
    log.atDebug().addKeyValue("skillId", event.aggregateId()).log("投影已累加下載次數");
}
```

### 4.8 application.yaml 變動

```diff
 spring:
-  mongodb:
-    uri: mongodb://localhost:27017/skillshub
-    database: skillshub
-  data:
-    mongodb:
-      auto-index-creation: false
+  datasource:
+    url: jdbc:postgresql://localhost:5432/mydatabase
+    username: myuser
+    password: secret
+    hikari:
+      maximum-pool-size: 10
+  flyway:
+    enabled: true
+    baseline-on-migrate: false
+    locations: classpath:db/migration
+  ai:
+    vectorstore:
+      pgvector:
+        initialize-schema: false       # 由 Flyway V1 建表，不交給 starter auto-init
+        schema-validation: false       # 顯式關閉 — 我們的 vector_store 表多了 owner / skill_id 欄位，
+                                       # 啟動驗證若未來預設變 true 會 fail
+        schema-name: public
+        table-name: vector_store
+        index-type: HNSW               # 對應 V1 SQL 的 vs_emb_idx
+        distance-type: COSINE_DISTANCE
+        dimensions: 768                # Gemini text-embedding-2 維度
+        max-document-batch-size: 10000 # 預設值；明寫便於將來調整
```

> Database / username / password 對齊 user 提供的 docker compose template (`mydatabase` / `myuser` / `secret`)。GCP profile 用 Cloud SQL Auth Proxy sidecar 覆寫（§4.9）。
>
> ✱ **修訂（2026-04-27 T7 階段）**：T1/T2 ship 期間 `application.yaml` 為簡化用 `autoconfigure.exclude: PgVectorStoreAutoConfiguration` 阻擋 bean 建立。**T7 把 PgVectorStore 啟用為實際 VectorStore 後，必須**：
> 1. 移除 `autoconfigure.exclude` 的 `PgVectorStoreAutoConfiguration` 一行（讓 starter auto-config 啟用）
> 2. 補上完整 `spring.ai.vectorstore.pgvector.*` 設定（如上 yaml diff 所示）
> 3. `initialize-schema: false`（由 Flyway V1 建表）+ `schema-validation: false`（容許 owner / skill_id 多餘欄位）為**必設**

### 4.9 application-gcp.yaml 變動（修訂 — Auth Proxy sidecar）

```yaml
spring:
  datasource:
    # Cloud SQL Enterprise + PostgreSQL 18 + db-f1-micro（0.614 GB RAM, max_connections=25）
    # Cloud SQL Auth Proxy sidecar：應用 container 透過 localhost:5432 連 sidecar
    # sidecar 用 IAM auth 連 Cloud SQL；無 socketFactory dep；dev/prod 同條 JDBC URL
    url: jdbc:postgresql://localhost:5432/${SKILLSHUB_DB_NAME:skillshub}
    username: ${SKILLSHUB_DB_USER}
    password: ${SKILLSHUB_DB_PASSWORD}
    hikari:
      # db-f1-micro max_connections=25 - 5 reserved = 20 available
      # 假設 Cloud Run max 5 instances → 20÷5 = 4/instance；取 3 留 buffer
      maximum-pool-size: 3
      minimum-idle: 1
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      # leak-detection-threshold 對 micro instance 特別有用（連線洩漏會更快觸頂）
      leak-detection-threshold: 60000

  # GCP 部署 firestore 整合移除（Firestore 完全拆乾淨；T7 範圍）
  cloud:
    gcp:
      storage:
        enabled: true
      # firestore:  ← 移除（決策 #10）
      #   enabled: true
```

> **DB 規格**：Cloud SQL **Enterprise edition** + **PostgreSQL 18** + **db-f1-micro**（shared core, 1 vCPU / 0.614 GB RAM）。
>
> **建立 instance + Cloud Run sidecar 部署**（屬部署腳本範圍，本 spec 不涵蓋；S013 GCP 部署模板需擴充）：
>
> ```bash
> # 1. 建立 Cloud SQL instance（含啟用 pgvector flag）
> gcloud sql instances create skillshub-db \
>   --database-version=POSTGRES_18 \
>   --edition=enterprise \
>   --tier=db-f1-micro \
>   --region=$REGION \
>   --database-flags=cloudsql.enable_pgvector=on
>
> # 2. 建立 db + user（migration 自動跑 V1）
> gcloud sql databases create skillshub --instance=skillshub-db
> gcloud sql users create skillshub-user --instance=skillshub-db --type=cloud_iam_service_account
>
> # 3. Cloud Run 部署 multi-container（main + cloud-sql-proxy sidecar）
> #    用 service.yaml 而非 gcloud flag，因 sidecar 配置 CLI 受限
> cat > service.yaml <<EOF
> apiVersion: serving.knative.dev/v1
> kind: Service
> metadata:
>   name: skillshub
> spec:
>   template:
>     spec:
>       serviceAccountName: skillshub-runner@$PROJECT.iam.gserviceaccount.com
>       containers:
>         - name: app
>           image: gcr.io/$PROJECT/skillshub:latest
>           ports: [{containerPort: 8080}]
>           env:
>             - {name: SPRING_PROFILES_ACTIVE, value: gcp,prod}
>             - {name: SKILLSHUB_DB_NAME, value: skillshub}
>             - {name: SKILLSHUB_DB_USER, value: skillshub-runner@$PROJECT.iam}
>             - {name: SKILLSHUB_DB_PASSWORD, valueFrom: {secretKeyRef: {name: skillshub-db-pass, key: latest}}}
>           startupProbe:
>             httpGet: {path: /actuator/health/liveness, port: 8080}
>             initialDelaySeconds: 20
>         - name: cloud-sql-proxy
>           image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.x
>           args: ["--port=5432", "--auto-iam-authn", "$PROJECT:$REGION:skillshub-db"]
>           startupProbe:
>             tcpSocket: {port: 5432}
>             initialDelaySeconds: 3
> EOF
> gcloud run services replace service.yaml --region=$REGION
> ```
>
> Secrets（`SKILLSHUB_DB_PASSWORD`）由 Secret Manager 注入（延續 S013 模式）；若用 IAM auth（推薦，sidecar 啟 `--auto-iam-authn`），可省 password env var、`SKILLSHUB_DB_USER` 改用 service account email。
>
> **production 升級路徑**：若觸 db-f1-micro 瓶頸（max_connections=25 + 0.614 GB RAM），可線上升級 dedicated machine（如 `db-custom-2-7680` → max=200）或 Enterprise Plus C4A，HikariCP `maximum-pool-size` 隨之 env var override 調高，無需改 yaml。

### 4.10 compose.yaml 變動

```diff
 services:
-  mongodb:
-    image: 'mongo:7'
-    ports:
-      - '27017:27017'
+  pgvector:
+    image: 'pgvector/pgvector:pg16'
+    environment:
+      - 'POSTGRES_DB=mydatabase'
+      - 'POSTGRES_USER=myuser'
+      - 'POSTGRES_PASSWORD=secret'
+    ports:
+      - '5432:5432'
+    labels:
+      - "org.springframework.boot.service-connection=postgres"
+    healthcheck:
+      test: ['CMD-SHELL', 'pg_isready -U myuser -d mydatabase']
+      interval: 5s
+      timeout: 2s
+      retries: 10

   mock-oauth2-server:
     # ... 不變 ...
```

> `org.springframework.boot.service-connection=postgres` label 讓 Spring Boot Docker Compose 整合自動把連線資訊注入 `spring.datasource.*`，無須手寫 url/username/password（dev 環境）。

### 4.11 TestcontainersConfiguration 變動

```diff
-import org.testcontainers.containers.MongoDBContainer;
+import org.testcontainers.containers.PostgreSQLContainer;
+import org.testcontainers.utility.DockerImageName;

 @TestConfiguration
 class TestcontainersConfiguration {
-    @Bean
-    @ServiceConnection
-    MongoDBContainer mongoDb() {
-        return new MongoDBContainer("mongo:7");
-    }
+    @Bean
+    @ServiceConnection
+    PostgreSQLContainer<?> pgvector() {
+        // 用 pgvector image — 與本機 compose 一致；S015/S016/S017 不需再換
+        // asCompatibleSubstituteFor 告訴 Testcontainers 此 image 為 postgres-compatible
+        return new PostgreSQLContainer<>(
+                DockerImageName.parse("pgvector/pgvector:pg16")
+                        .asCompatibleSubstituteFor("postgres"))
+                .withDatabaseName("test")
+                .withUsername("test")
+                .withPassword("test");
+    }
 }
```

`@ServiceConnection` 自動把 container 連線資訊寫到 `spring.datasource.url/username/password`，無需 `@DynamicPropertySource`。

### 4.12 SearchConfig 簡化（T8：移除所有 VectorStore @Bean；只留 EmbeddingModel）

落實 §2.1 決策 #2（T8 修訂）：T8 移除 `simpleVectorStore @Bean`（dev 走 Docker Compose pgvector，無 fallback 需要）+ 不註冊任何 `VectorStore @Bean`（per-request 由呼叫端用 `SkillshubPgVectorStore.builder(...)` 建構）。`SearchConfig` 削減為「`EmbeddingModel` 兩個 bean + 內嵌 `NoOpEmbeddingModel` private static class」。

```diff
- import org.springframework.ai.vectorstore.SimpleVectorStore;
- import org.springframework.ai.vectorstore.VectorStore;
- import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;

  import io.github.samzhu.skillshub.SkillshubProperties;

  @Configuration
  class SearchConfig {

      private static final Logger log = LoggerFactory.getLogger(SearchConfig.class);

      @Bean
      @ConditionalOnProperty(name = "skillshub.genai.api-key")
      EmbeddingModel googleGenAiEmbeddingModel(SkillshubProperties props) { ... }

      @Bean
      @ConditionalOnMissingBean(EmbeddingModel.class)
      EmbeddingModel noOpEmbeddingModel() { ... }

-     @Bean
-     @ConditionalOnProperty(name = "skillshub.search.vector-store", havingValue = "simple")
-     VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
-         return SimpleVectorStore.builder(embeddingModel).build();
-     }

      private static final class NoOpEmbeddingModel implements EmbeddingModel { ... }
  }
```

> **為什麼不註冊 `VectorStore @Bean`**：owner / skillId 是 per-write context，不適合 singleton bean attributes。`SkillshubPgVectorStore` 在每個 `SearchProjection.onSkillCreated` / `SemanticSearchService.search` 呼叫時用 builder 建構新 instance，操作完 GC — 無 thread-safety 顧慮、無 singleton state leak、構造成本可忽略（skill 寫入 ≪ 1/s）。

> **dependency 變動**：`build.gradle.kts` 從 `spring-ai-starter-vector-store-pgvector` 改為 `spring-ai-pgvector-store`（core artifact，無 auto-config）。

> **`application.yaml` 變動**：完全刪除 `spring.ai.vectorstore.pgvector.*` 與 `skillshub.search.vector-store` 設定 — 不再走 starter wiring；`SkillshubPgVectorStore` constructor 內部設定（dimensions=768、index-type=HNSW、distance-type=COSINE）。

### 4.13 SearchProjection per-request builder 寫入（T8）

落實 §2.1 決策 #12（T8 修訂）：取代 T7 的兩步驟 add+UPDATE 為單次 6-欄 INSERT。`SearchProjection` 每個 listener 用 `SkillshubPgVectorStore.builder(jdbc, em).owner(...).skillId(...).build()` 建構 instance、呼叫 `add(...)`、不持有 reference。

```java
@Component
class SearchProjection {

    private static final Logger log = LoggerFactory.getLogger(SearchProjection.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final CurrentUserProvider currentUserProvider;

    SearchProjection(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                     CurrentUserProvider currentUserProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.currentUserProvider = currentUserProvider;
    }

    @EventListener
    void onSkillCreated(SkillCreatedEvent event) {
        var doc = buildDocument(event.aggregateId(), event.name(), event.description(),
                event.author(), event.category(), null, null);

        // per-request：owner / skillId 鎖在這個 instance 裡，操作完 GC
        SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(currentUserProvider.userId())
                .skillId(event.aggregateId())
                .build()
                .add(List.of(doc));
    }

    @EventListener
    void onVersionPublished(SkillVersionPublishedEvent event) {
        // delete + re-add 用同一個 instance（同 owner/skillId context）
        var vectorStore = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(currentUserProvider.userId())
                .skillId(event.aggregateId())
                .build();

        vectorStore.delete(List.of(event.aggregateId()));

        var fm = event.frontmatter();
        var doc = buildDocument(event.aggregateId(),
                getString(fm, "name", event.aggregateId()),
                getString(fm, "description", ""),
                getString(fm, "author", ""),
                getString(fm, "category", ""),
                event.version(), null);
        vectorStore.add(List.of(doc));
    }
    // buildDocument / getString helpers 不變
}
```

**vs. T7 兩步驟**（已移除的 code）：
- ❌ `private final VectorStore vectorStore;`（無 singleton）
- ❌ `instanceof PgVectorStore pgvector` guard
- ❌ `pgvector.<JdbcTemplate>getNativeClient().orElseThrow(...)`
- ❌ `UUID.fromString(docId)` 顯式轉型
- ❌ 第二步 `jdbc.update("UPDATE vector_store SET owner=?, skill_id=? WHERE id=?", ...)`
- ❌ `private void updateOwnerAndSkillId(...)` private method

**冪等性**：`SkillshubPgVectorStore.doAdd` 走 `INSERT ... ON CONFLICT (id) DO UPDATE SET content=?, metadata=?, embedding=?, owner=COALESCE(?, owner), skill_id=COALESCE(?, skill_id)` — 同一 skill 多次 ingest 的後續 INSERT 走 UPDATE 路徑、6 欄重寫；owner / skill_id 用 COALESCE 保留既有非 null 值，避免後續 ingest 不帶 owner 時被 null 蓋掉（防禦深度）。

**listener 順序**：T7 加的 `SkillProjection.on(SkillCreatedEvent)` `@Order(HIGHEST_PRECEDENCE)` 仍需要 — 與「單次 / 兩步驟」無關，純粹是 FK `vector_store.skill_id → skills.id` 在 INSERT 時 enforce，skills row 必須先存在。

### 4.14 SkillshubPgVectorStore 設計（T8 新增）

新類別放於 `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`；繼承 Spring AI `AbstractObservationVectorStore`，包 6-欄 INSERT 與 owner-aware similarity search。

```java
package io.github.samzhu.skillshub.search;

import com.pgvector.PGvector;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 自訂 PgVectorStore — 寫入 vector_store 表 6 欄（id, content, metadata, embedding, owner, skill_id），
 * 而非官方 PgVectorStore 的 4 欄（無 owner / skill_id）。
 *
 * <p>**Per-request instantiation 模式**（不註冊 Spring Bean）：
 * <pre>{@code
 * SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
 *     .owner("user-42")        // 寫入時來自 CurrentUserProvider.userId()
 *     .skillId("uuid-...")     // 寫入時來自 SkillCreatedEvent.aggregateId()
 *     .build()
 *     .add(List.of(doc));
 * }</pre>
 *
 * <p>讀取（similaritySearch）不需要 owner/skillId — builder 略過即可。
 *
 * <p>繼承 {@link AbstractObservationVectorStore} 自動取得 Micrometer observation tracing
 * （via {@code AbstractVectorStoreBuilder.observationRegistry(...)}）。
 *
 * @see AbstractObservationVectorStore
 * @see SearchProjection
 */
class SkillshubPgVectorStore extends AbstractObservationVectorStore {

    static final String INSERT_SQL = """
            INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id)
            VALUES (?::uuid, ?, ?::jsonb, ?, ?, ?::varchar)
            ON CONFLICT (id) DO UPDATE
              SET content = EXCLUDED.content,
                  metadata = EXCLUDED.metadata,
                  embedding = EXCLUDED.embedding,
                  owner = COALESCE(EXCLUDED.owner, vector_store.owner),
                  skill_id = COALESCE(EXCLUDED.skill_id, vector_store.skill_id)
            """;

    static final String DELETE_SQL = "DELETE FROM vector_store WHERE id = ?::uuid";

    // <=> 為 pgvector cosine distance operator；ORDER BY ascending = closest first
    static final String SIMILARITY_SEARCH_SQL = """
            SELECT id, content, metadata, embedding <=> ? AS distance
              FROM vector_store
             WHERE embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbcTemplate;
    private final @Nullable String owner;
    private final @Nullable String skillId;

    private SkillshubPgVectorStore(Builder builder) {
        super(builder);
        this.jdbcTemplate = builder.jdbcTemplate;
        this.owner = builder.owner;
        this.skillId = builder.skillId;
    }

    public static Builder builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return new Builder(jdbcTemplate, embeddingModel);
    }

    @Override
    public void doAdd(List<Document> documents) {
        // 一次 batch embedding（呼叫 EmbeddingModel batch API）；reuse Spring AI BatchingStrategy
        var embeddings = this.embeddingModel.embed(documents, EmbeddingOptions.builder().build(), this.batchingStrategy);

        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            var pgVector = new PGvector(embeddings.get(i));
            var metadataJson = JSON.writeValueAsString(doc.getMetadata());
            jdbcTemplate.update(INSERT_SQL,
                    doc.getId(), doc.getText(), metadataJson, pgVector, owner, skillId);
        }
    }

    @Override
    public void doDelete(List<String> idList) {
        for (String id : idList) {
            jdbcTemplate.update(DELETE_SQL, id);
        }
    }

    @Override
    public void doDelete(Filter.Expression filterExpression) {
        // S014 不支援 filter-based delete；S017 ACL filter 階段才實作
        throw new UnsupportedOperationException("Filter-based delete not supported in S014");
    }

    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        var queryEmbedding = new PGvector(this.embeddingModel.embed(request.getQuery()));
        // SimilarityThreshold 0..1（1 = 完全相同）；轉成距離 = 1 - threshold
        double maxDistance = 1 - request.getSimilarityThreshold();
        return jdbcTemplate.query(SIMILARITY_SEARCH_SQL,
                new DocumentRowMapper(),
                queryEmbedding, queryEmbedding, maxDistance, request.getTopK());
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        @SuppressWarnings("unchecked")
        T client = (T) this.jdbcTemplate;
        return Optional.of(client);
    }

    public static class Builder extends AbstractVectorStoreBuilder<Builder> {
        private final JdbcTemplate jdbcTemplate;
        private @Nullable String owner;
        private @Nullable String skillId;

        Builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
            super(embeddingModel);
            this.jdbcTemplate = jdbcTemplate;
        }

        public Builder owner(@Nullable String owner) {
            this.owner = owner;
            return self();
        }

        public Builder skillId(@Nullable String skillId) {
            this.skillId = skillId;
            return self();
        }

        public SkillshubPgVectorStore build() {
            return new SkillshubPgVectorStore(this);
        }
    }

    private static class DocumentRowMapper implements RowMapper<Document> {
        @Override
        public Document mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            String id = rs.getString("id");
            String content = rs.getString("content");
            double distance = rs.getDouble("distance");
            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata = metadataJson == null ? Map.of()
                    : JSON.readValue(metadataJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            // Document.score = similarity（1 - distance）
            return Document.builder()
                    .id(id)
                    .text(content)
                    .metadata(metadata)
                    .score(1 - distance)
                    .build();
        }
    }
}
```

> **為什麼 `INSERT_SQL` 用 `?::uuid` cast**：vector_store.id 為 PostgreSQL `UUID` type；JDBC 預設 `setString` 會觸發 `column "id" is of type uuid but expression is of type character varying` 錯誤。`?::uuid` 顯式 cast 在 SQL 層完成轉型，比 Java 端 `UUID.fromString` 更直接（與 §2.4 #13 官方 PgVectorStore 同手法）。

> **為什麼 `skill_id ?::varchar` cast**：vector_store.skill_id 為 `VARCHAR(36)`；`null` 在 PreparedStatement 默認 type 為 `OTHER`，PostgreSQL 較嚴格的 driver 會拒絕；`::varchar` 強制宣告型別。

> **`COALESCE` ON CONFLICT 處理**：同一 doc id 多次寫入時，若後續 ingest 不帶 owner（如 batch sync 場景），`COALESCE(EXCLUDED.owner, vector_store.owner)` 保留首次寫入值 — 防禦深度。

> **不實作 `Filter.Expression` 變體 doDelete**：S014 無 filter-based delete 需求（只用 by-id delete）；S017 ACL filter 階段擴展。S014 拋 `UnsupportedOperationException`。

> **`BatchingStrategy` 來自父類**：`AbstractObservationVectorStore` 透過 `protected final BatchingStrategy batchingStrategy` 暴露；builder 預設 `TokenCountBatchingStrategy`。`embed(documents, options, batchingStrategy)` 走官方 batch API。

---

## 5. File Plan

| 檔案 | 動作 | 說明 |
|------|------|------|
| **Schema migration** | | |
| `backend/src/main/resources/db/migration/V1__initial_schema.sql` | A | pgvector + uuid-ossp extensions + 6 張表完整 SQL（§2.3） |
| **Build & config** | | |
| `backend/build.gradle.kts` | M | T2 已移 `spring-boot-starter-data-mongodb`、加 `data-jdbc` + flyway + postgresql + `spring-ai-starter-vector-store-pgvector`；T7 再移 `com.google.cloud:google-cloud-firestore`（決策 #10）；**T8 從 `spring-ai-starter-vector-store-pgvector` 改 `spring-ai-pgvector-store`**（core artifact，無 auto-config）|
| `backend/src/main/resources/application.yaml` | M | T2 已移 mongodb、加 datasource + flyway 與 `autoconfigure.exclude PgVectorStoreAutoConfiguration`；T5 補完整 `spring.ai.vectorstore.pgvector.*`；T7 移 exclude；**T8 完全刪除 `spring.ai.vectorstore.pgvector.*` 與 `skillshub.search.vector-store`**（不再走 starter wiring；自訂子類 constructor 寫死設定）|
| `backend/src/main/resources/application-local.yaml` | M | T5 加 `datasource.url=jdbc:postgresql://localhost:5432/...`（與 GCP sidecar 同條 URL）+ 補 base 的 `PgVectorStoreAutoConfiguration` exclude；**T7 才**移除 `spring.cloud.gcp.firestore.enabled=false`（必須與 dep 同時清，否則 `GcpFirestoreAutoConfiguration` 找不到 ProjectIdProvider）|
| `backend/src/main/resources/application-gcp.yaml` | M | T5 重寫為 Cloud SQL Auth Proxy sidecar 模式（`jdbc:postgresql://localhost:5432/...` + HikariCP pool=3 + 移除 firestore 設定，決策 #9） |
| `backend/compose.yaml` | M | T2 已將 mongodb service → `pgvector/pgvector:pg16`；T5 修正 mock-oauth2-server healthcheck 從 `wget --spider`（HEAD）→ `wget -q -O- ... > /dev/null`（GET）— navikt mock 不支援 HEAD |
| **Persistence config**（新增 module）| | |
| `backend/src/main/java/.../shared/persistence/JdbcConfiguration.java` | A | `AbstractJdbcConfiguration` + Map↔JSONB Converter |
| `backend/src/main/java/.../shared/persistence/package-info.java` | A | `@ApplicationModule` 宣告（Spring Modulith 邊界） |
| **Read model annotations**（5 個 record）| | |
| `backend/src/main/java/.../shared/events/DomainEvent.java` | M | `@Document` → `@Table`；`@CompoundIndex` 移除（schema.sql 處理）；`metadata` 型別改 `Map<String, Object>` |
| `backend/src/main/java/.../skill/query/SkillReadModel.java` | M | `@Document` → `@Table` |
| `backend/src/main/java/.../skill/query/SkillVersionReadModel.java` | M | `@Document` → `@Table`；`@CompoundIndex` 移除 |
| `backend/src/main/java/.../security/FlagReadModel.java` | M | `@Document` → `@Table` |
| `backend/src/main/java/.../analytics/DownloadEventReadModel.java` | M | `@Document` → `@Table`；`metadata` 型別 `Map<String, String>` → `Map<String, Object>` |
| **Repository interfaces**（5 個）| | |
| `backend/src/main/java/.../shared/events/DomainEventRepository.java` | M | `MongoRepository` → `ListCrudRepository`；derived query 不變 |
| `backend/src/main/java/.../skill/query/SkillReadModelRepository.java` | M | 同上，加 `incrementDownloadCount(@Modifying)` |
| `backend/src/main/java/.../skill/query/SkillVersionReadModelRepository.java` | M | 同上 |
| `backend/src/main/java/.../security/FlagReadModelRepository.java` | M | 同上 |
| `backend/src/main/java/.../analytics/DownloadEventRepository.java` | M | 同上 |
| **Dynamic queries** | | |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | M | `MongoTemplate` → `NamedParameterJdbcTemplate`；search() / getCategoryCounts() 改寫；downloadAndRecord 不動（domain event 部分） |
| `backend/src/main/java/.../skill/query/SkillProjection.java` | M | `on(SkillDownloadedEvent)` 改用 `incrementDownloadCount` atomic update |
| `backend/src/main/java/.../security/scan/ScanOrchestrator.java` | M | `MongoTemplate.updateFirst` → `JdbcTemplate.update` |
| **Search module（Firestore 拆除 + 自訂 PgVectorStore 子類接管，T7 + T8）** | | |
| `backend/src/main/java/.../search/SearchConfig.java` | M | T7 刪 `firestoreVectorStore @Bean`；T8 再刪 `simpleVectorStore @Bean` — SearchConfig 削減為「只提供 EmbeddingModel beans」（§4.12） |
| `backend/src/main/java/.../search/SkillshubPgVectorStore.java` | **A**（T8 新增） | 自訂 `extends AbstractObservationVectorStore` + Builder pattern；6 欄 INSERT；per-request 建構，不註冊 Bean（§4.14）|
| `backend/src/main/java/.../search/SearchProjection.java` | M | T7 加 `CurrentUserProvider` 注入 + 兩步驟寫入；**T8 改用 `SkillshubPgVectorStore.builder(...)` 單次 6-欄 INSERT，移除 instanceof guard / UUID.fromString workaround / updateOwnerAndSkillId private method**（§4.13）|
| `backend/src/main/java/.../search/SemanticSearchService.java` | M | T8 改用 `SkillshubPgVectorStore.builder(jdbc, em).build().similaritySearch(...)`；移除 `VectorStore` constructor 注入 |
| `backend/src/main/java/.../search/FirestoreVectorStore.java` | **D** | T7 刪除（決策 #10；AC-13） |
| **Test infrastructure** | | |
| `backend/src/test/java/.../TestcontainersConfiguration.java` | M | MongoDBContainer → PostgreSQLContainer(pgvector/pgvector:pg16) |
| `backend/src/test/resources/application.yaml` | M | datasource + flyway test 配置 |
| **Test files**（37 個 — 約 19 個小修；明細由 `/planning-tasks` 拆 task 時逐一掃描）| | |
| `backend/src/test/java/.../shared/events/DomainEventRepositoryTest.java` | M | `@DataMongoTest` → `@DataJdbcTest`；mongo-specific assertion 改 jdbc |
| `backend/src/test/java/.../skill/SkillIntegrationTest.java` | M | `@DataMongoTest` → `@DataJdbcTest` 或 `@SpringBootTest` |
| `backend/src/test/java/.../skill/query/SkillSearchTest.java` | M | 同上 |
| `backend/src/test/java/.../skill/query/SkillVersionQueryTest.java` | M | 同上 |
| `backend/src/test/java/.../skill/command/*Test.java` | M | 同上（4 個：SkillCommandServiceTest / SkillUploadTest / SkillDownloadTest / 等） |
| `backend/src/test/java/.../security/FlagControllerTest.java` | M | 同上 |
| `backend/src/test/java/.../security/RiskAssessmentIntegrationTest.java` | M | 直接驗 jdbc.update 寫 risk_assessment 結果（取代 MongoTemplate.updateFirst 驗證） |
| `backend/src/test/java/.../analytics/AnalyticsControllerTest.java` | M | 同上（如有） |
| **Search module 既有測試（T7 + T8 適配新架構）** | | |
| `backend/src/test/java/.../search/SemanticSearchTest.java` | M | mock `VectorStore` 行為；AC 標籤對應 §3 search-related；T8 不需改（SemanticSearchService 變動會讓此 test 改 mock SkillshubPgVectorStore 或改 integration test 風格 — 視 T8 實作）|
| `backend/src/test/java/.../search/SemanticSearchPocTest.java` | **D**（T8 刪除）| SimpleVectorStore POC 已 obsolete（被 PgVectorStoreOwnerWriteTest 完整覆蓋）|
| `backend/src/test/java/.../search/SemanticSearchIntegrationTest.java` | M | T7 加 `vector-store=simple` + exclude PgVectorStoreAutoConfiguration override；T8 移除 override，改用真實 SkillshubPgVectorStore 直接 seed（valid UUID 取代字串 ID）|
| `backend/src/test/java/.../search/SearchConfigTest.java` | M | T7 移除 `firestoreVectorStore` 條件；T8 再移除 `simpleVectorStore` 相關 test（bean 已刪）— 留 EmbeddingModel-related cases |
| `backend/src/test/java/.../search/SearchProjectionTest.java` | M | T7 加 `CurrentUserProvider` mock；T8 改為注入 JdbcTemplate + EmbeddingModel + CurrentUserProvider 三 mock，驗 SkillshubPgVectorStore.builder(...) 寫入（或改 @SpringBootTest 整合驗證）|
| **新增 unit / integration tests**（4 個）| | |
| `backend/src/test/java/.../shared/persistence/MapJsonbConverterTest.java` | A | AC-2：Map ↔ JSONB 雙向 round-trip（含 nested List/Map） |
| `backend/src/test/java/.../shared/events/DomainEventSequenceUniquenessTest.java` | A | AC-3：(aggregate_id, sequence) UNIQUE 違反 |
| `backend/src/test/java/.../skill/query/AtomicDownloadCountTest.java` | A | AC-6：100 次並發 increment 結果為 100 |
| `backend/src/test/java/.../search/PgVectorStoreOwnerWriteTest.java` | A | AC-10 + AC-13：真實 PostgreSQLContainer 寫一筆 doc，斷言 6 欄全對（id, content, metadata, embedding, owner, skill_id）— T7 寫過、T8 重寫為單次 6-欄 INSERT 驗證 |

**檔案總數估算（修訂 — S015 absorbed）**：3 add (production) + 16 modify (production) + 2 modify (search prod: SearchConfig + SearchProjection) + 1 delete (FirestoreVectorStore) + 1 add (V1 migration SQL) + 4 add (test) + ~12 modify (test) + 5 modify (search test) = **~45 檔**

> 與 v1.0.0 對比：S012 是 13 檔 (XS)、S013 是 11 檔 (S)、S010 是 ~25 檔 (M)、S007 是 ~30 檔 (M)。S014 是 ~45 檔 — 落在 **L(20)** 預估範圍（含 S015 absorbed scope）。

---

## Estimation

| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | Spring Data JDBC + JSONB 成熟；PgVectorStore 兩步驟寫入 trade-off 明確（§2.4 #15 驗證） |
| Uncertainty | 2 | search() 動態 query 改寫風格已有公開範例；`MongoTemplate.updateFirst` 對應 `JdbcTemplate.update` 直接；sidecar 連線方案已 GA（Cloud Run multi-container） |
| Dependencies | 3 | 5 個新 dep（data-jdbc / spring-ai pgvector starter / flyway / postgresql + sidecar image）；移除 dep 4 個（`google-cloud-firestore` + 3 個 mongo 系列）；`spring-ai-starter-vector-store-pgvector` 為 milestone 2.0.0-M4（成熟度 80%）|
| Scope | 4 | ~45 檔（5 read model + 5 repo + 動態 query + SearchConfig/Projection + FirestoreVectorStore 刪 + 5 search test 小修 + 4 新測 + ~12 既有 test 修 + 5 yaml/build/compose）|
| Testing | 2 | 既有 100+ 測試是回歸骨幹；新增 4 個 unit/integration test；`@ServiceConnection` 整合零設定 |
| Reversibility | 3 | 一旦合併，回滾需 revert 多個 commit + Flyway baseline reset + Firestore dep 重新引入；高度耦合 |
| **Total** | **16** | **L（接近 L 上界，因 S015 absorbed scope）** |

> 因檔案數高（~45）+ Dependencies/Scope 升至 3/4 + Reversibility 維持 3，實質規模升至 **L(20)**。Roadmap 同步更新（M12 row）。

---

## 6. Task Plan

> POC: **required（已嵌入 T1）** — Map<String, Object> JSONB Converter 雙向型別保留為 hypothesis；不另開 `poc/` 目錄，因為依賴（Spring Data JDBC + Testcontainers postgres + Flyway）就是 S014 在建構的 foundation，獨立 POC 反而要重複設定。T1 的 `MapJsonbConverterTest` 為 POC + 回歸測試二合一；失敗即 halt 全部後續 task。

`/planning-tasks` 將 S014 拆 6 個 task（user 要求「拆解細一點」）— 較原 4-task 計畫多 2 個（DomainEvent 獨立、Wiring 與 Sweep 拆開），降低每個 task 的爆炸半徑、各自有獨立 verification 命令。

| # | Task | 主題 | AC 對應 | 變動檔案類別 | 依賴 | Status |
|---|------|------|--------|------------|------|--------|
| T1 | Foundation + Map Converter（POC） | build.gradle 加 PostgreSQL/Flyway/data-jdbc/spring-ai-pgvector starter（additive）+ V1 Flyway schema（6 表 + extensions + indexes，含 vector_store 加 owner/skill_id）+ `JdbcConfiguration` + `MapJsonbConverterTest` + TestcontainersConfiguration 加 pgvector container（保留 mongoContainer）+ application.yaml 加 datasource + flyway + 排除 PgVectorStoreAutoConfiguration | AC-1, AC-2 | 4 add / 4 modify | none | ✅ PASS |
| T2 | **原子化 Mongo → PostgreSQL 遷移**（合併原 T2+T3+T4） | 5 個 records @Document → @Table；5 個 MongoRepository → ListCrudRepository（含 `incrementDownloadCount` / `updateRiskAssessment` @Modifying @Query）；SkillQueryService dynamic query 改 NamedParameterJdbcTemplate；`getCategoryCounts` 改 SQL GROUP BY；SkillProjection atomic increment；ScanOrchestrator 移除 MongoTemplate；移除 spring-boot-starter-data-mongodb / -test / testcontainers-mongodb；移除 mongoContainer + compose.yaml mongodb service + application.yaml spring.mongodb；新增 DomainEventSequenceUniquenessTest + AtomicDownloadCountTest | AC-3, AC-4, AC-5, AC-6 | 14 modify production + 多檔 test | T1 | pending |
| ~~T3~~ | ~~4 個 Read Model + Repo 遷移~~ | **SUPERSEDED → T2** | — | — | — | superseded |
| ~~T4~~ | ~~動態 query + Atomic 更新~~ | **SUPERSEDED → T2** | — | — | — | superseded |
| T5 | Application config + GCP Cloud SQL Auth Proxy sidecar + 完整 vectorstore.pgvector wiring（修訂） | `application.yaml` 補完整 `spring.ai.vectorstore.pgvector.*`（initialize-schema=false + schema-validation=false + 768 dim + HNSW + COSINE + max-document-batch-size），但暫保留 `autoconfigure.exclude PgVectorStoreAutoConfiguration` 直到 T7 翻轉；`application-local.yaml` 改 JDBC URL `jdbc:postgresql://localhost:5432/...` + 移除 `firestore.enabled=false`（dep 在 T7 才會刪，但設定先清）；`application-gcp.yaml` 完全重寫為 sidecar 模式（`jdbc:postgresql://localhost:5432/...` + HikariCP pool=3 + 移除 firestore；含 service.yaml multi-container 範例註解）；確認 `compose.yaml` pgvector pg16 service（已於 T2 就位）；`test/resources/application.yaml` 完整化 | AC-8, AC-12 | 4-5 modify | T1, T2 | pending |
| T7 | **PgVectorStore takeover + FirestoreVectorStore deletion + google-cloud-firestore dep removal**（新增；S015 absorbed） | T7.1 `build.gradle.kts` 移 `com.google.cloud:google-cloud-firestore`；T7.2 `SearchConfig` 改寫（移除 `firestoreVectorStore @Bean` + `Firestore` import；`simpleVectorStore` 改顯式 `havingValue=simple`）；T7.3 `SearchProjection` 兩步驟 `add()` + `getNativeClient().update(...)` 補 owner/skill_id；T7.4 刪 `FirestoreVectorStore.java`；T7.5 `application.yaml` 移 `autoconfigure.exclude PgVectorStoreAutoConfiguration` 一行；T7.6 既有 `SemanticSearch{,Poc,Integration}Test` + `SearchConfigTest` + `SearchProjectionTest` 改用 `PgVectorStore` mock / 真實 PgVectorStore；T7.7 新增 `PgVectorStoreOwnerWriteTest` 對 AC-10 + AC-13 整合驗證 | AC-10, AC-13 | 1 delete + 2 modify (production) + 5 modify (test) + 1 add (test) + 1 modify (build) + 1 modify (yaml) | T1, T2, T5 | pending |
| T6 | Modulith boundary + 全套回歸 + smoke test（修訂） | T6.1 `grep -rn '@Document\|MongoRepository\|FirestoreVectorStore\|google-cloud-firestore' src` 應為 0；`ModularityTests` 確認 `shared/persistence` module 通過；T6.2 `./gradlew clean test` 全套測試綠；T6.3 listener-order 與三向解耦驗證；T6.4 手動抽樣 5 個 endpoint curl smoke test，**含上傳一個 skill 後 `SELECT COUNT(*) FROM vector_store > 0` + `SELECT owner, skill_id FROM vector_store WHERE skill_id=?` 不為 NULL**（AC-10 / AC-13 in-vivo evidence） | AC-7, AC-9, AC-11 | sweep（預期 0 改動） | T1, T2, T5, T7 | pending |
| T8 | **Custom PgVectorStore 子類 + per-request builder（post-QA design refinement，2026-04-27 16:00）** | T8.1 `build.gradle.kts` swap dep `spring-ai-starter-vector-store-pgvector` → `spring-ai-pgvector-store`；T8.2 新增 `SkillshubPgVectorStore.java`（extends `AbstractObservationVectorStore` + Builder + 6-欄 INSERT，§4.14）；T8.3 `SearchConfig` 刪除 `simpleVectorStore @Bean`（§4.12）；T8.4 `SearchProjection` 重構為 `SkillshubPgVectorStore.builder(...)` per-request（§4.13）；T8.5 `SemanticSearchService` 重構為 builder pattern；T8.6 `application.yaml` + `test/resources/application.yaml` 完全刪除 `spring.ai.vectorstore.pgvector.*` + `skillshub.search.vector-store`；T8.7 刪除 `SemanticSearchPocTest.java`；T8.8 重寫 `SemanticSearchIntegrationTest`（移 `vector-store=simple` override，valid UUID seed）；T8.9 `SearchProjectionTest` 注入三 mock；T8.10 `PgVectorStoreOwnerWriteTest` 重寫驗 6 欄；T8.11 `SearchConfigTest` 移 simpleVectorStore tests；T8.12 全套 + bootRun 5 endpoint smoke + 6 欄 SELECT 驗證 | AC-10, AC-11, AC-13 | 1 add (production) + 4 modify (production) + 1 delete (test) + 4 modify (test) + 1 modify (build) + 2 modify (yaml) | T7 | pending |

**執行順序（線性）**：T1 → T2 → T5 → T7 → T6 → **T8**（post-QA design refinement）

**E2E 評估**：
- T6.4 抽樣 5 個 endpoint curl + `vector_store` row assertion 為「真實 bootRun + 真實 PostgreSQL Testcontainers + 真實 Flyway migration + 真實 PgVectorStore ingest」的整合 seam 驗證 — 即 Phase 4 Step 1.5 要求的 E2E artifact verification。
- T1 的 `MapJsonbConverterTest` 是真實 PostgreSQL Testcontainers 寫入/讀回，不是 unit-level mock — 同時驗證 POC 假設與 schema 正確性。
- T7.7 `PgVectorStoreOwnerWriteTest` 為「真實 PostgreSQLContainer + spring-ai-pgvector starter wired」的兩步驟寫入驗證 — 補 AC-10 hermetic test gate（T6.4 為 manual smoke）。
- 三個 listener 解耦（SkillProjection / SearchProjection / ScanOrchestrator）由 T6.3 結合既有 `SearchProjectionTest`（含兩步驟 mock）+ `RiskAssessmentIntegrationTest`（真實 jdbc.update）覆蓋。

無需獨立 E2E task；T1 + T6 + T7 已涵蓋整合 seam。

---

<!-- Section 7 added by /planning-tasks after implementation -->

---

## 7. Implementation Results & QA Review

### 7.1 Task Status

| Task | Scope | Status | Notes |
|------|-------|--------|-------|
| T1 | Foundation + Map Converter POC | ✅ **PASS** | `MapJsonbConverterTest` 涵蓋 POC + AC-2 |
| T2 mega | 5 records / 5 repos / dynamic queries / atomic updates / Mongo 移除 | ✅ **PASS** | 119/119 tests green；涵蓋 AC-3, AC-4, AC-5, AC-6 |
| T3 | (superseded → T2) | — | merged |
| T4 | (superseded → T2) | — | merged |
| T5 | Application config + GCP Cloud SQL Auth Proxy sidecar + 完整 vectorstore.pgvector wiring（修訂） | ✅ **PASS** | bootRun 11.1s 起來、GET /api/v1/skills 回 200、6 表 + vector_store 含 owner/skill_id + HNSW + extensions 全部就位；`application-gcp.yaml` 重寫為 sidecar；計畫外修正 mock-oauth2 healthcheck（spider→GET）+ revert firestore.enabled 移除（須等 T7 dep 一起清）— 詳 T5 task file Result 段 |
| T7 | **PgVectorStore takeover + FirestoreVectorStore deletion + google-cloud-firestore dep removal**（新增；S015 absorbed） | ✅ **PASS** | 121/121 tests green；bootRun in-vivo 單一 PgVectorStore bean；上傳一個 skill 後 `SELECT owner, skill_id FROM vector_store` 回 `lab-user / <uuid>`；`google-cloud-firestore` dep + `FirestoreVectorStore.java` 全清；計畫外修正 SkillProjection `@Order(HIGHEST_PRECEDENCE)` for SkillCreatedEvent + search 模組 allowed `shared :: security` + 移 `vector-store: simple` 預設 — 詳 T7 task file Result 段 |
| T6 | Modulith boundary + 全套回歸 + 5 endpoint smoke test（修訂） | ✅ **PASS** | T6.1 grep mongo/firestore residue=0 active；T6.2 ./gradlew clean test 121/121（含 ModularityTests AC-3）；T6.4 5 endpoint manual smoke 201/200/200/200/201；vector_store COUNT=1、owner=lab-user、skill_id 非 NULL；三 listener 解耦 in-vivo（skills + vector_store + riskLevel 三表都正確寫入）— 詳 T6 task file Result 段 |
| T8 | **Custom PgVectorStore 子類 + per-request builder（post-QA design refinement）** | ✅ **PASS** | 115/115 tests green（-3 SemanticSearchPocTest -3 SemanticSearchTest -2 SearchConfig simple +1 SearchProjection +1 PgVectorStoreOwnerWrite = -6）；bootRun 10.95s 起來、上傳 skill 後 vector_store 6 欄全寫入（id/content/metadata/embedding/owner=lab-user/skill_id）；無 SimpleVectorStore 與官方 PgVectorStore log（單一自寫子類）；單次 INSERT 取代 add+UPDATE 兩步驟 — 詳 T8 task file Result 段 |

### 7.2 QA Verdict — `PASS`（再次修訂 2026-04-27 16:30 — T8 design refinement 完成後）

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | **PASS** | `./gradlew clean test` → 115 tests / 0 failures / 0 errors / 0 skipped（T8 後：移除 SemanticSearchPocTest 3 + SemanticSearchTest 3 + SearchConfig 2 個 simple tests，新增 SearchProjectionTest 1 + PgVectorStoreOwnerWriteTest 1）|
| Coverage / Integration | **SKIP**（不影響 ship） | QA strategy 標 80% JaCoCo line coverage 但 build.gradle.kts 未配置 plugin（**pre-existing 專案層級缺口**，非 S014 引入；列為 follow-up） |
| Manual verification | **PASS** | T5.5 bootRun + GET /api/v1/skills 200；T6.4 5 endpoint curl smoke 全 201/200/200/200/201；T8 bootRun + 上傳 skill 後 vector_store 6 欄完整寫入（id/content/metadata/embedding/owner=lab-user/skill_id）— bootRun in-vivo evidence |
| Testability gate | **CLEAR** | 所有 AC 都有對應的測試或 in-vivo evidence；無 testability infra 缺口 |
| Architecture refinement | **PASS** | T8 用自寫 SkillshubPgVectorStore 子類 + per-request builder 取代官方 starter + add+UPDATE 兩步驟；對齊 CLAUDE.md「Spring AI Manual Configuration」原則；消除 add→UPDATE 中間視窗 + instanceof guard + UUID.fromString workaround |

### 7.3 Per-AC Classification

| AC | Subject | Classification | Evidence |
|----|---------|----------------|----------|
| AC-1 | Flyway V1 自動建立 6 表 + extensions + HNSW | **VERIFIED** | 全部整合測試啟動皆依賴 V1 schema；T7.7 `PgVectorStoreOwnerWriteTest.vectorStoreSchemaHasOwnerAndSkillIdColumns` 顯式斷言 owner / skill_id 欄位 + HNSW `vs_emb_idx` index 存在；T6.4 in-vivo `\d+ vector_store` + `SELECT extname FROM pg_extension` 確認 vector + uuid-ossp |
| AC-2 | Map<String,Object> JSONB 雙向 round-trip 保留 nested 型別 | **VERIFIED** | `MapJsonbConverterTest.roundTrip_preservesNestedTypes` PASS |
| AC-3 | (aggregate_id, sequence) UNIQUE + replay 升冪 | **VERIFIED** | `DomainEventSequenceUniquenessTest` 3 cases PASS |
| AC-4 | Skill keyword/category 動態 query | **VERIFIED** | `SkillSearchTest` AC-1/2/3 PASS（含 LIKE escape） |
| AC-5 | getCategoryCounts | **VERIFIED** | `SkillSearchTest "AC-4: 分類列表 API"` PASS |
| AC-6 | 100 並發 atomic increment | **VERIFIED** | `AtomicDownloadCountTest.concurrentIncrement_noLostUpdate` PASS |
| AC-7 | Spring Modulith 邊界仍綠 | **VERIFIED** | `ModularityTests AC-3` PASS（含 T7 新加的 `shared :: security` allowedDependency on `search` 模組）|
| AC-8 | bootRun in local profile + GET /api/v1/skills 200 | **VERIFIED** | T5.5 + T6.4 bootRun（11.1s 起來）+ `curl GET /api/v1/skills` 回 `HTTP/1.1 200`；evidence 寫入 T5/T6 task file Result 段 |
| AC-9 | 三 listener 解耦 | **VERIFIED** | `RiskAssessmentIntegrationTest`（真實 jdbc.update）+ `SearchProjectionTest`（mock VectorStore）+ T6.4 in-vivo（skills + vector_store + risk_assessment 三表正確寫入）三向覆蓋 |
| AC-10 | Custom SkillshubPgVectorStore 接管寫入；單次 6-欄 INSERT（T8 修訂）| **VERIFIED** | T8 `PgVectorStoreOwnerWriteTest.singleInsertWritesAll6Columns` 真實 PostgreSQLContainer 驗 6 欄全寫入；`PgVectorStoreOwnerWriteTest.onConflictPreservesExistingOwnerViaCoalesce` 驗 ON CONFLICT COALESCE 防護；T8 `SearchProjectionTest` 3 個 test cases 驗 listener 整合行為；T8 bootRun in-vivo 上傳 skill 後 `SELECT id, content, metadata, embedding IS NOT NULL, owner, skill_id FROM vector_store` 6 欄完整（owner=lab-user）|
| AC-11 | 既有 100+ 測試回歸通過 | **VERIFIED** | 115/115 PASS（T8 後 net -6：移除 obsolete POC + simple tests，新增 owner-aware tests）；既有 9 個 OAuth/security 測試 + 7 個 scanner unit test 零修改通過 |
| AC-12 | GCP Cloud SQL Auth Proxy sidecar 連線可解析（修訂） | **VERIFIED** | T5.3 `application-gcp.yaml` 完全重寫為 sidecar 模式（`jdbc:postgresql://localhost:5432/...` + HikariCP pool=3 / idle=1 / leak=60000）；無 socket-factory dep；`./gradlew test` 全套含 @SpringBootTest 整合測試載入 ApplicationContext 不報錯；本機 application-local.yaml 使用同條 URL（dev/prod parity） |
| AC-13 | Firestore 完全移除 + 自寫 PgVectorStore 子類取代官方 starter（強化；T8）| **VERIFIED** | T7.1 `./gradlew dependencies \| grep firestore` = 0；T7.4 `find src -name FirestoreVectorStore*` = 0；`application-{local,gcp,test}.yaml` 無 `spring.cloud.gcp.firestore` 設定；T8 build.gradle 改 core artifact (`spring-ai-pgvector-store`，非 starter)；無官方 PgVectorStore bean wiring；自寫 SkillshubPgVectorStore 子類 per-request builder；115/115 tests PASS |

### 7.4 Findings

#### CRITICAL — All Resolved ✅

1. ~~**T5 未完成 — application-gcp.yaml 仍是 v1.0.0 firestore 設定**~~ — **RESOLVED by T5**：`application-gcp.yaml` 完全重寫為 Cloud SQL Auth Proxy sidecar；含 `jdbc:postgresql://localhost:5432/...` + HikariCP pool=3 / idle=1 / leak=60000 / connection-timeout=30000 / idle-timeout=600000 / max-lifetime=1800000；移除 firestore 設定；加 service.yaml multi-container 部署範本註解
2. ~~**T6.4 未執行 — 5 endpoint manual curl smoke test**~~ — **RESOLVED by T6.4**：bootRun（單一 PgVectorStore bean，11.1s 起來）+ 5 endpoint 全綠（201/200/200/200/201）+ vector_store row 斷言（COUNT=1、owner='lab-user'、skill_id 非 NULL）

#### IMPORTANT — All Resolved ✅

3. ~~**AC-12 措辭與決策 #9 衝突**~~ — **RESOLVED by T5（spec 與 task 同步修訂）**：AC-12 在 §3 已改寫為 sidecar 措辭（`jdbc:postgresql://localhost:5432/...` + HikariCP pool=3 + 「不」含 socketFactory / cloudSqlInstance + dev/prod parity 同條 URL）
4. ~~**AC-1 缺顯式 schema introspection 測試**~~ — **RESOLVED by T7.7**：`PgVectorStoreOwnerWriteTest.vectorStoreSchemaHasOwnerAndSkillIdColumns` 透過 `information_schema.columns` + `pg_indexes` 顯式斷言 owner / skill_id is_nullable=YES + HNSW `vs_emb_idx` 存在；T6.4 in-vivo `\d+ vector_store` + `SELECT extname FROM pg_extension` 補強

#### MINOR — Status Update

5. ~~**AC-10 缺 vector_store 行數 / owner null 斷言**~~ — **RESOLVED by T7.7 + T6.4**（兩處覆蓋）
6. ~~**`application.yaml` 用 `autoconfigure.exclude` 而非 spec §4.8 的完整設定**~~ — **RESOLVED by T5 + T7**：T5 補完整 `spring.ai.vectorstore.pgvector.*`（initialize-schema/schema-validation/dimensions/index-type/distance-type/max-document-batch-size）；T7 移除 exclude；現況：完整設定 + starter 自動 wiring
7. **JaCoCo coverage gate 缺失（pre-existing project-level gap）** — 仍為 follow-up，與 S014 範圍無關。建議獨立 spec 補 plugin + verify task。

#### NEW — discovered during T7（all addressed in-spec, no follow-up needed）

8. **SkillCreatedEvent 兩 listener 順序未定** — T7.3 啟用兩步驟寫入後，SearchProjection.updateOwnerAndSkillId 寫 vector_store.skill_id（FK 至 skills.id）但 SkillProjection.on(SkillCreatedEvent) 原本沒 @Order → 順序未定 → 21 個整合測試 FK violation。**Fix**：SkillProjection.on(SkillCreatedEvent) 加 `@Order(HIGHEST_PRECEDENCE)`，與既有 SkillVersionPublishedEvent 同模式。
9. **search 模組 → shared :: security 依賴未授權** — SearchProjection 注入 CurrentUserProvider 觸發 ModularityTests 失敗。**Fix**：search/package-info.java `allowedDependencies` 加 `"shared :: security"`。
10. **base application.yaml `vector-store: simple` 預設 + dev override 造成雙 VectorStore bean** — T7 移 exclude 後，starter 提供 PgVectorStore；同時 `vector-store: simple` 觸發 SearchConfig.simpleVectorStore @Bean → 雙 bean。**Fix**：移 application.yaml + config/application-dev.yaml 的 `vector-store: simple` 預設；測試需要 simple 時自行 override。
11. **mock-oauth2-server healthcheck `wget --spider` 不支援 HEAD（pre-existing）** — bootRun docker compose --wait 因此 fail。**Fix**：T5 順手改 `wget -q -O - ... > /dev/null`（GET）。
12. **`application-local.yaml` `firestore.enabled=false` 與 dep 移除必須同步** — T5 試圖移此設定但 dep 還在 → `GcpFirestoreAutoConfiguration` 找不到 ProjectIdProvider → bootRun fail。**Fix**：T5 revert，T7 才一起清（與 dep 同 PR）。Spec §5 / T5 task file / T7 task file 已記錄此 ordering rule。

### 7.5 Code Quality Review

✅ **Pass 區**：
- `Persistable<String>.isNew()=true` 一致用於全 5 個 record，並有清楚的 design-intent comment（解釋為何不走預設 UPDATE 路徑）
- `@Modifying @Query` helpers 全部放在 repository 介面層（`incrementDownloadCount`、`updateLatestVersion`、`updateRiskLevel`、`updateRiskAssessment`），ScanOrchestrator 不再持有 `JdbcTemplate`
- LIKE wildcard escape（`% _ \`）+ ORDER BY 白名單（`SORTABLE_PROPERTIES`）防 SQL 注入正確處理
- ObjectMapper 統一用 `tools.jackson.databind`（Jackson 3.x），與 Spring Boot 4 主 ObjectMapper 一致
- Modulith named interfaces（`shared :: persistence`、`skill :: query`）正確 declare + 由 `security` 模組 `allowedDependencies` 引用，邊界乾淨
- Mongo residual import = 0（`grep` 後僅有 6 處遷移註解，無編譯依賴）
- Comments 全部追溯回 spec / ADR 設計決策；無 stale TODO/FIXME

⚠️ **Watch（非 blocker）**：
- `SkillProjection.on(SkillVersionPublishedEvent)` 用 `@Order(HIGHEST_PRECEDENCE)` 確保在 `ScanOrchestrator(@Order LOWEST_PRECEDENCE)` 之前執行；T7 同模式加 `@Order(HIGHEST_PRECEDENCE)` 到 `on(SkillCreatedEvent)` 確保 SearchProjection.updateOwnerAndSkillId 看得到 skills row（FK skill_id 至 skills.id）。listener 順序由 `@Order` 註解 + Spring 同步 EventPublisher 保證；T6.4 in-vivo 三表都正確寫入為間接驗證，建議未來加 listener-order assertion test 強化。

✅ **T7 引入的新 pattern 驗證 PASS**：
- `SearchProjection.updateOwnerAndSkillId` 用 `instanceof PgVectorStore pgvector` guard — `SimpleVectorStore` fallback 走無 SQL 路徑跳過 UPDATE；測試用 mock `VectorStore` 也能跳過（不丟例外）— 設計乾淨
- `pgvector.<JdbcTemplate>getNativeClient().orElseThrow(...)` 顯式 type witness + 例外訊息 — 走 `Optional.orElseThrow` 而非 `.get()`，符合 Java 21+ 慣例
- `UUID.fromString(docId)` 在 UPDATE 第三 param — 與 PgVectorStore.doAdd 內部 UUID type binding 一致；避免 PostgreSQL JDBC implicit cast 風險
- `SearchConfig` 削減為「`simpleVectorStore @Bean` + EmbeddingModel beans」— 移除 firestore @Bean / Firestore import；單一職責「fallback + embedding wiring」
- `search/package-info.java` `allowedDependencies` 加 `"shared :: security"` — modulith 邊界明確 declare，無隱式跨模組依賴

### 7.6 Design-Section Sync Check

| Spec 段落 | 現況 | 動作 |
|-----------|------|------|
| §2.4 challenge #6（ScanOrchestrator 用 `jdbc.update`） | 實作改為 `versionRepo.updateRiskAssessment` @Modifying @Query（見 §4.6） | spec §4.6 已反映；§2.4 #6 句子「對應：`jdbc.update(...)`」可加備註指向 §4.6 的 repository 路徑 |
| ~~§3 AC-12（socketFactory + cloudSqlInstance）~~ | ~~決策 #9 / §2.4 #10 已明文排除~~ | ✅ **DONE**（spec §3 AC-12 已改寫為 sidecar 措辭 — 修訂 2026-04-27） |
| ~~§4.8 application.yaml（vectorstore.pgvector 完整列表）~~ | ~~實作走 autoconfigure.exclude 簡化~~ | ✅ **DONE**（T5 補完整設定 + T7 移 exclude；§4.8 現況與實作一致） |
| ~~§6 Task Plan 執行順序「T1 → T2 → T3 → T4 → T5 → T6」~~ | ~~T3/T4 已 SUPERSEDED~~ | ✅ **DONE**（spec §6 已更新為「T1 → T2 → T5 → T7 → T6」）|
| §1 圖示「過渡狀態 / 終態」 | 過渡狀態 = T2 ship 後（Mongo 走、Firestore 暫留）；終態 = T7 ship 後（Firestore 全清） | ✅ S014 終態已達；§1 圖示與實況一致 |
| §6.1（T6.1 grep mongo）| T6.1 grep 擴增至 firestore + PgVectorStoreAutoConfiguration | ✅ T6 task file Result 段已記錄；§6 task plan T6 row 已更新 |

無剩餘 design drift；§2 / §4 / §6 全部與實作對齊。

### 7.7 Required Actions to Reach `PASS` — All Done ✅

1. ✅ **T5 完成** — application-gcp.yaml sidecar / application.yaml 完整 vectorstore.pgvector / AC-12 措辭改寫
2. ✅ **T7 完成**（新增）— PgVectorStore 接管寫入 + Firestore 全清；對應 AC-10 + AC-13
3. ✅ **T6 完成** — ./gradlew clean test 121/121；bootRun + 5 endpoint smoke + vector_store row 斷言；listener-order in-vivo 驗證

### 7.8 Test Run Evidence

```
$ cd backend && ./gradlew clean test
> Task :test
BUILD SUCCESSFUL in 1m 22s
TOTAL tests=121 failures=0 errors=0 skipped=0

S014 新增 tests（4 個 unit + 1 個 integration）：
- MapJsonbConverterTest               (1 test, PASS) — AC-2
- DomainEventSequenceUniquenessTest   (3 tests, PASS) — AC-3
- AtomicDownloadCountTest             (1 test, PASS) — AC-6
- PgVectorStoreOwnerWriteTest         (2 tests, PASS) — AC-10, AC-13（T7 新增）

S014 modified tests（既有測試適配 PostgreSQL/PgVectorStore）：
- RiskAssessmentIntegrationTest       (3 tests, PASS) — AC-9
- ScanOrchestratorTest                (7 tests, PASS) — AC-1.1/1.2/1.3, AC-2.1
- SkillSearchTest                     (4 tests, PASS) — AC-4, AC-5
- SearchProjectionTest                (2 tests, PASS) — AC-3 + AC-4 search variants（T7 加 CurrentUserProvider mock）
- SemanticSearchIntegrationTest       (2 tests, PASS) — T7 加 vector-store=simple + exclude PgVectorStoreAutoConfiguration profile

ModularityTests                       (1 test, PASS) — AC-7（T7 新加 search → shared :: security allowedDep）
```

E2E artifact verification (Phase 4 Step 1.5)：
```
$ ./gradlew bootRun -x processAot      # 跳過 GraalVM AOT（pre-existing tech debt）
2026-04-27 15:39:28.486  INFO  o.s.a.v.pgvector.PgVectorStore : Using the vector table name: vector_store. Is empty: false
（單一 VectorStore bean — 無 SimpleVectorStore log）
2026-04-27 15:39:29.386  INFO  i.g.s.skillshub.SkillshubApplication : Started SkillshubApplication in 10.952 seconds

$ curl -fsSi http://localhost:8080/api/v1/skills                          → HTTP/1.1 200
$ curl POST /api/v1/skills/upload (zip + version + author + category)     → 201 + {"id":"aef3043c-..."}
$ curl GET  /api/v1/skills (list)                                         → 200 + page (latestVersion="1.0.0", riskLevel="LOW")
$ curl GET  /api/v1/skills/{id} (detail)                                  → 200 + 完整詳情
$ curl PUT  /api/v1/skills/{id}/versions (publish v1.1)                   → 200
$ curl POST /api/v1/skills/{id}/flags (QUALITY flag)                      → 201

$ docker exec backend-pgvector-1 psql -U myuser -d mydatabase \
    -c "SELECT id, owner, skill_id FROM vector_store WHERE skill_id = 'aef3043c-...';"
                  id                  |  owner   |               skill_id
--------------------------------------+----------+--------------------------------------
 aef3043c-11ab-49e1-ab6e-b93c4927a97c | lab-user | aef3043c-11ab-49e1-ab6e-b93c4927a97c
(1 row)
```

Test results saved to `backend/build/test-results/test/*.xml`（121 tests across 39 test classes，2026-04-27 timestamp）。

### 7.9 Tech Debt Registered（forward-looking）

- **`:processAot` task fail at bootRun**（GraalVM `org.graalvm.buildtools.native:0.11.5` plugin 配置；非 native build 場景不需 AOT）— 暫時 workaround `bootRun -x processAot`。獨立 spec 處理：要嘛修 AOT 配置、要嘛在 build.gradle 設定 `bootRun` 不依賴 `processAot`。建議與 OpenTelemetry observability 切換（spec §2.2 already-known follow-up）一併處理。
- **S013 部署腳本擴充** — Cloud Run multi-container `service.yaml`（main + cloud-sql-proxy sidecar）支援；目前 `gcloud run deploy` CLI flag 對 sidecar 受限，須用 `gcloud run services replace service.yaml`（spec §4.9 已含完整範本，待部署腳本實作）。
- **JaCoCo coverage gate**（pre-existing）— `qa-strategy.md` 標 80% 但 `build.gradle.kts` 無 JaCoCo plugin；建議獨立 spec 補 plugin + verify task。

### 7.10 Post-QA Design Refinement — T8（2026-04-27 16:00）

**Trigger**：T5+T7+T6 完成、QA subagent PASS 後，user review 提出 architectural refinement — 採用「自寫 PgVectorStore 子類 + per-request builder 模式」取代官方 starter 自動 wiring 的 4-欄 INSERT + add+UPDATE 兩步驟 workaround。

**Per planning-tasks Post-Verification Bug Re-Entry Protocol**：spec 狀態 `✅ Done` → `⏳ Dev (bug fix)`；新增 T8 task；不直接 hot-fix code。

**設計變動摘要**：

| 維度 | T7 ship 後現況 | T8 新設計 |
|---|---|---|
| Spring AI dep | `spring-ai-starter-vector-store-pgvector`（auto-config starter）| `spring-ai-pgvector-store`（core artifact）|
| VectorStore 來源 | starter `PgVectorStoreAutoConfiguration` 自動提供 singleton bean | 自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`；**per-request 用 `Builder` 建構，不註冊 Spring Bean**，操作完 GC |
| Owner / skill_id 寫入 | 兩步驟：`vectorStore.add(...)` 後接 `getNativeClient().update("UPDATE vector_store SET owner=?, skill_id=? WHERE id=?")` | **單次 6-欄 INSERT** — `INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id) VALUES (?, ?, ?::jsonb, ?, ?, ?)`；owner / skill_id 由 builder 注入 |
| SimpleVectorStore | 保留為 fallback（`havingValue=simple`）| **完全刪除** — dev 走 Docker Compose pgvector container（dev/prod parity），無 fallback 必要 |
| `instanceof PgVectorStore` guard | 在 `SearchProjection.updateOwnerAndSkillId` 區分 SQL/no-SQL | **不需要** — 不再有 mock VectorStore 路徑（測試用真實 SkillshubPgVectorStore + Testcontainers）|
| `UUID.fromString(docId)` workaround | 第二步 UPDATE 顯式 String→UUID 轉型 | **不需要** — INSERT 在子類內部用 `convertIdToPgType` 統一處理 |
| Observation tracing | starter 的 PgVectorStore 提供 | `AbstractObservationVectorStore` 父類提供 — **行為等同** |
| `application.yaml` `spring.ai.vectorstore.pgvector.*` 設定 | 完整列表（initialize-schema / dimensions / index-type / distance-type / ...）| **完全刪除** — 不再走 starter wiring，所有設定由 `SkillshubPgVectorStore` constructor 寫死或 builder 設定 |
| `application.yaml` `skillshub.search.vector-store` 屬性 | 預設 starter PgVectorStore，可 override 為 simple | **刪除** — 單一 backend，無切換需求 |

**改善**：
- **Atomic single round-trip**：6 欄一次寫入，無 add→UPDATE 中間視窗（之前 owner=NULL observable）
- **per-request 隔離**：每次寫入用獨立 instance 帶 owner/skillId context，無 thread-safety 顧慮、無 singleton state leak
- **無 workaround**：刪除 `instanceof` guard、`UUID.fromString` 顯式轉型、`getNativeClient().orElseThrow()` 三層
- **dev/prod parity 強化**：dev 也跑 PgVectorStore，與 production 完全一致（消除「dev 用 simple、prod 用 pgvector」的行為差異）
- **對齊 CLAUDE.md「Spring AI Manual Configuration」原則**：與 S007 `GoogleGenAiTextEmbeddingModel` 同模式 — 不依賴 auto-config，所有 wiring 由應用程式 explicit
- **Test 簡化**：`SemanticSearchIntegrationTest` 不再需要 `vector-store=simple` + exclude PgVectorStoreAutoConfiguration override；統一走真實 PostgreSQLContainer + valid UUID

**保留約束**（與 T8 設計無關）：
- `SkillProjection.on(SkillCreatedEvent)` `@Order(HIGHEST_PRECEDENCE)` 仍需要（FK `vector_store.skill_id → skills.id` 在寫入時 enforce，與單次 / 兩步驟無關）

**T8 task scope**：見 §6 Task Plan T8 row 與 task file `docs/grimo/tasks/2026-04-27-S014-T8.md`。

**回歸驗證 gate**：T5/T7/T6 既有 121 tests + 1 個新 unit test（`SkillshubPgVectorStoreSchemaTest`，可選）+ bootRun 5 endpoint smoke + `SELECT id, owner, skill_id, content FROM vector_store WHERE id = ?` 6 欄都對 → T8 PASS。

---

### 7.11 Independent QA Review — Post-T7（2026-04-27，第一輪 QA subagent）

#### Verdict: PASS

| Gate | Result | Evidence |
|------|--------|----------|
| Automated tests | **PASS** | `./gradlew clean test` — 121/0/0/0 (tests/failures/errors/skipped); verified by parsing 39 XML files in `build/test-results/test/` — counts match spec §7.8 claim |
| compileTestJava | **PASS** | `./gradlew compileTestJava` exits 0 |
| Firestore dep removal | **PASS** | `./gradlew dependencies --configuration runtimeClasspath \| grep firestore` — 0 results; build.gradle.kts line 40 comment only ("S014 T7: google-cloud-firestore dep 移除") |
| FirestoreVectorStore deleted | **PASS** | `find src/main -name "FirestoreVectorStore*"` — 0 results |
| firestoreVectorStore @Bean removed | **PASS** | `grep -rn "firestoreVectorStore" src/` — 0 results |
| @Table on all 5 read models | **PASS** | DomainEvent / SkillReadModel / SkillVersionReadModel / FlagReadModel / DownloadEventReadModel all use `@Table`; no `@Document` |
| ListCrudRepository on all 5 repos | **PASS** | All 5 repository interfaces extend `ListCrudRepository` |
| SearchProjection two-step write | **PASS** | `vectorStore.add(List.of(doc))` → `updateOwnerAndSkillId()` → `jdbc.update(UPDATE_OWNER_SKILL_ID_SQL, owner, skillId, UUID.fromString(docId))` — both steps present in `SearchProjection.java` |
| SkillProjection @Order(HIGHEST_PRECEDENCE) on SkillCreatedEvent | **PASS** | Line 52 of `SkillProjection.java` |
| search/package-info.java allowedDependencies contains "shared :: security" | **PASS** | Verified; line 4 of `search/package-info.java` |
| application.yaml no autoconfigure.exclude PgVectorStoreAutoConfiguration | **PASS** | T7 removed it; full `spring.ai.vectorstore.pgvector.*` wiring present |
| Spring Modulith boundary (ModularityTests) | **PASS** | 1 test PASS in XML results |

#### AC Coverage Matrix (S014 AC-1 through AC-13)

| AC | JUnit Coverage | Evidence |
|----|---------------|----------|
| AC-1 (Flyway V1 schema) | INDIRECT — implicit in all @SpringBootTest integration tests; schema introspection explicitly in `PgVectorStoreOwnerWriteTest.vectorStoreSchemaHasOwnerAndSkillIdColumns` (@Tag("AC-10")) | Acceptable: any failing schema = all integration tests fail |
| AC-2 (JSONB Converter) | DIRECT — `MapJsonbConverterTest` @Tag("AC-2") | 1 test PASS |
| AC-3 (sequence UNIQUE + replay) | DIRECT — `DomainEventSequenceUniquenessTest` @Tag("AC-3") | 3 tests PASS |
| AC-4 (keyword/category search) | DIRECT — `SkillSearchTest` AC-1/2/3 | 3 tests PASS |
| AC-5 (getCategoryCounts) | DIRECT — `SkillSearchTest` AC-4 | 1 test PASS |
| AC-6 (atomic download_count) | DIRECT — `AtomicDownloadCountTest` @Tag("AC-6") | 1 test PASS |
| AC-7 (Spring Modulith boundary) | DIRECT — `ModularityTests` (labeled "AC-3" per that test's own spec context) | 1 test PASS |
| AC-8 (bootRun in-vivo) | MANUAL — T5.5 + T6.4 bootRun evidence documented in §7.8 | Acceptable per §7.3 classification |
| AC-9 (listener decoupling) | INDIRECT — `RiskAssessmentIntegrationTest` + `SearchProjectionTest` + T6.4 in-vivo | Acceptable per §7.3 |
| AC-10 (PgVectorStore active) | DIRECT — `PgVectorStoreOwnerWriteTest` @Tag("AC-10") | 2 tests PASS |
| AC-11 (regression) | DIRECT — 121/121 PASS | Full suite |
| AC-12 (GCP sidecar config) | CONFIG — `application-gcp.yaml` verified: JDBC URL `jdbc:postgresql://localhost:5432/...` + HikariCP pool=3/idle=1/leak=60000; no socketFactory dep; @SpringBootTest context loads without error | Acceptable per §7.3 |
| AC-13 (Firestore removed) | DIRECT — `PgVectorStoreOwnerWriteTest` @Tag("AC-13") + dep tree verified | 1 test PASS + dep scan |

#### Findings

**CRITICAL — None**

**IMPORTANT — None**

**MINOR**

1. **Stale comments in `V1__initial_schema.sql` lines 120–127** — The SQL comments say "S014 階段 vector_store 表已建好但未啟用：SearchConfig 仍透過 @ConditionalOnProperty 走 firestore / simple" and "S015 接管時：..." and column comment `owner VARCHAR(255) -- ★ S015 寫入`. These comments were written before T7 absorbed S015 scope. The actual implementation has PgVectorStore active and FirestoreVectorStore deleted within S014. The comments are factually incorrect in the final shipped state. No impact on runtime behavior; pure documentation drift. Recommend update to reflect actual S014 final state.

2. **Spec §1 diagram and §2.3 vector_store section stale after S015 absorption** — The §1 architecture diagram still shows "過渡狀態（S014 完成）" with "FirestoreVectorStore ← S015 才取代" and §2.3 says "S014 建立此表但**不啟用**（FirestoreVectorStore 仍在用）". The §3 AC-8 scenario says "vector_store 表存在但無資料（FirestoreVectorStore 仍主導向量寫入）". All three are contradicted by the actual T7 outcome. The §7.6 claim "§1 圖示與實況一致" is partially correct (the overall intent is captured) but the diagram text itself was not updated after S015 was absorbed into S014 T7. No impact on production code correctness; pure spec documentation gap. Recommend updating §1 diagram and §2.3 to show the actual final state.

3. **`SearchProjection` uses parameterized `log.info(...)` not structured `log.atInfo().addKeyValue(...)` pattern** — `SkillProjection` and `ScanOrchestrator` use `log.atInfo().addKeyValue(key, value).log(msg)` (fluent structured log API); `SearchProjection` uses `log.info("... skillId={}", ...)` (positional placeholders). Both are valid SLF4J; the inconsistency is cosmetic. `development-standards.md` does not mandate structured logging specifically. Non-blocking.

4. **`ModularityTests` @DisplayName says "AC-3" not "AC-7"** — The test is labeled `@DisplayName("AC-3: Spring Modulith module 結構驗證")` which matches its own spec's AC-3, not S014's AC-7 (Modulith boundary). The spec §7.3 correctly cites this test for AC-7 evidence. No functional gap; label cross-referencing is from a different spec's numbering. Non-blocking.

#### Independent Verification Commands Run

```bash
# 1. Clean test run (exit code 0)
cd /Users/samzhu/workspace/github-samzhu/skills-hub/backend && ./gradlew clean test
# Result: BUILD SUCCESSFUL in 1m 12s

# 2. XML count aggregation (Python)
# TOTAL: tests=121, failures=0, errors=0, skipped=0 (39 test classes)

# 3. compileTestJava (exit code 0)
./gradlew compileTestJava

# 4. Firestore dep check
./gradlew dependencies --configuration runtimeClasspath | grep firestore
# Result: 0 lines (Firestore fully removed)

# 5. FirestoreVectorStore deletion
find src/main -name "FirestoreVectorStore*"
# Result: 0 files

# 6. firestoreVectorStore @Bean check
grep -rn "firestoreVectorStore" src/
# Result: 0 results in source files
```

#### QA Conclusion

S014 is **ready to ship** via `/shipping-release`. All 13 ACs are verified (10 by automated JUnit tests, 3 by documented manual evidence). The 4 MINOR findings are documentation drift in SQL comments and spec §1 (no production code impact) plus two cosmetic style notes. None are blocking. The 4 corrections T7 applied (Finding #8–#12 in §7.4) are confirmed present in production code. 121/121 tests pass on a fresh `./gradlew clean test` run independently verified by this QA reviewer.

> Reviewed by: independent QA subagent, 2026-04-27

---

### 7.12 T8 Re-Verification — Independent QA Review（2026-04-27，第二輪 QA subagent）

#### Verdict: PASS

| Gate | Result | Evidence |
|------|--------|----------|
| Automated tests | **PASS** | `./gradlew test --rerun-tasks` — **115/0/0/0** (tests/failures/errors/skipped); verified by parsing all 37 XML files in `build/test-results/test/` — sum matches spec §7.1 T8 claim. |
| compileTestJava | **PASS** | Compilation succeeds as part of the above test run (no separate invocation needed). |
| Build artifact — core not starter | **PASS** | `build.gradle.kts` line 36: `implementation("org.springframework.ai:spring-ai-pgvector-store")`. No `spring-ai-starter-vector-store-pgvector` anywhere in the file. `./gradlew dependencies --configuration runtimeClasspath` shows `org.springframework.ai:spring-ai-pgvector-store -> 2.0.0-M4` — no starter auto-config pulled in. |
| Firestore dep fully absent | **PASS** | `./gradlew dependencies --configuration runtimeClasspath \| grep firestore` — 0 results. Only comment at build.gradle.kts line 41. |
| SkillshubPgVectorStore structure | **PASS** | File exists at `src/main/java/.../search/SkillshubPgVectorStore.java`; `extends AbstractObservationVectorStore`; `Builder` inner class with `owner(String)` and `skillId(String)` chainable methods both present. |
| 6-column INSERT SQL | **PASS** | `INSERT_SQL` (line 80) contains `(id, content, metadata, embedding, owner, skill_id)` with `?::uuid`, `?::jsonb`, `ON CONFLICT (id) DO UPDATE`, and `COALESCE(EXCLUDED.owner, vector_store.owner)` + `COALESCE(EXCLUDED.skill_id, vector_store.skill_id)`. |
| SearchConfig — no VectorStore @Bean | **PASS** | Only two `@Bean` annotations in `SearchConfig.java` (lines 60, 80), both for `EmbeddingModel`. No `VectorStore`, no `simpleVectorStore`, no `pgvectorVectorStore`, no `@ConditionalOnProperty` on vector-store. |
| SearchProjection constructor | **PASS** | Constructor takes `(JdbcTemplate, EmbeddingModel, CurrentUserProvider)` — no `VectorStore` field. Listener methods use `SkillshubPgVectorStore.builder(...)` per-request. |
| SemanticSearchService constructor | **PASS** | Constructor takes `(JdbcTemplate, EmbeddingModel)` — no `VectorStore` field. `search()` uses `SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel).build().similaritySearch(...)`. |
| application.yaml — no pgvector config | **PASS** | No `spring.ai.vectorstore.pgvector.*` block. No `skillshub.search.vector-store` property in value. A comment at line 44 explains the omission (T8 rationale). |
| test/resources/application.yaml — clean | **PASS** | No `spring.ai.vectorstore.pgvector.*`. `spring.ai.model.*` blocks remain to gate GoogleGenAi auto-config (correct). |
| SkillProjection @Order on SkillCreatedEvent | **PASS** | `SkillProjection.java` line 52: `@Order(Ordered.HIGHEST_PRECEDENCE)` confirmed on `on(SkillCreatedEvent)`. |
| search/package-info.java allowedDependencies | **PASS** | Contains `"shared", "shared :: security", "skill :: domain"` — `"shared :: security"` present. |
| compose.yaml mock-oauth2 healthcheck | **PASS** | `wget -q -O - http://localhost:8080/skills-hub-dev/.well-known/openid-configuration > /dev/null` confirmed — GET not spider (HEAD). |
| application-local.yaml — no firestore.enabled | **PASS** | File contains only PostgreSQL + docker-compose + GCP storage:false + autoconfigure.exclude entries. Comment on line 38 explains `firestore.enabled` was removed with the dep in T7. |
| bootRun smoke test | **PASS** | `./gradlew bootRun -x processAot`: "Started SkillshubApplication in 10.828 seconds". No PgVectorStoreAutoConfiguration log (starter not on classpath). `SearchConfig` log line shows `GoogleGenAiTextEmbeddingModel` in Manual Config mode (or NoOp if no API key). |
| 6-column in-vivo verification | **PASS** | POST `/api/v1/skills/upload` → 201 + `{"id":"80155a57-39c6-4a6e-8040-01cd174c6cc5"}`. `docker exec backend-pgvector-1 psql -U myuser -d mydatabase -c "SELECT id, content, has_metadata, has_embedding, owner, skill_id FROM vector_store WHERE skill_id='80155a57-...';"` → **1 row, all non-NULL, owner=lab-user** (LAB mode default). |
| Score formula (1 - distance) | **PASS** | `DocumentRowMapper.mapRow()` line 267: `.score(1.0 - distance)` — cosine distance 0..2, score 1 = identical. |
| Thread safety | **PASS** | No static mutable owner/skillId fields. `private final @Nullable String owner` and `private final @Nullable String skillId` are instance-final, set only in constructor from Builder. Each `builder(...).build()` produces an isolated instance. |
| COALESCE correctness | **PASS** | `onConflictPreservesExistingOwnerViaCoalesce` test: first write with `owner="first-owner"`, second write omitting `.owner(...)` (null) → asserts `content="v2"` (updated) AND `owner="first-owner"` (preserved). SQL `COALESCE(EXCLUDED.owner, vector_store.owner)` correctly returns `vector_store.owner` when `EXCLUDED.owner` is null. |
| Per-request isolation proof | **PASS** | `onSkillCreated_multipleSkillsHaveIndependentOwnerState` test calls `onSkillCreated` twice with different `currentUserProvider.userId()` mock returns; asserts distinct owners on distinct rows. The test changes the mock mid-test — if SearchProjection had a singleton owner field captured at startup, the second write would still use the first owner value. The mock switch + distinct assertion proves per-request re-evaluation. |
| Duplicate §7.10 heading | **NOTED** | Spec has two `### 7.10` headings (Post-QA Design Refinement note + prior Independent QA Review). Not a production code issue but a spec formatting defect. Addressed by this review being numbered §7.11. |

#### AC Coverage Matrix — T8 Specific Verification

| AC | T8 Status | Key Evidence |
|----|-----------|-------------|
| AC-10 (6-column single INSERT) | **VERIFIED** | `PgVectorStoreOwnerWriteTest.singleInsertWritesAll6Columns` PASS; bootRun in-vivo 6-column SELECT confirmed. INSERT_SQL is atomic — no intermediate owner=NULL window. |
| AC-11 (regression) | **VERIFIED** | 115/115 PASS. Net -6 from prior 121: removed SemanticSearchPocTest (3), SemanticSearchTest (3), SearchConfigTest simpleVectorStore (2); added SearchProjectionTest (1), PgVectorStoreOwnerWriteTest (1). All previously green tests remain green. |
| AC-13 (no starter, no firestore) | **VERIFIED** | `spring-ai-pgvector-store` core artifact confirmed; `spring-ai-starter-vector-store-pgvector` absent from build.gradle and dependency tree; firestore dep = 0 at runtime. |

#### Findings — T8

**CRITICAL — None**

**IMPORTANT — None**

**MINOR**

1. **`SkillshubProperties.Search.vectorStore` field is a dead field post-T8** — `SkillshubProperties.Search` record still has `@DefaultValue("simple") String vectorStore` (line 51) with Javadoc mentioning "simple（記憶體，本機開發）或 pgvector". No production code reads `.vectorStore()` anymore — `SearchConfig` only calls `props.genai()`. The field is present in `SearchConfigTest` line 39 as a constructor argument (`"simple"`) but it's a test-only prop object that doesn't influence any production bean. No runtime impact; pure dead code / Javadoc drift. Recommend removing the field and updating the `Search` record Javadoc in a future cleanup.

2. **`V1__initial_schema.sql` stale comments (carried over from prior QA MINOR #1)** — Lines 3, 120–127, 133 reference "S015 才接管寫入", "SearchConfig 仍透過 @ConditionalOnProperty 走 firestore / simple", and `-- ★ S015 寫入；S016 用於 ACL 授權`. These describe the T7-pre-ship state. The actual outcome (S015 absorbed into S014 T7+T8) is not reflected in the SQL comments. No runtime impact. Still unresolved from prior QA review — T8 did not fix it. Recommend updating in a future cleanup or dedicated docs task.

3. **`SearchProjection` uses `log.info(format, args)` while `SkillProjection` uses structured `log.atInfo().addKeyValue(key, val).log(msg)` (carried over from prior QA MINOR #3)** — Still present after T8. The two INFO log statements in `SearchProjection` (lines 68 and 84) use positional-placeholder style. Not a functional gap; `development-standards.md` does not mandate structured logging exclusively. Non-blocking.

4. **Spec has duplicate `### 7.10` headings** — Section 7.10 appears twice: once for "Post-QA Design Refinement — T8" and once for the prior "Independent QA Review". This is a doc authoring artifact from the T8 redesign workflow (the refinement note was added at the same heading number as the review). No production impact. This review uses §7.11 to avoid further collision. Recommend renumbering the prior review to §7.10b or §7.11 in a cleanup pass.

#### Comparison with Prior QA Review (§7.10)

| Dimension | Prior QA (T7, 121 tests) | T8 Re-Verification (115 tests) |
|-----------|--------------------------|--------------------------------|
| Test count | 121 / 0 failures | 115 / 0 failures |
| Vector write | Two-step: `add()` + `UPDATE owner/skill_id` | **Single 6-column INSERT** — atomic, no owner=NULL window |
| VectorStore bean | Official `PgVectorStore` singleton (auto-config starter) | **No singleton** — per-request `SkillshubPgVectorStore` via builder |
| Config complexity | `spring.ai.vectorstore.pgvector.*` (7 yaml keys) | **Zero yaml keys** — all wiring in constructor |
| Workarounds | `instanceof PgVectorStore` guard + `UUID.fromString` + `getNativeClient().orElseThrow()` | **All eliminated** |
| Dev/prod parity | Dev could use `simple` (in-memory) fallback | **No fallback** — dev always runs pgvector |
| MINOR findings | 4 (SQL comment drift, §1 diagram drift, log style, ModularityTests label) | 4 (dead field, SQL comments persist, log style persists, duplicate §7.10) |
| New improvements | — | Atomic write, per-request isolation, cleaner code, stronger AC-10 test |
| New regressions | — | None |

#### Conclusion

S014 with T8 is **ready to ship** via `/shipping-release`. The architectural refinement achieves its stated goals: atomic single-round-trip 6-column INSERT, zero singleton state, no workarounds, dev/prod parity. All 13 ACs remain verified (10 by automated tests, 3 by documented manual evidence). The 4 MINOR findings are documentation drift and cosmetic style — none are blocking. Regression suite is intact (115/115). bootRun in-vivo confirms the 6-column write path end-to-end.

> Reviewed by: independent QA subagent (T8 re-verification), 2026-04-27

---

### 7.13 T8 第三輪驗證 — `/verifying-quality` skill 主驗（2026-04-27 17:24）

#### Verdict: **PASS**

獨立執行的 QA 主驗（非 subagent；走 `/verifying-quality` skill 完整 9-step 流程，含 boundary-condition E2E 驗證）。

#### Layer 結果

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | **PASS** | `./gradlew clean test` → BUILD SUCCESSFUL in 1m 32s；XML aggregation 確認 `tests=115 failures=0 errors=0 skipped=0`（37 test classes）|
| compileTestJava | **PASS** | `./gradlew compileTestJava` exit 0 |
| Modulith boundary | **PASS** | `./gradlew test --tests "*ModularityTests*"` PASS（含 T7 加的 `search → shared :: security` allowed dep）|
| Coverage / JaCoCo | **SKIP**（pre-existing project gap） | `./gradlew jacocoTestCoverageVerification` → BUILD FAILED（task 不存在）；qa-strategy.md 第 18-21 行宣告 80% 線覆蓋率目標但 build.gradle.kts 無 JaCoCo plugin。**非 S014 引入；列為 IMPORTANT 但 project-level 範圍**（建議獨立 spec 補 plugin + verify task）|
| Manual / E2E gate | **PASS** | 獨立 hermetic bootRun 啟動（10.96s）+ POST upload（CJK + escape quote 邊界）+ 6-欄 SELECT 驗證 |
| Testability gate | **CLEAR** | 13 個 AC 全部有自動化測試 / in-vivo evidence（詳 §7.3） |

#### E2E hermetic verification（boundary scenario）

刻意設計**非 happy path** 邊界輸入：CJK 中文 + 反斜線 + 雙引號 escape。

```bash
# 1. Reset compose 環境
docker compose down --remove-orphans
pgrep -f SkillshubApplication | xargs -r kill -9

# 2. bootRun（隔離 process；GraalVM AOT 跳過為 pre-existing tech debt workaround）
./gradlew bootRun -x processAot
# → Started SkillshubApplication in 10.961 seconds

# 3. boundary-condition zip：SKILL.md 含 CJK + escape quote
# zip content: name=qa-edge-skill / description="QA boundary 含中文與特殊字元 \"quote\""
curl -X POST http://localhost:8080/api/v1/skills/upload \
  -F "file=@/tmp/qa-edge.zip" -F "version=1.0.0" -F "author=qa-tester" -F "category=Testing"
# → {"id":"184fcbb0-621e-483e-8cc8-d196e99275fa"}

# 4. 6-欄 SELECT + jsonb_pretty + embedding dim count
docker exec backend-pgvector-1 psql -U myuser -d mydatabase -c \
  "SELECT id, content, jsonb_pretty(metadata::jsonb), \
   array_length(string_to_array(embedding::text, ','), 1) AS embedding_dims, \
   owner, skill_id FROM vector_store WHERE skill_id = '184fcbb0-...';"
# Result:
#   id            = 184fcbb0-621e-483e-8cc8-d196e99275fa
#   content       = "qa-edge-skill QA boundary 含中文與特殊字元 \"quote\""  ← CJK + escape 保留
#   metadata      = JSON 含 description "QA boundary 含中文與特殊字元 \\\"quote\\\""  ← double-escape 正確
#   embedding_dims = 768  ← 真實 Gemini embedding（non-zero vector）
#   owner         = lab-user  ← CurrentUserProvider LAB 模式預設
#   skill_id      = 184fcbb0-...  ← FK 至 skills.id 非 NULL
```

#### Schema introspection

```sql
SELECT column_name, data_type, is_nullable FROM information_schema.columns
 WHERE table_schema='public' AND table_name='vector_store' ORDER BY ordinal_position;
-- 6 columns: id (uuid, NOT NULL) / content (text) / metadata (json) /
--            embedding (USER-DEFINED, pgvector type) / owner (varchar) / skill_id (varchar)

SELECT indexname, indexdef FROM pg_indexes WHERE tablename='vector_store';
-- vector_store_pkey (btree id) / vs_emb_idx (hnsw embedding vector_cosine_ops) / idx_vector_store_owner (btree owner)

SELECT extname FROM pg_extension;
-- plpgsql / uuid-ossp / vector
```

#### T8 design integrity verification

| Check | Result |
|-------|--------|
| `build.gradle.kts` 用 `spring-ai-pgvector-store`（core）而非 starter | ✅ confirmed line 36 |
| `spring-ai-starter-vector-store-pgvector` absent in build | ✅ 0 active hits（只有 T8 註釋）|
| `FirestoreVectorStore.java` 已刪 | ✅ file 不存在 |
| `google-cloud-firestore` dep 已移 | ✅ 0 active hits |
| `SearchConfig` 無 `VectorStore @Bean` | ✅ confirmed — 只有 EmbeddingModel beans |
| `SearchProjection` constructor 注入 (JdbcTemplate, EmbeddingModel, CurrentUserProvider) | ✅ confirmed lines 51-60 |
| `SearchProjection` 用 per-request `SkillshubPgVectorStore.builder(...)` | ✅ confirmed lines 79-83, 96-100 |
| `SemanticSearchService` 改 builder pattern | ✅ confirmed |
| `SkillshubPgVectorStore.INSERT_SQL` 6 欄 + `ON CONFLICT DO UPDATE COALESCE` | ✅ confirmed lines 78-89 |
| `SkillProjection.on(SkillCreatedEvent)` `@Order(HIGHEST_PRECEDENCE)` 仍在 | ✅ confirmed |
| `search/package-info.java` `allowedDependencies` 含 `"shared :: security"` | ✅ confirmed |
| `application.yaml` 無 `spring.ai.vectorstore.pgvector.*` 設定 | ✅ 0 hits |
| `application.yaml` 無 `skillshub.search.vector-store` 屬性 | ✅ 0 hits |
| `mock-oauth2-server` healthcheck T5 fix（GET 取代 spider）保留 | ✅ confirmed `wget -q -O - ... > /dev/null` |

#### Findings

**CRITICAL — None**

**IMPORTANT**

1. **Pre-existing project gap：JaCoCo coverage tooling 缺失** — `qa-strategy.md` 宣告 80% 線覆蓋率 PR gate（line 19-21），但 `build.gradle.kts` 無 JaCoCo plugin；`./gradlew jacocoTestCoverageVerification` 直接 fail with task-not-registered。**非 S014 引入**（自 S001 起持續）；亦於 §7.4 #7（pre-existing project-level gap）已記錄。建議獨立 spec 補 JaCoCo plugin + verify task + 註冊到 verification command registry。**不阻擋 S014 ship**（與 S014 ACs 無直接關係）。

2. **Pre-existing project gap：無 verification command registry / 自動化 verify-all script** — `/verifying-quality` skill Step 0.5 protocol 期望專案有結構化 registry table + executable script；目前無。每輪 QA review 須手動從 qa-strategy.md + build.gradle 推導命令。建議獨立 spec 建立 `scripts/verify-all.sh` + 把 `./gradlew test` / `compileTestJava` / `jacocoTestCoverageVerification` / `ModularityTests` 等命令編入 registry。**不阻擋 S014 ship**。

**MINOR**

3. **§7 標題重複編號**（已於本輪修正）— 原本 `### 7.10` 出現兩次（design refinement + 第一輪 QA review）；已重編為 7.10 / 7.11 / 7.12，本段為 7.13。
4. **`SearchProjection` 用 `log.info("...{}", ...)` positional placeholders，與 `SkillProjection` / `ScanOrchestrator` 的 `log.atInfo().addKeyValue(...)` fluent API 風格不一**（前 QA 已記錄，T8 未順手改）。development-standards.md 未強制；非 blocker。
5. **`SkillshubProperties.Search.vectorStore` field 為 dead code**（前 QA 已指出）— T8 後無 production consumer；可獨立 cleanup spec 移除。
6. **`V1__initial_schema.sql` 第 120-127 stale 註解**（前 QA 已指出兩輪）— 仍未清。

#### Comparison with prior 兩輪 QA reviews

| 維度 | §7.11（T7 QA） | §7.12（T8 QA） | §7.13（本輪）|
|------|---------------|----------------|--------------|
| Verdict | PASS | PASS | PASS |
| Test count | 121 | 115 | 115（相同）|
| E2E gate triggered | 隱式（task file evidence）| 隱式 | **顯式**（boundary CJK + escape quote 獨立執行）|
| Coverage gate evaluated | No | No | **Yes**（`./gradlew jacocoTestCoverageVerification` 嘗試 → IMPORTANT finding）|
| Verification registry check | No | No | **Yes**（registry 不存在 → IMPORTANT finding）|
| MINOR count | 4 | 4 | 4 + 2 IMPORTANT pre-existing |
| Per-request isolation 證據 | n/a | mock-based | 真實 PostgreSQL + multiple distinct UUID + owner state isolation tests PASS |

本輪相對前兩輪的增益：**明確觸發 E2E gate** + **明確檢查 coverage / registry tooling 的 project-level gaps**（兩個 IMPORTANT 升級至 explicit findings；前兩輪僅在 §7.4 / §7.9 列為 follow-up）。

#### Conclusion

S014 with T8 是 **ready to ship** via `/shipping-release`。三輪獨立 QA 一致 PASS verdict；CRITICAL = 0；IMPORTANT 兩項皆為 pre-existing project-level gaps（JaCoCo + verification script），不阻擋 S014；4 個 MINOR 為文件 drift / cosmetic。

T8 architectural refinement 達成所有設計目標（atomic 6-欄 INSERT / per-request isolation / 對齊 Manual Configuration 原則 / dev/prod parity / 消除 workaround），無 regression。

> Reviewed by: `/verifying-quality` skill 主驗，2026-04-27 17:24（第三輪 QA）
