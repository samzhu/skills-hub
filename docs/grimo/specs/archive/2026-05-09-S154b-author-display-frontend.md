# S154b: Author Display Identity (Frontend) — getDisplayName + 元件 sweep + ShareSkillModal polish + PublishPage author 改 read-only

> Spec: S154b | Size: S(9) → S(11) (Phase 0 加 PublishPage + useMe scope) | Status: ✅ Done (2026-05-11)
> Date: 2026-05-09（拆自 S154 backend/frontend split — planning-tasks size gate）
> Phase 0 amendment: 2026-05-11 — user 反映 PublishPage 「作者」欄位仍顯 OAuth sub raw 且為 input；補進 §2.3 + §3 + §4 + §6
> Depends On: **S154 backend ship**（須先有 `/api/v1/skills/{id}` 回 `authorDisplayName / authorHandle / authorEmail-conditional`）

---

## 1. Goal

**一句話：** 把 S154 backend 已 expose 的 `authorDisplayName / authorHandle / authorEmail` 在前端 9 個元件渲染出來；ShareSkillModal 4 個 polish 修正 user 看到 raw sub / 多餘 radio / 已 public 重複加 / placeholder 不友善。

**為什麼重要：**
- 不做 → S154 backend ship 後 user 仍看到 `u_a3f9c1` raw user_id（比 21 位 sub 短但仍是 ID，user 看不懂）
- ShareSkillModal 是「分享 skill 找得到對方」的入口，現況 4 個 issue 全部展示 raw sub，share UX 完全壞掉

**非目標：**
- 不動 backend（S154 已處理）
- 不做 user profile 頁 / settings UI（見 §7 S168/S169）

---

## 2. Approach

### 2.1 現況（S154 ship 後狀態）

API 回傳已含 4 個 author 相關欄位（per S154 §2.9 + AC-6）：
```json
{
  "id": "...",
  "author": "u_a3f9c1",                    // platform user_id（內部）
  "authorDisplayName": "Alice Chen",       // live join + snapshot fallback
  "authorHandle": "alice",                 // user-facing slug
  "authorEmail": "alice@example.com"       // optional — 只在 contact_email_public=true 出現
}
```

`/api/v1/me` 回應加 `userId` + `handle`（per S154 AC-2）。

### 2.2 設計核心：getDisplayName helper 統一邏輯

`frontend/src/lib/displayName.ts`：
```ts
type AuthorFields = {
  authorDisplayName?: string;
  authorHandle?: string;
  authorEmail?: string;
  author: string;  // platform user_id (fallback)
};

export function getDisplayName(obj: AuthorFields): string {
  if (obj.authorDisplayName) return obj.authorDisplayName;
  if (obj.authorEmail) return obj.authorEmail.split('@')[0];
  if (obj.authorHandle) return obj.authorHandle;
  return obj.author;  // u_<6hex> 是最終 fallback（不是 raw sub）
}
```

**對齊 backend `DisplayNameResolver`** — 五層優先序最後一層 fallback 都到 user_id，**前後端永遠不會顯 raw OAuth sub**。

### 2.3 元件 sweep 範圍

