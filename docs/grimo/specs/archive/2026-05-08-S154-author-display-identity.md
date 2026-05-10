# S154: Author Display Identity (Backend) — 平台 user_id 解耦 OAuth sub

> Spec: S154 | Size: M(12) | Status: ✅ Done (2026-05-11)
> Date: 2026-05-08（v3 split 2026-05-09 — planning-tasks size gate 拆出 S154b frontend）
> Origin: deployment audit 2026-05-08（LAB）— `skill.author` 存 OAuth sub `111161306011023995106`，導致 SkillCard / PageHeader / InstallCard / Profile dropdown / LandingPage cards / Reviews / Flags 全顯示一串無人能讀的 21 位數字。Install command `skills-hub install 111161306011023995106/auditing-terraform-...` 完全沒人記得起來。
>
> **本 spec scope = backend foundation**。Frontend display rollout + ShareSkillModal polish 拆至 [S154b](./2026-05-09-S154b-author-display-frontend.md)。

---

## 1. Goal

**一句話：** 後端建立 `users` 表把 OAuth `sub` 跟平台 `user_id` 解耦；既存 `skills.author` / `owner_id` / `acl_entries` 全部切到 `user_id`；API 回傳作者顯示資訊（displayName / handle / email-conditional）給未來 frontend 用；順手修「caller 偽造 author」漏洞。

**為什麼重要：**
- **可發現性歸零**：使用者看到 `111161306011023995106` 完全無法判斷是誰
- **OAuth provider lock-in**：sub 散在 `skills.author` + `acl_entries` JSONB，未來換 / 加 OAuth provider 就斷
- **Forgery 漏洞**：`POST /skills` 收 `@RequestParam("author") String` 後端不校驗，Bob 可填 Alice 的 sub 偷掛 skill
- **Frontend 無資料可顯**：S154b 要 render「作者：Alice Chen」必須先有 backend 回 `authorDisplayName` 等欄位

**非目標：**
- 不動 frontend（S154b 處理）
- 不做 ShareSkillModal polish（S154b 處理）
- 不做 ACL 機制改動（principal 比對方式不變，只是 principal 字串從 sub 改成 user_id）
- 不做 user profile 編輯頁 / 帳號連結 / org namespace（見 §7）

---

## 2. Approach

### 2.1 現況回顧（已驗證 2026-05-09）

**寫入路徑**（publish skill）：
```
SkillCommandController.publishSkill()
  ← 收 @RequestParam("author") String author    （caller-supplied，server 不校驗 — 這是漏洞）
  → Skill.create(..., author=<caller-supplied-sub>, ...)
  → repo.save() → skills.author = "111161306011023995106"（OAuth sub raw）
  → acl_entries[0] = "user:111161306011023995106:OWNER"
```

**讀取路徑**（Skill detail）：
```
GET /api/v1/skills/{id} → Skill record → JSON.author = "111161306011023995106"
（frontend 直接顯示 raw author — 屬 S154b 範圍）
```

**現有資產**（已 ship，無需另做）：
- `MeController` `GET /api/v1/me` 已回 9 個 keys（S141 v4.21.0 ship）：`sub / email / name / picture / roles / groups / companyId / deptId / scope`
- `CurrentUserProvider.current()` 回 `CurrentUser(userId, roles, groups, companyId)` — 但 `userId` **目前等於 OAuth sub**（本 spec 要改成 platform user_id）
- `JwtAuthenticationToken` extract claim 模式已在 `MeController` 建立可參考

**Database 現況**：
- `skills.author` `VARCHAR` 存 OAuth sub raw（既存 3 筆 row）
- `skills.owner_id` `VARCHAR` 存 OAuth sub raw（S114a / S016）
- `acl_entries` JSONB List<String>，內容如 `["user:<sub>:OWNER", "public:*:read"]`
- 無 `users` 表

### 2.2 設計核心：Platform user_id 解耦 OAuth sub

```
┌───────────────────────────┐         ┌──────────────────────────┐
│   OAuth Provider          │         │   Skills Hub Platform    │
│   (Google / GitHub / ...) │         │                          │
│                           │         │  users:                  │
│   sub = 111161306...      │ ──UPSERT→  id = "u_a3f9c1"         │
│   email = alice@..        │   /me   │  oauth_provider="google" │
│   name = "Alice Chen"     │         │  sub = "111161306..."    │
│                           │         │  email / name / handle   │
└───────────────────────────┘         └──────────────────────────┘

之後平台所有地方只用 user_id（"u_a3f9c1"）：
  skills.author = "u_a3f9c1"
  skills.owner_id = "u_a3f9c1"
  acl_entries = ["user:u_a3f9c1:OWNER", "public:*:read"]
  API 回傳: { author: "u_a3f9c1", authorDisplayName: "Alice Chen",
             authorHandle: "alice", authorEmail: <conditional> }
```

