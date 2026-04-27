---
topic: "S014 ship-ready — T8 design refinement (custom SkillshubPgVectorStore + per-request builder) 三輪 QA PASS"
session_type: "development"
status: "completed"
date: "2026-04-27"
---

# Handover: S014 ship-ready — T8 design refinement 三輪 QA PASS

## Layer 1 — Portable Summary

> 本節任何 agent / 人類可讀。S014（PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清）完整 ready to ship；待用戶執行 `/shipping-release`。

### Completed

- **S014 完整 ship-ready**：T1+T2 mega + T5 + T7 + T6 + T8（post-QA design refinement）全部 PASS
- **T8 architectural refinement**（本 session 主軸）：
  - `build.gradle.kts` 從 `spring-ai-starter-vector-store-pgvector` 換 `spring-ai-pgvector-store`（core artifact，無 auto-config）
  - 新增 `SkillshubPgVectorStore.java`（235 行）— `extends AbstractObservationVectorStore` + Builder pattern；6-欄 `INSERT ... ON CONFLICT DO UPDATE COALESCE` SQL；批次 PreparedStatement；DocumentRowMapper for similarity search
  - `SearchConfig.java` 削減 — 移除 `simpleVectorStore @Bean`、移除所有 `VectorStore` import；只剩 `googleGenAiEmbeddingModel` + `noOpEmbeddingModel` + 內嵌 `NoOpEmbeddingModel` private class
  - `SearchProjection.java` 重構為 per-request builder — constructor 注入 `(JdbcTemplate, EmbeddingModel, CurrentUserProvider)`；`onSkillCreated` / `onVersionPublished` 各自 `SkillshubPgVectorStore.builder(jdbc, em).owner(...).skillId(event.aggregateId()).build().add(...)`；移除 `instanceof PgVectorStore` guard、`UUID.fromString` workaround、`updateOwnerAndSkillId` private method（T7 兩步驟全清）
  - `SemanticSearchService.java` 同模式重構 — constructor 注入 `(JdbcTemplate, EmbeddingModel)`；`search()` 用 builder
  - `application.yaml` + `test/resources/application.yaml` 完全刪 `spring.ai.vectorstore.pgvector.*` + `skillshub.search.vector-store`
  - 刪 `SemanticSearchPocTest.java`（SimpleVectorStore POC obsolete）+ `SemanticSearchTest.java`（與 IntegrationTest scope 重疊）
  - 重寫 `SearchProjectionTest.java`（@SpringBootTest + Testcontainers，3 個 cases 含 per-request isolation 驗證）
  - 重寫 `SemanticSearchIntegrationTest.java`（移除 `vector-store=simple` override，valid UUID seed via `SkillshubPgVectorStore.builder(jdbc, em)`）
  - 重寫 `PgVectorStoreOwnerWriteTest.java`（直接 instantiate builder，3 個 cases 含 6-欄驗證 + COALESCE 防護驗證 + schema introspection）
  - 重寫 `SearchConfigTest.java`（移除 simpleVectorStore tests + helpers）
- **三輪獨立 QA PASS** 全部記錄於 spec §7：
  - §7.11 第一輪 QA subagent（T7 ship 後）
  - §7.12 第二輪 QA subagent（T8 ship 後）
  - §7.13 第三輪 `/verifying-quality` skill 主驗（含 hermetic E2E + boundary CJK/escape quote）
