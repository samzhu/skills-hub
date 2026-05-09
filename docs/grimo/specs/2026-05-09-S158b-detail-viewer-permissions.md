# S158b: Detail viewer permissions — `viewerPermissions` 加入 detail response + `/grants` owner-only + aclEntries 完全內化

> Spec: S158b | Size: M(8) | Status: 📐 in-design
> Date: 2026-05-09
> Origin: 拆自 S158（v4.33.0 — list 移除 aclEntries / ownerId）；本 spec 處理 detail endpoint + /grants authz + 補強 aclEntries 一律不出現
> Depends On: S158 ✅

---

## 1. Goal

**一句話：** Skill detail response 加 `viewerPermissions` 告訴前端「你能對這 skill 做什麼」（backend 算）；`/grants` endpoint 限 owner only；`aclEntries` 完全從任何 API response 抹除（它本來就是內部 query optimization cache，不該對外）。

**為什麼重要（架構原則）：**
- **CQRS 讀寫分離**：`acl_entries` JSONB 是 write-side 副產品 + read-side GIN index 查詢優化用，**永遠不該對外**
- 對外給人看的是 **grants**（角色 + 對誰）— 走 `/grants` endpoint
- 前端要決定 button enable/disable，最佳是 backend per-request 算好（避免 frontend 重複實作 ACL 邏輯，邏輯漂移）

**為什麼重要（user-facing）：**
- 目前 detail response 仍回 `aclEntries`（list 已 strip via S158）— 任何 viewer 都能看到 owner 的整套 grant 結構
- Frontend SkillDetailPage 上 「Edit / Delete / Share」 button 顯示與否目前靠 frontend 自己 compute (me.userId === skill.owner) — 邏輯散在多處且未 cover 「co-editor 也能 edit」 future case
- 沒有清楚 contract「viewer 能對 skill 做什麼」，未來 ACL 規則升級（co-owner、role-based grant）frontend 要全 sweep

**非目標：**
- 不改 ACL 機制本身（principal 比對、grant/revoke logic）
- 不改 read-side cache pattern（acl_entries JSONB GIN index 仍是 query optimization 主軸）
- 不做 share UI（依 ShareSkillModal — S154b cover）

---

## 2. Approach

### 2.1 架構複習（CQRS read/write 分離）

```
WRITE SIDE — 人可讀的 grant：
  POST /api/v1/skills/{id}/grants  body={ principal: "u_bob", role: VIEWER }
    ↓
  INSERT INTO skill_grants (skill_id, principal_id, role, granted_by, granted_at)
    ↓
  emit SkillGrantedEvent

ASYNC LISTENER：
  on(SkillGrantedEvent):
    UPDATE skills SET acl_entries = acl_entries || ["user:u_bob:read"]
    （JSONB array 加 entry — 給 read-side query optimization 用的 cache）

READ SIDE — DB 內部查詢優化（不對外）：
  SELECT * FROM skills WHERE acl_entries @> '["user:u_bob:read"]'::jsonb
    ↑ GIN index seek

READ SIDE — 對外 API：
  GET /api/v1/skills/{id}/grants       ← 從 skill_grants 讀 + 給 owner 看
  GET /api/v1/skills/{id}              ← 含 viewerPermissions field
```

**核心：** `aclEntries` 是 implementation detail，從外部看根本不該知道它存在。

### 2.2 設計三件事

#### A. `viewerPermissions` field 加進 detail response

```json
GET /api/v1/skills/{id}
{
  "id": "...",
  "name": "...",
  "owner": "u_alice",
  "authorDisplayName": "Alice Chen",       (per S154)
  "viewerPermissions": {                   ← NEW
    "isOwner": false,
    "canEdit": false,                      // owner OR has EDITOR grant
    "canDelete": false,                    // owner only
    "canShare": false,                     // owner only
    "canDownload": true                    // public OR has VIEWER grant
  },
  ...
}
```

**Backend compute logic**（`SkillQueryService.findById(id, currentUser)`）：
```java
ViewerPermissions perms = ViewerPermissions.builder()
    .isOwner(skill.ownerId().equals(currentUser.userId()))
    .canEdit(isOwner || hasGrant(skill, currentUser, "EDITOR"))
    .canDelete(isOwner)
    .canShare(isOwner)
    .canDownload(skill.isPublic() || hasGrant(skill, currentUser, "VIEWER") || isOwner)
    .build();
```

**Anonymous user 場景**（`currentUser == null`）：
```json
"viewerPermissions": {
  "isOwner": false, "canEdit": false, "canDelete": false,
  "canShare": false, "canDownload": <skill.isPublic()>
}
```

#### B. `aclEntries` 完全 strip 從所有 API response

既有：
- `Skill.aclEntries` 是 `@Column` JSONB field on aggregate
- S158 用 `@JsonView` 在 list endpoint hide
- Detail endpoint 仍 leak

改：用 `@JsonIgnore` 直接在 `Skill.aclEntries` field 上 — **任何 endpoint 都不出現**。
read-side query 內部仍透過 raw SQL `acl_entries @>` 比對（不走 ORM serialize path）。

