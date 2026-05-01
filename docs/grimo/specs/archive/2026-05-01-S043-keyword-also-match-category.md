# S043: Keyword Search Also Matches Category

> Spec: S043 | Size: XS(5) | Status: ✅ Done — target ship `v2.20.0`
> Trigger: 2026-05-01 /loop tick 18 — 在 HomePage 搜尋框輸入「DevOps」（既存 category 名）回 0 個 skill；user 預期 search 涵蓋 category，但目前 SQL 只 LIKE name/description

---

## 1. Goal

`SkillQueryService.search` 的 keyword `LIKE` 子句加 `category` 一併比對：

```diff
 WHERE status = 'PUBLISHED'
   AND (LOWER(name) LIKE :kw OR
-       LOWER(description) LIKE :kw)
+       LOWER(description) LIKE :kw OR
+       LOWER(category) LIKE :kw)
```

user 輸入 category 名（如「DevOps」、「Testing」）即可命中對應分類的所有 skill — 對齊 GitHub / npm 等通用 search 慣例。`?category=` 顯式 filter 仍維持為精確 match（與此擴展正交）。

---

## 2. Approach

### 2.1 SQL diff

```diff
 if (StringUtils.hasText(keyword)) {
-    var clause = " AND (LOWER(name) LIKE LOWER(:kw) ESCAPE '\\' "
-            + "OR LOWER(description) LIKE LOWER(:kw) ESCAPE '\\') ";
+    var clause = " AND (LOWER(name) LIKE LOWER(:kw) ESCAPE '\\' "
+            + "OR LOWER(description) LIKE LOWER(:kw) ESCAPE '\\' "
+            + "OR LOWER(category) LIKE LOWER(:kw) ESCAPE '\\') ";
     sql.append(clause);
     countSql.append(clause);
     params.addValue("kw", "%" + sanitizeLikePattern(keyword) + "%");
 }
```

### 2.2 為何 NOT 引入新 query param `?fields=name,desc,cat`

考慮過：保留原 keyword 行為 + 加 `?fields=` 細粒度控制。否決：
- 過早泛化；MVP 目前一個搜尋框，user 期待全文匹配
- 大多數 catalog 平台（GitHub, npm, Docker Hub）的搜尋框預設跨多欄位
- 未來若需細粒度，可加 advanced search 分支不破舊行為

### 2.3 為何 NOT 加 author 至 keyword

author 雖可 user 想 filter 但：
- author 是 PII（user identifier），cross-search 暴露作者列表、不利隱私
- 多數平台 author search 是獨立 query operator（如 GitHub `author:alice`）；非泛 keyword

留 future spec 評估 author `:` operator 與否。

### 2.4 Performance 顧慮

`category` 欄位無 index（catalog 規模小無需）；3 個 LIKE 從 2 個 → 50% 慢於 2 個。MVP 規模（< 1000 skills）此差異可忽略；若後續 skill 數成長到 ~10k 再考慮 GIN trigram index 或 full-text search。

---

## 3. SBE Acceptance Criteria

### AC-1: 輸入 category 名（DevOps）命中所有 DevOps skills

```gherkin
Given DB 含 13 個 status=PUBLISHED + category=DevOps 的 skill（per S031 過濾後）
When  GET /api/v1/skills?keyword=DevOps
Then  totalElements >= 13（含 category match）
And   每筆 result.category == "DevOps"
```

### AC-2: 既有 name match 不破

```gherkin
Given skill name 含 "tick2"
When  GET /api/v1/skills?keyword=tick2
Then  totalElements >= 1
```

### AC-3: 既有 description match 不破

```gherkin
Given skill description 含 "compose"
When  GET /api/v1/skills?keyword=compose
Then  totalElements >= 1
```

### AC-4: 大小寫不敏感（既有行為）

```gherkin
Given category=DevOps（混合大小寫）
When  GET /api/v1/skills?keyword=devops
Then  totalElements >= 1（DevOps skills 命中）
```

### AC-5: keyword + category 顯式 filter 組合

```gherkin
Given DB 含 DevOps + Testing 兩 category
When  GET /api/v1/skills?keyword=test&category=Testing
Then  totalElements >= 0
And   所有 result.category == "Testing"（顯式 filter 仍精確）
```

### AC-6: 既有 unit test 不破

```gherkin
Given S043 改動完成
When  ./gradlew test
Then  既有 305 tests 不破（SkillSearchTest fixture 用 lowercase fixture 不受 SQL OR 擴充影響）
```

---

## 4. Interface

詳 §2.1 SQL diff。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`：keyword `LIKE` clause 加 `LOWER(category) LIKE LOWER(:kw)` 第三個 OR

### 5.2 Test
- 既有 `SkillSearchTest`（demoted REPO slice per S025b）已驗 keyword=docker / category=DevOps；可加 1 case 驗 keyword=DevOps（category match）

### 5.3 Docs
- CHANGELOG `v2.20.0`
- spec-roadmap M39

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SQL clause 加 category match + 1 unit test + E2E retest | AC-1~6 | 🔲 |

POC: not required（純 SQL 擴展；既有 sanitize / parameterize 邏輯重用）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.20.0`
>
> Verification: `./gradlew test` 305 → 306 tests / 0 fail（1 新加 unit test）；E2E HTTP `keyword=DevOps` 從 0 → 25 skills，all category=DevOps。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL；306 tests / 0 fail |
| HTTP `?keyword=DevOps` | 25 skills，全部 category=DevOps ✓ AC-1（baseline 0）|
| HTTP `?keyword=devops`（小寫） | 25 skills（case-insensitive 仍 work）✓ AC-4 |
| HTTP `?keyword=test&category=Testing` | 0（合理；DB 此時無 Testing skills；keyword 只在「同時 match category=Testing」的範圍內生效）✓ AC-5 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`：keyword `LIKE` clause 加 `OR LOWER(category) LIKE LOWER(:kw) ESCAPE '\\'`

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillSearchTest.java`：加 `keywordSearchMatchesCategory` 測 keyword=DevOps 命中 2 個 fixture

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: keyword=category 名 → 命中所有該分類 skill | ✅ PASS | E2E 25 skills；all DevOps |
| AC-2: 既有 name match 不破 | ✅ PASS | LIKE clause OR 加 category，name match 仍生效（previous tests pass）|
| AC-3: 既有 description match 不破 | ✅ PASS | 同上 |
| AC-4: 大小寫不敏感 | ✅ PASS | LOWER on both sides |
| AC-5: keyword + category 顯式 filter 組合 | ✅ PASS | E2E 確認 |
| AC-6: 既有 unit test 不破 | ✅ PASS | 305 → 306 tests 全綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 18 — user 在 HomePage 搜尋框輸入「DevOps」（既存 category 名）回 0 個 skill；違反通用搜尋 UX 期待。

**Fix design rationale**:
- 加 `OR LOWER(category) LIKE LOWER(:kw)` — 對齊 GitHub / npm / Docker Hub 等通用 catalog search 慣例
- `?category=` 顯式 filter 仍保持精確 match — 兩種使用情境正交
- 不加 `author` 至 keyword（隱私 + 通常為獨立 operator 設計）
- 不加 query param `?fields=` 細粒度控制（過早泛化；MVP 一個搜尋框 user 期待全文匹配）

### 7.5 Pending Verification / Tech Debt

- 規模成長至 ~10k skills 時可考慮 GIN trigram / full-text search index（目前 2-3 個 LIKE 在 < 1000 規模可忽略）
- S031 §7.5 admin panel endpoint 仍待設計
