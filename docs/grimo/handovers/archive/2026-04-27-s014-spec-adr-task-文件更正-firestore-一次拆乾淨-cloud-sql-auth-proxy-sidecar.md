---
topic: "S014 spec/ADR/task 文件更正 — Firestore 一次拆乾淨 + Cloud SQL Auth Proxy sidecar"
session_type: "planning"
status: "in_progress"
date: "2026-04-27"
---

# Handover: S014 文件更正（Firestore 全清 + sidecar 連線）

## Layer 1 — Portable Summary

> 本節任何 agent / 人類可讀。記錄 S014 兩個關鍵 scope 變動的決策、文件更新進度、與下一段執行步驟。

### Completed

**Session 開頭（在文件變動之前）**：
- `/takeover` 讀完 archive/2026-04-27-s014-t2-mega-...md，掌握 T1+T2 mega 的進度（PostgreSQL data layer 已遷好；T5/T6 pending）
- `/verifying-quality` 跑全套 `./gradlew test` → **38 classes / 119 tests / 0 fail / 0 err / 0 skip**（fresh timestamp 2026-04-27 14:12）
- §7（Implementation Results & QA Review）已寫入 spec — 紀錄 T1/T2 PASS、T5/T6 pending、per-AC 分類、findings、required actions

**用戶決策（兩個 scope 變動）**：
- ✅ **B + C 路徑確認**：Firestore 一次拆乾淨（含 code/dep/設定，不留死碼）+ GCP 改 Cloud SQL Auth Proxy sidecar 模式（取代原 Private IP + VPC Connector）

**已完成的文件更正**（部分）：
- `docs/grimo/adr/ADR-001-postgresql-migration.md` —
  - 加 header「修訂 2026-04-27」備註
  - 加 §4.4「Alternative D — sidecar Auth Proxy vs Private IP+VPC vs Java Connector」決策表
  - 加 §4.5「Alternative E — S015 absorption rationale」
  - 修 §5 migration plan：S015 標 ABSORBED-INTO-S014；S014 規模升 L(20)
  - 修 §6.2 GCP integration：sidecar 取代 Private IP + VPC Connector
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` —
  - 改標題（加「+ PgVectorStore 接管 + Firestore 全清」）+ 修訂紀錄區塊
  - §1 Goal 重寫（含 S015 absorbed 說明）
  - §2.1 決策表：修 #2 / #9 / #10、新增 #12 PgVectorStore 兩步驟寫入
  - §2.2 架構契合表：VectorStore SPI row 改寫
  - §2.4 challenge #10 重寫（sidecar）；新增 #15 / #16（owner 寫入策略 + vector_store id 對應）
  - §3 ACs：重寫 AC-10（vector_store 有資料）、AC-12（sidecar 連線）；新增 AC-13（Firestore 完全移除驗證）
  - §4.1 build.gradle deps：移除 `google-cloud-firestore` line + 解釋註解
  - §4.8 application.yaml：加修訂註記說明 T7 必須移除 `autoconfigure.exclude` 並補完整 `spring.ai.vectorstore.pgvector.*`
  - §4.9 application-gcp.yaml：完全重寫為 sidecar Auth Proxy 路線（含 Cloud Run multi-container service.yaml 範例）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| **S015 併入 S014（不分批）** | T2 mega ship 後 Mongo 已乾淨；保留 Firestore 至 S015 會讓 `SearchConfig` 雙條件分支 + `google-cloud-firestore` dep tree + 兩 yaml profile 的 firestore 設定持續耦合 → 一次拆乾淨少一輪 PR review；spec 規模從 M-L(15) 升至 L(20) | A. 只移設定（留 `FirestoreVectorStore.java` 死碼 + dep）— 死碼會讓 Modulith / build cache 混淆且 S015 仍要再清一次 |
| **GCP 連線改 Cloud SQL Auth Proxy sidecar** | dev/prod parity：本機 Docker Compose `pgvector/pgvector:pg16` 暴露 localhost:5432，GCP Cloud Run `cloud-sql-proxy` sidecar 也 listen localhost:5432 → JDBC URL **同一條** `jdbc:postgresql://localhost:5432/...` 跨環境；應用端**無 socket-factory dep**，避免 `CloudSqlEnvironmentPostProcessor` 啟動驗證；IAM auth 由 sidecar 自動處理 | B. Private IP + VPC Connector（dev/prod parity 弱、JDBC URL 不同）；C. `postgres-socket-factory` Java Connector（dep + post-processor 干擾）；D. Public IP（安全） |
| **PgVectorStore owner / skill_id 寫入策略 = 兩步驟 add + UPDATE** | 官方 `vectorStore.add()` SQL 只動 4 欄（id/content/metadata/embedding）；先 add 走官方路徑（保留 observation tracing），再 `getNativeClient().update("UPDATE vector_store SET owner=?, skill_id=? WHERE id=?")` 補寫；冪等 | 自寫 INSERT 完全繞過 add（失去 observation tracing）；把 owner 塞 metadata JSON（無 GIN 支援；S017 ACL filter 路徑不對齊） |
| **Spec scope L(20)**（原 M-L(15)）| S015 absorption + sidecar 配置 = 多出 ~10 個檔（SearchConfig 改寫 / SearchProjection 兩步驟 / FirestoreVectorStore 刪除 / 4 個既有 SemanticSearch* tests 改 PgVectorStore mock / build.gradle / application-gcp.yaml 大改） | 維持 M-L(15) — 不反映實況 |

