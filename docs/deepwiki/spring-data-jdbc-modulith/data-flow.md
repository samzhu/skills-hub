# 端到端流程圖

5 個關鍵情境覆蓋 Spring Data JDBC + Spring Modulith 整合的「正常路徑」「失敗路徑」「邊緣情境」。所有流程以 Skills Hub S023 預期實作（`Skill` 充血聚合 + `@ApplicationModuleListener`）為背景。

---

## 1. 建立 Skill — 正常路徑

```
[HTTP Client]
  POST /api/v1/skills  { "name": "...", "description": "..." }
  │
  ▼
[SkillCommandController.create]
  ├─ validate request DTO
  └─ skillCommandService.createSkill(cmd)
       │
       ▼ (TX-1 STARTS)  @Transactional
[SkillCommandService.createSkill]
  ├─ Skill skill = new Skill(...)              ← 建構 aggregate (in-memory)
  ├─ skill.create(...)                         ← 充血方法：set 欄位 + registerEvent(SkillCreated)
  └─ skillRepository.save(skill)
       │
       ▼
[Spring Data JDBC: SimpleJdbcRepository.save]
  └─ JdbcAggregateTemplate.save(skill)
       ├─ persistentEntity.isNew(skill) == true       (id=null OR @Version=null)
       ├─ WritingContext.insert()
       │   └─ DbAction.InsertRoot(skill)
       └─ AggregateChangeExecutor.executeSave(...)
            └─ accessStrategy.insert(...)
                 │
                 ▼ ─────────────  ① INSERT INTO skills (...)  ─────────────▶ [PostgreSQL]
       │
       ▼
[Spring Data Commons: EventPublishingRepositoryProxyPostProcessor]
  ├─ invocation.proceed() returned (SQL done)
  ├─ skill.domainEvents() → [ SkillCreated ]
  ├─ ApplicationEventPublisher.publishEvent(SkillCreated)
  │    │
  │    ▼
  │  [Spring Modulith: PersistentApplicationEventMulticaster.multicastEvent]
  │    ├─ filter listeners with @TransactionalEventListener AFTER_COMMIT
  │    │    → [ SkillProjection.on(SkillCreated), AuditEventListener.on(any) ]
  │    └─ for each listener id:
  │         └─ JdbcEventPublicationRepository.create()
  │              │
  │              ▼ ─── ② INSERT INTO event_publication (uuid, listener_id, ...) ───▶ [PostgreSQL]
  │
  └─ skill.clearDomainEvents()                 ← @AfterDomainEventPublication
       │
       ▼ (TX-1 COMMITS)
       │
       ▼
[Return skill to controller]
       ▼
HTTP 201 { "id": "skill-uuid" }


===  AFTER_COMMIT phase — 兩個獨立 async TX  ===

  ┌─────────────────────────────────────────────────────┐
  │ TX-2a: SkillProjection async listener thread         │
  │   ├─ CompletionRegisteringMethodInterceptor.invoke   │
  │   │   └─ markProcessing(publicationId)               │
  │   │       └─ UPDATE event_publication SET status='PROCESSING' …
  │   │   (REQUIRES_NEW TX，獨立)                          │
  │   ├─ SkillProjection.on(SkillCreated)                │
  │   │   └─ skillReadModelRepository.save(new SkillReadModel(...))
  │   │        ▼ ─── INSERT INTO skills_read_model ───▶ [PostgreSQL]
  │   └─ markCompleted(publicationId)                    │
  │       └─ UPDATE event_publication SET status='COMPLETED', completion_date=now() …
  └─────────────────────────────────────────────────────┘
  ┌─────────────────────────────────────────────────────┐
  │ TX-2b: AuditEventListener async listener thread      │
  │   ├─ markProcessing(...)                              │
  │   ├─ AuditEventListener.on(domainEvent)              │
  │   │   └─ domainEventRepository.save(toDomainEvent(e))
  │   │        ▼ ── INSERT INTO domain_events (audit) ──▶ [PostgreSQL]
  │   └─ markCompleted(...)                              │
  └─────────────────────────────────────────────────────┘
```

