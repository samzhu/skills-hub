# S024: Skill State-Based Aggregate Migration

> Spec: S024 | Size: M(13) | Status: ⏳ Verify (待 QA subagent + `/shipping-release`)
> Date: 2026-04-29
> ADR: [ADR-002 — Skill aggregate state-based migration](../adr/ADR-002-skill-aggregate-state-based.md)
> Research: `docs/deepwiki/spring-data-jdbc-modulith/`
> Depends on: **S023 ship（v1.5.0）— code-level dependency**（必須先有可靠 outbox 才能 expose `repo.save()` → `@DomainEvents` 自動 publish 路徑）；S016 ✅ ACL JSONB；S018 ✅ SkillStatus enum；ADR-002 Accepted
> Blocks: 無

---

## 1. Goal

把 Skill 從**純 ES POJO**（每次 command load → replay events → return new event）改為 **Spring Data JDBC `@Table` 充血聚合**（state-based + `extends AbstractAggregateRoot<Skill>`）。S023 已建立可靠 outbox 投遞通道；S024 把 aggregate 放上去。

對應 ADR-002 §5 implementation phase 2 — 完成「Core Domain 從 ES 轉向 Spring Data JDBC 充血聚合」的架構轉向。Ship 為 `v2.0.0`（major bump，per ADR-002 §5.1）。

對外 REST API contract 不變。內部：
- `Skill.java` 變 `@Table("skills") + extends AbstractAggregateRoot`，業務 method 直接 mutate state + `registerEvent(...)`
- `SkillCommandService` 每命令方法縮為 3 行（`load → mutate → save`）
- `SkillReadModel.java` + `SkillReadModelRepository.java` **刪除**（Skill aggregate 自身即 read model）
- `SkillVersion` 升格為**獨立 aggregate**（避開 `@MappedCollection` delete-and-reinsert 雷）
- `AclEntry` **維持 `acl_entries jsonb` 欄位**（per ADR-002 §2.2；S016 設計保留）
- `domain_events` 表退化為 audit log，由新增的 `AuditEventListener` 寫入

---

## 2. Approach

### 2.1 對比表

| Approach | Chosen | Rationale |
|---|---|---|
| A: `Skill = @Table + extends AbstractAggregateRoot`；ACL 維持 JSONB；SkillVersion 拆獨立 aggregate；domain_events 退 audit log | **yes** | 對齊 ADR-002 決策；deepwiki 6 份 source-level 驗證可行；最小化 @MappedCollection 風險 |
| B: 全部用 `@MappedCollection` 維護 versions / acl 子集合 | no | 已在 ADR-002 §2.3 否決：高頻寫場景下 delete-and-reinsert 寫放大 100~1000 倍 |
| C: 直接注入 `ApplicationEventPublisher` 到 Skill entity 手動 publish | no | 已在 ADR-002 §2.3 否決：Spring Data JDBC entity 不是 Spring bean 無法 @Autowired；違反 POJO 原則 |
| D: 保留 `Skill.fromHistory(events)` factory 作為 emergency rollback path | **yes（隱藏實作）** | per ADR-002 §5.2；private static method，不在 public API 暴露；`@Deprecated` 標註 |

### 2.2 Aggregate 設計總覽

```
                             ┌───────────────────────┐
                             │  Skill (Aggregate)    │
                             │  @Table("skills")     │
                             │  extends AbstractAR   │
   load: skillRepo.findById  │                       │
  ◀───────────────────────── │  - id PK              │
                             │  - name UNIQUE        │
                             │  - status (enum)      │ ──── method: create / publishVersion(part) /
                             │  - latestVersion      │      suspend / reactivate / grantAcl /
                             │  - downloadCount      │      revokeAcl / recordDownload
                             │  - aclEntries (jsonb) │      → mutate state + registerEvent(...)
                             │  - @Version v         │
                             └───────┬───────────────┘
                                     │
                                     │  skillRepo.save(skill)
                                     │  → @DomainEvents publishes events
                                     │  → Modulith outbox INSERT (S023 路徑)
                                     │  → AFTER_COMMIT async listeners 觸發
                                     ▼
                             ┌───────────────────────┐
                             │ AuditEventListener    │ ── INSERT INTO domain_events (audit log)
                             │ SearchProjection      │ ── update vector_store (search index)
                             │ AnalyticsProjection   │ ── INSERT INTO download_events (analytics)
                             │ ScanOrchestrator      │ ── update SkillVersion.risk_assessment
                             └───────────────────────┘

                             ┌───────────────────────┐
                             │  SkillVersion         │  ◀── 獨立 aggregate
                             │  @Table("skill_       │
                             │     versions")        │      method: publish (static factory)
                             │  extends AbstractAR   │
                             │                       │      validation: skillId + version 不重複
                             │  - id PK              │      （由 SkillCommandService orchestrate
                             │  - skillId FK         │       + SkillVersionRepository.existsBy 檢查）
                             │  - version            │
                             │  - storagePath        │
                             │  - fileSize           │
                             │  - frontmatter (jsonb)│
                             │  - riskAssessment     │ ── 由 ScanOrchestrator AFTER_COMMIT
                             │    (jsonb, nullable)  │     async update（既有 S010 行為）
                             │  - publishedAt        │
                             │  - allowedTools       │
                             └───────────────────────┘
```

### 2.3 為什麼 SkillVersion 拆獨立 aggregate 而非 `@MappedCollection`

詳 ADR-002 §2.3 + deepwiki `aggregate-design.md §2`：

- `@MappedCollection` 在 `WritingContext.update()` 強制 delete-and-reinsert 全部子集合（source line 78-83）
- 一個 skill 累積 50 版本後，**每次** `repo.save(skill)` 都 DELETE 50 + INSERT 50 = 100 SQL
- 即使新增單一版本：DELETE 50 + INSERT 51 = 101 SQL（寫放大 100 倍）
- SkillVersion 的 frontmatter / riskAssessment 是大型 JSONB（含 SARIF），重寫成本更高

**獨立 aggregate** 解這個問題：每個 SkillVersion 自己 INSERT 一行，append-only，O(1)。Skill 與 SkillVersion 透過 `String skillId` 引用（plain 外鍵欄位，**不**用 `@MappedCollection` 也**不**用 `AggregateReference`，避免框架介入子集合 lifecycle）。

跨 aggregate 一致性（同一 skill 不能有重複 version）由 application service `SkillCommandService.publishVersion` 在同一 `@Transactional` boundary 內檢查 + 寫入；schema 層用 `UNIQUE (skill_id, version)` 既有 V1 constraint 兜底。

### 2.4 為什麼 ACL 維持 `acl_entries jsonb` 欄位

詳 ADR-002 §2.3：ACL grant/revoke 高頻（用戶權限變動是常態），用獨立 `@Table aclEntries` 拆 aggregate 的代價是每次都 INSERT/DELETE 子表 row + 觸發 outbox + listener 響應。S016 既有的 `acl_entries jsonb` + `WHERE acl_entries @> ...` 的 GIN 索引設計是高頻 ACL 場景的最佳解。

Skill aggregate 內部 `aclEntries: List<String>` 欄位，透過既有 `StringListJsonbConverter`（S016 ship）write/read JSONB；`Skill.grantAcl(cmd)` 直接在 list `add(...)`，`repo.save(skill)` 整行 UPDATE — 一個 `UPDATE skills SET acl_entries = ?, ... WHERE id=? AND version=?` 即完成。

### 2.5 SkillProjection 大幅縮減

S023 的 7 個 SkillProjection handlers 在 S024 多數**廢除**（Skill aggregate 自己 INSERT/UPDATE skills 表）：

| Handler | S023 狀態 | S024 狀態 | 為何廢除 |
|---|---|---|---|
| `on(SkillCreatedEvent)` | @EventListener（保留 from S023）| **刪除** | `Skill.create()` factory 內 `new Skill(...)` + `repo.save(skill)` 自己 INSERT skills row |
| `on(SkillVersionPublishedEvent)` | @EventListener（保留 from S023）| **刪除** | `SkillCommandService.publishVersion` 內 `skillRepo.save(skill)` 自己 UPDATE skills.latest_version + status；`skillVersionRepo.save(skillVersion)` 自己 INSERT skill_versions row |
| `on(SkillDownloadedEvent)` | @ApplicationModuleListener（S023 加冪等）| **刪除** | `Skill.recordDownload()` 自己 increment downloadCount + UPDATE skills row |
| `on(SkillAclGrantedEvent)` | @ApplicationModuleListener（S023 已是冪等 SQL）| **刪除** | `Skill.grantAcl()` 自己 add to aclEntries list + UPDATE skills row |
| `on(SkillAclRevokedEvent)` | @ApplicationModuleListener（S023 冪等）| **刪除** | `Skill.revokeAcl()` 自己 remove + UPDATE |
| `on(SkillSuspendedEvent)` | @ApplicationModuleListener（S023）| **刪除** | `Skill.suspend()` 自己 transition status + UPDATE |
| `on(SkillReactivatedEvent)` | @ApplicationModuleListener（S023）| **刪除** | `Skill.reactivate()` 自己 transition status + UPDATE |

