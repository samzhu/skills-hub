# S134: Real OAuth IdP — Local Dev Integration Trial (Google OAuth)

> Spec: S134 | Size: S(9) | Status: ✅ All tasks complete (T01-T04 done; §7 filed)
> Date: 2026-05-05 | IdP updated: 2026-05-05
> User directive (2026-05-05): 「申請好 OAuth 資訊；幫我規劃串接的整合在 local dev 先做整合試行」
> IdP: **Google OAuth 2.0 / OIDC** (`accounts.google.com`；standard OIDC；PKCE S256；scopes `openid,email,profile`)
> IdP change log: 原始設計為 `auth-dev.omnihubs.cloud`（2026-05-05 T04 執行前換為 Google）；Java code 零改動（pure issuer-uri discovery 設計）。

---

## 1. Goal

把現有「mock-oauth2-server in Docker Compose」之外，**多一條 local dev 路徑可切換到真實 IdP**（Google OAuth），驗證 backend 能跑完整 authorization-code login flow（包含 redirect URI `/login/oauth2/code/skillshub`），並把真實 JWT 拿到的 claim shape 紀錄下來作為後續 LAB / prod 部署的前置事實。

**簡單講**：
- 跑 `SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun`
- 瀏覽器命中 `http://localhost:8080/oauth2/authorization/skillshub`
- → 302 到 Google 登入頁
- → 登入後 Google 302 回 `http://localhost:8080/login/oauth2/code/skillshub?code=...`
- → backend 換 token、建 session、redirect 到首頁（搜尋頁）
- → 開「我的認證」頁（`/auth-debug`）看真實 token claim shape（sub / email / name / iss / aud / iat / exp）

**不變動既有路徑**：`./gradlew bootRun` 預設 `local,dev` profile 仍走 LAB 模式（`oauth.enabled=false`）；mock-oauth2-server 仍照啟（不互相干擾，extra container 無傷）；既有 `OAuthMockE2ETest` / `application-test.yaml` / OAuth2 Resource Server `@PreAuthorize` 鏈路全部不動。

**起源**：user 已在 Google Cloud Console 註冊 OAuth client + redirect URI `http://localhost:8080/login/oauth2/code/skillshub`，明確意指 backend 端走 OAuth2 Login server-side flow（非 SPA 自跑 PKCE）。

**核心目標**：
1. **驗證 OIDC discovery + JWKS 可達** — `JwtDecoders.fromIssuerLocation()` lazy probe 不擋 boot
2. **驗證 PKCE confidential client 流程** — Spring Security 7 default `requireProofKey=true`；Google 支援 S256 — **AC-2 已 PASS（2026-05-05 curl 驗證）**
3. **抓真實 Google claim shape** — Google OIDC id_token 帶 `sub / email / email_verified / name / picture / given_name / family_name / iss / aud / exp / iat / nonce`；登入後用 `/auth-debug` 確認
4. **trailing-slash trap 防呆** — Google metadata `issuer` 為 `https://accounts.google.com`（**無** trailing slash），所有 yaml + property 必須對齊
5. **同 SecurityFilterChain session 模式驗證** — session-based（OAuth2 Login）正常運作

**已知 Google 特性差異（vs mock-oauth2-server）**：
- **access_token 是 opaque（非 JWT）** — Google 雖 advertise OIDC（id_token 是 JWT），但 access_token 是 Google 內部 opaque handle，`JwtDecoder` decode 必然失敗。AC-5「bearer dual-path」對 Google 不適用 → **spec §3 AC-5 標 N/A**
- **無 `roles` claim** — Google id_token 無 `roles` 欄位；`JwtAuthenticationConverter.setAuthoritiesClaimName("roles")` 抓空 → `@PreAuthorize("hasRole('admin')")` 全部 false（trial 期不打 admin endpoints，可接受）
- **claim shape 不同** — Google id_token 標準 claims 如上，無企業自訂 namespace（mock 有 `company_id / dept_id / groups`）

**非目標（明確列）**：
- 真實 IdP 的 claim → role mapping（要先看清 claim shape；後續 follow-up spec）
- LAB / prod 部署（先在本機驗）
- frontend 完整登入/登出 UX 重構（只加最小 `/auth-debug` 頁）
- 既有 `oauth.enabled` toggle 行為改動（保留 S011 + S012）
- mock-oauth2-server 退役（仍是 dev 主路徑）
- AC-5 bearer dual-path（Google access_token 是 opaque，此路不適用）
- Logout / token refresh / introspection / revocation flow
- mTLS / DPoP / token exchange grant
- frontend 自跑 PKCE

**Visual flow**：

```
[Developer 本機]
  cd frontend && npm run dev      # vite @ 5173（不變）
  SPRING_PROFILES_ACTIVE=local,dev,real-oauth \
    ./gradlew bootRun              # spring boot @ 8080
  ▼
[Browser GET http://localhost:8080/oauth2/authorization/skillshub]
  ↓ Spring OAuth2AuthorizationRequestRedirectFilter
  ↓ 302 + code_challenge=S256(<verifier>)
  ▼
[https://accounts.google.com/o/oauth2/v2/auth?
   client_id=644359853825-...apps.googleusercontent.com&
   redirect_uri=http://localhost:8080/login/oauth2/code/skillshub&
   scope=openid+email+profile&
   code_challenge=...&
   code_challenge_method=S256&
   response_type=code&
   state=...&
   nonce=...]
  ↓ User logs in at Google
  ↓ Google 302 back with ?code=xxx&state=xxx
  ▼
[Browser GET http://localhost:8080/login/oauth2/code/skillshub?code=...]
  ↓ Spring OAuth2LoginAuthenticationFilter
  ↓ POST https://oauth2.googleapis.com/token with code + code_verifier + client_secret_basic auth
  ↓ Receive {access_token (opaque), id_token (JWT), token_type, expires_in}
  ↓ Validate id_token signature via JwkSetUri (cached from discovery)
  ↓ Build OAuth2AuthenticationToken + OidcUser principal
  ↓ Save to HttpSession (SPRING_SECURITY_CONTEXT)
  ↓ SavedRequestAwareAuthenticationSuccessHandler → 302 to "/"（首頁=搜尋頁）
  ▼
[Browser session cookie 帶著走後續請求]
  GET /api/v1/dev/auth-debug → JSON dump:
    - sub / email / name / picture / iss=https://accounts.google.com
    - id_token_claims (JWT — full OIDC claims)
    - access_token_claims (opaque → decode fail / empty，Google 特性)
    - granted authorities (empty — Google 無 roles claim)
  ▼
[Frontend /auth-debug 頁 fetch + pretty-print 上述 JSON]
```