### Blockers

**沒有真正的技術 blocker** — 所有決策已清晰；剩下純粹是把確認的決策寫進 spec / task 文件。被中斷只因 `/handover` 提早觸發。

### Next Steps

> **嚴格依此順序執行；每步是獨立可驗收的小 chunk。**

#### A. 完成 spec 文件更正（剩 ~5 個段落）

1. **新增 spec §4.12 — SearchConfig 改寫**（在 §4.11 TestcontainersConfiguration 之後 + §5 之前）：
   - 移除 `firestoreVectorStore @Bean`
   - 加 `pgvectorVectorStore @Bean`，注入 Spring AI auto-config 提供的 `PgVectorStore` instance
   - 預設 `skillshub.search.vector-store=pgvector`（既有 `simple` 條件保留為 fallback）

2. **新增 spec §4.13 — SearchProjection 兩步驟寫入**：
   - `onSkillCreated` / `onVersionPublished` 兩個 listener
   - 第一步：`vectorStore.add(documents)`（官方路徑）
   - 第二步：`((PgVectorStore) vectorStore).getNativeClient().orElseThrow().update("UPDATE vector_store SET owner = ?, skill_id = ? WHERE id = ?", ownerValue, skillId, docId)`
   - owner 來源：`CurrentUserProvider.get().id()`（S012 抽象）

3. **修 spec §5 File Plan**：
   - 新增列：`SearchConfig.java` (M)、`SearchProjection.java` (M)、`FirestoreVectorStore.java` (D, delete)、`SemanticSearchTest.java` 等四個既有測試檔 (M, mock 從 FirestoreVectorStore 改 PgVectorStore)
   - 新增列：`PgVectorStoreOwnerWriteTest.java` (A) — AC-10 + AC-13 整合驗證
   - 修 `application.yaml` 列：T7 階段移除 `autoconfigure.exclude PgVectorStoreAutoConfiguration` 一行
   - 修 `application-local.yaml` 列：T5 階段移除 `firestore.enabled: false`
   - 檔案總數估算：原 35 → **~45 檔**

4. **修 spec §6 Task Plan 表**：
   - T5 row scope 改寫為「Application config + GCP Cloud SQL Auth Proxy sidecar yaml + 完整 spring.ai.vectorstore.pgvector.* 設定（移除 autoconfigure.exclude）」
   - **新增 T7 row**：「PgVectorStore takeover + FirestoreVectorStore deletion + google-cloud-firestore dep removal」對應 AC-10 + AC-13；依賴 T1/T2/T5
   - T6 row scope 加：smoke test 包含「上傳後 `SELECT COUNT(*) FROM vector_store > 0`」
   - 執行順序改寫為：`T1 → T2 → T5 → T7 → T6`
   - Estimation 表 Total 改 17–18 → 對應 L(20)；Scope 從 3 升到 4（檔案 35 → 45）；Reversibility 維持 3

