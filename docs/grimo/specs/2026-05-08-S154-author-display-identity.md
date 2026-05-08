# S154: Author Display Identity — 把 OAuth sub ID 替換為人類可讀的作者顯示

> Spec: S154 | Size: M(11) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— `skill.author` 存 OAuth sub `111161306011023995106`，導致 SkillCard / PageHeader / InstallCard / Profile dropdown / LandingPage cards / Reviews / Flags 全部顯示一串無人能讀的 21 位數字。Install command `skills-hub install 111161306011023995106/auditing-terraform-infrastructure-for-security` 完全沒人記得起來。

---

## 1. Goal

讓「作者」概念從 raw OAuth `sub` 升級為**人類可讀的 user identity**：UI 顯示 user name（或 email 局部），install command 用 username slug，並具備「分享技能找得到聯絡資訊」的基礎。

**為什麼重要：**
- **可發現性歸零**：使用者看到 `111161306011023995106` 完全無法判斷是誰，平台「社群」屬性瓦解
- **Install command 不可記**：CLI install 是核心 UX 路徑，要求記 21 位數字違背 npm/registry 業界經驗
- **跨技能 author 連動失效**：同一作者多個 skill 看不出是同一人（兩個 skill 顯示相同數字串才看得出，但 user 不會比對）
- **Sharing pivot**：使用者要把技能丟給同事 review、找作者問問題、回報，沒任何聯絡點

**非目標：**
- 不改 ACL / authorization 邏輯（內部 ID 仍用 sub，這層不動）
- 不做 user profile 編輯頁（公開的「我的個人資料」延後另開 spec）
- 不做 organization / team / namespace（agentskills.io standard 的 namespace 概念，更後期再開）

---

## 2. Approach

### 2.1 現況回顧

**寫入路徑**（publish skill）：
```
SkillCommandController.publishSkill()
  → CurrentUserProvider.getCurrent().name  // 此 .name 其實是 sub
  → Skill.create(..., author=sub, ...)
  → repo.save() → skills.author = "111161306011023995106"
```

**讀取路徑**（Skill detail）：
```
GET /api/v1/skills/{id} → Skill record → JSON.author = "111161306011023995106"
  → Frontend：SkillCard / PageHeader 直接顯示 raw author
```

**現有資產**：
- `MeController` 已能從 JWT / OAuth2User 抽 `name`, `email`, `picture`（S141 ship）
- `CurrentUserProvider` 統一 principal 抽取（OAuth/Lab 模式皆通），但只給出 `sub` + `roles`，**沒 surface name/email**

**Database 現況**：
- `skills.author` 是 `VARCHAR` 單欄，無關聯 user 表
- 無 `users` 表（user identity 完全只活在 OAuth Provider 端）
- ACL 用 `acl_entries(skill_id, principal)`，principal 也是 sub 字串

### 2.2 三個 user-journey 場景（先寫場景，再評估方案）

| # | 場景 | 期望行為 | 目前 |
|---|------|---------|------|
| 1 | Alice 發布 skill「auditing-terraform」，Bob 在 LandingPage 看到 | SkillCard 顯示「Alice Chen」（或 alice@example.com）| 顯 `111161306011023995106` |
| 2 | Bob 想 install Alice 的 skill | `skills-hub install alice/auditing-terraform`（username 短）| `skills-hub install 111161306011023995106/auditing-terraform...`（21+ chars） |
| 3 | Bob 想聯絡 Alice 問問題 | SkillDetail 看見作者 link，點進去看 profile / 寄信 | 沒任何聯絡入口 |
| 4 | Alice 過了一週後改了 Google account display name | 既有 skill 顯示更新（或保留發佈時 snapshot — 由 spec §2.5 決定）| Skill 永遠是 sub 數字串 |
| 5 | Alice 換 Google account（sub 變了，少見但可能）| 既有 skill 仍歸屬「原本那個 Alice」 | sub 變 = 不同 user，skill 仍歸舊 sub |

### 2.3 三個方案

