# S204 — OAuth Login Error Page

Status: ⏳ QA recheck pending — V07b S203 last-page fix targeted PASS
Date: 2026-05-20
Owner: Codex planning
Size: S(5) initial

## 1. Goal

`/login/oauth2/code/skillshub?...` 失敗時，現在 Spring Security 直接把使用者導到 `/login?error`，畫面是英文 default UI：`Please sign in` / `Invalid credentials`。這不是 Skills Hub 的 React 頁，也沒有告訴使用者下一步要做什麼。

本 spec 要做的是：OAuth callback 失敗後，後端改導到 Skills Hub 自己的 `/auth/error` route；前端顯示繁中錯誤頁，只提供「返回瀏覽」讓使用者離開本次登入流程。同時後端 log 要記錄可排查的安全資訊，但頁面不能顯示 `code`、token、client secret、完整 exception message。

相依狀態：

- S139 Login UI + Lazy Auth Gate 已 shipped，提供 `AuthRedirectConfig`、`useAuth.login()` 與 OAuth2 Login chain。
- S152 SPA fallback 已 shipped，React 可承接未知 route；本 spec 仍需在 `App.tsx` 明確註冊 `/auth/error`，避免語意落到 generic 404。
- S162b API 401/403 body 統一已 cancelled/no-op；本 spec 不碰 API error body，只處理 browser login failure redirect。

非目標：

- 不修正 Google OAuth secret / Cloud Run revision 本身的部署問題。
- 不建立完整自訂登入頁；登入入口仍是 `/oauth2/authorization/skillshub`。
- 不把 Spring Security filter-chain 所有 401/403 都改成 React 頁。

## 2. Current State and Research

### 2.1 現場行為

使用者登入流程現在會看到：

```text
GET /oauth2/authorization/skillshub?returnTo=/browse
→ 302 https://accounts.google.com/...
→ GET /login/oauth2/code/skillshub?state=...&code=...
→ 302 /login?error
→ Spring default login page: Invalid credentials
```

Cloud Run log 只顯示 callback 302 到 `/login?error`，頁面沒有 Skills Hub 文案，也沒有回到 app 的按鈕。

### 2.2 Existing Implementation

| File | 現況 |
|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` | `props.security().oauth().login().enabled()` 時呼叫 `http.oauth2Login(...)`，目前只設定 authorization resolver + success handler。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java` | 儲存 `returnTo` 並在登入成功後 redirect；沒有 failure handler。 |
| `frontend/src/hooks/useAuth.ts` | `login(returnTo?)` 導到 `/oauth2/authorization/skillshub?returnTo=...`。 |
| `frontend/src/App.tsx` | 尚無 `/auth/error` route。 |
| `frontend/src/components/EmptyState.tsx` | 已有 `redirect` tone，可重用做錯誤頁。 |
| `docs/grimo/ui/DESIGN.md` | Page Inventory 需在實作時加入 `/auth/error`。 |

### 2.3 UI Design Source

S204 的 UI 必須以 `docs/grimo/ui/DESIGN.md` 為現行設計來源，不新增另一套錯誤頁視覺語言。

| DESIGN.md section | S204 constraint |
|---|---|
| Source of Truth rule | 新增 `/auth/error` route 時，同步更新 Page Inventory；frontend comments 引用 Page Inventory 或現有 prototype。 |
| Theming / colors | 使用既有 dark tokens：page background `#08080A`、card `#0F0F12`、text `#EEECEA` / `#A8A49C`。 |
| Components / EmptyState | 以 `EmptyState tone="redirect"` 作為主體，不新增 standalone error-card system。 |
| Beam rule | 只允許「返回瀏覽」這一個 primary CTA 使用 BeamFrame；錯誤頁不提供登入 CTA。 |
| Elevation | 不用 shadow；只用 tonal layers + hairline border。 |
| Page Inventory | 實作時加入 `AuthErrorPage`：route `/auth/error`、auth false、prototype derived from `Skills Hub Empty States.html` / current `EmptyState redirect` pattern、beam `["Back to browse CTA"]`。 |

### 2.4 Research Citations