5. **完成 spec §7（已寫的 §7 部分）的 task status 表更新**：
   - T7 加一列 pending
   - T5/T6 status 改為 PENDING（依新 task 配置）

#### B. Task files

6. **重寫 `docs/grimo/tasks/2026-04-27-S014-T5.md`**：
   - 標題改：「Application config + GCP Cloud SQL Auth Proxy sidecar + 完整 vectorstore.pgvector wiring」
   - BDD：T5.1 application.yaml 完整化（移除 exclude + 加 vectorstore.pgvector 完整設定）；T5.2 application-local.yaml（drop firestore.enabled=false）；T5.3 application-gcp.yaml（**sidecar JDBC URL** `jdbc:postgresql://localhost:5432/...`）；T5.4 確認 compose.yaml（已就位）；T5.5 bootRun 在 local profile + GET /api/v1/skills 200；T5.6 確認 vector_store 表存在 + 多餘欄位正確（schema introspection 測試）
   - 移除原 BDD 中提及 firestoreVectorStore 的句子（那是 T7 處理）
   - Target Files：`application.yaml`、`application-local.yaml`、`application-gcp.yaml`、`test/resources/application.yaml`
   - Verification：`curl localhost:8080/api/v1/skills` 200 + `psql -c "SELECT COUNT(*) FROM vector_store"` = 0（still 0 because nothing uploaded yet — T5 不啟動寫入；寫入由 T7）
   - AC mapping：AC-8 + AC-12

7. **新增 `docs/grimo/tasks/2026-04-27-S014-T7.md`**：
   - 標題：「PgVectorStore takeover + FirestoreVectorStore deletion + google-cloud-firestore dep removal」
   - BDD：T7.1 build.gradle 移除 `google-cloud-firestore` dep；T7.2 SearchConfig 移除 firestoreVectorStore @Bean、加 pgvectorVectorStore @Bean；T7.3 SearchProjection 兩步驟 add + UPDATE owner/skill_id；T7.4 刪除 `FirestoreVectorStore.java`；T7.5 新增 `PgVectorStoreOwnerWriteTest`；T7.6 既有 SemanticSearchTest / SemanticSearchPocTest / SemanticSearchIntegrationTest / SearchProjectionTest 改用 `PgVectorStore` mock；T7.7 application-local.yaml 移除 firestore.enabled=false 殘留（如 T5 沒清乾淨）
   - Target Files：`build.gradle.kts`（M）、`SearchConfig.java`（M）、`SearchProjection.java`（M）、`FirestoreVectorStore.java`（D）、`SemanticSearch*Test.java`（M, ~4 檔）、`PgVectorStoreOwnerWriteTest.java`（A）
   - Verification：`./gradlew dependencies | grep firestore` = 0；`grep -rn FirestoreVectorStore src` = 0；`./gradlew test` 全綠；上傳一個 skill 後 `SELECT * FROM vector_store WHERE skill_id=?` 回傳 row 含 owner / skill_id 非 NULL
   - AC mapping：AC-10 + AC-13
   - Depends：T1, T2, T5

8. **更新 `docs/grimo/tasks/2026-04-27-S014-T6.md`**：
   - T6.1 grep 改為「`grep -rn '@Document\|MongoRepository\|FirestoreVectorStore\|google-cloud-firestore'` 應為 0」
   - T6.4 smoke test 加：上傳測試 skill 後 `SELECT COUNT(*) FROM vector_store > 0`、`SELECT owner, skill_id FROM vector_store WHERE skill_id=?` 回傳非 NULL
   - Depends 加 T7
   - 預期登記項目：移除「forward-looking S015 接管」一條（已併入 S014）

#### C. roadmap 同步

9. **更新 `docs/grimo/specs/spec-roadmap.md`**：
   - M12 row：S014 規模 M-L(15) → **L(20)**；status 從 ⏳ Plan → ⏳ In Progress
   - M13 row：S015 標 **🚫 ABSORBED into S014（2026-04-27）**；併入 S014 後 M13 整段可加備註「Done by S014」（或留空 placeholder 以保 milestone 編號連續）
   - 第 124-125 行的依賴圖：`S014 → S015 → S016` 改為 `S014 → S016`（直接跳）

