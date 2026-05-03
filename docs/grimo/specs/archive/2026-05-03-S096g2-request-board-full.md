# S096g2: Request Board Full Feature (aggregate + voting + claim + fulfillment)

> Spec: S096g2 | Size: S(11) re-est from M(10-12) | Status: 🚧 in-progress (4 tasks queued — cron tick handoff)
> Date: 2026-05-03

> **Tasks**: T01 backend aggregate + service + claim/fulfill/delete endpoints + V10 schema → T02 vote toggle service + endpoint + race tests → T03 frontend infra (api/skills.ts request mutations + useRequests/useRequest hooks) → T04 RequestBoardPage CTA + CreateRequestModal + VoteButton + RequestActionBar + tests。Execution order T01→T02→T03→T04（T03 type-only 可平行 T01/T02）。

---

## 1. Goal

讓使用者公開發起「我需要這種 skill」的需求；社群投票推升優先級；作者可認領、釋放、或上架對應 skill 後 mark fulfilled。把 ✅ S096g1 的 stub backend (`GET /requests` 回 `[]`) + EmptyState UI 升級為完整功能。

**起源**：S096g1 ship 時明確 defer aggregate + voting + claim + 3 domain events 至本 spec；前端 `RequestBoardPage` 已有 disabled「發起新需求」CTA + EmptyState + StatusPill 渲染（OPEN / IN_PROGRESS / FULFILLED 三色），UI shell 完整等填空。對齊 PRD §P8 SBE Scenarios（發起 / 投票 / 認領 / 完成自動 link）。

**Visual flow**：

```
發起需求            User → POST /requests {title, description}
                    → Request.create() (status=OPEN, vote_count=0)
                    → registerEvent(RequestPostedEvent)

投票 (toggle)        User → POST /requests/{id}/vote
                    → request_votes UNIQUE(request_id, user_id) → INSERT 或 DELETE
                    → vote_count atomic +1 / -1 (raw SQL)
                    → registerEvent(RequestVotedEvent {voted: true|false})

認領 (claim)         Author → POST /requests/{id}/claim
                    → status OPEN → IN_PROGRESS, claimer_id=author
                    → registerEvent(RequestClaimedEvent)
                    → 第二人 POST → 409 already_claimed

放棄認領             Claimer → DELETE /requests/{id}/claim
                    → status IN_PROGRESS → OPEN, claimer_id=null
                    → registerEvent(RequestReleasedEvent)

完成 (fulfill)       Claimer → POST /requests/{id}/fulfill {skillId}
                    → 驗 claimer 身份 + skillId 存在且 PUBLISHED
                    → status IN_PROGRESS → FULFILLED, fulfilled_skill_id=skillId
                    → registerEvent(RequestFulfilledEvent)
```

## 2. Approach

走 **ADR-002 canonical pattern**（Spring Data JDBC 充血 + Modulith Outbox）+ vote 走獨立 join table（`request_votes`）防止 race + UNIQUE constraint enforce 「1 user 1 vote per request」。

### 2.1 7 個產品/UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Vote model | **Toggle 1 票**（POST 一次加，再 POST 一次取消） | Reddit upvote pattern；`request_votes` join table UNIQUE(request_id, user_id) 防 spam；vote_count 由 atomic UPDATE 維護 |
| 2 | Claim model | **1 claimer at a time**（exclusive） | 對齊 PRD「作者認領後實作」單一作者；status guard + claimer_id 唯一 enforce |
| 3 | Status transitions | OPEN → IN_PROGRESS → FULFILLED 線性 + IN_PROGRESS → OPEN（claimer 放棄） | MVP 簡潔；CLOSED 取消路徑 defer 為 polish |
| 4 | Fulfillment 與 Skill 連結 | **強制綁 skillId**（必須 PUBLISHED） | PRD「作者上傳對應 skill 後，需求狀態變 FULFILLED + 系統自動 link」明示要交付實體 |
| 5 | Owner 編輯權限 | **MVP 只允許 delete 自己的 OPEN request**；edit defer | YAGNI；title typo 改可以重發 |
| 6 | 重複 POST | **vote toggle**；**claim 拒絕 409**（已 claimed 後第二人 POST） | vote 是 idempotent toggle；claim 是 exclusive lock |
| 7 | Permission | **任何登入用戶可 vote / claim**；**只有 claimer 可 fulfill / release**；**只有 requester 可 delete** | per Feature First；admin override defer |

