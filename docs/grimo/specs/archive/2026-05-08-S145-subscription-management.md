# S145: 訂閱管理頁面

> Spec: S145 | Size: S(8) | Status: ✅ Done
> Date: 2026-05-08
> Revised: 2026-05-11
> Origin: site audit 2026-05-08 — Skill detail 已可「訂閱 / 取消訂閱」，但使用者沒有集中管理入口；`/subscriptions` 目前回 Spring 404。

---

## 1. Goal

讓使用者在「我的技能」頁看到自己訂閱了哪些 skills，並能取消不再需要的訂閱。

`skill_subscriptions` row 已經是新版通知的 subscriber source：作者發布新版本時，[NotificationProjectionListener](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java:161) 會查訂閱者並寫通知。S145 補的是管理列表，讓使用者能清理未來會收到通知的訂閱。

```
使用者在 SkillDetail 點「訂閱」
  → DB: skill_subscriptions 多一筆 (skill_id, subscriber_id, created_at)
  → 作者未來發布 v2.0.0
  → notification listener 查 skill_subscriptions 寫 notification
  → 使用者在「我的技能 / 訂閱」可取消不需要的訂閱
```

**非目標：**
- 不做 email / SMTP 寄信；本 spec 的「通知」只指平台內通知中心（notification inbox）。
- 不改現有 SkillDetail star/toggle API：`POST/DELETE /api/v1/skills/{id}/subscribe` 繼續沿用。
- 不做訂閱分類、標籤、批次退訂。

**相依狀態：**

| Dependency | Status | Code-level? | Notes |
|------------|--------|-------------|-------|
| S125a | ✅ shipped | yes | `skill_subscriptions` schema、aggregate、repo、service 已存在。 |
| S125b | ✅ shipped | yes | `POST/DELETE /skills/{id}/subscribe`、`GET /me/subscriptions`、新版通知 listener 已存在。 |
| S125c | ✅ shipped | yes | `useSubscription` hooks 與 SkillDetail star/toggle UI 已存在。 |

---

## 2. Approach

### 2.1 Research Findings

| Source | Finding |
|--------|---------|
| [V14 migration](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/resources/db/migration/V14__create_skill_subscriptions.sql:20) | `skill_subscriptions` 已有 `skill_id`、`subscriber_id`、`created_at`、`UNIQUE(skill_id, subscriber_id)`，且有 `subscriber_id` index；足夠支援「我的訂閱列表」。 |
| [SkillSubscriptionRepository](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionRepository.java:30) | `findBySubscriberId` 已存在；目前只回 skillId list 給 SkillDetail subscribed state。 |
| [SkillSubscriptionController](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionController.java:67) | 現有 endpoint 是 `GET /api/v1/me/subscriptions`，不是舊 spec 寫的 `/api/v1/subscriptions/me`。 |
| [SkillSubscriptionService](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionService.java:87) | 現有 `findSubscriptionsOfCurrentUser()` 回 `List<String>`；S145 需要新增 rich summary method，避免破壞既有 contract。 |
| [NotificationProjectionListener](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java:161) | `onVersionPublished` 已用 subscription row 產生 `versions` notification；S145 的管理列表會直接影響未來版本通知收件者。 |
| [S125c](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-04-S125c-subscription-frontend.md:37) | Frontend 已有 `useMySubscriptions()`、`useSubscribeSkill()`、`useUnsubscribeSkill()`；S145 可加 rich-list hook，不重寫 toggle flow。 |
| [PRD P9](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/PRD.md:283) | 產品目標是 domain events 轉成 user-facing notifications；subscription 是「新版發布通知」的過濾來源。 |

### 2.2 Approach Comparison

