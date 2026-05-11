# S160: Security Headers + CSRF — pre-production hardening 清單

> Spec: S160 | Size: M(8) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 實測 LAB env 缺 4 項標準 web security baseline：CSRF 關閉（POST 不需 token）、CSP missing、HSTS missing、Referrer-Policy missing。X-Frame-Options 與 X-Content-Type-Options 已設好。

---

## 1. Goal

把平台從「Feature First MVP」往「pre-production secure」過渡的 web security baseline 補齊：

1. **CSRF 重啟**（per-route opt-in；JWT bearer route exempt，session/cookie route enforce）
2. **CSP** 設 strict default-src + script-src self（漸進，先 report-only）
3. **HSTS** 加到 response（GCP Cloud Run 已 HTTPS-only，但 header 強制 client cache）
4. **Referrer-Policy** 設 `strict-origin-when-cross-origin`

**為什麼重要：**
- 現況：任何外站可透過 form POST 偷打 `POST /api/v1/requests` / `POST /skills/install` 等任意改變狀態的 endpoint，使用者只要登入過 LAB（cookie 還在）就被觸發
- LAB 公開可 access；任意 attacker 寫 phishing 頁 `<form action="https://lab.../api/v1/requests" method="post">` 可 spam 平台
- CSP 缺 → 任何 stored XSS（如 review markdown 沒 sanitize）會直接 exec script
- 雖然 CLAUDE.md「Feature First, Security Later」是 MVP 階段共識，但「Later」需明確時程；本 spec 即是這個 Later 的 plan

**非目標：**
- 不做 rate limiting / WAF（屬 ops layer，留另開 spec）
- 不做 audit log on auth failures（observability 層）
- 不做 secret scanning on uploads（屬 risk scanner scope）

---

## 2. Approach

### 2.1 現況實測

| Header / 行為 | LAB 現況 | 期望 |
|--------------|----------|------|
| CSP | `null` | `default-src 'self'; script-src 'self'; ...` |
| HSTS | `null` | `max-age=31536000; includeSubDomains` |
| Referrer-Policy | `null` | `strict-origin-when-cross-origin` |
| X-Frame-Options | `DENY` ✓ | 維持 |
| X-Content-Type-Options | `nosniff` ✓ | 維持 |
| CSRF on POST `/api/v1/requests` | 不需 token，**201 created** | 401 / 403 拒收（除非帶 X-CSRF-Token / 用 Bearer JWT） |
| Permissions-Policy | `null` | 設 default deny camera/mic/geolocation |

驗證指令：

```bash
curl -I https://skillshub-644359853825.asia-east1.run.app/
# 期望 response header 含 Content-Security-Policy / Strict-Transport-Security 等

curl -X POST https://...lab.../api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"title":"csrf-test","description":"x"}'
# 期望：401 或 403（Bearer/CSRF 缺）；現況：201 + 寫進 DB
```

### 2.2 CSRF 設計

**模式判定：**

Spring Security 預設行為：
- `@EnableWebSecurity` 啟用 CSRF for stateful（session-based）
- 純 stateless API（JWT Bearer in header） — CSRF 不必要（無 cookie auto-attached）

本平台 dual mode：
- **OAuth profile**: session-based + JWT bearer 都接（前端 SPA + CLI 並存）
- **LAB profile**: `LabSecurityFilter` mock 認證

**修法：**

```java
http.csrf(csrf -> csrf
    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
    // Bearer JWT 路徑 exempt（stateless，無 cookie auto-attach 風險）
    .ignoringRequestMatchers(
        new AntPathRequestMatcher("/api/v1/**", null /* any method */) {
            @Override public boolean matches(HttpServletRequest req) {
                String auth = req.getHeader("Authorization");
                return auth != null && auth.startsWith("Bearer ");
            }
        }
    )
);
```

