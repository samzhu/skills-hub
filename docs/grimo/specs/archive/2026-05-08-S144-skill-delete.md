# S144: Skill 刪除功能

> Spec: S144 | Size: S(9) | Status: ✅ Done
> Date: 2026-05-08（修訂 2026-05-11）
> Origin: site audit 2026-05-08 — OWNER role 已有 `delete` 權限宣告，但無 DELETE API endpoint 也無前端入口

---

## 1. Goal

讓 skill owner 可以從「我的技能」永久刪除自己發布的 skill；刪除後 API 回 204，DB 不再有該 skill 的可見資料，UI 立即從「我的技能」與 `/browse` 移除。

```
Owner 點刪除
  -> DELETE /api/v1/skills/{id}
  -> skills row 被刪除
  -> FK cascade 清 skill_versions/download_events/vector_store/reviews/flags/skill_grants
  -> service 額外清 soft-FK user-facing rows
  -> outbox 發 SkillDeletedEvent
  -> storage listener 非同步刪 zip 檔
```

**非目標：**
- 不做 soft delete；`skills` 主 row 直接硬刪。
- 不做版本選擇性刪除；整個 skill 含所有版本一起刪。
- 不做 admin bulk delete。
- 不刪 `domain_events` audit log；該表是永久事件記錄，刪除本身會新增一筆 `SkillDeleted` audit row。
- 不保證已下載到使用者本機的 zip 失效；平台只移除 registry 可見性與未來下載入口。

**相依關係：**
- S163 是 ordering-only：S163 UI 會在 detail page 放 `[刪除]` button，但 S144 的 backend API 與 MySkillsPage 入口可獨立設計。
- S162c 是 pattern dependency：owner-only 拒絕要回 403；本 spec 直接用 `@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")` 對齊。

---

## 2. Approach

### 2.1 現況

| 檔案 / 表 | 目前事實 | 對 S144 的影響 |
|----------|----------|----------------|
| `Role.OWNER.permissions()` | OWNER 已有 `read/write/delete` | 權限語意已存在，只缺 endpoint |
| `SkillPermissionStrategy` | `acl_entries ??| :patterns` 檢查 `delete` verb | `@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")` 可直接重用 |
| `V16__rbac_acl_projection.sql` | ACL source-of-truth 是 `skill_grants`，不是舊 `skill_acl` | 原 spec 的 `skill_acl` 刪除步驟已過時 |
| `domain_events` | architecture.md 明確保留為 audit/event log | 原 spec 的「刪 domain_events」違反架構，要修正 |
| `skill_versions/download_events/vector_store/reviews/flags/skill_grants` | FK 指向 `skills(id) ON DELETE CASCADE` | 刪 `skills` row 時由 DB cascade |
| `skill_subscriptions/skill_scores/collection_skills/notifications` | soft-FK 或無 FK | service 要明確清理，避免 orphan 仍出現在 user-facing flows |

### 2.2 Approach Comparison

| Approach | Pros | Cons | Recommendation |
|----------|------|------|----------------|
| A: `deleteById(id)` + 依賴 FK cascade | 最少 code | Spring Data 不會拿到 aggregate instance，所以不會發布 aggregate domain event；GCS zip 沒 async cleanup 入口；不存在 id 時不好區分 404 | |
| B: `findById` -> `skill.markDeleted(...)` -> soft-FK cleanup -> `skillRepo.delete(skill)` | 發 `SkillDeletedEvent`；符合 Spring Data aggregate event 機制；404/403/204 清楚；DB cascade 與手動 cleanup 分工明確 | 比 A 多一個 service method + event + listener | **Chosen** |
| C: 先 soft-hide，再背景 job 真刪 | 可恢復、可 retry | 違反本 spec「硬刪」目標；要新增 status / job / UI 狀態，範圍變大 | |

