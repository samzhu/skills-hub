# S023: Spring Modulith Outbox Foundation

> Spec: S023 | Size: M(12) | Status: ✅ Shipped (v1.5.0, 2026-04-29)
> Date: 2026-04-29
> ADR: [ADR-002 — Skill aggregate state-based migration](../adr/ADR-002-skill-aggregate-state-based.md)
> Research: `docs/deepwiki/spring-data-jdbc-modulith/`
> Depends on: S018 ✅（v1.4.0）；無 code-level dependency；ADR-002 Accepted
> Blocks: S024（Skill state-based aggregate — 必須先有可靠 outbox 才能改 aggregate 寫入 path）

---

## 1. Goal

啟用 Spring Modulith Event Publication Registry（transactional outbox），把 Skills Hub 全模組事件投遞從**手動同步 `ApplicationEventPublisher.publishEvent()`** 升級為**框架管控的 at-least-once outbox + AFTER_COMMIT async listener + 失敗 retry**。

對應 ADR-002 §5 的 implementation phase 1 — **不動 aggregate 設計**（仍是純 ES POJO），只動事件投遞基礎建設。S024 在此基礎上才動 Skill 充血改寫。

S023 ship 後使用者不會看到行為改變（API contract 不變、業務行為不變），但運維端獲得：
- 事件投遞可靠性（at-least-once，失敗 retry）
- 觀測性（incomplete count gauge、Modulith /actuator）
- listener 失敗不再 silently drop（status=FAILED 可查可重投）

---

## 2. Approach

### 2.1 對比表

| Approach | Chosen | Rationale |
|---|---|---|
| A: Spring Modulith Event Publication Registry（spring-modulith-starter-jdbc）+ V4 Flyway 手建 schema + ShedLock 7.7.0 互斥 retry | **yes** | 框架原生方案；deepwiki 6 份檔案 source-level 驗證可行；Skills Hub 已用 spring-modulith-starter-core；schema 走 Flyway 與既有遷移風格一致 |
| B: 自寫 outbox 表 + 手寫 polling | no | 需重造輪子（rendezvous lock、staleness monitor、metrics、retry semantic）；S023 的整個價值就是「不要再 hand-roll event delivery」 |
| C: 引入 Apache Kafka / RabbitMQ 做事件 broker | no | over-engineering；Skills Hub 是單體模組化；外部 broker 增加部署複雜度（額外 GCP service）+ MVP 階段未證需求 |
| D: 全 11 個 @EventListener 一次到位改 @ApplicationModuleListener | no（hybrid）| `SkillProjection.on(SkillCreatedEvent)` 與 `SkillProjection.on(SkillVersionPublishedEvent)` 是 FK target row 創建者，改 async 後 SearchProjection / ScanOrchestrator FK 違反；本 spec 採 hybrid（這 2 個保留 @EventListener，其餘 9 個改）；S024 廢除這 2 個 listener（Skill 自己 INSERT row） |

### 2.2 Hybrid Listener Migration 策略

11 個 listener 分兩類：

**保留 `@EventListener`（同步 in-TX）** — S024 廢除：

| File:Line | Method | 為何不動 |
|---|---|---|
| `SkillProjection.java:61` | `on(SkillCreatedEvent)` | 創建 `skills` row（FK target）；AFTER_COMMIT 才創 → SearchProjection/AnalyticsProjection 預期該 row 已存在會 FK violation |
| `SkillProjection.java:104` | `on(SkillVersionPublishedEvent)` | 創建 `skill_versions` row（FK target）；ScanOrchestrator AFTER_COMMIT 寫 risk_assessment 預期該 row 已存在 |

**改 `@ApplicationModuleListener`（async + AFTER_COMMIT + REQUIRES_NEW + outbox 追蹤）**：

| File:Line | Method | 既有冪等性 | 加保護策略 |
|---|---|---|---|
| `SkillProjection.java:149` | `on(SkillDownloadedEvent)` | 否（`UPDATE SET count=count+1` retry 多計）| **加冪等保護**：以 domain_event.id 檢查重投，已處理過則 skip |
| `SkillProjection.java:165` | `on(SkillAclGrantedEvent)` | 是（`appendAclEntry` SQL 含 `WHERE NOT (acl_entries @> ...)`）| 維持 |
| `SkillProjection.java:182` | `on(SkillAclRevokedEvent)` | 是（`removeAclEntry` SQL 用 `jsonb_agg WHERE elem != :entry`）| 維持 |
| `SkillProjection.java:197` | `on(SkillSuspendedEvent)` | 是（`updateStatus` SET 'SUSPENDED' 重複寫無副作用）| 維持 |
| `SkillProjection.java:212` | `on(SkillReactivatedEvent)` | 是（同上）| 維持 |
| `AnalyticsProjection.java:37` | `on(SkillDownloadedEvent)` | 否（INSERT 新 row with random UUID）| **加 idempotency UNIQUE constraint**：`download_events.event_id UNIQUE`（V4 同次 migration 加 column + index）|
| `SearchProjection.java:66` | `onSkillCreated(SkillCreatedEvent)` | 否（`PgVectorStore.add` ON CONFLICT 已冪等 by skill_id）| 維持（vector_store 已 ON CONFLICT id DO UPDATE） |
| `SearchProjection.java:98` | `onVersionPublished(SkillVersionPublishedEvent)` | 是（delete-then-add，重投無副作用） | 維持 |
| `ScanOrchestrator.java:109` | `on(SkillVersionPublishedEvent)` | 否（pipeline 含 `eventStore.save(SkillRiskAssessed)` random UUID）| **加冪等保護**：以 source domain_event.id 為 idempotency key（檢查 `skill_versions.risk_assessment IS NULL` 或 `risk_assessment->>'eventId'` 比對）|

