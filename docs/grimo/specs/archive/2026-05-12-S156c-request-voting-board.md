# S156c: Request 簡化為投票需求板 + Detail Page + Comments

> Spec: S156c | Size: M(10) | Status: ✅ Done
> Date: 2026-05-12
> Origin: 取代 S156b（claim/fulfill 設計不再符合產品方向 — voting-board pivot）
> Depends On: S156 ✅, S096g2 ✅ (Request aggregate 既有，但 claim/release/fulfill 機制要拆)
> Supersedes: S156b

---

## 1. Goal

**一句話：** Request 從「需求接案系統」(post → claim → fulfill) 簡化為「需求投票板」(post → vote / comment) — user 看到票多的 request 就知道「這個需求很多人要」，產品 / 作者**自己決定**要不要做。沒有 claim/fulfill 流程。

**為什麼重要：**

- 既有 claim/fulfill 機制（S096g2 v3.6.0 ship）MVP 階段沒人用，留著就是技術債（aggregate 多 3 method、3 events、3 endpoints、frontend ActionBar component）
- detail page 從未做 → user 看不到完整描述 / 留言 / deep-link 分享
- 不拆乾淨而疊新 detail page → 變成「按鈕還在但已無 product value」的半成品狀態，更糟

**非目標：**

- 不做 close (CLOSED state) — `DELETE /requests/{id}` 既有 hard delete 已足夠
- 不做 edit description — minimal scope；如需要再開後續 spec
- 不做 threaded comment / @mention — simple list
- 不做 comment edit — delete only（soft delete）

---

## 2. Approach

### 2.1 現況 (S096g2 已 ship)

**Request aggregate** (`community/Request.java`)：

- 5 method：`create` / `claim` / `release` / `fulfill` / `assertDeletable`
- State machine：`OPEN` → `IN_PROGRESS` → `FULFILLED`
- Fields：`status` / `claimerId` / `fulfilledSkillId`（受 claim/fulfill 機制驅動）

**5 個 events：** `RequestPostedEvent` / `RequestClaimedEvent` / `RequestReleasedEvent` / `RequestFulfilledEvent` / `RequestVotedEvent`

**8 個 endpoints：**

| Method | Path | 動作 | 本 spec 處置 |
|---|---|---|---|
| POST | `/api/v1/requests` | create | 保留 |
| GET | `/api/v1/requests` | list | 保留（response shape 精簡） |
| GET | `/api/v1/requests/{id}` | detail | **enrich**（加 comments + canDelete） |
| DELETE | `/api/v1/requests/{id}` | delete own | 保留（guard 簡化為 requester only） |
| POST | `/api/v1/requests/{id}/vote` | toggle vote | 保留 |
| POST | `/api/v1/requests/{id}/claim` | claim | **刪除** |
| DELETE | `/api/v1/requests/{id}/claim` | release | **刪除** |
| POST | `/api/v1/requests/{id}/fulfill` | fulfill | **刪除** |

**Frontend：**

- `RequestBoardPage` 用 `RequestActionBar` component 顯 claim / unclaim / fulfill 按鈕
- `api/skills.ts` 有 `claimRequest` / `unclaimRequest` / `fulfillRequest` function + type `SkillRequest` 含 `claimerId` / `fulfilledSkillId` / `status`
- **沒有** RequestDetailPage

**Notification consumer：**

- `NotificationProjectionListener.onRequestClaimed`（RequestClaimedEvent → 通知 requester）
- `NotificationProjectionListener.onRequestFulfilled`（RequestFulfilledEvent → 通知 requester）

**Audit consumer：** `AuditEventListener` 沒訂閱任何 Request* event（grep 確認）— 本 spec 也不加。

### 2.2 簡化後設計

**Request aggregate：** 2 method（`create` + `addComment`）；無 state machine；無 `status` / `claimerId` / `fulfilledSkillId` field

**3 個 events：** `RequestPostedEvent`（保留）/ `RequestVotedEvent`（保留）/ `RequestCommentedEvent`（新增）

**7 個 endpoints：**

| Method | Path | 動作 | Auth |
|---|---|---|---|
| POST | `/api/v1/requests` | create | logged-in |
| GET | `/api/v1/requests?sort=` | list | public |
| GET | `/api/v1/requests/{id}` | detail (含 comments + canDelete) | public（canDelete 看 currentUser） |
| DELETE | `/api/v1/requests/{id}` | delete | requester only |
| POST | `/api/v1/requests/{id}/vote` | toggle | logged-in |
| POST | `/api/v1/requests/{id}/comments` | add comment | logged-in |
| DELETE | `/api/v1/requests/{id}/comments/{cid}` | soft delete comment | comment author |

⚠️ `?status=` query param 從 GET `/api/v1/requests` 移除（status column 拆掉 → 變 no-op）。Frontend 也要砍對應呼叫，否則會被 S159a unknown param 拒收。

### 2.3 拆除清單（claim/release/fulfill machinery）

| 檔案 | 動作 |
|---|---|
| `community/Request.java` | 刪 `claim` / `release` / `fulfill` / `assertDeletable` method；刪 `status` / `claimerId` / `fulfilledSkillId` field；移除相關 import |
| `community/RequestService.java` | 刪 `claim` / `release` / `fulfill` method；`deleteRequest` guard 簡化為「requester only」（無 status guard） |
| `community/RequestCommandController.java` | 刪 POST `/claim` / DELETE `/claim` / POST `/fulfill` 三 endpoint + `FulfillBody` record |
| `community/RequestQueryController.java` | `RequestResponse` 移除 `status` / `claimerId` / `fulfilledSkillId`；GET `/{id}` 改回 `RequestDetailResponse`（含 comments + canDelete） |
| `community/events/RequestClaimedEvent.java` | 整檔刪除 |
| `community/events/RequestReleasedEvent.java` | 整檔刪除 |
| `community/events/RequestFulfilledEvent.java` | 整檔刪除 |
| `shared/api/NotRequestClaimerException.java` | 整檔刪除（唯一 caller 是 release/fulfill） |
| `shared/api/SkillNotPublishableException.java` | 保留（CollectionService 也用） — 只移除 RequestService 的 caller |
| `notification/NotificationProjectionListener.java` | 刪 `onRequestClaimed` / `onRequestFulfilled` listener；加 `onRequestCommented` |
| `notification/package-info.java` | 更新文字（移除 RequestClaimedEvent / RequestFulfilledEvent） |