**Chosen：B。** `SkillCommandService.deleteSkill(id)` 先載入 aggregate，註冊 `SkillDeletedEvent`，清 soft-FK 表，再呼叫 `skillRepo.delete(skill)`；`skills` FK cascade 清掉核心資料，GCS zip 由 event listener 非同步刪。

### 2.3 Backend Flow

```java
@DeleteMapping("/{id}")
@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")
ResponseEntity<Void> delete(@PathVariable String id) {
    commandService.deleteSkill(id, currentUserProvider.userId());
    return ResponseEntity.noContent().build();
}
```

`SkillCommandService.deleteSkill(id, deletedBy)`：

1. `skillRepo.findById(id).orElseThrow(() -> new NoSuchElementException(id))`
2. `versionRepo.findBySkillIdOrderByPublishedAtDesc(id)` 取 `storagePath` 清單
3. `skill.markDeleted(deletedBy, storagePaths)` 註冊 `SkillDeletedEvent`
4. 同 transaction 清 soft-FK rows：
   - `DELETE FROM skill_subscriptions WHERE skill_id = :id`
   - `DELETE FROM skill_scores WHERE skill_id = :id`
   - `DELETE FROM collection_skills WHERE skill_id = :id`
   - `DELETE FROM notifications WHERE skill_id = :id`
5. `skillRepo.delete(skill)`：DB cascade 清 `skill_versions/download_events/vector_store/reviews/flags/skill_grants`

**為什麼不刪 `domain_events`：** `domain_events` 是 audit log，architecture.md 明確說永久保留，刪除 skill 時應新增 `SkillDeleted` 事件，而不是刪掉歷史。

**為什麼不直接手動刪所有 FK 表：** `V1/V8/V16` 已有 `ON DELETE CASCADE`，讓 PostgreSQL 做 FK 順序比 application code 一張張刪更準；service 只處理沒有 FK 的 soft references。

### 2.4 Domain Event + Storage Cleanup

新增 event：

```java
public record SkillDeletedEvent(
        String aggregateId,
        String name,
        String deletedBy,
        Instant deletedAt,
        List<String> storagePaths
) {}
```

新增 listener 放在 `skill` module，例如 `SkillPackageCleanupListener`：

```java
@ApplicationModuleListener
void on(SkillDeletedEvent event) {
    event.storagePaths().forEach(storageService::delete);
}
```

Listener 放在 `skill` module 而不是 `storage` module，因為目前 `skill` module 已允許依賴 `storage`；如果讓 `storage` 反向 import `skill :: domain`，會造成 module cycle。

Storage cleanup 是 best-effort async：
- API response 不等 GCS / filesystem delete 完成。
- listener 每個 path 記 log；單一路徑刪除失敗要 throw，讓 Modulith outbox retry。
- event payload 只放 path list，不放 zip bytes 或大型內容。

### 2.5 Audit Event

`AuditEventListener` 新增 `on(SkillDeletedEvent)`：

```java
recordAudit(event.aggregateId(), "SkillDeleted",
    Map.of("name", event.name(), "deletedBy", event.deletedBy()),
    dedupKey("SkillDeleted", event.aggregateId()));
```

`dedupKey` 用 `SkillDeleted|{aggregateId}`：同一 skill 只能刪一次；Modulith retry 重投時同 UUID 被 `ON CONFLICT` 跳過。

### 2.6 Frontend Flow

`MySkillsPage` 每列 skill 右側新增 kebab menu：

- `檢視`：link 到 `/skills/{id}`（保留既有 row click 能力）
- `刪除`：開確認 dialog

確認 dialog：

```
確定要刪除「{name}」嗎？
此操作無法復原，所有版本與下載記錄都將一併刪除。

[取消] [確定刪除]
```

成功後：
- `DELETE /api/v1/skills/{id}` 回 204
- invalidate `['skills', 'list', ...]` 或直接 refetch MySkillsPage list
- toast：`技能已刪除`
- list row 消失

失敗後：
- 403：toast `刪除失敗：沒有權限執行此操作。`
- 404：toast `刪除失敗：找不到指定的資源。`
- 其他：toast `刪除失敗：{localizeApiError(err)}`