| 方案 | 核心 | Pros | Cons |
|------|------|------|------|
| **A. Snapshot only**（npm pattern）| publish 時把 `name` + `email` snapshot 進 `skills.author_name` / `skills.author_email`；ACL 仍用 sub | 簡單；user 刪除不影響 skill 顯示；無 join 開銷 | user 改名 → skill display stale；無集中的 user 表，sharing/contact 找不到當前資訊 |
| **B. Users table + JOIN**（typical SaaS）| 建 `users(sub PK, email, name, avatar_url, ...)` 表；`/me` UPSERT；skills query JOIN users | 單一真實源；改名即時生效；有 contact 點 | user 刪除 → skill 顯示斷；JOIN 多打一次 DB；user 表 PII 治理需設計 |
| **C. Hybrid — users 表 + skills 內 snapshot** ⭐ | A + B：publish 時 snapshot；同時 maintain users 表；display 優先 join users（fresh）fallback skill snapshot（resilient） | 改名能更新；user 刪除 skill 仍可顯示 snapshot；sharing/contact 走 users 表 | 兩處資料；同步邏輯（不複雜，UPSERT）；多打 1 個 JOIN |

**選 C**：sharing/contact 需求需要集中 user 表（A 做不到）；resilience 需要 snapshot（B 做不到）；複雜度增量低（一個 UPSERT + 一個 nullable JOIN）。

### 2.4 Display Name 計算規則

OAuth claims → display name 優先序：
1. `name`（OIDC standard claim — Google 提供 full name）
2. `given_name + " " + family_name`
3. `email` 的 local-part（`@` 前），首字大寫
4. `sub` 取最後 6 碼當 fallback handle

實作：寫一個 `DisplayNameResolver` static helper，pure function，給 OAuth claims map 回 display name。

### 2.5 Snapshot vs Live —「改名後既有 skill 顯示」決策

**選擇：** 顯示優先 live（users 表 join），無資料 fallback skill snapshot。

理由：
- Skill 是「使用者的作品」概念（vs 不可變 immutable artifact），改名 update 顯示符合 user 預期（GitHub repo author 也是 live）
- snapshot 仍保留為 fallback：若 user 刪除帳號（users row gone），skill 仍可顯示「原作者：Alice Chen（已停用）」
- 反例（npm）的 snapshot-only 是因為 npm 是 immutable registry；本平台支援 unpublish/edit，更接近 GitHub 模式

### 2.6 Username Slug —「install 用什麼 handle？」

`/api/v1/skills/{author}/{name}` canonical alias 目前 author 是 sub。改 username slug：

選項：
- **a.** username = sub 後 6 碼 `999106/auditing-terraform`（短但仍不可讀）
- **b.** username = email local-part `alice/auditing-terraform`（可讀但可能撞名 — 兩個 alice@ 不同 domain）
- **c.** username = 自選 handle（user 第一次登入時 provision；類似 GitHub username）— **複雜，留 future spec**
- **d.** username = email-derived slug，撞名時加 `-2`、`-3` 後綴（auto-resolve）

**選 b（先做）+ 留升級路徑到 c**：MVP 用 email local-part，提供 `users.handle` column（nullable，可手動填），未來新增「設 handle」UI 時 user 可改。撞名情境：兩個 alice@diff-domain.com 直接以 `alice / alice2` 區分（自動化簡單）。

**Username 生效範圍**：
- `/api/v1/skills/{username}/{name}` API endpoint（既有 path 已是 `{author}/{name}` shape）
- `skills-hub install {username}/{name}` CLI command（InstallCard 改顯）
- `/skills/{username}/{name}` 前端 canonical URL（既有 React route）

向下相容：
- 既存 `skills.author = sub` 不刪，與新 `users.handle` 並存
- 舊 install command `skills-hub install <sub>/<name>` 在 backend 加 fallback：先比 `users.handle`，沒中再比 `users.sub`，仍能 resolve（避免外部書籤斷）

### 2.7 Email 公開與 Contact 機制

「分享 skill 找得到對方信箱」需求：

- 預設：`users.email` 是 PII，**不公開**到 SkillDetail
- `users.contact_email_public BOOLEAN DEFAULT FALSE` — user 自選是否公開
- 公開時：SkillDetail 顯示「聯絡作者」按鈕 → mailto:link
- 非公開時：可顯示「聯絡作者」按鈕 → 走平台內 message（不在本 spec；先 hide 即可）

**MVP 範圍：** users 表內存 email + contact_email_public flag（default false），UI 先不做「設定公開」的 toggle，全 user 預設 hidden。toggle UI 留 follow-up spec。重點：基礎 schema 留好。

