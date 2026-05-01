# S031: Public PUBLISHED-Only Visibility（list / categories / analytics 過濾 status）

> Spec: S031 | Size: XS(5) | Status: ✅ Done — target ship `v2.8.0`
> Date: 2026-05-01
> Depends: S018 ✅ + S028 ✅ + S029 ✅
> Trigger: 2026-05-01 /loop tick 6 — `GET /api/v1/skills?keyword=...` / `GET /api/v1/categories` / `GET /api/v1/analytics/overview` 都把 SUSPENDED 與 DRAFT skills 計入；違反 PRD 公開瀏覽端只看 PUBLISHED 的設計（per S028 §7.5 tech debt）

---

## 1. Goal

公開查詢 endpoint 的 SQL 加 `WHERE status = 'PUBLISHED'`：
1. `SkillQueryService.search`（list / keyword / category 搜尋）
2. `SkillQueryService.getCategoryCounts`（categories endpoint）
3. `AnalyticsService.getOverview` 的 `totalSkills` + `newSkillsThisWeek`
4. `AnalyticsService.getTopSkills`

`findById`（detail page）保留全 status 可見 — admin / 擁有者 需直接 link 看 SUSPENDED 才能 reactivate；DRAFT 也是擁有者 self-service 看到自己未發版的 skill。

---

## 2. Approach

### 2.1 SQL filter 加入

統一加 `WHERE status = 'PUBLISHED'`（既有 WHERE 1=1 的位置或加新 WHERE）：

```diff
 // SkillQueryService.search
 var sql = new StringBuilder("""
         SELECT id, name, description, author, category,
                latest_version, risk_level, status, download_count,
                created_at, updated_at, acl_entries, version
           FROM skills
-         WHERE 1=1
+         WHERE status = 'PUBLISHED'
         """);

 // SkillQueryService.getCategoryCounts
 SELECT category AS name, COUNT(*) AS count
   FROM skills
- WHERE category IS NOT NULL
+ WHERE category IS NOT NULL AND status = 'PUBLISHED'
  GROUP BY category ORDER BY count DESC

 // AnalyticsService.getOverview totalSkills
- "SELECT COUNT(*) FROM skills"
+ "SELECT COUNT(*) FROM skills WHERE status = 'PUBLISHED'"

 // AnalyticsService.getOverview newSkillsThisWeek
- "SELECT COUNT(*) FROM skills WHERE created_at >= :since"
+ "SELECT COUNT(*) FROM skills WHERE created_at >= :since AND status = 'PUBLISHED'"

 // AnalyticsService.getTopSkills
 SELECT name, download_count
   FROM skills
+ WHERE status = 'PUBLISHED'
  ORDER BY download_count DESC LIMIT :limit
```

### 2.2 為何 NOT 過濾 findById

`findById`（GET `/api/v1/skills/{id}`）保留全狀態可見：
- skill detail page 是 admin 唯一能看 SUSPENDED 詳情、做 reactivate 決定的地方
- 擁有者也需要看自己 DRAFT 的 metadata
- frontend 既有 `STATUS_LABEL` + Badge variant（per S028）已能正確渲染三種狀態
- 不破 frontend `SkillDetailPage` 既有行為

### 2.3 為何 NOT 影響 vector_store / semantic search

`SearchProjection.onVersionPublished` 已 maintain vector_store row。SUSPENDED skills 的 vector 是否清除是另一議題：
- semantic search 經 ACL filter（vector_store.acl_entries）已守，但 status 沒走 ACL
- 若 SUSPENDED skill 的 vector 留著，semantic search 仍可能 hit
- 修 vector cleanup 屬 SearchProjection 加 `onSkillSuspended` listener 範疇 — 留 future spec（S032 SearchProjection status sync）

本 spec 暫不處理 semantic search；scope 鎖在 SQL list endpoints。

### 2.4 為何 NOT 加 query param `?includeAll=true` 之類後門