`SkillProjection.java` **整個檔案刪除**。`SkillReadModel.java` + `SkillReadModelRepository.java` 同。這是 S024 最大宗的 code 縮減。

`SkillVersionReadModel.java` + `SkillVersionReadModelRepository.java` 同樣**刪除**；改為 `SkillVersion.java` aggregate + `SkillVersionRepository.java`。`@Modifying @Query updateRiskAssessment` 由 ScanOrchestrator 直接呼叫新的 `SkillVersionRepository.updateRiskAssessment` 維持原語義。

### 2.6 AuditEventListener — domain_events 寫入收口

`domain_events` 表從 source of truth 退化為 audit log；不再由 `SkillCommandService.saveAndPublish` 主動寫入（該 method 整個刪除）。新增單一 listener：

```java
@Component
class AuditEventListener {

    private final DomainEventRepository auditRepo;
    private final ObjectMapper mapper;

    @ApplicationModuleListener
    void on(SkillCreatedEvent event)             { audit("SkillCreated", event); }
    @ApplicationModuleListener
    void on(SkillVersionPublishedEvent event)    { audit("SkillVersionPublished", event); }
    @ApplicationModuleListener
    void on(SkillDownloadedEvent event)          { audit("SkillDownloaded", event); }
    @ApplicationModuleListener
    void on(SkillAclGrantedEvent event)          { audit("SkillAclGranted", event); }
    @ApplicationModuleListener
    void on(SkillAclRevokedEvent event)          { audit("SkillAclRevoked", event); }
    @ApplicationModuleListener
    void on(SkillSuspendedEvent event)           { audit("SkillSuspended", event); }
    @ApplicationModuleListener
    void on(SkillReactivatedEvent event)         { audit("SkillReactivated", event); }
    @ApplicationModuleListener
    void on(SkillRiskAssessedEvent event)        { audit("SkillRiskAssessed", event); }
    @ApplicationModuleListener
    void on(SkillFlaggedEvent event)             { audit("SkillFlagged", event); }

    private void audit(String type, Object event) {
        // 反射或 record accessor 取出 aggregateId + sequence + payload
        // 為了避免事件 payload > 8191 bytes 撞 hash index（S023 / deepwiki §3 陷阱 10），
        // 此 listener 只序列化關鍵欄位至 domain_events.payload，不存原 event 完整 binary
        var domainEvent = DomainEventFactory.fromTyped(type, event);
        auditRepo.save(domainEvent);
    }
}
```

特性：
- `@ApplicationModuleListener` async — audit 失敗不阻塞業務 TX
- 失敗時 event_publication.status='FAILED' 可重投（既有 S023 retry 路徑）
- domain_events.sequence 由 listener 內維護（per-aggregate counter，從 max(sequence)+1 取）
- audit listener 失敗不影響業務正確性（業務狀態已存於 skills 表）— 唯一影響是 audit trail 缺漏，可從 event_publication.serialized_event 反查補回

### 2.7 SkillCommandService 縮減（每方法 3 行）

對比 S023 ship 後的 `SkillCommandService.publishVersion`（約 15 行）：

```java
// 變更前
public void publishVersion(PublishVersionCommand cmd) {
    var skill = loadAggregate(cmd.skillId());  // O(events) replay
    var versionEvent = skill.publishVersion(...);  // 純函數，不改 state
    saveAndPublish(cmd.skillId(), "SkillVersionPublished",
            Map.of("version", cmd.version(), ...),
            skill.nextSequence(), versionEvent);
}
```

變更後：

```java
@Transactional
public void publishVersion(PublishVersionCommand cmd) {
    if (skillVersionRepo.existsBySkillIdAndVersion(cmd.skillId(), cmd.version())) {
        throw new VersionExistsException(cmd.version());
    }
    var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
    skill.recordVersionPublished(cmd.version());          // 改 latest + status
    skillRepo.save(skill);
    skillVersionRepo.save(SkillVersion.publish(cmd));     // 新版本獨立 aggregate
}
```

業務邏輯（不變量驗證、state transition）全部下放到 aggregate；service 只 orchestrate。

### 2.8 Research Citations

