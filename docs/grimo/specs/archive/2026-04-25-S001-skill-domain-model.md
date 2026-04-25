# S001: Skill 領域模型 + Command/Query API (ES+CQRS)

> Spec: S001 | Size: S(11) | Status: ⏳ Design
> Date: 2026-04-25

---

## 1. Goal

建立 Skill 核心域的 ES+CQRS 完整架構，包含 domain events、command/query handlers、read model projection、SKILL.md frontmatter validator，以及 REST API。讓「建立技能 → 產生事件 → 更新 read model → 查詢」的端到端流程可運作。

依賴 S000（code-level：imports `DomainEvent`, `DomainEventRepository`）。S000 ⏳ Design 中，可平行設計，實作需等 S000 完成。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Full Aggregate Replay from Events | no | MVP 不需要，增加複雜度。Replay 留給 backlog ES-B1 |
| **B: Event Store + Read Model as Aggregate State (Snapshot)** | ⭐ yes | 務實折衷 — events 保歷史，read model 兼做 snapshot + projection + query。matches D23 "僅儲存事件 + 更新 projection" |
| C: CQRS only (no ES) | no | 無 event history，違反 D20 |

### Key Decisions

1. **Read model = Aggregate state = Snapshot** — command 驗證從 read model 載入，不 replay events
2. **JSON body API** — POST /api/v1/skills 接 JSON，不含檔案上傳。S003 加 async upload（先上傳 → 取得 fileRef → 再送 JSON）
3. **name 不綁 unique** — 只做 agentskills.io 格式驗證（1-64, lowercase-hyphen），唯一識別是 UUID
4. **SKILL.md validator 純邏輯** — 接收 frontmatter String → 回傳 ValidationResult，不碰 IO。zip 解壓是 S003
5. **S001 定義所有 domain events + command handlers** — 但 PublishVersion 的 REST endpoint 在 S003
6. **Concrete events = Spring Application Events** — typed records，publish via `ApplicationEventPublisher`；另存為 generic `DomainEvent` 到 event store

### Challenges Considered

- **Read model 與 event store 一致性** — write event + publish event 在同一 transaction。Projection 非同步更新 read model（@ApplicationModuleListener）。Spring Modulith 的 event publication log 確保 eventual consistency。
- **Aggregate from read model 有 lag 風險** — 兩個 concurrent command 可能讀到同一版 read model。MVP 可接受（事件量小、單一 instance）。未來可加 optimistic locking（version field on read model）。

### 2.3 Research Citations