[Implementation note] T03 使用共用 `localizeApiError`，而非在 `MySkillsPage` 寫一組刪除專用文案；這讓 `FORBIDDEN` / `NOT_FOUND` 與其他 mutation error 翻譯一致。

### 2.7 Research Citations

| Topic | Finding | Source |
|-------|---------|--------|
| Spring Data aggregate events | `@DomainEvents` 會在 repository `save(...)` / `delete(...)` 發布；`deleteById(...)` 明確不在清單，因為實作可能直接發 SQL 而拿不到 aggregate instance。 | https://docs.spring.io/spring-data/jdbc/docs/3.1.9/reference/html/#repositories.domain-events |
| `AbstractAggregateRoot` | `registerEvent(...)` 是為了在 repository `save` 或 `delete` 時發布事件。 | https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/AbstractAggregateRoot.html |
| Spring Modulith async listener | `@ApplicationModuleListener` 是 async + `REQUIRES_NEW` + transactional event listener，適合讓原交易 commit 後再跑 cleanup。 | https://docs.spring.io/spring-modulith/docs/2.0.0-M3/api/org/springframework/modulith/events/ApplicationModuleListener.html |
| Spring Security permission evaluator | `PermissionEvaluator.hasPermission(authentication, targetId, targetType, permission)` 支援只拿 id 做 method security 判斷。 | https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/access/PermissionEvaluator.html |

### 2.8 Confidence

| Decision | Confidence | Rationale |
|----------|------------|-----------|
| `@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")` | Validated | `Role.OWNER` 已有 delete verb；現有 PUT version / suspend tests 已驗過同一 evaluator path |
| `skillRepo.delete(skill)` 發 `SkillDeletedEvent` | Validated | Spring Data 官方文件列出 aggregate instance `delete(...)` 會發布 domain events |
| FK cascade 清核心表 | Validated | Flyway migrations 已有 `ON DELETE CASCADE` |
| soft-FK rows 手動清 | Validated | Flyway migrations 明確無 FK；用 SQL delete 即可 |
| GCS/filesystem async delete | Hypothesis, low risk | 現有 `StorageService.delete(path)` 已存在；未有刪多版本套件的 listener 實作，task planning 可用單元測試驗證 retry/logging 行為；不需獨立 POC |

---

## 3. SBE Acceptance Criteria

驗證指令：

```bash
cd backend && ./gradlew test
cd frontend && npm test
```

AC-S144-1: Owner 刪除自己的 skill
Given Alice 是 skill X 的 owner，且 X 有 2 個 `skill_versions`、1 筆 `download_events`、1 筆 `vector_store`
When Alice 呼叫 `DELETE /api/v1/skills/{X}`
Then API 回 204
And `skills` 查不到 X
And `skill_versions/download_events/vector_store/reviews/flags/skill_grants` 中 X 的 rows 被清除
And `skill_subscriptions/skill_scores/collection_skills/notifications` 中 X 的 rows 被清除
And `domain_events` 保留舊事件並新增 `SkillDeleted`

AC-S144-2: 非 owner 不能刪除
Given Bob 不是 skill X 的 owner
When Bob 呼叫 `DELETE /api/v1/skills/{X}`
Then API 回 403 Forbidden
And `skills` 仍查得到 X
And 不發 `SkillDeletedEvent`

AC-S144-3: 刪除不存在的 skill 回 404
Given skill id `missing-skill-id` 不存在
When 已登入 user 呼叫 `DELETE /api/v1/skills/missing-skill-id`
Then API 回 404 Not Found
And response body `error="NOT_FOUND"`

AC-S144-4: Storage cleanup 非同步刪所有版本 zip
Given skill X 有 storage paths `skills/X/1.0.0/skill.zip` 與 `skills/X/1.1.0/skill.zip`
When Alice delete X 且 transaction commit
Then `SkillPackageCleanupListener` 呼叫 `storageService.delete(...)` 兩次
And 任一路徑刪除失敗時 listener throw，Modulith publication 保持可 retry