| 來源 | 對本 spec 的支撐 |
|---|---|
| [`docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md`](../../deepwiki/spring-data-jdbc-modulith/aggregate-design.md) | `AbstractAggregateRoot` source 驗證；`@MappedCollection` delete-and-reinsert 證據；isNew 判斷；自訂 converter 整合 |
| [`docs/grimo/specs/archive/2026-04-28-S016-row-level-acl-foundation.md`](archive/2026-04-28-S016-row-level-acl-foundation.md) | `acl_entries jsonb` + `StringListJsonbConverter` 既有實作 — Skill 充血聚合直接復用 |
| [`docs/grimo/specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md`](archive/2026-04-27-S018-skill-aggregate-rich-domain.md) §2.4 | 既有 `SkillStatus` enum state machine（DRAFT/PUBLISHED/SUSPENDED + transition methods）— S024 直接復用 |
| [Spring Data Commons `AbstractAggregateRoot.java`](https://github.com/spring-projects/spring-data-commons/blob/main/src/main/java/org/springframework/data/domain/AbstractAggregateRoot.java) | source-cited per ADR-002 §6.1 |
| [`docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`](../adr/ADR-002-skill-aggregate-state-based.md) | 架構決策依據；本 spec 是其 implementation phase 2 |

### 2.9 Confidence Classification

| 設計決策 | Confidence | 證據 / POC 計畫 |
|---|---|---|
| `Skill extends AbstractAggregateRoot<Skill>` 配合 `SkillRepository extends CrudRepository<Skill, String>` 觸發 @DomainEvents publish | **Validated** | source-cited per deepwiki + ADR-002 §6.1 |
| `acl_entries jsonb` + `StringListJsonbConverter` 與 Skill `@Column("acl_entries") private List<String> aclEntries` 整合 | **Validated** | S016 既有運作模式；Skill 充血聚合直接復用 |
| `SkillVersion` 獨立 aggregate + `SkillVersionRepository.existsBySkillIdAndVersion` 為 version 唯一性檢查 | **Validated** | Spring Data JDBC derived query 標準模式；DB 層 `UNIQUE (skill_id, version)` 兜底 |
| `Skill.publishVersion(...)` 在同 TX 內 update Skill + insert SkillVersion 兩個 aggregate | **Hypothesis** — 需 POC | Spring Data JDBC 跨 aggregate 同 TX 是支援的（`@Transactional` 主控），但驗證每個 aggregate 各自的 `@DomainEvents` 是否都被 publish；POC 寫一個 ApplicationModuleTest 驗證 publishVersion 後 event_publication 含預期 events |
| `Skill.fromHistory(events)` deprecated factory 保留（rollback path） | **Validated** | 既有 v1.4.0 邏輯複製進 deprecated method；testing 不必須 |
| AuditEventListener async 失敗時 audit trail 缺漏可從 event_publication.serialized_event 補回 | **Validated** | event_publication 含完整 event payload；S023 已驗 outbox 寫入正確性 |

**POC: required**（1 項）— 跨 aggregate 同 TX 的 `@DomainEvents` publish 行為。POC scope：
- 寫 `SkillCommandServiceCrossAggregateTest`：呼叫 `publishVersion`
- 驗證 commit 後 event_publication 含：
  - 1 筆 `SkillVersionPublishedEvent` listener_id 為 SearchProjection
  - 1 筆 `SkillVersionPublishedEvent` listener_id 為 ScanOrchestrator
  - 1 筆 `SkillVersionPublishedEvent` listener_id 為 AuditEventListener
- 驗證沒有重複 event_publication（如 Skill.publishVersion 跟 SkillVersion.publish 各自 register 同 event class 應該避免）

POC 結果寫入 §6 Task Plan POC Findings。

### 2.10 Validation Pass — pre-handoff drift check

從現況 read 確認：
- ✅ S016 `StringListJsonbConverter` 在 `shared/persistence/JdbcConfiguration.java` 已註冊（line 60-64）— Skill 直接 `private List<String> aclEntries` 即綁定 JSONB
- ✅ S018 `SkillStatus` enum 已含 publish/suspend/reactivate transition methods — Skill 充血聚合直接呼叫
- ✅ V1 migration 已建 `skills` 表含 `id PK / name UNIQUE / status / latest_version / download_count / created_at / updated_at`；S016 V2 加 `acl_entries jsonb` — Skill aggregate 欄位設計與既有 schema 完全 align，**S024 不需要 V6 migration 建表**
- ✅ V1 migration 已建 `skill_versions` 表含 `id PK / skill_id FK / version / storage_path / file_size / frontmatter / risk_assessment / published_at`；S018 加 `allowed_tools` — SkillVersion aggregate 欄位完全 align，**不需要 V6 migration**
- ✅ S023 ship 後（v1.5.0）`event_publication` outbox 表已存在，`@ApplicationModuleListener` 機制已驗證
- ⚠ Skill aggregate 加 `@Version` 欄位需要 V6 migration `ALTER TABLE skills ADD COLUMN version BIGINT DEFAULT 0`
- ⚠ Skill aggregate 加 `@Column("created_by")` 補強 audit 欄位（optional） — 留為 backlog；S024 不引入

---

## 3. SBE Acceptance Criteria

> 驗收命令：`./gradlew clean test jacocoTestReport`（V01 from qa-strategy.md）— 所有 `@Tag("AC-N")` 測試綠燈 + JaCoCo 80% line coverage gate（V03）通過。

### AC-1: V6 Flyway migration 為 skills 表加 `version BIGINT` 欄位（@Version 樂觀鎖）

```gherkin
Given Skills Hub backend 從 v1.5.0 升級到 v2.0.0
When  Spring Boot 啟動 Flyway 自動執行 migration
Then  skills 表新增 version BIGINT NOT NULL DEFAULT 0 欄位
And   既有 row 預設 version=0
```

### AC-2: Skill 改為 @Table aggregate + 業務方法 mutate state

```gherkin
Given Skill 改寫完成（@Table("skills") + extends AbstractAggregateRoot）
When  呼叫 Skill.create(cmd) 建立新 aggregate
Then  Skill 物件 status 為 DRAFT、createdAt 為當下、aclEntries 為空 list
And   skill.domainEvents() 包含一個 SkillCreatedEvent
When  呼叫 skill.suspend(suspendCmd)
Then  Skill 物件 status 為 SUSPENDED
And   skill.domainEvents() 包含 SkillSuspendedEvent
```

### AC-3: skillRepository.save(skill) 觸發 @DomainEvents publish 至 Modulith outbox

```gherkin
Given S023 outbox 已就緒（v1.5.0）
And   Skill 物件 register 了 SkillCreatedEvent
When  skillRepository.save(skill)
Then  skills 表新增該 row（含 acl_entries '[]'::jsonb、version=0）
And   event_publication 表新增至少 1 筆 row（listener_id=AuditEventListener、SearchProjection 等所有訂閱 SkillCreatedEvent 的 listener）
And   event_publication INSERT 與 skills INSERT 在同一 transaction（業務 rollback 時 publication 也 rollback）
```

### AC-4: SkillCommandService 每命令方法縮為 3 行 orchestration

```gherkin
Given S024 ship 後
When  grep `void publishVersion` 在 SkillCommandService.java
Then  方法本體 ≤ 6 個 statement（uniqueness check + load + mutate + repo.save × 2，為 5 行）
And   無 `eventStore.save()` 呼叫
And   無 `events.publishEvent()` 呼叫
And   `saveAndPublish` private method 已刪除
And   `loadAggregate` private method 已刪除
```

### AC-5: SkillVersion 為獨立 aggregate；publishVersion 在同 TX 寫兩 aggregate

```gherkin
Given S024 ship 後 SkillVersion 為獨立 @Table("skill_versions") aggregate
When  SkillCommandService.publishVersion(cmd) 對 skill1 發 v1.0.0
Then  skills row 的 latest_version='1.0.0'、status='PUBLISHED'（從 DRAFT transition）、version+1
And   skill_versions 新增 row（skill_id=skill1, version='1.0.0'）
And   兩個 INSERT/UPDATE 在同一 transaction
And   event_publication 新增對應 SkillVersionPublishedEvent 的 listener entries
```

### AC-6: Skill.publishVersion 對 SUSPENDED skill 拋例外（state machine 守護）

```gherkin
Given skill1 status=SUSPENDED（已先 suspend）
When  呼叫 Skill.recordVersionPublished(version) 直接（單元測試）
Then  拋 IllegalStateException("Cannot publish version while skill is in SUSPENDED status")
And   skill 物件 state 不變（latestVersion 不變、status 仍 SUSPENDED）
And   skill.domainEvents() 為空（無事件 register）
```

### AC-7: SkillCommandService.publishVersion 對重複版本拋 VersionExistsException

```gherkin
Given skill1 已 publish v1.0.0
When  再次呼叫 SkillCommandService.publishVersion(cmd) with version='1.0.0'
Then  拋 VersionExistsException
And   skills row 不變（latest_version 仍 '1.0.0'、version 不變）
And   skill_versions 無新增 row
And   event_publication 無新增 row
```

### AC-8: ACL grant/revoke 透過 Skill 充血方法 + acl_entries jsonb 行內 UPDATE

```gherkin
Given Skill skill1 aclEntries=[]
When  skill.grantAcl(GrantAclCommand("user", "alice", "read"))
And   skillRepository.save(skill)
Then  skills.acl_entries = '["user:alice:read"]'::jsonb
And   只執行 1 條 UPDATE skills SET acl_entries=?, ... SQL（不 DELETE 子表）
And   event_publication 含 SkillAclGrantedEvent
When  再次 skill.grantAcl(同 cmd)
Then  拋 IllegalStateException("ACL entry already exists")
```

### AC-9: SkillReadModel.java + SkillReadModelRepository.java + SkillProjection.java 整組刪除

```gherkin
Given S024 ship 後
When  ls backend/src/main/java/io/github/samzhu/skillshub/skill/query/
Then  無 SkillReadModel.java
And   無 SkillReadModelRepository.java
And   無 SkillProjection.java
And   SkillVersionReadModel.java 改名為 SkillVersion.java（移到 domain/ 子模組）
And   SkillQueryService 改打 SkillRepository.findById() 取代 SkillReadModelRepository
And   SkillQueryController API contract 不變（GET /api/v1/skills 等回傳 JSON shape 一致）
```

### AC-10: AuditEventListener 寫入 domain_events（async；失敗不阻塞業務）

```gherkin
Given S024 ship 後 AuditEventListener 訂閱所有 9 種 domain event
When  SkillCommandService.createSkill 成功
And   業務 TX commit
And   AuditEventListener async 觸發
Then  domain_events 表新增 1 筆 row（aggregate_id=skill1, event_type='SkillCreated', payload jsonb 含必要欄位）
And   event_publication 對應 row status=COMPLETED
When  AuditEventListener mock 拋例外
And   IncompleteEventRepublishTask 觸發 retry
Then  audit row 最終仍寫入（at-least-once）
```

### AC-11: GET /api/v1/skills/{id} API contract 不變（回應 JSON shape）

```gherkin
Given S024 ship 後 SkillQueryController.getSkill 改打 SkillRepository
When  GET /api/v1/skills/{skill1.id}
Then  回 200 + JSON 含欄位：id / name / description / author / category / status / latestVersion / downloadCount / createdAt / updatedAt / aclEntries
And   JSON 結構與 v1.5.0 完全相同（前端不需修改）
And   無新增欄位（version 為 internal optimistic lock，不 expose）
```

### AC-12: ES backlog ES-B1~B4 標 obsolete；architecture.md / CLAUDE.md 同步更新

```gherkin
Given S024 ship 後
When  cat docs/grimo/specs/spec-roadmap.md
Then  ES-B1 / ES-B2 / ES-B3 / ES-B4 標記為「obsolete - superseded by ADR-002 (S024 ship v2.0.0)」
When  cat docs/grimo/architecture.md
Then  L22-345「Architecture Pattern: Event Sourcing + CQRS (Core Domain)」段落改寫為「Architecture Pattern: Spring Data JDBC Rich Aggregate + Modulith Outbox」
And   `domain_events` 段落從「Event Store」改為「Audit Log」
And   含 ADR-002 引用
When  cat CLAUDE.md
Then  「Core domain (skill, security): Event Sourcing + CQRS」改為「Core domain: Spring Data JDBC rich aggregate + Modulith outbox」
```

### AC-13: Spring Modulith ApplicationModules.verify() 通過

```gherkin
Given S024 PR 完成
When  ./gradlew test --tests "*ModularityTests*"
Then  通過（無模組邊界違規）
And   skill module 的 listener 全屬同 module（AuditEventListener 放 shared/events/audit/）
```

---

## 4. Interface / API Design

### 4.1 Skill aggregate

`backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：

```java
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> {

    @Id
    private String id;
    private String name;
    private String description;
    private String author;
    private String category;
    private SkillStatus status;
    private String latestVersion;          // nullable until first version published
    private long downloadCount;
    @Column("acl_entries")
    private List<String> aclEntries;        // converter from S016 JdbcConfiguration
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    private Skill() {}                       // for Spring Data JDBC

    // ═══ Factory: 建立新 skill ═══
    public static Skill create(CreateSkillCommand cmd) {
        Objects.requireNonNull(cmd.name(), "name is required");
        Objects.requireNonNull(cmd.description(), "description is required");

        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        skill.name = cmd.name();
        skill.description = cmd.description();
        skill.author = cmd.author();
        skill.category = cmd.category();
        skill.status = SkillStatus.DRAFT;
        skill.latestVersion = null;
        skill.downloadCount = 0;
        skill.aclEntries = new ArrayList<>();
        skill.createdAt = skill.updatedAt = Instant.now();
        skill.version = 0L;
        skill.registerEvent(new SkillCreatedEvent(
                skill.id, cmd.name(), cmd.description(), cmd.author(), cmd.category()));
        return skill;
    }

    // ═══ Command methods（充血：mutate state + register event） ═══

    public void recordVersionPublished(String version) {
        this.status.publish();              // state machine guard
        this.latestVersion = version;
        if (this.status == SkillStatus.DRAFT) {
            this.status = SkillStatus.PUBLISHED;  // 首版 transition
        }
        this.updatedAt = Instant.now();
        registerEvent(new SkillVersionPublishedFromAggregate(id, version));
        // Note: SkillVersionPublishedEvent 由 SkillVersion.publish() 自己 register
    }

    public void suspend(SuspendCommand cmd) {
        this.status = this.status.suspend();
        this.updatedAt = Instant.now();
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }

    public void reactivate(ReactivateCommand cmd) {
        this.status = this.status.reactivate();
        this.updatedAt = Instant.now();
        registerEvent(new SkillReactivatedEvent(id, cmd.reason()));
    }

    public void grantAcl(GrantAclCommand cmd) {
        var entry = entryString(cmd.type(), cmd.principal(), cmd.permission());
        if (aclEntries.contains(entry)) {
            throw new IllegalStateException("ACL entry already exists: " + entry);
        }
        aclEntries.add(entry);
        this.updatedAt = Instant.now();
        registerEvent(new SkillAclGrantedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.grantedBy()));
    }

    public void revokeAcl(RevokeAclCommand cmd) {
        var entry = entryString(cmd.type(), cmd.principal(), cmd.permission());
        if (!aclEntries.remove(entry)) {
            throw new IllegalStateException("ACL entry not found: " + entry);
        }
        this.updatedAt = Instant.now();
        registerEvent(new SkillAclRevokedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.revokedBy()));
    }

    public void recordDownload(String downloadedBy) {
        this.downloadCount++;
        this.updatedAt = Instant.now();
        registerEvent(new SkillDownloadedEvent(
                id, latestVersion, downloadedBy, Instant.now()));
    }

    // ═══ Read accessors ═══
    public String getId() { return id; }
    public String getName() { return name; }
    public SkillStatus getStatus() { return status; }
    public String getLatestVersion() { return latestVersion; }
    public List<String> getAclEntries() { return List.copyOf(aclEntries); }
    public boolean canBeAccessedBy(Principal p) { /* matches AclEntries */ }
    // ... 等

    // ═══ Deprecated: emergency rollback path（per ADR-002 §5.2） ═══
    /** @deprecated 僅 emergency rollback 用；正常路徑請用 SkillRepository.findById */
    @Deprecated(since = "v2.0.0", forRemoval = false)
    static Skill fromHistory(String aggregateId, List<DomainEvent> events) {
        // 既有 v1.4.0 replay 邏輯複製 — 僅在 rollback path 使用
        // ...
    }

    private static String entryString(String type, String principal, String permission) {
        return type + ":" + principal + ":" + permission;
    }
}
```

### 4.2 SkillRepository

`backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`：

```java
public interface SkillRepository extends ListCrudRepository<Skill, String> {