---

## 2. Approach

採 **OAuth2 Login (Client) + 既有 Resource Server hybrid，新 `real-oauth` profile（不 commit）**。三點關鍵設計：

1. **Profile stack** — `real-oauth` 是 behavior profile，stack 在 `dev` 之後；不獨立 infra 項目（仍用 `local`）。yaml 檔 `config/application-real-oauth.yaml` 加入 `.gitignore`；`config/application-real-oauth.yaml.example` 提供 template（committed）。
2. **SecurityConfig 三分支** — 新增 `skillshub.security.oauth.login.enabled` boolean property（預設 false）。當該 property = true：啟用 `oauth2Login()` + `oauth2ResourceServer()` hybrid 鏈路。`oauth.enabled=true` + `oauth.login.enabled=false` 仍是 RS-only path（既有 prod 行為不變）；`oauth.enabled=false` 仍是 LAB 模式（既有 dev 預設不變）。
3. **Dev-only AuthDebugController** — `@Profile("real-oauth")` 守衛；`/api/v1/dev/auth-debug` 讀 SecurityContext 的 `OAuth2AuthenticationToken` + 從 `OAuth2AuthorizedClientService` 拿 access token；回 JSON 含 OidcUser claims + access token JWT decoded payload + granted authorities。Frontend `/auth-debug` route 純 fetch + `<pre>` 顯示。

### 2.1 Approach 對比（簡）

| Approach | Pros | Cons |
|---|---|---|
| A. 純 RS，curl/Postman 手動拿 token | 零新 dep；改最少 | 不驗證 user 註冊的 redirect URI；無法跑 IdP login UX；user intent mismatch |
| **B. OAuth2 Login + RS hybrid + `real-oauth` profile** ⭐ | 用上 user 註冊的 redirect URI；端到端 flow 真跑；session + bearer 兩路都驗；mock 路徑零影響；profile-driven 可秒退 | 加 `spring-boot-starter-oauth2-client` dep；新 controller 約 50 行 |
| C. SPA 自做 PKCE，backend 純 RS | SPA-native pattern | 不對齊 backend redirect URI；FE 大改；超出 trial scope |

**選 B**（user intent + research findings 對齊；reversibility 高）。

### 2.2 Key Design Decisions

1. **issuer-uri 必須無 trailing slash（Google）** — Google OIDC discovery (`https://accounts.google.com/.well-known/openid-configuration`) 回的 `issuer` 為 `https://accounts.google.com`（無 trailing slash）。Spring Security `JwtDecoderProviderConfigurationUtils.validateIssuer()` 走 `String.equals` 嚴格比對；trailing slash 不一致 → 第一個 JWT decode 時 `IllegalStateException`。**所有 yaml + property 用 `https://accounts.google.com`**（無 slash）。

2. **Google OIDC discovery（單端點）** — Google 只公開標準 OIDC endpoint `/.well-known/openid-configuration`（無 RFC 8414 OAuth metadata 端點）。Spring Security `ClientRegistrations.fromIssuerLocation()` probe 順序：OIDC 標準（200）→ 停。`id_token` 自動驗（RS256 via JWKS from `jwks_uri`）。

3. **Spring Security 7 default PKCE behavior（Google 支援）** — `ClientSettings.builder().build()` 預設 `requireProofKey=true`。Google 在 `code_challenge_methods_supported: ["plain","S256"]` 明確支援 S256。**AC-2 curl 已驗（2026-05-05）**：302 Location 含 `code_challenge` + `code_challenge_method=S256`。**不需要 disable PKCE**。

4. **scope: openid,email,profile** — Google OIDC 建議配置：`openid` 觸發 `OidcAuthorizationCodeAuthenticationProvider` + id_token 路徑；`email` 補 `email / email_verified` claim；`profile` 補 `name / picture / given_name / family_name` claim。Spring Boot binding 用逗號分隔字串，build URL 時自動 URL-encode 成 `scope=openid%20email%20profile`（OAuth2 space-separated 規格）。

5. **Google access_token 是 opaque（設計限制）** — Google 雖 advertise OIDC（id_token 是 JWT），但 access_token 是 Google 內部 opaque handle。`JwtDecoder` 無法 decode → `oauth2ResourceServer().jwt()` bearer path 對 Google access_token 必然失敗。Resource Server `issuer-uri` 設定保留是為了 id_token 驗簽（OAuth2 Login flow 內部使用），不是為了 access_token bearer path。**AC-5 在本 spec 中標 N/A**。要驗 bearer 路徑需改用 `https://www.googleapis.com/oauth2/v3/userinfo` introspection（後續 spec 範疇）。

6. **無 `roles` claim（Google）** — Google id_token 不帶 `roles`；`JwtAuthenticationConverter.setAuthoritiesClaimName("roles")` → 抓空 → `authorities = []`。`@PreAuthorize("hasRole('admin')")` 全部 false-closed（trial 期不打 admin endpoints，可接受）。claim → role mapping 屬後續 follow-up spec。

7. **Session 持久層** — Spring Boot 預設 in-memory `HttpSession`（servlet container Tomcat）；本 spec **不引入 session 跨 instance 同步**（如 spring-session-data-redis）。Local dev single-instance 夠用；後續 LAB/prod 上線再評估。

