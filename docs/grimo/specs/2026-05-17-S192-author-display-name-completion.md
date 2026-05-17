# S192 - 作者顯示名稱一致性收斂

> Status: ⏳ Dev — Phase 4 Step 1 PASS；next `$planning-tasks S192` Phase 4 Step 1.5/2
> Owner: Codex  
> Date: 2026-05-17  
> Size: M(13)  
> Depends On: S154 ✅, S154b ✅, S177 ✅, S186 ✅  
> Non-goal: 不改登入流程、不做 user profile 編輯頁、不做帳號合併、不改 ACL/ownership 判斷

## 1. Goal

`/publish/review?id=301f8ea4-d45c-4814-9c42-ac2c2a055f0a` 的作者欄現在顯示 `u_f7eb3a`；S192 要把所有 user-facing 作者/留言者/評論者顯示改成人類可讀名稱，`author` / `authorId` 只保留給 API、權限判斷、filter、route fallback。

S154/S154b 已經建立基礎：

- `docs/grimo/glossary.md` 定義 `平台識別碼` = `u_<6hex>`，這是 internal PK。
- `frontend/src/types/skill.ts` 已註明 `Skill.author` 不直接顯給 user。
- `frontend/src/lib/displayName.ts` 已有 `getDisplayName(...)`，但目前最後 fallback 仍會顯 `author`。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` 已在 skill detail/list path enrich `authorDisplayName / authorHandle / authorEmail`。

這次不是重做 S154，而是補齊漏網路徑：PublishReview、semantic search result cards、reviews、request comments、notifications，以及所有 still-render `author` / `authorId` as human label 的前端畫面。

### Scenario Anchor

Alice 的平台 user id 是 `u_f7eb3a`，Google name 是 `Sam Zhu`，handle 是 `samzhu`。Alice 上傳「產生字幕檔」後，Bob 在發佈結果頁、瀏覽頁、語意搜尋結果卡片、評論、需求留言、通知都應該看到 `Sam Zhu` 或 `@samzhu`；Bob 不應在一般 UI 看到 `u_f7eb3a`。如果 Bob 看到 install command 或 legacy route URL，`u_f7eb3a` 只能作為 technical segment 出現。

## 2. Current State & Approach

### 2.1 Repo Scan Findings

| Surface | Current file | Current behavior | S192 decision |
| --- | --- | --- | --- |
| Publish result | `frontend/src/pages/PublishReviewPage.tsx` | `<Field label="作者" value={skill.author} />` | Display label uses author display helper |
| Skill cards | `frontend/src/components/SkillCard.tsx` | Already uses `getDisplayName(skill)` | Keep, but helper must stop exposing raw `u_` as visible fallback |
| Skill detail header | `frontend/src/components/v2/PageHeader.tsx` | Already uses `getDisplayName(skill)` | Keep, same helper rule |
| My subscriptions | `frontend/src/pages/MySkillsPage.tsx` | `row.authorDisplayName ?? row.author` | Use shared display helper or backend display label |
| Semantic search result display | `backend/.../SemanticSearchResult.java`, `SemanticSearchService.java` | DTO only has `author` | Add display fields so semantic `SkillCard` can render the author label; do not add author to search/ranking criteria |
| Analytics top skills | `backend/.../OverviewStats.java`, `AnalyticsService.java`, `frontend/src/pages/AnalyticsPage.tsx` | UI shows skill name + downloads only; `author` is used only to build `/skills/{author}/{name}` link | No display-name scope. Treat `author` here as allowed technical route segment; keep defensive guard for missing/`undefined` author |
| Reviews | `ReviewController`, `frontend/src/components/ReviewsPanel.tsx` | UI displays `review.authorId` | Response adds author display fields; delete still compares `authorId` |
| Request comments | `RequestQueryController`, `frontend/src/components/CommentList.tsx` | UI displays `comment.authorId` | Response adds author display fields; delete still compares `authorId` |
| Notifications | `NotificationProjectionListener.java` | New notification titles embed raw `authorId` | New notification titles use display name; stored notification body stays denormalized |

### 2.2 Domain Rule

| Field | Behavior-bearing? | User-visible? | Rule |
| --- | --- | --- | --- |
| `author` / `authorId` | Yes | No | Internal user id for ownership, ACL, API filter, delete comparison, route fallback only |
| `authorDisplayName` | No | Yes | Primary visible label |
| `authorHandle` | Partly | Yes | Public slug for URL/install command when available; not an authorization key |
| `authorEmail` | No | Conditional | Only visible when backend says contact email is public |
| `displayLabel` | No | Yes | UI helper output; must come from login/display data, author snapshot, or LAB display data; never from `u_<id>` |
| `routeSegment` / install command segment | Yes | Technical UI only | Prefer `authorHandle`; may fall back to `author` because commands/routes need a stable identifier |

S192 changes the display contract: platform user id is no longer an acceptable human-facing fallback. Uploading a skill requires an authenticated user, so missing display data is a backend projection/test fixture bug to fix at the source. The raw id can still appear in debug pages, API payloads, logs, tests, route params, and install commands where it is the technical identifier.

### 2.3 Backend Interface

Create a shared read helper in `shared :: security` so every module resolves user labels the same way:

```java
public record UserDisplay(
        String userId,
        @Nullable String displayName,
        @Nullable String handle,
        @Nullable String email
) {}