- **計畫外修正** — 過程發現問題已修：
  - `SkillProjection.on(SkillCreatedEvent)` 加 `@Order(HIGHEST_PRECEDENCE)`（T7 加；T8 保留）— 原本 21 個整合測試 FK violation 因 listener 順序未定
  - `search/package-info.java` `allowedDependencies` 加 `"shared :: security"`（T7 加；T8 保留）— SearchProjection 注入 CurrentUserProvider 觸發 ModularityTests
  - `compose.yaml` mock-oauth2-server healthcheck 改 `wget -q -O - ... > /dev/null`（T5 加，T8 保留）— 原 spider 模式對該 mock server 收 404
  - `application-local.yaml` 保留 `firestore.enabled=false` 至 T7（T5 試圖移但 dep 還在 → bootRun fail）— T7 與 dep 同步清乾淨
  - 移除 `application.yaml` + `config/application-dev.yaml` 的 `vector-store: simple` 預設（T7 加；T8 保留）— 否則雙 VectorStore bean 衝突
- **§7 標題重複編號修正** — 原本兩個 `### 7.10`，已重編為 7.10 / 7.11 / 7.12 / 7.13 連續無重複
- **121/115 tests 演進** — T7 ship 121；T8 後 115（-3 SemanticSearchPocTest -3 SemanticSearchTest -2 SearchConfig simple +1 SearchProjection +1 PgVectorStoreOwnerWrite）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| **自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore` 取代官方 starter** | vector_store schema 含 owner/skill_id 自訂欄位（S016/S017 ACL 鋪路），官方 starter 的 4-欄 INSERT 無法擴；自寫子類在 `doAdd` 走 6-欄 INSERT 一次寫完（atomic + 單 round-trip）；對齊 CLAUDE.md「Spring AI Manual Configuration」原則（與 S007 GoogleGenAi 同模式）| A. 官方 starter + add+UPDATE 兩步驟（T7 ship 過此版；中間視窗 owner=NULL observable；兩個 round-trip）<br/>B. 繼承 `PgVectorStore` 並 override `doAdd`（多數內部欄位 private，不易乾淨 override）<br/>C. owner 塞 metadata JSON（無 GIN，S017 ACL filter 路徑不對齊） |
| **Per-request instantiation，不註冊 Spring Bean** | owner/skillId 是 per-write context，不適合 singleton bean attributes；每次 `SearchProjection.onSkillCreated` / `SemanticSearchService.search` 用 builder 建構新 instance、操作完 GC；無 thread-safety 顧慮、無 singleton state leak | A. 註冊 singleton VectorStore Bean + 用 ThreadLocal 傳 owner（複雜且易漏）<br/>B. 注入 `Supplier<VectorStore>` factory（多一層抽象，無實質效益）<br/>C. 把 owner 放 builder 但 build 出 singleton Bean（混合語意，違反 per-request 直覺） |
| **移除 SimpleVectorStore fallback** | dev 走 Docker Compose pgvector container（dev/prod parity）；測試需要 simple 時可顯式 override；無 fallback 必要；移除避免雙 VectorStore bean 衝突 | 保留 `simpleVectorStore @Bean` 作 fallback — dev 走 in-memory vs prod 走 PgVectorStore，行為差異使搜尋語意難以信任 |
| **`ON CONFLICT DO UPDATE` 用 `COALESCE(EXCLUDED.owner, vector_store.owner)`** | 防後續 ingest 不帶 owner（如 batch sync 場景）時被 NULL 蓋掉；保留首次寫入的 owner；防禦深度 | A. 直接 `EXCLUDED.owner`（後續寫入不帶 owner 會清空既有值）<br/>B. 不用 ON CONFLICT，每次先 SELECT 判斷（race condition + 多一個 round-trip） |
| **id 欄位用 SQL `?::uuid` cast 而非 Java `UUID.fromString`** | `vector_store.id` 為 PostgreSQL `UUID` type；Java `String` 透過 PreparedStatement 預設綁 VARCHAR 觸發 type mismatch；`?::uuid` 在 SQL 層完成轉型，與官方 PgVectorStore 同手法（驗自 §2.4 #13） | Java `UUID.fromString(docId)` — 多一層轉換、若 id 非 UUID 字串會 throw IllegalArgumentException（T7 兩步驟版採此路徑） |
| **§7 標題重複編號修正** | spec §7 原本有兩個 `### 7.10`（design refinement + 第一輪 QA review）；本輪修正為 7.10 / 7.11 / 7.12 / 7.13 連續編號 | 留著重複編號 — 影響可讀性，doc auditing 麻煩 |