| Approach | Chosen | What changes | Runtime behavior | Cost / Risk |
|----------|--------|--------------|------------------|-------------|
| A: 前端拿 `GET /me/subscriptions` 的 id list，再逐一 `GET /skills/{id}` | no | 只改 frontend | 訂閱 20 個 skills 會打 21 個 HTTP request；列表載入慢且測試 mock 分散。 | 短期快，但 N+1 request 明顯，不適合管理頁。 |
| B: 保留既有 id-list endpoint，新增 `GET /api/v1/me/subscriptions/details` rich summary endpoint | yes | 後端加 summary DTO + service query；前端加列表 hook + tab UI | 一次 request 回每張 card 要的 `skillId/name/authorDisplayName/latestVersion/riskLevel/subscribedAt`；既有 `GET /me/subscriptions` 不破壞。 | 小幅 backend 查詢與 frontend UI；最少破壞面。 |
| C: 改 `GET /api/v1/me/subscriptions` 從 `string[]` 變 rich object list | no | 修改既有 contract | SkillDetail `useIsSubscribed()` 不能再直接 `includes(skillId)`；S125c tests 與所有 caller 要一起改。 | 破壞既有 API，不值得。 |

**Chosen:** Approach B.

原因很直接：`GET /api/v1/me/subscriptions` 已被 SkillDetail 的 star/toggle 使用，不能改 shape；管理頁需要 card 摘要，所以新增一個明確的 details endpoint。

### 2.3 Data Flow

```
MySkillsPage tab="subscriptions"
  → useMySubscriptionDetails()
  → GET /api/v1/me/subscriptions/details
  → SkillSubscriptionService.findSubscriptionDetailsOfCurrentUser()
  → DB:
      SELECT ss.skill_id, ss.created_at, s.name, s.author, s.author_name_snapshot,
             s.latest_version, s.risk_level, s.status
      FROM skill_subscriptions ss
      JOIN skills s ON s.id = ss.skill_id
      WHERE ss.subscriber_id = :currentUserId
      ORDER BY ss.created_at DESC
  → UI card 顯示技能名稱、作者顯示名、版本、風險、訂閱日期
```

### 2.4 Design Decisions

| Decision | Rationale |
|----------|-----------|
| 新 endpoint 用 `/api/v1/me/subscriptions/details` | 保留 `/api/v1/me/subscriptions` 的 `string[]` contract；`/me/*` 表示 current-user resource，與 S125b 既有路徑一致。 |
| Summary DTO 放 community module | subscription 是 community aggregate；controller/service 也在 community。DTO 只引用 plain fields，不讓 frontend 依賴 Skill aggregate detail view。 |
| 退訂仍呼叫 `DELETE /api/v1/skills/{id}/subscribe` | 這是 S125b shipped API；退訂 row 的 key 是 current user + skill id，不需要 subscription id。 |
| details endpoint 不回已刪除 skill 的 orphan subscription | V14 允許 soft-FK；管理頁只列還存在的 skills。若未來要清 orphan，可另開 cleanup spec。 |
| 平台通知中心，不做 email | PRD P9 已定義 notifications；S145 的訂閱管理只控制誰會收到平台內 `versions` notification，不處理 SMTP、退信或外部 email provider。 |

### 2.5 Confidence

| Decision | Confidence | Evidence |
|----------|------------|----------|
| 新增 current-user details endpoint | Validated | Spring MVC controller、CurrentUserProvider、Spring Data JDBC/JdbcTemplate patterns 已在 S125b/S154 多次使用。 |
| 使用 `skill_subscriptions.created_at` 當訂閱時間 | Validated | V14 schema 與 `SkillSubscription.getCreatedAt()` 已存在。 |
| Frontend TanStack Query hook + cache invalidation | Validated | S125c `useSubscription.ts` 已有相同 mutation invalidation pattern。 |
| Browser E2E | Not required for design | 這是 MySkillsPage 內 tab + API render，Vitest + backend tests 可覆蓋 AC；沒有新外部 boundary。 |

---

## 3. SBE Acceptance Criteria

驗證命令：
- Backend: `./gradlew test`
- Frontend: `cd frontend && npm test`

