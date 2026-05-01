# S032: Version Name Consistency（PUT /versions 驗 SKILL.md name 與 skill aggregate 一致）

> Spec: S032 | Size: XS(5) | Status: ✅ Done — target ship `v2.9.0`
> Date: 2026-05-01
> Depends: S018 ✅ + S031 ✅
> Trigger: 2026-05-01 /loop tick 7 — `PUT /api/v1/skills/{id}/versions` 接受 SKILL.md frontmatter `name` 與 aggregate `name` 不同的 zip；造成 storage 中可下載 zip 的 metadata 與平台列出的 skill name 不一致

---

## 1. Goal

`PUT /api/v1/skills/{id}/versions` 在驗 SKILL.md syntax 後，加 name consistency check：zip 的 SKILL.md `name` 必須等於 skill aggregate `name`。違反 → 400 VALIDATION_ERROR。

POST /skills/upload 不需此檢查 — 該 path 從 SKILL.md 抽 name 建立 aggregate，因此 name 必相等。

---

## 2. Approach

### 2.1 SkillCommandService.addVersion 加 guard

```diff
 var validation = skillValidator.validate(skillMdContent);
 if (!validation.valid()) {
     ...
     throw new IllegalArgumentException("SKILL.md validation failed: ...");
 }
+
+// S032: 確保 zip SKILL.md name 與 aggregate name 一致 — 防 download zip metadata vs 平台 listing 不一致
+var skill = skillRepo.findById(skillId)
+        .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
+var zipName = (String) validation.metadata().get("name");
+if (zipName != null && !zipName.equals(skill.getName())) {
+    throw new IllegalArgumentException(
+            "SKILL.md name '" + zipName + "' does not match skill name '" + skill.getName() + "'");
+}

 // AC-7 service-layer predicate
 if (skillVersionRepo.existsBySkillIdAndVersion(skillId, version)) {
     throw new VersionExistsException("Version " + version + " already exists");
 }
-var skill = skillRepo.findById(skillId)
-        .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
 skill.recordVersionPublished(version);
```

`findById` 從原本 line 145 上移到 name check 之前；既有後續邏輯重用同一 `skill` 變數。

### 2.2 為何 IllegalArgumentException → 400

既有 `IllegalArgumentException` → 400 VALIDATION_ERROR 已 wired（per `GlobalExceptionHandler:33`）。本 case 屬「user-supplied zip 違反業務規則」屬 validation 範疇 → 400 與既有「SKILL.md 缺失 / 驗證失敗」一致。

不選 409 STATE_CONFLICT — 該 code 為 server-side 狀態衝突（per S030）；client-supplied bad data 屬 400。

### 2.3 為何 NOT 在 SkillValidator 層加

`SkillValidator` 純驗 SKILL.md syntax（per agentskills.io spec），不知 aggregate context；name consistency 為 cross-context 規則，屬 service layer 編排。

### 2.4 為何 zipName == null 時不報錯

`name` 缺失已被 `SkillValidator` 在前面 catch（required field 驗證）；防禦性 null check 避免 NPE，但 null 時就讓 validation 早報錯。

---

## 3. SBE Acceptance Criteria

### AC-1: PUT 版本 zip name 與 skill 不同 → 400 VALIDATION_ERROR

```gherkin
Given alice 上傳 skill A，name="real-name"
When  PUT /api/v1/skills/{A}/versions with zip 內 SKILL.md name="different-name"
Then  HTTP 400
And   error code "VALIDATION_ERROR"
And   message 含 "does not match"
```

### AC-2: PUT 版本 zip name 與 skill 一致 → 200

```gherkin
Given alice 上傳 skill A，name="real-name"
When  PUT /api/v1/skills/{A}/versions with zip 內 SKILL.md name="real-name", version=1.1.0
Then  HTTP 200
And   skill latestVersion = "1.1.0"
```

### AC-3: 既有 unit test 不破

```gherkin
Given S032 改動完成
When  ./gradlew test
Then  既有 292 tests + 1 新增 test 全 PASS（既有 test 多用 same-name fixture，符合新規則）
```