### 2.2 Approach 比較 — Vote count 維護

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. `request_votes` join table + raw SQL atomic UPDATE `vote_count = vote_count ± 1`** | DB 層強一致；UNIQUE constraint enforce 1-vote-per-user；count 即時準確 | 需新表 + atomic guard against race | ⭐ |
| B. 純 read-side aggregation `SELECT COUNT(*) FROM request_votes WHERE request_id=?` | 無 race；無冗餘欄位 | List page 對 N requests 跑 N 個 COUNT；效能差 | |
| C. 把 vote 編進 requests.vote_users JSONB array（無 join table） | 1 表 | UNIQUE 要靠 application layer；race 風險；無 indexed lookup「哪些 request 我 vote 過」 | |

走 **A** — count + table 並存，pattern 對齊 既有 `download_count` projection（S076 ship）。

### 2.3 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| ADR-002 canonical pattern | Validated | S024 / S098e2 ship 同 pattern |
| Vote count atomic UPDATE pattern | Validated | S076 download-counter-atomic-increment 已 ship 同 pattern |
| `@MappedCollection` for one-to-many votes | Validated | Skill aggregate 已用同 pattern（skill_versions） |
| `@DomainEvents` outbox 自動 publish | Validated | S023 / S024 引用 |
| `community` module 已 wire（S096f2 補 `@ApplicationModule`） | Hypothesis (依賴 S096f2 ship 順序) | 若 S096f2 還沒 ship 時本 spec implementer 自補；若已 ship 則直接享用 |
| FrontendType `SkillRequest` 既有 | Validated | `frontend/src/api/skills.ts:85-92` 既有定義 |

零 Unknown；唯一 Hypothesis 與 ordering 有關不阻塞。**不需 POC**。

### 2.4 Trim list

S(11) 一個 cron tick 內可能 wall hit；可 defer：

- **Sort by created vs votes UI**（MVP 預設 votes desc 即可）
- **「我 vote 過哪些 request」** view（user-scoped vote history）— defer 為後續 user dashboard polish
- **CLOSED 取消狀態**（PRD 未提；發起人若覺得需求過時可考慮加）— defer
- **Skill picker UI 漂亮版**（fulfill 時選 skill）— MVP 走最簡 dropdown 列出 user 自己的 PUBLISHED skills

### 2.5 Research Citations

無外部框架研究 — 全部使用既有專案內 pattern。Internal references：

- `docs/grimo/specs/archive/2026-05-02-S096g1-request-board-stub.md`（前置 stub spec）
- `docs/grimo/PRD.md` §P8 lines 254-279（SBE Scenarios）
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（aggregate pattern）
- `backend/.../skill/domain/Skill.java`（@MappedCollection skill_versions 範本）
- `backend/.../community/RequestController.java`（既有 stub + `RequestSummary` record）
- `backend/.../community/CollectionController.java`（同 module sibling，S096f2 同期擴充）
- `frontend/src/pages/RequestBoardPage.tsx`（既有 page shell + RequestRow + StatusPill 可 reuse）
- `frontend/src/api/skills.ts:85-92`（`SkillRequest` type 已定義）
- S076 download-counter-atomic-increment（vote count atomic UPDATE 範本）

## 3. SBE Acceptance Criteria

驗證指令：

- Backend：`./gradlew test` + `./gradlew modulithTest`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠

---

**AC-1：建立 request — happy path**
- Given：alice 已登入
- When：發 `POST /api/v1/requests` body `{"title":"k8s autoscaler","description":"需要..."}`
- Then：回 201 + body `{"id":"<uuid>"}`；DB `requests` 新增 (status=OPEN, vote_count=0, requester_id=alice, claimer_id=null)；outbox 寫 `RequestPostedEvent`

**AC-2：title 長度上限**
- Given：alice 登入
- When：POST body `title` 為 201 字元
- Then：回 400 + `error: "title_too_long"` (cap 200)

