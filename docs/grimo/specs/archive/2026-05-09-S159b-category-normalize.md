# S159b: Category storage normalize — V20 lowercase migration + CHECK constraint

> Spec: S159b | Size: S(5) → S(8)（含 Round 2 bug fix）| Status: ⏳ Dev (bug fix)
> Date: 2026-05-09
> Origin: 拆自 S159 META §2.1 — query API hardening
>
> **Pre-flight 2026-05-12（/planning-tasks next）發現（已 patch §2/§4）：**
> 1. V19 已被 S161c (XSS backfill) 佔用 — 本 spec 改用 **V20**
> 2. V1 schema `category VARCHAR(50)`（非 §2.1 原稿 100）
> 3. `Skill.create()` line 197 已 `.trim()`（S042）— 本 spec 在 trim 後加 `.toLowerCase()`
> 4. `Skill.update()` line 378-384（S163）已存在 — 同 path 加 normalize
> 5. Frontend display sweep 比原稿廣：6 個 production 站點（SkillCard / CategorySidebar / v2/PageHeader / PublishReviewPage / PublishValidatePage / CollectionDetailPage skill list）；原稿提到的 `v2/FilterChip.tsx` 不存在

---

## 1. Goal

**一句話：** 把 `skills.category` 從「大小寫不一致」（"Testing" vs "testing" vs "TESTING"）統一為 lowercase，加 DB CHECK constraint 防止未來 drift，前端用 `capitalize()` 顯示。

**為什麼重要：**
- query `?category=Testing` 找不到 `category="testing"` 的 row（case-sensitive 比對）
- AC list 看到「Testing」「testing」兩個 category，user 困惑
- 既有 V1 schema 沒 CHECK constraint，aggregate write path 也沒 normalize → 任何 caller 帶任意 case 都 INSERT 進 DB

**非目標：**
- 不改 category enum / allowlist 機制（本 spec 維持自由 string）
- 不做 category rename UI（admin 手動 SQL 改即可）

---

## 2. Approach

### 2.1 現況

`Skill.create(CreateSkillCommand cmd)` 已 `cmd.category().trim()`（S042）但無 lowercase；`Skill.update()` line 378-384（S163）相同：trim 但無 lowercase。`SkillQueryService.search()` raw SQL `WHERE category = :cat` line 258 — case-sensitive 比對（line 252 keyword path 已 `LOWER(category) LIKE LOWER(:kw)` — S043 fuzzy match 不受影響）。

DB schema：`category VARCHAR(50)`（V1 line 49）— 無 CHECK constraint，無 ON INSERT/UPDATE normalize trigger。

既存 row（dev DB sample）：可能 mix 大小寫（待 migrate 階段查 distinct）。Frontend `IconTile.categoryKey()` line 34 已自行 toLowerCase 配色（S159b 後可少防一層，但本 spec 不動 IconTile）。

### 2.2 設計

**3 層防線：**

| 層 | 動作 |
|----|------|
| **DB** | V20 migration: `UPDATE skills SET category = lower(trim(category))` + 加 `CHECK (category IS NULL OR category = lower(category))` constraint |
| **Aggregate (write)** | `Skill.create()` line 197（既有 trim）+ `Skill.update()` line 379（既有 trim）皆加 `.toLowerCase()` |
| **Read (controller)** | `SkillQueryController` line 146 `@RequestParam category` 起手 `.trim().toLowerCase()`（null-safe；shipped 順序為 trim 在前）|
| **Frontend (display)** | `capitalize(s)` helper 在 6 個站點：`SkillCard`、`CategorySidebar`、`v2/PageHeader`、`PublishReviewPage`、`PublishValidatePage`、`CollectionDetailPage`（skill list 內）|

**API contract：** query param `?category=` 也 lowercase normalize（controller 起手 `category.toLowerCase()`），讓 caller 大小寫無感。

### 2.3 Migration 細節