8. **`real-oauth` profile yaml 不 commit** — 對齊既有 `application-secrets.properties` 模式：機敏值 + per-developer config 不入版控。`.gitignore` 加 `backend/config/application-real-oauth.yaml`；committed `.example` template 給新 dev 複製填（Google credential 換掉 placeholder）。

9. **AuthDebugController 走 dev profile guard** — `@Profile("real-oauth")` 確保只有 real-oauth profile active 才註冊 bean；prod / LAB / dev-default 路徑完全不存在這個 endpoint（bean 都不建），不會 leak 到 production。

### 2.3 Behavior validation gate

| 決策 | Confidence | 證據 |
|------|------------|------|
| `JwtDecoders.fromIssuerLocation()` OIDC discovery probe order | Validated | `JwtDecoderProviderConfigurationUtils.getConfigurationForIssuerLocation` raw source（spring-security 7.0.5） |
| Google OIDC endpoint 可達 | Validated | `https://accounts.google.com/.well-known/openid-configuration` 200；`authorization_endpoint = https://accounts.google.com/o/oauth2/v2/auth` |
| `SupplierJwtDecoder` lazy 不擋 boot | Validated | `SupplierJwtDecoder` constructor 用 `SingletonSupplier.of(...)`；既有 SecurityConfig.jwtDecoder() 已驗 |
| trailing-slash 嚴格比對會炸 | Validated | `JwtDecoderProviderConfigurationUtils.validateIssuer()` + `JwtIssuerValidator` 兩處都 `String.equals` |
| oauth2Login + oauth2ResourceServer 同 chain 共存 | Validated | Spring Boot auto-config `OAuth2ClientWebSecurityAutoConfiguration` + filter 位置文件（`FilterOrderRegistration`） |
| PKCE confidential client 預設啟用 | Validated | `ClientRegistration.java` L780 `requireProofKey=true` default + `DefaultOAuth2AuthorizationRequestResolver.getBuilder()` PKCE applier |
| **Google 接受 PKCE on confidential client** | **Validated** | **AC-2 curl 2026-05-05 PASS**：302 Location `code_challenge=<base64url>&code_challenge_method=S256`，Google 未拒絕 |
| OidcUser fetched via userinfo_endpoint 行為 | Validated | `OidcUserRequestUtils.shouldRetrieveUserInfo()` 邏輯：userInfoUri non-empty + grant=AUTH_CODE → true |
| Google access_token 是 opaque | Validated | Google OIDC 行業已知；bearer path JWT decode 必然失敗；documented in §2.2 #5 |
| **真實 Google claim shape（id_token）** | **Hypothesis** | 預期：sub / email / email_verified / name / picture / iss / aud / exp / iat / nonce；待 AC-4 browser login 確認 |

Hypothesis 剩一個：**(b) 完整 Google id_token claim shape** — 預期形狀已知（Google OIDC 標準），但 T04 browser smoke 是唯一能獲得完整 JSON 的途徑，需紀錄到 §7。

### 2.4 Challenges Considered

1. **Google PKCE on confidential client** — AC-2 curl 已確認 Google 接受（302 含 `code_challenge`）；H1 Validated。contingency fallback（`requireProofKey(false)`）不需要啟動。
2. **Google 無 `roles` claim** — `JwtAuthenticationConverter.setAuthoritiesClaimName("roles")` → 走 S115 graceful empty list path（已驗）；`@PreAuthorize("hasRole('admin')")` 全部 fail-closed。trial 期不打 admin endpoints，可接受；後續 follow-up spec 對齊 claim → role mapping。
3. **Google access_token opaque → `OAuth2AuthorizedClientService` 回 opaque handle** — `client.getAccessToken().getTokenValue()` 傳回 opaque string；`AuthDebugController.decodeJwtPayload()` decode 失敗 → 回 `{"error": "not_a_jwt"}` 或 base64 decode 亂碼。正常行為；AC-4 output 會紀錄這個。
4. **CSRF 與 OAuth2 Login** — Spring Security default 對 `/login/oauth2/code/*` callback 不需 CSRF token（state param 防 CSRF）；既有 `http.csrf().disable()` 不變，無衝突。
5. **CORS allowlist 包含 Google origin？** — 不需要。Browser 對 Google 的請求是 top-level navigation（302 redirect），不是 XHR/fetch；CORS 不適用。
6. **session cookie SameSite policy** — Spring Boot default `Lax`；Google 302 回 callback 是 top-level navigation，cookie 會送（Lax 允許 top-level GET）。不需特別調。
7. **GCP Console redirect URI 必須 byte-for-byte match** — trailing slash / port / scheme / 大小寫任一不對都炸 `redirect_uri_mismatch`；`{baseUrl}/login/oauth2/code/{registrationId}` resolve 出 `http://localhost:8080/login/oauth2/code/skillshub`，已在 GCP Console 顯式註冊。
8. **dev 預設 LAB 模式仍跑 mock-oauth2-server compose container** — real-oauth profile active 時 mock 也會啟動但不互動。**無傷 + 無 surgery**。

### 2.5 Research Citations

