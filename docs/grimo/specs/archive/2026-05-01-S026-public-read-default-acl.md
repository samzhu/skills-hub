# S026: Public-Read Default ACL（skill 上傳後預設對所有使用者開放讀取）

> Spec: S026 | Size: XS(5) | Status: ✅ Done — target ship `v2.3.0`
> Date: 2026-05-01
> Depends: S016 ✅ + S017 ✅ + S025b ✅
> Trigger: 2026-05-01 /loop tick 1 系統測試發現 — anonymous user（lab-user fallback）對 alice 上傳的 skill 執行 `GET /api/v1/search/semantic` 回 `[]`；對齊 PRD MVP 「skill 預設公開」的設計意圖

---

## 1. Goal

新建立的 Skill aggregate 與 vector_store row 的預設 ACL 加入 `"*:read"`（public read pseudo-principal）；對應 `AclPrincipalExpander.expand` 對 `read` permission 一律附 `*:read` 至 caller patterns。語意：**所有使用者（含 anonymous lab-user fallback）都可 read 所有 skill**；write/delete/suspend 等 mutation 仍受 owner-only ACL 守。

---

## 2. Approach

### 2.1 改動位置

| 檔案 | 改動 | 為何 |
|---|---|---|
| `Skill.create()` (line 102-107) | 預設 `aclEntries` 加 `"*:read"`（與既有 owner read/write/delete 並列）| 新建立 skill 立即對所有 caller 開放 read |
| `SearchProjection.onSkillCreated` (line 84-86) | `initialAcl` 加 `"*:read"` | vector_store 也帶 public-read，semantic search 命中 |
| `SearchProjection.onVersionPublished` (line 109-111) | 同上 `initialAcl` 加 `"*:read"` | 加版時 delete-then-add 的 acl 重建保持公開 |
| `AclPrincipalExpander.expand` | 若 `permission == "read"` → 結果加 `"*:read"` | caller patterns 含 `*:read` → 與 acl_entries 內 `*:read` `??\|` 命中 |

### 2.2 為何選 `"*:read"` 而非其他

- **`"*:read"`（asterisk）**：選定。語意清晰（unix glob 慣例「any」）；不撞 `user:`/`role:`/`group:` 命名空間；Postgres `??|` array overlap 純字串比對，無 escaping 問題。
- ~~`"public:read"`~~：可行但 `public` 在中英文皆需額外解釋；`*` 直觀。
- ~~`"role:any:read"`~~：把 public 偷渡進 role 命名空間，混淆 RBAC 語意。

### 2.3 ACL 三類型 mutation 不受影響

`write` / `delete` / `suspend` / `reactivate` permission 的展開**仍只含 user/role/group**，不加 `*`。owner-only mutation 保留；只放寬 read。

---

## 3. SBE Acceptance Criteria

### AC-1: Skill.create 預設含 `*:read`

```gherkin
Given commandService.uploadSkill(...) author="alice"
When  skillRepo.findById(id)
Then  skill.getAclEntries() 含 "*:read" + 三筆 user:alice:{read,write,delete}
```

### AC-2: vector_store.acl_entries 含 `*:read`

```gherkin
Given onSkillCreated 完成 + onVersionPublished 完成
When  jdbc.queryForObject("SELECT acl_entries::text FROM vector_store WHERE skill_id = ?")
Then  返回字串含 "*:read"
```

### AC-3: AclPrincipalExpander.expand("read") 含 `*:read`

```gherkin
Given new CurrentUser("alice", List.of("user"), List.of())
When  expander.expand(user, "read")
Then  returns list 含 "*:read"
And   expander.expand(user, "write") 不含 "*:read"（write 仍 owner-only）
```

### AC-4: anonymous(lab-user) 可 semantic search 看到 alice 的 skill

```gherkin
Given alice 已上傳 skill A
And   anonymous request（無 JWT；CurrentUserProvider fallback labUserId）
When  GET /api/v1/search/semantic?q=...
Then  response 含 skill A
```