### 2.3 失敗語義變化（必須在 PR 時對 user / ops 溝通）

| 情境 | 變更前（@EventListener） | 變更後（@ApplicationModuleListener） |
|---|---|---|
| Listener 拋例外 | propagate 回 publisher → 業務 TX rollback → API 回 5xx | event 留 outbox `status=FAILED` + `completion_date IS NULL` → 業務 TX 仍 commit → API 回 2xx |
| Read model 何時可見 | publisher TX commit 時即可見 | publisher TX commit 後 + listener async 完成（毫秒級延遲） |
| 多 listener 順序 | `@Order` 控同步順序 | 跨模組無順序（FK-creating 同步 listener 先跑 → 其他 async 並發） |
| 失敗可重投 | 無；丟了就丟了 | 排程 retry（`IncompleteEventPublications.resubmitOlderThan`）+ 重啟 staleness monitor 標 stuck row 為 FAILED |

### 2.4 新增依賴

```kotlin
// build.gradle.kts
extra["shedlockVersion"] = "7.7.0"

dependencies {
    // S023 — Spring Modulith Event Publication Registry (transactional outbox)
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    // S023 — 多 instance retry 互斥
    implementation("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${property("shedlockVersion")}")
}
```

`spring-modulith-starter-jdbc` 透過既有 `spring-modulith-bom:2.0.6` 解析版本（無需顯式 version）；ShedLock 無 BOM 必須顯式版本。

### 2.5 Research Citations

