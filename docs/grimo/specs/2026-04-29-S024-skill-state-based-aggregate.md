# S024: Skill State-Based Aggregate Migration

> Spec: S024 | Size: M(13) | Status: ⏳ Design
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

<!-- Sections 6 (Task Plan) + 7 (Implementation Results) added by /planning-tasks -->
