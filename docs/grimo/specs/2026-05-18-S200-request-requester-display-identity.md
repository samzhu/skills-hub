# S200: Request Requester Display Identity

> 規格：S200 | 大小：XS(4) | 狀態：✅ QA PASS / local release PASS
> 日期：2026-05-18
> 對應：S192 作者顯示名稱一致性收斂、S156c Request voting board、S196 Request Board tabs

---

## 1. 目標

`/requests/{id}` 詳情頁標題下方現在會顯示：

```text
u_5dccb3 · 2026/5/18
```

這是 `frontend/src/pages/RequestDetailPage.tsx:98` 直接把 `request.requesterId` 當成畫面文字渲染。`requesterId` 是平台內部 user id，跟 S192 已定義的 `author` / `authorId` 一樣，只能拿來做權限判斷、刪除判斷、API filter，不該當成人類可讀 label。

S200 要補齊 S192 漏掉的 request 本體作者面：後端 `/api/v1/requests` 與 `/api/v1/requests/{id}` 多回 `requesterDisplayName / requesterHandle`，前端詳情頁用顯示欄位渲染，刪除權限仍用 `requesterId`。

### Scope

| In | Out |
|---|---|
| `RequestQueryController.RequestResponse` 增加 `requesterDisplayName / requesterHandle` | 不改 request create / vote / comment API |
| `RequestDetailResponse` 增加同樣 display companion 欄位 | 不改 `requesterId` 欄位名稱與意義 |
| `RequestDetailPage` header 改用 `getDisplayName(...)` 顯示 requester label | 不在 request board card 顯示 requester，因目前 UI 沒有 requester 欄 |
| 後端/前端測試證明不顯示 `u_<id>` | 不新增 profile page 或 user search |

### Active Spec Overlap Scan

| Active spec | 是否重疊 | 判斷 |
|---|---:|---|
| S197 必填欄位即時提示 UX | no | Publish/Edit form UX，沒有 request DTO 或 identity display。 |
| S198 SKILL.md Recommendations Not Hard Errors | no | Validator hard error / warning 分流，沒有 request UI。 |
| S199 Publish Failed Actionable Validation Copy | no | Publish failed page error copy，沒有 request UI。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對 S200 的影響 |
|---|---|---|
| `docs/grimo/specs/archive/2026-05-17-S192-author-display-name-completion.md:40` | S192 只列 `Request comments`，補的是 `comment.authorId` display 欄位。 | 漏網的是 request 本體 `requesterId`，不是 comment row。 |
| `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java:64` | Detail path 已用 `UserDisplayService.resolveAll(...)` 幫 comments 查人名。 | 可沿用同一個服務查 requester，不需要新 service 或 schema。 |
| `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java:87` | List DTO 目前只回 `requesterId`，沒有 display companion。 | List API 也要一起補欄位，避免未來 card 想顯示 requester 時再次 leak。 |
| `frontend/src/pages/RequestDetailPage.tsx:98` | Header 直接 render `{request.requesterId}`。 | UI bug 觸發點就在這行。 |
| `frontend/src/lib/displayName.ts:16` | `getDisplayName(...)` 已保證不 fallback 顯示 raw platform id。 | 前端直接復用現有 helper；若後端沒 display data，畫面不要退回 `requesterId`。 |
| `frontend/src/components/CommentList.tsx:57` | Comment row 已把 `authorId` 映射到 `getDisplayName({ author, authorDisplayName, authorHandle })`。 | Request header 可用同一個 mapping pattern，只是欄位名從 requester 轉成 author helper 需要 adapter。 |

### 2.2 Root Cause

`S192` 的掃描清單把 request 區塊拆成「comments」來修，沒有把「request 自己是誰開的」列成一個 user-facing surface。結果是：

```text
GET /api/v1/requests/{id}
  -> response 有 requesterId
  -> response 沒 requesterDisplayName / requesterHandle
  -> RequestDetailPage 只能顯示 requesterId
  -> user 看到 u_5dccb3
```

真正要修的是 API contract 缺 display companion，不是只在前端把 `u_5dccb3` 隱藏。前端如果拿不到 display data，應該顯示日期或空 label，而不是自己猜名字。

### 2.3 Domain Rule

| Field | 行為用途 | 可顯示給 user? | 規則 |
|---|---|---:|---|
| `requesterId` | delete request ownership、notification recipient、audit/event payload | no | 保留在 API；不能當一般 UI label。 |
| `requesterDisplayName` | request requester human label | yes | primary display label；不可等於 raw `u_<id>`。 |
| `requesterHandle` | requester fallback label / possible future profile route | yes | display fallback；不參與權限判斷。 |
| `canDelete` | backend-computed viewer action | yes | 仍由 `users.current().userId().equals(requesterId)` 算出，不受 display name 影響。 |

