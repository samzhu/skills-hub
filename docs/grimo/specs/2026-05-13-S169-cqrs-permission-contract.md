# S169: CQRS permission contract — role grants, ACL projections, viewer actions, 403 semantics

> Spec: S169 | Size: M(14) | Status: 📐 in-design
> Date: 2026-05-13
> Origin: 整合權限讀寫分離、role-based sharing、`viewerPermissions`、ACL projection consistency、permission denial 403 semantics。S170 先提供 `group:<id>` principal model；S169 只消費該模型。
> Depends On: S016 ✅, S017 ✅, S114a ✅, S121 ✅, S154 ✅, S158 ✅, S170 📐

---

## 1. Goal

Alice 在 Skill Detail 分享 skill 時，只選「Bob = 可編輯」「雲端部門 = 可檢視」或「公開 = 可檢視」；後端把這些 role grant 寫進 `skill_grants`，再由 Spring Modulith listener 非同步更新 `skills.acl_entries` 與 `vector_store.acl_entries`。S170 先把人所屬的公司、部門、跨公司團隊打平成 `user_acl_principals`；S169 讀取這些 principal keys，放進 SQL 做 ACL 過濾。

```text
Write side: 人類管理 role
  skill_grants:
    user:bob              EDITOR
    group:dept_cloud   VIEWER

Projection: 系統執行用 ACL
  acl_entries:
    user:bob:read
    user:bob:write
    group:dept_cloud:read

Read side: SQL 先過濾再分頁/排序
  skills.acl_entries       ??| :aclPatterns
  vector_store.acl_entries ??| :aclPatterns

API/UI:
  GET /skills/{id}          -> viewerPermissions
  GET /skills/{id}/grants   -> owner-only grant list
  PUT /skills/{id}          -> needs write
  DELETE /skills/{id}       -> needs delete
```

### Current Code Facts

| Path | 現況 | 需要改 |
|------|------|--------|
| `backend/.../skill/security/Role.java` | 只有 `OWNER` / `VIEWER` | 加 `EDITOR`，展開成 `read/write` |
| `backend/.../skill/security/SkillAclProjectionListener.java` | grant/revoke 後只重建 `skills.acl_entries` | 同步重建 `vector_store.acl_entries` |
| `backend/.../skill/query/SkillQueryService.java` | list 已用 SQL ACL clause 過濾後分頁 | 保留此路徑，加 regression 守住 total count |
| `backend/.../search/SemanticSearchService.java` | semantic search 已展開 current user ACL patterns | 改用 S170 `PrincipalContextService` 產生 patterns |
| `backend/.../skill/domain/Skill.java` | detail 仍可能序列化 `aclEntries` | `aclEntries` 永不出 API JSON |
| `frontend/src/pages/SkillDetailPage.tsx` | action gating 用 `skill.ownerId === me.sub` | 改讀 backend `viewerPermissions` |
| `frontend/src/components/ShareModal.tsx` | S154b 後只顯示 `user` / `public` | 加 user / Group / public target picker；權限選項只顯示 role |

### Non-goals

- 不導入 Spring Security ACL module 或 PostgreSQL RLS。
- 不做 Group CRUD、樹狀管理、成員管理；這些由 S170 完成。
- 不做 owner transfer。Owner 仍由 skill create / migration seed，分享 UI 不提供「轉移擁有者」。
- 不做 ACL cache；未來需要等 profiling 後另開 spec。

## 2. Approach

### 2.1 Chosen Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: 每次查詢 join `skill_grants` 判斷權限 | no | list/search 分頁和 semantic search 都會變重；已違背 S016/S017/S121 的 JSONB GIN 熱路徑。 |
| B: role grants + S170 principal context + materialized ACL projections + SQL ACL filter | yes | 人管理 role 和 Group；系統查 `acl_entries` + `user_acl_principals`；分頁 total 在 DB ACL filter 後計算。 |
| C: 前端自己用 `/me` + ownerId 算按鈕 | no | S154 後 `sub` 不是平台 user id；且前端不該複製 role/permission matrix。 |

### 2.2 Principal Contract from S170

S170 提供 `user_acl_principals` 與 `PrincipalContextService`。S169 只要求以下契約：

```text
current user Bob:
  user:u_bob
  group:dept_cloud
  group:company_acme
```

