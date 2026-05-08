# S159: Skill Query API Hardening — Category Normalization + Tag Filter + 拒收未知 param

> Spec: S159 | Size: S(6) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— `GET /api/v1/skills?category=...` 大小寫敏感（`security` 0 / `Security` 1），且 SKILL.md 有 `tags:` frontmatter 但 `?tag=` query 被 backend 靜默忽略 → 回全部 skills，使用者以為 filter 有作用其實沒。

---

## 1. Goal

讓 skill query API 的 filter 行為符合 user 直覺與 REST best practice：

1. **Category filter case-insensitive**（`security` / `Security` / `SECURITY` 視為同一類）+ 同步 normalize storage
2. **實作 `?tag=` filter**（既然 SKILL.md 有 `tags:` frontmatter 且 frontend 顯示 tag，filter 也要 work）
3. **拒收未知 query param** 回 400（避免 typo silent fall-through）

**為什麼重要：**
- Filter 不一致會讓使用者 train 出「平台搜不到」的負面印象
- `?tag=` 靜默忽略是 silent failure 反模式 — user 明明指定了 filter，平台默默 ignore
- Category mixed case 在 frontend `/browse` sidebar 顯示 `research` 小寫 / `Security` 大寫 / `Trading` 大寫 — 視覺也不齊

**非目標：**
- 不改 sort 行為
- 不改 keyword search 邏輯
- 不動 `?author=` 既有 work 的 filter

---

## 2. Approach

### 2.1 Category Normalization

**現況**：
- `skills.category` 存原始 SKILL.md 值（`research` / `Security` / `Trading`）
- `?category=Security` → exact match → 1 hit
- `?category=security` → exact match → 0 hit

**選項：**

| 方案 | 動作 | Pros | Cons |
|------|------|------|------|
| A. 改 query case-insensitive | SQL `LOWER(category) = LOWER(:input)` | 不動既有資料 | 仍可能在 list 顯示 mixed case（視覺不齊）|
| B. 改 storage 全 lowercase | migration backfill `category = lower(category)`；publish 時 `lower()` | 視覺一致；query 自動 case-insensitive（因為都 lower）| migration 動既有資料；title-case 顯示需 frontend 處理 |
| C. 改 storage Title Case | migration backfill；publish 時 `titleCase()` | 視覺漂亮 | title-case 邏輯複雜（多字 vs 連字號）|

**選 B**：最徹底；frontend 顯示 title-case 由 view 層處理（CSS `text-transform: capitalize` 即可）。

Migration `V19__normalize_skill_category.sql`：
```sql
-- 全 lowercase normalize
UPDATE skills SET category = LOWER(category) WHERE category != LOWER(category);

-- 加 check constraint 確保未來 insert 也必 lowercase
ALTER TABLE skills ADD CONSTRAINT skills_category_lowercase
  CHECK (category = LOWER(category));
```

Publish flow：`SkillCommandService.publish()` 在解析 SKILL.md frontmatter 後 `category.toLowerCase()` 再寫 DB。

Frontend 顯示：
```tsx
<span className="capitalize">{skill.category}</span>  // 「security」 → 「Security」
```

`/categories` API response（既有 `[{name, count}]`）也回 lowercase；frontend 同樣 `capitalize` 顯示。

### 2.2 Tag Filter 實作

**現況**：SKILL.md frontmatter 解析後存 `skills.tags`（看起來是 `text[]` 或 JSONB），frontend 在 SkillCard 顯示 tag chips，但 `GET /api/v1/skills?tag=xxx` 完全沒 filter — backend 連 param 都沒接。

**驗證假設先**：
- 看 `Skill` entity / `SkillReadModel` 是否真有 `tags` 欄位
- 若沒，本 spec 範圍擴大為「含 storage 加欄位」
- 若有但 query 沒接，純 backend 加 filter

預期：`tags` 已存（V3 migration `add_allowed_tools.sql` 等暗示）；只要 controller 加 param + repo `findByTagsContaining()`。

實作：
```java
@GetMapping("/skills")
Page<SkillSummary> list(
    @RequestParam(required = false) String category,
    @RequestParam(required = false) String tag,        // 新增
    @RequestParam(required = false) String keyword,
    @RequestParam(required = false) String author,
    Pageable pageable
) { ... }
```