### Next Steps

1. **執行 `/shipping-release`** — S014 ready to ship；三輪 QA PASS verdict
   - 預期 commit message 風格：`feat(persistence): ship S014 — PostgreSQL 資料層遷移 + custom SkillshubPgVectorStore（含 S015 absorbed）`
   - tag：`v1.1.0`（依 PRD 版號慣例；MVP 為 v1.0.0，本 spec 為 first post-MVP feature）
   - 註記 ADR-001 §4.5（S015 absorbed 脈絡）+ §2.1 決策 #2/#12 二度修訂歷史
2. **（可選）先處理 2 個 IMPORTANT pre-existing project gaps**：
   - **JaCoCo plugin 缺失** — `qa-strategy.md` 宣告 80% 線覆蓋率但 `build.gradle.kts` 無 plugin；`./gradlew jacocoTestCoverageVerification` task-not-registered；建獨立 spec 補
   - **無 verification command registry / verify-all script** — `/verifying-quality` Step 0.5 期望結構化 registry table + executable script；目前無；建獨立 spec 建立 `scripts/verify-all.sh`
3. **（可選）4 個 MINOR doc cleanup** — 可後處理或忽略：
   - `SkillshubProperties.Search.vectorStore` field 為 dead code（T8 後無 production consumer）
   - `V1__initial_schema.sql` 第 120-127 stale 註解（仍說「S015 接管時」、「FirestoreVectorStore 仍主導」— T8 後皆失效）
   - `SearchProjection` 用 positional `log.info()`，與 `SkillProjection` / `ScanOrchestrator` fluent `log.atInfo().addKeyValue()` 風格不一
   - spec §1 架構圖 + §2.3 vector_store 段仍顯示「過渡狀態」帶 FirestoreVectorStore→S015（T8 後 §7.13 已記錄但 §1/§2.3 未改）
4. **後續 spec**：
   - **M14 S016 Row-Level ACL 基礎建設** — 加 `acl_entries JSONB` + GIN index + @PreAuthorize；`SkillshubPgVectorStore.getNativeClient()` 為 ACL filter 鋪路
   - **M15 S017 ACL-Aware 語意搜尋** — `SkillshubPgVectorStore.doDelete(Filter.Expression)` 此時實作（目前 throw `UnsupportedOperationException`）
   - **M16 S018 Skill Aggregate 充血演化** — 已 ⏳ Design 狀態；需 S014 event store JDBC + S016 PermissionEvaluator

### Lessons Learned