    // 依 name UNIQUE 取
    Optional<Skill> findByName(String name);

    // GET /api/v1/skills 列表分頁（with offset/limit）
    List<Skill> findAllByOrderByCreatedAtDesc(Limit limit);

    // ACL-aware 過濾（S016 既有 ?| 模式）
    @Query("""
        SELECT * FROM skills
         WHERE acl_entries ??| ?::text[]
         ORDER BY created_at DESC
         LIMIT :limit
    """)
    List<Skill> findAccessibleByAclEntries(@Param("acl") String[] aclPatterns,
                                            @Param("limit") int limit);
}
```

### 4.3 SkillVersion aggregate

`backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`：

```java
@Table("skill_versions")
public class SkillVersion extends AbstractAggregateRoot<SkillVersion> {

    @Id
    private String id;
    private String skillId;                 // FK 對應 skills.id（plain ID 引用）
    private String version;
    private String storagePath;
    private long fileSize;
    @Column("frontmatter")
    private Map<String, Object> frontmatter;
    @Column("risk_assessment")
    private Map<String, Object> riskAssessment;  // null until ScanOrchestrator 填入
    private Instant publishedAt;
    @Column("allowed_tools")
    private List<String> allowedTools;

    private SkillVersion() {}

    public static SkillVersion publish(PublishVersionCommand cmd) {
        var sv = new SkillVersion();
        sv.id = UUID.randomUUID().toString();
        sv.skillId = cmd.skillId();
        sv.version = cmd.version();
        sv.storagePath = cmd.storagePath();
        sv.fileSize = cmd.fileSize();
        sv.frontmatter = cmd.frontmatter();
        sv.riskAssessment = null;
        sv.publishedAt = Instant.now();
        sv.allowedTools = AllowedTools.parse(cmd.frontmatter());
        sv.registerEvent(new SkillVersionPublishedEvent(
                sv.id, sv.skillId, sv.version, sv.storagePath, sv.fileSize, sv.frontmatter, sv.allowedTools));
        return sv;
    }

    /** Called by ScanOrchestrator listener after scan completes — direct UPDATE via repo */
    public void attachRiskAssessment(Map<String, Object> assessment) {
        this.riskAssessment = assessment;
        registerEvent(new SkillRiskAssessedEvent(
                skillId, version, (String) assessment.get("level"), assessment.get("findings")));
    }

    // accessors...
}
```

### 4.4 SkillVersionRepository

```java
public interface SkillVersionRepository extends ListCrudRepository<SkillVersion, String> {

    boolean existsBySkillIdAndVersion(String skillId, String version);

    List<SkillVersion> findBySkillIdOrderByPublishedAtDesc(String skillId);

    Optional<SkillVersion> findBySkillIdAndVersion(String skillId, String version);
}
```

### 4.5 SkillCommandService（縮減後）

```java
@Service
public class SkillCommandService {

    private final SkillRepository skillRepo;
    private final SkillVersionRepository skillVersionRepo;
    private final StorageService storageService;
    private final PackageService packageService;
    private final SkillValidator skillValidator;

    @Transactional
    public String createSkill(CreateSkillCommand cmd) {
        var skill = Skill.create(cmd);
        skillRepo.save(skill);
        return skill.getId();
    }

    @Transactional
    public void publishVersion(PublishVersionCommand cmd) {
        if (skillVersionRepo.existsBySkillIdAndVersion(cmd.skillId(), cmd.version())) {
            throw new VersionExistsException(cmd.version());
        }
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.recordVersionPublished(cmd.version());
        skillRepo.save(skill);
        skillVersionRepo.save(SkillVersion.publish(cmd));
    }

    @Transactional
    public void grantAcl(GrantAclCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.grantAcl(cmd);
        skillRepo.save(skill);
    }

    @Transactional
    public void revokeAcl(RevokeAclCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.revokeAcl(cmd);
        skillRepo.save(skill);
    }

    @Transactional
    public void suspend(SuspendCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.suspend(cmd);
        skillRepo.save(skill);
    }

    @Transactional
    public void reactivate(ReactivateCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.reactivate(cmd);
        skillRepo.save(skill);
    }
}
```

`uploadSkill` 較複雜（含 zip 解析、storage upload），不在此完整列出 — 屬於同樣模式（多步驟 orchestrate Skill + SkillVersion）。

### 4.6 V6 Flyway migration

`backend/src/main/resources/db/migration/V6__skills_optimistic_lock.sql`：

```sql
-- ============================================================================
-- S024 V6 — Skill aggregate 加 @Version 樂觀鎖欄位
-- ADR-002 § implementation phase 2
-- ============================================================================

ALTER TABLE skills
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN skills.version IS
    'Spring Data JDBC @Version optimistic lock; per ADR-002';
```

---

## 5. File Plan

### 5.1 Production code

| File | Action | Description |
|---|---|---|
| `backend/src/main/resources/db/migration/V6__skills_optimistic_lock.sql` | new | ALTER TABLE skills ADD COLUMN version BIGINT |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | **rewrite** | 從純 ES POJO 改為 @Table + extends AbstractAggregateRoot；method 充血；保留 deprecated `fromHistory` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java` | new | `extends ListCrudRepository<Skill, String>`；含 `findByName`、`findAccessibleByAclEntries` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java` | new | `@Table("skill_versions")` + `extends AbstractAggregateRoot`；static factory `publish` + `attachRiskAssessment` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionRepository.java` | new | `extends ListCrudRepository<SkillVersion, String>`；含 `existsBySkillIdAndVersion`、`findBySkillIdOrderByPublishedAtDesc` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionPublishedFromAggregate.java` | new | 內部用 event；標識 Skill aggregate state change（與 SkillVersionPublishedEvent 區分） |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/events/audit/AuditEventListener.java` | new | 訂閱 9 個 domain events；`@ApplicationModuleListener` 寫入 domain_events |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/events/audit/DomainEventFactory.java` | new | 從 typed event 建構 DomainEvent（per-aggregate sequence 維護） |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | **rewrite** | 縮為 6 個 method × ~3 行；刪除 `loadAggregate`、`saveAndPublish` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | 改打 SkillRepository.findById 取代 SkillReadModelRepository |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` | modify | 回應 mapping 從 SkillReadModel → Skill aggregate（API contract JSON shape 不變） |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` | modify | `updateRiskAssessment` 改打 `skillVersionRepo.findById + attachRiskAssessment + save` 而非舊 read model `@Modifying @Query` |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java` | modify | 不再依賴 SkillReadModel；listener 仍訂閱 SkillCreatedEvent / SkillVersionPublishedEvent，但 fetch 用 SkillRepository |