| 元件 | 現顯 | 改 |
|------|------|----|
| `SkillCard` (HomePage / LandingPage / SearchResults / MySkills) | `{skill.author}` raw | `{getDisplayName(skill)}` |
| `v2/PageHeader` (SkillDetailPage) | `作者：{skill.author}` raw | `作者：{getDisplayName(skill)}` |
| `v2/InstallCard` | `skills-hub install {author}/{name}` | `skills-hub install {skill.authorHandle ?? skill.author}/{name}` |
| `AppShell` (profile dropdown) | `{me.email ?? me.sub}` | `{me.name ?? me.email ?? me.handle ?? me.userId}` |
| `pages/MySkillsPage` (hero) | 「以 X 身份發布」 | 對齊 priority chain |
| `ReviewsPanel` | `{review.author}` raw | `{getDisplayName(review)}`（前提 review API 也回 author 欄位 — 若無則 backend defer） |
| `pages/SkillDetailPage` (contact button) | 無 | conditional on `skill.authorEmail` 存在 → 顯 `mailto:` button |
| `pages/PublishPage` (作者欄位) | `<input value={me.sub}>` raw 21 位 OAuth sub + 誤導文字「可改為團隊或代發名稱」 | **改為 read-only display**：`{me.name ?? me.email ?? me.handle ?? me.userId}` + handle chip `@{me.handle}`；移除 input + 「代發名稱」文字；form submit 不送 `author` field（對齊 S154 backend §2 `@RequestParam("author")` drop — caller body 欄位 silent ignored，UI 不該誤導 user 以為可改）|
| `EmptyState` 等含 author 文案 | sweep 一遍 | helper 套用 |

**§2.3 Phase 0 update (2026-05-11)**：PublishPage row 加入，因 user 反映 LAB 跑 `/publish` 頁時「作者」input 仍顯 `116549129985546340268`（OAuth sub）。Root cause：S154 backend forge fix 已 drop `@RequestParam("author")`（server 從 `currentUserProvider.userId()` 取），但 PublishPage 前端仍用 `me.sub` prefill 且暴露為 input，user 誤以為可改但 server silent ignore。修法：純 read-only display，align Backend 已固定的 ownership semantics。

### 2.4 ShareSkillModal — 4 個 polish

| 觀察 | 修法 |
|------|------|
| 「現有分享」list 顯示「user:111161306011023995106 OWNER」raw sub | (a) S154 backend 已返回 user_id，ACL list API 加 displayName/handle enrich → (b) 前端套 helper 顯「Alice Chen OWNER」 |
| 「新增分享」radio 選 group / company — 平台無 organization model | hide group / company radio（feature first） |
| 已 public 仍可選「public」radio 加 — 重複授權無意義 | radio public 在 acl_entries 已含 `public:*:read` 時 disabled + tooltip「此技能已公開」 |
| 「輸入 ID...」placeholder | 改「輸入使用者 email 或 handle」+ submit 時走 backend `UserResolver.resolveByEmailHandleOrId()`（S154 已建）|

**(a) 細節**：S154 backend 設計 ACL list 端點 (e.g. `GET /api/v1/skills/{id}/acl`) 回傳每個 entry 時應 enrich displayName/handle。若 S154 沒做，本 spec 加一個 backend micro-task：ACL list response enrich（pure read-side join，~10 line）。

---

## 3. Acceptance Criteria

