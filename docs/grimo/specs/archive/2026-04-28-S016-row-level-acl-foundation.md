# S016: Row-Level ACL 基礎建設（JSONB acl_entries + GIN + PermissionEvaluator）

> Spec: S016 | Size: M(13) | Status: ✅ Done（2026-04-29；待 `/shipping-release` archive）
> Date: 2026-04-28
> Depends: S014 ✅（PostgreSQL JDBC + V1 schema + 既有 `vector_store.owner` 鋪路欄位）
> Blocks: S017（ACL-aware 語意搜尋；消費本 spec 的 `acl_entries` schema + `?|` SQL pattern）、S018 部分（`@PreAuthorize("hasPermission(...)")` 整合 — S018 在 S016 ship 前 graceful degrade 用 `hasRole('admin')` 占位）

> **本 spec 自包含**：所有載重設計決策的研究結論已內聯於 §2.4 / §2.5。

---

## 1. Goal

建立 Skills Hub 的 **row-level ACL 基礎建設層** — 讓「使用者只看得到 / 改得到自己有權限的 skill」這個能力跑起來，並為 S017（語意搜尋 ACL filter）、S018（suspend/reactivate 授權）、未來 B7（組織層級）/ B8（軟結構）統一鋪路。

**簡單講**：以前任何匿名使用者都能 POST `/api/v1/skills` 建立 skill、PUT `/api/v1/skills/{id}/versions` 改別人的 skill；以後每個 skill row 自己帶一個 `acl_entries` JSONB 陣列（內容形如 `["user:alice:read", "user:alice:write", "group:engineering:read"]`），Controller 端透過 `@PreAuthorize("hasPermission(#id, 'Skill', 'write')")` 擋下無權的人。授權邏輯由 `PermissionEvaluator` Strategy/Registry 集中處理；ACL 變更走 ES domain events（`SkillAclGranted` / `SkillAclRevoked`）有完整 audit trail。

```
┌── Before S016 ─────────────────────────────────────────────────┐
│   any caller → POST /api/v1/skills/{id}/versions → 200         │
│   無 row-level 權限；任何人能改任何 skill                      │
│   skills 表只有 author（純 string 標籤，不參與授權）          │
│   vector_store 表已有 owner 欄位（S014 鋪路），未啟用          │
└────────────────────────────────────────────────────────────────┘
                                ↓ S016（本 spec）
┌── After S016 ──────────────────────────────────────────────────┐
│   skills + vector_store 兩表新增 acl_entries JSONB + GIN index │
│   V2 migration 從 author / owner 衍生初始 ACL                  │
│   SkillshubPermissionEvaluator + PermissionStrategy registry   │
│   CurrentUser 加 groups; AclPrincipalExpander 展開 patterns    │
│   SkillCommandController write 端套 @PreAuthorize（403 擋）    │
│   POST/DELETE/GET /api/v1/skills/{id}/acl 三個 ACL CRUD 端點  │
│   ACL 變更走 SkillAclGranted/Revoked domain events            │
│   為 S017 doSimilaritySearch + S018 hasPermission(...) 鋪好路 │
└────────────────────────────────────────────────────────────────┘
```

### 與 PRD 的對應

- **PRD §Backlog B1（Admin / Publisher / Consumer 三層角色）** — 本 spec 提供「每個 row 自己決定誰能讀寫刪」的底層機制；B1 後續可在 ACL 層上掛角色語意（例如 `role:admin:*` 萬用授權）
- **PRD §Backlog B7（組織層級管理）+ B8（軟結構：戰情室、合作專案）** — JSONB 字串陣列的 type 命名空間（`user:` / `group:` / `org:` / `dept:` / `room:`）為這兩個 spec 留 zero-modification 擴充點；S016 MVP 僅啟用 `user:` + `group:` + `role:`
- **ADR-001 §3.1（Firestore array-contains-any 30 元素天花板觸發 PostgreSQL 遷移）** — 本 spec 是 ADR-001 的核心兌現；遷到 PG + JSONB GIN 後，patterns 數量無上限，企業級多層次組織模型可行

### ADR-001 修訂注記（jsonb_path_ops → jsonb_ops）