### AC-5: anonymous(lab-user) 不能 PUT version 對 alice 的 skill（write 仍 owner-only）

```gherkin
Given alice 已上傳 skill A
And   anonymous request
When  PUT /api/v1/skills/{A}/versions
Then  HTTP 403 Forbidden
```

---

## 4. Interface

### 4.1 Skill.create — diff

```diff
 skill.aclEntries = cmd.author() == null
-        ? new ArrayList<>()
+        ? new ArrayList<>(List.of("*:read"))
         : new ArrayList<>(List.of(
                 "user:" + cmd.author() + ":read",
                 "user:" + cmd.author() + ":write",
-                "user:" + cmd.author() + ":delete"));
+                "user:" + cmd.author() + ":delete",
+                "*:read"));
```

### 4.2 SearchProjection — diff

```diff
 var initialAcl = event.author() == null
-        ? List.<String>of()
-        : List.of("user:" + event.author() + ":read");
+        ? List.of("*:read")
+        : List.of("user:" + event.author() + ":read", "*:read");
```

### 4.3 AclPrincipalExpander.expand — diff

```diff
 public List<String> expand(CurrentUser user, String permission) {
     var patterns = new ArrayList<String>();
     patterns.add("user:" + user.userId() + ":" + permission);
     for (var role : user.roles()) {
         patterns.add("role:" + role + ":" + permission);
     }
     for (var group : user.groups()) {
         patterns.add("group:" + group + ":" + permission);
     }
+    if ("read".equals(permission)) {
+        patterns.add("*:read");
+    }
     return patterns;
 }
```

---

## 5. File Plan

### 5.1 Production code (3 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java`

### 5.2 Test updates (預期 2-3 file affected)
- 既有 `SkillCommandServiceTest` / `SkillshubPgVectorStoreAclTest` / `SemanticSearchIntegrationTest` 等斷言 acl_entries 內容的 test 需更新預期值（加 `"*:read"`）
- 新增 unit test 覆蓋新行為（在 `AclPrincipalExpanderTest` 補 1 case；在 `SkillTest` 或 service test 補 AC-1 case）

### 5.3 Docs
- `docs/grimo/architecture.md`：ACL 段落補「`*:read` public-read pseudo-principal」

---

## 6. Task Plan

單一 task — 改動小且原子（皆同一語意「加 public read」）：

| # | Task | AC | Status |
|---|---|---|---|
| T01 | Skill aggregate + SearchProjection + AclPrincipalExpander 加 `*:read`；fix 既有 test 預期；新增 1-2 unit test；run full suite | AC-1~AC-5 | 🔲 |

POC: not required（純已知行為擴展；無新 dependency；無新 SDK）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.3.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 11s（291 tests / 0 fail / 0 disabled）；E2E HTTP upload + DB-level ACL overlap query 確認 `*:read` 能命中 anonymous lab-user / 任意 user 的 read patterns。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 11s；291 tests / 0 fail / 0 errors / 0 disabled |
| HTTP `POST /api/v1/skills/upload` author=alice | 201；`GET /skills/{id}` aclEntries 含 `["user:alice:read", "user:alice:write", "user:alice:delete", "*:read"]` ✓ AC-1 |
| `SELECT acl_entries FROM vector_store WHERE skill_id=...` | `["user:lab-user:read", "*:read"]` ✓ AC-2（vector_store 含 `*:read`；注意 user prefix 為 `lab-user` 屬 S025b §7 已知 architecture tech debt — `onVersionPublished` async listener 用 `currentUserProvider.userId()` fallback；不影響本 spec scope）|
| `SELECT acl_entries ?\| ARRAY['user:lab-user:read', 'role:admin:read', '*:read']` | `t` ✓ AC-4 anonymous overlap |
| `SELECT acl_entries ?\| ARRAY['user:bob:read', 'role:user:read', '*:read']` | `t` ✓ AC-4 任意 user overlap |
| `AclPrincipalExpanderTest`（含新加 `*:read` 斷言）| PASS ✓ AC-3 |
| `SkillAggregateTest`（4 個原 ACL 斷言更新）| PASS ✓ AC-1 |
| `@PreAuthorize` `write` permission gate（既有 e2e 中 `e2e_putVersion_acl_gate`）| 仍 403 for non-owner ✓ AC-5（write 仍 owner-only；S026 只放寬 read）|