### 2.8 Migration & Backfill

**新 migration `V18__create_users_and_author_snapshot.sql`**：

```sql
-- users 表（OAuth sub → display profile snapshot）
CREATE TABLE users (
    sub                  VARCHAR(255) PRIMARY KEY,
    email                VARCHAR(320) NOT NULL,
    name                 VARCHAR(255),
    handle               VARCHAR(64) UNIQUE,
    avatar_url           TEXT,
    contact_email_public BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at        TIMESTAMPTZ NOT NULL,
    last_seen_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_users_handle ON users(handle) WHERE handle IS NOT NULL;
CREATE INDEX idx_users_email  ON users(email);

-- skills 加 snapshot columns（NULLABLE — 舊資料無 snapshot 不擋讀）
ALTER TABLE skills ADD COLUMN author_name_snapshot  VARCHAR(255);
ALTER TABLE skills ADD COLUMN author_email_snapshot VARCHAR(320);

-- backfill：對既存 3 筆 skill，author 是 sub，沒 OAuth claims 可拿 → 留 NULL，UI 走最後 fallback「sub 後 6 碼」
-- （後續使用者重新登入時 /me UPSERT users 表會建立 row；既存 skill snapshot 仍 NULL，但 JOIN users 可拿到 fresh name）
```

**Domain code**：
- `Skill` aggregate 加 `authorNameSnapshot`、`authorEmailSnapshot` fields
- `SkillCommandService.publish()` 透過 `CurrentUserProvider.getProfile()`（新增 method 回 name/email）填 snapshot
- `MeController` 已能取 OAuth profile，加 UPSERT users logic（每次 /me 呼叫時更新 last_seen_at + sync name/email/avatar_url）

**Read side**：
- `SkillReadModel` 加 `authorDisplayName`、`authorEmail`（已 join users 後填，nullable）
- 前端 `Skill` interface 對齊新欄位
- 顯示 priority：`authorDisplayName` (live) → `authorNameSnapshot` (snapshot) → `email local-part` → `sub-suffix-fallback`

### 2.9 Frontend 影響範圍

| 元件 | 現顯 | 改 |
|------|------|----|
| `SkillCard`（HomePage / LandingPage / SearchResults / MySkills） | `{skill.author}` raw | `{getDisplayName(skill)}` |
| `PageHeader`（SkillDetailPage） | `作者：{skill.author}` raw | `作者：{getDisplayName(skill)}`（可選 + email link） |
| `InstallCard`（v2） | `skills-hub install {author}/{name}` | `skills-hub install {handle ?? sub}/{name}` |
| `Profile dropdown`（AppShell） | `{me.email ?? me.sub}` | 已有部份邏輯（S141 ship 後 `me.name`），確認優先序 |
| `MySkillsPage` Hero 「以 X 身份發布」 | `{me.name ?? me.email ?? sub}` | 確認 LAB 拿到 OAuth name 後正確 |
| `ReviewsPanel` review item author | `{review.author}` raw | `{getDisplayName(review)}` |

`getDisplayName(obj)` helper（frontend `lib/displayName.ts`）：
```ts
export function getDisplayName(obj: { authorDisplayName?: string; authorNameSnapshot?: string; authorEmail?: string; author: string }) {
  if (obj.authorDisplayName) return obj.authorDisplayName;
  if (obj.authorNameSnapshot) return obj.authorNameSnapshot;
  if (obj.authorEmail) return obj.authorEmail.split('@')[0];
  return `user-${obj.author.slice(-6)}`;
}
```

---

## 3. Acceptance Criteria