**關鍵跨界標記**：
- ① 業務 entity SQL — 與 publication INSERT 同 TX-1（atomic）
- ② publication INSERT — 同 TX-1
- TX-2a / TX-2b — AFTER_COMMIT 後分別新開 TX，async 並行執行

**外部觀察**：HTTP 201 回傳時，`skills` row 已寫入 + `event_publication` row 已寫入（status=PUBLISHED），但 `skills_read_model` 與 `domain_events` 可能尚未填入（async listener 還沒跑完）。對 `GET /api/v1/skills/{id}` 即時查詢可能短暫 404 — **eventual consistency window**。

---

## 2. 發版（PublishVersion）— 含 Listener 失敗 + retry

此情境展示 `event_publication` 狀態機在失敗路徑下的演化。

```
[HTTP] POST /api/v1/skills/{id}/versions  { version: "1.0.0", zip: ... }
  │
  ▼ (TX-1 STARTS)
[SkillCommandService.publishVersion]
  ├─ Skill skill = skillRepository.findById(id).orElseThrow()
  ├─ skill.publishVersion(...)               ← 充血：驗 version 不重複 + status transition + registerEvent
  └─ skillRepository.save(skill)
       │
       ▼ ── ① UPDATE skills SET status='PUBLISHED', latest_version='1.0.0' WHERE id=? AND version=N ──▶ [PG]
       ▼ ── ② INSERT INTO event_publication (event_type='SkillVersionPublished', listener_id='SearchProjection.on', status='PUBLISHED') ──▶ [PG]
       ▼ ── ② INSERT INTO event_publication (event_type='SkillVersionPublished', listener_id='AuditEventListener.on', status='PUBLISHED') ──▶ [PG]
       ▼ (TX-1 COMMITS)
HTTP 201

===  AFTER_COMMIT  ===

[TX-2a: SearchProjection async]
  ├─ markProcessing(pub_a)
  ├─ SearchProjection.on(SkillVersionPublished)
  │   └─ embeddingClient.embed(skillContent)        ← Gemini API call
  │       ✘ 連線 timeout / quota exceeded
  └─ throw EmbeddingException
        │
        ▼ CompletionRegisteringMethodInterceptor.handleFailure
        ▼ markFailed(pub_a)  (REQUIRES_NEW)
        ▼ ── UPDATE event_publication SET status='FAILED' WHERE id=pub_a ──▶ [PG]
        ▼ exception re-thrown to async executor

[TX-2b: AuditEventListener async]
  ├─ markProcessing(pub_b)
  ├─ AuditEventListener.on(SkillVersionPublished)   ✓ 成功
  └─ markCompleted(pub_b)
       └─ ── UPDATE event_publication SET status='COMPLETED', completion_date=now() WHERE id=pub_b ──▶ [PG]


===  60 秒後 — 應用排程的 retry  ===

[Application @Scheduled bean]
  └─ incompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1))
       │
       ▼
[DefaultEventPublicationRegistry.processPublications]
  ├─ SELECT * FROM event_publication
  │    WHERE completion_date IS NULL AND publication_date < now() - 1min
  │    → [pub_a]                                  ← FAILED 也算 incomplete
  ├─ for pub_a:
  │    ├─ events.markResubmitted(pub_a, now())
  │    │   └─ UPDATE event_publication SET status='RESUBMITTED', last_resubmission_date=now()
  │    └─ invokeTargetListener(SkillVersionPublished, listener_id='SearchProjection.on')
  │         │
  │         ▼ TX-3a
  │         ├─ markProcessing(pub_a)
  │         ├─ SearchProjection.on(SkillVersionPublished)
  │         │   └─ embeddingClient.embed(skillContent)   ✓ 這次成功
  │         │   └─ vectorRepo.save(...)
  │         └─ markCompleted(pub_a)
  │              └─ UPDATE event_publication SET status='COMPLETED', completion_date=now()
```

**Listener 冪等性的關鍵**：retry 時 `SearchProjection.on` **可能已經在前一次** vector INSERT 部分成功（如果失敗發生在 vector INSERT 後但 markCompleted 前）。Listener 必須處理「同一 event 來兩次」— 用 `ON CONFLICT (skill_id, version) DO UPDATE` 或先 SELECT 檢查。