**AC-3：列表 endpoint — votes desc 預設排序**
- Given：3 requests vote_count = (5, 12, 3)
- When：發 `GET /api/v1/requests`
- Then：回 200 + 順序 [12, 5, 3]
- 同時支援 `?sort=created` → createdAt desc

**AC-4：列表 endpoint — status filter**
- Given：4 requests (2 OPEN, 1 IN_PROGRESS, 1 FULFILLED)
- When：`GET /api/v1/requests?status=OPEN`
- Then：回 2 筆 OPEN

**AC-5：Vote toggle on**
- Given：alice 登入；request `r1` vote_count=5；alice 從未 vote
- When：發 `POST /api/v1/requests/r1/vote`
- Then：回 200 + body `{"voted": true, "voteCount": 6}`；DB `request_votes` 新增 (r1, alice)；`requests.vote_count` 變 6；outbox 寫 `RequestVotedEvent {voted: true}`

**AC-6：Vote toggle off — 重複 POST**
- Given：alice 已 vote 過 r1（count=6）
- When：alice 再發 `POST /api/v1/requests/r1/vote`
- Then：回 200 + body `{"voted": false, "voteCount": 5}`；DB `request_votes` 該 row 消失；`requests.vote_count` 變 5

**AC-7：Claim happy path**
- Given：bob 登入；r1 status=OPEN claimer_id=null
- When：bob 發 `POST /api/v1/requests/r1/claim`
- Then：回 200 + body `{"claimer": "bob", "status": "IN_PROGRESS"}`；DB 更新；outbox 寫 `RequestClaimedEvent`

**AC-8：Claim — 已 claimed 409**
- Given：r1 已被 bob claim
- When：carol 發 POST claim
- Then：回 409 + `error: "request_already_claimed"`

**AC-9：Release claim**
- Given：bob 已 claim r1
- When：bob 發 `DELETE /api/v1/requests/r1/claim`
- Then：回 204；DB status=OPEN claimer_id=null；outbox 寫 `RequestReleasedEvent`
- 非 claimer (carol) 發 DELETE → 403 `not_request_claimer`

**AC-10：Fulfill happy path**
- Given：bob claim 了 r1；bob 自己 publish 了 skill `sk1` (PUBLISHED)
- When：bob 發 `POST /api/v1/requests/r1/fulfill` body `{"skillId":"sk1"}`
- Then：回 200 + body `{"status": "FULFILLED", "fulfilledSkillId": "sk1"}`；DB updated；outbox 寫 `RequestFulfilledEvent`

**AC-11：Fulfill — 非 claimer 403**
- Given：bob claim 了 r1
- When：alice 發 fulfill
- Then：回 403 + `error: "not_request_claimer"`

**AC-12：Fulfill — 非 PUBLISHED skill 拒絕**
- Given：bob claim 了 r1；sk2 是 DRAFT
- When：bob fulfill body `{"skillId":"sk2"}`
- Then：回 400 + `error: "skill_not_publishable"`

**AC-13：Delete own OPEN request**
- Given：alice 發起 r1（OPEN）
- When：alice 發 `DELETE /api/v1/requests/r1`
- Then：回 204；DB row 消失（CASCADE 連動 `request_votes`）
- 非 requester (bob) 發 DELETE → 403
- request 已 IN_PROGRESS / FULFILLED → 409 `cannot_delete_active_request`

**AC-14：Modulith 邊界驗證**
- Given：本 spec 完成後 `community` 模組含 Collection + Request 兩 aggregate
- When：跑 `./gradlew modulithTest`
- Then：所有 ModularityTests PASS（無循環依賴；community allowedDependencies 與 S096f2 一致）

**AC-15：Frontend RequestBoardPage — CTA 啟用 + 真資料**
- Given：DB 內有 3 筆 requests
- When：user 開啟 `/requests`
- Then：「發起新需求」按鈕 **不再 disabled**；row list 渲染 3 筆，按 votes desc

**AC-16：Frontend Create modal happy path**
- Given：alice 登入
- When：alice 點「發起新需求」→ modal 開 → 填 title + description → Submit
- Then：modal 關閉；列表新增 row；toast「需求已發布」