- **「per-request builder pattern」對 per-write context 很乾淨** — owner/skillId 鎖在 builder 建構出來的 instance attribute 裡，操作完 GC；避免 singleton ThreadLocal 或 Supplier factory 的複雜度。trade-off：每次寫入有 object allocation，但 skill 上傳非 hot path（< 1/s），可接受。
- **`ON CONFLICT DO UPDATE` 的 `EXCLUDED.col` vs `table.col` 語義微妙** — `EXCLUDED.col` 是想插入但被 ON CONFLICT 攔下的新值；`table.col` 是表中既有值。`COALESCE(EXCLUDED.col, table.col)` 防禦「後續寫入不帶 col」場景，保留首次值。
- **PostgreSQL `UUID` type 在 PreparedStatement 必須 SQL-side cast** — 官方 PgVectorStore 與我們的 SkillshubPgVectorStore 都用 `?::uuid` 而非 Java `UUID.fromString`；這是 PostgreSQL JDBC driver 預設行為（String 綁 VARCHAR）的標準解。
- **Spring Modulith `allowedDependencies` 必須跟著 cross-module 注入更新** — SearchProjection 注入 `CurrentUserProvider`（在 `shared.security` named interface 裡）→ search 模組 package-info 必須加 `"shared :: security"`；否則 ModularityTests 直接 fail。
- **`@EventListener` 順序由 `@Order` 顯式控制；同優先級的 listener 順序未定** — T7 階段 SearchProjection 寫 `vector_store.skill_id` FK 至 skills.id 必須等 SkillProjection.on(SkillCreatedEvent) 寫完 → 兩 listener 都需 `@Order` 或 `@DependsOn`。錯過會出現 21 個整合測試 FK violation。
- **`google-cloud-firestore` dep 與 `firestore.enabled=false` yaml 設定必須同步移除** — 移 yaml 但留 dep → `GcpFirestoreAutoConfiguration` 啟用後找不到 `GcpProjectIdProvider` → bootRun fail with `APPLICATION FAILED TO START`。T5 試圖移 yaml 但 dep 還在被踩到；T7 才一起清。
- **`mock-oauth2-server:3.0.1` 的 `wget --spider` healthcheck 拿到 404** — server 不支援 HEAD on `/.well-known/openid-configuration`；改 `wget -q -O - ... > /dev/null`（GET）即正常。pre-existing compose.yaml bug，前 session 從未 bootRun 故未發現。
- **`./gradlew bootRun` 觸發 `:processAot` 失敗（GraalVM `org.graalvm.buildtools.native:0.11.5` plugin）** — 與 native build 無關但被加進 bootRun 任務鏈。Workaround：`./gradlew bootRun -x processAot`。**pre-existing tech debt**，已在 spec §7.9 列為 follow-up（建議與 OpenTelemetry observability 切換獨立 spec 一併處理）。
- **`/verifying-quality` skill Step 0.5 protocol 強制 verification command registry + executable script** — 本專案缺；本輪 QA review 列為 IMPORTANT（pre-existing）但未建。建議獨立 spec 補。
- **`AbstractObservationVectorStore` constructor 需要 `AbstractVectorStoreBuilder<?>`** — 自寫子類的 `Builder` 必須 `extends AbstractVectorStoreBuilder<Builder>`，constructor 用 `super(builder)`；父類自動處理 EmbeddingModel + ObservationRegistry + BatchingStrategy 三個 protected field。
- **官方 PgVectorStore source 是最佳參考** — 6-欄 INSERT SQL 模板、`PGvector` 用法、`StatementCreatorUtils.setParameterValue` 模式都可直接參考；不必重新發明。

### Session Summary

本 session 從 `/takeover` 接 S014 設計階段（已完成 §1-§5 spec + 8 個 task tracking），承接後執行完整 `/planning-tasks S014` → `/implementing-task` 任務循環，依序 ship T5（GCP Cloud SQL Auth Proxy sidecar yaml）→ T7（PgVectorStore 接管 + Firestore 全清，含計畫外修正 SkillProjection @Order + search 模組 allowedDeps + vector-store=simple 預設移除）→ T6（grep sweep + 5 endpoint smoke + vector_store row 斷言）→ Phase 4 consolidate + 第一輪 QA subagent PASS。隨後 user 提出 architectural refinement：採用「自寫 PgVectorStore 子類 + per-request builder」取代 starter + 兩步驟 workaround（更乾淨的 atomic 設計）。執行 Post-Verification Bug Re-Entry Protocol：spec status 回 ⏳ Dev → 修訂 §2.1 決策 #2/#12（再次修訂）+ §4.12-4.14 + §5/§6 + 新增 T8 task → ship T8（含計畫外修正：`AbstractObservationVectorStore` constructor 簽名、`EmbeddingOptions` 正確 import、`SqlTypeValue` package、`skills.name` UNIQUE constraint 在測試中需 random name）→ 第二輪 QA subagent PASS。最後執行 `/verifying-quality` skill 第三輪主驗（hermetic E2E + boundary CJK/escape quote + 6-欄 SELECT + design integrity 14-check + JaCoCo / verification registry 缺失列為 IMPORTANT pre-existing）— 一致 PASS verdict。Session 結束於 ship-ready 狀態，等待 `/shipping-release` 觸發 commit + tag + archive。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | **115/115 PASS**（最後 clean run 17:22；37 test classes；0 failures / 0 errors / 0 skipped）|
| Java | OpenJDK 25.0.1 |
| Gradle | 9.4.1 |
| Spring Boot | 4.0.6 |
| Spring AI | 2.0.0-M4 |

