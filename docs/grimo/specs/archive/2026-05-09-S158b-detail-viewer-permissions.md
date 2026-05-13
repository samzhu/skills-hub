# S158b: CQRS permission contract — roles → ACL projection → viewerPermissions

> Spec: S158b | Size: M(13) | Status: ⛔ superseded by S169
> Date: 2026-05-09
> Replanned: 2026-05-13
> Origin: 拆自 S158（v4.33.0 — list 移除 aclEntries / ownerId）；2026-05-13 已整合進 S169，後續實作不參考本檔。
> Depends On: S114a ✅, S154 ✅, S158 ✅, S162b 📐（401/403 final ErrorResponse shape；ordering-only）, S162c 📐（ownership/permission denial sweep；ordering-only）

---

## 1. Goal

Alice 在 Skill Detail 點「分享」只選「Bob = 可編輯」「雲端部門 = 可檢視」；後端把這些 role grant 寫進 `skill_grants`，再由 Spring Modulith listener 非同步更新 `skills.acl_entries` 與 `vector_store.acl_entries`，detail API 回 `viewerPermissions` 給前端決定按鈕，不再把 `aclEntries` 這種查詢快取欄位丟給瀏覽器。

現有程式已經有讀寫分離骨架：

```
人看的關係表：skill_grants
  Alice -> user:Bob role=EDITOR
  Alice -> group:cloud role=VIEWER

系統執行用投影：acl_entries
  user:bob:read
  user:bob:write
  group:cloud:read

熱路徑查詢：
  list/detail:   SQL WHERE skills.acl_entries ??| ARRAY[...]
  semantic:      vector_store.acl_entries ??| ARRAY[...]

對外 API：
  GET /skills/{id} 回 viewerPermissions，不回 aclEntries
  GET /skills/{id}/grants 只給 owner 看 role grants
```

### 現況差距

| 檔案 | 現況 | 問題 |
|------|------|------|
| `backend/.../skill/security/Role.java` | 只有 `OWNER` / `VIEWER` | Bob 不能拿「可編輯但不能刪除/分享」角色 |
| `backend/.../skill/security/SkillAclProjectionListener.java` | 只更新 `skills.acl_entries` | grant 變更後語意搜尋仍看 `vector_store.acl_entries`，權限可能不同步 |
| `backend/.../skill/domain/Skill.java` | detail view 仍可序列化 `aclEntries` | 把內部查詢快取洩漏給 API client |
| `backend/.../skill/security/SkillGrantController.java` | `GET /grants` 只要 read permission | viewer 可看到整張分享清單；這是管理資料，不是瀏覽資料 |
| `frontend/src/pages/SkillDetailPage.tsx` | `skill.ownerId === me.sub` 算 owner | S154 後應用 `me.userId`，且前端不該重做 ACL 邏輯 |

### 非目標

- 不導入 Spring Security ACL module；本專案既有設計是 JSONB flat ACL + GIN index。
- 不做完整組織樹 CRUD；本 spec 只使用既有 principal namespace：`user` / `group` / `company` / `public`。使用者口中的「部門」先落在 `group` claim。
- 不做 ACL cache；`S2XX-cache` 保持 deferred，等真實流量 profiling 再啟動。

## 2. Approach

### 2.1 Approach comparison

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: 直接 join `skill_grants` + users/groups/company 做每次查詢授權 | no | 每次 list / semantic search 都要 join，多 principal 展開後 SQL 會變重；違背 S016/S114a 已 ship 的 `acl_entries` GIN 熱路徑。 |
| B: 保留 CQRS projection，補齊 role matrix、雙表投影、viewerPermissions | yes | 最貼近現有程式：`skill_grants` 給管理 UI，`acl_entries` 給 SQL filter；S121 已證明 list/search 分頁必須在 SQL 先過濾。 |
| C: 只在前端用 `/me` + `ownerId` 判斷按鈕 | no | 已經被 S154 platform user_id 變更打穿；也無法支援 Editor / department / company grant。 |