**Platform user_id 格式：** `u_<6-hex>` — 從 `UUID.randomUUID()` 取前 6 hex 字元，UNIQUE check + collision retry。

### 2.3 OAuth provider 多供應商支援（schema 預留，UI 不做）

`users` 表加 `oauth_provider` 欄位（MVP 全 `'google'`），`UNIQUE(oauth_provider, sub)` composite key。未來 GitHub 上線：Alice 用 GitHub 登入 → 開**新 row**（不做帳號連結；升級路徑見 §7 S170）。

### 2.4 Display Name 計算規則

`DisplayNameResolver` static helper（pure function；frontend `lib/displayName.ts` 同邏輯，由 S154b 實作）：

優先序：
1. `name`（OIDC standard claim — Google 提供 full name → "Alice Chen"）
2. `given_name + " " + family_name`
3. `email` 的 local-part（`@` 前），首字大寫 → "Alice"
4. `handle` → "alice"
5. `user_id` → "u_a3f9c1"（**永遠不會 fall 到 raw OAuth sub**）

### 2.5 Snapshot vs Live —「user 改名 / 刪帳號後既有 skill 顯示」

**選擇：** 顯示優先 live（join `users` 表），無資料 fallback `skills.author_name_snapshot`。

| 變化 | 結果 |
|------|------|
| Alice 改 Google name | 下次 /me UPSERT → users.name 更新 → API live 回新名 |
| Alice 帳號被 admin 刪除（users row 刪） | API fallback 回 `skills.author_name_snapshot`（publish 時 freeze），可加 polish「（已停用）」 |

**Snapshot 範圍：** 只存 `author_name_snapshot`（VARCHAR 255），**不存 email**（PII 副本越少越好）。極端 fallback 走 `user_id` 而非 email local-part。

### 2.6 Username Slug — handle 規則

**生成規則（first /me on signup）：**
1. Slugify email local-part：`alice@example.com` → `alice`
2. 過濾：lowercase、移除非 `[a-z0-9-]`、縮短到 ≤ 32 chars
3. 撞名 retry：`alice` 被佔 → `alice-2` → `alice-3` → ...
4. local-part 太怪（純數字、空字串）→ fallback `user-<6-hex>`（同 user_id 後綴）

**Mutability：**
- `user_id` (`u_a3f9c1`) — 永遠不變（內部 PK + ACL principal）
- `handle` (`alice`) — 可改（本 spec 不做改 handle UI，留 §7 S169 followup）

`/api/v1/skills/{author}/{name}` resolve order：handle → user_id → sub（向下相容老 install command）。

### 2.7 Email 公開 / Contact 機制

`users.contact_email_public BOOLEAN DEFAULT FALSE` — user 自選是否公開 email。

| flag | API 回傳 |
|------|---------|
| FALSE（default） | `authorEmail` 不出現在 SkillResponse JSON |
| TRUE | `authorEmail = "alice@example.com"` |

**MVP 範圍**：schema + read-side filter ready；toggle UI 留 follow-up（手動 SQL 設 TRUE 可驗證）。

### 2.8 Migration — V18 Schema (Fresh Start)

> **2026-05-10 user 決策**：資料庫舊資料會清掉，V18 不做 backfill。Local dev 跑 `cd backend && docker compose down -v && ./gradlew bootRun` 走 V1-V18 fresh 即可；既有 3 筆 skill / skill_grants / acl_entries 全棄。Original V18 backfill `DO $$` block 連同 placeholder email 機制（AC-9）一起 drop。

```sql
-- V18__create_users_and_decouple_oauth_sub.sql

-- 1. users 表 — platform user_id 解耦 OAuth sub
CREATE TABLE users (
    id                     VARCHAR(20)  PRIMARY KEY,        -- "u_<6hex>"
    oauth_provider         VARCHAR(20)  NOT NULL,           -- 'google' (MVP)
    sub                    VARCHAR(255) NOT NULL,           -- OAuth provider sub
    email                  VARCHAR(320) NOT NULL,
    name                   VARCHAR(255),
    handle                 VARCHAR(64)  UNIQUE NOT NULL,
    avatar_url             TEXT,
    contact_email_public   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL,
    last_seen_at           TIMESTAMPTZ  NOT NULL,
    UNIQUE(oauth_provider, sub)
);
CREATE INDEX idx_users_email ON users(email);

-- 2. skills 加 snapshot column（只存 name，不存 email）
ALTER TABLE skills ADD COLUMN author_name_snapshot VARCHAR(255);
```