### 2.4 DTO Contract

後端 request list/detail response 增加 nullable display 欄位：

```java
record RequestResponse(
    String id,
    String title,
    String description,
    String requesterId,
    @Nullable String requesterDisplayName,
    @Nullable String requesterHandle,
    long voteCount,
    Instant createdAt,
    Instant updatedAt
) {}

record RequestDetailResponse(
    String id,
    String title,
    String description,
    String requesterId,
    @Nullable String requesterDisplayName,
    @Nullable String requesterHandle,
    long voteCount,
    Instant createdAt,
    Instant updatedAt,
    List<CommentDto> comments,
    boolean canDelete
) {}
```

`requesterId` 不改名，因為 frontend delete 判斷、notification、audit 與既有 caller 仍需要穩定 id。

### 2.5 做法比較

| 做法 | 改哪裡 | user 看到什麼 | 成本 / 風險 | 採用 |
|---|---|---|---|---|
| A: 前端只把 requesterId 隱藏 | `RequestDetailPage.tsx` | 看不到 `u_5dccb3`，但也看不到開需求的人 | 低成本，但 API contract 還是缺 display data，下一個 UI 會再踩。 | no |
| B: 後端 DTO 補 display companion + 前端 helper render | `RequestQueryController`、`skills.ts`、`RequestDetailPage.tsx` | `Sam Sam · 2026/5/18` | XS，沿用 `UserDisplayService`；需補測試。 | yes |
| C: 把 `requesterId` 改成 display name | API field rename / meaning change | UI 變簡單 | 會破壞刪除權限、通知 recipient、audit/event 語意。 | no |

Chosen approach: B。

### 2.6 UI Sketch

```text
← 需求看板

↑
1        需要字幕功能
         Sam Sam · 2026/5/18

┌──────────────────────────────────────────────┐
│ 想將 youtube 影片加上字幕                     │
└──────────────────────────────────────────────┘

留言（1）
Sam Sam  2026/5/18 下午9:57:31
我也要
```

如果後端缺 display data，header 不 fallback 顯示 `u_<id>`：

```text
需要字幕功能
2026/5/18
```

## 3. 驗收條件（SBE）

驗證命令：

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.community.RequestDetailQueryTest
cd frontend && npm test -- RequestDetailPage.test.tsx
```

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S200-1 | 必做 | Test | request detail API 回 requester display companion |
| AC-S200-2 | 必做 | Test | request list API 回 requester display companion |
| AC-S200-3 | 必做 | Test | detail header 顯示 requesterDisplayName，不顯 requesterId |
| AC-S200-4 | 必做 | Test | canDelete 仍用 requesterId，不用 display name |
| AC-S200-5 | 必做 | Test | display data 缺失時 UI 不 fallback 顯示 `u_<id>` |

**AC-S200-1: request detail API 回 requester display companion**
- Given `users` 表有 `id="u_aa1111", name="Alice Chen", handle="alice"`
- And Alice 建立一筆 request
- When `GET /api/v1/requests/{id}` 回應
- Then JSON 含 `requesterId="u_aa1111"`
- And JSON 含 `requesterDisplayName="Alice Chen"`
- And JSON 含 `requesterHandle="alice"`

**AC-S200-2: request list API 回 requester display companion**
- Given `users` 表有 `id="u_bb2222", name="Bob Lin", handle="bob"`
- And Bob 建立一筆 request
- When `GET /api/v1/requests` 回應
- Then該 row 含 `requesterId="u_bb2222"`
- And 該 row 含 `requesterDisplayName="Bob Lin"`
- And 該 row 含 `requesterHandle="bob"`

**AC-S200-3: detail header 顯示 requesterDisplayName，不顯 requesterId**
- Given frontend 收到 request detail：`requesterId="u_alice"`、`requesterDisplayName="Alice Chen"`、`requesterHandle="alice"`
- When `RequestDetailPage` render
- Then header meta 顯示 `Alice Chen · 2026/5/3`
- And 畫面不包含 `u_alice`

**AC-S200-4: canDelete 仍用 requesterId，不用 display name**
- Given current user `userId="u_aa1111"`
- And request API 回 `requesterDisplayName="Alice Chen"`
- When backend 計算 detail response
- Then `canDelete=true` 的判斷仍是 `current.userId == requesterId`
- And 修改 `requesterDisplayName` 不會影響刪除按鈕是否顯示

**AC-S200-5: display data 缺失時 UI 不 fallback 顯示 `u_<id>`**
- Given frontend 收到 request detail：`requesterId="u_missing"`，但沒有 `requesterDisplayName/requesterHandle`
- When `RequestDetailPage` render
- Then header meta 只顯示日期
- And 畫面不包含 `u_missing`

### NFR Coverage

| Category | Coverage |
|---|---|
| Performance | List endpoint 用 `UserDisplayService.resolveAll(...)` batch 查 requester display，不對每筆 request 做單筆查詢。 |
| Security | Display fields 不參與 authorization；AC-S200-4 證明 delete ownership 仍看 `requesterId`。 |
| Reliability | Display data 缺失時 UI 不顯示 raw id；AC-S200-5 防止 fallback leak。 |
| Usability | User 在 request detail header 看到人名或 handle，不再看到 `u_5dccb3`。 |
| Maintainability | 沿用 S192 `UserDisplayService` 與 frontend `getDisplayName(...)`，不新增第二套 display fallback。 |

## 4. File Plan

| File | Change |
|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java` | `RequestResponse` / `RequestDetailResponse` 增加 requester display fields；list batch resolve requester ids；detail resolve requester id。 |
| `backend/src/test/java/io/github/samzhu/skillshub/community/RequestDetailQueryTest.java` | 補 AC-S200-1 / AC-S200-2 / AC-S200-4。 |
| `frontend/src/api/skills.ts` | `SkillRequest` 增加 `requesterDisplayName?` / `requesterHandle?`。 |
| `frontend/src/pages/RequestDetailPage.tsx` | Header meta 用 `getDisplayName({ author: requesterId, authorDisplayName: requesterDisplayName, authorHandle: requesterHandle })`；空 label 時只顯日期。 |
| `frontend/src/pages/RequestDetailPage.test.tsx` | 補 AC-S200-3 / AC-S200-5，並讓既有 S192 comment test 不被 header raw id 汙染。 |