### 5.2 Production code — DELETE

| File | Action | Reason |
|---|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModel.java` | **delete** | Skill aggregate 自己即 read model |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModelRepository.java` | **delete** | 由 SkillRepository 取代 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillProjection.java` | **delete** | aggregate 自己 mutate state；不再需 listener 維護 read model |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillVersionReadModel.java` | **delete** | 由 SkillVersion aggregate 取代 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillVersionReadModelRepository.java` | **delete** | 由 SkillVersionRepository 取代 |

### 5.3 Test

| File | Action | Description |
|---|---|---|
| `backend/src/test/java/.../skill/domain/SkillAggregateTest.java` | new | AC-2 / AC-6 — 純 unit test，無 Spring；validate state mutation + registered events + state machine guard |
| `backend/src/test/java/.../skill/domain/SkillVersionAggregateTest.java` | new | unit test for SkillVersion factory + attachRiskAssessment |
| `backend/src/test/java/.../skill/command/SkillCommandServiceIntegrationTest.java` | new | AC-3 / AC-4 / AC-5 / AC-7 — Spring Boot test + Testcontainers；驗證 repo.save 觸發 outbox + 跨 aggregate TX + version uniqueness |
| `backend/src/test/java/.../shared/events/audit/AuditEventListenerTest.java` | new | AC-10 — 9 種 event 各觸發一次 + 失敗 retry |
| `backend/src/test/java/.../skill/query/SkillQueryControllerApiContractTest.java` | new | AC-11 — JSON shape regression（snapshot test） |
| `backend/src/test/java/.../shared/migration/V6MigrationTest.java` | new | AC-1 — V6 migration 跑後 skills 表含 version 欄位 |
| `backend/src/test/java/.../skill/command/CrossAggregatePOCTest.java` | new（POC for §2.9）| 驗證 Skill + SkillVersion 兩 aggregate 同 TX 各自 publish events |

### 5.4 Test — DELETE

| File | Action |
|---|---|
| `backend/src/test/java/.../skill/query/SkillProjectionTest.java`（如果存在） | **delete** |
| `backend/src/test/java/.../skill/query/SkillReadModelRepositoryTest.java`（如果存在） | **delete** |

### 5.5 Docs

| File | Action | Description |
|---|---|---|
| `docs/grimo/architecture.md` | **major rewrite** | L22-345 改為「Spring Data JDBC Rich Aggregate + Modulith Outbox」；L329-345 `domain_events` 段落改為「Audit Log」；移除 ES backlog 表（L165-174）；加 ADR-002 引用 |
| `CLAUDE.md` | modify | Architecture Pattern 段「Core domain (skill, security): Event Sourcing + CQRS」改為「Core domain: Spring Data JDBC rich aggregate + Modulith outbox」 |
| `docs/grimo/specs/spec-roadmap.md` | modify | ES-B1~B4 標 obsolete - superseded by ADR-002（S024 ship v2.0.0）；M18 milestone 加上 S024 完成 |
| `docs/grimo/glossary.md` | modify（如果有） | 加 entries：Aggregate Root（state-based）、Event Publication Registry、Audit Log（取代 Event Store） |

**File 統計**：1 SQL + 5 new java prod + 6 modify java prod + 5 delete java prod + 6 new test + 0~2 delete test + 4 modify docs = **~28 files net change**（M scope，含 5 個 deletion 反映 code 縮減）

---

## 6. 估算驗證

| 維度 | Score | Rationale |
|---|---|---|
| Tech risk | 2 | `AbstractAggregateRoot` + Spring Data JDBC 整合已 source-validated；唯一 hypothesis 是跨 aggregate 同 TX publish（POC 確認） |
| Uncertainty | 1 | 設計決策 § 2 全部 Validated 或可 POC 驗證；既有 schema / converter / event types 全部復用 |
| Dependencies | 2 | 強依賴 S023 ship；無新 external dep |
| Scope | 3 | Skill 重寫 + SkillVersion 新增 + 5 個檔案刪除 + 多 test 重寫 + 文件改寫 |
| Testing | 2 | Spring Boot integration test + Testcontainers；無 Docker daemon flakiness 風險 |
| Reversibility | 2 | revert PR + Skill.fromHistory deprecated path 提供 emergency rollback；既有 events audit log 可重建 state |
| **Total** | **12** | **M**（接近 M-L 邊界） |

---

## 7. Ship 後即時觀察點（給 release notes / qa-strategy 參考）

S024 ship 後 24 小時內監控：

1. `event_publication.failed.count` gauge — 任何 spike 立即查 listener log
2. AuditEventListener 是否與 publisher 同步達 100% audit trail（compare event_publication.serialized_event vs domain_events.payload count）
3. `skills` 表寫入頻率（pre/post 對比；應大幅上升因為現在每個 command 都直接 UPDATE skills）
4. HikariCP `hikaricp_connections_pending` metric — async listener 並發是否撐爆 pool
5. p95 latency for `POST /api/v1/skills` + `PUT /api/v1/skills/{id}/versions` — 預期降低（少了 listener 同步消耗，雖然多了 outbox INSERT）

---

## 6. Task Plan

> 由 `/planning-tasks` 於 2026-04-29 寫入。Task files 位於 `docs/grimo/tasks/2026-04-29-S024-T0{1..6}.md`（temporary；ship 後刪除，結果合併進 §7）。

### POC Decision

**POC: required**（per §2.9 hypothesis：跨 aggregate 同 TX `@DomainEvents` publish 行為）

**Strategy**: **folded into T1 first RED test**（不開獨立 `poc/S024/` dir）

**Rationale**:
- 原 spec POC plan 的 `SkillCommandServiceCrossAggregateTest` 需要 Skill aggregate 已轉 `@Table + AbstractAggregateRoot` 才能跑 — 開獨立 POC dir 等於 duplicate T1 implementation work
- TDD RED→GREEN cycle 自然驗 hypothesis：T1 first failing test 即 POC
- 若 T1 RED→GREEN 失敗（即 hypothesis 不成立）→ escalate `/planning-spec S024` 改設計，**不**繼續推 T2-T6

**POC Findings**:（T1 task ship 後填入；含實際 listener row count + 跨 aggregate publish 順序觀察 + 任何 spec drift）

### Task Granularity Check

| 維度 | Score | Rationale |
|---|---|---|
| Task count | 6 | M(13) target 4-6 task；6 task 對齊複雜度（spec §5.1 列 28 file net change）|
| Per-task RED→GREEN→REFACTOR 工作量 | 30-90 min | 每 task 1 個主要 AC + 1-2 個 partial AC；不過 splittable |
| Per-task 對應 AC | 1-3 | T1=AC-1/2/3/13 partial（POC 重）；T2=AC-2 full + AC-6 + AC-8；T3=AC-5/AC-7 partial；T4=AC-4 + AC-7 full；T5=AC-9/AC-10 + AC-11/AC-3 partial；T6=AC-11 full + AC-12 + AC-13 full |
| Splittable | no | 嚴格序列依賴（T1 → T2 → T3 → T4 → T5 → T6），各 task 自包含但下游 depend on 上游 production code 存在 |

### Task Plan Index

| Task | Subject | Primary AC | Partial AC | Est. effort | Status |
|---|---|---|---|---|---|
| **T1** | Infrastructure + Skill aggregate skeleton + cross-aggregate POC RED test | AC-1, AC-3 (POC) | AC-2 (partial), AC-13 (partial) | 60-90 min | **PASS** ✅ |
| **T2** | Skill 完整充血方法 + state machine 守護 + ACL JSONB inline | AC-2 (full), AC-6, AC-8 | AC-3 (extended) | 30-60 min | **PASS** ✅ (15 unit tests) |
| **T3** | SkillVersion 獨立 aggregate 完整化 + repository derived queries + attachRiskAssessment | AC-5 | AC-7 (partial via DB UNIQUE) | 45-60 min | **PASS** ✅ (4 unit + 6 integration) |
| **T4** | SkillCommandService 縮減為 3 行 orchestration | AC-4, AC-7 (full) | AC-3 (extended) | 60 min | **PASS** ✅ (dual-write transitional；4 tests @Disabled，T5 接管) |
| **T5** | Scaffolding subset: AuditEventListener stub + SkillRepository.updateRiskLevel + ScanOrchestrator 改造 | AC-3 (extended) | AC-10 (partial — listener exists, real write deferred) | 30 min | **PASS** ✅ (scaffolding only) |
| **T5B** | (split from T5) AuditEventListener 真實 audit write + 刪 read-models + Query side 切換 + remove saveDomainEventOnly + 修 4 @Disabled tests + ScanOrchestratorTest rewrite | AC-9, AC-10 (full), AC-4 (full) | AC-11 (partial) | 90-120 min | **PASS** ✅ (audit module 拆分；advisory lock 序列化 sequence；20+ tests refactor) |
| **T6** | API contract regression + ApplicationModules.verify + 文件同步（architecture / CLAUDE / standards / roadmap）| AC-11 (full), AC-12, AC-13 (full) | — | 60 min | **PASS** ✅ (3 new tests + 4 docs sync + FlagService advisory lock fix) |

### Task Sequencing Constraints

```
T1 (infrastructure)
  └─▶ T2 (Skill 充血)
        └─▶ T3 (SkillVersion 獨立 aggregate)
              └─▶ T4 (SkillCommandService 縮減)
                    └─▶ T5 (read-side cleanup + Audit)
                          └─▶ T6 (cleanup + doc sync)
