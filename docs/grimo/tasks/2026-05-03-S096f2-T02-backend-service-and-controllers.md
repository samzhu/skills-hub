# S096f2-T02: CollectionService + Command/Query controllers + 2 exceptions + GlobalExceptionHandler

## Spec
S096f2 — Collections Full Feature（spec doc: `docs/grimo/specs/2026-05-03-S096f2-collections-full.md`）

## BDD（涵蓋的 AC）

**AC-1: 建立 collection happy path**
- Given：alice 已登入；3 個 PUBLISHED skill (sk1/sk2/sk3)
- When：`POST /api/v1/collections` body `{"name":"DevOps Starter","description":"...","category":"DevOps","skillIds":["sk1","sk2","sk3"]}`
- Then：回 201 + body `{"id":"<uuid>"}`；DB collection 1 筆 + collection_skills 3 筆；outbox CollectionCreatedEvent

**AC-2: skillIds 空 list 拒絕**
- Given：alice 登入
- When：POST body `{"name":"X","description":"x","category":"X","skillIds":[]}`
- Then：回 400 + `error: "collection_must_have_skills"`

**AC-3: 含不存在 / 非 PUBLISHED skill 拒絕**
- Given：sk1 PUBLISHED；sk2 SUSPENDED；sk-bogus 不存在
- When：POST body skillIds=["sk1","sk2","sk-bogus"]
- Then：回 400 + `error: "skill_not_publishable"`；body 含哪些 skillIds invalid

**AC-4: name 長度上限**
- Given：alice 登入
- When：POST body name=201 字元
- Then：回 400 + `error: "name_too_long"`

**AC-5: list endpoint — 預設 + category filter**
- Given：3 collection (DevOps / Frontend / Frontend)
- When：`GET /api/v1/collections` → 回 200 + 3 筆 createdAt desc
- When：`GET /api/v1/collections?category=Frontend` → 回 200 + 2 筆 (Frontend)

**AC-6: 單筆 endpoint 含 skills detail**
- Given：collection c1 含 sk1/sk2
- When：`GET /api/v1/collections/c1`
- Then：回 200 + body `{ id, name, description, category, ownerId, installCount, createdAt, skills: [{id, name, category, riskLevel, latestVersion}, ...] }`（兩個 skill summary，按 position）

**AC-7: Install happy path**
- Given：collection c1 含 sk1/sk2/sk3 (全 PUBLISHED)；當前 install_count=5
- When：alice POST `/api/v1/collections/c1/install`
- Then：回 200 + body `{"downloadUrls": ["/api/v1/skills/sk1/download", ...]}`；DB install_count=6；outbox CollectionInstalledEvent(c1, alice)

**AC-8: Install 不存在 collection → 404**
- Given：collection c-bogus 不存在
- When：POST `/api/v1/collections/c-bogus/install`
- Then：回 404 + `error: "collection_not_found"`

## Implementation outline

### `backend/.../shared/api/CollectionNotFoundException.java` (new)

```java
public class CollectionNotFoundException extends RuntimeException {
    public CollectionNotFoundException(String collectionId) {
        super("collection_not_found: " + collectionId);
    }
}
```

### `backend/.../shared/api/SkillNotPublishableException.java` (modify if exists, else new)

S096g2 RequestService 已 throw `IllegalArgumentException("skill_not_publishable: ...")` for fulfill — 本 task 升級為獨立 exception class（給 GlobalExceptionHandler 翻 400 with structured body 含 invalid skillIds list）。對齊既有 `RequestNotFoundException` / `NotRequestClaimerException` naming。

```java
public class SkillNotPublishableException extends RuntimeException {
    private final List<String> invalidSkillIds;

    public SkillNotPublishableException(List<String> invalidSkillIds) {
        super("skill_not_publishable: " + String.join(",", invalidSkillIds));
        this.invalidSkillIds = invalidSkillIds;
    }

    public List<String> getInvalidSkillIds() { return invalidSkillIds; }
}
```

**Caller migration**：S096g2 RequestService.fulfill 既有 `throw new IllegalArgumentException("skill_not_publishable: ...")` — 本 task **同步改用** `SkillNotPublishableException`（避免兩個 mapping path）+ 對應 RequestServiceTest assertion 從 `IllegalArgumentException + hasMessageContaining("skill_not_publishable")` → `isInstanceOf(SkillNotPublishableException.class)`。

### `backend/.../shared/api/GlobalExceptionHandler.java` (modify — 加 2 mapping)

- `@ExceptionHandler(CollectionNotFoundException.class)` → 404 + `error: "collection_not_found"`
- `@ExceptionHandler(SkillNotPublishableException.class)` → 400 + `error: "skill_not_publishable"` + body 含 `invalidSkillIds` list（structured ErrorResponse extension or extra field）
- 對齊既有 RequestNotFound / NotClaimer 命名 + log atWarn pattern

### `backend/.../community/CollectionService.java` (new — business orchestration)