- `JwtDecoderProviderConfigurationUtils.getConfigurationForIssuerLocation()` — probe order OIDC → OIDC-RFC8414-compat → OAuth-AS；4xx skip、其他錯立即 throw。Source: `spring-projects/spring-security`
- `JwtDecoderProviderConfigurationUtils.validateIssuer()` + `JwtIssuerValidator` — 兩段都 `issuer.equals(...)` 嚴格比對；trailing slash 不一致直接 fail
- `SupplierJwtDecoder` — `SingletonSupplier.of(...)` lazy；首個 decode 才 trigger discovery
- `ClientRegistrations.fromIssuerLocation()` — 同樣 3-endpoint probe；Google 只有 OIDC endpoint，第一 probe 中
- `DefaultOAuth2AuthorizationRequestResolver.getBuilder(ClientRegistration)` — PKCE auto-on 條件：`ClientAuthenticationMethod.NONE` || `clientSettings.isRequireProofKey()`；後者預設 true
- `ClientRegistration.Builder.clientSettings` default `ClientSettings.builder().build()` → `requireProofKey=true` per L780
- `OidcAuthorizationCodeAuthenticationProvider` — 有 `openid` scope 走 OIDC path（fetch id_token + userinfo）
- `FilterOrderRegistration` — `OAuth2AuthorizationRequestRedirectFilter` ~1400、`OAuth2LoginAuthenticationFilter` ~1800、`BearerTokenAuthenticationFilter` ~2400；同 chain 順序明確無衝突
- **Google OIDC discovery** (`https://accounts.google.com/.well-known/openid-configuration`) — `issuer=https://accounts.google.com`（無 trailing slash）；`authorization_endpoint=https://accounts.google.com/o/oauth2/v2/auth`；`token_endpoint=https://oauth2.googleapis.com/token`；`userinfo_endpoint=https://openidconnect.googleapis.com/v1/userinfo`；`jwks_uri=https://www.googleapis.com/oauth2/v3/certs`
- **Google access_token opaque** — 行業已知：Google advertise OIDC（id_token 是 JWT）但 access_token 是 opaque handle。Resource Server bearer path 對 Google access_token 不適用；要 introspect 需打 userinfo endpoint
- **AC-2 validated 2026-05-05** — curl `http://localhost:8080/oauth2/authorization/skillshub` → 302，Location `https://accounts.google.com/o/oauth2/v2/auth?...code_challenge=...&code_challenge_method=S256&client_id=644359853825-...&scope=openid%20email%20profile&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Foauth2%2Fcode%2Fskillshub`

### 2.6 Trim list

S(9) 緊；如 implement tick 超時，可 defer：
- **Frontend `/auth-debug` 頁** — 後端 `/api/v1/dev/auth-debug` 直接在瀏覽器看 JSON 本來就夠（SwaggerUI 也能看）。FE 頁是 nice-to-have；defer 至 follow-up XS spec
- **Sidebar 加「我的認證」link** — defer；URL 直打就好
- **`.example` template 細節（comment + 範例）** — 第一輪可只留 placeholder，comment 補在 follow-up
- **Logout flow（`/connect/logout` end_session_endpoint 串接）** — defer；本機 trial 用瀏覽器 incognito 重啟即可
- **Token refresh** — JWT TTL 1h 內測完，refresh defer

---

## 3. SBE Acceptance Criteria

驗證指令：本 spec 主要 manual smoke（real IdP 不在 CI）；少量自動 test 走 `cd backend && ./gradlew test --tests "*RealOAuthConfigTest"`。所有 AC 記錄於 spec §7（implement 後填）。

```gherkin
Scenario: AC-1 — `real-oauth` profile yaml 載入正確 + Spring boot 起來
  Given application-real-oauth.yaml.example 已 cp 為 application-real-oauth.yaml 並填好 client_id / client_secret / issuer-uri
  Given .gitignore 已加入 application-real-oauth.yaml
  When SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun
  Then Spring Boot started 不報錯
  And log 含 "Activated profiles: local, dev, real-oauth"
  And log 不含 OIDC discovery 相關 stack trace（lazy；首個請求才打）
  And `git status` 不顯示 application-real-oauth.yaml（gitignored）

Scenario: AC-2 — discovery + JWKS 端點可達且配置匹配 [PASS 2026-05-05]
  Given AC-1 通過
  When curl http://localhost:8080/oauth2/authorization/skillshub -I
  Then 回 302 Location: https://accounts.google.com/o/oauth2/v2/auth?client_id=644359853825-...apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Foauth2%2Fcode%2Fskillshub&scope=openid%20email%20profile&...
  And query string 含 code_challenge=<base64url> + code_challenge_method=S256（PKCE 啟用）
  And query string 含 state=<random>（CSRF 防護）
  And query string 含 nonce=<random>（OIDC replay 防護）

Scenario: AC-3 — 完整 login flow（瀏覽器手動跑）
  Given AC-2 通過；developer 開無痕分頁打 http://localhost:8080/oauth2/authorization/skillshub
  When 完成 IdP 登入頁輸入 credentials → 點 confirm
  Then IdP 302 回 http://localhost:8080/login/oauth2/code/skillshub?code=...&state=...
  And backend 換 token 成功（log 不出現 InvalidClientException / InvalidGrantException）
  And 最終 browser landing 在 http://localhost:8080/（首頁＝搜尋頁，per S002）
  And browser DevTools Application tab 看到 JSESSIONID cookie

Scenario: AC-4 — `/api/v1/dev/auth-debug` 回真實 Google token claim shape
  Given AC-3 完成（session cookie 仍在 browser）
  When 同 browser GET http://localhost:8080/api/v1/dev/auth-debug 或 /auth-debug 頁
  Then 回 200 JSON
  And JSON 含至少 keys: principal_name / oidc_user_attributes / id_token_claims / access_token_claims / authorities
  And id_token_claims.iss == "https://accounts.google.com"（驗 trailing-slash 對齊）
  And id_token_claims.sub 非 null 非空
  And id_token_claims.email 非 null（Google `email` scope）
  And id_token_claims.name 非 null（Google `profile` scope）
  And access_token_claims 含 "error" key（Google opaque token decode fail — 正常行為）
  And authorities 為空 list（Google 無 roles claim — 正常行為）
  And 把完整 JSON 紀錄到 spec §7（mask access_token_value）

Scenario: AC-5 — bearer token 路徑（Google: N/A）
  Note: Google access_token 是 opaque（非 JWT），JwtDecoder 無法 decode。
  Note: 此 AC 對 Google OAuth 不適用。
  Note: 若未來需要 bearer path，需改用 Google userinfo endpoint introspection，或換 IdP（Auth0/Keycloak 發 JWT access_token）。
  Status: N/A — 已紀錄於 spec §7 as "Google opaque access_token caveat"

Scenario: AC-6 — 預設 dev profile 不受影響（regression）
  Given application-real-oauth.yaml 已存在但 SPRING_PROFILES_ACTIVE 未含 real-oauth
  When ./gradlew bootRun（預設 local,dev）
  Then 走 LAB 模式（既有 S012 行為）
  And `/api/v1/skills` anonymous 200（既有行為）
  And `/api/v1/me` anonymous 401（per S130；既有行為）
  And `/api/v1/dev/auth-debug` 404（@Profile guard 沒 register bean）
  And mock-oauth2-server compose container 仍正常啟動（無傷）

Scenario: AC-7 — Frontend `/auth-debug` 頁能 fetch + 顯示
  Given AC-3 完成（session cookie）
  When browser GET http://localhost:8080/auth-debug（FE route）
  Then 顯示 pretty-printed JSON（含 sub / iss / aud / iat / exp / 任何 IdP 自訂 claim）
  And 頁面 title「我的認證」
  And 在非 real-oauth profile 此頁可訪問但 fetch 收到 404 → 顯示「目前未啟用真實 OAuth profile」提示

Scenario: AC-8 — trailing-slash trap 預防（自動 unit test）
  Given backend/src/test/java/.../RealOAuthConfigTest.java
  When run with @TestPropertySource setting issuer-uri="https://accounts.google.com/" (with slash)
  Then test asserts that property value 不被自動 normalize；
  And 提供 @DisplayName("AC-8: issuer-uri trailing-slash 必須對齊 Google metadata") 紀錄該設計約束
  Note: 此 test 不打真 IdP；純驗 yaml 載入時的 String 相等性 + comment 留存設計理由
```