#### D. 實際 code 變動（**這次不做，下一輪 session**）

10. 進 `/planning-tasks` task loop 跑 T5
11. 跑 T7
12. 跑 T6
13. 進 `/verifying-quality S014` re-verify

### Lessons Learned

- **「拆 deps 不分批」原則**：T2 mega ship 後驗證了「Mongo 漸進遷移會在最後 deps 移除時觸發雪崩編譯錯誤」（archive/2026-04-27-s014-t2-mega 紀錄）。同性質的 Firestore 拆解，user 直接決策一次拆 — spec 規模升一級換少一輪 PR review。
- **dev/prod parity 是強訊號**：Cloud SQL Auth Proxy sidecar 不只是「沒有額外 dep」，更關鍵是 `jdbc:postgresql://localhost:5432/...` JDBC URL 跨 local docker compose + GCP Cloud Run 同一條 — 連同 username / password env var 都用同一組 key（`SKILLSHUB_DB_*`），除錯心智模型直線統一。
- **Cloud Run multi-container 已 GA**（2024 中起）：`gcloud run services replace service.yaml` 可定義 sidecar；但 `gcloud run deploy` CLI flag 對 sidecar 支援受限，必須用 YAML manifest。S013 部署腳本下一輪要擴充。
- **官方 PgVectorStore INSERT 只動 4 欄**：驗證自原始碼（spec §2.4 #13），這意味著加 `owner` / `skill_id` 不破壞官方 ingest；兩步驟 add + UPDATE 是利用此特性的最直接做法。
- **`/verifying-quality` ≠ `/shipping-release`**：在 T5/T6 還沒做完前 `/verifying-quality` 會出 REJECT-FIX，那是預期行為（gate 起作用），不是 spec 設計錯誤。

### Session Summary

本 session 從 `/takeover`（讀 T2 mega 的舊 handover）+ `/verifying-quality S014`（跑出 119/119 PASS、寫入 §7 結果並 REJECT-FIX 因 T5/T6 未做）開始，本來預期是直接接 T5。但 user 在 review 時對 spec scope 做了兩個重要決策：(1) Firestore 一次拆乾淨（把原 S015 PgVectorStore 接管併入 S014）、(2) GCP 連線改 Cloud SQL Auth Proxy sidecar（取代原 Private IP + VPC Connector）。我提出 B+C 配置確認後，user 指示「先改文件」— 所以本 session 後半段都在更新 ADR-001 與 spec。完成 ADR-001（4 處修訂）+ spec 大半（標題、§1、§2.1 / §2.2 / §2.4、§3 ACs、§4.1 / §4.8 / §4.9）。**剩下的文件工作**：spec §4.12（SearchConfig 改寫）、§4.13（SearchProjection 兩步驟）、§5 File Plan、§6 Task Plan 表（加 T7 row）、§7 task status 更新；以及三個 task file（T5 重寫 / T7 新增 / T6 更新）+ spec-roadmap.md（S015 absorbed 標記）。**沒有任何 code 變動**，所有 source code 維持 T2 mega ship 後狀態（119/119 test PASS）。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | 119/119 PASS（最後一輪 `./gradlew test` 在本 session 開頭跑，2026-04-27 14:12；本 session 後半純文件變動，code 未動） |
| Java | OpenJDK 25.0.1 |
| Gradle | 9.4.1 |

### Uncommitted Changes

