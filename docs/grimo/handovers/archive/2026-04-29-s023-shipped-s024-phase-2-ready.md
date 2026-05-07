---
topic: "S023 Modulith Outbox shipped (v1.5.0); S024 Phase 0+1 done, ready for Phase 2 task creation"
session_type: "development"
status: "completed"
date: "2026-04-29"
---

# Handover: S023 Modulith Outbox shipped (v1.5.0); S024 ready for Phase 2

## Layer 1 — Portable Summary

> 任何 agent / 人都讀得懂。本 session 從前 handover「S023 Phase 4 blocked on full test suite OOM」起，推到 S023 ship + S024 Phase 0/1 完成。

### Completed

#### S023 Ship 全流程
- **OOM blocker 解**：`build.gradle.kts` 加 `tasks.test { maxHeapSize = "3g" }` + `-Dspring.test.context.cache.maxSize=8`。診斷 root cause = 53 個 distinct `@SpringBootTest` cache key（default LRU=32 不夠）導致 evict + 重建 + container churn。研究排除 native/AOT 因素（S019 已驗證 GraalVM 與 JaCoCo task graph 零交集）。
- **Singleton container hack 嘗試後撤回**：static field + `destroyMethod=""` 讓全 JVM 共用 1 個 pgvector container。實測有效（1 container 而非 8+）。但讀官方 docs 後發現偏離 Spring Boot Testcontainers 推薦的 lifecycle 設計，撤回到 official `@TestConfiguration + @Bean @ServiceConnection` 寫法。Container churn 留為 known limitation pointer 到 S025。
- **Phase 4 Step 1.5 E2E actuator 驗證**：`./gradlew bootRun -x processAot` + curl 三 endpoint：
  - `/actuator/modulith` → 6 modules + EVENT_LISTENER edges 可見
  - `/actuator/metrics/event_publication.failed.count` → 0
  - `/actuator/metrics/event_publication.incomplete.count` → 0
- **Phase 4 Step 2 spec §7 寫好**：12 AC results table + verification summary + 3 production bug fixes + 4 design refinements + build infra tuning + hybrid migration final state + 7 open risks + files changed summary。
- **Phase 4 Step 3 task files 已刪**：`docs/grimo/tasks/2026-04-29-S023-T0*.md` 7 個檔案 rm。
- **Phase 4 Step 4 QA**：verify-all.sh 跑出 V01/V03 連續 fail（`SearchProjectionAclWriteTest.AC-1` 第一次跑、`SkillSuspendControllerSecurityTest.AC-12` 第二次跑）— 兩個都是 async listener timing flake。修：
  - 把 13 處 Awaitility timeout 從 10s 升 30s（5 個 file）
  - 補 `SkillSuspendControllerSecurityTest` 兩處漏網 await 包裝（line 91 suspend + line 132 reactivate）
- **verify-all.sh 連續 3 次 PASS** — V01/V03/V04/V05/V06 全綠 stability 確認。
- **S023 ship**：commit `ee5cbdd`（63 files / +6040 / -178）+ tag `v1.5.0`。
- **Doc sync 完成**：
  - `PRD.md` Phase 3a v1.5.0 上線狀態
  - `architecture.md` Spring Modulith Outbox 段落（T06 已寫；保留）
  - `development-standards.md` 新增「Spring Modulith Outbox 規範」段（10 條 validated patterns）
  - `CHANGELOG.md` v1.5.0 entry
  - `spec-roadmap.md` M18 ✅ + S025 entry 升級
  - Spec archived → `specs/archive/2026-04-29-S023-modulith-outbox-foundation.md`

