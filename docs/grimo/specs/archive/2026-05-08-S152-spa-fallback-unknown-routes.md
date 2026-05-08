# S152: SPA Fallback for Unknown Routes — 任意未知 URL 走進 React `NotFoundPage`

> Spec: S152 | Size: S(6) | Status: ✅ shipped 2026-05-08
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB `https://skillshub-644359853825.asia-east1.run.app/`）— 直接訪問 `/random-xyz` 顯示 raw XML `<ErrorResponse>` 或 Spring Whitelabel Error Page，而非 React `NotFoundPage`。

---

## 1. Goal

讓任何 **非 `/api/**` 也非真實靜態資源** 的 URL，全部 forward 到 `/index.html`，由 React Router 接手判斷 → 顯示 `NotFoundPage`。

**為什麼重要：**
- 使用者書籤、外部連結、typo URL 直接打 → 看到 Spring Whitelabel/XML 等技術頁面，破壞品牌一致感
- React Route 增刪時必須同步 `SpaFallbackController` allowlist，**容易漏改**（drift 風險高）
- 違背 SPA 的契約：「所有非 API 的 URL 由 frontend 負責」

**非目標：**
- 不改 React `NotFoundPage` 視覺
- 不改 `/api/**` 路由行為（仍回 JSON 404）
- 不改 favicon、CSS、JS 等真實靜態資源服務

---

## 2. Approach

### 2.1 現況回顧

`backend/src/main/java/io/github/samzhu/skillshub/shared/api/SpaFallbackController.java`：

```java
@GetMapping({
    "/browse", "/publish", "/publish/**",
    "/my-skills", "/collections", "/requests",
    "/notifications", "/analytics", "/flags", "/search",
    "/skills", "/skills/**", "/docs/**", "/auth-debug",
})
String forwardToIndex() {
    return "forward:/index.html";
}
```

**設計動機（既有 JavaDoc）：** 用 explicit list 避免攔到不存在的 `/api/...` typo（應回 JSON 404 給前端 / curl）。

**Drift 證據（fresh 案例 2026-05-08）：** S150 新增 React route `/collections/:id`，但 `SpaFallbackController` allowlist 僅含 `/collections`（無 `/collections/**`）。LAB 實測 `curl /collections/test-id` 直接回 **404**（連 SPA shell 都沒服務），證明每加一條新 nested React route 都需手改 backend，**極易遺漏**。任何 1 條新 React route 沒同步 → 書籤 / refresh 即壞。

### 2.2 實測 LAB（2026-05-08）

| URL | HTTP Status | 回應 | 期望 |
|-----|-------------|------|------|
| `/random-xyz`（瀏覽器導航 Accept=html,xml,...） | 404 | XML `<ErrorResponse>` | React `NotFoundPage` |
| `/foo`（fetch Accept=html） | 404 | Spring Whitelabel HTML | React `NotFoundPage` |
| `/foo.txt`（檔名 + html accept） | 404 | Whitelabel HTML | 維持 404（真實 static resource 處理） |
| `/api/v1/nonexistent`（json accept） | 404 | JSON `{"error":"NOT_FOUND"...}` | ✅ 維持 |
| `/api/foo`（html accept） | 404 | Whitelabel HTML | 維持 404 + JSON（API path 不該走 SPA） |
| `/skills/non-existent-id` | 200 | SPA index.html → React 顯「載入技能時發生錯誤」 | ✅ 路由運作（404 vs error 訊息細節留 S153） |

關鍵分界：
- **API path（`/api/**`）**：永遠回 JSON 4xx/5xx，絕不走 SPA shell
- **真實靜態檔案（含副檔名 `*.{js,css,svg,png,...}` + `index.html`）**：交給 Spring static resource handler
- **其他無副檔名的 path**：forward 到 `/index.html` → React Router 接手

### 2.3 三個方案