```

每 task 完成 RED→GREEN→REFACTOR + verify-all 跑通才進入下一 task。Phase 4 verify-all 3x stability check 在 T6 後跑。

### E2E Smoke Test Decision

**Required**: no（沒有 explicit smoke test task）

**Rationale per Phase 2 instruction**：
- T1-T6 全部 test 用 `@SpringBootTest + Testcontainers`（real PostgreSQL container）— 非 unit-only stub
- Cross-aggregate publish 路徑（T1 first RED test）即 integration test 驗 outbox + listener
- T6 含 API contract test（real MockMvc HTTP layer）
- ScanOrchestrator multi-engine pipeline 既有 `RiskAssessmentIntegrationTest` 覆蓋
- Phase 4 step 1.5 actuator E2E 驗證已在 S023 做過；S024 不引入新 actuator path（per spec）

如 Phase 4 actuator boundary 條件變動（如 metrics 名稱、`/actuator/modulith` module count），會在 T6 補 boundary verification。

### Task Files

每 task 一個檔案於 `docs/grimo/tasks/`：
- `2026-04-29-S024-T01.md`
- `2026-04-29-S024-T02.md`
- `2026-04-29-S024-T03.md`
- `2026-04-29-S024-T04.md`
- `2026-04-29-S024-T05.md`
- `2026-04-29-S024-T06.md`

Task files 為 temporary work item — ship 後刪除，所有 result（含 POC findings）consolidated 進 §7。

---

## 7. Implementation Results

> Consolidated from `docs/grimo/tasks/2026-04-29-S024-T0{1..6,5B}.md` after all tasks PASS（task files cleaned up post-consolidation；source of truth 為本節）。

### Verification

- `./gradlew test`：**269 tests / 0 failures / 5 skipped** — 連續穩定通過（cold + rerun）
- `./gradlew compileTestJava`：BUILD SUCCESSFUL
- `./gradlew compileJava`：BUILD SUCCESSFUL
- ModularityTests.verifyModuleStructure：通過 — `ApplicationModules.of(SkillshubApplication.class).verify()` 不拋例外
- ModularityTests.shouldHaveAuditModuleWithCorrectDependencies：通過 — audit module 邊界乾淨

**E2E artifact verification rationale**: skipped。本 spec 整體為純 production 程式碼演化（aggregate state mapping、Spring Data JDBC `@DomainEvents` proxy、Modulith outbox listener wiring），所有 integration seam 已由 269 個 Spring Boot test（Testcontainers PostgreSQL + 全 Spring context）full-stack 覆蓋：`AuditEventListenerTest` (8 tests)、`SkillCommandServiceCrossAggregateTest` (3 tests)、`S016EndToEndSmokeTest` (multi-step E2E)、`SkillQueryControllerApiContractTest` (2 tests JSON shape regression)。Manual artifact 啟動無新增 boundary condition 可揭露。

### Key Findings

#### KF-1：Spring Data JDBC `@Id` + isNew default 對 client-generated UUID 的雷

**問題**：客戶端產生 UUID 作為 `@Id`，預設 `isNew()` 規則「@Id 非 null = existing」→ `repo.save()` 走 UPDATE → 0 rows affected → 拋 `IncorrectResultSizeDataAccessException`。

**解法**：Aggregate 必須 `implements Persistable<String>` + 自訂 `isNew()`：
- `Skill`：用 `@Version Long version` 是否 null 判斷（factory 設 null → INSERT；DB load 後框架寫回 0 → UPDATE）
- `SkillVersion`：用 `@Transient boolean isNew` flag（factory 設 true；`@PersistenceCreator` no-arg 預設 false）

**參考**：[`docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md` §1.@Version + §4.isNew](../deepwiki/spring-data-jdbc-modulith/aggregate-design.md)

#### KF-2：Spring Modulith cycle 限制 — AuditEventListener 必須獨立 module

**問題**：原計劃放 `shared.events.audit.AuditEventListener`，但 `ApplicationModules.verify()` 拒絕：`shared → skill.domain`（events 引用）+ `skill → shared.security/events`（既有）→ 形成 cycle。

**解法**：移到獨立頂層 module `audit`（`@ApplicationModule(allowedDependencies = {"shared :: events", "skill :: domain"})`）。

**通則**：listener 訂閱 cross-module event 應放在「訂閱者所屬 module」，而非 event 提供者所屬 module。

**Drift 紀錄**：spec §4 描述 AuditEventListener 應位於 `shared :: events :: audit`；實際移至 `audit` 頂層 module。development-standards.md / architecture.md 已反映實作；ModularityTests 註記原因。

#### KF-3：Audit log idempotency — deterministic UUID + ON CONFLICT + advisory lock 三層保險

**問題**：AuditEventListener 9 個 listener method 訂閱不同 events；同 aggregate 上多 listener 並發執行 → `MAX(sequence) + 1` race → `(aggregate_id, sequence)` UNIQUE 衝突 → Modulith 標 publication incomplete + 不自動 retry。

**解法**（per [`AuditEventListener.java`](../../backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java) + [`DomainEventRepository.saveAuditIdempotent`](../../backend/src/main/java/io/github/samzhu/skillshub/shared/events/DomainEventRepository.java)）：
1. `UUID.nameUUIDFromBytes(dedupKey.getBytes(UTF-8))` 確定性映射 row id（dedupKey 構成依事件特性：含 unique id 的用其 UUID；其餘用 `aggregateId + event_type + content`）
2. `INSERT ... ON CONFLICT (id) DO NOTHING` — Modulith retry 重投同事件不產生 duplicate row
3. `SELECT pg_advisory_xact_lock(hashtext('audit:' || aggregate_id)::bigint)` 在獨立 SQL 取鎖（同 TX 持有至 commit）— 同 aggregate 上多 listener 排隊；不同 aggregate 平行；獨立 statement 關鍵原因：`MAX(sequence)` 子查詢需在 lock 取得後的新 statement snapshot 中計算（READ COMMITTED 下，後續 SQL 才看得到 lock holder 已 commit 的 row）

**衍生 fix**：`FlagService.createFlag` 同樣寫 `domain_events` row → 與 audit listener 共用 advisory lock 才不會 race（per T6 fix）。

#### KF-4：PostgreSQL CTE inline + transaction snapshot 互動

**Failed attempt**：`WITH _lock AS (SELECT pg_advisory_xact_lock(...)) INSERT ... SELECT MAX(...) FROM _lock` 即使加 `MATERIALIZED` 也無效 — `MAX(sequence)` 子查詢在 INSERT 同 statement 內計算，使用 statement-start snapshot，**看不到** lock 期間 commit 的競爭 row。

**通則**：需要 「先取鎖 → 後讀最新 state」 必須拆兩個 statement（不同 statement snapshot）— 不能依賴單一 SQL CTE 順序。

#### KF-5：`pg_advisory_xact_lock(integer)` overload 不存在

**坑**：`hashtext` 回 `int4`；`pg_advisory_xact_lock` 只有 `(bigint)` 與 `(integer, integer)` 簽名 → 直接傳 `int4` 嘗試解析為 `(int, int)` 失敗。

**解法**：顯式 cast — `pg_advisory_xact_lock(hashtext('audit:' || x)::bigint)`。

#### KF-6：Awaitility `untilAsserted` 例外處理盲點

**問題**：預設僅捕 `AssertionError`；其他 `RuntimeException`（如 `EmptyResultDataAccessException` from `queryForObject` on empty result）會立即中斷 polling 而非重試。

**解法**：用 `queryForList` + `assertThat(rows).hasSize(1)` 取代 `queryForObject`（empty list 不拋；assertion 失敗才會被捕並 retry）。

#### KF-7：Test cascade 規模遠超預期

T05B 共改寫 ~20 test 檔（Awaitility wrap、setup pattern 改 `commandService.create` / `Skill.fromRow` seed、response type 改 `Map`）+ 刪除 7 test 檔（5 read-model listener tests + 2 ES path replay constructor tests）。Cascade 主因：v1.5.0 ES path 的 sync `eventStore` write → S024 async listener write 的 timing 變化，所有 sync `eventStore.findByAggregateId` 斷言都需 Awaitility wrap。

**Lesson**：架構轉向類 spec（write path 從 sync 變 async）的 test cascade 規模 ≈ 直接 production 改動的 1.5-2x。下次估算需 budget。

#### KF-8：domain_events 角色重新定位（user feedback）

**Initial framing**：T6 doc updates 描述 `domain_events` 為「audit log（非 source of truth）」。

**User feedback**：「domain_events 理論上 event 可以還原出當時聚合的狀態, 這個精神不變, 只是實務上小專案不用這麼重的快照等機制」。

**Revised framing**：`domain_events` 為 **event log** — 保留完整 ES 精神（events 不可變、`(aggregate_id, sequence)` 嚴格遞增、理論上可 replay 還原任意時點 aggregate state）；只是寫入路徑改變（不再是業務寫入端的 source of truth，由 AuditEventListener async 接收 outbox 統一寫入）+ **不主動 replay**（小專案 read-heavy；`repo.findById()` O(1) 顯著快於 events fold）。三份文件（architecture / CLAUDE / standards）一致更新；明確標註「為何不主動 replay」設計取捨 + 「保留 events 序列以備未來 fromHistory factory 重建」。

### Correct Usage Patterns（最有價值的 snippet）

#### Pattern 1：Spring Data JDBC 充血聚合 minimal template

```java
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> implements Persistable<String> {
    @Id private String id;
    @Version @JsonIgnore private Long version;       // 樂觀鎖；不 expose API JSON
    private SkillStatus status;

    @PersistenceCreator
    private Skill() {}                                // Spring Data 用此 constructor 載入

    public static Skill create(CreateSkillCommand cmd) {
        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        skill.version = null;                          // → isNew()=true → INSERT
        // ... mutate state
        skill.registerEvent(new SkillCreatedEvent(...));
        return skill;
    }

    public void suspend(SuspendCommand cmd) {
        this.status = this.status.suspend();           // state machine guard
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }

    @Override public String getId() { return id; }
    @Override public boolean isNew() { return this.version == null; }
}
```

#### Pattern 2：Service 3-line orchestration

```java
@Service
public class SkillCommandService {
    @Transactional
    public void suspend(SuspendCommand cmd) {
        var skill = skillRepo.findById(cmd.skillId()).orElseThrow();
        skill.suspend(cmd);
        skillRepo.save(skill);
        // @DomainEvents proxy interceptor → events 進 event_publication outbox（同 TX）
        // → AFTER_COMMIT async listeners 觸發
    }
}
```

#### Pattern 3：Idempotent audit INSERT（advisory lock + ON CONFLICT）

```java
// AuditEventListener.recordAudit (single TX from @ApplicationModuleListener REQUIRES_NEW)
private void recordAudit(String aggregateId, String eventType,
        Map<String, Object> payload, String dedupKey) {
    // Step 1: per-aggregate 序列化鎖（同 TX 持有；新 statement snapshot）
    jdbc.queryForList(
            "SELECT pg_advisory_xact_lock(hashtext('audit:' || :aggregateId)::bigint)",
            Collections.singletonMap("aggregateId", aggregateId));

    // Step 2: 確定性 UUID + ON CONFLICT DO NOTHING
    var rowId = UUID.nameUUIDFromBytes(dedupKey.getBytes(UTF-8)).toString();
    var payloadJson = objectMapper.writeValueAsString(payload);
    eventRepo.saveAuditIdempotent(rowId, aggregateId, "Skill", eventType, payloadJson, Instant.now());
}
```

```java
// DomainEventRepository
@Modifying
@Query("""
    INSERT INTO domain_events (id, aggregate_id, aggregate_type, event_type, payload, sequence, occurred_at, metadata)
    SELECT :id, :aggregateId, :aggregateType, :eventType, CAST(:payloadJson AS jsonb),
           COALESCE((SELECT MAX(sequence) FROM domain_events WHERE aggregate_id = :aggregateId), 0) + 1,
           :occurredAt, '{}'::jsonb
    ON CONFLICT (id) DO NOTHING
    """)