### 2.4 新增：Comment

**Schema (V22 migration)：**

```sql
CREATE TABLE request_comments (
    id          VARCHAR(36)  PRIMARY KEY,
    request_id  VARCHAR(36)  NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    author_id   VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL CHECK (length(content) BETWEEN 1 AND 5000),
    created_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX idx_request_comments_request ON request_comments(request_id, created_at);
```

**Entity (`community/RequestComment.java`)：**

```java
@Table("request_comments")
public class RequestComment {
    @Id private String id;
    @Column("request_id") private String requestId;
    @Column("author_id")  private String authorId;
    private String content;
    @Column("created_at") private Instant createdAt;
    @Column("deleted_at") private Instant deletedAt;
    @PersistenceCreator private RequestComment() {}
    public static RequestComment create(String requestId, String authorId, String content) { ... }
    public void softDelete() { this.deletedAt = Instant.now(); }
    // getters
}
```

**Event：**

```java
public record RequestCommentedEvent(
    String commentId, String requestId, String authorId,
    String content, Instant occurredAt) {}
```

**Request aggregate 加 method（充血）：**

```java
public void addComment(String commentId, String authorId, String content) {
    registerEvent(new RequestCommentedEvent(commentId, this.id, authorId, content, Instant.now()));
}
```

Service 端 `CommentService`：3-line orchestration（load request → registerEvent → insert comment row + repo.save(request) trigger outbox）。

**Detail response shape (GET /{id})：**

```json
{
  "id": "uuid",
  "title": "需要 k8s autoscaler skill",
  "description": "詳細描述 markdown...",
  "requesterId": "u_alice",
  "voteCount": 12,
  "createdAt": "2026-05-12T10:00:00Z",
  "updatedAt": "2026-05-12T10:00:00Z",
  "comments": [
    {"id": "c_001", "authorId": "u_bob",     "content": "+1 我也要",    "createdAt": "2026-05-12T11:00:00Z"},
    {"id": "c_002", "authorId": "u_charlie", "content": "有人在做嗎？", "createdAt": "2026-05-12T12:00:00Z"}
  ],
  "canDelete": true
}
```

- `comments[]`：earliest first（ASC by `createdAt`）；soft-deleted (`deleted_at IS NOT NULL`) 不回傳
- `canDelete`：`requesterId === currentUser.userId`；未登入 = false
- List response (GET /api/v1/requests) **不含** `comments` / `canDelete` — 列表場景用不到，省流量

### 2.5 Frontend 拆除 + 新增

**拆除：**

- 刪 `components/RequestActionBar.tsx`（整 component 不要了）
- `api/skills.ts`：刪 `claimRequest` / `unclaimRequest` / `fulfillRequest` 三 function；type `SkillRequest` 移除 `claimerId` / `fulfilledSkillId` / `status` 三 field
- `pages/RequestBoardPage.tsx`：刪 `RequestActionBar` import + JSX；card title 改 `<Link to={/requests/${id}}>`
- `pages/RequestBoardPage.test.tsx`：fixture 移除 `claimerId` / `fulfilledSkillId` / `status` field

**新增：**

- `pages/RequestDetailPage.tsx` — route `/requests/:id`
- `components/CommentList.tsx` — list + delete button (own only)
- `components/CommentForm.tsx` — textarea + 送出
- `api/skills.ts` 加 `useRequestDetail(id)` / `usePostComment` / `useDeleteComment` hook
- `App.tsx` 加 route

**Detail page layout：**

```
┌── PageHeader ────────────────────────┐
│ ← 返回看板                              │
│ # 需要 k8s autoscaler skill             │
│ Alice Chen · 2 天前                     │
│ 👍 12  ← toggle vote                    │
└────────────────────────────────────────┘

┌── Description (markdown rendered) ────┐
│ 詳細描述...                            │
│ [🗑 刪除]（只有 requester 看得到）       │
└────────────────────────────────────────┘

┌── Comments ──────────────────────────┐
│ Bob · 1d ago                          │
│   +1 我也需要                          │
│   [Delete]（only own）                 │
│                                       │
│ Charlie · 12h ago                     │
│   有人在做？                           │
│                                       │
│ [textarea 回覆 + 送出 button]         │
└───────────────────────────────────────┘
```

### 2.6 DB migration (V22)

⚠️ User 確認：production / dev DB 將整批清空，**migration 不需保留 row**。

```sql
-- V22__request_voting_board_simplification.sql

-- 1. drop claim/fulfill columns + index
ALTER TABLE requests DROP COLUMN status;
ALTER TABLE requests DROP COLUMN claimer_id;
ALTER TABLE requests DROP COLUMN fulfilled_skill_id;
DROP INDEX IF EXISTS idx_requests_status;

-- 2. add request_comments
CREATE TABLE request_comments (
    id          VARCHAR(36)  PRIMARY KEY,
    request_id  VARCHAR(36)  NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    author_id   VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL CHECK (length(content) BETWEEN 1 AND 5000),
    created_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX idx_request_comments_request ON request_comments(request_id, created_at);
```

### 2.7 Notification 整合

`RequestCommentedEvent` 由 `NotificationProjectionListener.onRequestCommented` 訂閱：

- 通知 requester（除非 commenter === requester self）
- 對齊既有 `onRequestPosted` / `onReviewCreated` async listener pattern

### 2.8 Audit 整合 — Request events 全寫入 domain_events（Event Sourcing 精神）

`AuditEventListener` **本 spec 補上 3 個 Request listener**，對齊既有 8 個 Skill listener pattern（`@ApplicationModuleListener` + deterministic UUID + `ON CONFLICT (id) DO NOTHING` idempotent；詳 `AuditEventListener.java` class JavaDoc）：

