# S042: Aggregate description / category Validation（補完 Skill.create 四欄位驗證）

> Spec: S042 | Size: XS(5) | Status: ✅ Done — target ship `v2.19.0`
> Depends: S041 ✅
> Trigger: 2026-05-01 /loop tick 17 — `POST /api/v1/skills` JSON path 仍接受空 / 超長 description；空白 category。S041 補了 name/author，S042 補完 description/category 的同類驗證。

---

## 1. Goal

`Skill.create` 四欄位驗證對齊 SkillValidator multipart path 行為：
1. **description**：非 null（既有）+ trim 後非 blank + 長度 ≤ 1024（agentskills.io spec，per `SkillValidator.DESCRIPTION_MAX`）
2. **category**：trim；非 null 但 blank（`""` / `"   "`）→ 拒絕（mirror S041 author 行為）；null 仍允許（schema 允許）

---

## 2. Approach

### 2.1 Skill.create 加 description / category guard

```diff
 public static Skill create(CreateSkillCommand cmd) {
     Objects.requireNonNull(cmd.name(), "name is required");
     Objects.requireNonNull(cmd.description(), "description is required");
     ...
+    // S042: description trim + length cap（與 SkillValidator.DESCRIPTION_MAX=1024 對齊）
+    var description = cmd.description().trim();
+    if (description.isEmpty()) {
+        throw new IllegalArgumentException("Skill description must not be blank");
+    }
+    if (description.length() > DESCRIPTION_MAX) {
+        throw new IllegalArgumentException(
+                "Skill description exceeds " + DESCRIPTION_MAX + " characters (got: " + description.length() + ")");
+    }
+
+    // S042: category trim；非 null 但 blank → reject（mirror S041 author）
+    var category = cmd.category() == null ? null : cmd.category().trim();
+    if (category != null && category.isEmpty()) {
+        throw new IllegalArgumentException(
+                "Skill category must not be blank (got: " + cmd.category() + ")");
+    }

     ...
-    skill.description = cmd.description();
+    skill.description = description;
-    skill.category = cmd.category();
+    skill.category = category;
     ...
     skill.registerEvent(new SkillCreatedEvent(
-            skill.id, name, cmd.description(), author, cmd.category()));
+            skill.id, name, description, author, category));
 }

 private static final int DESCRIPTION_MAX = 1024;
```

### 2.2 為何 description 限長 1024（與 SkillValidator 一致）

agentskills.io spec 由 `SkillValidator.DESCRIPTION_MAX` 設 1024（per S018 AC-14）。aggregate 守同樣上限避免 multipart upload 與 JSON POST 行為分歧；變更 spec 上限時需手動同步兩處（與 S041 NAME_REGEX 同模式 — domain 不依賴 validation 子模組）。

### 2.3 為何 category 不加長度限制

agentskills.io spec 沒對 category 設長度規範，`SkillValidator` 也未驗。category 屬自由文本，frontend 端用戶 typing；server-side 不過嚴。schema 的 `varchar` 已隱含 length cap（雖然 PG `varchar` 默認無界）。如未來 PRD 加 category whitelist 再獨立 spec。

### 2.4 為何 description 也要 trim

per S041 對 name/author 的同樣理由：user input 前後空白通常是意外；trim 提升 UX 並讓 length check 對 valid 內容生效（不被 padding 影響）。

---

## 3. SBE Acceptance Criteria

### AC-1: description 空字串 → 400

```gherkin
Given POST /api/v1/skills with {"name":"valid","description":"","author":"alice","category":"DevOps"}
Then  HTTP 400 VALIDATION_ERROR
And   message 含 "must not be blank"
```

### AC-2: description 全空白 → 400

```gherkin
Given description="   "
Then  HTTP 400 VALIDATION_ERROR
```

### AC-3: description >1024 chars → 400

```gherkin
Given description = 'a' * 2000
Then  HTTP 400 VALIDATION_ERROR
And   message 含 "exceeds 1024 characters"
```