**AC-17：Frontend Vote button toggle**
- Given：r1 顯示 vote_count=5
- When：alice 點 ↑ vote 按鈕
- Then：發 POST vote；count 樂觀更新為 6；按鈕變「已投票」style；再點 → 5、按鈕復原

## 4. Interface / API Design

### 4.1 Backend — REST endpoints

```
POST   /api/v1/requests                      # 建立
   body { title: string ≤200, description: string ≤2000 }
   201 { id: string }
   400 title_too_long / description_too_long
   401 unauthenticated（依 LAB mode 配合）

GET    /api/v1/requests                      # 列表
   query ?status=OPEN|IN_PROGRESS|FULFILLED (optional)
   query ?sort=votes|created (default: votes)
   200 [{ id, title, description, requesterId, claimerId, fulfilledSkillId, status, voteCount, createdAt }, ...]

GET    /api/v1/requests/{id}                 # 單筆
   200 { ... + votedByMe: boolean (current user 是否 vote 過) }
   404 request_not_found

POST   /api/v1/requests/{id}/vote            # toggle
   200 { voted: boolean, voteCount: number }
   404 request_not_found

POST   /api/v1/requests/{id}/claim           # 認領
   200 { claimer: string, status: "IN_PROGRESS" }
   404 request_not_found
   409 request_already_claimed

DELETE /api/v1/requests/{id}/claim           # 釋放
   204
   403 not_request_claimer
   404 request_not_found

POST   /api/v1/requests/{id}/fulfill         # 完成
   body { skillId: string }
   200 { status: "FULFILLED", fulfilledSkillId: string }
   400 skill_not_publishable
   403 not_request_claimer
   404 request_not_found / skill_not_found
   409 cannot_fulfill_non_in_progress

DELETE /api/v1/requests/{id}                 # 刪除
   204
   403 not_request_owner
   409 cannot_delete_active_request
   404 request_not_found
```

### 4.2 Backend — Schema migration

```sql
-- V<next>__create_request_tables.sql
CREATE TABLE requests (
    id                  VARCHAR(36) PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    description         TEXT NOT NULL,
    requester_id        VARCHAR(255) NOT NULL,
    claimer_id          VARCHAR(255),
    fulfilled_skill_id  VARCHAR(36),
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'FULFILLED')),
    vote_count          INTEGER NOT NULL DEFAULT 0 CHECK (vote_count >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_requests_status  ON requests (status);
CREATE INDEX idx_requests_votes   ON requests (vote_count DESC);
CREATE INDEX idx_requests_created ON requests (created_at DESC);

CREATE TABLE request_votes (
    request_id  VARCHAR(36) NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    user_id     VARCHAR(255) NOT NULL,
    voted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (request_id, user_id)
);
CREATE INDEX idx_request_votes_user ON request_votes (user_id);
```

### 4.3 Backend — Aggregate (Spring Data JDBC + @MappedCollection)

```java
@Table("requests")
public class Request extends AbstractAggregateRoot<Request> implements Persistable<String> {
    @Id String id;
    String title;
    String description;
    @Column("requester_id") String requesterId;
    @Column("claimer_id") String claimerId;
    @Column("fulfilled_skill_id") String fulfilledSkillId;
    String status;
    @Column("vote_count") int voteCount;
    @Column("created_at") Instant createdAt;
    @Column("updated_at") Instant updatedAt;

    public static Request create(String title, String description, String requesterId) { ... }
    public void claim(String userId) { /* OPEN check + status flip + event */ }
    public void release(String userId) { /* claimer check + back to OPEN + event */ }
    public void fulfill(String userId, String skillId) { /* claimer + skill check + status flip + event */ }
    public boolean canBeDeletedBy(String userId) { return userId.equals(requesterId) && "OPEN".equals(status); }
}
```

`request_votes` 用 raw SQL atomic UPSERT/DELETE 維護（不走 aggregate 充血 — 對齊 S076 download_count pattern）：

