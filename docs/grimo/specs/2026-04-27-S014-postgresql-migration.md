# S014: PostgreSQL 資料層遷移 + pgvector schema 建立

> Spec: S014 | Size: M-L(15) | Status: ⏳ Design
> Date: 2026-04-27
> Depends: ADR-001（PostgreSQL 路線決策） — `docs/grimo/adr/ADR-001-postgresql-migration.md`
> Blocks: S015（PgVectorStore 接管向量寫入）→ S016（Row-Level ACL 基礎建設）→ S017（ACL-Aware 搜尋）

> **本 spec 自包含**：所有載重設計決策的研究結論已內聯於 §2.4 / §2.5；不依賴 `docs/deepwiki/` 路徑留存（該目錄為規劃期臨時研究產物）。

---

## 1. Goal

把 Skills Hub 的 **CRUD 讀模型 + Event Store** 從 Firestore Enterprise（MongoDB driver）遷到 PostgreSQL（Spring Data JDBC + 官方 `spring-ai-starter-vector-store-pgvector`），**業務行為等同 v1.0.0，零使用者可見變動**。同步建立含 `owner` 欄位的 **vector_store 表**（S015 才使用，S014 先把 schema 立起來）。

**簡單講**：把 4 個 `@Document` read model + 1 個 event store 換成 PostgreSQL `@Table` + Flyway V1 migration（同檔含 vector_store 表 + pgvector extension），既有 `FirestoreVectorStore` 在本 spec 期間繼續運作，由 S015 接手。所有既有測試（37 個檔案 / 100+ 個方法）在 Testcontainers PostgreSQL 全綠就算成功。

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

### 2.1 關鍵設計決策（共 11 項）