AC-S144-5: MySkillsPage 確認 dialog + 成功 UX
Given Alice 在 `/my-skills` 看到 skill X
When Alice 點 X 的 menu「刪除」
Then dialog 顯示 `確定要刪除「X」嗎？`
When Alice 點「確定刪除」且 API 回 204
Then X 從列表消失
And toast 顯示 `技能已刪除`

AC-S144-6: 刪除後 browse 不再顯示
Given skill X 原本在 `/browse` list 可見
When Alice delete X 成功後重新載入 `/browse`
Then list 中不顯示 X
And `GET /api/v1/skills/{X}` 回 404 或 403，不回舊 metadata

---

## 4. Interface / API Design

### 4.1 HTTP API

```http
DELETE /api/v1/skills/{id}
Authorization: Bearer <jwt>

204 No Content
```

Error:

```json
{
  "error": "NOT_FOUND",
  "message": "Skill not found: missing-skill-id",
  "timestamp": "2026-05-11T00:00:00Z"
}
```

403 走既有 S162b / Spring Security access denied path。

### 4.2 Backend Signatures

```java
// SkillCommandController
@DeleteMapping("/{id}")
@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")
ResponseEntity<Void> delete(@PathVariable String id)

// SkillCommandService
@Transactional
public void deleteSkill(String skillId, String deletedBy)

// Skill aggregate
public void markDeleted(String deletedBy, List<String> storagePaths)

// domain event
public record SkillDeletedEvent(
        String aggregateId,
        String name,
        String deletedBy,
        Instant deletedAt,
        List<String> storagePaths
) {}
```

### 4.3 SQL Cleanup Contract

```sql
-- soft-FK / user-facing references
DELETE FROM skill_subscriptions WHERE skill_id = :skillId;
DELETE FROM skill_scores WHERE skill_id = :skillId;
DELETE FROM collection_skills WHERE skill_id = :skillId;
DELETE FROM notifications WHERE skill_id = :skillId;

-- hard FK cascade triggered by repository delete
DELETE FROM skills WHERE id = :skillId;
```

Example after deleting skill `sk-1`:

| Table | Before | After |
|-------|--------|-------|
| `skills` | `sk-1`, `sk-2` | `sk-2` |
| `skill_versions` | `sk-1@1.0.0`, `sk-1@1.1.0`, `sk-2@1.0.0` | `sk-2@1.0.0` |
| `skill_subscriptions` | `(sk-1,bob)`, `(sk-2,bob)` | `(sk-2,bob)` |
| `collection_skills` | `(collection-a,sk-1)`, `(collection-a,sk-2)` | `(collection-a,sk-2)` |
| `domain_events` | `SkillCreated(sk-1)`, `SkillVersionPublished(sk-1)` | old rows + `SkillDeleted(sk-1)` |

### 4.4 Frontend API

```ts
export async function deleteSkill(id: string): Promise<void> {
  await apiFetchVoid(`/skills/${id}`, { method: 'DELETE' })
}
```