#### Scenario API pilot（試水 + 撤回 + 開 S025）
- **Pilot**：把 `SearchProjectionAclWriteTest` 改 `@ApplicationModuleTest(BootstrapMode.ALL_DEPENDENCIES) + Scenario`，用 `scenario.stimulate(() -> publisher.publishEvent(...)).andWaitForStateChange(supplier).andVerify(...)`。
- **驗證行為**：listener 確實 fire 並執行 `SearchProjection.onSkillCreated`（log 證實），但 SQL `INSERT INTO vector_store` 撞 FK constraint `vector_store_skill_id_fkey`（key skill_id not present in skills）。
- **根因**：原 `@SpringBootTest + Awaitility` 跑得起來是因為**所有 listener 都載**（含 sync `SkillProjection.onSkillCreated` 寫 skills row）。`@ApplicationModuleTest` 為 `search` test 只載 `search` module + `skill::domain` NamedInterface（events 而已），不載 `skill::query` 的 `SkillProjection` → 沒人寫 skills row → vector_store FK 違反。
- **教訓**：Scenario migration **不是純語法替換**，需要顯式 seed 跨 module FK target；module-isolated test 要重新設計 fixture。
- **撤回 + S025**：把 pilot 還原到 `@SpringBootTest + Awaitility 30s`；`spec-roadmap.md` S025 entry 升級含 4 範圍（cache key 收斂 / Scenario migration with FK seed / slice 重組 / workaround 移除）。

#### S024 Phase 0 + Phase 1 (POC 決定)
- **Phase 0 PASS**：spec §2.10 Validation Pass 已記（S016 JsonbConverter ✅ / S018 SkillStatus enum ✅ / S023 outbox ✅ / V1+V2 schema align — 不需建表，只需 V6 ALTER ADD COLUMN version BIGINT）。
- **§2.9 Confidence**：5 Validated / 1 Hypothesis（**POC: required** — 跨 aggregate 同 TX 的 `@DomainEvents` publish 行為）。
- **POC 決定（Phase 1）**：spec author 原 POC plan 寫 `SkillCommandServiceCrossAggregateTest`，但這個 test 需要 Skill aggregate **已轉 `@Table + AbstractAggregateRoot`** 才能跑。獨立 POC dir 會 duplicate T1 implementation work。**決定：把 POC 折進 T1 first RED test**（T1 first test = POC 驗證；若 RED→GREEN 失敗則 escalate `/planning-spec` 改設計）。

### Decisions

| Decision | Why | Alternatives Rejected |
|---|---|---|
| OOM 解：`maxHeapSize=3g + cache.maxSize=8`（不是 native / forkEvery / maxParallelForks）| 53 distinct cache key 是真因；JaCoCo + native 早就無交集；forkEvery 失 cache；parallelForks×SpringBootTest = OOM 加劇 | Singleton container hack（撤回 — 違反 official lifecycle）；`@DirtiesContext`（cost 太高 + race） |
| Awaitility 30s（而非 5s 或改 Scenario）| 53 cache key 熱機/ container churn 下 10s 不夠；30s 0 影響冷機 case；正式解需 S025 重整 cache key | Scenario migration（pilot 揭露 FK seed 問題；scope creep 至 S023）|
| Scenario migration 延後到 S025 | S023 內做違反 spec scope（無 AC、無 design phase）；FK seed 跨 module 設計需 design discussion；測試最佳實踐 = 「不在錯時間做對事」 | S023 hot-fix 全改（scope creep 5-6 hr，無 traceability） |
| `destroyMethod=""` 撤回，留 official `@Bean @ServiceConnection` | Spring Boot Testcontainers docs 推薦 lifecycle 綁 context；`destroyMethod=""` 雖是 Spring Framework 標準但不是 testcontainer 官方 pattern；container churn 為 transient 問題（peak 1 個 + ryuk 收尾）| Singleton + destroyMethod="" hack（驗證有效，但偏離 docs） |
| 全部測試應該共用 1 cache key — 但 S025 spec 流程做 | 53 個 test 各有 design decision（FK seed 範圍、cross-module 邊界）；S025 已在 roadmap，正是它的 scope；S023 已可 ship 不卡 | S023 內塞全 refactor（5-6 hr，無 design phase，QA 重開）|
| S024 POC 折進 T1 RED test | spec POC plan 需要 Skill aggregate 已轉 @Table 才能跑；獨立 POC dir 等於 duplicate T1；TDD RED→GREEN 自然驗 hypothesis | 獨立 `poc/S024/` dir（重複 setup）；Phase 4 才驗（hypothesis 失敗 = 整批 task 廢料）|

