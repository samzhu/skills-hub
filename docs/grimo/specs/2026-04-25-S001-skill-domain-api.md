# S001: Skill 領域模型 + Command/Query API (ES+CQRS)

> Spec: S001 | Size: S(10) | Status: ✅ Done
> Date: 2026-04-25

---

## 1. Goal

建立 skill module 的 ES+CQRS 核心：domain events、command/query handlers、REST API、read model projections、SKILL.md frontmatter 驗證器。這是平台的核心域，所有後續 spec（S002 瀏覽 UI、S003 上傳、S005 風險評估）都依賴此 spec。

依賴 S000（✅ shipped）— 使用 `shared.events.DomainEvent` + `DomainEventRepository`。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: MVP Aggregate（factory methods, no event replay） | ⭐ yes | 符合 architecture.md MVP 範圍：「僅儲存事件 + 更新 projection」。Aggregate 是產生 events 的 factory，不從 event store 重建狀態 |
| B: Full ES Aggregate（event replay 重建狀態） | no | Backlog 功能（ES-B1），MVP 不需要 |

### Key Decisions

1. **MVP Aggregate pattern** — Skill aggregate 作為 factory method holder，接收 command → 驗證 → 回傳 domain event。不做 event replay。
2. **Command service 流程** — validate → aggregate factory → save DomainEvent to event store → publishEvent → return。使用 `@Transactional` 確保 `@EventListener` 在 commit 後觸發。
3. **@EventListener（同步）** — [Implementation note] POC 驗證 `@ApplicationModuleListener` 需要 `spring-modulith-events-core` + `@EnableAsync` + transaction context，MVP 過重。改用 Spring 原生 `@EventListener`，同步處理，publishEvent() 時立即觸發 projection。
4. **SkillReadModel** — MongoDB `@Document("skills")` collection，由 projection 從 events 建構。Query API 只讀 read model。
5. **SKILL.md frontmatter validator** — 使用 SnakeYAML（Spring Boot 內建）解析 YAML frontmatter，驗證 agentskills.io 必填欄位。
6. **Security MVP** — 加入 `SecurityConfig` 允許所有 API 請求通過（`permitAll()`），OAuth2 Resource Server 保留但不強制。
7. **Domain events 為 Spring ApplicationEvent** — 使用 record + implements ApplicationEvent 的方式讓 Spring 認識，payload 嵌入到通用 DomainEvent 中持久化。

### Challenges Considered

- **[POC validated] `@EventListener` 同步處理** — 不需要 `@Transactional`，不需要 MongoDB replica set。event 在 `publishEvent()` 時同步觸發 listener。如果 listener 拋出 exception，command service 也會失敗（保證一致性）。
- **Event delivery 為 at-most-once** — listener 失敗時 exception 傳播到 command service。event store 中的 DomainEvent 是永久記錄，未來可加 event replay 補償。
- **Aggregate 不載入歷史狀態** — uniqueness 驗證（如 version 不重複）在 MVP 中透過查詢 read model 實現，非從 event stream 重建 aggregate。

### 2.3 Research Citations

- [Spring `@EventListener`](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events) — 同步事件處理，`publishEvent()` 時立即觸發。[POC validated] `@ApplicationModuleListener` 需要額外 infra（events-core + @EnableAsync + transaction），MVP 過重
- [agentskills.io SKILL.md spec](https://agentskills.io/specification) — Required frontmatter: `name` (lowercase-hyphen, max 64), `description` (max 1024). Optional: `license`, `version`, `author`, `compatibility`, `metadata`, `allowed-tools`
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml/) — Bundled with Spring Boot, `org.yaml.snakeyaml.Yaml`
- S000 §7 validated patterns — `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` for MongoDB integration tests; GCP auto-config disabled in test `application.yaml`

## 3. SBE Acceptance Criteria

Verification command:

    Run: cd backend && ./gradlew test
    Pass: all tests carrying S001 AC ids are green.

---

**AC-1: 建立新 Skill（Command Side）**

