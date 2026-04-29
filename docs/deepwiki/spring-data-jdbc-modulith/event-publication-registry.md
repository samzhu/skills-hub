# Spring Modulith 2.0.x Event Publication 與模組機制

> 所有 source 引用對應 tag `2.0.6` 的 [`spring-projects/spring-modulith`](https://github.com/spring-projects/spring-modulith)。

---

## 1. `@ApplicationModuleListener` 註解定義

`spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/ApplicationModuleListener.java` lines 47-92：

```java
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
@TransactionalEventListener
@Documented
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ApplicationModuleListener {

    @AliasFor(annotation = Transactional.class, attribute = "readOnly")
    boolean readOnlyTransaction() default false;

    @AliasFor(annotation = EventListener.class, attribute = "id")
    String id() default "";

    @AliasFor(annotation = EventListener.class, attribute = "condition")
    String condition() default "";

    @AliasFor(annotation = Transactional.class, attribute = "propagation")
    Propagation propagation() default Propagation.REQUIRES_NEW;
}
```

source 確認的事實：

- **`@Async`** — listener 一定跑在獨立 thread（非 publish thread）
- **`@TransactionalEventListener` 無 `phase`** — 用 Spring 預設 `TransactionPhase.AFTER_COMMIT`。`PersistentApplicationEventMulticaster.TransactionalEventListeners` inner class（lines 296-298）的 filter 證實：`.filter(it -> it.getTransactionPhase().equals(TransactionPhase.AFTER_COMMIT))`
- **`@Transactional(propagation = REQUIRES_NEW)`** — listener 自己跑在**全新獨立 transaction** 內。listener TX 失敗不影響 publish TX（已 commit）

### 與 raw `@TransactionalEventListener(AFTER_COMMIT)` 對照

| 面向 | 純 `@TransactionalEventListener(AFTER_COMMIT)` | `@ApplicationModuleListener` |
|---|---|---|
| Phase | `AFTER_COMMIT`（顯式） | `AFTER_COMMIT`（預設） |
| Thread | caller thread | 獨立 async thread (`@Async`) |
| 自身 TX | 無（除非手動加） | `REQUIRES_NEW` 自動 |
| Registry 追蹤 | **無** wiring | 自動 wiring（`CompletionRegisteringAdvisor`） |

---

## 2. Event Publication Registry — schema 與狀態機

### Schema 檔案位置

`spring-modulith-events/spring-modulith-events-jdbc/src/main/resources/org/springframework/modulith/events/jdbc/schemas/`

`DatabaseType` enum line 161-162 確認：`return SCHEMA_ROOT + "/" + (legacy ? "v1" : "v2") + "/schema-" + value;` — 預設走 V2，可由 `spring.modulith.events.jdbc.use-legacy-structure=true` 退回 V1。

### V2 PostgreSQL DDL（預設）

```sql
-- spring-modulith-events-jdbc/.../schemas/v2/schema-postgresql.sql lines 1-16
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);
```

V1 schema（legacy）只有前 6 欄，缺 `status`、`completion_attempts`、`last_resubmission_date`。

### 狀態機

`EventPublication.Status` enum（`EventPublication.java` lines 130-156）：

```java
enum Status {
    PUBLISHED,    // row 已 INSERT，listener 還沒開始
    PROCESSING,   // listener 已 pickup，markProcessing() called
    COMPLETED,    // listener 成功，markCompleted() called
    FAILED,       // listener 拋例外，markFailed() called
    RESUBMITTED;  // 之前失敗，已重新排入 retry
}
```

SQL 語意：
- `completion_date IS NULL` → 未完成（status ∈ {PUBLISHED, PROCESSING, RESUBMITTED, FAILED}）
- `completion_date IS NOT NULL` → 完成（COMPLETED）

V1 用 `completion_date IS NULL` 唯一判斷「pending」；V2 多了 `status` 欄位允許更精細查詢。

`isCompleted()`（`EventPublication.java` line 89-91）：
```java
default boolean isCompleted() {
    return getCompletionDate().isPresent();
}
```

`markCompleted(Instant)`（`DefaultEventPublication.java` lines 112-116）：
```java
@Override
public void markCompleted(Instant instant) {
    this.completionDate = Optional.of(instant);
    this.status = Status.COMPLETED;
}
```

---

## 3. Publish 進入 Registry — INSERT 路徑

完整鏈路：

1. 業務 code 呼叫 `ApplicationEventPublisher.publishEvent(event)`
2. Spring 路由到 `PersistentApplicationEventMulticaster.multicastEvent()`：

```java
// PersistentApplicationEventMulticaster.java lines 103-119
@Override
public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
    var type = eventType == null ? ResolvableType.forInstance(event) : eventType;
    var listeners = getApplicationListeners(event, type);

    if (listeners.isEmpty()) {
        return;
    }

    new TransactionalEventListeners(listeners)
            .ifPresent(it -> storePublications(it, getEventToPersist(event)));

    for (ApplicationListener listener : listeners) {
        listener.onApplicationEvent(event);
    }
}
```

3. `storePublications()`（lines 227-234）map listener IDs → `PublicationTargetIdentifier` → `registry.get().store(event, identifiers)`：

```java
private void storePublications(Stream<TransactionalApplicationListener<ApplicationEvent>> listeners,
        Object eventToPersist) {
    var identifiers = listeners.map(TransactionalApplicationListener::getListenerId)
            .map(PublicationTargetIdentifier::of);
    registry.get().store(eventToPersist, identifiers);
}
```

4. `DefaultEventPublicationRegistry.store()`（lines 81-88）建立 `TargetEventPublication` 並對每個呼叫 `events.create(it)`：

```java
@Override
public Collection<TargetEventPublication> store(Object event,
        Stream<PublicationTargetIdentifier> listeners) {
    return listeners.map(it -> TargetEventPublication.of(event, it, clock.instant()))
        .peek(it -> LOGGER.debug(REGISTER, ...))
        .map(events::create)             // ← SQL INSERT 在此
        .map(inProgress::register)
        .toList();
}
```

5. `JdbcEventPublicationRepository.create()`（lines 175-189）執行 INSERT：

```java
@Override
@Transactional
public TargetEventPublication create(TargetEventPublication publication) {
    var serializedEvent = serializeEvent(publication.getEvent());
    operations.update(
        sqlStatementInsert,                          // INSERT INTO EVENT_PUBLICATION ...
        uuidToDatabase(publication.getIdentifier()),
        publication.getEvent().getClass().getName(),
        publication.getTargetIdentifier().getValue(),
        Timestamp.from(publication.getPublicationDate()),
        serializedEvent);
    return publication;
}
```

### Transactional 時序的關鍵

`create()` 帶 `@Transactional`（預設 propagation = `REQUIRED`）— **加入呼叫者的 transaction**。意味 INSERT 與業務 TX **共用同一個 transaction**，commit/rollback 同生共死。

**這就是 transactional outbox 模式的核心**：「event_publication row 存在 ⇔ 業務 TX 已 commit」。若業務 TX rollback，publication row 也 rollback。**至少一次投遞的物理基礎**。

---

## 4. Listener 包裝機制

機制有兩塊：

### Piece 1：`PersistentApplicationEventMulticaster`

替換 Spring 預設的 `SimpleApplicationEventMulticaster` bean，在 `publishEvent()` 分發點攔截，**在任何 listener 執行前**寫 publication row（§3 已述）。

### Piece 2：`CompletionRegisteringAdvisor`

AOP `AbstractPointcutAdvisor`（`CompletionRegisteringAdvisor.java` lines 50-227）的 pointcut 鎖定任何**帶 `@TransactionalEventListener` 且 phase = `AFTER_COMMIT`** 的方法（lines 66-76, 117-126）：

```java
this.pointcut = new AnnotationMatchingPointcut(null,
        TransactionalEventListener.class, true) {
    @Override
    public MethodMatcher getMethodMatcher() {
        return new CommitListenerMethodMatcher(super.getMethodMatcher());
    }
};
```

`CommitListenerMethodMatcher`：
```java
// lines 117-126
var annotation = AnnotatedElementUtils.findMergedAnnotation(method,
        TransactionalEventListener.class);
return annotation != null && annotation.phase().equals(TransactionPhase.AFTER_COMMIT);
```

### 攔截器邏輯

`CompletionRegisteringMethodInterceptor.invoke()`（lines 157-192）：

```java
@Override
public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Object result = null;
    var method = invocation.getMethod();
    var argument = invocation.getArguments()[0];

    registerStateTransition(method, argument, EventPublicationRegistry::markProcessing);

    try {
        result = invocation.proceed();

        if (result instanceof CompletableFuture<?> future) {
            return future
                .thenApply(it -> {
                    registerStateTransition(method, argument, EventPublicationRegistry::markCompleted);
                    return it;
                })
                .exceptionallyCompose(it -> {
                    handleFailure(method, argument, it);
                    return CompletableFuture.failedFuture(it);
                });
        }
    } catch (Throwable o_O) {
        handleFailure(method, argument, o_O);
        throw o_O;   // 重抛 — 例外不被吞掉
    }

    registerStateTransition(method, argument, EventPublicationRegistry::markCompleted);
    return result;
}
```

### Listener 失敗時

`handleFailure()`（lines 201-210）呼叫 `EventPublicationRegistry::markFailed`（`@Transactional(REQUIRES_NEW)` 操作）**並重抛**。Row 的 `status='FAILED'`、`completion_date` 維持 NULL。例外傳回 async executor。

`DefaultEventPublicationRegistry.markFailed()`（lines 146-152）用自己的 `REQUIRES_NEW`，確保失敗紀錄 durable：

```java
@Override
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markFailed(Object event, PublicationTargetIdentifier targetIdentifier) {
    propagateStateTransitionAndConclude(event, targetIdentifier,
            it -> events.markFailed(it.getIdentifier()), () -> {});
    inProgress.unregister(event, targetIdentifier);
}
```

---

## 5. Retry 與 Republish

### `IncompleteEventPublications` API

`spring-modulith-events-api/.../IncompleteEventPublications.java` lines 26-49：

```java
public interface IncompleteEventPublications {
    void resubmitIncompletePublications(Predicate<EventPublication> filter);
    void resubmitIncompletePublicationsOlderThan(Duration duration);
    void resubmitIncompletePublications(ResubmissionOptions options);   // 2.0+
}
```

`PersistentApplicationEventMulticaster` 實作此 interface。`resubmitIncompletePublications(filter)` 委派給 `registry.get().processIncompletePublications(filter, this::invokeTargetListener, null)`。每筆會先 `events.markResubmitted(it.getIdentifier(), clock.instant())`（樂觀鎖式 update），再重新 publish。

### 應用重啟時自動 republish

`PersistentApplicationEventMulticaster.afterSingletonsInstantiated()` lines 175-187：

```java
@Override
public void afterSingletonsInstantiated() {
    var env = environment.get();
    Boolean republishOnRestart = Optional.ofNullable(
            env.getProperty(REPUBLISH_ON_RESTART, Boolean.class))
        .orElseGet(() -> env.getProperty(REPUBLISH_ON_RESTART_LEGACY, Boolean.class));

    if (!Boolean.TRUE.equals(republishOnRestart)) {
        return;
    }
    resubmitIncompletePublications(__ -> true);
}
```

Property：`spring.modulith.events.republish-outstanding-events-on-restart`。**預設 `false` — 不自動重投**。

### 內建週期 retry？

**沒有**。框架不為 `resubmitIncompletePublications` wire `@Scheduled` task。應用必須自己排程：

```java
@Scheduled(fixedDelay = 60_000)
void retryIncomplete() {
    incompleteEventPublications
        .resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
}
```

### 自動排程的東西：staleness monitor

2.0 新增 `StalenessMonitorConfiguration.java` lines 60-70 — 註冊 `ScheduledTaskRegistrar` fixed-delay task 呼叫 `registry.markStalePublicationsFailed(staleness)`：把卡住的 PROCESSING/PUBLISHED/RESUBMITTED row **標為 FAILED**（**不**重試）。

`StalenessProperties.java`：

```java
@ConfigurationProperties("spring.modulith.events.staleness")
// check 間隔預設 1 分鐘：
this.checkIntervall = checkIntervall == null ? Duration.ofMinutes(1) : checkIntervall;

boolean monitorStaleness() {
    return !published.equals(Duration.ZERO)
        || !processing.equals(Duration.ZERO)
        || !resubmitted.equals(Duration.ZERO);
}
```

至少設一個 staleness duration 為非 zero，monitor 才啟用。

---

## 6. Externalization (`@Externalized`)

`spring-modulith-events-api/.../Externalized.java` lines 33-52：

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface Externalized {
    @AliasFor("value") String value() default "";
    @AliasFor("target") String target() default "";
}
```

放在 **domain event class** 上，宣告事件需 forward 到外部 message infrastructure。`target` 屬性指定 logical destination（如 `"orders.created"` 對應 Kafka topic / AMQP exchange）。

支援 broker：
- Apache Kafka — `spring-modulith-events-kafka`
- RabbitMQ/AMQP — `spring-modulith-events-amqp`
- JMS — `spring-modulith-events-jms`
- AWS SQS — `spring-modulith-events-aws-sqs`

外部化事件**走同一條 Publication Registry** — externalization adapter 本身就是 `@TransactionalEventListener`，會在 `event_publication` 拿到一筆紀錄。Skills Hub 目前不需要外部 broker，此功能保留為未來選項。

---

## 7. 模組邊界 — 發現與檢驗

### 套件即模組

模組邊界**主要由套件結構**決定。`@Modulith` 標記的 root class，其下每個直接子 package 即為一個模組，**無需註解**。

`ApplicationModules.of(MyApplication.class)`（`ApplicationModules.java` ~line 270-290）：

```java
public static ApplicationModules of(Class<?> modulithType) {
    return of(modulithType, alwaysFalse());
}

public static ApplicationModules of(Class<?> modulithType,
        DescribedPredicate<? super JavaClass> ignored) {
    Assert.notNull(modulithType, "Modulith root type must not be null!");
    Assert.notNull(ignored, "Predicate to describe ignored types must not be null!");
    return of(CacheKey.of(modulithType, ignored, IMPORT_OPTION));
}
```

內部用 **ArchUnit `ClassFileImporter`** 掃 bytecode（line 46 import），**無需 Spring context**。`IMPORT_OPTION = DoNotIncludeTests`（line 62）。結果快取在 `ConcurrentHashMap<CacheKey, ApplicationModules>`（line 60）。

### `@ApplicationModule` 註解（選用）

`@org.springframework.modulith.ApplicationModule` 是**選用**註解，放在 `package-info.java` 上自訂 metadata（允許依賴清單、display name 等）。基本模組偵測不需要它。

`@NamedInterface` 可放在 sub-package 的 `package-info.java`，宣告該套件為某個 named API subset。

### Skills Hub 對應

`io.github.samzhu.skillshub` 下的 `shared` / `skill` / `security` / `search` / `analytics` / `storage` 自動成為 6 個模組。模組間目前依賴 `@EventListener` 通訊；S023 升級為 `@ApplicationModuleListener`。

---

## 8. Observability hooks

### 主要 metric

`spring-modulith-observability/.../support/ModulithMetrics.java` lines 27-60：

```java
enum ModulithMetrics implements MeterDocumentation {
    EVENTS {
        @Override public String getName() { return "modulith.events.processed"; }
        @Override public Meter.Type getType() { return Meter.Type.COUNTER; }
        @Override public KeyName[] getKeyNames() { return LowKeys.values(); }
    };

    enum LowKeys implements KeyName {
        EVENT_TYPE { public String asString() { return "event.type"; } },
        MODULE_NAME { public String asString() { return "module.name"; } }
    }
}
```

Counter 在 `ModuleEventListener.onApplicationEvent()` lines 100-105 增加：

```java
Counter.builder(ModulithMetrics.EVENTS.getName())
    .tags("event.type", payloadType.getSimpleName())
    .tags("module.name", moduleByType.getDisplayName())
    .register(registry).increment();
```

每個 `(event.type, module.name)` 組合一個 counter。

### 缺失：未完成發佈的 gauge

**沒有**內建 gauge 監控 incomplete / failed publication 數量。Skills Hub 若要儀表板，必須自寫 — 查詢 `IncompleteEventPublications` / `FailedEventPublications` 註冊 custom gauge。實作範例見 [design-decisions.md §3 陷阱 7](./design-decisions.md)。

### `ModulithEventMetrics` SPI

允許注入 counter customizer（`ModulithEventMetricsCustomizer` bean）。也支援 OpenTelemetry tracing（`ModulithObservations` per-event-per-module span）。

---

## 9. Skills Hub 關鍵發現

### 可靠性保證 — 保護什麼，不保護什麼

**保護**：應用在「業務 TX commit 後 / listener 完成前」crash → publication row 仍存在於 `event_publication`（`status='PUBLISHED'`、`completion_date IS NULL`）。重啟後（`republish-outstanding-events-on-restart=true` 或排程 retry）會被重投。

**不保護**：

1. **Listener 冪等性**：完全是開發者責任。Registry 保證 at-least-once，**不**保證 exactly-once。listener 可能被同一筆 publication 呼叫多次（如 markCompleted 前 crash）。Skills Hub 的 `@ApplicationModuleListener` 必須冪等。
2. **JVM 在 INSERT 前 crash**：若 JVM 在「業務 TX commit 與 `publishEvent()` 呼叫之間」死掉，無 row → 事件**永遠遺失**。但這在 Modulith 範圍外。
3. **Listener 無 `@Transactional`**：`markFailed` 仍記錄失敗，但 listener 副作用無 transactional rollback 保護。

### `spring-modulith-starter-jdbc` 自動建表？

**否**。

`JdbcEventPublicationAutoConfiguration.java` lines 74-80：

```java
@Bean
@ConditionalOnProperty(
    name = "spring.modulith.events.jdbc.schema-initialization.enabled",
    havingValue = "true")
DatabaseSchemaInitializer databaseSchemaInitializer(...) { ... }
```

`DatabaseSchemaInitializer` 只在 `spring.modulith.events.jdbc.schema-initialization.enabled=true` 時註冊，預設 `false`。Skills Hub 必須**手動加 Flyway migration**。

完整 V4 DDL 見 [design-decisions.md §4](./design-decisions.md)。

### 預設 vs 必要設定（retry / republish）

| 行為 | 預設 | 必要設定 |
|---|---|---|
| 重啟時重投 | 停用 | `spring.modulith.events.republish-outstanding-events-on-restart=true` |
| 週期重試 incomplete | **無內建** | 自己提供 `@Scheduled` bean 呼叫 `IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(...)` |
| Staleness monitor（標 stuck row 為 FAILED） | 停用 | 設 `spring.modulith.events.staleness.published/processing/resubmitted` 任一為非 zero `Duration` |
| Staleness check interval | 1 分鐘（啟用後） | `spring.modulith.events.staleness.check-intervall` |
