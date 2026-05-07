---
topic: "S016 Row-Level ACL 基礎建設 — 寫完 spec §1-6 + 6 task files；T1 implementation 已 RED 階段中斷"
session_type: "development"
status: "in_progress"
date: "2026-04-28"
---

# Handover: S016 Row-Level ACL 基礎建設 — Phase 3 Task Loop T1 中斷

## Layer 1 — Portable Summary

> This section is readable by any agent (Claude, Gemini, Copilot, human).
> It contains everything needed to understand and resume this work.

### Completed

- **S016 spec file 寫完 §1-5 設計 + §6 task plan**：`docs/grimo/specs/2026-04-28-S016-row-level-acl-foundation.md`
  - §1 Goal + §2 Approach（13 個 design decisions + 13 個 challenges + research citations + confidence classification 全 Validated）
  - §3 SBE Acceptance Criteria（15 ACs）
  - §4 Interface / API Design（18 個 sub-sections — 完整介面 + SQL + Java code skeletons）
  - §5 File Plan（26 files：19 production + 7 test）
  - §6 Task Plan（POC: not required；6 tasks T1-T6 索引；E2E smoke 屬 T6）
- **Phase 2 Research 完成 4 個並行 sub-agents**（findings 已內聯於 spec §2.4-2.5）：
  - `samzhu/spring-acl-jsonb` reference repo 完整模式 audit
  - Spring Security 7 PermissionEvaluator 注入路徑（`static @Bean MethodSecurityExpressionHandler`）
  - Spring Data JDBC + JSONB `?|` operator 路徑（NamedParameterJdbcTemplate native skip + SqlParameterValue Types.ARRAY 避 IN-list）
  - Spring AI PgVectorStore filter SPI（S017 用，本 spec 只需確保 vector_store schema ready）
- **6 task files 創建完成**：`docs/grimo/tasks/2026-04-28-S016-T{1..6}-*.md` 全 `Status: pending`
  - T1: V2 Flyway migration + StringListJsonbConverter + SkillReadModel.aclEntries（first task — 進行中）
  - T2: PermissionStrategy + DelegatingPermissionEvaluator + AclPrincipalExpander + CurrentUser.groups + SecurityConfig
  - T3: SkillPermissionStrategy + Modulith allowedDeps + 既有 PUT 套 @PreAuthorize
  - T4: ACL events + aggregate methods + SkillCommandService grant/revoke + SkillAclController POST/DELETE
  - T5: SkillProjection ACL listeners + SkillReadModelRepository atomic UPDATE + SkillAclQueryService GET
  - T6: SkillshubPgVectorStore INSERT_SQL 7-col + SearchProjection 寫 acl_entries + 端到端 E2E smoke
