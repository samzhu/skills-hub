# S096f2-T01: Collection aggregate + V12 schema + community @ApplicationModule + 2 events

## Spec
S096f2 — Collections Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096f2-collections-full.md`）

## BDD（涵蓋的 AC）

**AC-9: Modulith 邊界驗證**
- Given：本 task 加 `community/package-info.java` `@ApplicationModule(displayName="Community", allowedDependencies={"shared::events", "shared::api", "shared::security", "skill::domain"})`
- When：跑 `./gradlew test --tests "io.github.samzhu.skillshub.ModularityTests"`
- Then：所有 ModularityTests PASS（無循環依賴 + community 只用宣告的 allowedDependencies）

**AC-1（partial — schema/aggregate round-trip）：建立 collection aggregate 寫入後 round-trip OK**
- Given：3 個 PUBLISHED skill 存在 (sk1/sk2/sk3)
- When：smoke test 透過 `Collection.create("DevOps Starter", desc, "DevOps", "alice", List.of("sk1","sk2","sk3"))` + `repo.save(c)`
- Then：DB `collections` 1 筆 + `collection_skills` 3 筆（position 0/1/2 + ON DELETE CASCADE）；reload 後 skills list 順序對；outbox `event_publication` 寫入 `CollectionCreatedEvent`

**AC-Smoke: install_count 計數正確 + recordInstall registerEvent**
- Given：collection c1 存在 install_count=0
- When：`c1.recordInstall("alice")` + `repo.save(c1)`
- Then：DB install_count=1；outbox 寫入 `CollectionInstalledEvent(c1, alice)`

## Implementation outline

### `backend/.../community/package-info.java` (modify — 加 @ApplicationModule 正式 register)

S096g2 已有 community module；本 task 在既有 package-info 加 `displayName + allowedDependencies` 正式 register（per spec §2.3）。allowedDependencies 在既有 S096g2 Request 寫入時可能已存在 — 確認 + 補 `skill::domain` 給 SkillRepository.findAllPublishedByIdIn lookup。

### `backend/.../community/Collection.java` (new — Aggregate)

ADR-002 canonical pattern + `@Version`（per S096g2 既驗，**不走** spec §4.3 範本的 Persistable — 對齊 Request aggregate 既驗 deviation）：

- `extends AbstractAggregateRoot<Collection>` for `registerEvent(...)`
- `@Version Long version` for INSERT/UPDATE 區分（version=null INSERT；loaded UPDATE）
- `@MappedCollection(idColumn="collection_id", keyColumn="position") List<CollectionSkillRef> skills`
- factory `create(name, description, category, ownerId, skillIds)` 驗 name (≤200) + description (≤2000) + category (≤100) + skillIds (≥1)；不在 factory 設 createdAt（DB DEFAULT NOW()）；`registerEvent(new CollectionCreatedEvent(...))`
- mutation method `recordInstall(installerId)`：`installCount++` + `registerEvent(new CollectionInstalledEvent(...))`
- getter for service / controller / DTO mapping

### `backend/.../community/CollectionSkillRef.java` (new — join 列 record)

```java
@Table("collection_skills")
public record CollectionSkillRef(@Column("skill_id") String skillId) {}
```

### `backend/.../community/CollectionRepository.java` (new — Spring Data JDBC repo)

`extends CrudRepository<Collection, String>` + 1 derived query `findAllByCategoryOrderByCreatedAtDesc(String category)` + 1 `findAllByOrderByCreatedAtDesc()`。AOT compound-sort bug 不影響 single-property sort（`ORDER BY created_at DESC` 屬 single sort），無需 `@Query` workaround。

### `backend/.../community/events/CollectionCreatedEvent.java` (new — record)

```java
public record CollectionCreatedEvent(String collectionId, String name, String ownerId,
                                     List<String> skillIds, Instant createdAt) {}