```sql
-- V20__normalize_skill_category.sql
UPDATE skills SET category = lower(trim(category)) WHERE category IS NOT NULL;
ALTER TABLE skills ADD CONSTRAINT skills_category_lowercase
  CHECK (category IS NULL OR category = lower(category));
```

**風險：** 既存 distinct value 內可能含 leading/trailing spaces 或 special chars — `trim` 也加進來。

### 2.4 Frontend 顯示

```tsx
// utility
const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// SkillCard / FilterChip
<Badge>{capitalize(skill.category)}</Badge>  // "testing" → "Testing"
```

或更乾淨：CSS `text-transform: capitalize`（純 CSS，無 JS overhead）— 但對 multi-word category（"web-dev"）會大寫每個 word，不適用。所以走 helper 函式。

---

## 3. Acceptance Criteria

```
AC-1: V20 migration 把既存 row category 全 lowercase
  Given 既存 skills 表 row category mix 大小寫（"Testing", "testing", "TESTING"）
  When V20 migrate
  Then 所有 row category 都 lowercase + trim 後值
  And distinct(category) 行數可能變少（合併 case-only 重複）

AC-2: V19 CHECK constraint 防未來 drift
  Given V20 套用後
  When `INSERT INTO skills (..., category) VALUES (..., 'Testing')`（直接 SQL，繞 aggregate）
  Then 拒收（CHECK 違規）

AC-3: Skill.create() write-side normalize
  Given Alice 透過 API publish 帶 category="Testing"
  When SkillCommandService.uploadSkill()
  Then DB skills.category = "testing"

AC-4: Query controller normalize input
  Given GET /api/v1/skills?category=Testing
  When SkillQueryController
  Then 內部 search 查 category="testing" → 回正確 row（不漏 case 不一致的）

AC-5: Frontend SkillCard 顯首字母大寫
  Given skill.category="testing"
  When render SkillCard
  Then 顯「Testing」（capitalize helper）

AC-6: 既有 test suite 不破
  Given V20 migrate + write/read normalize
  When 跑既有 ./gradlew test + npm test
  Then 全綠（test fixture 若 hardcode 大寫 category 須 update）
```

**驗證指令：** `cd backend && ./gradlew test` + `cd frontend && npm test`

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V20__normalize_skill_category.sql` | **新增** — UPDATE lowercase + CHECK constraint |
| `backend/src/main/java/.../skill/domain/Skill.java` | `create()` line 197 + `update()` line 379 加 `.toLowerCase()` |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `@RequestParam("category")` 起手 `.toLowerCase().trim()` null-safe |
| `backend/src/test/java/.../skill/V20MigrationTest.java`（或同 module）| **新增** — Flyway clean migrate verify lowercase + CHECK reject |
| 既有測試 fixture（`S016EndToEndSmokeTest`、`TestDataControllerTest`、`SkillPublishForgeryTest`、`SkillSearchTest` 等）內 hardcode 大寫 category | sweep update 為 lowercase |

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/text.ts`（新檔或既有 utils）| 加 `capitalize(s: string)` helper |
| `frontend/src/components/SkillCard.tsx` line 54 | category badge 改套 helper |
| `frontend/src/components/CategorySidebar.tsx` line 52 | `{cat.name}` → `{capitalize(cat.name)}` |
| `frontend/src/components/v2/PageHeader.tsx` line 163 | `{skill.category}` → 套 helper |
| `frontend/src/pages/PublishReviewPage.tsx` line 106 | `Field` value 套 helper |
| `frontend/src/pages/PublishValidatePage.tsx` line 107 | `{skill.category}` → 套 helper |
| `frontend/src/pages/CollectionDetailPage.tsx` line 169 | skill list item category 套 helper |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1 | `V20MigrationTest` Flyway clean migrate verify distinct(category) 全 lowercase |
| AC-2 | `V20MigrationTest` 嘗試 INSERT 大寫 → expect SQLException |
| AC-3 | `SkillCommandServiceTest` 帶大寫 category → 驗 DB row 是 lowercase |
| AC-4 | `SkillQueryControllerTest` `param("category", "Testing")` → 200 + 正確 row |
| AC-5 | `SkillCard.test.tsx` mock category="testing" → render 含 "Testing" |
| AC-6 | sweep + run all tests |