| Event | event_type | dedupKey 構成 | 頻率 |
|---|---|---|---|
| `RequestPostedEvent` | `RequestPosted` | `requestId` | 1 per request |
| `RequestVotedEvent` | `RequestVoted` | `requestId + ":" + userId + ":" + voted + ":" + votedAt` | toggle on/off 多次 |
| `RequestCommentedEvent` | `RequestCommented` | `commentId` | 1 per comment |

**為什麼要寫 `domain_events`：**

- CLAUDE.md「Event log」段：`domain_events` 保留 ES 精神 — events 不可變、`(aggregate_id, sequence)` 嚴格遞增、理論上可 replay
- 即使 hard delete request row（AC-7）+ CASCADE 刪 comments，**對應 events 仍在 `domain_events` 永存** — 滿足「事件朔源都有保存 event」原則
- emergency 場景可走 `fromHistory` factory 重建 aggregate state（per CLAUDE.md「不主動 replay」）

**RequestVotedEvent dedupKey 設計說明：** 用戶可 toggle on/off 多次（mirror SkillDownloadedEvent 用戶可重複下載 pattern）；single user 可能在同毫秒內快速連點 → `votedAt` timestamp 加 `voted` boolean 一同放入 dedupKey 防 collision。若實作端 POC 發現 timestamp 仍可能 collide，task 階段改成 event 自帶 `eventId UUID`（對齊 SkillDownloadedEvent 設計）。

### 2.9 既有事件 / 表保留說明

| 物件 | 保留？ | 說明 |
|---|---|---|
| `RequestPostedEvent` | ✅ | create 仍 register；notification listener 既有 onRequestPosted 不動 |
| `RequestVotedEvent` | ✅ | vote toggle 仍 register；S096g2 既有 |
| `RequestVoteService` + `request_votes` 表 | ✅ | 全部不動 |
| `RequestRepository` | ✅ | 不動（只用到 list / findById / save / deleteById） |
| `requests` table | ✅ | 拆 3 column；其他不動（id / title / description / requester_id / vote_count / created_at / updated_at / version 保留） |

---

## 3. SBE Acceptance Criteria

| AC | Priority | Verify | Title |
|----|----------|--------|-------|
| AC-1 | Must | Test | Request aggregate 簡化（無 state machine） |
| AC-2 | Must | Test | claim/release/fulfill endpoints 全消失（404 / no route） |
| AC-3 | Must | Test | Vote toggle 維持運作（regression） |
| AC-4 | Must | Test | GET /{id} 回 detail + comments[] + canDelete |
| AC-5 | Must | Test | POST comment → RequestCommentedEvent → notification |
| AC-6 | Must | Test | DELETE comment owner-only (soft delete) |
| AC-7 | Must | Test | DELETE request requester-only (hard delete, cascade comments) |
| AC-8 | Must | Test | NotificationProjectionListener 不再有 claim/fulfill listener (regression) |
| AC-9 | Must | Test | RequestDetailPage 完整渲染（vote + desc + comments + form） |
| AC-10 | Must | Test | RequestBoardPage card title 點擊跳 detail |
| AC-11 | Must | Test | GET /{id} 不存在 → 404 + 前端友善 |
| AC-12 | Must | Test | Request events 全寫入 domain_events（ES 永存，hard delete 後仍在） |

**AC-1: Request aggregate 簡化（無 state machine）**

- Given `community/Request.java` 編譯
- Then aggregate 只有 `create` + `addComment` 兩 method；無 `claim` / `release` / `fulfill` / `assertDeletable`
- And 無 `status` / `claimerId` / `fulfilledSkillId` field
- And V22 migrate 完 `requests` 表也無對應 column（pg_columns inspection）
- And `community/events/` 無 `RequestClaimedEvent.java` / `RequestReleasedEvent.java` / `RequestFulfilledEvent.java` 三檔

**AC-2: claim/release/fulfill endpoints 全消失**

- Given backend 啟動
- When `POST /api/v1/requests/x/claim` → 404 NotFound
- When `DELETE /api/v1/requests/x/claim` → 404 NotFound
- When `POST /api/v1/requests/x/fulfill` → 404 NotFound

**AC-3: Vote toggle 維持運作（regression）**

- Given request r1 (voteCount=0), user u1 未投過
- When POST `/api/v1/requests/r1/vote` as u1 → voteCount=1
- When POST `/api/v1/requests/r1/vote` as u1 again → voteCount=0
- And `request_votes` 表行為不變
- And `RequestVotedEvent` 仍 publish

**AC-4: GET /{id} 回 detail + comments[] + canDelete**

- Given request r1（requester=alice, voteCount=5, comments=[c1, c2]）
- When GET `/api/v1/requests/r1` as alice
- Then 200 + JSON 含 `{id, title, description, requesterId, voteCount, createdAt, updatedAt, comments[2], canDelete=true}`
- And `comments` ASC by `createdAt`；soft-deleted 不回傳
- And response **沒** `status` / `claimerId` / `fulfilledSkillId` field
- When GET `/api/v1/requests/r1` as bob (非 requester) → `canDelete=false`
- When GET `/api/v1/requests/r1` 未登入 → `canDelete=false`

**AC-5: POST comment → event → notification**

- Given request r1 (requester=alice)
- When bob POST `/api/v1/requests/r1/comments` body=`{"content":"+1 我也要"}`
- Then 201 + JSON 含新 comment id
- And `request_comments` 多 1 row
- And `RequestCommentedEvent` 進 `event_publication` outbox
- And `NotificationProjectionListener.onRequestCommented` 寫 1 row notification 給 alice
- When alice 自己 POST comment 在自己 request → notification **不**寫（self-comment 不通知）

**AC-6: DELETE comment owner-only**

- Given bob's comment c1, charlie's comment c2 on request r1
- When DELETE `/requests/r1/comments/c1` as bob → 204 + `request_comments.c1.deleted_at` 不為 null
- When DELETE `/requests/r1/comments/c1` as charlie → 403 PERMISSION_DENIED（c1 已被 bob 刪過則 404）
- When DELETE `/requests/r1/comments/c2` as bob → 403
- And GET `/requests/r1` 不回傳已 soft-deleted comment

