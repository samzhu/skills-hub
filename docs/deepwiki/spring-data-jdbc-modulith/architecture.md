# 核心架構

## 兩個專案的關係

```
┌─────────────────────────────────────────────────────────────┐
│  Application code (Skills Hub)                               │
│  ┌────────────────────┐    ┌────────────────────────────┐   │
│  │  Skill aggregate   │    │ @ApplicationModuleListener │   │
│  │  extends           │    │ on(SkillCreated event)     │   │
│  │  AbstractAggregate │    └────────────▲───────────────┘   │
│  │  Root              │                 │                   │
│  └─────────┬──────────┘                 │                   │
│            │ repo.save()                │                   │
└────────────┼─────────────────────────────┼───────────────────┘
             │                             │
┌────────────▼──────────────┐  ┌──────────▲────────────────────┐
│  Spring Data JDBC 4.0.x   │  │  Spring Modulith 2.0.x        │
│  - JdbcAggregateTemplate  │  │  - PersistentApplication      │
│  - WritingContext         │  │    EventMulticaster           │
│  - DataAccessStrategy     │  │  - EventPublicationRegistry   │
│  - EventPublishingProxy ──┼──┼─▶ ApplicationEventPublisher   │
│    (Spring Data Commons)  │  │    intercepted               │
└────────────┬──────────────┘  └──────────┬────────────────────┘
             │                             │
             │ SQL (entity)                │ INSERT (event_publication)
             ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│  PostgreSQL — same transaction (transactional outbox)        │
└─────────────────────────────────────────────────────────────┘
```

整合點是 Spring `ApplicationEventPublisher`：Spring Data 在 `repo.save()` 後呼叫它；Spring Modulith 把 multicaster 換成 `PersistentApplicationEventMulticaster`，攔截後寫入 `event_publication` 表。**兩者沒有編譯期耦合**，靠 Spring 容器的 ApplicationEventMulticaster bean 替換點銜接。

---

## Spring Data JDBC 目錄與分層

### 主要 sub-modules

```
spring-data-relational/
├── spring-data-relational/        ← 共用：mapping context、SQL 抽象、value 物件
├── spring-data-jdbc/              ← JDBC 實作：JdbcAggregateTemplate、AccessStrategy、Repository
├── spring-data-r2dbc/             ← R2DBC 實作（與 jdbc 平行；Skills Hub 不用）
└── spring-data-jdbc-distribution/ ← 包裝模組
```

### 分層架構

```
Repository interface (CrudRepository<Skill, String>)
  ↓ (proxy by RepositoryFactorySupport + EventPublishingRepositoryProxyPostProcessor)
SimpleJdbcRepository
  ↓
JdbcAggregateTemplate.save(instance)
  ↓ (decides INSERT vs UPDATE via PersistentEntity.isNew())
WritingContext.insert() / .update()
  ↓ (generates DbAction list: InsertRoot, Insert(child), Delete(child)…)
AggregateChangeExecutor.executeSave()
  ↓ (dispatches DbActions)
JdbcAggregateChangeExecutionContext
  ↓ (calls accessStrategy)
DataAccessStrategy (DefaultDataAccessStrategy)
  ↓
NamedParameterJdbcOperations / JdbcOperations
  ↓
JDBC driver → DB
```

**關鍵設計**：`WritingContext` 把「儲存一個 aggregate」拆成 declarative 的 `DbAction` 列表（DDD 操作 → DB 操作 mapping），`AggregateChangeExecutor` 順序執行。這個分層讓未來改寫 SQL 生成策略（例如改成 upsert）有清楚切入點，但目前所有 `@MappedCollection` 子集合都走「DELETE + INSERT all」。

### 核心類別

| 類別 | 職責 | 檔案 |
|---|---|---|
| `JdbcAggregateTemplate` | aggregate-level CRUD 入口；負責 isNew/version 處理 | `spring-data-jdbc/.../JdbcAggregateTemplate.java` |
| `WritingContext` | 把 aggregate root + 子集合轉為 `DbAction` 列表 | `spring-data-relational/.../WritingContext.java` |
| `AggregateChangeExecutor` | 順序執行 `DbAction`；populate 自動生成的 ID | `spring-data-jdbc/.../AggregateChangeExecutor.java` |
| `DataAccessStrategy` | SQL 抽象（INSERT/UPDATE/DELETE/SELECT） | `spring-data-jdbc/.../DataAccessStrategy.java` |
| `AbstractAggregateRoot<A>` | DDD aggregate root base；管理待發佈 events | `spring-data-commons/.../AbstractAggregateRoot.java` |
| `EventPublishingRepositoryProxyPostProcessor` | proxy `save/delete` → 收集 events → publish | `spring-data-commons/.../EventPublishingRepositoryProxyPostProcessor.java` |
| `BasicRelationalPersistentEntity` | 將 `@Table` / `@Id` / `@Column` 解析為 mapping metadata | `spring-data-relational/.../BasicRelationalPersistentEntity.java` |
| `DialectResolver` | runtime 連 DB 偵測 SQL dialect | `spring-data-jdbc/.../dialect/DialectResolver.java` |