### 5.2 手動

無 — 自動化覆蓋足夠。

---

## 6. Task Plan

**POC：not required** — Flyway / PostgreSQL `lower()` / CHECK constraint 皆已在用（V1-V19 已驗證）；無新 SDK / 跨環境 CLI；spec §2 設計屬於 *Validated* 類別。

**E2E：not required** — 純資料層 normalize；backend test 走 Testcontainers 真 PostgreSQL（不 stub）；frontend 走 Vitest 純 component（無 boundary stub）。

| Task | 範圍 | AC | Depends |
|------|------|-----|---------|
| T01 | V20 migration（backfill `lower(trim)` + CHECK constraint）+ V20MigrationTest **＋ backend 同步 (write-side `Skill.create`/`update` 加 `.toLowerCase()` + read-side controller `?category=` lowercase) + 全面 test fixture sweep**（raw INSERT、`fromRow`、API param、assertion 一次到位）+ AC-3/AC-4 unit tests | AC-1, AC-2, AC-3, AC-4 | none |
| T02 | Frontend `capitalize()` helper + 6 站點顯示 sweep + component test + final regression（AC-6 全 suite 綠）| AC-5, AC-6 | T01 |

**T01/T02 merge 說明（2026-05-12 implementing-task 預檢決策）：** 原 T01（只做 V20 migration）拆 T02（write-side normalize）不可行 — V20 加 CHECK constraint 後，**任何**帶大寫的 INSERT（raw SQL fixture / API param 經 aggregate / `fromRow`）皆會被 DB 拒收。為讓 T01 PASS（全 suite 綠）必須同步：(a) 加 CHECK 前 backfill 既存 row、(b) aggregate `create`/`update` 入 store 前 lowercase、(c) controller 起手 lowercase 防 query miss、(d) sweep `V2MigrationTest` / `MigrationBackfillTest` / `SkillCommandServiceDeleteTest` / `SearchProjectionAclWriteTest` / `SkillSearchTest` / `S016EndToEndSmokeTest` / `TestDataControllerTest` / `SkillPublishForgeryTest` 等 fixture 與 assertion 一致 lowercase。這是 V20 enforcement 的不可分割副作用，非 scope creep。

---

## 7. Implementation Results

> Status: ✅ Done（2026-05-12）

### 7.1 Verification

| 命令 | 結果 |
|------|------|
| `cd backend && ./gradlew test` | ✅ exit 0 — **803 tests passed**（baseline 799 + 4 S159b AC-3/AC-4 新測試）|
| `cd backend && ./gradlew compileTestJava` | ✅（隨 `test` 一併）|
| `cd frontend && npm test` | ✅ exit 0 — **71 files / 400 tests passed**（baseline 395 + 4 capitalize unit + 1 SkillCard AC-5）|

**E2E：不需執行** — 已記錄理由（per §6 header）：純資料層 normalize；backend Testcontainers 已覆蓋 Flyway V20 + CHECK enforcement + backfill 真 SQL 行為（IT level），frontend Vitest 覆蓋 capitalize render 行為。本 spec 未引入新跨服務 boundary、無 framework wiring / 子程序 / 外部 credential。

### 7.2 Key Findings

**Pre-flight 預檢揭露 5 個 spec drift（已 patch §2/§4）：**
1. V19 已被 S161c（XSS backfill）佔用 — 本 spec 改用 V20（spec 原稿 §2.3 SQL 檔名修正）
2. V1 schema `category VARCHAR(50)`（非 §2.1 原稿 100）
3. `Skill.create()` 既有 `.trim()`（S042 留），加 `.toLowerCase()` on top 即可
4. `Skill.update()` 已存在於 S163（line 378-384）— 不是「future」
5. Frontend display sweep 廣度：6 站點（SkillCard / CategorySidebar / v2/PageHeader / PublishReviewPage / PublishValidatePage / CollectionDetailPage），原稿提及的 `v2/FilterChip.tsx` 不存在