### Next Steps

**S024 Phase 2 Task Creation**（next session）— spec §1-5 + §2.9-2.10 + Phase 1 POC 決策已完成，下一步建 6 個 task file：

1. **T1 — Infrastructure + Skill aggregate skeleton + cross-aggregate POC RED test**
   - V6 Flyway migration: `ALTER TABLE skills ADD COLUMN version BIGINT NOT NULL DEFAULT 0`
   - `Skill extends AbstractAggregateRoot<Skill>` + `@Table("skills")` + `@Id` + `@Version Long version` + `@Column("acl_entries") List<String> aclEntries` (reuse S016 `StringListJsonbConverter`)
   - Minimal factory `Skill.create(...)` + `publishVersion(...)` 方法（含 `registerEvent(SkillVersionPublishedEvent.of(...))`）
   - `SkillRepository extends CrudRepository<Skill, String>`
   - **First test (RED→GREEN POC)**：`SkillCommandServiceCrossAggregateTest` — call publishVersion → verify event_publication has 3 listeners 的 row（SearchProjection / ScanOrchestrator / AuditEventListener — AuditEventListener 在 T5 才實作，先用 mock listener 占位）+ no duplicate
   - ACs: AC-1, AC-2 (partial), AC-3 (partial), AC-13 (partial)

2. **T2 — Skill 完整充血方法 + state machine 守護**
   - `Skill.suspend(SuspendCommand)` / `reactivate(ReactivateCommand)` / `grantAcl(GrantAclCommand)` / `revokeAcl(RevokeAclCommand)` / `recordDownload()`
   - `Skill.publishVersion()` SUSPENDED 拋例外（state machine guard）
   - `VersionExistsException`（重複 version 拋）
   - ACs: AC-2 (full), AC-6, AC-7, AC-8

3. **T3 — SkillVersion 獨立 aggregate + repository**
   - `SkillVersion extends AbstractAggregateRoot<SkillVersion>` + `@Table("skill_versions")`
   - `SkillVersionRepository extends CrudRepository` + `existsBySkillIdAndVersion(...)` derived query + `updateRiskAssessment(...)` `@Modifying @Query`
   - `Skill.publishVersion` 配合：UPDATE skills + INSERT skill_versions 同 TX
   - ACs: AC-5, AC-7

4. **T4 — SkillCommandService 縮減**
   - 每 method 3 行 orchestration（`load → mutate → save`）
   - 移除舊 `saveAndPublish` path（domain_events 寫入交給 T5 AuditEventListener）
   - ACs: AC-4

5. **T5 — AuditEventListener + delete SkillProjection**
   - 新 `AuditEventListener` `@ApplicationModuleListener` consume all skill events → INSERT `domain_events`
   - 刪 `SkillProjection.java`（整個檔；7 listener 全部由 aggregate 自己 mutate state）
   - 刪 `SkillReadModel.java` + `SkillReadModelRepository.java`
   - 刪 `SkillVersionReadModel.java` + `SkillVersionReadModelRepository.java`（被 SkillVersion aggregate 取代）
   - ACs: AC-9, AC-10

6. **T6 — Cleanup + integration verify**
   - 改 `ScanOrchestrator` 用 `SkillVersionRepository.updateRiskAssessment`（替代 `SkillVersionReadModelRepository.updateRiskAssessment`）
   - GET `/api/v1/skills/{id}` API contract 驗（response JSON shape 不變）
   - `architecture.md` / `CLAUDE.md` 更新（Core Domain ES → state-based）
   - ES backlog ES-B1~B4 標 obsolete（roadmap.md 已部分做，但要在 S024 ship 後正式收掉）
   - `ApplicationModules.verify()` 加 `@Tag("AC-13")`
   - ACs: AC-11, AC-12, AC-13