## 5. Task Boundary Hints

| Task | Scope | BDD anchor |
|---|---|---|
| T01 | Backend request DTO display companion | Alice 開需求後 `GET /requests/{id}` 與 `GET /requests` 都回 `Alice Chen` display 欄位。 |
| T02 | Frontend detail header render + missing display defensive test | Alice 開的需求 header 顯示 `Alice Chen · 日期`，整頁不出現 `u_alice`；display data 缺失時只顯日期，不把 `requesterId` 當 fallback。 |

POC: not required。這是 S192 已驗證過的 display companion pattern 延伸，不新增 schema、library、framework API 或 browser-only 行為。

## 6. Task Plan

POC：not required — S200 是 S192 display companion pattern 的延伸，沿用 `UserDisplayService.resolveAll(...)` 與 frontend `getDisplayName(...)`；不新增 schema、library、framework SPI、外部 API 或 browser-only 行為。Phase 0 pre-flight 已對照 `CONTEXT.md` Platform User ID / User Display Name 規則、S192 shipped findings、目前 request detail/list DTO 與 frontend header render；設計方向仍成立。

E2E：not required for planning — S200 的 user-visible behavior 可由 backend MockMvc JSON contract tests 與 frontend RTL page tests 驗證；沒有新增 route、test seed endpoint、schema migration、credential injection 或真瀏覽器-only workflow。Phase 4 仍需重新評估 source scan 是否需要補充，例如檢查 `RequestDetailPage.tsx` 不再 render `requesterId`。

| 順序 | Task | 主要檔案 | 覆蓋 AC | 驗證方式 | 前置 |
|---:|---|---|---|---|---|
| 1 | [S200-T01 Request API 回 requester display companion](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-18-S200-T01-requester-display-companion-api.md) | `RequestQueryController.java`, `RequestDetailQueryTest.java` | AC-S200-1, AC-S200-2, AC-S200-4 | `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.community.RequestDetailQueryTest` | 無 |
| 2 | [S200-T02 Request detail header 顯示 requesterDisplayName](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-18-S200-T02-request-detail-header-display-name.md) | `skills.ts`, `RequestDetailPage.tsx`, `RequestDetailPage.test.tsx` | AC-S200-3, AC-S200-5 | `cd frontend && npm test -- RequestDetailPage.test.tsx` | T01 |

### AC Coverage

| AC | Task | 可驗證輸出 |
|---|---|---|
| AC-S200-1 | T01 | `GET /api/v1/requests/{id}` JSON 含 `requesterDisplayName` / `requesterHandle`。 |
| AC-S200-2 | T01 | `GET /api/v1/requests` list row 含 requester display companion。 |
| AC-S200-3 | T02 | detail header 顯示 `Alice Chen · 2026/5/3`，整頁不含 `u_alice`。 |
| AC-S200-4 | T01 | `canDelete` 仍由 `requesterId` 比對 current user，display name 不參與權限。 |
| AC-S200-5 | T02 | display data 缺失時 header 只顯示日期，不 fallback raw id。 |