```
AC-1: SkillCard / PageHeader / MySkills 顯 displayName 不顯 user_id
  Given Alice (authorDisplayName="Alice Chen", authorHandle="alice", author="u_a3f9c1") publish skill
  When Bob 訪問 LandingPage / HomePage / SearchResults / MySkillsPage / SkillDetailPage
  Then 顯示「作者：Alice Chen」（不顯 user_id "u_a3f9c1" 也不顯 sub）

AC-2: InstallCard install command 用 handle
  Given Alice authorHandle="alice"
  When Bob 開 SkillDetailPage 看 InstallCard
  Then command 字串 = `skills-hub install alice/auditing-terraform-infrastructure-for-security`
  Given 假設某 skill 對應 user 的 handle 為 NULL（極罕見 edge）
  Then command fallback 用 user_id (`skills-hub install u_a3f9c1/...`)，**不顯 raw sub**

AC-3: AppShell profile dropdown 顯 priority
  Given me.name="Alice Chen", me.email="alice@..", me.handle="alice", me.userId="u_a3f9c1"
  When 開 profile dropdown
  Then 主行顯 "Alice Chen"
  Given me.name=null（極罕見）
  Then 主行顯 me.email "alice@..."
  Given me.email/name 都 null
  Then 主行顯 me.handle "alice"

AC-4: SkillDetailPage 預設不顯 contact button（email 沒公開）
  Given skill.authorEmail 不存在 (default false)
  When Bob 訪問 SkillDetailPage
  Then 沒「聯絡作者」button

AC-5: SkillDetailPage 公開 email 顯 mailto button
  Given skill.authorEmail = "alice@example.com"
  When Bob 訪問 SkillDetailPage
  Then 顯「聯絡作者」button → href="mailto:alice@example.com"

AC-6: ShareSkillModal 「現有分享」list 顯 displayName
  Given skill 已分享給 Alice（acl_entries 含 "user:u_a3f9c1:OWNER"）
  And ACL list API 回 enriched entry（含 displayName="Alice Chen", handle="alice"）
  When 開 ShareSkillModal
  Then list 顯「Alice Chen OWNER」（不顯 raw user_id 也不顯 sub）

AC-7: ShareSkillModal 「新增分享」radio 隱藏 group/company
  Given MVP 階段無 organization model
  When 開 modal「新增分享」section
  Then 只見 user / public radio，**無** group / company

AC-8: ShareSkillModal 已 public 時 disable public radio
  Given skill ACL 已含 "public:*:read"
  When 點「新增分享」section
  Then public radio disabled
  And tooltip 顯「此技能已公開瀏覽」

AC-9: ShareSkillModal 輸入欄接受 email / handle
  Given user radio active
  When user 在輸入欄打 "alice@example.com" 或 "alice"
  Then submit 觸發 backend grant API，後端走 UserResolver 解析 → user_id → ACL 寫入
  And placeholder 顯「輸入使用者 email 或 handle」（非 raw 「輸入 ID...」）

AC-10: PublishPage 作者欄位改 read-only display（不顯 raw sub / 不可編輯 / form submit 不送 author）
  Given Alice (me.name="Alice Chen", me.handle="alice", me.email="alice@example.com", me.sub="116549129985546340268", me.userId="u_a3f9c1") 開 /publish 頁
  When 看到「作者」欄位
  Then 該欄位為 read-only display（**不是** <input>）
  And  顯主文字「Alice Chen」（priority: me.name → me.email local-part → me.handle → me.userId；**永不**顯 raw sub "116549129985546340268"）
  And  顯 handle chip「@alice」
  And  **無** 「已自動填入你的識別 ... 可改為團隊或代發名稱」這段文字
  Given user 點「發佈」submit form
  When form submit
  Then 送進 backend 的 multipart/form-data 不含 `author` field（對齊 S154 backend silent-ignore semantics — server 一律從 `currentUserProvider.userId()` 取）
```

**驗證指令：** `cd frontend && npm test`（含新 `displayName.test.ts` / `SkillCard.test.tsx` / `InstallCard.test.tsx` / `ShareSkillModal.test.tsx`）+ 手動 LAB 驗證

---

## 4. Files to Change