```java
// RequestVoteService.toggle(requestId, userId)
@Transactional
public ToggleResult toggle(String requestId, String userId) {
    var deleted = jdbc.update("DELETE FROM request_votes WHERE request_id=:r AND user_id=:u",
        Map.of("r", requestId, "u", userId));
    if (deleted > 0) {
        jdbc.update("UPDATE requests SET vote_count = GREATEST(vote_count - 1, 0) WHERE id=:r",
            Map.of("r", requestId));
        events.publishEvent(new RequestVotedEvent(requestId, userId, false));
        return new ToggleResult(false, /* new count */);
    } else {
        jdbc.update("INSERT INTO request_votes (request_id, user_id) VALUES (:r, :u)",
            Map.of("r", requestId, "u", userId));
        jdbc.update("UPDATE requests SET vote_count = vote_count + 1 WHERE id=:r",
            Map.of("r", requestId));
        events.publishEvent(new RequestVotedEvent(requestId, userId, true));
        return new ToggleResult(true, /* new count */);
    }
}
```

### 4.4 Backend — Domain events

```java
public record RequestPostedEvent(String requestId, String title, String requesterId) {}
public record RequestVotedEvent(String requestId, String userId, boolean voted) {}
public record RequestClaimedEvent(String requestId, String claimerId) {}
public record RequestReleasedEvent(String requestId, String previousClaimerId) {}
public record RequestFulfilledEvent(String requestId, String claimerId, String fulfilledSkillId) {}
```

放置 `community/events/`。MVP 無 listener 訂閱（給 future S096h2 Notifications projection 預留 hook — 「我 claim 的需求被 fulfill」「我 vote 的需求進度」等通知）。

### 4.5 Backend — Service shape

```java
@Service
public class RequestService {
    private final RequestRepository repo;
    private final SkillRepository skillRepo;       // skill::domain
    private final CurrentUserProvider users;       // shared::security
    private final RequestVoteService voteService;

    @Transactional
    public String create(String title, String description) {
        var req = Request.create(title, description, users.current().userId());
        repo.save(req);
        return req.getId();
    }

    @Transactional
    public ClaimResult claim(String requestId) {
        var req = repo.findById(requestId).orElseThrow(...);
        req.claim(users.current().userId());  // throws if already claimed
        repo.save(req);
        return new ClaimResult(req.getClaimerId(), req.getStatus());
    }

    @Transactional
    public FulfillResult fulfill(String requestId, String skillId) {
        // verify skill exists + PUBLISHED
        var skill = skillRepo.findById(skillId).orElseThrow(SkillNotFoundException::new);
        if (!"PUBLISHED".equals(skill.getStatus())) throw new SkillNotPublishableException();
        var req = repo.findById(requestId).orElseThrow(...);
        req.fulfill(users.current().userId(), skillId);
        repo.save(req);
        return new FulfillResult(req.getStatus(), skillId);
    }
    // ... list / get / release / delete
}
```

### 4.6 Frontend — API additions in `frontend/src/api/skills.ts`

```typescript
// 既有 SkillRequest / fetchRequests 保留

export interface CreateRequestRequest {
  title: string
  description: string
}

export function createRequest(body: CreateRequestRequest): Promise<{ id: string }> {
  return apiFetch('/requests', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export interface RequestDetail extends SkillRequest {
  requesterId: string
  claimerId: string | null
  fulfilledSkillId: string | null
  votedByMe: boolean
}

export function fetchRequest(id: string): Promise<RequestDetail> {
  return apiFetch(`/requests/${id}`)
}

export function toggleVote(id: string): Promise<{ voted: boolean; voteCount: number }> {
  return apiFetch(`/requests/${id}/vote`, { method: 'POST' })
}

export function claimRequest(id: string): Promise<{ claimer: string; status: string }> {
  return apiFetch(`/requests/${id}/claim`, { method: 'POST' })
}

export function releaseClaim(id: string): Promise<void> {
  return apiFetch(`/requests/${id}/claim`, { method: 'DELETE' })
}

export function fulfillRequest(id: string, skillId: string): Promise<{ status: string; fulfilledSkillId: string }> {
  return apiFetch(`/requests/${id}/fulfill`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ skillId }),
  })
}

export function deleteRequest(id: string): Promise<void> {
  return apiFetch(`/requests/${id}`, { method: 'DELETE' })
}
```