Cookie-based session：
- Spring Security 寫 `XSRF-TOKEN` cookie（http-only=false 讓 JS 讀取）
- Frontend `apiFetch` client 對 mutation 方法（POST/PUT/DELETE/PATCH）read cookie value 並帶 `X-XSRF-TOKEN` header
- 後端 `XorCsrfTokenRequestHandler` / `SpaCsrfTokenRequestHandler` 比對

**LAB profile 處理：**
- LAB 用 `LabSecurityFilter` 注 mock principal；CSRF 仍套用（cookie 仍 issue）
- LAB 自動化測試：`MockMvc.with(csrf())` 或關 CSRF for LAB only（簡單 trade-off）

**選擇**：LAB enable CSRF（與 prod 一致）；E2E test 透過 `csrf()` post-processor 帶 token 走完整流程（best practice for parity）。

### 2.3 CSP 設計

漸進策略：

**Phase 1（report-only，本 spec）：**
```
Content-Security-Policy-Report-Only:
  default-src 'self';
  script-src 'self' 'unsafe-inline' 'unsafe-eval';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data: https://lh3.googleusercontent.com https://*.googleusercontent.com;
  font-src 'self' data:;
  connect-src 'self';
  frame-ancestors 'none';
  base-uri 'self';
  form-action 'self';
  report-uri /api/v1/csp-report;
```

`unsafe-inline` / `unsafe-eval` 暫留是因為：
- React + Vite build 可能含 inline styles
- shadcn/ui / Beam 預設 inline style attributes
- 完全 strict CSP 需重做 build（add nonce / hash）— 留 follow-up

**Phase 2（enforce + nonce-based，留未來 polish spec follow-up；非 S161/S162/S163/S164 — 那些 ID 已分配給 input sanitization / API consistency / owner mgmt）：**
- 拿掉 `unsafe-inline` / `unsafe-eval`
- 用 Vite plugin 為 inline script / style 加 nonce
- 改 `Content-Security-Policy`（非 Report-Only）正式 enforce

**googleusercontent.com**：OAuth user picture 來源（per S154），需 allowlist 否則 avatar 顯不出

### 2.4 HSTS

```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
```

GCP Cloud Run 預設 HTTPS-only（HTTP 自動 redirect to HTTPS），但**只在第一次訪問**前 client 還可能被中間人降級。HSTS 強制 client 1 年內**只走 HTTPS**，避免 TLS-strip MITM。

`preload` 留下次 spec 處理（需主動向 Chrome HSTS preload list 申請）。MVP `max-age=31536000; includeSubDomains` 即可。

### 2.5 Referrer-Policy

```
Referrer-Policy: strict-origin-when-cross-origin
```

意義：
- 同 origin navigation: 完整 URL referrer（含 path + query）— 內部分析需要
- 跨 origin navigation 同協議: 只送 origin（無 path / query）— 不洩漏 user 在哪頁
- 跨 protocol（HTTPS → HTTP）: 完全不送 referrer

對應隱私層：使用者點 SkillDetail 內 description 含的外連結（如 GitHub repo），不會洩漏「我在 lab.skillshub 看 skill X」資訊到第三方站。

### 2.6 Permissions-Policy

```
Permissions-Policy: camera=(), microphone=(), geolocation=(), interest-cohort=()
```

平台無相機 / 麥克風 / 定位需求；明確 deny 阻擋未來誤啟。`interest-cohort=()` 反 FLoC 隱私訊號。

### 2.7 Implementation Sketch

```java
@Bean
SecurityFilterChain securityChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .ignoringRequestMatchers(this::isBearerAuth))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp
                .policyDirectives(CSP_REPORT_ONLY)
                .reportOnly())
            .strictTransportSecurity(hsts -> hsts
                .maxAgeInSeconds(31_536_000)
                .includeSubDomains(true))
            .referrerPolicy(rp -> rp
                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .permissionsPolicy(pp -> pp
                .policy("camera=(), microphone=(), geolocation=(), interest-cohort=()"))
            // 既有 frame-options DENY / xss filter 維持
        )
        // ... 其他既有
        ;
    return http.build();
}

private boolean isBearerAuth(HttpServletRequest req) {
    String auth = req.getHeader("Authorization");
    return auth != null && auth.startsWith("Bearer ");
}
```

