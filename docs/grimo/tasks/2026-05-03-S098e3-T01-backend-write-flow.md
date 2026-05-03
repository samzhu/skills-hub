# S098e3-T01: Backend write flow — FlagStatus enum + status mutations + cross-skill list

## Spec
S098e3 — Flag Write Flow + Reviewer Queue（spec doc: `docs/grimo/specs/2026-05-03-S098e3-flag-write-flow.md`）

## BDD（涵蓋的 AC）

**AC-1: createFlag with reporter identity** — POST 帶登入身份 → DB `reported_by="alice"`（非 "anonymous"）。
**AC-2: anonymous fallback** — 無 Auth header → DB `reported_by="anonymous"`。
**AC-3: cross-skill OPEN list** — `GET /api/v1/flags?status=OPEN` → 跨 skill 過濾。
**AC-4: cross-skill 全部** — `GET /api/v1/flags`（無 filter）→ 全 status。
**AC-5: per-skill status filter** — `GET /api/v1/skills/S/flags?status=OPEN`。
**AC-6: PATCH resolve** — `PATCH /api/v1/skills/S/flags/{id}` body `{status:"RESOLVED"}` → 204 + DB UPDATE + FlagStatusChangedEvent publish。
**AC-7: invalid transition** — RESOLVED → OPEN 或 bogus status → 400 invalid_status_transition。
**AC-8: flag not found** — 404 flag_not_found。

## Implementation outline

### `security/FlagStatus.java` (new)

```java
public enum FlagStatus {
    OPEN, RESOLVED, DISMISSED;

    public boolean canTransitionTo(FlagStatus target) {
        return switch (this) {
            case OPEN -> target == RESOLVED || target == DISMISSED;
            case RESOLVED, DISMISSED -> false;
        };
    }
}
```

### `security/FlagService.java` (modify)

- `createFlag(skillId, type, description, reporter)` — 加 reporter 參數；line 123 `reportedBy` 從 `"anonymous"` 改為 `reporter ?? "anonymous"`
- 新 `updateStatus(flagId, newStatus, actor)`：load + canTransitionTo 檢查 + UPDATE + publish FlagStatusChangedEvent
- 新 `listAllFlags(Optional<String> statusFilter)`：repo derived query
- 既有 `getFlagsBySkillId(skillId)` 加 status filter overload

### `security/FlagController.java` (modify)

- `POST` createFlag inject CurrentUserProvider，抽 sub 給 reporter
- `GET` getFlags 加 `?status=` query param 透傳給 service
- 新 `PATCH /api/v1/skills/{skillId}/flags/{flagId}` body `{status: "RESOLVED"|"DISMISSED"}`

### `security/FlagAdminQueryController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/flags")
class FlagAdminQueryController {
    @GetMapping
    List<FlagReadModel> list(@RequestParam(required = false) String status) {
        return flagService.listAllFlags(Optional.ofNullable(status));
    }
}
```

### `security/FlagReadModelRepository.java` (modify)

- `findAllByOrderByCreatedAtDesc()` — AC-4
- `findByStatusOrderByCreatedAtDesc(String status)` — AC-3
- `findBySkillIdAndStatusOrderByCreatedAtDesc(String skillId, String status)` — AC-5

### Events + exceptions

- `security/FlagStatusChangedEvent.java` (record): `(flagId, skillId, oldStatus, newStatus, actor, changedAt)`
- `shared/api/InvalidStatusTransitionException.java` + `FlagNotFoundException.java` + GlobalExceptionHandler 加 400/404 mapping

### Tests

- `FlagServiceTest.java` (new — 既有 S058/S072 path 已有但無此檔；建之) — Testcontainers AC-1/2/6/7
- `FlagControllerTest.java` (modify or new) — slice mock service AC-3/4/5/8
- `FlagAdminQueryControllerTest.java` (new) — slice AC-3/4

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagStatus.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java` (modify)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagController.java` (modify)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagAdminQueryController.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagReadModelRepository.java` (modify)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagStatusChangedEvent.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/InvalidStatusTransitionException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/FlagNotFoundException.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` (modify)
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagServiceTest.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagControllerTest.java` (modify — 既有 file)
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagAdminQueryControllerTest.java` (new)

## Depends On
none

## Status
pending
