# S162b: API consistency — 401/403 走平台 ErrorResponse shape

> Spec: S162b | Size: S(5) | Status: ⛔ cancelled / no-op（2026-05-16 查證後關閉）
> Date: 2026-05-09
> Origin: 拆自 S162 META — API consistency 補強；S162 v4.34/v4.35 ship 415/500，AC-1/2 (401/403) 拆出此 spec

---

## 0. Closure Decision（2026-05-16）

**結論：不實作，關閉本 spec。**

`frontend/src/api/auth.ts:30` 的登入探測只看 HTTP status：`GET /api/v1/me` 回 401 時直接 `return null`，前端不需要讀 401 body 裡的 `error` code 或 message 來呈現 UI。未登入狀態目前由 AppShell / MySkills / Notifications 等頁面顯示「登入」或空狀態，不依賴 `ErrorResponse`。

`backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java:194` 的受保護路徑與 Spring Security filter chain 確實會讓部分 401/403 不走 `GlobalExceptionHandler`，所以原始技術觀察成立；但目前沒有產品需求要在 401 body 呈現額外資訊，也沒有前端流程消費 `AUTHENTICATION_REQUIRED` / `PERMISSION_DENIED` 這組新 code。

`backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java:632` 已處理 controller / service 內丟出的 `AccessDeniedException`，並回現有平台 code：`UNAUTHORIZED` / `ACCESS_DENIED`。若未來真的需要統一 Security filter chain 的 JSON body，應另開小 spec，沿用現有 `UNAUTHORIZED` / `ACCESS_DENIED`，不要導入本 spec 草案裡的 `AUTHENTICATION_REQUIRED` / `PERMISSION_DENIED`。

查證指令：

```bash
cd backend && ./gradlew test -x processTestAot \
  --tests 'io.github.samzhu.skillshub.shared.security.MeControllerTest.me_withoutJwt_returns401' \
  --tests 'io.github.samzhu.skillshub.shared.security.AdminControllerTest.adminEcho_withViewerJwt_returns403' \
  --tests 'io.github.samzhu.skillshub.community.CommentControllerTest.delete_nonAuthor_returns403' \
  --tests 'io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest.accessDeniedFromAuthenticatedReturns403'
```

結果：`BUILD SUCCESSFUL`。不加 `-x processTestAot` 時失敗在既有 generated AOT 檔名衝突（`SkillshubApplication__TestContext009_BeanDefinitions.java already exists`），不是 S162b 行為本身。

---

## 1. Goal

**一句話：** OAuth2 ResourceServer 預設的 401/403 response 格式（裸 string body）改走 `ErrorResponse` JSON shape — 對齊既有 4xx/5xx 統一格式。

**為什麼重要：**
- 前端 401/403 分支現在拿不到 `error` code，只能字串 match — 脆弱
- 跟既有 `ErrorResponse{ error, message, timestamp }` 不一致 — frontend i18n / monitoring 雙倍邏輯
- API contract test 寫不下（每個 controller 401 case 要 hardcode 不同 raw string）

**非目標：**
- 不改 OAuth2 認證機制（JWT 解析、claim binding 等不動）
- 不改 SecurityConfig 的 endpoint 規則（哪些 path 需要 auth 等不動）

---

## 2. Approach

### 2.1 現況

`SecurityConfig` 走 OAuth2 Resource Server 預設 chain：
- Auth fail（無 token / token 過期 / signature 錯）→ `BearerTokenAuthenticationEntryPoint` 預設 401 + `WWW-Authenticate: Bearer error="invalid_token"` header + 空/裸 body
- Authz fail（@PreAuthorize / hasRole 沒過）→ `AccessDeniedHandlerImpl` 預設 403 + 空/裸 body

GlobalExceptionHandler 對非 Spring Security 拋出的 `AccessDeniedException` 也有 handler，但 SecurityConfig chain 內 throw 的不會走到它（filter chain 提早 commit response）。

### 2.2 設計

加 2 個 bean + SecurityConfig wire：

```java
@Component
class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest req, HttpServletResponse resp,
                         AuthenticationException ex) throws IOException {
        resp.setStatus(401);
        resp.setContentType("application/json");
        ErrorResponse body = new ErrorResponse(
            "AUTHENTICATION_REQUIRED",
            ex.getMessage(),
            Instant.now()
        );
        objectMapper.writeValue(resp.getWriter(), body);
    }
}

@Component
class JsonAccessDeniedHandler implements AccessDeniedHandler {
    @Override
    public void handle(HttpServletRequest req, HttpServletResponse resp,
                       AccessDeniedException ex) throws IOException {
        resp.setStatus(403);
        resp.setContentType("application/json");
        ErrorResponse body = new ErrorResponse(
            "PERMISSION_DENIED",
            ex.getMessage(),
            Instant.now()
        );
        objectMapper.writeValue(resp.getWriter(), body);
    }
}

// SecurityConfig
http.exceptionHandling(eh -> eh
    .authenticationEntryPoint(jsonAuthenticationEntryPoint)
    .accessDeniedHandler(jsonAccessDeniedHandler)
);
```