public interface UserDisplayService {
    UserDisplay resolve(String userId, boolean exposeEmail);
    Map<String, UserDisplay> resolveAll(Collection<String> userIds, boolean exposeEmail);
}
```

Implementation uses existing `UserRepository` + `DisplayNameResolver`. For skill authors, the resolver must use `authorNameSnapshot` when a live `users` row is unavailable. For review/comment/notification actors, the resolver must use the user row created during login; if test fixtures bypass login, the fixture must seed enough user display data instead of relying on a UI fallback.

Keep low-level `DisplayNameResolver` as-is: its final `user_id` fallback is still useful for technical/log/debug call sites. S192 adds the user-facing guard at DTO projection time. `UserDisplayService` must not put a `u_<id>` value into `authorDisplayName`, `reviewerDisplayName`, `commentAuthorDisplayName`, or other fields rendered as normal UI text. If it cannot resolve a real human label for a user-facing DTO, that is a failing fixture/projection path to fix, not a value to silently display.

`SkillQueryService.enrichAuthorIdentity(...)` may either keep its existing private implementation or delegate to `UserDisplayService`. New modules should use the shared service instead of duplicating fallback logic.

`analytics/package-info.java` must add `shared :: security` if Analytics imports `UserDisplayService`; `search`, `review`, `community`, `notification`, and `skill` already allow it or can use existing allowed shared security dependency.

### 2.4 DTO Additions

Add nullable display fields while keeping current id fields for behavior:

```java
// semantic search
record SemanticSearchResult(
    String id,
    String name,
    String description,
    String author,
    @Nullable String authorDisplayName,
    @Nullable String authorHandle,
    String category,
    String categoryDisplay,
    String latestVersion,
    String riskLevel,
    long downloadCount,
    double score
) {}

// reviews / request comments
record ReviewResponse(..., String authorId,
    @Nullable String authorDisplayName,
    @Nullable String authorHandle, ...)

record CommentDto(..., String authorId,
    @Nullable String authorDisplayName,
    @Nullable String authorHandle, ...)
```

Do not rename `author` / `authorId` in existing APIs; that would break permissions and callers. The new fields are display companions.

Semantic search must remain based on `skills.embedding` and visibility scope only. S192 may enrich returned rows with display fields for result cards, but it must not search by author, rank by author, or add author identity to embedding text.

### 2.5 Frontend Interface

Update `frontend/src/lib/displayName.ts` into two explicit helpers:

```ts
export function getDisplayName(obj: AuthorFields): string
// Human-visible label. Never returns `u_<id>`; callers must provide display data.

export function getAuthorRouteSegment(obj: Pick<AuthorFields, 'author' | 'authorHandle'>): string
// Technical URL/install segment. Prefer handle; fallback to author id because command/route segments are identifiers.
```

Update types:

- `SemanticSearchResult` adds `authorDisplayName?` and `authorHandle?`.
- `Review` adds `authorDisplayName?` and `authorHandle?`.
- `RequestComment` adds `authorDisplayName?` and `authorHandle?`.

### 2.6 Low-Fidelity UI Sketches

#### PublishReviewPage

```text
產生字幕檔   [中風險]
...
作者    Sam Zhu
分類    video
版本    v1
狀態    已發佈
```

Install command follows the same split:

```text
作者：Sam Zhu
skills-hub install samzhu/產生字幕檔

作者：Sam Zhu
skills-hub install u_f7eb3a/產生字幕檔   # allowed only when handle is unavailable
```

#### Review / Comment Rows

```text
★★★★★  Sam Zhu          2026/05/17
這個 skill 很好用

