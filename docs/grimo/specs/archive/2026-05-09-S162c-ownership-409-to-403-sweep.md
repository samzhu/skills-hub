# S162c: permission denial 409→403 sweep — owner-only 與 ACL-gated mutation 拒絕語意

> Spec: S162c | Size: S(7) | Status: ⛔ superseded by S169
> Date: 2026-05-09
> Replanned: 2026-05-13
> Origin: 拆自 S162 META — API consistency 補強；2026-05-13 已整合進 S169，後續實作不參考本檔。
> Depends On: S162b 📐（401/403 ErrorResponse final shape；code-level for final assertions）, S158b 📐（Skill role matrix；ordering-only for skill-specific endpoints）

---

## 1. Goal

Bob 嘗試刪 Alice 的 review / collection / flag，或沒有對 skill 的 write/delete 權限卻呼叫 mutation endpoint 時，後端回 403 Forbidden，不回 409 Conflict；但 `PUT /api/v1/skills/{id}` 不能被寫死成 owner-only，因為 S158b 會加入 Editor role，Editor 應可編輯 skill metadata。

大白話：

```text
409 = 資源狀態衝突，例如重複版本號、狀態機不允許
403 = 這個 user 沒權限做這個操作
```

### 非目標

- 不改 S158b 的 role matrix；本 spec 只修 HTTP 語意與例外型別。
- 不改 frontend button visibility；S158b 會讓 SkillDetailPage 讀 `viewerPermissions`。
- 不把所有 service 都改成 `@PreAuthorize`；現有 service-level owner check 或 SQL ACL evaluator 可保留，只要拒絕語意是 403。

## 2. Approach

### 2.1 Approach comparison

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: 所有 owner check 都改 `AccessDeniedException` | partial | 適合 review/request/comment 這類純 ownership guard；Spring Security handler 會回 403。 |
| B: 保留 domain-specific forbidden exception，但全都 map 403 | yes | 現有已有 `ReviewForbiddenException`、`CollectionForbiddenException`、`NotSkillOwnerException`；可保留語意名稱，只要不是 409。 |
| C: 直接改 frontend 不顯按鈕 | no | client hide 不是授權；API 仍需正確回 403。 |

### 2.2 Endpoint classification

| Endpoint / operation | Guard type | Correct denial |
|----------------------|------------|----------------|
| `DELETE /api/v1/reviews/{id}` | review author only | 403 |
| `PUT/DELETE /api/v1/collections/{id}` | collection owner only | 403 |
| `DELETE /api/v1/flags/{id}` if endpoint exists | flag author/admin policy | 403 |
| `DELETE /api/v1/requests/{id}` | requester only | 403 |
| `DELETE /api/v1/requests/{id}/comments/{cid}` | comment author only | 403 |
| `POST/DELETE /api/v1/skills/{id}/grants` | skill owner only | 403 |
| `PUT /api/v1/skills/{id}` | ACL `write` permission | 403 when no write; Editor allowed by S158b |
| `DELETE /api/v1/skills/{id}` | ACL `delete` permission | 403 when no delete; Editor denied |

Skill mutation endpoints must use the ACL permission contract as source of truth: update needs `write`, delete needs `delete`. The implementation can be the existing `@PreAuthorize("hasPermission(#id, 'Skill', 'write/delete')")` guard or the S158b SQL ACL evaluator; do not add a second owner-only service check for update, or Editor will be broken.

### 2.3 Sweep rule

Allowed 409 cases:

| Case | Why 409 remains correct |
|------|--------------------------|
| Duplicate skill version | Request conflicts with existing resource state |
| Duplicate review by same user | Current state already has one review for that skill/user |
| Invalid aggregate state transition | Skill status prevents the requested transition |
| Optimistic locking failure | Caller wrote against stale state |

Forbidden 409 cases:

| Pattern | Replacement |
|---------|-------------|
| `IllegalStateException("not_*_owner")` | `AccessDeniedException` or `*ForbiddenException` mapped to 403 |
| `IllegalStateException("only author can delete")` | `AccessDeniedException("not_review_author")` |
| custom ownership exception mapped to 409 | remap to 403 |

### 2.4 Research citations

| Source | Finding |
|--------|---------|
| RFC 9110: https://www.ietf.org/rfc/rfc9110.html | 403 Forbidden and 409 Conflict are distinct 4xx statuses; permission denial belongs to 403, not resource-state conflict. |
| Spring Security `ExceptionTranslationFilter` API: https://docs.spring.io/spring-security/site/docs/5.7.9/api/org/springframework/security/web/access/ExceptionTranslationFilter.html | Anonymous access denied starts authentication; authenticated access denied delegates to `AccessDeniedHandler`, matching S162b 401/403 split. |
| Local S158b spec: `docs/grimo/specs/2026-05-09-S158b-detail-viewer-permissions.md` | Skill `PUT` must be permission-based (`write`), not owner-only, because Editor role can edit but cannot share/delete. |

