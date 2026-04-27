---
topic: "S014 T2 mega — Atomic Mongo→PostgreSQL migration (中斷於最後一輪 ./gradlew test)"
session_type: "development"
status: "in_progress"
date: "2026-04-27"
---

# Handover: S014 T2 mega — Atomic Mongo→PostgreSQL migration

## Layer 1 — Portable Summary

> 任何 agent（Claude / Gemini / Copilot / 人類）皆可讀此節，包含恢復工作所需的全部脈絡。

### Completed

**Deep Research + 設計階段**：
- `docs/deepwiki/spring-acl-pgvector/` — spring-acl-jsonb × pgvector 完整深度分析（6 份檔案）
- `docs/grimo/adr/ADR-001-postgresql-migration.md` — 反轉 D8/D9/D14（Firestore→PostgreSQL）
- `docs/grimo/specs/spec-roadmap.md` — 加 M12-M16（S014-S018，user 外加 S018 Aggregate 充血）
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` — M-L(15) spec 含 ADR、規格、AC、File Plan
- 6 個 task files（T1-T6）

**T1: Foundation + Map Converter POC** — ✅ PASS
- `MapJsonbConverterTest` 測試 Map<String,Object> ↔ PostgreSQL JSONB 雙向 round-trip：通過
- 加入：JdbcConfiguration、shared/persistence package、V1__initial_schema.sql（6 表 + extensions + indexes）、TestcontainersConfiguration 加 pgvector container
- build.gradle.kts additive：`spring-boot-starter-data-jdbc` + `spring-ai-starter-vector-store-pgvector` + `spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql` + `postgresql` driver + `testcontainers-postgresql`

**T2 mega（合併 T2+T3+T4）** — 程式碼變更已完成，**但最後 `./gradlew test` 尚未驗證**：
- 5 個 records `@Document → @Table`：DomainEvent / SkillReadModel / SkillVersionReadModel / FlagReadModel / DownloadEventReadModel
- 5 個 repos `MongoRepository → ListCrudRepository` + 加 @Modifying @Query helpers：incrementDownloadCount / updateLatestVersion / updateRiskLevel / updateRiskAssessment
- SkillQueryService dynamic search：MongoTemplate Criteria → NamedParameterJdbcTemplate（含 LIKE wildcard escape + ORDER BY 白名單）
- AnalyticsService：MongoTemplate count + Aggregation → SQL COUNT + ORDER BY LIMIT
- ScanOrchestrator：mongoTemplate.updateFirst → repo.updateRiskLevel + repo.updateRiskAssessment（Modulith 加 `skill::query` named interface allowed）
- SkillProjection.on(SkillDownloadedEvent)：read-modify-write → atomic incrementDownloadCount
- SkillProjection.on(SkillVersionPublishedEvent)：read-modify-write → atomic updateLatestVersion
- 移除 Mongo：build.gradle 三個 deps、TestcontainersConfiguration 的 mongoContainer、compose.yaml 的 mongodb（換 pgvector pg16）、application.yaml 的 spring.mongodb / spring.data.mongodb
- 新增 2 個 unit test：`DomainEventSequenceUniquenessTest` + `AtomicDownloadCountTest`
- 修正 RiskAssessmentIntegrationTest + ScanOrchestratorTest：MongoTemplate mock → repo mocks
- Modulith named interfaces：`shared :: persistence`（新增）+ `skill :: query`（新增 package-info）

**T3 / T4** — 標 SUPERSEDED（合併入 T2 mega）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| 反轉 D8/D9/D14 → PostgreSQL | Firestore `array-contains-any` 30 元素硬上限阻塞企業級 ACL | 留 Firestore + 受限 ACL |
| 4-spec 拆分 + ADR-001 | L+ 規模強制拆分；分階段驗證遷移風險 | 單一 mega-spec |
| Spring AI 官方 PgVectorStore（透過 starter）+ 自訂 schema | 維護成本最低；支援 owner 欄位（schema-validation=false） | 自訂實作（與官方版分歧） |
| Cloud SQL Enterprise + PG18 + db-f1-micro + Private IP + VPC Connector | 官方推薦最佳實踐；無公網暴露；不引入 socketFactory 依賴 | Cloud SQL Java Connector（postgres-socket-factory）會觸發 `CloudSqlEnvironmentPostProcessor` 驗證 |
| HikariCP maximum-pool-size=3（GCP） | db-f1-micro max_connections=25 - 5 reserved = 20 ÷ 5 instances = 4 → 取 3 留 buffer | pool=10（會超過 DB 限制） |
| T2 mega-merge（原子化 Mongo 移除） | user 指示「Mongo 相關可以移除了」；移除 Mongo deps 會破壞所有 MongoTemplate / @Document 編譯 | 維持 T2-T4 漸進遷移 |
| `Persistable<String>` isNew()=true 在所有 5 個 records | Spring Data JDBC 對 record + 非 null id 預設 UPDATE，導致 save() 變 0 rows affected；read models projection 透過 save() 只用於 INSERT，update 走 @Modifying @Query | 加 @Version 欄位（schema 改動較大） |
| 加 `spring-boot-flyway` 依賴 | Spring Boot 4 split auto-config 到獨立 artifact；`spring-boot-starter-data-jdbc` 不含 Flyway auto-config | 預設依賴（沒 trigger Flyway） |

### Blockers

**全套 `./gradlew test` 23 failures 尚未驗證最新修正後的結果**

| Attempt | Result | Why It Failed |
|---------|--------|---------------|
| 1️⃣ 全套測試（T2 mega 完成 + Mongo 移除） | 27 failures：bad SQL grammar `domain_events does not exist` | Spring Boot 4 split auto-config — Flyway auto-config 不在 `spring-boot-starter-data-jdbc`；需顯式加 `spring-boot-flyway` |
| 2️⃣ 加 `spring-boot-flyway` 後再測 | 23 failures：`Expected size: 1 but was: 0` 等 | Spring Data JDBC 對 record + non-null id 預設「UPDATE → 0 rows affected」；save() 沒真的 INSERT |
| 3️⃣ 加 Persistable<String> + atomic updateLatestVersion | **未驗證**（user 中斷 ./gradlew test 跑） | — |

Current hypothesis: 第 3 次修正應該大部分通過。剩餘可能 issue：
- ScanOrchestratorTest 的 ObjectMapper 真實實例（非 mock）可能在 Jackson 3 序列化時對某些 record 行為不同
- AnalyticsControllerTest 可能與 OverviewStats deserialize 有關
- RiskAssessmentIntegrationTest 的 SARIF JSONB 可能有 nested type 推論問題

### Next Steps

1. **跑 `cd backend && ./gradlew test`** — 驗證最新修正（Persistable + atomic UPDATE）的成效，紀錄 pass/fail 數量
2. 若仍有 failure，diagnose 各 test 的 root cause（讀 build/test-results/test/*.xml）
3. 修正剩餘 failures → 全綠 → T2 mega PASS
4. 進 T5：完整 vectorstore.pgvector yaml + GCP profile yaml + 確認 compose.yaml
5. 進 T6：ModularityTests 確認模組邊界 + 全套回歸 + 5 個 endpoint smoke test
6. 觸發獨立 QA subagent（`/verifying-quality S014`）
7. user `/shipping-release` ship S014

### Lessons Learned

- **Spring Boot 4 split auto-config 到 per-feature artifact**：`spring-boot-starter-data-jdbc` 不含 `spring-boot-flyway` 的 auto-config；S014 T1 預設啟動時 V1 不跑，必須顯式加 `org.springframework.boot:spring-boot-flyway`。Flyway auto-config 在 4.x 是獨立 artifact。
- **Spring Boot 4 重組 test slice package**：`@DataJdbcTest` / `@AutoConfigureTestDatabase` 從 `org.springframework.boot.test.autoconfigure.X` 改為 `org.springframework.boot.X.test.autoconfigure`。
- **Spring Boot 4 帶 Jackson 3.x（`tools.jackson.*`）為 primary ObjectMapper**：Jackson 2.x 仍透過 BOM 在 classpath（既有 SarifReporter 持續使用）。新 code 統一用 Jackson 3。
- **Spring Modulith `@NamedInterface("X")` 暴露子 package**：跨模組引用 `module :: X` 必須在子 package 加 `@NamedInterface`，且 module 主 package-info 用 `@ApplicationModule`。新增 `shared :: persistence` 與 `skill :: query` named interfaces。
- **Spring Data JDBC + record + non-null id 預設 UPDATE 而非 INSERT**：record 自帶 id 時 `repo.save()` 會跑 UPDATE；要 INSERT 必須實作 `Persistable<String>` 並讓 `isNew()=true`。Read model projection 設計：`save()` 只用於建立新 row（INSERT），既有 row 更新走 `@Modifying @Query`（避免歧義）。
- **Spring AI `PgVectorStoreAutoConfiguration` 預設啟用**：與既有 `SearchConfig.simpleVectorStore` / `firestoreVectorStore` 衝突；S014 階段必須在 application.yaml 排除此 auto-config（S015 接管時移除排除設定）。
- **Cloud SQL Java Connector（postgres-socket-factory）會觸發 `spring-cloud-gcp-autoconfigure.CloudSqlEnvironmentPostProcessor`** 啟動驗證 `spring.cloud.gcp.sql.database-name`；非 GCP profile 也會 fail。**改用 Private IP + VPC Connector** 路線後不需要此依賴，整體簡化。
- **db-f1-micro `max_connections = 25`**（[Cloud SQL flags](https://docs.cloud.google.com/sql/docs/postgres/flags) 文件依 RAM 決定）；HikariCP pool 必須對應 Cloud Run autoscale 計算 → pool=3 安全。
- **PostgreSQL `?|` 運算子在 JDBC 中需 escape 為 `??|`**（spring-acl-jsonb pattern）；目前 S014 不直接用此運算子，但 S016/S017 會用。
- **Testcontainers `@ServiceConnection` + Spring Boot 4** 與 PostgreSQLContainer 配合無痛；`asCompatibleSubstituteFor("postgres")` 讓 pgvector image 被識別為 postgres-compat。
- **JSONB 欄位的 `@Column` 會自動透過 JdbcConfiguration 的 Map<String,Object>↔PGobject converter 處理**，無需個別 RowMapper（除 SkillQueryService 動態 SQL 處）。

### Session Summary

本次 session 從 deep research（spring-acl-jsonb × pgvector）開始，使用者用實證資料引導四個重大決策：(1) 反轉 D8/D9/D14 → 全面遷往 PostgreSQL，(2) 採用 Spring AI 官方 PgVectorStore + 自訂 schema 含 owner 欄位，(3) 用 Cloud SQL Enterprise + PG18 + db-f1-micro + Private IP + VPC Connector，(4) 把原本 T2/T3/T4 漸進遷移合併為「T2 mega 原子化移除 Mongo」。寫好 ADR-001、S014 spec、6 個 task files 後執行：T1 一次通過（POC validate Map↔JSONB Converter），T2 mega 完成所有程式碼變更（5 records / 5 repos / dynamic queries / atomic updates / Mongo 整套移除），但測試遇到 3 階段問題：① V1 schema 沒跑（需顯式加 spring-boot-flyway artifact），② Spring Data JDBC 對 record 預設 UPDATE 路徑（需 Persistable<String>.isNew()=true），③ projection read-modify-write 與 isNew()=true 衝突（改 atomic @Modifying @Query）。修完上述三層問題後 user 中斷在「最後一輪 ./gradlew test」之前。下一步只需跑全套測試驗證即可繼續推進。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | 上次 `./gradlew test` = 23 failures（Persistable 修正前）；最新修正後**未驗證** |
| Java | OpenJDK 25.0.1 |
| Gradle | 9.4.1 |
| Docker | OrbStack 29.4.0 ✓ |

### Uncommitted Changes

```
M backend/build.gradle.kts
M backend/compose.yaml
M backend/src/main/java/io/github/samzhu/skillshub/analytics/AnalyticsService.java
M backend/src/main/java/io/github/samzhu/skillshub/analytics/DownloadEventReadModel.java
M backend/src/main/java/io/github/samzhu/skillshub/analytics/DownloadEventRepository.java
M backend/src/main/java/io/github/samzhu/skillshub/security/FlagReadModel.java
M backend/src/main/java/io/github/samzhu/skillshub/security/FlagReadModelRepository.java
M backend/src/main/java/io/github/samzhu/skillshub/security/package-info.java
M backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java
M backend/src/main/java/io/github/samzhu/skillshub/shared/events/DomainEvent.java
M backend/src/main/java/io/github/samzhu/skillshub/shared/events/DomainEventRepository.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillProjection.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModel.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModelRepository.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillVersionReadModel.java
M backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillVersionReadModelRepository.java
M backend/src/main/resources/application.yaml
M backend/src/test/java/io/github/samzhu/skillshub/TestcontainersConfiguration.java
M backend/src/test/java/io/github/samzhu/skillshub/security/RiskAssessmentIntegrationTest.java
M backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java
新增（git untracked，需 git add）：
  backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java
  backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/package-info.java
  backend/src/main/java/io/github/samzhu/skillshub/skill/query/package-info.java
  backend/src/main/resources/db/migration/V1__initial_schema.sql
  backend/src/test/java/io/github/samzhu/skillshub/shared/events/DomainEventSequenceUniquenessTest.java
  backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/MapJsonbConverterTest.java
  backend/src/test/java/io/github/samzhu/skillshub/skill/query/AtomicDownloadCountTest.java
  docs/deepwiki/spring-acl-pgvector/*.md（6 檔）
  docs/grimo/adr/ADR-001-postgresql-migration.md
  docs/grimo/specs/2026-04-27-S014-postgresql-migration.md
  docs/grimo/tasks/2026-04-27-S014-T1.md ~ T6.md
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

**Spec / 設計文件**：
- `docs/grimo/adr/ADR-001-postgresql-migration.md` — 遷移決策、4-spec 拆分、Cloud SQL 規格
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` — S014 完整 spec（§1 Goal / §2 Approach / §3 AC / §4 Interface / §5 File Plan / §6 Task Plan）
- `docs/grimo/tasks/2026-04-27-S014-T1.md` ~ `T6.md` — T1 PASS、T3/T4 SUPERSEDED、T2 in-progress、T5/T6 pending

**T1 已就位（PASS）**：
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — 6 表 schema（含 vector_store + owner / skill_id 欄位）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java` — Map<String,Object>↔JSONB Converter（Jackson 3.x `tools.jackson.*`）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/package-info.java` — `@NamedInterface("persistence")`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/MapJsonbConverterTest.java` — POC 通過

**T2 mega 變動點**（最後狀態 = 待測）：
- 5 records 含 `implements Persistable<String>` + `isNew()=true`：DomainEvent / SkillReadModel / SkillVersionReadModel / FlagReadModel / DownloadEventReadModel
- 5 repos extends `ListCrudRepository`（DomainEventRepository / SkillReadModelRepository 含 4 個 @Modifying @Query helpers / SkillVersionReadModelRepository 含 updateRiskAssessment / FlagReadModelRepository / DownloadEventRepository）
- `SkillQueryService` rewrite：NamedParameterJdbcTemplate + LIKE escape + ORDER BY 白名單
- `SkillProjection` rewrite：on(SkillVersionPublishedEvent) 用 `repo.updateLatestVersion(...)`，on(SkillDownloadedEvent) 用 `repo.incrementDownloadCount(...)`
- `ScanOrchestrator` rewrite：用 `skillRepo.updateRiskLevel(...)` + `versionRepo.updateRiskAssessment(skillId, version, json)`
- `AnalyticsService` rewrite：純 SQL（COUNT + ORDER BY LIMIT）
- `compose.yaml`：mongodb service → pgvector/pgvector:pg16 service-connection
- `TestcontainersConfiguration`：移除 mongoContainer，保留 pgvectorContainer
- `application.yaml`：移除 spring.mongodb / spring.data.mongodb；保留 PgVectorStoreAutoConfiguration exclude

**Modulith named interfaces 變動**：
- `backend/src/main/java/io/github/samzhu/skillshub/security/package-info.java` — `allowedDependencies` 加 `shared :: persistence`、`skill :: query`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/package-info.java` — 新增，`@NamedInterface("query")`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/package-info.java` — 從 `@ApplicationModule` 改為 `@NamedInterface("persistence")`

**測試狀態（待驗證）**：
- 上次 23 failures 修正後預期應大部分通過
- 3 個新 unit test：`MapJsonbConverterTest`（PASS）、`DomainEventSequenceUniquenessTest`、`AtomicDownloadCountTest`
- Modified test：`RiskAssessmentIntegrationTest`（用 repository 取代 MongoTemplate 讀取）、`ScanOrchestratorTest`（mock SkillReadModelRepository / SkillVersionReadModelRepository / 真實 ObjectMapper）
- 既有 9 個 OAuth/security 測試 + 7 個 scanner unit test 不需修改

### Resume 流程

```bash
cd /Users/samzhu/workspace/github-samzhu/skills-hub/backend
./gradlew test 2>&1 | tail -10

# 如果還有 failures：
total=$(grep -h 'testsuite' build/test-results/test/*.xml | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' | awk '{s+=$1} END {print s}')
fail=$(grep -h 'testsuite' build/test-results/test/*.xml | grep -oE 'failures="[0-9]+"' | grep -oE '[0-9]+' | awk '{s+=$1} END {print s}')
echo "Total: $total / Failures: $fail"

# 看哪些 test class 有 failures：
for f in build/test-results/test/*.xml; do
  failures=$(grep -c "<failure" "$f" 2>/dev/null)
  if [ "$failures" -gt 0 ]; then
    echo "$failures fail: $(basename $f .xml)"
  fi
done
```

全綠後 → T2 PASS，繼續 T5、T6、subagent QA、ship。