### AC-4: category 空字串 → 400

```gherkin
Given category=""
Then  HTTP 400 VALIDATION_ERROR
And   message 含 "category must not be blank"
```

### AC-5: category 全空白 → 400

```gherkin
Given category="   "
Then  HTTP 400 VALIDATION_ERROR
```

### AC-6: 既有 unit test 不破

```gherkin
Given S042 改動完成 + 既有 fixture 已用 valid description/category
When  ./gradlew test
Then  301 + 新加 = 305 tests / 0 fail
```

### AC-7: multipart upload happy path 不破

```gherkin
Given 合法 SKILL.md（含 description ≤ 1024 + 合法 category）
When  POST /skills/upload
Then  HTTP 201（既有行為）
```

---

## 4. Interface

詳 §2.1 diff。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：`create()` 加 description trim+length / category trim+blank validation

### 5.2 Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`：加 4 case（empty desc / blank desc / long desc / blank category）

### 5.3 Docs
- CHANGELOG `v2.19.0`
- spec-roadmap M38

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | Skill.create 加 description/category validation + 4 unit tests + E2E retest | AC-1~7 | 🔲 |

POC: not required（純 invariant guard；mirror S041 pattern）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.19.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 8s（301 → 305 tests / 0 fail，新加 4 unit tests）；E2E HTTP 6 個 AC 綠燈。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL；305 tests / 0 fail |
| HTTP `POST /skills` JSON description="" | 400 VALIDATION_ERROR + msg "must not be blank" ✓ AC-1 |
| HTTP description="   " | 400 ✓ AC-2 |
| HTTP description=2000 chars | 400 + msg "exceeds 1024 characters (got: 2000)" ✓ AC-3 |
| HTTP category="" | 400 + msg "category must not be blank" ✓ AC-4 |
| HTTP category="   " | 400 ✓ AC-5 |
| 既有 unit test | 305 tests / 0 fail ✓ AC-6 |
| multipart upload happy path | 201 regression OK ✓ AC-7 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：
  - 加 `DESCRIPTION_MAX = 1024` 常數（與 `SkillValidator.DESCRIPTION_MAX` 同值；inline 註解提醒同步）
  - `Skill.create` 加 description trim + non-blank + length 驗證
  - `Skill.create` 加 category trim + non-blank validation（null 仍允許 — schema 允許）
  - SkillCreatedEvent 帶 trimmed description + category（不帶有未 trim 字面）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`：加 4 個 tests（empty desc / blank desc / long desc / blank category）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: description 空字串 → 400 | ✅ PASS | E2E HTTP 確認 |
| AC-2: description 全空白 → 400 | ✅ PASS | E2E HTTP 確認 |
| AC-3: description >1024 chars → 400 | ✅ PASS | message 含 "exceeds 1024 characters (got: 2000)" |
| AC-4: category 空字串 → 400 | ✅ PASS | E2E HTTP 確認 |
| AC-5: category 全空白 → 400 | ✅ PASS | E2E HTTP 確認 |
| AC-6: 既有 unit test 不破 | ✅ PASS | 301 → 305 tests 全綠 |
| AC-7: multipart upload regression | ✅ PASS | happy path 201 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 17 — `POST /api/v1/skills` JSON path 接受空 / 超長 description 與 空白 category；S041 補了 name/author，S042 補完 description/category 同類驗證。

**Fix design rationale**:
- mirror S041 pattern — 4 個欄位驗證一致風格（trim + blank reject + 適用時 length cap）
- DESCRIPTION_MAX = 1024 與 `SkillValidator.DESCRIPTION_MAX` 同字面（agentskills.io spec 來源；S018 AC-14）
- category 不加長度限制 — agentskills.io 沒對 category 設規範；屬自由文本，未來如有 whitelist 需求再獨立 spec
- aggregate factory 是 invariant 最終守門 — 所有 entry path（multipart + JSON POST + 未來新 path）一致

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 仍待設計。S041 + S042 完成 Skill.create 四欄位 invariant 守門。
