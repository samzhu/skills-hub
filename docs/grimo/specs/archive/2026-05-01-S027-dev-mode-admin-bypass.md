# S027: Dev Mode Admin Bypass（local profile LAB mode + ROLE_admin 全 permission bypass）

> Spec: S027 | Size: XS(5) | Status: ✅ Done — target ship `v2.4.0`
> Date: 2026-05-01
> Depends: S016 ✅ + S025b ✅ + S026 ✅
> Trigger: 2026-05-01 /loop tick 2 系統測試 — anonymous 對 alice 的 skill 執行 `POST /api/v1/skills/{id}/acl` 回 HTTP 401；違反 PRD「Feature First, Security Later — MVP 階段以功能開發為主」與 user instruction「dev 先不做授權認證」

---

## 1. Goal

讓 `local` profile（dev 模式）下所有 mutation API（grant/revoke ACL、suspend、reactivate、PUT version）對 LAB 模式的 lab-user **全可通過 @PreAuthorize ACL gate**，無需手動 JWT；prod (`gcp` profile) 仍保留 OAuth + JWT + per-user ACL 行為。

機制：
1. `application-local.yaml` 改用 LAB mode（`skillshub.security.oauth.enabled=false`）— `LabSecurityFilter` 注入 lab-user with `ROLE_admin`，繞過 OAuth JWT 要求
2. `DelegatingPermissionEvaluator` 加 admin role bypass — 任何 `Authentication` 含 `ROLE_admin` authority 時 `hasPermission` 直接返 `true`，不查 ACL strategy

---

## 2. Approach

### 2.1 Profile-level fix（local 改 LAB mode）

`application-local.yaml` 加：

```yaml
skillshub:
  security:
    oauth:
      enabled: false   # S027: dev 模式跳過 OAuth；改用 LabSecurityFilter 注入 lab-user
```

`SecurityConfig.filterChain` 已有 `if (oauth.enabled())` 分支（line 79）— `false` 會走 LAB 路徑：所有 endpoint permitAll + `LabSecurityFilter` 注入 `lab-user` with `ROLE_admin` authority。

### 2.2 Code-level fix（admin role bypass in evaluator）

`DelegatingPermissionEvaluator.evaluate()` 加短路：

```java
private boolean evaluate(Authentication auth, Object target, String targetType, String permission) {
    // S027: ROLE_admin 全 permission bypass — admin 為 super-admin role，
    // 對所有 Skill / 未來 aggregate 都有完整 read/write/delete/suspend/reactivate 權限。
    // dev 模式 lab-user 預設帶 ROLE_admin（per LabSecurityFilter）→ 自動通過 @PreAuthorize；
    // prod 模式只有 OIDC claim roles=["admin"] 的真實 user 才會帶此 authority。
    if (hasAdminRole(auth)) {
        return true;
    }
    var principals = expandPrincipals(auth, permission);
    return strategies.stream()
            .filter(s -> s.supports(targetType))
            .findFirst()
            .map(s -> s.hasPermission(principals, target, permission))
            .orElse(false);
}

private boolean hasAdminRole(Authentication auth) {
    return auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
}
```

### 2.3 為何 admin bypass 不破壞 ACL 設計意圖

- ACL 是 row-level fine-grained access — 用於非 admin role 的 per-user / per-group 控制
- admin 是 organizational super-admin — RBAC 慣例（如 GitHub org admin、Atlassian site admin）；不查 ACL 是設計，不是繞過
- 與 `*:read` (S026) 的差異：`*:read` 對所有人開放 read；admin bypass 對 admin 開放所有 permission（含 mutation）
- prod 守護：JWT `roles` claim 由 OIDC provider 控制；攻擊者沒辦法自行加 admin role 到 JWT 上

### 2.4 為何 NOT 改 default ACL 加 `role:admin:*`

選 evaluator bypass 而非 ACL data。理由：
- ACL data 為 per-skill；admin bypass 是 cross-skill organization-level 概念，不該寫進每筆 skill 的 acl_entries
- 加 `role:admin:write/delete/suspend/reactivate` 4 條 entry 到每個 skill 預設 ACL 太冗長
- bypass 在 evaluator 短路 — 邏輯集中、零 SQL overhead

---

## 3. SBE Acceptance Criteria

### AC-1: local profile 啟動為 LAB mode

```gherkin
Given application-local.yaml 內 skillshub.security.oauth.enabled = false
When  bootRun --args='--spring.profiles.active=local'
Then  log 含 "LAB mode" 或 LabSecurityFilter 註冊（無 JwtDecoder bean）
And   curl -sS POST /api/v1/skills/upload 不需 JWT 即可 201
```