研究 Phase 揭露 ADR-001 §3.2 / §8 References 與 reference repo `samzhu/spring-acl-jsonb` schema 都寫 `USING GIN (acl_entries jsonb_path_ops)`，但 PostgreSQL 16 [官方 docs §JSON Indexing](https://www.postgresql.org/docs/16/datatype-json.html#JSON-INDEXING) 明確：`jsonb_path_ops` operator class **不支援** key-existence operators（`?` / `?|` / `?&`），只支援 containment（`@>` / `@?` / `@@`）。S016 V2 migration 必須用 default `jsonb_ops`（即 `USING GIN (acl_entries)` 不加 operator class 後綴）。本 spec ship 後 ADR-001 §3.2 + §8 同步修訂。詳 §2.4 Challenge #1。

### 事件驅動架構（既有，本 spec 擴充）

```
SkillAclController.grant(skillId, AclEntryRequest)
   │
   ├─▶ skill = loadAggregate(skillId)              ← 完整 replay 重建狀態（與 S014 既有路徑一致）
   │      apply(SkillCreated)
   │      apply(SkillVersionPublished)
   │      apply(SkillAclGranted) × N               ← 之前已 grant 過的 entries
   │
   ├─▶ event = skill.grantAcl(cmd)                ← aggregate 驗業務規則（無重複 grant、type 合法）
   │
   └─▶ saveAndPublish(skillId, "SkillAclGranted", payload, skill.nextSequence(), event)
          eventStore.save(domainEvent)             ← 持久化 generic DomainEvent
          ApplicationEventPublisher.publish(SkillAclGrantedEvent)
                  └─▶ SkillProjection.on(SkillAclGrantedEvent)
                              └─▶ jdbc.update("UPDATE skills SET acl_entries = acl_entries || ?::jsonb")
```

S014 既有的 ES + CQRS 流程不變；S016 只新增 2 個 event types + 2 個 listener handlers + 2 個 atomic UPDATE repository methods。

---

## 2. Approach

### 2.1 關鍵設計決策（共 13 項）

| # | 決策 | 選擇 | 理由 | 否決的替代 |
|---|------|------|------|-----------|
| 1 | GIN operator class | **default `jsonb_ops`**（即 `USING GIN (acl_entries)` 不加後綴）| `jsonb_path_ops` 不支援 `?|` operator（PostgreSQL 16 docs 明確）— ADR-001 §3.2 與 reference repo `samzhu/spring-acl-jsonb` 兩處皆有此 BUG，S016 一併修正 | `jsonb_path_ops`（雖索引較小但用不到 `?|`，無意義） |
| 2 | ACL JSONB schema | **`["type:principal:permission", ...]` flat string array**（per `samzhu/spring-acl-jsonb` 範本） | 一個 entry 一個 string；GIN(jsonb_ops) 對每個 array element 建 1 個 index entry，`?|` operator 一次 SQL 比對任意 patterns | 物件陣列（`[{type, principal, permission}, ...]`）— 需 `@>` containment 查詢、表達力差、index 體積大 |
| 3 | type 命名空間 | **MVP 啟用 `user:` / `group:` / `role:` 三類**；`org:` / `dept:` / `room:` 預留給 B7/B8（schema 不變、零修改擴充） | YAGNI；MVP 用戶模型只有 user + roles + groups，三個夠用；命名空間 free-form 字串，未來無 schema migration | 一次啟用六類 — 後三類無資料源、純擺設 |
| 4 | permission 詞彙 | **`read` / `write` / `delete` / `suspend` / `reactivate` 五個 verb**；S018 預留 `suspend` / `reactivate` | `read` 對應 GET、`write` 對應 POST/PUT、`delete` 對應 DELETE；S018 spec §4.6 寫 `@PreAuthorize("hasPermission(#id, 'Skill', 'suspend')")`，S016 評估器需認此 verb | `*` 萬用 — 不夠細緻；只 `read`/`write`/`delete` — S018 整合時還要再來一輪修 |
| 5 | PermissionEvaluator 註冊路徑 | **`static @Bean MethodSecurityExpressionHandler`** + `setPermissionEvaluator(...)` | Spring Security 7 唯一官方 documented 路徑；`PrePostMethodSecurityConfiguration` 透過 `@Autowired(required=false) setExpressionHandler()` 把 handler 套入四個 method interceptor；`static` 為破除 circular dep | 直接 `@Bean PermissionEvaluator` — Spring Security 7 不會 auto-detect（研究確認）；`GlobalMethodSecurityConfiguration` extension — 已 deprecated |
| 6 | Strategy/Registry 模式 | **`PermissionStrategy` interface + `DelegatingPermissionEvaluator` registry + `SkillPermissionStrategy` impl** | 對齊 reference repo 模式 + ADR-001 §5「Strategy/Registry」措辭；MVP 雖只有 `Skill` 一個 aggregate 但 PRD B7（Workspace）/ B8（WarRoom）已預示會有；新增 aggregate 時 zero-mod evaluator | 單一 `SkillshubPermissionEvaluator` switch on targetType — B7/B8 進來時要重構 |
| 7 | ACL 變更模式 | **走 domain events**（`SkillAclGranted` / `SkillAclRevoked`）→ `SkillProjection` 監聽 → atomic JSONB UPDATE | 對齊 development-standards §23「核心域使用 Aggregate + ES + CQRS」；完整 audit trail（誰在何時授權誰）滿足合規剛性需求；同 transaction 一致性（與 S014 既有 saveAndPublish 模式一致） | 直接 SQL UPDATE（reference repo 模式）— 破壞 ES 一致性、無 audit、與 development-standards §23 不符 |
| 8 | ACL CRUD REST shape | **`POST /api/v1/skills/{id}/acl`（grant）+ `DELETE /api/v1/skills/{id}/acl?type=...&principal=...&permission=...`（revoke）+ `GET /api/v1/skills/{id}/acl`（list）** | REST 慣用法；DELETE 用 query params 表達 idempotent revoke；reference repo 模式微調 | 動詞式 `/acl/grant` + `/acl/revoke` — 非 REST；DELETE 用 body — 部分 client / proxy 不支援 DELETE body |
| 9 | CurrentUser 擴展 | **加 `groups: List<String>`**（從 JWT `groups` claim / LAB mode 預設空 list）；`orgs` / `depts` 留 B7 spec | MVP YAGNI；JWT 含 `groups` claim 是 OIDC 慣例；LAB mode 不模擬組織結構（fallback 到空 list） | 一次加 groups + orgs + depts — claim 不存在、純擺設；不擴 — PermissionEvaluator 自行處理破壞既有 abstraction |
| 10 | Spring Data JDBC `?|` SQL 路徑 | **`NamedParameterJdbcTemplate` 直接寫 `?|`，不需 escape**（Spring `NamedParameterUtils.parseSqlStatement()` 已 native 識別 PostgreSQL JSONB 三個 `?` operators 並跳過） | 研究確認（spring-framework `NamedParameterUtils.java` 原始碼有專門 skip `??` / `?|` / `?&` 的 logic）；可直接寫 `@Query("... WHERE acl_entries ?| :patterns")` | raw `JdbcTemplate` 配 `??|` JDBC escape — 既有 codebase 用 NamedParam（`SkillReadModelRepository` 等），統一風格 |
| 11 | `?|` SQL ARRAY 參數綁定 | **`new SqlParameterValue(Types.ARRAY, String[])` 包 `MapSqlParameterSource`**（避開 `addValue("k", List.of(...))` 的 IN-list 自動展開） | 研究確認 `NamedParameterJdbcTemplate` 對 `Iterable<?>` 參數預設展成多個 `?` placeholder（IN-list），會破壞 `?|` 語法；用 `SqlParameterValue + Types.ARRAY` 強制走 `ps.setArray()` 路徑 | `addValue("k", list)` — IN-list expansion bug；`createArrayOf("text", String[])` callback — code 比較髒 |
| 12 | `List<String>` JSONB converter | **新增 `StringListJsonbConverter`**（`@WritingConverter` + `@ReadingConverter`，鏡射既有 `MapJsonbConverter` 模式） | 對齊 S014 既有 JSONB round-trip pattern（`frontmatter` / `risk_assessment` 等都走同一路徑）；`Persistable<String>.isNew()=true` 強制 INSERT 路徑 | 直接 `String` 自寫序列化 — 重新發明輪子；annotation `@JsonbColumn` 自訂 — Spring Data JDBC 無此 mechanism |
| 13 | Modulith allowedDependencies | **`skill/package-info.java` 加 `"shared :: security"`**（既有為 `["shared :: events", "shared :: api", "storage"]`） | `skill/security/SkillPermissionStrategy` 需 `import` `shared/security/PermissionStrategy` interface；無此宣告 `ApplicationModules.verify()` 紅 | 把 `PermissionStrategy` 放 `skill/` — 違反 dispatcher 中立原則（B7 加 `Workspace` 時又要拆出來） |

### 2.2 與既有架構的契合

| 維度 | 現況（v1.1.1 + S014）| S016 變動 |
|------|---------------------|-----------|
| **儲存層** | PostgreSQL + Spring Data JDBC（S014 後）| **不變** |
| **Event Store** | `domain_events` 表（JDBC, S014 後）| **不變** |
| **Read Models** | 4 個 `@Table` records；`skills` 表已 6 欄 + 5 indices | **加 `acl_entries JSONB NOT NULL DEFAULT '[]'` 欄位 + 1 個 GIN index**（V2 migration） |
| **Vector Store** | `vector_store` 表 6 欄（含 `owner` / `skill_id` 自訂欄位）| **加 `acl_entries JSONB NOT NULL DEFAULT '[]'` 欄位 + 1 個 GIN index**；`SkillshubPgVectorStore.INSERT_SQL` 從 6 欄升 7 欄 |
| **Aggregate Root** | `Skill` 部分重建（追蹤版本號 + sequence）| **加 grantAcl / revokeAcl business methods + apply(SkillAclGranted/Revoked)**；不擴狀態（acl_entries 不在 aggregate state） |
| **Domain Events** | SkillCreated / SkillVersionPublished / SkillDownloaded（既有；S018 將加 SkillSuspended/Reactivated）| **加** SkillAclGranted / SkillAclRevoked |
| **Commands** | CreateSkillCommand / PublishVersionCommand | **加** GrantAclCommand / RevokeAclCommand |
| **Read Listener** | `SkillProjection`（DRAFT / Published / Downloaded）| **加** `on(SkillAclGrantedEvent)` / `on(SkillAclRevokedEvent)` listeners + 2 個 atomic UPDATE repo methods |
| **Spring Modulith 邊界** | shared / skill / security / search / analytics / storage | **`skill/package-info.java` 加 `"shared :: security"` allowedDependencies**；`skill/security/` 新 sub-package |
| **CQRS + ES 模式** | Aggregate Root → events → projection listener | **不變**（這是核心模式，本 spec 強化它） |
| **API Endpoints** | POST / PUT / GET 既有 | **既有 write 端套 `@PreAuthorize`**；**加** POST / DELETE / GET `/api/v1/skills/{id}/acl` |
| **Spring Security** | OAuth2 RS（S011）+ LAB mode（S012）+ `@EnableMethodSecurity` 已啟用 | **加 `static @Bean MethodSecurityExpressionHandler`** + 註冊 `PermissionEvaluator`；`@PreAuthorize` 第一次真正啟用業務授權 |
| **Frontend** | React 19 SPA | **不動** — 本 spec 純後端基礎建設；前端 ACL UI 由後續 spec 覆蓋（不在本 spec scope） |

### 2.3 Schema 設計（範例資料）

#### `skills` 表的 acl_entries 演化

V2 migration 從 `author` 欄位衍生 owner-style ACL（owner-as-author 簡化模型 — MVP 階段每個 skill 的 author 即 owner，授予完整 read/write/delete 權限）：

**Before S016：**

| id | name | author | acl_entries（不存在）|
|----|------|--------|-----|
| `abc-1` | docker-helper | `alice` | — |
| `abc-2` | k8s-deploy | `bob` | — |

**After S016 V2 migration（initial backfill）：**

| id | name | author | acl_entries |
|----|------|--------|-------------|
| `abc-1` | docker-helper | `alice` | `["user:alice:read", "user:alice:write", "user:alice:delete"]` |
| `abc-2` | k8s-deploy | `bob` | `["user:bob:read", "user:bob:write", "user:bob:delete"]` |

**After S016 + 後續 grant 操作（runtime mutation 透過 SkillAclGranted event）：**

| id | name | author | acl_entries |
|----|------|--------|-------------|
| `abc-1` | docker-helper | `alice` | `["user:alice:read", "user:alice:write", "user:alice:delete", "group:engineering:read"]` |
| `abc-2` | k8s-deploy | `bob` | `["user:bob:read", "user:bob:write", "user:bob:delete", "user:carol:read"]` |

#### `vector_store` 表的 acl_entries 演化

V2 migration 從既有 `owner` 欄位衍生（與 skills 表同邏輯，但只給 read 權限因為 vector_store 的 chunk 是衍生資料）：

**After V2 migration：**

| id | content | owner | skill_id | acl_entries |
|----|---------|-------|----------|-------------|
| `uuid-1` | "Helps with Docker..." | `alice` | `abc-1` | `["user:alice:read"]` |
| `uuid-2` | "Kubernetes deployment..." | `bob` | `abc-2` | `["user:bob:read"]` |

S017 將在此基礎上加 `?|` filter 到 `SkillshubPgVectorStore.SIMILARITY_SEARCH_SQL`（S016 不碰 SQL；只確保 schema + 寫入端 ready）。

#### Domain Event payload 範例

`SkillAclGranted`：

```json
{
  "id": "evt-acl-001",
  "aggregateId": "abc-1",
  "aggregateType": "Skill",
  "eventType": "SkillAclGranted",
  "payload": {
    "type": "group",
    "principal": "engineering",
    "permission": "read",
    "grantedBy": "admin-user-1"
  },
  "sequence": 7,
  "occurredAt": "2026-05-01T10:00:00Z",
  "metadata": {}
}
```

`SkillAclRevoked`：

```json
{
  "id": "evt-acl-002",
  "aggregateId": "abc-1",
  "aggregateType": "Skill",
  "eventType": "SkillAclRevoked",
  "payload": {
    "type": "group",
    "principal": "engineering",
    "permission": "read",
    "revokedBy": "admin-user-1"
  },
  "sequence": 8,
  "occurredAt": "2026-05-02T15:30:00Z",
  "metadata": {}
}
```

#### CurrentUser 結構演化

```java
// Before S016
record CurrentUser(String userId, List<String> roles) {}

// After S016
record CurrentUser(String userId, List<String> roles, List<String> groups) {}
```

OAuth 模式：`groups` 從 JWT `groups` claim 抽取（OIDC standard claim；若 issuer 無此 claim 則 empty list）。LAB 模式：`groups` 預設空 list（unless `skillshub.security.lab.groups` property 顯式注入；MVP 不開此 property）。

### 2.4 Challenges Considered

> 本節內聯所有對載重決策有貢獻的研究結論，不依賴外部目錄留存。

1. **GIN `jsonb_path_ops` 不支援 `?|` operator（critical fix）**
   - **PostgreSQL 16 [docs §JSON Indexing](https://www.postgresql.org/docs/16/datatype-json.html#JSON-INDEXING)** 明確：`jsonb_path_ops` operator class supports only `@>`, `@?`, `@@`；不支援 `?` / `?|` / `?&` key-existence operators
   - ADR-001 §3.2 + §8 References + reference repo `samzhu/spring-acl-jsonb` 三處都寫 `jsonb_path_ops` — 三處都有同一 BUG
   - 後果：若採 `jsonb_path_ops`，planner 永遠走 seq scan（看似工作，企業規模時效能災難）
   - 修法：S016 V2 migration 用 default `USING GIN (acl_entries)` 不加 operator class 後綴；ADR-001 ship 後修訂
   - Confidence: **Validated**（PostgreSQL 16 官方 docs 直接證據）

2. **`?|` 在 `NamedParameterJdbcTemplate` **仍需** `??|` JDBC escape — [Implementation note: T1 verified]**
   - Spring Framework `NamedParameterUtils.parseSqlStatement()` raw 原始碼包含 skip logic：`if (statement[j] == '?' || statement[j] == '|' || statement[j] == '&') { i = i + 2; continue; }` — 把 `??`、`?|`、`?&` 當 PostgreSQL operator 跳過，不算 named param placeholder
   - **但這只解決 Spring 命名參數 layer 的 placeholder 計數問題**；NamedParameterJdbcTemplate 把 `:name` 轉為 `?` 後傳給 raw JDBC 的 SQL 仍會被 pgJDBC `PgPreparedStatement` parser 重新解析 — 此 layer 的 `?` placeholder 計數不知道 `?|` 是 PostgreSQL operator，會把它算成「兩個」placeholder
   - **T1 實測證據**（2026-04-28 V01 紅）：SQL `WHERE id = ? AND acl_entries ?| ?` 在 NamedParameterJdbcTemplate `queryForObject` 路徑回 `PSQLException: 未設定參數值 3 的內容`（pgJDBC 預期 3 個 placeholder，實際只給 2 個）
   - **正確寫法**：`WHERE acl_entries ??| :patterns`（雙問號）— pgJDBC `Parser.java` unescape `??` → `?`，最終送 PostgreSQL 的 SQL 是 `WHERE acl_entries ?| ?`
   - 此規則對 raw `JdbcTemplate` 與 `NamedParameterJdbcTemplate` 一視同仁（pgJDBC layer 不分上層）
   - Skills Hub `SkillReadModelAclTest`（T1 ship）已採 `??|` 並通過驗證；後續 `SkillPermissionStrategy`（T3）+ S017 search filter SQL 必須沿用此寫法
   - Confidence: **Validated**（T1 `SkillReadModelAclTest.anyKeyMatch_findsRowViaGinIndex` 跑過 pgvector/pg16 Testcontainer + spring-framework + pgjdbc raw source）

3. **`SqlParameterValue(Types.ARRAY, String[])` 避開 IN-list 展開**
   - `NamedParameterJdbcTemplate` 對 `Iterable<?>` / `Collection` 參數預設展成 `?, ?, ?, ?`（IN-list）— 程式設計初衷為 `WHERE id IN (:ids)`
   - 此預設會破壞 `acl_entries ?| :patterns` — 期望單一 SQL ARRAY，被展成多個 `?` placeholder 後語法錯誤
   - 解法：包 `new SqlParameterValue(Types.ARRAY, patternsArray)` 強制走 `ps.setArray(i, sqlArray)` 路徑（`StatementCreatorUtils.setParameterValue()` raw 原始碼確認）
   - Confidence: **Validated**

4. **Spring Security 7 注入自訂 `PermissionEvaluator` 唯一路徑**
   - Spring Security 7 移除 `GlobalMethodSecurityConfiguration` 擴充入口
   - `PermissionEvaluator` bean 直接註冊**不會**被 auto-detect — `PrePostMethodSecurityConfiguration` 沒有 `@Autowired PermissionEvaluator setter`，只有 `@Autowired(required=false) setExpressionHandler(MethodSecurityExpressionHandler)`
   - 唯一路徑：`static @Bean MethodSecurityExpressionHandler` + 內部 `setPermissionEvaluator(...)`
   - **`static` 必要**：破除 `@EnableMethodSecurity` config 與 expression handler bean 之間的 circular dep（[官方 docs](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html) 明文 "must be static"）
   - Confidence: **Validated**（spring-security `PrePostMethodSecurityConfiguration.java` raw 原始碼 + 官方 docs）

5. **Spring Modulith 邊界處理 — `shared :: security` named interface**
   - `shared/security/package-info.java` 已宣告 `@NamedInterface("security")`（S011/S012 鋪路）
   - `skill/package-info.java` 既有 allowedDependencies `["shared :: events", "shared :: api", "storage"]` — 缺 `shared :: security`
   - 新檔 `skill/security/SkillPermissionStrategy.java` import `shared/security/PermissionStrategy` 會被 `ApplicationModules.verify()` 紅
   - 修法：`skill/package-info.java` allowedDependencies 加 `"shared :: security"`
   - Confidence: **Validated**（讀過既有 `skill/package-info.java`）

6. **ACL 寫入冪等性 + race condition**
   - 同一個 ACL entry（如 `"user:alice:read"`）grant 兩次：aggregate 端應 throw（業務不變量「不重複授權」），projection 端 atomic UPDATE 用 `acl_entries || '...'::jsonb` 不去重 — 雙保險，aggregate 是主，projection 是被動跟隨
   - Race：兩個並發 grant 不同 entry 的 transaction — `acl_entries || '...'::jsonb` 是 PostgreSQL row-level lock 路徑（與 `download_count = download_count + 1` 同模式），無 race
   - Revoke：用 `jsonb_array_elements_text` 過濾後 jsonb_agg 重組 — 比 `-` operator 更穩健（`-` 對 array 是按 index 移除，需先 query index）
   - Confidence: **Validated**（PostgreSQL JSONB 操作慣例 + S014 既有 `incrementDownloadCount` atomic UPDATE 模式）

7. **`samzhu/spring-acl-jsonb` reference repo 限制 — Skills Hub 不全盤採用之原因**
   - reference repo（[GitHub](https://github.com/samzhu/spring-acl-jsonb)）是 hexagonal demo，Spring Boot 4.0.0-M3（milestone）；Skills Hub 4.0.6（GA）API 相容性 OK
   - **採用**：JSONB schema convention（type:principal:permission flat string）、DelegatingPermissionEvaluator + ResourcePermissionChecker registry、`@PreAuthorize("hasPermission(#id, 'FQCN', 'verb')")` 三參數形式
   - **不採用**：(a) `X-Username` header filter（Skills Hub 用 OAuth2 RS + LAB mode，不 demo header auth）；(b) `jsonb_path_ops` GIN（如上述 BUG）；(c) 直接 SQL UPDATE（Skills Hub 走 ES events）；(d) FQCN 全限定名 SpEL 字串（Skills Hub 用 Spring Modulith short name `'Skill'` 對齊 module 邊界）
   - Confidence: **Validated**（讀過 `ProjectController` / `DelegatingPermissionEvaluator` / `ProjectPermissionEvaluator` / `schema.sql`）

8. **`@PreAuthorize` 對 anonymous 使用者的行為**
   - Spring Security 7 `AnonymousAuthenticationToken.isAuthenticated() == true`（counterintuitive）；evaluator 內若只檢 `auth.isAuthenticated()` 不能擋 anonymous
   - 後處理由 `ExceptionTranslationFilter` 區分：authenticated user denied → 403 Forbidden（`AccessDeniedHandler`）；anonymous denied → 401 / login redirect（`AuthenticationEntryPoint`）
   - Skills Hub LAB mode 永遠注入 `UsernamePasswordAuthenticationToken`（非 anonymous）；OAuth mode 未帶 JWT 時走 anonymous filter
   - 處理：`SkillshubPermissionEvaluator` 顯式檢 `auth instanceof AnonymousAuthenticationToken` → return false；HTTP layer 自然回 401 / 403
   - Confidence: **Validated**

9. **Backfill 策略 — author / owner 欄位的歷史資料一致性**
   - `skills.author VARCHAR(255) NOT NULL` — V1 schema NOT NULL，所有既有 row 都有 author
   - `vector_store.owner VARCHAR(255)` — V1 schema nullable，但 S014 起 `SearchProjection` 強制寫入；既有 row 應全部有 owner
   - 不一致情境：V1 schema 啟用後，若有 row 因 bug owner=NULL，V2 migration 中的 `WHERE owner IS NOT NULL` 條件會跳過 — 這些 row 留 acl_entries=`[]` 變成「沒人能讀」（fail-secure）；DB ops 可後續手動補
   - 對 S017 的影響：`acl_entries=[]` 的 row `?|` 任何 patterns 都 false → 不會出現在搜尋結果（fail-secure）
   - Confidence: **Validated**

10. **Suspend / Reactivate verb 預留 — S018 整合保證**
    - S018 spec §4.6 寫 `@PreAuthorize("hasPermission(#id, 'Skill', 'suspend')")` / `'reactivate'`
    - S016 `SkillPermissionStrategy.hasPermission()` 直接走 `acl_entries ?| :patterns` SQL — pattern 是 `["user:{userId}:suspend", "role:admin:suspend", ...]`
    - V2 backfill 給 author 完整 `read/write/delete`，**不**自動給 `suspend/reactivate`（管理員專屬動作；admin role 透過 `role:admin:suspend` pattern 取得）
    - 預留路徑：admin user 透過 JWT `roles=["admin"]` claim → `AclPrincipalExpander` 展開 `role:admin:suspend` → `?|` 比對 `acl_entries` 中 `"role:admin:suspend"`（管理員需手動 grant 給某 skill；或全域 super-admin 透過 PermissionStrategy short-circuit `hasRole('admin') → true`）
    - 取捨：本 spec 只實作前者（per-skill grant）；後者 short-circuit 留 S018 ship 時做（S018 spec §4.6 graceful degrade 階段已用 `hasRole('admin')`）
    - Confidence: **Validated**（讀過 S018 spec §4.6）

11. **`SkillshubPgVectorStore.INSERT_SQL` 升 6→7 欄的相容性**
    - 既有 `INSERT_SQL` 6 欄（id / content / metadata / embedding / owner / skill_id）
    - V2 migration 加 `acl_entries JSONB NOT NULL DEFAULT '[]'` 後，舊 INSERT 仍可 work（DEFAULT 補空 array）— 但寫入 row 永遠 acl_entries=`[]` 即「無人能讀」（fail-secure）
    - 同步升 INSERT_SQL：(a) Search Projection 計算 acl_entries（從 owner 衍生 `["user:{owner}:read"]`）；(b) INSERT 帶上此值
    - `ON CONFLICT (id) DO UPDATE` 子句：`acl_entries = COALESCE(EXCLUDED.acl_entries, vector_store.acl_entries)` — re-embed 不覆蓋已 grant 的 entries（per §2.4 #6 race 模式）
    - Confidence: **Validated**（讀過 `SkillshubPgVectorStore.INSERT_SQL` 既有結構）

12. **CurrentUser 擴展對既有 callers 的影響**
    - 既有 `CurrentUser(userId, roles)` callers：`MeController.me()`、各 service `audit field 寫 createdBy = currentUserProvider.userId()`
    - 加 `groups: List<String>` 為 record 的第三個 component — record canonical constructor 簽名變、所有 `new CurrentUser(...)` call site 編譯紅
    - call sites 掃描：`CurrentUserProvider.current()` 內部 3 處 `new CurrentUser(...)`、test 檔約 5 處（unit test）
    - 修法：每處顯式 `new CurrentUser(userId, roles, List.of())` 補空 groups；後續 OAuth path 從 JWT claim 抽取
    - Confidence: **Validated**（grep 過所有 `new CurrentUser(` call sites）

13. **`acl_entries` 不在 Aggregate state — Skill aggregate 不重建 ACL**
    - 設計取捨：Skill aggregate 僅追蹤業務 invariant（版本不重複、status 轉換合法），ACL 是 cross-cutting concern
    - aggregate 重建時忽略 SkillAclGranted/Revoked events（apply 中 `default → no-op`）
    - 業務不變量檢查（如「不能重複 grant 同一 entry」）由 aggregate 維護一個 transient `Set<String> currentAclEntries` field — 重建時從 events 累積，僅用於本次 grant/revoke 的 invariant 檢查，不對外暴露
    - 替代：把 acl_entries 完整列入 Skill state — aggregate 重建變慢（每筆 ACL 一次 apply），但本 spec MVP 規模 acl_entries < 50 entries，cost 可忽略
    - 結論：採前者（minimal aggregate state）— 對齊 development-standards §27「aggregate 只封裝必要 invariant」
    - Confidence: **Validated**

### 2.5 Research Citations

| 來源 | 對本 spec 的支撐點 |
|------|-------------------|
| [PostgreSQL 16 docs — JSON Indexing](https://www.postgresql.org/docs/16/datatype-json.html#JSON-INDEXING) | `jsonb_ops` vs `jsonb_path_ops` operator support 對比表（Challenge #1，決策 #1） |
| [Spring Framework `NamedParameterUtils.java` raw source](https://raw.githubusercontent.com/spring-projects/spring-framework/main/spring-jdbc/src/main/java/org/springframework/jdbc/core/namedparam/NamedParameterUtils.java) | `?|` / `?&` / `??` 自動 skip logic（Challenge #2，決策 #10） |
| [pgjdbc `Parser.java` raw source](https://raw.githubusercontent.com/pgjdbc/pgjdbc/master/pgjdbc/src/main/java/org/postgresql/core/Parser.java) | `??` → `?` unescape 機制（raw JdbcTemplate 路徑，本 spec 不採用但 reference 用） |
| [Spring Security `PermissionEvaluator.java` raw source](https://raw.githubusercontent.com/spring-projects/spring-security/main/core/src/main/java/org/springframework/security/access/PermissionEvaluator.java) | 2-arg + 3-arg interface 簽名（決策 #5，§4.3） |
| [Spring Security `SecurityExpressionRoot.java` raw source](https://raw.githubusercontent.com/spring-projects/spring-security/main/core/src/main/java/org/springframework/security/access/expression/SecurityExpressionRoot.java) | `hasPermission()` SpEL → `permissionEvaluator.hasPermission()` 路由（決策 #5） |
| [Spring Security `PrePostMethodSecurityConfiguration.java` raw source](https://raw.githubusercontent.com/spring-projects/spring-security/main/config/src/main/java/org/springframework/security/config/annotation/method/configuration/PrePostMethodSecurityConfiguration.java) | `@Autowired(required=false) setExpressionHandler` — 證明 `static @Bean MethodSecurityExpressionHandler` 是唯一路徑（Challenge #4，決策 #5） |
| [Spring Security ACLs reference docs](https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html) | Canonical `static @Bean MethodSecurityExpressionHandler` 範例（決策 #5） |
| [Spring Security Method Security reference docs](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html) | `@PreAuthorize` SpEL `hasPermission()` 用法（決策 #5，§4.3） |
| [samzhu/spring-acl-jsonb GitHub repo](https://github.com/samzhu/spring-acl-jsonb) | ACL JSONB schema convention `["type:principal:permission", ...]`（決策 #2）；DelegatingPermissionEvaluator + ResourcePermissionChecker registry（決策 #6）；ACL CRUD endpoint shape（決策 #8 微調） |
| [Spring AI `PgVectorStore.java` raw source](https://raw.githubusercontent.com/spring-projects/spring-ai/main/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java) | `getNativeClient()` 暴露 JdbcTemplate；`schema-validation` 對多餘欄位無害（Challenge #11） |
| [Spring Data JDBC `MappingRelationalConverter` + `CustomConversions`](https://docs.spring.io/spring-data/jdbc/docs/current/reference/html/) | `Converter<List<String>, PGobject>` 註冊路徑（決策 #12） |

**既有 codebase 錨點**（git 永久留存）：
- `backend/.../shared/security/CurrentUser.java` — 加 `groups` field
- `backend/.../shared/security/CurrentUserProvider.java` — JWT `groups` claim 抽取 + LAB mode fallback
- `backend/.../shared/security/SecurityConfig.java:54-95` — 加 `static @Bean MethodSecurityExpressionHandler`
- `backend/.../shared/persistence/MapJsonbConverter.java`（既有，S014）— `StringListJsonbConverter` 模板
- `backend/.../skill/command/SkillCommandService.java` — 加 `grantAcl` / `revokeAcl` methods
- `backend/.../skill/command/SkillCommandController.java` — 既有 endpoints 套 `@PreAuthorize`
- `backend/.../skill/query/SkillProjection.java` — 加 ACL listeners
- `backend/.../skill/query/SkillReadModelRepository.java` — 加 atomic UPDATE 方法（grant / revoke）
- `backend/.../skill/package-info.java` — allowedDependencies 加 `"shared :: security"`
- `backend/.../search/SkillshubPgVectorStore.java` — INSERT_SQL 升 7 欄
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — V1 schema 對照
- `backend/src/main/resources/db/migration/V2__add_acl_entries.sql`（new）— 本 spec 新增

### 2.6 Confidence Classification

| 設計決策 | Confidence | 證據 / POC 計畫 |
|---------|-----------|-----------------|
| GIN(default jsonb_ops) + `?|` | **Validated** | PostgreSQL 16 docs 明文 |
| JSONB array of strings + `?|` ARRAY 參數 | **Validated** | Reference repo + research 完整 |
| `static @Bean MethodSecurityExpressionHandler` | **Validated** | Spring Security raw source + 官方 docs |
| Strategy/Registry（`PermissionStrategy` + `DelegatingPermissionEvaluator`）| **Validated** | Reference repo + Spring DI 標準 |
| ACL 走 domain events | **Validated** | development-standards §23 + S014 既有 saveAndPublish 模式 |
| `?|` in `NamedParameterJdbcTemplate` **仍需** `??|` escape | **Validated** | T1 實測 + pgjdbc `Parser.java`（spec §2.4 #2 已勘誤）|
| `SqlParameterValue(Types.ARRAY, String[])` | **Validated** | spring-jdbc `StatementCreatorUtils.java` 原始碼 |
| `StringListJsonbConverter` 註冊 | **Validated** | S014 既有 `MapJsonbConverter` 模式 |
| Modulith allowedDependencies 加 `"shared :: security"` | **Validated** | 讀過既有 `package-info.java` |
| CurrentUser 加 `groups` 對 callers 影響 | **Validated** | grep 完所有 `new CurrentUser(` call sites |

**POC: not required** — 所有設計決策皆基於既有 code 模式或標準 Spring/Java/PostgreSQL 用法 + 已驗證 raw source。

### 2.7 Validation Pass — pre-handoff drift check

從現況 read 確認：
- ✅ V1 schema `skills.author NOT NULL` — backfill 安全（決策 #9）
- ✅ V1 schema `vector_store.owner` nullable — backfill 條件 `WHERE owner IS NOT NULL`（決策 #9）
- ✅ `SkillshubPgVectorStore.INSERT_SQL` 6 欄結構 — 升 7 欄路徑明確（Challenge #11）
- ✅ `CurrentUser` 是 record，加 component 為 binary breaking change — 須掃 call sites + test fixtures（Challenge #12）
- ✅ `SecurityConfig` `@EnableMethodSecurity` 已啟用（行 56）— 加 `static @Bean MethodSecurityExpressionHandler` 直接生效
- ✅ `skill/package-info.java` 既有 allowedDependencies 缺 `"shared :: security"` — Modulith verify 必紅（決策 #13）
- ✅ S014 既有 `MapJsonbConverter` 註冊在 `JdbcConfiguration.userConverters()` — 模板可鏡射（決策 #12）

無 design drift；可進 §3。

---

## 3. SBE Acceptance Criteria

> 驗證指令：`cd backend && ./gradlew test`（既有 QA strategy 標準入口）
> 測試類別：8 個新測試檔（unit + integration）+ 對既有 `SkillCommandControllerTest` / `MeControllerTest` / `CurrentUserProviderTest` 的回歸更新

```gherkin
Scenario: AC-1 — V2 migration 建立 acl_entries column + GIN(default jsonb_ops) index
  Given V1 schema 已套用（domain_events / skills / skill_versions / flags / download_events / vector_store）
  When  Flyway 套用 V2__add_acl_entries.sql
  Then  skills 表多 acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb 欄位
  And   vector_store 表多 acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb 欄位
  And   pg_indexes 含 idx_skills_acl_entries（amname=gin, opclass 為 default jsonb_ops 即無 jsonb_path_ops 字串）
  And   pg_indexes 含 idx_vector_store_acl_entries（同上）

Scenario: AC-2 — V2 migration backfill skills.acl_entries from author
  Given V1 階段已存在 3 個 skills (author=alice / bob / carol)
  When  Flyway 套用 V2 migration
  Then  skills WHERE author='alice' 的 acl_entries = ["user:alice:read", "user:alice:write", "user:alice:delete"]
  And   skills WHERE author='bob' 的 acl_entries = ["user:bob:read", "user:bob:write", "user:bob:delete"]
  And   無 acl_entries=[] 的 row（all backfilled from NOT NULL author）

Scenario: AC-3 — V2 migration backfill vector_store.acl_entries from owner
  Given V1 階段已存在 vector_store row（owner='alice', skill_id='abc-1'）
  When  Flyway 套用 V2 migration
  Then  該 row 的 acl_entries = ["user:alice:read"]
  And   owner IS NULL 的 row 維持 acl_entries=[]（fail-secure）

Scenario: AC-4 — CurrentUser 加 groups field，OAuth mode 從 JWT groups claim 抽取
  Given JWT 含 claims: { sub: "alice", roles: ["user"], groups: ["engineering", "platform"] }
  When  CurrentUserProvider.current() 呼叫
  Then  回傳 CurrentUser(userId="alice", roles=["user"], groups=["engineering", "platform"])

Scenario: AC-5 — CurrentUser 在 LAB mode 預設空 groups
  Given LAB mode（skillshub.security.oauth.enabled=false）
  And   無 skillshub.security.lab.groups property
  When  CurrentUserProvider.current() 呼叫
  Then  回傳 CurrentUser(userId="lab-user", roles=["admin"], groups=[])

Scenario: AC-6 — DelegatingPermissionEvaluator 路由到 SkillPermissionStrategy
  Given 註冊 SkillPermissionStrategy（supports("Skill")=true）
  And   acl_entries=["user:alice:read"] 的 skill abc-1
  And   currentUser=alice
  When  evaluator.hasPermission(authentication, "abc-1", "Skill", "read")
  Then  return true（dispatcher 找到 strategy + strategy 走 SQL ?| 比對）
  And   evaluator.hasPermission(authentication, "abc-1", "Workspace", "read") return false（無 strategy）

Scenario: AC-7 — hasPermission 對 owner 通過、對非 owner 拒絕（403）
  Given skill abc-1 acl_entries=["user:alice:read", "user:alice:write"]
  When  alice 呼叫 PUT /api/v1/skills/abc-1/versions
  Then  HTTP 200
  When  bob 呼叫 PUT /api/v1/skills/abc-1/versions
  Then  HTTP 403 Forbidden
  And   event store 不變

Scenario: AC-8 — hasPermission 整合 group: principal 模式
  Given skill abc-1 acl_entries=["user:alice:read", "group:engineering:read"]
  And   carol roles=[], groups=["engineering"]
  When  carol 呼叫 GET /api/v1/skills/abc-1
  Then  HTTP 200（透過 group:engineering:read 比對）

Scenario: AC-9 — POST /api/v1/skills/{id}/acl 觸發 SkillAclGranted event
  Given skill abc-1 acl_entries=["user:alice:read", "user:alice:write", "user:alice:delete"]
  And   alice 有 write 權限
  When  alice 呼叫 POST /api/v1/skills/abc-1/acl, body { type: "group", principal: "engineering", permission: "read" }
  Then  domain_events 含一筆 SkillAclGranted, payload={type:"group", principal:"engineering", permission:"read", grantedBy:"alice"}
  And   skills.acl_entries WHERE id='abc-1' 含 "group:engineering:read"
  And   HTTP 201 Created

Scenario: AC-10 — DELETE /api/v1/skills/{id}/acl 觸發 SkillAclRevoked event
  Given skill abc-1 acl_entries=["user:alice:read", "group:engineering:read"]
  And   alice 有 write 權限
  When  alice 呼叫 DELETE /api/v1/skills/abc-1/acl?type=group&principal=engineering&permission=read
  Then  domain_events 含一筆 SkillAclRevoked
  And   skills.acl_entries WHERE id='abc-1' = ["user:alice:read"]
  And   HTTP 204 No Content

Scenario: AC-11 — GET /api/v1/skills/{id}/acl 回傳 current entries
  Given skill abc-1 acl_entries=["user:alice:read", "group:engineering:read"]
  And   alice 有 read 權限
  When  alice 呼叫 GET /api/v1/skills/abc-1/acl
  Then  HTTP 200，body=[{type:"user", principal:"alice", permission:"read"}, {type:"group", principal:"engineering", permission:"read"}]

Scenario: AC-12 — AclPrincipalExpander 展開 user / role / group patterns
  Given CurrentUser(userId="alice", roles=["admin"], groups=["engineering", "platform"])
  When  expander.expand(currentUser, "read")
  Then  回傳 ["user:alice:read", "role:admin:read", "group:engineering:read", "group:platform:read"]

Scenario: AC-13 — `acl_entries ?| :patterns` SQL 走 GIN index（EXPLAIN 驗證）
  Given skills 表已有 ≥ 100 row + V2 migration 完成
  When  EXPLAIN SELECT * FROM skills WHERE acl_entries ?| ARRAY['user:alice:read']::text[]
  Then  query plan 含 "Bitmap Index Scan on idx_skills_acl_entries"
  And   不含 "Seq Scan"（小資料集需 SET enable_seqscan=off 強制驗證）

Scenario: AC-14 — Modulith verify 在加 allowedDependencies 後仍綠
  Given skill/package-info.java allowedDependencies=["shared :: events", "shared :: api", "shared :: security", "storage"]
  When  ApplicationModules.of(SkillshubApplication.class).verify()
  Then  no IllegalStateException

Scenario: AC-15 — `suspend` / `reactivate` verb 在 PermissionEvaluator 認得（S018 預備）
  Given skill abc-1 acl_entries=["role:admin:suspend", "role:admin:reactivate"]
  And   admin user roles=["admin"]
  When  evaluator.hasPermission(authentication, "abc-1", "Skill", "suspend")
  Then  return true
  When  evaluator.hasPermission(authentication, "abc-1", "Skill", "reactivate")
  Then  return true
  When  evaluator.hasPermission(authentication, "abc-1", "Skill", "unknown_verb")
  Then  return false（pattern 不存在；行為等價於否定授權）
```

每個 AC 必須對應至少一個測試（`@DisplayName("AC-N: ...")` 或 `@Tag("AC-N")`）。

### 驗收命令

per `qa-strategy.md`：

```
cd backend && ./gradlew test
```

**Pass 條件**：所有 15 個 AC 對應的測試 green。

---

## 4. Interface / API Design

### 4.1 ACL JSONB schema convention

**Format**：`String` of `"<type>:<principal>:<permission>"`（colon-separated 三段）

**Validated 格式**（per `samzhu/spring-acl-jsonb` `ProjectQuery.java` regex，本 spec 採同範本）：

```
^[a-zA-Z][a-zA-Z0-9_-]{0,49}     # type      (1-50 chars)
:
[a-zA-Z0-9_@.+-]{1,255}           # principal (1-255 chars; allows email, dot-notation)
:
[a-zA-Z][a-zA-Z0-9_.-]{0,99}      # permission (1-100 chars; allows storage.buckets.create dotted)
$
```

**MVP 啟用 type**：`user` / `role` / `group`
**MVP 啟用 permission**：`read` / `write` / `delete` / `suspend` / `reactivate`

### 4.2 Flyway V2 migration

```sql
-- backend/src/main/resources/db/migration/V2__add_acl_entries.sql

-- 1. skills 表加 acl_entries + GIN index（default jsonb_ops，per S016 §2.4 #1）
ALTER TABLE skills
    ADD COLUMN acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_skills_acl_entries
    ON skills USING GIN (acl_entries);   -- default jsonb_ops（支援 ?|）

-- 2. skills backfill — author 即 owner（MVP 簡化模型）
UPDATE skills
SET acl_entries = jsonb_build_array(
    'user:' || author || ':read',
    'user:' || author || ':write',
    'user:' || author || ':delete'
)
WHERE acl_entries = '[]'::jsonb;

-- 3. vector_store 表加 acl_entries + GIN index
ALTER TABLE vector_store
    ADD COLUMN acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_vector_store_acl_entries
    ON vector_store USING GIN (acl_entries);

-- 4. vector_store backfill — owner 為 NOT NULL 才補（fail-secure）
UPDATE vector_store
SET acl_entries = jsonb_build_array('user:' || owner || ':read')
WHERE owner IS NOT NULL
  AND acl_entries = '[]'::jsonb;
```

### 4.3 `PermissionStrategy` interface（shared/security）

```java
package io.github.samzhu.skillshub.shared.security;

import java.util.Set;

import org.springframework.lang.Nullable;

/**
 * Aggregate-level permission strategy — DelegatingPermissionEvaluator 透過 supports() 路由。
 *
 * <p>每個業務 aggregate（Skill, Workspace, ...）一個 @Component 實作；DI 自動收齊。
 * 新增 aggregate 不需修改 dispatcher（Open/Closed Principle）。
 *
 * @see DelegatingPermissionEvaluator
 */
public interface PermissionStrategy {

    /** Matches PreAuthorize SpEL targetType — e.g., 'Skill', 'Workspace'. */
    boolean supports(String targetType);

    /**
     * @param principals 已展開的 patterns（如 ["user:alice:read", "role:admin:read", "group:eng:read"]）
     * @param targetIdOrObject {@code @PreAuthorize("hasPermission(#id, ...)")} 傳入的 id（String）
     *                         或 {@code @PostAuthorize("hasPermission(returnObject, ...)")} 傳入的 domain object
     * @param permission       SpEL 第三參數，如 "read" / "write"
     */
    boolean hasPermission(Set<String> principals,
                          @Nullable Object targetIdOrObject,
                          String permission);
}
```

### 4.4 `DelegatingPermissionEvaluator`（shared/security）

```java
package io.github.samzhu.skillshub.shared.security;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 路由 hasPermission(...) SpEL 到對應 aggregate 的 PermissionStrategy。
 *
 * <p>DI 自動注入所有 PermissionStrategy 實作；以 supports(targetType) 找第一個匹配。
 * Anonymous / null Authentication 直接拒絕（HTTP layer 由 ExceptionTranslationFilter 區分 401/403）。
 *
 * @see PermissionStrategy
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html">Spring Security ACLs Reference</a>
 */
@Component
public class DelegatingPermissionEvaluator implements PermissionEvaluator {

    private final List<PermissionStrategy> strategies;

    public DelegatingPermissionEvaluator(List<PermissionStrategy> strategies) {
        this.strategies = strategies;
    }

    @Override
    public boolean hasPermission(Authentication auth, @Nullable Object target, Object permission) {
        if (!authenticated(auth) || target == null) return false;
        var targetType = target.getClass().getSimpleName(); // e.g. "Skill"
        return evaluate(auth, target, targetType, permission.toString());
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId,
                                 String targetType, Object permission) {
        if (!authenticated(auth) || targetId == null || targetType == null) return false;
        return evaluate(auth, targetId, targetType, permission.toString());
    }

    private boolean authenticated(@Nullable Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    private boolean evaluate(Authentication auth, Object target, String targetType, String permission) {
        var principals = expandPrincipals(auth, permission);
        return strategies.stream()
                .filter(s -> s.supports(targetType))
                .findFirst()
                .map(s -> s.hasPermission(principals, target, permission))
                .orElse(false);
    }

    /** Authentication → patterns set；同 AclPrincipalExpander 結果但跳過 CurrentUser 抽象（測試友善）。 */
    private Set<String> expandPrincipals(Authentication auth, String permission) {
        var p = new HashSet<String>();
        p.add("user:" + auth.getName() + ":" + permission);
        auth.getAuthorities().forEach(a -> {
            var role = a.getAuthority().replaceFirst("^ROLE_", "");
            p.add("role:" + role + ":" + permission);
        });
        // groups 由 SkillPermissionStrategy 透過 CurrentUserProvider 取得（避免 dispatcher 耦合 CurrentUser）
        return p;
    }
}
```

### 4.5 `SkillPermissionStrategy`（skill/security）

```java
package io.github.samzhu.skillshub.skill.security;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.PermissionStrategy;

/**
 * Skill aggregate 的 row-level ACL 檢查 — `acl_entries ?| :patterns` SQL 比對。
 *
 * <p>注意：dispatcher 傳入的 principals 僅含 user / role 兩類（per
 * {@link io.github.samzhu.skillshub.shared.security.DelegatingPermissionEvaluator}）；
 * 本策略額外從 {@link CurrentUserProvider#current()} 取 groups 補上 group: patterns。
 *
 * @see PermissionStrategy
 */
@Component
public class SkillPermissionStrategy implements PermissionStrategy {

    // NB: 必須用 `??|` 而非 `?|` — 即使 NamedParameterJdbcTemplate 已 skip Spring layer
    // 的 `?` 計數，pgJDBC `PgPreparedStatement` 在更下層會重新 parse `?` 為 placeholder；
    // 詳 §2.4 Challenge #2 [Implementation note: T1 verified]。
    private static final String SQL = """
        SELECT EXISTS (
          SELECT 1 FROM skills
           WHERE id = :skillId
             AND acl_entries ??| :patterns
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;
    private final AclPrincipalExpander expander;

    public SkillPermissionStrategy(DataSource ds,
                                   CurrentUserProvider currentUserProvider,
                                   AclPrincipalExpander expander) {
        this.jdbc = new NamedParameterJdbcTemplate(ds);
        this.currentUserProvider = currentUserProvider;
        this.expander = expander;
    }

    @Override
    public boolean supports(String targetType) {
        return "Skill".equals(targetType);
    }

    @Override
    public boolean hasPermission(Set<String> principals, Object targetIdOrObject, String permission) {
        var skillId = targetIdOrObject.toString();

        // 補 group: patterns（dispatcher 不知 groups；由 CurrentUserProvider 抽）
        var fullPatterns = new HashSet<>(principals);
        fullPatterns.addAll(expander.expandGroups(currentUserProvider.current().groups(), permission));

        var params = new MapSqlParameterSource()
                .addValue("skillId", skillId)
                .addValue("patterns",
                          new java.sql.Types[]{}, // workaround: see implementation note
                          java.sql.Types.ARRAY);
        // 實作時用 SqlParameterValue(Types.ARRAY, fullPatterns.toArray(new String[0]))
        // 此處 stub 表達意圖；正式碼 per §4.6 AclPrincipalExpander Implementation Note

        return Boolean.TRUE.equals(jdbc.queryForObject(SQL, params, Boolean.class));
    }
}
```

> 實作注意：`?|` SQL ARRAY 參數使用 `new SqlParameterValue(Types.ARRAY, fullPatterns.toArray(new String[0]))` 包裝（per §2.4 Challenge #3），避免 NamedParameterJdbcTemplate 對 `Iterable<?>` 的 IN-list 自動展開。實作期間 task 階段補正。

### 4.6 `AclPrincipalExpander`（shared/security）

```java
package io.github.samzhu.skillshub.shared.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 把 CurrentUser 展開為 acl_entries 比對用的 patterns（type:principal:permission 三段）。
 *
 * <p>純 utility 無狀態；之所以 component 而非 static helper：方便 mock 測試 + 未來
 * 加 org / dept / room 命名空間時不破壞 caller。
 */
@Component
public class AclPrincipalExpander {

    /** 完整展開 — user + roles + groups 三類 patterns。 */
    public List<String> expand(CurrentUser user, String permission) {
        var patterns = new ArrayList<String>();
        patterns.add("user:" + user.userId() + ":" + permission);
        for (var role : user.roles()) {
            patterns.add("role:" + role + ":" + permission);
        }
        for (var group : user.groups()) {
            patterns.add("group:" + group + ":" + permission);
        }
        return patterns;
    }

    /** 僅展 group：給 SkillPermissionStrategy 補 dispatcher 沒給的部分。 */
    public List<String> expandGroups(List<String> groups, String permission) {
        return groups.stream().map(g -> "group:" + g + ":" + permission).toList();
    }
}
```

### 4.7 `CurrentUser` + `CurrentUserProvider` 擴展（shared/security）

```java
// CurrentUser.java（modify — 加 groups field）
public record CurrentUser(
        String userId,
        List<String> roles,
        List<String> groups   // ★ 新增
) {}
```

```java
// CurrentUserProvider.java（modify — JWT groups claim 抽取 + LAB fallback）
public CurrentUser current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth instanceof JwtAuthenticationToken jwt) {
        var token = jwt.getToken();
        var roles = token.getClaimAsStringList("roles");
        var groups = token.getClaimAsStringList("groups");   // ★ 新增；OIDC standard claim
        return new CurrentUser(
                jwt.getName(),
                roles == null ? List.of() : roles,
                groups == null ? List.of() : groups);
    }

    if (auth != null && auth.isAuthenticated()
            && !"anonymousUser".equals(auth.getPrincipal())) {
        var roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).toList();
        return new CurrentUser(auth.getName(), roles, List.of());   // ★ LAB mode 預設空 groups
    }

    return new CurrentUser(labUserId, List.of("admin"), List.of());  // ★ fallback
}
```

### 4.8 `SecurityConfig` 擴展（shared/security）

```java
// SecurityConfig.java（modify — 加 static @Bean）

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

@Bean
static MethodSecurityExpressionHandler methodSecurityExpressionHandler(
        PermissionEvaluator permissionEvaluator) {
    var handler = new DefaultMethodSecurityExpressionHandler();
    handler.setPermissionEvaluator(permissionEvaluator);
    return handler;
}
```

> `static` 必要 — 破除 `PrePostMethodSecurityConfiguration` 的 circular dep（per §2.4 Challenge #4）。

### 4.9 ACL Domain Events（skill/domain）

```java
public record SkillAclGrantedEvent(
        String aggregateId,
        String type,           // "user" | "role" | "group"
        String principal,
        String permission,     // "read" | "write" | "delete" | "suspend" | "reactivate"
        String grantedBy
) {}

public record SkillAclRevokedEvent(
        String aggregateId,
        String type,
        String principal,
        String permission,
        String revokedBy
) {}
```

### 4.10 ACL Commands（skill/command）

```java
public record GrantAclCommand(
        String skillId,
        String type,
        String principal,
        String permission,
        String grantedBy   // 從 SecurityContext 取
) {}

public record RevokeAclCommand(
        String skillId,
        String type,
        String principal,
        String permission,
        String revokedBy
) {}
```

### 4.11 `SkillCommandService` 新方法 + 既有 method 套 @PreAuthorize

```java
// SkillCommandService.java（modify）

@Transactional
public void grantAcl(GrantAclCommand cmd) {
    var skill = loadAggregate(cmd.skillId());
    var event = skill.grantAcl(cmd);   // aggregate 驗業務不變量（無重複 grant + verb 合法）
    saveAndPublish(cmd.skillId(), "SkillAclGranted",
            Map.of("type", cmd.type(), "principal", cmd.principal(),
                   "permission", cmd.permission(), "grantedBy", cmd.grantedBy()),
            skill.nextSequence(), event);
}

@Transactional
public void revokeAcl(RevokeAclCommand cmd) {
    var skill = loadAggregate(cmd.skillId());
    var event = skill.revokeAcl(cmd);  // aggregate 驗 entry 存在
    saveAndPublish(cmd.skillId(), "SkillAclRevoked",
            Map.of("type", cmd.type(), "principal", cmd.principal(),
                   "permission", cmd.permission(), "revokedBy", cmd.revokedBy()),
            skill.nextSequence(), event);
}
```

### 4.12 ACL CRUD Endpoints（skill/command/SkillAclController.java — new）

```java
@RestController
@RequestMapping("/api/v1/skills/{id}/acl")
public class SkillAclController {

    private final SkillCommandService commandService;
    private final SkillAclQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'write')")
    public ResponseEntity<Void> grant(@PathVariable String id,
                                       @RequestBody AclEntryRequest req) {
        commandService.grantAcl(new GrantAclCommand(
                id, req.type(), req.principal(), req.permission(),
                currentUserProvider.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'write')")
    public ResponseEntity<Void> revoke(@PathVariable String id,
                                        @RequestParam String type,
                                        @RequestParam String principal,
                                        @RequestParam String permission) {
        commandService.revokeAcl(new RevokeAclCommand(
                id, type, principal, permission, currentUserProvider.userId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    public ResponseEntity<List<AclEntryResponse>> list(@PathVariable String id) {
        return ResponseEntity.ok(queryService.listEntries(id));
    }
}

public record AclEntryRequest(String type, String principal, String permission) {}
public record AclEntryResponse(String type, String principal, String permission) {}
```

### 4.13 既有 `SkillCommandController` 套 @PreAuthorize（modify）

```java
// SkillCommandController.java（modify — 既有 endpoints 加 @PreAuthorize）

@PostMapping
// 建立新 skill — 不需 row-level ACL（無 #id 可參考）；改檢角色或保持 permitAll
// MVP 階段：保持 permitAll（per CLAUDE.md "Feature First, Security Later"）
ResponseEntity<Map<String, String>> createSkill(@RequestBody CreateSkillCommand command) { ... }

@PostMapping("/upload")
// 同上 — 新建路徑無 row-level
ResponseEntity<Map<String, String>> uploadSkill(...) { ... }

@PutMapping("/{id}/versions")
@PreAuthorize("hasPermission(#id, 'Skill', 'write')")   // ★ 新增
ResponseEntity<Void> addVersion(@PathVariable String id, ...) { ... }
```

> create/upload 端點 MVP 暫不套 row-level（無 id 可比對）；後續 spec 可加 quota / role check。本 spec 範圍限既有「以 id 為對象」的端點。

### 4.14 `SkillProjection` ACL listeners（skill/query）

```java
// SkillProjection.java（modify — 加 2 個 listener handlers）

@EventListener
void on(SkillAclGrantedEvent event) {
    var entry = String.format("%s:%s:%s",
            event.type(), event.principal(), event.permission());
    repo.appendAclEntry(event.aggregateId(), entry, Instant.now());

    log.atInfo()
            .addKeyValue("skillId", event.aggregateId())
            .addKeyValue("entry", entry)
            .log("已授權 ACL entry");
}

@EventListener
void on(SkillAclRevokedEvent event) {
    var entry = String.format("%s:%s:%s",
            event.type(), event.principal(), event.permission());
    repo.removeAclEntry(event.aggregateId(), entry, Instant.now());

    log.atInfo()
            .addKeyValue("skillId", event.aggregateId())
            .addKeyValue("entry", entry)
            .log("已撤銷 ACL entry");
}
```

### 4.15 `SkillReadModelRepository` atomic UPDATE（skill/query）

```java
// SkillReadModelRepository.java（modify — 加 2 個 @Modifying queries）

@Modifying
@Query("""
    UPDATE skills
       SET acl_entries = acl_entries || to_jsonb(:entry),
           updated_at  = :ts
     WHERE id = :id
       AND NOT (acl_entries @> to_jsonb(:entry))
    """)
int appendAclEntry(@Param("id") String id, @Param("entry") String entry,
                    @Param("ts") Instant ts);

@Modifying
@Query("""
    UPDATE skills
       SET acl_entries = (
         SELECT jsonb_agg(elem)
           FROM jsonb_array_elements_text(acl_entries) elem
          WHERE elem != :entry
       ),
           updated_at  = :ts
     WHERE id = :id
    """)
int removeAclEntry(@Param("id") String id, @Param("entry") String entry,
                    @Param("ts") Instant ts);
```

> `appendAclEntry` 用 `NOT (acl_entries @> ...)` 確保冪等（重複 grant 不疊加）；`removeAclEntry` 用 `jsonb_array_elements_text` 過濾後 `jsonb_agg` 重組（比 `-` operator 對字串元素更穩健）。

### 4.16 `SkillshubPgVectorStore.INSERT_SQL` 升 7 欄

```java
// SkillshubPgVectorStore.java（modify — INSERT_SQL 加 acl_entries）

static final String INSERT_SQL = """
    INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id, acl_entries)
    VALUES (?::uuid, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
    ON CONFLICT (id) DO UPDATE
       SET content     = EXCLUDED.content,
           metadata    = EXCLUDED.metadata,
           embedding   = EXCLUDED.embedding,
           owner       = COALESCE(EXCLUDED.owner, vector_store.owner),
           skill_id    = COALESCE(EXCLUDED.skill_id, vector_store.skill_id),
           acl_entries = COALESCE(EXCLUDED.acl_entries, vector_store.acl_entries)
    """;
```

> `SearchProjection` 寫入時：`aclEntries = List.of("user:" + owner + ":read")` 從 owner 衍生（與 V2 backfill 邏輯一致）。

### 4.17 Spring Modulith allowedDependencies（skill/package-info）

```java
// skill/package-info.java（modify）

@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: events",
        "shared :: api",
        "shared :: security",   // ★ 新增 — 為 skill/security/SkillPermissionStrategy import
        "storage"
    }
)
package io.github.samzhu.skillshub.skill;
```

### 4.18 `StringListJsonbConverter`（shared/persistence — new）

```java
// shared/persistence/StringListJsonbConverter.java
package io.github.samzhu.skillshub.shared.persistence;

import java.util.List;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class StringListJsonbConverter {

    @WritingConverter
    public static final class Writing implements Converter<List<String>, PGobject> {
        private final ObjectMapper mapper;
        public Writing(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public PGobject convert(List<String> source) {
            try {
                var pgo = new PGobject();
                pgo.setType("jsonb");
                pgo.setValue(source == null ? "[]" : mapper.writeValueAsString(source));
                return pgo;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write List<String> as JSONB", e);
            }
        }
    }

    @ReadingConverter
    public static final class Reading implements Converter<PGobject, List<String>> {
        private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};
        private final ObjectMapper mapper;
        public Reading(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public List<String> convert(PGobject source) {
            try {
                var v = source.getValue();
                return (v == null || v.isBlank()) ? List.of() : mapper.readValue(v, TYPE);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read JSONB as List<String>", e);
            }
        }
    }
}
```

註冊在既有 `shared/persistence/JdbcConfiguration.java` 的 `userConverters()` list — 與 `MapJsonbConverter` 並列。

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V2__add_acl_entries.sql` | **new** | skills + vector_store 加 `acl_entries JSONB NOT NULL DEFAULT '[]'` + GIN(default jsonb_ops) index + backfill from author/owner |
| `backend/.../shared/security/CurrentUser.java` | modify | record component 加 `List<String> groups` |
| `backend/.../shared/security/CurrentUserProvider.java` | modify | OAuth path 抽 JWT `groups` claim；LAB / fallback 預設空 list |
| `backend/.../shared/security/SecurityConfig.java` | modify | 加 `static @Bean MethodSecurityExpressionHandler`（破 circular dep）+ inject `PermissionEvaluator` |
| `backend/.../shared/security/PermissionStrategy.java` | **new** | interface — `supports(targetType)` + `hasPermission(principals, target, permission)` |
| `backend/.../shared/security/DelegatingPermissionEvaluator.java` | **new** | 註冊為 `@Component PermissionEvaluator`；DI 收齊 PermissionStrategy；route by `supports()` |
| `backend/.../shared/security/AclPrincipalExpander.java` | **new** | CurrentUser → `["user:...:read", "role:admin:read", "group:eng:read"]` patterns |
| `backend/.../shared/persistence/StringListJsonbConverter.java` | **new** | `Writing` + `Reading` static inner classes，鏡射 `MapJsonbConverter` |
| `backend/.../shared/persistence/JdbcConfiguration.java` | modify | `userConverters()` list 加 2 個 String-list converter |
| `backend/.../skill/package-info.java` | modify | `allowedDependencies` 加 `"shared :: security"` |
| `backend/.../skill/security/SkillPermissionStrategy.java` | **new**（含 sub-package 建立）| `supports("Skill")` + `acl_entries ?| :patterns` SQL 查詢；NamedParameterJdbcTemplate + SqlParameterValue(Types.ARRAY) |
| `backend/.../skill/domain/Skill.java` | modify | 加 `grantAcl(GrantAclCommand)` / `revokeAcl(RevokeAclCommand)` business methods + `currentAclEntries: Set<String>` transient field（per §2.4 Challenge #13）|
| `backend/.../skill/domain/SkillAclGrantedEvent.java` | **new** | record(aggregateId, type, principal, permission, grantedBy) |
| `backend/.../skill/domain/SkillAclRevokedEvent.java` | **new** | record(aggregateId, type, principal, permission, revokedBy) |
| `backend/.../skill/command/GrantAclCommand.java` | **new** | record |
| `backend/.../skill/command/RevokeAclCommand.java` | **new** | record |
| `backend/.../skill/command/SkillCommandService.java` | modify | 加 `grantAcl()` / `revokeAcl()` `@Transactional` methods（saveAndPublish 路徑與 S014 既有對齊）|
| `backend/.../skill/command/SkillCommandController.java` | modify | `addVersion`（PUT `/{id}/versions`）加 `@PreAuthorize("hasPermission(#id, 'Skill', 'write')")` |
| `backend/.../skill/command/SkillAclController.java` | **new** | `POST/DELETE/GET /api/v1/skills/{id}/acl` 三端點 + AclEntryRequest/Response records |
| `backend/.../skill/query/SkillReadModel.java` | modify | 加 `@Column("acl_entries") List<String> aclEntries` field |
| `backend/.../skill/query/SkillReadModelRepository.java` | modify | 加 `appendAclEntry(id, entry, ts)` + `removeAclEntry(id, entry, ts)` `@Modifying @Query` 方法 |
| `backend/.../skill/query/SkillProjection.java` | modify | 加 `on(SkillAclGrantedEvent)` / `on(SkillAclRevokedEvent)` listeners |
| `backend/.../skill/query/SkillAclQueryService.java` | **new** | `listEntries(skillId)` — JdbcTemplate query JSONB array → `List<AclEntryResponse>` |
| `backend/.../search/SkillshubPgVectorStore.java` | modify | `INSERT_SQL` 加 `acl_entries` 第 7 欄 + ON CONFLICT clause；`doAdd` 簽名/實作對應升級 |
| `backend/.../search/SearchProjection.java` | modify | 寫 vector_store 時計算 `aclEntries = List.of("user:" + owner + ":read")` |
| `backend/src/test/.../shared/security/AclPrincipalExpanderTest.java` | **new** | unit — AC-12 |
| `backend/src/test/.../shared/security/DelegatingPermissionEvaluatorTest.java` | **new** | unit — AC-6（routing）+ anonymous rejection |
| `backend/src/test/.../shared/security/CurrentUserProviderTest.java` | modify | 加 AC-4 / AC-5（JWT groups + LAB 空 groups）|
| `backend/src/test/.../skill/security/SkillPermissionStrategyTest.java` | **new** | integration with Testcontainers — AC-7 / AC-8 / AC-15 / AC-13（EXPLAIN GIN index）|
| `backend/src/test/.../skill/command/SkillAclControllerTest.java` | **new** | controller security + behavior — AC-9 / AC-10 / AC-11 |
| `backend/src/test/.../skill/command/SkillCommandControllerSecurityTest.java` | **new** | 既有 PUT `/{id}/versions` 套 @PreAuthorize 後 — AC-7 邊界 |
| `backend/src/test/.../skill/query/SkillProjectionAclTest.java` | **new** | projection 監聽 SkillAclGranted/Revoked → atomic UPDATE — AC-9 / AC-10 |
| `backend/src/test/.../ModularityTests.java`（既有）| modify | 確認 `ApplicationModules.verify()` 在加 allowedDependencies 後仍綠 — AC-14 |

**Files: 19 production（11 new + 8 modify）+ 7 test = 26 files**。size **M(13)** 對齊（兩個表 schema migration + 4 個新 component class + 2 個 events + 8 個既有檔修改 + 7 個測試）。

---

## 6. Task Plan

### Phase 0 Pre-Flight Validation 結論（2026-04-28）

- ✅ Existing knowledge：所有研究結論已內聯於 §2.4 / §2.5；無 `docs/local/` 額外研究檔需回讀
- ✅ Cross-validate with PRD：spec §1 已 mapping PRD §Backlog B1/B7/B8 + ADR-001 §3.1（30 元素天花板觸發 PG 遷移；ACL JSONB inline 為 ADR §4.3 explicit 否決 RLS / Spring Security ACL 模組之後的 deliberate 選擇）
- ✅ Question the approach：(a) 框架已解決問題？— Spring Security 內建 ACL 模組過重，per ADR-001 §4.3 否決；(b) 簡單方案？— Strategy/Registry 為 PRD B7/B8 鋪路，已 grill confirmed；(c) 加 dep？— 全用既有 Spring Security + Spring Data JDBC + Jackson + PostgreSQL，無新 dep

### POC Decision

**POC: not required** — spec §2.6 全 Validated；fallback 評估亦無 trigger（無新 SDK / 不熟外部 API / 已 raw source verified Spring Security SPI / 無跨環境 CLI）。

### Task Files

| Task | Topic | ACs Covered | Files (prod / test) | Depends On |
|------|-------|-------------|---------------------|-----------|
| **T1** | V2 Flyway migration + StringListJsonbConverter + SkillReadModel.aclEntries | AC-1, AC-2, AC-3 | 4 / 3 | none |
| **T2** | PermissionStrategy interface + DelegatingPermissionEvaluator + AclPrincipalExpander + CurrentUser groups + SecurityConfig MethodSecurityExpressionHandler | AC-4, AC-5, AC-6, AC-12 | 6 / 3 | T1 |
| **T3** | SkillPermissionStrategy + skill/package-info allowedDependencies + 既有 PUT `/{id}/versions` 套 @PreAuthorize | AC-7, AC-8, AC-13, AC-14, AC-15 | 3 / 3 | T1, T2 |
| **T4** | ACL events + Skill aggregate methods + SkillCommandService grant/revoke + SkillAclController POST/DELETE | AC-9, AC-10 | 7 / 3 | T1, T2, T3 |
| **T5** | SkillProjection ACL listeners + SkillReadModelRepository atomic UPDATE + SkillAclQueryService GET | AC-9 (read), AC-10 (read), AC-11 | 4 / 3 | T1, T4 |
| **T6** | SkillshubPgVectorStore INSERT_SQL 7-col + SearchProjection 寫 acl_entries + 端到端 E2E smoke | cross-stack（AC-1~AC-15） | 2 / 3 | T1~T5 全部 |

### Execution Order

```
T1 ─▶ T2 ─▶ T3 ─▶ T4 ─▶ T5 ─▶ T6
(infra)(security)(strategy)(commands)(projection)(vector + E2E)
```

線性 chain；每個 task 對應一群相關 AC + 完整 RED → GREEN → REFACTOR 週期。

### E2E Smoke（per planning-tasks Step 1.5 mandatory）

T6 即 E2E 任務 — 整合 ApplicationContext + Testcontainer + 真實 Spring Security filter chain + 真實 JSONB SQL，覆蓋 spec 全部 15 ACs cross-stack。理由：

- 單元測試 stub/mock Authentication（`.with(jwt(...))` / `@WithMockUser`）— 無法驗 Spring Security 真實 filter chain
- 單元測試 mock JdbcTemplate — 無法驗 PostgreSQL `?|` operator + GIN index 真實行為
- 單元測試獨立 listener — 無法驗 `@EventListener` 真實 publish 順序

### Verification Commands

per `qa-strategy.md` Verification Command Registry：

```
V01:  cd backend && ./gradlew clean test jacocoTestReport      # CRITICAL — 含 ModularityTests
V03:  cd backend && ./gradlew jacocoTestCoverageVerification   # CRITICAL — 80% line coverage gate
```

每個 AC 必須對應至少一個 `@DisplayName("AC-N: ...")` 或 `@Tag("AC-N")` 測試（per qa-strategy.md AC-to-Test Contract）。

### Open Risks / Watch List

- T6 E2E 揭露 schema / wiring drift 時走 "Post-Verification Bug Re-Entry Protocol"（spec status 退回 ⏳ Dev，新增 Round 2 task files；不允許 hotfix 繞過）
- 既有 `SkillReadModel` 加 component 為 binary breaking change（per §2.4 Challenge #12）— T1 實作期間 grep 全 codebase 補完所有 `new SkillReadModel(...)` call sites

### Interim QA Review — 2026-04-28（after T1 + T2 PASS）

> 此 sub-section 不替代 Phase 4 §7（會在 T1~T6 全 PASS 後由 /planning-tasks 寫入完整 §7）；只用於記錄 mid-spec checkpoint 的 QA evidence + 設計 drift 修正。

**Verdict**: PASS-INTERIM（T1+T2 範圍）；spec design drift 已就地修正以避免 T3 浪費 RED cycle。

**Verification evidence**：

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests (V01) | PASS | 142/142 tests，0 failures（含 ModularityTests）— `./scripts/verify-all.sh` 2026-04-28T15:51:22Z |
| Coverage gate (V03) | PASS | 88.2% LINE coverage（gate 80%）— V02 INFO `covered=1119 / total=1269` |
| Frontend (V04~V06) | PASS | npm test + lint + coverage 全 green（無 frontend code 變動於本 spec，但 V01-V06 全跑保險）|
| Code quality | PASS | 4 個新 production files（StringListJsonbConverter / PermissionStrategy / DelegatingPermissionEvaluator / AclPrincipalExpander）皆有 class-level Javadoc + 內聯設計意圖 comment；無 forbidden pattern；無 hardcoded secret |
| AC tag mapping (T1+T2 scope) | PASS | AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 / AC-12 全有 `@DisplayName("AC-N: ...")` + `@Tag("AC-N")`（per qa-strategy §AC-to-Test Contract）|
| Testability gate | CLEAR | T1+T2 範圍內無 UNTESTABLE AC |

**AC coverage matrix（mid-spec）**：

| AC | Status | Test files |
|----|--------|------------|
| AC-1 (V2 schema + GIN jsonb_ops) | ✅ VERIFIED | V2MigrationTest × 2 + SkillReadModelAclTest × 3 + StringListJsonbConverterTest × 4 |
| AC-2 (skills backfill from author) | ✅ VERIFIED | V2MigrationTest.skillsBackfill_fromAuthor |
| AC-3 (vector_store backfill fail-secure) | ✅ VERIFIED | V2MigrationTest.vectorStoreBackfill_failSecureOnNullOwner |
| AC-4 (CurrentUser groups + JWT claim) | ✅ VERIFIED | CurrentUserProviderTest × 5 cases |
| AC-5 (LAB mode 預設空 groups) | ✅ VERIFIED | CurrentUserProviderTest 4 cases 全加 groups assertion |
| AC-6 (DelegatingPermissionEvaluator routing + anonymous 短路) | ✅ VERIFIED | DelegatingPermissionEvaluatorTest × 8 cases |
| AC-12 (AclPrincipalExpander 三命名空間展開) | ✅ VERIFIED | AclPrincipalExpanderTest × 6 cases |
| AC-7 (PUT @PreAuthorize) | ⏳ NOT-IMPL | T3 pending |
| AC-8 (group: principal mode integration) | ⏳ NOT-IMPL | T3 pending |
| AC-9 (POST /acl 觸發 SkillAclGranted) | ⏳ NOT-IMPL | T4 pending |
| AC-10 (DELETE /acl 觸發 SkillAclRevoked) | ⏳ NOT-IMPL | T4 pending |
| AC-11 (GET /acl 列 entries) | ⏳ NOT-IMPL | T5 pending |
| AC-13 (EXPLAIN GIN large dataset) | ⏳ NOT-IMPL | T6 E2E pending（per spec §6 task plan T6）|
| AC-14 (Modulith allowedDeps) | ⏳ NOT-IMPL | T3 pending |
| AC-15 (suspend/reactivate verbs) | ⏳ NOT-IMPL | T3 pending |

**Findings**：

1. **CRITICAL → 已修正**：spec §2.4 Challenge #2 + §2.6 Confidence Classification 原寫「`?|` 在 `NamedParameterJdbcTemplate` 不需 JDBC escape」— T1 SkillReadModelAclTest 實測證明錯誤（pgJDBC PgPreparedStatement 在 Spring layer 之下重新 parse `?`，仍需 `??|`）。已就地修正 §2.4 #2 + §2.6 + §4.5 SkillPermissionStrategy SQL 範例（將 `?|` 改 `??|`），避免 T3 RED 階段浪費 cycle。修正基於 T1 task file Result section 的 Lessons Learned。
2. **IMPORTANT**：spec §3 AC-13 BDD 期望「query plan 含 Bitmap Index Scan on idx_skills_acl_entries」+「不含 Seq Scan（小資料集需 SET enable_seqscan=off 強制）」— T1 實測：`SET LOCAL enable_seqscan=off` 在 HikariCP 多連線 pool 下不跨呼叫保持，且 < 100 row 的 test 環境 planner 必選 seq scan。AC-13 留待 T6 E2E 大資料集（≥ 100 row + ANALYZE + 同連線）覆蓋；T1 已用 schema meta 驗證（V2MigrationTest indexdef 不含 jsonb_path_ops 字串）+ 功能驗證（acl_entries ??| ARRAY[...] 實際命中）兩路替代。T6 spec scope 可放心承接。
3. **MINOR**：spec §4.5 SkillPermissionStrategy 範例的 ARRAY 參數綁定段落為 stub（`new java.sql.Types[]{}` 註解預留）— T3 實作時須對齊 §2.4 #3 用 `new SqlParameterValue(Types.ARRAY, fullPatterns.toArray(new String[0]))`。建議 T3 起點時 lead engineer 先驗 §4.5 stub 已 verify。

**Next steps**: 繼續 T3 (`/planning-tasks S016` → T3 `SkillPermissionStrategy + Modulith allowedDeps + 既有 PUT @PreAuthorize`)。spec 與 T1 evidence 已對齊；T3 RED 可信任 §4.5 SQL 寫法（已用 `??|`）。

<!-- Section 7 added by /planning-tasks Phase 4 after implementation -->

---

## 7. Implementation Results（2026-04-29）

### 7.1 Verification

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests (V01) | PASS | 182/182 tests，0 failures（含 ModularityTests）— `./scripts/verify-all.sh` exit=0 |
| Coverage gate (V03) | PASS | 88.7% LINE coverage（gate 80%）— covered=1254 / total=1413 |
| Frontend (V04~V06) | PASS | npm test + lint + coverage 全 green（無 frontend code 變動於本 spec）|
| ModularityTests | PASS | `ApplicationModules.of(SkillshubApplication.class).verify()` 加 `shared :: security` 後仍綠（AC-14）|
| E2E smoke (T6) | PASS | upload→grant→list（carol via group:engineering）→ bob 403→revoke 全鏈跨 controller→service→aggregate→event store→projection→read model→vector_store 一致（per S016EndToEndSmokeTest）|

**Test growth path**：T2 baseline 142 → T3 +12 (154) → T4 +13 (167) → T5 +9 (176) → T6 +6 (182)。

### 7.2 Files Created

**Production**（13 new files）：
- `shared/persistence/StringListJsonbConverter.java`（T1）— JSONB ↔ List<String> 雙向 converter
- `shared/security/AclPrincipalExpander.java`（T2）— user/role/group 命名空間展開
- `shared/security/DelegatingPermissionEvaluator.java`（T2）— PermissionEvaluator dispatcher
- `shared/security/PermissionStrategy.java`（T2）— Strategy interface
- `skill/security/SkillPermissionStrategy.java`（T3）— Skill aggregate `??|` SQL 比對
- `skill/domain/SkillAclGrantedEvent.java`（T4）— ACL grant domain event
- `skill/domain/SkillAclRevokedEvent.java`（T4）— ACL revoke domain event
- `skill/command/GrantAclCommand.java`（T4）— grant request payload
- `skill/command/RevokeAclCommand.java`（T4）— revoke request payload
- `skill/command/SkillAclController.java`（T4+T5）— POST/DELETE/GET `/api/v1/skills/{id}/acl`
- `skill/query/SkillAclQueryService.java`（T5）— colon split + 畸形 entry skip
- `skill/query/AclEntryResponse.java`（T5）— GET response DTO
- `db/migration/V2__add_acl_entries.sql`（T1）— skills + vector_store ACL 欄位 + GIN index + backfill

**Tests**（10 new test classes）：
- `db.migration/V2MigrationTest`（T1）
- `shared/persistence/StringListJsonbConverterTest`（T1）
- `skill/query/SkillReadModelAclTest`（T1）
- `shared/security/CurrentUserProviderTest`（T2 modify — 既有檔擴 5 cases）
- `shared/security/DelegatingPermissionEvaluatorTest`（T2）
- `shared/security/AclPrincipalExpanderTest`（T2）
- `skill/security/SkillPermissionStrategyTest`（T3）
- `skill/command/SkillCommandControllerSecurityTest`（T3）
- `skill/domain/SkillAclTest`（T4）
- `skill/command/SkillAclCommandServiceTest`（T4）
- `skill/command/SkillAclControllerTest`（T4+T5）
- `skill/query/SkillProjectionAclTest`（T5）
- `skill/query/SkillAclQueryServiceTest`（T5）
- `search/SkillshubPgVectorStoreAclTest`（T6）
- `search/SearchProjectionAclWriteTest`（T6）
- `S016EndToEndSmokeTest`（T6）

### 7.3 Files Modified

**Production**：
- `shared/persistence/JdbcConfiguration.java` — 註冊 `StringListJsonbConverter` Reading/Writing
- `shared/security/CurrentUser.java` — 加 `groups: List<String>` field
- `shared/security/CurrentUserProvider.java` — JWT `groups` claim 抽取 + LAB 模式空 list fallback
- `shared/security/SecurityConfig.java` — 加 `static @Bean MethodSecurityExpressionHandler`（破除 PrePostMethodSecurityConfiguration circular dep）
- `skill/package-info.java` — `allowedDependencies` 加 `"shared :: security"`
- `skill/command/SkillCommandController.java` — `addVersion` 加 `@PreAuthorize("hasPermission(#id, 'Skill', 'write')")`
- `skill/command/SkillCommandService.java` — 加 `grantAcl(GrantAclCommand)` / `revokeAcl(RevokeAclCommand)` `@Transactional`
- `skill/domain/Skill.java` — 加 `currentAclEntries: Set<String>` replay state + `grantAcl` / `revokeAcl` 業務方法
- `skill/query/SkillProjection.java` — `on(SkillCreatedEvent)` 加 owner ACL seed + 加 `on(SkillAclGrantedEvent)` / `on(SkillAclRevokedEvent)` listeners
- `skill/query/SkillReadModel.java` — 加 `aclEntries: List<String>` field（@Column("acl_entries")）
- `skill/query/SkillReadModelRepository.java` — 加 `appendAclEntry` / `removeAclEntry` `@Modifying @Query`
- `search/SkillshubPgVectorStore.java` — `INSERT_SQL` 升 7 欄（acl_entries），8 placeholder 解 NOT NULL × COALESCE preservation 矛盾；Builder 加 `aclEntries(@Nullable List<String>)`
- `search/SearchProjection.java` — `onSkillCreated` / `onVersionPublished` 從 owner 衍生 `["user:" + owner + ":read"]` initial ACL

**Tests**：
- `ModularityTests` — 加 `@Tag("AC-14")`（多 spec 共用同一 module 結構驗證）
- `SkillUploadTest` / `SkillVersionQueryTest` — 加 `@TestPropertySource(oauth.enabled=false, lab.user-id=...)` LAB 模式 workaround
- `PgVectorStoreOwnerWriteTest` / `SearchProjectionTest` / `SemanticSearchIntegrationTest` — fixture 加 acl_entries argument

### 7.4 Spec Design Drift（已就地修正於 §2.4 / §2.6 / §4.5）

| # | 原 spec 寫法 | 實作驗證後修正 | 修正時機 |
|---|------------|------------|---------|
| 1 | `?|` SQL operator（spec §2.4 #2 + §4.5）| `??|`（pgJDBC PgPreparedStatement 在 Spring layer 下重 parse `?`）| T1 interim QA |
| 2 | `MapSqlParameterSource.addValue("patterns", new java.sql.Types[]{}...)` stub（§4.5）| `new SqlParameterValue(Types.ARRAY, fullPatterns.toArray(new String[0]))`（§2.4 #3 已寫，§4.5 範例未對齊）| T3 |
| 3 | `vector_store INSERT_SQL` 7 placeholder（§4.16）| 8 placeholder（acl_entries 雙綁解 NOT NULL × COALESCE preservation 矛盾）| T6 |
| 4 | `SkillProjection.on(SkillCreatedEvent)` 不 seed ACL（T1 留 `List.of()`）| seed `["user:author:read|write|delete]`（T3 加 @PreAuthorize 後揭露作者自身通不過 gate）| T3 |
| 5 | `SkillReadModelRepository.removeAclEntry` 缺 `COALESCE(..., '[]'::jsonb)`（§4.15）| 加 COALESCE — `jsonb_agg` 在 source array 全濾後回 NULL 違反 NOT NULL | T5 |
| 6 | AC-13 期望「EXPLAIN bitmap index scan」+「seq scan disabled」| 小資料集 PostgreSQL planner 必選 seq scan + `SET LOCAL enable_seqscan=off` 不跨 HikariCP 連線 — 改用 schema meta（V2MigrationTest indexdef 不含 jsonb_path_ops）+ 功能性命中（`??|` 真實命中）兩路替代；EXPLAIN 大資料集驗證留 future work | T1+T3+T6 |

### 7.5 Key Findings — Validated Patterns（給未來 spec 引用）

#### 7.5.1 PostgreSQL JSONB ACL `??|` SQL pattern（複用於 S017+）

```java
private static final String SQL = """
    SELECT EXISTS (
      SELECT 1 FROM skills
       WHERE id = :skillId
         AND acl_entries ??| :patterns
    )
    """;

var params = new MapSqlParameterSource()
        .addValue("skillId", skillId)
        .addValue("patterns",
                new SqlParameterValue(Types.ARRAY, fullPatterns.toArray(new String[0])));

return Boolean.TRUE.equals(jdbc.queryForObject(SQL, params, Boolean.class));
```

關鍵點：
- SQL 字面寫 `??|`（不是 `?|`）— pgJDBC 在 Spring `NamedParameterJdbcTemplate` 之下會重新 parse `?` 為 placeholder
- `SqlParameterValue(Types.ARRAY, String[])` 必須包裝 — 否則 `Iterable<?>` 走 IN-list 自動展開破壞 `?|` 語法
- 配 GIN(default `jsonb_ops`) index — 不是 `jsonb_path_ops`（後者不支援 `?|`）

#### 7.5.2 Read-side projection 冪等 SQL（appendAclEntry）

```sql
UPDATE skills
   SET acl_entries = acl_entries || to_jsonb(:entry),
       updated_at = :ts
 WHERE id = :id
   AND NOT (acl_entries @> to_jsonb(:entry))
```

— `WHERE NOT (... @> ...)` 條件保證重複 grant event 不疊加 entry。

#### 7.5.3 Read-side projection 移除 SQL（removeAclEntry，含 COALESCE 防 null）

```sql
UPDATE skills
   SET acl_entries = COALESCE(
       (SELECT jsonb_agg(elem)
          FROM jsonb_array_elements_text(acl_entries) elem
         WHERE elem != :entry),
       '[]'::jsonb),
       updated_at = :ts
 WHERE id = :id
```

— `jsonb_agg` 對全濾後的空 source 回 NULL，必須 `COALESCE(..., '[]'::jsonb)` 維持 NOT NULL 約束。

#### 7.5.4 Vector Store INSERT 雙綁解 NOT NULL × COALESCE 矛盾

```sql
INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id, acl_entries)
VALUES (?::uuid, ?, ?::jsonb, ?, ?, ?, ?::jsonb)            -- 位 7：必非 null
ON CONFLICT (id) DO UPDATE
  SET ...,
      acl_entries = COALESCE(?::jsonb, vector_store.acl_entries)   -- 位 8：可 null 觸發保留
```

`doAdd` 對 builder 端 `aclEntries == null` 時：位 7 用 `"[]"` 兜底（INSERT 不違反 NOT NULL），位 8 用 SQL null（UPDATE 走 COALESCE 保留既有）。

#### 7.5.5 Aggregate ACL 部分 replay state

```java
// Skill.java constructor
for (var event : events) {
    switch (event.eventType()) {
        case "SkillVersionPublished" -> publishedVersions.add((String) event.payload().get("version"));
        case "SkillAclGranted" -> currentAclEntries.add(formatEntry(event.payload()));
        case "SkillAclRevoked" -> currentAclEntries.remove(formatEntry(event.payload()));
        default -> { /* maxSeq 累積即可 */ }
    }
    if (event.sequence() > maxSeq) maxSeq = event.sequence();
}
```

— minimal aggregate state，只 replay 業務不變量需要的部分（無重複 grant、revoke 必須有對應 entry），不模擬 read model 全狀態。

#### 7.5.6 Spring Modulith ACL 跨模組存取

`skill/package-info.java`：
```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: events",
        "shared :: api",
        "shared :: security",   // 為 skill/security/SkillPermissionStrategy 引 PermissionStrategy 而加
        "storage"
    }
)
```

shared/security 已標 `@NamedInterface("security")`（既有 S011/S012）— 直接引用即可。

### 7.6 AC Coverage Matrix

| AC | Status | Test files |
|----|--------|------------|
| AC-1（V2 schema + GIN jsonb_ops + acl_entries 欄位）| ✅ VERIFIED | V2MigrationTest × 2 + StringListJsonbConverterTest × 4 + SkillReadModelAclTest × 3 + SkillshubPgVectorStoreAclTest × 3 + SearchProjectionAclWriteTest × 1 |
| AC-2（skills backfill from author）| ✅ VERIFIED | V2MigrationTest.skillsBackfill_fromAuthor |
| AC-3（vector_store backfill fail-secure on null owner）| ✅ VERIFIED | V2MigrationTest.vectorStoreBackfill_failSecureOnNullOwner + SkillshubPgVectorStoreAclTest.unsetAclEntriesDefaultsToEmptyArray |
| AC-4（CurrentUser.groups + JWT claim）| ✅ VERIFIED | CurrentUserProviderTest × 5 |
| AC-5（LAB 模式預設空 groups）| ✅ VERIFIED | CurrentUserProviderTest × 4 |
| AC-6（DelegatingPermissionEvaluator routing + anonymous 短路）| ✅ VERIFIED | DelegatingPermissionEvaluatorTest × 8 |
| AC-7（PUT /versions @PreAuthorize；POST/DELETE /acl 自身 gate）| ✅ VERIFIED | SkillPermissionStrategyTest × 2 + SkillCommandControllerSecurityTest × 2 + SkillAclControllerTest（grantAcl_nonOwner_returns403）+ S016EndToEndSmokeTest.e2e_putVersion_acl_gate |
| AC-8（group: principal 模式比對）| ✅ VERIFIED | SkillPermissionStrategyTest × 2 + S016EndToEndSmokeTest（carol via group:engineering） |
| AC-9（POST /acl 觸發 SkillAclGranted + read-side append）| ✅ VERIFIED | SkillAclTest × 3 + SkillAclCommandServiceTest × 2 + SkillAclControllerTest × 1 + SkillProjectionAclTest × 2 |
| AC-10（DELETE /acl 觸發 SkillAclRevoked + read-side remove）| ✅ VERIFIED | SkillAclTest × 2 + SkillAclCommandServiceTest × 2 + SkillAclControllerTest × 1 + SkillProjectionAclTest × 2 |
| AC-11（GET /acl 列 entries + read 授權守門）| ✅ VERIFIED | SkillAclQueryServiceTest × 3 + SkillAclControllerTest × 2（200 owner / 403 non-reader）|
| AC-12（AclPrincipalExpander 三命名空間展開）| ✅ VERIFIED | AclPrincipalExpanderTest × 6 |
| AC-13（GIN index for `??|` operator）| ⚠️ PARTIAL | V2MigrationTest indexdef meta（schema 不含 jsonb_path_ops）+ SkillPermissionStrategyTest.ginIndexUsableForAnyKeyMatch（功能性命中）；EXPLAIN bitmap 大資料集驗證留 future work（小資料集 planner 限制；T1/T3/T6 三輪皆驗證此限制）|
| AC-14（Modulith verify 加 shared::security 仍綠）| ✅ VERIFIED | ModularityTests `@Tag("AC-14")` |
| AC-15（suspend / reactivate / unknown verb）| ✅ VERIFIED | SkillPermissionStrategyTest × 3 |

### 7.7 Pending Verification / Tech Debt

- **AC-13 EXPLAIN bitmap 大資料集驗證**：`SET LOCAL enable_seqscan=off` 在 HikariCP 多連線 pool 下不跨呼叫保持，且 < 100 row 的 test 環境 planner 必選 seq scan。本 spec 用 schema meta + 功能性命中兩路替代覆蓋。實際 PostgreSQL 在 ≥100 row + ANALYZE + 同連線情境會選 Bitmap Index Scan（per docs；研究 §2.5）。**Follow-up**：S017 ACL-aware 語意搜尋會自然帶入大資料集 + ANALYZE 流程，可順手加 EXPLAIN 驗證 spec。
- **重複 grant 在 controller 層 HTTP 表現**：本 spec aggregate 拋 `IllegalStateException`，目前無對應 `@ExceptionHandler`，會走 Spring default → HTTP 500（而非 409 Conflict）。後續可加 controller advice 統一轉換。**Backlog**：與 S018 SkillValidator 嚴格化 + suspend/reactivate state machine 同期處理錯誤碼集中化。
- **ADR-001 §3.2 / §8 修訂**：需把 `jsonb_path_ops` 改 default `jsonb_ops`（per §1 修訂注記）。在 `/shipping-release` 階段一併處理，避免單獨 PR 拆碎。

### 7.8 Routing

S016 6 task 全 PASS + V01-V06 全綠 + spec §6 / §7 已合併。下一步：spawn QA subagent 做 independent verification（per `/planning-tasks` Phase 4 Step 4）；通過後 `/shipping-release S016`。

> Status header 變更：`⏳ Dev` → `✅ Done`（per Phase 4 Step 2）。Roadmap 同步於 `/shipping-release` 階段更新。

---

## QA Review (independent subagent — 2026-04-29)

**Verdict**: PASS

**Verification re-run**:
- V01: PASS (tests=182, failures=0, coverage=88.7% LINE, covered=1254/1413)
- ModularityTests: PASS (`ApplicationModules.of(SkillshubApplication.class).verify()` green with `"shared :: security"` in allowedDependencies)
- `./scripts/verify-all.sh`: PASS (V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS, exit=0)

**Findings**:

1. [IMPORTANT] **E2E `e2e_putVersion_acl_gate` alice assertion is weak** — The test asserts "not 403" (`if (s == 403) throw AssertionError`) but does NOT assert that alice's PUT actually returns 200 or 201. A bug that returns 500 on a valid PUT (e.g., a downstream exception) would pass silently. Bob's 403 assertion is correct. This is AC-7 coverage gap: the positive case is weakly guarded.

2. [IMPORTANT] **ADR-001 §3.2 / §8 still contain `jsonb_path_ops` (3 occurrences)** — Spec §7.7 explicitly notes this as pending tech debt ("在 `/shipping-release` 階段一併處理"). The ADR at `docs/grimo/adr/ADR-001-postgresql-migration.md` lines 48, 154, 214 still say `jsonb_path_ops` — the BUG that S016 §2.4 #1 corrected in the V2 migration has not yet been reflected in the ADR. This does not block ship (it is declared tech debt), but must be addressed in the `/shipping-release` step before the ADR misleads future specs.

3. [MINOR] **`DelegatingPermissionEvaluator` has no Logger** — The QA checklist requires Logger on "every new Service / Listener / Controller"; development-standards.md does not formally mandate Logger on pure utility/dispatcher beans. The class has thorough class-level Javadoc. Since no state changes or I/O occur in this class that aren't already logged by `SkillPermissionStrategy`, this is cosmetic but noted.

4. [MINOR] **AC-13 PARTIAL explicitly acknowledged** — Spec §7.7 correctly records that EXPLAIN bitmap-scan validation is deferred to S017 due to HikariCP connection-scope limitation with `SET LOCAL enable_seqscan=off` and small-dataset planner decisions. The schema meta verification (V2MigrationTest checking `indexdef` does not contain `jsonb_path_ops`) and functional hit test (`SkillPermissionStrategyTest.ginIndexUsableForAnyKeyMatch`) are adequate substitutes for the MVP milestone. No action required before ship.

**Recommendation**: ship — after ADR-001 correction in the `/shipping-release` step (finding #2 is declared pending-at-ship). Finding #1 (alice assertion weakness) is IMPORTANT but the critical path (bob 403) is verified, and the same flow is verified end-to-end by `e2e_uploadGrantListRevoke_acrossModules` which does check alice's read model state post-grant. The ACL gate for alice's positive path is confirmed by the read-model assertions showing `user:alice:write` seeded, and by `SkillCommandControllerSecurityTest` and `SkillPermissionStrategyTest` unit coverage. Verdict: **PASS** with ADR-001 fix required at `/shipping-release`.