**冪等保證**：fresh schema 純 DDL；二次跑 Flyway 因 `flyway_schema_history` 有 V18 row 自動 skip。

**舊資料清空後的全新流程**（V18 ship 後第一次跑 LAB）：
1. User 第一次 Google 登入 → `MeController` UPSERT → 建 users row（真實 email + name + handle）
2. User publish skill → `Skill.create()` 寫 author = user_id（從 `CurrentUserProvider`），author_name_snapshot = name
3. ACL 寫入經 `SkillGrantService` → 寫 skill_grants 用 user_id principal → projection 重建 skills.acl_entries

skill_grants / acl_entries 全程從零累積，永不會跨 sub/user_id 兩種 principal 格式混雜。

### 2.9 Application code 改動（summary — 詳 §4）

**新建：**
- `User` entity / `UserRepository` / `UserUpsertService` / `UserResolver` / `DisplayNameResolver` — 放 `shared/security/`

**改動：**
- `MeController` hook UserUpsertService；response 加 `userId / handle`
- `CurrentUserProvider.current()` — JWT sub → users 表查 → 回 `CurrentUser(userId=<platform user_id>, sub, name, email, handle, roles, groups, companyId)`
- `SkillCommandController.publishSkill()` — **拒收 `author` request param**，server 自取 `currentUserProvider.userId()`
- `Skill.create()` 加 `authorNameSnapshot` 參數（從 `currentUserProvider.name()` 取）
- `SkillQueryService.findById/search()` LEFT JOIN users → 回應加 `authorDisplayName / authorHandle / authorEmail`（後者 conditional）
- `SkillQueryController.getByAuthorAndName()` — resolve order: handle → user_id → sub

**Modulith 邊界：** `User` 放 `shared/security/`（既有 named interface，無 cycle 風險）；`skill` module 既有 `allowedDependencies = {"shared :: security", ...}` → User 自動 visible。

---

## 3. Acceptance Criteria