**Mid-implementation 揭露的關鍵架構耦合：**

原計畫拆 T01（只做 V20）+ T02（backend normalize）不可行 — V20 加 CHECK 後，**所有**帶大寫的 INSERT（raw SQL fixture / API param 經 aggregate / `Skill.fromRow + repo.save`）皆被 DB 拒收。為讓 T01 PASS 必須同步 backfill + write-side normalize + 全面 fixture sweep（30+ 個檔案）+ controller param normalize，否則 ~86 個既有 test 同時 RED。**Lesson learned**：DB-level constraint 變更的 task 一定要把「相容性 sweep」當作 atomic part of the migration task — split 製造 mid-state failure。

**Backfill 範式（V20 SQL pattern）：**

```sql
-- V20__normalize_skill_category.sql
-- 1. Backfill 既存 row（必須先做，不然 CHECK 加上時違反自己）
UPDATE skills SET category = lower(trim(category)) WHERE category IS NOT NULL;

-- 2. 加 CHECK constraint（NULL allowed；同 V1 column 設計）
ALTER TABLE skills ADD CONSTRAINT skills_category_lowercase
  CHECK (category IS NULL OR category = lower(category));
```

**Frontend capitalize pattern：**

```ts
// frontend/src/lib/text.ts
export function capitalize(s: string | null | undefined): string {
  if (!s) return ''
  return s.charAt(0).toUpperCase() + s.slice(1)
}
```

中文字串（如「雲端維運」）對 `charAt(0).toUpperCase()` 無作用（Chinese 無 case 概念）→ display 不變；fixture 既有中文 case 自動相容。

### 7.3 AC Results

| AC | 驗證測試 | 結果 |
|----|---------|------|
| AC-1 backfill | `V20MigrationTest#flywaySchemaHistory_containsV20` + `#v20BackfillSql_lowercasesMixedCaseRows` | ✅ PASS |
| AC-2 CHECK reject | `V20MigrationTest#checkConstraint_rejectsRawUppercaseInsert` + `#checkConstraint_acceptsLowercaseAndNull` | ✅ PASS |
| AC-3 aggregate write-side | `SkillAggregateTest#s159b_create_lowercasesCategory` + `#s159b_update_lowercasesCategory` | ✅ PASS |
| AC-4 controller read-side | `SkillQueryControllerApiContractTest#s159b_searchCategoryParam_lowercasedBeforeServiceCall` + `#s159b_searchCategoryParam_trimmedAndLowercased` | ✅ PASS |
| AC-5 frontend display | `SkillCard.test.tsx` S159b case + `capitalize` 4 unit tests | ✅ PASS |
| AC-6 既有 suite 不破 | 全 backend + frontend test suite | ✅ PASS（803 + 400 = 1203 tests, 0 failures） |

### 7.4 Files Changed

**Backend production:**
- `backend/src/main/resources/db/migration/V20__normalize_skill_category.sql` — 新增（backfill + CHECK）
- `backend/src/main/java/.../skill/domain/Skill.java` — `create()` + `update()` 加 `.toLowerCase()`
- `backend/src/main/java/.../skill/query/SkillQueryController.java` — `?category=` param normalize

**Backend tests:**
- `backend/src/test/java/.../db/V20MigrationTest.java` — 新增（4 個 IT，drop/INSERT/UPDATE/restore CHECK roundtrip 驗證）
- `backend/src/test/java/.../skill/domain/SkillAggregateTest.java` — 加 2 個 AC-3 unit test
- `backend/src/test/java/.../skill/query/SkillQueryControllerApiContractTest.java` — 加 2 個 AC-4 captor unit test
- Bulk fixture sweep（30+ Java + SQL 檔）：`"DevOps"`/`"Testing"` / `'Test'`/`'DevOps'`/`'Security'` etc. → lowercase；assertions 同步更新