| Source | Finding |
|---|---|
| Spring Security OAuth2 Login API — `OAuth2LoginConfigurer` 7.0.5: https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/config/annotation/web/configurers/oauth2/client/OAuth2LoginConfigurer.html | `oauth2Login()` 支援 `failureHandler` / `failureUrl`，OAuth2 Login chain 會安裝 `OAuth2AuthorizationRequestRedirectFilter` 與 `OAuth2LoginAuthenticationFilter`。 |
| Spring Security OAuth2 Login core config: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html | OAuth callback 預設 path 是 `{baseUrl}/login/oauth2/code/{registrationId}`；登入成功後會建立 authenticated session。 |
| Spring Security OAuth2 Login advanced config: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html | `oauth2-login` namespace 明列 `login-processing-url="/login/oauth2/code/*"` 與 `authentication-failure-handler-ref`；default login page 由 `DefaultLoginPageGeneratingFilter` 產生，client login link 預設是 `/oauth2/authorization/{registrationId}`。 |
| Spring Security Form Login docs: https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html | `error` query param 表示 authentication failed；若沒有自訂 login page，Spring Security 會產生 default login page。 |
| Spring Security source — `AbstractAuthenticationFilterConfigurer` 7.0.5: https://github.com/spring-projects/spring-security/blob/7.0.5/config/src/main/java/org/springframework/security/config/annotation/web/configurers/AbstractAuthenticationFilterConfigurer.java | `failureUrl(...)` 是 `failureHandler(new SimpleUrlAuthenticationFailureHandler(...))` 的 shortcut；default failure URL 是 `/login?error`；`configure(...)` 會把 failure handler 掛到 auth filter。 |
| Spring Security source — `OAuth2LoginAuthenticationFilter` 7.0.5: https://github.com/spring-projects/spring-security/blob/7.0.5/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/web/OAuth2LoginAuthenticationFilter.java | 此 filter 的 `DEFAULT_FILTER_PROCESSES_URI` 是 `/login/oauth2/code/*`；callback 會讀 `code/state`、移除 session 中的 authorization request，然後交給 `AuthenticationManager` 換 token/建立 authentication。 |
| Spring Security source — `AbstractAuthenticationProcessingFilter` 7.0.5: https://github.com/spring-projects/spring-security/blob/7.0.5/web/src/main/java/org/springframework/security/web/authentication/AbstractAuthenticationProcessingFilter.java | `attemptAuthentication(...)` 丟出 `AuthenticationException` 時會呼叫 `unsuccessfulAuthentication(...)`，由已設定的 `AuthenticationFailureHandler` 決定 browser response。 |
| Spring Security source — `SimpleUrlAuthenticationFailureHandler` 7.0.5: https://github.com/spring-projects/spring-security/blob/7.0.5/web/src/main/java/org/springframework/security/web/authentication/SimpleUrlAuthenticationFailureHandler.java | 設定 failure URL 時預設使用 redirect；可改成 forward，但 forward 會保留 callback URL context，不適合把 `code` 留在瀏覽器目前位置。 |
| Spring Boot Servlet static content docs: https://docs.spring.io/spring-boot/reference/web/servlet.html | Boot 從 classpath `/static` 等位置服務靜態檔，並支援 `index.html` welcome page；本專案已用 `SpaFallbackController` 額外把 extensionless SPA deep link forward 到 `/index.html`。 |
| Cloud Run secrets docs: https://cloud.google.com/run/docs/configuring/services/secrets | Secret 作為 environment variable 時是在 instance startup resolve；這解釋部署問題，但本 spec 只把錯誤頁做可理解，不處理 secret rotation 流程。 |

### 2.5 Problem Shape

`/login?error` 是 Spring Security filter chain 產生的 browser response，不會進 React route。只做前端 route 無法攔到這個頁面；必須先在後端 OAuth2 Login failure path 改 redirect 目的地。

### 2.6 Spring `/auth/error` and React Route Integration

結論：`/auth/error` 應該是 React route，不需要新增 Spring MVC page controller。Spring 只負責在 OAuth2 Login failure handler 裡送 302 redirect 到 `/auth/error?...`；瀏覽器重新 GET `/auth/error` 後，既有 `SpaFallbackController` 會 forward 到 `/index.html`，再由 React Router render `AuthErrorPage`。

Actual request chain:

```text
GET /oauth2/authorization/skillshub?returnTo=/publish
→ OAuth2AuthorizationRequestRedirectFilter stores request/state in session
→ 302 Google
→ GET /login/oauth2/code/skillshub?state=...&code=...
→ OAuth2LoginAuthenticationFilter attempts authentication
→ AuthenticationException
→ AbstractAuthenticationProcessingFilter.unsuccessfulAuthentication(...)
→ AuthRedirectConfig oauthFailureHandler:
   302 /auth/error?reason=token_exchange_failed
→ GET /auth/error?reason=token_exchange_failed
→ SpaFallbackController returns forward:/index.html
→ React Router renders <AuthErrorPage />
```

Routing ownership:

| Path | Owner | Why |
|---|---|---|
| `/oauth2/authorization/skillshub` | Spring Security | Starts OAuth2 authorization request; frontend only links to it. |
| `/login/oauth2/code/skillshub` | Spring Security | OAuth provider callback; React must not handle this path. |
| `/auth/error` | React Router | First-party recovery page; backend serves SPA shell through `SpaFallbackController`. |
| `/api/**` | Spring MVC API | Existing `SpaFallbackController` returns 404 for unknown API paths, not SPA shell. |

Redirect vs forward:

| Option | Browser URL after failure | Result |
|---|---|---|
| `failureUrl("/auth/error")` | `/auth/error` | Works as minimum fix, but no safe reason mapping or structured log classification. |
| Custom `AuthenticationFailureHandler` + `sendRedirect("/auth/error?...")` | `/auth/error?reason=...` | Chosen; clears Google callback URL from the address bar and lets React read safe enum query params. |
| Custom failure handler + server forward | likely still displays `/login/oauth2/code/skillshub?...` | Avoid; callback URL may contain `code`, and React would render under the wrong browser location. |

## 3. Acceptance Criteria

### AC-S204-1 — OAuth callback failure redirects to Skills Hub page

Given 使用者完成 Google login 後回到 `/login/oauth2/code/skillshub`
When Spring Security OAuth2 Login authentication fails
Then response redirects to `/auth/error?reason=<safe-code>`
And 不再導到 `/login?error`
And redirect URL 不包含 `code`、token、client secret、完整 exception message

### AC-S204-2 — Error page renders without backend context

Given 使用者直訪 `/auth/error`
When query string 沒有 `reason`
Then React 顯示 `登入沒有完成`
And 只顯示「返回瀏覽」互動按鈕
And 頁首只顯示 `Skills Hub` 品牌，不顯示導覽連結
And 不顯示頁首右上角登入按鈕
And 底部可用低調文字顯示 safe error code
And 不顯示英文 `Invalid credentials`

### AC-S204-3 — Token exchange failure gives actionable copy

Given URL 是 `/auth/error?reason=token_exchange_failed`
When page renders
Then headline 仍是 `登入沒有完成`
And sub text 說明「Google 已回到 Skills Hub，但後端沒有完成認證」
And 顯示下一步「返回瀏覽」
And 顯示補充提示「若剛更新 OAuth 設定，請確認 Cloud Run 已開新 revision」

### AC-S204-4 — Provider denial gives consent copy

Given URL 是 `/auth/error?reason=access_denied`
When page renders
Then sub text 說明「Google 沒有授權這次登入」
And 顯示下一步「返回瀏覽」
And 不顯示 Cloud Run revision 提示

### AC-S204-5 — Session/state failure gives recovery copy

Given URL 是 `/auth/error?reason=session_expired`
When page renders
Then sub text 說明「這次登入流程已失效，請回到瀏覽頁面後再重新開始」
And primary action 導向 `/browse`

### AC-S204-6 — Backend log is useful but safe

Given OAuth callback failure occurred
When backend failure handler runs
Then Cloud Run log contains `OAuth login failed`
And log key-values include `oauthErrorCode`, `exceptionClass`, `path`, `method`, `returnToPath`
And log 不包含 OAuth `code` query value、access token、id token、client secret
And `returnToPath` 不包含 query string

### AC-S204-7 — Existing auth success path is unchanged

Given OAuth login succeeds
When user returns from Google
Then `AuthRedirectConfig` success handler still redirects to stored `returnTo`
And `/api/v1/me` still returns authenticated user data

### AC-S204-8 — UI route inventory is updated

Given `/auth/error` route is added
When implementation is complete
Then `docs/grimo/ui/DESIGN.md` Page Inventory records `/auth/error` as the OAuth login failure page
And `frontend/src/App.tsx` has an explicit route before `*`