```
AC-1: V18 migration 建 fresh schema 成功（per 2026-05-10 user 決策 — 舊資料清空、不做 backfill）
  Given Flyway clean DB（或 docker compose down -v 後 fresh 重跑 V1-V17）
  When V18 migrate
  Then users 表存在
    And 含欄位：id VARCHAR(20) PK / oauth_provider VARCHAR(20) NOT NULL / sub VARCHAR(255) NOT NULL
        / email VARCHAR(320) NOT NULL / name VARCHAR(255) / handle VARCHAR(64) UNIQUE NOT NULL
        / avatar_url TEXT / contact_email_public BOOLEAN NOT NULL DEFAULT FALSE
        / created_at TIMESTAMPTZ NOT NULL / last_seen_at TIMESTAMPTZ NOT NULL
    And UNIQUE(oauth_provider, sub) constraint 存在
    And idx_users_email 索引存在
  And skills 表新增 author_name_snapshot VARCHAR(255) NULLABLE 欄位
  And 二次跑 Flyway no-op（schema_history 中 V18 row → skip）

AC-2: /me 觸發 UPSERT，新 user 建 row + 舊 user refresh
  Given Alice 第一次用 Google 登入（JWT sub=111161..., email=alice@..., name="Alice Chen"）
  When 呼叫 GET /api/v1/me
  Then users 表新增 1 row（id=u_<6hex>, oauth_provider='google', sub='111161...', email='alice@...', name='Alice Chen', handle='alice', created_at=now, last_seen_at=now）
  And response JSON 含 `userId="u_<6hex>"` + `handle="alice"`
  Given Alice 第二次登入（OAuth name 改成「Alice Liu」）
  When 呼叫 /me
  Then users row 同 id，但 name 更新為 "Alice Liu"，last_seen_at refresh

AC-3: SkillCommandController 拒收偽造 author（forgery 漏洞修復）
  Given Bob 已登入（JWT sub=bob_sub → user_id=u_bob_xx）
  When Bob POST /api/v1/skills 帶 multipart 不含 `author` 欄位
  Then 後端從 currentUserProvider.userId() 取 u_bob_xx 寫入 skills.author
  Given Bob POST /api/v1/skills 帶 `author=u_alice_xx` (試圖偽造)
  Then 後端 ignore 該 param，仍寫 u_bob_xx (或 400 拒收 — 實作擇一)

AC-4: ACL principal 切換到 user_id (既有 RBAC 行為等價)
  Given V18 backfill 跑完，acl_entries 從 "user:<sub>:OWNER" 改成 "user:<user_id>:OWNER"
  And CurrentUserProvider.current().userId() 回 user_id (非 sub)
  When 既有 RBAC 測試集 run（owner edit / viewer read / public anonymous read）
  Then 全部通過（principal 比對機制不變，比對值對齊 user_id）

AC-5: Skill aggregate freeze authorNameSnapshot 於 publish/republish
  Given Alice publish skill (CurrentUserProvider.name()="Alice Chen")
  When skill.publishVersion(...) 觸發 (initial publish 或 new version)
  Then skills.author_name_snapshot = "Alice Chen"
  Given Alice 改名 "Alice Liu" 後 republish
  Then snapshot 更新為 "Alice Liu"

AC-6: SkillQueryService LEFT JOIN users 回 authorDisplayName / Handle / Email-conditional
  Given Alice (users.name="Alice Chen", handle="alice", contact_email_public=false) publish skill
  When GET /api/v1/skills/{id}
  Then response JSON 含 `authorDisplayName="Alice Chen"` + `authorHandle="alice"`
  And **不**含 `authorEmail` 欄位（contact_email_public=false）
  Given Alice 改 contact_email_public=true
  When GET /api/v1/skills/{id}
  Then response 含 `authorEmail="alice@example.com"`
  Given users row 被刪 (但 skills.author_name_snapshot="Alice Chen" 仍在)
  When GET /api/v1/skills/{id}
  Then response 含 `authorDisplayName="Alice Chen"` (snapshot fallback)

AC-7: GET /skills/{author}/{name} resolve order: handle → user_id → sub
  Given Alice (handle="alice", user_id="u_a3f9c1", sub="111161...")
  When GET /api/v1/skills/alice/my-skill
  Then 回 200 (handle 中)
  When GET /api/v1/skills/u_a3f9c1/my-skill
  Then 回 200 (user_id 中)
  When GET /api/v1/skills/111161306011023995106/my-skill
  Then 回 200 (sub 中 — 向下相容老 install command)
  When GET /api/v1/skills/nonexistent/my-skill
  Then 回 404

AC-8: user_id 格式校驗 + collision retry
  Given UserUpsertService 連續生成 1000 user_id
  When 全部 INSERT users
  Then 每個 user_id 符合 `u_[0-9a-f]{6}` regex
  And 無 PRIMARY KEY conflict (collision 時 retry up to 5 次)

AC-9: ❌ DROPPED 2026-05-10 — 資料庫舊資料清空、無 backfill 需要
  原 BDD：backfill placeholder email 在 user 真登入後被覆寫
  Drop 理由：V18 不再做 backfill，placeholder email 機制不存在；user 第一次 /me 觸發
           UPSERT 直接建真實 row（已涵蓋於 AC-2）。
```

**驗證指令：** `cd backend && ./gradlew test`（含新 `UserRepositoryTest` / `UserUpsertServiceTest` / `DisplayNameResolverTest` / `UserResolverTest` / `SkillAuthorJoinIntegrationTest` / `V18MigrationTest` / `SkillPublishForgeryTest`）

---

## 4. Files to Change