**AC-7: DELETE request requester-only**

- Given request r1 created by alice, with 2 comments
- When DELETE `/api/v1/requests/r1` as alice → 204 + `requests.r1` 從 DB 消失
- And `request_comments` 對應 row 也消失（CASCADE）
- And `domain_events` 對應 `RequestPosted` / `RequestVoted` / `RequestCommented` row **仍存在**（ES 永存 — 參 AC-12）
- When DELETE `/api/v1/requests/r1` as bob → 403

**AC-8: NotificationProjectionListener regression**

- Given `NotificationProjectionListener.java` 編譯
- Then 無 `onRequestClaimed` / `onRequestFulfilled` method（reflection / grep verify）
- And 有 `onRequestCommented` method
- And Spring Modulith verify 通過（無 dangling import）

**AC-9: RequestDetailPage 完整渲染**

- Given user 訪問 `/requests/r1`（r1 含 description, voteCount=5, 2 comments）
- When page render
- Then 顯 PageHeader (title + requester name + 相對時間) + vote button + description (markdown rendered) + comments list (ASC) + CommentForm textarea
- And `currentUser === requester` → 顯「🗑 刪除」button；否則不顯
- And own comment 顯「Delete」button；他人 comment 不顯
- And 點 vote button → voteCount + 1（optimistic update）

**AC-10: RequestBoardPage card title 跳 detail**

- Given board 顯 r1, r2, r3
- When click r1 title
- Then navigate to `/requests/r1`
- And browser back → 回 board

**AC-11: GET /{id} 不存在 → 404 + 前端友善**

- Given GET `/api/v1/requests/nonexistent`
- Then 404 + ErrorResponse `{code: "RESOURCE_NOT_FOUND", ...}`
- And frontend `/requests/nonexistent` 顯「找不到此 request」+「回看板」link

**AC-12: Request events 全寫入 domain_events（ES 永存）**

- Given Request 建立、vote toggle、comment 各觸發 1 次
- When `repo.save(request)` + `RequestVoteService.toggle` + `CommentService.addComment` 跑完（AFTER_COMMIT async listener 完成）
- Then `domain_events` 表多 3 row：
  - `event_type='RequestPosted'`, `aggregate_id=r1`, payload JSONB 含 requestId / title / requesterId
  - `event_type='RequestVoted'`,  `aggregate_id=r1`, payload 含 userId / voted / voteCount
  - `event_type='RequestCommented'`, `aggregate_id=r1`, payload 含 commentId / authorId / content
- And `(aggregate_id, sequence)` 嚴格遞增
- When 同 event 觸發 retry（Modulith 重投）
- Then deterministic UUID + `ON CONFLICT (id) DO NOTHING` → `domain_events` 仍只有 3 row（idempotent）
- When AC-7 hard delete request r1 完成
- Then `requests` / `request_comments` row 消失，但 `domain_events` 對應 3 row **仍存在**（ES 不可變）

### NFR coverage

| Category | Covered by | Or N/A reason |
|---|---|---|
| Performance | AC-4 | detail response 含 comments，MVP cap 50 條（implicit），response < 50 KB；超過再開分頁 spec |
| Security | AC-6 / AC-7 | owner-only / requester-only guard；S161 既有 PlainTextDeserializer 套用至 comment content |
| Reliability | AC-5 | event publish 與 comment insert 同 TX（Modulith outbox at-least-once）；soft delete 避免 cascade race |
| Usability | AC-9 / AC-11 | error UX 友善；comment 排序 earliest first 對齊 GitHub Issues |
| Maintainability | AC-1 / AC-2 / AC-8 | retire dead code（拆 4 個檔 + 3 events + 3 endpoints + 1 frontend component） |
| Audit / ES | AC-12 | Request events 全進 `domain_events`（對齊既有 Skill 8-event pattern；hard delete 後仍永存） |

**驗證指令：** `cd backend && ./gradlew test` + `cd frontend && npm test`

---

## 4. Interface / API Design

### REST API

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| POST | `/api/v1/requests` | `{title, description}` | 201 `{id}` | logged-in |
| GET | `/api/v1/requests?sort=votes\|created` | — | 200 `[{id,title,description,requesterId,voteCount,createdAt,updatedAt}]` | public |
| GET | `/api/v1/requests/{id}` | — | 200 `{...,comments,canDelete}` | public |
| DELETE | `/api/v1/requests/{id}` | — | 204 / 403 | requester |
| POST | `/api/v1/requests/{id}/vote` | — | 200 `{voted, voteCount}` | logged-in |
| POST | `/api/v1/requests/{id}/comments` | `{content}` | 201 `{id}` | logged-in |
| DELETE | `/api/v1/requests/{id}/comments/{cid}` | — | 204 / 403 | comment author |

### Java Interfaces (simplified)

```java
// community/RequestService.java
public class RequestService {
    public String createRequest(String title, String description, String requesterId);
    public List<Request> listRequests(String sort);  // status param 移除
    public Request getRequest(String requestId);
    public void deleteRequest(String requestId, String userId);  // requester-only
}

// community/CommentService.java (新)
public class CommentService {
    @Transactional public String addComment(String requestId, String authorId, String content);
    @Transactional public void deleteComment(String requestId, String commentId, String userId);
    public List<RequestComment> listByRequest(String requestId);  // soft-deleted 過濾
}

// community/RequestComment.java entity 設計詳 §2.4
```

### Frontend Types

```typescript
// frontend/src/api/skills.ts
export type SkillRequest = {
  id: string
  title: string
  description: string
  requesterId: string
  voteCount: number
  createdAt: string
  updatedAt: string
  // status / claimerId / fulfilledSkillId 全刪
}

export type RequestComment = {
  id: string
  authorId: string
  content: string
  createdAt: string
}

export type RequestDetail = SkillRequest & {
  comments: RequestComment[]
  canDelete: boolean
}
```

---

## 5. File Plan

### Backend production