### AC-S204-9 — Backend SPA fallback serves the React error route

Given browser requests `/auth/error?reason=oauth_failed` directly
When Spring MVC handles the request
Then `SpaFallbackController` forwards to `/index.html`
And `/api/auth/error` still returns 404 instead of the SPA shell

## 4. Design

### 4.1 Recommended Approach

Use a backend `AuthenticationFailureHandler` for OAuth2 Login failures, then render a React route.

| Approach | Files | Runtime behavior | Cost / risk |
|---|---|---|---|
| A. Frontend-only `/auth/error` | `App.tsx`, new page | `/login?error` still served by Spring; React route only works when user manually opens it | Not enough; does not fix actual path |
| B. `oauth2Login().failureUrl("/auth/error")` | `SecurityConfig.java` | Failure redirects to React route | Small, but cannot classify/log safely without extra handler |
| C. Custom `AuthenticationFailureHandler` + React page | `AuthRedirectConfig.java`, `SecurityConfig.java`, `AuthErrorPage.tsx`, tests | Failure maps exception to safe reason, logs safe details, redirects to first-party page | Recommended; slightly more code, best diagnostics |

Chosen: C.

### 4.2 Backend Contract

Add one bean in `AuthRedirectConfig`, gated by existing `skillshub.security.oauth.login.enabled=true`:

```java
@Bean
AuthenticationFailureHandler oauthFailureHandler() {
    return (request, response, exception) -> {
        // map exception -> safe reason
        // log safe key-values; include returnToPath only, not full returnTo query
        // remove returnTo from session
        // redirect /auth/error?reason=...
    };
}
```

`SecurityConfig.filterChain(...)` should accept:

```java
ObjectProvider<AuthenticationFailureHandler> oauthFailureHandlerProvider
```

and wire it inside the existing `http.oauth2Login(login -> { ... })` branch:

```java
if (failureHandler != null) {
    login.failureHandler(failureHandler);
}
```

Reason mapping:

| Raw condition | Safe reason | User copy |
|---|---|---|
| Missing / invalid stored authorization request, state mismatch | `session_expired` | 這次登入流程已失效，請回到瀏覽頁面後再重新開始。 |
| OAuth provider returned `access_denied` | `access_denied` | Google 沒有授權這次登入。請回到瀏覽頁面後再重新開始。 |
| Token endpoint / client authentication / provider error | `token_exchange_failed` | Google 已回到 Skills Hub，但後端沒有完成認證。 |
| Unknown exception | `oauth_failed` | 登入沒有完成，請回到瀏覽頁面後再重新開始。 |

Implementation note: do not put `exception.getMessage()` in query params. Log it only if it is known not to contain OAuth code/token; safer default is log class + OAuth error code only.

Failure handler should not include `returnTo` in the `/auth/error` redirect URL because the error page does not retry login. It may log only the path portion as `returnToPath`. Example: stored `returnTo=/publish?draftToken=abc` redirects to `/auth/error?reason=...`, while the log records `returnToPath=/publish`.

### 4.3 Frontend Contract

Add:

```text
frontend/src/pages/AuthErrorPage.tsx
frontend/src/pages/AuthErrorPage.test.tsx
```

Route:

```tsx
<Route path="/auth/error" element={<AuthErrorPage />} />
```

Page uses `AppShell` + `EmptyState tone="redirect"`, with a minimal header:

```text
登入沒有完成
Google 已回到 Skills Hub，但後端沒有完成認證。請先回到瀏覽頁面。

[返回瀏覽]

錯誤代碼：token_exchange_failed
若剛更新 OAuth 設定，請確認 Cloud Run 已開新 revision。
```

The error code is okay to show when it is one of the safe enum values. Never show raw backend message, OAuth code, or provider token response.

Recovery behavior:

| Error page URL | Primary action target |
|---|---|
| `/auth/error?reason=token_exchange_failed` | `/browse` |
| `/auth/error?reason=oauth_failed` | `/browse` |
| `/auth/error?reason=session_expired` | `/browse` |

Implementation note: `AuthErrorPage` must not call `login()` or render a login button. The page exits the failed login flow; users can start login again only from a normal app page that needs login.

Design constraints from `docs/grimo/ui/DESIGN.md`:

- Wrap page content in `AppShell`, but use a minimal header on `/auth/error`: show only the `Skills Hub` brand, hide nav links, hide notification bell, hide the header auth area. Do not create a bare full-screen Spring-like login page.
- Use `EmptyState tone="redirect"` so layout, spacing, border, and CTA styling match existing no-result / redirect states.
- Keep copy zh-TW and product-chrome practical: no marketing copy, no English `Invalid credentials`.
- Use existing primary action behavior: the only CTA is「返回瀏覽」and it uses existing `EmptyState.primaryAction` / BeamFrame.
- Show safe reason as low-emphasis troubleshooting text only when it is one of `session_expired`, `access_denied`, `token_exchange_failed`, `oauth_failed`; otherwise normalize to `oauth_failed`.
- Do not introduce new red/amber severity palette for OAuth login failure. This page is a navigation/recovery state, not a risk/security finding.
- Mobile layout follows existing `EmptyState` responsive behavior; do not add custom viewport-specific CSS unless a screenshot shows text overflow.

### 4.4 Low-Fidelity UI Sketch

Desktop:

```text
┌─────────────────────────────────────────────────────────────┐
│ Skills Hub                                                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│        ┌───────────────────────────────────────────┐        │
│        │ 查詢 · token_exchange_failed              │        │
│        │                                           │        │
│        │ 登入沒有完成                              │        │
│        │ Google 已回到 Skills Hub，但後端沒有完成  │        │
│        │ 認證。請先回到瀏覽頁面。                  │        │
│        │                                           │        │
│        │ [返回瀏覽]                                │        │
│        │                                           │        │
│        │ 你可以這樣做                              │        │
│        │ → 回到技能瀏覽                            │        │
│        │ → 若剛更新 OAuth 設定，確認 Cloud Run     │        │
│        │   已開新 revision                         │        │
│        └───────────────────────────────────────────┘        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

Mobile:

```text
┌─────────────────────────────┐
│ Skills Hub                  │
├─────────────────────────────┤
│ ┌─────────────────────────┐ │
│ │ 登入沒有完成            │ │
│ │ 這次登入流程已失效，    │ │
│ │ 請回到瀏覽頁面。        │ │
│ │                         │ │
│ │ [返回瀏覽]              │ │
│ │                         │ │
│ │ 錯誤代碼：session_expired│ │
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

### 4.5 File Plan

Production files:

| File | Change |
|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AuthRedirectConfig.java` | Add safe failure handler bean, reason mapper, returnTo cleanup, and failure redirect without returnTo. |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` | Wire failure handler into `oauth2Login()`. |
| `frontend/src/App.tsx` | Add `/auth/error` route. |
| `frontend/src/components/AppShell.tsx` | Add a page-level minimal header option for `/auth/error`: brand only, no nav, no bell, no auth area. |
| `frontend/src/pages/AuthErrorPage.tsx` | New page with `AppShell` + `EmptyState redirect`. |
| `docs/grimo/ui/DESIGN.md` | Add route to Page Inventory. |

Test files:

| File | Coverage |
|---|---|
| `backend/src/test/java/io/github/samzhu/skillshub/shared/security/AuthRedirectTest.java` | Safe reason mapping, safe returnTo log path, failure redirect does not leak raw code/message/returnTo. |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/security/OAuthFailureHandlerTest.java` or same-package unit | Direct failure handler test for OAuth2AuthenticationException and generic exception. |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java` | Add `/auth/error?reason=oauth_failed` direct-hit coverage and `/api/auth/error` 404 guard. |
| `frontend/src/pages/AuthErrorPage.test.tsx` | Reason-specific copy, CTA targets, no raw query leak. |
| `frontend/src/components/AppShell.test.tsx` | Minimal header hides nav/bell/auth for the OAuth error page while normal pages still render them. |
| `frontend/src/App.test.tsx` | `/auth/error` route renders page, `*` route still NotFound. |

## 5. NFR Sweep and Task Boundary Hints

### 5.1 NFRs

| Category | Requirement |
|---|---|
| Performance | N/A — static React page + single redirect; no API call needed. |
| Security | Query params and UI must contain only safe enum values. No OAuth `code`, tokens, client secret, raw exception message. |
| Reliability | Missing session / missing query / unknown exception all render a usable page with a browse action. |
| Usability | Page must be zh-TW, first viewport shows headline + next action. User never sees Spring default `Please sign in`. |
| Maintainability | Reason mapping lives in one helper and has unit tests; adding a new reason is one enum/copy map change. |