```java
// Skill.java
@Column("acl_entries")
@JsonIgnore                // S158b: aclEntries 是內部 query cache，永不對外
private List<String> aclEntries;
```

⚠️ S158 用 `@JsonView` 的 list-only hide 同時還原（不需了；`@JsonIgnore` 全 cover），sweep 確認 view annotation 移除無 regression。

#### C. `/grants` endpoint owner-only authz

```
GET /api/v1/skills/{id}/grants

Alice (owner)  → 200 [{ principal: "u_bob", principalName: "Bob", role: VIEWER, grantedAt: ... }]
Bob (VIEWER)   → 403 PERMISSION_DENIED
Eve (anon)     → 401 AUTHENTICATION_REQUIRED
```

**為何 viewer 也擋：** 因 §2.2-A 的 `viewerPermissions` field 已告訴 viewer 「你的權限是什麼」(canDownload=true 等)，**viewer 不需要看 grant list 就能知道自己權限**。/grants 變成純 owner 管理工具。

對齊 GitHub repo collaborator list 預設行為（non-collaborator hide）。

```java
@GetMapping("/skills/{id}/grants")
@PreAuthorize("@skillSecurity.isOwner(#id, authentication)")
public List<GrantResponse> grants(@PathVariable String id) { ... }
```

### 2.3 與 S154/S162b/S162c 的對齊

| Spec | 對齊點 |
|------|-------|
| S154 backend | platform user_id (`u_<6hex>`) — viewerPermissions 用 user_id 比對 owner |
| S162b | 401/403 走 `ErrorResponse` shape — `/grants` 拒收用同 pattern |
| S162c | ownership 拒絕 → `AccessDeniedException` 不用 IllegalStateException — 本 spec 也遵循 |
| S167b dead-code | grantAcl/revokeAcl deprecated path 移除後，本 spec 不需 cover 對應 dead 路徑 |

### 2.4 Frontend 串接

`SkillDetailPage` 的 button 狀態改讀 `skill.viewerPermissions`：

```tsx
{skill.viewerPermissions.canEdit && <EditButton />}
{skill.viewerPermissions.canDelete && <DeleteButton />}
{skill.viewerPermissions.canShare && <ShareButton />}
```

取代既有 `me.userId === skill.owner` 計算。

---

## 3. Acceptance Criteria

```
AC-1: Detail response 含 viewerPermissions field（owner case）
  Given Alice (u_alice) 是 skill 的 owner
  When GET /api/v1/skills/{id} as Alice
  Then response 含 viewerPermissions = { isOwner: true, canEdit: true, canDelete: true, canShare: true, canDownload: true }

AC-2: Detail response viewerPermissions（VIEWER grant case）
  Given Alice 把 skill 給 Bob VIEWER role
  When GET /api/v1/skills/{id} as Bob
  Then response 含 viewerPermissions = { isOwner: false, canEdit: false, canDelete: false, canShare: false, canDownload: true }

AC-3: Detail response viewerPermissions（EDITOR grant case）
  Given Alice 把 skill 給 Charlie EDITOR role
  When GET /api/v1/skills/{id} as Charlie
  Then response viewerPermissions.canEdit = true (其餘 false 同 AC-2)

AC-4: Detail response viewerPermissions（anonymous case）
  Given skill is_public=true，無認證 request
  When GET /api/v1/skills/{id} (no auth header)
  Then response viewerPermissions = { all false except canDownload=true }

AC-5: Detail response 不含 aclEntries
  Given 任何 user 任何角色 access detail
  When GET /api/v1/skills/{id}
  Then response JSON 不含 "aclEntries" key（grep 不到）

AC-6: List response 仍不含 aclEntries（regression check S158）
  Given GET /api/v1/skills?...
  Then 每筆不含 aclEntries（既有 S158 行為維持）

AC-7: GET /grants owner only — 200
  Given Alice 是 skill owner
  When GET /api/v1/skills/{id}/grants as Alice
  Then 200 + List<GrantResponse>（每筆含 principal, principalName, role, grantedAt, grantedBy）

AC-8: GET /grants viewer 拒絕 — 403
  Given Bob 是 VIEWER（不是 owner）
  When GET /api/v1/skills/{id}/grants as Bob
  Then 403 + ErrorResponse PERMISSION_DENIED

AC-9: GET /grants anon 拒絕 — 401
  Given 無認證 request
  When GET /api/v1/skills/{id}/grants
  Then 401 + ErrorResponse AUTHENTICATION_REQUIRED

AC-10: Frontend SkillDetailPage button 改讀 viewerPermissions
  Given skill.viewerPermissions.canEdit = false
  When SkillDetailPage 渲染
  Then EditButton 不顯（或 disabled）
  And 不再依賴 me.userId === skill.owner 計算

AC-11: 既有 RBAC test suite 不破
  Given 改動 Skill.aclEntries @JsonIgnore + /grants @PreAuthorize
  When 跑既有 SkillSecurityIntegrationTest
  Then 全綠（內部 ACL filter 邏輯不變，只變 API serialize / endpoint authz）
```

**驗證指令：** `cd backend && ./gradlew test` + `cd frontend && npm test`

