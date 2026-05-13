# S156b: RequestDetailPage — `/requests/:id` 完整 detail + 7 個 actions + 留言

> Spec: S156b | Size: M(8) | Status: ⛔ superseded 2026-05-12 — 取代為 **S156c**
> Date: 2026-05-09
> Origin: 拆自 S156（v4.32.0 — List clickability + Analytics hero card）；S156 #1 + #3 ✅，本 spec 處理 #2「RequestDetailPage 新 page」
> Depends On: S156 ✅, S096g2 ✅ (Request Board full feature)
> Superseded By: S156c — voting-board pivot；本 spec 7-actions 設計（含 downvote / close / unclaim alias / fulfill UI）與 S096g2 既有 toggle vote + release endpoint 設計衝突，且 user 確認 claim/fulfill 機制要整批拆除。詳 `2026-05-12-S156c-request-voting-board.md`。

---

## 1. Goal

**一句話：** 把 RequestBoardPage 上的 request item 點進去 → 跳到獨立的 detail page，顯示完整描述、status、票數、所有 actions、留言串。

**為什麼重要：**
- 目前 `/requests` board 只顯示 title + 票數 — user 看不到完整 description / 既有 comments / 認領狀態
- 沒 detail page → user 無法 deep-link 分享 request（沒 stable URL per request）
- claim / fulfill / comment 等 action 都需要 context（看完整描述 + 既有討論）才能決定

**非目標：**
- 不做 threaded comment / @mention（Q1 確認走 simple list；threaded 留 future）
- 不做 comment edit（Q2 確認 — delete only）
- 不做 request analytics（票數趨勢圖等）

---

## 2. Approach

### 2.1 現況

`Request` aggregate 已存在（S096g2 v3.6.0 ship）含：
- Domain events 已有：`RequestCreatedEvent` / `RequestUpvotedEvent` / `RequestClaimedEvent` / `RequestFulfilledEvent`（per nativeCompile AOT log）
- 狀態機：OPEN → IN_PROGRESS (claim) → FULFILLED / CLOSED
- HTTP endpoints：POST `/requests` (create), POST `/requests/{id}/upvote`, POST `/requests/{id}/claim`, POST `/requests/{id}/fulfill`（need verify path naming）

`RequestBoardPage` 已 ship（S096g2）顯 list；但 item 點擊只 expand inline 或無 effect — 無獨立 detail page。

### 2.2 設計

**Backend 新增：**

1. **`request_comments` 表（V20 migration）**
   ```sql
   CREATE TABLE request_comments (
       id           VARCHAR(36) PRIMARY KEY,
       request_id   VARCHAR(36) NOT NULL REFERENCES requests(id),
       author_id    VARCHAR(20) NOT NULL,         -- platform user_id (依 S154)
       content      TEXT NOT NULL,
       created_at   TIMESTAMPTZ NOT NULL,
       deleted_at   TIMESTAMPTZ                   -- soft delete
   );
   CREATE INDEX idx_request_comments_request ON request_comments(request_id, created_at);
   ```

2. **`Request` aggregate 加 method（充血）**
   ```java
   public void addComment(AddCommentCommand cmd) { ... registerEvent(new RequestCommentedEvent(...)); }
   public void unclaim(...) { ... }      // 新增 — Q2 包含
   public void editDescription(...) { ... }  // 新增 — Q2 包含
   public void close(...) { ... }         // 新增 — Q2 包含
   public void downvote(...) { ... }      // 新增 — Q2 包含
   ```

3. **新 events**：`RequestCommentedEvent` / `RequestUnclaimedEvent` / `RequestEditedEvent` / `RequestClosedEvent` / `RequestDownvotedEvent`

4. **Endpoints**（補齊 7 actions + comments + detail）
   - `GET /api/v1/requests/{id}` — detail（含 comments inline）
   - `POST /api/v1/requests/{id}/comments` — add comment
   - `DELETE /api/v1/requests/{id}/comments/{commentId}` — delete (owner only — comment author)
   - `POST /api/v1/requests/{id}/unclaim` — unclaim (claimer only)
   - `POST /api/v1/requests/{id}/downvote` — downvote
   - `POST /api/v1/requests/{id}/close` — close (requester or admin)
   - `PUT /api/v1/requests/{id}` — edit description (requester only)

