# S185 — Skill List / Detail Projection Consistency

> SpecID: S185
> Status: 📐 in-design
> Date: 2026-05-16
> Size: XS(8)
> Related: S119 list rating projection, S142b SkillDetailPage v2 backend supplement, S177 is_public-first search visibility, S184 visibility command contract

---

## 1. Goal

`GET /api/v1/skills?page=0&size=10` 和 `GET /api/v1/skills/{id}` 回同一個 skill 時，公開狀態與版本欄位要一致，讓瀏覽卡片、分類側欄、詳情頁都讀同一個事實。

Production Round 69 在 `skillshub-00032-9v8` 查到同一筆 skill：

| Endpoint | `visibility` | `verified` | `latestVersionPublishedAt` | `versionCount` |
| --- | --- | ---: | --- | ---: |
| `GET /api/v1/skills?page=0&size=10` | `PRIVATE` | `false` | `null` | `0` |
| `GET /api/v1/skills/8ee45695-c16e-4586-9869-9fdbe110ca88` | `PUBLIC` | `true` | `2026-05-15T21:06:42.704893Z` | `1` |

兩個 request 都是 HTTP 200，Cloud Run 同時段 `severity>=ERROR` 是 0 rows。這不是平台錯誤；是 list endpoint 的 raw JDBC projection 沒有帶齊 detail/source-of-truth 欄位。

本 spec 只修 read-side API 欄位一致性，不改 publish、scan、visibility command、ACL 權限規則，也不改前端畫面 layout。

## 2. Research And Design

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
| --- | --- | --- |
| Production curl `2026-05-16T04:02:27Z` | list trace `67c9a15e464ce35b012f5747c89ad937` 回 `PRIVATE/false/null/0`；detail trace `c8118bb60fb90933c96617e9a1ae0d2f` 回 `PUBLIC/true/publishedAt/versionCount=1`。 | AC 要直接比對同 id 的 list/detail 欄位。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java:223-229` | list SQL SELECT 只有 `latest_version/risk_level/status/.../average_rating/review_count`，沒有 `is_public`，也沒有 latest `skill_versions.published_at` / version count / flags count。 | list path 無法產生 S184 `visibility` 與 S142b detail enrichment 欄位。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java:313-338` | `Skill.fromRow(...)` raw JDBC factory 最後固定 `skill.publicSkill = false`。 | 任何 list row 只要走 `fromRow` 都會序列化成 `visibility:"PRIVATE"`，即使 DB `skills.is_public=true`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java:125-141` | detail path `enrichDetail()` 會讀 `skill_versions`、算 `verified/versionCount/latestVersionPublishedAt/openFlagCount`，再加 `viewerPermissions`。 | list path 不該加 detail-only `viewerPermissions`，但 JSON 已有的 S142b fields 要對齊 detail。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java:453-464` | `/categories` 只用 `status='PUBLISHED'`，沒有套 `is_public OR acl_entries ??| :aclPatterns`。 | 匿名分類側欄可能顯示 private-only category；Round 69 的 `video` count 就是同類症狀。 |
| `docs/grimo/specs/archive/2026-05-04-S119-list-rating-projection.md` | S119 已用 backward-compatible `fromRow` overload + SQL SELECT 補欄位修過 list/detail rating 不一致。 | S185 沿用同一個低風險 pattern：擴充 row factory，不做全 callsite migration。 |
| `docs/grimo/specs/archive/2026-05-15-S177-is-public-first-search-visibility.md` | public visibility source-of-truth 是 `skills.is_public`；ACL 只保存 explicit grants。 | list SQL 必須 SELECT `is_public`，不可以從 public grant 或 ACL 字串推回公開狀態。 |
| `docs/grimo/specs/archive/2026-05-16-S184-api-empty-response-contract.md` | Skill detail JSON expose `visibility`，PageHeader visibility button 讀 `skill.visibility`。 | list JSON 也應由同一欄 `skills.is_public` 產生 `visibility`，避免 browse/card 和 detail 說法不同。 |
| Spring Framework `RowMapper` docs | `RowMapper` 的工作是把 `ResultSet` 當前 row 映射成一個 result object。Source: https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html | raw JDBC list path 要自己把每個需要的欄位 SELECT + map 出來；不會自動得到 Spring Data repository `findById` 的 enrichment。 |
| Spring Data JDBC docs | `@Transient` 欄位不會被 persistence framework 寫入或讀取。Source: https://docs.spring.io/spring-data/relational/reference/jdbc/mapping.html | `verified/latestVersionPublishedAt/versionCount/openFlagCount` 是 transient API 欄位，list path 必須像 detail path 一樣明確填值。 |