### Backend production code

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V18__create_users_and_decouple_oauth_sub.sql` | **新增** — users 表 + skills.author_name_snapshot + backfill DO block |
| `backend/src/main/java/.../shared/security/User.java` | **新增** — `@Table("users")` Spring Data JDBC entity |
| `backend/src/main/java/.../shared/security/UserRepository.java` | **新增** — `ListCrudRepository<User, String>` + findByOauthProviderAndSub / findByHandle / findByEmail |
| `backend/src/main/java/.../shared/security/UserUpsertService.java` | **新增** — /me 觸發 UPSERT；user_id `u_<6hex>` + collision retry；handle slugify + collision retry |
| `backend/src/main/java/.../shared/security/UserResolver.java` | **新增** — `resolveByEmailHandleOrId(String) → user_id`（給未來 grant 端點 + Query Controller resolve order 用） |
| `backend/src/main/java/.../shared/security/DisplayNameResolver.java` | **新增** — pure static helper，五層 fallback |
| `backend/src/main/java/.../shared/security/MeController.java` | hook `UserUpsertService.upsertFromAuthentication()`；response 加 `userId` + `handle` |
| `backend/src/main/java/.../shared/security/CurrentUserProvider.java` | `current()` 改：JWT sub → users 查 → 回 `CurrentUser(userId=<platform>, sub, name, email, handle, roles, groups, companyId)` |
| `backend/src/main/java/.../shared/security/CurrentUser.java` | record 加 `sub / name / email / handle` 4 個 fields |
| `backend/src/main/java/.../skill/domain/Skill.java` | 加 `authorNameSnapshot` `@Column` field |
| `backend/src/main/java/.../skill/command/CreateSkillCommand.java` | 加 `authorNameSnapshot` field |
| `backend/src/main/java/.../skill/command/SkillCommandController.java` | **拒收 `author` request param**；server 從 `currentUserProvider.userId()` 取；snapshot 從 `currentUserProvider.name()` 取 |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | `findById` / `search` 改 LEFT JOIN users → 回 authorDisplayName / Handle / Email-conditional |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `/skills/{author}/{name}` resolve order: handle → user_id → sub |

### Backend test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../shared/security/DisplayNameResolverTest.java` | **新增** — 五層 fallback pure unit |
| `backend/src/test/java/.../shared/security/UserUpsertServiceTest.java` | **新增** — UPSERT logic + handle/user_id collision retry |
| `backend/src/test/java/.../shared/security/UserRepositoryTest.java` | **新增** — `@DataJdbcTest` `RepositorySliceTestBase` derived queries |
| `backend/src/test/java/.../shared/security/UserResolverTest.java` | **新增** — email / handle / user_id 三種輸入 resolve |
| `backend/src/test/java/.../skill/SkillAuthorJoinIntegrationTest.java` | **新增** — `@SpringBootTest` Testcontainers，verify LEFT JOIN + snapshot fallback + email-conditional |
| `backend/src/test/java/.../skill/V18MigrationTest.java` | **新增** — Flyway clean migrate verify backfill 正確 |
| `backend/src/test/java/.../skill/command/SkillPublishForgeryTest.java` | **新增** — AC-3 偽造 author 拒收 / 自動覆寫 |

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

| AC | 驗證方式 |
|----|---------|
| AC-1 | `V18MigrationTest`（Flyway clean migrate verify backfill 三個 dimension：users INSERT / skills.author UPDATE / acl_entries JSONB rewrite） |
| AC-2 | `UserUpsertServiceTest` UPSERT 新 user + 改 name 既存 user |
| AC-3 | `SkillPublishForgeryTest` 不帶 author param + 帶錯 author param 都驗證 |
| AC-4 | 既有 RBAC test suite must pass unchanged（V18 backfill 後 acl_entries 內容變但語意等價）|
| AC-5 | `SkillAuthorJoinIntegrationTest` publish + republish 兩次驗 snapshot freeze |
| AC-6 | `SkillAuthorJoinIntegrationTest` 三 case：default hide email / public show email / users row 刪除 fallback snapshot |
| AC-7 | `SkillQueryControllerTest` 四 path：handle / user_id / sub / 404 |
| AC-8 | `UserUpsertServiceTest` 跑 1000 user 生成驗 user_id 格式 + 唯一 |
| AC-9 | `UserUpsertServiceTest` placeholder email 被覆寫場景 |

### 5.2 手動 LAB 驗證（S154 ship 後，S154b 未 ship 前）

deploy 後：
- [ ] 登入 LAB → DB 查 `users` 應有 1 row（id=`u_<6hex>`、oauth_provider='google'、handle 從 email derive）
- [ ] `curl /api/v1/me -H "Authorization: Bearer ..."` → response 含 `userId / handle`
- [ ] `curl /api/v1/skills/{id}` → response 含 `authorDisplayName / authorHandle`，無 `authorEmail`（default）
- [ ] `curl /api/v1/skills/alice/my-skill` 中（handle resolve）
- [ ] `curl /api/v1/skills/u_a3f9c1/my-skill` 中（user_id resolve）
- [ ] `curl /api/v1/skills/111161...../my-skill` 中（sub backwards compat）
- [ ] 偽造 publish request 帶 `author=other_user_id` → 後端覆寫成自己的 user_id

> 注意：SkillCard / install command 仍顯舊 `author` raw（待 S154b ship）

---

## 6. Task Plan

**POC: not required** — pattern 是教科書（users 表 + Flyway backfill + Spring Data JDBC entity + JOIN）；無新 framework SPI / external API；風險在 coordination（cross-cutting ACL principal switch）不在 technique。

### Tasks

