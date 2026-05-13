# S170: Group tree principal model

> Spec: S170 | Size: M(16) | Status: 📐 in-design
> Date: 2026-05-13
> Origin: S169 前置 spec。Company / Department / 虛擬組織本質上都是「把人框在一起」的 Group；每個 Group 都可掛使用者，也可掛子 Group。
> Depends On: S154 ✅

---

## 1. Goal

管理者在「群組」頁面新增 `Acme` 公司群組，再新增子群組 `Cloud` 部門，也可以憑空新增 `AI Enablement` 這種跨公司的專案群組；每個群組都能直接放人，也能再放子群組。後端由 `PrincipalContextService` 直接查平台 DB 的 `group_members` 與 `group_closure`，產生目前使用者可用的 principal keys，供 S169 分享權限使用。

```text
groups:
  Acme                  id=g_a1b2c3 kind=COMPANY
    Cloud               id=g_d4e5f6 kind=DEPARTMENT
      Platform Team     id=g_123abc kind=TEAM
  AI Enablement         id=g_789def kind=TEAM

group_members:
  Bob -> Platform Team
  Bob -> AI Enablement

PrincipalContextService output for Bob:
  user:u_bob
  group:g_123abc
  group:g_d4e5f6
  group:g_a1b2c3
  group:g_789def
```

`kind` 是給人看的分類欄位，不是資料庫行為。DB 不因 `kind` 改變 child rule、membership rule、ACL rule 或刪除規則；所有 row 都是同一種 Group。初始 kind 只提供 `COMPANY`, `DEPARTMENT`, `TEAM`, `OTHER` 給 UI 顯示、icon、搜尋篩選與預設文案。

同一個人可以同時在實體部門與多個 TEAM 內。例：Bob 可以是 `Acme / Cloud / Platform Team` 的成員，也可以同時在 root TEAM `AI Enablement` 內；產生 principal context 時保留全部 principal，不互相覆蓋。

### Scenario Anchor

S170 的驗收標準與單元測試都以這個情境為核心，不只驗 CRUD：

1. `Acme` 是 `COMPANY` Group。
2. `Cloud` 是 `DEPARTMENT` Group，parent 是 `Acme`。
3. `Platform Team` 是 `TEAM` Group，parent 是 `Cloud`。
4. `AI Enablement` 是 `TEAM` Group，沒有 parent，代表憑空建立的跨公司虛擬組織。
5. Bob 同時被放進 `Platform Team` 與 `AI Enablement`。
6. Bob 的 `PrincipalContextService` output 必須同時有 `user:u_bob`, `group:g_123abc`, `group:g_d4e5f6`, `group:g_a1b2c3`, `group:g_789def`。

這個情境驗證三件事：`kind` 不控制 DB 行為、TEAM 可 root、同一個人可同時屬於實體組織路徑與跨公司 TEAM。

### Current Code Facts

| Path | 現況 | 需要改 |
|------|------|--------|
| `docs/grimo/PRD.md` | 有 Organization / Company / Department / 軟結構概念，但標成設計先行、MVP 不啟用 | S170 把它收斂成同一種 `Group` 可用模型 |
| `backend/.../shared/security/CurrentUser.java` | Google OAuth login path 以平台 `users` 表解析 `userId`；token 內沒有產品內 Group membership | Group membership 來源改走平台 DB，不讀 JWT `groups` / `companyId` 當產品組織來源 |
| `backend/.../shared/security/AclPrincipalExpander.java` | 展開 user / role / JWT group / company | 後續 S169 改由 `PrincipalContextService` 直接查 DB 取得 `group:<id>` |
| `frontend/src/components/ShareModal.tsx` | S154b 後移除 group/company UI，因為沒有 group model | S170 先提供 group 管理與搜尋 API，S169 再接 Share modal |

### Non-goals

- 不做 HR / IdP 自動同步；S170 只做平台內手動 CRUD。
- 不做 group 角色權限矩陣；Group 只負責「誰屬於哪個集合」。
- 不處理 skill 分享角色；S169 消費 `group:<id>` principal 後再設計 `VIEWER` / `EDITOR`。
- 不導入 PostgreSQL `ltree` extension；先用 closure table，避免額外型別 mapping 與 Cloud SQL extension 權限風險。

## 2. Approach