未來 admin panel（S032+）會有獨立 `/api/v1/admin/skills` endpoint with `@PreAuthorize("hasRole('admin')")` 看全狀態；不在公開 endpoint 加 query param 後門（避免 admin 路徑 leak 到公開 controller）。

---

## 3. SBE Acceptance Criteria

### AC-1: list 過濾 SUSPENDED + DRAFT

```gherkin
Given 17 個 skills（13 PUBLISHED + 2 SUSPENDED + 2 DRAFT）
When  GET /api/v1/skills
Then  totalElements = 13
And   content 不含 status="SUSPENDED" 或 status="DRAFT" 的 skill
```

### AC-2: keyword 搜尋過濾 SUSPENDED

```gherkin
Given alice 上傳 skill A，suspend 之
When  GET /api/v1/skills?keyword=<A's name>
Then  totalElements = 0（SUSPENDED 不該命中）
```

### AC-3: categories 過濾

```gherkin
Given DevOps category：13 PUBLISHED + 1 SUSPENDED + 0 DRAFT；隨機 category：1 DRAFT
When  GET /api/v1/categories
Then  DevOps count = 13（不算 SUSPENDED）
And   隨機 category 不出現（只 DRAFT，count=0）
```

### AC-4: analytics totalSkills 過濾

```gherkin
Given 13 PUBLISHED + 2 SUSPENDED + 2 DRAFT
When  GET /api/v1/analytics/overview
Then  totalSkills = 13
And   topSkills 只含 PUBLISHED skill name
```

### AC-5: detail page 仍可看 SUSPENDED / DRAFT

```gherkin
Given alice suspend 了 skill A
When  GET /api/v1/skills/{A}
Then  HTTP 200
And   response body status = "SUSPENDED"
```

### AC-6: 既有 unit test 不破

```gherkin
Given S031 改動完成
When  ./gradlew test
Then  既有 292 tests 全 PASS（既有 test fixtures 多用 PUBLISHED status；過濾規則不破）
```

---

## 4. Interface

詳 §2.1 SQL diffs。Java code 改動範圍：
- `SkillQueryService` line 110 之後 SQL builder
- `SkillQueryService.getCategoryCounts` SQL string
- `AnalyticsService.getOverview` 兩處 COUNT
- `AnalyticsService.getTopSkills` SQL string

---

## 5. File Plan

### 5.1 Production (2 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`（search SQL + categoryCounts SQL）
- `backend/src/main/java/io/github/samzhu/skillshub/analytics/AnalyticsService.java`（totalSkills / newSkillsThisWeek / topSkills SQL）

### 5.2 Test
- 既有 `SkillSearchTest`（demoted to REPO slice, S025b T04）seeds 多筆 skill 以 DRAFT；本 spec 後 search 改只回 PUBLISHED → 既有 test 預期 fail，需更新 fixture 為 PUBLISHED 或補 seed 後 publish
- E2E HTTP retest 涵蓋 AC-1~5

### 5.3 Docs
- CHANGELOG `v2.8.0` entry
- spec-roadmap M27

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SQL filter（5 處）+ 修 `SkillSearchTest` fixture（如有 break）+ E2E retest 全 6 AC | AC-1~6 | 🔲 |