```
AC-1: SkillCard 不再顯 sub
  Given Alice 已 publish skill 且 alice@example.com 有 OAuth name "Alice Chen"
  When Bob 訪問 LandingPage / HomePage / SearchResults / MySkills 看到 Alice 的 skill
  Then 顯示「作者：Alice Chen」（不再顯 21 位 sub ID）

AC-2: Install command 用 username
  Given Alice handle 是 "alice"（從 email local-part derive）
  When Bob 訪問 SkillDetail 並複製 install command
  Then command 為 `skills-hub install alice/auditing-terraform-infrastructure-for-security`

AC-3: 舊 install command 仍 resolve（向下相容）
  Given backend 收到 GET /api/v1/skills/{old-sub}/{name}
  When 比對 users.handle 找不到，再比對 users.sub
  Then 仍能成功取得 skill record（避免既有書籤斷）

AC-4: User 改 OAuth display name 後，既有 skill 自動 refresh
  Given Alice publish 完後，下次登入時 OAuth name 從「Alice Chen」改為「Alice Liu」
  When users 表 /me UPSERT 同步新 name
  And 任何人下次訪問 Alice 的 skill page
  Then 顯示「Alice Liu」（live join 優先 snapshot）

AC-5: User 帳號刪除後，skill 仍可顯示
  Given Alice 帳號被 admin 刪除（users row gone），但 skill 留著（snapshot 已存）
  When 任何人訪問 Alice 的 skill
  Then 顯示「Alice Chen（已停用）」 (snapshot fallback) — disabled 標籤可選 polish

AC-6: 未公開 email 的作者不被洩漏
  Given Alice users.contact_email_public = false（default）
  When Bob 訪問 SkillDetail
  Then 不顯示 alice@example.com 任何形式
  And 「聯絡作者」按鈕 hide（未做 mailto fallback 前 MVP 先 hide）

AC-7: 公開 email 的作者可顯 mailto link
  Given Alice users.contact_email_public = true
  When Bob 訪問 SkillDetail
  Then 顯示「聯絡作者」按鈕 link 至 mailto:alice@example.com
  Note: AC-7 觸發需 user toggle UI；MVP 暫無 toggle UI，可手動 SQL 設 true 驗證；toggle 留 follow-up

AC-8: Migration backfill 不破現有 skill 顯示
  Given V18 migration 跑完
  When 訪問既存 3 筆 skill
  Then snapshot 為 NULL 但 join users（若 user 已登入過 → users row 存在）正常顯示 name
  And 若 users row 也 missing（首次部署無人登入），顯示 fallback「user-995106」（sub 後 6 碼）— 不顯 raw sub

AC-9: ACL / authorization 行為不變
  Given Skill ACL entries 仍用 sub 為 principal
  When 既有 RBAC 測試集 run
  Then 全部通過（本 spec 不動 ACL）
```

驗證指令：`cd backend && ./gradlew test`（含新 SkillUserJoinTest、UsersUpsertTest、DisplayNameResolverTest）+ `cd frontend && npm test`（getDisplayName helper test、SkillCard render test）

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V18__create_users_and_author_snapshot.sql` | 新增 — users table + skills snapshot columns |
| `backend/src/main/java/.../shared/security/User.java` | 新增 entity（@Table users，sub PK，email/name/handle/avatar/...) |
| `backend/src/main/java/.../shared/security/UserRepository.java` | 新增 — Spring Data JDBC，含 `findByHandle()` / `findBySub()` |
| `backend/src/main/java/.../shared/security/UserUpsertService.java` | 新增 — `/me` 呼叫時 UPSERT user，含 handle generate（email local-part；撞名加數字） |
| `backend/src/main/java/.../shared/security/MeController.java` | hook UserUpsertService — 每次 /me UPSERT |
| `backend/src/main/java/.../shared/security/CurrentUserProvider.java` | 加 `getProfile()` method 回 name/email/avatar |
| `backend/src/main/java/.../skill/domain/Skill.java` | 加 `authorNameSnapshot`, `authorEmailSnapshot` fields |
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | publish/republish 時填 snapshot |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | 改成 join users 拿 live `authorDisplayName`，fallback snapshot |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `/skills/{author}/{name}` 改先比 handle 再比 sub（向下相容） |
| `backend/src/main/java/.../shared/security/DisplayNameResolver.java` | 新增 static helper，OAuth claims → display name |

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/types/skill.ts` | 加 `authorDisplayName?: string`、`authorNameSnapshot?: string`、`authorEmail?: string`、`authorHandle?: string` |
| `frontend/src/lib/displayName.ts` | 新增 `getDisplayName()` helper |
| `frontend/src/components/SkillCard.tsx` | 改用 helper |
| `frontend/src/components/v2/PageHeader.tsx` | 改用 helper；作者欄加 link 至 `/users/{handle}` (留未來；先純文字) |
| `frontend/src/components/v2/InstallCard.tsx` | install command 改用 `skill.authorHandle ?? skill.author` |
| `frontend/src/components/AppShell.tsx` | profile dropdown 顯 priority：name > email > sub-suffix |
| `frontend/src/pages/SkillDetailPage.tsx` | 作者顯示 helper；contact button conditional |
| `frontend/src/components/ReviewsPanel.tsx` | review.author 走 helper（如果 review API 也改 expose 同欄位） |
| `frontend/src/pages/MySkillsPage.tsx` | hero「以 X 身份發布」 — 已有邏輯，確認生效 |
| `frontend/src/components/EmptyState.tsx` 等顯示 author 文案 | sweep 一遍 |