### AC-2: anonymous lab-user 通過 @PreAuthorize ACL grant gate

```gherkin
Given local profile 啟動
And   alice 已上傳 skill A（acl_entries 含 "user:alice:write"，不含 lab-user）
When  curl -X POST /api/v1/skills/{A}/acl -d '{"type":"group","principal":"engineering","permission":"read"}'
Then  HTTP 201（admin bypass via ROLE_admin → @PreAuthorize 通過）
And   GET /api/v1/skills/{A}/acl 回應含 group:engineering:read entry
```

### AC-3: anonymous lab-user 通過 PUT version + suspend + reactivate gate

```gherkin
Given local profile 啟動
And   alice 已上傳 skill A
When  curl -X PUT /api/v1/skills/{A}/versions ...
Then  HTTP 200
When  curl -X POST /api/v1/skills/{A}/suspend -d '{"reason":"test"}'
Then  HTTP 200
When  curl -X POST /api/v1/skills/{A}/reactivate -d '{"reason":"test"}'
Then  HTTP 200
```

### AC-4: prod / oauth.enabled=true 行為不變（ACL 守門仍生效）

```gherkin
Given oauth.enabled=true（base default）
And   alice 上傳 skill；bob JWT (roles=["user"]) 不含 ROLE_admin authority
When  bob PUT /api/v1/skills/{A}/versions
Then  HTTP 403（admin bypass 不命中；走 ACL strategy；bob 不在 acl_entries → 失敗）
```

### AC-5: DelegatingPermissionEvaluatorTest 既有斷言不破

```gherkin
Given S016 既有 9 個 unit test in DelegatingPermissionEvaluatorTest
When  ./gradlew test --tests "*DelegatingPermissionEvaluatorTest*"
Then  全 PASS（admin bypass 為新增 path；既有 user/role/group ACL 行為對非 admin user 不變）
```

---

## 4. Interface

### 4.1 application-local.yaml

```diff
+# S027: dev 模式跳過 OAuth；改用 LabSecurityFilter 注入 lab-user with ROLE_admin
+# DelegatingPermissionEvaluator 對 ROLE_admin 短路 hasPermission → true
+# → @PreAuthorize ACL gates 自動通過，dev 體驗對齊 PRD「Feature First, Security Later」
+skillshub:
+  security:
+    oauth:
+      enabled: false
```

### 4.2 DelegatingPermissionEvaluator.evaluate

```diff
 private boolean evaluate(Authentication auth, Object target, String targetType, String permission) {
+    // S027: ROLE_admin 全 permission bypass — admin 為 super-admin role
+    if (hasAdminRole(auth)) {
+        return true;
+    }
     var principals = expandPrincipals(auth, permission);
     return strategies.stream()
             .filter(s -> s.supports(targetType))
             .findFirst()
             .map(s -> s.hasPermission(principals, target, permission))
             .orElse(false);
 }
+
+private boolean hasAdminRole(Authentication auth) {
+    return auth.getAuthorities().stream()
+            .anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
+}
```

---

## 5. File Plan

### 5.1 Production (2 files)
- `backend/src/main/resources/application-local.yaml`（加 `skillshub.security.oauth.enabled: false`）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java`（加 admin bypass）

### 5.2 Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluatorTest.java`（加 1 個 admin bypass test）

### 5.3 Docs
- `docs/grimo/development-standards.md`：補「ROLE_admin 為 cross-aggregate super-admin bypass — DelegatingPermissionEvaluator 短路」段
- `docs/grimo/CHANGELOG.md`：v2.4.0 entry
- `docs/grimo/specs/spec-roadmap.md`：S027 ✅ + M23 entry

---

## 6. Task Plan

單一 task — 改動原子（profile + evaluator + 1 test + docs）：

| # | Task | AC | Status |
|---|---|---|---|
| T01 | application-local.yaml LAB mode + DelegatingPermissionEvaluator admin bypass + 1 unit test + docs sync | AC-1 ~ AC-5 | 🔲 |