### AC-4: POST /skills/upload 行為不變

```gherkin
Given 一個 SKILL.md name="alpha" 的 zip
When  POST /api/v1/skills/upload
Then  HTTP 201
And   skill name = "alpha"（從 SKILL.md 抽出，必 self-consistent）
```

---

## 4. Interface

詳 §2.1 diff。

---

## 5. File Plan

### 5.1 Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`（addVersion 加 guard + 上移 findById）

### 5.2 Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java` 或同 path 加 1 個 unit test：name mismatch → IllegalArgumentException + message

### 5.3 Docs
- CHANGELOG `v2.9.0`
- spec-roadmap M28

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | addVersion 加 name consistency guard + 1 unit test + E2E retest 全 4 AC | AC-1~4 | 🔲 |

POC: not required（純 invariant guard；validation 邏輯既有，加一行 check）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.9.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 40s（既有 292 + 新加 1 = 293 tests / 0 fail）；E2E HTTP AC-1~4 全綠：mismatch zip → 400 VALIDATION_ERROR；matching zip → 200 + latestVersion 1.1.0；POST /upload 行為不變。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 40s；293 tests / 0 fail（含 `addVersion_nameMismatch_rejects` 新 test）|
| HTTP `PUT /skills/{A}/versions` 用 mismatch zip | **400 VALIDATION_ERROR** + msg `"SKILL.md name 'definitely-different' does not match skill name 't7-s032-de3c8bcd'"` ✓ AC-1 |
| HTTP `PUT /skills/{A}/versions` 用 matching zip | **200** + latestVersion=1.1.0 ✓ AC-2 |
| 既有 unit tests | 292 既有 + 1 新加 全 PASS ✓ AC-3 |
| `POST /skills/upload`（baseline） | 行為不變 — 從 SKILL.md 抽 name 建立 aggregate，self-consistent ✓ AC-4 |

### 7.2 Files Changed

#### Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：`addVersion()` 加 name consistency check — 上移 `findById` 至 validation 之後、version 重複 check 之前；新增 IllegalArgumentException with structured log 含 `skillId / aggregateName / zipName`。

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java`：加 `addVersion_nameMismatch_rejects` test（uploadSkill 建立 skill A → 用不同 name 的 zip 嘗試 PUT 1.1.0 → 拋 IllegalArgumentException + 既有 1.0.0 不被破壞）。

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: mismatch → 400 VALIDATION_ERROR | ✅ PASS | E2E HTTP message 準確包含 zipName + aggregateName |
| AC-2: matching → 200 | ✅ PASS | latestVersion 升至 1.1.0 |
| AC-3: 既有 unit test 不破 | ✅ PASS | 293 tests 全綠 |
| AC-4: POST /skills/upload 行為不變 | ✅ PASS | path 從 SKILL.md 抽 name 建 aggregate；structurally name 必相等 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 7 — 測試發現可以對 skill A（name=`t7-json-then-version-XXX`）PUT 一個 SKILL.md name=`random-different-name` 的 zip，HTTP 200 接受；造成 storage 內可下載 zip 的 metadata 與平台列出的 skill name 矛盾。

**Fix design rationale**:
- 統一 invariant — 整個生命週期內，aggregate name 與 zip SKILL.md name 必須一致；違反屬 client-supplied bad data → 400 VALIDATION_ERROR
- 上移 `findById` 至 validate 後：避免 DB query 在純 syntax error 時白做
- `IllegalArgumentException` → 既有 `GlobalExceptionHandler:33` mapping → 400 VALIDATION_ERROR；不引入新 exception class
- 不在 `SkillValidator` 加（純 SKILL.md syntax 範疇，不知 aggregate context）；在 service layer 編排 cross-context invariant

**Defense-in-depth note**: 此 invariant 也阻止「上傳一個高分 PUBLISHED skill A v1.0.0 後，PUT 一個惡意內容 zip 但 name 改成另一名 v1.1.0」變身攻擊（分數刷量 + 內容偷換）。每個 version 必延續同 aggregate name，下游 client 看 metadata 與下載內容一致。

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 的 vector cleanup + admin panel 議題範圍不變。