**Phase 3 Task Loop**：T1 → T2 → T3 → T4 → T5 → T6（嚴格序列；每個 task 30-60 min RED→GREEN→REFACTOR）。

**Phase 4 Verify + QA subagent**：跑 verify-all 3x stability + spawn QA subagent。

**Ship as `v2.0.0`**（major bump per ADR-002 §5.1）— 不只 minor bump 因為 architecture 從 ES 轉 state-based。

### Lessons Learned

- **Spring Boot test context cache**：默認 LRU=32；cache key buster = `@MockitoBean`/`@TestPropertySource`/`webEnvironment`/`@AutoConfigure*`；Skills Hub 53 個 cache key（>32）導致 evict + 重建。
- **`destroyMethod=""` 是 Spring Framework 標準**（externally-managed bean）但不是 Spring Boot Testcontainers 官方 pattern；docs 假設 lifecycle 綁 context；用了會偏離（雖然有效）。
- **Spring Modulith `Scenario` API 只在 `@ApplicationModuleTest` 註冊**（JUnit 5 ParameterResolver via meta-annotation）；`@SpringBootTest + Scenario` = `ParameterResolutionException`。
- **`scenario.publish(Supplier)` vs `scenario.stimulate(Runnable + publishEvent)`**：第二個明顯包 TX，第一個語義不夠清楚 — 用 stimulate 較保險。
- **`@ApplicationModuleTest` BootstrapMode**：STANDALONE / DIRECT_DEPENDENCIES / ALL_DEPENDENCIES — 即使 ALL_DEPENDENCIES 也不會 load 跨 module 的非 NamedInterface 內部（如 search test 不會 load skill::query 的 SkillProjection）。
- **Cross-module FK violation in module-isolated test**：原 `@SpringBootTest + Awaitility` 隱式靠別 module 的 sync listener 副作用；module test 必須**顯式 seed FK target**，這是測試最佳實踐（test as documentation）。
- **Awaitility timeout 不是 fixed sleep**：`atMost` 是 upper bound，conditions 達成立即 return；冷機/熱機差別大；30s upper bound 0 影響冷機 happy path。
- **`SyncTaskExecutor` test profile**：簡單暴力解 async timing — 把 `applicationTaskExecutor` 在 test profile 換成 `SyncTaskExecutor`。Trade-off：失 concurrency fidelity（race bug 測不到）。
- **`PublishedEvents` / `@RecordApplicationEvents`**：sync snapshots（point-in-time），不能等 async listener 完成；只能配合 `Scenario.andWaitForStateChange` 或 Awaitility。
- **`IncompleteEventPublications.findAll()` 不存在**：只有 `resubmit*` API；不是 query API；不能用來 poll 測試完成度。
- **JaCoCo + GraalVM native 零交集**：S019 已驗證 `processAot` / `nativeCompile` / `nativeTest` 不在 `clean test jacocoTestReport` task graph；`-x processAot` 只對 `bootRun` 有用（既有 tech debt）。
- **53/77 tests 用 @SpringBootTest = 測試金字塔倒置**：應 ~50% 純單元 / ~30% slice / ~15% full integration。Skills Hub 0% slice / 0% @ApplicationModuleTest。S025 系統解。
- **Test as documentation**：Awaitility 30s 是「猜時間」隱式假設；顯式 seed + Scenario.stimulate 是「Given X，When Y，Then Z」清楚故事。最佳實踐 + 重構抗性 + 失敗模式都贏。
- **「最佳實踐」也包括「不在錯時間做對事」**：測試重構需要 design phase + AC 對照 + QA 階段；S023 hot-fix 塞進去違反 spec scope；S025 spec 流程才是正確路徑。

### Session Summary