---

## 3. ACL Grant — 高頻寫場景（為何不能用 `@MappedCollection`）

此情境展示**錯誤**設計（把 ACL 當 `@MappedCollection` 子集合）的災難效果。

### 反例：Skill 含 `@MappedCollection Set<AclEntry>`

```
Skill skill = skillRepository.findById(id);   ← SELECT skills + JOIN/SELECT acl_entries (500 rows)
skill.grantAcl("user:bob", "read");           ← 充血：set.add(new AclEntry(...))
skillRepository.save(skill);
   │
   ▼ JdbcAggregateTemplate.save → WritingContext.update()
   ▼
   ├─ ① UPDATE skills SET ... WHERE id=? AND version=?  (1 row affected)
   ├─ ② DELETE FROM acl_entries WHERE skill_id=?         (500 rows DELETED)
   ├─ ③ INSERT INTO acl_entries VALUES (...)             (501 INSERTs，含新加的 1 個)
   │   〔batch 化但語意仍是 "全刪重插"〕
   └─ ④ INSERT INTO event_publication ...                (publication INSERTs)
```

**寫入量**：1 update + 500 delete + 501 insert = **1002 SQL 操作**。對只新增 1 個 ACL entry 而言，寫放大 1000 倍。Index thrashing 嚴重。

### 正解：ACL 為獨立 aggregate，**不**放進 Skill

```
[POST /api/v1/skills/{id}/acl] { "principal": "user:bob", "permission": "read" }
  │
  ▼ (TX-1)
[SkillAclService.grant(skillId, cmd)]
  ├─ Skill skill = skillRepository.findById(skillId)     ← SELECT skills 唯一一行
  ├─ skill.guardCanGrantAcl(cmd, currentUser)           ← 業務驗證（不改 state）
  │
  ├─ AclEntry entry = AclEntry.of(skillId, cmd);        ← 新獨立 aggregate
  ├─ entry.registerEvent(new SkillAclGranted(...));
  └─ aclEntryRepository.save(entry)
       ▼ ── INSERT INTO acl_entries (id, skill_id, ...) ──▶ [PG]   ← 1 INSERT
       ▼ ── INSERT INTO event_publication (...) ──▶ [PG]
       (TX-1 COMMITS)

===  AFTER_COMMIT — projection 同步維護冗餘 view（如 Skill 上的 acl_entries jsonb 列）  ===

[SkillAclProjection async]
  └─ skillRepository.appendAclEntry(skillId, "user:bob:read")   ← @Modifying @Query 直接 UPDATE
       ▼ ── UPDATE skills SET acl_entries = acl_entries || ... WHERE id=? AND NOT (... @> ...)
```

**寫入量**：1 select + 2 inserts + 1 update = 4 SQL 操作。可接受。

**Skills Hub 對應**：保持當前 `acl_entries jsonb` 設計（S016）— 不引入 `@MappedCollection`。新的 `AclEntry` aggregate 用於完整審計記錄（每筆獨立 row），projection 維護 `skills.acl_entries` 的 denormalized 視圖以加速查詢。

---

## 4. 應用重啟後 outbox 重投

此情境展示「業務 TX 已 commit 但 listener 未跑完」期間 JVM crash 的恢復路徑。

### Crash 前狀態

```
event_publication 表中：
┌──────┬───────────┬──────────────┬──────────────────┬─────────────────────┬──────────┐
│ id   │ status    │ event_type   │ publication_date │ completion_date     │ ...      │
├──────┼───────────┼──────────────┼──────────────────┼─────────────────────┼──────────┤
│ p_a  │ PROCESSING│ SkillCreated │ 10:00:00         │ NULL                │ ...      │
│ p_b  │ PUBLISHED │ SkillCreated │ 10:00:00         │ NULL                │ ...      │
│ p_c  │ COMPLETED │ SkillUploaded│ 09:55:00         │ 09:55:03            │ ...      │
└──────┴───────────┴──────────────┴──────────────────┴─────────────────────┴──────────┘
   ↑ p_a：listener 已 markProcessing 但未 markCompleted 就 crash
   ↑ p_b：multicaster INSERT 完，listener 還沒 pickup（async queue 中）
   ↑ p_c：正常完成
```