Spec overlap scan:

| Active spec | 是否重疊 | 判斷 |
| --- | --- | --- |
| S162b | no | 只處理 401/403 ErrorResponse shape，不改 list/detail projection。 |
| S178 | no | 改 browse/search route 與 frontend request routing，不改 backend list response 欄位。 |
| S179 | no | 只改 publish page 作者欄位文案。 |
| S180 | no | 修 `Skill.publicSkill` native readback；目前 blocked 在 Chrome UI 覆測，不改 `SkillQueryService.search()` projection。 |

### 2.2 Root Cause Hypothesis

```text
GET /api/v1/skills
  → SkillQueryService.search()
  → raw SQL SELECT 沒有 is_public / version aggregates
  → mapSkillRow() 呼叫 Skill.fromRow(...)
  → Skill.fromRow() 固定 publicSkill=false
  → JSON List view 輸出 visibility=PRIVATE、verified=false、versionCount=0

GET /api/v1/skills/{id}
  → SkillRepository.findById(id)
  → Spring Data JDBC 讀 skills.is_public=true
  → enrichDetail() 讀 skill_versions
  → JSON 輸出 visibility=PUBLIC、verified=true、versionCount=1
```

`GET /api/v1/categories` 也有第二個 projection gap：

```text
GET /api/v1/categories
  → WHERE category IS NOT NULL AND status='PUBLISHED'
  → 沒有套 search() 同款 visibility ACL clause
  → anonymous sidebar 可能顯示看不到的 private category count
```

### 2.3 Approach Comparison

| Approach | 改哪個 file / line | 跑出實際行為 | 成本 |
| --- | --- | --- | --- |
| A. list 每筆都呼叫 `enrichDetail()` | `SkillQueryService.search()` 在 stream 裡做 detail enrichment | list/detail 欄位會一致，但每頁最多 100 筆會跑 N 次 `skill_versions` query、N 次 `flags` count、N 次 permission 計算。 | 快，但把 detail-only 成本搬到 browse hot path。 |
| B. raw SQL 一次 SELECT list 需要的 source 欄位，再用 row mapper 填 transient fields（recommended） | `SkillQueryService.search()` SELECT 加 `is_public` + latest version / count / open flag aggregate；`Skill.fromRow` 加 overload；`mapSkillRow()` 填 `withDetail(...)` | `/skills` 一次 query 回 `visibility/verified/latestVersionPublishedAt/versionCount/openFlagCount/license/compatibility`；不輸出 ownerId/viewerPermissions。 | XS；沿用 S119 pattern，測試集中在 query slice。 |
| C. 新增 `SkillSummaryResponse` DTO | controller/service/frontend type 全改 summary/detail DTO | API type 更乾淨，但要改 frontend `Skill` 共用型別、CollectionSkillSummary 呼叫端、Mock fixtures。 | 超出這次 production bug 修復範圍。 |
| D. 只修 `visibility`，不修 version fields | SELECT `is_public` + fromRow overload | browse 不再顯 PRIVATE，但 `verified/versionCount/latestVersionPublishedAt` 仍和 detail 矛盾。 | 太窄，留下 Round 69 已看到的另一半 bug。 |

Chosen approach: B。

### 2.4 Designed Query Contract

List endpoint 的 JSON 不新增新概念，只把既有 `Skill` response fields 填成正確值：

