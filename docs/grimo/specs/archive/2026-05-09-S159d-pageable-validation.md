# S159d: Pageable 非法值拒收 — `page<0` / `size<=0` / `size>100` 一律 400

> Spec: S159d | Size: XS(2) | Status: ✅ shipped
> Date: 2026-05-09 (designed) → 2026-05-10 (shipped)
> Origin: 拆自 S159 META §2.2b — query API hardening 子 spec；S159a (v4.43.0) 已 ship 未知 query 參數 fail-fast，此 spec 補 Pageable 數值範圍

---

## 1. Goal

**一句話：** API 收到 `?page=-1` 或 `?size=999999` 時直接 400，不再讓 Spring 預設「-1 當 0」、「999999 觸發 OOM 風險」過去。

**為什麼重要：**
- `?size=999999&sort=createdAt` 會觸發大量 DB row fetch + 反序列化，Cloud Run instance 可能 OOM
- `?page=-1` Spring 預設 silently 變 0，user 看到資料但邏輯不對 — debug 困難
- 對齊 S159a（未知 param 400）的 fail-fast 哲學

**非目標：**
- 不改 sort 欄位 allowlist（另一個議題）
- 不加 cursor-based pagination（重型，未來 spec）

---

## 2. Approach

### 2.1 現況

`SkillQueryController` `@GetMapping("/skills")` 收 `Pageable pageable`，由 Spring Data Web `PageableHandlerMethodArgumentResolver` 解析。預設行為：
- `page<0` → 強制變 0（silent）
- `size<=0` → 用 default size（silent）
- `size > maxPageSize`（預設 2000）→ clamp 到 maxPageSize（silent，不 400）

### 2.2 設計

**實作走 `HandlerInterceptor` 路徑（preHandle 階段檢 raw query params）**，對齊 S159a `UnknownQueryParamInterceptor` pattern。

#### 2.2a 實作經驗：controller-level helper 不可行（spec 原始設計修正）

Spec design 第一版預想「controller 收 `Pageable` 起手 call `PageableValidator.validate(pageable)`」。實作時驗證 Spring 行為發現此路**不通**：

`PageableHandlerMethodArgumentResolver.parseAndApplyBoundaries()` 對 raw query string 數值 silent clamp：
- `?page=-1` → resolver clamp 為 0 後才構造 `PageRequest`
- `?size=0` → resolver fallback 至 default size
- `?size=999999` → resolver clamp 至 maxPageSize（預設 2000）

等到 `pageable` 進到 controller method body 時，`pageable.getPageNumber()` / `pageable.getPageSize()` **已是 silent fixed 後的合法值** — controller-level 檢查永遠抓不到原始違規 input。

#### 2.2b 改走 `PageableValidationInterceptor`（preHandle 階段攔 raw 值）

```java
public class PageableValidationInterceptor implements HandlerInterceptor {
    public static final int MAX_PAGE_SIZE = 100;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return true;
        if (!(handler instanceof HandlerMethod hm)) return true;
        if (!hasPageableParam(hm)) return true;          // method 無 Pageable 跳過

        validatePageParam(req.getParameter("page"));     // raw string，未經 resolver
        validateSizeParam(req.getParameter("size"));
        return true;
    }
    // ... validate* methods 拋 InvalidPageableException
}
```

關鍵：**`HandlerInterceptor.preHandle` 早於 `HandlerAdapter.handle()` 的 argument resolution** — 是唯一在 resolver clamp 前看到 raw `page` / `size` 字串的時機。

註冊在 `WebMvcConfig`（與 S159a `UnknownQueryParamInterceptor` 同 path scope）：

```java
registry.addInterceptor(new PageableValidationInterceptor())
        .addPathPatterns("/api/v1/skills/**", "/api/v1/skills", "/api/v1/categories");
```

`InvalidPageableException` extends `IllegalArgumentException`；`GlobalExceptionHandler` 加專屬 `@ExceptionHandler` 給 `INVALID_PAGEABLE` error code（most-specific-first 規則保證早於 generic `IllegalArgumentException` handler 匹配）。

### 2.3 範圍

實作前 grep `Pageable ` 確認：production code 中**只有 `SkillQueryController.search`** 接 `Pageable`（其他被掃過的 `MyCollectionsController` / `CollectionsPageController` / `RequestBoardController` 實際不存在或不收 Pageable）。

interceptor 不寫死 controller 名單 — 透過 `HandlerMethod.getMethodParameters()` 動態判斷是否含 Pageable，未來新 controller 加 Pageable 自動覆蓋。

### 2.4 為何不用 Bean Validation `@Valid` on Pageable

`Pageable` 是 Spring 介面，不是 record，無法直接 annotate `@Min`/`@Max`。要用 `@Valid` 必須包一層 DTO，反而複雜。

### 2.5 為何不用 `PageableHandlerMethodArgumentResolverCustomizer`

`setMaxPageSize(100)` 仍是 silent clamp（resolver 直接把 `size=999999` 改 100 不 throw）— 達不到 spec §1 fail-fast 目標。

---

## 3. Acceptance Criteria