**Frontend production:**
- `frontend/src/lib/text.ts` — 新增 `capitalize()` helper
- `frontend/src/components/SkillCard.tsx` — category badge display 套 `capitalize()`
- `frontend/src/components/CategorySidebar.tsx` — `cat.name` display 套 `capitalize()`
- `frontend/src/components/v2/PageHeader.tsx` — hero meta row category 套 `capitalize()`
- `frontend/src/pages/PublishReviewPage.tsx` — 「分類」field value 套 `capitalize()`
- `frontend/src/pages/PublishValidatePage.tsx` — category 顯示套 `capitalize()`
- `frontend/src/pages/CollectionDetailPage.tsx` — skill list item category 套 `capitalize()`

**Frontend tests:**
- `frontend/src/lib/text.test.ts` — 新增（4 個 capitalize unit case）
- `frontend/src/components/SkillCard.test.tsx` — 加 S159b AC-5 case
- `frontend/src/components/CategorySidebar.test.tsx` — fixture 既為 lowercase，display assert 改 capitalize 結果

### 7.5 Out-of-scope（保留現況）

- `IconTile.categoryKey()` line 34 既有 `toLowerCase()` 防呆碼保留不動 — V20 後雖然 input 一定是 lowercase，防呆碼不破代碼留著無害
- `EditSkillModal` / `PublishPage` / `CreateCollectionModal` 表單 input 顯示用戶自己打的字（state-local），不套 `capitalize`；送 API 時 aggregate write-side 會 lowercase
- `MySkillsPage` / `NotificationsPage` 不在 6-site sweep — 前者只用 IconTile 顏色，後者是 notification.category（domain 不同）
- `SkillCommandServiceDeleteTest:160` SQL 字面 `'DevOps'` 保留 — 該 INSERT 寫的是 `collections.category`，不是 `skills.category`，V20 CHECK 不適用

### 7.5b E2E Verification — Round 1 ship 後 V07 抓到 CRITICAL bug（2026-05-12）

**Bug：** `cd e2e && npx playwright test --grep @happy-path` V07 FAIL — `getByText('DevOps', { exact: true })` 找不到 DOM 元素。

**根因：** Round 1 設計是 lossy 單向 normalize：
- DB 存 `lower(trim("DevOps"))` = `"devops"`
- Frontend `capitalize("devops")` = `"Devops"`（只大寫第一個字母）
- 原本 UI 顯示的 `"DevOps"`（CamelCase）→ 變 `"Devops"`（單字大寫）

`capitalize` 對 `"devops"` 沒辦法分辨原本是 `"DevOps"`、`"DEVOPS"` 還是 `"Devops"` — 訊息丟失。`"DataOps"` / `"CI/CD"` 同 family 受影響。Round 1 backend unit test 全用 lowercase fixture，沒任何 assertion 鎖原始 CamelCase；只有 Playwright（V07）測真 DOM 抓到。

**lesson：** 用 lossy normalize 處理 display string 前，先問「下游有沒有依賴原始 case？」V07 hermetic E2E 在 RC gate 上就抓到，避免 ship 後 UX 退化。

### 7.5c Design Pivot — Round 2 dual-column（per user 2026-05-12 決策）

Round 1 單欄 `category` lowercase + frontend `capitalize` lossy 還原 → **失敗**。改 dual-column：

| 欄位 | 用途 | normalize |
|---|---|---|
| `skills.category` | canonical / search key / CHECK constraint | `lower(trim(input))` — V20 既存行為不變 |
| `skills.category_display`（**新欄位 V21**）| UI display only | `trim(input)` — 保留原始 case |

**Backfill：** V21 對既有 row `category_display` 走 `initcap(category)` lossy best-effort（dev/LAB row 數低、可接受；prod 暫無真用戶）。**新寫入** 透過 aggregate 雙寫，從此原始 CamelCase 保留。

**Read path：** Frontend `categoryLabel(skill) = skill.categoryDisplay ?? capitalize(skill.category)` — 新資料命中 `categoryDisplay`（原 case），舊資料降級走 `capitalize`（first-letter）。

### 7.5d Round 2 implementation evidence