| ID | 標題 | 涵蓋 AC | 主要檔案 | Depends On |
|----|------|--------|---------|-----------|
| T01 | V18 migration — users 表 + skills.author_name_snapshot column（fresh schema, no backfill per 2026-05-10 user 決策） | AC-1 | V18 SQL + V18MigrationTest | none |
| T02 | User domain（entity / repo / upsert / resolvers） | AC-8 | User.java + UserRepository + UserUpsertService + UserResolver + DisplayNameResolver + 4 unit tests | T01 |
| T03 | Auth refactor（CurrentUserProvider + MeController） | AC-2, AC-9 | CurrentUserProvider + CurrentUser + MeController + UserUpsertServiceTest extended | T02 |
| T04 | Command side（Skill snapshot + Controller forgery fix） | AC-3, AC-5 | Skill.java + CreateSkillCommand + SkillCommandController + SkillPublishForgeryTest | T03 |
| T05 | Query side（LEFT JOIN + Controller resolve order） | AC-6, AC-7 | SkillQueryService + SkillQueryController + SkillAuthorJoinIntegrationTest | T03 |
| T06 | ACL principal verification | AC-4 | 既有 RBAC test suite 跑（無需改 production code，純驗證 backfill 後等價）| T01 + T05 |

### Execution order

```
T01 ─┬──▶ T02 ──▶ T03 ──┬──▶ T04
     │                  └──▶ T05 ──▶ T06
     └──▶ (T06 也依 T01 — 等 backfill 完才能跑既有 RBAC test)
```

T04 + T05 可平行（都依 T03，無互相依賴）。但為避免同時改 Skill domain 衝突，序列跑。

---

## 7. Implementation Results

> Status: ✅ Done (2026-05-11) — 6 tasks shipped 序列；所有 9 AC PASS（AC-9 dropped per design 不算 fail）。

### 7.1 Verification

```
$ ./gradlew test
BUILD SUCCESSFUL in 2m 51s
Total: tests=719 failures=0 skipped=7

$ ./gradlew compileTestJava
BUILD SUCCESSFUL in 819ms
```

T06 收尾後：8 個 RBAC 整合測試（S016 / S120 / SemanticSearchIT）全綠；T03/T04 期間
暫時 `@Disabled` 的 8 個測試於 T06 完整 re-enable 並通過。`skipped=7` 為其他 spec
既有的條件性 disable，與 S154 無關。

### 7.2 E2E Verification (integration seam gate)

**評估：non-browser**。本 spec 的 critical seam 為：
- JWT auth → DB upsert（real Spring Security filter chain + real Postgres）
- ACL row-level filter via Postgres jsonb `?|` operator（real pgJDBC + real JSONB schema）
- LEFT JOIN users 端到端 enrich response（real Spring Data JDBC + 真實 schema）
- V18 Flyway migration 寫入端 schema（real container fresh-init）

**這些 seam 在 task loop 中已透過 `@SpringBootTest + Testcontainers` 覆蓋：**

| Seam | 覆蓋 test | 結果 |
|---|---|---|
| JWT → user_id UPSERT（mock-oauth2-server + 真 JWT decoder） | `SkillsHubAuthE2ETest.e2e_authAclDownloadFlow` | ✅ |
| JWT → user_id UPSERT（test JWT post-processor） | `S016EndToEndSmokeTest.*`（8 tests） | ✅ |
| ACL `?|` Postgres jsonb filter（owner edit / non-owner 403） | `S016.e2e_putVersion_acl_gate` + `SkillsHubAuthE2ETest` | ✅ |
| 跨 user namespace ACL（alice 看自己 skill / bob 隱蔽） | `SemanticSearchIntegrationTest.aliceSeesOnlyOwnSkills` | ✅ |
| LEFT JOIN users → authorDisplayName / handle / email-conditional | `SkillAuthorJoinIntegrationTest` 4 unit | ✅ |
| Author identity snapshot fallback when users row deleted | `withAuthorIdentityFallbackToSnapshotWhenUserRowMissing` | ✅ |
| V18 fresh-schema migration + idempotent re-run | `V18MigrationTest`（T01）| ✅ |
| Forgery prevention（caller author param ignored） | `SkillPublishForgeryTest`（T04）| ✅ |
| Resolver order（handle / user_id / sub / 404） | `UserResolverTest` 5+1 | ✅ |

**無瀏覽器/UI seam** — frontend display rollout 拆至 S154b。**無外部 CLI/binary** —
所有 contract 透過 HTTP API 驗證。整合 seam 全部 covered through real-stack
integration tests，**不需 Playwright/瀏覽器 E2E**。

### 7.3 Key Findings

#### F1. Dispatcher principal expansion 與 ACL 寫入端 user_id 對齊（T06 root-cause）

T03 改 `CurrentUserProvider.userId()` 回 platform user_id 後，`acl_entries` 寫入端
（`Skill.create()` line 219-223）也對齊用 platform user_id。但 `DelegatingPermissionEvaluator.expandPrincipals`
line 153 仍走 `auth.getName()`（JWT sub），導致 dispatcher 與 acl_entries 用兩個不同 identity，
@PreAuthorize 永遠 false。RED 才暴露此漏改點。