```
Given 一個合法的 CreateSkillCommand（name="docker-helper", description="Docker compose helper", author="sam", category="DevOps"）
When  POST /api/v1/skills with JSON body
Then  domain_events collection 新增一筆 SkillCreated event
And   event 的 aggregateType = "Skill", eventType = "SkillCreated"
And   event 的 payload 含 name, description, author, category
And   skills read model collection 同步建立對應 document
And   回傳 201 Created，body 含 { "id": "<uuid>" }
```

**AC-2: 取得 Skill 詳情（Query Side）**

```
Given 已有一個由 AC-1 建立的 skill（id 為回傳值）
When  GET /api/v1/skills/{id}
Then  回傳 200 + JSON body 含 id, name, description, author, category, status, createdAt
And   status 為 "DRAFT"
```

**AC-3: SKILL.md frontmatter 驗證 — 成功**

```
Given 一個含合法 frontmatter 的 SKILL.md 內容:
      ---
      name: docker-helper
      description: Docker compose helper
      ---
When  SkillValidator.validate(content) 解析
Then  回傳 valid = true
And   回傳解析後的 metadata（name, description）
```

**AC-4: SKILL.md frontmatter 驗證 — 失敗**

```
Given 一個缺少 name 欄位的 SKILL.md 內容:
      ---
      description: Some skill
      ---
When  SkillValidator.validate(content) 解析
Then  回傳 valid = false
And   errors 包含 "Missing required field: name"
```

**AC-5: Event Store 完整性**

```
Given 對同一 skill 執行 create + publishVersion commands
When  查詢 domain_events by aggregateId
Then  回傳 2 筆 events，按 sequence 排序
And   sequence 分別為 1, 2
And   eventType 分別為 "SkillCreated", "SkillVersionPublished"
```

## 4. Interface / API Design

### 4.1 Domain Events (skill/domain/)

```java
// Application event published after SkillCreated DomainEvent is stored
public record SkillCreatedEvent(
    String aggregateId,
    String name,
    String description,
    String author,
    String category
) {}

// Application event published after SkillVersionPublished DomainEvent is stored
public record SkillVersionPublishedEvent(
    String aggregateId,
    String version,
    String storagePath
) {}
```

Domain events are published as Spring application events. The full event data is also persisted as a `DomainEvent` record in the event store (shared module).

### 4.2 Commands (skill/command/)

```java
public record CreateSkillCommand(
    String name,         // required, lowercase-hyphen, max 64
    String description,  // required, max 1024
    String author,       // required
    String category      // required
) {}

public record PublishVersionCommand(
    String skillId,      // target skill aggregate
    String version,      // semver string
    String storagePath   // GCS path (provided by storage module in S003)
) {}
```

### 4.3 Command Service (skill/command/)

```java
@Service
public class SkillCommandService {
    private final DomainEventRepository eventStore;
    private final ApplicationEventPublisher events;

    // Create new skill → SkillCreated event
    public String createSkill(CreateSkillCommand cmd) {
        // 1. Validate command (name format, required fields)
        // 2. Generate aggregateId (UUID)
        // 3. Build DomainEvent(aggregateId, "Skill", "SkillCreated", payload, seq=1)
        // 4. eventStore.save(domainEvent)
        // 5. events.publishEvent(new SkillCreatedEvent(...))
        // 6. return aggregateId
    }

    // Publish version → SkillVersionPublished event
    public void publishVersion(PublishVersionCommand cmd) {
        // 1. Find latest sequence for this aggregateId
        // 2. Build DomainEvent(aggregateId, "Skill", "SkillVersionPublished", payload, seq+1)
        // 3. eventStore.save(domainEvent)
        // 4. events.publishEvent(new SkillVersionPublishedEvent(...))
    }
}
```

### 4.4 Command Controller (skill/command/)

```
POST /api/v1/skills
  Request:  { "name": "docker-helper", "description": "...", "author": "sam", "category": "DevOps" }
  Response: 201 Created { "id": "uuid-string" }
  Error:    400 Bad Request { "error": "VALIDATION_ERROR", "message": "...", "timestamp": "..." }
```

### 4.5 Read Model (skill/query/)