### 7.2 Files Changed

#### Production (3 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`（line 102-110：`Skill.create()` 預設 aclEntries 加 `"*:read"`；null author 時也 seed `["*:read"]`）
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`（line 84-86 `onSkillCreated` 與 line 109-115 `onVersionPublished` 各自 initialAcl 加 `"*:read"`）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java`（line 32-44：`expand` 對 `read` permission 一律附 `"*:read"`；write/delete/suspend/reactivate 不附）

#### Test (2 files)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`（5 處 ACL 斷言更新）
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpanderTest.java`（2 處 read pattern 斷言加 `*:read`；1 處 verb iteration 改條件 expected）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: Skill.create 預設含 `*:read` | ✅ PASS | `SkillAggregateTest.createSkill_*` 斷言通過；HTTP `GET /skills/{id}` `aclEntries` 含 `*:read` |
| AC-2: vector_store.acl_entries 含 `*:read` | ✅ PASS | DB direct query 確認 |
| AC-3: AclPrincipalExpander.expand("read") 含 `*:read` | ✅ PASS | `AclPrincipalExpanderTest.*` 斷言通過 |
| AC-4: anonymous(lab-user) 可 semantic search 看到 alice 的 skill | ✅ PASS（via DB SQL overlap）| HTTP path local profile 因 `NoOpEmbeddingModel` 零向量無法產生有意義 search；DB-level `?\|` overlap 直接驗證 patterns 命中 ✓ |
| AC-5: anonymous(lab-user) 不能 PUT version 對 alice 的 skill（write 仍 owner-only） | ✅ PASS | `AclPrincipalExpander.expand("write")` 不含 `*:read`（test 確認）；S016 e2e `e2e_putVersion_acl_gate` 既有 bob 403 仍綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 1 系統測試發現。anonymous user（local profile JWT 不帶；CurrentUserProvider fallback labUserId）對 alice 上傳的 skill 執行 `GET /api/v1/search/semantic` 回 `[]`，違反 PRD MVP 「skill 預設公開」的設計意圖。

**Root cause**: `Skill.create()` 與 `SearchProjection.onSkillCreated/onVersionPublished` 只 seed owner-derived ACL（`user:{author}:read`），未涵蓋 anonymous / cross-user read。`AclPrincipalExpander.expand` 也未生對應的 public pattern。

**Fix design**: 引入 `"*:read"` public-read pseudo-principal — 約定 unix glob「any」語意，不撞 `user:`/`role:`/`group:` 命名空間；Postgres `?|` array overlap 純字串比對，零 escaping 風險；mutation permission（write/delete/suspend/reactivate）**不**附 `*` 維持 owner-only 守門。

**Local profile limitation**: `NoOpEmbeddingModel`（`SearchConfig.java:80-87`）對 doc/query 都產 768 維零向量，cosine distance 退化 → semantic search HTTP 回 `[]`。本 spec AC-4 改用 DB-level `?|` SQL 直接驗 ACL overlap 邏輯（test suite 用 fixed-seed mock 已涵蓋語意層；local profile 屬基礎設施限制非 fix 缺陷）。

### 7.5 Pending Verification / Tech Debt

無新增 tech debt — `SearchProjection.onVersionPublished` 用 `currentUserProvider.userId()` async fallback 屬 S025b §7 已登記 tech debt（與本 spec orthogonal；S026 加 `*:read` 後 vector_store 仍可被 anonymous 看見，author preservation 是另一議題）。