```
# Source code（T1+T2 mega ship 完成、本 session 未動）
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
M backend/src/test/resources/application.yaml

# 文件（本 session 變動 — 仍在更正中）
M docs/grimo/adr/ADR-001-postgresql-migration.md         ← 已完成本 session 的 4 處修訂
M docs/grimo/specs/2026-04-27-S014-postgresql-migration.md ← 大半完成；§4.12/§4.13/§5/§6 待補

# 新檔（git untracked，T1/T2 mega 產出 + handover archive）
?? .claude/handovers/archive/2026-04-27-s014-t2-mega-atomic-mongo-postgresql-migration.md
?? backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/    （JdbcConfiguration + package-info）
?? backend/src/main/java/io/github/samzhu/skillshub/skill/query/package-info.java
?? backend/src/main/resources/db/    （V1__initial_schema.sql）
?? backend/src/test/java/io/github/samzhu/skillshub/shared/events/DomainEventSequenceUniquenessTest.java
?? backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/    （MapJsonbConverterTest）
?? backend/src/test/java/io/github/samzhu/skillshub/skill/query/AtomicDownloadCountTest.java
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

**本 session 已修改（文件，無 code）**：
- `docs/grimo/adr/ADR-001-postgresql-migration.md` — 加 §4.4 sidecar / §4.5 absorb / 修 §5 migration plan / 修 §6.2 GCP integration（4 處 Edit）
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` — header / §1 Goal / §2.1 決策表 / §2.2 / §2.4 / §3 AC / §4.1 / §4.8 / §4.9 已修；**§4.12 / §4.13 / §5 / §6 task table 仍待補**

**本 session 必看以重建 context**：
- `docs/grimo/adr/ADR-001-postgresql-migration.md` §4.4 / §4.5 — sidecar Auth Proxy 與 S015 absorption 決策表（**權威來源**）
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` §2.1 決策 #2 / #9 / #10 / #12 — spec 端決策同步
- `.claude/handovers/archive/2026-04-27-s014-t2-mega-atomic-mongo-postgresql-migration.md` — T1+T2 mega 完成的脈絡

**下一段要修改的檔（pending）**：
- `docs/grimo/specs/2026-04-27-S014-postgresql-migration.md` — §4.12（SearchConfig）/ §4.13（SearchProjection）/ §5（File Plan 加 ~10 檔）/ §6（Task table 加 T7 row + 執行順序改 T1→T2→T5→T7→T6）/ §7 task table 更新
- `docs/grimo/tasks/2026-04-27-S014-T5.md` — 重寫（sidecar JDBC URL + 完整 vectorstore.pgvector）
- `docs/grimo/tasks/2026-04-27-S014-T7.md` — 新增（PgVectorStore takeover + Firestore deletion）
- `docs/grimo/tasks/2026-04-27-S014-T6.md` — 加 firestore residue 0 grep + vector_store row > 0 smoke
- `docs/grimo/specs/spec-roadmap.md` — M12 升 L(20)、M13 標 ABSORBED、依賴圖修

**本 session 不變的檔**（後續 T5/T7/T6 階段才動）：
- `backend/build.gradle.kts`（T7 移除 `google-cloud-firestore`）
- `backend/src/main/java/.../search/SearchConfig.java`（T7 改 bean 配置）
- `backend/src/main/java/.../search/SearchProjection.java`（T7 兩步驟寫入）
- `backend/src/main/java/.../search/FirestoreVectorStore.java`（T7 刪除）
- `backend/src/main/resources/application.yaml`（T5 移除 autoconfigure.exclude + 加 vectorstore.pgvector 完整設定）
- `backend/src/main/resources/application-local.yaml`（T5 移除 firestore.enabled=false）
- `backend/src/main/resources/application-gcp.yaml`（T5 完全重寫為 sidecar）

### Resume 流程

```bash
cd /Users/samzhu/workspace/github-samzhu/skills-hub

# 1. 讀本 handover + ADR/spec 已修部分
cat docs/grimo/adr/ADR-001-postgresql-migration.md  | head -100  # 看 §4.4 / §4.5
grep -n "^### 4\." docs/grimo/specs/2026-04-27-S014-postgresql-migration.md  # 確認進度

# 2. 進「Next Steps」第 1 步：補 spec §4.12 SearchConfig 段落
#    （在 §4.11 TestcontainersConfiguration 之後）

# 3. 完成所有 9 個 next steps 後：
cd backend && ./gradlew test    # 仍應 119/119 PASS（純文件未動 code）

# 4. 進 /planning-tasks 跑 T5（user-trigger）
```

文件全寫完 + roadmap 同步 → 才進 `/planning-tasks` 開始 T5 實作（第 10 步起）。
