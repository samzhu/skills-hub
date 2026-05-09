# S159a: Unknown Query Param Rejection — Skill 查詢端點 typo 防護

> Spec: S159a | Size: XS(3) | Status: ✅ shipped
> Date: 2026-05-09
> Parent: S159（Skill query API hardening；拆分為 S159a/b/c/d）
> Trim from S159 §2.3 — 其餘 (§2.1 category normalize / §2.2 tag filter / §2.2b pageable validation) 拆 S159b/c/d backlog rows defer

---

## 1. Goal

讓 SkillQueryController 端點對拼錯的 query 參數 fail-fast 回 400，避免 silent fall-through。

```
GET /api/v1/skills?categroy=Security  ← typo
```

**現況**：backend silently 忽略 `categroy`，回 200 + 全部 list（filter 沒套）→ user 以為「沒命中 Security」其實是參數名錯。

**目標**：偵測未在 controller method 宣告的 query 參數 → 回 400 `VALIDATION_ERROR` + 列出未知參數名。

**非目標**（拆 sub-specs 後 defer）：
- 不做 category storage normalize（→ S159b）
- 不實作 `?tag=` filter（→ S159c）
- 不做 pageable 非法值拒收（→ S159d）
- 不擴展至全 controller（先試 SkillQuery + categories；漸進方式 per S159 §2.3 風險評估）

---

## 2. Approach

### 2.1 套用範圍

`/api/v1/skills/**` + `/api/v1/skills` + `/api/v1/categories`（所有 GET 端點）。

漸進化策略 per S159 §2.3：先試 query 端點，觀察是否有老 CLI / bookmark 帶 stale param 被擋；穩定後再擴展。

### 2.2 偵測規則

於 `HandlerInterceptor.preHandle` 階段：

1. 從 `HandlerMethod.getMethodParameters()` 蒐集所有 `@RequestParam` 的 `name() / value()`（empty 時 fallback method parameter name — 編譯需 `-parameters` flag，本專案 build 已啟用）
2. method 含 `Pageable / Sort` 參數 → 加入 framework reserved `page / size / sort`（Spring Data 預設 binder 名）
3. request 實際 query keys 不在 known set 即視為 unknown → 拋 `UnknownQueryParamException`
4. 由 `GlobalExceptionHandler.handleUnknownQueryParam` 轉 HTTP 400 + 平台 `ErrorResponse`

### 2.3 跳過條件

- non-GET method（POST / PUT / DELETE 等）→ pass through（spec 範圍只關注查詢端 GET）
- handler 非 `HandlerMethod`（static resource、SPA fallback 等）→ pass through
- 空 query string → pass through

---

## 3. Acceptance Criteria

```
AC-5: Unknown query param 拒收 400
  Given GET /api/v1/skills?categroy=Security（typo）
  When backend 處理
  Then 回 400 VALIDATION_ERROR
       message="Unknown query parameter(s): categroy"
  And 不靜默回全 list

AC-6: Pageable 標準 param 不被誤拒
  Given GET /api/v1/skills?page=0&size=10&sort=createdAt,desc
  When backend 處理
  Then 200（known framework params: page/size/sort）

AC-A: 多個 unknown 一次列出
  Given ?categroy=x&fooBar=y
  When backend 處理
  Then 400 message 同時含「categroy」「fooBar」

AC-B: @RequestParam(name="q") rename — 用 annotation 名而非 method 原參數名
  Given controller method `void m(@RequestParam(name="q") String alias)`
  When ?q=foo → 200；?alias=foo → 400「alias」

AC-C: Non-GET / non-HandlerMethod 不擋
  POST /api/v1/skills?anything=x → pass interceptor（後續走 controller / 別的 handler）
  Static resource → pass

AC-D: Exception 拒收同時保 immutable param set
  UnknownQueryParamException.unknownParams 為 unmodifiable
```

驗證指令：`cd backend && ./gradlew test --tests UnknownQueryParamInterceptorTest --tests GlobalExceptionHandlerTest --tests SkillQueryControllerApiContractTest`

