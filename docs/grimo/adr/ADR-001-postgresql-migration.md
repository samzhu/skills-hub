# ADR-001: 儲存層從 Firestore Enterprise 遷往 PostgreSQL + pgvector

> Status: **Accepted** (2026-04-27)
> Supersedes: PRD Decision Log D8, D9, D14
> Triggered by: S014 規劃階段研究 — Row-Level ACL × 向量搜尋整合需求
> Research: `docs/deepwiki/spring-acl-pgvector/`

---

## 1. Context

Skills Hub MVP（v1.0.0，14 specs / 147 points）已於 2026-04-27 上線，採用以下儲存決策：

| 原決策 | 選擇 | 載入時的理由 |
|--------|------|-------------|
| **D8** | Firestore Enterprise（MongoDB driver） | GCP 原生、免維運、MongoDB wire protocol 相容、彈性 schema |
| **D9** | Spring AI + Gemini embedding + Firestore 原生向量搜尋（`findNearest()`） | Spring AI 已在模板、GCP 生態整合、~$1-2/月最低成本 |
| **D14** | 混合：MongoDB driver（CRUD）+ 原生 SDK（向量） | `findNearest()` 不在 MongoDB wire protocol，必須混用 |

這些決策在 MVP 階段（無多使用者、無權限）成立。但進入 Backlog **B1（權限控制）+ B7（組織層級）+ B8（軟結構）**規劃時觸到結構性瓶頸。

---

## 2. Decision

**全面遷移儲存層到 PostgreSQL：**

- **CRUD / Read Model**：Spring Data MongoDB → **Spring Data JDBC + PostgreSQL**
- **Event Store**：Firestore `domain_events` collection → **PostgreSQL `domain_events` 表**
- **向量搜尋**：自訂 `FirestoreVectorStore`（基於 Firestore `findNearest()`）→ **Spring AI 官方 `PgVectorStore`**（透過 `spring-ai-starter-vector-store-pgvector` starter 引入），**Schema 自訂**（含 `owner` 欄位，因為 row-level ACL 需要紀錄擁有者；setting `spring.ai.vectorstore.pgvector.initialize-schema=false`）

採用 Spring AI 官方 starter 而非自訂實作，原因見 §4.2。Schema 不交由 starter auto-create — 由本 ADR 範圍內的 V1 migration SQL 統一建立（S014 處理）。

---

## 3. Drivers — 為什麼非遷不可

### 3.1 Firestore array-contains-any 的 30 元素硬上限（首要驅動因子）

行級權限控制（ACL）的核心查詢模式是：

> 「使用者隸屬於 N 個 group / org / dept / team；查出所有 ACL entry 至少匹配一個的 row」

PostgreSQL JSONB 用 `?|` 運算子搭配 GIN(`jsonb_path_ops`) 索引，**任意數量 patterns 都是一次 SQL**，無上限。

