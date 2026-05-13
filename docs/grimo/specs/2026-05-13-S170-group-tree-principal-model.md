# S170: Group tree principal model

> Spec: S170 | Size: M(16) | Status: 📐 in-design
> Date: 2026-05-13
> Origin: S169 前置 spec。Company / Department / 虛擬組織本質上都是「把人框在一起」的 Group；每個 Group 都可掛使用者，也可掛子 Group。
> Depends On: S154 ✅

---

## 1. Goal

管理者在「群組」頁面新增 `Acme` 公司群組，再新增子群組 `Cloud` 部門，也可以憑空新增 `AI Enablement` 這種跨公司的專案群組；每個群組都能直接放人，也能再放子群組。後端把使用者所屬群組與所有父群組投影到 `user_acl_principals`，供 S169 分享權限使用。

```text
groups:
  Acme                  kind=COMPANY
    Cloud               kind=DEPARTMENT
      Platform Team     kind=TEAM
  AI Enablement         kind=TEAM

group_members:
  Bob -> Platform Team
  Bob -> AI Enablement

user_acl_principals for Bob:
  user:u_bob
  group:platform_team
  group:cloud
  group:acme
  group:ai_enablement
```

`kind` 是給人看的分類欄位，不是資料庫行為。DB 不因 `kind` 改變 child rule、membership rule、ACL rule 或刪除規則；所有 row 都是同一種 Group。初始 kind 只提供 `COMPANY`, `DEPARTMENT`, `TEAM`, `OTHER` 給 UI 顯示、icon、搜尋篩選與預設文案。

同一個人可以同時在實體部門與多個 TEAM 內。例：Bob 可以是 `Acme / Cloud / Platform Team` 的成員，也可以同時在 root TEAM `AI Enablement` 內；投影時保留全部 principal，不互相覆蓋。

### Scenario Anchor

S170 的驗收標準與單元測試都以這個情境為核心，不只驗 CRUD：

1. `Acme` 是 `COMPANY` Group。
2. `Cloud` 是 `DEPARTMENT` Group，parent 是 `Acme`。
3. `Platform Team` 是 `TEAM` Group，parent 是 `Cloud`。
4. `AI Enablement` 是 `TEAM` Group，沒有 parent，代表憑空建立的跨公司虛擬組織。
5. Bob 同時被放進 `Platform Team` 與 `AI Enablement`。
6. Bob 的 `user_acl_principals` 必須同時有 `group:platform_team`, `group:cloud`, `group:acme`, `group:ai_enablement`。

這個情境驗證三件事：`kind` 不控制 DB 行為、TEAM 可 root、同一個人可同時屬於實體組織路徑與跨公司 TEAM。

### Current Code Facts

| Path | 現況 | 需要改 |
|------|------|--------|
| `docs/grimo/PRD.md` | 有 Organization / Company / Department / 軟結構概念，但標成設計先行、MVP 不啟用 | S170 把它收斂成同一種 `Group` 可用模型 |
| `backend/.../shared/security/CurrentUser.java` | 只讀 JWT `groups` / `companyId` claim | 不拿 JWT 當產品組織來源；新增 DB-backed principal projection |
| `backend/.../shared/security/AclPrincipalExpander.java` | 展開 user / role / JWT group / company | 後續 S169 改由 `user_acl_principals` 取得 `group:<id>` |
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
    id             VARCHAR(64) PRIMARY KEY,
    parent_id      VARCHAR(64) REFERENCES groups(id),
    kind           VARCHAR(24) NOT NULL,
    display_name   VARCHAR(160) NOT NULL,
    slug           VARCHAR(80) NOT NULL,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    sort_order     INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (parent_id, slug)
);