| JSON field | List source | Detail source | 備註 |
| --- | --- | --- | --- |
| `visibility` | `skills.is_public` | `Skill.publicSkill` from Spring Data JDBC | S177/S184 source-of-truth。 |
| `verified` | `skills.status='PUBLISHED' AND skills.risk_level IS NOT NULL` | 同邏輯 in `enrichDetail()` | S142b derived field。 |
| `latestVersionPublishedAt` | latest `skill_versions.published_at` by `skill_id, published_at DESC LIMIT 1` | `skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(...).getFirst()` | DB 已有 `idx_skill_versions_skill_published`。 |
| `license` | latest `skill_versions.frontmatter->>'license'` 或 row mapper parse frontmatter | `extractLicense(latest.frontmatter)` | 沒填就 `null`。 |
| `compatibility` | latest `skill_versions.frontmatter->'compatibility'` 或 row mapper parse frontmatter | `extractCompatibility(latest.frontmatter)` | 沒填就 `[]`。 |
| `versionCount` | `COUNT(*) FROM skill_versions WHERE skill_id=s.id` | `versions.size()` | Count 不只 latest version。 |
| `openFlagCount` | `COUNT(*) FROM flags WHERE skill_id=s.id AND status='OPEN'` | current `flags` count query | List JSON already contains this field by default-view inclusion。 |
| `viewerPermissions` | not populated / excluded by `@JsonView(Views.List)` | `viewerPermissionService.viewerPermissions(skill)` | Detail-only action contract，不放 list。 |
| `ownerId` | excluded by `@JsonView(Views.List)` | Spring Data JDBC field + Detail view | S158 privacy contract 不變。 |

Implementation hint:

```sql
SELECT s.id, s.name, s.description, s.author, s.category, s.category_display,
       s.latest_version, s.risk_level, s.status, s.download_count,
       s.created_at, s.updated_at, s.acl_entries, s.version,
       s.average_rating, s.review_count, s.is_public,
       lv.published_at AS latest_version_published_at,
       lv.frontmatter AS latest_version_frontmatter,
       vc.version_count,
       fc.open_flag_count
  FROM skills s
  LEFT JOIN LATERAL (
      SELECT published_at, frontmatter
        FROM skill_versions
       WHERE skill_id = s.id
       ORDER BY published_at DESC
       LIMIT 1
  ) lv ON TRUE
  LEFT JOIN LATERAL (
      SELECT COUNT(*) AS version_count
        FROM skill_versions
       WHERE skill_id = s.id
  ) vc ON TRUE
  LEFT JOIN LATERAL (
      SELECT COUNT(*) AS open_flag_count
        FROM flags
       WHERE skill_id = s.id AND status = 'OPEN'
  ) fc ON TRUE
 WHERE s.status = 'PUBLISHED'
   AND (s.is_public = TRUE OR s.acl_entries ??| :aclPatterns)
```

`author` mode remains an owner/admin-oriented existing path. If `author` is supplied, status filtering stays as-is per S094a, but row mapping must still fill `is_public` and S142b fields from the same SELECT.

### 2.5 Category Count Contract

`GET /api/v1/categories` should count categories from the same visible set as `GET /api/v1/skills` for the current principal.

| Caller | Expected count source |
| --- | --- |
| anonymous | `status='PUBLISHED' AND is_public=TRUE` |
| authenticated user | `status='PUBLISHED' AND (is_public=TRUE OR acl_entries ??| :readPatterns)` |

This keeps sidebar counts aligned with the list the user can actually open. It does not add an `author` parameter to categories.

### 2.6 Confidence Classification

| Design decision | Confidence | Reason |
| --- | --- | --- |
| `visibility` should come from `skills.is_public` in list rows | Validated | S177/S184 already shipped this as source-of-truth; production mismatch maps exactly to `fromRow()` hardcoding false. |
| List S142b fields can be computed in SQL with lateral subqueries | Validated | `skill_versions(skill_id, published_at DESC)` index exists; S142b already computes same fields from repository data. |
| `Skill.fromRow` overload is the right compatibility pattern | Validated | S119 shipped the same pattern for list rating projection without migrating all fixture callsites. |
| Category counts must use same visibility clause as search | Validated | Round 69 saw `/categories` return `video` while `/skills` only returned `testing`; current SQL lacks the clause. |
| POC requirement | not required | No new framework or SDK; implementation uses existing Spring JDBC + existing repository slice tests. |