ErrorResponse 沿用既有 `shared/api/ErrorResponse.java`（per dev-standards §API Standards）。

### 2.3 Decision points

- Error code naming — 沿 S131 ship 的 SCREAMING_SNAKE_CASE：`AUTHENTICATION_REQUIRED` / `PERMISSION_DENIED`（對齊 GlobalExceptionHandler 風格）
- 401 是否仍帶 `WWW-Authenticate` header：**保留**（OAuth2 RFC 6750 規範；前端可選不用，但移除違反規範）
- LAB mode（`oauth.enabled=false`）行為：仍走同 entry point（LAB 不應該觸發 401，但加防禦性）

---

## 3. Acceptance Criteria

```
AC-1: 401 走 ErrorResponse JSON shape
  Given 受保護的 endpoint（如 GET /api/v1/me）+ 無 Authorization header
  When 請求發出
  Then HTTP 401
  And response body = JSON { "error": "AUTHENTICATION_REQUIRED", "message": "...", "timestamp": "..." }
  And content-type = application/json
  And WWW-Authenticate header 仍含 "Bearer error=..." (RFC 6750)

AC-2: 401 因 token 過期 / signature 錯
  Given 帶過期 JWT 或 invalid signature
  When 請求發出
  Then HTTP 401 + AUTHENTICATION_REQUIRED ErrorResponse
  And message 含 "expired" 或 "invalid" 提示（不洩漏 secret 細節）

AC-3: 403 走 ErrorResponse JSON shape
  Given Bob 已登入 + 嘗試操作 Alice 的 skill（PreAuthorize 沒過）
  When 請求發出（如 PUT /api/v1/skills/{alice-skill}/...）
  Then HTTP 403
  And response body = JSON { "error": "PERMISSION_DENIED", "message": "...", "timestamp": "..." }

AC-4: 既有 controller 拋 AccessDeniedException 也對齊（GlobalExceptionHandler path）
  Given controller 內 throw new AccessDeniedException
  When 走 GlobalExceptionHandler
  Then 同 AC-3 shape（PERMISSION_DENIED）

AC-5: LAB mode（oauth.enabled=false）下不影響
  Given application-lab.yaml oauth.enabled=false
  When 請求 /api/v1/me（LAB stub user injected）
  Then 200 + 預設 LAB user info（不觸發 401）

AC-6: 前端錯誤 i18n 可讀 error code
  Given frontend lib/api-error-messages.ts 已有 error code → message map
  When 401 / 403 response
  Then 前端拿 response.body.error 找對應 i18n message 顯示
  And 不需 fallback HTTP status code-only message
```

**驗證指令：** `cd backend && ./gradlew test`（含 `OAuth2EntryPointTest` + `AccessDeniedHandlerTest`）

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../shared/security/JsonAuthenticationEntryPoint.java` | **新增** — implements `AuthenticationEntryPoint` |
| `backend/src/main/java/.../shared/security/JsonAccessDeniedHandler.java` | **新增** — implements `AccessDeniedHandler` |
| `backend/src/main/java/.../shared/security/SecurityConfig.java` | wire 兩個 bean 進 `http.exceptionHandling(...)` |
| `backend/src/test/java/.../shared/security/OAuth2EntryPointTest.java` | **新增** — `@WebMvcTest` slice 4 case（無 token / 過期 / invalid sig / 正常）|
| `backend/src/test/java/.../shared/security/AccessDeniedHandlerTest.java` | **新增** — controller throw AccessDenied → ErrorResponse |
| 既有 `*ControllerTest` 跑 401 / 403 case 的 | sweep 改 assert ErrorResponse JSON shape（取代 raw string assertion）|

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/api-error-messages.ts` | 加 `AUTHENTICATION_REQUIRED` / `PERMISSION_DENIED` i18n entry |
| `frontend/src/api/client.ts`（fetch wrapper）| 401/403 從 ErrorResponse.error 拿 code（不再 fallback HTTP status text）|

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1, 2 | `OAuth2EntryPointTest` slice — 4 case JSON shape + WWW-Authenticate header |
| AC-3, 4 | `AccessDeniedHandlerTest` + 既有 controller @PreAuthorize fail case |
| AC-5 | `LabModeAuthTest` 已有測 LAB user injection；確認本 spec 不破 |
| AC-6 | `client.test.ts` mock 401 response 驗 frontend 拿 code |

### 5.2 手動 LAB 驗證

- [ ] curl `/api/v1/me` 不帶 token → 401 + JSON ErrorResponse
- [ ] curl 帶過期 token → 401 + JSON
- [ ] curl Alice 操作 Bob 的 resource → 403 + JSON

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| 既有 client（CLI tool / curl 腳本）依賴 raw string body | 不太可能 — 既有 raw string 是 Spring 預設，沒人 parse；但加 release note |
| 401 message 洩漏內部資訊（token id / signing key）| EntryPoint impl 用 `"Authentication required"` 通用 message；不直接 echo `ex.getMessage()` 若敏感 |
| 既有 test sweep 漏掉某 controller | sweep 透過 grep `andExpect(status().isUnauthorized())` 找 — sweep 後還剩 raw string assert 的逐個 update |