---

## 4. Files Changed

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../shared/api/UnknownQueryParamException.java` | 新增 |
| `backend/src/main/java/.../shared/api/UnknownQueryParamInterceptor.java` | 新增 |
| `backend/src/main/java/.../shared/config/WebMvcConfig.java` | 新增（首個 WebMvcConfigurer） |
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 加 `@ExceptionHandler(UnknownQueryParamException.class)` |
| `backend/src/test/java/.../shared/api/UnknownQueryParamInterceptorTest.java` | 新增（11 unit tests） |
| `backend/src/test/java/.../shared/api/GlobalExceptionHandlerTest.java` | 加 1 unit test for new handler |

---

## 5. Test Plan

純 unit test 驗 interceptor 行為 + handler 回應合約 — 無需啟動 Spring context；快速；不依賴 DB。

涵蓋：
- AC-5 / AC-A：unknown / multiple unknown → throw
- AC-6：pageable reserved 通過
- AC-B：@RequestParam(name=) alias
- known params（keyword/category/author）通過
- non-GET / non-HandlerMethod / empty query 跳過
- AC-D：exception immutability

`SkillQueryControllerApiContractTest` 已驗證 interceptor 不擋既有 `.param("category", ...)` MockMvc 測試（既有 2/2 PASS）。

---

## 6. 設計筆記

### 為何不用 @Validated + sentinel param

Spring 沒內建拒收 unknown query param 機制。`@Validated` 對 method-level 有效，但 query string 由 `RequestMappingHandlerAdapter` 在 binding 階段就忽略未宣告 param。Interceptor 在 `preHandle` 拿 `HandlerMethod` 反射 `@RequestParam`，一處規則覆蓋所有 controller，新增 method 不用記得加 sentinel。

### 為何 GET only

POST / PUT / DELETE 等 mutation 端點通常不接 query param（body-based）。本 spec 範圍只關 query 端 silent fall-through 問題。Mutation 端的 unknown body field 由 Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 處理（已預設 enabled）。

### 為何 Pageable reserved hardcode

Spring Data `PageableHandlerMethodArgumentResolver` 預設 bind `page / size / sort`。理論上可從 resolver 配置動態取，但複雜度不值；hardcode 三個常數最直接。若未來改 prefix（如 `qualifier`），interceptor 再對應更新。

### 為何漸進 path scope

`/api/v1/skills/**` + `/api/v1/categories` 是查詢端入口。一次套全平台會破老 CLI client（帶 stale param）。穩定後（觀察 1-2 週 ops 無噪音）擴展至 collection / review / request 等 GET 端點。

---

## 7. Result（實測）

**測試數據**（本 tick 跑）：

```
UnknownQueryParamInterceptorTest:    11/11 PASS  (0.042s)
GlobalExceptionHandlerTest:          43/43 PASS  (0.184s) — 含新增 1 個
SkillQueryControllerApiContractTest:  2/2  PASS  (3.668s) — 確認 interceptor 不擋既有 test
```

**Build**：`./gradlew test --tests ...` BUILD SUCCESSFUL in 1m 19s（含 jacoco / aotTestClasses 全 prep）

**Coverage 觸碰範圍**：
- 新增 3 個 production class（exception / interceptor / config）+ 1 個 handler method
- 所有 public method 與 branch 由 11 unit test 涵蓋（GET / non-GET / HandlerMethod / 非 HandlerMethod / empty query / pageable / 多 unknown / alias）

**Defer list**（拆 sub-spec backlog rows）：
- S159b：Category storage normalize（V19 migration → lowercase + CHECK constraint + frontend `capitalize`）
- S159c：`?tag=` filter 實作
- S159d：Pageable 非法值拒收（page < 0 / size 邊界）

下次 LAB deploy 後手動 smoke：
- `curl '/api/v1/skills?categroy=foo'` → 400 含 "categroy"
- `curl '/api/v1/skills?keyword=k&page=0&size=10'` → 200