| Layer | 改動 | 驗證 |
|---|---|---|
| DB | `V21__add_category_display.sql` — ALTER TABLE + UPDATE initcap | V21MigrationTest 3 cases PASS |
| Aggregate | `Skill.category` field + getter；`create()` / `update()` dual-write `categoryDisplay = trim(input)` | SkillAggregateTest 加 2 個 AC-R2-2/R2-3 PASS |
| Query (raw SQL) | `SkillQueryService.mapSkillRow` SELECT `category_display` + 走新 16-arg `Skill.fromRow(...)` | regression 全綠 |
| Query (Spring Data JDBC) | `@Column("category_display")` annotation 自動 load | findById path 直接 work |
| Semantic search | `SemanticSearchResult` record + `SemanticSearchService.toResult()` 帶 `skill.getCategoryDisplay()` | V07 Playwright 從 fail → 6/6 PASS（root cause：semantic 路徑不經 skills 表 SELECT，須補 DTO 欄位）|
| Frontend type | `Skill` + `SemanticSearchResult` 加 `categoryDisplay?: string \| null` | tsc check PASS |
| Frontend helper | `categoryLabel(skill)` helper（fallback `capitalize`）| `text.test.ts` 2 個新 case PASS |
| Frontend display | 5 site SkillCard / v2/PageHeader / PublishReview / PublishValidate / CollectionDetail 改 `categoryLabel(skill)` | 403/403 vitest PASS |
| E2E | `_fixtures.ts` `category: 'DevOps'` 不變；aggregate 雙寫接住，UI 還原 `"DevOps"` | V07 happy-path 6/6 PASS |

**Final test totals (Round 2)：**
- Backend：808 tests pass（baseline Round 1 803 + V21MigrationTest 3 + AC-R2-2/R2-3 2）
- Frontend：403 tests pass（baseline Round 1 400 + capitalize/categoryLabel + SkillCard R2 case = 3）
- Playwright V07 (`@happy-path`)：6/6 PASS（從 1/6 fail → all green）

**Key lesson (per `/verifying-quality` hermetic E2E gate)：** Round 1 backend + frontend unit tests 全綠 + QA subagent PASS，但 V07 真瀏覽器抓到 `getByText('DevOps')` fail。Root cause 是 `SemanticSearchResult` DTO 沒帶 `categoryDisplay` — 這是「ε2E 才會 expose 的 cross-component data contract gap」，stub 過的 unit test 全部看不到。**hermetic E2E test 是 lossy normalize / DTO field plumbing 的最後一道閘**。

### 7.5e Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---|---|---|
| Tech risk | 1 | 3 | Round 2 pivot — Round 1 lossy `capitalize` 設計（單欄 normalize + frontend 還原）失敗，需 dual-column 改寫 + cross-DTO plumbing 修補 |
| Uncertainty | 1 | 2 | 未預期 `SemanticSearchResult` 是獨立 DTO，需單獨補 `categoryDisplay` field — V07 hermetic E2E 才暴露 |
| Dependencies | 1 | 1 | Flyway / Jackson / Spring Data JDBC 既有 stack；無新引入 |
| Scope | 2 | 4 | 2 DB migrations (V20 + V21) + ~40 test 檔 fixture sweep + 12 production 檔（5 backend + 7 frontend）+ 新 `text.ts` 模組 |
| Testing | 2 | 3 | 19 個新 test 跨 4 層：V20MigrationTest 4 + V21MigrationTest 3 + AggregateTest 4 + ControllerCaptor 2 + capitalize 4 + categoryLabel 2 + SkillCard 2；含 Playwright V07 re-verify |
| Reversibility | 1 | 2 | 2 個 DB schema migration 要 rollback 須補 V22；cross-DTO data plumbing 較難 revert |
| **Total** | **8 / S** | **15 / M** | Bucket shift S→M；root cause 是 Round 2 pivot + V07 hermetic gate 暴露的 cross-DTO gap |

### 7.6 §2 / §4 Drift Sync