### 2.1 Chosen Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Company / Department / Team 各自一套表 | no | 三套 CRUD、三套 membership、三套 share target UI；但需求其實都是「Group 可掛人、可掛子 Group」。 |
| B: 單一 `groups` adjacency list + closure table | yes | 一套模型支援公司、部門、跨公司團隊；closure table 可快速取祖先/子孫，不需要遞迴 join 掛在 hot path。 |
| C: PostgreSQL `ltree` path | no | 官方 `ltree` 適合階層路徑查詢，但會引入 extension 與 Spring Data JDBC custom type mapping；目前 closure table 已足夠。 |

### 2.2 Naming Decision

架構命名採用這個分層：

| Layer | Name | Meaning |
|-------|------|---------|
| Domain aggregate | `Group` | 真正的業務概念：把人放在一起，可形成樹。 |
| Human-facing classification | `kind` / `GroupKind` | 只給人類知道這個 Group 看起來像公司、部門、團隊或其他；不改變資料規則。 |
| Principal namespace | `group:<id>` | ACL 執行端用的扁平字串。 |

不用 `OrgNode` 的理由：它描述資料結構，不描述人類要完成的工作。使用者不是在管理「節點」，而是在管理「哪些人屬於哪個群組」。

不用 `GroupKind.GROUP` 的理由：`Group` 已經是模型名稱，kind 再叫 `GROUP` 會重複。跨公司虛擬組織在 UI 上用 `TEAM` 表示；需要更細的顯示名稱時，由 frontend label mapping 決定，不改 DB 行為。

### 2.3 Data Model

```sql
CREATE TABLE groups (
    id             VARCHAR(64) PRIMARY KEY,       -- stable group id, format g_<6hex>
    parent_id      VARCHAR(64) REFERENCES groups(id), -- null means root group
    kind           VARCHAR(24) NOT NULL,          -- display-only: COMPANY / DEPARTMENT / TEAM / OTHER
    display_name   VARCHAR(160) NOT NULL,         -- human label shown in UI
    slug           VARCHAR(80) NOT NULL,          -- unique among siblings, used for stable search/path display
    status         VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    sort_order     INTEGER NOT NULL DEFAULT 0,    -- sibling ordering in tree UI
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (parent_id, slug)
);

CREATE TABLE group_closure (
    ancestor_id    VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE, -- parent/ancestor
    descendant_id  VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE, -- child/descendant
    depth          INTEGER NOT NULL,             -- 0=self, 1=direct child, 2+=deeper descendant
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE TABLE group_members (
    group_id       VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE, -- direct membership only
    user_id        VARCHAR(64) NOT NULL REFERENCES users(user_id),               -- platform users.user_id
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_groups_parent_order
    ON groups (parent_id, sort_order, display_name)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_group_closure_descendant
    ON group_closure (descendant_id, ancestor_id, depth);

CREATE INDEX idx_group_members_user
    ON group_members (user_id, group_id);
```

Example rows for the scenario anchor:

| Table | Rows |
|-------|------|
| `groups` | `g_a1b2c3(parent=null, kind=COMPANY, displayName=Acme)`, `g_d4e5f6(parent=g_a1b2c3, kind=DEPARTMENT, displayName=Cloud)`, `g_123abc(parent=g_d4e5f6, kind=TEAM, displayName=Platform Team)`, `g_789def(parent=null, kind=TEAM, displayName=AI Enablement)` |
| `group_closure` | `(g_a1b2c3, g_a1b2c3, 0)`, `(g_a1b2c3, g_d4e5f6, 1)`, `(g_a1b2c3, g_123abc, 2)`, `(g_d4e5f6, g_d4e5f6, 0)`, `(g_d4e5f6, g_123abc, 1)`, `(g_123abc, g_123abc, 0)`, `(g_789def, g_789def, 0)` |
| `group_members` | `(g_123abc, u_bob)`, `(g_789def, u_bob)` |

`PrincipalContextService.currentPrincipalKeys()` for Bob returns:

```text
user:u_bob
group:g_123abc
group:g_d4e5f6
group:g_a1b2c3
group:g_789def
```

`group:<id>` has two parts: `group` is the ACL principal namespace, and `<id>` is the `groups.id` value. Group ids follow the existing short UID style used by platform users: prefix + 6 lowercase hex chars. Users use `u_<6hex>`; Groups use `g_<6hex>`. The readable meaning comes from `display_name`, not from the id.

`kind` 沒有 DB CHECK constraint。原因是它只服務 UI 顯示，未來新增 `SQUAD`、`PROJECT`、`WAR_ROOM` 不應該為了改文案或 icon 動資料庫 migration。Application 層用 `GroupKind` 提供目前支援的選項，API 對未知 kind 以 `OTHER` 顯示 fallback。

