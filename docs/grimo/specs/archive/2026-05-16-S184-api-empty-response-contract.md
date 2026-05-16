# S184 — API Empty Response + Visibility Command Contract

> SpecID: S184
> Status: ✅ Done — shipped locally 2026-05-16
> Date: 2026-05-16
> Size: S(7)
> Related: S160b' frontend `apiFetch`, S163/S163b' visibility toggle, S162 API response consistency

---

## 1. Goal

`frontend/src/api/grants.ts:45` 的 `revokeGrant()` 會打 `DELETE /api/v1/skills/{id}/grants/{grantId}`；production Cloud Run log 在 `2026-05-15T22:30:36Z` 顯示第一次 DELETE 回 `202` 並寫出 `Skill grant revoked`，但瀏覽器畫面沒有刷新，使用者接著連續重按同一個 grant id，後端因此回多次 `404`。

這不是後端沒有把 skill 轉私人；後端第一次已刪掉 public grant。真正問題是前端 `apiFetch<void>()` 對成功但沒有 body 的 response 仍呼叫 `res.json()`，使 mutation promise 進 error path，`VisibilityToggleButton` 的 `onSuccess` 沒有 invalidate grants / skill query。

S184 要處理兩層問題：

1. API client contract：把「成功但沒有 response body」變成明確契約，避免同類 bug 在通知、評論、旗標、collection、skill delete/update 等 mutation 重複發生。
2. Visibility command design：讓「轉為私人 / 公開分享」不要再由 UI 操作底層 public grant id。畫面只送目標狀態，後端用既有 Public Grant path 統一更新 `skills.is_public`；Public Grant 不會展開成 ACL entry。

相依狀態：

| Spec | 狀態 | 關係 |
| --- | --- | --- |
| S160b' | ✅ shipped | `frontend/src/api/client.ts` 已有 `apiFetchVoid()`，但 caller 仍可誤用 `apiFetch<void>()`。 |
| S163b' | ✅ shipped | visibility toggle 是本次 production trigger。 |
| S162b | ⏸ deferred | 401/403 error shape 不在本 spec 內；S184 只處理 2xx empty body 與 visibility command。 |

Spec overlap scan：active specs S178/S179/S181/S183 沒有處理 frontend API empty-body contract 或 explicit visibility command；S162b 是 SecurityFilterChain 401/403 JSON shape，與 S184 重疊低於 50%。

Compatibility constraint：S184 可以同時修改 frontend / backend / DTO；不需要維持舊 ShareModal public target 的相容行為。

Roadmap note：目前 `docs/grimo/specs/spec-roadmap.md` 已有未提交變動；本 spec 草案暫不改 roadmap，避免混入 unrelated diff。

---

## 2. Research And Design

### 2.1 Pre-implementation problem facts

This table records the code facts found before S184 implementation. The final implementation result is recorded in §7.