POC: not required（純 SQL filter；無新 dep；既有 status 欄位可用）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.8.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 28s（既有 292 tests + 修 fixture 後全綠）；E2E HTTP 6 個 AC 全綠：list 17→13、keyword search 過濾 SUSPENDED、categories 16→13、analytics totalSkills 17→13 + topSkills 排除 SUSPENDED、detail 仍可看 SUSPENDED。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 28s；292 tests / 0 fail；`SkillSearchTest` fixture 從 `DRAFT` 改 `PUBLISHED`（per S031 過濾規則） |
| Status histogram | DRAFT 2 / SUSPENDED 2 / PUBLISHED 13 |
| `GET /skills?size=50` | totalElements **13**（baseline 17）✓ AC-1；distinct statuses 集合 {`PUBLISHED`} |
| `GET /skills?keyword=<SUSPENDED 名>` | totalElements **0**（baseline 1）✓ AC-2 |
| `GET /categories` | `[{"name":"DevOps","count":13}]`（baseline 16；隨機 category 因 1 DRAFT 也消失）✓ AC-3 |
| `GET /analytics/overview` | totalSkills **13**（baseline 17）；newSkillsThisWeek **13**；topSkills 不含 `suspend-download-test` / `draft-skill-tick5` ✓ AC-4 |
| `GET /skills/{SUSPENDED_ID}` | status="SUSPENDED" ✓ AC-5（admin / owner 仍可看） |

### 7.2 Files Changed

#### Production (2 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`：
  - `search()` — `WHERE 1=1` → `WHERE status = 'PUBLISHED'`（含 countSql）
  - `getCategoryCounts()` — 加 `AND status = 'PUBLISHED'`
- `backend/src/main/java/io/github/samzhu/skillshub/analytics/AnalyticsService.java`：
  - `getOverview()` totalSkills + newSkillsThisWeek 兩 SQL 加 `WHERE status = 'PUBLISHED'`
  - `getTopSkills()` 加 `WHERE status = 'PUBLISHED'`

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillSearchTest.java`：3 個 fixture skill 從 status `"DRAFT"` 改 `"PUBLISHED"`，latestVersion 從 null 改 `"1.0.0"`（語意對齊 — fixture 既要被 search 命中即必為 PUBLISHED）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: list 過濾 SUSPENDED + DRAFT | ✅ PASS | totalElements 17→13；distinct status 只剩 PUBLISHED |
| AC-2: keyword 搜尋過濾 SUSPENDED | ✅ PASS | tick6-suspend 名 search 從 1 → 0 |
| AC-3: categories 過濾 | ✅ PASS | DevOps 16→13；含 DRAFT 的隨機 category 消失 |
| AC-4: analytics totalSkills + topSkills 過濾 | ✅ PASS | 17→13；topSkills 不含 SUSPENDED 與 DRAFT |
| AC-5: detail page 仍可看 SUSPENDED / DRAFT | ✅ PASS | `GET /skills/{ID}` 直接 ID lookup 不受過濾影響 |
| AC-6: 既有 unit test 不破 | ✅ PASS | 292 tests 全綠（修 fixture 1 處）|

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 6 — list/keyword/categories/analytics 都把 SUSPENDED 與 DRAFT skills 計入；S028 §7.5 已登記為 tech debt，本 spec 落地。

**Fix design rationale**:
- 5 處 SQL 加 `WHERE status = 'PUBLISHED'` — 統一公開查詢的可見性 invariant
- `findById` 不過濾 — admin / owner 直接 link 看 SUSPENDED 詳情、做 reactivate 決定的唯一路徑；frontend `STATUS_LABEL` + Badge variant（per S028）已渲染三種狀態
- 不在公開 endpoint 加 `?includeAll=true` query param 後門 — 未來 admin panel 將為獨立 endpoint with `@PreAuthorize("hasRole('admin')")`，避免後門 leak
- 不影響 vector_store / semantic search — 留 future spec（S032 SearchProjection status sync）處理 SUSPENDED skill 的 vector cleanup

### 7.5 Pending Verification / Tech Debt

**Tech debt — semantic search vector cleanup on suspend**：當前 SUSPENDED skill 的 vector_store row 仍在；ACL filter 不知 status；semantic search 仍可能 hit SUSPENDED skill 的 row。修法：`SearchProjection` 加 `onSkillSuspended` listener 刪 vector，`onSkillReactivated` listener 重新 embed；屬另一範疇。

**Tech debt — admin panel endpoint**：未來需 `/api/v1/admin/skills` with full status visibility 給 admin 做 reactivate / 監控；屬 S032+ 設計。