`group_closure` 的規則：

- 每個 Group 都有 self row：`ancestor_id = descendant_id`, `depth = 0`。
- 新增子 Group 時，複製 parent 的 ancestors，再加 self row。
- 移動 Group 時，重建該 subtree 的 closure rows。
- `PrincipalContextService` 查 `group_members` + `group_closure`，把使用者直接所屬 Group 與所有 ancestor 轉成 `group:<ancestorId>`。

### 2.4 Tree Query Strategy

`GET /api/v1/groups/tree` 不做 N+1，也不為每個 parent 查一次 child。讀取完整樹時一次查出 active rows，再在 Java 裡用 `parent_id` 組成 nested response。

```sql
SELECT id, parent_id, kind, display_name, slug, sort_order
FROM groups
WHERE status = 'ACTIVE'
ORDER BY parent_id NULLS FIRST, sort_order, display_name;
```

Backend assembly:

```java
var byId = rows.stream().collect(toMap(GroupRow::id, GroupTreeResponse::from));
var roots = new ArrayList<GroupTreeResponse>();

for (var row : rows) {
    var node = byId.get(row.id());
    if (row.parentId() == null) {
        roots.add(node);
    } else {
        byId.get(row.parentId()).children().add(node);
    }
}
```

原因：group management tree 是管理 UI，資料量通常遠小於 skill list/search；一次 flat query + in-memory assembly 最簡單，也避免 recursive CTE 的排序和 JSON aggregation 複雜度。`idx_groups_parent_order` 支援 parent/sibling ordering。

需要查單一 subtree 時用 closure table，不用 recursive CTE：

```sql
SELECT g.id, g.parent_id, g.kind, g.display_name, g.slug, g.sort_order, c.depth
FROM group_closure c
JOIN groups g ON g.id = c.descendant_id
WHERE c.ancestor_id = :rootGroupId
  AND g.status = 'ACTIVE'
ORDER BY c.depth, g.parent_id NULLS FIRST, g.sort_order, g.display_name;
```

需要 path labels 時也用 closure table：

```sql
SELECT ancestor.display_name
FROM group_closure c
JOIN groups ancestor ON ancestor.id = c.ancestor_id
WHERE c.descendant_id = :groupId
  AND ancestor.status = 'ACTIVE'
ORDER BY c.depth DESC;
```

`ORDER BY c.depth DESC` 會回 root → leaf，因為 root 到 target 的 depth 最大，self depth 是 0。

### 2.5 Domain Model

```java
@Table("groups")
public class Group extends AbstractAggregateRoot<Group> implements Persistable<String> {
    @Id private String id;
    private String parentId;
    private GroupKind kind;
    private String displayName;
    private String slug;
    private GroupStatus status;

    public static Group create(CreateGroupCommand command) { ... }
    public void rename(String displayName) { ... }
    public void moveTo(String newParentId) { ... }
    public void archive() { ... }
}

public enum GroupKind {
    COMPANY, DEPARTMENT, TEAM, OTHER
}
```

Membership 不放進 aggregate 子集合。原因是 Spring Data JDBC 對 child collection update 會偏向整組處理；成員異動是高頻操作，獨立 `group_members` row + service command 比較穩。

### 2.6 Principal Context Flow

```text
POST /api/v1/groups/{groupId}/members
  -> INSERT group_members
  -> publish UserAddedToGroupEvent(userId, groupId)
  -> no principal context table update needed

PUT /api/v1/groups/{groupId}/parent
  -> update groups.parent_id
  -> rebuild group_closure for moved subtree
  -> publish GroupMovedEvent(groupId)
  -> no principal context table update needed

PrincipalContextService.currentPrincipalKeys()
  -> add user:<currentUser.userId()>
  -> SELECT DISTINCT 'group:' || c.ancestor_id
     FROM group_members m
     JOIN group_closure c ON c.descendant_id = m.group_id
     JOIN groups g ON g.id = c.ancestor_id
     WHERE m.user_id = :currentUserId
       AND g.status = 'ACTIVE'
```

Optimized SQL:

```sql
SELECT DISTINCT 'group:' || c.ancestor_id AS principal_key
FROM group_members m
JOIN group_closure c ON c.descendant_id = m.group_id
JOIN groups g ON g.id = c.ancestor_id
WHERE m.user_id = :currentUserId
  AND g.status = 'ACTIVE';
```

Index usage:

