# S162: API Response Consistency — 統一 Error Shape + 鎖死 JSON Content Negotiation

> Spec: S162 | Size: S(5) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 兩個 API 一致性問題：(1) 403 errors 走 Spring `BasicErrorController` whitelabel shape，與平台自家 `{error, message, timestamp}` shape 不同 → frontend 錯誤解析會 fail。(2) `Accept: application/xml` 直接回 XML serialized response — 非預期 surface area，OpenAPI 3.1 spec 只承諾 JSON。

---

## 1. Goal

讓平台 REST API 對外只暴露 **一致的 JSON contract**：

1. 所有錯誤（含 401/403/404/405/415/500）走**同一 `ErrorResponse` shape**
2. Content negotiation 鎖死 `application/json`（rejecting `application/xml` / `text/html` 等非預期 accept）

**為什麼重要：**
- 不一致的 error shape → frontend `ApiError` parse 失敗 → user 看到 generic「載入失敗」而非實際 backend message（per S153 同類議題）
- XML response 是 Jackson XML extension 在 classpath 自動啟用的副作用；平台無 docs / SDK / contract 承諾此格式 → user 不會用，但 attack surface 沒理由保留
- XML deserialize 在某些 config 下有 XXE 風險（即便 inputStream 進不來，response side 同樣是不必要的 dual contract）

**非目標：**
- 不改 download endpoint（仍 `application/octet-stream`）
- 不改 SKILL.md / `text/markdown` endpoint
- 不改 actuator endpoints（自有 contract）

---

## 2. Approach

### 2.1 Error Shape 不一致實測

| HTTP status | endpoint 來源 | 實際 response shape | 平台預期 |
|-------------|-------------|---------------------|----------|
| 403 (Access Denied) | Spring Security default | `{"timestamp", "status":403, "error":"Forbidden", "message":"Access Denied", "path":"..."}` | 應為 `{"error":"FORBIDDEN", "message":"Access Denied", "timestamp":"..."}` |
| 400 (Validation) | Platform `@ExceptionHandler(MethodArgumentNotValidException)` | `{"error":"VALIDATION_ERROR", "message":"...", "timestamp":"..."}` ✓ | ✓ |
| 405 (Method) | Platform | `{"error":"METHOD_NOT_ALLOWED", ...}` ✓ | ✓ |
| 404 (NOT_FOUND from controller) | Platform | `{"error":"NOT_FOUND", ...}` ✓ | ✓ |
| 404 (No static resource — typo path) | Spring `BasicErrorController` | `{"error":"NOT_FOUND", "message":"No static resource ...", "timestamp":"..."}` | 看起來符合，但不確定 path 欄位 |
| 409 (state conflict) | Platform | `{"error":"STATE_CONFLICT", ...}` ✓ | ✓ |
| 415 (unsupported media) | Spring default | shape 不確定（瀏覽器 access blocked 看不到 raw body） | 應走 `{error:"UNSUPPORTED_MEDIA_TYPE", ...}` |
| 500 (untyped exception) | 未實測 | 推測 Spring default | 應走 platform shape |

**關鍵：** 403 / 401 / 415 / 500 等 framework-level error 沒被 platform `@RestControllerAdvice` 接到，fall through 到 Spring `BasicErrorController` 預設 JSON shape。

### 2.2 統一 ErrorResponse 設計

既有 platform shape：

```java
public record ErrorResponse(
    String error,         // SCREAMING_SNAKE_CASE 錯誤碼
    String message,       // 人類可讀訊息（English；frontend 轉繁中）
    Instant timestamp     // ISO-8601
) {}
```

**漏網之魚** — 加 `@RestControllerAdvice` handler：

```java
@RestControllerAdvice
class GlobalExceptionHandler {

    // 新增 — Spring Security 401/403
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(401).body(new ErrorResponse(
            "AUTHENTICATION_REQUIRED",
            "Authentication is required to access this resource",
            Instant.now()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(new ErrorResponse(
            "FORBIDDEN",
            "You don't have permission to perform this action",
            Instant.now()
        ));
    }

    // 新增 — content negotiation 失敗
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleUnsupportedMedia(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(415).body(new ErrorResponse(
            "UNSUPPORTED_MEDIA_TYPE",
            "Content-Type not supported: " + ex.getContentType(),
            Instant.now()
        ));
    }

    // 新增 — fallback uncaught
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(new ErrorResponse(
            "INTERNAL_ERROR",
            "An unexpected error occurred",  // 不洩漏 stack
            Instant.now()
        ));
    }
}
```