### 5.2 Suggested Task Split

1. Backend failure handler:
   - Modify `AuthRedirectConfig` and `SecurityConfig`.
   - Tests: failure redirects to `/auth/error`, logs safe code, does not leak `code`.

2. Frontend error page:
   - Add `AuthErrorPage`, route, unit tests.
   - Reuse `EmptyState redirect`; do not call `useAuth.login()` from the error page.

3. Design/docs sync:
   - Update `docs/grimo/ui/DESIGN.md` Page Inventory.
   - Add short note to debugging playbook: `/auth/error?reason=token_exchange_failed` means Google callback reached backend but OAuth2 authentication failed.

### 5.3 AC Well-Formedness Check

- Singular: each AC checks one observable behavior.
- Unambiguous: reasons are finite safe strings.
- Implementation-free: user-facing ACs describe visible page and redirects; backend file plan records implementation.
- Verifiable: backend unit tests, frontend Vitest, optional Cloud Run curl.
- Bounded: only OAuth browser login failure path, not all auth/API errors.

### 5.4 Open Questions

1. 是否要在 `/login?error` 也加 SPA fallback？Recommended: 不做。正確做法是後端 failure handler 不再導向 `/login?error`。

## 6. Task Plan

POC: not required — S204 沒有新增 dependency，也沒有包未知 SDK。Pre-flight 已核對本專案現有 `AuthRedirectConfig` / `SecurityConfig` / `SpaFallbackController` / `AppShell` 架構；官方 Spring Security `OAuth2LoginConfigurer` 文件確認 `oauth2Login()` 可掛 `failureHandler`，既有 `SpaFallbackController` 已能 forward extensionless React route。

### 6.1 Pre-Flight Findings

- PRD 對齊：本 spec 改善 OAuth login failure 的可理解性，不新增認證/授權限制；符合 MVP 階段 feature-first 原則。
- 現有 code 對齊：`AuthRedirectConfig` 已管理 `returnTo` session 與 success handler；failure handler 放同一個 config 內，讓成功/失敗都能清理同一個 session attribute。
- UI source of truth 對齊：`docs/grimo/ui/DESIGN.md` 要新增 `/auth/error` Page Inventory；頁面重用 `EmptyState tone="redirect"`，不新增錯誤頁設計系統。
- E2E 評估：S204 是 OAuth provider callback failure 的 browser route；unit/component tests 可覆蓋 safe redirect、copy、SPA fallback。若後續 QA 要驗真組裝，可在 V07 mock OAuth server 補 failure path，但本輪 task 不先新增 Playwright spec。

### 6.2 Task Index