### 2.2 Role matrix

人類只管理 role，不管理 operation checkbox。

| Role | `acl_entries` verbs | Detail `viewerPermissions` | UI label |
|------|---------------------|-----------------------------|----------|
| `OWNER` | `read`, `write`, `delete` | 全 true；可分享、可管理 grants | 擁有者 |
| `EDITOR` | `read`, `write` | 可檢視、下載、編輯；不可刪除、分享、管理 grants | 可編輯 |
| `VIEWER` | `read` | 可檢視、下載；不可編輯、刪除、分享 | 可檢視 |

`canShare` / `canManageGrants` 不放進 `acl_entries`，而是由 `skill.owner_id == currentUser.userId` 算出。原因：分享清單是管理面資料；Google Drive 類 UI 也是選 role，不讓一般 editor 看完整 ACL。

### 2.3 Write side flow

```text
POST /api/v1/skills/{id}/grants
  body { principalType: "user", principalId: "bob@example.com", role: "EDITOR" }
    ↓
SkillGrantService.grant()
  1. 用 CurrentUserProvider 取 Alice 的 platform user_id
  2. 確認 Alice == skills.owner_id
  3. user principal 解析成 platform user_id；group/company/public 原樣驗證
  4. INSERT skill_grants
  5. publish SkillGrantedEvent
    ↓ after commit
SkillAclProjectionListener
  1. SELECT skill_grants WHERE skill_id = ?
  2. role → verbs 展開成 flat ACL entries
  3. UPDATE skills.acl_entries
  4. UPDATE vector_store.acl_entries WHERE skill_id = ?
```

`SkillCreatedEvent` 仍自動 seed `OWNER` grant；PUBLIC skill 仍 seed `public:* VIEWER` grant，避免第一次 rebuild 後公開可見性消失。

### 2.4 Read side flow — SQL first, method security second

S121 已經驗證：list/search 這種有分頁的讀取端不能靠 `@PreAuthorize` / `@PostFilter` 後過濾，因為 page content 和 `totalElements` 會先被 DB 算好，Java 端再濾會產生「本頁少資料、總數錯」的結果。讀取端主路徑必須先把 current user 打平成 ACL patterns，再放進 SQL。

Canonical read pattern：

```java
var aclPatterns = aclExpander.expand(currentUserProvider.current(), "read");
var aclClause = " AND acl_entries ??| :aclPatterns ";
params.addValue("aclPatterns",
    new SqlParameterValue(Types.ARRAY, aclPatterns.toArray(new String[0])));
```

S158b 新增「同一份 SQL ACL contract 的 response adapter」；`viewerPermissions` 不呼叫 `@PreAuthorize` 取得答案，而是用相同 pattern 對 skill id 做 `EXISTS` 查詢：

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