CSP report endpoint：

```java
@PostMapping("/api/v1/csp-report")
@ResponseStatus(HttpStatus.NO_CONTENT)
void cspReport(@RequestBody String report) {
    // 結構化 log；後續可送到 ELK / log analytics
    log.warn("CSP violation: {}", report);
}
```

---

## 3. Acceptance Criteria

```
AC-1: CSRF 對 cookie-session POST 拒收
  Given 未帶 Authorization Bearer 的 request
  When POST /api/v1/requests body={...}（無 X-XSRF-TOKEN 與 cookie XSRF-TOKEN 也無）
  Then 回 403 Forbidden
  And response 含 hint「CSRF token missing or invalid」

AC-2: Bearer JWT route exempt
  Given 帶 Authorization: Bearer <jwt> 的 request
  When POST /api/v1/requests
  Then 不要求 CSRF token；request 正常處理（行為與本 spec 前一致）

AC-3: SPA cookie-based session 流程
  Given 使用者透過 OAuth 登入後，前端 apiFetch client
  When 觸發 mutation（POST/PUT/DELETE/PATCH）
  Then client 自動讀 XSRF-TOKEN cookie 帶 X-XSRF-TOKEN header
  And request 通過 CSRF check

AC-4: CSP report-only 啟用
  Given 任何 GET response
  When 檢查 response header
  Then 含 Content-Security-Policy-Report-Only
  And policy 含 default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'
  And 含 form-action 'self'; frame-ancestors 'none'
  And 違規時前端 console 顯 CSP error 但不 block（report-only 性質）

AC-5: HSTS 一年強制
  Given 任何 HTTPS GET response
  When 檢查 header
  Then 含 Strict-Transport-Security: max-age=31536000; includeSubDomains

AC-6: Referrer-Policy 設為 strict-origin
  Given 任何 GET response
  When 檢查 header
  Then 含 Referrer-Policy: strict-origin-when-cross-origin

AC-7: Permissions-Policy deny 敏感 feature
  Given 任何 GET response
  When 檢查 header
  Then 含 Permissions-Policy: camera=() microphone=() geolocation=() interest-cohort=()

AC-8: CSP report endpoint 接收 violation
  Given 前端硬塞 inline script 違反 CSP
  When 瀏覽器發 violation report
  Then POST /api/v1/csp-report 回 204
  And backend log 含 violation detail

AC-9: 既有 X-Frame-Options / X-Content-Type-Options 不退化
  Given 任何 GET response
  When 檢查 header
  Then 仍含 X-Frame-Options: DENY 與 X-Content-Type-Options: nosniff
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SecurityHeadersTest` MockMvc + `CsrfFilterTest`）
- 手動 LAB：`curl -I /` 看所有 header；`curl -X POST /api/v1/requests` 預期 403

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../shared/security/SecurityConfig.java` | 加 CSRF + headers chain |
| `backend/src/main/java/.../shared/api/CspReportController.java` | 新增 — 接 violation reports |
| `backend/src/main/java/.../shared/security/LabSecurityFilter.java` | 確認 LAB profile 仍 issue XSRF cookie（與 prod parity）|
| `frontend/src/api/client.ts` | apiFetch mutation method 讀 XSRF-TOKEN cookie 加 X-XSRF-TOKEN header |
| `frontend/src/api/csrf.ts` | 新增 helper — `getCsrfToken()` 從 cookie 取 |
| **Tests** | `SecurityHeadersTest.java`、`CsrfFilterTest.java`、frontend `apiFetch.csrf.test.ts` |

---

## 5. Test Plan

### 5.1 自動化（gradlew test + Testcontainers）

```java
@Test @DisplayName("AC-1: cookie session POST 無 token → 403")
void cookieSessionPostWithoutCsrf403() throws Exception {
    mvc.perform(post("/api/v1/requests")
            .contentType(APPLICATION_JSON)
            .content("{\"title\":\"x\",\"description\":\"y\"}")
            .with(authentication(mockSessionAuth())))
        .andExpect(status().isForbidden());
}