int saveAuditIdempotent(...);
```

### AC Results

| AC | Description | Status | Test Coverage |
|---|---|---|---|
| AC-1 | V6 Flyway migration 加 `skills.version` BIGINT | ✅ | `V6__skills_optimistic_lock.sql` + Skill 載入觸發 framework 寫入 |
| AC-2 | Skill `@Table` aggregate + 充血方法 mutate state | ✅ | `SkillAggregateTest` (15 unit tests) |
| AC-3 | `skillRepo.save(skill)` → @DomainEvents publish 至 outbox | ✅ | `SkillCommandServiceCrossAggregateTest` (3 tests; T1 POC validated) |
| AC-4 | SkillCommandService 縮為 3 行 orchestration；`saveDomainEventOnly` 已刪除 | ✅ | `SkillCommandService.java` 每 method ≤ 4 statements；無 `eventStore.save` 直接呼叫 |
| AC-5 | SkillVersion 獨立 aggregate；publishVersion 同 TX 寫兩 aggregate | ✅ | `SkillVersionAggregateTest` (4 unit) + `SkillVersionRepositoryTest` (6 integration) |
| AC-6 | Skill.publishVersion / suspend on SUSPENDED 拋例外 | ✅ | `SkillAggregateTest.recordSuspendedOnDraftThrows` etc |
| AC-7 | publishVersion 對重複版本拋 `VersionExistsException` | ✅ | `SkillUploadTest.duplicateVersionRejected` (re-enabled in T05B) + `SkillVersionRepositoryTest.existsBySkillIdAndVersion` |
| AC-8 | ACL grant/revoke 透過充血方法 + acl_entries jsonb 行內 UPDATE | ✅ | `SkillAggregateTest.grantAcl*` etc + `SkillAclCommandServiceTest` (4 tests) |
| AC-9 | SkillProjection / SkillReadModel / SkillVersionReadModel + repos 整組刪除 | ✅ | 5 production files + 5 test files 已 `rm`；compile pass |
| AC-10 | AuditEventListener async 寫 domain_events + idempotent | ✅ | `AuditEventListenerTest` (8 tests / 9 events × idempotency) |
| AC-11 | API contract regression：JSON shape 與 v1.5.0 一致 | ✅ | `SkillQueryControllerApiContractTest` (2 tests; jsonPath assertions for findById + search) |
| AC-12 | architecture.md / CLAUDE.md / development-standards.md / spec-roadmap.md 同步 | ✅ | 4 docs updated；ES backlog ES-B1~B4 已 strikethrough（既有）|
| AC-13 | `ApplicationModules.verify()` 通過；audit module 邊界乾淨 | ✅ | `ModularityTests` 2 tests（含 audit module location 斷言）|

### Tech Debt

| Type | Description | Future Action |
|---|---|---|
| **drift** | spec §4 描述 AuditEventListener 在 `shared :: events :: audit`；實作因 Modulith cycle 限制移至獨立 `audit` 頂層 module（per KF-2）。development-standards.md / architecture.md 已反映實作 | 無需 action — 設計取捨已記錄；spec §4 設計意圖文字保留供 audit trail |
| **skip** | `Skill.fromHistory` deprecated test（spec §6.7 為可選）— T05B 已完整移除 ES path API；emergency rollback path 不存在 → 無 deprecated method 需驗 | 若未來真有 emergency replay 需求，可寫 `Skill.fromHistory(events)` private factory + 對應 test。架構文件已記載此選項 |
| **drift** | spec §2.6 描述 `__dedup` metadata key 為 idempotency 機制；實作改為「確定性 UUID + ON CONFLICT (id)」更直接（per KF-3）。spec §2.6 文字未即時更新；§7 為 ground truth | 無需 action — §7 KF-3 + Pattern 3 已詳載 |
| **drift** | spec §2.7 描述 SkillCommandService 終態為 3 行 orchestration；實作達成 ≤ 4 statements（含 logging line）— spec 字面要求滿足 | 無 |

### Pending Verification

無。所有 AC 在本 session 內 verified；無 integration test compiled-but-not-run。

### Pending Verification (E2E artifact)

無 — 已有 269 Spring Boot tests 覆蓋所有 integration seam（per `verification` 段 rationale）。

---

### QA Review (independent subagent verification)

> Reviewed: 2026-04-30 by independent QA subagent (claude-sonnet-4-6)

**Verdict**: PASS (with minor Javadoc fix applied in-place)

---

#### Build Pipeline Results

| Command | Result |
|---|---|
| `./gradlew test` | BUILD SUCCESSFUL — **269 tests / 0 failures / 5 skipped** ✅ |
| `./gradlew compileTestJava` | BUILD SUCCESSFUL ✅ |
| `./gradlew test --tests "*ModularityTests*"` | BUILD SUCCESSFUL (2 tests pass) ✅ |
| `./gradlew test --tests "*.AuditEventListenerTest"` | BUILD SUCCESSFUL (8 tests pass) ✅ |
| `./gradlew test --tests "*.SkillQueryControllerApiContractTest"` | BUILD SUCCESSFUL (2 tests pass) ✅ |

All five mandatory pipeline steps pass. Test counts match spec §7 claim exactly.

---

#### AC Coverage Verification

| AC | Claimed Test | Verified | Notes |
|---|---|---|---|
| AC-1 | `SkillCommandServiceCrossAggregateTest#v6MigrationAddsVersionColumn` @Tag("AC-1") | ✅ | Test queries `skills.version` column directly; passes |
| AC-2 | `SkillAggregateTest` (15 unit tests) @Tag("AC-2") | ✅ | All state mutation + event registration paths covered |
| AC-3 | `SkillCommandServiceCrossAggregateTest` (3 tests) @Tag("AC-3") | ✅ | Cross-aggregate same-TX outbox publish verified |
| AC-4 | `SkillCommandService.java` method bodies ≤ 4 statements; no `eventStore.save`; `saveDomainEventOnly` absent | ✅ | Code-level inspection confirmed |
| AC-5 | `SkillVersionAggregateTest` (4 unit) + `SkillVersionRepositoryTest` (6 integration) @Tag("AC-5") | ✅ | Pass |
| AC-6 | `SkillAggregateTest.recordVersionPublishedOnSuspendedThrows` etc @Tag("AC-6") | ✅ | 3 state-machine guard tests pass |
| AC-7 | `SkillUploadTest.duplicateVersionRejected` + `SkillVersionRepositoryTest` @Tag("AC-7") | ✅ | Both service-layer + DB UNIQUE guard verified |
| AC-8 | `SkillAggregateTest.grantAcl*` + `SkillAclCommandServiceTest` (4 tests) @Tag("AC-8") | ✅ | Pass |
| AC-9 | `SkillProjection.java` / `SkillReadModel.java` / `SkillReadModelRepository.java` / `SkillVersionReadModel.java` / `SkillVersionReadModelRepository.java` — all absent from filesystem | ✅ | `find` returns empty; compile passes |
| AC-10 | `AuditEventListenerTest` (8 tests covering 9 event types) @Tag("AC-10") | ✅ | Pass; note: SkillVersionPublishedFromAggregate replaces SkillFlaggedEvent from §2.6 design (acknowledged design drift) |
| AC-11 | `SkillQueryControllerApiContractTest` (2 tests) @Tag("AC-11") | ✅ | JSON shape regression passes; `version` field correctly @JsonIgnore-d |
| AC-12 | `architecture.md` L22 heading is "Spring Data JDBC Rich Aggregate + Modulith Outbox"; `CLAUDE.md` L100 updated; `development-standards.md` §Spring Data JDBC updated; `spec-roadmap.md` ES-B1~B4 strikethrough | ✅ | All 4 docs verified |
| AC-13 | `ModularityTests.verifyModuleStructure` + `shouldHaveAuditModuleWithCorrectDependencies` | ✅ | audit module at `io.github.samzhu.skillshub.audit`; `allowedDependencies = {"shared :: events", "skill :: domain"}` |