詳細 source-level 分析見 [aggregate-design.md](./aggregate-design.md)。

---

## Spring Modulith 目錄與分層

### 主要 sub-modules

```
spring-modulith/
├── spring-modulith-core/                   ← ApplicationModules、ArchUnit 整合、模組驗證
├── spring-modulith-events/
│   ├── spring-modulith-events-api/         ← @ApplicationModuleListener、EventPublication 介面
│   ├── spring-modulith-events-core/        ← PersistentApplicationEventMulticaster、CompletionRegisteringAdvisor
│   ├── spring-modulith-events-jdbc/        ← JdbcEventPublicationRepository、schema 檔案
│   ├── spring-modulith-events-jpa/         ← JPA 實作（Skills Hub 不用）
│   ├── spring-modulith-events-mongodb/     ← MongoDB 實作
│   ├── spring-modulith-events-kafka/       ← @Externalized → Kafka
│   ├── spring-modulith-events-amqp/        ← @Externalized → RabbitMQ
│   └── spring-modulith-events-jms/
├── spring-modulith-observability/          ← Micrometer / OpenTelemetry 整合
├── spring-modulith-test/                   ← @ApplicationModuleTest、Scenario API
└── spring-modulith-starters/
    ├── spring-modulith-starter-core
    ├── spring-modulith-starter-jdbc        ← Skills Hub S023 需引入
    └── spring-modulith-starter-test
```

### 分層架構（事件路徑）

```
Application code: ApplicationEventPublisher.publishEvent(domainEvent)
  ↓
PersistentApplicationEventMulticaster.multicastEvent()
  ↓ (filter listeners with @TransactionalEventListener AFTER_COMMIT)
TransactionalEventListeners.ifPresent(it -> storePublications(it, event))
  ↓
DefaultEventPublicationRegistry.store()
  ↓ (creates TargetEventPublication per listener)
JdbcEventPublicationRepository.create()
  ↓ (@Transactional REQUIRED — joins business TX)
INSERT INTO event_publication (...) VALUES (...)
  ↓
[Business TX commits — together with entity write]
  ↓
[AFTER_COMMIT phase — separate async thread per @ApplicationModuleListener]
CompletionRegisteringMethodInterceptor.invoke()
  ↓ (markProcessing → run listener → markCompleted/markFailed)
listener method body
```

### 核心類別

| 類別 | 職責 | 檔案 |
|---|---|---|
| `@ApplicationModuleListener` | 組合註解：`@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener` | `spring-modulith-events-api/.../ApplicationModuleListener.java` |
| `PersistentApplicationEventMulticaster` | 替換 Spring 預設 multicaster，攔截 `publishEvent()` 寫入 outbox | `spring-modulith-events-core/.../PersistentApplicationEventMulticaster.java` |
| `EventPublicationRegistry` | outbox CRUD：store / markProcessing / markCompleted / markFailed | `spring-modulith-events-core/.../EventPublicationRegistry.java` |
| `JdbcEventPublicationRepository` | JDBC 實作：INSERT/UPDATE `event_publication` 表 | `spring-modulith-events-jdbc/.../JdbcEventPublicationRepository.java` |
| `CompletionRegisteringAdvisor` | AOP advisor 包裝 `@TransactionalEventListener AFTER_COMMIT` 方法 | `spring-modulith-events-core/.../CompletionRegisteringAdvisor.java` |
| `IncompleteEventPublications` | 公開 API：retry incomplete publications | `spring-modulith-events-api/.../IncompleteEventPublications.java` |
| `ApplicationModules` | ArchUnit-based 模組發現與驗證 | `spring-modulith-core/.../ApplicationModules.java` |
| `StalenessMonitorConfiguration` | 自動排程 — 把 stuck PROCESSING/RESUBMITTED 標為 FAILED | `spring-modulith-events-core/.../StalenessMonitorConfiguration.java` |