**Spring Security exception 入口**：`@ExceptionHandler` 在 `@RestControllerAdvice` 收 `AuthenticationException` / `AccessDeniedException` 預設不接（這些在 filter chain 拋；by default 走 `AccessDeniedHandler` / `AuthenticationEntryPoint`）。需要設：

```java
http
    .exceptionHandling(ex -> ex
        .authenticationEntryPoint((req, res, e) -> {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write(toJson(new ErrorResponse("AUTHENTICATION_REQUIRED", "...", now)));
        })
        .accessDeniedHandler((req, res, e) -> {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write(toJson(new ErrorResponse("FORBIDDEN", "...", now)));
        })
    );
```

### 2.3 `BasicErrorController` 替換

Spring `BasicErrorController` 處理「filter chain throw 但 advice 沒接 / 靜態資源 404」等 fallback。其預設 JSON shape `{timestamp, status, error, message, path}` 不符平台。

**替換方案：**

```java
@Component
class PlatformErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ErrorResponse> handleError(HttpServletRequest req) {
        Integer status = (Integer) req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object message = req.getAttribute(RequestDispatcher.ERROR_MESSAGE);

        return ResponseEntity.status(status != null ? status : 500)
            .body(new ErrorResponse(
                statusToCode(status),                 // 404 → "NOT_FOUND" 等
                message != null ? message.toString() : "Error",
                Instant.now()
            ));
    }

    private String statusToCode(Integer s) {
        if (s == null) return "INTERNAL_ERROR";
        return switch (s) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> "INTERNAL_ERROR";
            default -> "ERROR";
        };
    }
}
```

註冊覆蓋 default：自動發現（`@Component` 實作 `ErrorController`）。

### 2.4 鎖死 JSON Content Negotiation

**現況**：`Accept: application/xml` 走 Jackson XML extension 序列化 response 為 XML。可能因 classpath 含 `jackson-dataformat-xml` 自動 register。

**修法**：

A. **Build-side**: 移除 `jackson-dataformat-xml` 依賴（如果是 transitive）。風險：可能其他模組需要。

B. **Config-side**: 在 `WebMvcConfigurer` 移除 XML message converter：

```java
@Configuration
class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer config) {
        config.defaultContentType(MediaType.APPLICATION_JSON)
              .mediaType("json", MediaType.APPLICATION_JSON);
        // 不註冊 xml mapping
    }
}
```

C. **Per-controller**: `@RequestMapping(produces = APPLICATION_JSON_VALUE)` 全 controller。**繁瑣，不選**。

**選 B**：global 鎖死，新加 controller 不用記。

驗證：`curl -H 'Accept: application/xml' .../skills` 預期 → 406 Not Acceptable + JSON ErrorResponse（搭配 §2.3 走 platform shape）

---

## 3. Acceptance Criteria