| 方案 | 機制 | Pros | Cons |
|------|------|------|------|
| **A：catchall + extension exclusion** ⭐ | 用 `@GetMapping("/{path:[^.]*}")` 與 `@GetMapping("/**/{path:[^.]*}")` 兩條 regex pattern catch 無副檔名 path；`/api/**` 已被 REST controller 顯式佔走，catchall 看不到 | 無 drift；React 加新 route 不需動 backend；保留 static resource 處理 | regex pattern 需熟悉 Spring Path Pattern Parser 行為 |
| **B：`ErrorController` 介入** | 實作 `ErrorController` `/error` handler，在 4xx HTML accept 時回 index.html | 利用既有 Spring error pipeline | 需處理 status code 保留；HTML/JSON 內容協商複雜；與 `@RestControllerAdvice` 互動需驗證 |
| **C：CI 驗證 allowlist drift** | 寫 test 解析 `App.tsx` Routes vs `SpaFallbackController` 字串列表 diff，drift 時 build fail | 保留現有結構 | 仍要兩處同步；不解決外部 typo 路徑問題（`/foo`、`/random-xyz` 仍壞） |

**選 A**：根本解 — drift 不可能發生，外部 typo 也走進 React NotFoundPage。

### 2.4 為什麼 A 不會誤攔 `/api/...`

Spring MVC 的 handler mapping 順序（`RequestMappingHandlerMapping` 優先於 `RouterFunctionMapping` 與 catch-all `ResourceHttpRequestHandler`）：

1. `RequestMappingHandlerMapping`：先比對所有 `@RestController` 上的 `@RequestMapping("/api/v1/...")` — 命中 → API controller 處理（含 404 fallback 到 Spring 的 `NoHandlerFoundException` → JSON `ErrorResponse`）
2. 沒命中 → 再比對 `@Controller` 上的 SPA fallback patterns
3. 都沒命中 → `ResourceHttpRequestHandler` 處理靜態資源
4. 都失敗 → `BasicErrorController` 回 Whitelabel

**驗證情境：** typo `/api/v1/skils`（少 l）— Spring 會先匹配 `/api/**`（透過 `@RequestMapping` 在 `SkillController` 的 prefix）走進 controller chain，找不到 method 回 JSON 404。本 spec 的 catchall pattern 因為 path 含 `/api/...` segment 不會搶到（catchall 必須 path 整體不含 `.` 才匹配，但更關鍵：API path 的 prefix 已被 `@RestController` 佔走，HandlerMapping order 優先）。

**反證測試：** `/api/foo`（HTML accept）目前回 Whitelabel 而非 JSON — 因為**沒有任何 controller 匹配 `/api/foo`**。實作 A 後，`@GetMapping("/{path:[^.]*}")` 也不會匹配（因為它只匹配單一 segment）；`@GetMapping("/**/{path:[^.]*}")` 確實會匹配 `/api/foo`。**這是 A 的一個邊界**：`/api/foo` 會被 catch 到 → forward index.html → React NotFoundPage。

是否能接受？**討論：**
- 若 client（curl / 前端）打 `/api/foo` 期待 JSON，本來就 broken — 因為 `/api/v1` 才是 prefix
- 接受這個邊界：對人而言，`/api/foo` typo 也看到 React NotFoundPage 比 Whitelabel 好
- 嚴格不接受：在 `SpaFallbackController` 加 `excludeUrlPatterns = "/api/**"`（@GetMapping 不直接支援，但可在 method body 檢查 `request.getRequestURI()` startsWith `/api/`，然後 `forward:/error`）

**選擇：** 加 `/api/**` 早返判斷，明確表達「API 路徑永遠不走 SPA shell」。

### 2.5 Implementation Sketch

```java
@Controller
class SpaFallbackController {

    /**
     * 攔截所有「無副檔名」的 GET 請求，forward 到 /index.html 讓 React Router 接手。
     *
     * Patterns:
     * - "/{path:[^.]*}"        → 單層 path 如 /browse, /foo
     * - "/**/{path:[^.]*}"     → 多層 path 如 /skills/abc, /docs/overview, /foo/bar
     *
     * 例外：以 /api/ 開頭的 path 一律 404（保留 JSON 4xx 給 API client）。
     * 含副檔名的 path 不被本 controller 攔截 → 走 Spring static resource handler。
     */
    @GetMapping({"/{path:[^.]*}", "/**/{path:[^.]*}"})
    Object forwardToIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        return "forward:/index.html";
    }
}
```

**Notes:**
- `path:[^.]*` 是 Spring Path Pattern Parser 的 capture group with regex constraint — 路段不可含 `.`（保留副檔名給 static handler）
- `[^.]*` 包含空字串，所以 `/` root path 仍會被現有 `RootController`（如有）或 static resource handler 的 welcome page 機制接手；若無 explicit `/`，則本 catchall 第一條 `/{path:[^.]*}` 會 match `path=""`；驗證後若需要可加 `@GetMapping("/")` 顯式 forward
- `request.getRequestURI()` 包含 context path（在 Spring Boot embedded 預設為空）