### 2.7 Size Re-score

| Dimension | Score | Rationale |
| --- | ---: | --- |
| Tech risk | 1 | Existing `NamedParameterJdbcTemplate`, row mapper, and S119 overload pattern. |
| Uncertainty | 1 | Production response shows exact mismatch and code explains it. |
| Dependencies | 2 | Depends on shipped S142b/S177/S184 semantics. |
| Scope | 1 | One backend service + domain factory overload + tests. |
| Testing | 2 | Needs repository slice/API contract tests with Testcontainers. |
| Reversibility | 1 | Internal read mapping only; no schema/API field additions. |
| **Total** | **8 / XS** | Original roadmap XS(5) was too low because category count and S142b field parity both need tests. |

## 3. Acceptance Criteria

Verification command:

Run: `cd backend && ./gradlew test`

Pass: all tests carrying `AC-S185-*` ids are green.

| AC | Priority | Verification | Title |
| --- | --- | --- | --- |
| AC-S185-1 | must | Test | list row visibility comes from `skills.is_public` |
| AC-S185-2 | must | Test | list row S142b fields match detail source fields |
| AC-S185-3 | must | Test | category counts use same visibility filter as skill list |
| AC-S185-4 | must | Test | list JSON keeps privacy contract while exposing filled list fields |
| AC-S185-5 | should | Demo / log | production recheck shows list/detail same id no longer disagree |

### AC-S185-1 — list row visibility comes from `skills.is_public`

Given（前提）DB 有一筆 `skills.id='skill-public'`，`status='PUBLISHED'`，`is_public=TRUE`，`acl_entries` 只有 owner explicit permissions，沒有 `public:*:read`

When（動作）anonymous 呼叫 `GET /api/v1/skills?page=0&size=10`

Then（結果）response `content[0].id` 是 `skill-public`

And（而且）`content[0].visibility == "PUBLIC"`

And（而且）list row 不可因 `Skill.fromRow()` default 變成 `PRIVATE`

### AC-S185-2 — list row S142b fields match detail source fields

Given（前提）同一筆 public skill 有 1 筆 `skill_versions` row，`published_at='2026-05-15T21:06:42Z'`，`frontmatter={"license":"MIT","compatibility":["codex"]}`，且 `risk_level='LOW'`

When（動作）anonymous 呼叫 `GET /api/v1/skills?page=0&size=10`

Then（結果）該 list row 回：

```json
{
  "verified": true,
  "latestVersionPublishedAt": "2026-05-15T21:06:42Z",
  "license": "MIT",
  "compatibility": ["codex"],
  "versionCount": 1
}
```

And（而且）同 id 的 `GET /api/v1/skills/{id}` 回相同 `visibility/verified/latestVersionPublishedAt/versionCount/license/compatibility`

### AC-S185-3 — category counts use same visibility filter as skill list

Given（前提）DB 有兩筆 PUBLISHED skills：`testing-public` 是 `is_public=TRUE, category='testing'`；`video-private` 是 `is_public=FALSE, category='video'`，anonymous 沒有 ACL grant

When（動作）anonymous 呼叫 `GET /api/v1/categories`

Then（結果）response contains `{"name":"testing","count":1}`

And（而且）response 不包含 `{"name":"video","count":1}`

### AC-S185-4 — list JSON keeps privacy contract while exposing filled list fields

Given（前提）`SkillQueryController.search()` 回一筆 S185 fixture skill

When（動作）測試用 MockMvc 呼叫 `GET /api/v1/skills`

Then（結果）response body 包含 `content[0].visibility`、`content[0].verified`、`content[0].latestVersionPublishedAt`、`content[0].versionCount`

And（而且）response body 不包含 `content[0].ownerId`

