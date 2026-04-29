# 設計決策與 Skills Hub S023 借鑑分析

---

## 1. 兩專案的關鍵設計決策

| # | 決策 | 理由 | 被否決的替代方案 |
|---|---|---|---|
| 1 | Spring Data JDBC 採 **immutable aggregate + 顯式 SQL**，不做 lazy loading / dirty tracking | 移除 JPA 的 session/cache 心智負擔；測試與除錯更直接 | JPA / Hibernate（已被 spring-data-jpa 覆蓋） |
| 2 | `@MappedCollection` 子集合一律 **delete-and-reinsert** | 不維護 collection 前狀態 → 簡化內部模型；放棄 diff 才能「無 session」 | 增量 diff（[issue #450](https://github.com/spring-projects/spring-data-relational/issues/450) 開放中） |
| 3 | Domain events 透過 `AbstractAggregateRoot` + repository proxy 自動發佈 | 不需要 service 層手動 publish；事件與 entity 生命週期綁定 | 手動 `ApplicationEventPublisher.publishEvent()` |
| 4 | `EventPublishingRepositoryProxyPostProcessor` 在 SQL 之 **後** 才 publish | 確保 SQL 失敗時不會 publish stale event | publish-then-save（會出現「事件已發但 entity 寫入失敗」） |
| 5 | Spring Modulith 把 outbox INSERT 包進業務 TX | transactional outbox 的物理基礎；at-least-once 投遞 | 兩階段提交、message broker（複雜且耦合） |
| 6 | `@ApplicationModuleListener` 預設 **AFTER_COMMIT + REQUIRES_NEW + Async** | 業務 TX 不被 listener 拖；listener 失敗不影響業務 | 同步 in-TX listener（會耦合執行時間與失敗模式） |
| 7 | Listener 失敗 row 標 `FAILED`，**不**自動 retry | 不假設失敗類型；應用決定 retry 策略 | 內建 exponential backoff retry |
| 8 | Republish-on-restart **預設關閉** | 多 instance 部署下會多重啟動引發風暴 | 預設開啟（早期版本如此） |
| 9 | `event_publication` schema 由應用負責建立（**不**自動 init） | 與既有 schema 工具（Flyway/Liquibase）相容；避免衝突 | autoconfigure 自動建表（衝突風險） |
| 10 | 模組邊界用 ArchUnit 掃 bytecode（非 Spring context） | 啟動快；無需註解 | runtime container 介入（侵入性高） |

---

## 2. 已知挑戰與技術債

### 2-1. `@MappedCollection` delete-and-reinsert（spring-data-relational [#450](https://github.com/spring-projects/spring-data-relational/issues/450)）

**問題**：每次 `repo.save()` 對子集合全刪重插，對大集合或高頻寫不可接受。

**計畫中的解法**：選擇性刪除 + upsert，issue 開放但未合入。

**對 Skills Hub 的影響**：S023 不能把 ACL/SkillVersion 放進 Skill aggregate `@MappedCollection`。詳見 §4.1。

### 2-2. `saveAll()` 與 `AbstractAggregateRoot` 衝突（spring-data-commons [#1620](https://github.com/spring-projects/spring-data-commons/issues/1620)）

**問題**：`saveAll(Iterable<S>)` 對 `AbstractAggregateRoot` 子類，`clearDomainEvents()` 反射呼叫打到 collection 而非元素，拋 `IllegalArgumentException`。

**對 Skills Hub 的影響**：S023 起 `SkillCommandService` **避免用 `saveAll()`**；逐筆 `save()` 雖損失批次效率但避開此 bug。

### 2-3. Backlog 大時 `PublicationsInProgress` 線性搜尋（spring-modulith [#1146](https://github.com/spring-projects/spring-modulith/issues/1146)）

**問題**：`getPublication()` O(n)；listener 跟不上發佈時雪崩（積壓越多、查越慢、再積壓）。

**對 Skills Hub 的影響**：S023 必須監控 backlog 增長率；確保 async executor 線程數足夠。GCP Cloud SQL `db-f1-micro` 上 connection pool max 3 是嚴格制約 — async listener 並發數要 ≤ 2。

### 2-4. `event_publication.serialized_event` 8191 bytes 上限（spring-modulith [#519](https://github.com/spring-projects/spring-modulith/issues/519)）

**問題**：PostgreSQL btree/hash index 對單個 entry 限制 8191 bytes；event payload 過大 → INSERT 失敗。

**對 Skills Hub 的影響**：domain events **只序列化** aggregate ID + 最小欄位；**不**含 SARIF report、SKILL.md frontmatter、riskAssessment 等大型 payload。Listener 從 read model 或 storage 重新 fetch。

### 2-5. Spring Data Relational lifecycle events 進入 outbox（spring-modulith [#186](https://github.com/spring-projects/spring-modulith/issues/186)）

**問題**：歷史版本 `BeforeSaveEvent`、`AfterSaveEvent` 也被 outbox 紀錄，造成雜訊。

**對 Skills Hub 的影響**：升 2.0.6 時驗證 `event_publication` 內容；issue 已 closed 但要實測確認。

---

## 3. 生產陷阱清單（10 項）

### 陷阱 1：不呼叫 `save()` → 事件永遠不發出

`@DomainEvents` 只在 Spring Data repository 的 `save()` / `delete()` 後觸發。直接修改 aggregate 物件而不 `repo.save()` → 事件靜默丟棄。

**引用**：[Spring Data — Power of Domain Events (DEV.to)](https://dev.to/kirekov/spring-data-power-of-domain-events-2okm) — "Events are only published when `repository.save()` is called."

**Skills Hub 對策**：`SkillCommandService` 每個命令方法**必須**結尾呼叫 `repo.save(skill)`。Service 層程式碼 review 守則加進 `development-standards.md`。

### 陷阱 2：TX rollback → entity 和 publication 一起 rollback

`repo.save()` 拋例外（constraint violation、連線失敗），TX 回滾，entity 與 publication 都不存在。**設計安全**，但開發者常誤以為「事件卡在 outbox」，實際根本沒寫進去。

**引用**：[Working with Application Events :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html) — "writes entries... as part of the original business transaction"

### 陷阱 3：Listener 必須冪等（retry 會重複觸發）

`status='FAILED'` 的 record 會被 `IncompleteEventPublications.resubmitIncompletePublications()` 再次投遞。Listener 必須容忍「同一 event 執行多次」。

**引用**：[IncompleteEventPublications API](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/events/IncompleteEventPublications.html)

**Skills Hub 對策**：所有 projection listener 用 `ON CONFLICT (id) DO UPDATE`、`saveIfAbsent`、或先 `existsBy()` 檢查。

### 陷阱 4：AFTER_COMMIT → listener 看到的是「最終一致性」

`@ApplicationModuleListener` 在業務 TX commit **之後**的新 TX 執行。短暫時間窗內，外部 query 看得到 entity 但 read model 還沒更新。

**引用**：[Working with Application Events :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html) — "execution occurring after the event publishing transaction completion"

**Skills Hub 影響**：`POST /api/v1/skills` 回 201 後，`GET /api/v1/skills/{id}` 在毫秒級時窗可能 404。前端需處理 — 或 controller 寫入 `skills_read_model` 同步進行（綁定主 TX，犧牲解耦）。

### 陷阱 5：`@ApplicationModuleListener` 預設非同步 → 例外不傳回 caller

`@ApplicationModuleListener` = `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener`。Listener 拋例外**不會傳到原 HTTP 請求**，只留在 `event_publication.status='FAILED'`。預期同步行為的開發者會錯愕。

**引用**：[ApplicationModuleListener Javadoc](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/events/ApplicationModuleListener.html)

### 陷阱 6：長時間 listener 耗光 DB 連線池

每個 `@ApplicationModuleListener` 在 `REQUIRES_NEW` TX 內全程持有 DB 連線。Listener 內若有檔案 I/O、HTTP 呼叫、AI embedding（如 Skills Hub `SearchProjection` 呼叫 Gemini），連線被長時間佔用。

**引用**：[GitHub Discussions #363 — Scaling modulith without DB bottleneck](https://github.com/spring-projects/spring-modulith/discussions/363) — Oliver Drotbohm 建議：長流程改 `@Async + @TransactionalEventListener` 手動組合，分段管 TX。

**Skills Hub 對策**：
- `SearchProjection` 的 embedding 運算先在 listener 完成（不開 TX 階段），完成後才開 TX 寫向量
- 或拆兩段事件：先 mark `pending_embedding`，後台批次填入
- HikariCP `maximum-pool-size` 必須 ≥ `(async listener 最大並發) + (主執行緒 JDBC 使用)`
- Skills Hub GCP `db-f1-micro` 環境上限 3 → async listener 嚴格限 2 並發

### 陷阱 7：`republish-on-restart` + 排程 retry = 重複投遞

兩者同開，應用重啟時兩個機制同時觸發，event 被投遞 2~3 次。

**引用**：[GitHub spring-modulith #926 — Unpublished events, sending duplicates on application startup](https://github.com/spring-projects/spring-modulith/issues/926)

**Skills Hub 對策（S023）**：
- `republish-outstanding-events-on-restart=false`
- 自寫 `@Scheduled` bean 呼叫 `IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5))`
- 配 [ShedLock](https://github.com/lukas-krecan/ShedLock) 確保多 instance 互斥

### 陷阱 8：Event class 改名 → 反序列化失敗

`event_publication.serialized_event` 含 class FQN。class 改名/換 package → 未完成 row 反序列化拋 `ClassNotFoundException`，retry 機制崩潰。

**引用**：[The Hidden Trap in Spring Modulith's Event Retry Mechanism](https://prestigegodson.medium.com/the-hidden-trap-in-spring-moduliths-event-retry-mechanism-5135ddd81158)

**Skills Hub 對策**：
- Event class 視為公開 API，不得任意改名
- 必要重構時：舊名留 alias，等 outbox 清空後再移除
- `development-standards.md` 加入強制守則

### 陷阱 9：Spring Data Relational lifecycle events 進 outbox

Spring Data 框架本身發 `BeforeSaveEvent`、`AfterSaveEvent`。歷史版本未過濾，造成 outbox 雜訊與意外 retry。

**引用**：[GitHub spring-modulith #186](https://github.com/spring-projects/spring-modulith/issues/186)（已 closed）

**Skills Hub 對策**：升 2.0.6 後驗證 `event_publication` 只含自訂 domain events；若仍出現，回頭看 `PersistentApplicationEventMulticaster` 的 filter 邏輯。

### 陷阱 10：大 payload 炸掉 hash index

Event 序列化超過 8191 bytes → `event_publication_serialized_event_hash_idx` INSERT 失敗：

> `index row requires 36896 bytes, maximum size is 8191`

**引用**：[GitHub spring-modulith #519](https://github.com/spring-projects/spring-modulith/issues/519)

**Skills Hub 對策**：domain events **只序列化** aggregate ID 與關鍵欄位。
- `SkillVersionPublished` 不帶 `fileContent` / 完整 frontmatter，只帶 `storagePath`
- Listener 從 storage / read model 重新 fetch 完整資料

---

## 4. Skills Hub S023 借鑑分析

### 4.1. 直接可借鑑的設計

#### A. 充血聚合 + `AbstractAggregateRoot`

`Skill.java` 重寫為：

```java
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> {

    @Id private final String id;
    private String name;
    private String description;
    private String author;
    private String category;
    private SkillStatus status;            // S018 enum
    private String latestVersion;
    private long downloadCount;
    private final Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;                  // 樂觀鎖

    // 充血方法
    public void publishVersion(String newVersion, ...) {
        this.status = status.publish();    // state machine 驗
        this.latestVersion = newVersion;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVersionPublishedEvent(id, newVersion, ...));
    }

    public void suspend(SuspendCommand cmd) {
        this.status = status.suspend();
        this.updatedAt = Instant.now();
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }

    // grant/revoke ACL 不在這 — ACL 是獨立 aggregate（§4.1.B）
}
```

**關鍵**：method 直接改 state，加 `registerEvent()` 累積事件；`repo.save()` 觸發 publish；無需手動 `eventPublisher.publishEvent()`。

#### B. `SkillVersion` / `AclEntry` 為**獨立 aggregate**（不放 Skill 內部）

```java
@Table("skill_versions")
public class SkillVersion extends AbstractAggregateRoot<SkillVersion> {

    @Id private final String id;
    private final String skillId;          // plain ID 引用，不用 @MappedCollection
    private final String version;
    private final String storagePath;
    // ...

    public static SkillVersion publish(String skillId, ..., Map<String,Object> frontmatter) {
        var v = new SkillVersion(...);
        v.registerEvent(new SkillVersionPublishedEvent(skillId, ...));
        return v;
    }
}

@Table("acl_entries")
public class AclEntry extends AbstractAggregateRoot<AclEntry> {

    @Id private final String id;
    private final String skillId;
    private final String type;             // "user" / "role" / "group"
    private final String principal;
    private final String permission;
    private final Instant grantedAt;
    private final String grantedBy;
    // 用 @Modifying @Query 直接 INSERT/DELETE，避免 update 路徑的 delete-reinsert
}
```

**理由**：避開 §3 陷阱 6（`@MappedCollection` delete-and-reinsert）。S023 拒絕「子集合做為 aggregate 內部欄位」的誘惑。

#### C. `@ApplicationModuleListener` 替換 `@EventListener`

當前 `SkillProjection.java` 用 `@EventListener`（同步、in-TX）。S023 改 `@ApplicationModuleListener`（async、AFTER_COMMIT、REQUIRES_NEW、outbox 追蹤）。需配套：
- listener 加冪等保護（陷阱 3）
- HikariCP pool size 校準（陷阱 6）
- 配 retry scheduler + ShedLock（陷阱 7）

### 4.2. 值得注意但不一定適用

#### Liquibase code-first schema 生成

Spring Data JDBC 文件推薦 `LiquibaseChangeSetWriter` 從 entity 生成 DDL。**對 Skills Hub 不適用** — 已用 Flyway，schema 變更要保留 migration history（reverse-engineering 不利於 review）。S023 維持手寫 SQL migration。

#### `@Externalized` (Kafka / RabbitMQ)

短期不需要（Skills Hub 為單體）。長期若拆 search / analytics 為獨立 service，再啟用。**保留作為 S025+ 預留設計**。

### 4.3. 不適用的設計

| 設計 | 不適用原因 |
|---|---|
| `@MappedCollection` for ACL/versions | 高頻寫場景，delete-and-reinsert 不可接受 |
| `repo.saveAll()` for aggregates with `@DomainEvents` | spring-data-commons #1620 bug |
| `republish-outstanding-events-on-restart=true` | 多 instance 部署引發重複投遞 |
| 自動 schema initialization (`spring.modulith.events.jdbc.schema-initialization.enabled=true`) | 與 Flyway 衝突 |

---

## 4-DDL. V4 Flyway Migration（S023 必交付物）

完整 DDL — 可直接複製成 `backend/src/main/resources/db/migration/V4__event_publication.sql`：

```sql
-- ============================================================================
-- S023 V4 — Spring Modulith Event Publication Registry
-- 啟用 transactional outbox pattern（@ApplicationModuleListener 可靠投遞）
--
-- 與 domain_events 表的分工：
--   domain_events  = 業務 audit log（永久 audit，read-only after write）
--   event_publication = Modulith outbox（投遞狀態追蹤；可 archive 或刪除已完成 row）
--
-- 對應：spring-modulith-events-jdbc/.../schemas/v2/schema-postgresql.sql (2.0.6)
-- ============================================================================

CREATE TABLE IF NOT EXISTS event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- hash index 用於 listener 查詢完成狀態（by serialized_event content）
-- 注意：當 serialized_event 超過 8191 bytes 時此 index 會阻擋 INSERT（陷阱 10）
-- 對策：domain events 只序列化 ID + 關鍵欄位，不含 SKILL.md / SARIF
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

-- 用於查詢 completion_date IS NULL（incomplete publications）+ 清理已完成
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);

-- 選用：若啟用 archive mode (spring.modulith.events.completion-mode=archive)
-- 已完成 publication 移到此表保留（可後續排程清理）
CREATE TABLE IF NOT EXISTS event_publication_archive
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_archive_serialized_event_hash_idx
    ON event_publication_archive USING hash (serialized_event);

CREATE INDEX IF NOT EXISTS event_publication_archive_by_completion_date_idx
    ON event_publication_archive (completion_date);
```

### 對應 build.gradle.kts 變更

```kotlin
implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
// 已有 spring-modulith-starter-core 不重複加；events-jdbc 透過 starter 引入
```

### application.yaml 變更

```yaml
spring:
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: false   # 由 Flyway 管理，不要自動建表（陷阱 9）
      republish-outstanding-events-on-restart: false  # 分散式部署用 ShedLock（陷阱 7）
      staleness:
        published: 60m     # 卡住 PROCESSING 60 分鐘 → 標 FAILED
        processing: 30m
```

### 套件結構（S023 新增）

```
io.github.samzhu.skillshub.shared/
├── events/
│   ├── DomainEvent.java
│   ├── DomainEventRepository.java       ← 保留：audit log 用
│   └── ...
└── events/audit/                        ← S023 新增
    └── AuditEventListener.java          ← @ApplicationModuleListener，所有 domain event → INSERT INTO domain_events
```

---

## 5. Skills Hub S023 spec 規格建議

### 範圍

**包含**：
- `Skill` 重寫為充血聚合（state-mutating method + `registerEvent`）
- `SkillVersion` 升格獨立 aggregate（從 `SkillVersionReadModel` 演化）
- 新增 `AclEntry` aggregate（保留現有 `acl_entries` JSONB projection 作為查詢加速 view）
- 全模組 `@EventListener` → `@ApplicationModuleListener`
- V4 Flyway migration（event_publication）
- AuditEventListener（domain_events 寫入收口）
- ShedLock 整合（多 instance retry 互斥）
- 監控：`event_publication_failed_count` Micrometer gauge + Prometheus alert

**不包含**：
- `@Externalized` Kafka/RabbitMQ（留 S025+）
- 完全移除 `domain_events` 表（保留為 audit log）
- Read model 完全併回 aggregate（僅縮減：`SkillReadModel` 大部分欄位移到 `Skill` aggregate root，但 `download_count`、`acl_entries` JSONB 維持為 projection）

### 估算

**M-L（12-15 story points）**：
- Phase 1：`Skill` aggregate 重寫 + `SkillRepository` + service 層改寫（5 pts）
- Phase 2：`SkillVersion` / `AclEntry` aggregate 獨立 + projection 配套（4 pts）
- Phase 3：`@ApplicationModuleListener` 全模組改寫 + 冪等性審查（3 pts）
- Phase 4：V4 migration + AuditEventListener + ShedLock + 監控（3 pts）

### 風險排序（高到低）

| 風險 | 影響 | 緩解 |
|---|---|---|
| HikariCP pool 耗盡（陷阱 6） | 生產 5xx 雪崩 | S023 開始前先擴 pool 至 5+ 或拆 listener |
| Listener 冪等性遺漏（陷阱 3） | 重複資料、計數錯亂 | code review checklist + integration test 驗證重複 invoke |
| Event class 改名（陷阱 8） | 重啟後 retry 全失敗 | `development-standards.md` 強制守則 |
| 多 instance retry race（陷阱 7） | 重複處理 | ShedLock + database row lock |
| ES 歷史 events 對接 | domain_events 仍要可重建（部分 audit 場景） | 維持 domain_events 為 read-only audit；不再用作 source of truth |

---

## 6. 總結

**3 個關鍵 takeaway**：

1. **充血聚合 + outbox 是 Spring 官方推薦的「模組化單體 + 事件驅動」組合** — 兩者透過 `ApplicationEventPublisher` bean 替換點解耦，`@DomainEvents` + `@ApplicationModuleListener` 提供 at-least-once 投遞與失敗追蹤。Skills Hub S023 的方向與生態主流一致。

2. **`@MappedCollection` 是 Spring Data JDBC 最大效能陷阱**，對 Skills Hub 的 ACL/SkillVersion 高頻寫情境**完全不適用**。S023 必須用「獨立 aggregate + ID 引用」模式，否則寫放大 100~1000 倍。

3. **outbox 不是萬靈丹** — Listener 冪等、event class 不改名、HikariCP pool 大小、多 instance retry 互斥 — 這四件事在 S023 一定要做，否則 outbox 帶來的可靠性會被吃掉。Production checklist 要寫進 `qa-strategy.md`。

---

## Sources

- [Persisting Entities :: Spring Data Relational](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html)
- [Domain Driven Design :: Spring Data Relational](https://docs.spring.io/spring-data/relational/reference/jdbc/domain-driven-design.html)
- [Working with Application Events :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html)
- [Appendix (PostgreSQL DDL) :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/appendix.html)
- [Integration Testing :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/testing.html)
- [Production-ready Features :: Spring Modulith](https://docs.spring.io/spring-modulith/reference/production-ready.html)
- [AbstractAggregateRoot Javadoc](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/AbstractAggregateRoot.html)
- [IncompleteEventPublications Javadoc](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/events/IncompleteEventPublications.html)
- [Spring Data JDBC, References, and Aggregates (Spring Blog)](https://spring.io/blog/2018/09/24/spring-data-jdbc-references-and-aggregates/)
- [Spring Data JDBC Aggregates — Thorben Janssen](https://thorben-janssen.com/spring-data-jdbc-aggregates/)
- [Spring Data — Power of Domain Events (DEV.to)](https://dev.to/kirekov/spring-data-power-of-domain-events-2okm)
- [GitHub spring-data-relational #450 — Only delete referenced entities no longer present](https://github.com/spring-projects/spring-data-relational/issues/450)
- [GitHub spring-data-commons #1620 — saveAll() domain events bug](https://github.com/spring-projects/spring-data-commons/issues/1620)
- [GitHub spring-modulith #186 — Spring Data Relational lifecycle events in event_publication](https://github.com/spring-projects/spring-modulith/issues/186)
- [GitHub spring-modulith #519 — Postgres index 8191 bytes limit](https://github.com/spring-projects/spring-modulith/issues/519)
- [GitHub spring-modulith #641 — Dedicated executor for @ApplicationModuleListener](https://github.com/spring-projects/spring-modulith/issues/641)
- [GitHub spring-modulith Discussions #363 — Scaling modulith without DB bottleneck](https://github.com/spring-projects/spring-modulith/discussions/363)
- [GitHub spring-modulith #796 — Overhaul event publication lifecycle](https://github.com/spring-projects/spring-modulith/issues/796)
- [GitHub spring-modulith #926 — Duplicate events on startup](https://github.com/spring-projects/spring-modulith/issues/926)
- [GitHub spring-modulith #1146 — Performance problem with event publication lookup](https://github.com/spring-projects/spring-modulith/issues/1146)
- [GitHub spring-data-relational #1337 — OptimisticLockingFailureException fix](https://github.com/spring-projects/spring-data-relational/issues/1337)
- [GitHub spring-modulith #272 — ApplicationModuleTest + Flyway + Testcontainers](https://github.com/spring-projects/spring-modulith/issues/272)
- [GitHub spring-modulith #1440 — Flyway integration](https://github.com/spring-projects/spring-modulith/issues/1440)
- [The Hidden Trap in Spring Modulith's Event Retry Mechanism (Medium)](https://prestigegodson.medium.com/the-hidden-trap-in-spring-moduliths-event-retry-mechanism-5135ddd81158)
- [Event Publication Registry — DeepWiki](https://deepwiki.com/spring-projects/spring-modulith/3.1-event-publication-registry)
- [ShedLock — distributed lock for @Scheduled](https://github.com/lukas-krecan/ShedLock)