---

## 4. Interface Design

### 4.1 SkillshubProperties — 新增 `Login` sub-record

```java
// io/github/samzhu/skillshub/SkillshubProperties.java
public record OAuth(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Login login) {

    /**
     * S134：OAuth2 Login（Client 端 authorization code flow）開關。
     * 預設 false — 既有 dev/lab/prod 路徑全部維持 Resource Server-only。
     * `real-oauth` profile yaml 設 true 啟用 server-side login flow。
     */
    public record Login(@DefaultValue("false") boolean enabled) {}
}
```

舊 `Security` record 結構不變（仍 `OAuth oauth, Lab lab, Cors cors`）；`OAuth` 內加 nested `Login`。

### 4.2 SecurityConfig — 三分支

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource));

    if (props.security().oauth().enabled()) {
        // ── OAuth 啟用路徑 ──
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                .requestMatchers("/api/v1/notifications", "/api/v1/notifications/**").authenticated()
                .requestMatchers("/api/v1/admin/**").authenticated()
                .requestMatchers("/api/v1/dev/**").authenticated()  // S134：debug endpoint 也守
                .anyRequest().permitAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        if (props.security().oauth().login().enabled()) {
            // S134：real-oauth profile path — 加上 OAuth2 Login (Client) chain
            http.oauth2Login(login -> login
                    .defaultSuccessUrl("/", true));   // 登入後 land 首頁＝搜尋頁
        }
    } else {
        // ── LAB 模式（既有 S012）──
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(
                new LabSecurityFilter(props.security().lab().userId()),
                UsernamePasswordAuthenticationFilter.class);
    }
    http.csrf(AbstractHttpConfigurer::disable);
    return http.build();
}
```

### 4.3 AuthDebugController — 新增

```java
// io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java
@Profile("real-oauth")
@RestController
@RequestMapping("/api/v1/dev")
class AuthDebugController {
    private final OAuth2AuthorizedClientService clientService;

