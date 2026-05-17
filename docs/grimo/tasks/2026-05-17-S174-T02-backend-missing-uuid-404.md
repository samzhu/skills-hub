# S174-T02: Backend Missing UUID Returns 404 Before Permission Deny

## 對應規格
S174：Skill detail anonymous 401 not-found UX

## 這個 task 要做什麼
`GET /api/v1/skills/{id}` 收到合法 UUID 但資料庫沒有該 skill 時，API 要先進 `SkillQueryService.findById()`，由 `NoSuchElementException` 回 404 `NOT_FOUND`。如果資料庫真的有 private skill，但 anonymous 沒權限，仍要回 401，且 response 不能輸出 private skill JSON。

## 使用者情境（BDD）
Given（前提）資料庫沒有 id `00000000-0000-0000-0000-000000000000` 的 skill
When（動作）anonymous request `GET /api/v1/skills/00000000-0000-0000-0000-000000000000`
Then（結果）HTTP status 是 404
And（而且）response body error 是 `NOT_FOUND`

Given（前提）資料庫有一筆 private skill，且 anonymous 沒有 read permission
When（動作）anonymous request `GET /api/v1/skills/{private-id}`
Then（結果）HTTP status 仍是 401
And（而且）response 不輸出該 private skill 的 `name` / `description` / `author` / `version` / `viewerPermissions`

## 研究來源
- `docs/grimo/specs/2026-05-17-S174-skill-detail-anonymous-not-found-ux.md`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`

## 先做 POC
- POC：not required — 沿用同一 controller 既有 `getByAuthorAndName` 的 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")` pattern。

## 正式程式怎麼做
- Class / file 名稱：`SkillQueryController.java`
- 入口：`GET /api/v1/skills/{id}`
- 必要行為：
  - `getById` 從 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` 改為 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`
  - 找不到 UUID 時，`SkillQueryService.findById()` 拋 `NoSuchElementException`，`GlobalExceptionHandler` 回 404 `NOT_FOUND`
  - private existing skill 仍由 permission evaluator deny，anonymous 回 401

## 單元測試 / 整合測試
- `SkillQueryControllerApiContractTest`
  - `AC-S174-1: anonymous GET missing UUID returns 404 NOT_FOUND before permission deny`
  - `AC-S174-2: anonymous GET private existing skill still returns 401 without skill JSON`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillQueryControllerApiContractTest"`

## 前置條件
- S174-T01 PASS

## 狀態
PASS

## Result

RED：
- `cd backend && ./gradlew test --tests "*SkillQueryControllerApiContractTest"` -> FAIL，`AC-S174-1` 仍收到 401，表示 `@PreAuthorize` 在 `SkillQueryService.findById()` 前先拒絕。

GREEN：
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` 將 `getById` 改為 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`。
- `cd backend && ./gradlew test --tests "*SkillQueryControllerApiContractTest"` -> PASS，1 class / 7 tests。