@Test @DisplayName("AC-2: Bearer JWT POST 不要 token")
void bearerJwtPostBypassesCsrf() throws Exception {
    mvc.perform(post("/api/v1/requests")
            .contentType(APPLICATION_JSON)
            .header("Authorization", "Bearer " + validJwt)
            .content("{\"title\":\"x\",\"description\":\"y\"}"))
        .andExpect(status().isCreated());
}

@Test @DisplayName("AC-4: CSP header 出現")
void cspHeaderPresent() throws Exception {
    mvc.perform(get("/"))
        .andExpect(header().string("Content-Security-Policy-Report-Only",
            containsString("default-src 'self'")));
}

@Test @DisplayName("AC-5/6/7: HSTS / Referrer / Permissions header")
void allSecurityHeadersPresent() throws Exception {
    var response = mvc.perform(get("/")).andReturn().getResponse();
    assertThat(response.getHeader("Strict-Transport-Security"))
        .startsWith("max-age=31536000");
    assertThat(response.getHeader("Referrer-Policy"))
        .isEqualTo("strict-origin-when-cross-origin");
    assertThat(response.getHeader("Permissions-Policy"))
        .contains("camera=()");
}
```

### 5.2 手動 LAB

deploy 後：
- [ ] `curl -I https://lab.../` 看所有 header
- [ ] `curl -X POST .../api/v1/requests -d '{"title":"x"}'` → 403（不再 201）
- [ ] DevTools console 看 CSP report-only violation
- [ ] 點某 SkillDetail description 中外連結 → 對方站 server log 看 referrer header（應只 origin 不含 path）

---

## 6. 風險與緩解

| 風險 | 緩解 |
|------|------|
| CSRF 啟用後既有 frontend mutation 全壞 | 與本 spec 同 commit 改 frontend apiFetch，不能跳；test 先 ship，行為驗證再 ship security |
| CSP 太嚴 → 整站白畫面 | 用 Report-Only 啟用 phase 1；觀察 violation log 1–2 週再 enforce |
| `unsafe-inline` / `unsafe-eval` 留著對 XSS 防禦弱 | 標記為 Phase 2 follow-up（S161）；MVP 先有比沒有好 |
| HSTS preload 不可 reverse | 不上 preload；只設 max-age 1 年；想撤可以下個版本減 max-age |
| LAB 自動化測試破 | LAB E2E test 走 `WebTestClient` / `MockMvc` `csrf()` post-processor；Playwright E2E 透過 cookie + token 走完整 flow |

---

## 7. 後續 follow-up（未來 spec ID 待分配 — S161~S164 已用於其他主題）

- **CSP Phase 2**: 拿掉 unsafe-inline，用 nonce + Vite plugin
- **Rate limiting**: 對 `/api/v1/requests` / `/skills/install` 等可被 spam 的 endpoint 加 rate limit
- **WAF / GCP Cloud Armor**: 規則設定
- **Audit log**: auth failure / privilege escalation 嘗試紀錄

## 8. 與其他 spec 關係

- **S161（user input sanitization）**：縱深防禦另一層 — 即使 stored XSS payload 存進 DB，CSP 也擋住 inline script exec；本 spec 攔 transport / response header 層
- **S148（GraalVM AOT）**：Spring Security CSRF token repo / header writers 反射；若 native build 需驗證 reflection metadata
- **S139（OAuth login）**：OAuth flow 透過 cookie session；CSRF 對該 path 必啟；Bearer JWT exempt 路徑要驗證 LAB 與 prod parity