Firestore 的對應運算子是 `array-contains-any`，[官方明文上限 30 個元素](https://firebase.google.com/docs/firestore/query-data/queries#array-contains-any)。

**衝擊範圍**：

| 使用者規模 | groups + orgs + depts 平均數 | 展開讀取 patterns | Firestore 是否可行 |
|-----------|-----------------------------|------------------|-------------------|
| 個人開發者 | 0–3 | 1–4 | 可 |
| 部門級 | 5–10 | 6–11 | 可 |
| 跨組織協作（戰情室、合作專案） | 15–25 | 16–26 | **逼近上限** |
| 企業級多公司多戰情室 | 30+ | 31+ | **不可行** |

PRD Backlog **B7（組織層級管理：集團 → 公司 → 部門）+ B8（軟結構：戰情室 / 合作專案）** 明確要求企業級多層次組織模型，Firestore 路線會在 B7/B8 的最終形態觸頂。

### 3.2 Vector Search × ACL 的整合表達力

Firestore `findNearest()` 支援 `where()` prefilter，但：
- 官方 Java SDK 文件僅明確示範 `==` 等值與 `and`/`or` composite filter
- `array-contains-any` × `findNearest()` 組合**未在官方文件示範**（hypothesis 級，需 POC 驗證才能採用）
- 即使可行，仍受 §3.1 的 30 元素天花板

PostgreSQL + pgvector 的對應寫法是教科書級成熟：

```sql
SELECT id, content, 1 - (embedding <=> :emb) AS similarity
  FROM vector_store
 WHERE acl_entries ?| CAST(:patterns AS text[])    -- GIN 過濾
 ORDER BY embedding <=> :emb                       -- HNSW 排序
 LIMIT :topK
```

一條 SQL 同時做 ACL 過濾 + 向量近鄰搜尋；planner 自動選 GIN + HNSW；patterns 數量無上限。

### 3.3 既有架構複雜度（次要驅動）

D14 的混合策略（MongoDB driver + Firestore native SDK）讓專案同時維護兩條 Firestore 連線、兩套 Type Converter、兩種錯誤處理。遷往 PostgreSQL 後**單一連線、單一驅動**，整體心智負擔下降。

### 3.4 開發本機體驗

PostgreSQL Testcontainers 在所有 OS 上一致；既有 QA strategy（`docs/grimo/qa-strategy.md`）已宣告 Testcontainers 為主流測試手段。Firestore Emulator 在 macOS 啟動較慢、CI 上偶有 flakiness。

---

## 4. Alternatives Considered

### 4.1 Alternative A：留在 Firestore + array-contains-any

- 優：零架構衝擊、既有 v1.0.0 不動
- 劣：30 元素天花板是**硬性限制**，企業級需求不可行（§3.1）
- 結論：**否決** — 這是觸發本 ADR 的唯一阻塞點

### 4.2 Alternative B：自訂 `PgVectorStore extends AbstractObservationVectorStore`

- 原本初稿建議自訂實作以對齊 S007 `FirestoreVectorStore` pattern
- **改採 Spring AI 官方 `spring-ai-starter-vector-store-pgvector` starter** —
  - 官方實作已涵蓋 HNSW / IVFFlat / NONE 三種索引、cosine / euclidean / inner product 三種距離、batch ingest、observation tracing
  - Schema 控制權保留：`spring.ai.vectorstore.pgvector.initialize-schema=false` + 自寫 V1 migration SQL，把 `owner` 欄位（row-level ACL 必要）與其他自訂欄位一次建好
  - ACL filter 透過 `PgVectorStore.getNativeClient()` 取 `JdbcTemplate` 寫自訂 SQL（同一 query 同時做 GIN 過濾 + HNSW 排序）— 這是 S017 的設計核心
  - 自訂實作會與官方版本長期維護分歧（HNSW 算法細節、observation API 升版）
- 結論：**採用官方 starter + 自訂 schema** — 維護成本最低、行為可預測

### 4.3 Alternative C：PostgreSQL Row-Level Security（RLS）作為主授權層

- 優：DB 強制執行、應用層侵入低
- 劣：
  - 與 Skills Hub ES + CQRS 寫入順序耦合（ApplicationEventPublisher 觸發 projection 需要明確的 user context；`SET LOCAL app.user_id` 需在 transaction 內維護）
  - Spring Modulith `@EventListener` 同步觸發跨 transaction，session 變數傳播脆弱
  - JSONB ACL 已能達成同等效果，且應用層行為可預測
- 結論：**否決為主授權層**，但保留為**未來加固層**選項（若需 DB 強制執行，可加 RLS policy 做縱深防禦）

---

## 5. Migration Plan — Spec 拆分

| Spec | 主題 | 規模 | 核心輸出 | 依賴 |
|------|------|------|---------|------|
| **S014** | PostgreSQL 資料層遷移（無 ACL） | M–L | Spring Data JDBC + 4 read models（`skills` / `skill_versions` / `flags` / `download_events`） + `domain_events` 表 + Testcontainers Postgres；功能等同 v1.0.0 | ADR-001 |
| **S015** | 引入 Spring AI `PgVectorStore` 並接管向量寫入路徑 | S–M | 透過 `spring-ai-starter-vector-store-pgvector` 啟用官方 `PgVectorStore` bean；`SearchConfig` 加 `vector-store=pgvector` 分支；`SearchProjection` 把 ingest 從 `FirestoreVectorStore` 切到 `PgVectorStore`（`metadata` 含 `owner`、`skillId`）；移除 `FirestoreVectorStore` 與 `google-cloud-firestore` 依賴 | S014 |
| **S016** | Row-Level ACL 基礎建設 | M | `acl_entries JSONB` + GIN(`jsonb_path_ops`) 索引、`PermissionEvaluator` Strategy/Registry、`CurrentUser` 擴充 groups/orgs/depts、`@PreAuthorize` 套上 `SkillCommandService`、`SkillAclGranted/Revoked` events、ACL CRUD API | S015 |
| **S017** | ACL-Aware 語意搜尋 | S–M | `vector_store` 表加 `acl_entries`；`PgVectorStore.doSimilaritySearch` 擴充 ACL SQL composition；composite query 一次完成 GIN filter + HNSW 排序 | S016 |

每個 spec 落在 M 範圍內；總計四個 spec 處理整段遷移 + ACL 功能。

---

## 6. Consequences

### 6.1 正面後果

- ACL row-level 控制無 patterns 數量上限，支援企業級多層次組織模型
- 向量搜尋與 ACL 過濾用同一條 SQL（GIN + HNSW），效能可預測
- 儲存層單一驅動（Spring Data JDBC），心智負擔下降
- Testcontainers 一致化測試體驗
- 為 Backlog B1/B7/B8 + S010 後續安全升級項打開技術空間

### 6.2 負面後果

- **架構決策反轉**：D8/D9/D14 全部 superseded，PRD 決策日誌需更新
- **功能凍結期**：S014/S015 純技術遷移，期間不增加任何使用者可見功能
- **既有測試重寫**：~100+ 個 MongoTemplate / `@Document` 測試需改 `@JdbcTest` / Spring Data JDBC（不過 S014 T1 證實多數測試只要 `@DataMongoTest` → `@DataJdbcTest` 一行替換）
- **遷移風險**：專案尚未上正式生產（CLAUDE.md 明文「Feature First, Security Later」），無使用者資料風險；DEV / STAGING 採取「乾淨啟動」策略 — drop database → V1 Flyway migration，重新上傳即可
- **GCP 部署成本上升**：Cloud Run + Cloud SQL（PostgreSQL）比 Firestore 月費高
  - Cloud SQL **Enterprise edition + PostgreSQL 18 + db-f1-micro**（shared core、1 vCPU / 0.614 GB RAM）— GCP 最小規格，dev/staging 夠用
  - 含 VPC Connector 月費；粗估從 v1.0.0 的 $1–2/月升至 $15–25/月
  - 上 production 時需評估升級到 dedicated core（如 db-custom-2-7680 或 Enterprise Plus 機型）
- **db-f1-micro `max_connections = 25` 限制**：HikariCP `maximum-pool-size = 3` 是必要設計（25 - 5 reserved = 20 available；÷ Cloud Run 假設 5 instances = 4/instance；保留 buffer 取 3）。Cloud Run 端理論上限 100 connections/DB（[Cloud SQL quotas](https://docs.cloud.google.com/sql/docs/quotas)）— 受 DB 端 25 上限主導。
- **Cloud Run + Cloud SQL 整合**：原 D10 + S013 部署腳本需更新 — 採 **Private IP + VPC Connector**（不用 Cloud SQL Java Connector / postgres-socket-factory，避免 spring-cloud-gcp 額外配置；S014 T1 階段已驗證移除 socketFactory 後 application context 啟動順暢）
- **pgvector extension 啟用**：Cloud SQL 須在 instance 建立時配置 `cloudsql.enable_pgvector = on`（PostgreSQL 18 支援；S014 V1 migration 含 `CREATE EXTENSION IF NOT EXISTS vector`，但 instance flag 必須先在 GCP 端啟用）

### 6.3 不變的事

- Spring Boot 4.0.6、Java 25、Gradle 9.4.1、Spring Modulith 2.0.6 — 不變
- Spring AI 2.0.0-M4 + Gemini embedding — 不變（換的是 vector store 實作而非 embedding model）
- ES + CQRS 架構模式（決策 D20–D23）— 不變
- 部署模型（Cloud Run + 單一 container；決策 D10）— 不變
- Frontend stack（React 19 + Vite 8 + shadcn/ui）— 完全不變
- agentskills.io SKILL.md 標準（D2）— 不變

---

## 7. Superseded Decisions（PRD Decision Log）

| 原 # | 原決策 | 新狀態 |
|------|--------|--------|
| D8 | 資料庫 = Firestore Enterprise（MongoDB driver） | **Superseded by ADR-001**：改 PostgreSQL + Spring Data JDBC |
| D9 | 語意搜尋 = Spring AI + Firestore 原生向量搜尋（`findNearest()`） | **Superseded by ADR-001**：改 pgvector + 自訂 `PgVectorStore` |
| D14 | Firestore 存取方式 = MongoDB driver（CRUD）+ 原生 SDK（向量） | **Superseded by ADR-001**：單一 PostgreSQL 連線 |

PRD 決策日誌更新時保留原條目並加註 `Superseded by ADR-001 (2026-04-27)`，遷移完成後移到 PRD 末段「Historical Decisions」段落。

---

## 8. References

> 重要：本 ADR 自包含。本節列出外部 URL 來源；任何研究結論若對本 ADR 的決策有支撐，已**內聯於 §3 Drivers 與 §4 Alternatives**，不依賴外部目錄留存。

**權威外部來源**：
- [Spring AI PgVector reference (2.0)](https://docs.spring.io/spring-ai/reference/2.0/api/vectordbs/pgvector.html) — `spring-ai-starter-vector-store-pgvector` 用法、`initialize-schema` 行為、HNSW/IVFFlat 索引選項
- [PostgreSQL JSONB Indexing](https://www.postgresql.org/docs/current/datatype-json.html#JSON-INDEXING) — `jsonb_path_ops` GIN index（S016 用）
- [Firestore Vector Search docs](https://firebase.google.com/docs/firestore/vector-search) — `findNearest()` API；本 ADR 的 §3.2 引用其 `array-contains-any` 30 元素限制反證
- [samzhu/spring-acl-jsonb](https://github.com/samzhu/spring-acl-jsonb) — ACL JSONB pattern 公開範例（`type:principal:permission` + `?\|` GIN 查詢；S016 範本）
- [Implementing RLS in Vector DBs (Hannecke)](https://medium.com/@michael.hannecke/implementing-row-level-security-in-vector-dbs-for-rag-applications-fdbccb63d464) — RLS × pgvector pattern（本 ADR §4.3 引用）
- [Supabase RAG with Permissions](https://supabase.com/docs/guides/ai/rag-with-permissions) — RLS in vector store

**既有 codebase 錨點**（git 永久留存）：
- `backend/src/main/java/.../search/FirestoreVectorStore.java` — 既有 VectorStore 實作，S015 取代為 Spring AI 官方版
- `backend/src/main/java/.../shared/security/CurrentUserProvider.java` — S012 user 抽象，S016 擴充 groups/orgs/depts
- `backend/src/main/java/.../skill/query/SkillReadModel.java` — read model 範本，S014 改用 Spring Data JDBC 注解

**研究產物說明**：
規劃 S014–S017 期間在 `docs/deepwiki/spring-acl-pgvector/` 建立的研究筆記為**臨時產物**，**不保證留存**。所有對 ADR-001 載重決策有貢獻的結論已內聯本文 §3/§4。
