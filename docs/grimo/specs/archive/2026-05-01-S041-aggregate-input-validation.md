# S041: Skill Aggregate Input Validation（補 JSON POST path 驗證 + ACL 清潔）

> Spec: S041 | Size: XS(5) | Status: ✅ Done — target ship `v2.18.0`
> Depends: S016 ✅ + S026 ✅
> Trigger: 2026-05-01 /loop tick 16 — `POST /api/v1/skills`（JSON path）接受空字串 / 空白 / 違反 agentskills.io regex 的 name / author，產生畸形 ACL `user::read` / `user:   :read`，違反 invariant

---

## 1. Goal

`Skill.create` aggregate factory 加入 invariant 守門：
1. **`name`**：必非 null + trim 後 match `^[a-z0-9-]{1,64}$`（agentskills.io 正規格式）
2. **`author`**：trim；blank（null / "" / "   "）→ 視為無 author（ACL 只 seed `*:read`）；非 blank 才 seed user-namespace ACL

任何進入 aggregate 的 path（upload multipart / JSON POST）一律經此守門；不再有「JSON POST 繞過驗證」破口。

---

## 2. Approach

### 2.1 Skill.create 加 guard

```diff
+private static final java.util.regex.Pattern NAME_REGEX =
+        java.util.regex.Pattern.compile("^[a-z0-9-]{1,64}$");
+
 public static Skill create(CreateSkillCommand cmd) {
+    var name = cmd.name() == null ? null : cmd.name().trim();
+    if (name == null || !NAME_REGEX.matcher(name).matches()) {
+        throw new IllegalArgumentException(
+            "Skill name must match ^[a-z0-9-]{1,64}$ (got: " + cmd.name() + ")");
+    }
+    var author = cmd.author() == null ? null : cmd.author().trim();
+    if (author != null && author.isEmpty()) {
+        author = null;  // blank-only → 視為無 author，避免 ACL "user::read" 畸形
+    }
+
     var skill = new Skill();
     skill.id = UUID.randomUUID().toString();
-    skill.name = cmd.name();
+    skill.name = name;
     skill.description = cmd.description();
-    skill.author = cmd.author();
+    skill.author = author;
     ...
-    skill.aclEntries = cmd.author() == null
+    skill.aclEntries = author == null
             ? new ArrayList<>(List.of("*:read"))
             : new ArrayList<>(List.of(
-                    "user:" + cmd.author() + ":read",
-                    "user:" + cmd.author() + ":write",
-                    "user:" + cmd.author() + ":delete",
+                    "user:" + author + ":read",
+                    "user:" + author + ":write",
+                    "user:" + author + ":delete",
                     "*:read"));
     ...
     skill.registerEvent(new SkillCreatedEvent(
-            skill.id, cmd.name(), cmd.description(), cmd.author(), cmd.category()));
+            skill.id, name, cmd.description(), author, cmd.category()));
 }
```

### 2.2 為何 NOT 加 description / category 驗證

scope 鎖定在 broken invariant 的兩個欄位（name / author）：
- `description`：agentskills.io spec 有 length 規範但已由 `SkillValidator` 在 upload path 驗；JSON POST seeding 用通常給測試 / 內部 — 不加額外規則
- `category`：自由文本，沒既定 regex

未來如要全 input 驗證可獨立 spec。

### 2.3 為何 NOT 在 controller / DTO 層 `@NotBlank` `@Pattern`

考慮過 `CreateSkillCommand` record 加 `@NotBlank @Pattern` 註解 + `@Valid`。否決：
- aggregate factory 是 invariant 的最終守門 — 在這裡守可保護「未來新 entry path」
- 兩條 path（multipart upload + JSON POST）的 entry shape 不同，controller 層註解難共用
- aggregate guard 對 `Skill.create` 任何 caller（含 unit test fixture 直接呼叫）也生效

未來新 controller 可加 layer-2 註解作 fail-fast；aggregate 是 last line of defense。

### 2.4 為何重用 SkillValidator NAME_REGEX 不直接 import

不 import `SkillValidator.NAME_REGEX` 因 module 邊界：
- `Skill` 在 `skill.domain`（純 domain）
- `SkillValidator` 在 `skill.validation`（依賴 SnakeYAML 等 lib）
- domain 不該反向依賴 validation 子模組（依賴方向錯）

複製 regex literal 是輕量且明確 — 同 codebase 內若 regex 改動需手動同步兩處（spec §7 加 inline 註解）。

---

## 3. SBE Acceptance Criteria

### AC-1: name 違反 regex → 400 VALIDATION_ERROR

```gherkin
Given POST /api/v1/skills 帶 {"name":"BadName","description":"...","author":"alice","category":"DevOps"}
When  controller 呼叫 Skill.create
Then  HTTP 400 VALIDATION_ERROR
And   error message 含 "must match ^[a-z0-9-]{1,64}$"
```

### AC-2: name 空字串 → 400

```gherkin
Given POST /api/v1/skills with {"name":"","description":"...","author":"alice","category":"DevOps"}
Then  HTTP 400 VALIDATION_ERROR
```