## 3. SBE Acceptance Criteria

驗證指令：

- Run: `./scripts/verify-all.sh`
- Pass: all tests carrying `S162c AC-*` ids are green；existing 409 state-conflict tests remain green.

AC-1: DELETE 別人的 review → 403
Given Alice wrote review R
When Bob DELETE `/api/v1/reviews/{R}`
Then HTTP 403 + ErrorResponse permission code from S162b
And not HTTP 409

AC-2: DELETE 自己的 review 仍可
Given Alice wrote review R
When Alice DELETE `/api/v1/reviews/{R}`
Then HTTP 204

AC-3: Collection owner-only denial → 403
Given Alice owns collection C
When Bob PUT or DELETE `/api/v1/collections/{C}`
Then HTTP 403
And not HTTP 409

AC-4: Request/comment owner-only denial → 403
Given Alice owns request/comment
When Bob deletes Alice's request/comment
Then HTTP 403
And not HTTP 409

AC-5: Skill update is write-permission, not owner-only
Given Alice owns skill S
And Bob has `EDITOR` grant from S158b
When Bob PUT `/api/v1/skills/{S}`
Then request is authorized by `write` permission
And service must not reject Bob only because Bob is not owner

AC-6: Skill delete denies Editor
Given Alice owns skill S
And Bob has `EDITOR` grant from S158b
When Bob DELETE `/api/v1/skills/{S}`
Then HTTP 403 because Bob lacks `delete`
And not HTTP 409

AC-7: `/grants` remains owner-only
Given Alice owns skill S
And Bob has `EDITOR` grant
When Bob POST or DELETE `/api/v1/skills/{S}/grants`
Then HTTP 403
And not HTTP 409

AC-8: Real conflict cases still return 409
Given a user attempts duplicate version publish or duplicate review
When the duplicate request is sent
Then HTTP 409 remains unchanged

## 4. Interface / API Design

Use one of these two patterns.

Pure service ownership guard:

```java
if (!resource.ownerId().equals(currentUserId)) {
    throw new AccessDeniedException("not_resource_owner");
}
```

Domain-specific forbidden exception:

```java
public class CollectionForbiddenException extends RuntimeException {
    public CollectionForbiddenException(String message) {
        super(message);
    }
}

@ExceptionHandler(CollectionForbiddenException.class)
ResponseEntity<ErrorResponse> handleCollectionForbidden(CollectionForbiddenException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse("PERMISSION_DENIED", ex.getMessage(), Instant.now()));
}
```

Do not use `IllegalStateException` for permission denial. Keep `IllegalStateException` for aggregate state conflicts only.

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewService.java` | inspect/modify | Ensure non-author delete maps to 403, not 409. |
| `backend/src/main/java/io/github/samzhu/skillshub/community/CollectionService.java` | inspect/modify | Ensure owner-only update/delete maps to 403. |
| `backend/src/main/java/io/github/samzhu/skillshub/community/RequestService.java` | inspect | Already uses `AccessDeniedException`; keep green. |
| `backend/src/main/java/io/github/samzhu/skillshub/community/CommentService.java` | inspect | Already uses `AccessDeniedException`; keep green. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java` | inspect/modify | Non-owner grant/revoke must be 403; align error code with S162b if needed. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | inspect | Keep `PUT` = `write`, `DELETE` = `delete`; do not add owner-only service guard to update. |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` | modify | Map ownership/forbidden exceptions to S162b final permission error shape; leave real state conflicts 409. |
| `backend/src/test/java/.../review/ReviewServiceTest.java` | modify | AC-1, AC-2. |
| `backend/src/test/java/.../community/CollectionCommandControllerTest.java` | modify | AC-3. |
| `backend/src/test/java/.../community/RequestServiceTest.java` | inspect | AC-4 regression. |
| `backend/src/test/java/.../community/CommentServiceTest.java` | inspect | AC-4 regression. |
| `backend/src/test/java/.../skill/command/SkillCommandControllerSecurityTest.java` | modify | AC-5, AC-6 after S158b Editor role lands. |
| `backend/src/test/java/.../skill/security/SkillGrantControllerAuthzTest.java` | modify | AC-7. |
| `backend/src/test/java/.../shared/api/GlobalExceptionHandlerTest.java` | modify | AC-8 plus forbidden exception mapping. |
| `docs/grimo/development-standards.md` | modify | Add rule: permission denial must use `AccessDeniedException` or forbidden exception mapped 403; `IllegalStateException` is only for state conflict. |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