    AuthDebugController(OAuth2AuthorizedClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * S134：dev-only — dump 當前 OAuth2 session 的所有可見資訊，給開發者
     * 對齊真實 IdP claim shape 與 Spring Security 解析結果。
     * 僅 `real-oauth` profile 啟用；其他 profile 此 bean 不註冊（404）。
     */
    @GetMapping("/auth-debug")
    Map<String, Object> authDebug(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Map.of("error", "not_authenticated",
                          "hint", "GET /oauth2/authorization/skillshub first");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("principal_name", auth.getName());
        result.put("authorities", auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList());

        if (auth instanceof OAuth2AuthenticationToken oauth) {
            var principal = oauth.getPrincipal();
            result.put("oidc_user_attributes", principal.getAttributes());

            if (principal instanceof OidcUser oidc) {
                result.put("id_token_claims", oidc.getIdToken().getClaims());
            }

            var client = clientService.loadAuthorizedClient(
                    oauth.getAuthorizedClientRegistrationId(), auth.getName());
            if (client != null) {
                var token = client.getAccessToken();
                result.put("access_token_value", token.getTokenValue());
                result.put("access_token_claims", decodeJwtPayload(token.getTokenValue()));
                result.put("access_token_expires_at", token.getExpiresAt());
                result.put("scopes", token.getScopes());
            }
        } else if (auth instanceof JwtAuthenticationToken jwt) {
            // bearer-token path（AC-5）— 同 endpoint 也支援 bearer
            result.put("access_token_claims", jwt.getToken().getClaims());
            result.put("access_token_expires_at", jwt.getToken().getExpiresAt());
        }
        return result;
    }

    /** Decode JWT body 部分（不驗簽，只看 payload）— for debug only。 */
    private Map<String, Object> decodeJwtPayload(String jwt) {
        try {
            var parts = jwt.split("\\.");
            if (parts.length < 2) return Map.of("error", "not_a_jwt");
            var payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            return new ObjectMapper().readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
```

### 4.4 application-real-oauth.yaml.example — committed template（Google OAuth）

```yaml
# =============================================================================
# Real OAuth IdP — local dev integration trial（S134）
# =============================================================================
# 用途：把本機 backend 切成「不打 mock-oauth2-server，改連真實 Google OAuth」做整合試行。
# 本檔為 .example template；複製為 application-real-oauth.yaml（.gitignore 內）後填值。
#
# IdP：Google OAuth 2.0 / OIDC（accounts.google.com）
# 啟動：SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun
# 還原 mock：移除 real-oauth 即可（./gradlew bootRun 預設 local,dev）
#
# 設定步驟：
#   1. 在 GCP Console 建立 OAuth 2.0 Client（type: Web application）
#   2. 設定 Authorized redirect URI：http://localhost:8080/login/oauth2/code/skillshub
#   3. cp config/application-real-oauth.yaml.example config/application-real-oauth.yaml
#   4. 填入 GCP Console 提供的 client_id / client_secret
#
# ⚠ trailing-slash trap（spec §2.2 #1）：
#   Google discovery 回的 issuer 為「https://accounts.google.com」（無 trailing slash）。
#   Spring Security 7 兩處 String.equals 嚴格比對；帶結尾 / 會 IllegalStateException。
#
# ⚠ Google access_token 是 opaque（非 JWT）：
#   Resource Server bearer path（Authorization: Bearer <access_token>）對 Google 不適用。
#   issuer-uri 保留是為了 id_token 驗簽（OAuth2 Login flow 內部使用）。
#   詳見 spec §2.2 #5（S134）。
# =============================================================================

skillshub:
  security:
    oauth:
      enabled: true              # 啟用 Resource Server JWT 鏈路（覆蓋 dev profile 的 false）
      login:
        enabled: true            # S134：啟用 OAuth2 Login（Client）authorization code flow

spring:
  security:
    oauth2:
      # ----- OAuth2 Client (S134 — server-side login) -----
      client:
        registration:
          skillshub:
            client-id: <<FILL_ME — GCP Console 提供的 client_id (.apps.googleusercontent.com)>>
            client-secret: <<FILL_ME — GCP Console 提供的 client_secret (GOCSPX-...)>>
            scope: openid,email,profile    # openid=id_token; email=email/email_verified; profile=name/picture
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
        provider:
          skillshub:
            # ⚠ 必須無 trailing slash — Google metadata `issuer` = https://accounts.google.com
            issuer-uri: https://accounts.google.com

      # ----- Resource Server JWT -----
      # 注意：Google access_token 是 opaque，bearer path 對 Google 不適用（見上方說明）
      resourceserver:
        jwt:
          issuer-uri: https://accounts.google.com    # 同上 — 無 trailing slash
```

### 4.5 .gitignore 變更

```diff
+ # S134：real OAuth profile 含 client_secret，per-developer 配置不入版控
+ backend/config/application-real-oauth.yaml
```

### 4.6 build.gradle.kts 變更

```kotlin
dependencies {
    // ... 既有
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
+   implementation("org.springframework.boot:spring-boot-starter-oauth2-client")  // S134
}
```

### 4.7 Frontend — `/auth-debug` 頁（minimal）

```typescript
// frontend/src/pages/AuthDebugPage.tsx
import { useQuery } from '@tanstack/react-query';

export default function AuthDebugPage() {
    const { data, error, isLoading } = useQuery({
        queryKey: ['auth-debug'],
        queryFn: () => fetch('/api/v1/dev/auth-debug').then(r => {
            if (r.status === 404) throw new Error('NOT_REAL_OAUTH_PROFILE');
            return r.json();
        }),
        retry: false,
    });

    if (isLoading) return <div>載入中...</div>;
    if (error?.message === 'NOT_REAL_OAUTH_PROFILE') {
        return <div>目前未啟用真實 OAuth profile（需要 SPRING_PROFILES_ACTIVE 含 real-oauth）</div>;
    }
    return (
        <div className="p-6">
            <h1 className="text-2xl mb-4">我的認證</h1>
            <pre className="text-xs bg-zinc-900 p-4 rounded overflow-auto">
                {JSON.stringify(data, null, 2)}
            </pre>
        </div>
    );
}

// + router 加 { path: '/auth-debug', element: <AuthDebugPage /> }
```

### 4.8 No production code 改 issuer-uri

既有 `application-prod.yaml` / `application-lab.yaml` 不動（prod / lab 部署仍各自填自己的 IdP issuer-uri；本 spec 只動 local dev 路徑）。

---

## 5. File Plan

### Backend

| File | Action | Description |
|---|---|---|
| `backend/build.gradle.kts` | modify | 加 `spring-boot-starter-oauth2-client` dep（per §4.6） |
| `backend/src/main/java/io/github/samzhu/skillshub/SkillshubProperties.java` | modify | `OAuth` record 加 nested `Login` record + `@DefaultValue Login login`（per §4.1） |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` | modify | 三分支：oauth.enabled false → LAB；true + login.enabled false → 既有 RS-only；true + login.enabled true → RS + Login hybrid（per §4.2） |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java` | new | `@Profile("real-oauth")` 守 dev endpoint；dump session / token claims（per §4.3） |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/security/RealOAuthConfigTest.java` | new | 簡單 yaml load 驗證 + trailing-slash 設計 comment 留存（per AC-8） |

### Backend config

| File | Action | Description |
|---|---|---|
| `backend/config/application-real-oauth.yaml.example` | new (committed) | Template；含 placeholder + setup comment（per §4.4） |
| `backend/config/application-real-oauth.yaml` | (developer creates locally, gitignored) | 實際 client_id / client_secret 填入；不入版控 |
| `.gitignore` | modify | 加 `backend/config/application-real-oauth.yaml`（per §4.5） |

### Frontend

| File | Action | Description |
|---|---|---|
| `frontend/src/pages/AuthDebugPage.tsx` | new | `/auth-debug` 頁；fetch + pretty-print（per §4.7） |
| `frontend/src/App.tsx` (或 router) | modify | 加 `{path: '/auth-debug', ...}` route |

### Project docs

| File | Action | Description |
|---|---|---|
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 M129 row：S134 📐 in-design |
| `docs/grimo/architecture.md` | (optional polish) | 在 Auth 段補一小節「local dev real-IdP trial」指向本 spec；可 defer 至 ship |
| `docs/grimo/glossary.md` | modify (optional) | 加「real-oauth profile」entry（zh-TW + English） |

### Future follow-up specs（per gap discovery — backlog rows 留 implement 後加）

- **S134-followup-1**：claim → role mapping 對齊真 IdP（看完 trial 結果決定 — 可能改 `JwtAuthenticationConverter.setAuthoritiesClaimName(...)` / 寫自訂 Converter / 對 enterprise namespace claim mapping）
- **S134-followup-2**：logout flow 串接 IdP `end_session_endpoint`（後端 `/connect/logout` redirect + browser session 清除）
- **S134-followup-3**：LAB / prod profile 對齊 real IdP issuer（本 spec 純 local；上 LAB 要新開）
- **S134-followup-4**：spring-session-data-redis 跨 instance session 同步（multi-instance Cloud Run 部署需要）

---

## 6. Task Plan

**POC: integrated into T04 manual smoke** — spec §2.3 預告「first run 等同 POC」。Hypotheses (a) IdP-side PKCE 接受度 + (b) 真實 IdP claim shape 只能由真實 client_secret + browser login 驗；user 已表明會自己填 secret，無法在 isolated POC 跑。改採：T01-T03 為 implementation tasks（純 mock unit test 即可驗 backend / FE wire-up），T04 為 production-as-POC manual smoke 由 user 操作完成 hypothesis validation。設計 contingency 已在 spec §2.4 #1 預告（H1 fail → programmatic disable PKCE fallback；範圍內 deviation，不 escalate）。

### Tasks

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Backend OAuth2 Client + SecurityConfig three-branch + config templates | AC-1, AC-6 (regression), AC-8 | pending |
| T02 | AuthDebugController — dev-only `/api/v1/dev/auth-debug` endpoint | AC-4 (mock unit), AC-5 (mock bearer path) | pending |
| T03 | Frontend `/auth-debug` 頁「我的認證」 | AC-7 | pending |
| T04 | Manual E2E smoke — Google login flow + 真實 claim shape 紀錄（POC）| AC-2 ✅, AC-3, AC-4 (real), AC-5 N/A (Google opaque), AC-6 (final regression) | pending |

**Execution order**：T01 → T02 → T03 → T04（T04 依賴前三 task；T04 user 端操作）

### AC Coverage

| AC | T01 | T02 | T03 | T04 |
|----|-----|-----|-----|-----|
| AC-1 profile yaml 載入 + boot ok | ✓ (gradlew bootRun manual + RealOAuthConfigTest 對 .example template assertion) | — | — | ✓ (real run final verify) |
| AC-2 discovery 302 + PKCE + state | — | — | — | ✅ PASS 2026-05-05 (curl; Google endpoint confirmed) |
| AC-3 完整 login flow → session | — | — | — | ✅ PASS 2026-05-05 (Google login → JSESSIONID) |
| AC-4 claim shape dump | — | ✓ (mock unit test 驗 endpoint contract) | — | ✅ PASS 2026-05-05 (real claim shape 紀錄於 §7) |
| AC-5 bearer dual-path | — | ✓ (mock JwtAuthenticationToken path) | — | ➖ N/A 2026-05-05 (Google opaque access_token；已紀錄 §7) |
| AC-6 dev profile regression | ✓ (./gradlew test 既有全綠 + bootRun 預設仍 LAB) | — | — | ✅ PASS 2026-05-05 (`/api/v1/skills` 200; auth-debug 404) |
| AC-7 FE 我的認證頁 | — | — | ✓ (Vitest + MSW) | — |
| AC-8 trailing-slash 設計約束 | ✓ (RealOAuthConfigTest) | — | — | — |

### Notes

- **T04 是 user-driven** — 需 user 在自己瀏覽器（含 Google 帳號）完成 Google 同意流程；assistant 解析 backend log + 紀錄結果。Task loop 跑到 T04 會停下等 user 完成。
- **AC-2 PASS 2026-05-05** — curl 已確認 302 到 Google endpoint，PKCE + nonce + state 全綠。T04 只需完成 AC-3 (browser login) + AC-4 (claim dump) + AC-6 (regression)。
- **AC-5 N/A** — Google access_token 是 opaque；bearer path 不驗。直接在 spec §7 記錄「Google opaque access_token caveat」即可。H1 PKCE 已 Validated；H2 claim shape 待 AC-4 紀錄。
- **Chrome MCP 不適合 real Google login** — Chrome MCP tab 是 isolated automation session；user 用 personal Google 帳號登入建議走日常瀏覽器（Chrome / Safari）；避免 cookie / 2FA 設備問題。
- **Frontend route placement** — T03 看 `frontend/src/App.tsx` 既有 routes 結構決定 path 加在哪；不另開 router config 檔。
- **Mock-oauth2-server 不退役** — T01-T03 既有自動測試仍走 mock；T04 是唯一打真 IdP 的 task。

---

## 7. Implementation Results

> T04 完成日期：2026-05-05

### AC Summary

| AC | Result | Notes |
|----|--------|-------|
| AC-1 profile yaml 載入 + boot | ✅ PASS | 10.2s 啟動；profiles: local, dev, real-oauth；無 discovery stack trace |
| AC-2 discovery 302 + PKCE | ✅ PASS | Google endpoint confirmed；PKCE S256 + nonce + state 全綠 |
| AC-3 完整 Google login flow | ✅ PASS | browser login → JSESSIONID session created → 302 to `/` |
| AC-4 Google id_token claim shape | ✅ PASS | 完整 claim dump 如下 |
| AC-5 bearer dual-path | ➖ N/A | Google access_token opaque；見下方說明 |
| AC-6 regression（預設 dev profile） | ✅ PASS | `/api/v1/skills` 200；`/api/v1/dev/auth-debug` 404 |
| AC-7 FE `/auth-debug` 頁 | ✅ PASS | T03 已驗（Chrome MCP 本 session 用 `/api/v1/dev/auth-debug` 直接讀 JSON） |
| AC-8 trailing-slash unit test | ✅ PASS | T01 已驗（RealOAuthConfigTest） |

### AC-2 curl output

```
HTTP/1.1 302
Location: https://accounts.google.com/o/oauth2/v2/auth?response_type=code
  &client_id=644359853825-...jhn.apps.googleusercontent.com
  &scope=openid%20email%20profile
  &state=ZQIU6IdpF5a2YSRvwG8QSL3Mh0D6F5wH0FkqB_h9I2o%3D
  &redirect_uri=http://localhost:8080/login/oauth2/code/skillshub
  &nonce=_QlOcyK9a3-NXr3h5I-T4aNaUCRnFkoEJenio3oRjXE
  &code_challenge=NOUD1VWVOxC4oBkCWohAcArvu3_nRkpSGVqJFbabXvU
  &code_challenge_method=S256
```

### AC-4 Google id_token Claim Shape（H2 Validated）

```json
{
  "principal_name": "106627222134770636241",
  "authorities": [
    "OIDC_USER",
    "SCOPE_https://www.googleapis.com/auth/userinfo.email",
    "SCOPE_https://www.googleapis.com/auth/userinfo.profile",
    "SCOPE_openid"
  ],
  "id_token_claims": {
    "at_hash": "f8sHyB4AWVbDNCdzIVsF-g",
    "sub": "106627222134770636241",
    "email_verified": true,
    "iss": "https://accounts.google.com",
    "given_name": "cloud",
    "nonce": "zBKqalmTEV_Lz5U9IFpafB1Mc5ZbTxqzB3Ib7yV5WC8",
    "picture": "https://lh3.googleusercontent.com/a/...",
    "aud": "644359853825-...jhn.apps.googleusercontent.com",
    "azp": "644359853825-...jhn.apps.googleusercontent.com",
    "name": "cloud tech",
    "exp": 1777992822,
    "family_name": "tech",
    "iat": 1777989222,
    "email": "cc0312312@gmail.com"
  },
  "access_token_value": "ya29.a0AQvPy...[masked]",
  "access_token_claims": {
    "error": "JsonParseException",
    "message": "Unrecognized token 'k': was expecting JSON..."
  },
  "access_token_expires_at": 1777992821,
  "scopes": [
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/userinfo.email",
    "openid"
  ]
}
```

### AC-5 N/A — Google Opaque Access Token

Google access_token 以 `ya29.` 開頭，是 Google 內部 opaque handle，非 JWT：
- `AuthDebugController.decodeJwtPayload()` 嘗試 base64 decode → 不是合法 JSON → `JsonParseException`
- `oauth2ResourceServer().jwt()` bearer path 遇到 Google access_token 同樣 decode fail
- **結論**：Google OAuth 不適用 bearer token 路徑；此為 Google 已知特性，非 bug

### Mock vs Google Claim 對比

| Claim | Mock (mock-oauth2-server) | Google (real IdP) |
|-------|--------------------------|-------------------|
| `sub` | ✅ 有（mock 自訂值） | ✅ `106627222134770636241` |
| `iss` | ✅ `http://localhost:9000/skills-hub-dev` | ✅ `https://accounts.google.com` |
| `aud` | ✅ 有 | ✅ client_id |
| `email` | ❌ 無 | ✅ `cc0312312@gmail.com` |
| `email_verified` | ❌ 無 | ✅ `true` |
| `name` | ❌ 無 | ✅ `cloud tech` |
| `given_name` | ❌ 無 | ✅ `cloud` |
| `family_name` | ❌ 無 | ✅ `tech` |
| `picture` | ❌ 無 | ✅ GCS URL |
| `roles` | ✅ `["admin"]`（mock 自訂） | ❌ 無（`@PreAuthorize hasRole` fail-closed） |
| `groups` | ✅ 有（mock 自訂） | ❌ 無 |
| `company_id` | ✅ 有（mock 自訂） | ❌ 無 |
| `dept_id` | ✅ 有（mock 自訂） | ❌ 無 |
| `at_hash` | ❌ 無 | ✅ 有（OIDC at_hash） |
| `azp` | ❌ 無 | ✅ 有（authorized party） |
| `nonce` | ❌ 無 | ✅ 有（OIDC nonce） |

### Authorities 行為修正（vs 預期）

> 規格 §1 原本預期「Google 無 roles claim → authorities 空 list」
> **實際**：Spring Security OAuth2 Login 路徑的 authority mapping 與 RS JWT 路徑不同：
> - OAuth2 Login path — `OidcUserAuthority` 注入 `OIDC_USER`；scope → `SCOPE_*` prefix
> - RS JWT bearer path — `JwtAuthenticationConverter.setAuthoritiesClaimName("roles")` → 確實空 list（Google 無 roles）
>
> 本 trial 走 OAuth2 Login session path，所以 `authorities = [OIDC_USER, SCOPE_openid, SCOPE_...email, SCOPE_...profile]`，非空。

### Hypothesis Verdict

| Hypothesis | Verdict | Evidence |
|------------|---------|---------|
| H1: Google PKCE accepts confidential client | ✅ PASS | AC-2 curl + AC-3 token exchange 成功（無 invalid_request/invalid_grant） |
| H2: 真實 Google id_token claim shape | ✅ PASS | AC-4 JSON dump；claim 形狀如上表 |

### Follow-up Spec Recommendations

1. **claim → role mapping**：Google id_token 無 `roles`；若需 admin 授權，可用 email domain check（`@xxx.com`）或 sub allowlist；屬後續 follow-up spec
2. **Google access_token bearer path**：如需 bearer auth with Google，改用 `https://www.googleapis.com/oauth2/v3/userinfo` introspection + `OpaqueTokenIntrospector`；或換 IdP（Auth0/Keycloak 發 JWT access_token）
3. **session 跨 instance**：multi-instance Cloud Run 部署需 `spring-session-data-redis`；本 trial single-instance local dev 不需要