### Uncommitted Changes（55 個 entries — S014 完整 changeset）

**已修改（M）— production code**：
```
backend/build.gradle.kts                              (T8: starter→core dep swap)
backend/compose.yaml                                  (T5: mock-oauth2 healthcheck spider→GET)
backend/config/application-dev.yaml                   (T7: 移 vector-store: simple)
backend/src/main/java/.../analytics/*.java            (T2 mega: @Document→@Table 等)
backend/src/main/java/.../search/SearchConfig.java    (T7+T8: 削減為 EmbeddingModel-only)
backend/src/main/java/.../search/SearchProjection.java (T8: per-request builder pattern)
backend/src/main/java/.../search/SemanticSearchService.java (T8: builder pattern)
backend/src/main/java/.../search/package-info.java    (T7: allowedDeps 加 shared :: security)
backend/src/main/java/.../security/*.java             (T2 mega: @Table 等)
backend/src/main/java/.../shared/events/*.java        (T2 mega)
backend/src/main/java/.../skill/query/SkillProjection.java (T7: @Order(HIGHEST_PRECEDENCE) on SkillCreatedEvent)
backend/src/main/java/.../skill/query/SkillQueryService.java (T2 mega: NamedParameterJdbcTemplate)
backend/src/main/java/.../skill/query/Skill*Repository.java (T2 mega: ListCrudRepository)
backend/src/main/resources/application*.yaml          (T5+T7+T8: yaml cleanup; sidecar)
backend/src/test/java/.../TestcontainersConfiguration.java (T1: pgvector container)
backend/src/test/java/.../search/SearchConfigTest.java       (T8: 移 simpleVectorStore tests)
backend/src/test/java/.../search/SearchProjectionTest.java   (T8: @SpringBootTest + Testcontainers)
backend/src/test/java/.../search/SemanticSearchIntegrationTest.java (T8: valid UUID, builder seed)
backend/src/test/java/.../security/RiskAssessmentIntegrationTest.java (T2 mega)
backend/src/test/java/.../security/scan/ScanOrchestratorTest.java (T2 mega)
backend/src/test/resources/application.yaml           (T5+T8)
docs/grimo/adr/ADR-001-postgresql-migration.md        (Session 開頭：sidecar / S015 absorption)
docs/grimo/specs/2026-04-27-S014-postgresql-migration.md (大規模修訂；§7 加到 §7.13)
docs/grimo/specs/spec-roadmap.md                      (M12 升 L(20) → ✅ Done；M13 absorbed)
```

**已刪除（D）**：
```
backend/src/main/java/.../search/FirestoreVectorStore.java    (T7)
backend/src/test/java/.../search/SemanticSearchPocTest.java   (T8: SimpleVectorStore POC obsolete)
backend/src/test/java/.../search/SemanticSearchTest.java      (T8: scope 與 IntegrationTest 重疊)
docs/grimo/tasks/2026-04-27-S014-T1.md ~ T7.md                (Phase 4 cleanup)
```