### 4.7 Frontend — Hooks + UI

- 新檔 `frontend/src/hooks/useRequests.ts`（list with status filter + sort）
- 新檔 `frontend/src/hooks/useRequest.ts`（single detail）
- 修改 `frontend/src/pages/RequestBoardPage.tsx`：
  - CTA enable + open `<CreateRequestModal>`
  - `RequestRow` 加 vote button + claim/fulfill/release 對應 status 的按鈕
- 新檔 `frontend/src/components/CreateRequestModal.tsx`（title + description + submit）
- 新檔 `frontend/src/components/VoteButton.tsx`（toggle UI；樂觀更新）
- 新檔 `frontend/src/components/RequestActionBar.tsx`（state-aware：OPEN→「Claim」/IN_PROGRESS by me→「Fulfill / Release」/IN_PROGRESS by others→鎖；FULFILLED→link to skill）

### 4.8 Frontend — Skill picker for fulfillment

MVP 走最簡：fulfill 按鈕 → 開 modal → 列出 user 自己的 PUBLISHED skills（reuse `useSkillList({author: me.sub, status: "PUBLISHED"})`）→ 點選 → confirm submit。漂亮 picker（搜尋 + autocomplete）defer。

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../community/Request.java` | new | Aggregate `extends AbstractAggregateRoot` |
| `backend/.../community/RequestRepository.java` | new | Spring Data JDBC repo + sort/filter derived queries |
| `backend/.../community/RequestService.java` | new | create / claim / release / fulfill / delete orchestration |
| `backend/.../community/RequestVoteService.java` | new | toggle (raw SQL atomic) |
| `backend/.../community/RequestCommandController.java` | new | POST/DELETE endpoints |
| `backend/.../community/RequestQueryController.java` | new (取代 既有 RequestController.java stub) | GET list / GET single |
| `backend/.../community/RequestController.java` | delete | 由 Command + Query 取代 |
| `backend/.../community/events/RequestPostedEvent.java` etc | new | 5 records |
| `backend/.../community/RequestNotFoundException.java` etc | new | + GlobalExceptionHandler mapping |
| `backend/src/main/resources/db/migration/V<next>__create_request_tables.sql` | new | 見 §4.2 |
| `backend/src/test/.../community/RequestServiceTest.java` | new | AC-1/2/7/8/9/10/11/12/13 (Testcontainers) |
| `backend/src/test/.../community/RequestVoteServiceTest.java` | new | AC-5/6 含 race test |
| `backend/src/test/.../community/RequestCommandControllerTest.java` | new | web slice |
| `backend/src/test/.../community/RequestQueryControllerTest.java` | new | AC-3/4 list with filter/sort |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/skills.ts` | modify | 加 createRequest / fetchRequest / toggleVote / claimRequest / releaseClaim / fulfillRequest / deleteRequest |
| `frontend/src/hooks/useRequests.ts` | new | list with sort/filter |
| `frontend/src/hooks/useRequest.ts` | new | single detail |
| `frontend/src/components/CreateRequestModal.tsx` | new (implementer 可 inline if testability allows) | title + description + submit |
| `frontend/src/components/VoteButton.tsx` | new | toggle UI 樂觀更新 |
| `frontend/src/components/RequestActionBar.tsx` | new | state-aware claim/fulfill/release/link buttons |
| `frontend/src/pages/RequestBoardPage.tsx` | modify | CTA enabled + 真資料 list + sort chips + status filter |
| `frontend/src/pages/RequestBoardPage.test.tsx` | new | AC-15/16/17 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M90g2 row：📋 → 📐 in-design + 估點修為 S(11) + 設計摘要 |
| `docs/grimo/glossary.md` | modify | 加 Request / Vote / Claim / Fulfill 中英對照 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

| # | Task | Size | Status |
|---|------|------|--------|
| T01 | Backend Request aggregate + endpoints + V10 schema | M | ✅ Tick 18 |
| T02 | Backend vote toggle service (atomic SQL) + endpoint + race tests | S | ✅ Tick 19 |
| T03 | Frontend infra (api/skills.ts mutations + useRequests/useRequest hooks) | XS | ✅ Tick 20 |
| T04 | Frontend RequestBoardPage CTA + CreateRequestModal + VoteButton + RequestActionBar + tests | S | ✅ Tick 21 |

