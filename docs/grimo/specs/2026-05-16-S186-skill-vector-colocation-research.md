# S186: Skill Embedding 同表化

> 規格：S186 | 大小：M(13) | 狀態：⏳ Dev — tasks PASS, Phase 4 verification pending
> 日期：2026-05-16
> 對應：PRD P5 語意搜尋 / S107 semantic projection fields / S157 semantic search / S177 is_public-first search visibility / S185 list-detail projection consistency

---

## 1. 目標

把語意搜尋用的 embedding 放回 `skills` 表，讓搜尋排序資料、實體資料、可見性資料都來自同一筆 skill row。

現在 `GET /api/v1/search/semantic?q=...` 會先查 `vector_store` 算距離，再批次讀 `skills` 補卡片欄位；`vector_store.is_public` / `vector_store.acl_entries` 只是 `skills` 的投影，Grant / visibility 變更還要事件同步一次。S186 改成：

```text
User GET /api/v1/search/semantic?q=部署容器
  -> SemanticSearchService 產生 query embedding
  -> SQL 直接查 skills.embedding
  -> SQL 同 row 判斷 skills.status / is_public / acl_entries
  -> response 回 SemanticSearchResult card fields + score
```

關鍵邊界：`Skill` aggregate 平常不撈 embedding。`embedding` 是 Search Embedding 欄位，不是 domain invariant；command path 仍只載入 `Skill` 需要的欄位，semantic search 才讀 `embedding`。設計口徑：physical colocation, conceptual separation；資料與 skill read state 放同一張 `skills` table，但 Search Embedding 不是 user-facing Skill concept。

相依狀態：