S169 不知道 `dept_cloud` 是 Department、Company、Team 還是 Other；它只看 principal key 字串。分享 UI 搜尋 Group 時也用 S170 的 search API 回 `principalKey = group:<id>`。

### 2.3 Role Matrix

| Role | ACL verbs | viewerPermissions | UI label |
|------|-----------|-------------------|----------|
| `OWNER` | `read`, `write`, `delete` | 全 true；可分享、可管理 grants | 擁有者 |
| `EDITOR` | `read`, `write` | 可檢視、下載、編輯；不可刪除、分享、管理 grants | 可編輯 |
| `VIEWER` | `read` | 可檢視、下載；不可編輯、刪除、分享 | 可檢視 |

`canShare` / `canManageGrants` 只由 `skills.owner_id == currentUser.userId` 決定，不放進 `acl_entries`。原因是 grants list 是管理資料，不是讀取 skill 內容所需資料。

### 2.4 SQL-first Read Rule

S121 已驗證：list/search 不能靠 method security 或 Java 後過濾，否則 `page.content` 與 `page.totalElements` 會錯。所有多筆讀取必須先展 ACL patterns，再放進 SQL。

```java
var patterns = principalContextService.currentPrincipalKeys().stream()
    .map(key -> key.value() + ":read")
    .toList();

params.addValue("aclPatterns",
    new SqlParameterValue(Types.ARRAY, patterns.toArray(new String[0])));

var aclClause = " AND acl_entries ??| :aclPatterns ";
```

`??|` 是 pgJDBC escape 後送到 PostgreSQL 的 `?|` JSONB operator；`SqlParameterValue(Types.ARRAY, ...)` 避免 Spring 把 `String[]` 展成 IN-list。

### 2.5 Skill ACL Projection Flow

```text
POST /api/v1/skills/{id}/grants
  -> INSERT skill_grants
  -> publish SkillGrantedEvent
  -> after commit listener:
       SELECT skill_grants WHERE skill_id = ?
       expand role -> ACL entries
       UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id
       UPDATE vector_store SET acl_entries = :acl::jsonb WHERE skill_id = :id
```

`SkillCreatedEvent` 仍 auto-seed owner grant；PUBLIC skill 仍 auto-seed `public:* VIEWER` grant，讓 `is_public` generated column 與 semantic search 都跟 grants 一致。

### 2.6 Viewer Permissions

`viewerPermissions` 是 detail response 的 action contract。它用 SQL ACL evaluator 計算，不呼叫 `@PreAuthorize` 當資料來源。

```java
public record ViewerPermissions(
    boolean isOwner,
    boolean canView,
    boolean canDownload,
    boolean canEdit,
    boolean canDelete,
    boolean canShare,
    boolean canManageGrants
) {}

public interface SkillAclReadEvaluator {
    boolean canRead(String skillId);
    boolean canWrite(String skillId);
    boolean canDelete(String skillId);
}
```

| Field | Source |
|-------|--------|
| `isOwner` | authenticated 且 `currentUser.userId() == skill.ownerId` |
| `canView` | SQL evaluator `read` |
| `canDownload` | `canView && skill.status != SUSPENDED` |
| `canEdit` | SQL evaluator `write` |
| `canDelete` | SQL evaluator `delete` |
| `canShare` | `isOwner` |
| `canManageGrants` | `isOwner` |

### 2.7 Permission Denial Semantics

Permission denial returns 403. State conflict remains 409.

| Scenario | Correct status |
|----------|----------------|
| Bob deletes Alice's review | 403 |
| Bob updates Alice's collection | 403 |
| Bob has `EDITOR` and updates Alice's skill metadata | 200/204 allowed |
| Bob has `EDITOR` and deletes Alice's skill | 403 |
| Bob has `EDITOR` and edits grants | 403 |
| Duplicate version publish | 409 |
| Duplicate review by same user | 409 |
| Invalid aggregate state transition | 409 |

Service code may use `AccessDeniedException` or domain-specific forbidden exceptions mapped to 403. Do not use `IllegalStateException` for permission denial.

### 2.8 Research Citations

