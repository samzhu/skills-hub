# Spring Data JDBC 4.0.x 聚合設計深度分析

> 所有 source 引用對應 tag `4.0.5` 的 [`spring-projects/spring-data-relational`](https://github.com/spring-projects/spring-data-relational) 與 main 分支的 [`spring-projects/spring-data-commons`](https://github.com/spring-projects/spring-data-commons)（commons 在 4.x 期間 functionally 等價於 main）。

---

## 1. Aggregate 註解

### `@Table`

`spring-data-relational/src/main/java/org/springframework/data/relational/core/mapping/Table.java`

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Inherited
public @interface Table {
    @AliasFor("name") String value() default "";   // 表名，支援 SpEL
    @AliasFor("value") String name() default "";
    String schema() default "";                    // 顯式 schema prefix
}
```

`@Table` 是 type-level 註解。實際綁定到 `SqlIdentifier` 在 `BasicRelationalPersistentEntity` 建構子內（lines 70-89）：若 `table.value()` 非空則用作 quoted identifier，否則由 `NamingStrategy.getTableName(type)` 推導。值內可含 SpEL 表達式（`PARSER.parse()` 偵測）。

`@Id` 來自 `org.springframework.data.annotation.Id`（spring-data-commons），`@Column` 是 `org.springframework.data.relational.core.mapping.Column`，`@Version` 是 `org.springframework.data.annotation.Version`。三者透過 `BasicPersistentEntity`（superclass）掃描，由 `BasicRelationalPersistentProperty` 解讀。

### `@MappedCollection`

`spring-data-relational/src/main/java/org/springframework/data/relational/core/mapping/MappedCollection.java`

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE })
public @interface MappedCollection {
    String idColumn() default "";    // 子表 FK 欄位（指回 aggregate root PK）
    String keyColumn() default "";   // 僅 List/Map 才用（list index 或 map key）
}
```

`@MappedCollection` 標記 `List` / `Set` / `Map` 欄位為 1-to-many 關聯，存於**獨立子表**。子集合的生命週期完全由 `WritingContext` 控制（見 §2），註解本身不含邏輯。

### `@Embedded`

`spring-data-relational/src/main/java/org/springframework/data/relational/core/mapping/Embedded.java`

```java
public @interface Embedded {
    OnEmpty onEmpty();               // USE_NULL or USE_EMPTY
    String prefix() default "";      // 欄位名 prefix
    enum OnEmpty { USE_NULL, USE_EMPTY }
}
```

`@Embedded` 把 value object 的欄位**展開到 parent 同一個表行**（無 join、無子表）。當 nested 型別**沒有獨立 identity** 且所有欄位適合存於 parent 表時用它；需要獨立子表時用 `@MappedCollection`。

### `@Version`

樂觀鎖在 `JdbcAggregateTemplate.prepareVersionForInsert()`（lines 560-567）與 `prepareVersionForUpdate()`（lines 569-577）處理：

- INSERT：版本設為 `0`（primitive 型別則 `1`）
- UPDATE：`RelationalEntityVersionUtils.getVersionNumberFromEntity()` 讀當前值 +1，舊值傳入 `DbAction.UpdateRoot.previousVersion`
- 樂觀鎖檢查：`JdbcAggregateChangeExecutionContext.updateWithVersion()`（lines 311-314） — 若 `accessStrategy.updateWithVersion()` 回 `false`（0 rows affected），呼叫 `OptimisticLockingUtils.updateFailed()` 拋 `OptimisticLockingFailureException`

---

## 2. Save 生命週期 — 含 `@MappedCollection` 子集合

### Entry：`JdbcAggregateTemplate.save()`

```java
// JdbcAggregateTemplate.java lines 208-215
@Override
public <T> T save(T instance) {
    Assert.notNull(instance, "Aggregate instance must not be null");
    verifyIdProperty(instance);
    return performSave(new EntityAndChangeCreator<>(instance, changeCreatorSelectorForSave(instance)));
}
```

`changeCreatorSelectorForSave()`（lines 548-550）用 `persistentEntity.isNew(instance)` 決定路由：
- 若 new → `createInsertChange`
- 否則 → `createUpdateChange`

### INSERT 路徑：`WritingContext.insert()`

```java
// WritingContext.java lines 67-71
void insert() {
    setRootAction(new DbAction.InsertRoot<>(root, rootIdValueSource));
    insertReferenced().forEach(aggregateChange::addAction);
}
```

`insertReferenced()` 走訪所有 relational paths（建構時用 `RelationalPredicates::isRelation` 列出），對每個 `@MappedCollection` 元素生成 `DbAction.Insert`。**INSERT 路徑不發 DELETE**。

### UPDATE 路徑：`WritingContext.update()` — DELETE-then-INSERT 證據

```java
// WritingContext.java lines 78-83
void update() {
    setRootAction(new DbAction.UpdateRoot<>(root, previousVersion));
    deleteReferenced().forEach(aggregateChange::addAction);   // ① DELETE 全部
    insertReferenced().forEach(aggregateChange::addAction);   // ② INSERT 全部
}
```

`deleteReferenced()`（lines 149-164）：

```java
private List<DbAction<?>> deleteReferenced() {
    List<DbAction<?>> deletes = new ArrayList<>();
    paths.forEach(path -> deletes.add(0, deleteReferenced(path)));
    return deletes;
}

private DbAction.Delete<?> deleteReferenced(
        PersistentPropertyPath<RelationalPersistentProperty> path) {
    Object id = context.getRequiredPersistentEntity(entityType)
        .getIdentifierAccessor(root).getIdentifier();
    Assert.state(id != null, "Id must not be null");
    return new DbAction.Delete<>(id, path);
}
```

每個 relation path 都生成一個 `DbAction.Delete`。意思是：對 `@MappedCollection Set<AclEntry>` 註解的欄位，**每次 `repository.save(root)` 都會發出 `DELETE … WHERE root_id = ?` 給 `acl_entries` 表，然後對當前集合每個元素發一個 INSERT**。

### Execution：`AggregateChangeExecutor` dispatch

```java
// AggregateChangeExecutor.java lines 54-61
<T> List<T> executeSave(AggregateChange<T> aggregateChange) {
    JdbcAggregateChangeExecutionContext executionContext =
        new JdbcAggregateChangeExecutionContext(converter, accessStrategy);
    aggregateChange.forEachAction(action -> execute(action, executionContext));
    return executionContext.populateIdsIfNecessary();
}
```

dispatch（lines 76-107）match 各 `DbAction` subtype：
- `DbAction.Delete` → `executeDelete` → `accessStrategy.delete(rootId, propertyPath)`（一條 DELETE by FK）
- `DbAction.Insert` → `executeInsert` → `accessStrategy.insert(entity, type, parentKeys, idValueSource)`（一條 INSERT per child）
- `DbAction.BatchInsert` → `executeBatchInsert` → `accessStrategy.insert(subjects, type, batchValue)`（batch INSERT）

**沒有 UPSERT、沒有 diff、沒有「只更新有變的子實體」**。框架永遠刪光重插。

### 對高頻寫集合（Skills Hub ACL）的後果

500 entry 的 `Set<AclEntry>` 改一個 entry 後 `repo.save(skill)`：

1. `DELETE FROM acl_entry WHERE skill_id = ?` — 刪 500 行
2. 500 條獨立 `INSERT INTO acl_entry …`（或一條 batch insert 含 500 row）

每次 save 是 O(N) 寫入，不論真正改了幾個。`saveAll()` 在 4.0 用 `BatchInsert`（透過 `SaveBatchingAggregateChange`）batch 化 INSERT，但**語意仍然是「全刪 + 全插」**。

對 ACL 高頻變更場景：
- **規模成本**：500 entry → 500 inserts per save
- **Index thrashing**：反覆 DELETE + INSERT 讓子表 index cache 失效
- **無冪等性**：若子實體用 `@Id GENERATED`，每次 re-save 都產生新 ID
- **Optimistic locking 不保護子實體**：`@Version` 只防 root，子表行無版本檢查

**緩解方案**：
1. 自訂 `DataAccessStrategy` 跳過 delete-step（複雜，不推薦）
2. **把高頻集合移出 aggregate**，獨立 aggregate + repository，用 `JdbcAggregateTemplate.insert()` / `deleteById()` 顯式控制（推薦）
3. 改成 JSONB 欄位（一行 UPDATE），如 Skills Hub 現行 `acl_entries jsonb` 設計

---

## 3. `AbstractAggregateRoot` + `@DomainEvents` 機制

### `AbstractAggregateRoot` source

`spring-data-commons/src/main/java/org/springframework/data/domain/AbstractAggregateRoot.java`

```java
// lines 37-71
public class AbstractAggregateRoot<A extends AbstractAggregateRoot<A>> {

    private transient final @Transient List<Object> domainEvents = new ArrayList<>();

    protected <T> T registerEvent(T event) {
        Assert.notNull(event, "Domain event must not be null");
        this.domainEvents.add(event);
        return event;
    }

    @AfterDomainEventPublication
    protected void clearDomainEvents() {
        this.domainEvents.clear();
    }

    @DomainEvents
    protected Collection<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
}
```

關鍵：
- `domainEvents` field 是 `transient` + `@Transient` — 不被 Spring Data 持久化
- `registerEvent()` 是 protected — aggregate method 內部呼叫
- `@DomainEvents` 標記的 method 在 `repo.save()` 後被 invocation interceptor 呼叫
- `@AfterDomainEventPublication` 標記的 method 在 events publish 完後被呼叫（清空 list）

### `EventPublishingRepositoryProxyPostProcessor` — 攔截器

`spring-data-commons/src/main/java/org/springframework/data/repository/core/support/EventPublishingRepositoryProxyPostProcessor.java`

```java
// lines 104-118 (EventPublishingMethodInterceptor.invoke)
@Override
public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Object result = invocation.proceed();   // ① SQL 執行先發生

    if (!isEventPublishingMethod(invocation.getMethod())) {
        return result;
    }

    Iterable<?> arguments = asIterable(invocation.getArguments()[0], invocation.getMethod());
    eventMethod.publishEventsFrom(arguments, publisher);   // ② SQL 之後 publish events

    return result;
}
```

**重要事實**：`invocation.proceed()` 先跑 → SQL 已 commit 到 connection → 然後才呼叫 `publishEventsFrom`。**事件在 DB 寫入「之後」publish，但仍在「同一執行 thread」內**。

是否在同一 transaction 取決於 TX boundary 設定；預設 Spring Data JDBC 把 repository save 包在 `@Transactional` 內，所以 SQL commit 與 event publish **都在同一個 TX boundary 內**。`@TransactionalEventListener(phase = AFTER_COMMIT)` 標記的 listener 會在 TX commit **後** fire。

### 攔截器如何掛載

`EventPublishingRepositoryProxyPostProcessor` 註冊在 `RepositoryFactorySupport`（spring-data-commons），對任何**domain type 有 `@DomainEvents` 標記 method 的 repository** 自動套用。對 JDBC，`JdbcRepositoryFactoryBean` 繼承 `TransactionalRepositoryFactoryBeanSupport`，後者呼叫 `RepositoryFactorySupport.addRepositoryProxyPostProcessor` 把 event publisher 自動 wire 進去。**開發者無需手動配置**。

---

## 4. `Persistable` / `isNew()` 偵測

### `changeCreatorSelectorForSave` — new vs existing 分支

`JdbcAggregateTemplate.java`, lines 548-550：

```java
private <T> AggregateChangeCreator<T> changeCreatorSelectorForSave(T instance) {
    return context.getRequiredPersistentEntity(instance.getClass()).isNew(instance)
        ? entity -> createInsertChange(prepareVersionForInsert(entity))
        : entity -> createUpdateChange(prepareVersionForUpdate(entity));
}
```

`isNew()` 委派給 `BasicPersistentEntity.isNew()`（spring-data-commons）。預設規則：**`@Id` 欄位為 `null`（或 primitive 的 `0` / `0L`）即視為 new**。若 domain type 實作 `Persistable<ID>`，自訂 `isNew()` 覆蓋預設。

### `IdValueSource.forInstance` — 子實體層級的 new 偵測

`spring-data-relational/src/main/java/org/springframework/data/relational/core/conversion/IdValueSource.java`, lines 47-67：

```java
public static <T> IdValueSource forInstance(Object instance,
        RelationalPersistentEntity<T> persistentEntity) {
    RelationalPersistentProperty idProperty = persistentEntity.getIdProperty();
    if (idProperty != null && idProperty.hasSequence()) {
        return IdValueSource.PROVIDED;           // @Sequence 優先
    }
    Object idValue = persistentEntity.getIdentifierAccessor(instance).getIdentifier();
    if (idProperty == null) return IdValueSource.NONE;
    boolean idPropertyValueIsSet = idValue != null &&
        (idProperty.getType() != int.class || !idValue.equals(0)) &&
        (idProperty.getType() != long.class || !idValue.equals(0L));
    return idPropertyValueIsSet ? IdValueSource.PROVIDED : IdValueSource.GENERATED;
}
```

`IdValueSource` 用於 `DbAction.Insert` 子實體判斷。`GENERATED` → DB 生成 ID，由 `populateIdsIfNecessary()` 寫回 in-memory 物件。

### `@Version` 與 `isNew()` 的互動

`@Version` **不影響** `isNew()` 的判斷。一個 non-null `@Id` + null `@Version` → `isNew()` 視為 existing → 走 update 路徑。`prepareVersionForUpdate`（lines 569-577）讀 null 版本當 `0`，更新後設為 `1`。

**陷阱**：手動建構 entity 時若 `@Id` 非 null 但忘記設 version，update 路徑會嘗試 `WHERE id = ? AND version = 0`，DB 行的版本若非 0 → 拋 `OptimisticLockingFailureException`。**唯一安全的「new entity」路徑是讓 `@Id` 為 null（或 0）讓 `isNew()` 回 true**。

### Skills Hub `Persistable.isNew() == true` 慣用

當前 `SkillReadModel.java:62-65`：

```java
@Override
public boolean isNew() {
    return true;   // projection 透過 save() 只用於建立新 row
}
```

這個慣用避開 isNew 預設邏輯：read model 的 `id` 是預先決定的（aggregate ID），但 projection 的 save 永遠是 INSERT。實作 `Persistable<String>` 並覆寫 `isNew()` 為常數 `true` 是**正確的 escape hatch**。但 S023 改成充血聚合後，`Skill` 自己會被 INSERT/UPDATE 區分，這個 trick 不能套用。

---

## 5. Custom Converters

### `AbstractJdbcConfiguration.userConverters()` + `jdbcCustomConversions()`

`spring-data-jdbc/src/main/java/org/springframework/data/jdbc/repository/config/AbstractJdbcConfiguration.java`, lines 147-156：

```java
@Bean
public JdbcCustomConversions jdbcCustomConversions() {
    JdbcDialect dialect = applicationContext.getBean(JdbcDialect.class);
    return JdbcConfiguration.createCustomConversions(dialect, userConverters());
}

protected List<?> userConverters() {
    return Collections.emptyList();   // override in @Configuration subclass
}
```

慣用：在 `@Configuration` class extend `AbstractJdbcConfiguration`，override `userConverters()` 回 `@WritingConverter` / `@ReadingConverter` 標記的 converter 實例 list。框架把你的 converter 與 dialect 內建 converter（如 Postgres array、`LocalDateTime` mapper）合併進 `JdbcCustomConversions`，wire 到 `JdbcConverter` bean。

Skills Hub 已用此 pattern：`JdbcConfiguration.java` extends `AbstractJdbcConfiguration` 註冊 `Map<String, Object> ↔ PGobject(jsonb)` 與 `List<String> ↔ JSONB array` 雙向轉換。

### Converter 契約與時序

- `@WritingConverter`：Java type → store type（如 `SkillStatus enum → String`）。在 `accessStrategy.insert/update` 建構 `SqlParameterSource` 時呼叫。
- `@ReadingConverter`：store type → Java type。在 `EntityRowMapper.mapRow()` 讀取 `ResultSet` 欄位時呼叫。
- **converter chain 在 Spring 預設 type coercion 之前執行**：若 `@WritingConverter` 匹配 source/target pair，`ConversionService` 用它取代預設。意味自訂 `UUID → String` 會用於**所有 UUID 欄位**，不只特定 column；設計時要意識到 universal 適用性。

---

## 6. AOT 與 `DialectResolver`

### 為什麼 dialect 解析需要 DataSource

`spring-data-jdbc/src/main/java/org/springframework/data/jdbc/core/dialect/DialectResolver.java`, lines 81-88：

```java
public static JdbcDialect getDialect(JdbcOperations operations) {
    return Stream.concat(LEGACY_DETECTORS.stream(), DETECTORS.stream())
        .map(it -> it.getDialect(operations))
        .flatMap(Optionals::toStream)
        .map(it -> it instanceof JdbcDialect jd ? jd : new JdbcDialectAdapter(it))
        .findFirst()
        .orElseThrow(() -> new NoDialectException(...));
}
```

`DefaultDialectProvider.getDialect`（line 113）：

```java
public Optional<Dialect> getDialect(JdbcOperations operations) {
    return Optional.ofNullable(
        operations.execute((ConnectionCallback<Dialect>) DefaultDialectProvider::getDialect));
}
```

dialect 解析需要**真實 JDBC 連線**檢查 `DatabaseMetaData.getDatabaseProductName()`。當 GraalVM 或 Spring Boot AOT 在 build time 處理 bean 時，會嘗試 instantiate `JdbcDialect` bean → 觸發 `DialectResolver.getDialect(operations)` → `operations.execute(ConnectionCallback)` → 真實 SQL 連線嘗試。**若 DataSource 在 build time 不可用（如 Cloud SQL Auth Proxy sidecar 僅 runtime 才有），AOT 初始化失敗**。

### `JdbcRepositoryConfigExtension` AOT 處理

`spring-data-jdbc/src/main/java/org/springframework/data/jdbc/repository/config/JdbcRepositoryConfigExtension.java`, lines 148-178：

```java
public static class JdbcRepositoryRegistrationAotProcessor
        extends RepositoryRegistrationAotProcessor {

    @Override
    protected @Nullable RepositoryContributor contributeAotRepository(
            AotRepositoryContext repositoryContext) {

        if (!repositoryContext.isGeneratedRepositoriesEnabled(MODULE_NAME)) {
            return null;
        }

        ConfigurableListableBeanFactory beanFactory = repositoryContext.getBeanFactory();
        JdbcDialect dialect = beanFactory.getBeanProvider(JdbcDialect.class)
            .getIfAvailable(() -> JdbcH2Dialect.INSTANCE);   // AOT fallback to H2
        // ... builds JdbcRepositoryContributor
    }
}
```

AOT processor 在 **repository contributor 路徑**（query generation）有 H2 fallback。但**主 `jdbcDialect` bean**（在 `AbstractJdbcConfiguration` 宣告）仍 eager，會嘗試真實連線除非介入。

### Skills Hub 的解法

當前 Skills Hub 在 `bootRun` 觸發 `processAot` 時遭遇此問題（見對話前段）。可選解法：

1. **build-time 提供 DataSource stub/mock**（用 Testcontainers）— 重型，nativeImage 才划算
2. **顯式註冊 `JdbcDialect` bean**（`JdbcPostgresDialect.INSTANCE`），讓 resolver 不觸發
3. **bootRun 不走 AOT**（最簡單，dev 推薦）— 在 `build.gradle.kts` 把 `processAot` 從 `bootRun` 依賴移除（前段對話確認可行；production AOT 仍走 `bootBuildImage` / `nativeCompile`）

S023 實施時建議走 (3)，已在對話中驗證 docker-compose 自動拉起 + bootRun 順利啟動。

---

## 從 Pure ES 遷移的關鍵差異

| 概念 | Pure ES（Skills Hub 現狀） | Spring Data JDBC stateful aggregate |
|---|---|---|
| 子集合 save | append event，不覆寫 | DELETE all children + re-INSERT 每次 update |
| 事件 publish 時序 | 顯式呼叫 `events.publishEvent()`，在 service 層 | `repo.save()` 後由 proxy interceptor 呼叫 |
| 版本檢查 | event store sequence UNIQUE | `@Version` 欄位（root 才有） |
| isNew 判斷 | aggregate 重建 from event log（new = empty events） | `@Id` null = new；可由 `Persistable.isNew()` 覆蓋 |
| ACL 高頻變更 | append event O(1) | DELETE + N INSERT each save，不可接受 |
| 聚合「狀態」 | 衍生自 events，每次 method call 都 replay | 直接讀 / 寫表行 |

**Skills Hub S023 對 ACL 與 SkillVersion 的具體決策**：拆為獨立 aggregate（不放進 `Skill` 內 `@MappedCollection`），用 `AggregateReference<Skill, String>` 或 plain `String skillId` 引用。詳見 [design-decisions.md §4](./design-decisions.md)。