- `idx_group_members_user` finds Bob's direct groups.
- `idx_group_closure_descendant` expands each direct group to itself + ancestors.
- `groups` primary key checks ancestor status.

This query is not on the skill list/search hot table. It runs once per request to produce a small ACL pattern array, then S169 applies that array to `skills.acl_entries` / `vector_store.acl_entries` before pagination.

這不是 JWT claim 展開，也不是另一張 projection 表。Google OAuth 只負責讓 `CurrentUserProvider` 解析出平台 `userId`；Group membership 完全由平台 DB 維護。

### 2.7 API Contract

```text
GET    /api/v1/groups/tree
POST   /api/v1/groups
PUT    /api/v1/groups/{id}
PUT    /api/v1/groups/{id}/parent
DELETE /api/v1/groups/{id}

GET    /api/v1/groups/{id}/members
POST   /api/v1/groups/{id}/members
DELETE /api/v1/groups/{id}/members/{userId}

GET    /api/v1/groups/search?q=cloud
```

```json
POST /api/v1/groups
{
  "parentId": "g_a1b2c3",
  "kind": "DEPARTMENT",
  "displayName": "Cloud"
}
```

```json
GET /api/v1/groups/search?q=cloud
[
  {
    "id": "g_d4e5f6",
    "principalKey": "group:g_d4e5f6",
    "kind": "DEPARTMENT",
    "displayName": "Cloud",
    "path": ["Acme", "Cloud"],
    "memberCount": 12
  }
]
```

### 2.8 Delete Semantics

`DELETE /api/v1/groups/{id}` archives the Group and its subtree in S170. It also removes memberships from archived Groups, so later `PrincipalContextService.currentPrincipalKeys()` will no longer return principals from that subtree.

Reason: after S169, skill grants may reference `group:<id>`. Hard delete would leave historical grants unreadable. Archive preserves Group identity while removing the archived subtree from active principal context.

### 2.9 Research Citations

| Source | Finding |
|--------|---------|
| `docs/grimo/PRD.md` §Organization Model | Product already names hard structure and soft structure, but the user clarified they share one behavior: tree Group + members. |
| PostgreSQL 16 recursive CTE docs: https://www.postgresql.org/docs/16/queries-with.html | Recursive queries support tree traversal, but S170 keeps traversal results in `group_closure` so principal context lookup does not need recursive work on every request. |
| PostgreSQL `ltree` docs: https://www.postgresql.org/docs/current/ltree.html | `ltree` is purpose-built for hierarchical paths and GiST indexes, but it adds an extension/custom type surface; not needed for this MVP. |
| Spring Data JDBC docs: https://docs.spring.io/spring-data/relational/reference/jdbc.html | Spring Data JDBC provides repository support around aggregate-oriented JDBC persistence; S170 keeps high-churn memberships out of the aggregate child collection. |
| Spring Modulith events docs: https://docs.spring.io/spring-modulith/reference/events.html | Event Publication Registry records listener publications in the original transaction; S170 still publishes group lifecycle events for audit/future consumers even though principal context is queried directly. |

## 3. SBE Acceptance Criteria

驗證指令：

- Run: `./scripts/verify-all.sh`
- Pass: all tests carrying `S170 AC-*` ids are green.

AC-1: Create root and child Groups
Given admin creates root Group `Acme` with kind `COMPANY`
When admin creates child Group `Cloud` with kind `DEPARTMENT` under `Acme`
Then `groups.parent_id` for `Cloud` is `Acme`
And `group_closure` has `Acme -> Cloud` depth 1
And `Cloud -> Cloud` depth 0

AC-2: Kind does not restrict children
Given Groups with kind `COMPANY`, `DEPARTMENT`, `TEAM`, and `OTHER`
When admin creates a child under each Group
Then every create succeeds
And no kind has special child restrictions

AC-3: Kind does not restrict direct members
Given Groups with kind `COMPANY`, `DEPARTMENT`, `TEAM`, and `OTHER`
When admin adds Bob to each Group
Then `group_members` stores one row for each Group and Bob

AC-4: Physical department membership includes ancestors in principal context
Given `Acme -> Cloud -> Platform Team`
When Bob is added to `Platform Team`
Then `PrincipalContextService` includes `group:g_123abc`
And includes `group:g_d4e5f6`
And includes `group:g_a1b2c3`