Pass：所有標記 `AC-S145-*` 的 backend/frontend tests 綠。

**AC-S145-1: 查看我的訂閱列表**

Given Alice 已訂閱 `deep-research` 和 `docker-helper`  
When Alice 進入「我的技能」並點「訂閱」tab  
Then 畫面顯示 2 張訂閱 card  
And 每張 card 顯示 skill 名稱、作者、最新版本、風險等級、訂閱時間  

**AC-S145-2: 訂閱列表只顯示當前使用者的訂閱**

Given Alice 訂閱 `deep-research`  
And Bob 訂閱 `docker-helper`  
When Alice 呼叫 `GET /api/v1/me/subscriptions/details`  
Then response 只包含 `deep-research`  
And 不包含 Bob 的 `docker-helper`  

**AC-S145-3: 從管理頁退訂**

Given Alice 在訂閱列表看到 `deep-research`  
When Alice 點該 card 的「取消訂閱」按鈕  
Then frontend 呼叫 `DELETE /api/v1/skills/{deepResearchId}/subscribe` 並收到 204  
And `deep-research` card 從列表移除  
And 顯示 toast「已取消訂閱」  

**AC-S145-4: 空訂閱狀態**

Given Alice 尚未訂閱任何 skill  
When Alice 進入「我的技能」的「訂閱」tab  
Then 顯示 empty state「尚未訂閱任何技能」  
And 顯示「前往瀏覽」按鈕連到 `/`  

**AC-S145-5: 既有 SkillDetail subscribed state 不破壞**

Given Alice 已訂閱 `deep-research`  
When SkillDetail 呼叫既有 `GET /api/v1/me/subscriptions`  
Then response 仍是 `["{deepResearchId}"]`  
And `useIsSubscribed(deepResearchId)` 仍回 `true`  

---

## 4. Interface / API Design

### 4.1 Backend API

Keep existing:

```http
GET /api/v1/me/subscriptions
200 OK
["skill-uuid-1", "skill-uuid-2"]
```

Add:

```http
GET /api/v1/me/subscriptions/details
200 OK
[
  {
    "skillId": "0d4c7a6a-2f4d-4f9b-9a10-111111111111",
    "skillName": "deep-research",
    "author": "u_a3f9c1",
    "authorDisplayName": "Sam Zhu",
    "latestVersion": "1.2.0",
    "riskLevel": "LOW",
    "status": "PUBLISHED",
    "subscribedAt": "2026-05-08T10:15:30Z"
  }
]
```

Field sources:

| Field | Source |
|-------|--------|
| `skillId` | `skill_subscriptions.skill_id` |
| `skillName` | `skills.name` |
| `author` | `skills.author` platform user id |
| `authorDisplayName` | same resolver pattern as skill list/detail: live user name if available, fallback snapshot/handle/user id |
| `latestVersion` | `skills.latest_version` |
| `riskLevel` | `skills.risk_level` |
| `status` | `skills.status` |
| `subscribedAt` | `skill_subscriptions.created_at` |

### 4.2 Backend Types

```java
public record SkillSubscriptionSummary(
        String skillId,
        String skillName,
        String author,
        String authorDisplayName,
        String latestVersion,
        String riskLevel,
        String status,
        Instant subscribedAt
) {}
```

```java
@GetMapping("/api/v1/me/subscriptions/details")
List<SkillSubscriptionSummary> mySubscriptionDetails() {
    return service.findSubscriptionDetailsOfCurrentUser();
}
```

```java
@Transactional(readOnly = true)
public List<SkillSubscriptionSummary> findSubscriptionDetailsOfCurrentUser()
```

Implementation note: use `JdbcTemplate` for the join if Spring Data JDBC derived queries cannot express the projection cleanly. This is read-side query code, not aggregate mutation.

### 4.3 Frontend API + Hook