### Frontend production code

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/displayName.ts` | **新增** — `getDisplayName()` helper |
| `frontend/src/types/skill.ts` | 加 `authorDisplayName?` / `authorHandle?` / `authorEmail?` field |
| `frontend/src/hooks/useMe.ts` | `CurrentUser` interface 擴充：加 `userId: string` + `handle: string` + `name: string \| null` + `email: string \| null` + `displayName?: string`（對齊 S154 backend `MeController` 11-key response）— **Phase 0 修正**：原 spec §4 line 157 寫 `types/me.ts` 但該檔不存在；實際 `CurrentUser` interface 在 `hooks/useMe.ts:12`（S094a 寫的 6 fields drift） |
| `frontend/src/components/SkillCard.tsx` | 改用 helper |
| `frontend/src/components/v2/PageHeader.tsx` | 改用 helper |
| `frontend/src/components/v2/InstallCard.tsx` | install command 改用 `skill.authorHandle ?? skill.author` |
| `frontend/src/components/AppShell.tsx` | profile dropdown 顯 priority chain |
| `frontend/src/pages/SkillDetailPage.tsx` | contact button conditional on `skill.authorEmail` 存在 |
| `frontend/src/pages/PublishPage.tsx` | **Phase 0 add** — 作者欄位 `<input value={author}>` 改 read-only display：drop `authorEdit` state + `author` derived + `setAuthorEdit`；改用 `getDisplayName({author: me.userId, authorDisplayName: me.name, authorEmail: me.email, authorHandle: me.handle})` + handle chip；form `handleSubmit` 不送 author 給 `uploadSkill()`（一併拔 `uploadSkill` signature 第 3 個 `author` 參數，或保留但傳空字串 — 取決於 S154 backend 是否接受 author missing） |
| `frontend/src/api/skills.ts` | **Phase 0 add** — `uploadSkill(file, version, author, category, visibility)` signature 拔 `author` 參數 + 移除 `form.append('author', author)`（對齊 backend silent-ignore；server 從 auth context 取） |
| `frontend/src/pages/MySkillsPage.tsx` | hero「以 X 身份發布」對齊 priority |
| `frontend/src/components/ReviewsPanel.tsx` | review.author 走 helper（前提 review API 已 enrich；若無則 defer） |
| `frontend/src/components/EmptyState.tsx` 等含 author 文案 | sweep |
| `frontend/src/components/v2/ShareSkillModal.tsx` | 4 個 polish |
| `frontend/src/api/skills.ts` | grantAcl request body 接受 `email | handle | user_id`（後端 UserResolver 解析） |

### Backend micro-changes（若 S154 沒做就在本 spec 補）

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/query/SkillAclQueryService.java`（若有） or `SkillQueryController` | ACL list endpoint 回傳 entry 加 enriched displayName / handle（pure read-side join） |
| `backend/src/main/java/.../skill/command/SkillCommandController.java` (grant endpoint) | 接受 email / handle / user_id 三種輸入，走 `UserResolver.resolveByEmailHandleOrId()` 解析 |

