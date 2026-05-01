# S038: ACL List Recognizes `*:read`（消除 WARN log spam + UI 露出公開讀取狀態）

> Spec: S038 | Size: XS(5) | Status: ✅ Done — target ship `v2.15.0`
> Date: 2026-05-01
> Depends: S026 ✅ + S028 ✅
> Trigger: 2026-05-01 /loop tick 13 — `SkillAclQueryService.listEntries` 對 `*:read`（S026 公開讀取 pseudo-principal）視為「格式異常」並 WARN log + 從 response 排除；公開瀏覽 ACL 都觸發 WARN 屬 log spam，UI 也無法呈現「公開讀取」狀態

---

## 1. Goal

`SkillAclQueryService.listEntries` 識別 `*:read` 為 valid 公開讀取 pseudo-entry：
1. **不再 WARN log**（消除 every-public-skill 的 log spam）
2. **回 synthetic entry** `{type:"public", principal:"*", permission:"read"}`（讓 frontend 可呈現「公開讀取」標示）
3. **CRUD 約束不變**：`grantAcl` / `revokeAcl` 仍只接受 `user|role|group` 三 namespace；`*:read` 由 `Skill.create` 自動 seed，user 不能手動 grant/revoke 此 pseudo-principal

---

## 2. Approach

### 2.1 listEntries 加 `*:read` 早 return

```diff
 for (var entry : skill.get().getAclEntries()) {
+    // S038: "*:read" 為 S026 公開讀取 pseudo-principal — 識別為 synthetic entry，不走三段 split
+    if ("*:read".equals(entry)) {
+        result.add(new AclEntryResponse("public", "*", "read"));
+        continue;
+    }
     var parts = entry.split(":", 3);
     if (parts.length != 3) {
         log.atWarn()
                 ...
                 .log("ACL entry 格式異常（非 type:principal:permission 三段），略過");
         continue;
     }
     result.add(new AclEntryResponse(parts[0], parts[1], parts[2]));
 }
```

### 2.2 為何選 `type="public"` / `principal="*"`

- `type="public"` — UI clear semantic ("Public read" 文字直接對齊)
- `principal="*"` — 保留 wildcard 標記語意；frontend 渲染時看到 type=public 即特殊 case，不會誤解為 user/role/group
- `permission="read"` — 與其他 entry 的 permission column 對齊；未來如要公開 download/list 也可有 `*:read` 之外的 pseudo

### 2.3 為何 NOT 接受 `grant`/`revoke` 的 `type=public`

`grantAcl` / `revokeAcl` 屬 owner/admin 對 user/role/group 的細粒度授權；`*:read` 是 system-owned pseudo-principal（per `Skill.create` 自動 seed）。允許 user CRUD 會：
- 破 invariant：「公開讀取屬 platform-level 預設，不屬個別 owner 政策」
- 增加 grant/revoke validation 複雜度（`grant type=public principal=*` 對應到什麼？）
- 違反 S026 設計意圖（per S026 §1：「skill 預設對所有使用者開放讀取」）

未來如有 user-controlled 公開化 / 私有化需求，應走獨立 endpoint（如 `POST /skills/{id}/visibility` with `{public: true}`）— 屬 future spec scope。

### 2.4 為何 NOT 改後端 ACL storage schema

考慮過：把 `*:read` 從 acl_entries JSONB 抽出至 `skills.public_read BOOLEAN` 欄位。否決：
- migration 複雜（V7 schema change + backfill）
- 既有 ACL `?|` SQL 已 work；`*:read` 透過 `AclPrincipalExpander.expand` 加入 patterns 形式統一
- 增量 schema 不能無條件 reverse — minor display issue 不值得 schema 改動

---

## 3. SBE Acceptance Criteria

### AC-1: 含 `*:read` 的 ACL list response 含 synthetic entry

```gherkin
Given alice 已上傳 skill A（ACL: ["user:alice:read", "user:alice:write", "user:alice:delete", "*:read"]）
When  GET /api/v1/skills/{A}/acl
Then  response 4 entries
And   一筆 {type:"public", principal:"*", permission:"read"}
And   三筆 user:alice 既有
```

### AC-2: 不再 WARN log

```gherkin
Given 對 public skill 連 3 次 GET /acl
When  完成
Then  backend 無 "格式異常" WARN log（先前每次 read 都會 WARN）
```

### AC-3: 真正畸形 entry 仍 WARN + 跳過