Repo 層走 Spring Data JDBC `Specifications` 或自訂 `@Query`：
```java
@Query("SELECT * FROM skills WHERE :tag = ANY(tags)")
List<Skill> findByTag(String tag);
```
（PostgreSQL `ANY(text[])`；若 tags 是 JSONB 改用 `jsonb_array_elements_text`）

支援多 tag AND（`?tag=terraform&tag=security`）：留 follow-up；MVP 單 tag 即可。

### 2.3 拒收未知 query param（return 400）

**現況**：`?tag=` `?fooBar=` `?random=` 全被 backend 接受 → 回 200 + 全 list（filter 沒套）。

**問題**：silent acceptance — user typo 看不到問題。例如 `?categroy=Security` → 拼錯 → 回 200 全部 → user 以為「沒 skill 命中 Security」其實是參數錯。

**修法**：Spring MVC 沒內建拒收 unknown query param 機制，但可：

A. 自訂 `HandlerMethodArgumentResolver` / interceptor 比對 `@RequestParam` annotation 收的 param 與實際 request param key set，差集非空 → 拋 `UnknownQueryParamException` → @ControllerAdvice 回 400
B. 用 `@Validated` + 自訂 validator：在 controller method signature 加 sentinel 收所有 query param 然後檢查
C. 在 controller method 內手動 `request.getParameterMap().keySet().removeAll(KNOWN_PARAMS); if (!leftover.isEmpty()) throw ...`

**選 A**：global 套用所有 controller，一次寫好；新增 controller method 不用改。

實作：
```java
@Component
class UnknownQueryParamInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            Set<String> known = Arrays.stream(hm.getMethodParameters())
                .map(p -> p.getParameterAnnotation(RequestParam.class))
                .filter(Objects::nonNull)
                .map(rp -> rp.name().isEmpty() ? null : rp.name())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            // 加入 framework reserved（page, size, sort）
            known.addAll(Set.of("page", "size", "sort"));
            Set<String> actual = req.getParameterMap().keySet();
            Set<String> unknown = new HashSet<>(actual);
            unknown.removeAll(known);
            if (!unknown.isEmpty()) {
                throw new UnknownQueryParamException(unknown);
            }
        }
        return true;
    }
}

@ExceptionHandler(UnknownQueryParamException.class)
ResponseEntity<ErrorResponse> handle(UnknownQueryParamException ex) {
    return ResponseEntity.badRequest().body(new ErrorResponse(
        "VALIDATION_ERROR",
        "Unknown query parameter(s): " + String.join(", ", ex.getUnknownParams()),
        Instant.now()
    ));
}
```

**範圍**：先套 `/api/v1/skills` controller 試；確認穩定後擴大至全部 controller（新 spec or follow-up）。

**驗證 trade-off**：嚴格拒收會破舊 client（CLI / browser bookmark 帶舊 param）— 一次 ship 全平台會有風險。本 spec 只動 `/skills` endpoint group，CLI deploy 後再擴 — 漸進方式。

---

## 3. Acceptance Criteria