**Detail response shape：**
```json
{
  "id": "r_...",
  "title": "需要 k8s autoscaler skill",
  "description": "詳細描述...",
  "status": "OPEN | IN_PROGRESS | FULFILLED | CLOSED",
  "voteCount": 12,
  "createdAt": "...", "createdBy": "u_...", "createdByName": "Alice Chen",
  "claimedBy": "u_..." | null, "claimedByName": "..." | null,
  "fulfilledBySkillId": "..." | null, "fulfilledBySkillName": "..." | null,
  "comments": [
    { "id": "c_...", "authorId": "u_...", "authorName": "Bob",
      "content": "+1", "createdAt": "..." }
  ],
  "viewerActions": {              // backend 算 per-request, action button enable/disable
    "canUpvote": true,            // 任何登入 user (還沒投過)
    "canDownvote": true,
    "canClaim": true,             // status=OPEN 且非 own request
    "canUnclaim": false,          // 是 claimer 才 true
    "canFulfill": false,          // 是 claimer 才 true
    "canComment": true,           // 任何登入 user
    "canEditDescription": false,  // requester only
    "canClose": false             // requester or admin
  }
}
```

**`viewerActions` 對齊 S158b `viewerPermissions` 同一 pattern**（backend 算，避免 frontend 重複實作 state machine）。

### 2.3 Frontend

**新 page** `frontend/src/pages/RequestDetailPage.tsx` route `/requests/:id`：

```
┌── PageHeader ────────────────────────┐
│ ← 返回看板                              │
│ # r_a3f9c1                             │
│ 標題：需要 k8s autoscaler skill           │
│ status badge: OPEN / IN_PROGRESS / ... │
│ requester: Alice Chen · 2 days ago     │
│ vote: ▲ 12 / ▼ 0                       │
└────────────────────────────────────────┘

┌── Description ────────────────────────┐
│ Markdown rendered description          │
│ [Edit] (僅 requester 看得到)            │
└────────────────────────────────────────┘

┌── Actions (Sticky) ───────────────────┐
│ [Upvote] [Downvote] [Claim] [Comment]  │
│ [Unclaim] [Fulfill] [Close]            │
│ (依 viewerActions enable/disable)      │
└────────────────────────────────────────┘

┌── Comments (latest first or earliest first?) ──┐
│ Bob Chen · 1d ago                       │
│   +1 我也需要                            │
│   [Delete]（僅 own comment 看得到）       │
│                                        │
│ Charlie Liu · 12h ago                   │
│   有人在做？                            │
│                                        │
│ ...                                    │
│                                        │
│ [回覆框 textarea + 送出 button]         │
└────────────────────────────────────────┘
```

**RequestBoardPage 補 link：** 既有 `RequestCard` title 包 `<Link to={/requests/${r.id}}>`。

### 2.4 Comment 排序：earliest first

對齊 GitHub Issues、StackOverflow comments 風格 — 較舊在上，新 comment append 在下。infinite scroll 不需（MVP 假設 ≤ 50 comments per request）。

### 2.5 Notification 整合

`RequestCommentedEvent` 由 `NotificationProjectionListener` 訂閱（既有 listener 加 method）：
- 通知 request 的 requester（除非 commenter 是 self）
- 通知 request 的 claimer（除非 commenter 是 self）

對齊既有 `onRequestClaimed` / `onRequestFulfilled` 模式（per AOT log）。

---

## 3. Acceptance Criteria