- [agentskills.io specification](https://agentskills.io/specification) — Required fields: name (1-64, lowercase-hyphen, no consecutive hyphens), description (1-1024). Optional: license, compatibility (1-500), metadata (nested map), allowed-tools (space-separated).
- [Spring Modulith @ApplicationModuleListener](https://docs.spring.io/spring-modulith/reference/events.html) — Async TransactionalEventListener, runs in own tx. Event publication log implements transactional outbox.
- [SnakeYAML](https://github.com/snakeyaml/snakeyaml) — Already in Spring Boot classpath. Handles nested YAML structures (maps, lists). Used for frontmatter parsing.
- Spring AI MarkdownDocumentReader — confirmed does NOT parse YAML frontmatter. Custom parser needed.

## 3. SBE Acceptance Criteria

Verification command:

    Run: cd backend && ./gradlew test
    Pass: all tests carrying S001 AC ids are green.

---

**AC-1: 建立新 Skill（Command Side）**

```
Given 一個合法的 CreateSkillCommand { name: "docker-helper", description: "Helps with Docker", author: "sam", category: "DevOps" }
When  POST /api/v1/skills
Then  domain_events collection 新增一筆 SkillCreated event
And   skills read model 同步建立對應 document（status=DRAFT, latestVersion=null）
And   回傳 201 Created，含 skill id
```

**AC-2: 取得 Skill 詳情（Query Side）**

```
Given 已有一個 id 為 "abc123" 的 skill（由 projection 建構的 read model）
When  GET /api/v1/skills/abc123
Then  回傳 200 + 完整 skill metadata（name, description, author, category, status, createdAt）
```

**AC-3: SKILL.md frontmatter 驗證 — 成功**

```
Given 合法 frontmatter:
      ---
      name: docker-helper
      description: Helps with Docker containers and compose files.
      metadata:
        author: example-org
        version: "1.0"
      ---
When  SkillValidator.validate(frontmatterString)
Then  回傳 ValidationResult { valid: true, fields: { name: "docker-helper", description: "..." } }
```

**AC-4: SKILL.md frontmatter 驗證 — 失敗**

```
Given frontmatter 缺少 name 欄位:
      ---
      description: Some description
      ---
When  SkillValidator.validate(frontmatterString)
Then  回傳 ValidationResult { valid: false, errors: ["Missing required field: name"] }

Given frontmatter name 格式錯誤:
      ---
      name: Docker-Helper
      description: Some description
      ---
When  SkillValidator.validate(frontmatterString)
Then  回傳 ValidationResult { valid: false, errors: ["name must be lowercase letters, numbers, and hyphens only"] }
```

**AC-5: Event Store 完整性（programmatic, 不經 REST）**

```
Given 對同一 skill 先執行 createSkill，再執行 publishVersion（programmatic call）
When  查詢 domain_events by aggregateId
Then  回傳 2 筆 events，按 sequence 排序：[SkillCreated(seq=1), SkillVersionPublished(seq=2)]
And   每筆 event 的 aggregateId, eventType, payload, occurredAt 皆正確
```

## 4. Interface / API Design

### 4.1 Data Flow

```
POST /api/v1/skills { name, description, author, category }
    │
    ▼
SkillCommandController
    │
    ▼
SkillCommandService.createSkill(cmd)
    │
    ├─ Skill.create(cmd)              ← Aggregate validates name format
    │      │
    │      ▼
    │  SkillCreated record            ← typed domain event
    │
    ├─ eventMapper.toDomainEvent(event) → DomainEventRepository.save()
    │                                      ↓
    │                                   domain_events collection
    │
    └─ applicationEventPublisher.publishEvent(event)
           │
           ▼
       SkillProjection.on(SkillCreated)  ← @ApplicationModuleListener (async)
           │
           ▼
       SkillReadModelRepository.save(new SkillReadModel(...))
           │
           ▼
       skills collection (read model = aggregate state = snapshot)
           │
           ▼
       GET /api/v1/skills/{id} → SkillQueryController → SkillReadModel
```

### 4.2 Domain Events (skill/domain/)

```java
// SkillCreated — published when a new skill is created
public record SkillCreated(
    String aggregateId,    // UUID
    String name,           // agentskills.io format
    String description,
    String author,
    String category,
    List<String> tags,
    Instant occurredAt
) {}

// SkillVersionPublished — published when a version is uploaded (REST in S003)
public record SkillVersionPublished(
    String aggregateId,
    String version,        // semver e.g. "1.0.0"
    String storagePath,    // GCS path e.g. "skills/abc123/1.0.0.zip"
    long fileSize,
    Map<String, Object> frontmatter,
    Instant occurredAt
) {}
```

### 4.3 Skill Aggregate (skill/domain/)

```java
public class Skill {
    private String id;
    private String name;
    private SkillStatus status;
    private List<SkillVersion> versions;

    // Factory — create new skill, returns event
    public static SkillCreated create(CreateSkillCommand cmd) {
        validateNameFormat(cmd.name());    // agentskills.io rules
        return new SkillCreated(
            UUID.randomUUID().toString(),
            cmd.name(), cmd.description(),
            cmd.author(), cmd.category(),
            cmd.tags(), Instant.now()
        );
    }

    // Load from read model (= snapshot)
    public static Skill fromReadModel(SkillReadModel rm) {
        var skill = new Skill();
        skill.id = rm.id();
        skill.name = rm.name();
        skill.status = rm.status();
        skill.versions = rm.versions();  // simplified
        return skill;
    }

    // Business method — validate invariants, return event
    public SkillVersionPublished publishVersion(PublishVersionCommand cmd) {
        if (this.status == SkillStatus.SUSPENDED)
            throw new IllegalStateException("Cannot publish to suspended skill");
        if (versions.stream().anyMatch(v -> v.version().equals(cmd.version())))
            throw new IllegalArgumentException("Version already exists: " + cmd.version());
        return new SkillVersionPublished(
            this.id, cmd.version(), cmd.storagePath(),
            cmd.fileSize(), cmd.frontmatter(), Instant.now()
        );
    }
}
```

### 4.4 Commands (skill/command/)

```java
public record CreateSkillCommand(
    String name,
    String description,
    String author,
    String category,
    List<String> tags
) {}

public record PublishVersionCommand(
    String version,       // semver
    String storagePath,   // from S003 upload
    long fileSize,
    Map<String, Object> frontmatter
) {}
```

### 4.5 Command Service (skill/command/)

```java
@Service
public class SkillCommandService {
    private final DomainEventRepository eventStore;
    private final ApplicationEventPublisher events;
    private final EventMapper eventMapper;
    private final SkillReadModelRepository readModelRepo;  // load aggregate state

    public String createSkill(CreateSkillCommand cmd) {
        var event = Skill.create(cmd);
        eventStore.save(eventMapper.toDomainEvent(
            event.aggregateId(), "Skill", event, 1));
        events.publishEvent(event);
        return event.aggregateId();
    }

    public void publishVersion(String skillId, PublishVersionCommand cmd) {
        var readModel = readModelRepo.findById(skillId)
            .orElseThrow(() -> new SkillNotFoundException(skillId));
        var skill = Skill.fromReadModel(readModel);
        var nextSeq = eventStore.findTopByAggregateIdOrderBySequenceDesc(skillId)
            .map(e -> e.sequence() + 1).orElse(1L);
        var event = skill.publishVersion(cmd);
        eventStore.save(eventMapper.toDomainEvent(
            skillId, "Skill", event, nextSeq));
        events.publishEvent(event);
    }
}
```

### 4.6 Read Model + Projection (skill/query/)

```java
@Document("skills")
public record SkillReadModel(
    @Id String id,
    String name,
    String description,
    String author,
    String category,
    List<String> tags,
    String latestVersion,          // null until first publish
    SkillStatus status,            // DRAFT initially
    long downloadCount,            // 0 initially
    Instant createdAt,
    Instant updatedAt
) {}

@Component
class SkillProjection {
    private final SkillReadModelRepository repo;

    @ApplicationModuleListener
    void on(SkillCreated e) {
        repo.save(new SkillReadModel(
            e.aggregateId(), e.name(), e.description(),
            e.author(), e.category(), e.tags(),
            null, SkillStatus.DRAFT, 0,
            e.occurredAt(), e.occurredAt()
        ));
    }

    @ApplicationModuleListener
    void on(SkillVersionPublished e) {
        repo.findById(e.aggregateId()).ifPresent(rm ->
            repo.save(new SkillReadModel(
                rm.id(), rm.name(), rm.description(),
                rm.author(), rm.category(), rm.tags(),
                e.version(), SkillStatus.PUBLISHED, rm.downloadCount(),
                rm.createdAt(), Instant.now()
            )));
    }
}
```

**Example data (skills collection, 2 rows):**

| _id | name | description | author | category | latestVersion | status | downloadCount | createdAt |
|-----|------|-------------|--------|----------|---------------|--------|---------------|-----------|
| `abc-123` | `docker-helper` | Helps with Docker | sam | DevOps | `1.0.0` | PUBLISHED | 0 | 2026-04-25T10:00Z |
| `def-456` | `test-generator` | Generates unit tests | alex | Testing | null | DRAFT | 0 | 2026-04-25T11:00Z |

### 4.7 REST API (skill/command/ + skill/query/)

```
POST /api/v1/skills
  Request:  { "name": "docker-helper", "description": "...", "author": "sam", "category": "DevOps", "tags": ["docker"] }
  Response: 201 { "id": "abc-123" }
  Errors:   400 { "error": "VALIDATION_ERROR", "message": "name must be lowercase..." }

GET /api/v1/skills?page=0&size=20&sort=createdAt,desc&category=DevOps
  Response: 200 { "content": [...], "page": { "number": 0, "size": 20, "totalElements": 50 } }

GET /api/v1/skills/{id}
  Response: 200 { "id": "abc-123", "name": "docker-helper", ... }
  Errors:   404 { "error": "SKILL_NOT_FOUND", "message": "Skill with id xxx not found" }
```

### 4.8 SKILL.md Validator (skill/validation/)

```java
public record ValidationResult(
    boolean valid,
    Map<String, Object> fields,      // parsed frontmatter fields (if valid)
    List<String> errors              // error messages (if invalid)
) {
    public static ValidationResult success(Map<String, Object> fields) { ... }
    public static ValidationResult failure(List<String> errors) { ... }
}

public class SkillValidator {
    // Parse YAML frontmatter from SKILL.md content string
    // Split on "---" delimiters → extract YAML → parse with SnakeYAML → validate
    public ValidationResult validate(String skillMdContent) { ... }
}
```

Validation rules (from agentskills.io):

| Field | Rule |
|-------|------|
| `name` | Required. 1-64 chars. `[a-z0-9-]` only. No start/end hyphen. No consecutive hyphens. |
| `description` | Required. 1-1024 chars. Non-empty. |
| `license` | Optional. String. |
| `compatibility` | Optional. 1-500 chars. |
| `metadata` | Optional. Map<String, String>. |
| `allowed-tools` | Optional. Space-separated string. |

### 4.9 Event Mapper (shared/events/)

```java
@Component
public class EventMapper {
    private final ObjectMapper objectMapper;

    public DomainEvent toDomainEvent(String aggregateId, String aggregateType,
                                      Object event, long sequence) {
        @SuppressWarnings("unchecked")
        var payload = objectMapper.convertValue(event, Map.class);
        return new DomainEvent(
            UUID.randomUUID().toString(), aggregateId, aggregateType,
            event.getClass().getSimpleName(), payload, sequence,
            Instant.now(), Map.of()
        );
    }
}
```

## 5. File Plan

Package base: `io.github.samzhu.skillshub` (abbreviated as `...`)

| # | File | Action | Description |
|---|------|--------|-------------|
| **shared — event mapper** |||
| 1 | `.../shared/events/EventMapper.java` | new | Typed event → DomainEvent 轉換 |
| **skill/domain — aggregate + events** |||
| 2 | `.../skill/domain/Skill.java` | new | Aggregate Root — create(), publishVersion(), fromReadModel() |
| 3 | `.../skill/domain/SkillVersion.java` | new | Value Object (version, storagePath, fileSize) |
| 4 | `.../skill/domain/SkillStatus.java` | new | enum: DRAFT, PUBLISHED, SUSPENDED |
| 5 | `.../skill/domain/SkillCreated.java` | new | Domain event record |
| 6 | `.../skill/domain/SkillVersionPublished.java` | new | Domain event record |
| 7 | `.../skill/domain/SkillNotFoundException.java` | new | Domain exception |
| **skill/command — write side** |||
| 8 | `.../skill/command/CreateSkillCommand.java` | new | Command record |
| 9 | `.../skill/command/PublishVersionCommand.java` | new | Command record |
| 10 | `.../skill/command/SkillCommandService.java` | new | Command handler（load from read model, validate, persist event, publish） |
| 11 | `.../skill/command/SkillCommandController.java` | new | POST /api/v1/skills |
| **skill/query — read side** |||
| 12 | `.../skill/query/SkillReadModel.java` | new | @Document("skills") read model record |
| 13 | `.../skill/query/SkillReadModelRepository.java` | new | MongoRepository for skills collection |
| 14 | `.../skill/query/SkillQueryService.java` | new | Query service (findById, search, paginate) |
| 15 | `.../skill/query/SkillQueryController.java` | new | GET /api/v1/skills, GET /api/v1/skills/{id} |
| 16 | `.../skill/query/SkillProjection.java` | new | @ApplicationModuleListener — SkillCreated, SkillVersionPublished → update read model |
| **skill/validation — frontmatter** |||
| 17 | `.../skill/validation/SkillValidator.java` | new | SKILL.md frontmatter parser + validator (SnakeYAML) |
| 18 | `.../skill/validation/ValidationResult.java` | new | Result record (valid, fields, errors) |
| **shared/api — error handling** |||
| 19 | `.../shared/api/ErrorResponse.java` | new | Unified error response record |
| 20 | `.../shared/api/GlobalExceptionHandler.java` | new | @ControllerAdvice — SkillNotFoundException → 404, validation → 400 |
| **tests** |||
| 21 | `.../skill/command/SkillCommandServiceTest.java` | new | AC-1, AC-5: create + publishVersion + event store |
| 22 | `.../skill/query/SkillQueryServiceTest.java` | new | AC-2: query read model |
| 23 | `.../skill/query/SkillProjectionTest.java` | new | Projection updates read model correctly |
| 24 | `.../skill/validation/SkillValidatorTest.java` | new | AC-3, AC-4: valid/invalid frontmatter |
| 25 | `.../skill/SkillIntegrationTest.java` | new | End-to-end: POST → event → projection → GET |
| **docs** |||
| 26 | `docs/grimo/specs/spec-roadmap.md` | modify | S001 status 🔲 → ⏳ |