**Correct pattern：dispatcher 也走 `currentUserProvider.userId()`：**

```java
// DelegatingPermissionEvaluator.java（T06 fix）
private Set<String> expandPrincipals(Authentication auth, String permission) {
    var p = new HashSet<String>();
    // S154-T06：用平台 user_id（CurrentUserProvider）而非 JWT sub（auth.getName()）。
    // acl_entries 寫入的是 currentUser.userId()，評估端必須一致。
    p.add("user:" + currentUserProvider.userId() + ":" + permission);
    auth.getAuthorities().forEach(...);
    if ("read".equals(permission)) p.add("public:*:read");
    return p;
}
```

#### F2. Test fixture 用 pre-seed 固定 mapping 取代 random user_id

T03 之後 `(google, "alice")` upsert 產 random `u_<6hex>`，每次 test 不可預期 →
hardcoded `user:alice:read` ACL string 全失敗。T06 引入 `TestUserSeed`：
6 個固定 `(oauthSub → userId)` mapping 常數（`ALICE_ID="u_alice1"` 等），idempotent
`INSERT ... ON CONFLICT DO NOTHING`。後續 JWT auth 走 `upsertFromOidc("google", sub)`
找到既有 row 直接返穩定 user_id，hardcoded string 對得上。

**Test fixture pattern：**

```java
// shared/security/testsupport/TestUserSeed.java
public static final String ALICE_ID = "u_alice1";
public static void seedDefaults(JdbcTemplate jdbc) {
    seed(jdbc, ALICE_ID, "alice");      // (google, "alice") → "u_alice1"
    seed(jdbc, BOB_ID, "bob");
    // ... 6 個固定 mapping
}

// 任何 RBAC test 在 @BeforeEach 加：
TestUserSeed.seedDefaults(jdbc);

// 後續 assertion 走常數對齊：
.contains("user:" + TestUserSeed.ALICE_ID + ":read")
```

#### F3. WebMvcTest slice + 新依賴 → @MockitoBean pattern

T05 給 `SkillQueryController` 加 `UserResolver` 依賴後，既有 2 個 `@WebMvcTest` slice
（`SkillQueryControllerApiContractTest` / `SkillsApiAnonymousTest`）ApplicationContext
load 失敗，因為 slice 不 scan `@Service` bean。Pattern：一行加 `@MockitoBean UserResolver` 解決。
記入 dev-standards 隱性慣例。

#### F4. 資料庫 fresh schema 取代 backfill — 拿掉 AC-9

原設計 V18 含 backfill DO block 把舊 `acl_entries` 從 `user:<sub>` 改寫為 `user:<user_id>`。
T01 期間 user 反饋「沒關係 改用新設計, 資料庫舊資料會清掉」，drop 該 DO block 改 pure DDL。
AC-9（backfill placeholder email 正確覆寫）順帶 drop（後續 user 第一次 /me 觸發 UPSERT
直接建真實 row，placeholder 機制不存在）。**節省維護成本**：免 SQL backfill 邊界調整、
免測 placeholder fallback 行為、免擔心 backfill 對 prod row 的相容性。

#### F5. AOT processor 與 dual-ctor visibility-for-test pattern

T02 給 `UserUpsertService` 加 visible-for-test ctor（注入 `Supplier<String>` 控制 random
generator）後，AOT processor 失敗 "No constructor or factory method candidate found"。
Fix：給 production single-arg ctor 加 `@Autowired` 顯式 disambiguate。記為 development-standards
新加 pattern reference。

### 7.4 Pending Verification

無。E2E artifact verification 已透過 real-stack integration tests 完整覆蓋
（見 §7.2 表）。無 ⏳ 標記項目。

### 7.5 AC Results

| AC | 對應 test | 結果 |
|----|----------|------|
| AC-1: V18 fresh schema | `V18MigrationTest`（T01） | ✅ |
| AC-2: /me UPSERT first + refresh | `MeControllerTest` + `UserUpsertServiceTest`（T03） | ✅ |
| AC-3: forgery prevention | `SkillPublishForgeryTest`（T04） + `S016.postThenGetSkill_jsonRoundTrip` | ✅ |
| AC-4: ACL principal user_id 等價 | 8 RBAC tests（T06）| ✅ |
| AC-5: snapshot freeze on publish/republish | `SkillCommandServiceTest` snapshot capture | ✅ |
| AC-6: LEFT JOIN + email-conditional + snapshot fallback | `SkillAuthorJoinIntegrationTest` 4 + service unit | ✅ |
| AC-7: resolve order handle / user_id / sub / 404 | `UserResolverTest` 5+1 | ✅ |
| AC-8: user_id 格式 + collision retry | `UserUpsertServiceTest`（T02）| ✅ |
| AC-9: ❌ DROPPED 2026-05-10（無 backfill）— per F4 | n/a | n/a |