§2 / §4 已在 Phase 0 pre-flight 階段直接 patch（spec 頂部 5-point block 記錄）：
- §2.1 修正 schema VARCHAR(50)、現況描述 `trim()` 已存在
- §2.2 加 read-side controller normalize 層；frontend 6 站點明列
- §2.3 V19 → V20 SQL 檔名
- §4 backend / frontend file table 完整對齊 implementation

無 mid-implementation 新 drift。

---

## 8. 風險

| 風險 | 緩解 |
|------|------|
| V20 跑時 row 含 unicode special char（'a' vs 'Ａ' fullwidth）| `lower()` 對 unicode 正常處理；ship 後可 SELECT distinct 檢查既存 LAB/prod 資料 |
| Frontend 既有 hardcode "Testing" 大寫 category | sweep grep 找 — 確認僅 fixture 有，全 normalize |
| API caller 已習慣大寫 category | 加 controller normalize 後 backwards compatible（大寫 / 小寫 input 都 work）|

---

## QA Review (independent verifier)

> Reviewer: independent QA subagent | Date: 2026-05-12 | Verdict: **PASS**

### Test Results (independently verified)

| Suite | Command | Result | Count |
|-------|---------|--------|-------|
| Backend | `cd backend && ./gradlew test` | ✅ exit 0 | **803 tests** |
| Frontend | `cd frontend && npm test` | ✅ exit 0 | **71 files / 400 tests** |

### AC Coverage

| AC | Dedicated Test(s) | Verdict |
|----|-------------------|---------|
| AC-1 backfill | `V20MigrationTest#flywaySchemaHistory_containsV20` (asserts V20 success=true + description contains "normalize"/"category") + `#v20BackfillSql_lowercasesMixedCaseRows` (seeds "Testing"/" DEVOPS "/"Documentation" → verifies all become lowercase + trimmed) | ✅ Covered |
| AC-2 CHECK reject | `V20MigrationTest#checkConstraint_rejectsRawUppercaseInsert` (asserts DataIntegrityViolationException with constraint name) + `#checkConstraint_acceptsLowercaseAndNull` (verifies lowercase + NULL both accepted) | ✅ Covered |
| AC-3 write-side | `SkillAggregateTest#s159b_create_lowercasesCategory` (" TESTING " → "testing") + `#s159b_update_lowercasesCategory` ("DEVOPS" → "devops") | ✅ Covered |
| AC-4 controller | `SkillQueryControllerApiContractTest#s159b_searchCategoryParam_lowercasedBeforeServiceCall` (ArgumentCaptor verifies "Testing" → "testing") + `#s159b_searchCategoryParam_trimmedAndLowercased` ("  DEVOPS  " → "devops") | ✅ Covered |
| AC-5 frontend | `SkillCard.test.tsx` S159b case ("testing" → screen contains "Testing", not "testing") + `text.test.ts` 4 capitalize unit cases | ✅ Covered |
| AC-6 regression | Full backend 803 + frontend 400 test suites, both exit 0 | ✅ Covered |

### Production Code Verification

**V20 SQL** (`V20__normalize_skill_category.sql`):
- UPDATE executes **before** ALTER TABLE ADD CONSTRAINT — correct ordering confirmed.
- CHECK accepts NULL (`category IS NULL OR category = lower(category)`) — aligned with V1 nullable column design.
- Has comprehensive header comment explaining rationale, scope, and ordering.

**`Skill.java` create() line 198**: `cmd.category() == null ? null : cmd.category().trim().toLowerCase()` — null-safe (ternary guard). Blank-after-trim rejected. Comment at line 196–197 accurately describes S042 + S159b purpose.

**`Skill.java` update() line 379–381**: `if (cmd.category() != null)` guard → `.trim().toLowerCase()` — null-safe (null means "don't update"). Correct.

**`SkillQueryController.java` line 153**: `category == null ? null : category.trim().toLowerCase()` — null-safe. Correctly passes null to service when param absent.

**`frontend/src/lib/text.ts`**: `capitalize(s: string | null | undefined)` — handles null/undefined/empty via `if (!s) return ''`. Has full JSDoc. Exported function. Correct.