| File | 查到什麼 | 影響 |
| --- | --- | --- |
| `frontend/src/api/client.ts:99-115` | `apiFetch<T>()` 對任何 `res.ok` 都直接 `return res.json()`。 | `T = void` 只是 TypeScript 標註，runtime 還是 parse JSON。 |
| `frontend/src/api/client.ts:117-126` | `apiFetchVoid()` 成功時不 parse body，只在非 2xx parse error body。 | 這是現有正確工具，但需要讓 void endpoint 都用它。 |
| `frontend/src/api/grants.ts:45` | `revokeGrant()` 原本使用 `apiFetch<void>()`；本次 hotfix 已改 `apiFetchVoid()`。 | 只修了轉私人；同類 caller 還要掃完。 |
| `frontend/src/api/notifications.ts:59-68` | mark read / mark all read / delete notification 使用 `apiFetch<void>()`。 | 後端回 204；目前有同家族風險。 |
| `frontend/src/api/reviews.ts:36-37` | delete review 使用 `apiFetch<void>()`。 | 後端回 204；目前有同家族風險。 |
| `frontend/src/api/flags.ts:69-78` | update flag status 使用 `apiFetch<void>()`。 | 後端回 204；目前有同家族風險。 |
| `frontend/src/components/VisibilityToggleButton.tsx:20-49` | 先 `GET /grants` 找 `principalType='public' && principalId='*'` 的 grant id，再 `DELETE /grants/{grantId}` 或 `POST /grants`。 | UI cache stale 時會重送已刪除 grant id；visibility UI 直接依賴 DB row id。 |
| `frontend/src/components/VisibilityToggleButton.tsx:28-32` | invalidate `['skill-grants', skillId]`、`['skill', skillId]`、`['skills']`；但 `useSkill()` query key 是 `['skills', id]`。 | `['skill', skillId]` 單數 key 不命中；需要 query key helper 防 typo。 |
| `backend/src/main/java/.../NotificationController.java:67-84` | 三個 notification mutation 回 `204 No Content`。 | 前端必須用 void client。 |
| `backend/src/main/java/.../ReviewController.java:56-62` | delete review 回 `204 No Content`。 | 前端必須用 void client。 |
| `backend/src/main/java/.../FlagController.java:77-84` | update flag status 回 `204 No Content`。 | 前端必須用 void client。 |
| `backend/src/main/java/.../SkillGrantController.java:58-64` | revoke grant 回 `202 Accepted` 空 body。 | 既然 service 已同步刪除 row，狀態碼應改成 `204 No Content` 或前端必須把 202 empty 明確當成功。 |
| `backend/src/main/java/.../SkillCommandService.java:319-323` | PUBLIC skill 建立時 seed `skill_grants(principal_type='public', principal_id='*', role='VIEWER')`。 | Public Grant 是現有 unified Grant event path；它不等於 ACL entry。 |
| `backend/src/main/java/.../SkillGrantService.java:111-133` | `GrantRequest("public","*",VIEWER)` 會寫 public grant，並呼叫 `skill.makePublic(...)`。 | S184 後外部 grant API 不再接受 public；Public Grant 僅能由 visibility command 內部 helper 管理。 |
| `backend/src/main/java/.../SkillGrantService.java:167-170` | revoke public grant 會呼叫 `skill.makePrivate(...)`。 | 這是現有 Public Grant revoke path；問題是 UI 重複 DELETE 同一 row id 會得到 404。 |
| `backend/src/main/java/.../SkillAclProjectionListener.java:120` / `SkillGrantService.java:224` | rebuild ACL 時過濾 `principalType='public'`，不把 public grant 展開成 `acl_entries`。 | 「read permission 不靠 public:*」已成立；剩下的是 visibility mirror / UI 操作殘留。 |
| `backend/src/main/java/.../Skill.java:130-540` | `publicSkill` 是 `skills.is_public` source-of-truth，但 getter `isPublic()` 被 `@JsonIgnore`，detail JSON 不直接告訴 frontend 目前 visibility。 | S184 要 expose display-safe `visibility`，PageHeader 不再查 grants 判斷 public/private。 |

### 2.2 Official references