```
AC-1: GET /api/v1/requests/{id} 回 detail 含 comments + viewerActions
  Given request r1 (status=OPEN, voteCount=5, comments=[c1, c2])
  When GET /api/v1/requests/r1 as logged-in user
  Then 200 + JSON 含完整 detail（per §2.2 shape）
  And comments[] 含 c1, c2 sorted by createdAt ASC（earliest first）
  And viewerActions 對齊 user 角色 + request status

AC-2: 7 個 actions endpoint 都 work
  各 endpoint 走 happy path 一次：
  - POST /upvote → voteCount++
  - POST /downvote → voteCount-- (could go negative)
  - POST /claim (status=OPEN) → status=IN_PROGRESS, claimedBy=self
  - POST /unclaim (status=IN_PROGRESS, claimer=self) → status=OPEN, claimedBy=null
  - POST /fulfill {skillId} (status=IN_PROGRESS, claimer=self) → status=FULFILLED
  - PUT description (requester only) → description updated, RequestEditedEvent fired
  - POST /close (requester or admin) → status=CLOSED

AC-3: POST comment 觸發 RequestCommentedEvent
  Given request r1, Bob comment "+1"
  When POST /api/v1/requests/r1/comments body={ content: "+1" }
  Then 201 + comment row inserted
  And RequestCommentedEvent published to outbox
  And NotificationProjectionListener 寫 notification 給 requester (Alice)

AC-4: DELETE comment owner-only
  Given Bob's comment c1, Charlie's comment c2
  When DELETE /api/v1/requests/r1/comments/c1 as Bob → 204 + soft delete
  When DELETE /api/v1/requests/r1/comments/c1 as Charlie → 403 PERMISSION_DENIED
  When DELETE /api/v1/requests/r1/comments/c2 as Bob → 403

AC-5: Action 拒收場景（state machine guard）
  Given request status=OPEN（無 claimer）
  When POST /unclaim as anyone → 400 INVALID_STATE
  When POST /fulfill as anyone → 400 INVALID_STATE
  Given request status=IN_PROGRESS, claimer=Alice
  When POST /unclaim as Bob → 403 (not claimer)
  When POST /fulfill as Bob → 403

AC-6: Edit description requester-only
  Given request created by Alice
  When PUT /api/v1/requests/r1 body={ description: "..." } as Alice → 200
  When PUT /api/v1/requests/r1 body={ description: "..." } as Bob → 403

AC-7: Close requester or admin
  Given request created by Alice
  When POST /api/v1/requests/r1/close as Alice → 200
  When POST /api/v1/requests/r1/close as admin → 200
  When POST /api/v1/requests/r1/close as Bob (rando) → 403

AC-8: Frontend RequestDetailPage 渲染所有 sections
  Given user 訪問 /requests/r1
  When page render
  Then 顯 PageHeader + Description (markdown) + Actions bar + Comments list + Add comment textarea
  And action button enable/disable 對齊 viewerActions
  And 留言 ASC 排序

AC-9: RequestBoardPage card click → detail page
  Given /requests board 顯 r1, r2, r3
  When user click r1 title
  Then navigate to /requests/r1
  And browser back 回 board

AC-10: 404 deep-link 友善
  Given GET /api/v1/requests/nonexistent
  Then 404 + ErrorResponse RESOURCE_NOT_FOUND
  And frontend RequestDetailPage 顯「找不到此 request」+ 回 board link
```