從 takeover 「S023 Phase 4 blocked on OOM」開始：先研究 OOM root cause（不是 native，是 53 cache key 爆炸）→ build.gradle.kts patch（`maxHeapSize=3g + cache.maxSize=8`）解了。然後遭遇「container 一直開」現象，試 singleton hack 又撤回（讀官方 docs）。Phase 4 跑 verify-all 撞 flaky test 兩次，發現是 Awaitility 10s 在熱機下不夠；修了 13 處 + 補漏 1 處 + 撤 Scenario pilot（pilot 揭露 cross-module FK seed 設計問題 — 真正解需要 S025）。3 連綠後 ship S023 v1.5.0（commit ee5cbdd / 63 files / +6040 / -178 / tag v1.5.0），doc 全 sync。最後做 S024 Phase 0 + 1（POC 決策：折進 T1 RED test）。Session 累積很多測試最佳實踐洞察（Spring Modulith Scenario API、`@ApplicationModuleTest` BootstrapMode 限制、cross-module FK violation 教訓、`SyncTaskExecutor` 暴力解選項等）— 全寫進 development-standards.md「Spring Modulith Outbox 規範」段。下一步是 S024 Phase 2 task creation（6 task file 已設計好，待 fresh session 撰寫）+ task loop ping-pong → ship `v2.0.0`。

---

## Layer 2 — Environment Details

> 同 repo / 同 machine 才用。

| Property | Value |
|---|---|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub`（git）/ `/Users/samzhu/workspace/github-samzhu/skills-hub/backend`（gradle）|
| Test Status | verify-all.sh **連續 3 次 PASS**（V01-V06 全綠）；262 tests / 0 failed / 5 skipped；JaCoCo line coverage 89.53% |
| Java | 25.0.1 LTS (sdkman: `25.0.1-librca`) |
| Docker | OrbStack v29.4.0 — running |
| Spec Status | S023 ✅ Shipped (`v1.5.0`, M18, commit `ee5cbdd`)；S024 Phase 0+1 done, Phase 2 pending |
| Active git tag | `v1.5.0` |

### Uncommitted Changes

```
?? .claude/handovers/archive/2026-04-29-s023-modulith-outbox-phase-4-blocked.md
```

僅前 handover 的 archive 副本（無 production 影響；可忽略或下次 commit 順手帶上）。

### Recent Commits

```
ee5cbdd feat(skill): ship S023 — Spring Modulith Outbox Foundation (M18 完成 v1.5.0；ADR-002 Phase 1)
40e0de6 feat(skill): ship S018 — Aggregate 充血演化 + SKILL.md alignment (M16 完成 v1.4.0；Phase 2 全部完成)
b9f81a4 feat(search): ship S017 — ACL-Aware 語意搜尋 (M15 完成 v1.3.0)
a2b2653 feat(security): ship S016 — Row-Level ACL Foundation (M14 完成 v1.2.0)
1f62353 chore: update .gitignore (scheduled_tasks.lock + .vscode/) + loop default interval
```

### Key Files

**Spec / 規格**：
- `docs/grimo/specs/2026-04-29-S024-skill-state-based-aggregate.md` — S024 spec；§1-5 完成 + §2.9 Confidence + §2.10 Validation Pass 完成；**§6 Task Plan 待寫（Phase 2）**；§7 待 Phase 4 補
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md` — 架構決策（Accepted）；§5 Implementation Plan: phase 1=S023 ✅, phase 2=S024
- `docs/grimo/specs/archive/2026-04-29-S023-modulith-outbox-foundation.md` — S023 shipped spec，§7 完整 Implementation Results