```

### `backend/.../community/events/CollectionInstalledEvent.java` (new — record)

```java
public record CollectionInstalledEvent(String collectionId, String installerId, Instant installedAt) {}
```

### `backend/.../skill/domain/SkillRepository.java` (modify — 加 derived query)

加 `List<Skill> findAllByIdInAndStatus(List<String> ids, SkillStatus status)`（或 implementer 用 existing `findAllById` + filter — XS task 內擇一）。給 CollectionService.create 驗 skillIds 全 PUBLISHED 用。

### `backend/src/main/resources/db/migration/V12__create_collections_tables.sql` (new)

per spec §4.2：
- `collections` 表（id PRIMARY KEY VARCHAR(36) + name VARCHAR(200) NOT NULL + description TEXT + owner_id VARCHAR(255) NOT NULL + category VARCHAR(100) NOT NULL + install_count INTEGER NOT NULL DEFAULT 0 + created_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + version BIGINT NOT NULL DEFAULT 0）
- `collection_skills` 表（collection_id REFERENCES collections(id) ON DELETE CASCADE + skill_id VARCHAR(36) + position INTEGER NOT NULL + PRIMARY KEY (collection_id, skill_id)）
- 4 個 index：idx_collections_category / idx_collections_created / idx_collections_owner / idx_collection_skills_skill

### `backend/src/test/.../community/CollectionModuleSmokeTest.java` (new — 6-7 tests)

對齊 NotificationModuleSmokeTest 既驗 pattern（@SpringBootTest + Testcontainers）：
- AC-1 partial: Collection.create + save → INSERT + findById round-trip + skills 順序保留
- ON DELETE CASCADE: delete collection → collection_skills rows 自動清
- AC-Smoke: recordInstall increments install_count；outbox 有 CollectionInstalledEvent
- factory rejection: skillIds empty / name too long → IllegalArgumentException
- @Version round-trip: 二次 save 走 UPDATE path
- ModularityTests 2/2 仍 PASS

## Target Files

- `backend/.../community/package-info.java` (modify — `@ApplicationModule(displayName="Community", allowedDependencies={"shared::events", "shared::api", "shared::security", "skill::domain"})`)
- `backend/.../community/Collection.java` (new — AbstractAggregateRoot + @Version + @MappedCollection)
- `backend/.../community/CollectionSkillRef.java` (new — record)
- `backend/.../community/CollectionRepository.java` (new — Spring Data JDBC)
- `backend/.../community/events/CollectionCreatedEvent.java` (new — record)
- `backend/.../community/events/CollectionInstalledEvent.java` (new — record)
- `backend/.../skill/domain/SkillRepository.java` (modify — add `findAllByIdInAndStatus` 或對應 method)
- `backend/src/main/resources/db/migration/V12__create_collections_tables.sql` (new)
- `backend/src/test/.../community/CollectionModuleSmokeTest.java` (new — 6-7 smoke tests)

## Depends On
- 既有 community module (S096g2 註冊)
- 既有 skill::domain SkillRepository

## Status
shipped 2026-05-03 — commit pending（本 tick 內）

## Result

- **Files**：
  - `backend/.../community/Collection.java`（new — `extends AbstractAggregateRoot<Collection>` + `@Version Long version` + `@MappedCollection(idColumn="collection_id", keyColumn="position")`；factory `create()` + `recordInstall()` + 5 個 validator + 對齊 Request defensive copy 慣例）
  - `backend/.../community/CollectionSkillRef.java`（new — `@Table("collection_skills") record (@Column("skill_id") String skillId)` 不持有 own @Id，Spring Data JDBC derived PK 模式）
  - `backend/.../community/CollectionRepository.java`（new — `ListCrudRepository<Collection,String>` + 2 derived query；single-property sort 不觸 AOT bug 故無需 `@Query` workaround）
  - `backend/.../community/events/CollectionCreatedEvent.java` + `CollectionInstalledEvent.java`（new — record；MVP 無 listener，預留 hook 給 future S101b）
  - `backend/.../community/package-info.java`（modify — 加 `displayName="Community"` 對齊 audit/notification/analytics 既驗 metadata 慣例；既有 allowedDependencies 含 `skill::domain` 已給 SkillRepo 跨模組 lookup 用）
  - `backend/.../skill/domain/SkillRepository.java`（modify — 加 `findAllByIdInAndStatus(Collection<String>, SkillStatus)` derived query；給 T02 service create 預檢全 PUBLISHED 用）
  - `backend/src/main/resources/db/migration/V12__create_collections_tables.sql`（new — collections 表含 `version` BIGINT DEFAULT 0 + collection_skills 表 PK (collection_id, position) + UNIQUE (collection_id, skill_id) + ON DELETE CASCADE + 4 indexes）
  - `backend/src/test/.../community/CollectionModuleSmokeTest.java`（new — 9 個 smoke + factory rejection + DB UNIQUE + CASCADE + ApplicationEvents 流驗證）
- **Tests**：CollectionModuleSmokeTest 9/9 PASS @ 15.8s；ModularityTests 2/2 PASS（無 violation；community 模組 metadata 補 displayName 不破既有 module map）；regression RequestServiceTest 13/13 + RequestVoteServiceTest 5/5 PASS（@SkillRepository import 加 `Collection` 不衝突 — 已是 `java.util.Collection` 完整 path）。
- **Design notes**：
  - **不走 spec §4.3 範本的 Persistable**：對齊 Request (S096g2-T01) 既驗，`@Version` 為 INSERT/UPDATE 唯一區分器（version=null INSERT；loaded UPDATE）。Persistable + 自訂 `isNew(createdAt==null)` 範本會破 isNew flag — codebase 第 4 次踩坑教訓
  - **Factory 必設 `createdAt = Instant.now()`**：DB DEFAULT NOW() 不會 fire 因 Spring Data JDBC INSERT explicit pass column value（即使 null 也走 explicit）。本 task ship 前第一輪 fail 6 個 test 即此 root cause；對齊 Request factory pattern 修正
  - **collection_skills PK = (collection_id, position)**：spec §4.2 範本 PK = (collection_id, skill_id) 與 Spring Data JDBC `@MappedCollection(keyColumn="position")` canonical pattern 衝突（child entity Optional<@Id> 與 keyColumn 衝突）。改 PK = (collection_id, position) + 加 UNIQUE (collection_id, skill_id) 維持「同 collection 內 skill 不重複」語意 — 由 factory + DB 雙保護
  - **MVP 無 listener 訂閱**（per spec §4.4）：Modulith Event Publication Registry 只追蹤 `(event, listener_id)` pair；無 listener 故 outbox 表空。Smoke test 改用 Spring Test `@RecordApplicationEvents` 驗證 ApplicationEventPublisher 流（同等 at-least-once 保證；未來加 listener 時 outbox 自動 wire）