| Source | Finding |
|--------|---------|
| `docs/grimo/specs/2026-05-13-S170-group-tree-principal-model.md` | S170 owns Group tree, memberships, and `user_acl_principals`; S169 consumes only `principalKey` strings. |
| `docs/grimo/specs/archive/2026-05-04-S121-list-acl-filter.md` | List pagination must filter ACL in SQL before page count; method security does not protect list queries. |
| `docs/grimo/specs/archive/2026-04-29-S017-acl-aware-semantic-search.md` | Semantic search already uses ACL patterns + `vector_store.acl_entries ??| ?::text[]`; S169 keeps that shape. |
| `docs/grimo/adr/ADR-001-postgresql-migration.md` | PostgreSQL + pgvector can combine JSONB ACL filtering and vector ordering in one SQL path; this was a core migration driver. |
| Spring Modulith events docs: https://docs.spring.io/spring-modulith/reference/events.html | Event Publication Registry stores listener publication in the original transaction and marks listener completion after successful processing. |
| RFC 9110: https://www.ietf.org/rfc/rfc9110.html | 403 and 409 have different meanings; permission denial belongs to 403, state conflict belongs to 409. |

## 3. SBE Acceptance Criteria

驗證指令：

- Run: `./scripts/verify-all.sh`
- Pass: all tests carrying `S169 AC-*` ids are green.

AC-1: Role matrix supports Editor
Given `Role.EDITOR`
When permissions are expanded
Then entries are exactly `read`, `write`
And do not include `delete`

AC-2: Grant user Editor updates skills ACL
Given Alice owns skill S
When Alice grants Bob `EDITOR`
Then `skill_grants` has Bob `EDITOR`
And async settled `skills.acl_entries` includes `user:<bobUserId>:read/write`
And does not include `user:<bobUserId>:delete`

AC-3: Grant Group Viewer updates skills and vector ACL
Given Alice owns skill S with vector rows
And S170 provides principal key `group:dept_cloud`
When Alice grants `group:dept_cloud VIEWER`
Then `skills.acl_entries` and `vector_store.acl_entries` both include `group:dept_cloud:read`
And both tables have the same ACL entry set for S

AC-4: Semantic search respects Group ACL
Given S170 projects Bob's principals including `group:dept_cloud`
And Charlie's principals do not include `group:dept_cloud`
When Bob searches for content from S
Then Bob can see S
When Charlie searches the same query
Then Charlie cannot see S

AC-5: Paged list count is filtered in SQL
Given 20 public skills and 1 private skill Bob cannot read
When Bob GET `/api/v1/skills?page=0&size=20`
Then `page.totalElements = 20`
And the private skill is not counted then removed in Java after pagination

AC-6: Detail returns owner viewerPermissions
Given Alice owns S
When Alice GET `/api/v1/skills/{S}`
Then all viewerPermissions fields are true except fields that are impossible for suspended download state

AC-7: Detail returns editor viewerPermissions
Given Bob has `EDITOR` grant on S
When Bob GET `/api/v1/skills/{S}`
Then `canEdit=true`
And `canDelete=false`
And `canShare=false`
And `canManageGrants=false`

AC-8: Detail returns Group viewerPermissions
Given Bob can read S through `group:dept_cloud VIEWER`
When Bob GET `/api/v1/skills/{S}`
Then `canView=true`, `canDownload=true`
And `canEdit=false`, `canDelete=false`, `canShare=false`

AC-9: API never exposes aclEntries
Given any caller can read S
When caller GET `/api/v1/skills/{S}` or `/api/v1/skills`
Then JSON does not contain `aclEntries`

AC-10: Grants list is owner-only
Given Alice owns S and Bob has `EDITOR`
When Alice GET `/api/v1/skills/{S}/grants`
Then 200 with grant list
When Bob GET the same endpoint
Then 403

AC-11: Skill update uses write permission
Given Bob has `EDITOR` grant on S
When Bob PUT `/api/v1/skills/{S}`
Then update is allowed by `write`
And service does not reject Bob solely because Bob is not owner

AC-12: Skill delete requires delete permission
Given Bob has `EDITOR` grant on S
When Bob DELETE `/api/v1/skills/{S}`
Then 403
And not 409

AC-13: Share modal exposes principals and roles, not raw operations
Given Alice opens Share modal
When the form renders
Then target choices include people, Groups, and public
And role choices are `可檢視` and `可編輯`
And no checkbox/input exposes `read`, `write`, or `delete`