**新增（??，untracked）**：
```
backend/src/main/java/.../search/SkillshubPgVectorStore.java         (T8 新類，235 行)
backend/src/main/java/.../shared/persistence/                        (T1: JdbcConfiguration + package-info)
backend/src/main/java/.../skill/query/package-info.java              (T2 mega: named interface)
backend/src/main/resources/db/                                        (T1: V1 Flyway migration)
backend/src/test/java/.../search/PgVectorStoreOwnerWriteTest.java    (T7 新；T8 重寫)
backend/src/test/java/.../shared/events/DomainEventSequenceUniquenessTest.java (T1)
backend/src/test/java/.../shared/persistence/                        (T1: MapJsonbConverterTest)
backend/src/test/java/.../skill/query/AtomicDownloadCountTest.java   (T1: AC-6)

.claude/handovers/archive/2026-04-27-s014-spec-adr-task-...md   (前一輪 handover)
.claude/handovers/archive/2026-04-27-s014-t2-mega-...md          (T2 mega handover)
.claude/scheduled_tasks.lock                                     (Claude harness file)
```

### Recent Commits

```
15822e1 docs(planning): plan Phase 2 — S014 PostgreSQL migration + S018 Skill aggregate evolution
f1e6120 docs(skills): add 2 audit gates to planning-spec research-protocol
6a7db35 docs(handovers): archive 2026-04-27 session — S011 ship + S012/S013 design
73d534e feat(deploy): ship S013 — GCP Cloud Run 部署腳本（v1.0.0 MVP complete 🎉）
a512e97 feat(security): ship S012 — OAuth 開關 + LAB 模式 + CurrentUserProvider 抽象
```

### Key Files

**T8 核心新增 / 重寫**（必看以掌握 architecture）：
- `backend/src/main/java/.../search/SkillshubPgVectorStore.java` — **核心新類 235 行**；自訂 PgVectorStore 子類；6-欄 INSERT；Builder pattern；觀察 spec §4.14
- `backend/src/main/java/.../search/SearchProjection.java` — per-request builder 用法範例
- `backend/src/main/java/.../search/SemanticSearchService.java` — 同模式（讀取場景無 owner/skillId）
- `backend/src/main/java/.../search/SearchConfig.java` — 削減至 EmbeddingModel-only
- `backend/build.gradle.kts` line 36 — `spring-ai-pgvector-store` core dep
- `backend/src/main/resources/application.yaml` — 無 `spring.ai.vectorstore.pgvector.*` 與 `skillshub.search.vector-store`

**Spec / Roadmap**（權威記錄）：
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` — **Status: ✅ Done — 待 QA subagent 重新驗證**；§2.1 決策 #2/#12 二度修訂（✱✱ / ★★ 標記）；§4.12-4.14 設計範本；§7.10 design refinement；§7.11/§7.12/§7.13 三輪 QA reviews
- `docs/grimo/specs/spec-roadmap.md` — M12 ✅ Done；M13 ABSORBED into S014
- `docs/grimo/adr/ADR-001-postgresql-migration.md` — §4.5 S015 absorption rationale

**Resume command sequence**（用戶下個動作）：
```bash
cd /Users/samzhu/workspace/github-samzhu/skills-hub

# 1. Verify state（可選 sanity check）
git status --short | wc -l                   # 預期 55 個 entries
cd backend && ./gradlew test 2>&1 | tail -3  # 預期 BUILD SUCCESSFUL；115/115

# 2. Ship
/shipping-release
# 預期 commit message: feat(persistence): ship S014 — PostgreSQL 資料層遷移 + custom SkillshubPgVectorStore（含 S015 absorbed）
# 預期 tag: v1.1.0
```

**或先處理 2 個 IMPORTANT pre-existing project gaps（建獨立 spec）**：
1. JaCoCo plugin 缺失 spec — `./gradlew jacocoTestCoverageVerification` 任務 + 80% line coverage gate
2. Verification command registry / `scripts/verify-all.sh` spec — 把 `./gradlew test` / `compileTestJava` / `jacocoTestCoverageVerification` / ModularityTests 等命令編入 registry

兩個都是 project-level 多 spec 共享的 follow-up，**不阻擋 S014 ship**。