| 檔案 | Action | 說明 |
|---|---|---|
| `db/migration/V22__request_voting_board_simplification.sql` | new | drop 3 cols + create request_comments |
| `community/Request.java` | modify | 刪 4 method + 3 field + 相關 import；加 `addComment` |
| `community/RequestComment.java` | new | entity |
| `community/RequestCommentRepository.java` | new | `ListCrudRepository<RequestComment, String>` + `findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc` |
| `community/events/RequestCommentedEvent.java` | new | record |
| `community/events/RequestClaimedEvent.java` | delete | — |
| `community/events/RequestReleasedEvent.java` | delete | — |
| `community/events/RequestFulfilledEvent.java` | delete | — |
| `community/RequestService.java` | modify | 刪 claim/release/fulfill；deleteRequest 簡化；listRequests status param 移除 |
| `community/RequestCommandController.java` | modify | 刪 3 endpoint + FulfillBody |
| `community/RequestQueryController.java` | modify | RequestResponse 移除 3 field；GET /{id} 改回 `RequestDetailResponse` (含 comments + canDelete) |
| `community/CommentService.java` | new | 3-line orchestration |
| `community/CommentController.java` | new | POST /comments + DELETE /comments/{cid} |
| `shared/api/NotRequestClaimerException.java` | delete | — |
| `notification/NotificationProjectionListener.java` | modify | 刪 onRequestClaimed/onRequestFulfilled；加 onRequestCommented |
| `notification/package-info.java` | modify | 更新文字 |
| `audit/AuditEventListener.java` | modify | 加 3 listener：`onRequestPosted` / `onRequestVoted` / `onRequestCommented`（deterministic UUID + ON CONFLICT idempotent；對齊 8 Skill listener pattern；詳 §2.8） |

### Backend test

| 檔案 | Action |
|---|---|
| `community/RequestServiceTest.java` | modify — 移除 claim/release/fulfill test |
| `community/CommentServiceTest.java` | new |
| `community/CommentControllerTest.java` | new |
| `community/RequestDetailQueryTest.java` | new — GET /{id} response shape |
| `community/V22MigrationTest.java` | new — Flyway clean migrate verify |
| `notification/NotificationProjectionListenerTest.java` | modify — 移除 claim/fulfill case；加 commented case |
| `shared/api/GlobalExceptionHandlerTest.java` | modify — 移除 NotRequestClaimerException test |
| `audit/AuditEventListenerTest.java` | modify — 加 3 Request listener case（含 idempotency retry + AC-12 hard-delete-then-events-persist scenario） |

### Frontend

| 檔案 | Action | 說明 |
|---|---|---|
| `pages/RequestDetailPage.tsx` | new | route `/requests/:id` |
| `pages/RequestDetailPage.test.tsx` | new | render + interactions + 404 |
| `components/CommentList.tsx` | new | |
| `components/CommentForm.tsx` | new | |
| `components/RequestActionBar.tsx` | delete | |
| `api/skills.ts` | modify | 刪 3 function；type 砍 3 field；加 hooks |
| `pages/RequestBoardPage.tsx` | modify | 刪 RequestActionBar；title 改 `<Link>` |
| `pages/RequestBoardPage.test.tsx` | modify | 移除 fixture 中 3 field |
| `App.tsx` | modify | 加 `/requests/:id` route |

---

## 6. 風險

| 風險 | 緩解 |
|---|---|
| migration 拆 column → 既有 DB row lost | user 已確認可清；V22 不做 backfill |
| `SkillNotPublishableException` 拆錯（CollectionService 也用） | grep 確認 — 已知 CollectionService 還在用，**保留 class**，只移除 RequestService 的 throw 點 |
| Frontend `?status=` 殘留呼叫 → S159a interceptor 拒收 400 | grep frontend `?status=` 全砍；確認 RequestBoardPage filter chip 不再傳 |
| Audit / Notification 對 Posted/Voted event 影響 | 都不動，只動 Claimed/Released/Fulfilled |
| Modulith verify 失敗（dangling import） | T01 拆完跑 `processTestAot` 驗 |
| `RequestVotedEvent` dedupKey 用 timestamp 有 collision 風險（同毫秒 toggle） | task POC 階段驗 — 若 collision 真發生 → event record 加 `eventId UUID` field（mirror SkillDownloadedEvent） |
| `domain_events` 寫入頻率變高（comment 量 + vote toggle 量） | DB index `idx_domain_events_aggregate` 既有；MVP 規模 OK；future 真大量再 partition |

---

## 6. Task Plan

**POC: not required** — 所有 design hypothesis 都已被既有 ship 過的 pattern 證實：

| 設計決策 | Validated by |
|---|---|
| `AuditEventListener` deterministic UUID + ON CONFLICT 寫 `domain_events` | S024（8 個 Skill listener 已 ship） |
| Spring Data JDBC `@PersistenceCreator` 充血聚合 + `@DomainEvents` outbox | S096g2（Request aggregate 既有） |
| ON DELETE CASCADE | PostgreSQL 標準功能；既有 `request_votes` 已用 `ON DELETE CASCADE` |
| `RequestVotedEvent.eventId UUID` dedupKey | mirror `SkillDownloadedEvent`（用戶可重複動作 pattern；S076 已 ship） |
| Spring Modulith `@ApplicationModuleListener` async + idempotent | S024 + S096g2 |

§6 風險中的「dedupKey timestamp collision」由工程決策解決：`RequestVotedEvent` 加 `eventId UUID` field（T03），非 hypothesis。

### Tasks

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Retire claim/release/fulfill machinery（backend + frontend cleanup + V22 drop part） | AC-1 / AC-2 / AC-3 / AC-7 (partial) / AC-8 | pending |
| T02 | Comment backend — entity + event + service + controller + notification listener + V22 CREATE part | AC-5 / AC-6 / AC-7 (cascade) | pending |
| T03 | AuditEventListener — 3 Request listener + `RequestVotedEvent` 加 eventId UUID | AC-12 | pending |
| T04 | GET /{id} detail enrich + RequestDetailPage frontend + route | AC-4 / AC-9 / AC-10 / AC-11 | pending |

**執行順序：** T01 → T02 → T03 → T04（嚴格序列；T02 依賴 T01 拆完 aggregate；T03 依賴 T02 的 RequestCommentedEvent；T04 依賴 T02 的 CommentService）