- **spec-roadmap.md 同步**：S016 status `🔲 Backlog → ⏳ Design → ⏳ Plan → ⏳ Dev`（T1 in progress）；M14 Milestone 表同步；dependency graph 同步
- **Phase 3 Task Loop T1 啟動 — RED 階段環境前置檢查通過**（Java 25 / Gradle 9.4.1 / Docker via OrbStack OK）；既有 codebase patterns 已讀（JdbcConfiguration / SkillReadModel / TestcontainersConfiguration / AtomicDownloadCountTest）
- **T1 callsite 盤點完成**：8 個 `new SkillReadModel(...)` 呼叫點需更新（5 test + 2 main + 1 rowMapper），位於：
  - `src/main/java/.../skill/query/SkillProjection.java:55`
  - `src/main/java/.../skill/query/SkillQueryService.java:185`
  - `src/test/java/.../search/SemanticSearchIntegrationTest.java:104`
  - `src/test/java/.../search/PgVectorStoreOwnerWriteTest.java:75, 109`
  - `src/test/java/.../search/SearchProjectionTest.java:62, 119`
  - `src/test/java/.../skill/query/AtomicDownloadCountTest.java:46`

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| GIN operator class = default `jsonb_ops`（不是 `jsonb_path_ops`） | PostgreSQL 16 docs 明確：`jsonb_path_ops` 不支援 `?|` / `?` / `?&` key-existence operators | `jsonb_path_ops`（reference repo `samzhu/spring-acl-jsonb` + ADR-001 §3.2 都有此 BUG，本 spec ship 時順帶修 ADR）|
| ACL JSONB schema = flat string array `["type:principal:permission", ...]` | GIN(jsonb_ops) 對每個 array element 建 1 個 index entry；`?|` operator 一次 SQL 比對任意 patterns | 物件陣列（需 `@>` containment、表達力差、index 體積大）|
| MVP type 命名空間 = `user:` / `role:` / `group:`（3 類） | YAGNI；MVP 用戶模型只有 user + roles + groups | 一次啟用 user / role / group / org / dept / room（後三類 B7/B8 才有資料源）|
| MVP permission verb = `read` / `write` / `delete` / `suspend` / `reactivate` | S018 spec §4.6 已寫 hasPermission(..., 'suspend' / 'reactivate')；S016 PermissionEvaluator 需認此 verb（預備）| 只 read/write/delete（S018 整合時還要再來一輪修）|
| PermissionEvaluator 注入 = `static @Bean MethodSecurityExpressionHandler` + setPermissionEvaluator() | Spring Security 7 唯一 documented 路徑（PrePostMethodSecurityConfiguration 的 setExpressionHandler 是 @Autowired(required=false)）；`static` 必要破除 circular dep | 直接 @Bean PermissionEvaluator（Spring Security 7 不 auto-detect）；GlobalMethodSecurityConfiguration（已 deprecated）|
| Strategy/Registry 模式 = PermissionStrategy interface + DelegatingPermissionEvaluator + SkillPermissionStrategy | ADR-001 §5「Strategy/Registry」措辭；MVP 雖只有 Skill，但 PRD B7（Workspace）/B8（WarRoom）將來會有；新增 aggregate 時 zero-mod | 單一 SkillshubPermissionEvaluator switch on targetType（B7 進來時要重構）|
| ACL 變更 = 走 domain events（SkillAclGranted / SkillAclRevoked）→ projection atomic UPDATE | development-standards §23「核心域使用 Aggregate + ES + CQRS」+ 完整 audit trail | 直接 SQL UPDATE（reference repo 模式；破壞 ES 一致性、無 audit）|
| ACL CRUD REST shape = POST/DELETE/GET `/api/v1/skills/{id}/acl`（DELETE 用 query params）| REST 慣用法；DELETE 用 query params 表達 idempotent revoke | 動詞式 `/acl/grant` + `/acl/revoke`（非 REST）；DELETE body（部分 client/proxy 不支援）|
| CurrentUser 擴展 = 加 `groups: List<String>`（不加 orgs/depts）| YAGNI；JWT `groups` claim 是 OIDC 慣例 | 一次加 groups + orgs + depts（claim 不存在；純擺設）|
| `?|` SQL 路徑 = NamedParameterJdbcTemplate 直接寫 `?|` 不需 escape | Spring `NamedParameterUtils.parseSqlStatement()` 已 native skip 三個 `?` operators（spring-framework raw source verified）| raw JdbcTemplate + `??|` JDBC escape（pgjdbc Parser.java unescape `??` → `?`）|
| `?|` ARRAY 參數綁定 = `new SqlParameterValue(Types.ARRAY, String[])` | 避開 NamedParameterJdbcTemplate 對 `Iterable<?>` 的 IN-list 自動展開（會破壞 `?|` 語法）| `addValue("k", List.of(...))` 直接傳（會被展成 `?, ?, ?` IN-list）|
| StringListJsonbConverter 使用 Jackson 3 imports = `tools.jackson.*`（**不是** `com.fasterxml.jackson.*`）| 既有 `MapJsonbConverter` 用 Jackson 3，須一致 | `com.fasterxml.jackson.*`（會編譯錯：tools.jackson 才是 Jackson 3 package）|

### Next Steps

> 直接接續處：`/takeover` 後 → 重新進入 Phase 3 Task Loop T1 的 RED 階段（已掌握 callsite 盤點 + Jackson 3 imports）。

1. **TDD RED for T1** — 在開始寫實作前，先寫 3 個 failing tests：
   - `src/test/.../db/V2MigrationTest.java`：`@SpringBootTest` + `@Import(TestcontainersConfiguration)` — 驗 V2 migration 後 `pg_indexes` 含 `idx_skills_acl_entries`（amname=gin，indexdef 不含 `jsonb_path_ops` 字串）+ `idx_vector_store_acl_entries` + skills.acl_entries backfill from author + vector_store.acl_entries backfill from owner（owner=NULL 維持 `[]` fail-secure）→ 驗 AC-1 / AC-2 / AC-3
   - `src/test/.../shared/persistence/StringListJsonbConverterTest.java`：unit test — `List<String>` ↔ JSONB round-trip + 空 list / null 邊界
   - `src/test/.../skill/query/SkillReadModelAclTest.java`：`@DataJdbcTest` + Testcontainer — save/findById round-trip 驗 aclEntries field 持久化 + `?|` SQL 用 GIN index（`JdbcTemplate.queryForObject("EXPLAIN ...", String.class)`）