```ts
export interface SubscriptionSummary {
  skillId: string
  skillName: string
  author: string
  authorDisplayName: string | null
  latestVersion: string | null
  riskLevel: RiskLevel | null
  status: SkillStatus
  subscribedAt: string
}

export function fetchMySubscriptionDetails(): Promise<SubscriptionSummary[]> {
  return apiFetch<SubscriptionSummary[]>('/me/subscriptions/details')
}

export function useMySubscriptionDetails() {
  return useQuery({
    queryKey: ['my-subscriptions', 'details'],
    queryFn: fetchMySubscriptionDetails,
    staleTime: 30 * 1000,
  })
}
```

Mutation invalidation:

```ts
onSuccess: () => {
  qc.invalidateQueries({ queryKey: ['my-subscriptions'] })
  qc.invalidateQueries({ queryKey: ['my-subscriptions', 'details'] })
}
```

### 4.4 Frontend UI

`MySkillsPage` tab set changes from lifecycle-only to include subscriptions:

```ts
type MySkillsTab = 'all' | 'PUBLISHED' | 'DRAFT' | 'SUSPENDED' | 'SUBSCRIPTIONS'
```

When `tab === 'SUBSCRIPTIONS'`:
- Hide author skill rows.
- Render subscription cards/rows.
- Each row has:
  - `Link` to `/skills/{skillId}`
  - skill name
  - author display name
  - latest version badge
  - risk badge
  - subscribed date
  - `取消訂閱` button with `BellOff` or `Trash2` icon

Empty state:

```tsx
<EmptyState
  tone="invite"
  headline="尚未訂閱任何技能"
  sub="前往瀏覽找到有興趣的技能後點「訂閱」，未來有新版本時會收到通知。"
  primaryAction={{ label: '前往瀏覽', href: '/' }}
/>
```

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionController.java` | modify | Add `GET /api/v1/me/subscriptions/details`; keep existing `GET /me/subscriptions` unchanged. |
| `backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionService.java` | modify | Add `findSubscriptionDetailsOfCurrentUser()` and summary projection query. |
| `backend/src/test/java/io/github/samzhu/skillshub/community/SkillSubscriptionServiceTest.java` | modify | Add AC-S145-2 and AC-S145-5 regression coverage for current-user filtering and old id-list contract. |
| `backend/src/test/java/io/github/samzhu/skillshub/community/SkillSubscriptionControllerTest.java` | new/modify | Add WebMvc coverage for details endpoint shape if no suitable existing controller test exists. |
| `frontend/src/api/subscriptions.ts` | modify | Add `SubscriptionSummary` type and `fetchMySubscriptionDetails()`. |
| `frontend/src/hooks/useSubscription.ts` | modify | Add `useMySubscriptionDetails()` and invalidate details cache after subscribe/unsubscribe. |
| `frontend/src/pages/MySkillsPage.tsx` | modify | Add 「訂閱」 tab, subscription list rows/cards, cancel button, empty state. |
| `frontend/src/pages/MySkillsPage.test.tsx` | modify | Add AC-S145-1/3/4 UI tests and AC-S145-5 API regression mock. |
| `docs/grimo/specs/2026-05-08-S145-subscription-management.md` | modify | This revised design. |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

POC: not required — S145 only reuses existing S125 subscription table/API patterns, Spring MVC controller patterns, and TanStack Query hook patterns already validated in shipped specs. No new SDK, external service, framework SPI, or container/CLI behavior is introduced.

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Backend subscription details endpoint | AC-S145-2, AC-S145-5 | PASS |
| T02 | Frontend subscription details API and hook | AC-S145-5 | PASS |
| T03 | MySkills subscription tab and unsubscribe UI | AC-S145-1, AC-S145-3, AC-S145-4 | PASS |

Execution order: T01 → T02 → T03

### POC Findings

Not required.

---

## 7. Implementation Results

### 7.1 Verification Results

| Command | Result | Notes |
|---------|--------|-------|
| `cd backend && ./gradlew test --tests "*SkillSubscriptionServiceTest" --tests "*SkillSubscriptionControllerTest"` | PASS | Gradle `:test` completed `BUILD SUCCESSFUL`; the project also ran test AOT scanning/context setup during this command. |
| `cd frontend && npm test -- --run src/hooks/useSubscription.test.tsx src/pages/MySkillsPage.test.tsx` | PASS | 2 files / 15 tests. |
| `cd frontend && npm test` | PASS | 66 files / 360 tests. |
| `./scripts/verify-all.sh` | PASS | V01/V03/V04/V05/V06/V07/V08a/V08b all PASS; V02 INFO line coverage 82.9%; Verdict `✅ all CRITICAL passed; exit=0`. |

### 7.2 E2E Artifact Verification

E2E not required — S145 does not add a new browser fixture boundary, external service, schema migration, event serialization path, or subprocess behavior. The real seams are covered by backend controller/service tests and frontend page/hook tests:

- Backend verifies `GET /api/v1/me/subscriptions/details` response shape and current-user filtering.
- Frontend verifies `/my-skills` renders the subscription tab, removes a row after `DELETE /skills/{id}/subscribe`, updates cache state, and shows the empty state.

### 7.3 Key Findings

- `GET /api/v1/me/subscriptions` remains a `string[]` endpoint for SkillDetail subscribed-state compatibility.
- `GET /api/v1/me/subscriptions/details` was added for the management page card data.
- `subscribeSkill()` and `unsubscribeSkill()` now use `apiFetchVoid()` because the existing subscribe/unsubscribe endpoints return empty 201/204 bodies. This was exposed by the S145 mutation cache invalidation test.
- `useUnsubscribeSkill()` invalidates both `['my-subscriptions']` and `['my-subscriptions', 'details']`; `MySkillsPage` also optimistically removes the cancelled row from both caches after success.
- The implementation explicitly uses the platform notification inbox model only; no email/SMTP code or configuration was added.

### 7.4 AC Results

| AC | Result | Evidence |
|----|--------|----------|
| AC-S145-1 | PASS | `MySkillsPage.test.tsx` renders two subscription rows with name, author display name, version, risk, and subscribed date. |
| AC-S145-2 | PASS | `SkillSubscriptionServiceTest` verifies Alice only sees Alice's subscription details, not Bob's. |
| AC-S145-3 | PASS | `MySkillsPage.test.tsx` verifies clicking `取消訂閱` calls `DELETE /api/v1/skills/{id}/subscribe`, removes the row, and shows `已取消訂閱`. |
| AC-S145-4 | PASS | `MySkillsPage.test.tsx` verifies empty state `尚未訂閱任何技能` and `前往瀏覽`. |
| AC-S145-5 | PASS | Backend and frontend tests verify the existing id-list contract remains `string[]` and `useIsSubscribed()` still derives from it. |

### 7.5 Pending Verification

None.

### 7.6 QA Review

Verdict: PASS.

Independent QA re-read the S145 spec and implementation files, reran the backend and frontend targeted tests, and found no blocking findings.

QA-confirmed AC coverage:

| AC | Test Evidence |
|----|---------------|
| AC-S145-1 | `MySkillsPage.test.tsx` — `AC-S145-1: 訂閱 tab 顯示 skill card 欄位` |
| AC-S145-2 | `SkillSubscriptionServiceTest` — `AC-S145-2: details list 只回當前 user 訂閱摘要，含 card 欄位` |
| AC-S145-3 | `MySkillsPage.test.tsx` — `AC-S145-3: 點取消訂閱後呼叫 DELETE、移除 card、顯示 toast` |
| AC-S145-4 | `MySkillsPage.test.tsx` — `AC-S145-4: 無訂閱時顯示 empty state 和前往瀏覽` |
| AC-S145-5 | `SkillSubscriptionControllerTest` — `AC-S145-5: GET /me/subscriptions still returns string id list`; plus service/hook regressions. |