## 7. Results

### 2026-05-19 — S200-T01 backend requester display companion

| Task | 狀態 | 檔案 | 驗證 |
|---|---|---|---|
| S200-T01 | PASS | `RequestQueryController.java`, `RequestDetailQueryTest.java` | `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.community.RequestDetailQueryTest` PASS — 10 tests completed，`BUILD SUCCESSFUL in 2m 13s` |

實作結果：
- `GET /api/v1/requests` list row 現在保留 `requesterId`，並多回 `requesterDisplayName` / `requesterHandle`。
- `GET /api/v1/requests/{id}` detail response 現在保留 `requesterId`，並多回 `requesterDisplayName` / `requesterHandle`。
- `canDelete` 仍用 `users.current().userId().equals(request.getRequesterId())`，不看 display name。

### 2026-05-19 — S200-T02 frontend requester display header

| Task | 狀態 | 檔案 | 驗證 |
|---|---|---|---|
| S200-T02 | PASS | `skills.ts`, `RequestDetailPage.tsx`, `RequestDetailPage.test.tsx` | `cd frontend && npm test -- RequestDetailPage.test.tsx` PASS — 9 tests passed，`Test Files 1 passed` |

實作結果：
- `SkillRequest` type 現在接收 `requesterDisplayName` / `requesterHandle`，對齊 S200-T01 的 API response。
- `RequestDetailPage` header 會用 `getDisplayName({ author: requesterId, authorDisplayName: requesterDisplayName, authorHandle: requesterHandle })` 產生人類可讀 label。
- display label 有值時顯示 `Alice Chen · 2026/5/3`；display data 缺失時只顯示 `2026/5/3`，不把 `u_missing` 放進畫面文字。
- 既有 S192 comment row 測試改用 `document.body.textContent` 檢查 raw id，避免 header 合併文字漏檢。

RED / GREEN：
- RED：`cd frontend && npm test -- RequestDetailPage.test.tsx` failed — page text still contained `u_alice`; missing display data rendered `u_missing · 2026/5/3`。
- GREEN：same command PASS — 9 tests passed，`Test Files 1 passed`。

下一步：
- S200-T01 / S200-T02 都是 PASS；local implementation done，已進入 `$verifying-quality S200`。

### 2026-05-19 — QA Review / local release evidence

| 檢查 | 結果 | 證據 |
|---|---|---|
| S200 backend command | PASS | `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.community.RequestDetailQueryTest` -> `BUILD SUCCESSFUL in 2m 13s`。 |
| S200 frontend command | PASS | `cd frontend && npm test -- RequestDetailPage.test.tsx` -> 9 tests passed，`Test Files 1 passed`。 |
| Repo full local check | PASS | `./scripts/verify-all.sh` -> `Results: V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS`；`Verdict: ✅ all CRITICAL passed; exit=0`。 |

AC evidence:

| AC | 結果 | 證據 |
|---|---|---|
| AC-S200-1 | PASS | `RequestDetailQueryTest` 的 `@Tag("AC-S200-1")` 檢查 detail JSON 回 `requesterDisplayName` / `requesterHandle`。 |
| AC-S200-2 | PASS | `RequestDetailQueryTest` 的 `@Tag("AC-S200-2")` 檢查 list JSON row 回 requester display companion。 |
| AC-S200-3 | PASS | `RequestDetailPage.test.tsx` 的 `AC-S200-3` 檢查 header 顯示 `Alice Chen · 2026/5/3` 且畫面不含 `u_alice`。 |
| AC-S200-4 | PASS | `RequestDetailQueryTest` 的 `@Tag("AC-S200-4")` 檢查 `canDelete` 仍由 `requesterId` 比對 current user。 |
| AC-S200-5 | PASS | `RequestDetailPage.test.tsx` 的 `AC-S200-5` 檢查 display data 缺失時只顯日期且畫面不含 `u_missing`。 |

Source scan:
- `rg "@Tag(\"AC-S200|AC-S200-" ...` 找到 S200 後端與前端驗收標籤。
- `RequestDetailPage.tsx` header 現在用 `getDisplayName(...)` 產生 requester label；`requesterId` 只作為 helper input 與權限/API id，不直接當畫面標題文字。

Manual browser step: not required。S200 沒新增 route、test seed endpoint、schema migration、credential injection 或真瀏覽器-only workflow；API JSON 與 React DOM text 已由上方 command 驗證，`./scripts/verify-all.sh` 的 V07 也已 PASS。

Verdict: PASS — local release PASS；下一步 `$shipping-release S200`。