### Test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../shared/security/DisplayNameResolverTest.java` | 新增 — pure unit |
| `backend/src/test/java/.../shared/security/UserUpsertServiceTest.java` | 新增 — UPSERT logic + handle collision |
| `backend/src/test/java/.../skill/SkillAuthorJoinIntegrationTest.java` | 新增 — Testcontainers + Flyway，verify join + snapshot fallback |
| `frontend/src/lib/displayName.test.ts` | 新增 |
| `frontend/src/components/SkillCard.test.tsx` | 新增 — render 驗 author display |

---

## 5. Test Plan

### 5.1 自動化（gradlew test + npm test）

- AC-1：`SkillQueryServiceIntegrationTest` 跑 join users 場景
- AC-2：`InstallCard.test.tsx` 驗 command 字串含 handle 而非 sub
- AC-3：`SkillQueryControllerTest` 驗 `/skills/{old-sub}/{name}` fallback
- AC-4：`UserUpsertServiceTest` UPSERT 改 name → 下次 join 取新值
- AC-5：手動 SQL 模擬 users row delete，integration 驗 snapshot fallback
- AC-6：`SkillDetailPage.test.tsx` 驗 contact button 預設 hide
- AC-7：手動測 + spec note：toggle UI 為 follow-up
- AC-8：`V18__migration_test`（Flyway clean migrate verify backfill 正確）
- AC-9：既有 RBAC test suite must pass unchanged

### 5.2 手動 LAB 驗證

deploy 後：
- [ ] 登入 LAB → 確認 /api/v1/me 觸發 users 表 UPSERT（DB 查 users 應有 1 row）
- [ ] 訪問 LandingPage → SkillCard 顯 OAuth name（不再 21 位數字）
- [ ] 訪問 SkillDetail → install command 含 handle 短形式
- [ ] 改 Google account display name → 重登 → /me UPSERT → SkillDetail 顯新名
- [ ] 既有 skill 仍能讀取（向下相容）

---

## 6. 設計風險與緩解

| 風險 | 緩解 |
|------|------|
| handle 撞名（兩個 alice@diff-domain）| UPSERT 時自動加 `-2`/`-3`/...；email-derive 失敗時 fallback `user-{sub-suffix}` |
| handle 含特殊字元 / 非 ASCII | local-part 先過 slugify（lowercase + 移除非 `[a-z0-9-]` + 縮短到 ≤32 chars） |
| OAuth `name` 含特殊字元 / XSS | DB 存 raw，frontend render 用 React 預設 escape 即可（無需另寫 sanitizer） |
| User 帳號刪除 → ACL 漏洞 | 本 spec 不動 ACL，只動 display；ACL 仍 query `acl_entries.principal = sub`，刪 users row 不影響 |
| Email PII 曝露 | default `contact_email_public = false`；read-side 主動 filter（即使 API leak 也擋 frontend）|
| 既有 skill snapshot NULL backfill | 第一次部署+登入後 join 自動填；displayName helper 有 4-tier fallback，不會 crash UI |
| `Skill` 領域物件爆炸（充血聚合 invariant）| snapshot fields 是 mutable VALUE，不參與 invariant；只在 publish/republish 寫一次 |

---

## 7. 後續 follow-up（不在本 spec）

- **S155**: User profile page `/users/{handle}` — 列出該作者所有 skill
- **S156**: User settings UI — 改 handle、toggle email 公開、頭像上傳
- **S157**: Organization / namespace（agentskills.io standard）— 多人共同維護一組 skill
- **S158**: 平台內 message — 不公開 email 也能聯絡作者
- **S159**: User soft-delete 流程 + skill 「已停用作者」標籤 polish