```
AC-1: 403 走平台 ErrorResponse shape
  Given 觸發 Access Denied（如打私人 skill 無權限）
  When 收 403 response
  Then body = {"error":"FORBIDDEN","message":"...","timestamp":"..."}
  And 不含 "status"、"path" 欄位

AC-2: 401 走平台 shape
  Given anonymous 打需登入 endpoint
  When 收 401
  Then body = {"error":"AUTHENTICATION_REQUIRED","message":"...","timestamp":"..."}

AC-3: 415 走平台 shape
  Given POST 帶 Content-Type: text/plain
  When backend reject
  Then body = {"error":"UNSUPPORTED_MEDIA_TYPE","message":"...","timestamp":"..."}

AC-4: 404 (typo path) 走平台 shape
  Given 訪問 /api/v1/foo（不存在 endpoint）
  When backend 處理
  Then body = {"error":"NOT_FOUND","message":"...","timestamp":"..."}
  And 不含 "status"、"path"

AC-5: 500 不洩漏 stack
  Given 內部 unchecked exception
  When backend response
  Then body = {"error":"INTERNAL_ERROR","message":"An unexpected error occurred","timestamp":"..."}
  And 不含 stack trace / class name

AC-6: Accept: application/xml → 406 + JSON error
  Given GET /api/v1/skills 帶 Accept: application/xml
  When backend 處理
  Then 回 406 Not Acceptable
  And body content-type = application/json
  And body shape = ErrorResponse 平台格式

AC-7: 既有 JSON contract 不破
  Given GET /api/v1/skills 帶 Accept: application/json（或不帶 Accept）
  When 處理
  Then 200 + JSON content-type
  And body 結構與本 spec 前完全一致

AC-8: Frontend ApiError 對所有 status 正確 parse
  Given 前端任一 4xx/5xx response
  When ApiError.is(error) 與 .message 取值
  Then message 為 ErrorResponse.message（不 fall through 到 generic「載入失敗」）
  Note: 前端 ApiError parse 應已 robust；本 AC 是 backend ship 後 frontend regression check
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `ErrorResponseConsistencyTest`）
- 手動 LAB：`curl -X GET .../api/v1/skills/00000000-0000-0000-0000-000000000000 -i` → 確認 body shape；
  `curl -H 'Accept: application/xml' .../api/v1/skills -i` → 406 JSON

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 加 `AuthenticationException`、`AccessDeniedException`、`HttpMediaTypeNotSupportedException`、`Exception` (fallback) handlers |
| `backend/src/main/java/.../shared/api/PlatformErrorController.java` | 新增 — 取代 BasicErrorController |
| `backend/src/main/java/.../shared/security/SecurityConfig.java` | `exceptionHandling.authenticationEntryPoint` / `accessDeniedHandler` 寫平台 ErrorResponse |
| `backend/src/main/java/.../shared/config/WebMvcConfig.java` | 移除 XML message converter；ContentNegotiationConfigurer 鎖 JSON |
| `backend/build.gradle.kts` | （optional）移除 `jackson-dataformat-xml` 顯式依賴（若有） |
| **Tests** | `ErrorResponseConsistencyTest`（含 4xx/5xx 各種 case）+ `ContentNegotiationTest`（Accept xml → 406）|

---

## 5. Test Plan

### 5.1 自動化（gradlew test + MockMvc）

```java
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseConsistencyTest {

    @Test @DisplayName("AC-1: 403 走平台 shape")
    void forbiddenUsesPlatformShape() throws Exception {
        mvc.perform(get("/api/v1/skills/{id}", privateSkillId)
                .with(authentication(unauthorizedUser())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test @DisplayName("AC-6: Accept xml → 406 + JSON error")
    void xmlAcceptRejected() throws Exception {
        mvc.perform(get("/api/v1/skills").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").exists());
    }
}
```

### 5.2 手動 LAB

deploy 後：
```bash
# AC-1
curl -i 'https://.../api/v1/skills/00000000-0000-0000-0000-000000000000'
# expect: 403 + body 含 "error":"FORBIDDEN"，不含 "status":403, "path":...

# AC-6
curl -i -H 'Accept: application/xml' 'https://.../api/v1/skills'
# expect: 406 Not Acceptable，body application/json，平台 ErrorResponse

# AC-7
curl -s 'https://.../api/v1/skills' | jq 'keys'
# expect: ["content","empty","first","last","number","numberOfElements","pageable","size","sort","totalElements","totalPages"]
```

---

## 6. 風險與緩解

| 風險 | 緩解 |
|------|------|
| 既有 frontend 對 401/403 行為依賴 Spring shape | 同 commit ship frontend 確認 ApiError parse 對新 shape OK；既有 status code 仍正確 |
| 移除 XML converter 影響其他需要 XML 的功能（如 RSS / Atom） | 平台目前無 RSS / Atom；audit confirm；未來如需，per-endpoint `produces` annotation |
| Spring Security exception handler chain 順序衝突 | 用 SecurityConfig 顯式設 `authenticationEntryPoint` / `accessDeniedHandler` 在 chain 早於 default；test 多種 401/403 路徑 |
| `BasicErrorController` 替換可能影響 actuator | actuator endpoint 走自己 chain；本 spec 只動 `/error`；test actuator 仍 work |
| 500 fallback 訊息太籠統 user 無 actionable | 加 `correlation-id` 進 log，response 也回該 id；user 可附 id 給 support |

---

## 7. 與其他 spec 關係

- **S153**（Skill detail 404 UX）— frontend 接同 platform error shape；S153 + S162 同 commit ship 最順
- **S160**（CSRF / security headers）— CSRF reject 走 403；本 spec ship 後 CSRF 拒收訊息走 ErrorResponse 一致
- **S148**（GraalVM AOT reflection）— ErrorResponse record 已被廣泛使用；新加 fields 需 reflection hint（Spring AOT 自動 cover）