### Crash 後重啟（兩種設定）

#### 設定 A — `republish-outstanding-events-on-restart=true`

```
[Application 啟動]
  └─ PersistentApplicationEventMulticaster.afterSingletonsInstantiated()
       └─ resubmitIncompletePublications(__ -> true)
            │
            ▼
       [DefaultEventPublicationRegistry.processIncompletePublications]
            ├─ SELECT * FROM event_publication WHERE completion_date IS NULL
            │     → [ p_a, p_b ]
            ├─ for each:
            │   ├─ markResubmitted(...)
            │   └─ invokeTargetListener(...)
            │       (走 §1 圖的 TX-2 路徑)
            └─ ...

→ p_a 的 listener 被再次呼叫（必須冪等！）
→ p_b 的 listener 第一次被呼叫
```

**風險**：若應用 deploy 多 instance（GCP Cloud Run），**每個 instance 啟動都重投一次**，同一筆 publication 被多 instance 同時抓 → race condition + 重複處理。

#### 設定 B — `republish-outstanding-events-on-restart=false` + 應用排程 retry

```
[Application 啟動]
  └─ 不自動重投

[60s 後，@Scheduled tick 在某個 instance 觸發]
  └─ incompleteEventPublications.resubmitIncompletePublicationsOlderThan(1min)
       │（需配 ShedLock 等分散式 lock，避免多 instance 同時跑）
       ▼
       [同上 processIncompletePublications]
```

**Skills Hub 推薦走 B**（design-decisions.md §3 陷阱 7）— 配 ShedLock + Cloud SQL row lock，確保多 instance 部署下 retry 互斥。

---

## 5. 跨模組事件 — Skill → Search 投影

展示 Modulith 模組邊界透過事件解耦。

```
[Module: skill]                                [Module: search]
─────────────────                              ──────────────────
SkillCommandService                            SearchProjection
  │
  └─ skillRepository.save(skill)
       │
       ▼ (publishes SkillVersionPublished)
       │
       ▼  (event_publication INSERT in TX-1)
       │
   [TX-1 COMMIT]
       │
       ▼ AFTER_COMMIT trigger
       │                                       ▼
       │                                       @ApplicationModuleListener
       │                                       void on(SkillVersionPublished e) {
       │                                           // 同模組內可寫 search.* 表
       │                                           vectorRepo.save(...)
       │                                       }
       │
       ▼ markCompleted(publicationId)

  ❌ skill 模組程式不能直接呼叫 search.* 程式
  ✓ skill 模組可發布 event；search 模組可訂閱
```

**驗證**：

```java
@Test
void verifyModuleStructure() {
    var modules = ApplicationModules.of(SkillshubApplication.class);
    modules.verify();   // ArchUnit：若 skill 模組有 import io.github.samzhu.skillshub.search.*，測試 fail
}
```

**好處**：未來把 search 模組整個抽出變獨立 service（透過 Kafka externalization），只需加 `@Externalized` 到 event class，模組內部 listener 維持不變。

---

## 流程總結 — TX 邊界對照

| 流程階段 | TX 屬性 | 與業務 TX 的關係 | 失敗效果 |
|---|---|---|---|
| `repo.save()` SQL | `@Transactional` REQUIRED（業務 TX） | 是業務 TX | 業務 rollback → entity 與 publication 一起 rollback |
| `event_publication` INSERT | `@Transactional` REQUIRED | 加入業務 TX | 同上 |
| `markProcessing` | `@Transactional` REQUIRES_NEW（聚合 listener TX 之外） | 獨立 | 失敗不影響 listener 執行 |
| Listener 本體 | `@Transactional` REQUIRES_NEW（`@ApplicationModuleListener` 內含） | 獨立 | listener TX 失敗不影響 publication row、不影響業務 TX |
| `markCompleted` / `markFailed` | `@Transactional` REQUIRES_NEW | 獨立 | 失敗 → row 卡在 PROCESSING（staleness monitor 才能標 FAILED） |

**心智模型**：業務 TX 是 atomic「entity + publication」交易；listener TX 是獨立的 at-least-once 投遞；retry / staleness 是 recovery 機制。