## 7. Result（2026-05-03 ship）

### Verified

- Backend：RequestServiceTest 13/13 PASS（Testcontainers，Tick 18）+ RequestVoteServiceTest 5/5 PASS（Tick 19）+ ModularityTests 2/2 PASS（community 模組正式註冊 ApplicationModule + skill::query / skill::domain SPI 經 NamedInterface 暴露）
- Frontend：RequestBoardPage.test.tsx 3/3 PASS @ 1.54s (AC-15 list+CTA / AC-16 create modal flow / AC-17 vote toggle 樂觀更新)；T03 typecheck PASS。S103 「no spec ID leak」invariant carry-forward 進 AC-15 assertion。

### Implementation deviations from §4

1. **Vote count 維護**：spec §4.3 列 「DELETE → check rows → branch」pattern，T02 改為 「INSERT ON CONFLICT DO NOTHING + UPDATE GREATEST(0, count±1)」雙保險（DB CHECK >= 0 + application GREATEST guard 防 race；對齊 Skill downloadCount S076 已驗 pattern）。
2. **`@Query` annotation 取代 derived query method names**：Spring Boot 4.0.6 AOT codegen 對多屬性 compound sort（`findAllByOrderByVoteCountDescCreatedAtDesc`）產生壞 code（缺逗號）；T01 RequestRepository 改用 explicit `@Query("SELECT ... ORDER BY ... DESC, ... DESC")` workaround。
3. **State-based aggregate delete event**：spec §4.5 假設 `repo.save()` 觸發 `@DomainEvents`；實作發現 state-based aggregate 無 `@Version` 時 `repo.save(loadedEntity)` 誤觸 INSERT 衝主鍵；T01 delete flow 改 `repo.deleteById()` + `ApplicationEventPublisher.publishEvent()` 直接發 `RequestDeletedEvent`（不走 outbox；簡化 path 對齊 security domain pattern）。
4. **Cross-module SPI 走 NamedInterface**：T01 community 模組需訂閱 `SkillRepository.findById`（fulfill 驗 PUBLISHED）+ `skill::query` rating service（無；defer）；正式註冊 `community` ApplicationModule allowedDependencies 加 `skill::domain` + `skill::query`，對齊 review 模組 Tick 7-11 既有 pattern。
5. **Sort chips / status filter chips UI defer**：per §2.4 trim list — AC-15/16/17 不要求；T04 預設 votes desc + 全 status mix；future polish spec 補。
6. **Fulfill skill picker UI defer**：per §2.4 trim — T04 走最簡 `window.prompt('輸入 PUBLISHED skill UUID')`；fancy `useSkillList({author: me.sub, status: 'PUBLISHED'})` modal picker 留 follow-up。
7. **`votedByMe` 列表欄位 defer**：spec §4.1 GET single 含 `votedByMe`；T01/T02 backend 未實作（list/single 皆無該欄位）；T04 VoteButton initial state 預設 `voted=false`，page reload 視覺重置（vote_count 仍正確）— per task spec §Implementation outline 接受 trim。

### Pattern echo to future specs

- **Atomic SQL counter pattern**（vote_count / download_count）— 走 INSERT ON CONFLICT + UPDATE GREATEST(0, count±1) 雙保險；DB CHECK 約束 + application guard。
- **State-based aggregate `@DomainEvents` 限制**：無 `@Version` 時 update flow 加 version field + schema column；delete flow 走 `deleteById` + publishEvent；projection update 走 `@Modifying @Query` raw SQL + aggregate 對應 field 標 `@ReadOnlyProperty`。
- **Cross-module callable service 用 NamedInterface 暴露**：sub-package + `package-info.java` 加 `@NamedInterface("name")`；consumer 模組 allowedDependencies 加 `target :: name`。
- **Spec-Only-Handoff pattern 第 6 次 demo**：user 寫 spec → cron 拆 4 tasks → cron 連續 4 ticks ship。