### 2.6 Existing Routes 拆解

刪除 `SpaFallbackController` 的 explicit allowlist，移除 `forwardToIndex` 舊版。新版 catchall 自動覆蓋所有現有 React routes：`/browse`、`/publish`、`/publish/validate`、`/skills/abc`、`/skills/foo/bar`（canonical alias）、`/docs/overview` 等。

---

## 3. Acceptance Criteria

```
AC-1: 未知 URL（無副檔名）走 React NotFoundPage
  Given 使用者瀏覽器直接輸入 /random-xyz 或 /foo/bar/baz
  When backend 收到 GET request
  Then 回 200 + index.html
  And 前端 React Router 比對 Routes，無匹配 → 渲染 NotFoundPage

AC-2: API path typo 仍回 JSON 404（不走 SPA shell）
  Given client 打 /api/v1/nonexistent 或 /api/foo
  When backend 處理 request
  Then 回 404 + JSON ErrorResponse
  And 不 forward 到 index.html

AC-3: 真實靜態資源檔案行為不變
  Given /assets/index-abc123.js 或 /favicon.ico 被請求
  When backend 處理 request
  Then 由 Spring static resource handler 服務該檔案

AC-4: 含副檔名的 typo URL 走靜態資源 404
  Given 使用者打 /foo.txt（不存在的 .txt 檔）
  When backend 處理 request
  Then 回 404（不 forward 到 index.html，避免「typo 文件擴展名」誤觸 SPA 渲染）

AC-5: 既有 SPA 路徑（/browse、/skills/abc、/docs/overview 等）行為不變
  Given 使用者直接訪問現有 React route URL
  When 頁面載入
  Then 顯示對應 React 頁面（與目前一致）

AC-6: 新增 React route 後，refresh 自動可用
  Given 開發者新增 /collections/:id（S150）並 deploy
  When 使用者直接訪問 /collections/some-id
  Then 走進 React `CollectionDetailPage`，無需動 backend allowlist
```