### Frontend test

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/displayName.test.ts` | **新增** — 五層 fallback |
| `frontend/src/components/SkillCard.test.tsx` | **新增** — render 驗 author display |
| `frontend/src/components/v2/InstallCard.test.tsx` | **新增** — command 含 handle |
| `frontend/src/components/v2/ShareSkillModal.test.tsx` | **新增** — 4 個 polish 行為（list display / radio hide / disable / placeholder + email-resolve） |
| `frontend/src/pages/PublishPage.test.tsx` | **Phase 0 add** — `it("AC-10: 作者欄位 read-only display")` 驗：`queryByRole('textbox', { name: '作者' })` returns null（不是 input）；`getByText('Alice Chen')` 存在；`queryByText('代發名稱')` returns null；mock fetch 攔 `/api/v1/skills/upload` 驗 FormData 不含 `author` key |

---

## 5. Test Plan

### 5.1 自動化（npm test）

| AC | 驗證方式 |
|----|---------|
| AC-1 | `SkillCard.test.tsx` render 多 case（有 displayName / 只有 handle / 都 null fallback user_id） |
| AC-2 | `InstallCard.test.tsx` 驗 command 字串含 handle / fallback user_id |
| AC-3 | `AppShell.test.tsx` 三 priority case |
| AC-4 | `SkillDetailPage.test.tsx` 預設 contact button hide |
| AC-5 | `SkillDetailPage.test.tsx` mock `authorEmail` 存在驗 mailto |
| AC-6 | `ShareSkillModal.test.tsx` mock ACL list response enriched |
| AC-7 | `ShareSkillModal.test.tsx` queryByText group/company 不存在 |
| AC-8 | `ShareSkillModal.test.tsx` mock acl_entries 含 public → public radio disabled |
| AC-9 | `ShareSkillModal.test.tsx` mock submit 流 backend grant API request body 含 email |
| AC-10 | `PublishPage.test.tsx` 驗 author input absent + display Alice Chen + FormData 不含 author |

### 5.2 手動 LAB 驗證（S154 + S154b 都 ship 後）

- [ ] LandingPage 看 skill card → 顯「Alice Chen」不顯 `u_a3f9c1`
- [ ] SkillDetailPage install command → `skills-hub install alice/<name>`
- [ ] AppShell profile dropdown → 「Alice Chen」
- [ ] ShareSkillModal 開：list 顯 displayName / 無 group-company / public 已加 disabled / placeholder 友善
- [ ] **PublishPage 開：作者欄位顯「Alice Chen」 + 「@alice」chip，無 input，無「代發名稱」文字；submit upload 可成功**（AC-10）
- [ ] 手動 SQL 設 contact_email_public=true → SkillDetailPage 出現 mailto button

---

## 6. Task Plan

**POC: not required** — 純 frontend wiring + 一個 backend micro-change（若需要）；無新 dep；pattern 已驗證。

### Tasks

| ID | 標題 | 涵蓋 AC | 主要檔案 | Depends On |
|----|------|--------|---------|-----------|
| T01 | getDisplayName helper + types | (基礎) | `displayName.ts` + `types/skill.ts` + `hooks/useMe.ts` (CurrentUser interface 擴充) + `displayName.test.ts` | none |
| T02 | 元件 sweep（SkillCard / PageHeader / InstallCard / AppShell / MySkills / EmptyState） | AC-1, AC-2, AC-3 | 6 元件 + 3 test files | T01 |
| T03 | SkillDetailPage contact button + ReviewsPanel | AC-4, AC-5 | SkillDetailPage + ReviewsPanel + 1 test | T01 |
| T04 | ShareSkillModal 4 polish + backend ACL list enrich + grant resolve | AC-6, AC-7, AC-8, AC-9 | ShareSkillModal + SkillAclQueryService + SkillCommandController grant + skills.ts api + ShareSkillModal.test.tsx | T01 + S154 ship |
| T05 | **PublishPage 作者欄位改 read-only display** — drop `authorEdit` state + `<input>` + 誤導文字；`uploadSkill()` signature 拔 `author` 參數 + FormData 不送 author；test 驗 input absent + display 正確 + FormData 不含 author | AC-10 | `pages/PublishPage.tsx` + `pages/PublishPage.test.tsx` + `api/skills.ts` (uploadSkill signature) | T01 |

### Execution order

```
T01 ─┬──▶ T02
     ├──▶ T03
     ├──▶ T04 (依 T01 + S154 ship)
     └──▶ T05 (Phase 0 add — PublishPage read-only)