2. **TDD GREEN — 寫 production code**：
   - 創 `backend/src/main/resources/db/migration/V2__add_acl_entries.sql`（spec §4.2 完整 SQL；用 `USING GIN (acl_entries)` 不加 operator class 後綴）
   - 創 `backend/.../shared/persistence/StringListJsonbConverter.java`（spec §4.18；用 `tools.jackson.databind.ObjectMapper` + `tools.jackson.core.type.TypeReference`，鏡射既有 `MapJsonbConverter` 模式）
   - 改 `backend/.../shared/persistence/JdbcConfiguration.java`：`userConverters()` list 加 2 個 String-list converter
   - 改 `backend/.../skill/query/SkillReadModel.java`：record 末尾加 `@Column("acl_entries") List<String> aclEntries`（field 12）
   - 改 8 個 callers 補新 component（每個 `new SkillReadModel(...)` 加 `List.of()` 為第 12 參數）：見 Layer 2 §Key Files
3. **驗證** `cd backend && ./gradlew test`（V01 含 ModularityTests）；compile + 全 test green 後 update T1 task file `Status: PASS`
4. **完成 T1 後** invoke `/planning-tasks S016` 進 T2（PermissionEvaluator + CurrentUser groups）
5. **Loop 持續直到** T1~T6 全 PASS → Phase 4 consolidation + spawn `/verifying-quality` subagent → user 跑 `/shipping-release`

### Lessons Learned

- **Jackson 3 imports：** Skills Hub 已切到 Jackson 3（`tools.jackson.*`），不是 `com.fasterxml.jackson.*`。所有新 JSONB converter 必須用 `tools.jackson.databind.ObjectMapper` + `tools.jackson.core.type.TypeReference`。既有 `MapJsonbConverter.java` 是直接模板。
- **`jsonb_path_ops` 不支援 `?|`：** 這是 PostgreSQL 16 官方文件明確（不是 bug 或邊角案例）— `jsonb_path_ops` operator class 只支援 `@>` / `@?` / `@@` containment。Reference repo `samzhu/spring-acl-jsonb` 用 `jsonb_path_ops` 是隱性 BUG（看似工作但 planner 永遠走 seq scan）；ADR-001 §3.2 + §8 也踩同一坑，本 spec ship 時順帶修 ADR。
- **Spring Security 7 `static @Bean MethodSecurityExpressionHandler` 必要：** Spring Security 7 不會自動偵測 `PermissionEvaluator` bean — 必須走 `MethodSecurityExpressionHandler` 注入路徑，且**必須 static**（破 `@EnableMethodSecurity` config 與 expression handler 之間的 circular dep）。raw source verified：`PrePostMethodSecurityConfiguration.setExpressionHandler` 是 `@Autowired(required=false)` 唯一注入點。
- **`NamedParameterJdbcTemplate` `?|` 不需 escape：** spring-framework `NamedParameterUtils.parseSqlStatement()` 有 native skip logic（`if (statement[j] == '?' || statement[j] == '|' || statement[j] == '&') { i = i + 2; continue; }`）— 直接寫 `WHERE acl_entries ?| :patterns`。但 raw `JdbcTemplate` 需 `??|`（pgjdbc 層 escape）。Skills Hub 既有 `SkillReadModelRepository` 用 `@Query` (NamedParam) 風格，新查詢對齊既有。
- **`?|` ARRAY 參數陷阱：** `MapSqlParameterSource.addValue("patterns", List.of("a", "b"))` 會被 NamedParameterJdbcTemplate 自動展成 `?, ?, ?` IN-list — 破壞 `?|` 單一 ARRAY 語意。必須包 `new SqlParameterValue(Types.ARRAY, patternsArray)` 強制走 `ps.setArray()` 路徑。
- **`SkillReadModel` 加 component 是 binary breaking：** record 加 component 改變 canonical constructor 簽名；T1 預期需同步更新 8 個 `new SkillReadModel(...)` call sites（grep 已盤點）。
- **`SkillshubPgVectorStore.INSERT_SQL` 升 6→7 欄屬 T6 範圍：** S014 既有 INSERT_SQL 是 6 欄；本 spec V2 加 `acl_entries` 第 7 欄；T1 只加 schema column + GIN index + backfill（INSERT_SQL 升 7 欄留 T6 一起改）。
- **Spring Modulith allowedDependencies：** `skill/package-info.java` 既有 `["shared :: events", "shared :: api", "storage"]` 缺 `"shared :: security"`；T3 加上後 `ApplicationModules.verify()` 才綠（既有 `shared/security/package-info.java` 已宣告 `@NamedInterface("security")`）。