AC-5: Team membership can coexist with physical department membership
Given Bob belongs to physical Group `Platform Team`
And root Team `AI Enablement` has no parent
When Bob is added to `AI Enablement`
Then Bob still has `group:g_123abc`, `group:g_d4e5f6`, and `group:g_a1b2c3`
And Bob also has `group:g_789def`
And no membership overwrites another membership

AC-6: Removing membership changes only the affected user's principal context
Given Bob belongs to `Platform Team`
And Bob also belongs to `AI Enablement`
When Bob is removed from `AI Enablement`
Then Bob still has `group:g_123abc`
And Bob still has `group:g_d4e5f6`
And Bob no longer has `group:g_789def`

AC-7: Moving subtree rebuilds closure and changes principal context
Given `Acme -> Cloud -> Platform Team`
And Bob belongs to `Platform Team`
When admin moves `Cloud` under `Global`
Then `group_closure` no longer has `Acme -> Platform Team`
And Bob has `group:g_456abc`
And Bob no longer has `group:g_a1b2c3`

AC-8: Cannot create a cycle
Given `Acme -> Cloud -> Platform Team`
When admin moves `Acme` under `Platform Team`
Then API returns 409
And existing closure rows are unchanged

AC-9: Tree API returns nested Groups
Given `Acme -> Cloud -> Platform Team`
When caller GET `/api/v1/groups/tree`
Then response contains `Acme.children[0].displayName = Cloud`
And `Cloud.children[0].displayName = Platform Team`

AC-10: Search returns principal-ready Groups
Given Group `Cloud`
When caller GET `/api/v1/groups/search?q=cloud`
Then result includes `principalKey = group:<cloudId>`
And includes path labels from root to Group

AC-11: Delete archives subtree and removes active principal context
Given `Cloud -> Platform Team`
And Bob belongs to `Platform Team`
When admin DELETE `/api/v1/groups/{cloudId}`
Then `Cloud` and `Platform Team` status are `ARCHIVED`
And Bob no longer has `group:g_d4e5f6` or `group:g_123abc`

AC-12: Duplicate sibling slug is rejected
Given `Acme` has child `Cloud`
When admin creates another child named `Cloud` under `Acme`
Then API returns 409
And no duplicate `groups(parent_id, slug)` row is written

AC-13: UI can manage tree and members
Given admin opens group management page
When admin selects `Cloud`
Then UI shows child Group controls and member list
And buttons are labeled in zh-TW

AC-14: Scenario anchor is covered by unit tests
Given S170 test suite runs
When `PrincipalContextServiceTest` seeds `Acme -> Cloud -> Platform Team` and root `AI Enablement`
Then the test asserts Bob has all four principals from the scenario anchor
And the test asserts removing `AI Enablement` membership does not remove physical department principals

AC-15: Group id uses project short UID style
Given admin creates a Group
When backend assigns `groups.id`
Then the id matches `^g_[0-9a-f]{6}$`
And if a generated id already exists, backend retries with a new candidate before failing

## 4. Interface / API Design

### Backend Records

```java
public record CreateGroupCommand(
    @Nullable String parentId,
    GroupKind kind,
    String displayName
) {}

public record MoveGroupCommand(
    String groupId,
    @Nullable String newParentId
) {}

public record GroupTreeResponse(
    String id,
    @Nullable String parentId,
    GroupKind kind,
    String displayName,
    String principalKey,
    List<GroupTreeResponse> children
) {}

public record GroupSearchResult(
    String id,
    String principalKey,
    GroupKind kind,
    String displayName,
    List<String> path,
    int memberCount
) {}
```

### Principal Context

```java
public record PrincipalKey(String value) {}

public interface PrincipalContextService {
    List<PrincipalKey> currentPrincipalKeys();
}
```

`PrincipalContextService.currentPrincipalKeys()` returns:

1. `user:<currentUser.userId()>`
2. every active `group:<ancestorId>` produced by joining `group_members` and `group_closure`

### Events

```java
public record GroupCreatedEvent(String groupId, @Nullable String parentId) {}
public record GroupMovedEvent(String groupId, @Nullable String oldParentId, @Nullable String newParentId) {}
public record GroupArchivedEvent(String groupId) {}
public record UserAddedToGroupEvent(String userId, String groupId) {}
public record UserRemovedFromGroupEvent(String userId, String groupId) {}
```

### Frontend

`/settings/groups` is an operational tree editor:

- left pane: nested Group tree
- right pane: selected Group editor
- members tab: add/remove users
- children tab: add child Group, move Group, archive Group

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V23__group_tree_principals.sql` | new | Add `groups`, `group_closure`, `group_members`; V22 is already used by S156c request voting-board simplification. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupIdGenerator.java` | new | Generate `g_<6hex>` ids using the same short UID style as platform users. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/Group.java` | new | Rich aggregate for group fields and lifecycle. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupKind.java` | new | `COMPANY`, `DEPARTMENT`, `TEAM`, `OTHER`; display-only kind. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupRepository.java` | new | Spring Data JDBC repository. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupService.java` | new | Create/update/move/archive Groups and maintain closure table. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupMembershipService.java` | new | Add/remove direct members and publish membership events. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupController.java` | new | REST endpoints for tree CRUD and members. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupQueryController.java` | new | Tree and search endpoints. |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/PrincipalContextService.java` | new | Returns `user:<id>` plus active Group ancestor principals by querying `group_members` + `group_closure`. |
| `frontend/src/api/groups.ts` | new | Tree, CRUD, search, membership API client. |
| `frontend/src/pages/GroupsPage.tsx` | new | `/settings/groups` tree management page. |
| `frontend/src/components/GroupTree.tsx` | new | Nested tree component. |
| `frontend/src/components/GroupEditor.tsx` | new | Group detail, child controls, member controls. |
| `frontend/src/App.tsx` | modify | Add `/settings/groups` route. |
| `docs/grimo/glossary.md` | modify | Add Group and group principal terms. |

### Test Files

| File | AC |
|------|----|
| `backend/src/test/java/.../org/GroupServiceTest.java` | AC-1, AC-2, AC-8, AC-12, AC-15 |
| `backend/src/test/java/.../org/GroupMembershipServiceTest.java` | AC-3, AC-6 |
| `backend/src/test/java/.../org/PrincipalContextServiceTest.java` | AC-4, AC-5, AC-6, AC-7, AC-11, AC-14 |
| `backend/src/test/java/.../org/GroupQueryControllerTest.java` | AC-9, AC-10 |
| `frontend/src/pages/GroupsPage.test.tsx` | AC-13 |

---

## 6. Task Plan

### 6.1 POC Decision

POC: not required. S170 uses PostgreSQL tables, Spring Data JDBC repositories, repository/service/controller patterns, and React page/component patterns already used in shipped specs. The only new design surface is the Group domain model itself; §3 AC-1 through AC-15 cover the behavior directly through backend tests and one frontend page test.

### 6.2 Execution Order

Preflight 2026-05-14: `rg --files backend/src/main/resources/db/migration` shows `V22__request_voting_board_simplification.sql` already exists, so T01 must create `V23__group_tree_principals.sql` instead of the original draft `V22` file name.

| Order | Task | AC | Depends On | Scope |
|-------|------|----|------------|-------|
| 1 | `2026-05-14-S170-T01-data-foundation.md` | AC-1, AC-2, AC-8, AC-12, AC-15 | none | Migration, id generator, Group aggregate/repository, closure self/ancestor rows, duplicate/cycle guards. |
| 2 | `2026-05-14-S170-T02-membership-principals.md` | AC-3, AC-4, AC-5, AC-6, AC-14 | T01 | Direct membership service, membership events, `PrincipalContextService` direct + ancestor principal output. |
| 3 | `2026-05-14-S170-T03-move-archive.md` | AC-7, AC-11 | T02 | Subtree move closure rebuild, archive subtree, remove archived subtree from active principal context. |
| 4 | `2026-05-14-S170-T04-query-api.md` | AC-9, AC-10 | T03 | REST tree/search/member endpoints and principal-ready search response. |
| 5 | `2026-05-14-S170-T05-frontend-groups-page.md` | AC-13 | T04 | `/settings/groups` UI, API client, tree/editor/member controls, zh-TW labels. |

### 6.3 Verification Chain

Run backend task tests from narrow to broad:

```bash
cd backend && ./gradlew test --tests "*GroupServiceTest"
cd backend && ./gradlew test --tests "*GroupMembershipServiceTest" --tests "*PrincipalContextServiceTest"
cd backend && ./gradlew test --tests "*GroupQueryControllerTest"
```

Run frontend task tests after T05:

```bash
cd frontend && npm test -- --run src/pages/GroupsPage.test.tsx
```

Final verification after all tasks pass:

```bash
./scripts/verify-all.sh
```

### 6.4 Next Task

Start with `docs/grimo/tasks/2026-05-14-S170-T01-data-foundation.md`.

<!-- Section 7 added by /planning-tasks after implementation -->