| Task | File | AC | Status | Notes |
|---|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-20-S204-T01-backend-oauth-failure-handler.md` | AC-S204-1, AC-S204-6, AC-S204-7 | PASS | `AuthRedirectConfig.oauthFailureHandler()` 會 redirect `/auth/error?reason=...`、清 session returnTo、記 safe structured log；`SecurityConfig` 已接 `login.failureHandler(...)`。 |
| T02 | `docs/grimo/tasks/2026-05-20-S204-T02-frontend-auth-error-page.md` | AC-S204-2, AC-S204-3, AC-S204-4, AC-S204-5 | PASS | `AuthErrorPage` 已接 `/auth/error`，只顯示 safe enum reason、繁中 recovery copy、`返回瀏覽` CTA；`AppShell minimalHeader` 隱藏 nav/bell/auth area。 |
| T03 | `docs/grimo/tasks/2026-05-20-S204-T03-route-fallback-and-doc-sync.md` | AC-S204-8, AC-S204-9 | PASS | `SpaFallbackControllerTest` 已鎖 `/auth/error?reason=oauth_failed` forward 到 `/index.html`，`/api/auth/error` 維持 404；DESIGN/debugging docs 已同步。 |

### 6.3 Execution Order

1. T01 backend failure handler 先做，因為它把實際 `/login?error` runtime path 改成 `/auth/error?reason=...`。
2. T02 frontend page 接住 T01 的 safe reason enum，讓使用者看見繁中頁面與「返回瀏覽」。
3. T03 補 route/fallback/docs，避免後續 Page Inventory 與 route 行為漂移。

### 6.4 Verification Commands

- T01: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.security.AuthRedirectTest`
- T02: `cd frontend && npm test -- AuthErrorPage.test.tsx AppShell.test.tsx App.test.tsx`
- T03: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.SpaFallbackControllerTest && cd ../frontend && npm test -- App.test.tsx`

## 7. Implementation Results

### 7.1 S204 Task Status

All implementation tasks are PASS. Next workflow step is `$verifying-quality S204`; this spec is not ready for `$shipping-release` until independent QA records PASS evidence.

### 7.2 T03 Route Fallback and Docs Sync

- RED: `rg -n "AC-S204-9|AuthErrorPage|/auth/error|token_exchange_failed" backend/src/test/java/io/github/samzhu/skillshub/shared/api/SpaFallbackControllerTest.java docs/grimo/ui/DESIGN.md docs/grimo/debugging-playbook.md` returned no matches before T03, so the AC-S204-9 backend assertions and docs markers were absent.
- GREEN: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.SpaFallbackControllerTest && cd ../frontend && npm test -- App.test.tsx` passed; backend JUnit XML reports `tests="10"` with `failures="0"` and `errors="0"`; frontend Vitest reports `1 passed (1)` test file and `6 passed (6)` tests.
- Runtime behavior now recorded in tests/docs: `/auth/error?reason=oauth_failed` forwards to `/index.html`, `/api/auth/error` returns 404, Page Inventory lists `AuthErrorPage`, and the debugging playbook explains `token_exchange_failed`.

### 7.3 QA Review Attempt — 2026-05-20

Verdict: REJECT-FIX — S204 is still not ready for `$shipping-release`.

| Layer | Result | Evidence |
|---|---|---|
| S204 coverage include | FIXED | `frontend/vite.config.ts` now includes `src/components/AppShell.tsx` and `src/pages/AuthErrorPage.tsx`, so S204 frontend files are measured by the existing 80% frontend coverage gate. |
| Frontend coverage gate | PASS | `cd frontend && npm test -- --coverage` passed: 84 test files / 530 tests; aggregate line coverage 94.87%. `coverage-summary.json` reported `src/pages/AuthErrorPage.tsx` at 100% lines and `src/components/AppShell.tsx` at 91.3% lines. |
| Release verification | FAIL | `./scripts/verify-release.sh` wrote `verify-release.log`; summary: `V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V07b=FAIL V07c=PASS V07d=SKIP V08a=PASS V08b=PASS V09=PASS`; verdict: `FAIL - 1 CRITICAL failure(s); exit=1`. |
| Failing browser checks | FAIL | V07b failed 3 Playwright specs unrelated to `/auth/error`: `S176-explicit-publish-skill-name.spec.ts` timed out waiting for `/publish/(validate|review)?id=...`; `S187-skill-edit-page.spec.ts` and `S195-skill-edit-upload-validation-ux.spec.ts` timed out waiting for `edit-skill-btn`. |
| Testability gate | CLEAR | S204 ACs have direct backend/frontend tests, and the repository can run the required QA gates. The release blocker is a failing required browser gate, not missing verification tooling. |

Next workflow step remains `$verifying-quality S204` after the V07b failures are fixed or proven flaky with repeatable evidence. Do not route to `$shipping-release S204` until `./scripts/verify-release.sh` returns exit 0.

### 7.4 QA Fix — V07b Authenticated Browser Specs

Verdict: PARTIAL PASS — the known V07b failures are fixed; S204 still needs a full `./scripts/verify-release.sh` pass before shipping.

Root cause:

- `e2e/tests/S176-explicit-publish-skill-name.spec.ts`, `e2e/tests/S187-skill-edit-page.spec.ts`, and `e2e/tests/S195-skill-edit-upload-validation-ux.spec.ts` were running anonymous browser contexts even though the production-image app now enables real OAuth.
- Anonymous `/publish` redirects into login instead of uploading, so S176 timed out before `/publish/validate`.
- Anonymous skill detail pages do not expose owner-only `edit-skill-btn`, so S187/S195 timed out waiting for the edit button.
- After adding `test.use({ storageState: authState('developer') })`, S176/S187 advanced to stable page-state assertions; S176 was updated to match the current review-page copy `已成功發佈`, and S187 no longer asserts a transient scan text that can disappear before Playwright observes it.