### E2E task — not required

| Seam 類型 | 涵蓋方式 |
|---|---|
| Backend 行為（aggregate / events / DB cascade / audit log） | Backend `./gradlew test`（Testcontainers PostgreSQL；real Modulith outbox） |
| Frontend component / page / 404 / link nav | Frontend `npm test`（vitest + msw mock API） |
| Cross-stack happy path | 不在 V07 critical path（V07 涵蓋核心 skill upload / download / browse；request detail 為 supporting feature） |

未來若 request feature 進入 V07 critical path，再開 spec 加 Playwright happy-path test。

---

## 7. Implementation Results

### 7.1 Verification（所有 commands 最終 green）

| Command | Result | Spec |
|---|---|---|
| `cd backend && ./gradlew test` | ✅ BUILD SUCCESSFUL (3m 34s, T04 final) | Full backend test suite — RequestServiceTest / CommentServiceTest / CommentControllerTest / V22MigrationTest / RequestDetailQueryTest / NotificationProjectionListenerTest / AuditEventListenerTest 全 PASS |
| `cd frontend && npm test` | ✅ 410/410 passed (14.73s, T04 final) | +7 新 case (RequestDetailPage 6 + RequestBoardPage AC-10 Link) |
| `cd backend && ./gradlew processTestAot` | ✅ BUILD SUCCESSFUL (1m 29s, T04 final) | Modulith 模組邊界驗證 — audit ↔ community::events / notification ↔ community::events 通過 |

### 7.2 E2E artifact verification — not required

per Phase 1.5 active evaluation：

| Seam 類型 | 涵蓋情況 |
|---|---|
| Spring Modulith outbox + AFTER_COMMIT async listener | `NotificationProjectionListenerTest @SpringBootTest` + `AuditEventListenerTest @ApplicationModuleTest(DIRECT_DEPENDENCIES)` 用 `Scenario` 跑真實 outbox redelivery 路徑 |
| Flyway V22 migration（drop 3 cols + CREATE TABLE + CASCADE FK） | `V22MigrationTest @SpringBootTest` 走 Testcontainers + 真 PostgreSQL 驗 schema columns / indexes / FK delete_rule |
| ON DELETE CASCADE 行為 | `CommentServiceTest.requestHardDelete_cascadesComments` Testcontainers 整合測試 |
| Event → domain_events ES 永存（hard delete 後 row 仍在） | `CommentServiceTest.requestHardDelete_domainEventsPersist` Testcontainers 跨 module 整合 |
| Frontend UI browser flow | Frontend vitest 涵蓋 component-layer；非 V07 critical-path（per spec §6 task plan）|

**Rationale**：所有 backend integration seam 已由 Testcontainers @SpringBootTest + ApplicationModuleTest Scenario API 涵蓋真實 framework 行為；frontend 由 vitest component test 涵蓋；無 cross-stack 場景需走 Playwright。未來 request feature 若進入 V07 critical-path，再開後續 spec 加 E2E。

### 7.3 Task Results 摘要

| Task | AC Coverage | Status | 關鍵交付 |
|---|---|---|---|
| T01 | AC-1 / 2 / 3 / 7(partial) / 8 | ✅ PASS | Cleanup — backend 拆 4 method + 3 events + 3 endpoints + 1 exception；frontend 刪 RequestActionBar；V22 drop part |
| T02 | AC-5 / 6 / 7(cascade) | ✅ PASS | Comment infrastructure — entity / repo / event / service / controller + NotificationProjectionListener.onRequestCommented |
| T03 | AC-12 | ✅ PASS | AuditEventListener 3 Request listener + `RequestVotedEvent` 加 `eventId UUID` |
| T04 | AC-4 / 9 / 10 / 11 | ✅ PASS | GET /{id} 改 `RequestDetailResponse`（含 comments + canDelete）+ RequestDetailPage + CommentList + CommentForm + route |

### 7.4 AC Results

| AC | 狀態 | 驗證 evidence |
|---|---|---|
| AC-1 | ✅ | `Request.java` 僅剩 `create` + `addComment` 兩 method；3 event 檔已刪；V22MigrationTest 驗 columns absent |
| AC-2 | ✅ | 3 endpoint 自 RequestCommandController 移除；route 不註冊 → Spring 自動 404 |
| AC-3 | ✅ | `RequestServiceTest.list_votesDesc` regression PASS；RequestVoteService 不動 |
| AC-4 | ✅ | `RequestDetailQueryTest` 5 case：alice canDelete=true / bob false / unauth false / soft-deleted filter / base fields |
| AC-5 | ✅ | `CommentServiceTest` 3 case + `CommentControllerTest` 3 case + `NotificationProjectionListenerTest` RequestCommented case |
| AC-6 | ✅ | owner-only soft delete + 非 author 403 + already-deleted 404 + nonexistent 404 |
| AC-7 | ✅ | requester-only delete (403) + CASCADE 刪 comments + RequestServiceTest delete_requesterOnly PASS |
| AC-8 | ✅ | NotificationProjectionListener 無 onRequestClaimed/onRequestFulfilled method（grep + 5 case 全 PASS）|
| AC-9 | ✅ | RequestDetailPage.test 4 case：render / canDelete true & false / own comment delete button |
| AC-10 | ✅ | RequestBoardPage.test 新 case「card title 是 `<Link href='/requests/r1'>`」PASS |
| AC-11 | ✅ | Backend `GET nonexistent → 404` + Frontend `/requests/nonexistent → 找不到此需求 + link` |
| AC-12 | ✅ | AuditEventListenerTest 4 case (Posted/Voted dedup-by-eventId/Commented/3-ordered) + CommentServiceTest hard-delete-then-events-persist |

### 7.5 Key Findings — 過程中踩到的坑 + 教訓

**1. Spring Data JDBC string Id entity 必須有 `@Version` 才能正確 INSERT**

T02 初版 `RequestComment` 缺 `@Version Long version` field → `commentRepo.save()` 走 UPDATE path（id 非 null）→ silent no-op → `findById` 找不到 row → 6 個 CommentServiceTest 失敗。修法：加 `@Version` + V22 migration `version BIGINT NOT NULL DEFAULT 0`。