```java
@Service
public class CollectionService {
    private final CollectionRepository repo;
    private final SkillRepository skillRepo;
    private final CurrentUserProvider users;

    @Transactional
    public String create(String name, String description, String category, List<String> skillIds) {
        // 1. validate skillIds 全 PUBLISHED
        var found = skillRepo.findAllByIdInAndStatus(skillIds, SkillStatus.PUBLISHED);
        var foundIds = found.stream().map(Skill::getId).toList();
        var invalid = skillIds.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!invalid.isEmpty()) {
            throw new SkillNotPublishableException(invalid);
        }
        // 2. factory + save (outbox auto-publish)
        var collection = Collection.create(name, description, category, users.userId(), skillIds);
        return repo.save(collection).getId();
    }

    @Transactional
    public List<String> install(String collectionId) {
        var collection = repo.findById(collectionId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));
        collection.recordInstall(users.userId());
        repo.save(collection);
        return collection.getSkills().stream()
                .map(s -> "/api/v1/skills/" + s.skillId() + "/download")
                .toList();
    }

    public List<Collection> list(String categoryFilter) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return repo.findAllByOrderByCreatedAtDesc();
        }
        return repo.findAllByCategoryOrderByCreatedAtDesc(categoryFilter);
    }

    public Collection get(String id) {
        return repo.findById(id).orElseThrow(() -> new CollectionNotFoundException(id));
    }
}
```

### `backend/.../community/CollectionCommandController.java` (new — POST endpoints)

```java
@RestController
@RequestMapping("/api/v1/collections")
class CollectionCommandController {
    private final CollectionService service;

    @PostMapping
    ResponseEntity<Map<String, String>> create(@RequestBody CreateCollectionBody body) {
        var id = service.create(body.name(), body.description(), body.category(), body.skillIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/{id}/install")
    InstallResponse install(@PathVariable String id) {
        return new InstallResponse(service.install(id));
    }

    record CreateCollectionBody(String name, String description, String category, List<String> skillIds) {}
    record InstallResponse(List<String> downloadUrls) {}
}
```

### `backend/.../community/CollectionQueryController.java` (new — 取代 stub `CollectionController`)

```java
@RestController
@RequestMapping("/api/v1/collections")
class CollectionQueryController {
    private final CollectionService service;
    private final SkillRepository skillRepo;  // for AC-6 detail enrichment

    @GetMapping
    List<CollectionSummary> list(@RequestParam(required = false) String category) {
        return service.list(category).stream().map(CollectionSummary::from).toList();
    }

    @GetMapping("/{id}")
    CollectionDetail get(@PathVariable String id) {
        var collection = service.get(id);
        // AC-6: enrich with skill detail (id, name, category, riskLevel, latestVersion)
        var skillDetails = collection.getSkills().stream()
                .map(ref -> skillRepo.findById(ref.skillId()).orElse(null))
                .filter(Objects::nonNull)
                .map(SkillSummary::from)
                .toList();
        return CollectionDetail.from(collection, skillDetails);
    }

    record CollectionSummary(String id, String name, String description, String category,
                             int skillCount, int installs, Instant createdAt) {
        static CollectionSummary from(Collection c) { ... }
    }

    record CollectionDetail(String id, String name, String description, String category,
                            String ownerId, int installCount, Instant createdAt,
                            List<SkillSummary> skills) {
        static CollectionDetail from(Collection c, List<SkillSummary> skills) { ... }
    }

    record SkillSummary(String id, String name, String category,
                        String riskLevel, String latestVersion) {
        static SkillSummary from(Skill s) { ... }
    }
}
```

### `backend/.../community/CollectionController.java` (delete — 既有 S096f1 stub 由 Command + Query 取代)

S096f1 stub `CollectionController` (return `[]`) 由本 task 兩個 controller 取代。delete 既有檔。

### Tests

- `backend/src/test/.../community/CollectionServiceTest.java` (new — Testcontainers AC-1/2/3/4/7 + non-existent install 404 + create-then-install 整合 flow)
- `backend/src/test/.../community/CollectionControllerTest.java` (new — @WebMvcTest slice extending WebMvcSliceTestBase；mock CollectionService + SkillRepository；AC-1/2/5/6/8 HTTP routing + status code)
- `backend/src/test/.../community/RequestServiceTest.java` (modify — AC-12 fulfill 改為 `assertThatThrownBy.isInstanceOf(SkillNotPublishableException.class)` per caller migration)

## Target Files

- `backend/.../shared/api/CollectionNotFoundException.java` (new)
- `backend/.../shared/api/SkillNotPublishableException.java` (new, with invalidSkillIds list)
- `backend/.../shared/api/GlobalExceptionHandler.java` (modify — 加 2 mapping)
- `backend/.../community/CollectionService.java` (new — create/install/list/get)
- `backend/.../community/CollectionCommandController.java` (new — POST create + POST install)
- `backend/.../community/CollectionQueryController.java` (new — GET list + GET single with skills detail)
- `backend/.../community/CollectionController.java` (delete — S096f1 stub 取代)
- `backend/.../community/RequestService.java` (modify — fulfill 改 throw `SkillNotPublishableException`)
- `backend/src/test/.../community/CollectionServiceTest.java` (new — AC-1/2/3/4/7 + 8 integration)
- `backend/src/test/.../community/CollectionControllerTest.java` (new — AC-1/2/5/6/8 web slice)
- `backend/src/test/.../community/RequestServiceTest.java` (modify — AC-12 assertion)

## Depends On
- T01（Collection aggregate + schema + community @ApplicationModule）

## Status
pending
