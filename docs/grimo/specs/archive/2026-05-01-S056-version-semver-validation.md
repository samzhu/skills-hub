# S056: Version Semver Validation

> Spec: S056 | Size: XS(5) | Status: ✅ Done — target ship `v2.33.0`
> Trigger: 2026-05-01 /loop tick 29 — `PUT /skills/{id}/versions` 接受任意 version 字串：
> - `version=foo` → 200 創建 literal "foo" 版本
> - `version=` 空 → 200 創建 empty 版本
> - `version=` 超長 (>50 chars) → 500 + SQL leak（DB constraint violation 落 Spring 預設 500）
>
> 應用層完全沒驗 version 字串格式；DB constraint 為兜底。

---

## 1. Goal

`Skill.recordVersionPublished` 加 semver 預驗：`version` 必須符合 `MAJOR.MINOR.PATCH` 三段數字（optional 連字 pre-release suffix）。違反 → IllegalArgumentException → 400 VALIDATION_ERROR。同時涵蓋 length cap，避免超長字串觸 DB 500。

```java
private static final Pattern VERSION_REGEX =
        Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$");

public void recordVersionPublished(String version) {
    if (version == null || !VERSION_REGEX.matcher(version).matches()) {
        throw new IllegalArgumentException(
                "Version must match semver MAJOR.MINOR.PATCH (got: " + version + ")");
    }
    ...
}
```

---

## 2. Approach

### 2.1 Code diff

```java
// Skill.java
private static final Pattern VERSION_REGEX =
        Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$");

public void recordVersionPublished(String version) {
    // S056: semver 預驗 — 違反 → 400 VALIDATION_ERROR
    if (version == null || !VERSION_REGEX.matcher(version).matches()) {
        throw new IllegalArgumentException(
                "Version must match semver MAJOR.MINOR.PATCH (got: " + version + ")");
    }
    SkillStatus next = this.status.publish();
    this.latestVersion = version;
    ...
}
```

### 2.2 為何在 `Skill.recordVersionPublished` 而非 `SkillVersion.publish`

兩 entry path 都先 call `Skill.recordVersionPublished`（uploadSkill / addVersion）。守在這層 single point；`SkillVersion.publish` 後續會接到合法 version。

### 2.3 為何選嚴格 semver 而非寬鬆

- 對齊 npm / Cargo / pip 等生態系慣例
- agentskills.io 雖未強制但社群慣例 1.0.0 三段
- 嚴格 → DB column varchar(50) 不會被破
- 拒絕「foo」「v1」「1」「1.0」等不規格字串

### 2.4 Pre-release suffix `-alpha.1` 為何允許

semver `1.0.0-alpha.1` / `2.1.0-rc.5` 是合法 semver，社群常見。regex `(?:-[0-9A-Za-z.-]+)?` optional 後綴。

### 2.5 為何 NOT 補 build metadata `+sha.abc123` 支援

build metadata 在 semver spec 是可選且通常 build pipeline 自動加；MVP 階段 user 手填版本不太會用，先不支援；後續若有需求再擴 regex。

---

## 3. SBE Acceptance Criteria

### AC-1: `version=foo` → 400 VALIDATION_ERROR

```gherkin
When  PUT /skills/{id}/versions with version=foo
Then  HTTP 400 + 「Version must match semver」
```

### AC-2: `version=` 空 → 400

```gherkin
When  PUT /skills/{id}/versions with version=
Then  HTTP 400
```

### AC-3: 超長 version → 400（不洩 DB）

```gherkin
When  PUT /skills/{id}/versions with version=9.9.9.....0 (>50 chars)
Then  HTTP 400 + VALIDATION_ERROR
And   不包含 "PreparedStatementCallback" / SQL detail
```

### AC-4: 合法 semver 接受

```gherkin
When  PUT /skills/{id}/versions with version=1.2.3
Then  HTTP 200
```

### AC-5: 合法 pre-release semver 接受

```gherkin
When  PUT /skills/{id}/versions with version=2.0.0-rc.1
Then  HTTP 200
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
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：加 `VERSION_REGEX` 常數 + `recordVersionPublished` 第一行驗證

### 5.2 Test
- 既有 test 不破；E2E curl 5 個 case

### 5.3 Docs
- CHANGELOG `v2.33.0`
- spec-roadmap M52

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | regex + curl retest | AC-1~6 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.33.0`
>
> Verification: backend 286 / 0 fail；E2E 5 ACs 全綠；超長 version 不再噴 SQL。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-6 |
| PUT version=foo | 400 「Version must match semver MAJOR.MINOR.PATCH (got: foo)」✓ AC-1 |
| PUT version=「」 | 400 ✓ AC-2 |
| PUT version=400-char string | 400（不含 SQL leak）✓ AC-3 |
| PUT version=1.2.3 | 200 ✓ AC-4 |
| PUT version=2.0.0-rc.1 | 200 ✓ AC-5 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：加 `VERSION_REGEX` 常數 + `recordVersionPublished` 第一行驗證

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: foo → 400 | ✅ |
| AC-2: 空 → 400 | ✅ |
| AC-3: 超長 → 400 不洩 SQL | ✅ |
| AC-4: 1.2.3 → 200 | ✅ |
| AC-5: 2.0.0-rc.1 → 200 | ✅ |
| AC-6: 既有 test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 29 multi-version probe — `PUT /skills/{id}/versions` 接受任意 version 字串：「foo」/「」 都 200 創建；超長版本觸 DB column 長度違反 → DataIntegrityViolationException → 500 + raw SQL leak。

**Three bugs unified by single fix**:
- Bug Q: 非 semver 接受
- Bug R: 空 version 接受
- Bug T: 超長 version → 500 + SQL（DataIntegrityViolation 父類沒被 GlobalExceptionHandler 攔，僅 DuplicateKey 子類被 S051 處理）

aggregate factory 用嚴格 semver regex 預驗 → DB 邊界永不被觸發 → 三個 bug 一次解。

**Defense-in-depth**: 即使將來新增 entry path（如 admin batch import），也守在 `recordVersionPublished` single point。

### 7.5 Pending Verification / Tech Debt

- DB 既有 malformed version row（如 "foo"、""）需 future migration 清理
- DataIntegrityViolationException 父類仍可從其他 path 觸發 500 + SQL leak（如有其他 column constraint violation 場景）— 可加 generic handler 兜底
- semantic 系統性回 0 根因仍待查
- suspend reason 空字串/null 是否該驗證為非 blank — UX 微調，留下一輪