```java
@Document("skills")
public record SkillReadModel(
    @Id String id,
    String name,
    String description,
    String author,
    String category,
    String latestVersion,    // updated by SkillVersionPublished projection
    String status,           // DRAFT (initial), PUBLISHED, SUSPENDED
    long downloadCount,      // updated by SkillDownloaded projection (S006)
    Instant createdAt,
    Instant updatedAt
) {}
```

**Example data (2 rows):**

| id | name | description | author | category | latestVersion | status | downloadCount | createdAt |
|----|------|-------------|--------|----------|---------------|--------|---------------|-----------|
| `abc-123` | `docker-helper` | Docker compose helper | sam | DevOps | `1.0.0` | DRAFT | 0 | 2026-04-25T10:00:00Z |
| `def-456` | `k8s-deploy` | K8s deployment skill | jane | DevOps | null | DRAFT | 0 | 2026-04-25T10:05:00Z |

### 4.6 Read Model Repository (skill/query/)

```java
public interface SkillReadModelRepository extends MongoRepository<SkillReadModel, String> {
}
```

### 4.7 Projection (skill/query/)

```java
@Component
public class SkillProjection {
    private final SkillReadModelRepository repo;

    @EventListener
    void on(SkillCreatedEvent event) {
        // Build SkillReadModel from event fields → repo.save()
    }

    @EventListener
    void on(SkillVersionPublishedEvent event) {
        // repo.findById(event.aggregateId()) → update latestVersion → repo.save()
    }
}
```

### 4.8 Query Controller (skill/query/)

```
GET /api/v1/skills/{id}
  Response: 200 { "id": "...", "name": "...", ... }
  Error:    404 { "error": "SKILL_NOT_FOUND", "message": "...", "timestamp": "..." }

GET /api/v1/skills?page=0&size=20&sort=name,asc
  Response: 200 { "content": [...], "page": { "number": 0, "size": 20, "totalElements": 50, "totalPages": 3 } }
```

### 4.9 SKILL.md Validator (skill/validation/)

```java
public record ValidationResult(
    boolean valid,
    Map<String, Object> metadata,  // parsed frontmatter fields
    List<String> errors            // empty if valid
) {}

@Component
public class SkillValidator {
    // Extract YAML between --- delimiters, parse with SnakeYAML,
    // validate required fields (name, description)
    public ValidationResult validate(String skillMdContent) { ... }
}
```

### 4.10 Security Config (SecurityConfig in root package)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
```

### 4.11 Error Response (shared/api/)

```java
public record ErrorResponse(
    String error,
    String message,
    Instant timestamp
) {}