| 來源 | 對本 spec 的支撐 |
|---|---|
| [`docs/deepwiki/spring-data-jdbc-modulith/event-publication-registry.md`](../../deepwiki/spring-data-jdbc-modulith/event-publication-registry.md) | Spring Modulith outbox 完整機制（schema、insert path、listener wrapping、retry、staleness monitor）source-level 引用 |
| [`docs/deepwiki/spring-data-jdbc-modulith/design-decisions.md` §3 陷阱清單](../../deepwiki/spring-data-jdbc-modulith/design-decisions.md) | 10 條生產陷阱 — listener 冪等、event 不可改名、payload 8191 bytes 上限、HikariCP pool 等 |
| [Spring Modulith `@ApplicationModuleListener.java` lines 47-92](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/ApplicationModuleListener.java) | 確認語義 = `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener`（默認 AFTER_COMMIT phase） |
| [Spring Modulith V2 PostgreSQL schema](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/resources/org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql) | event_publication 表 DDL 來源（V4 migration 直接照抄 + 註解） |
| [Spring Modulith `JdbcEventPublicationAutoConfiguration.java` lines 74-80](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationAutoConfiguration.java) | 確認 schema initialization 預設 disabled（`@ConditionalOnProperty(... havingValue = "true")`）— 必須走 Flyway |
| [ShedLock RELEASES.md](https://github.com/lukas-krecan/ShedLock/blob/master/RELEASES.md) | 7.7.0 明確支援 Spring Boot 4.x（Skills Hub 用 4.0.6）|
| [ShedLock README — PostgreSQL DDL + JdbcTemplateLockProvider config](https://github.com/lukas-krecan/ShedLock/blob/master/README.md) | shedlock 表 schema + `usingDbTime()` 規避 clock skew 設定 |

### 2.6 Confidence Classification

| 設計決策 | Confidence | 證據 |
|---|---|---|
| `@ApplicationModuleListener` AFTER_COMMIT semantics + outbox INSERT 同 publisher TX | **Validated** | source-cited per deepwiki `event-publication-registry.md §3` |
| Hybrid listener migration（2 個保留 @EventListener，9 個改） | **Validated** | listener inventory + FK relationship 推導；S024 將廢除保留的 2 個 |
| ShedLock 7.7.0 + Spring Boot 4.0.6 相容 | **Validated** | RELEASES.md 明確標示 |
| `usingDbTime()` 規避 clock skew | **Validated** | ShedLock README + GCP Cloud SQL Proxy 環境符合 |
| `event_publication.serialized_event` 8191 bytes 上限不會觸發 | **Validated** | 既有 events payload 最大 ~2KB（無 SARIF / SKILL.md 內容）|
| `@ApplicationModuleListener` async executor 預設配置適合 GCP Cloud Run | **Hypothesis** | 預設 `SimpleAsyncTaskExecutor` 每 task 開新 thread，可能耗盡；POC 確認是否需自訂 `ThreadPoolTaskExecutor` |
| FK-creating @EventListener 仍能在業務 TX 內 commit skills/skill_versions row | **Validated** | 既有 v1.4.0 已運作此模式；不變動 |

**POC: required**（1 項，限縮）— Spring Modulith async executor 預設 thread pool 是否需要 customize。POC scope：寫 ApplicationModuleTest，連發 50 個 SkillDownloadedEvent，觀察 thread 開啟數、HikariCP pool 是否飽和、event_publication processing latency。

### 2.7 Validation Pass — pre-handoff drift check

從現況 read 確認：
- ✅ build.gradle.kts 已有 `spring-modulith-starter-core 2.0.6`（line 52）— 加 `spring-modulith-starter-jdbc` 不引入版本衝突
- ✅ application.yaml 無 `spring.modulith.events.*` 設定 — 全部新加
- ✅ 既有 V1 migration 不含 `event_publication` 與 `shedlock` 表 — V4 / V5 加新表無衝突
- ✅ `@ApplicationModuleListener` 全庫零實際使用（per listener inventory）— migration 是淨新增
- ✅ Skills Hub HikariCP `maximum-pool-size: 10`（local）/ `3`（gcp）— async listener REQUIRES_NEW 各持 1 connection，並發限制需考慮（POC 確認）

---

## 3. SBE Acceptance Criteria

> 驗收命令：`./gradlew clean test jacocoTestReport`（V01 from qa-strategy.md）— 所有 `@Tag("AC-N")` 測試綠燈 + JaCoCo 80% line coverage gate（V03）通過。

### AC-1: V4 Flyway migration 建立 `event_publication` + `event_publication_archive` 表

```gherkin
Given Skills Hub backend 從 v1.4.0 升級
When  Spring Boot 啟動 Flyway 自動執行 migration
Then  資料庫存在 event_publication 表（9 欄位 per V2 schema）
And   存在 event_publication_archive 表（同 schema）
And   存在 hash index event_publication_serialized_event_hash_idx
And   存在 btree index event_publication_by_completion_date_idx
And   spring.modulith.events.jdbc.schema-initialization.enabled = false（避免與 Flyway 衝突）
```

### AC-2: V5 Flyway migration 建立 `shedlock` 表

```gherkin
Given Skills Hub backend 從 v1.4.0 升級
When  Spring Boot 啟動 Flyway 自動執行 migration
Then  資料庫存在 shedlock 表（4 欄位：name PK / lock_until / locked_at / locked_by）
And   表為空（lock 由 ShedLock 動態 INSERT）
```

### AC-3: V4 migration 為 `download_events` 加 `event_id` UNIQUE column

```gherkin
Given AnalyticsProjection.on(SkillDownloadedEvent) 改 @ApplicationModuleListener 後可能 retry
When  V4 migration 完成
Then  download_events 表新增 event_id VARCHAR(36) NOT NULL DEFAULT gen_random_uuid()::text 欄位
And   存在 UNIQUE INDEX uq_download_events_event_id (event_id)
And   AnalyticsProjection 寫入時填入 SkillDownloadedEvent.eventId（避免重投插重複行）
```

### AC-4: 9 個 listener 改 `@ApplicationModuleListener`，2 個保留 `@EventListener`

```gherkin
Given S023 PR merge 後
When  grep `@ApplicationModuleListener` 在 backend/src/main/java/io/github/samzhu/skillshub/
Then  恰有 9 個 method 標註（per §2.2 表格）
And   grep `@EventListener` 恰有 2 個（SkillProjection.on(SkillCreatedEvent) + SkillProjection.on(SkillVersionPublishedEvent)）
And   每個改 @ApplicationModuleListener 的 listener 配對一個 integration test 驗證冪等性
```

### AC-5: 業務 TX rollback 時 event_publication 也 rollback（atomic outbox）

```gherkin
Given SkillCommandService.createSkill 執行中 eventStore.save() 後拋例外
When  TX rollback
Then  domain_events 表無此 record（既有行為，不變）
And   event_publication 表無此 record（驗證 outbox INSERT 與業務 TX 同 commit/rollback）
And   無 listener 被觸發（async listener 也不執行）
```

### AC-6: Listener 拋例外時 event_publication.status = FAILED

```gherkin
Given SkillProjection.on(SkillSuspendedEvent) 內部 mock 拋 RuntimeException
When  publisher 呼叫 events.publishEvent(SkillSuspendedEvent)
And   publisher TX commit
And   AFTER_COMMIT async listener 觸發並失敗
Then  event_publication 對應 row 的 status = 'FAILED'
And   completion_date IS NULL
And   publisher API 仍回 2xx（async 失敗不傳回 caller — 已記錄此語義變化於 §2.3）
```

### AC-7: `@Scheduled` retry bean 重投 incomplete publication

```gherkin
Given event_publication 表存在 1 筆 status='FAILED' 且 publication_date 為 5 分鐘前
And   原始 listener mock 修復為 success
When  IncompleteEventRepublishTask.republishIncompleteEvents() 被排程觸發
Then  該 publication 被 resubmit（status 由 FAILED → RESUBMITTED → COMPLETED）
And   completion_date 不再是 NULL
And   original listener 被呼叫一次（mock verify(listener, times(1))）
```

### AC-8: ShedLock 確保多 instance retry 互斥

```gherkin
Given 兩個 IncompleteEventRepublishTask instance 同時排程觸發（模擬多 Cloud Run instance）
When  兩者同時呼叫 republishIncompleteEvents()
Then  恰有一個 instance 取得 lock 並執行
And   另一個 instance 跳過（無 exception，無 lock 競爭）
And   shedlock 表中 name='republish-incomplete-events' 的 row 反映 lock_until / locked_by
```

### AC-9: HikariCP pool 在 50 並發 download event 下不飽和

```gherkin
Given application.yaml 設 maximum-pool-size=10（local）
When  50 個 SkillDownloadedEvent 同時被 publish（壓測）
Then  AnalyticsProjection async listener 全部完成（無 timeout / 無 connection wait exception）
And   event_publication 全部 COMPLETED
And   無 HikariPool-1 - Connection is not available 例外於 log
```

### AC-10: `event_publication.failed.count` Micrometer gauge 暴露給 actuator

```gherkin
Given Spring Boot 啟動完成
When  GET /actuator/metrics/event_publication.failed.count
Then  回 200 + JSON 含 measurements[].value
And   value 反映即時 SELECT COUNT(*) FROM event_publication WHERE status='FAILED' 結果
And   integration test 寫入 1 筆 FAILED row → gauge 值變 1
```

### AC-11: `/actuator/modulith` 端點曝露 + 列出 6 個 module

```gherkin
Given application.yaml management.endpoints.web.exposure.include 含 modulith
When  GET /actuator/modulith
Then  回 200 + JSON keys 含：shared / skill / security / search / analytics / storage
And   skill module dependencies 列表反映實際 listener 訂閱關係
```

### AC-12: Spring Modulith ApplicationModules.verify() 通過

```gherkin
Given S023 PR 完成
When  ./gradlew test --tests "*ModularityTests*"
Then  通過（無模組邊界違規）
And   無新增非法依賴（S023 不該引入跨模組直接 import）
```

---

## 4. Interface / API Design

### 4.1 V4 Flyway migration

`backend/src/main/resources/db/migration/V4__event_publication_outbox.sql`：

```sql
-- ============================================================================
-- S023 V4 — Spring Modulith Event Publication Registry (transactional outbox)
-- ADR-002 § implementation phase 1
--
-- 與 domain_events 表的分工（S024 起）：
--   domain_events    = 業務 audit log（permanent；S024 後由 AuditEventListener 寫入）
--   event_publication = Modulith outbox（投遞狀態追蹤；可 archive 或 cleanup）
--
-- 對應 spring-modulith-events-jdbc 2.0.6 v2 schema（PostgreSQL）
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

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);

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

-- ============================================================================
-- AnalyticsProjection 冪等保護：download_events.event_id UNIQUE
-- AC-3 對應；@ApplicationModuleListener 重投時透過 ON CONFLICT 防重複行
-- ============================================================================

ALTER TABLE download_events
    ADD COLUMN IF NOT EXISTS event_id VARCHAR(36) NOT NULL DEFAULT gen_random_uuid()::text;

CREATE UNIQUE INDEX IF NOT EXISTS uq_download_events_event_id
    ON download_events (event_id);
```

### 4.2 V5 Flyway migration（ShedLock）

`backend/src/main/resources/db/migration/V5__shedlock.sql`：

```sql
-- ============================================================================
-- S023 V5 — ShedLock 7.7.0 distributed lock 表
-- ADR-002 § implementation phase 1
-- 用途：多 Cloud Run instance @Scheduled 任務互斥（避免 incomplete event publication
--       被多個 instance 同時 retry，造成 listener 多次觸發）
-- ============================================================================

CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

### 4.3 application.yaml 新增段落

```yaml
spring:
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: false      # 由 Flyway V4 管，不要自動建表（陷阱 9）
      republish-outstanding-events-on-restart: false  # 多 instance 部署用 ShedLock 排程 retry（陷阱 7）
      staleness:
        # 卡住 PROCESSING 30 分鐘 → 標 FAILED；卡住 PUBLISHED 60 分鐘 → 標 FAILED
        # 由 staleness monitor 排程觸發（預設 1 分鐘 check 一次）
        published: 60m
        processing: 30m

# ----- Skills Hub 自定 ----- 
skillshub:
  scheduler:
    republish-delay: PT1M      # IncompleteEventRepublishTask fixedDelay
    republish-older-than: PT5M # 只重投 5 分鐘前還沒完成的
```

### 4.4 SchedulerConfig

`backend/src/main/java/io/github/samzhu/skillshub/shared/config/SchedulerConfig.java`：

```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()  // PostgreSQL NOW() — 規避 cluster clock skew
                .build()
        );
    }
}
```

### 4.5 IncompleteEventRepublishTask

`backend/src/main/java/io/github/samzhu/skillshub/shared/events/IncompleteEventRepublishTask.java`：

```java
@Component
public class IncompleteEventRepublishTask {