### Session Summary

本 session 起於 user 觸發 dynamic loop（"讀 spec-roadmap 推進所有 active specs"），目標把 S016 / S017 / S018 推到 ✅。

第一個 tick 切到 S016（順序 5、🔲 Backlog、deps S014 ✅）— 進 `/planning-spec S016`：Phase 1 Context 掃描（PRD / architecture / dev-standards / glossary / S014 archived / ADR-001 / S018 spec），Phase 2 並行 4 個 research sub-agents（reference repo / Spring Security 7 / Spring Data JDBC JSONB / Spring AI VectorStore），Phase 3 grill user 5 個關鍵 scope 決策（GIN BUG / 部署範圍 / Strategy 模式 / event vs SQL / API shape）— user 全選推薦 → 寫完 spec §1-5（13 design decisions + 13 challenges 全 Validated）。

第二個 tick 進 `/planning-tasks S016`：Phase 0 pre-flight 通過（無 PRD 衝突 / 無新 dep），Phase 1 POC skip（spec §2.6 全 Validated），Phase 2 創 6 task files + 寫 spec §6 task plan + roadmap 同步 ⏳ Plan。

第三個 tick 進 Phase 3 Task Loop T1：roadmap 升 ⏳ Dev、invoke `/implementing-task S016`、T1 環境前置檢查通過、讀既有 patterns（JdbcConfiguration / SkillReadModel callsites / TestcontainersConfiguration / 既有 IT 範本 AtomicDownloadCountTest），**揭露 Jackson 3 imports（`tools.jackson.*`）為新 converter 的關鍵發現**。準備寫 RED test 時被 user 中斷觸發 `/handover`。

下一次 session takeover 後：直接從 T1 RED 階段續寫（3 個 failing tests），然後 GREEN（4 個 production files + 8 個 callsite 更新），run V01 verify。Spec / task plan / roadmap 全部 ready，無需重新規劃。

---

## Layer 2 — Environment Details

> This section is for resuming in the same repo/machine.
> Skip this when handing off to a different environment.

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | 未跑（T1 RED 階段中斷；上次 ship 是 v1.1.1 commit `c222f80`，當時全綠）|
| Java | 25.0.1 LTS（OpenJDK；BellSoft）|
| Gradle | 9.4.1 |
| Docker | OrbStack（client 29.4.0）|

### Uncommitted Changes

```
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-04-28-S016-row-level-acl-foundation.md
?? docs/grimo/tasks/
```

> 6 個 S016 task files 在 `docs/grimo/tasks/` 全 untracked。Spec 檔本身 untracked（新建）；roadmap 是 in-place 修改。

### Recent Commits

```
1f62353 chore: update .gitignore (scheduled_tasks.lock + .vscode/) + loop default interval
1858425 chore: untrack .vscode/settings.json (already in .gitignore)
c222f80 feat(frontend): ship S022 — Frontend Verification Baseline (M17 完成 v1.1.1)
70c0187 docs(phase2-sync): ship S021 — align PRD/architecture/glossary/qa-strategy to PostgreSQL + pgvector
97b744a build(verify): ship S020 — Verification Command Registry + verify-all.sh (Round 2 fix)
```

### Key Files

**Spec + Task plans（已寫，無需重看細節，直接執行）：**