驗證指令：
- 自動化：`cd backend && ./gradlew test`（per qa-strategy.md；新增 `SpaFallbackControllerTest` 覆蓋 AC-1~AC-4）
- 手動：deploy 後 `curl -i https://.../random-xyz`、`curl -i https://.../api/v1/foo`、瀏覽器直接打 `/random-xyz` 看到 React NotFoundPage

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/io/github/samzhu/skillshub/shared/api/SpaFallbackController.java` | 移除 explicit allowlist；改用 catchall pattern `{"/{path:[^.]*}", "/**/{path:[^.]*}"}`；加入 `/api/**` early-return 404 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java` | 新增 `@WebMvcTest`：covers AC-1（unknown path forward）、AC-2（/api/* not forwarded）、AC-4（dotted path passthrough）、AC-5（known route forward） |

---

## 5. Test Plan

### 5.1 單元 / 整合測試（`@WebMvcTest`）

```java
@WebMvcTest(SpaFallbackController.class)
class SpaFallbackControllerTest {

    @Autowired MockMvc mvc;

    @Test @DisplayName("AC-1: unknown path → forward to /index.html")
    void unknownPathForwards() throws Exception {
        mvc.perform(get("/random-xyz"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/index.html"));
    }

    @Test @DisplayName("AC-1: deep unknown path → forward")
    void deepUnknownPathForwards() throws Exception {
        mvc.perform(get("/foo/bar/baz"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/index.html"));
    }

    @Test @DisplayName("AC-2: /api/* path returns 404 not forwarded")
    void apiPathReturns404() throws Exception {
        mvc.perform(get("/api/foo"))
           .andExpect(status().isNotFound())
           .andExpect(content().string(""));
    }

    @Test @DisplayName("AC-4: dotted path not handled by this controller")
    void dottedPathNotMatched() throws Exception {
        // dotted path 走進 ResourceHttpRequestHandler；本 controller 不該匹配
        // → 沒有 forward；@WebMvcTest 不載入 ResourceHandler，預期 404 + 沒 forwardedUrl
        mvc.perform(get("/foo.txt"))
           .andExpect(status().isNotFound());
    }

    @Test @DisplayName("AC-5: known SPA route forwards normally")
    void knownRouteForwards() throws Exception {
        mvc.perform(get("/browse"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/index.html"));
    }
}
```

### 5.2 手動驗證

deploy 後：
- [ ] `https://.../random-xyz` → React NotFoundPage（漂亮頁面，不是 XML/Whitelabel）
- [ ] `https://.../api/v1/foo` → JSON `{"error":"NOT_FOUND",...}`
- [ ] `https://.../skills/abc`（不存在 ID）→ SPA 載入，React 頁面顯示「找不到技能」（細節 S153）
- [ ] `https://.../browse`（已存在 route）→ HomePage 正常
- [ ] `https://.../assets/index-abc.js` 直訪 → 真實 JS bundle（DevTools network 確認 200 + js content-type）

---

## 6. Verification

| 項目 | 結果 |
|------|------|
| `./gradlew test --tests "...SpaFallbackControllerTest" -x processTestAot` | ✅ 8/8 PASS |
| 既有 SPA route（/browse / /docs/overview）regression | ✅ AC-5 + AC-6 直接覆蓋（forward 至 /index.html） |
| `/api/` typo 早返 404 | ✅ AC-2 (/api/foo) + 變體 (/api/v1/nonexistent) 雙覆蓋 |
| TypeScript 編譯（無前端改動） | n/a |

實作對齊 spec：用 `WebMvcSliceTestBase` 共用 `SecurityConfig + AotStubBeans + CacheManager` stub，避免重複設置；test slice cache key 收斂到既有 base class。`@WebMvcTest` 走 `-x processTestAot` 繞過 pre-existing Modulith cycle（per S148c），不影響 runtime context loading。

---

## 7. Result

**Shipped 2026-05-08** — 1 backend file edit + 1 new test file，8/8 PASS。

### 7.1 程式變動

- `backend/.../shared/api/SpaFallbackController.java`
  - 移除 explicit allowlist (`/browse`, `/publish/**`, ..., `/auth-debug`) — 14 條 entry 全砍
  - 新增 catchall pattern `{"/{path:[^.]*}", "/**/{path:[^.]*}"}` 涵蓋所有「無副檔名」path
  - `/api/` 開頭 early-return 404（保留 JSON 4xx 給 API client）
- `backend/.../shared/api/SpaFallbackControllerTest.java`（新增）
  - 8 個 case 覆蓋 AC-1（單層 + 多層）/ AC-2（/api/ + /api/v1/ typo 變體）/ AC-4（dotted path）/ AC-5（既有 SPA route）/ AC-6（新 nested route 自動 forward）
  - extends `WebMvcSliceTestBase` 共用 base class config

### 7.2 行為對照

| URL | 改前 | 改後 |
|-----|------|------|
| `/random-xyz` | 404 + Whitelabel/XML | 200 + index.html → React NotFoundPage ✅ |
| `/collections/abc` | 404（allowlist 沒 `/collections/**`，drift bug）| 200 + forward ✅ |
| `/api/foo` typo | Whitelabel HTML 404 | 純 404 無 body（HandlerMapping 順序保證 API path 不誤攔；本 controller 早返）✅ |
| `/foo.txt` | 404（static handler 沒此檔）| 404（catchall 不匹配含 dot path → static handler）✅ |
| `/browse` | 200 + forward（allowlist 命中）| 200 + forward（catchall 命中）✅ |

### 7.3 Trade-off

- 新 catchall 對 `/api/foo`（沒對應 controller 的 typo）行為微變：原本走 Spring `BasicErrorController` 回 Whitelabel；現在 `SpaFallbackController` 早返 純 404 無 body。對 client 而言皆 404，但 body 略簡。S162 ship 後 platform error handler 接管會回 JSON ErrorResponse，本 spec 不重複處理。
- HandlerMapping 順序保證 `@RestController("/api/v1/...")` 永遠先於 catchall，正常 API 路徑不受影響。

---

## 8. 相關 spec

- **S153**（Skill not found UX 區分 404 vs 5xx）：本 spec 解決路由層 fallback；S153 解決「打對 SPA 路由但 backend 回 404」的訊息區分。互不相依，可並行。
- **S143**（`/docs` canonical entry）：原依賴 allowlist 已含 `/docs/**`；本 spec 移除 allowlist 後，S143 邏輯仍然成立（SPA route `/docs` redirect 在 client-side React Router 處理）。