@RestControllerAdvice
public class GlobalExceptionHandler {
    // Handle validation errors → 400
    // Handle not found → 404
}
```

## 5. File Plan

Package base: `io.github.samzhu.skillshub` (abbreviated as `...` below)

| # | File | Action | Description |
|---|------|--------|-------------|
| **Domain events** |||
| 1 | `.../skill/domain/SkillCreatedEvent.java` | new | Application event record |
| 2 | `.../skill/domain/SkillVersionPublishedEvent.java` | new | Application event record |
| **Command side** |||
| 3 | `.../skill/command/CreateSkillCommand.java` | new | Command record |
| 4 | `.../skill/command/PublishVersionCommand.java` | new | Command record |
| 5 | `.../skill/command/SkillCommandService.java` | new | Service: validate → save event → publish (no @Transactional needed) |
| 6 | `.../skill/command/SkillCommandController.java` | new | POST /api/v1/skills |
| **Query side** |||
| 7 | `.../skill/query/SkillReadModel.java` | new | @Document("skills") read model |
| 8 | `.../skill/query/SkillReadModelRepository.java` | new | MongoRepository for read model |
| 9 | `.../skill/query/SkillProjection.java` | new | @EventListener → update read model |
| 10 | `.../skill/query/SkillQueryService.java` | new | Query service (findById, findAll) |
| 11 | `.../skill/query/SkillQueryController.java` | new | GET /api/v1/skills, GET /api/v1/skills/{id} |
| **Validation** |||
| 12 | `.../skill/validation/SkillValidator.java` | new | SKILL.md frontmatter parser + validator |
| 13 | `.../skill/validation/ValidationResult.java` | new | Validation result record |
| **Shared / infrastructure** |||
| 14 | `.../shared/api/ErrorResponse.java` | new | Unified error response record |
| 15 | `.../shared/api/GlobalExceptionHandler.java` | new | @RestControllerAdvice |
| 16 | `.../SecurityConfig.java` | new | MVP: permitAll() |
| **Tests** |||
| 17 | `.../skill/command/SkillCommandServiceTest.java` | new | AC-1, AC-5: integration test |
| 18 | `.../skill/query/SkillQueryControllerTest.java` | new | AC-2: integration test |
| 19 | `.../skill/validation/SkillValidatorTest.java` | new | AC-3, AC-4: unit test |
| 20 | `.../skill/SkillIntegrationTest.java` | new | AC-1+AC-2 end-to-end: POST then GET |

## 6. Task Plan

### POC Findings

**POC: required** — validated transactional event flow design.

| Hypothesis | Verdict |
|------------|---------|
| `@ApplicationModuleListener` works out of the box with `spring-modulith-starter-core` | ❌ FAIL — requires `spring-modulith-events-core` + `spring-modulith-events-mongodb` + `@EnableAsync` + MongoDB replica set (transactions). Too heavy for MVP |
| `@EventListener` (synchronous) works for event-driven projection | ✅ PASS — no transaction required, fires immediately during `publishEvent()` |
| `@Transactional` needed on command service | ❌ No — `@EventListener` works without transactions |
| Additional dependencies needed | ❌ No — Spring core `@EventListener` is sufficient |

**Design revision applied:** Changed from `@ApplicationModuleListener` to `@EventListener` in §2 Key Decision #3, §4.7, §4.9. Removed `@Transactional` from §4.3. No new dependencies needed.

### Task Overview

| Task | Description | AC Coverage | Depends On | Status |
|------|-------------|-------------|------------|--------|
| T1 | Security config + shared API infrastructure | Infra (enables API access) | none | PASS |
| T2 | Command side — SkillCreated event + POST API | AC-1, AC-5 | T1 | PASS |
| T3 | Query side — Projection + Read Model + GET API | AC-2 | T2 | PASS |
| T4 | SKILL.md frontmatter validator | AC-3, AC-4 | T1 | PASS |

### AC Coverage Matrix

| AC | Task(s) | Verification |
|----|---------|-------------|
| AC-1: 建立新 Skill | T2 | `SkillCommandServiceTest` |
| AC-2: 取得 Skill 詳情 | T3 | `SkillIntegrationTest` |
| AC-3: Frontmatter 驗證成功 | T4 | `SkillValidatorTest` |
| AC-4: Frontmatter 驗證失敗 | T4 | `SkillValidatorTest` |
| AC-5: Event Store 完整性 | T2 | `SkillCommandServiceTest` |

## 7. Implementation Results

### Verification Results

```
./gradlew test → BUILD SUCCESSFUL (10 tests, 0 failures)
./gradlew compileTestJava → BUILD SUCCESSFUL
```

### Key Findings

1. **SecurityConfig.java removed** — Spring Security dependency (`spring-boot-starter-security-oauth2-resource-server`) is commented out in `build.gradle.kts` per "Feature First, Security Later" principle. Without Spring Security on classpath, all endpoints are accessible by default. An orphaned `SecurityConfig.java` was causing compilation errors and was deleted.

2. **Spring Modulith `@NamedInterface` required for shared sub-packages** — `shared.events` and `shared.api` are sub-packages, so Spring Modulith treats them as internal by default. Added `@NamedInterface("events")` and `@NamedInterface("api")` package-info annotations. The `skill` module's `allowedDependencies` was updated to `{"shared :: events", "shared :: api"}`.

3. **`@EventListener` (synchronous) works perfectly for MVP** — No `@Transactional`, no MongoDB replica set, no extra dependencies. Event published → listener fires immediately → read model updated. If listener throws, command fails too (consistency guaranteed).

4. **SkillReadModel as immutable record** — MongoDB `@Document("skills")`, created by `SkillProjection` on `SkillCreatedEvent`, updated on `SkillVersionPublishedEvent`.

### Correct API Usage Patterns

```java
// Command: create skill → event store + publish
var aggregateId = UUID.randomUUID().toString();
var domainEvent = new DomainEvent(id, aggregateId, "Skill", "SkillCreated", payload, 1L, Instant.now(), Map.of());
eventStore.save(domainEvent);
events.publishEvent(new SkillCreatedEvent(aggregateId, name, description, author, category));