詳細 source-level 分析見 [event-publication-registry.md](./event-publication-registry.md)。

---

## 持久化模型

### Spring Data JDBC — Aggregate ↔ Tables

| 概念 | 對應實作 |
|---|---|
| Aggregate root | 一個 `@Table` annotated POJO，主表一行 |
| Aggregate 子實體 (`@MappedCollection`) | 子表，FK 指回主表 PK；無獨立 repository |
| 跨 aggregate reference | `AggregateReference<T, ID>` 或 plain ID 欄位（**不**用 `@MappedCollection`） |
| Embedded value object | `@Embedded` — 同表行，欄位 prefix 區分 |
| 樂觀鎖 | `@Version` 欄位（root 才有效，子集合無保護） |

### Spring Modulith — Outbox Schema

`event_publication` 表為 transactional outbox 的核心結構：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `id` | UUID | publication 唯一識別 |
| `listener_id` | TEXT | 哪個 listener 應處理（`@EventListener.id` 或 method 簽名） |
| `event_type` | TEXT | event class FQN |
| `serialized_event` | TEXT | 完整 event 物件（預設 Jackson JSON） |
| `publication_date` | TIMESTAMP WITH TIME ZONE | INSERT 時間 |
| `completion_date` | TIMESTAMP WITH TIME ZONE | listener 成功完成時間（NULL = 未完成） |
| `status` | TEXT | `PUBLISHED` / `PROCESSING` / `COMPLETED` / `FAILED` / `RESUBMITTED`（V2 schema） |
| `completion_attempts` | INT | 嘗試次數（V2） |
| `last_resubmission_date` | TIMESTAMP WITH TIME ZONE | 最後一次 retry 時間（V2） |

**V1 vs V2**：V1 schema（legacy）只有前 6 欄；V2（2.0.x 預設）加了 `status` / `completion_attempts` / `last_resubmission_date`。Skills Hub 使用 V2。

完整 DDL 與索引設計見 [event-publication-registry.md §2](./event-publication-registry.md) 與 [design-decisions.md §4 V4 Flyway migration](./design-decisions.md)。

---

## 模組邊界（Spring Modulith 特色）

### 套件即模組

```java
// MyApp.java (root package: com.example.app)
@SpringBootApplication
@Modulith
public class MyApp { … }

// 自動辨識的模組：
// com.example.app.skill   → "skill" module
// com.example.app.search  → "search" module
// com.example.app.shared  → "shared" module（被引用 ok，但本身不能被算為模組）
```

無註解情況下，**根 package 下每個直接子 package** 即為一個模組。`@ApplicationModule` 註解放在 `package-info.java` 上可細調（命名、display name、允許依賴清單），但不必填。

### 邊界檢驗

```java
@Test
void verifyModuleStructure() {
    var modules = ApplicationModules.of(MyApp.class);
    modules.verify();   // ArchUnit 檢驗：模組間只能透過 API 套件互動
}
```

底層用 ArchUnit `ClassFileImporter` 掃 bytecode，無需啟動 Spring context。預設 `IMPORT_OPTION = DoNotIncludeTests`。

### Skills Hub 對應

`io.github.samzhu.skillshub` 下的：`shared`、`skill`、`security`、`search`、`analytics`、`storage` 自動成為 6 個模組。模組間依賴目前是 event-driven（透過 `@EventListener`），S023 後會升級為 `@ApplicationModuleListener`。

---

## 兩專案的依賴關係

```
spring-modulith-starter-jdbc
  ├─ spring-modulith-events-jdbc
  │   ├─ spring-modulith-events-core
  │   │   ├─ spring-modulith-events-api
  │   │   └─ spring-modulith-core
  │   └─ spring-jdbc                    ← 共用 JdbcTemplate
  └─ spring-tx                          ← 共用 TX abstraction

spring-boot-starter-data-jdbc
  ├─ spring-data-jdbc
  │   ├─ spring-data-relational
  │   ├─ spring-data-commons            ← AbstractAggregateRoot 在這
  │   └─ spring-jdbc                    ← 同上
  └─ HikariCP / DataSource autoconfig
```

關鍵：兩者**共用 `spring-jdbc` 與 `spring-tx`**，所以 outbox INSERT 與業務 entity INSERT 可以在同一條 `Connection` / 同一個 `@Transactional` boundary 內。這是 transactional outbox 模式可行的物理基礎。