| # | 決策 | 選擇 | 理由 | 否決的替代 |
|---|------|------|------|-----------|
| 1 | 資料存取技術 | **Spring Data JDBC 為主、`NamedParameterJdbcTemplate` 為輔**（`spring-boot-starter-data-jdbc`） | Skill CRUD 全走 Spring Data JDBC：`@Table` record + `ListCrudRepository` 處理 90% 操作（findById / save / findAll / derived query / `@Modifying @Query` UPDATE）；**`NamedParameterJdbcTemplate` 僅用於唯一一處真正動態 SQL — `SkillQueryService.search(keyword, category, pageable)` 因有 optional filters + 動態排序，無法用固定 `@Query` 表達** | JPA/Hibernate（重）、純 raw `JdbcTemplate`（5 個 read model 各要寫 RowMapper + 自家 SQL，工程量倍增） |
| 2 | 向量儲存 starter | **`spring-ai-starter-vector-store-pgvector`**（官方 starter） | 官方版實作已涵蓋 HNSW/IVFFlat、cosine/euclidean、batch ingest、observation tracing；維護成本低；S015/S017 可透過 `getNativeClient()` 注入自訂 SQL 達成 ACL filter | 自訂 `PgVectorStore extends AbstractObservationVectorStore`（與官方版長期維護分歧；HNSW 算法細節重現負擔） |
| 3 | Vector store schema 控制 | **`spring.ai.vectorstore.pgvector.initialize-schema=false` + 自寫 V1 SQL** | 必須在 vector_store 加 `owner` 欄位（row-level ACL 必要；S016 開始用）；starter auto-init 不支援自訂欄位 | 讓 starter auto-init 後再 `ALTER TABLE ADD COLUMN owner`（多一次 migration；schema drift 風險） |
| 4 | Schema 遷移工具 | **Flyway**（`spring-boot-starter-flyway` + `flyway-database-postgresql`） | Spring Boot 4 自動配置；S016/S017 加欄位 / index 用 V2/V3 增量；社群成熟 | Liquibase（YAML 學習曲線）、`schema.sql` built-in（無版本管理；不利後續增量） |
| 5 | JSONB Map 持久化 | **自訂 Jackson 雙向 Converter**（`Map<String, Object> ↔ JSONB`） | Spring Data JDBC `userConverters()` SPI 標準路徑；ObjectMapper 已是 Spring Boot bean 可注入 | 第三方庫（額外依賴）、序列化成 String 存 TEXT（失去 JSONB 查詢能力） |
| 6 | id 欄位型別 | **`VARCHAR(36)` 存 UUID 字串**（含連字號形式） | 既有 read model `String id` 維持不變；不需要任何 type converter；遷移風險最低；vector_store 仍用 `UUID` 型別（Spring AI starter 預設） | PostgreSQL 原生 `UUID` 型別於業務表（要加 UUID converter；無實質效益） |
| 7 | 連線池 | **HikariCP**（Spring Boot 4 預設）— GCP `maximum-pool-size: 3` / `minimum-idle: 1`；本機 dev 寬鬆設 10 / 2 | 0 設定；業界標準。**db-f1-micro `max_connections = 25` 約束**：[Cloud SQL flags 文件](https://docs.cloud.google.com/sql/docs/postgres/flags)列出 ~0.5 GB RAM 機型預設 max=25。預算公式：`25 - 5 reserved (Cloud SQL admin/monitoring) = 20 available ÷ Cloud Run max instances (假設 5) = 4/instance → 取 3 留 buffer`。Cloud Run 端理論上限 100 connections/DB（[Cloud SQL quotas](https://docs.cloud.google.com/sql/docs/quotas)）— 受 DB 端 25 主導 | 其他 pool（Spring Boot 4 已不預設） |
| 8 | 本機 Docker Compose image | **`pgvector/pgvector:pg16`** | S015 開始用 vector extension；本 spec 已建立 vector_store 表；用 pgvector 鏡像可避免 S015 再換鏡像；pg16 是 pgvector 官方目前主推 LTS | `postgres:17-alpine`（S015 還要再換）、`pgvector/pgvector:pg17`（S014 階段尚無壓力測試） |
| 9 | GCP 部署連線 | **Private IP + Direct VPC egress / Serverless VPC Access connector**（標準 JDBC URL `jdbc:postgresql://PRIVATE_IP:5432/db`） | 官方文件最推薦做法；無公網暴露；無代理開銷（最低延遲）；不需要 `postgres-socket-factory` 依賴（避免 `spring-cloud-gcp-autoconfigure.CloudSqlEnvironmentPostProcessor` 啟動驗證 — S014 T1 已實證移除後 context 啟動順暢） | Cloud SQL Java Connector + `socketFactory`（多一個依賴 + spring-cloud-gcp post-processor 干擾）、Cloud SQL Auth Proxy sidecar（legacy；多 process）、Public IP（安全風險） |
| 10 | MongoDB driver 處置 | **本 spec 一次移除 `spring-boot-starter-data-mongodb` + `testcontainers-mongodb`** | S014 完成後 Mongo 已無人使用；保留會混淆 import / build cache；`google-cloud-firestore` 暫留（FirestoreVectorStore 還在用，S015 才移） | 留到 S015 才一併移（混淆現況；多輪 PR review） |
| 11 | Aggregation pipeline 遷移 | **MongoDB `Aggregation.group()` → 純 SQL `GROUP BY`** | 既有唯一案例 `getCategoryCounts()` 是 simple `SELECT category, COUNT(*) GROUP BY category ORDER BY count DESC` | Spring Data JDBC `@Query` annotation（其實就是 native SQL，沒省什麼） |

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
| **VectorStore SPI** | `SearchConfig` 用 `@ConditionalOnProperty` 切換 `simple` / `firestore` | 加第三條件 `pgvector` 但 **S014 不啟用**（auto-config bean 因 `@ConditionalOnMissingBean(VectorStore.class)` 不生效）；`vector-store=firestore` 維持，由 S015 切換 |
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

10. **GCP Cloud SQL 部署規格 + 連線方式 + Secrets？**
    - **規格**：Cloud SQL **Enterprise edition** + **PostgreSQL 18** + **db-f1-micro**（shared core / 1 vCPU / 0.614 GB RAM）— GCP 最小規格、dev/staging 夠用；上 production 需評估升級
    - **連線**：Cloud SQL **Private IP**（10.x.x.x）+ Cloud Run 配置 **Direct VPC egress** 或 **Serverless VPC Access connector**（資源在同 VPC）
    - JDBC URL 格式：`jdbc:postgresql://${SKILLSHUB_DB_HOST}:5432/${SKILLSHUB_DB_NAME}` — 標準格式，**不需要** `postgres-socket-factory` 依賴
    - 部署 CLI 範例：`gcloud run services update SERVICE --vpc-connector=... --vpc-egress=private-ranges-only`
    - **Secrets**：延續 S013 Secret Manager 模式 — GCP profile 從 env var 讀 `SKILLSHUB_DB_HOST` / `SKILLSHUB_DB_USER` / `SKILLSHUB_DB_PASSWORD` / `SKILLSHUB_DB_NAME`
    - **本機**：固定 `myuser:secret`（pgvector container 啟動時 `POSTGRES_USER` / `POSTGRES_PASSWORD` 環境變數設定）
    - 安全性：Private IP 流量限制在 VPC 網路內、**完全無公網暴露**；對應 [Configure private IP](https://docs.cloud.google.com/sql/docs/postgres/configure-private-ip) + [Connect Cloud Run to Cloud SQL](https://docs.cloud.google.com/sql/docs/postgres/connect-run) 官方文件

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

Scenario: AC-10 — vector_store 表已建好但 S014 期間未啟用
  Given S014 完成、`spring.ai.vectorstore.pgvector.initialize-schema=false`
        + `spring.ai.vectorstore.pgvector.schema-validation=false`
  When ApplicationContext 啟動
  Then PgVectorStore auto-config bean 不存在於 ApplicationContext
       （因 SearchConfig 已顯式 @Bean 滿足 @ConditionalOnMissingBean）
  And `vector-store=firestore`（local profile 預設）下 SearchProjection 寫入仍走 FirestoreVectorStore
  And vector_store 表 SELECT COUNT(*) = 0（S015 接管後才有資料）
  And vector_store 表的多餘欄位（owner / skill_id）存在但全為 NULL

Scenario: AC-11 — 既有 SBE acceptance（S001–S013）回歸通過
  Given S014 完成、./gradlew test
  When 跑全套既有測試
  Then 既有 100+ 測試全綠（業務行為等同 v1.0.0）
  And 新增 3 個 unit test 也綠

Scenario: AC-12 — Cloud SQL 部署 yaml 解析正確
  Given application-gcp.yaml 含 Cloud SQL Java Connector 連線設定
  When @SpringBootTest 用 gcp profile（mock Cloud SQL 環境變數）載入
  Then ApplicationContext 啟動不報錯
  And HikariDataSource 連線屬性含 socketFactory + cloudSqlInstance
```

---

## 4. Interface / API Design

### 4.1 build.gradle.kts 依賴變動

```diff
 dependencies {
     implementation("org.springframework.boot:spring-boot-starter-actuator")
-    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
+    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
     implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
     implementation("org.springframework.boot:spring-boot-starter-validation")
     implementation("org.springframework.boot:spring-boot-starter-webmvc")
     implementation("com.google.cloud:google-cloud-firestore:3.31.6")
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
+    implementation("org.flywaydb:flyway-core")
+    runtimeOnly("org.flywaydb:flyway-database-postgresql")
+    runtimeOnly("org.postgresql:postgresql")
+    // 不引入 com.google.cloud.sql:postgres-socket-factory（決策 #9 改採 Private IP + VPC Connector）
     // ... 其他不動 ...
-    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
+    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
-    testImplementation("org.testcontainers:testcontainers-mongodb")
+    testImplementation("org.testcontainers:testcontainers-postgresql")
 }
```

> 說明：`spring-ai-starter-vector-store-pgvector` 會 transitively 帶入 `spring-boot-starter-jdbc` + `spring-ai-pgvector-store`；本 spec 額外明寫 `spring-boot-starter-data-jdbc`（提供 `@Table` / `CrudRepository` SPI）+ `flyway` + `org.postgresql:postgresql`（明確 runtime dep）+ Cloud SQL connector（GCP 部署）。

> `spring-ai-advisors-vector-store` **本 spec 不引入** — 為 ChatClient RAG advisor 用，Skills Hub 目前 semantic search 不走 ChatClient。將來若有 LLM judge / RAG 擴展再加。

> `google-cloud-firestore` + `spring-cloud-gcp-starter-storage` **本 spec 暫保留** — FirestoreVectorStore 仍在 search/ 模組使用；S015 才移除。

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

> Database / username / password 對齊 user 提供的 docker compose template (`mydatabase` / `myuser` / `secret`)。GCP profile 用 Cloud SQL connector 覆寫。

### 4.9 application-gcp.yaml 變動

```yaml
spring:
  datasource:
    # Cloud SQL Enterprise + PostgreSQL 18 + db-f1-micro（0.614 GB RAM, max_connections=25）
    # Private IP（10.x.x.x）+ Cloud Run 配置 Direct VPC egress
    # 標準 JDBC URL；無 socketFactory；流量限制在 VPC 內（無公網暴露）
    url: jdbc:postgresql://${SKILLSHUB_DB_HOST}:5432/${SKILLSHUB_DB_NAME:skillshub}
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
```

> **DB 規格**：Cloud SQL **Enterprise edition** + **PostgreSQL 18** + **db-f1-micro**（shared core, 1 vCPU / 0.614 GB RAM）。
>
> **建立 instance**（屬部署腳本範圍，本 spec 不涵蓋；S013 GCP 部署模板需擴充）：
> ```bash
> # 1. 建立 Cloud SQL instance（含啟用 pgvector flag）
> gcloud sql instances create skillshub-db \
>   --database-version=POSTGRES_18 \
>   --edition=enterprise \
>   --tier=db-f1-micro \
>   --region=$REGION \
>   --network=projects/$PROJECT_ID/global/networks/default \
>   --no-assign-ip \
>   --database-flags=cloudsql.enable_pgvector=on
>
> # 2. 建立 db + user（migration 自動跑 V1）
> gcloud sql databases create skillshub --instance=skillshub-db
> gcloud sql users create skillshub-user --instance=skillshub-db --password=...
>
> # 3. Cloud Run 配置 VPC connector + Private IP 連線
> gcloud run services update skillshub \
>   --vpc-connector=projects/$PROJECT_ID/locations/$REGION/connectors/$CONNECTOR_NAME \
>   --vpc-egress=private-ranges-only \
>   --update-env-vars=SKILLSHUB_DB_HOST=10.x.x.x,SKILLSHUB_DB_NAME=skillshub
> ```
>
> Secrets（`SKILLSHUB_DB_USER` / `SKILLSHUB_DB_PASSWORD`）由 Secret Manager 注入（延續 S013 模式）。
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

---

## 5. File Plan

| 檔案 | 動作 | 說明 |
|------|------|------|
| **Schema migration** | | |
| `backend/src/main/resources/db/migration/V1__initial_schema.sql` | A | pgvector + uuid-ossp extensions + 6 張表完整 SQL（§2.3） |
| **Build & config** | | |
| `backend/build.gradle.kts` | M | `spring-boot-starter-data-mongodb` → `data-jdbc` + flyway + postgresql + Cloud SQL connector + spring-ai pgvector starter；testcontainers swap |
| `backend/src/main/resources/application.yaml` | M | 移除 mongodb、加 datasource + flyway + spring.ai.vectorstore.pgvector 設定 |
| `backend/src/main/resources/application-local.yaml` | M | 移除 firestore.enabled=false（不再相關） |
| `backend/src/main/resources/application-gcp.yaml` | M | Cloud SQL connector 連線設定 |
| `backend/compose.yaml` | M | mongodb service → `pgvector/pgvector:pg16`（含 service-connection label + healthcheck） |
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
| **新增 unit tests**（3 個）| | |
| `backend/src/test/java/.../shared/persistence/MapJsonbConverterTest.java` | A | AC-2：Map ↔ JSONB 雙向 round-trip（含 nested List/Map） |
| `backend/src/test/java/.../shared/events/DomainEventSequenceUniquenessTest.java` | A | AC-3：(aggregate_id, sequence) UNIQUE 違反 |
| `backend/src/test/java/.../skill/query/AtomicDownloadCountTest.java` | A | AC-6：100 次並發 increment 結果為 100 |

**檔案總數估算：3 add (production code + module-info) + 16 modify (production) + 1 add (V1 migration SQL) + 3 add (test) + ~12 modify (test) = 35 檔**

> 與 v1.0.0 對比：S012 是 13 檔 (XS)、S013 是 11 檔 (S)、S010 是 ~25 檔 (M)、S007 是 ~30 檔 (M)。S014 是 35 檔 — 落在 **M-L(15)** 預估範圍。

---

## Estimation

| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | Spring Data JDBC + JSONB 是成熟技術；唯一 hypothesis 是 Map<String, Object> Converter 的型別保留；POC 容易（unit test 級別） |
| Uncertainty | 2 | search() 動態 query 改寫風格已有公開範例；`MongoTemplate.updateFirst` 對應 `JdbcTemplate.update` 直接 |
| Dependencies | 2 | 5 個新 dep（spring-data-jdbc / spring-ai pgvector starter / flyway / postgresql / Cloud SQL connector）；其中 spring-ai pgvector starter 為 BOM-managed 但 2.0.0-M4 是 milestone（成熟度 80%） |
| Scope | 3 | 35 檔（5 read model + 5 repo + 動態 query + 約 12 個測試小修 + 6 個新檔） |
| Testing | 2 | 既有 100+ 測試是回歸驗證骨幹；只新增 3 個 unit test；Spring Boot `@ServiceConnection` Testcontainers 整合零設定 |
| Reversibility | 3 | 一旦合併，回滾需 revert 多個 commit + Flyway baseline reset；高度耦合 |
| **Total** | **14** | **M（接近 M-L 邊界）** |

> 因檔案數高（35）+ Reversibility 高（3），實質規模偏向 M-L(15)。Roadmap 已標 M-L(15) 反映此實況。

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
| T5 | Application config + Docker compose + pgvector wiring | application.yaml 完整 vectorstore.pgvector 設定（initialize-schema=false + schema-validation=false + 768 dim + HNSW + COSINE + max-document-batch-size）；application-local.yaml / application-gcp.yaml（Cloud SQL Enterprise + PG18 + db-f1-micro Private IP + pool size 3）；compose.yaml 確認 pgvector pg16 service（mongodb 已於 T2 移除）；test/resources/application.yaml 完整化 | AC-8, AC-10, AC-12 | 4-5 modify | T1, T2 | pending |
| T6 | Modulith boundary + 全套回歸 + smoke test | `grep` 殘留 mongo import 應為 0（T2 已清）；`ModularityTests` 確認 `shared/persistence` module 通過；./gradlew clean test 全套 100+ 測試綠；手動抽樣 5 個 endpoint curl smoke test | AC-7, AC-9, AC-11 | sweep（預期 0 改動） | T1, T2, T5 | pending |

**執行順序（線性）**：T1 → T2 → T3 → T4 → T5 → T6

**E2E 評估**：
- T6.4 抽樣 5 個 endpoint curl 為「真實 bootRun + 真實 PostgreSQL Testcontainers + 真實 Flyway migration」的整合 seam 驗證 — 即 Phase 4 Step 1.5 要求的 E2E artifact verification。
- T1 的 `MapJsonbConverterTest` 是真實 PostgreSQL Testcontainers 寫入/讀回，不是 unit-level mock — 同時驗證 POC 假設與 schema 正確性。
- 三個 listener 解耦（SkillProjection / SearchProjection / ScanOrchestrator）由 T6.3 結合既有 SearchProjectionTest（mock VectorStore）+ RiskAssessmentIntegrationTest（真實 jdbc.update）覆蓋。

無需獨立 E2E task；T1 + T6 已涵蓋整合 seam。

---

<!-- Section 7 added by /planning-tasks after implementation -->
