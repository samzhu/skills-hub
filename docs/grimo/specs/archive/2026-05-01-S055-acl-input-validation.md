# S055: ACL Tuple Input Validation

> Spec: S055 | Size: XS(5) | Status: ✅ Done — target ship `v2.32.0`
> Trigger: 2026-05-01 /loop tick 28 — `POST /api/v1/skills/{id}/acl` 接受任意 type / permission 字串：
> - `{"type":null, "principal":"user:bob", "permission":"read"}` → 201 創建（DTO 缺 type，aggregate 不驗）
> - `{"type":"user", "principal":"bob", "permission":"invalid_perm"}` → 201 創建（permission 任意字串都接受）
> - 結果：DB 內出現 `null:user:bob:read` / `user:bob:invalid_perm` 等畸形 ACL entry；GET list 解析時也錯位（`{type: 'null', principal: 'user', permission: 'bob:read'}`）。

---

## 1. Goal

`Skill` aggregate 加 ACL tuple 預驗：
- `type` ∈ {`user`, `role`, `group`}
- `principal` 非 blank
- `permission` ∈ {`read`, `write`, `delete`, `suspend`, `reactivate`}

違反 → `IllegalArgumentException` → 400 VALIDATION_ERROR。對 `grantAcl` + `revokeAcl` 都驗（同 funnel point）。

---

## 2. Approach

### 2.1 Aggregate diff

```java
// Skill.java
private static final Set<String> ACL_TYPES = Set.of("user", "role", "group");
private static final Set<String> ACL_PERMISSIONS =
        Set.of("read", "write", "delete", "suspend", "reactivate");

private static void validateAclTuple(String type, String principal, String permission) {
    if (type == null || !ACL_TYPES.contains(type)) {
        throw new IllegalArgumentException(
                "ACL type must be one of " + ACL_TYPES + " (got: " + type + ")");
    }
    if (principal == null || principal.isBlank()) {
        throw new IllegalArgumentException("ACL principal must not be blank");
    }
    if (permission == null || !ACL_PERMISSIONS.contains(permission)) {
        throw new IllegalArgumentException(
                "ACL permission must be one of " + ACL_PERMISSIONS + " (got: " + permission + ")");
    }
}

public void grantAcl(GrantAclCommand cmd) {
    validateAclTuple(cmd.type(), cmd.principal(), cmd.permission());
    var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
    ...
}
public void revokeAcl(RevokeAclCommand cmd) {
    validateAclTuple(cmd.type(), cmd.principal(), cmd.permission());
    var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
    ...
}
```

### 2.2 為何 NOT 在 Controller 層用 Bean Validation 註解

對齊既有風格（S041 / S054 都在 aggregate 守 invariant）：
- 多 entry path（command service / future admin batch import 等）都受惠
- aggregate 為 source of truth，不依賴 controller 註解

### 2.3 Permission set 含 `suspend` / `reactivate` 為何

對齊 architecture.md / SkillAclController 既有設計：admin 可被授「suspend」/「reactivate」權限以掌控 skill 生命週期。MVP 階段這些非 user 自助可達；保留 set 預留未來 admin panel。

### 2.4 為何 NOT 修舊有畸形 entries

範圍守住 fix-going-forward。舊資料（`null:user:bob:read` 等）由 future migration 清理；本 spec 不寫 SQL migration 避免擴張。

---

## 3. SBE Acceptance Criteria

### AC-1: 缺 type → 400

```gherkin
When  POST /skills/{id}/acl body {"principal":"bob","permission":"read"}（缺 type）
Then  HTTP 400 VALIDATION_ERROR + 包含「ACL type must be one of」
```

### AC-2: invalid permission → 400

```gherkin
When  POST /skills/{id}/acl body {"type":"user","principal":"bob","permission":"hack"}
Then  HTTP 400 VALIDATION_ERROR + 包含「ACL permission must be one of」
```

### AC-3: blank principal → 400

```gherkin
When  POST /skills/{id}/acl body {"type":"user","principal":"  ","permission":"read"}
Then  HTTP 400 VALIDATION_ERROR + 包含「principal must not be blank」
```

### AC-4: 合法 grant 仍 201

```gherkin
When  POST /skills/{id}/acl body {"type":"user","principal":"bob","permission":"read"}
Then  HTTP 201
And   GET list 顯 entry {type:"user", principal:"bob", permission:"read"}
```

### AC-5: revoke 同樣驗證

```gherkin
When  DELETE /skills/{id}/acl?type=invalid&principal=bob&permission=read
Then  HTTP 400 VALIDATION_ERROR
```

### AC-6: 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：加 `ACL_TYPES` / `ACL_PERMISSIONS` 常數 + `validateAclTuple` private method；`grantAcl` 與 `revokeAcl` 第一行 call

### 5.2 Test
- 既有 test 不破；E2E 由 curl 手測 5 個 AC

### 5.3 Docs
- CHANGELOG `v2.32.0`
- spec-roadmap M51

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | validate + curl retest | AC-1~6 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.32.0`
>
> Verification: backend 286 tests / 0 fail；E2E 6 ACs 全綠。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-6 |
| POST acl 缺 type | 400 + 「ACL type must be one of [role, user, group] (got: null)」✓ AC-1 |
| POST acl invalid permission | 400 + 「ACL permission must be one of [...] (got: hack)」✓ AC-2 |
| POST acl blank principal | 400 + 「ACL principal must not be blank」✓ AC-3 |
| POST acl valid (role:engineering:read) | 201 ✓ AC-4 |
| DELETE acl invalid type | 400 + 「ACL type must be one of...」✓ AC-5 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：
  - 加 `ACL_TYPES` constant (Set: user/role/group)
  - 加 `ACL_PERMISSIONS` constant (Set: read/write/delete/suspend/reactivate)
  - 加 private static `validateAclTuple(type, principal, permission)`
  - `grantAcl` + `revokeAcl` 第一行 call validate

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 缺 type → 400 | ✅ PASS | curl confirm |
| AC-2: invalid permission → 400 | ✅ PASS | curl confirm |
| AC-3: blank principal → 400 | ✅ PASS | curl confirm |
| AC-4: 合法 grant → 201 | ✅ PASS | curl confirm |
| AC-5: revoke invalid type → 400 | ✅ PASS | curl confirm |
| AC-6: 既有 test 不破 | ✅ PASS | 286 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 28 ACL endpoints probe — POST `/api/v1/skills/{id}/acl` 接受 `{principal:"user:bob", permission:"read"}`（缺 type）→ 201 創建；接受 `permission:"invalid_perm"` → 201 創建。aggregate factory 沒驗 ACL tuple shape，畸形 entry 入 DB（type=null / permission=任意字串），後續 GET list 解析也錯位（{type:'null', principal:'user', permission:'bob:read'}）。

**Fix scope choice**:
- 守在 aggregate（對齊 S041/S054）— 多 entry path 受惠
- `grantAcl` + `revokeAcl` 共用 `validateAclTuple` 私有 helper — DRY
- 不寫 SQL migration 清舊資料 — 範圍守 fix-going-forward

**Permission set 含 `suspend` / `reactivate`**：對齊 architecture.md ACL spec，未來 admin panel 可授這兩個權限掌控 skill 生命週期。

### 7.5 Pending Verification / Tech Debt

- DB 內既存畸形 entries（如 `null:user:bob:read` / `user:bob:invalid_perm`）需 future migration 清理
- semantic 系統性回 0 根因仍待查
- analytics「本週新增」算法仍待驗
