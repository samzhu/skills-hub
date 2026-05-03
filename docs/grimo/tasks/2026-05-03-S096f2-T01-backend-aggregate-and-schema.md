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
pending