CREATE TABLE group_closure (
    ancestor_id    VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    descendant_id  VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    depth          INTEGER NOT NULL,
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE TABLE group_members (
    group_id       VARCHAR(64) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id        VARCHAR(64) NOT NULL REFERENCES users(user_id),
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE user_acl_principals (
    user_id        VARCHAR(64) NOT NULL REFERENCES users(user_id),
    principal_key  VARCHAR(160) NOT NULL,
    source         VARCHAR(24) NOT NULL CHECK (source IN ('USER','GROUP')),
    source_id      VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, principal_key)
);
```

`kind` 沒有 DB CHECK constraint。原因是它只服務 UI 顯示，未來新增 `SQUAD`、`PROJECT`、`WAR_ROOM` 不應該為了改文案或 icon 動資料庫 migration。Application 層用 `GroupKind` 提供目前支援的選項，API 對未知 kind 以 `OTHER` 顯示 fallback。

`group_closure` 的規則：

- 每個 Group 都有 self row：`ancestor_id = descendant_id`, `depth = 0`。
- 新增子 Group 時，複製 parent 的 ancestors，再加 self row。
- 移動 Group 時，重建該 subtree 的 closure rows。
- 使用者掛在子 Group 時，`user_acl_principals` 投影該 Group 與所有 ancestor。

### 2.4 Domain Model

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

### 2.5 Projection Flow

```text
POST /api/v1/groups/{groupId}/members
  -> INSERT group_members
  -> publish UserAddedToGroupEvent(userId, groupId)
  -> after commit listener:
       DELETE user_acl_principals WHERE user_id = :userId AND source = 'GROUP'
       INSERT group:<ancestorId> for all direct memberships' ancestors

PUT /api/v1/groups/{groupId}/parent
  -> update groups.parent_id
  -> rebuild group_closure for moved subtree
  -> publish GroupMovedEvent(groupId)
  -> after commit listener:
       find users under moved subtree
       rebuild user_acl_principals for affected users
```

`user:<userId>` principal 由 user lifecycle / projection bootstrap 保證存在；S170 listener 不刪 `source = USER` rows。

### 2.6 API Contract

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
  "parentId": "grp_acme",
  "kind": "DEPARTMENT",
  "displayName": "Cloud"
}
```

```json
GET /api/v1/groups/search?q=cloud
[
  {
    "id": "grp_cloud",
    "principalKey": "group:grp_cloud",
    "kind": "DEPARTMENT",
    "displayName": "Cloud",
    "path": ["Acme", "Cloud"],
    "memberCount": 12
  }
]
```

### 2.7 Delete Semantics

`DELETE /api/v1/groups/{id}` archives the Group and its subtree in S170. It also removes memberships from archived Groups and rebuilds affected `user_acl_principals`.

Reason: after S169, skill grants may reference `group:<id>`. Hard delete would leave historical grants unreadable. Archive preserves Group identity while removing active membership projection.

### 2.8 Research Citations

| Source | Finding |
|--------|---------|
| `docs/grimo/PRD.md` §Organization Model | Product already names hard structure and soft structure, but the user clarified they share one behavior: tree Group + members. |
| PostgreSQL 16 recursive CTE docs: https://www.postgresql.org/docs/16/queries-with.html | Recursive queries support tree traversal, but S170 keeps traversal results in `group_closure` so ACL projection does not need recursive work on every request. |
| PostgreSQL `ltree` docs: https://www.postgresql.org/docs/current/ltree.html | `ltree` is purpose-built for hierarchical paths and GiST indexes, but it adds an extension/custom type surface; not needed for this MVP. |
| Spring Data JDBC docs: https://docs.spring.io/spring-data/relational/reference/jdbc.html | Spring Data JDBC provides repository support around aggregate-oriented JDBC persistence; S170 keeps high-churn memberships out of the aggregate child collection. |
| Spring Modulith events docs: https://docs.spring.io/spring-modulith/reference/events.html | Event Publication Registry records listener publications in the original transaction and supports reliable after-commit projection updates. |

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

AC-4: Physical department membership projects ancestors
Given `Acme -> Cloud -> Platform Team`
When Bob is added to `Platform Team`
Then `user_acl_principals` includes `group:platform_team`
And includes `group:cloud`
And includes `group:acme`

AC-5: Team membership can coexist with physical department membership
Given Bob belongs to physical Group `Platform Team`
And root Team `AI Enablement` has no parent
When Bob is added to `AI Enablement`
Then Bob still has `group:platform_team`, `group:cloud`, and `group:acme`
And Bob also has `group:ai_enablement`
And no membership overwrites another membership

AC-6: Removing membership rebuilds only affected user principals
Given Bob belongs to `Platform Team`
And Bob also belongs to `AI Enablement`
When Bob is removed from `AI Enablement`
Then Bob still has `group:platform_team`
And Bob still has `group:cloud`
And Bob no longer has `group:ai_enablement`

AC-7: Moving subtree rebuilds closure and principals
Given `Acme -> Cloud -> Platform Team`
And Bob belongs to `Platform Team`
When admin moves `Cloud` under `Global`
Then `group_closure` no longer has `Acme -> Platform Team`
And Bob has `group:global`
And Bob no longer has `group:acme`

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

AC-11: Delete archives subtree and removes projection
Given `Cloud -> Platform Team`
And Bob belongs to `Platform Team`
When admin DELETE `/api/v1/groups/{cloudId}`
Then `Cloud` and `Platform Team` status are `ARCHIVED`
And Bob no longer has `group:cloud` or `group:platform_team`

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
When `GroupPrincipalProjectionListenerTest` seeds `Acme -> Cloud -> Platform Team` and root `AI Enablement`
Then the test asserts Bob has all four principals from the scenario anchor
And the test asserts removing `AI Enablement` membership does not remove physical department principals

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
| `backend/src/main/resources/db/migration/V22__group_tree_principals.sql` | new | Add `groups`, `group_closure`, `group_members`, `user_acl_principals`. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/Group.java` | new | Rich aggregate for group fields and lifecycle. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupKind.java` | new | `COMPANY`, `DEPARTMENT`, `TEAM`, `OTHER`; display-only kind. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupRepository.java` | new | Spring Data JDBC repository. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupService.java` | new | Create/update/move/archive Groups and maintain closure table. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupMembershipService.java` | new | Add/remove direct members and publish membership events. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupPrincipalProjectionListener.java` | new | Rebuild `user_acl_principals` for affected users. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupController.java` | new | REST endpoints for tree CRUD and members. |
| `backend/src/main/java/io/github/samzhu/skillshub/org/GroupQueryController.java` | new | Tree and search endpoints. |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/PrincipalContextService.java` | new | Loads `user_acl_principals` for current user; S169 consumes it. |
| `frontend/src/api/groups.ts` | new | Tree, CRUD, search, membership API client. |
| `frontend/src/pages/GroupsPage.tsx` | new | `/settings/groups` tree management page. |
| `frontend/src/components/GroupTree.tsx` | new | Nested tree component. |
| `frontend/src/components/GroupEditor.tsx` | new | Group detail, child controls, member controls. |
| `frontend/src/App.tsx` | modify | Add `/settings/groups` route. |
| `docs/grimo/glossary.md` | modify | Add Group and group principal terms. |

### Test Files

| File | AC |
|------|----|
| `backend/src/test/java/.../org/GroupServiceTest.java` | AC-1, AC-2, AC-8, AC-12 |
| `backend/src/test/java/.../org/GroupMembershipServiceTest.java` | AC-3, AC-6 |
| `backend/src/test/java/.../org/GroupPrincipalProjectionListenerTest.java` | AC-4, AC-5, AC-6, AC-7, AC-11, AC-14 |
| `backend/src/test/java/.../org/GroupQueryControllerTest.java` | AC-9, AC-10 |
| `frontend/src/pages/GroupsPage.test.tsx` | AC-13 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