Sam Zhu                  2026/05/17 14:30
我也需要支援 mov 檔
```

### 2.7 Approach Comparison

| Approach | What changes | Pros | Cons | Recommendation |
| --- | --- | --- | --- | --- |
| A: Frontend-only helper sweep | Only replace visible `author` render sites with current helper | Fast; no backend DTO changes | Semantic result cards, reviews, and comments still do not have display fields, so UI can still leak ids or lack a usable label | Not enough |
| B: Backend DTO companions + frontend helper split | Add display fields to every user-facing DTO; keep ids for behavior; split display label vs route segment helper | Fixes source of raw ids and preserves permissions/routes | More files and tests | Recommended |
| C: Rename `author` to display name everywhere | Change API meaning of `author` | Looks simple in UI | Breaks author filter, ACL, route fallback, delete ownership checks | Reject |

Chosen approach: B.

## 3. Acceptance Criteria

Run:

```bash
cd backend && ./gradlew test
cd frontend && npm test
cd frontend && npm run verify
```

| AC | Scenario | Verification |
| --- | --- | --- |
| AC-S192-1 | Given `/api/v1/skills/{id}` returns `author="u_f7eb3a"` and `authorDisplayName="Sam Zhu"`; when `/publish/review?id=<id>` renders; then 作者 row shows `Sam Zhu` and does not show `u_f7eb3a`. | Test |
| AC-S192-2 | Given a user-facing skill/review/comment DTO contains `author="u_f7eb3a"` or `authorId="u_f7eb3a"`; when the DTO is returned by the API; then it also contains human-readable display data (`authorDisplayName` or equivalent), because normal UI must not invent a placeholder instead of fixing the source data. | Test |
| AC-S192-3 | Given semantic search returns Alice's skill by embedding match; when `GET /api/v1/search/semantic?q=字幕` responds; then each result includes author display fields for rendering, HomePage semantic card shows `Sam Zhu`, and the search SQL/ranking still does not match or rank by author. | Test |
| AC-S192-4 | Given AnalyticsPage renders a top skill with `author="u_f7eb3a"`; when the row is shown; then visible text contains skill name and downloads only, not `u_f7eb3a`. The link may use `/skills/u_f7eb3a/<name>` because that is a technical route segment. | Test |
| AC-S192-5 | Given Alice wrote a review; when SkillDetailPage reviews load; then review row shows `Sam Zhu`; delete button ownership still uses current `userId === review.authorId`. | Test |
| AC-S192-6 | Given Alice wrote a request comment; when RequestDetailPage loads comments; then comment row shows `Sam Zhu`; delete button ownership still uses current `userId === comment.authorId`. | Test |
| AC-S192-7 | Given notification projection creates a review/comment notification from Alice; when notification title is stored; then the title contains `Sam Zhu` and not `u_f7eb3a`. Existing stored notifications are not migrated. | Test |
| AC-S192-8 | Given source is searched after implementation; when excluding debug/test/API docs/log-only contexts and technical route/command segments; then current user-facing TSX files do not render `.author`, `.authorId`, or `u_<id>` directly as visible human labels. | Inspection |
| AC-S192-9 | Given `authorDisplayName` changes from `Sam Zhu` to `Samuel Zhu`; when display-only fields change; then ACL ownership, author filter, route fallback, review delete, and comment delete still compare `author` / `authorId`, not display name. | Test |
| AC-S192-10 | Given user row is missing but `authorNameSnapshot="Sam Zhu"` exists on a skill; when skill detail/list is returned; then display name falls back to snapshot. Given a test fixture creates a skill/review/comment actor without display data; then the fixture is updated to seed display data before UI assertions run. | Test |
| AC-S192-11 | Given low-level `DisplayNameResolver.resolve(...)` has only `userId="u_f7eb3a"`; when called directly by technical code; then it may return `u_f7eb3a`. Given a user-facing DTO projection uses `UserDisplayService`; then the DTO display field must not equal `u_f7eb3a`. | Test |
| AC-S192-12 | Given a skill has `authorDisplayName="Sam Zhu"` and no `authorHandle`; when InstallCard renders; then the author label shows `Sam Zhu`, and the install command may contain `u_f7eb3a` as the technical segment. | Test |

### NFR Coverage

| Category | Coverage |
| --- | --- |
| Performance | `UserDisplayService.resolveAll(...)` batch resolves review/comment/semantic result display where practical; no N+1 lookup for pageable lists larger than 20. |
| Security | Display names never affect authorization; AC-S192-9 proves behavior still uses ids. |
| Reliability | Missing live user rows for skills must fall back to author snapshot; actor fixtures must seed display data instead of leaking IDs. |
| Usability | Visible UI hides `u_<id>` and displays real user names/handles from authenticated user data; AC-S192-1~8 cover. |
| Maintainability | Shared helper/service prevents each module inventing a different display fallback. |

## 4. File Plan

### Backend

| File | Change |
| --- | --- |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplay.java` | New record |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplayService.java` | New shared resolver service |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | Optionally delegate existing enrich logic to shared service |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchResult.java` | Add display fields |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java` | Carry display fields |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | Enrich result display fields for hits without changing embedding search/ranking criteria |
| `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewController.java` | Enrich `ReviewResponse` |
| `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java` | Enrich `CommentDto` |
| `backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java` | New notification titles use display names |

### Frontend

| File | Change |
| --- | --- |
| `frontend/src/lib/displayName.ts` | Split human label vs route segment fallback |
| `frontend/src/types/skill.ts` | Update display helper contract comments |
| `frontend/src/types/skill.ts` / `frontend/src/api/reviews.ts` / `frontend/src/api/skills.ts` | Add display fields to DTOs |
| `frontend/src/pages/PublishReviewPage.tsx` | Author row uses display helper |
| `frontend/src/pages/HomePage.tsx` | Semantic card gets display fields from API |
| `frontend/src/pages/AnalyticsPage.tsx` | No new author label; keep route segment behavior and visible-text guard |
| `frontend/src/pages/MySkillsPage.tsx` | Subscription author label uses display helper |
| `frontend/src/components/ReviewsPanel.tsx` | Review row uses display helper |
| `frontend/src/components/CommentList.tsx` | Comment row uses display helper |
| Existing tests near each file | Add AC-S192 ids |

### Docs

| File | Change |
| --- | --- |
| `docs/grimo/glossary.md` | Clarify platform user id is behavior-only and not a user-facing label |
| `docs/grimo/development-standards.md` | Add short rule: user-facing UI must use display helper, not raw `author` / `authorId` |

## 5. Task Boundary Hints

1. Backend shared display resolver: `UserDisplayService` + unit tests.
2. Backend DTO parity: semantic search result display.
3. Backend actor display parity: reviews + request comments + notifications.
4. Frontend helper split + existing component tests update.
5. Frontend page sweep: PublishReview, Analytics, ReviewsPanel, CommentList, MySkills subscription row.
6. Source-scan guard and docs update.

This is M-sized because it crosses six backend modules plus multiple frontend surfaces, but the domain model is already known from S154/S154b.

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
## 6. Task Plan

POC：not required — S192 不新增 package、SDK、framework SPI、schema migration 或外部服務；它只補齊 S154/S154b 已驗證的 user identity/display-name pattern。Phase 0 pre-flight 已重新檢查 PRD、S154/S154b archived findings、目前 `DisplayNameResolver` / `displayName.ts` / semantic/review/comment/notification code；未發現需要回到 `/planning-spec` 的設計矛盾。

| 順序 | Task | AC | 狀態 | 驗證 |
|---:|---|---|---|---|
| 1 | `2026-05-17-S192-T01-backend-user-display-service.md` | AC-S192-2, AC-S192-10, AC-S192-11 | PASS | `cd backend && ./gradlew test --tests "*UserDisplayServiceTest"` |
| 2 | `2026-05-17-S192-T02-semantic-author-display.md` | AC-S192-3 | PASS | `cd backend && ./gradlew test --tests "*SemanticSearch*"` |
| 3 | `2026-05-17-S192-T03-actor-display-dtos.md` | AC-S192-2, AC-S192-5, AC-S192-6, AC-S192-7, AC-S192-9 | PASS | `cd backend && ./gradlew test --tests "*ReviewControllerTest" --tests "*Comment*Test" --tests "*NotificationProjectionListenerTest"` |
| 4 | `2026-05-17-S192-T04-frontend-display-helper.md` | AC-S192-11, AC-S192-12 | PASS | `cd frontend && npm test -- displayName` |
| 5 | `2026-05-17-S192-T05-frontend-surface-sweep.md` | AC-S192-1, AC-S192-3, AC-S192-4, AC-S192-5, AC-S192-6, AC-S192-12 | PASS | `cd frontend && npm test -- PublishReviewPage HomePage MySkillsPage AnalyticsPage ReviewsPanel CommentList` |
| 6 | `2026-05-17-S192-T06-source-scan-docs-guard.md` | AC-S192-8, Maintainability NFR | PASS | `rg -n "\\.(author|authorId)\\b" frontend/src --glob '*.tsx'` |
| 7 | `2026-05-17-S192-T07-skillcard-fixture-display-data.md` | AC-S192-8, AC-S192-11 | PASS | `cd frontend && npm test -- SkillCard` |
| 8 | `2026-05-17-S192-T08-user-display-fixture-isolation.md` | AC-S192-2 | PASS | `cd backend && ./gradlew test --tests "*UserDisplayServiceTest" --tests "*UserRepositoryTest"` |

E2E artifact verification：not required for planning — S192 的 AC 都是 API DTO shape、React component text、notification title projection 或 source inspection；沒有新增 route、test seed endpoint、browser-only workflow、schema migration、credential injection 或 packaged artifact 行為。Phase 4 仍需重新評估並在 §7 記錄理由。

## 7. Implementation Results

### S192-T06 Source Scan Guard

`rg -n "\\.(author|authorId)\\b" frontend/src --glob '*.tsx'` 回傳 15 筆，分類如下：

| 分類 | 檔案 | 判定 |
|---|---|---|
| Route / API filter | `SkillDetailPage.tsx`, `MySkillsPage.tsx`, `AnalyticsPage.tsx` | 可保留；這些值用於 `useSkillByAuthorAndName(...)`、author filter、或 `/skills/{author}/{name}` technical route segment，不是人名 label |
| Delete / ownership comparison | `ReviewsPanel.tsx`, `CommentList.tsx` | 可保留；delete button 判斷仍必須比較 current user id 與 `authorId` |
| Display helper input | `MySkillsPage.tsx`, `ReviewsPanel.tsx`, `CommentList.tsx` | 可保留；raw id 只作為 `getDisplayName(...)` 的輸入，helper 會避免輸出 `u_<id>` |
| Test comment | `RequestDetailPage.test.tsx` | 可保留；註解描述 fixture ownership 行為 |

AC-S192-8 PASS：source scan 沒有找到 `.author` / `.authorId` 直接作為一般 visible label render 的 TSX。`docs/grimo/glossary.md` 與 `docs/grimo/development-standards.md` 已補上 display-vs-id 規則。

### Phase 4 Finding: SkillCard Fixture

`cd frontend && npm test` 在 `frontend/src/components/SkillCard.test.tsx:42` 失敗：base fixture 只有 `author: 'samzhu'`，但 S192 後 `getDisplayName(...)` 不再把 `author` 當人名 fallback，所以畫面作者列為空。這不是 production UI bug；這是舊 test fixture 還把 behavior-bearing `author` 當 display data。T07 已修正 fixture，讓 raw `author` 改為 `u_a3f9c1`，visible label 由 `authorHandle: 'samzhu'` 提供；`cd frontend && npm test -- SkillCard` PASS（2 files / 11 tests）。

### Phase 4 Finding: UserDisplayService Fixture Collision

`./gradlew test` 在 `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserRepositoryTest.java:43` 失敗，錯誤是 `DuplicateKeyException`。實際撞到的是 S192 新增的 `UserDisplayServiceTest.resolveAllDeduplicatesIds()` 也 seed `users.id='u_bbbbbb'`；`RepositorySliceTestBase` 共用 PostgreSQL container 且不靠 rollback 清資料，所以 S192 fixture 不能重用既有 fixed id。T08 已將 S192 專用 fixture 改為 `u_192bbb`；`cd backend && ./gradlew test --tests "*UserDisplayServiceTest" --tests "*UserRepositoryTest"` PASS（BUILD SUCCESSFUL）。

### Phase 4 Step 1 Deterministic Checks

`cd backend && ./gradlew test` PASS（BUILD SUCCESSFUL in 4m 58s）。這確認 T08 修掉 `UserDisplayServiceTest` / `UserRepositoryTest` 的 fixed-id 撞資料問題後，完整 backend test suite 可以跑完。

`cd frontend && npm test` PASS（80 files / 459 tests）。這確認 T07 修掉 `SkillCard.test.tsx` fixture 後，完整 frontend test suite 可以跑完。

`cd frontend && npm run verify` PASS（`eslint . --max-warnings 0` + `tsc -b`）。這確認 S192 frontend helper/type changes 沒有 lint 或 TypeScript build error。