// Projection: @EventListener (synchronous, no @Transactional needed)
@EventListener
void on(SkillCreatedEvent event) {
    repo.save(new SkillReadModel(event.aggregateId(), ...));
}

// Query: GET from read model
repo.findById(id).orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));

// SKILL.md validator: SnakeYAML frontmatter extraction
var yaml = new Yaml();
Map<String, Object> parsed = yaml.load(extractFrontmatter(content));
```

### AC Results

| AC | Status | Evidence |
|----|--------|----------|
| AC-1: 建立新 Skill | ✅ PASS | `SkillCommandServiceTest.createSkillViaApi` — POST /api/v1/skills → 201, event in store |
| AC-2: 取得 Skill 詳情 | ✅ PASS | `SkillIntegrationTest.postThenGetSkill` — POST then GET, all fields match |
| AC-3: Frontmatter 驗證成功 | ✅ PASS | `SkillValidatorTest.validFrontmatter` — valid=true, metadata extracted |
| AC-4: Frontmatter 驗證失敗 | ✅ PASS | `SkillValidatorTest.missingNameField` — valid=false, error message correct |
| AC-5: Event Store 完整性 | ✅ PASS | `SkillCommandServiceTest.eventStoreIntegrity` — 2 events, sequence 1,2 |

### E2E Verification

E2E not required beyond integration tests — `@SpringBootTest` with Testcontainers exercises the full event flow (HTTP → command → event store → publish → projection → read model → query) with a real MongoDB instance.

### Pending Verification

None — all tests run and pass with Testcontainers.

---

## 8. QA Review

**Reviewer:** Independent QA subagent
**Review Date:** 2026-04-25
**Verdict:** ✅ PASS (with minor findings documented below)

---

### Layer 1 — Automated Test Gate

| Command | Result |
|---------|--------|
| `./gradlew compileTestJava` | BUILD SUCCESSFUL — 0 errors |
| `./gradlew test` | BUILD SUCCESSFUL — 10 tests, 0 failures, 0 errors |

**Compiler warning (non-blocking):** `SkillCommandServiceTest.java` emits an unchecked/unsafe operations note at compile time. Root cause: raw `Map.class` used in `restTemplate.postForEntity(…, java.util.Map.class)` on line 36. This is a minor style issue — test logic is correct and the warning does not affect correctness or coverage.

---

### Layer 2 — AC-to-Test Coverage Matrix

| AC | Required @DisplayName | Test File | Found | Green |
|----|----------------------|-----------|-------|-------|
| AC-1 | "AC-1: 建立新 Skill …" | `SkillCommandServiceTest` | ✅ | ✅ |
| AC-2 | "AC-2: 取得 Skill 詳情 …" | `SkillIntegrationTest` | ✅ | ✅ |
| AC-3 | "AC-3: SKILL.md frontmatter 驗證 — 成功" | `SkillValidatorTest` | ✅ | ✅ |
| AC-4 | "AC-4: SKILL.md frontmatter 驗證 — 失敗" | `SkillValidatorTest` | ✅ (×3 variants) | ✅ |
| AC-5 | "AC-5: Event Store 完整性 …" | `SkillCommandServiceTest` | ✅ | ✅ |

All 5 ACs have matching `@DisplayName` tests. AC-4 is over-covered with 3 test variants (missing name, missing description, no frontmatter) — positive finding.

**Note (§5 vs reality):** Spec §5 File Plan lists `SkillQueryControllerTest` (file #18) as a test for AC-2. In the implemented code AC-2 is instead covered by `SkillIntegrationTest` (file #20). The `SkillQueryControllerTest` file was not created. AC-2 is fully verified by `SkillIntegrationTest`, so this is a documentation inconsistency in the file plan, not a missing test.

---

### Layer 3 — Code Quality vs development-standards.md

| Check | Result | Detail |
|-------|--------|--------|
| Constructor injection (no `@Autowired` fields) | ✅ | All services use constructor injection |
| Record types for Commands, Events, DTOs | ✅ | All commands, events, read model, error response are records |
| `@RestController` + `@RequestMapping` on controllers | ✅ | Both `SkillCommandController` and `SkillQueryController` conform |
| API prefix `/api/v1/` | ✅ | Both controllers use `/api/v1/skills` |
| Pagination on list endpoint | ✅ | `GET /api/v1/skills` accepts `Pageable` |
| Error response format (`error`, `message`, `timestamp`) | ✅ | `ErrorResponse` record + `GlobalExceptionHandler` match spec §4.11 |
| Module boundary via Spring Modulith | ✅ | `skill/package-info.java` declares `allowedDependencies = {"shared :: events", "shared :: api"}`; `ModularityTests` verifies and passes |
| `@EventListener` (synchronous) for projection | ✅ | `SkillProjection` uses `@EventListener`, consistent with §2 Key Decision #3 |
| No `SecurityConfig.java` on classpath | ✅ | Spring Security commented out in `build.gradle.kts`; no `SecurityConfig.java` present (deleted per §7 finding) |
| `@Transactional` absent on command service | ✅ | `SkillCommandService` has no `@Transactional` annotation |
| Integration tests use `@Import(TestcontainersConfiguration.class)` | ✅ | All Spring Boot integration tests import the Testcontainers config |
| Test naming: `@DisplayName("AC-N: …")` | ✅ | All S001 tests follow the convention |

**Minor deviation — 404 error code string:** `GlobalExceptionHandler` returns `"NOT_FOUND"` for `NoSuchElementException`. Spec §4.8 example shows `"SKILL_NOT_FOUND"`. This is a cosmetic inconsistency; the HTTP 404 status code is correct and the error response structure conforms to the standard. No test currently asserts the exact error code string on 404, so this is not a test failure but a spec drift worth noting for S002+ when the UI may depend on the error code.

---

### Layer 4 — Design Drift Check (§2/§4 vs Code)

| Design Intent | Actual Code | Verdict |
|---------------|-------------|---------|
| MVP Aggregate: factory methods, no event replay | `SkillCommandService` generates UUID + builds `DomainEvent` directly — no aggregate class. Slightly simpler than §2 describes (no separate Aggregate class), but intent is met | ✅ Within MVP scope |
| Sequence for `publishVersion` read from event store | `findTopByAggregateIdOrderBySequenceDesc` used to get latest seq, then +1 | ✅ Matches §4.3 |
| `SkillReadModel` as immutable `@Document("skills")` record | Implemented exactly as §4.5 | ✅ |
| `SkillVersionPublishedEvent` updates `latestVersion` in projection | `SkillProjection.on(SkillVersionPublishedEvent)` performs `findById` → rebuild record with new `version` → save | ✅ |
| SKILL.md required fields: `name`, `description` | `REQUIRED_FIELDS = List.of("name", "description")` | ✅ |
| `@ApplicationModuleListener` → changed to `@EventListener` per POC | `@EventListener` used in `SkillProjection` | ✅ POC revision applied correctly |

---

### Summary of Findings

| Severity | Finding |
|----------|---------|
| INFO | `SkillCommandServiceTest` uses raw `Map.class` — emits unchecked compiler warning. Consider using `ParameterizedTypeReference<Map<String, String>>` or a typed response record |
| INFO | `SkillQueryControllerTest` listed in §5 File Plan was not created; coverage responsibility moved to `SkillIntegrationTest`. File plan should be updated to reflect actual files |
| INFO | 404 error code is `"NOT_FOUND"` in code vs `"SKILL_NOT_FOUND"` in spec §4.8 example. No test asserts the string today, but downstream UI code (S002) should be aware |

No blocking issues found. All 5 ACs are covered by passing tests. Build is clean. Module boundaries verified.
