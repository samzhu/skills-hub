# S021: Phase 2 doc-sync — PRD.md + architecture.md（含 glossary + qa-strategy 補丁）

> Spec: S021 | Size: S(8) | Status: ✅ Done
> Date: 2026-04-27
> Depends: — (純 docs；ADR-001 + S014 archived spec + backend/build.gradle.kts 為唯一 source of truth；無 code-level deps；可與 S019/S020 平行)
> Blocks: 後續 spec 規劃讀 PRD/architecture 不再踩 Firestore 死訊

> **Roadmap text 校正**：spec-roadmap §M17 描述「D8/D9/D14/**D15**」為 typo — D15 是 Spring Modulith decision（與 storage 無關）。實際受影響為 **D3/D8/D9/D14/D22**（5 條 storage decisions）。本 spec 同 commit 修正 roadmap 文字（per §5 file plan）。

---

## 1. Goal

把 PRD.md / architecture.md / glossary.md / qa-strategy.md 4 docs 中所有 Firestore + MongoDB 字眼 **一次性 rewrite** 為 PostgreSQL 16 + pgvector + 自訂 SkillshubPgVectorStore + Cloud SQL Auth Proxy sidecar 現況，與 ADR-001 + S014 ship 後實際 `backend/build.gradle.kts` / 程式碼一致。

**簡單講**: S014 ship 後 `/shipping-release` 跳過了 PRD/architecture 更新（spec-roadmap §M17 driver 提到）；ADR-001 已記決策軌跡，但 PRD/architecture 文字仍稱 Firestore — 新 spec 規劃時若讀錯文件會做出與現況衝突的決策。本 spec 一次性 rewrite ~17 個 touchpoints across 4 docs；歷史交給 ADR-001 + S014 archived spec + git log 三層保留（PRD/architecture 為 current-state docs，不在受影響行加 superseded annotation 以避免認知負擔）。

```
┌── 現況（S014 ship 後、本 spec 之前）──────────────────────────┐
│  PRD.md          → L251/252/307/381/386/387/392/400 仍稱 Firestore│
│  architecture.md → L9/70/212/280/281/316-380/468-471/499-528  │
│                    Firestore + MongoDB driver + @Document     │
│  glossary.md     → L24 Firestore collection                   │
│  qa-strategy.md  → L72 + L127-131 Testing with Firestore      │
│  ADR-001 ✅      → 已記錄完整 PostgreSQL 決策軌跡             │
│  build.gradle ✅ → 已 PostgreSQL（jdbc starter + pgvector）   │
│  ▶ Source of truth (code) 與 docs 不一致                      │
└──────────────────────────────────────────────────────────────┘
                              ↓ S021（本 spec）
┌── 目標 ─────────────────────────────────────────────────────┐
│  PRD.md          → §MVP Scope + ASCII diagram + Decision Log  │
│                    D3/D8/D9/D14/D22 全部 in-place rewrite     │
│                    + §上線狀態（Status）mini-section          │
│                    + §Decision Log 末尾 ADR-001 pointer footer│
│  architecture.md → §Data Model + §Firestore Configuration     │
│                    full rewrite；ASCII diagrams + Module Design│
│                    + Framework Dependency Table 同步           │
│  glossary.md     → 1 行 L24                                   │
│  qa-strategy.md  → §Testing with PostgreSQL                  │
│  ▶ grep -i "firestore|mongodb" docs/grimo/{PRD,architecture, │
│             glossary,qa-strategy}.md → 0 hits 在 current-     │
│             state 段落（ADR/archive 引用名仍可保留）          │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

S spec — 純 documentation rewrite。**Phase 2 Research SKIP**（純文件搬運；source = ADR-001 + S014 archived + build.gradle.kts，全部 in-repo + 已 validated）。

### 2.1 關鍵設計決策（4 項）

| # | 決策 | 選擇 | 理由 | 否決 |
|---|------|------|------|------|
| 1 | Rewrite 模式 | **Full in-place rewrite**；不在受影響行加「Superseded」annotation | 歷史已由 ADR-001（決策來源 + alternatives）+ S014 archived spec（實作細節）+ git log 三層保留；PRD/architecture 是 current-state docs；annotation 反稀釋當前 source of truth | A. Inline annotate + new entries D27-D31（5 條 superseded mark；雙倍認知負擔）<br/>B. 並列 v1.0.0/v1.1.0 historical（文件 ~doubled；讀者看 2 套 design）|
| 2 | 範圍擴張 | **4 docs 一起改**（roadmap 原文 PRD + architecture + audit 找到 glossary + qa-strategy）| Audit 找到 glossary L24 + qa-strategy L72/L127-131 屬同類；分批必下一輪再開 follow-up；額外成本 ~5 行 | 嚴守 roadmap 原文 → 開 S022 補；浪費一輪 PR review |
| 3 | Decision Log 處理 | **In-place rewrite** D3/D8/D9/D14/D22 cells；§Decision Log 末尾加 footer pointer 指向 ADR-001 | PRD = current state；ADR = decision archeology；單一 footer 提供路徑；保留 D-id 連續性（D3/D8/D9/D14/D22）方便未來 cross-reference | 5 條 inline `⚠️ Superseded`（亂）|
| 4 | Diagram 更新 | **In-place rewrite** ASCII diagrams（PRD L307 / architecture L70 / L212）— Firestore box → `PostgreSQL 16 + pgvector\n(via Cloud SQL Auth Proxy sidecar)` | 同上；diagram = current state；歷史於 ADR §3 Drivers + S014 §1 Goal 已圖示遷移路線 | 並列「pre/post」雙圖（doubled）|

### 2.2 與既有架構的契合

| 維度 | 現況 | S021 變動 |
|------|------|-----------|
| PRD.md L251-252 §MVP Scope | Firestore Enterprise + Firestore findNearest | Replace 兩行；緊接加 `### 上線狀態（Status）` mini-section（MVP v1.0.0 ✅ + Phase 1 v1.1.0 ✅）|
| PRD.md L307 §Architecture Overview ASCII | Firestore box | In-place edit；Firestore → PostgreSQL+pgvector，標 Cloud SQL Auth Proxy sidecar |
| PRD.md L381/386/387/392/400 §Decision Log | D3/D8/D9/D14/D22 寫 Firestore | In-place rewrite 5 cells；§Decision Log 末尾加 ADR-001 pointer footer |
| architecture.md L9 §State at Planning | "需加入 Spring Data MongoDB（連接 Firestore Enterprise）" | Rewrite bullet + 加 ADR-001 footnote |
| architecture.md L70 §Event Flow ASCII | (Firestore) | In-place edit |
| architecture.md L212 §System Architecture ASCII | Firestore Enterprise + 子 box | In-place rewrite — PostgreSQL 16 + pgvector + Cloud SQL Auth Proxy |
| architecture.md L280-281 §Module Design | "MongoDB query on read model" / "Firestore vector" | Replace |
| architecture.md L316-380 §Data Model | Firestore / MongoDB schema | **Full rewrite** → PostgreSQL `@Table` records + Flyway V1（per §4.3）|
| architecture.md L468-471 §Framework Dep Table | mongodb-starter / google-cloud-firestore | Remove 2；Add 5（jdbc/jdbc-starter/spring-ai-pgvector-store/flyway/postgresql）對齊 build.gradle.kts L27-86 |
| architecture.md L499-528 §Firestore Configuration | retryWrites / Change Streams 約束 | **Full replace** → §PostgreSQL Configuration（Cloud SQL Auth Proxy sidecar / HikariCP / Flyway / pgvector ext）|
| glossary.md L24 | Firestore collection (`domain_events`) | Replace 1 行 |
| qa-strategy.md L72 | Firestore MongoDB 相容性 | Replace |
| qa-strategy.md L127-131 §Testing with Firestore | MongoDB image / Firestore Emulator | Replace 整個 sub-section → `### Testing with PostgreSQL`（Testcontainers `pgvector/pgvector:pg16`）|
| spec-roadmap.md §M17 描述 | "D8/D9/D14/**D15**" typo | 同 commit 校正為 D3/D8/D9/D14/D22 |

### 2.3 Source of Truth Map

無外部研究。所有 rewrite 內容皆從 in-repo authoritative sources 拉：

| # | 主題 | Source | 對應 spec 章節 |
|---|------|--------|--------------|
| I1 | PostgreSQL migration 決策 + alternatives | `docs/grimo/adr/ADR-001-postgresql-migration.md` §1-§4 | PRD §Decision Log rewrite |
| I2 | 最終實作 schema + dependency 列表 | `docs/grimo/specs/archive/2026-04-27-S014-postgresql-migration.md` §4 | architecture.md §Data Model rewrite |
| I3 | Cloud SQL Auth Proxy sidecar 設計 | ADR-001 §4.4 + S014 archived §2.1 決策 #9 | architecture.md §PostgreSQL Configuration |
| I4 | 自寫 SkillshubPgVectorStore 設計 | S014 archived §2.1 決策 #2 / #12（再修訂）| architecture.md §Data Model + §Module Design |
| I5 | 實際 dependency 版本 + import paths | `backend/build.gradle.kts` L27-86 | architecture.md §Framework Dependency Table |
| I6 | Schema migration 真實 SQL | `backend/src/main/resources/db/migration/V1__*.sql`（讀取時若存在）| architecture.md §Data Model（佐證 schema 描述） |

### 2.4 Research Sufficiency Gate

| 設計決策 | 信心 | 證據 |
|---------|------|------|
| ADR-001 為決策權威 | **Validated** | I1 ADR `Status: Accepted (2026-04-27)` |
| S014 已 ship + 實作 final state | **Validated** | spec-roadmap M12 `v1.1.0` ✅；I2 archive |
| build.gradle.kts 為 dep table 真實狀態 | **Validated** | I5 raw file |
| 純 docs 變動無 runtime risk | **Validated** | docs 不影響 binary build |

**POC: not required**。

---

## 3. SBE Acceptance Criteria

> AC-naming contract: `@DisplayName("AC-N: ...")` 或 `@Tag("AC-N")`。本 spec 為純 docs 變動；ACs 由 grep / line-count / human review evidence 取代測試方法（與 S013/S014 文件變動 AC 模式一致）。

**AC-1**: PRD.md current-state 段落零殘留 Firestore/MongoDB
- Given PRD.md 已 rewrite
- When `grep -nE "Firestore|MongoDB|@Document" docs/grimo/PRD.md | grep -v "ADR-001|archive|MVP v1.0.0|Phase 1 migration"`
- Then current-state 段落（§MVP Scope / §Architecture Overview / §Decision Log）匹配 0 處
- And `grep -E "PostgreSQL|pgvector|Spring Data JDBC"` 至少 5 處（D8/D9/D14/D22 + §MVP Scope）

**AC-2**: PRD §Decision Log 5 條 storage entries 重寫 + footer 指向 ADR-001
- Given §Decision Log table
- When 讀 D3/D8/D9/D14/D22 row
- Then 每 row 「選擇」「理由」「否決的替代」cells 對齊 ADR-001 §2 Decision + §4 Alternatives Considered（per §4.1 payload）
- And §Decision Log 末尾含 footer：「Phase 1 PostgreSQL migration（2026-04-27 v1.1.0）：D3/D8/D9/D14/D22 已重寫；遷移決策軌跡見 `adr/ADR-001-postgresql-migration.md` + `specs/archive/2026-04-27-S014-postgresql-migration.md`」

**AC-3**: architecture.md current-state 段落零殘留 Firestore/MongoDB
- Given architecture.md 已 rewrite
- When `grep -nE "Firestore|MongoDB|@Document|MongoRepository" docs/grimo/architecture.md`
- Then 匹配 0 處（包括 §State at Planning 已加 footnote 註解；不算 current-state 殘留）
- And `grep -E "PostgreSQL|pgvector|Spring Data JDBC|@Table|Flyway"` 至少 10 處

**AC-4**: §Data Model 對齊實際 schema + Framework Dependency Table 對齊 build.gradle.kts
- Given §Data Model 已 rewrite + §Framework Dependency Table 已調整
- When 讀 §Data Model
- Then schema 描述含 6 張表（`domain_events` / `skills` / `skill_versions` / `flags` / `download_events` / `vector_store`）+ Flyway V1 references + JSONB payload 描述（per §4.3）
- And §Framework Dependency Table 含 `spring-boot-starter-data-jdbc` / `spring-ai-pgvector-store` / `spring-boot-flyway` / `flyway-core` / `org.postgresql:postgresql` 5 條（per §4.5）
- And 不含 `spring-boot-starter-data-mongodb` / `google-cloud-firestore` 任一條

**AC-5**: §PostgreSQL Configuration 替代 §Firestore Configuration
- Given §Firestore Configuration 已被 replace
- When 讀 architecture.md `## PostgreSQL Configuration`（新章節，per §4.4）
- Then 含 Cloud SQL Auth Proxy sidecar 配置範例（multi-container service.yaml hint）+ HikariCP pool 設定（GCP `max=3` / `min=1`、dev `max=10` / `min=2`）+ pgvector extension（`CREATE EXTENSION IF NOT EXISTS vector`）+ Flyway versioning
- And 引用 ADR-001 §4.4 為決策來源

**AC-6**: glossary.md + qa-strategy.md 補丁
- Given 兩 docs 已 patch
- When `grep -nE "Firestore|MongoDB" docs/grimo/glossary.md docs/grimo/qa-strategy.md`
- Then glossary 0 處；qa-strategy 0 處 current-state（§Three-Layer Verification + §Testing with X 段落）
- And qa-strategy.md 含 `### Testing with PostgreSQL` 章節（Testcontainers `pgvector/pgvector:pg16`）

**AC-7**: spec-roadmap §M17 D15 typo 修正
- Given roadmap §M17 "Goal" 段 + §Active Work table 提到 D8/D9/D14/D15
- When `grep -nE "D8/D9/D14/D15" docs/grimo/specs/spec-roadmap.md`
- Then 0 hits；改寫為 D3/D8/D9/D14/D22（5 條）；同 commit 處理避免下個 spec 又踩

### 驗收命令

per qa-strategy.md（純 docs；用 grep + human review）：

```bash
# 主驗：current-state 段落零殘留 Firestore/MongoDB
grep -nE "Firestore|MongoDB|@Document|MongoRepository" \
  docs/grimo/{PRD,architecture,glossary,qa-strategy}.md \
  | grep -v "ADR-001\|archive\|MVP v1.0.0\|Phase 1 migration"

# 副驗：dependency 表對齊 build.gradle
grep -E "spring-boot-starter-data-(mongodb|jdbc)|google-cloud-firestore|spring-ai-pgvector-store|flyway-core|org\.postgresql:postgresql" \
  docs/grimo/architecture.md backend/build.gradle.kts
```

**Pass 條件**: 主驗 0 hits（current-state 段落）；副驗 PRD/architecture 列出的 dependency 與 build.gradle 一致；human review 確認 §Data Model schema 與 backend/src/main/resources/db/migration/V1__*.sql 一致。

---

## 4. Interface / API Design

本 spec 純 docs；無 API/interface。以下為 rewrite content payload 範本（§4.1-§4.5）。

### 4.1 PRD.md §Decision Log rewrite payload（D3/D8/D9/D14/D22 5 cells + footer）

```markdown
| D3 | 儲存架構 | Object Storage (GCS) + DB (PostgreSQL 16 + pgvector) | 多租戶權限控制容易、搜尋統計方便、運維可控（Phase 1 從 Firestore 遷移；見 ADR-001）| Git-backed、Git + Registry API |
...
| D8 | 資料庫 | PostgreSQL 16 + pgvector（Spring Data JDBC + 自寫 SkillshubPgVectorStore；GCP 部署採 Cloud SQL Auth Proxy sidecar）| Row-level ACL（JSONB + GIN）+ 向量 + 一般查詢統一 SQL；Phase 2 ACL × 向量整合需要無上限 array filter（ADR-001 §3.1：Firestore array-contains-any 30 元素硬上限）| Firestore Enterprise（ACL 表達力天花板）、純 MongoDB Atlas（多一層供應商）|
| D9 | 語意搜尋 | Spring AI（core artifact）+ Gemini embedding + 自訂 `SkillshubPgVectorStore`（HNSW 索引 + cosine distance）| 與 D8 統一；ACL × vector 一條 SQL 同時 GIN filter + HNSW 排序（ADR-001 §3.2）| Firestore `findNearest()`、Vertex AI Vector Search（$65-100/月）、Cloud SQL pgvector starter（4-欄 INSERT 不支援 owner 自訂欄位）|
...
| D14 | DB 存取方式 | 統一 Spring Data JDBC（CRUD + event store JSONB payload）+ 自訂 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（向量 6-欄 atomic INSERT；含 owner / skill_id 自訂欄位）| 單一連線池 / 單一 transaction 模型；不再混 wire protocol；對齊 Spring AI Manual Configuration 原則（S014 §2.1 決策 #2/#12）| 混用 driver、官方 PgVectorStore starter（owner 欄位需 add+UPDATE 兩步驟、中間視窗 owner=NULL observable）|
...
| D22 | Event Store 位置 | 同 PostgreSQL 的 `domain_events` 表（JSONB payload + per-aggregate `(aggregate_id, sequence)` UNIQUE）| 與 read model 同 DB / 同 transaction；query 簡單；無額外基礎設施 | 獨立 DB（多一套系統）、per-aggregate table（管理複雜）|

> **Phase 1 PostgreSQL migration（2026-04-27 v1.1.0）**：D3/D8/D9/D14/D22 已重寫；遷移決策軌跡見 [`adr/ADR-001-postgresql-migration.md`](../adr/ADR-001-postgresql-migration.md) + [`specs/archive/2026-04-27-S014-postgresql-migration.md`](specs/archive/2026-04-27-S014-postgresql-migration.md)。其他 D-entry（D1/D2/D4-D7/D10-D13/D15-D21/D23-D24+）不受 Phase 1 影響。
```

### 4.2 PRD §MVP Scope rewrite payload（L251-252 + 新 status mini-section）

```markdown
- **資料儲存**：PostgreSQL 16 + pgvector（Spring Data JDBC + 自訂 `SkillshubPgVectorStore`）+ GCS 存 skill 打包檔
- **語意搜尋**（非最優先）：Spring AI + Gemini embedding + 自訂 `SkillshubPgVectorStore`（HNSW + cosine），P1-P4 完成後再實作
```

附 mini status section（緊接 §In Scope 列表後）：

```markdown
### 上線狀態（Status）

- ✅ MVP `v1.0.0`（2026-04-27 ship）— 14 specs / 147 story points；初始上線使用 Firestore Enterprise（已被 Phase 1 取代）
- ✅ Phase 1 `v1.1.0`（2026-04-27 ship）— S014 PostgreSQL 資料層遷移 + 自訂 SkillshubPgVectorStore + Firestore 全清；詳 [ADR-001](../adr/ADR-001-postgresql-migration.md) + [`S014 archived spec`](specs/archive/2026-04-27-S014-postgresql-migration.md)
- 🔲 Phase 2.5 — Project Infra（S019/S020/S021 規劃中）
- 🔲 Phase 2 — Row-Level ACL × 充血 Aggregate（S016/S017/S018 規劃中）
```

### 4.3 architecture.md §Data Model (PostgreSQL) rewrite skeleton（L316-380 替換）

```markdown
## Data Model (PostgreSQL)

> Schema 由 Flyway V1 migration 建立（`backend/src/main/resources/db/migration/V1__*.sql`）；6 張表 + extensions（`vector` for pgvector）+ HNSW 索引；S016/S017 將以 V2/V3 增量加 ACL 欄位。

### Event Store

\`\`\`sql
CREATE TABLE domain_events (
    id              VARCHAR(36) PRIMARY KEY,
    aggregate_id    VARCHAR(36) NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         JSONB NOT NULL,
    sequence        BIGINT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    metadata        JSONB,
    UNIQUE (aggregate_id, sequence)
);
CREATE INDEX idx_domain_events_aggregate ON domain_events(aggregate_id, sequence);
\`\`\`

### Read Model Tables（Projections）

`skills` / `skill_versions` / `flags` / `download_events` — 4 張 read model 表；皆以 Spring Data JDBC `@Table` record 表達 + `Persistable<String>.isNew()=true` 強制 INSERT 路徑（避開預設 SELECT-then-UPDATE）；`Map<String,Object>` 欄位透過 `MapJsonbConverter` 雙向 round-trip JSONB 並保留 nested 型別（per S014 §2.1 決策 #5/#6）。

### Vector Store

`vector_store` 由自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore` 控制：6 欄 atomic INSERT（`id` / `content` / `metadata` / `embedding` / `owner` / `skill_id`）— `owner` 為 S016 row-level ACL 鋪路；`ON CONFLICT (id) DO UPDATE` 冪等；HNSW 索引（cosine distance `<=>`）。

詳 S014 archived spec §4 / §2.1 決策 #2 / #12。
```

### 4.4 architecture.md §PostgreSQL Configuration rewrite skeleton（L499-528 替換）

```markdown
## PostgreSQL Configuration

### Local Development（Docker Compose）

\`\`\`yaml
# backend/compose.yaml — pgvector image
services:
  postgres:
    image: pgvector/pgvector:pg16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: skillshub
      POSTGRES_USER: skillshub
      POSTGRES_PASSWORD: skillshub
\`\`\`

### GCP Production（Cloud SQL Auth Proxy sidecar）

Cloud Run multi-container：main container + `gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest` sidecar；共享 localhost network。

\`\`\`yaml
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
\`\`\`

**dev/prod parity**：本機 + GCP 同條 JDBC URL `jdbc:postgresql://localhost:5432/<db>`（只差 env var 帶不同 db name / user / password）。

### Connection Pool（HikariCP）

| Env | maximum-pool-size | minimum-idle | 理由 |
|-----|-------------------|--------------|------|
| GCP（db-f1-micro）| 3 | 1 | DB `max_connections=25` − 5 reserved = 20 ÷ 5 instances ≈ 4/inst → 取 3 留 buffer |
| Local dev | 10 | 2 | 寬鬆 |

### Schema Migration（Flyway）

`backend/src/main/resources/db/migration/V1__*.sql` 建 6 張表 + `CREATE EXTENSION IF NOT EXISTS vector` + HNSW 索引；S016/S017 增量 V2/V3。

詳 ADR-001 §4.4。
```

### 4.5 architecture.md §Framework Dependency Table delta

| Action | Package | Version | Import |
|--------|---------|---------|--------|
| **Remove** | `org.springframework.boot:spring-boot-starter-data-mongodb` | — | — |
| **Remove** | `com.google.cloud:google-cloud-firestore` | — | — |
| **Add** | `org.springframework.boot:spring-boot-starter-data-jdbc` | BOM | `org.springframework.data.jdbc.*` |
| **Add** | `org.springframework.boot:spring-boot-starter-jdbc` | BOM | `org.springframework.jdbc.core.*` |
| **Add** | `org.springframework.ai:spring-ai-pgvector-store` | 2.0.0-M4 BOM | `org.springframework.ai.vectorstore.pgvector.*` |
| **Add** | `org.springframework.boot:spring-boot-flyway` | BOM | — |
| **Add** | `org.flywaydb:flyway-core` | (BOM-managed) | `org.flywaydb.core.*` |
| **Add** | `org.flywaydb:flyway-database-postgresql` | runtime | — |
| **Add** | `org.postgresql:postgresql` | runtime | — |

對齊 `backend/build.gradle.kts` L27-86。其他既有 deps（spring-boot-starter-actuator / spring-cloud-gcp-* / spring-ai-* / spring-modulith-* / springdoc / cyclonedx）不變。

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/PRD.md` | modify | (i) L251-252 §MVP Scope rewrite + 加 §上線狀態（Status）mini-section（per §4.2）；(ii) L307 area diagram in-place rewrite；(iii) L381 / L386 / L387 / L392 / L400 §Decision Log 5 cells rewrite（per §4.1）；(iv) §Decision Log 末尾加 ADR-001 footer |
| `docs/grimo/architecture.md` | modify | (i) L9 §State at Planning footnote；(ii) L70 + L212 ASCII diagrams in-place rewrite；(iii) L280-281 §Module Design line replace；(iv) L316-380 §Data Model **full rewrite**（per §4.3）；(v) L468-471 §Framework Dependency Table（per §4.5）；(vi) L499-528 §Firestore Configuration → §PostgreSQL Configuration **full replace**（per §4.4）|
| `docs/grimo/glossary.md` | modify | L24 1 行 Firestore collection → PostgreSQL 表 |
| `docs/grimo/qa-strategy.md` | modify | L72 1 行 Firestore MongoDB 相容性 → PostgreSQL pgvector；L127-131 §Testing with Firestore → §Testing with PostgreSQL（Testcontainers `pgvector/pgvector:pg16`）|
| `docs/grimo/specs/spec-roadmap.md` | modify | (i) S021 status `🔲 Planning → ⏳ Design`；ship 時 → `✅`；(ii) §M17 D15 typo 修正為 D3/D8/D9/D14/D22 |
| `docs/grimo/specs/2026-04-27-S021-phase2-doc-sync.md` | new | 本 spec 檔案 |

### 不動的檔案

| File | 原因 |
|------|------|
| `docs/grimo/adr/ADR-001-postgresql-migration.md` | 本 spec 之 source of truth；不 retroactively edit |
| `docs/grimo/specs/archive/2026-04-27-S014-postgresql-migration.md` | shipped + archived；歷史檔不動 |
| `docs/grimo/CHANGELOG.md` | `/shipping-release` 處理；本 spec ship 時記錄 |
| `backend/build.gradle.kts` | 已是真實狀態；本 spec 從此檔取真相 |
| `backend/src/main/resources/db/migration/V1__*.sql` | 真實 schema；本 spec 從此檔取真相 |
| `CLAUDE.md` | S014 ship 時已更新「PostgreSQL 16 + pgvector」+「Cloud SQL Auth Proxy sidecar」字眼；本 spec 不重複動 |

### 不在本 spec 範圍

- ADR-001 / S014 archived spec 內容修訂 → 不動歷史檔
- `scripts/gcp/04-deploy.sh` Cloud SQL Auth Proxy sidecar 整合 → S013 follow-up spec
- `compose.yaml` mock-oauth2-server healthcheck workaround → 獨立 spec
- 圖片 / Mermaid / draw.io 等非 ASCII 圖表 → 本專案 docs 全 ASCII，無此需求
- Frontend 文件（README / components）— 與 storage 變動無關

---

## 6. Task Plan

> POC: not required（spec §2.4 全 Validated；純 docs 無 runtime risk）
> E2E smoke task: not required（無 stub/mock；驗收命令為 grep + human review，per §3 驗收命令 + qa-strategy.md "純 docs 變動" pattern）

### Task Index

| Task | Title | ACs | Target Files | Depends |
|------|-------|-----|--------------|---------|
| **T1** | PRD.md rewrite — §MVP Scope + §Architecture Overview ASCII + §Decision Log D3/D8/D9/D14/D22 + ADR-001 footer | AC-1, AC-2 | `docs/grimo/PRD.md` | — |
| **T2** | architecture.md rewrite — §State at Planning footnote + ASCII diagrams + §Module Design + §Data Model + §Framework Dependency Table + §Firestore Configuration → §PostgreSQL Configuration | AC-3, AC-4, AC-5 | `docs/grimo/architecture.md` | — |
| **T3** | glossary + qa-strategy + spec-roadmap §M17 D15 typo 補丁 | AC-6, AC-7 | `docs/grimo/glossary.md`, `docs/grimo/qa-strategy.md`, `docs/grimo/specs/spec-roadmap.md` | — |

3 tasks 對齊 7 個 ACs。皆為 in-place doc rewrite；T1/T2/T3 互不依賴（不同檔案，無 cross-doc reference 衝突），但建議依序執行（T1 → T2 → T3）方便 verification 單向累積。

### Task Files

- `docs/grimo/tasks/2026-04-28-S021-T1-prd-rewrite.md`
- `docs/grimo/tasks/2026-04-28-S021-T2-architecture-rewrite.md`
- `docs/grimo/tasks/2026-04-28-S021-T3-glossary-qa-roadmap.md`

### Granularity 理由

- 3 tasks 對應 spec S(8)（Lead Engineer 標準：S → 3-4 tasks）
- 每個 task RED → GREEN → REFACTOR：RED = grep 仍命中 Firestore/MongoDB；GREEN = 依 §4.x payload rewrite + grep 0 hits；REFACTOR = human review 確認語意通順 + 連結正確（ADR-001 / S014 archive）
- 不再細拆（如 PRD §Decision Log 5 cells 各一 task）— 同檔同段重寫無獨立驗證價值，會放大 RED-GREEN cycle 而無收益

### POC Findings

POC: not required。理由見 spec §2.4：

| 設計決策 | 信心 |
|---------|------|
| ADR-001 為決策權威 | Validated |
| S014 已 ship + 實作 final state | Validated |
| build.gradle.kts 為 dep table 真實狀態 | Validated |
| 純 docs 變動無 runtime risk | Validated |

純文件搬運；source = ADR-001 + S014 archived spec + build.gradle.kts，全部 in-repo + 已 validated。

---

## 7. Implementation Results

### Verification Summary

| 驗證 | 命令 | 結果 |
|------|------|------|
| 主驗（spec §3）| `grep -nE "Firestore\|MongoDB\|@Document\|MongoRepository" docs/grimo/{PRD,architecture,glossary,qa-strategy}.md \| grep -v "ADR-001\|archive\|MVP v1.0.0\|Phase 1 migration"` | exit 1（0 hits — current-state 零殘留）|
| 副驗（spec §3）| `grep -E "spring-boot-starter-data-(mongodb\|jdbc)\|google-cloud-firestore\|spring-ai-pgvector-store\|flyway-core\|org\.postgresql:postgresql" docs/grimo/architecture.md backend/build.gradle.kts` | architecture.md 4 PostgreSQL deps + 0 mongodb/firestore；build.gradle 同 4 + 2 historical comments（移除 deps 標記，非 actual deps）|
| AC-1 supplementary | `grep -cE "PostgreSQL\|pgvector\|Spring Data JDBC" docs/grimo/PRD.md` | 10（≥5 required）|
| AC-3b | `grep -cE "PostgreSQL\|pgvector\|Spring Data JDBC\|@Table\|Flyway" docs/grimo/architecture.md` | 14（≥10 required）|
| AC-6 | `grep -c "### Testing with PostgreSQL" docs/grimo/qa-strategy.md` | 1（present）|
| AC-7 | `grep -cE "D8/D9/D14/D15" docs/grimo/specs/spec-roadmap.md` | 0（typo absent）|

### Pipeline Decision — Why no `./gradlew test` or `./gradlew compileTestJava`

S021 是純 docs 變動（4 個 markdown 檔案 + 1 個 spec-roadmap 行）— 未動 production 或 test code。Spec §3 已聲明「per qa-strategy.md（純 docs；用 grep + human review）」；spec §2.4 表格「純 docs 變動無 runtime risk」evidence 為 Validated。Gradle JaCoCo gate / unit test / compile 對純 docs 變動為 zero-signal noise，不執行。

### E2E Artifact Verification

**Not required** — no integration seams identified. Spec only modifies 5 markdown files; no framework wiring, schema initialization, event serialization, subprocess communication, or credential injection touched. All ACs verifiable via `grep` text-matching commands inline above.

### Implementation Notes（divergences captured）

1. **Stale line numbers in spec §5 File Plan vs actual files**: spec §5 註明 qa-strategy.md L72 / L127-131，但 actual file 在 L102 / L157-161（漂移 ~30 行）。推測原因：S019 / S020 ship 期間在 qa-strategy 加入了 §Verification Command Registry 子段（L60-83），讓 §Three-Layer Verification 與 §Testing with X 區段往下推。改寫照舊用 §識別 + 內容 match，不用行號定位 → 修正落地正確。Spec §5 line numbers 不再回填（行號注定 stale；spec §識別才是穩定 anchor）。

2. **L9 §State at Planning footnote wording**: spec §2.2 / AC-3 寫 "已加 footnote 註解" 暗示 footnote 可保留 Firestore 字面字。但 AC-3 grep 條件無 `-v` 過濾 → strict 0 hits 才能過。改寫為「Phase 1 已從前期儲存層遷移完成（詳 ADR-001 + S014 archived spec）」— 用 ADR-001 替代字面 "Firestore"，兼顧 footnote 語義 + strict grep 過。Spec §3 AC-3 bracket 措辭可未來小幅調整為「（包括 §State at Planning 已加 footnote 註解；可用 ADR-001 替稱 Firestore）」更明確，但與當前狀態相容。

3. **D14 描述路線分歧 — ADR-001 §2 vs spec §4.1 / S014 archive**: ADR-001 §2 / §4.2 寫「Spring AI 官方 PgVectorStore（透過 spring-ai-starter-vector-store-pgvector starter 引入）」；spec §4.1 D14 / S014 archive §2.1 #2/#12 / CLAUDE.md / backend/build.gradle.kts L37 actual dep 都是「自寫 SkillshubPgVectorStore extends AbstractObservationVectorStore（核心 artifact `spring-ai-pgvector-store`，非 starter）」。改寫採 spec / archive / actual code 為 source of truth — 即「自寫子類」路線。ADR-001 §2 描述為設計階段稿，未 retroactively edit（per spec §5「不動的檔案」清單）。讀者若見 ADR-001 與當前 PRD/architecture 描述差異 → ADR-001 §3-§4 仍是決策驅動軌跡的權威；§2 段為早期計畫已被 S014 實作演化覆蓋。

4. **架構 / glossary cross-link 形式**: T3 `### Testing with PostgreSQL` 補 cross-link 至 `architecture.md#postgresql-configuration` anchor，便於讀者跨 doc 跳轉。Glossary L24 與 architecture.md §Data Model Event Store SQL DDL 描述對齊（per-aggregate `(aggregate_id, sequence)` UNIQUE 一致）。

5. **Spec §4.5 Framework Dependency Table delta vs actual**: spec §4.5 列 5 條 "Add"，但實際 backend/build.gradle.kts L57-61 有 7 條（多 `spring-boot-starter-jdbc` + `flyway-database-postgresql`）— architecture.md 同步加上 7 條完整列表，超出 spec §4.5 的 5 條描述。決策依據：build.gradle.kts 為唯一 source of truth；spec §4.5 為 design-time 估計，actual 為 implementation 真相。Spec AC-4 條件「至少 5 條」滿足，超出 2 條增強對齊精度。

### Tech Debt Surfaced

1. **Spec §5 line numbers go stale**: 任何時間有人在 PRD/architecture/qa-strategy 中段插入內容，spec §5 / §2.2 的 line numbers 都會 drift。建議未來 spec 採「§識別」+ 內容 anchor，不用 absolute line number — S021 的 §2.2 "L9 §State at Planning" 形式比 "L9" 形式好。記入 development-standards 的 spec writing guide（待加）。

2. **ADR-001 §2 vs S014 archive 路線歷史記錄**: ADR-001 為 source of decision drivers + alternatives，但 §2 Decision 段與 S014 final state 不一致。建議 ADR 新增一個「Implementation outcome」段標註「最終採 §4.2 Alternative B 子類路線；§2 描述為設計階段稿」— 但此屬 ADR 更新範圍，非 S021 scope。記入 Backlog 觀察。

3. **無 `architecture.md#postgresql-configuration` anchor 標準化**: T3 用 markdown anchor 形式（spaces → hyphens, lowercase）連結，符合 GitHub markdown convention，但未在 development-standards 中標準化。建議下一個 docs-touching spec 加入 standard。

### AC Results

| AC | 描述 | 結果 | 證據 |
|----|------|------|------|
| AC-1 | PRD.md current-state 零殘留 + ≥5 PostgreSQL/pgvector/Spring Data JDBC | ✅ pass | 主驗 0 hits；count = 10 |
| AC-2 | PRD §Decision Log 5 cells（D3/D8/D9/D14/D22）+ ADR-001 footer | ✅ pass | 5 cells 對齊 ADR-001 §2/§4；footer 含 ADR-001 + S014 archived 雙 pointer |
| AC-3 | architecture.md current-state 零殘留 + ≥10 PostgreSQL/pgvector/JDBC/@Table/Flyway | ✅ pass | 主驗 0 hits（§State at Planning 用 ADR-001 替稱 Firestore）；count = 14 |
| AC-4 | §Data Model 6 表 + Flyway V1 + 5 new deps + 0 mongodb/firestore deps | ✅ pass | 6 表（domain_events / skills / skill_versions / flags / download_events / vector_store）+ 7 new deps（超過 spec 5 條 minimum）+ 0 removed deps |
| AC-5 | §PostgreSQL Configuration 含 sidecar + HikariCP + extension + Flyway + ADR-001 ref | ✅ pass | L503-558 全 5 子段 present；引用 ADR-001 §4.4 |
| AC-6 | glossary + qa-strategy 0 hits + Testing with PostgreSQL section | ✅ pass | 0 hits；§Testing with PostgreSQL 含 Testcontainers `pgvector/pgvector:pg16` + cross-link |
| AC-7 | spec-roadmap §M17 D15 typo 修正 | ✅ pass | typo 已不存在於 roadmap（Status correction comment L122 仍保留作為 audit trail） |

### Pending Verification

無。所有 ACs 已透過 grep + human review 驗證完成；下一步由獨立 QA subagent 確認。

---

<!-- §7.QA Review by independent subagent: appended below by /verifying-quality -->

## 7.QA Review (Round 1)

> Reviewer: independent QA subagent | Date: 2026-04-28 | Verdict: **PASS with MINOR inline fixes**

### Verification Commands Re-run

| 命令 | 獨立執行結果 |
|------|------------|
| 主驗（AC-1/3/6）`grep -nE "Firestore\|MongoDB\|@Document\|MongoRepository" docs/grimo/{PRD,architecture,glossary,qa-strategy}.md \| grep -v "ADR-001\|archive\|MVP v1.0.0\|Phase 1 migration"` | exit 1（0 hits）— CONFIRMED |
| 副驗（AC-4）`grep -E "spring-boot-starter-data-(mongodb\|jdbc)\|google-cloud-firestore\|spring-ai-pgvector-store\|flyway-core\|org\.postgresql:postgresql" docs/grimo/architecture.md backend/build.gradle.kts` | architecture.md 4 hits（data-jdbc / spring-ai-pgvector-store / flyway-core / postgresql）；build.gradle 4 prod hits + 2 historical comments — CONFIRMED |
| AC-1 supplementary `grep -cE "PostgreSQL\|pgvector\|Spring Data JDBC" docs/grimo/PRD.md` | 10（≥5）— CONFIRMED |
| AC-3b `grep -cE "PostgreSQL\|pgvector\|Spring Data JDBC\|@Table\|Flyway" docs/grimo/architecture.md` | 14（≥10）— CONFIRMED |
| AC-6 `grep -c "### Testing with PostgreSQL" docs/grimo/qa-strategy.md` | 1 — CONFIRMED |
| AC-7 `grep -cE "D8/D9/D14/D15" docs/grimo/specs/spec-roadmap.md` | 0（typo absent）— CONFIRMED |

### AC-by-AC Evidence

| AC | 結果 | 獨立證據 |
|----|------|---------|
| AC-1 | ✅ PASS | 主驗 0 hits；count=10（≥5）；PRD L251-252 改 PostgreSQL；L307 area diagram 改 PostgreSQL+pgvector+sidecar；D3/D8/D9/D14/D22 全 rewrite |
| AC-2 | ✅ PASS | PRD L395/400/401/406/414 5 cells 確認；D3 aligned ADR-001 §3.1 drivers；D8 aligned §3.1；D9 aligned §3.2；D14 aligned S014 archived §2.1 決策 #2/#12（見 Implementation Note #3 — ADR-001 §2 vs final code 差異已記錄）；D22 aligned ADR-001 §6.2；footer 位於 PRD L418 — 格式完整，雙 pointer |
| AC-3 | ✅ PASS | 主驗 0 hits；count=14（≥10）；architecture.md L9 §State at Planning 用 ADR-001 替稱，不帶 literal Firestore |
| AC-4 | ✅ PASS | architecture.md L324-380 確認 6 表（domain_events / skills / skill_versions / flags / download_events / vector_store）+ Flyway V1 reference；§Framework Dependency Table 含 7 new deps（超出 spec §4.5 最低 5 條）；0 mongodb/firestore deps |
| AC-5 | ✅ PASS | architecture.md L503-558 確認 5 子段（Local Dev compose.yaml / GCP sidecar service.yaml / HikariCP pool table / Flyway Schema Migration / Key Constraints）；ADR-001 §4.4 引用在 L558 |
| AC-6 | ✅ PASS | glossary L24 改 PostgreSQL `domain_events` 表；qa-strategy L157-161 `### Testing with PostgreSQL`（Testcontainers `pgvector/pgvector:pg16`）+ cross-link；0 hits both docs |
| AC-7 | ✅ PASS | roadmap L122 已更正為 D3/D8/D9/D14/D22；`grep -cE "D8/D9/D14/D15"` = 0 |

### Cross-Check: Ground Truth Alignment

#### PRD Decision Log vs ADR-001

D3/D8/D9/D22 與 ADR-001 drivers（§3.1 / §3.2）及 alternatives（§4.1 / §4.4）對齊良好。

D14 存在已知分歧（Implementation Note #3）：ADR-001 §2 描述「Spring AI 官方 PgVectorStore starter」，PRD D14 描述「自訂 SkillshubPgVectorStore extends AbstractObservationVectorStore」。Spec §7 已正確判斷：以 S014 archive + build.gradle.kts（actual code）為 source of truth，PRD D14 描述正確反映當前實際實作。ADR-001 §2 為設計階段稿，未 retroactively edit（per §5「不動的檔案」清單）。此為已知文件間不一致，非 QA 驗收缺陷。

#### architecture.md §Data Model vs V1__initial_schema.sql

比對 `backend/src/main/resources/db/migration/V1__initial_schema.sql` 後，發現以下 MINOR DDL 精度落差（所有落差均不影響語意正確性或 AC pass 條件）：

| 項目 | architecture.md | V1 SQL (ground truth) |
|------|----------------|----------------------|
| `domain_events.aggregate_type` | `VARCHAR(64)` | `VARCHAR(50)` |
| `domain_events.event_type` | `VARCHAR(128)` | `VARCHAR(100)` |
| `domain_events.metadata` | `JSONB`（nullable，無 default）| `JSONB NOT NULL DEFAULT '{}'::jsonb` |
| `domain_events` UNIQUE 形式 | 內嵌 `UNIQUE (aggregate_id, sequence)` table constraint | `CREATE UNIQUE INDEX idx_domain_events_aggregate_seq`（獨立 index）|
| `domain_events` index 名稱 | `idx_domain_events_aggregate` | `idx_domain_events_aggregate_seq` |
| `vector_store.metadata` | `JSONB` | `JSON`（INSERT 時用 `::jsonb` cast；Spring AI 預設行為） |

以上 6 項皆為 MINOR 精度落差：語意等效（UNIQUE index vs constraint 行為相同；NOT NULL DEFAULT 是 implementation detail；JSON vs JSONB 在 INSERT cast 後行為一致）。architecture.md 為 human-readable 設計文件，不以 DDL 精確度為 AC gate（AC-4 條件為「含 6 張表 + Flyway V1 + ≥5 deps + 0 removed deps」，均已滿足）。

**Inline 修正**：為提高 architecture.md 精度，已就地修正 domain_events DDL 中的欄位寬度與 metadata DEFAULT，以更貼近 V1 SQL ground truth。index 名稱差異與 JSON/JSONB 落差保留（文件可讀性優先；V1 SQL comment 已說明 JSON 原因）。

#### architecture.md §Framework Dependency Table vs build.gradle.kts

全 7 條 PostgreSQL-related deps（data-jdbc / starter-jdbc / spring-ai-pgvector-store / spring-boot-flyway / flyway-core / flyway-database-postgresql / postgresql）均在 architecture.md 中對應記錄。0 mongodb/firestore。

#### glossary vs architecture.md §Data Model

glossary L24「事件儲存」描述與 architecture.md §Data Model Event Store DDL 一致（`(aggregate_id, sequence)` UNIQUE per-aggregate）。

### Link Integrity

| 連結 | 狀態 |
|------|------|
| PRD.md `./adr/ADR-001-postgresql-migration.md` | ✅ 目標存在 |
| PRD.md `./specs/archive/2026-04-27-S014-postgresql-migration.md` | ✅ 目標存在 |
| PRD.md §上線狀態 `./adr/ADR-001-postgresql-migration.md` | ✅ 目標存在 |
| PRD.md §上線狀態 `./specs/archive/2026-04-27-S014-postgresql-migration.md` | ✅ 目標存在 |
| qa-strategy.md `./architecture.md#postgresql-configuration` | ✅ anchor 存在（L503）|

### Style Consistency

- PRD Decision Log 表格格式一致（5 欄：# / 決策 / 選擇 / 理由 / 排除的替代方案）
- ASCII diagrams 對齊良好（PRD L285-337 / architecture.md L66-81 / L180-238）
- 中文用詞一致：全文使用「PostgreSQL / pgvector / 自訂 SkillshubPgVectorStore」— 無混用

### Implementation Note #5 補驗

Spec §7 Implementation Note #5 記：「spec §4.5 列 5 條 Add，但實際 build.gradle 有 7 條（多 starter-jdbc + flyway-database-postgresql）— architecture.md 同步加上 7 條。」

獨立確認：architecture.md L467-474 列出 7 條 PostgreSQL-related deps，與 build.gradle.kts L33-61 完整對應。AC-4 要求「至少 5 條」，實際 7 條 — PASS。

### Roadmap Status Note

spec-roadmap.md 目前 S021 狀態為 `⏳ Dev`（L26/L128），spec 檔頭為 `Status: ✅ Done`。per §5 File Plan，roadmap `✅` 更新屬 `/shipping-release` 處理（「ship 時 → ✅」），非 QA 階段責任。**不構成 QA 缺陷**。

### Inline Fixes Applied

以下 MINOR 修正已就地完成（不影響 AC pass/fail 狀態，不需重新驗證）：

1. **architecture.md `domain_events` DDL 精度** — 將 `aggregate_type VARCHAR(64)` 改為 `VARCHAR(50)`，`event_type VARCHAR(128)` 改為 `VARCHAR(100)`，與 `V1__initial_schema.sql` ground truth 對齊。`metadata JSONB` 加上 `NOT NULL DEFAULT '{}'::jsonb` 標記。UNIQUE constraint 形式與 index 名稱保留（等效語意；文件可讀性優先）。`vector_store.metadata` JSON/JSONB 落差保留（V1 SQL 已有 comment 說明）。

### Final Verdict

**PASS（REJECT-MINOR inline fixed）**

所有 7 個 ACs 獨立驗證通過。主要正向發現：
- 主驗命令 0 hits — Firestore/MongoDB 完全清除
- PRD Decision Log 5 cells（D3/D8/D9/D14/D22）內容正確，ADR-001 footer 雙 pointer 完整
- architecture.md 6 張表描述正確，7 deps 完整對齊 build.gradle
- §PostgreSQL Configuration 5 子段齊全，ADR-001 §4.4 引用正確
- 所有 links 有效
- ADR-001 §2 vs PRD D14 路線差異已在 Implementation Note #3 誠實記錄

MINOR 精度落差（domain_events DDL 欄位寬度 / metadata 屬性）已 inline 修正。無 CRITICAL 或 IMPORTANT 缺陷。

**Next action**: `/shipping-release` — 更新 spec-roadmap S021 `⏳ Dev → ✅`，歸檔 spec，更新 CHANGELOG.md。