- `docs/grimo/specs/2026-04-28-S016-row-level-acl-foundation.md` — S016 主 spec；§4.2 V2 SQL / §4.18 StringListJsonbConverter / §5 file plan 26 files 全列；status `⏳ Dev`
- `docs/grimo/tasks/2026-04-28-S016-T1-schema-and-jsonb-converter.md` — T1 BDD + Target Files + Notes for /implementing-task；**進行中**（RED 階段）
- `docs/grimo/tasks/2026-04-28-S016-T2-permission-evaluator-and-current-user.md` — T2 ready
- `docs/grimo/tasks/2026-04-28-S016-T3-skill-strategy-modulith-preauthorize.md` — T3 ready
- `docs/grimo/tasks/2026-04-28-S016-T4-acl-events-aggregate-command-controller.md` — T4 ready
- `docs/grimo/tasks/2026-04-28-S016-T5-projection-listeners-and-acl-query.md` — T5 ready
- `docs/grimo/tasks/2026-04-28-S016-T6-vector-store-integration-and-e2e-smoke.md` — T6 ready
- `docs/grimo/specs/spec-roadmap.md` — Active Work table + Phase 2 Status Summary + Milestone 14 + dependency graph 全更新；S016 status `⏳ Dev`

**待創建（T1 範圍）：**

- `backend/src/main/resources/db/migration/V2__add_acl_entries.sql` — V2 migration（per spec §4.2 完整 SQL：alter skills + alter vector_store + GIN(jsonb_ops) × 2 + backfill from author × 1 + backfill from owner × 1）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/StringListJsonbConverter.java` — `Writing` + `Reading` static inner classes（per spec §4.18；用 `tools.jackson.*` imports）
- `backend/src/test/java/io/github/samzhu/skillshub/db/V2MigrationTest.java` — 驗 schema + GIN + backfill correctness
- `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/StringListJsonbConverterTest.java` — round-trip unit
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillReadModelAclTest.java` — `@DataJdbcTest` + Testcontainer + `?|` SQL EXPLAIN

**待修改（T1 範圍）：**

- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java` — `userConverters()` list 加 `new StringListJsonbConverter.Writing(objectMapper)` + `new StringListJsonbConverter.Reading(objectMapper)`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModel.java` — record 末尾加 `@Column("acl_entries") List<String> aclEntries`（field 12）
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillProjection.java:55-67` — `new SkillReadModel(...)` constructor call 加第 12 參數 `List.of()`（initial empty；ACL 由後續 grant events 累積）
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java:185-196` — rowMapper 加 `rs.getArray("acl_entries")` 或讓 Spring Data JDBC 自動透過 converter（**注意**：rowMapper 是 raw JDBC 不過 converter，需手動 parse JSONB string）
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchIntegrationTest.java:104-106` — 加 `, List.of()` 第 12 參數
- `backend/src/test/java/io/github/samzhu/skillshub/search/PgVectorStoreOwnerWriteTest.java:75-77, 109-111` — 同上 × 2
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionTest.java:62-64, 119-121` — 同上 × 2
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/AtomicDownloadCountTest.java:46-57` — 同上

**重要 reference（已讀，無需重讀）：**

- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java` — `MapToPGobjectConverter` + `PGobjectToMapConverter` 是 `StringListJsonbConverter` 的直接模板；用 `tools.jackson.*` imports
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — V1 schema（`skills` / `vector_store` 等 6 張表 + 既有 indexes；V2 是 incremental）
- `backend/src/test/java/io/github/samzhu/skillshub/TestcontainersConfiguration.java` — `pgvector/pgvector:pg16` Testcontainer + `@ServiceConnection` + `InMemoryStorageService`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/AtomicDownloadCountTest.java` — `@SpringBootTest` + `@Import(TestcontainersConfiguration)` IT 範本
- `docs/grimo/architecture.md` — 框架版本（Spring Boot 4.0.6 / Java 25 / Gradle 9.4.1 / Spring Modulith 2.0.6 / Spring AI 2.0.0-M4）
- `docs/grimo/development-standards.md` — naming conventions / `@DisplayName("AC-N: ...")` testing rule / `@ApplicationModuleListener` 預期（但本 spec 保留 `@EventListener` per §2.4 Challenge #7 既有 drift）
- `docs/grimo/qa-strategy.md` — V01/V03 verification commands（`./gradlew clean test jacocoTestReport` / `./gradlew jacocoTestCoverageVerification`）
- `docs/grimo/specs/2026-04-27-S018-skill-aggregate-rich-domain.md` — S018 spec；§4.6 用 `hasPermission(#id, 'Skill', 'suspend')` SpEL — S016 必須認 `suspend` / `reactivate` verb（AC-15）

### Resume Workflow

```bash
# 1. takeover
/takeover

# 2. 直接接續 T1 RED — invoke /planning-tasks（會自動偵測 ⏳ Dev + T1 pending → /implementing-task）
/planning-tasks S016

# 或直接：
/implementing-task S016
```