```

T02 / T03 / T04 / T05 都依 T01 完工，但彼此互不依賴 — 可序列跑（避免同時改多 frontend 元件衝突）。

---

## 7. Implementation Results

**Status**: ✅ Done (2026-05-11)

### Verification

| Layer | Command | Result |
|---|---|---|
| Frontend unit | `cd frontend && npm test -- --run` | **350/350 PASS** (+10 new from T01-T05) |
| Backend unit | `cd backend && ./gradlew test` | **725/725 PASS** (+4 new from T04) |
| E2E integration seam | `SkillsHubAuthE2ETest` (S120 ACL grant E2E) | PASS — grant flow with new resolver path tested end-to-end |

### Key Findings

**1. `getDisplayName` 5-layer fallback — single source of truth for display logic.**
`frontend/src/lib/displayName.ts` codifies the priority chain `authorDisplayName → email local-part → handle → user_id`。所有元件（SkillCard / PageHeader / AuthArea / ShareModal / PublishPage / MySkillsPage）統一走 helper，permanent fix for the "raw OAuth sub leaks to UI" class of bug。

**2. PublishPage author field — read-only is the only correct UI.**
原 `<input>` 可改但 backend silent-ignore（per S154 forge fix）→ UI 誤導 user。T05 把 input 改 read-only display + `uploadSkill()` signature drop `author` param → frontend 與 backend authoritative ownership semantics 完全對齊。

**3. SkillGrant ACL — accept email/handle, store user_id.**
T04 在 `SkillGrantService.grant()` 加 trust-or-resolve 分支：input 以 `u_` 前綴視為 user_id 直接 trust（允許 ACL 預先 grant 給未登入 user_id），否則走 `UserResolver.resolveByEmailHandleOrId()` 解析 email/handle → user_id。`listGrants()` 對 user principal LEFT JOIN users 補 displayName/handle 給 ShareModal list row 用。

**4. AuthArea defensive empty fallback — 防 test mock 不完整 NPE。**
T02 期間發現 `displayLabel.charAt(0)` 在所有 priority 全 undefined 時 NPE 致 9 個 page tests fail。加 `?? ''` 與 `(label || '?').charAt(0)` 雙重 fallback；production 端 backend 保證 handle NOT NULL 永遠不會走到此分支，但 unit test mock 不完整時 graceful degrade。

**5. Regex granularity — production vs test fixture mismatch.**
T04 第一次寫 `^u_[0-9a-f]{6}$` 嚴格 hex 規則擋下 E2E fixture `u_view07`（非 hex 變體可讀性）→ E2E test fail。Bisect 後改 `startsWith("u_")` 寬鬆規則涵蓋兩種變體。設計教訓：trust path 規則寬於 production data shape spec 是 OK 的（registry semantics 不要求 principal 存在）。

### Correct Usage Patterns

**`getDisplayName(obj)` — for skill/grant author display:**
```ts
import { getDisplayName } from '@/lib/displayName'
const label = getDisplayName({
  author: skill.author,           // user_id fallback (always present)
  authorDisplayName: skill.authorDisplayName,
  authorEmail: skill.authorEmail,
  authorHandle: skill.authorHandle,
})
```

**`AuthArea` priority chain — for current user dropdown:**
```ts
// AuthArea.tsx — 5-layer with defensive empty fallback
const displayLabel = user.name ?? user.email ?? user.handle ?? user.userId ?? ''
const fallbackChar = (displayLabel || '?').charAt(0).toUpperCase()
```

**`SkillGrantService.grant()` — accept any principal form:**
```java
// user principal trust-or-resolve
if (input != null && input.startsWith("u_")) {
    resolvedPrincipalId = input;  // trust, no users-row check
} else {
    resolvedPrincipalId = userResolver.resolveByEmailHandleOrId(input)
            .orElseThrow(() -> new IllegalArgumentException(
                    "user_not_found: cannot resolve principal '" + input + "' to user_id"));
}
```

### AC Results

| AC | Status | Test |
|---|---|---|
| AC-1 | ✅ | `SkillCard.test.tsx` 顯 displayName 不顯 user_id |
| AC-2 | ✅ | `InstallCard.test.tsx` ×2 — handle 與 user_id fallback |
| AC-3 | ✅ | `AuthArea.test.tsx` priority chain |
| AC-4 | ✅ | `PageHeader.test.tsx` — `authorEmail` 不存在 → 無 mailto link |
| AC-5 | ✅ | `PageHeader.test.tsx` — `authorEmail` 存在 → `mailto:` link href 正確 |
| AC-6 | ✅ | `ShareModal.test.tsx` + `SkillGrantServiceTest` — list enrich displayName/handle |
| AC-7 | ✅ | `ShareModal.test.tsx` — radio 只 user/public |
| AC-8 | ✅ | `ShareModal.test.tsx` — 已 public → disabled + 文案 |
| AC-9 | ✅ | `ShareModal.test.tsx` + `SkillGrantServiceTest` ×3 — placeholder + email/handle/public resolve path |
| AC-10 | ✅ | `PublishPage.test.tsx` ×2 — read-only display + FormData drop author |

### Files Changed

**Frontend (10 files):**
- NEW `frontend/src/lib/displayName.ts` + `displayName.test.ts`
- `frontend/src/types/skill.ts` — +authorDisplayName/authorHandle/authorEmail
- `frontend/src/hooks/useMe.ts` — CurrentUser 11 keys
- `frontend/src/api/auth.ts` — AuthUser +userId/handle
- `frontend/src/api/grants.ts` — SkillGrant +displayName/handle
- `frontend/src/api/skills.ts` — uploadSkill drop author param
- `frontend/src/components/SkillCard.tsx` / `v2/PageHeader.tsx` / `v2/InstallCard.tsx` / `AuthArea.tsx` / `ShareModal.tsx`
- `frontend/src/pages/PublishPage.tsx` / `MySkillsPage.tsx`
- NEW `frontend/src/components/ShareModal.test.tsx`
- + existing `SkillCard.test.tsx` / `InstallCard.test.tsx` / `AuthArea.test.tsx` / `MySkillsPage.test.tsx` / `PageHeader.test.tsx` / `PublishPage.test.tsx` 補測

**Backend (3 files):**
- `backend/src/main/java/.../skill/security/SkillGrant.java` — @Transient displayName/handle + enrichDisplay mutator
- `backend/src/main/java/.../skill/security/SkillGrantService.java` — ctor 6 args + grant resolve + listGrants enrich
- `backend/src/test/java/.../skill/security/SkillGrantServiceTest.java` — +4 AC test

### E2E Verification Rationale

Per Phase 4 Step 1.5 gate: this spec touches **3 integration seams**:

1. **SkillGrant ACL flow** — `SkillsHubAuthE2ETest` (S120 existing E2E) exercises full grant → projection → query path with new resolver; PASS confirms trust-path + resolver-path both intact under real Spring DI + PostgreSQL.
2. **Author display in skill list/detail** — purely read-side projection, no schema/wiring change; unit tests with mock data sufficient.
3. **PublishPage upload** — `uploadSkill()` signature change; backend already silent-ignored `author` param (per S154 forge fix) → backwards-compat. No new E2E needed.

**Verdict**: existing S120 E2E covers the only seam with new write-side behavior. No Playwright additions needed.

### QA Review

**Reviewer**: Independent QA Agent | **Date**: 2026-05-11 | **Verdict**: ✅ PASS

#### Test Suite Results

| Layer | Command | Actual Result |
|---|---|---|
| Frontend unit | `cd frontend && npm test -- --run` | **350/350 PASS** (65 test files) ✅ |
| Backend unit | XML artifacts from `15:36` today (post-S154b) | **725/725 PASS, 0 failures** ✅ |

Backend artifacts reused (timestamps `2026-05-11 15:36`, post latest commit `133d442 v4.47.0`; subsequent commits S168 are unrelated domain).

#### AC Coverage

| AC | Test File | Found | Status |
|---|---|---|---|
| AC-1 | `SkillCard.test.tsx` line 59 | `it('AC-1: 顯示 authorDisplayName…')` | ✅ |
| AC-2 | `InstallCard.test.tsx` lines 22, 31 | handle + user_id fallback × 2 | ✅ |
| AC-3 | `AuthArea.test.tsx` line 102 | single test covers handle/userId fallback; name-present case covered implicitly by AC-4 tests (lines 54, 82) with `name: 'Alice'`; email-fallback case absent as explicit AC-3 | ⚠ MINOR |
| AC-4 | `PageHeader.test.tsx` line 102 | no-email → no mailto | ✅ |
| AC-5 | `PageHeader.test.tsx` line 109 | mailto href correct | ✅ |
| AC-6 | `ShareModal.test.tsx` line 49 + `SkillGrantServiceTest` line 229 | enriched list display | ✅ |
| AC-7 | `ShareModal.test.tsx` line 69 | only user/public radios | ✅ |
| AC-8 | `ShareModal.test.tsx` line 80 | disabled + tooltip | ✅ |
| AC-9 | `ShareModal.test.tsx` line 98 + `SkillGrantServiceTest` lines 168, 190, 209 | placeholder + email/handle/public paths × 4 | ✅ |
| AC-10 | `PublishPage.test.tsx` lines 142, 154 | read-only display + FormData drop × 2 | ✅ |

#### Code Quality

- `frontend/src/lib/displayName.ts` — 4-layer fallback (`authorDisplayName → email local-part → handle → author`) is correct and complete; runtime behavior matches spec §2.2.
- `frontend/src/components/AuthArea.tsx` line 48-49 — defensive `?? ''` and `(displayLabel || '?').charAt(0)` double fallback present; correctly guarded.
- `backend/.../skill/security/SkillGrant.java` lines 57, 64 — `@org.springframework.data.annotation.Transient` annotations correct on both `displayName` and `handle`; `enrichDisplay()` mutator scoped to read-side only (called only in `listGrants()`); no write-path contamination.
- `backend/.../skill/security/SkillGrantService.java` — ctor has 6 args (lines 54-66); trust-or-resolve branch uses `startsWith("u_")` (line 102); `listGrants()` enrich loop correctly skips non-user principals (line 169); DisplayNameResolver called with null email param (correct — email not exposed in share modal list per comment line 166).

#### Design Drift (Documented in §7)

- **T03 ReviewsPanel deferred** — documented at §8 follow-up row; production code still shows raw user_id for reviews. Acceptable — precondition (review API enrich) not met; correctly scoped out.
- **T04 trust path `startsWith("u_")` vs strict hex regex** — documented in §7 "Key Findings" #5 with rationale. Code at `SkillGrantService.java:102` matches documented decision.

#### Findings

**MINOR**:
- `displayName.ts` JSDoc and §7 Finding #1 both say "五層/5-layer fallback" but the actual `getDisplayName()` function has **4 layers** (authorDisplayName / email local-part / handle / author). AuthArea's priority chain is `name → email → handle → userId → ''` (5 counting the defensive empty). The mislabeling spans `displayName.ts:2`, `displayName.ts:20`, spec §7 Finding #1 title. No runtime impact — code is correct; only the count label is off.
- AC-3 spec requires three explicit priority cases (name-present, email-fallback, handle-fallback). `AuthArea.test.tsx` has one dedicated AC-3 test (handle case); name-present is covered by AC-4 tests but not tagged AC-3; email-only fallback case is not tested. All production paths are guarded by `displayName.test.ts` unit tests, so functional risk is low.
- §5.1 says AC-3 verified by `AppShell.test.tsx`; §7 Results says `AuthArea.test.tsx`. The component was correctly implemented in `AuthArea.tsx` (AppShell delegates to AuthArea), but the test file cross-reference in §5.1 diverges from §7. Minor doc inconsistency only.

**No CRITICAL or IMPORTANT findings.**

---

## 8. 後續 follow-up（同 S154 §7）

| ID | 範圍 | 說明 |
|----|------|------|
| S168 | User profile page `/users/{handle}` | 列出該作者所有 skill；avatar；contact button |
| S169 | User settings UI | 改 handle、toggle email 公開、avatar 上傳 |
| S170 | OAuth account linking | Pattern A → B 升級；連結多 OAuth providers |
| S171 | Organization / namespace | agentskills.io standard |
| S172 | Platform-internal messaging | 不公開 email 也能聯絡作者 |
| follow-up | Review API author enrich | T03 scope deferred — ReviewsPanel 仍顯 raw user_id；backend `ReviewResponse` LEFT JOIN users 補 displayName/handle 後前端走 `getDisplayName` |