**Production code（S023 shipped；S024 將大改）**：
- `backend/src/main/java/.../skill/domain/Skill.java` — **目前是純 ES POJO**；S024 T1+T2 改 `@Table + AbstractAggregateRoot + @Version` 充血聚合
- `backend/src/main/java/.../skill/query/SkillProjection.java` — **目前 hybrid migration**（5 async + 2 sync）；S024 T5 **整檔刪除**（aggregate 自己 mutate state）
- `backend/src/main/java/.../skill/query/SkillReadModel.java` + `SkillReadModelRepository.java` — S024 T5 **刪除**（aggregate 即 read model）
- `backend/src/main/java/.../skill/query/SkillVersionReadModel.java` + `SkillVersionReadModelRepository.java` — S024 T3+T5 **刪除**，由 `SkillVersion` aggregate + `SkillVersionRepository` 取代
- `backend/src/main/java/.../skill/command/SkillCommandService.java` — S024 T4 縮減為 3 行 orchestration；移除 `saveAndPublish` path
- `backend/src/main/java/.../security/scan/ScanOrchestrator.java` — S024 T6 改注入 `SkillVersionRepository`（替代 `SkillVersionReadModelRepository`）

**Production code（S023 ship 後保留無變動）**：
- `backend/src/main/java/.../shared/config/AsyncListenerConfig.java` — `applicationTaskExecutor` 配置（保留）
- `backend/src/main/java/.../shared/config/SchedulerConfig.java` — ShedLock + @EnableScheduling（保留）
- `backend/src/main/java/.../shared/events/IncompleteEventRepublishTask.java` — Modulith retry（保留）
- `backend/src/main/java/.../shared/events/EventPublicationMetrics.java` — Micrometer gauges（保留）
- `backend/src/main/resources/db/migration/V4__event_publication_outbox.sql` + `V5__shedlock.sql`（保留；S024 T1 加 `V6__skills_version_optimistic_lock.sql`）

**Test 重點**：
- `backend/src/test/java/.../shared/events/TestEventTxHelper.java` — shared `@Component` helper publish event in `@Transactional`（S024 多 test 沿用；但長遠 S025 改 Scenario 後可能廢除）
- 13 處 Awaitility wraps timeout=30s（5 個 file）— S023 transitional state；S025 改 Scenario 後可收回 5s 並改 module-isolated FK seed pattern
- 4 個 method `@Disabled` with rationale（`S016EndToEndSmokeTest` × 1 + `RiskAssessmentIntegrationTest` × 3）— S025 改寫為 `@ApplicationModuleTest + Scenario`

**Build / config**：
- `backend/build.gradle.kts` — `tasks.test { maxHeapSize = "3g"; jvmArgs("-Dspring.test.context.cache.maxSize=8") }`（S025 ship 後可移除）
- `backend/src/test/java/.../TestcontainersConfiguration.java` — official `@TestConfiguration + @Bean @ServiceConnection` 寫法 + design intent comment 標 known limitation（S025 重整 cache key 後 churn 自然降）

**Documentation**：
- `docs/grimo/development-standards.md` — 新「Spring Modulith Outbox 規範」段（10 條 validated patterns）— S024 開發要遵守
- `docs/grimo/architecture.md` — 「Spring Modulith Outbox」段（T06 寫的）+ ADR-002 引用；S024 ship 後要把 Core Domain 段改寫為「state-based」
- `docs/grimo/PRD.md` Phase 3a v1.5.0 上線狀態（Phase 3b S024 待 ship）
- `docs/grimo/CHANGELOG.md` v1.5.0 entry
- `docs/grimo/specs/spec-roadmap.md` M18 ✅ + S024 Active + S025 Active

**研究 / Reference**：
- `docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md` — `AbstractAggregateRoot + @DomainEvents` source-level 細節 — S024 T1 必讀
- `docs/deepwiki/spring-data-jdbc-modulith/event-publication-registry.md` — outbox 機制
- `docs/deepwiki/spring-data-jdbc-modulith/design-decisions.md` §3 — 10 條生產陷阱（S023 多數已踩過；S024 注意 §3 陷阱 1-3 跨 aggregate publish ordering）

**Ship 證據**：
- Commit `ee5cbdd`（63 files / +6040 / -178）
- Tag `v1.5.0`
- Spec archived（`docs/grimo/specs/archive/2026-04-29-S023-modulith-outbox-foundation.md`）