### 7.6 Tech Debt

無新增 tech debt。F1 / F3 / F5 已 fix in-place；F4 為 user 主動精簡 scope，
非延後項；F2 為 fixture pattern 改善（已落地，不需 follow-up）。

---

### 7.7 QA Review (Independent Verification — 2026-05-11)

**Reviewer**: Independent subagent (general-purpose)
**Verdict**: PASS (MINOR findings — no blocking issues)

**Verification commands**:
- `./gradlew test` → tests=719 failures=0 skipped=7
- `./gradlew compileTestJava` → BUILD SUCCESSFUL (UP-TO-DATE)

**AC Coverage** (each must have matching @DisplayName test):
| AC | Test | Status |
|---|---|---|
| AC-1: V18 fresh schema | `V18MigrationTest` 5 tests (@Tag("AC-1")) | ✅ PASS |
| AC-2: /me UPSERT first + refresh | `MeControllerTest` (userId+handle assertions) + `UserUpsertServiceTest` (real UPSERT via @SpringBootTest); no dedicated `@Tag("AC-2")`/`@DisplayName("AC-2: /me UPSERT…")` for S154 UPSERT scenario | ⚠ COVERED (indirect) |
| AC-3: forgery prevention | `SkillPublishForgeryTest` 3 tests (@Tag("AC-3")) | ✅ PASS |
| AC-4: ACL principal user_id equiv | `S016EndToEndSmokeTest` (TestUserSeed + ALICE_ID assertions) + `SemanticSearchIntegrationTest` | ✅ PASS |
| AC-5: snapshot freeze on publish/republish | `SkillAuthorJoinIntegrationTest` 5 tests (@Tag("AC-5")) | ✅ PASS |
| AC-6: LEFT JOIN + email-conditional + fallback | `SkillAuthorJoinIntegrationTest` 4 tests (@Tag("AC-6")) | ✅ PASS |
| AC-7: resolve order handle/user_id/sub/404 | `UserResolverTest` 6 tests (@Tag("AC-7")) | ✅ PASS |
| AC-8: user_id format + collision retry | `UserUpsertServiceTest` 6 tests (@Tag("AC-8")) | ✅ PASS |
| AC-9: DROPPED | n/a | n/a |

**Findings**:

1. MINOR: `DelegatingPermissionEvaluator` class-level Javadoc (line 40-43) says "dispatcher 僅展開 `user:` / `role:` 兩命名空間（從 `Authentication` 直接取，避免循環依賴 `CurrentUserProvider`）" — but after T06, `expandPrincipals` now calls `currentUserProvider.userId()` directly, not `auth.getName()`. The class-level `<h2>Principal 展開分工</h2>` block contradicts the actual implementation. The constructor Javadoc (line 62-66) correctly explains the T06 change, so the risk is reader confusion. Suggest updating the class-level block to remove the "避免循環依賴 CurrentUserProvider" clause and note T06 changed this. No runtime impact.

2. MINOR: `UserRepository` Javadoc at line 15 references `{@link #findByEmail}` but the actual method name is `findFirstByEmail`. Spec §4 table also says "findByEmail". The `{@link}` reference points to a non-existent method (broken @link tag). Actual code and behavior are correct; only the Javadoc link is stale.

3. MINOR: `Skill.create()` Javadoc (line 153-157) does not mention the `authorNameSnapshot` field added in T04 (S154 AC-5). The Javadoc only references S024/S016 design. Per CLAUDE.md "Spec-Linked Rationale" policy, the factory Javadoc should note the `authorNameSnapshot` parameter from `CreateSkillCommand` and its S154 origin. No runtime impact.

4. OBSERVATION: AC-2 has no dedicated `@Tag("S154-AC-2")` or `@DisplayName("AC-2: /me UPSERT 新 user 建 row + response 含 userId+handle")` test in the S154 context. Coverage exists but is scattered: `MeControllerTest` verifies the JSON response shape with `userId`/`handle` using mocked `CurrentUserProvider`; `UserUpsertServiceTest` verifies the real UPSERT logic. An integration test calling real `/me` endpoint and asserting DB row creation + `userId`/`handle` in response would close this gap, but the existing combination is functionally adequate. Not blocking.