AC-14: Frontend action buttons read viewerPermissions
Given SkillDetailPage receives `viewerPermissions.canEdit=false`
When page renders
Then edit button is not shown
And code does not use `skill.ownerId === me.sub` for action gating

AC-15: Permission denial is 403 and conflict remains 409
Given Bob attempts owner-only or permission-denied operations
When the request is sent
Then response is 403
Given duplicate version or duplicate review
When the request is sent
Then response remains 409

## 4. Interface / API Design

### Backend

```java
public enum Role {
    OWNER, EDITOR, VIEWER;

    public List<String> permissions() {
        return switch (this) {
            case OWNER -> List.of("read", "write", "delete");
            case EDITOR -> List.of("read", "write");
            case VIEWER -> List.of("read");
        };
    }
}
```

```java
public interface SkillAclReadEvaluator {
    boolean canRead(String skillId);
    boolean canWrite(String skillId);
    boolean canDelete(String skillId);
}
```

```java
public record ViewerPermissions(
    boolean isOwner,
    boolean canView,
    boolean canDownload,
    boolean canEdit,
    boolean canDelete,
    boolean canShare,
    boolean canManageGrants
) {}
```

### REST Contract

```json
GET /api/v1/skills/{id}
{
  "id": "S",
  "name": "docker-helper",
  "viewerPermissions": {
    "isOwner": false,
    "canView": true,
    "canDownload": true,
    "canEdit": true,
    "canDelete": false,
    "canShare": false,
    "canManageGrants": false
  }
}
```

```text
POST   /api/v1/skills/{id}/grants
DELETE /api/v1/skills/{id}/grants/{grantId}
GET    /api/v1/skills/{id}/grants

Grant principal input: user | group | public
Grant role input: VIEWER | EDITOR
OWNER remains system-seeded / migration-owned; UI does not expose owner transfer.
```

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V23__skill_grants_editor_group.sql` | new | Extend `skill_grants.role` to `OWNER/EDITOR/VIEWER`; extend principal type to `group`. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/Role.java` | modify | Add `EDITOR` and role matrix. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | modify | Rebuild `skills.acl_entries` and `vector_store.acl_entries`; use ObjectMapper for JSON. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillAclReadEvaluator.java` | new | SQL evaluator for read/write/delete patterns. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissions.java` | new | Detail action contract. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissionService.java` | new | Compute permissions from ownerId + SQL evaluator. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | Add transient viewerPermissions; hide aclEntries with `@JsonIgnore`. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | Enrich detail; preserve SQL ACL filtering before pagination. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java` | modify | Grants list owner-only; accept `EDITOR` role and `group` principal. |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` | modify | Permission denial exceptions map 403; state conflicts stay 409. |
| `frontend/src/types/skill.ts` | modify | Add `ViewerPermissions`. |
| `frontend/src/api/shareTargets.ts` | new | Fetch user and Group share target candidates. |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | Gate actions by viewerPermissions. |
| `frontend/src/components/ShareModal.tsx` | modify | Target picker for people / Groups / public; role-based UI; no raw operation checkboxes. |
| `docs/grimo/development-standards.md` | modify | Add rules for SQL-first ACL pagination and permission-denial 403 semantics. |

### Test Files

| File | AC |
|------|----|
| `backend/src/test/java/.../skill/security/RolePermissionMatrixTest.java` | AC-1 |
| `backend/src/test/java/.../skill/security/SkillAclProjectionListenerTest.java` | AC-2, AC-3 |
| `backend/src/test/java/.../search/SemanticSearchIntegrationTest.java` | AC-4 |
| `backend/src/test/java/.../skill/query/SkillSearchTest.java` | AC-5 |
| `backend/src/test/java/.../skill/query/SkillDetailViewerPermissionsTest.java` | AC-6, AC-7, AC-8 |
| `backend/src/test/java/.../skill/query/SkillResponsePrivacyTest.java` | AC-9 |
| `backend/src/test/java/.../skill/security/SkillGrantControllerAuthzTest.java` | AC-10 |
| `backend/src/test/java/.../skill/command/SkillCommandControllerSecurityTest.java` | AC-11, AC-12 |
| `backend/src/test/java/.../shared/api/GlobalExceptionHandlerTest.java` | AC-15 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | AC-14 |
| `frontend/src/components/ShareModal.test.tsx` | AC-13 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