And（而且）response body 不包含 `content[0].aclEntries`

And（而且）response body 不包含 `content[0].viewerPermissions`

### AC-S185-5 — production recheck shows list/detail same id no longer disagree

Given（前提）S185 deployed to Cloud Run

When（動作）run:

```bash
curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/skills?page=0&size=10' \
  | jq '.content[] | select(.id=="8ee45695-c16e-4586-9869-9fdbe110ca88") | {id,visibility,verified,latestVersionPublishedAt,versionCount}'

curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/8ee45695-c16e-4586-9869-9fdbe110ca88' \
  | jq '{id,visibility,verified,latestVersionPublishedAt,versionCount}'
```

Then（結果）兩個 JSON object 的 `visibility/verified/latestVersionPublishedAt/versionCount` 相同

And（而且）Cloud Run latest revision `severity>=ERROR` query returns 0 rows for the recheck window

### NFR Coverage

| Category | Coverage | Reason |
| --- | --- | --- |
| Performance | AC-S185-2 | List query must compute version/flag fields in the same SQL request, not by N extra repository calls per row. |
| Security | AC-S185-3, AC-S185-4 | Anonymous categories cannot count private-only rows; list JSON still hides `ownerId/aclEntries/viewerPermissions`. |
| Reliability | AC-S185-1, AC-S185-2, AC-S185-5 | Same skill id must not show conflicting state across list/detail in production. |
| Usability | AC-S185-1, AC-S185-3 | Browse cards and category sidebar must describe what the user can actually open. |
| Maintainability | AC-S185-4 | MockMvc JSON contract test guards S158/S165/S184 interaction when future fields are added. |

## 4. Interface And API Design

No public endpoint path changes.

### 4.1 Backend helper shape

`Skill.fromRow(...)` gains a backward-compatible overload rather than forcing all existing fixtures to migrate:

```java
public static Skill fromRow(
        String id,
        String name,
        String description,
        String author,
        String category,
        String categoryDisplay,
        String latestVersion,
        String riskLevel,
        String status,
        long downloadCount,
        Instant createdAt,
        Instant updatedAt,
        List<String> aclEntries,
        Boolean publicSkill,
        Long version,
        double averageRating,
        long reviewCount)
```

Existing 13/15/16-arg overloads delegate with `publicSkill=false` to keep old tests explicit. `SkillQueryService.mapSkillRow()` is the new caller that passes `rs.getBoolean("is_public")`.

`SkillQueryService.mapSkillRow()` then fills list-safe S142b fields:

```java
var skill = Skill.fromRow(..., rs.getBoolean("is_public"), ...);
return skill.withDetail(
    skill.getStatus() == SkillStatus.PUBLISHED && skill.getRiskLevel() != null,
    latestVersionPublishedAt,
    license,
    compatibility,
    versionCount,
    openFlagCount);
```

It must not call `withViewerPermissions(...)` in list mode.

### 4.2 Category count filter

`getCategoryCounts()` uses the same current-principal visibility clause as `search()`:

```sql
WHERE category IS NOT NULL
  AND status = 'PUBLISHED'
  AND (is_public = TRUE OR acl_entries ??| :aclPatterns)
```

The method already has access to `principalContextService`, so it does not need a new controller parameter.

## 5. File Plan

| File | Action | Description |
| --- | --- | --- |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | Add `fromRow` overload accepting `Boolean publicSkill`; keep existing overloads as compatibility delegates. |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | SELECT/map `is_public`; compute list-safe S142b fields from `skill_versions` / `flags`; apply visibility ACL filter to `getCategoryCounts()`. |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryServiceVisibilityTest.java` | modify | Add AC-S185-1/2/3 repository slice tests for list/detail parity and category visibility counts. |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java` | modify | Add AC-S185-4 MockMvc JSON contract assertion: list exposes filled safe fields and hides privacy fields. |
| `docs/grimo/specs/spec-roadmap.md` | modify | Mark S185 as `📐 in-design` and update estimate to XS(8). |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