---

#### Production Code Inspection

| File | Key Checks | Result |
|---|---|---|
| `skill/domain/Skill.java` | `@Table("skills")` ✅; `extends AbstractAggregateRoot<Skill>` ✅; `implements Persistable<String>` ✅; `@Version @JsonIgnore Long version` ✅; `isNew()` = `version == null` ✅; factory + 6 mutator methods ✅; Javadoc matches actual methods ✅ | PASS |
| `skill/domain/SkillVersion.java` | `@Table("skill_versions")` ✅; `extends AbstractAggregateRoot<SkillVersion>` ✅; `implements Persistable<String>` ✅; `@Transient boolean isNew` flag ✅; `publish` factory + `attachRiskAssessment` ✅ | PASS |
| `skill/domain/SkillRepository.java` | `extends ListCrudRepository<Skill, String>` ✅; `updateRiskLevel @Modifying @Query` ✅ | PASS |
| `skill/domain/SkillVersionRepository.java` | `existsBySkillIdAndVersion` ✅; `findBySkillIdOrderByPublishedAtDesc` ✅; `findBySkillIdAndVersion` ✅ | PASS |
| `skill/command/SkillCommandService.java` | No `eventStore` field ✅; No `saveDomainEventOnly` method ✅; No `loadAggregate` method ✅; each command method ≤ 4 statements ✅; Logger present ✅ | PASS |
| `skill/query/SkillQueryService.java` | Uses `SkillRepository.findById()` ✅; returns `Skill` aggregate ✅; Logger present ✅ | PASS |
| `audit/AuditEventListener.java` | 9 `@ApplicationModuleListener` methods ✅; advisory lock (`pg_advisory_xact_lock`) ✅; deterministic UUID + `ON CONFLICT DO NOTHING` via `saveAuditIdempotent` ✅; Logger present ✅; class-level Javadoc ✅ | PASS |
| `shared/events/DomainEventRepository.java` | `saveAuditIdempotent` `@Modifying @Query` with idempotent INSERT ✅ | PASS |
| `security/scan/ScanOrchestrator.java` | No `eventStore` field ✅; No `objectMapper` field ✅; persist via `versionRepo.findBySkillIdAndVersion + attachRiskAssessment + save` ✅ | PASS (see Finding F-1) |
| `security/FlagService.java` | Advisory lock acquisition (`pg_advisory_xact_lock`) before `eventStore.save` ✅ | PASS |

---

#### Deleted Files Verification (AC-9)

All five files confirmed absent from filesystem (`find` returns empty):
- `skill/query/SkillProjection.java` — deleted ✅
- `skill/query/SkillReadModel.java` — deleted ✅
- `skill/query/SkillReadModelRepository.java` — deleted ✅
- `skill/query/SkillVersionReadModel.java` — deleted ✅
- `skill/query/SkillVersionReadModelRepository.java` — deleted ✅

---

#### Design Drift Check

| Drift | Acknowledged in §7? | Status |
|---|---|---|
| AuditEventListener 位於 `audit` 頂層 module（非 `shared :: events :: audit`，per spec §4/§13 描述）| ✅ KF-2 | Documented; architecture.md + ModularityTests 反映實際位置 |
| Idempotency 機制為「確定性 UUID + ON CONFLICT」（非 spec §2.6 的 `__dedup` metadata key）| ✅ KF-3 + Tech Debt | Documented |
| AuditEventListener 訂閱 `SkillVersionPublishedFromAggregate`（非 spec §2.6 所列 `SkillFlaggedEvent`）作為第 9 個 event | ⚠ **未在 §7 明確記錄**（但 AuditEventListenerTest 8 tests / 9 events 的說明隱含此差異）| Minor — FlagService 仍直接寫 domain_events（非走 AuditEventListener），架構一致；僅 Javadoc 表 caption 描述與實作稍有出入 |

---

#### Findings

**F-1 (MINOR — fixed in-place):** `security/scan/ScanOrchestrator.java` class-level Javadoc at lines 51-53 referenced deleted classes `SkillReadModelRepository#updateRiskLevel` and `SkillVersionReadModelRepository#updateRiskAssessment`; line 94-95 referenced deleted `SkillProjection`. These classes were removed as part of AC-9. **Fixed**: Javadoc updated to reference `SkillRepository#updateRiskLevel` and `SkillVersionRepository#findBySkillIdAndVersion → SkillVersion.attachRiskAssessment → versionRepo.save`, and `SkillProjection` reference replaced with `SkillCommandService` explanation. `compileJava` passes after fix.

**F-2 (COSMETIC — no action needed):** `SkillAggregateTest.java` `@DisplayName` strings use legacy naming `recordSuspended` / `recordReactivated` (matching v1.4.0 ES method names) while actual `Skill` methods are `suspend()` / `reactivate()`. Test bodies call the correct methods and pass; naming is legacy documentation artifact from TDD write phase. Not a defect.

**F-3 (COSMETIC — no action needed):** `CLAUDE.md` line 115 still refers to "event store" in the database description (`Spring Data JDBC for CRUD + event store`). This refers to `domain_events` which KF-8 repositions as "event log"; the sentence is in the Tech Stack description (infrastructure-level, pre-ADR-002 wording) and does not contradict the architecture pattern section which correctly uses "event log" terminology. Low impact.

---

#### Summary

- 全部 5 個 mandatory build pipeline 步驟通過
- 269 tests / 0 failures / 5 skipped — 與 spec §7 完全一致
- AC-1 至 AC-13 全部有對應測試且通過
- 刪除清單驗證完整（5 個 production files、5 個 test files 均已消失）
- 2 個設計 drift 均已在 §7 Tech Debt 記錄；1 個小 drift（SkillFlaggedEvent → SkillVersionPublishedFromAggregate）未記錄但不影響正確性
- 發現 1 個 Javadoc 殘留引用（刪除類別的 @link）已在本次 review 修正（ScanOrchestrator.java）
- 無 CRITICAL 或 IMPORTANT 問題