| Source | Summary | Decision |
| --- | --- | --- |
| [MDN `Response.json()`](https://developer.mozilla.org/en-US/docs/Web/API/Response/json) | `json()` 會讀完整 body 並 parse JSON；body 不能 parse 時會丟 `SyntaxError`。 | 前端不應對 empty success response 呼叫 `json()`。 |
| [RFC 9110 §15.3.3 202 Accepted](https://httpwg.org/specs/rfc9110.html#status.202) | 202 表示 request 已接受但處理尚未完成。 | `SkillGrantService.revoke()` 已同步刪 row；DELETE 回 202 語意不精準。 |
| [RFC 9110 §15.3.5 204 No Content](https://httpwg.org/specs/rfc9110.html#status.204) | 204 表示 request 成功，且不會送 response content。 | 同步完成且無 body 的 DELETE / PATCH / mark-read 應回 204。 |
| [TanStack Query — Invalidations from Mutations](https://tanstack.com/query/v5/docs/framework/react/guides/invalidations-from-mutations) | mutation 成功後用 `onSuccess` + `invalidateQueries` 更新相關 query。 | `VisibilityToggleButton` 的 invalidate 位置是對的；問題是 mutationFn 不該把成功 response 變 error。 |

### 2.3 Optimized Design

採兩段式設計，第一段消除同家族 bug，第二段把 visibility 從 grant CRUD 中抽出成穩定 command。

#### Phase A — Empty response contract

1. Frontend void mutation 必須使用 `apiFetchVoid()`。
2. 新增測試或靜態檢查，禁止 production code 出現 `apiFetch<void>(...)`。
3. Backend 同步完成且無 body 的 endpoint 回 `204 No Content`；`SkillGrantController.revoke()` 從 `202` 改 `204`。
4. `apiFetch<T>()` 保持嚴格：JSON endpoint 沒送 JSON 就丟錯，避免把真正壞掉的 JSON API 靜默吞掉。

不建議把 `apiFetch<T>()` 改成「只要 body 空就回 undefined」作為主修，因為 `fetchSkillById()`、`fetchReviews()`、`createGrant()` 這些 JSON contract 如果某天後端空回應，前端應該立刻爆錯，而不是 UI 用 `undefined` 繼續跑。

#### Phase B — Dedicated visibility command（Public Grant remains non-ACL）

新增 command endpoint：

```http
PUT /api/v1/skills/{id}/visibility
Content-Type: application/json

{"visibility":"PRIVATE"}
```

成功 response：

```json
{
  "skillId": "028cecf1-3326-4327-bbe9-28b4e6fab6d5",
  "visibility": "PRIVATE",
  "updatedAt": "2026-05-16T06:40:00Z"
}
```

行為：

| Request | DB 目前狀態 | 後端行為 | HTTP |
| --- | --- | --- | --- |
| `PRIVATE` | `skills.is_public=true` | 設 `skills.is_public=false`；內部同步 Public Grant 狀態；重建 projection | 200 + JSON |
| `PRIVATE` | `skills.is_public=false` | 冪等 no-op，只回目前狀態 | 200 + JSON |
| `PUBLIC` | `skills.is_public=false` | 設 `skills.is_public=true`；內部同步 Public Grant 狀態；重建 projection | 200 + JSON |
| `PUBLIC` | `skills.is_public=true` | 冪等 no-op，只回目前狀態 | 200 + JSON |

後端仍保留 `/grants` 作為分享人員管理 API。PageHeader 的 visibility button 不再直接使用 `/grants/{grantId}`，而是呼叫 visibility command；command 內部可沿用 `SkillGrantService.grant/revoke` 的 Public Grant path。Public Grant 不進 `acl_entries`，公開讀取仍由 `skills.is_public` / `vector_store.is_public` 判斷。ShareModal 只管理 user/group/company Share Targets，不再提供 public target。

Visibility command 的冪等判斷只看 `skills.is_public`。Public Grant row 若缺失或殘留，屬內部同步細節；不影響 command 成功與否，也不讓 UI 看到 404。

External grant API contract after S184:

```json
{
  "principalType": "user | group | company",
  "principalId": "u_alice0",
  "role": "VIEWER | EDITOR"
}
```

`principalType:"public"` is rejected with HTTP 400 and an error message that tells the caller to use `PUT /api/v1/skills/{id}/visibility`.

Visibility command authorization:

- Controller requires authenticated user.
- Service enforces `actor == skill.ownerId`, same owner-only rule as grant management.
- Frontend shows the visibility button when `viewerPermissions.canShare === true`.
- No new raw ACL verb such as `visibility` is introduced.

Skill detail response 直接 expose display-safe `visibility`：

```json
{
  "id": "028cecf1-3326-4327-bbe9-28b4e6fab6d5",
  "name": "字幕",
  "visibility": "PUBLIC",
  "viewerPermissions": {
    "canShare": true
  }
}
```

Frontend PageHeader 用 `skill.visibility` 決定按鈕文字，不再為 visibility label 打 `/grants`。

#### Phase C — Query key helper

新增前端 query key helper，避免 `['skill', id]` / `['skills', id]` 這種 typo：

```ts
export const skillKeys = {
  all: ['skills'] as const,
  detail: (id: string) => ['skills', id] as const,
  grants: (id: string) => ['grants', id] as const,
}
```

`useSkill()`、`VisibilityToggleButton`、`ShareModal` 相關 invalidation 改用 helper。visibility mutation 成功後：

```ts
queryClient.setQueryData(skillKeys.detail(skillId), (old) =>
  old ? { ...old, visibility: response.visibility } : old
)
queryClient.invalidateQueries({ queryKey: skillKeys.all })
queryClient.invalidateQueries({ queryKey: skillKeys.grants(skillId) })
```

### 2.4 UI Sketch

```text
Skill detail PageHeader（owner）

┌──────────────────────────────────────────────────────────────┐
│ [icon] 字幕  v1.0.0  LOW                                     │
│       作者：朱尚禮 · 更新於 今天 · Documentation              │
│                                                              │
│                                      [編輯] [轉為私人] [分享] │
└──────────────────────────────────────────────────────────────┘

Button state source:
- skill.visibility === "PUBLIC"  → 顯示「轉為私人」
- skill.visibility === "PRIVATE" → 顯示「公開分享」

Click:
- 轉為私人 → PUT /skills/{id}/visibility {"visibility":"PRIVATE"}
- 公開分享 → PUT /skills/{id}/visibility {"visibility":"PUBLIC"}

Pending:
- button disabled，文字「處理中...」

Success:
- response.visibility 直接更新 detail cache
- background invalidate skills list + grants list

Error:
- 保留原狀態，不改 cache
- toast 顯示 localizeApiError(err)
```

### 2.5 Alternatives

| Option | 改哪裡 | 實際行為 | 成本 / 風險 |
| --- | --- | --- | --- |
| A. 只修 `revokeGrant()` | `frontend/src/api/grants.ts:45` 改 `apiFetchVoid()` | 轉私人按鈕正常刷新 | 已完成 hotfix；但 notifications/reviews/flags 同類洞還在。 |
| B. `apiFetch<T>()` 自動接受 empty body | `frontend/src/api/client.ts:99-115` 判斷 204/205/empty text | 所有 empty success 都不炸 | 低工時；但 JSON endpoint 壞掉可能被吞，debug 變慢。 |
| C. Void endpoint 全改 `apiFetchVoid()` + guard | 多個 `frontend/src/api/*.ts` caller + 新測試/檢查 | 空回應 mutation 正常；JSON endpoint 仍嚴格 | 必做；工時小，風險低。 |
| D. 新增 dedicated visibility endpoint | 後端加 `PUT /skills/{id}/visibility`，前端不再操作 public grant id；後端保留 Public Grant 作為統一事件 path | 「轉私人」變 idempotent：已私人再按也回成功 | 推薦納入 S184；比 C 多 1 個 backend endpoint + 1 個 frontend API，但能消掉根本 UX 脆弱點。 |
| E. 只把 DELETE missing public grant 改成 204 | `SkillGrantService.revoke()` 對 public grant missing 特判 | 重複 DELETE 不報錯 | 不推薦：會把一般 grant id 打錯也吞掉，權限管理 debug 變慢。 |

---

## 3. Acceptance Criteria

### AC-S184-1 — 轉私人空回應視為成功

Given（前提）後端 `DELETE /api/v1/skills/skill-1/grants/public-grant-1` 回 204 或 202 且 body 為空

When（動作）前端呼叫 `revokeGrant("skill-1", "public-grant-1")`

Then（結果）promise resolve `undefined`

And 不呼叫 `Response.json()`

And PageHeader visibility button 不再呼叫 `revokeGrant()`；visibility UI 成功路徑由 AC-S184-7/8 的 `PUT /visibility` 覆蓋

### AC-S184-2 — 所有 204 mutation caller 使用 void client

Given（前提）`notifications`、`reviews`、`flags`、`skills`、`collections` 等 API client 內有回 `Promise<void>` 的 function

When（動作）跑 frontend test 或 static guard

Then（結果）production `frontend/src/**/*.ts` 不允許出現 `apiFetch<void>(...)`

And `apiFetchVoid(...)` 是 void mutation 的唯一合法 client helper

### AC-S184-3 — JSON endpoint 仍嚴格 parse JSON

Given（前提）`createGrant()` 或 `fetchSkillById()` 這類 JSON endpoint 回 2xx 但 body 為空

When（動作）前端呼叫對應 API client

Then（結果）promise reject

And error 不被誤當成功

### AC-S184-4 — 同步 DELETE 無 body 回 204

Given（前提）owner 對 public skill 呼叫 `DELETE /api/v1/skills/{id}/grants/{publicGrantId}`

When（動作）後端已同步刪除 `skill_grants.public:*` row 並更新 `skills.is_public=false`

Then（結果）HTTP status 為 `204 No Content`

And Cloud Run log 不再出現第一次成功後因前端誤判造成的同 grant id 重複 DELETE 404 burst

### AC-S184-5 — Visibility command idempotent

Given（前提）skill `s1` 目前已經是 PRIVATE

When（動作）owner 連續兩次呼叫 `PUT /api/v1/skills/s1/visibility` body `{"visibility":"PRIVATE"}`

Then（結果）兩次都回 200

And response body 都是 `{"skillId":"s1","visibility":"PRIVATE",...}`

And `skill_grants` 不新增第二筆 Public Grant

And `skills.is_public=false`

### AC-S184-6 — Visibility command idempotency uses is_public

Given（前提）skill `s1` 目前 `skills.is_public=false`

When（動作）owner 呼叫 `PUT /api/v1/skills/s1/visibility` body `{"visibility":"PRIVATE"}`

Then（結果）HTTP 200

And `skills.is_public=false`

And 不因 Public Grant row 缺失或殘留而回 404

Given（前提）skill `s2` 目前 `skills.is_public=true`

When（動作）owner 呼叫 `PUT /api/v1/skills/s2/visibility` body `{"visibility":"PUBLIC"}`

Then（結果）HTTP 200

And `skills.is_public=true`

And 不因 Public Grant row 缺失或殘留而新增重複 row 或回 404

### AC-S184-7 — PageHeader 不再用 grants query 判斷 public/private

Given（前提）owner 打開 skill detail

When（動作）後端 `GET /api/v1/skills/{id}` 回 `visibility:"PUBLIC"`

Then（結果）PageHeader 顯示「轉為私人」

And Network 不需要先打 `GET /api/v1/skills/{id}/grants` 才能決定按鈕文字

And 點「轉為私人」送 `PUT /api/v1/skills/{id}/visibility` body `{"visibility":"PRIVATE"}`

### AC-S184-8 — Query key helper 防止 stale UI

Given（前提）`useSkill()` 使用 `skillKeys.detail(id)`

When（動作）visibility mutation 成功

Then（結果）detail cache 的 `visibility` 立即更新

And `skillKeys.all` 與 `skillKeys.grants(id)` 被 invalidate

And source code 不再出現裸寫 `['skill', skillId]`

### AC-S184-9 — Public cannot be created through grants API

Given（前提）owner 呼叫 `POST /api/v1/skills/s1/grants`

When（動作）body 為 `{"principalType":"public","principalId":"*","role":"VIEWER"}`

Then（結果）HTTP 400

And response message 提示使用 `PUT /api/v1/skills/s1/visibility`

And `skill_grants` 不新增 public row

### AC-S184-10 — Visibility command is owner/share managed

Given（前提）viewerPermissions.canShare=false 的使用者打開 skill detail

When（動作）頁面渲染 PageHeader

Then（結果）不顯示 visibility button

Given（前提）非 owner 直接呼叫 `PUT /api/v1/skills/s1/visibility`

When（動作）body 為 `{"visibility":"PRIVATE"}`

Then（結果）HTTP 403 `NOT_SKILL_OWNER`

And 不新增 `visibility` raw ACL verb

### AC-S184-11 — Legacy public ACL wording cleaned outside migration evidence

Given（前提）production docs、frontend fixtures、新增/修改的 tests 會提到公開可見性

When（動作）S184 實作完成

Then（結果）這些檔案不用 `*:read` 或 `public:*:read` 表示公開 skill

And migration/backfill tests 可保留 `*:read` / `public:*:read` 作為 legacy data evidence

And legacy tests 的註解必須明確說明這是歷史 ACL 格式，不是現行 Public Visibility 模型

---

## 4. File Plan

| File | Change |
| --- | --- |
| `frontend/src/api/notifications.ts` | import `apiFetchVoid`；3 個 void mutation 改用它。 |
| `frontend/src/api/reviews.ts` | delete review 改用 `apiFetchVoid`。 |
| `frontend/src/api/flags.ts` | update flag status 改用 `apiFetchVoid`。 |
| `frontend/src/api/client.test.ts` or new guard test | 加一個掃 source text 的 test，禁止 `apiFetch<void>(`。 |
| `frontend/src/api/grants.test.ts` | 保留本次 hotfix regression：202/204 empty response 不 parse JSON。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java` | `revoke()` 回 `ResponseEntity.noContent().build()`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantControllerAuthzTest.java` | 補 owner DELETE `/grants/{grantId}` 回 204 的 WebMvc slice test。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | 新增 `PUT /api/v1/skills/{id}/visibility`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java` | 新增 `setVisibility(skillId, Visibility)` 或拆出 `SkillVisibilityService`；實作 idempotent + self-heal。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | Detail JSON expose display-safe `visibility` getter；`publicSkill` 仍 `@JsonIgnore`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantServiceVisibilityTest.java` | AC-S184-5/6/9/10：idempotent + public grant mirror + owner guard + explicit grants preserved。 |
| `frontend/src/api/skills.ts` | 新增 `setSkillVisibility(skillId, visibility)`。 |
| `frontend/src/types/skill.ts` | `Skill.visibility: 'PUBLIC' | 'PRIVATE'`。 |
| `frontend/src/api/queryKeys.ts` | 新增 `skillKeys` helper。 |
| `frontend/src/components/VisibilityToggleButton.tsx` | 改讀 `skill.visibility` 或 prop；mutation 改打 visibility endpoint；不再 fetch grants 判斷狀態。 |
| `frontend/src/components/VisibilityToggleButton.test.tsx` | 更新為 visibility endpoint 行為測試；保留 pending / error 狀態。 |
| `frontend/src/components/ShareModal.tsx` | 移除 public target；ShareModal 只管理 user/group/company Share Targets。 |
| `frontend/src/api/grants.ts` | `CreateGrantRequest.principalType` 移除 `public`，讓 frontend DTO 無法送 public grant。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java` | 新增 idempotent visibility helper；冪等判斷看 `skills.is_public`，Public Grant 同步是內部細節。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java` | `POST /grants` 拒絕 `principalType=public`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantControllerAuthzTest.java` | 補 public grant API 400 測試。 |
| `frontend/src/pages/HomePage.test.tsx` and touched frontend fixtures | 移除 `aclEntries: ['*:read']` 這類舊公開 ACL fixture。 |
| Migration/backfill tests containing `public:*:read` | 保留 legacy evidence，但補註解說明不是現行模型。 |

---

## 5. Verification Plan

| Command | 會看到什麼 |
| --- | --- |
| `cd frontend && npm test -- grants.test.ts client.test.ts` | `revokeGrant()` 空回應測試綠；`apiFetch<void>` guard 綠。 |
| `cd frontend && npm test -- notifications reviews flags VisibilityToggleButton.test.tsx` | 相關 mutation 不 parse 空 body；PageHeader visibility button 改打新 endpoint。 |
| `cd frontend && npm run typecheck` | TypeScript import / return type 綠。 |
| `cd backend && ./gradlew test --tests '*SkillGrantControllerAuthzTest' --tests '*SkillGrantServiceVisibilityTest' --tests '*SkillUpdateControllerTest'` | revoke grant status 改 204；visibility command idempotent / owner guard 綠。 |
| Production gcloud query | 點一次「轉為私人」後只看到一筆 PUT `/visibility` 200；不再連續出現同 grant id 的 DELETE 404。 |

Recommendation：S184 一次做 C + D，且 D 採「Public Grant remains non-ACL」版本。C 修掉 API client 家族洞；D 消掉「visibility button 操作 grant id」的根本脆弱點。範圍仍是小型：不改 ACL permission model、不改 search visibility SQL、不改 Public Grant 的統一 event path。

---

## 6. Task Plan

POC：不需要。S184 使用既有 `apiFetchVoid()`、Spring MVC controller、`SkillGrantService`、React Query mutation；沒有新增 SDK / framework。

| Task | Scope | Acceptance |
| --- | --- | --- |
| T01 | Frontend empty-response contract | `revokeGrant()`、notifications、reviews、flags 等 void mutation 全改 `apiFetchVoid()`；guard test 禁止 `apiFetch<void>(...)`。 |
| T02 | Backend grant cleanup | `DELETE /grants/{grantId}` 回 204；`POST /grants` 拒絕 `principalType=public`。 |
| T03 | Backend visibility command | 新增 `PUT /skills/{id}/visibility`；service 以 `skills.is_public` 判斷冪等，內部同步 Public Grant，不把 public 寫入 ACL。 |
| T04 | Skill detail visibility DTO | `GET /skills/{id}` expose `visibility:"PUBLIC"|"PRIVATE"`；frontend `Skill` type 接上。 |
| T05 | Frontend visibility UI | PageHeader 把 `skill.visibility` 傳給 `VisibilityToggleButton`；button 改打 visibility command，不再 fetch grants 找 public grant id。 |
| T06 | Share target cleanup | ShareModal / CreateGrantRequest 移除 public target，只保留 user/group/company；public grant row 只作為內部可見性 mirror。 |
| T07 | Verification + spec result | 跑 frontend/backend focused tests 與 typecheck；把結果寫回 §7。 |

---

## 7. Implementation Result

Implemented on 2026-05-16.

| Area | Result |
| --- | --- |
| Empty response client | `revokeGrant()`、notifications、reviews、flags、request delete/comment delete 皆使用 `apiFetchVoid()`；`client.test.ts` 會掃 `frontend/src` production code，禁止 `apiFetch<void>(...)`。 |
| Backend grants API | `DELETE /api/v1/skills/{id}/grants/{grantId}` 改回 `204 No Content`；`POST /grants` 收到 `principalType:"public"` 會回 400，訊息指向 `PUT /visibility`。 |
| Visibility command | 新增 `PUT /api/v1/skills/{id}/visibility`；`SkillGrantService.setVisibility(...)` 用 `skills.is_public` 判斷冪等，轉換時才同步 Public Grant mirror 並發既有 `SkillGrantedEvent` / `SkillRevokedEvent`。 |
| Skill detail DTO | `Skill.getVisibility()` expose `PUBLIC` / `PRIVATE`；`isPublic()` 保持 `@JsonIgnore`，避免前端依賴 raw boolean。 |
| Frontend visibility UI | `PageHeader` 傳 `skill.visibility` 給 `VisibilityToggleButton`；button 不再打 `/grants` 查 public grant id，點擊改打 `setSkillVisibility()`。 |
| Share UI | `ShareModal` / `CreateGrantRequest` 只支援 user/group/company；public grant row 若出現在 list response 會被 UI 過濾，不當成分享對象。 |
| Query keys | 新增 `skillKeys`，修掉 `['skill', id]` / `['skills', id]` typo 風險。 |

Verification:

| Command | Result |
| --- | --- |
| `cd frontend && npm test -- grants.test.ts client.test.ts VisibilityToggleButton.test.tsx ShareModal.test.tsx` | PASS — 4 files / 25 tests。 |
| `cd frontend && npm run typecheck` | PASS。 |
| `cd backend && ./gradlew test --tests '*SkillGrantControllerAuthzTest' --tests '*SkillGrantServiceVisibilityTest' --tests '*SkillUpdateControllerTest'` | PASS。 |
| `cd backend && ./gradlew test --tests '*SkillGrantServiceVisibilityTest'` | PASS — non-owner visibility guard 補測後重跑。 |

Code split progress:

| Date | Slice | Evidence |
| --- | --- | --- |
| 2026-05-16 | Frontend empty-response API callers: grants, notifications, reviews, flags | `cd frontend && npm test -- grants.test.ts client.test.ts` PASS — 2 files / 15 tests. |
| 2026-05-16 | Backend grants + visibility command: DELETE grants 204, POST public grants 400, PUT `/skills/{id}/visibility`, `Skill.visibility` DTO | `cd backend && ./gradlew test --tests '*SkillGrantControllerAuthzTest' --tests '*SkillGrantServiceVisibilityTest' --tests '*SkillUpdateControllerTest' --tests '*SkillCommandControllerSecurityTest' --tests '*SkillPublishForgeryTest' --tests '*SkillSuspendControllerSecurityTest' --tests '*SkillUploadAuthTest'` PASS. |
| 2026-05-16 | Frontend visibility + share contract: PageHeader uses `skill.visibility`, visibility button calls `PUT /visibility`, ShareModal hides public mirror grants, query keys use `skillKeys` | `cd frontend && npm test -- grants.test.ts client.test.ts VisibilityToggleButton.test.tsx ShareModal.test.tsx` PASS — 4 files / 25 tests；`cd frontend && npm run typecheck` PASS. |

Backend contract cleanup:

| Date | Trigger | Result |
| --- | --- | --- |
| 2026-05-16 | S183 shipping preflight `./scripts/verify-all.sh` failed because two legacy backend tests still asserted pre-S184 behavior: DELETE grant expected `202 Accepted`; external public grant expected a saved `public/*` row. | Updated `S016EndToEndSmokeTest` to expect `204 No Content` from `DELETE /api/v1/skills/{id}/grants/{grantId}`. Updated `SkillGrantServiceTest` to assert `principalType="public"` is rejected and points callers to `PUT /api/v1/skills/{id}/visibility`. |

Verification:

| Command | Result |
| --- | --- |
| `cd backend && ./gradlew test --tests '*S016EndToEndSmokeTest' --tests '*SkillGrantServiceTest'` | PASS — `S016EndToEndSmokeTest` 9 tests / 0 failures; `SkillGrantServiceTest` 11 tests / 0 failures. |

E2E decision:

Browser E2E not required for S184. This change touches API client empty-response handling, backend controller/service contracts, and React Query mutation cache updates. The behavior is covered by focused frontend API/component tests and backend controller/service tests. Production follow-up after deploy is the gcloud log query in §5: one click on 「轉為私人」 should show one `PUT /visibility` 200 and no repeated DELETE 404 burst for the same grant id.

Independent QA review:

| Item | Result |
| --- | --- |
| Reviewer | Curie subagent, 2026-05-16 |
| Verdict | PASS |
| Finding | LOW-DOC only: §2.1 previously used the title `Current code facts` while describing pre-implementation facts. Fixed by renaming it to `Pre-implementation problem facts` and pointing readers to §7 for final code state. |
| Evidence | Curie verified `revokeGrant()` uses `apiFetchVoid()`, production code has no `apiFetch<void>(...)`, `DELETE /grants/{grantId}` returns 204, public grants API rejects `principalType=public`, visibility command idempotency reads `skills.is_public`, Public Grant is filtered out of ACL projections, `VisibilityToggleButton` calls `setSkillVisibility()`, `Skill.getVisibility()` exposes `PUBLIC` / `PRIVATE`, and `ShareModal` only offers user/group/company targets. |
| Commands | `cd frontend && npm test -- grants.test.ts client.test.ts VisibilityToggleButton.test.tsx ShareModal.test.tsx` PASS; `cd frontend && npm run typecheck` PASS; `cd backend && ./gradlew test --tests '*SkillGrantControllerAuthzTest' --tests '*SkillGrantServiceVisibilityTest' --tests '*SkillUpdateControllerTest' -x processTestAot -x compileAotTestJava -x processAotTestResources` PASS. |

Release verification:

| Command | Result |
| --- | --- |
| `./scripts/verify-all.sh` | PASS — V01=PASS, V02=INFO, V03=PASS, V04=PASS, V05=PASS, V06=PASS, V07=PASS, V08a=PASS, V08b=PASS; `Verdict: ✅ all CRITICAL passed; exit=0`. |

Production follow-up:

| Item | Status |
| --- | --- |
| Cloud Run deploy | PASS — Cloud Build `a70196af-93ee-471d-9741-21dcb6cc4b79` pushed `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260516-031017`; Cloud Run revision `skillshub-00032-9v8` became Ready and serves 100% traffic. |
| Production smoke | PASS — `GET /`, `GET /actuator/health/readiness`, and `GET /api/v1/skills?page=0&size=5` returned 200; skill list response includes `visibility`. Latest revision `severity>=ERROR` log query returned no rows. |
| Visibility unauth route check | INFO — 2026-05-16T03:23Z unauthenticated `PUT /api/v1/skills/8ee45695-c16e-4586-9869-9fdbe110ca88/visibility` with `{"visibility":"PRIVATE"}` returned 401 JSON: `{"error":"UNAUTHORIZED","message":"Authentication required",...}`. Cloud Run logged the same request on trace `909402df4adefdd36d0e579b9bdb0910`; follow-up `GET /api/v1/skills?page=0&size=5` still returned `visibility:"PRIVATE"`. |
| DELETE grant burst watch | PASS — Cloud Run latest revision `skillshub-00032-9v8` logs from 2026-05-16T03:10Z returned no `DELETE ... /grants/` rows, and `severity>=ERROR` returned no rows. Cloud Build error log query for build `a70196af-93ee-471d-9741-21dcb6cc4b79` also returned no rows. |
| Browser visibility retest | PENDING — Chrome plugin is not callable in this tick, and unauthenticated curl cannot perform the owner UI action. Next manual/browser retest should click 「轉為私人」 once while logged in and confirm Cloud Run logs show one `PUT /api/v1/skills/{id}/visibility` 200 and no repeated `DELETE /grants/{grantId}` 404 burst for the same grant id. |

### Final Size Re-score

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 2 | Needed backend controller/service contract cleanup plus frontend cache/query-key changes, but no new dependency. |
| Uncertainty | 1 | 1 | Root cause and response contracts were verified by local tests and production log evidence from the original bug. |
| Dependencies | 1 | 2 | Consumed S160b' `apiFetchVoid`, S163 visibility UI, S177 public visibility model, and S169 grants contract. |
| Scope | 2 | 3 | Touched frontend API clients, visibility UI, backend grants API, visibility command, DTOs, and stale backend tests. |
| Testing | 1 | 2 | Required focused frontend/backend tests plus full `verify-all.sh` because S183 ship gate exposed stale backend expectations. |
| Reversibility | 1 | 1 | Endpoint and frontend changes are localized; no schema migration. |
| **Total** | **7 / XS** | **11 / S** | Bucket shift XS→S because the fix became a cross-stack API contract cleanup, not only a void-response caller swap. |