| Spec | 狀態 | 影響 |
|---|---|---|
| S107 | ✅ shipped | 已決定 search response 顯示欄位要從 canonical `skills` 讀，不依賴 vector metadata。 |
| S157 | ✅ shipped | semantic search / Gemini embedding / pgvector wiring 已可用。 |
| S177 | ✅ shipped | `skills.is_public` 是 public visibility truth；`vector_store.is_public` 只是 projection。 |
| S185 | 📋 planned | list/detail 欄位一致性修復；S186 不 import S185 production type，release 順序是 ordering-only。 |
| S187 | 📐 in-design | S187 等 S186 ship 後再實作；先修 semantic search / vector projection 問題，再做 SKILL.md edit page。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|---|---|---|
| [backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java:91) | 現在 `vector_store` insert 8 欄：`id/content/metadata/embedding/owner/skill_id/acl_entries/is_public`。 | 同表化後這個 class 可以刪掉；write path 改成更新 `skills.embedding_*`。 |
| [backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java:82) | 現在先查 vector docs，再 `skillRepo.findAllById(skillIds)` 批次補 canonical fields。 | 同表 SQL 可一次回 `SemanticSearchResult` 需要欄位，拿掉 metadata + batch lookup。 |
| [backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java:104) | Grant 變更會同時 update `skills.acl_entries` 與 `vector_store.acl_entries/is_public`。 | 同表化後 listener 只需要更新 `skills.acl_entries`；不再有 vector 權限 projection lag。 |
| [backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java:39) | `findByAuthorAndName` 目前 `SELECT * FROM skills`。 | 同表化前要改成 explicit column list，避免平常 alias detail 讀到 embedding。 |
| [backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java:221) | list SQL 已手寫 explicit column list；可沿用這種模式。 | 同表化不要求所有路徑改成 repository projection；既有 raw JDBC list path 只要不選 embedding 即可。 |
| [pgvector README — Filtering and iterative scans](https://github.com/pgvector/pgvector/blob/master/README.md#filtering) | HNSW / IVFFlat approximate index 會先掃索引再套 WHERE filter；filter 選擇性低時可能回不到足夠筆數，需要提高 `hnsw.ef_search`、iterative scan、partial index 或 partition。 | 同表化不會消除 HNSW post-filter 問題；AC 要保留 topK 與 limit 行為，不把「一定回滿筆數」當保證。 |
| [pgvector README — Vector type](https://github.com/pgvector/pgvector/blob/master/README.md#vector-type) | `vector(768)` 每 row 約 `4 * dimensions + 8 = 3080 bytes`。 | `skills` row 會變寬；必須用 explicit projection 避免一般讀取拉 embedding。 |
| [PostgreSQL 18 docs — TOAST](https://www.postgresql.org/docs/18/storage-toast.html) | 太大的 varlena 值會壓縮或移到 TOAST； unchanged out-of-line values 在 UPDATE 時通常可保留。 | embedding 不是每次 skill update 都重寫，但 row 變寬仍要量測 buffer / HOT 指標。 |
| [PostgreSQL 18 docs — HOT](https://www.postgresql.org/docs/18/storage-hot.html) | UPDATE 若不改 indexed column 且 page 有空間，才可能 HOT；改 indexed `embedding` 會需要 index work。 | re-embed 必然更新 HNSW indexed column；這是同表化的寫入成本，要用 AC-S186-7 量化。 |
| [PostgreSQL 18 docs — VACUUM](https://www.postgresql.org/docs/current/sql-vacuum.html) | UPDATE/DELETE 舊 row 會留下 dead tuple，需 VACUUM / index cleanup。 | S186 要減少 Grant/visibility 對 vector projection 的額外 UPDATE，但 re-embed 仍需觀察 dead tuple。 |
| [Spring Data Relational 4.0.5 — JDBC `@Query`](https://docs.spring.io/spring-data/relational/reference/jdbc/query-methods.html) | 手寫 `@Query` 可以只 select 指定欄位；constructor 必要欄位要提供，setter/field access 欄位沒出現在 result 時不會設定；`@Modifying` query 直接打 DB，不觸發 entity callbacks。 | `SkillRepository.findByAuthorAndName` 可改 explicit columns；`SearchEmbeddingRepository` 可直接 UPDATE，不走 aggregate save。 |
| [Spring Data Relational 4.0.5 — projections](https://docs.spring.io/spring-data/relational/reference/repositories/projections.html) | closed projection / DTO projection 可讓 Spring Data 根據 projection 欄位最佳化查詢；DTO 欄位由 constructor parameter names 決定。 | 若用 repository method 查 partial skill，可用 DTO；semantic SQL 因 `<=>` operator 與 pgvector binding，建議用 `NamedParameterJdbcTemplate`。 |

### 2.2 架構設計

資料表改成 `skills` 自帶 embedding 欄位：

```sql
ALTER TABLE skills
  ADD COLUMN embedding_content TEXT,
  ADD COLUMN embedding VECTOR(768),
  ADD COLUMN embedding_model VARCHAR(64),
  ADD COLUMN embedding_updated_at TIMESTAMPTZ;

CREATE INDEX idx_skills_embedding_hnsw
  ON skills USING HNSW (embedding vector_cosine_ops);
```

`embedding_content` 要保存，但只給 debug / rebuild index 使用，不輸出 API/UI。語意搜尋結果怪時，可直接看該 row 當初用哪段文字產生向量；平常 `Skill` 讀取與 list/detail API 都不 select 這個欄位。

Search Embedding 內容來源固定為 `skills.name` + latest SKILL.md frontmatter `name` + latest SKILL.md frontmatter `description`。只保存 latest embedding，不保存每個版本的 embedding history；新版本 publish 後覆蓋 `skills.embedding_*`。

`skills.description` 作為 latest SKILL.md frontmatter `description` 的 Skill Description Snapshot，屬於詳情頁 / 編輯流程調整；另由 S187 設計。S186 不設計 `/skills/{id}/edit`、不修改 Version tab 行為，也不處理 `UpdateSkillCommand` 是否保留。S186 只要求 embedding builder 不讀舊 `vector_store` metadata，且用 latest SKILL.md frontmatter 產生 `embedding_content`。

執行順序決議：問題修正先做 S186，再做 S187。S186 ship 前不得開始 S187 task loop，避免同時改 search embedding 來源、description snapshot、edit page 三個面向。

範例資料：

| id | name | status | is_public | acl_entries | embedding_content | embedding |
|---|---|---|---|---|---|---|
| `skill-docker` | `docker-compose-helper` | `PUBLISHED` | `true` | `["user:u_alice:read","user:u_alice:write"]` | `docker-compose-helper Compose multi-service containers` | `[0.12,...]` |
| `skill-private` | `internal-release` | `PUBLISHED` | `false` | `["user:u_alice:read"]` | `internal-release private deployment workflow` | `[0.22,...]` |
| `skill-draft` | `draft-tool` | `DRAFT` | `true` | `["user:u_bob:read"]` | `draft-tool unpublished skill` | `[0.08,...]` |

讀寫分工：

| 路徑 | 讀/寫哪些欄位 | 不做什麼 |
|---|---|---|
| `SkillRepository.findById` / command load | `Skill` aggregate mapped fields，不含 `embedding*` | 不讀 embedding，不讓 domain method 改 embedding |
| `SearchEmbeddingRepository.upsertEmbedding` | `UPDATE skills SET embedding_content, embedding, embedding_model, embedding_updated_at WHERE id=?` | 不呼叫 `skillRepo.save(skill)` |
| `SemanticSearchService.search` | `SELECT id,name,description,author,category,category_display,latest_version,risk_level,download_count, embedding <=> :query AS distance FROM skills ...` | 不讀 `vector_store`，不再做 `findAllById` 補欄位 |
| `SkillAclProjectionListener.rebuildAcl` | `UPDATE skills SET acl_entries = :acl::jsonb WHERE id=:id` | 不更新 `vector_store` |

`Skill` aggregate 不新增 `embedding` field。這點是同表方案的安全閥：資料在同一張 DB table，但 Java domain object 不承擔 search infrastructure state。

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|---|---|---|
| A. 保留 `vector_store`，只讓 visibility / ACL filter 回讀 `skills` | no | 能拿掉權限 projection lag，但仍有兩張表、metadata、delete/re-add embedding path；沒有完全回應「實體與向量同 row」的設計目標。 |
| B. `skills` 同表保存 embedding，domain aggregate 不 mapping embedding（recommended） | yes | 搜尋排序、卡片欄位、visibility/ACL 都在同一 row；一般讀取用 explicit columns / DTO 避免拉 embedding；可刪掉 `vector_store` 同步點。 |
| C. 新建 `skill_embeddings` 一對一表 | no | 比 `vector_store` 語意乾淨，但仍有 join 與兩表同步；只比現況少 metadata/ACL duplication，沒有同表簡化最大收益。 |

### 2.4 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|---|
| T01 schema + aggregate non-mapping guard | `Vxx__skill_embedding_columns.sql`, `SkillRepositoryEmbeddingColumnTest` | PostgreSQL pgvector + Spring Data JDBC `@Query` docs | `skills.embedding` 有值時 `skillRepo.findById/save` 不覆蓋 embedding | `SELECT *` query 被 guard test 擋下 | required |
| T02 semantic query DTO | `SemanticSearchService`, `SkillSemanticHit` | `SemanticSearchService.search()` 現況 | public skill 直接由 `skills.embedding` 命中並回 `SemanticSearchResult` | private skill anonymous 不回傳 | not required |
| T03 embedding write path | `SearchProjection`, `SearchEmbeddingRepository` | `SearchProjection` 現況 | publish/re-embed 更新 `skills.embedding_*` | suspend 設 embedding null 或不回傳 suspended skill | not required |
| T04 remove vector ACL projection | `SkillAclProjectionListener` | S177 projection listener | grant 改動只更新 `skills.acl_entries`，下一次 semantic search 立即用新 ACL | 不再 update `vector_store` | not required |
| T05 migration cleanup + tests | migration, test fixtures, `TestDataController` reset allowlist | `vector_store` table usages | tests 不再 seed/read vector_store | stale `vector_store` reference build fail | not required |
| T06 docs + architecture sync | `architecture.md`, `development-standards.md`, `qa-strategy.md` if needed | ADR-001/S014 historical docs | docs 說明 embedding 是 `skills` infrastructure column | docs 不再說 semantic search 依賴 `vector_store` | not required |

### 2.5 POC 驗證結果

POC 測試檔：[SkillEmbeddingColocationPocTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/search/SkillEmbeddingColocationPocTest.java:1)

執行：

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SkillEmbeddingColocationPocTest
```

結果：`BUILD SUCCESSFUL in 2m 5s`，3 個 POC case 全綠。

Recheck（2026-05-16 21:55 CST）：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SkillEmbeddingColocationPocTest` 通過，結果 `BUILD SUCCESSFUL in 2m 39s`；11 個 actionable tasks，其中 6 個 executed、5 個 up-to-date。

| POC case | 實際做了什麼 | 結論 |
|---|---|---|
| `POC-S186-1` | 在 `skills` 加 `embedding_content/embedding/embedding_model/embedding_updated_at`；用 `skillRepo.save` 建 skill；用 JDBC 寫 `embedding`；再 `findById -> update description -> save`。 | `Skill` aggregate 沒有任何 `embedding*` field，save 後 `skills.embedding_content='docker compose helper'` 還在，`embedding <=> query` 距離接近 0。可行：同表但 domain 不 mapping embedding，不會被一般 save 清掉。 |
| `POC-S186-2` | 不寫任何 `vector_store` row，只在 `skills.embedding` 寫 public skill、Alice private skill、Bob private skill；SQL 用同一 row 的 `status/is_public/acl_entries` 篩選。 | anonymous 只回 public skill；`aclPatterns=["user:alice:read"]` 回 public + Alice private，不回 Bob private；`SELECT COUNT(*) FROM vector_store` 是 0。可行：semantic search 可以完全不依賴 `vector_store`。 |
| `POC-S186-3` | 對同表 semantic SQL 跑 `EXPLAIN (COSTS OFF)`。 | plan 只包含 `skills`，不包含 `vector_store`。可行：query shape 已是單表。 |

POC 沒證明的事：

| 未驗證項 | 原因 | S186 實作時怎麼補 |
|---|---|---|
| 大資料量下 HNSW 是否一定被 planner 使用 | POC 只有少量 rows，PostgreSQL 可能選 seq scan。 | AC-S186-7 在實作後用 `EXPLAIN (ANALYZE, BUFFERS)` 記錄 planner 結果。 |
| `SELECT * FROM skills` 是否會拉大欄位造成一般 API 變慢 | POC 只證明 unmapped field 不會被 save 清掉；`SELECT *` 仍會把欄位從 DB 傳回 driver。 | `SkillRepository.findByAuthorAndName` 必須改 explicit column list；加 code inspection/test 擋 `SELECT * FROM skills`。 |
| re-embed 對 HOT / dead tuple 的實際成本 | POC 只寫少量 row，沒有量測 update churn。 | 實作 AC-S186-7 時加 re-embed EXPLAIN / table stats，§7 記錄結果。 |
| latest SKILL.md frontmatter source | POC 用測試字串直接寫 `embedding_content`，沒有讀 `skill_versions.frontmatter`。 | S186 實作時建立單一路徑：用 `skills.name + frontmatter.name + frontmatter.description` 產生 `embedding_content`；description snapshot 更新由 S187 承接。 |

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd backend && ./gradlew test`
通過條件：所有帶 `S186` / `AC-S186-*` 的 backend 測試都是綠燈。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S186-1 | 必做 | Test | 一般 Skill 讀取不載入 embedding |
| AC-S186-2 | 必做 | Test | semantic search 從 `skills.embedding` 回 public result |
| AC-S186-3 | 必做 | Test | private skill anonymous 不可由 semantic search 看見 |
| AC-S186-4 | 必做 | Test | visibility/ACL 改動不需要 vector projection lag |
| AC-S186-5 | 必做 | Test | publish 新版 re-embed latest SKILL.md frontmatter |
| AC-S186-6 | 必做 | Inspection/Test | 程式碼不再讀寫 `vector_store` |
| AC-S186-7 | 建議 | Test/Inspection | 同表 query 有 EXPLAIN evidence |
| AC-S186-8 | 必做 | Test | result card 欄位來自 `skills` row |

**AC-S186-1: 一般 Skill 讀取不載入 embedding**
- Given（前提）DB 有 `skills.id='skill-docker'`，`embedding` 非 null，`embedding_content='docker compose helper'`
- When（動作）後端呼叫 `skillRepo.findById("skill-docker")` 並 `skillRepo.save(skill)` 更新一般欄位，例如 `updated_at`
- Then（結果）`skills.embedding` 原值仍存在
- And（而且）`Skill` aggregate class 沒有 `embedding` / `embeddingContent` / `embeddingUpdatedAt` persisted field

**AC-S186-2: semantic search 從 `skills.embedding` 回 public result**
- Given（前提）DB 有 `skills.id='skill-docker'`，`status='PUBLISHED'`，`is_public=true`，`embedding` 為測試向量
- When（動作）anonymous 呼叫 `GET /api/v1/search/semantic?q=部署容器&limit=10`
- Then（結果）HTTP 200 response 包含 `{"id":"skill-docker","name":"docker-compose-helper","score":...}`
- And（而且）測試資料不需要建立任何 `vector_store` row

**AC-S186-3: private skill anonymous 不可由 semantic search 看見**
- Given（前提）DB 有 `skills.id='skill-private'`，`status='PUBLISHED'`，`is_public=false`，`acl_entries=["user:u_alice:read"]`，`embedding` 與 query 相近
- When（動作）anonymous 呼叫 `GET /api/v1/search/semantic?q=internal release`
- Then（結果）HTTP 200 response 不包含 `skill-private`
- And（而且）以 Alice JWT 呼叫同一 endpoint 時 response 包含 `skill-private`

**AC-S186-4: visibility/ACL 改動不需要 vector projection lag**
- Given（前提）DB 有 `skills.id='skill-a'`，`status='PUBLISHED'`，`is_public=false`，`embedding` 非 null
- When（動作）`PUT /api/v1/skills/skill-a/visibility` 把 `skills.is_public` 改成 true
- Then（結果）下一次 anonymous semantic search 直接依 `skills.is_public=true` 命中 `skill-a`
- And（而且）不需要等待任何 listener update `vector_store.is_public`

**AC-S186-5: publish 新版 re-embed latest SKILL.md frontmatter**
- Given（前提）DB 有 `skills.id='skill-docker'`，`embedding_content='old text'`
- When（動作）`SkillVersionPublishedEvent` 帶 latest SKILL.md frontmatter `name='docker-helper'`、`description='Compose deploy helper'`
- Then（結果）`skills.embedding_content='<skills.name> docker-helper Compose deploy helper'`，`skills.embedding_updated_at` 變新
- And（而且）`skills.name/status/is_public/acl_entries` 不因 re-embed 被覆蓋

**AC-S186-6: 程式碼不再讀寫 `vector_store`**
- Given（前提）S186 實作完成
- When（動作）執行 code inspection：`rg -n "vector_store|SkillshubPgVectorStore" backend/src/main/java backend/src/test/java`
- Then（結果）production semantic search / ACL projection / test fixture 不再依賴 `vector_store`
- And（而且）保留的字串只允許出現在舊 migration 或 archived spec，不出現在 active runtime path

**AC-S186-7: 同表 query 有 EXPLAIN evidence**
- Given（前提）測試 DB 至少有 public、private、draft 三種 skill，各有 embedding
- When（動作）對 semantic SQL 執行 `EXPLAIN (ANALYZE, BUFFERS)`
- Then（結果）spec §7 記錄 `Execution Time`、`Buffers shared hit/read`、是否使用 `idx_skills_embedding_hnsw`
- And（而且）若 planner 不使用 HNSW index，§7 記錄原因與後續調校建議，不在本 spec 偷改 threshold 或 planner setting

**AC-S186-8: result card 欄位來自 `skills` row**
- Given（前提）DB 有 `skills.id='skill-docker'`，`author='u_current'`，`category='devops'`，`download_count=7`
- When（動作）semantic search 命中該 skill
- Then（結果）response 的 `author/category/downloadCount/latestVersion/riskLevel` 來自 `skills` row
- And（而且）response 不依賴 `metadata` JSON 或第二次 `findAllById` 補值

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S186-1, AC-S186-7 | 平常 skill 讀取不拉 embedding；semantic SQL 必須留下 EXPLAIN evidence。 |
| Security | AC-S186-3, AC-S186-4 | visibility/ACL 直接讀 canonical `skills` row，避免 stale projection 造成 private leak。 |
| Reliability | AC-S186-5 | re-embed 只更新 embedding 欄位，不覆蓋 skill state。 |
| Usability | AC-S186-2, AC-S186-8 | API response shape 不變，前端 card 不需要改。 |
| Maintainability | AC-S186-6 | 刪除 `vector_store` runtime dependency，少一條事件同步鏈。 |

## 4. 介面與 API 設計

### 4.1 Migration

```sql
ALTER TABLE skills
  ADD COLUMN IF NOT EXISTS embedding_content TEXT,
  ADD COLUMN IF NOT EXISTS embedding VECTOR(768),
  ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64),
  ADD COLUMN IF NOT EXISTS embedding_updated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_skills_embedding_hnsw
  ON skills USING HNSW (embedding vector_cosine_ops);

DROP TABLE IF EXISTS vector_store;
```

`vector_store` cleanup 決議：

1. S186 不保留舊 `vector_store` 向量資料，不做 `vector_store -> skills.embedding` migration。
2. 部署目標環境可清除重建資料；S186 不是 production-preserving data migration spec。
3. S186 不做舊 skill embedding backfill，也不新增手動 re-embed 維運入口；部署 / 測試目標環境以系統資料重建後重新驗證 semantic search。
4. migration 只負責 schema：新增 `skills.embedding_*` 欄位 / index，並 `DROP TABLE IF EXISTS vector_store`；不嘗試用 SQL、application startup、或手動 command 搬移既有向量資料。
5. 測試資料與 e2e fixture 必須改成 seed skill 後寫 `skills.embedding_*`，不再 seed `vector_store`。

### 4.2 Search DTO

```java
record SkillSemanticHit(
        String id,
        String name,
        String description,
        String author,
        String category,
        String categoryDisplay,
        String latestVersion,
        String riskLevel,
        long downloadCount,
        double distance
) {
    SemanticSearchResult toResult() {
        return new SemanticSearchResult(
                id, name, description, author, category, categoryDisplay,
                latestVersion, riskLevel, downloadCount, 1.0 - distance);
    }
}
```

欄位來源：

| DTO field | DB source |
|---|---|
| `id/name/description/author/category/categoryDisplay/latestVersion/riskLevel/downloadCount` | `skills` row |
| `distance` | `skills.embedding <=> :queryEmbedding` |
| `score` | Java `1.0 - distance` |

### 4.3 Semantic SQL

```sql
SELECT id, name, description, author, category, category_display,
       latest_version, risk_level, download_count,
       embedding <=> :queryEmbedding AS distance
  FROM skills
 WHERE status = 'PUBLISHED'
   AND embedding IS NOT NULL
   AND (is_public = TRUE OR acl_entries ??| :aclPatterns)
   AND embedding <=> :queryEmbedding < :maxDistance
 ORDER BY distance
 LIMIT :limit
```

binding 注意事項：

- `queryEmbedding` 使用 `com.pgvector.PGvector`。
- `aclPatterns` 用 `SqlParameterValue(Types.ARRAY, patterns.toArray(new String[0]))`，沿用 `SkillQueryService` 對 `??|` 的處理。
- `limit` 仍由 `SearchController.MAX_LIMIT = 50` cap。

### 4.4 Embedding Write Port

```java
interface SearchEmbeddingRepository {
    void upsertEmbedding(String skillId, String content, float[] embedding, String model, Instant updatedAt);
    void clearEmbedding(String skillId, Instant updatedAt);
}
```

內容來源：

```text
embedding_content = skills.name + " " + latest SKILL.md frontmatter.name + " " + latest SKILL.md frontmatter.description
```

建議 implementation 用 `NamedParameterJdbcTemplate`：

```sql
UPDATE skills
   SET embedding_content = :content,
       embedding = :embedding,
       embedding_model = :model,
       embedding_updated_at = :updatedAt
 WHERE id = :skillId
```

`clearEmbedding` 用於 suspend：

```sql
UPDATE skills
   SET embedding = NULL,
       embedding_content = NULL,
       embedding_updated_at = :updatedAt
 WHERE id = :skillId
```

### 4.5 Aggregate Read Guard

`Skill` class 不加以下欄位：

```java
// 禁止
@Column("embedding")
private float[] embedding;
```

所有 `SkillRepository.@Query` 不能使用 `SELECT * FROM skills`。既有 `findByAuthorAndName` 改成 explicit list，至少包含 `Skill` constructor / field access 需要的 mapped columns：

```sql
SELECT id, name, description, author, category, category_display,
       author_name_snapshot, status, latest_version, risk_level,
       download_count, average_rating, review_count, acl_entries,
       is_public, owner_id, created_at, updated_at, version
  FROM skills
 WHERE LOWER(author) = LOWER(:author)
   AND LOWER(name) = LOWER(:name)
 ORDER BY created_at DESC, id DESC
 LIMIT 1
```

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/resources/db/migration/Vxx__skill_embedding_columns.sql` | new | 新增 `skills.embedding_*` 欄位與 HNSW index；移除或停用 `vector_store`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchEmbeddingRepository.java` | new | 專責 `skills.embedding_*` upsert / clear。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java` | new | semantic SQL row DTO，轉成既有 `SemanticSearchResult`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | modify | 改用 `NamedParameterJdbcTemplate` 查 `skills.embedding`；移除 `SkillshubPgVectorStore` 與 `skillRepo.findAllById` 補欄位。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java` | modify | publish/reactivate 走 `SearchEmbeddingRepository.upsertEmbedding`；suspend 走 `clearEmbedding`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java` | delete | runtime 不再需要自訂 VectorStore table wrapper。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | modify | 移除 `UPDATE vector_store ...`；只重建 `skills.acl_entries`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java` | modify | `findByAuthorAndName` 改 explicit column list；禁止 `SELECT * FROM skills`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java` | modify | reset allowlist 移除 `vector_store`；seed skill 後 embedding 由 listener / test helper 寫入 `skills`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/*` | modify/new | 更新 semantic search integration / visibility / ACL tests，不再 seed `vector_store`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/*` | new/modify | 加 aggregate non-mapping guard：embedding 存在時 `findById/save` 不覆蓋。 |
| `docs/grimo/architecture.md` | modify | Vector Store 段改為 `skills.embedding` 同表設計；說明 aggregate 不 mapping embedding。 |
| `docs/grimo/development-standards.md` | modify | Permission / Sharing Contract 改掉 `vector_store.acl_entries` 投影說法。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | S186 狀態/點數同步。 |

---

## 6. Task Plan（/planning-tasks S186）

### 6.1 Pre-flight 結果

2026-05-16 22:00 CST 進入 `$planning-tasks S186`。本輪只拆 S186，不啟動 S187。`docs/grimo/tasks/` 目前仍有 S178/S185 舊 task files；依 user 最新順序「問題修正先 S186 再 S187」，S186 task files 先建立，S187 task loop 必須等 S186 ship 後才開始。

已讀文件與既有證據：

| 檔案 / 命令 | 看到什麼 | 對 task plan 的影響 |
|---|---|---|
| `docs/grimo/PRD.md` P5 | 語意搜尋要用自然語言找技能，結果按語意相關度排序。 | S186 不改 API endpoint 目的；只改資料來源從 `vector_store` 到 `skills.embedding`。 |
| `docs/grimo/specs/archive/2026-05-03-S107-semantic-search-projection-fields.md` §7 | S107 已把 search result card 欄位改回 canonical `Skill` source，因為 `vector_store.metadata` 曾 stale。 | S186 T02 要一次從 `skills` row 回 card 欄位，不再 `findAllById` 補資料。 |
| `docs/grimo/specs/archive/2026-05-08-S157-semantic-search-not-functional.md` §7 | S157 證明 semantic search 必須有真實 embedding fixture / deterministic stub；只看 metadata 會漏欄位。 | S186 T02/T03 測試要直接 seed `skills.embedding_*` 或走 listener 寫入，不再 seed `vector_store`。 |
| `docs/grimo/specs/archive/2026-05-15-S177-is-public-first-search-visibility.md` §7 | S177 已把 public visibility source 改成 `skills.is_public`，`vector_store.is_public` 只是投影。 | S186 T04 要移除 `SkillAclProjectionListener` 對 `vector_store` 的更新，semantic SQL 直接讀 `skills.is_public / skills.acl_entries`。 |
| `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SkillEmbeddingColocationPocTest` | PASS；`BUILD SUCCESSFUL in 2m 39s`。 | POC 已證明同表 embedding + ACL query 可行，Phase 1 不再另建 `poc/S186/`。 |
| `git status --short` | 進 planning 前乾淨。 | 可以直接建立 S186 task files 並單獨 commit。 |

### 6.2 POC Decision

POC: not required for task creation — §2.5 的 `SkillEmbeddingColocationPocTest` 已覆蓋三個設計假設：

1. `skills.embedding_*` 可以存在於 DB row，但 `Skill` aggregate 不 mapping，也不會被 `skillRepo.save(skill)` 清掉。
2. Semantic search 可以只查 `skills.embedding + skills.status + skills.is_public + skills.acl_entries`，完全不需要 `vector_store` row。
3. SQL plan shape 可保持單表 `skills`，不包含 `vector_store`。

未驗證的大資料量 HNSW planner 行為不阻塞 task planning；T06 會把 `EXPLAIN (ANALYZE, BUFFERS)` 證據寫入 §7。

### 6.3 Task Files

| 順序 | Task | 狀態 | AC | 做完會看到什麼 |
|---|---|---|---|---|
| 1 | [S186-T01 schema + aggregate guard](../tasks/2026-05-16-S186-T01-schema-aggregate-guard.md) | PASS | AC-S186-1 | DB 有 `skills.embedding_*` 欄位和 HNSW index；`vector_store` 已 drop；`SkillRepository.findByAuthorAndName` 不再 `SELECT *`。 |
| 2 | [S186-T02 semantic SQL from skills](../tasks/2026-05-16-S186-T02-semantic-sql-from-skills.md) | PASS | AC-S186-2, AC-S186-3, AC-S186-8 | `GET /api/v1/search/semantic` 從 `skills.embedding` 回 public / granted private skill，card 欄位直接來自同一 row。 |
| 3 | [S186-T03 embedding write path](../tasks/2026-05-16-S186-T03-embedding-write-path.md) | PASS | AC-S186-5 | `SkillVersionPublishedEvent` 後 `skills.embedding_content / embedding / embedding_model / embedding_updated_at` 更新，其他 skill 欄位不被覆蓋；`SkillSuspendedEvent` 清空 embedding。 |
| 4 | [S186-T04 remove vector ACL projection](../tasks/2026-05-16-S186-T04-remove-vector-acl-projection.md) | PASS | AC-S186-4 | `PUT /visibility` 或 grant 變更 commit 後，下一次 semantic search 直接用 `skills` row，不等任何 `vector_store` listener。 |
| 5 | [S186-T05 vector-store cleanup sweep](../tasks/2026-05-16-S186-T05-vector-store-cleanup-sweep.md) | PASS | AC-S186-6 | `rg -n "vector_store|SkillshubPgVectorStore" backend/src/main/java backend/src/test/java` 只剩允許的舊 migration test 引用；active runtime/test code 不再讀寫舊表。 |
| 6 | [S186-T06 docs and explain evidence](../tasks/2026-05-16-S186-T06-docs-explain-evidence.md) | PASS | AC-S186-7 | spec §7 有 semantic SQL `EXPLAIN (ANALYZE, BUFFERS)` 的實際數字；architecture / standards 不再把 runtime search 說成依賴 `vector_store`。 |

### 6.4 AC Coverage

| AC | Task |
|---|---|
| AC-S186-1 | T01 |
| AC-S186-2 | T02 |
| AC-S186-3 | T02 |
| AC-S186-4 | T04 |
| AC-S186-5 | T03 |
| AC-S186-6 | T05 |
| AC-S186-7 | T06 |
| AC-S186-8 | T02 |

### 6.5 Execution Order

1. T01 must run first because production code cannot write `skills.embedding_*` before V27 exists, and `vector_store` cannot be dropped until repository guard is in place.
2. T02 depends on T01 because semantic SQL reads `skills.embedding` and must prove no `vector_store` row is needed.
3. T03 depends on T01/T02 because write path must produce rows that T02 semantic SQL can read.
4. T04 depends on T02 because visibility/ACL lag is only fixed after semantic search reads `skills` directly.
5. T05 depends on T03/T04 because cleanup is only safe after runtime read/write paths no longer reference `SkillshubPgVectorStore`.
6. T06 runs last because it records implementation evidence and syncs docs to actual code.

### 6.6 Task Results

2026-05-16 S186-T01 PASS：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.domain.SkillRepositoryEmbeddingColumnTest --tests io.github.samzhu.skillshub.db.SkillEmbeddingMigrationTest` 先紅後綠；最後 `BUILD SUCCESSFUL in 2m 48s`。完成 `V27__skill_embedding_columns.sql`、`SkillRepository.findByAuthorAndName` explicit column list、`SkillRepositoryEmbeddingColumnTest`、`SkillEmbeddingMigrationTest`。

2026-05-16 S186-T02 PASS：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SemanticSearchFromSkillsTest --tests io.github.samzhu.skillshub.search.SemanticSearchServiceVisibilityTest` 先紅後綠；最後 `BUILD SUCCESSFUL in 2m 49s`。完成 `SemanticSearchService` direct `skills.embedding` SQL、`SkillSemanticHit` row DTO、`SemanticSearchFromSkillsTest`，並保留 S177 空 ACL 不加 public pseudo principal 的 unit test。

2026-05-16 S186-T03 PASS：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.search.SearchEmbeddingRepositoryTest --tests io.github.samzhu.skillshub.search.SearchProjectionEmbeddingWriteTest` 先紅後綠；RED 是兩個新測試 class 尚不存在，Gradle 回 `No tests found`；GREEN 最後 `BUILD SUCCESSFUL in 2m 38s`。完成 `SearchEmbeddingRepository` 對 `skills.embedding_*` 的 upsert / clear，`SearchProjection` 的 created / published / reactivated / suspended listener 不再寫 `vector_store`，改為更新或清空同 row embedding 欄位。

2026-05-16 S186-T04 PASS：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.security.SkillAclProjectionListenerEmbeddingColocationTest --tests io.github.samzhu.skillshub.search.SemanticSearchVisibilityLagTest` 先紅後綠；RED 是 `SkillAclProjectionListenerEmbeddingColocationTest` 等不到 `skills.acl_entries` 出現 Bob read grant，shutdown 時留下 `SkillGrantedEvent` 未完成；GREEN 最後 `BUILD SUCCESSFUL in 3m 11s`。完成 `SkillAclProjectionListener` 移除 `SELECT is_public` 與 `UPDATE vector_store ...`，Grant event 只重建 `skills.acl_entries`；新增 semantic visibility/grant tests 確認下一次 search 直接讀同一筆 `skills` row。

2026-05-16 S186-T05 PASS：RED：`rg -n "vector_store|SkillshubPgVectorStore" backend/src/main/java backend/src/test/java` 一開始列出 `SkillshubPgVectorStore.java`、`TestDataController` reset allowlist、search/security/delete tests 的舊表讀寫。GREEN：同一 grep 只剩 `backend/src/test/java/io/github/samzhu/skillshub/db/*MigrationTest.java` 的歷史 migration references；`rg ... | rg -v "backend/src/test/java/io/github/samzhu/skillshub/db/"` 無輸出。`cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.search.*'` 最後 `BUILD SUCCESSFUL in 2m 52s`。完成刪除 custom vector store class、舊 vector-store-specific tests/fixture，TestDataController reset allowlist 改為 15 張表，並新增 `VectorStoreRuntimeRemovalTest` 防止 active runtime/test path 再引用舊表或舊 class。

2026-05-16 S186-T06 PASS：RED：`rg -n "SkillshubPgVectorStore|vector_store\\.acl_entries|vector_store\\.is_public" docs/grimo/architecture.md docs/grimo/development-standards.md` 列出 architecture / standards 仍把 runtime semantic search 說成依賴舊獨立向量表。GREEN：`SemanticSearchExplainEvidenceTest` 以 public/private/draft 三筆 `skills.embedding` fixture 跑 `EXPLAIN (ANALYZE, BUFFERS)`；docs grep 改成 0 筆。`cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.search.SemanticSearchExplainEvidenceTest'` 最後 `BUILD SUCCESSFUL in 2m 5s`；S186 顯式 test class 清單最後 `BUILD SUCCESSFUL in 2m 18s`。`cd backend && ./gradlew test --tests '*S186*'` 不可用，因為 Gradle `--tests` filter 不匹配 JUnit `@Tag("S186")`。

---

## 7. Implementation Results（Phase 4 QA pending）

T01-T06 已完成；本節先保存 task-level implementation evidence。`/planning-tasks S186` 下一輪應進 Phase 4，跑標準 verification gate、整理 QA review，通過後再交給 `$shipping-release`。

### 7.1 Task Result Summary

| Task | AC | Result | Evidence |
|---|---|---|---|
| T01 schema + aggregate guard | AC-S186-1 | PASS | V27 新增 `skills.embedding_*` / `idx_skills_embedding_hnsw` 並 drop 舊獨立向量表；`Skill` aggregate 不 mapping embedding。 |
| T02 semantic SQL from skills | AC-S186-2, AC-S186-3, AC-S186-8 | PASS | `SemanticSearchService` 直接查 `skills.embedding`，同 row 回 card fields + ACL/public filter。 |
| T03 embedding write path | AC-S186-5 | PASS | `SearchProjection` publish/reactivate upsert `skills.embedding_*`；suspend clear embedding。 |
| T04 remove vector ACL projection | AC-S186-4 | PASS | `SkillAclProjectionListener` 只重建 `skills.acl_entries`；semantic search 下一次 query 直接讀同一 row。 |
| T05 cleanup sweep | AC-S186-6 | PASS | active runtime/test path 的 `vector_store` / `SkillshubPgVectorStore` reference 已清除；只保留舊 migration tests。 |
| T06 docs + EXPLAIN evidence | AC-S186-7 | PASS | `SemanticSearchExplainEvidenceTest` 記錄 `EXPLAIN (ANALYZE, BUFFERS)`；architecture / standards 同步 S186 後 runtime 事實。 |

### 7.2 EXPLAIN Evidence（AC-S186-7）

執行：

```bash
cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.search.SemanticSearchExplainEvidenceTest'
```

Fixture：

| id | status | visibility | embedding |
|---|---|---|---|
| `skill-public` | `PUBLISHED` | `is_public=true` | non-null |
| `skill-private` | `PUBLISHED` | `is_public=false`, `acl_entries=["user:alice:read"]` | non-null |
| `skill-draft` | `DRAFT` | `is_public=true` | non-null |

Observed plan summary:

| Field | Value |
|---|---|
| Top node | `Limit` → `Sort` → `Index Scan using idx_skills_status on skills` |
| HNSW used? | No |
| HNSW index present? | Yes, verified by `SkillEmbeddingMigrationTest`; not selected for this 3-row fixture |
| Buffers | query nodes: `shared hit=12`; planning: `shared hit=33 read=1` |
| Rows returned | 1 public published row |
| Rows removed by filter | 1 private published row |
| Planning Time | `0.139 ms` |
| Execution Time | `0.069 ms` |

精簡 raw plan：

```text
Limit (actual time=0.042..0.042 rows=1 loops=1)
  Buffers: shared hit=12
  ->  Sort (actual time=0.041..0.041 rows=1 loops=1)
        Sort Key: ((embedding <=> '[1,0,...]'::vector))
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=12
        ->  Index Scan using idx_skills_status on skills (actual time=0.029..0.034 rows=1 loops=1)
              Index Cond: ((status)::text = 'PUBLISHED'::text)
              Filter: ((embedding IS NOT NULL) AND ((embedding <=> '[1,0,...]'::vector) < '2'::double precision) AND (is_public OR (acl_entries ?| ('{}'::cstring)::text[])))
              Rows Removed by Filter: 1
              Buffers: shared hit=9
Planning:
  Buffers: shared hit=33 read=1
Planning Time: 0.139 ms
Execution Time: 0.069 ms
```

Planner 沒使用 `idx_skills_embedding_hnsw` 的原因：這個 verification fixture 只有 3 筆 row，PostgreSQL 選 `idx_skills_status` + in-memory sort 成本更低。S186 不在本 task 強制 planner setting；若 production semantic query 在大資料量下 p95 超過 200ms，後續再用實際資料量評估 `hnsw.ef_search`、partial index、iterative scan 或 query shape 調校。

### 7.3 Current Verification Snapshot

| Command | Result |
|---|---|
| `cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.search.SemanticSearchExplainEvidenceTest'` | PASS — `BUILD SUCCESSFUL in 2m 5s` |
| `cd backend && ./gradlew test --tests '*S186*'` | N/A — Gradle 回 `No tests found for given includes: [*S186*]`，這個 filter 不匹配 JUnit tag |
| `cd backend && ./gradlew test --tests 'io.github.samzhu.skillshub.skill.domain.SkillRepositoryEmbeddingColumnTest' --tests 'io.github.samzhu.skillshub.db.SkillEmbeddingMigrationTest' --tests 'io.github.samzhu.skillshub.search.SemanticSearchFromSkillsTest' --tests 'io.github.samzhu.skillshub.search.SemanticSearchServiceVisibilityTest' --tests 'io.github.samzhu.skillshub.search.SearchEmbeddingRepositoryTest' --tests 'io.github.samzhu.skillshub.search.SearchProjectionEmbeddingWriteTest' --tests 'io.github.samzhu.skillshub.skill.security.SkillAclProjectionListenerEmbeddingColocationTest' --tests 'io.github.samzhu.skillshub.search.SemanticSearchVisibilityLagTest' --tests 'io.github.samzhu.skillshub.search.VectorStoreRuntimeRemovalTest' --tests 'io.github.samzhu.skillshub.search.SemanticSearchExplainEvidenceTest'` | PASS — `BUILD SUCCESSFUL in 2m 18s` |
| `rg -n "SkillshubPgVectorStore|vector_store\\.acl_entries|vector_store\\.is_public" docs/grimo/architecture.md docs/grimo/development-standards.md` | PASS — no output |

### 7.4 Pending Phase 4

- `scripts/verify-all.sh` 尚未在本節跑完；下一輪由 `/planning-tasks S186` Phase 4 或 `/verifying-quality S186` 執行。
- 正式站 Chrome / Cloud Run log 覆測尚未在本節完成；本輪 execution context 沒有可呼叫的 Chrome automation tool。