Evidence:

- `cd e2e && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --project=chromium --grep "@S176|@S187|@S195"` passed: 9 tests / 0 failed. The run built `skillshub:e2e-local`, started Compose, saved developer/viewer/admin auth states, and passed S176/S187/S195 plus fixture setup/teardown.

Next workflow step remains `$verifying-quality S204`: rerun `./scripts/verify-release.sh`; if it returns exit 0, update this spec to QA PASS and route the following tick to `$shipping-release S204`.

### 7.5 QA Recheck — 2026-05-21

Verdict: REJECT-FIX — S204 is still not ready for `$shipping-release`.

Full release gate evidence:

- `./scripts/verify-release.sh` wrote `verify-release.log`.
- Summary: `V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V07b=FAIL V07c=PASS V07d=SKIP V08a=PASS V08b=PASS V09=PASS`.
- Verdict line: `FAIL - 1 CRITICAL failure(s); exit=1`.
- V02 reported backend LINE coverage `87.7% (covered=4834 / total=5514)`.

V07b failure:

- `e2e/tests/S203-semantic-masonry-pagination.spec.ts` failed at line 64 while waiting for `已載入 11-19 個相關技能`.
- The same test had already verified page 0 returned 10 cards and page 1 returned more than 0 cards. When page 1 returns 10 cards, the actual UI copy is `已載入 20 個相關技能`, so the old `1[1-9]` assertion was too narrow for valid fixture data.
- This failure is not an S204 product behavior failure: S204 backend/frontend AC tests and the release gate's V01-V07/V07c-V09 checks passed. It is still a release blocker because V07b is a required browser gate.

Fix applied in this QA tick:

- `e2e/tests/S203-semantic-masonry-pagination.spec.ts` now stores `firstPage.content.length + secondPage.content.length` and waits for that exact loaded-card count in the UI copy.

Targeted evidence after the fix:

- `cd e2e && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --project=chromium --grep @S203` passed: 7 tests / 0 failed. The run built `skillshub:e2e-local`, started Compose, created fixture/auth state, passed the S203 browser spec, and tore down the fixture stack.

Next workflow step remains `$verifying-quality S204`: rerun the full `./scripts/verify-release.sh`; if it returns exit 0, update this spec to QA PASS and route the following tick to `$shipping-release S204`.

### 7.6 QA Recheck — 2026-05-21 second run

Verdict: REJECT-FIX — S204 is still not ready for `$shipping-release`.

Full release gate evidence:

- `./scripts/verify-release.sh` wrote `verify-release.log`.
- Summary: `V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V07b=FAIL V07c=PASS V07d=SKIP V08a=PASS V08b=PASS V09=PASS`.
- Verdict line: `FAIL - 1 CRITICAL failure(s); exit=1`.
- V02 reported backend LINE coverage `87.7% (covered=4836 / total=5514)`.

V07b failure:

- `e2e/tests/S203-semantic-masonry-pagination.spec.ts` failed at line 68 while waiting for `已顯示全部相關技能`.
- The test had loaded the first semantic page and at least one next page, but the fixture can still have another page after page 1. In that case the UI correctly keeps the load-more sentinel and should not show the all-loaded copy yet.
- This is not an S204 product behavior failure: S204 backend/frontend AC tests and release gate V01-V07/V07c-V09 passed. It is still a release blocker because V07b is a required browser gate.

Fix applied in this QA tick:

- `e2e/tests/S203-semantic-masonry-pagination.spec.ts` now follows the semantic API response: it keeps scrolling the sentinel until the returned Slice has `last=true`, then checks the final loaded count and all-loaded copy.

Targeted evidence after the fix:

- `cd e2e && SKILLSHUB_E2E_SEMANTIC_FIXTURES=true npx playwright test --project=chromium --grep @S203` passed: 7 tests / 0 failed. The run built `skillshub:e2e-local`, started Compose, created fixture/auth state, passed the S203 browser spec, and tore down the fixture stack.

Next workflow step remains `$verifying-quality S204`: rerun the full `./scripts/verify-release.sh`; if it returns exit 0, update this spec to QA PASS and route the following tick to `$shipping-release S204`.