```
AC-1: Category filter case-insensitive
  Given 平台有 1 筆 skill category=security（lowercase 後）
  When 任一大小寫 query: ?category=security / ?category=Security / ?category=SECURITY
  Then 全部回相同 1 個結果

AC-2: Migration normalize 既有資料
  Given V19 migration 跑完
  When SELECT DISTINCT category FROM skills
  Then 全 lowercase（無 mixed case）

AC-3: Frontend 顯示 title-case
  Given /browse sidebar 列出 categories
  When 渲染
  Then 「security」顯示為「Security」（CSS capitalize）
  And /api/v1/categories response.name 仍為 lowercase

AC-4: Tag filter 真的 filter
  Given 平台有 skill A（tags:[terraform,security]）+ skill B（tags:[trading]）
  When ?tag=terraform
  Then 只回 skill A
  When ?tag=trading
  Then 只回 skill B
  When ?tag=nonexistent
  Then 回空 list

AC-5: Unknown query param 拒收 400
  Given ?categroy=Security（typo）
  When backend 處理
  Then 回 400 VALIDATION_ERROR
       message="Unknown query parameter(s): categroy"
  And 不靜默回全 list

AC-6: Pageable 標準 param 不被誤拒
  Given ?page=0&size=10&sort=createdAt,desc
  When backend 處理
  Then 接受（known framework params: page/size/sort）

AC-7: Frontend 既有 filter 不破
  Given /browse sidebar 用 ?category=Security ?keyword=terraform
  When 點擊 / search
  Then 仍正常 work（frontend 改傳 lowercase category 即可）
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SkillQueryControllerTest` 補 AC-1/4/5/6 + `V19_migration_test`）
- 手動 LAB：`curl '/api/v1/skills?category=Security'` vs `?category=security` 結果同；`?categroy=foo` → 400

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V19__normalize_skill_category.sql` | 新增 — UPDATE backfill lowercase + CHECK constraint |
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | publish 時 `category.toLowerCase()` |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | 加 `@RequestParam String tag`；category lookup 用 lower |
| `backend/src/main/java/.../skill/query/SkillRepository.java` | 加 `findByTag()` query |
| `backend/src/main/java/.../shared/api/UnknownQueryParamInterceptor.java` | 新增 |
| `backend/src/main/java/.../shared/api/UnknownQueryParamException.java` | 新增 |
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 加 `@ExceptionHandler(UnknownQueryParamException)` |
| `backend/src/main/java/.../shared/config/WebMvcConfig.java` | 註冊 interceptor 套用 `/api/v1/skills/**` 路徑 |

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/components/RiskFilterSidebar.tsx`（or category list） | 顯示加 `className="capitalize"` |
| `frontend/src/api/skills.ts` | `fetchSkills(...)` 傳 `category.toLowerCase()` 確保 query 一致 |
| `frontend/src/types/skill.ts` | （無需改型別，純行為）|

### Test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../skill/query/SkillQueryControllerTest.java` | 補 AC-1, AC-4, AC-5, AC-6 |
| `backend/src/test/java/.../shared/api/UnknownQueryParamInterceptorTest.java` | 新增 — 各種 path / method 行為 |

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

```java
@Test @DisplayName("AC-1: ?category 大小寫不影響結果")
void categoryFilterCaseInsensitive() throws Exception {
    var resA = mvc.perform(get("/api/v1/skills?category=security"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var resB = mvc.perform(get("/api/v1/skills?category=Security"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var jsonA = mapper.readTree(resA).path("content");
    var jsonB = mapper.readTree(resB).path("content");
    assertThat(jsonA.size()).isEqualTo(jsonB.size());
}

@Test @DisplayName("AC-4: ?tag=terraform 真的 filter")
void tagFilter() throws Exception {
    mvc.perform(get("/api/v1/skills?tag=terraform"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].name").value(containsString("terraform")));
}

@Test @DisplayName("AC-5: 未知 param → 400")
void unknownParamRejected() throws Exception {
    mvc.perform(get("/api/v1/skills?categroy=Security"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("categroy")));
}

@Test @DisplayName("AC-6: page/size/sort 不被誤拒")
void pageableParamsAccepted() throws Exception {
    mvc.perform(get("/api/v1/skills?page=0&size=5&sort=createdAt,desc"))
        .andExpect(status().isOk());
}
```

### 5.2 手動 LAB

deploy 後：
- [ ] `curl '/api/v1/skills?category=security'` count 與 `?category=Security` 同
- [ ] `curl '/api/v1/skills?tag=terraform'` 回 1 筆
- [ ] `curl '/api/v1/skills?tag=nonexistent'` 回 0 筆
- [ ] `curl '/api/v1/skills?categroy=Security'` 回 400
- [ ] `/browse` sidebar 顯示 `Security` / `Research` / `Trading`（大寫）
- [ ] 點 sidebar 任一 category → URL 與 filter 行為 OK

---

## 6. 設計筆記

### 為何不選 Title Case storage

Title Case（`Security`、`Cyber Security`）的 normalize 邏輯比 lowercase 複雜（首字大寫，連字號後也大寫，縮寫 ALL CAPS 例外）。view 層 `text-transform: capitalize` 處理絕大多數 case 已夠。少數特殊（如 `iOS`、`AI`）SKILL.md 作者可在 frontmatter 直接寫；DB normalize 統一 lowercase，view 層 capitalize 顯為「Ios」「Ai」可接受（trade-off 簡化）。

### Unknown param strict mode 漸進化

第一階段只套 `/api/v1/skills/**`。觀察：
- 是否有 CLI 老 client 帶不認識 param 被擋
- 是否誤擋 actuator / admin endpoints
擴展時加白名單 path pattern 明確指定。

### `?tag` 多選 follow-up

multi-tag AND（`?tag=a&tag=b`） / OR 都是有用 filter；MVP 單 tag 簡單；未來新 spec 加 `?tags=a,b&tagOp=AND` syntax。