[Implementation note] T03 added `apiFetchVoid()` instead of changing `apiFetch<T>()` semantics globally. This keeps existing JSON callers untouched and gives 204/no-body endpoints an explicit helper.

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | modify | Add `DELETE /api/v1/skills/{id}` with `@PreAuthorize("hasPermission(#id, 'Skill', 'delete')")` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | Add `deleteSkill(skillId, deletedBy)` flow and soft-FK cleanup SQL |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | Add `markDeleted(...)` registering `SkillDeletedEvent` |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillDeletedEvent.java` | new | Deletion domain event with deletedBy + storagePaths |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillPackageCleanupListener.java` | new | Listen to `SkillDeletedEvent`, call `StorageService.delete(path)` async |
| `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java` | modify | Add `SkillDeletedEvent` audit handler |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java` | modify | Test `markDeleted` registers event |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceDeleteTest.java` | new | Repository slice/service tests for AC-S144-1/3/6 soft cleanup and browse disappearance |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandControllerSecurityTest.java` | modify | WebMvc slice tests for 204/403/404 route behavior |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillPackageCleanupListenerTest.java` | new | Verify storage delete paths and failure retry behavior |
| `backend/src/test/java/io/github/samzhu/skillshub/audit/AuditEventListenerTest.java` | modify | Add `SkillDeleted` audit row/idempotency case |
| `frontend/src/api/client.ts` | modify | Add `apiFetchVoid` for 204/no-body endpoints |
| `frontend/src/api/skills.ts` | modify | Add `deleteSkill(id)` |
| `frontend/src/pages/MySkillsPage.tsx` | modify | Add kebab delete menu, confirm dialog, mutation, list refetch/toast |
| `frontend/src/pages/MySkillsPage.test.tsx` | modify | Add AC-S144-5 component tests |
| `frontend/src/pages/HomePage.test.tsx` | modify | Add/refine browse disappearance regression if practical through query refetch; otherwise cover with backend AC-S144-6 |
| `docs/grimo/specs/spec-roadmap.md` | modify | Mark S144 as `✅ Done` |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

POC: not required — S144 uses existing Spring Data JDBC aggregate delete events, existing Spring Modulith `@ApplicationModuleListener`, existing `StorageService.delete(path)`, and existing `SkillPermissionStrategy` ACL evaluator. No new dependency, SDK, external API, or unvalidated framework SPI is introduced. The only low-risk hypothesis (`StorageService.delete` across multiple paths) is covered directly by T02 listener tests.

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Backend delete command + data cleanup | AC-S144-1, AC-S144-3 | PASS |
| T02 | DELETE endpoint, audit event, and package cleanup listener | AC-S144-2, AC-S144-3, AC-S144-4 | PASS |
| T03 | Frontend delete API and MySkillsPage confirmation UX | AC-S144-5 | PASS |
| T04 | Acceptance coverage and browse disappearance verification | AC-S144-1, AC-S144-6 | PASS |

Execution order: T01 → T02 → T03 → T04.

### Phase 0 Findings

- PRD alignment: hard delete supports the product's management lifecycle without adding auth/security scope beyond the existing permit-all MVP posture.
- Architecture alignment: `domain_events` must remain permanent audit history; S144 deletes registry state, not audit history.
- Existing stack check: Spring Data aggregate instance `delete(...)` already publishes domain events; no custom outbox or new dependency is needed.
- E2E planning note: T03 uses component tests with fetch stubs for UI behavior. T04 must actively record whether Playwright is required; if browser-only assembly issues appear, add a follow-up E2E task before consolidation.

---

## 7. Implementation Results

### Verification Results

| Command | Result |
|---------|--------|
| `cd backend && ./gradlew test` | PASS — `BUILD SUCCESSFUL in 3m 4s` |
| `cd frontend && npm test` | PASS — 65 test files, 354 tests |
| `cd backend && ./gradlew test --tests "io.github.samzhu.skillshub.skill.domain.SkillAggregateTest" --tests "io.github.samzhu.skillshub.skill.command.SkillCommandServiceDeleteTest"` | PASS after RED compile failure confirmed missing `markDeleted` / `deleteSkill` / `SkillDeletedEvent` |
| `cd backend && ./gradlew test --tests "io.github.samzhu.skillshub.skill.command.SkillCommandControllerSecurityTest" --tests "io.github.samzhu.skillshub.skill.command.SkillPackageCleanupListenerTest" --tests "io.github.samzhu.skillshub.audit.AuditEventListenerTest"` | PASS after RED compile failure confirmed missing `SkillPackageCleanupListener` |
| `cd frontend && npm test -- MySkillsPage.test.tsx` | PASS after RED confirmed missing row action menu |
| `cd frontend && npm test -- HomePage.test.tsx` | PASS |
| `./scripts/verify-all.sh` | PASS — 2026-05-11 current tick: V01/V03/V04/V05/V06/V07/V08a/V08b all PASS; V02 INFO line coverage 82.8%; exit=0 |
| `cd frontend && npm run typecheck` | FAIL — existing project-wide test fixture type debt; errors are in pre-existing tests such as `AppShell.test.tsx`, `AuthGatedButton.test.tsx`, `QualityTab.test.tsx`, `ReviewsPanel.test.tsx`, and `NotificationsPage.test.tsx`, not in S144-modified source files. |