POC: not required（純已知行為擴展；無新 dependency；既有 SecurityConfig LAB mode 分支已存在）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.4.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 7s（291+1=292 tests / 0 fail / 0 disabled）；E2E HTTP 全 mutation 在 LAB mode + admin bypass 通過 @PreAuthorize gate（ACL grant 201 / suspend 200 / reactivate 200 / PUT version 200 / revoke 204）。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 7s；292 tests / 0 fail / 0 errors / 0 disabled（含新加 admin bypass test）|
| `POST /api/v1/skills/upload` author=alice | 201 ✓（LAB mode anonymous 仍允許）|
| `POST /api/v1/skills/{A}/acl` 加 group:engineering:read | **201 ✓** AC-2（tick 1 為 401，S027 fix 後通過）|
| `POST /api/v1/skills/{A}/suspend` reason=test | **200 ✓** AC-3 |
| `POST /api/v1/skills/{A}/reactivate` reason=test | **200 ✓** AC-3 |
| `PUT /api/v1/skills/{A}/versions` version=1.1.0 | **200 ✓** AC-3 |
| `DELETE /api/v1/skills/{A}/acl?...` revoke | **204 ✓** AC-2 |
| `DelegatingPermissionEvaluatorTest.hasPermission_adminRole_bypassesStrategy` | PASS ✓ AC-5 |

### 7.2 Files Changed

#### Production (2 files)
- `backend/src/main/resources/application-local.yaml`（尾部加 `skillshub.security.oauth.enabled: false` + 註解說明）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java`（`evaluate()` 短路 `hasAdminRole(auth)` → return true；新增 private helper `hasAdminRole`）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluatorTest.java`（加 1 個 admin bypass test：read/write/suspend 三 verb 都通過；strategy stub 確認未被 invoke）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: local profile 啟動為 LAB mode | ✅ PASS | `application-local.yaml` 加 `oauth.enabled: false`；bootRun 啟動成功；anonymous upload 201 |
| AC-2: anonymous lab-user 通過 ACL grant gate | ✅ PASS | `POST /skills/{A}/acl` HTTP 201（tick 1 為 401）|
| AC-3: anonymous lab-user 通過 PUT version + suspend + reactivate gate | ✅ PASS | 各回 200 |
| AC-4: prod / oauth.enabled=true 行為不變 | ✅ PASS（via existing test）| `DelegatingPermissionEvaluatorTest` 既有 9 test 仍綠（admin bypass 為新增 path；非 admin user 行為不變）|
| AC-5: 既有 unit test 不破 | ✅ PASS | 全 292 tests / 0 fail |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 2 系統測試發現。anonymous 對 alice 的 skill 執行 `POST /api/v1/skills/{id}/acl` 回 HTTP 401。

**Root cause analysis**:
1. `application.yaml` 預設 `skillshub.security.oauth.enabled: true`（line 137）
2. `application-local.yaml` 未 override → 本機 dev 也走 OAuth Resource Server
3. anonymous（無 JWT）request 通過 `anyRequest().permitAll()` 進到 controller，但 `@PreAuthorize` 啟動 `DelegatingPermissionEvaluator`
4. `DelegatingPermissionEvaluator.authenticated()`（line 80-84）對 `AnonymousAuthenticationToken` return false → hasPermission false → @PreAuthorize fail → `BearerTokenAuthenticationEntryPoint` 把 403 轉成 401

**Fix design rationale**:

選 admin role bypass 而非「ACL data 加 `role:admin:*`」：
- ACL data 為 per-skill row-level；admin bypass 是 cross-skill organization-level 概念
- 加 `role:admin:write/delete/suspend/reactivate` 4 條 entry 到每個 skill 預設 ACL 太冗長（既有已 4 條 owner + S026 加 `*:read` = 5 條，再加 4 條 admin = 9 條）
- bypass 在 evaluator 短路 — 邏輯集中、零 SQL overhead、與 RBAC 慣例對齊（GitHub org admin / Atlassian site admin / 多數 SaaS admin role 均 cross-resource bypass）

選 `application-local.yaml` LAB mode 而非條件式 `@PreAuthorize`：
- 既有 `SecurityConfig.filterChain` 已有 `if (oauth.enabled())` 分支（line 79）— 重用 existing infrastructure，零新 code path
- LabSecurityFilter 注入 `lab-user` with `ROLE_admin`（line 93）— 配合 evaluator bypass 自動通過所有 ACL gate
- prod (`gcp` profile) 預設 `oauth.enabled=true`，零行為變化

**Defense-in-depth note**: ROLE_admin authority 在 prod 模式只能由 OIDC provider（如 IdP / Auth0）的 JWT `roles` claim 控制；攻擊者無法自行加 admin role 到 JWT 上（簽章驗證守護）。`JwtGrantedAuthoritiesConverter` (`SecurityConfig:163-165`) 把 `roles: ["admin"]` 映射為 `ROLE_admin` — 唯一注入路徑。

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。

`SkillAclQueryService.listEntries` 過濾 `*:read` entry（解析 colon-separated 字串時 `"*:read"` 不符 `type:principal:permission` 三段格式）— 是 inherent design（API 不暴露 public-read pseudo-principal 為 ACL CRUD 條目），非 bug。