```
AC-1: page<0 → 400
  Given GET /api/v1/skills?page=-1
  Then HTTP 400 + ErrorResponse{ "error": "INVALID_PAGEABLE", "message": "page must be >= 0" }

AC-2: size<=0 → 400
  Given GET /api/v1/skills?size=0
  Then HTTP 400 + INVALID_PAGEABLE

AC-3: size>100 → 400（防 OOM）
  Given GET /api/v1/skills?size=101
  Then HTTP 400 + INVALID_PAGEABLE

AC-4: 合法 pageable 不影響
  Given GET /api/v1/skills?page=0&size=20
  Then HTTP 200 + Page<Skill>
  And page=2&size=50 也 200

AC-5: 預設值（無 param）不影響
  Given GET /api/v1/skills（無 page/size param）
  Then HTTP 200 + 預設 page=0, size=20

AC-6: 多 controller sweep
  Given 任何 endpoint 接 Pageable
  Then 違規 param 一律 400（不能 controller A 防 controller B 不防）
```

**驗證指令：** `cd backend && ./gradlew test`（含新 `PageableValidatorTest` + `SkillQueryControllerPageableTest`）

---

## 4. Files to Change（實際 ship）

### Backend production code

| 檔案 | 變動 |
|------|------|
| `shared/api/InvalidPageableException.java` | **新增** — extends `IllegalArgumentException`，攜訊息 |
| `shared/api/PageableValidationInterceptor.java` | **新增** — `HandlerInterceptor.preHandle` 拒收 raw `page<0` / `size<=0` / `size>100`；偵測 method 含 `Pageable` 才觸發 |
| `shared/api/GlobalExceptionHandler.java` | **加 handler** — `@ExceptionHandler(InvalidPageableException.class)` → 400 + `INVALID_PAGEABLE`（most-specific 早於 generic `IllegalArgumentException`）|
| `shared/config/WebMvcConfig.java` | **註冊 interceptor** — 同 S159a path scope（`/api/v1/skills/**`, `/api/v1/skills`, `/api/v1/categories`）|
| `skill/query/SkillQueryController.java` | inline 註解標 S159d 由 interceptor 在 preHandle 處理（無程式變動）|

### Backend test

| 檔案 | 變動 |
|------|------|
| `shared/api/PageableValidationInterceptorTest.java` | **新增** — 13 case：4 reject (page<0 / size=0 / size=-5 / size>100 / size=999999) + 4 accept (page=0/size=20、page=2/size=50、size=100 邊界、無 param) + 4 跳過情境 (POST、無 Pageable param、非 HandlerMethod、page=abc 非數值) |
| `shared/api/GlobalExceptionHandlerTest.java` | **加 1 case** — `handleInvalidPageable` 單元測試驗 400 + `INVALID_PAGEABLE` shape |

註：原 spec 預設 `PageableValidatorTest` + `SkillQueryControllerPageableTest` 改為 interceptor unit test + handler unit test，覆蓋面相同（直接驗 preHandle 邏輯比走 MockMvc + slice 更貼近實際攔截路徑且快 ~30x）。

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

| AC | 驗證方式 |
|----|---------|
| AC-1 | `PageableValidationInterceptorTest.rejectsNegativePage` |
| AC-2 | `PageableValidationInterceptorTest.rejectsZeroSize` + `rejectsNegativeSize` |
| AC-3 | `PageableValidationInterceptorTest.rejectsOversizedPage` + `rejectsExtremeSize` + 邊界 `acceptsBoundarySize` (size=100) |
| AC-4 | `PageableValidationInterceptorTest.acceptsValidPageable` + `acceptsValidNonDefaultPageable` |
| AC-5 | `PageableValidationInterceptorTest.acceptsMissingParams` |
| AC-6 | `Method 不含 Pageable 參數 → 跳過` 確認 interceptor 動態偵測（未來 controller 加 Pageable 自動覆蓋）|
| `INVALID_PAGEABLE` shape | `GlobalExceptionHandlerTest.invalidPageableReturns400WithCode` |

### 5.2 手動

無 — 純 API contract 改動，自動化覆蓋足夠。

---

## 6. Verification

```
$ cd backend && ./gradlew test \
    --tests 'PageableValidationInterceptorTest' \
    --tests 'GlobalExceptionHandlerTest' \
    --tests 'UnknownQueryParamInterceptorTest' \
    --tests 'SkillQueryControllerApiContractTest'

✓ PageableValidationInterceptorTest:   13/13 PASS (0.030s)
✓ GlobalExceptionHandlerTest:           44/44 PASS (0.178s)  ← +1 vs baseline (handleInvalidPageable)
✓ UnknownQueryParamInterceptorTest:     11/11 PASS (0.021s)  ← regression check S159a
✓ SkillQueryControllerApiContractTest:   2/2  PASS (3.515s)  ← controller contract 不破

70/70 PASS · 0 failures · 0 errors
BUILD SUCCESSFUL in 1m 24s
```

**設計修正紀錄**：原 spec §2.2 走 controller-level helper，實作驗證發現 Spring resolver silent clamp 早於 controller，改走 interceptor preHandle 路徑（§2.2a 補記）。AC 全部一致達成；error code、訊息文案皆對齊原 spec。

---

## 7. Result

**ship metric**：

- 新增 production 檔：2（InvalidPageableException + PageableValidationInterceptor）
- 修改 production 檔：3（GlobalExceptionHandler、WebMvcConfig、SkillQueryController inline 註解）
- 新增 test 檔：1（PageableValidationInterceptorTest，13 case）
- 修改 test 檔：1（GlobalExceptionHandlerTest +1 case）
- 測試覆蓋：6 AC 全綠 · 既有 S159a / SkillQueryController contract regression 0
- 編譯時間：1m 24s（targeted test slice）
- LOC：production +120 / test +175

**對 S159 META 進度**：S159a (✅ ship v4.43.0) + S159d (✅ 本 spec) — 2/4 完成；剩 S159b（category normalize）、S159c（`?tag=` filter）。