**教訓**：codebase Collection.java doc 第 3 行明寫「**不**走 spec §4.3 範本的 Persistable + 自訂 isNew()：factory 設 createdAt=Instant.now() 會破 isNew flag（已是 codebase 第 4 次踩坑教訓）」— 該優先讀既有 entity pattern。

**2. `IllegalStateException` → 409，不是 403**

T01 對「非 requester 嘗試刪除 request」沿用既有 `IllegalStateException("not_request_requester")` — 但 GlobalExceptionHandler 第 440 行 mapping IllegalStateException → 409 CONFLICT。Spec AC-7 期望 403。T02 順手對齊：改拋 `org.springframework.security.access.AccessDeniedException` → handler line 608 → 403 ACCESS_DENIED。

**教訓**：寫 sad-path 測試前先 grep GlobalExceptionHandler 確認 mapping。

**3. `@WebMvcTest` slice 不掃 component scan**

T02 `CommentControllerTest` 一開始 ApplicationContext 載入失敗 — `No qualifying bean of type 'CurrentUserProvider' available`。修法：加 `@MockitoBean CurrentUserProvider users` + `@BeforeEach` 用 `CurrentUser.synthetic(...)` 設預設返回；MockMvc `.with(jwt())` 設 authenticated 讓 AccessDeniedException 走 403 而非 anonymous 401。

**教訓**：@WebMvcTest 走 slice，只載 controller。所有 collaborator 需 mock。

**4. JWT subject != platform user_id**

T04 `RequestDetailQueryTest` 一開始 `canDelete` 一律 false — `.with(jwt().jwt(j -> j.subject("alice")))` 注入後，`CurrentUserProvider.fromJwt` 走 `UserUpsertService.upsertFromOidc` 把 sub "alice" 轉成 `u_xxx`，但 `request.getRequesterId()` 是直接寫入字串 "alice" → mismatch。修法：改用 `SecurityMockMvcRequestPostProcessors.user("alice")`（UsernamePasswordAuthenticationToken）→ CurrentUserProvider 走 path (2) labLikeCurrentUser，userId=auth.getName()="alice"，跟 requesterId 對齊。

**教訓**：需要「lab 風格」test identity 用 `.with(user(...))`；需要 OAuth 路徑用 `.with(jwt())`。兩者導向 CurrentUserProvider 不同分支。

### 7.6 Correct Usage Patterns（給後續 spec 參考）

**Spring Data JDBC 充血 entity with @Version：**
```java
@Table("request_comments")
public class RequestComment {
    @Id private String id;
    // ... 業務 fields
    @Version
    @JsonIgnore
    private Long version;

    @PersistenceCreator
    private RequestComment() {}

    public static RequestComment create(...) {
        var c = new RequestComment();
        c.id = UUID.randomUUID().toString();
        // ... 設 business fields
        c.version = null; // INSERT path — @Version 為 INSERT/UPDATE 唯一區分器
        return c;
    }
}
```

**AuditEventListener 多 aggregate type pattern：**
```java
private static final String SKILL_AGGREGATE_TYPE = "Skill";
private static final String REQUEST_AGGREGATE_TYPE = "Request";

// 4-arg overload 給 Skill listener 用（預設）
private void recordAudit(String aggregateId, String eventType,
        Map<String, Object> payload, String dedupKey) {
    recordAudit(aggregateId, SKILL_AGGREGATE_TYPE, eventType, payload, dedupKey);
}

// 5-arg full path 給 Request listener 用
private void recordAudit(String aggregateId, String aggregateType, String eventType,
        Map<String, Object> payload, String dedupKey) {
    // ... pg_advisory_xact_lock + saveAuditIdempotent
}
```

**MockMvc test auth pattern：**
```java
// Lab-style identity（principal name = userId 直接對應）
.with(user("alice"))   // UsernamePasswordAuthenticationToken
                       // → CurrentUserProvider path (2) → userId="alice"

// OAuth-style identity（走 UserUpsertService → platform user_id）
.with(jwt().jwt(j -> j.subject("u_xxx")))  // JwtAuthenticationToken
                                            // → CurrentUserProvider path (1) → userId=upsert result
```

### 7.7 Doc Sync

- `docs/grimo/specs/spec-roadmap.md`：S156c status `⏳ Plan` → `✅` 由 `/shipping-release` 在 ship 時處理
- `docs/grimo/architecture.md`：無變動（沿用既有 Spring Modulith outbox + Spring Data JDBC pattern；無新增 framework / library）
- `docs/grimo/glossary.md`：無新增 domain term（RequestComment / RequestDetail 為 entity / DTO 內部命名）
- 無 ADR 需求：本 spec 屬於 S096g2 範圍內 simplification，未引入新架構決策

### 7.8 Pending Verification

無 — 所有 test 已實際 run；無 skipped IT。

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---|---|---|
| Tech risk | 2 | 2 | 設計沒 pivot；2 個 minor 踩坑（@Version field + AC-7 status code 漂移）皆當下解決 |
| Uncertainty | 1 | 1 | 既有 pattern 充足，無 hypothesis 待驗 |
| Dependencies | 1 | 1 | 不變 |
| Scope | 3 | 3 | 拆 4 events + 1 exception + 加 entity / service / controller / 2 frontend components / new page；數量符合 plan |
| Testing | 2 | 3 | +2 testing burden：(a) CommentControllerTest 撞 @WebMvcTest slice CurrentUserProvider bean missing；(b) RequestDetailQueryTest JWT vs user() identity 差異；(c) QA touchup 加 RetiredEndpointsTest + 集中 AC-3 anchor |
| Reversibility | 2 | 2 | V22 drop column 不可逆（user 確認 DB 可清，不影響 ship） |
| **Total** | **M(10)** | **M(12)** | 仍 M bucket；testing 維度 +1 反映實作期間真實 friction |

### 7.10 Post-QA touchup — 處理 QA agent 2 個 MINOR finding

2026-05-12 — QA subagent 找到 2 個 MINOR 後，user 要求做完再 ship。補 2 個測試 + 跑全測試綠（3m 19s）：

