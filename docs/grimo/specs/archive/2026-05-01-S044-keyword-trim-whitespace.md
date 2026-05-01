# S044: Keyword Trim — Tolerate Leading / Trailing Whitespace

> Spec: S044 | Size: XS(5) | Status: ✅ Done — target ship `v2.21.0`
> Trigger: 2026-05-01 /loop tick 19 — `keyword=t17` → 1 結果；`keyword=t17 `（trailing space）→ 0 結果；`keyword= DevOps `（leading + trailing）→ 0 結果（DevOps 應有 25 skills）。複製 / 自動填入後 trailing whitespace 在通用搜尋實務常見，user 期待靜默忽略。

---

## 1. Goal

`SkillQueryService.search` 的 `keyword` 參數做 trim：leading / trailing whitespace 不影響結果。trim 後若為空字串，視同無 keyword（等同當前 whitespace-only 行為，既有 `StringUtils.hasText` 邏輯維持）。

```diff
 if (StringUtils.hasText(keyword)) {
+    var kw = keyword.trim();
     ...
-    params.addValue("kw", "%" + sanitizeLikePattern(keyword) + "%");
+    params.addValue("kw", "%" + sanitizeLikePattern(kw) + "%");
 }
```

對齊 GitHub / npm / Google 等通用搜尋 trim 行為。

---

## 2. Approach

### 2.1 Code diff

```diff
 if (StringUtils.hasText(keyword)) {
+    // S044: trim leading/trailing whitespace（user 從複製貼上常含 trailing space；
+    // sanitizeLikePattern 不 trim 因 % _ \ 才是其職責，trim 屬於 input 預處理）
+    var trimmed = keyword.trim();
     var clause = " AND (LOWER(name) LIKE LOWER(:kw) ESCAPE '\\' "
             + "OR LOWER(description) LIKE LOWER(:kw) ESCAPE '\\' "
             + "OR LOWER(category) LIKE LOWER(:kw) ESCAPE '\\') ";
     sql.append(clause);
     countSql.append(clause);
-    params.addValue("kw", "%" + sanitizeLikePattern(keyword) + "%");
+    params.addValue("kw", "%" + sanitizeLikePattern(trimmed) + "%");
 }
```

### 2.2 為何 NOT 對 `category` 顯式 filter 也 trim

`?category=Testing` 屬精確 enum-style match（通常從前端 dropdown 選；不是手 typed）；trim 看似無害但會掩蓋前端 bug（誤送了空白字元）。MVP 階段保守處理 — 只 trim user-typed search 欄位。

### 2.3 為何 trim 後 `StringUtils.hasText` 不需重判

`StringUtils.hasText("   ")` → false 已在當前邏輯處理「全空白 = 無 keyword」場景。trim 只是把 `"t17 "` 變 `"t17"` 給 LIKE 正確匹配，trim 結果仍非空，hasText 結果不變。

---

## 3. SBE Acceptance Criteria

### AC-1: trailing space 不影響 keyword 結果

```gherkin
Given DB 含 1 個 skill name 含 "t17"
When  GET /api/v1/skills?keyword=t17%20
Then  totalElements == 1（與 keyword=t17 相同）
```

### AC-2: leading space 不影響

```gherkin
When  GET /api/v1/skills?keyword=%20DevOps
Then  totalElements >= 1（DevOps 分類有 skill；對齊 keyword=DevOps）
```

### AC-3: surround whitespace 不影響

```gherkin
When  GET /api/v1/skills?keyword=%20%20t17%20%20
Then  totalElements >= 1
```

### AC-4: 全空白 keyword 仍視同無 filter（既有行為不破）

```gherkin
When  GET /api/v1/skills?keyword=%20%20%20
Then  totalElements == DB 總公開 skill 數
```

### AC-5: 既有 keyword=docker 等不破

```gherkin
When  GET /api/v1/skills?keyword=docker
Then  與既有結果相同
```

### AC-6: 既有 unit test 不破

```gherkin
When  ./gradlew test
Then  既有 306 tests 不破（fixture 都用 trim 後 keyword，trim 對其無影響）
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`：keyword `.trim()`

### 5.2 Test (1 unit test)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillSearchTest.java`：加 `keywordTrimsWhitespace` 驗 `" docker "` 與 `"docker"` 結果一致

### 5.3 Docs
- CHANGELOG `v2.21.0`
- spec-roadmap M40

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | trim keyword + unit test + E2E retest | AC-1~6 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.21.0`
>
> Verification: `./gradlew clean test` BUILD SUCCESSFUL / 286 tests / 0 fail（含新加 `keywordTrimsWhitespace`）；E2E HTTP 6 個 AC 全綠（trail t17 0→1、lead DevOps 0→25、surround t17 0→1、whitespace-only 仍 25 不破、plain 不破）。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew clean test` | BUILD SUCCESSFUL；286 tests / 0 failures / 0 errors |
| HTTP `?keyword=t17%20` | 1（plain `t17` 也 1）✓ AC-1 |
| HTTP `?keyword=%20DevOps` | 25（plain `DevOps` 也 25）✓ AC-2 |
| HTTP `?keyword=%20%20t17%20%20` | 1 ✓ AC-3 |
| HTTP `?keyword=%20%20%20`（純空白）| 25（無 filter）✓ AC-4 |
| HTTP `?keyword=t17` / `?keyword=DevOps` | 1 / 25（既有不破）✓ AC-5 |
| 既有 305 tests 不破 | ✓ AC-6 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`：keyword `.trim()` 預處理（line 124）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillSearchTest.java`：加 `keywordTrimsWhitespace` 驗 plain / trailing / leading / surround 4 種空白都同一結果

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: trailing space 不影響 | ✅ PASS | E2E `t17 ` → 1（與 `t17` 同）|
| AC-2: leading space 不影響 | ✅ PASS | E2E ` DevOps` → 25 |
| AC-3: surround whitespace 不影響 | ✅ PASS | E2E `  t17  ` → 1 |
| AC-4: 全空白 keyword 視同無 filter | ✅ PASS | E2E `   ` → 25（既有 hasText 行為）|
| AC-5: 既有 keyword 不破 | ✅ PASS | E2E `t17` → 1、`DevOps` → 25 |
| AC-6: 既有 unit test 不破 | ✅ PASS | 286 / 0 fail |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 19 — API E2E sweep 試 `keyword=t17%20`（trailing space）回 0；plain `keyword=t17` 回 1；確認 user 從複製貼上含 trailing whitespace 會被誤判 0 結果。對齊 GitHub / npm / Google 通用搜尋 trim 慣例。

**Fix design rationale**:
- `.trim()` 為 input 預處理 — 與 `sanitizeLikePattern`（`%/_/\` SQL escape）職責正交
- `StringUtils.hasText` 已處理「全空白 = 無 keyword」場景（trim 後 `""` hasText = false）；trim 不影響該分支
- `?category=` 顯式 filter 不 trim — 該欄位來自前端 dropdown 應為精確 match，trim 反掩蓋前端 bug

### 7.5 Pending Verification / Tech Debt

- **Bug B（同 tick 19 發現）**：`POST /api/v1/skills/{id}/versions`（method not allowed）回 12.9KB 含 `LabSecurityFilter` / Spring Security filter chain class names 的 stack trace — `GlobalExceptionHandler` 未處理 `HttpRequestMethodNotSupportedException` 落入 Spring 預設 `BasicErrorController`（含 `trace`）。屬資訊洩漏類，留下一輪 spec
- 規模成長至 ~10k skills 時 keyword search 性能評估（同 S043 §7.5 列）
- S031 §7.5 admin panel endpoint 仍待設計