### AC Results

| AC | Result | Evidence |
|----|--------|----------|
| AC-S144-1 | PASS | `SkillCommandServiceDeleteTest.deleteSkill_removesSkillAndDependentRows_butPreservesDomainEvents` verifies hard delete, FK cascade, soft-FK cleanup, and preserved audit rows. |
| AC-S144-2 | PASS | `SkillCommandControllerSecurityTest` verifies owner delete 204 and non-owner delete 403 through `hasPermission(#id, 'Skill', 'delete')`. |
| AC-S144-3 | PASS | Service missing-id test verifies `NoSuchElementException`; WebMvc test verifies DELETE missing id returns 404 with `NOT_FOUND`. |
| AC-S144-4 | PASS | `SkillPackageCleanupListenerTest` verifies every storage path is deleted and storage failures propagate; `AuditEventListenerTest` verifies idempotent `SkillDeleted` audit row. |
| AC-S144-5 | PASS | `MySkillsPage.test.tsx` verifies row action menu keeps detail navigation and delete command separate, confirmation dialog text, 204 success row removal, success toast, and localized 403 toast. |
| AC-S144-6 | PASS | `SkillCommandServiceDeleteTest` verifies public browse SQL excludes deleted skill; `HomePage.test.tsx` verifies reload of `/browse` does not render deleted skill from refreshed API response. |

### Key Implementation Notes

- `SkillCommandService.deleteSkill(skillId, deletedBy)` uses `findById -> markDeleted -> deleteSoftReferences -> skillRepo.delete(skill)`. The aggregate instance path is required so Spring Data publishes `SkillDeletedEvent`; `deleteById` was intentionally avoided.
- `domain_events` is not deleted. S144 preserves old audit rows and adds `SkillDeleted` through `AuditEventListener`.
- Core FK tables (`skill_versions`, `download_events`, `vector_store`, `reviews`, `flags`, `skill_grants`) are cleaned by PostgreSQL cascade. Soft-FK/user-facing tables (`skill_subscriptions`, `skill_scores`, `collection_skills`, `notifications`) are explicitly deleted before the skill row.
- `SkillPackageCleanupListener` runs after commit via `@ApplicationModuleListener`; it does not catch storage exceptions, so Modulith can retry incomplete publications.
- Frontend uses `apiFetchVoid()` for 204/no-body endpoints and updates React Query `['skills', 'list']` cached pages after successful delete so `/my-skills` removes the row immediately.

### E2E Verification Decision

Browser E2E was not required for S144. The browser-only surface is the menu/dialog/cache-update behavior in `MySkillsPage`, covered by component tests with fetch stubs. Backend HTTP method security, delete transaction behavior, audit listener, and storage cleanup are covered by Spring WebMvc/module/repository tests. No missing Playwright-only locator, backend seed endpoint, or fixture profile gap was identified.

### QA Note

Independent QA subagent was not spawned because current execution policy only permits subagents when the user explicitly asks for delegation/subagents. Same-session deterministic verification passed with full backend and frontend test suites.

### Pending Verification

- `cd frontend && npm run typecheck` remains blocked by unrelated existing test fixture type errors (`global` name, stale `AuthUser` fixtures missing `userId/handle`, stale `SkillScores` / `Skill` fixture shapes). S144-specific frontend tests and full `npm test` pass.