---

## 4. Files to Change

### Backend production code

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/domain/Skill.java` | `aclEntries` field 加 `@JsonIgnore`；移除既有 `@JsonView(Skill.Views.List.class)` annotation（被 @JsonIgnore 取代）|
| `backend/src/main/java/.../skill/query/ViewerPermissions.java` | **新增** — record (isOwner, canEdit, canDelete, canShare, canDownload) |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | `findById(id, currentUser)` 加算 viewerPermissions；search() (list) 不加（per S158 list 不需要這 field）|
| `backend/src/main/java/.../skill/query/SkillResponse.java` | 加 `viewerPermissions: ViewerPermissions` field |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `findById` pass currentUser 進 service |
| `backend/src/main/java/.../skill/query/GrantResponse.java` | 既有；確認 shape（principal, principalName, role, grantedAt, grantedBy）|
| `backend/src/main/java/.../skill/security/SkillSecurityEvaluator.java`（或既有）| 加 `isOwner(skillId, authentication)` Spring EL eval method 給 `@PreAuthorize` 用 |
| `backend/src/main/java/.../skill/query/SkillGrantsController.java`（既有）| `GET /grants` 加 `@PreAuthorize("@skillSecurity.isOwner(#id, authentication)")` |

### Backend test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../skill/query/SkillDetailViewerPermissionsTest.java` | **新增** — AC-1~4 owner / VIEWER / EDITOR / anon 4 case |
| `backend/src/test/java/.../skill/query/SkillDetailAclLeakTest.java` | **新增** — AC-5~6 grep response JSON 確認無 aclEntries |
| `backend/src/test/java/.../skill/query/SkillGrantsControllerAuthzTest.java` | **新增 / 更新** — AC-7~9 三 case |
| 既有 SkillQueryControllerTest 含 aclEntries assertion 的 | sweep 移除（既有應為 0 hit since S158）|

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/types/skill.ts` | 加 `viewerPermissions: { isOwner, canEdit, canDelete, canShare, canDownload }` |
| `frontend/src/pages/SkillDetailPage.tsx` | EditButton/DeleteButton/ShareButton conditional 改讀 viewerPermissions |
| `frontend/src/components/v2/ShareSkillModal.tsx` | 開 modal 前檢查 viewerPermissions.canShare（S154b 也 cover）|
| `frontend/src/pages/SkillDetailPage.test.tsx` | mock viewerPermissions 4 種 case 驗 button 顯示 |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1~4 | `SkillDetailViewerPermissionsTest` 4 case |
| AC-5 | `SkillDetailAclLeakTest` grep response 無 aclEntries |
| AC-6 | regression — 跑既有 SkillQuery list test |
| AC-7~9 | `SkillGrantsControllerAuthzTest` 3 case |
| AC-10 | `SkillDetailPage.test.tsx` mock viewerPermissions 4 case |
| AC-11 | `SkillSecurityIntegrationTest`（既有）跑全綠 |

### 5.2 手動 LAB 驗證

- [ ] curl `/api/v1/skills/{id}` as Alice (owner) → 看到 viewerPermissions 全 true
- [ ] curl as Bob (anon or VIEWER) → 看到 canEdit=false / canDownload=true
- [ ] curl response grep `aclEntries` → 無
- [ ] curl `/api/v1/skills/{id}/grants` as Alice → 200 grant list
- [ ] curl `/api/v1/skills/{id}/grants` as Bob → 403
- [ ] LAB 開 SkillDetailPage 切角色看 button 變化

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| `@JsonIgnore` on aclEntries 影響內部 raw SQL 查詢 | 不影響 — raw SQL 走 PostgreSQL JDBC `acl_entries @>` operator，不經 Jackson serialize；@JsonIgnore 只影響 ObjectMapper write |
| viewerPermissions 算錯 → frontend button 顯示但 API 拒收 | backend SkillCommandService 同樣的 ACL check（既有）— viewerPermissions 是「告訴 frontend 要不要顯 button」，真正 enforcement 仍在 commandService；不一致時 backend 會 403 |
| 既有 frontend code 仍依 me.userId === skill.owner 計算 | sweep `me.userId === skill.owner` grep；逐個改 viewerPermissions 讀 |
| /grants endpoint authz 改後既有 frontend ShareSkillModal 變 403 | ShareSkillModal 本來就是 owner 才開 — viewerPermissions.canShare 已限制；不是 owner 開不到 modal，自然不 GET /grants |
| 跟 S154 user_id 對齊 | viewerPermissions.isOwner 比對用 `currentUser.userId()` (platform user_id per S154)；S154 ship 後即生效 |
| 跟 S162b/S162c 對齊 | `/grants` 403 走 S162b 的 PERMISSION_DENIED ErrorResponse；ownership 拒絕用 S162c 的 AccessDeniedException |

---

## 7. 後續 follow-up

- viewerPermissions 加更多 fields（canFlag / canReview / canSubscribe）— 看需求
- /grants response 排序 / 分頁（如果 grant 數量大）
- co-owner role（多 owner 共管）— ACL schema 改動，獨立 spec