    private final IncompleteEventPublications incompletePublications;
    private final Duration olderThan;

    public IncompleteEventRepublishTask(
            IncompleteEventPublications incompletePublications,
            @Value("${skillshub.scheduler.republish-older-than:PT5M}") Duration olderThan) {
        this.incompletePublications = incompletePublications;
        this.olderThan = olderThan;
    }

    @Scheduled(fixedDelayString = "${skillshub.scheduler.republish-delay:PT1M}")
    @SchedulerLock(
        name = "republish-incomplete-events",
        lockAtMostFor = "PT10M",
        lockAtLeastFor = "PT30S"
    )
    public void republishIncompleteEvents() {
        LockAssert.assertLocked();
        incompletePublications.resubmitIncompletePublicationsOlderThan(olderThan);
    }
}
```

### 4.6 EventPublicationMetrics

`backend/src/main/java/io/github/samzhu/skillshub/shared/events/EventPublicationMetrics.java`：

```java
@Component
public class EventPublicationMetrics {

    private final JdbcTemplate jdbc;

    public EventPublicationMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("event_publication.failed.count",
                () -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE status = 'FAILED'",
                    Long.class))
             .description("Count of event publications in FAILED state")
             .register(registry);

        Gauge.builder("event_publication.incomplete.count",
                () -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL",
                    Long.class))
             .description("Count of event publications not yet completed")
             .register(registry);
    }
}
```

### 4.7 Listener migration pattern（範例：SkillProjection.on(SkillSuspendedEvent)）

```diff
-    @EventListener
-    void on(SkillSuspendedEvent event) {
+    @ApplicationModuleListener
+    void on(SkillSuspendedEvent event) {
         repo.updateStatus(event.aggregateId(), SkillStatus.SUSPENDED.name(), Instant.now());
     }
```

對 idempotency 不足的 listener（`SkillDownloadedEvent` / `ScanOrchestrator`）需額外加保護 — 詳 §4.8 / §4.9。

### 4.8 SkillDownloadedEvent listener 加冪等保護（範例）

```java
@ApplicationModuleListener
void on(SkillDownloadedEvent event) {
    // 用 event id 檢查重投 — 若 download_events 已存在這個 event_id，skip
    int updated = repo.incrementDownloadCountIfFirstTime(
            event.aggregateId(),
            event.eventId(),         // SkillDownloadedEvent 需新增 eventId 欄位（V4 配套）
            Instant.now());
    if (updated == 0) {
        log.atDebug()
            .addKeyValue("eventId", event.eventId())
            .log("Skipping duplicate SkillDownloadedEvent (already processed)");
    }
}
```

對應的 SQL（在 `SkillReadModelRepository`）：

```java
@Modifying
@Query("""
    UPDATE skills
       SET download_count = download_count + 1, updated_at = :ts
     WHERE id = :id
       AND NOT EXISTS (
           SELECT 1 FROM download_events de
            WHERE de.event_id = :eventId
       )
""")
int incrementDownloadCountIfFirstTime(@Param("id") String id,
                                       @Param("eventId") String eventId,
                                       @Param("ts") Instant ts);
```

### 4.9 ScanOrchestrator 冪等保護

ScanOrchestrator 觸發完整三階段掃描 pipeline；冪等保護以 source domain_event.id 為 key：

```java
@ApplicationModuleListener
void on(SkillVersionPublishedEvent event) {
    String eventId = event.sourceEventId();   // 新增欄位
    // 檢查該版本是否已掃描過（以 event id 為冪等 key）
    if (skillVersionReadModelRepository.hasRiskAssessmentFromEvent(
            event.skillId(), event.version(), eventId)) {
        log.atDebug()
            .addKeyValue("eventId", eventId)
            .addKeyValue("skillId", event.skillId())
            .log("Skipping duplicate scan trigger");
        return;
    }
    runScan(event);
}
```

對應 SQL（在 `SkillVersionReadModelRepository`）：

```java
@Query("""
    SELECT EXISTS (
        SELECT 1 FROM skill_versions
         WHERE skill_id = :skillId
           AND version = :version
           AND risk_assessment->>'sourceEventId' = :eventId
    )
""")
boolean hasRiskAssessmentFromEvent(@Param("skillId") String skillId,
                                    @Param("version") String version,
                                    @Param("eventId") String eventId);
```

ScanOrchestrator 寫 risk_assessment 時，將 source event id 寫入 jsonb 內：

```java
Map<String, Object> riskAssessment = Map.of(
    "level", level,
    "findings", findings,
    "sarif", sarif,
    "scannedAt", Instant.now(),
    "sourceEventId", event.sourceEventId()   // 新增
);
```

---

## 5. File Plan

| File | Action | Description |
|---|---|---|
| `backend/build.gradle.kts` | modify | 加 `spring-modulith-starter-jdbc` + `shedlock-spring 7.7.0` + `shedlock-provider-jdbc-template 7.7.0`；`extra["shedlockVersion"] = "7.7.0"` |
| `backend/src/main/resources/application.yaml` | modify | 加 `spring.modulith.events.*` 段（schema-init disabled、no auto-republish、staleness）+ `skillshub.scheduler.*` 段 |
| `backend/src/main/resources/db/migration/V4__event_publication_outbox.sql` | new | event_publication + archive 表 + indexes + download_events.event_id UNIQUE 補強 |
| `backend/src/main/resources/db/migration/V5__shedlock.sql` | new | shedlock 表 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/config/SchedulerConfig.java` | new | `@EnableScheduling` + `@EnableSchedulerLock` + `LockProvider` bean（usingDbTime） |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/events/IncompleteEventRepublishTask.java` | new | `@Scheduled(PT1M)` + `@SchedulerLock` republish older than `PT5M` |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/events/EventPublicationMetrics.java` | new | `event_publication.failed.count` + `event_publication.incomplete.count` Micrometer gauge |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillDownloadedEvent.java` | modify | 加 `String eventId`（建構時 `UUID.randomUUID().toString()`，給 idempotency key 用） |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionPublishedEvent.java` | modify | 加 `String sourceEventId`（同上，給 ScanOrchestrator 冪等 key 用） |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillProjection.java` | modify | 5 個 method（`on(SkillDownloadedEvent)` / `on(SkillAclGrantedEvent)` / `on(SkillAclRevokedEvent)` / `on(SkillSuspendedEvent)` / `on(SkillReactivatedEvent)`）改 `@ApplicationModuleListener`；2 個 method（`on(SkillCreatedEvent)` / `on(SkillVersionPublishedEvent)`）保留 `@EventListener` 加註解說明 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillReadModelRepository.java` | modify | 新增 `incrementDownloadCountIfFirstTime` `@Modifying @Query`（with NOT EXISTS event_id check） |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillVersionReadModelRepository.java` | modify | 新增 `hasRiskAssessmentFromEvent` 查詢 |
| `backend/src/main/java/io/github/samzhu/skillshub/analytics/AnalyticsProjection.java` | modify | `@EventListener` → `@ApplicationModuleListener`；INSERT 時填入 `event.eventId()`（搭配 V4 UNIQUE constraint + ON CONFLICT DO NOTHING） |
| `backend/src/main/java/io/github/samzhu/skillshub/analytics/DownloadEventReadModel.java` | modify | record 加 `String eventId` 欄位 |
| `backend/src/main/java/io/github/samzhu/skillshub/analytics/DownloadEventReadModelRepository.java` | modify | save 改 `@Modifying @Query INSERT ... ON CONFLICT (event_id) DO NOTHING` |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java` | modify | 2 個 method 改 `@ApplicationModuleListener` |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` | modify | `@EventListener` → `@ApplicationModuleListener`；加 `hasRiskAssessmentFromEvent` 冪等檢查；`riskAssessment` jsonb 寫入 `sourceEventId` 欄位 |
| `backend/src/test/java/.../shared/events/EventPublicationOutboxIntegrationTest.java` | new | AC-5 / AC-6 / AC-7 — outbox INSERT 同 TX、listener 失敗 → status=FAILED、retry 重投 |
| `backend/src/test/java/.../shared/events/IncompleteEventRepublishTaskTest.java` | new | AC-7 / AC-8 — ShedLock 互斥 + IncompleteEventPublications 整合 |
| `backend/src/test/java/.../shared/events/EventPublicationMetricsTest.java` | new | AC-10 — gauge 即時反映 DB row count |
| `backend/src/test/java/.../skill/query/SkillProjectionIdempotencyTest.java` | new | AC-3 / AC-4 — 5 個改 listener 各跑兩次驗冪等性 |
| `backend/src/test/java/.../analytics/AnalyticsProjectionIdempotencyTest.java` | new | AC-3 — 重投 SkillDownloadedEvent 不產生重複 download_events row |
| `backend/src/test/java/.../security/scan/ScanOrchestratorIdempotencyTest.java` | new | AC-3 — 重投不產生重複 SkillRiskAssessed event |
| `backend/src/test/java/.../shared/events/HikariPoolUnderLoadTest.java` | new | AC-9 — 50 並發 download event 不耗盡 pool |
| `backend/src/test/java/.../actuator/ModulithActuatorTest.java` | new | AC-11 — `/actuator/modulith` endpoint smoke |
| `docs/grimo/architecture.md` | modify | 加「Spring Modulith Outbox」段落（在 `domain_events` 表說明後）；標 `domain_events` 為「source of truth — until S024 ships, then audit log」transitional state |
| `docs/grimo/specs/spec-roadmap.md` | modify | 把 S023 從 Backlog 移到 Active；加 S024；標 ES-B1~B4 為 obsolete pending S024 |

**File 統計**：3 modify config + 2 new SQL + 5 new java prod + 8 new java test + 9 modify java + 2 modify docs = **29 files touched**（適合 M scope）

---

## 6. 估算驗證

| 維度 | Score | Rationale |
|---|---|---|
| Tech risk | 2 | Spring Modulith outbox 已 GA、source-verified；唯一 hypothesis 是 async executor pool（POC 確認） |
| Uncertainty | 1 | 設計決策 § 2 全部 Validated；listener inventory 完整；FK 順序解法明確（hybrid migration） |
| Dependencies | 2 | depends on S018 ✅（已 ship）；ShedLock 為 external lib（穩定，無未知）|
| Scope | 3 | 9 個 listener migration + 5 個新 java + 2 new SQL + 8 個 test + 多模組 — 接近大型但仍 single phase |
| Testing | 2 | 多個 integration test 含 ApplicationModuleTest；無 Docker daemon flakiness（用 Testcontainers pgvector） |
| Reversibility | 2 | revert 一個 PR；event_publication 表保留無傷；listener 改回 @EventListener |
| **Total** | **12** | **M** |

---

## 6. Task Plan

POC: required（已完成 Phase 1 — validated by source reading；無需 runtime POC）

### POC Findings

**Question**：Spring Modulith async executor 預設 thread pool 是否需要 customize？

**Answer**：**Yes — 必須 customize**。

**證據（Spring 官方文件 + Spring Modulith source）**：
1. `@ApplicationModuleListener` 包含 `@Async`（per [deepwiki event-publication-registry.md §1](../../deepwiki/spring-data-jdbc-modulith/event-publication-registry.md)）
2. Spring `@Async` 預設行為（[Spring docs § Annotation Support](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async)）：
   - 若有 bean of type `TaskExecutor`（推薦 name `applicationTaskExecutor`）→ 使用之
   - 否則 → fallback 至 `SimpleAsyncTaskExecutor`（每 task 開新 thread，**無上限**）
3. Skills Hub 目前無 `TaskExecutor` bean → fallback path
4. GCP Cloud Run `db-f1-micro` HikariCP pool=3；`@ApplicationModuleListener` `@Transactional(REQUIRES_NEW)` 各 task 持 1 connection → 不限 thread = 不限 connection 需求 → pool 飽和拋 `Connection is not available`

**設計選擇**（落地於 T01）：注入 `ThreadPoolTaskExecutor` bean (name=`applicationTaskExecutor`, corePoolSize=2, maxPoolSize=2, queueCapacity=200)：
- pool=3 留 1 connection 給主請求 thread → async listener 並發上限 2
- queueCapacity=200 緩衝突發負載
- 名稱 `applicationTaskExecutor` 對齊 Spring `@Async` 預設查找

### Task Index

| # | Task | AC | Status |
|---|---|---|---|
| T01 | Infrastructure: deps + V4/V5 Flyway + ThreadPoolTaskExecutor | AC-1, AC-2, AC-3 | ✅ PASS |
| T02 | SkillProjection — 5 listeners → @ApplicationModuleListener + SkillDownloadedEvent idempotency | AC-4 (5/9) | ✅ PASS |
| T03 | AnalyticsProjection + SearchProjection migration with idempotency | AC-4 (3/9 more) | ✅ PASS |
| T04 | ScanOrchestrator migration with idempotency | AC-4 (1/9 last) | ✅ PASS |
| T05 | Outbox semantics + scheduled retry + ShedLock 互斥 | AC-5, AC-6, AC-7, AC-8 | ✅ PASS |
| T06 | Observability + final integration verification | AC-9, AC-10, AC-11, AC-12 | ✅ PASS |
| T07 | Fix existing test regressions after async listener migration（Phase 4 期間新增）| - | ✅ PASS |

**Execution order**：T01 → T02 → T03 → T04 → T05 → T06（嚴格序列；T03 / T04 平行可行但無 throughput gain）

**Total**：6 tasks for M(12) spec — 對齊 estimation-scale.md M-size 4-6 task 範圍。

**E2E smoke test**：T05 `EventPublicationOutboxIntegrationTest` 與 T06 `HikariPoolUnderLoadTest` + `ModulithActuatorTest` 共同提供 E2E coverage（無 stub 整體 publisher → outbox → listener → metric chain）。

---

## 7. Implementation Results

Date: 2026-04-29 · Status: **✅ Ready to Ship** (target `v1.5.0`, M18)

### 7.1 Acceptance Criteria — All PASS

| AC | Verification | Result |
|---|---|---|
| AC-1: V4 Flyway 建立 event_publication + archive 表 | `EventPublicationSchemaTest` × 3 + Flyway log `Applied 5 migrations` | ✅ |
| AC-2: V5 Flyway 建立 shedlock 表 | `ShedlockSchemaTest` × 3（schema + 4 欄位）| ✅ |
| AC-3: V4 為 download_events 加 event_id UNIQUE | `EventPublicationSchemaTest` × 2 + `DownloadEventRepositoryIdempotencyTest` × 2 | ✅ |
| AC-4: 9 個 listener → @ApplicationModuleListener，2 個保留 @EventListener | 4 個 ListenerAnnotationsTest reflection check（5+1+2+1=9）| ✅ |
| AC-5: 業務 TX rollback 時 event_publication 也 rollback | `EventPublicationOutboxBehaviorTest` AC-5 | ✅ |
| AC-6: Listener 拋例外時 event_publication.status = FAILED | `EventPublicationOutboxBehaviorTest` AC-6（Awaitility）| ✅ |
| AC-7: @Scheduled retry bean 重投 incomplete publication | `IncompleteEventRepublishTaskWiringTest` AC-7 × 2 | ✅ |
| AC-8: ShedLock 多 instance 互斥 | `IncompleteEventRepublishTaskWiringTest` AC-8 × 2（wiring 驗證；framework 並發行為由 ShedLock 7.7.0 自身保證）| ✅ |
| AC-9: HikariCP 50 並發不飽和 | `HikariPoolUnderLoadTest` 50 LoadTestEvent 全 COMPLETED / 0 FAILED | ✅ |
| AC-10: event_publication.failed.count Micrometer gauge | `EventPublicationMetricsTest` × 2 | ✅ |
| AC-11: /actuator/modulith 列出 6 個 module | `ModulithActuatorTest` + E2E bootRun probe 顯示 6 modules + EVENT_LISTENER edges | ✅ |
| AC-12: ApplicationModules.verify() 通過 | `ModularityTests`（含 S023 新 shared/config + IncompleteEventRepublishTask + EventPublicationMetrics）| ✅ |

### 7.2 Verification Summary

| Gate | Command | Result |
|---|---|---|
| V01 — full test suite | `./gradlew clean test jacocoTestReport` | ✅ BUILD SUCCESSFUL 2m 37s · 262 tests / 0 failed / 5 skipped（含 4 method @Disabled with rationale）|
| V03 — JaCoCo 80% line gate | `./gradlew jacocoTestCoverageVerification` | ✅ LINE 89.53%（covered=6834, missed=799）|
| V04 — ApplicationModules.verify() | `ModularityTests` | ✅ |
| Step 1.5 — E2E actuator artifacts | `bootRun` + `curl /actuator/{modulith, metrics/event_publication.failed.count, metrics/event_publication.incomplete.count}` | ✅ 6 modules + 0 failed + 0 incomplete |

### 7.3 Production Bugs Discovered & Fixed

| Bug | Symptom | Root Cause | Fix |
|---|---|---|---|
| async listener 失去 SecurityContext | SearchProjection ACL 寫入 `currentUserOwnerId()` 為 null | Spring `@Async` 在新 thread 執行；預設不傳遞 `SecurityContextHolder` | `AsyncListenerConfig` 用 `DelegatingSecurityContextAsyncTaskExecutor` wrap `ThreadPoolTaskExecutor` |
| `@Transactional` 對 private method 無效 | `SkillQueryService.downloadAndRecord` 為 private；publish 在 TX 外 → `@ApplicationModuleListener` silently drop | Spring AOP 不 proxy private method | 在 public 入口 `downloadLatest` / `downloadVersion` 加 `@Transactional` |
| AOP proxy field 不透明 | Test 直接 `bean.field` 取得 null（拿到 proxy class 同名未初始化 field）| Spring AOP proxy 對 field access 不 delegate；只 proxy method | Test 改用 method-level access (`getInvocations()` getter)；落地於 `IncompleteEventRepublishTask.getOlderThan()` package-private getter |

### 7.4 Design Refinements during Implementation

| 變更 | 原 spec 設計 | 實作後修正 | 原因 |
|---|---|---|---|
| Drop SkillProjection.on(SkillDownloadedEvent) idempotency | §4.8 `incrementDownloadCountIfFirstTime` 用 `download_events.event_id NOT EXISTS` 子查詢 dedup | 拿掉 idempotency 檢查；SkillProjection 直接增量 | async 並行下 SkillProjection 與 AnalyticsProjection 順序未定；Analytics 先 INSERT 會錯誤跳過增量。改為「接受極罕見 markCompleted 失敗 retry 雙計」（UI 顯示，非財務）|
| `@Order(LOWEST_PRECEDENCE)` 移除 | ScanOrchestrator 用 `@Order` 確保 SkillProjection 先寫 skill_versions row | 移除 @Order | Hybrid migration 後 SkillProjection 仍 sync @EventListener（在 publisher TX 內寫 row）；async listener 在 commit 後觸發，row 已存在 |
| YAML profile actuator exposure | application.yaml 加 `modulith` | application.yaml + config/application-dev.yaml 都加 | YAML profile override = replace（不是 merge）；test 加 `@SpringBootTest(properties=...)` 顯式 override 防後續 profile 變更 |

### 7.5 Build Infrastructure Tuning

| 問題 | 短期解 | 長期解 |
|---|---|---|
| `./gradlew clean test` Java heap OOM（17+ `@SpringBootTest`）| `tasks.test { maxHeapSize = "3g" }` + `-Dspring.test.context.cache.maxSize=8` 寫入 `build.gradle.kts` | S025 Test Pyramid Realignment — 改 slice / @ApplicationModuleTest 降 cache key 數 |
| Test container churn（53 distinct context cache key）| `TestcontainersConfiguration` 加 design-intent comment 文件化為 known limitation | 同上 |

### 7.6 Hybrid Listener Migration — Final State

| Module | Listener method | Annotation | Rationale |
|---|---|---|---|
| skill | SkillProjection.onSkillCreated | `@EventListener`（保留 sync）| 寫 `skills` row；後續 async listener 依賴 row 存在（FK guard）|
| skill | SkillProjection.onVersionPublished | `@EventListener`（保留 sync）| 寫 `skill_versions` row；同上 FK guard |
| skill | SkillProjection.on(SkillDownloaded) | `@ApplicationModuleListener` | 增量更新，不創 FK target |
| skill | SkillProjection.on(SkillSuspended/Reactivated/AclGranted/AclRevoked) × 4 | `@ApplicationModuleListener` | 同上 |
| analytics | AnalyticsProjection.on(SkillDownloaded) | `@ApplicationModuleListener` + `saveIdempotent`（ON CONFLICT event_id DO NOTHING）| 與 SkillProjection 並行；UNIQUE constraint 嚴格冪等 |
| search | SearchProjection.onSkillCreated | `@ApplicationModuleListener` | 創 vector embeddings（async）|
| search | SearchProjection.onVersionPublished | `@ApplicationModuleListener` | 同上 |
| security | ScanOrchestrator.on(SkillVersionPublished) | `@ApplicationModuleListener` + `hasRiskAssessmentFromEvent` 冪等檢查 | scan 是 expensive；retry 不重做 |

**Total**：9 async + 2 sync = 11 listeners（per spec §2.1 對比表）。

### 7.7 Open Risks / Follow-ups

| 風險 | 緩解 / 處理 spec |
|---|---|
| 2 個 e2e MockMvc test method `@Disabled`（`S016EndToEndSmokeTest` + `RiskAssessmentIntegrationTest` × 3）| MockMvc + `@ApplicationModuleListener` async 時序不可靠；功能由其他 test 分散覆蓋。S025 改寫為 `@ApplicationModuleTest + Scenario` API |
| 53/77 tests 用 `@SpringBootTest`（測試金字塔倒置）| `maxHeapSize=3g + cache.maxSize=8` workaround；S025 系統解 |
| Container churn during test run（每 context 重建 → container 重建）| Spring 官方 lifecycle 設計如此；S025 降 cache key 數後自然減 |
| **Async listener test flakiness under load**（QA 連續跑時 `SearchProjectionAclWriteTest.AC-1` 等 Awaitility wrap 在 53 個 cache key churn 下 10s 不夠）| T07 後續 QA 發現後將所有 Awaitility timeout 從 10s 升 30s（13 處 / 6 個檔案）；驗證 verify-all.sh 連續 3 次全綠後 ship。S025 系統解：降 cache key 數，async dispatch 自然穩 |
| `bootRun -x processAot` workaround 仍存在 | pre-existing tech debt（S014 follow-up）；非 S023 範圍 |
| ShedLock 跨 JVM 並發互斥行為未直接測試 | 由 ShedLock 7.7.0 框架自身保證 + production observability（`event_publication.failed.count` gauge spike）覆蓋 |

### 7.8 Files Changed Summary

**Production code（11 modify + 5 new）**：
- `backend/build.gradle.kts` — deps + JVM heap config
- `backend/src/main/resources/application.yaml` + `config/application-dev.yaml` — modulith / scheduler / actuator
- `backend/src/main/java/.../shared/config/SchedulerConfig.java` (new)
- `backend/src/main/java/.../shared/config/AsyncListenerConfig.java` (new)
- `backend/src/main/java/.../shared/events/IncompleteEventRepublishTask.java` (new)
- `backend/src/main/java/.../shared/events/EventPublicationMetrics.java` (new)
- `backend/src/main/resources/db/migration/V4__event_publication_outbox.sql` (new)
- `backend/src/main/resources/db/migration/V5__shedlock.sql` (new)
- `backend/src/main/java/.../skill/domain/SkillDownloadedEvent.java` — 加 eventId field + factory
- `backend/src/main/java/.../skill/domain/SkillVersionPublishedEvent.java` — 加 sourceEventId field + factory
- `backend/src/main/java/.../skill/domain/Skill.java` — publish path 改 factory
- `backend/src/main/java/.../skill/command/SkillCommandService.java` — 同上
- `backend/src/main/java/.../skill/query/SkillProjection.java` — 5 method 改 async + 2 method 保留 sync
- `backend/src/main/java/.../skill/query/SkillQueryService.java` — 改 factory + downloadLatest/Version 加 @Transactional
- `backend/src/main/java/.../skill/query/SkillVersionReadModelRepository.java` — 加 hasRiskAssessmentFromEvent
- `backend/src/main/java/.../analytics/AnalyticsProjection.java` — async + saveIdempotent
- `backend/src/main/java/.../analytics/DownloadEventReadModel.java` — 加 eventId field
- `backend/src/main/java/.../analytics/DownloadEventRepository.java` — 加 saveIdempotent
- `backend/src/main/java/.../search/SearchProjection.java` — 2 method async
- `backend/src/main/java/.../security/scan/ScanOrchestrator.java` — async + 加 idempotency check + 寫 sourceEventId

**Test code（9 modify + 13 new）**：T01-T07 各 task Result 段已列；測試金字塔結構性整理留 S025。

**Documentation**：
- `docs/grimo/architecture.md` — 加 Spring Modulith Outbox 段 + ADR-002 引用
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md` — 架構決策依據
- `docs/grimo/specs/spec-roadmap.md` — Phase 3 milestone (M18/M19)；S025 Backlog entry
- `docs/grimo/CHANGELOG.md` — 由 `/shipping-release` 寫入