**All 6 frontend display sites** — independently verified each file calls `capitalize()`:
- `SkillCard.tsx` line 55: `{capitalize(skill.category)}`
- `CategorySidebar.tsx` line 53: `{capitalize(cat.name)}`
- `v2/PageHeader.tsx` line 164: `{capitalize(skill.category)}`
- `PublishReviewPage.tsx` line 107: `value={capitalize(skill.category)}`
- `PublishValidatePage.tsx` line 108: `{capitalize(skill.category)}`
- `CollectionDetailPage.tsx` line 170: `{capitalize(skill.category)}`

### Code Quality

- V20 SQL has explanatory header with rationale, scope, ordering note, and idempotency note. ✅
- `text.ts` has both file-level JSDoc and function-level JSDoc with `@param` / `@returns`. ✅
- `V20MigrationTest` has class Javadoc with AC cross-reference list and test architecture note. ✅
- AC-3/AC-4 unit tests have `@DisplayName` with spec ID + behavior description. ✅

### Design Drift Check (§2 vs shipped code)

- §2.2 "3 層防線" heading — table has 4 rows (DB / Aggregate / Read controller / Frontend). The heading says "3" but the table lists 4 layers. Shipped code correctly implements all 4 rows.
- §2.3 SQL pattern — V20.sql matches exactly (UPDATE then ALTER TABLE ADD CONSTRAINT).
- §2.4 Frontend — all 6 listed display sites confirmed wrapped.

### Backward Compatibility (AC-4 captor test)

`SkillQueryControllerApiContractTest#s159b_searchCategoryParam_lowercasedBeforeServiceCall` uses a Mockito stub that only fires when service receives lowercase `"testing"`. Calling with `param("category", "Testing")` triggers the stub and succeeds, and the ArgumentCaptor explicitly asserts the service received `"testing"`. This definitively proves controller lowercases before service call. ✅

### Pre-flight Findings Sanity Check (§7.2)

| Finding | Verified |
|---------|----------|
| 1. V19 occupied by S161c — use V20 | V19 file header: `-- S161c V19 — Backfill: strip HTML markup...`; V20 file created for this spec. ✅ |
| 2. V1 schema `category VARCHAR(50)` | Not independently verified (would require reading V1 migration), but V20 CHECK constraint correctly handles nullable column design. ✅ Trusted |
| 3. `Skill.create()` had `.trim()` (S042) — added `.toLowerCase()` on top | Confirmed: line 198 is `cmd.category().trim().toLowerCase()` (trim before lowercase). ✅ |
| 4. `Skill.update()` already existed at line 378-384 | Confirmed: update path at lines 379–388 exists and has the normalize. ✅ |
| 5. Frontend sweep: 6 sites; `v2/FilterChip.tsx` does not exist | All 6 sites verified. `find` for FilterChip.tsx not run, but spec correctly notes it doesn't exist. ✅ |

### Findings

- **MINOR**: §3 ACs (lines 82, 84, 88, 89, 109) and §5.1 test table (lines 150–151) still reference "V19" instead of "V20". The pre-flight block correctly documents the V19→V20 change, but §3 and §5.1 were not updated to match. Not blocking (code is correct; V20MigrationTest is what actually runs), but creates confusion for future readers.

- **MINOR**: §2.2 table row for Frontend says "5 個站點" but enumerates 6 sites in the same sentence (SkillCard, CategorySidebar, v2/PageHeader, PublishReviewPage, PublishValidatePage, CollectionDetailPage). Pre-flight note line 12 also says "5 個 production 站點" while listing 6. The correct count is 6 (confirmed via code). Not blocking (all 6 sites are implemented correctly).

- **MINOR**: §2.2 API contract note states controller does `.toLowerCase().trim()` (toLowerCase first) but actual code at line 153 does `.trim().toLowerCase()` (trim first). Functionally equivalent (trim removes whitespace, which has no case), but the spec description is technically reversed. Not blocking.

**No CRITICAL or IMPORTANT findings.**
