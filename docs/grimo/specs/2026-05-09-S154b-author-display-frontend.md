# S154b: Author Display Identity (Frontend) — getDisplayName + 元件 sweep + ShareSkillModal polish

> Spec: S154b | Size: S(9) | Status: 📐 in-design
> Date: 2026-05-09（拆自 S154 backend/frontend split — planning-tasks size gate）
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
| `EmptyState` 等含 author 文案 | sweep 一遍 | helper 套用 |

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
```

**驗證指令：** `cd frontend && npm test`（含新 `displayName.test.ts` / `SkillCard.test.tsx` / `InstallCard.test.tsx` / `ShareSkillModal.test.tsx`）+ 手動 LAB 驗證

---

## 4. Files to Change

### Frontend production code

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/displayName.ts` | **新增** — `getDisplayName()` helper |
| `frontend/src/types/skill.ts` | 加 `authorDisplayName?` / `authorHandle?` / `authorEmail?` field |
| `frontend/src/types/me.ts` | `Me` interface 加 `userId: string` + `handle: string` |
| `frontend/src/components/SkillCard.tsx` | 改用 helper |
| `frontend/src/components/v2/PageHeader.tsx` | 改用 helper |
| `frontend/src/components/v2/InstallCard.tsx` | install command 改用 `skill.authorHandle ?? skill.author` |
| `frontend/src/components/AppShell.tsx` | profile dropdown 顯 priority chain |
| `frontend/src/pages/SkillDetailPage.tsx` | contact button conditional on `skill.authorEmail` 存在 |
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

### 5.2 手動 LAB 驗證（S154 + S154b 都 ship 後）

- [ ] LandingPage 看 skill card → 顯「Alice Chen」不顯 `u_a3f9c1`
- [ ] SkillDetailPage install command → `skills-hub install alice/<name>`
- [ ] AppShell profile dropdown → 「Alice Chen」
- [ ] ShareSkillModal 開：list 顯 displayName / 無 group-company / public 已加 disabled / placeholder 友善
- [ ] 手動 SQL 設 contact_email_public=true → SkillDetailPage 出現 mailto button

---

## 6. Task Plan

**POC: not required** — 純 frontend wiring + 一個 backend micro-change（若需要）；無新 dep；pattern 已驗證。

### Tasks

| ID | 標題 | 涵蓋 AC | 主要檔案 | Depends On |
|----|------|--------|---------|-----------|
| T01 | getDisplayName helper + types | (基礎) | displayName.ts + skill.ts + me.ts + displayName.test.ts | none |
| T02 | 元件 sweep（SkillCard / PageHeader / InstallCard / AppShell / MySkills / EmptyState） | AC-1, AC-2, AC-3 | 6 元件 + 3 test files | T01 |
| T03 | SkillDetailPage contact button + ReviewsPanel | AC-4, AC-5 | SkillDetailPage + ReviewsPanel + 1 test | T01 |
| T04 | ShareSkillModal 4 polish + backend ACL list enrich + grant resolve | AC-6, AC-7, AC-8, AC-9 | ShareSkillModal + SkillAclQueryService + SkillCommandController grant + skills.ts api + ShareSkillModal.test.tsx | T01 + S154 ship |

### Execution order

```
T01 ─┬──▶ T02
     ├──▶ T03
     └──▶ T04 (依 T01 + S154 ship)
```

T02 / T03 / T04 都依 T01 完工，但彼此互不依賴 — 可序列跑（避免同時改多 frontend 元件衝突）。

---

## 7. 後續 follow-up（同 S154 §7）

| ID | 範圍 | 說明 |
|----|------|------|
| S168 | User profile page `/users/{handle}` | 列出該作者所有 skill；avatar；contact button |
| S169 | User settings UI | 改 handle、toggle email 公開、avatar 上傳 |
| S170 | OAuth account linking | Pattern A → B 升級；連結多 OAuth providers |
| S171 | Organization / namespace | agentskills.io standard |
| S172 | Platform-internal messaging | 不公開 email 也能聯絡作者 |