**驗證指令：** `cd backend && ./gradlew test` + `cd frontend && npm test`

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V20__request_comments.sql` | **新增** — request_comments table + index |
| `backend/src/main/java/.../request/domain/Request.java` | 加 `addComment / unclaim / editDescription / close / downvote` 充血 method |
| `backend/src/main/java/.../request/domain/RequestComment.java` | **新增** — entity (id, requestId, authorId, content, createdAt, deletedAt) |
| `backend/src/main/java/.../request/domain/RequestCommentedEvent.java` | **新增** |
| `backend/src/main/java/.../request/domain/RequestUnclaimedEvent.java` | **新增** |
| `backend/src/main/java/.../request/domain/RequestEditedEvent.java` | **新增** |
| `backend/src/main/java/.../request/domain/RequestClosedEvent.java` | **新增** |
| `backend/src/main/java/.../request/domain/RequestDownvotedEvent.java` | **新增** |
| `backend/src/main/java/.../request/domain/RequestCommentRepository.java` | **新增** — `ListCrudRepository<RequestComment, String>` + `findByRequestIdOrderByCreatedAt` |
| `backend/src/main/java/.../request/command/RequestCommandService.java` | 加 7 個 method（addComment + unclaim + editDescription + close + downvote 等）|
| `backend/src/main/java/.../request/command/RequestCommandController.java` | 加對應 endpoint（7 actions + 1 comment add/delete）|
| `backend/src/main/java/.../request/query/RequestQueryService.java` | `findById(id, currentUser)` 算 viewerActions |
| `backend/src/main/java/.../request/query/RequestQueryController.java` | `GET /requests/{id}` 端點 |
| `backend/src/main/java/.../notification/NotificationProjectionListener.java` | 加 `onRequestCommented` + 其他新 events |
| `backend/src/main/java/.../audit/AuditEventListener.java` | 加新 events 對應 audit handler（5 個）|

### Backend test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../request/RequestActionsTest.java` | 7 actions 各 happy + sad path |
| `backend/src/test/java/.../request/RequestCommentTest.java` | add/delete + permission |
| `backend/src/test/java/.../request/V20MigrationTest.java` | Flyway clean migrate verify |
| `backend/src/test/java/.../request/RequestDetailQueryTest.java` | `findById` 含 comments + viewerActions |

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/pages/RequestDetailPage.tsx` | **新增** |
| `frontend/src/components/v2/RequestActionsBar.tsx` | **新增** — 7 button 依 viewerActions enable |
| `frontend/src/components/v2/CommentList.tsx` | **新增** — list + delete button |
| `frontend/src/components/v2/CommentForm.tsx` | **新增** — textarea + 送出 |
| `frontend/src/api/requests.ts` | 加 `useRequest(id)` hook + 7 mutation hooks + comment hooks |
| `frontend/src/types/request.ts` | 加 viewerActions / RequestComment 等 type |
| `frontend/src/App.tsx` | 加 route `/requests/:id` |
| `frontend/src/pages/RequestBoardPage.tsx` | RequestCard title 加 `<Link>` |
| `frontend/src/pages/RequestDetailPage.test.tsx` | render + action interaction |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1 | `RequestDetailQueryTest` 跑 detail JSON shape |
| AC-2 | `RequestActionsTest` 7 actions 各 happy + sad |
| AC-3 | `RequestCommentTest` + `NotificationProjectionListenerTest` chain |
| AC-4 | `RequestCommentTest` 3 case |
| AC-5 | `RequestActionsTest` state machine guard |
| AC-6, 7 | `RequestActionsTest` permission sad case |
| AC-8 | `RequestDetailPage.test.tsx` |
| AC-9 | `RequestBoardPage.test.tsx` link click |
| AC-10 | `RequestDetailPage.test.tsx` 404 case |

### 5.2 手動 LAB 驗證

- [ ] `/requests` 點 title → 跳 detail
- [ ] 各 action button 操作（依角色 enable/disable 不同）
- [ ] 留言 + 自己刪
- [ ] 別人留言點 delete → 看不到 button（或 403）
- [ ] 訪問 /requests/nonexistent → 404 友善

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| `Request` aggregate 變太胖（10+ method）| 對齊既有 `Skill` aggregate pattern；invariant 集中 |
| 7 個新 endpoint 撞名 / 衝突 | 用 `/requests/{id}/<verb>` POST 統一 RPC-style，對齊既有 claim/fulfill |
| comments 量大時 detail response 過大 | MVP 假設 ≤ 50 comments；future 如超過開 separate `/comments?cursor=` endpoint |
| viewerActions 算錯 → button enable 但 API 回 403 | backend service 內統一邏輯；frontend 只讀，不重複算 |
| 跟 S154 user_id 對齊 | `author_id VARCHAR(20)` 對齊 platform user_id；S154 ship 後 backfill 即生效 |

---

## 7. 後續 follow-up

- comment threading / @mention（如有需求）
- comment edit（5 min window）
- request 訂閱通知（subscribe to specific request）
- comment markdown render（目前純文字）