**Fix 1：AC-2 加 routing 測試**

新檔：`backend/src/test/java/io/github/samzhu/skillshub/community/RetiredEndpointsTest.java`

- 3 個 `MockMvc` 案例，分別對 `POST /api/v1/requests/{id}/claim` / `DELETE /api/v1/requests/{id}/claim` / `POST /api/v1/requests/{id}/fulfill` 發請求
- 斷言 `is4xxClientError()` — 涵蓋實際行為（Spring Boot static resource handler fallthrough 回 **405 Method Not Allowed**，而非 404）
- 守住「未來不小心把這 3 條 endpoint 加回去」會被擋下

**Implementation note**：Spring Boot 對「沒對應 @RestController 的路徑」走 fallthrough 至 `ResourceHttpRequestHandler`，POST/DELETE 一律回 405。對 user 而言 404 vs 405 等效（endpoint 都不可用），但 spec §3 AC-2 原文寫 404 — 此差異記錄於 §7.10 不視為 design drift。

**Fix 2：AC-3 在 RequestVoteServiceTest 加集中 regression case**

修改：`backend/src/test/java/io/github/samzhu/skillshub/community/RequestVoteServiceTest.java`

- 加 `@Tag("AC-3") voteToggle_fullCycle_postPivot` — 完整 on→off→on cycle + 換 user 累加，明確標為 S156c AC-3 regression anchor
- 更新 class-level Javadoc，註明這檔是 S156c AC-3 的單一集中驗證點
- 既有 AC-5/AC-6 標 (S096g2 ACs) 不動 — vote toggle 行為驗證本來就在此

**驗證：** `./gradlew test` 3m 19s 全綠（含新加的 3 個 RetiredEndpointsTest case + 1 個 AC-3 集中 case）

---

### 7.9 QA Review (independent subagent)

**Verdict:** PASS
**Date:** 2026-05-12
**Reviewer:** independent QA subagent

#### Findings

- [MINOR ✅ RESOLVED 2026-05-12] AC-2 加 `RetiredEndpointsTest`（3 case）斷言 3 個拆掉的 endpoint 對外回 4xx — 詳 §7.10。
- [MINOR ✅ RESOLVED 2026-05-12] AC-3 在 `RequestVoteServiceTest` 加 `@Tag("AC-3") voteToggle_fullCycle_postPivot` 集中 regression anchor + class-level Javadoc 註明 S156c AC-3 single anchor — 詳 §7.10。
- [MINOR] `package-info.java` for `notification` module mentions `RequestClaimedEvent` / `RequestFulfilledEvent` in the migration description comment (to document what was removed). This is intentional spec documentation, not a dangling production reference. No production class references these deleted events.
- [INFO] `AuditEventListener` Javadoc states "11 個訂閱事件" — verified: 8 Skill events (SkillCreated / SkillVersionPublished / SkillVersionPublishedFromAggregate / SkillDownloaded / SkillSuspended / SkillReactivated / SkillRiskAssessed / SkillDeleted) + 3 Request events (RequestPosted / RequestVoted / RequestCommented). Count and code match exactly.
- [INFO] `processTestAot` BUILD SUCCESSFUL (1m 30s) — Modulith module boundary verify passes. No dangling cross-module imports.
- [INFO] `compileTestJava` BUILD SUCCESSFUL (892ms) — clean compile, no warnings.
- [INFO] All 6 core S156c test files (AuditEventListenerTest / CommentControllerTest / CommentServiceTest / RequestDetailQueryTest / RequestServiceTest / NotificationProjectionListenerTest) show 0 failures / 0 errors in latest XML results.

#### AC Coverage Verified

| AC | Test file | Status |
|----|-----------|--------|
| AC-1 | `RequestServiceTest` (no state machine fields) + `V22MigrationTest` (columns absent) | ✅ |
| AC-2 | `RetiredEndpointsTest` 3 case (claim/release/fulfill 全 4xx) — §7.10 補 | ✅ |
| AC-3 | `RequestVoteServiceTest.voteToggle_fullCycle_postPivot` (S156c regression anchor) + `RequestServiceTest.list_votesDesc` (list sort) — §7.10 集中 | ✅ |
| AC-4 | `RequestDetailQueryTest` (6 cases: canDelete true/false/unauth, soft-deleted filter, base fields) | ✅ |
| AC-5 | `CommentServiceTest` + `CommentControllerTest` + `NotificationProjectionListenerTest` (self-comment skip) | ✅ |
| AC-6 | `CommentServiceTest` + `CommentControllerTest` (owner-only, 403, 404) | ✅ |
| AC-7 | `RequestServiceTest` (requester-only, 403) + `CommentServiceTest` (cascade) + `V22MigrationTest` (FK CASCADE) | ✅ |
| AC-8 | `NotificationProjectionListenerTest` (onRequestCommented present; no onRequestClaimed/onRequestFulfilled) | ✅ |
| AC-9 | `RequestDetailPage.test.tsx` (5 cases: render / canDelete true+false / own comment delete / submit) | ✅ |
| AC-10 | `RequestBoardPage.test.tsx` (card title is `<Link to="/requests/:id">`) | ✅ |
| AC-11 | `RequestDetailPage.test.tsx` (404 → 找不到此需求 + link) | ✅ |
| AC-12 | `AuditEventListenerTest` (4 cases: Posted/Voted dedup/Commented/3-ordered) + `CommentServiceTest` (hard-delete-then-events-persist) | ✅ |

#### Recommendation

Ship — all 12 ACs covered with explicit tests (含 §7.10 post-QA touchup 加的 AC-2 routing test + AC-3 集中 regression case)；3m 19s 全 backend test 綠。

---

## 8. 後續 follow-up

- Edit description（如有需求）
- Comment threading / @mention
- Comment markdown rendering（目前 plain text + S161 sanitization）
- Comment pagination（> 50 comments / request 時）
- Request 訂閱通知（subscribe to specific request）
- 若未來真要回頭做 claim/fulfill — 新 spec，從乾淨狀態加，不要 revert 本 spec