### AC-3: author 空白 → ACL 只含 *:read（不產生 user::read 畸形）

```gherkin
Given POST /api/v1/skills with {"name":"valid-name","author":"   ","description":"...","category":"DevOps"}
When  Skill.create 處理
Then  HTTP 201
And   skill.author 為 null（trim 後 blank 視為無 author）
And   skill.aclEntries 只含 ["*:read"]（不含 user::read 等畸形）
```

### AC-4: name 含前後空白 → trim 後驗證並儲存 trimmed

```gherkin
Given POST /api/v1/skills with {"name":"  valid-name  ","author":"alice","description":"...","category":"DevOps"}
Then  HTTP 201
And   skill.name 為 "valid-name"（trimmed）
```

### AC-5: 既有 happy path 不破

```gherkin
Given multipart upload with 合法 SKILL.md 並 author="alice"
When  POST /api/v1/skills/upload
Then  HTTP 201（既有行為）
And   skill.aclEntries 含 user:alice:* + *:read
```

### AC-6: 既有 unit test 不破

```gherkin
Given S041 改動完成
When  ./gradlew test
Then  既有 296 tests 不破
And   既有 SkillAggregateTest fixtures 用合法 name（lowercase + dash）已通過 regex
```

---

## 4. Interface

詳 §2.1 diff。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：`create()` 加 NAME_REGEX 常數 + name/author trim + validation

### 5.2 Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`：加 4 cases — invalid name throws / empty name throws / blank author seeds only `*:read` / trim name

### 5.3 Docs
- CHANGELOG `v2.18.0`
- spec-roadmap M37

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | Skill.create 加 validation + 4 unit tests + E2E retest | AC-1~6 | 🔲 |

POC: not required（純 invariant guard；既有 ACL JSONB schema 不變）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.18.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 11s（296 → 301 tests / 0 fail，新加 5 個 tests）；E2E HTTP 全 5 個 AC 綠燈：bad name/empty name/whitespace author 都 400 VALIDATION_ERROR；trim name 儲存正確；multipart upload happy path 不破。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 11s；301 tests / 0 fail |
| HTTP `POST /skills` JSON name="BadName" | **400 VALIDATION_ERROR** + msg "must match ^[a-z0-9-]{1,64}$" ✓ AC-1 |
| HTTP `POST /skills` JSON name="" | **400 VALIDATION_ERROR** ✓ AC-2 |
| HTTP `POST /skills` JSON author="   " | **400 VALIDATION_ERROR** + msg "must not be blank" ✓ AC-3 |
| HTTP `POST /skills` JSON name="  valid-name  " | trim 後儲存為 "valid-name" ✓ AC-4 |
| HTTP `POST /skills/upload` happy path | 201（regression check）✓ AC-5 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：
  - 新增 `NAME_REGEX` 常數（`^[a-z0-9-]{1,64}$`，與 `SkillValidator.NAME_REGEX` 同字面）
  - `Skill.create` 加 name trim + regex 驗證；author trim + 非 null 但 blank 拒絕
  - SkillCreatedEvent 用 trimmed name + author（避免事件帶有未 trim 的字面）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`：加 5 個 tests（invalid name uppercase / empty name / blank author throws / null author OK seeds *:read / trim name）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: name 違反 regex → 400 | ✅ PASS | E2E HTTP 確認；message 含 "must match" + 原始輸入 |
| AC-2: name 空字串 → 400 | ✅ PASS | E2E HTTP 確認 |
| AC-3: author 空白 → 400（非 null-out）| ✅ PASS | E2E HTTP 確認；schema `skills.author NOT NULL` 不被 silent null 違反 |
| AC-4: name trim 後儲存 | ✅ PASS | DB 確認 |
| AC-5: multipart upload 不破 | ✅ PASS | regression 201 |
| AC-6: 既有 unit test 不破 | ✅ PASS | 296 → 301 tests 全綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 16 — `POST /api/v1/skills` JSON path 缺驗證，接受 `name=""` / `name="BadName"` / `author="   "`，產生畸形 ACL `user::read` / `user:   :read`。

**Schema constraint discovered mid-fix**: 第一次嘗試 trim author blank → null 時，DB save 失敗（`skills.author NOT NULL`）。改為 throw IllegalArgumentException 拒絕 blank，與 schema 一致。null author（caller 顯式不傳）仍允許 — 用於 unit test fixture 控制 ACL seed 行為，但該 path 在 prod schema 下永遠不會持久化成功。

**Fix design rationale**:
- aggregate factory 是 invariant 最終守門 — 在 `Skill.create` 守可保護所有 entry path（multipart upload + JSON POST + 未來新 path）
- name regex 與 `SkillValidator.NAME_REGEX` 同字面但不 import — domain 不該反向依賴 validation 子模組（依賴方向錯）；複製 regex literal + inline 註解提醒同步
- 拒絕 blank 而非 null-out — 對 user input 給明確 error，不靜默轉換為其他語意

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 仍待設計。