```gherkin
Given skill 含畸形 entry "malformed-entry-no-colons"
When  GET /acl
Then  response 不含此 entry
And   backend log 含 "格式異常" WARN
```

### AC-4: grantAcl / revokeAcl 不接受 type=public

```gherkin
Given POST /skills/{A}/acl with {type:"public", principal:"*", permission:"read"}
Then  不期望此 spec 內加新 validation；既有行為（duplicate "*:read" 拋 IllegalStateException → 409 STATE_CONFLICT per S030）即可
And   user 仍不能透過 CRUD 手動 grant/revoke `*:read`
```

### AC-5: 既有 unit test 不破

```gherkin
Given S038 改動完成
When  ./gradlew test
Then  既有 295 tests 仍 PASS（SkillAclQueryServiceTest 既有 case 用 user/role/group 不受影響）
```

---

## 4. Interface

詳 §2.1 diff。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillAclQueryService.java`：listEntries 加 `*:read` 早 return synthetic entry

### 5.2 Test (1 unit test 補)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillAclQueryServiceTest.java`：加 1 case 驗 `*:read` → synthetic entry，且不 WARN log

### 5.3 Docs
- CHANGELOG `v2.15.0`
- spec-roadmap M34

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | listEntries 早 return + 1 unit test + E2E retest | AC-1~5 | 🔲 |

POC: not required（純查詢 service 行為擴展；無 schema / contract 變更）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.15.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 23s（295 + 1 = 296 tests / 0 fail）；E2E HTTP `GET /skills/{A}/acl` 回 4 entries 含 `{type:"public", principal:"*", permission:"read"}`；連 3 次 read 後 WARN log count = 0。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 23s；296 tests / 0 fail（含新 `listEntries_publicReadPseudoPrincipal_recognized`）|
| HTTP `GET /skills/{A}/acl` | response 4 entries 含 `{type:"public", principal:"*", permission:"read"}` ✓ AC-1 |
| 連 3 次 ACL list call 後 log | WARN "格式異常" count = **0**（baseline 每次 read 一條）✓ AC-2 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillAclQueryService.java`：
  - `listEntries` 加 `*:read` 早 return synthetic `AclEntryResponse("public", "*", "read")`
  - 註解 documenting CRUD 約束不變（grantAcl/revokeAcl 仍只接 user/role/group）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillAclQueryServiceTest.java`：加 `listEntries_publicReadPseudoPrincipal_recognized` test — 驗 `*:read` 出現在 response 為 synthetic entry，user/group 既有行為不破

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: ACL list response 含 public synthetic entry | ✅ PASS | E2E HTTP 4 entries（3 user + 1 public）|
| AC-2: 不再 WARN log spam | ✅ PASS | 連 3 次 call 後 WARN count = 0 |
| AC-3: 真正畸形 entry 仍 WARN + skip | ✅ PASS | 既有 `listEntries_malformedEntry_skipped` test 不破；新分支不影響 fallthrough |
| AC-4: grantAcl/revokeAcl 不接受 type=public | ✅ PASS（既有設計）| Skill.create 是 `*:read` 唯一寫入路徑；grantAcl 只寫 user:/role:/group: 三 namespace |
| AC-5: 既有 unit test 不破 | ✅ PASS | 296 tests 全綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 13 — 對 public skill 每次 GET /acl 都觸發 WARN log "ACL entry 格式異常（非 type:principal:permission 三段），略過"；S026 後每個 PUBLISHED skill 都有 `*:read` → 每次 read 一條 WARN → log spam at scale。同時 ACL list response 不含 `*:read`，frontend 無法呈現「公開讀取」狀態。

**Fix design rationale**:
- **synthetic entry shape `{type:"public", principal:"*", permission:"read"}`**：`type="public"` 為新 namespace 標記語意清楚；frontend 渲染時看到 type=public 即特殊 case
- **CRUD 不變**：grantAcl/revokeAcl 仍只接 user/role/group；`*:read` 由 `Skill.create` 自動 seed 屬 platform-level 預設，不允許 user CRUD（per S026 設計意圖）
- **不改 schema**：考慮過 `skills.public_read BOOLEAN` 抽欄位，否決因 V7 migration 複雜，現有 `*:read` JSONB entry + ACL `?|` SQL 已 work

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 仍待設計。Frontend 顯示「公開讀取」UI 屬另一範疇 — 既有 ACL list UI 視 type=public 為新 case，可後續 spec 加 badge/icon 區分。