public interface ViewerPermissionService {
    ViewerPermissions forSkill(Skill skill);
}
```

計算規則：

| Field | Source |
|-------|--------|
| `isOwner` | authenticated 且 `currentUser.userId() == skill.ownerId` |
| `canView` | `SkillAclReadEvaluator.canRead(skill.id)`，或 owner fallback |
| `canDownload` | 同 `canView` 且 skill 非 `SUSPENDED` |
| `canEdit` | `SkillAclReadEvaluator.canWrite(skill.id)`，或 owner fallback |
| `canDelete` | `SkillAclReadEvaluator.canDelete(skill.id)`，或 owner fallback |
| `canShare` | `isOwner` |
| `canManageGrants` | `isOwner` |

`@PreAuthorize("hasPermission(#id, 'Skill', 'read/write/delete')")` 可繼續作為現有 single endpoint / mutation endpoint 的 controller guard，但不作為 list/search/detail payload 的資料來源。未來若重構 single detail，也應走「SQL resolve + ACL clause」而不是 method-security 後過濾。

### 2.5 API contract

`GET /api/v1/skills/{id}` detail response 新增：

```json
{
  "id": "sk1",
  "name": "docker-helper",
  "authorDisplayName": "Alice Chen",
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

`aclEntries` 永遠不出現在 API JSON。`ownerId` 可暫時保留 detail backward compatibility，但前端按鈕不得再使用它；未來若要完全移除 ownerId，另開 response privacy spec。

`GET /api/v1/skills/{id}/grants` 改成 owner-only：

```text
Alice owner  → 200 [{ principalType, principalId, principalDisplayName, role, grantedBy, grantedAt }]
Bob editor   → 403 ErrorResponse
匿名使用者   → 401 ErrorResponse
```

### 2.6 Frontend UX

Skill Detail 的操作按鈕全改讀 `viewerPermissions`：

| UI element | Visible/enabled when |
|------------|----------------------|
| 下載 | `canDownload` |
| 編輯 | `canEdit` |
| 刪除 | `canDelete` |
| 分享 | `canShare` |
| 分享 modal grant list | `canManageGrants` |

Share modal 只讓 owner 選：

| 控制項 | 選項 |
|--------|------|
| 對象類型 | 個人 / 部門 / 公司 / 公開 |
| 對象 | user email/handle/id；group id；company id；public 固定 `*` |
| 角色 | 可檢視 / 可編輯 |

不提供「read/write/delete」勾選框。operation 是系統依 role matrix 算出，不是人類管理的表單欄位。

### 2.7 Research citations

| Source | Finding |
|--------|---------|
| Spring Modulith docs — Working with Application Events: https://docs.spring.io/spring-modulith/reference/events.html | Event Publication Registry 會在原 business transaction 中記錄 listener publication；listener 成功後標 completed，失敗可留待 retry。符合 grant row 先 commit、ACL projection async rebuild 的設計。 |
| Spring Modulith `ApplicationModuleListener` API: https://docs.spring.io/spring-modulith/docs/2.0.0-M3/api/org/springframework/modulith/events/ApplicationModuleListener.html | `@ApplicationModuleListener` 是 async transactional event listener，適合 module integration listener；本專案已在 `SkillAclProjectionListener` 使用。 |
| Spring Security `ExceptionTranslationFilter` API: https://docs.spring.io/spring-security/site/docs/5.7.9/api/org/springframework/security/web/access/ExceptionTranslationFilter.html | Anonymous access denied 走 `AuthenticationEntryPoint`，已認證但無權走 `AccessDeniedHandler`；S162b/S162c 的 401/403 split 應沿用這個語意。 |
| RFC 9110: https://www.ietf.org/rfc/rfc9110.html | 403 與 409 是不同 client error；ownership / permission denial 應回 forbidden，state conflict 才回 conflict。 |
| Local S121 shipped spec: `docs/grimo/specs/archive/2026-05-04-S121-list-acl-filter.md` | `@PreAuthorize` 只保護單筆路徑不會自動保護 list；分頁 query 必須在 SQL 中加 `acl_entries ??| :aclPatterns`，否則 private skill 會出現在 page result / total count。 |
| Local S017 shipped spec: `docs/grimo/specs/archive/2026-04-29-S017-acl-aware-semantic-search.md` | Semantic search 已採 `AclPrincipalExpander.expand(currentUser, "read")` + `vector_store.acl_entries ??| ?::text[]`，證明向量搜尋也要 SQL-level ACL filter。 |
| ADR-001 PostgreSQL migration: `docs/grimo/adr/ADR-001-postgresql-migration.md` | PostgreSQL + pgvector 可在同一 SQL 做 `acl_entries ?| text[]` GIN filter 與 vector ordering；這是從 Firestore 遷移的核心原因之一。 |
| Local S114a shipped spec: `docs/grimo/specs/archive/2026-05-03-S114a-rbac-acl-projection.md` | 已接受 `skill_grants` source-of-truth + `acl_entries` materialized projection，不需重開架構選型。 |

## 3. SBE Acceptance Criteria

驗證指令：

- Run: `./scripts/verify-all.sh`
- Pass: all tests carrying `S158b AC-*` ids are green；V01/V04/V05/V07 對本 spec 相關測試全綠。

AC-1: Role matrix 支援 Editor
Given `Role.EDITOR`
When `Role.EDITOR.permissions()` 被展開
Then entries 只有 `read` 與 `write`
And 不含 `delete`

AC-2: Grant Bob Editor 後 skills ACL projection 有 read/write
Given Alice 是 skill owner
When Alice POST `/api/v1/skills/{id}/grants` body `{ principalType:"user", principalId:"bob@example.com", role:"EDITOR" }`
Then `skill_grants` 新增 Bob 的 `EDITOR` row
And async settle 後 `skills.acl_entries` 含 `user:<bobUserId>:read` 與 `user:<bobUserId>:write`
And 不含 `user:<bobUserId>:delete`

AC-3: Grant 部門 Viewer 後 skills/vector ACL projection 同步
Given Alice 是 skill owner
When Alice grant `{ principalType:"group", principalId:"cloud", role:"VIEWER" }`
Then async settle 後 `skills.acl_entries` 與 `vector_store.acl_entries` 都含 `group:cloud:read`
And 兩張表對同一 skill 的 ACL entry set 相同

AC-4: Semantic search 使用更新後的 vector ACL
Given Bob 的 current user groups 含 `cloud`
And Alice grant `group:cloud VIEWER` 給 skill S
When Bob 用自然語言搜尋會命中 S 的內容
Then semantic search result 包含 S
When Charlie 不在 `cloud` 且沒有 grant
Then semantic search result 不包含 S

AC-5: Detail response 回 owner viewerPermissions
Given Alice 是 skill owner
When Alice GET `/api/v1/skills/{id}`
Then `viewerPermissions = { isOwner:true, canView:true, canDownload:true, canEdit:true, canDelete:true, canShare:true, canManageGrants:true }`

AC-6: Detail response 回 editor viewerPermissions
Given Bob 有 `EDITOR` grant
When Bob GET `/api/v1/skills/{id}`
Then `viewerPermissions.canEdit = true`
And `canDelete=false`
And `canShare=false`
And `canManageGrants=false`

AC-7: Detail response 回 viewer viewerPermissions
Given 雲端部門有 `VIEWER` grant
And Carol 的 groups 含 `cloud`
When Carol GET `/api/v1/skills/{id}`
Then `canView=true`, `canDownload=true`
And `canEdit=false`, `canDelete=false`, `canShare=false`

AC-8: Detail/List response 不含 aclEntries
Given any authenticated or anonymous caller can read a skill
When caller GET `/api/v1/skills/{id}` or `/api/v1/skills`
Then JSON response does not contain `aclEntries`

AC-9: `/grants` owner only
Given Alice owner、Bob editor、Carol viewer
When Alice GET `/api/v1/skills/{id}/grants`
Then 200 with grant list
When Bob or Carol GET the same endpoint
Then 403 ErrorResponse
When anonymous GET the same endpoint
Then 401 ErrorResponse

AC-10: Frontend action buttons read viewerPermissions
Given SkillDetailPage receives `viewerPermissions.canEdit=false`
When page renders
Then edit button is not shown
And code no longer uses `skill.ownerId === me.sub` for action gating

AC-11: Share modal is role-based, not operation-based
Given Alice opens Share modal
When the modal renders
Then role choices are `可檢視` and `可編輯`
And no checkbox/input exposes raw `read`, `write`, `delete` permissions

AC-12: Existing owner visibility toggle still works
Given Alice toggles public/private visibility
When public VIEWER grant is added or removed
Then `skills.is_public` generated column changes through `acl_entries`
And `vector_store.acl_entries` changes consistently for semantic search

AC-13: Paged list count is filtered in SQL
Given 20 public skills and 1 private skill that Bob cannot read
When Bob GET `/api/v1/skills?page=0&size=20`
Then `page.totalElements = 20`
And the private skill is not counted then removed in Java after pagination

## 4. Interface / API Design

### Backend records/classes

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

```java
public interface SkillAclReadEvaluator {
    boolean canRead(String skillId);
    boolean canWrite(String skillId);
    boolean canDelete(String skillId);
}
```

```java
@Service
public class ViewerPermissionService {
    public ViewerPermissions forSkill(Skill skill) { ... }
}
```

### Projection update contract

```java
private void rebuildAcl(String skillId) {
    // 1. advisory lock by skillId
    // 2. read all skill_grants
    // 3. expand role to flat entries
    // 4. UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id
    // 5. UPDATE vector_store SET acl_entries = :acl::jsonb WHERE skill_id = :id
}
```

The JSON serialization must use `ObjectMapper.writeValueAsString(entries)` instead of manual quoting because future group/company ids might contain characters that need JSON escaping.

### Frontend type

```ts
export interface ViewerPermissions {
  isOwner: boolean
  canView: boolean
  canDownload: boolean
  canEdit: boolean
  canDelete: boolean
  canShare: boolean
  canManageGrants: boolean
}

export interface Skill {
  viewerPermissions?: ViewerPermissions
}
```

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V22__add_editor_role.sql` | new | Extend `skill_grants.role` CHECK constraint from `OWNER/VIEWER` to `OWNER/EDITOR/VIEWER`. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/Role.java` | modify | Add `EDITOR` and role→permission matrix. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | modify | Rebuild `skills.acl_entries` and `vector_store.acl_entries`; switch JSON building to ObjectMapper. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissions.java` | new | Detail response permission contract. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillAclReadEvaluator.java` | new | Shared SQL evaluator: expand current user to ACL patterns and run `EXISTS ... acl_entries ??| :patterns` for read/write/delete. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/ViewerPermissionService.java` | new | Compute per-request permissions from ownerId + `SkillAclReadEvaluator`, not from `@PreAuthorize`. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | Add transient `viewerPermissions`; `aclEntries` gets `@JsonIgnore`; keep ownerId detail compatibility for now. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | Enrich detail with `viewerPermissions`; keep list ACL in SQL before pagination; add AC-13 regression if needed. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java` | modify | `GET /grants` owner-only; POST accepts `EDITOR`. |
| `backend/src/test/java/.../skill/security/RolePermissionMatrixTest.java` | new | AC-1. |
| `backend/src/test/java/.../skill/security/SkillAclProjectionListenerTest.java` | modify | AC-2, AC-3, AC-12. |
| `backend/src/test/java/.../search/SemanticSearchIntegrationTest.java` | modify/new | AC-4. |
| `backend/src/test/java/.../skill/query/SkillDetailViewerPermissionsTest.java` | new | AC-5, AC-6, AC-7. |
| `backend/src/test/java/.../skill/query/SkillResponsePrivacyTest.java` | new | AC-8. |
| `backend/src/test/java/.../skill/security/SkillGrantControllerAuthzTest.java` | new/modify | AC-9. |
| `frontend/src/types/skill.ts` | modify | Add `ViewerPermissions`. |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | Use `viewerPermissions` instead of `ownerId === me.sub`. |
| `frontend/src/components/ShareModal.tsx` | modify | Role-based UI: Viewer / Editor; principal type selector. |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | AC-10. |
| `frontend/src/components/ShareModal.test.tsx` | modify/new | AC-11. |
| `docs/grimo/development-standards.md` | modify | Add rule: UI must gate skill actions by backend `viewerPermissions`, not by local owner comparison. |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
