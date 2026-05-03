# S112-T01: Backend `/me/flags-summary` endpoint + service + tests

## Spec
S112 — Flag wiring full-stack（spec doc: `docs/grimo/specs/2026-05-03-S112-flag-wiring-full-stack.md`）

## BDD

**AC-5 — endpoint shape**
- Given：current user `sub="alice"`，alice 有 1 個 PUBLISHED skill 含 1 個 OPEN flag
- When：發 `GET /api/v1/me/flags-summary`
- Then：回 200 + body `{"openCount": 1}`，Content-Type `application/json`

**AC-6 — user isolation + status filter**
- Given：alice 有 1 個 PUBLISHED skill 含 1 個 OPEN flag；bob 有 1 個 PUBLISHED skill 含 5 個 OPEN flags；alice 也有 1 個 DRAFT skill 含 3 個 OPEN flags
- When：以 alice 身份發 `GET /api/v1/me/flags-summary`
- Then：回 `{"openCount": 1}` — 不含 bob 的（user 隔離）+ 不含 alice DRAFT skill 的（status filter）

**AC-7 — 0-skill graceful**
- Given：alice 無任何 PUBLISHED skill（只 DRAFT 或 0 skill）
- When：alice 發 `GET /api/v1/me/flags-summary`
- Then：回 `{"openCount": 0}`，不丟 error

## Implementation outline

1. **`FlagService.countOpenFlagsForAuthor(String author): long`** — `NamedParameterJdbcTemplate` 跨表 SQL：
   ```sql
   SELECT COUNT(*) FROM flags f
   WHERE f.status = 'OPEN'
     AND f.skill_id IN (
         SELECT id FROM skills WHERE author = :author AND status = 'PUBLISHED'
     )
   ```
   Null-safe：`queryForObject(..., Long.class)` 回 null 時 return 0L
2. **`MeController` 加新方法** `@GetMapping("/flags-summary") flagsSummary()`：
   ```java
   var userId = users.current().userId();
   long openCount = flagService.countOpenFlagsForAuthor(userId);
   return Map.of("openCount", openCount);
   ```
3. **Constructor 注入 `FlagService`**（既有 bean，跨 module 引用 OK — Modulith 已 wire）
4. **Tests**：
   - `MeControllerTest` 加 AC-5（@DisplayName("AC-5: GET /me/flags-summary 回 openCount JSON")）
   - `FlagServiceTest` 加 AC-6（@DisplayName("AC-6: countOpenFlagsForAuthor 過濾 user + PUBLISHED status")）
   - `FlagServiceTest` 加 AC-7（@DisplayName("AC-7: 無 PUBLISHED skill 回 0 不丟 error")）
   - 用 Testcontainers + 真實 PostgreSQL；test data setup 走既有 helper / @Sql script

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java` — 新增 `countOpenFlagsForAuthor` method
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/MeController.java` — 加 `/flags-summary` endpoint + 注入 `FlagService`
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagServiceTest.java` — 加 AC-6 / AC-7 tests（檔不存在則新建）
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/MeControllerTest.java` — 加 AC-5 test（檔不存在則新建）

## Depends On
none

## Status
pending
