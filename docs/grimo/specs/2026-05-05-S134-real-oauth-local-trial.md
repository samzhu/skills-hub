# S134: Real OAuth IdP — Local Dev Integration Trial

> Spec: S134 | Size: S(9) | Status: 📐 Design
> Date: 2026-05-05
> User directive (2026-05-05): 「申請好 OAuth 資訊；幫我規劃串接的整合在 local dev 先做整合試行」
> IdP: `https://auth-dev.omnihubs.cloud` (Spring Authorization Server-style，OIDC + RFC 8414 雙 metadata；PKCE S256；single advertised scope `openid`)

---

## 1. Goal

把現有「mock-oauth2-server in Docker Compose」之外，**多一條 local dev 路徑可切換到真實 IdP**（auth-dev.omnihubs.cloud），驗證 backend 能跑完整 authorization-code login flow（包含 redirect URI `/login/oauth2/code/skillshub`），並把真實 JWT 拿到的 claim shape 紀錄下來作為後續 LAB / prod 部署的前置事實。

**簡單講**：
- 跑 `SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun`
- 瀏覽器命中 `http://localhost:8080/oauth2/authorization/skillshub`
- → 302 到 omnihubs IdP 登入頁
- → 登入後 IdP 302 回 `http://localhost:8080/login/oauth2/code/skillshub?code=...`
- → backend 換 token、建 session、redirect 到首頁（搜尋頁）
- → 開「我的認證」頁（`/auth-debug`）看真實 token claim shape（sub / aud / iss / scope / 任何 IdP 自訂 claim）

**不變動既有路徑**：`./gradlew bootRun` 預設 `local,dev` profile 仍走 LAB 模式（`oauth.enabled=false`）；mock-oauth2-server 仍照啟（不互相干擾，extra container 無傷）；既有 `OAuthMockE2ETest` / `application-test.yaml` / OAuth2 Resource Server `@PreAuthorize` 鏈路全部不動。

**起源**：user 已在 IdP 註冊 client + redirect URI `http://localhost:8080/login/oauth2/code/skillshub`，明確意指 backend 端走 OAuth2 Login server-side flow（非 SPA 自跑 PKCE）。

**核心目標**：
1. **驗證 OIDC discovery + JWKS 可達** — `JwtDecoders.fromIssuerLocation()` lazy probe 不擋 boot
2. **驗證 PKCE confidential client 流程** — Spring Security 7 default `requireProofKey=true`；IdP 支援 S256
3. **抓真實 claim shape** — IdP 只 advertise `scopes_supported: ["openid"]`、無 `claims_supported` metadata；登入後才知道 token 真正帶哪些 claim（mock 是自訂的 sub/roles/groups/company_id/dept_id；real IdP 可能完全不同）
4. **trailing-slash trap 防呆** — IdP metadata `issuer` 為 `https://auth-dev.omnihubs.cloud`（**無** trailing slash），所有 yaml + property 必須對齊
5. **同 SecurityFilterChain 雙模驗證** — session-based（OAuth2 Login）+ bearer-based（Resource Server）共存可同時運作

**非目標（明確列）**：
- 真實 IdP 的 claim → role mapping（要先看清 claim shape；後續 follow-up spec）
- LAB / prod 部署（先在本機驗）
- frontend 完整登入/登出 UX 重構（只加最小 `/auth-debug` 頁）
- 既有 `oauth.enabled` toggle 行為改動（保留 S011 + S012）
- mock-oauth2-server 退役（仍是 dev 主路徑）
- Logout / token refresh / introspection / revocation flow（IdP 都有 endpoint，但本 spec 不串）
- mTLS / DPoP / token exchange grant（IdP advertise 了，但本 spec 不用）
- frontend 自跑 PKCE（不對齊 user 給的 backend redirect URI）

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
[https://auth-dev.omnihubs.cloud/oauth2/authorize?
   client_id=596527ca-...&
   redirect_uri=http://localhost:8080/login/oauth2/code/skillshub&
   scope=openid&
   code_challenge=...&
   code_challenge_method=S256&
   response_type=code&
   state=...]
  ↓ User logs in at IdP
  ↓ IdP 302 back with ?code=xxx&state=xxx
  ▼
[Browser GET http://localhost:8080/login/oauth2/code/skillshub?code=...]
  ↓ Spring OAuth2LoginAuthenticationFilter
  ↓ POST IdP token endpoint with code + code_verifier + client_secret_basic auth
  ↓ Receive {access_token, id_token, refresh_token, token_type, expires_in}
  ↓ Validate id_token signature via JwkSetUri (cached)
  ↓ Build OAuth2AuthenticationToken + OidcUser principal
  ↓ Save to HttpSession (SPRING_SECURITY_CONTEXT)
  ↓ SavedRequestAwareAuthenticationSuccessHandler → 302 to "/"（首頁=搜尋頁）
  ▼
[Browser session cookie 帶著走後續請求]
  GET /api/v1/dev/auth-debug → JSON dump:
    - sub / aud / iss / iat / exp
    - access_token claims (decoded as JWT)
    - id_token claims
    - OAuth2User attributes
    - granted authorities (Spring 自動 expand)
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

1. **issuer-uri 必須無 trailing slash** — IdP metadata `issuer` 為 `https://auth-dev.omnihubs.cloud`（驗證過：`curl https://auth-dev.omnihubs.cloud/.well-known/openid-configuration | jq .issuer`）。Spring Security `JwtDecoderProviderConfigurationUtils.validateIssuer()` 走 `String.equals` 嚴格比對；trailing slash 不一致 → 第一個 JWT decode 時 `IllegalStateException`。**所有 yaml + property 用 `https://auth-dev.omnihubs.cloud`**（無 slash）。

2. **OIDC discovery vs RFC 8414 OAuth metadata** — 兩者皆有公開：
   - `/.well-known/openid-configuration`（OIDC）有 `userinfo_endpoint`、`scopes_supported`、`subject_types_supported`、`id_token_signing_alg_values_supported`
   - `/.well-known/oauth-authorization-server`（RFC 8414）少了上述欄位
   - Spring Security `ClientRegistrations.fromIssuerLocation()` probe 順序：OIDC 標準 → OIDC RFC 8414 compat → OAuth-AS metadata；任一 200 即停。**OIDC 路徑會中**，所以 `id_token` 自動驗（含 RS256 簽章 via JWKS）。

3. **Spring Security 7 default PKCE behavior** — `ClientSettings.builder().build()` 預設 `requireProofKey=true`（per `ClientRegistration.java` line 780）。Confidential client（client_secret_basic auth）+ PKCE 是新版預設；IdP 端 `code_challenge_methods_supported: ["S256"]` 支援。**不需要 disable PKCE**。如果 IdP 端 client config 對 confidential client 拒絕 PKCE（罕見但可能），fallback 是 `.clientSettings(ClientSettings.builder().requireProofKey(false).build())` programmatic override；本 spec 先不加，待 smoke 出問題再補。

4. **scope: openid 唯一** — IdP `scopes_supported: ["openid"]` 只 advertise 這個。我們 yaml 配 `scope: openid`。OIDC `openid` 觸發 `OidcAuthorizationCodeAuthenticationProvider` 路徑 → 自動拿 id_token + 走 `OidcUserService`（如有 `userinfo_endpoint` 會額外打一次補 user attributes，IdP 有 advertise `/userinfo`）。

5. **Resource Server + OAuth2 Login 同 issuer 共用 JwtDecoder** — auto-config `OAuth2ResourceServerJwtConfiguration` 偵測 `spring.security.oauth2.resourceserver.jwt.issuer-uri` property → 自動建 `SupplierJwtDecoder` bean（lazy）。OAuth2 Login 用獨立的 `OidcIdTokenDecoderFactory` 驗 id_token；兩者底層都打同一 jwks_uri，但 bean 獨立（per Spring Security 7 design）。**為避免衝突，原本 `SecurityConfig.jwtDecoder()` 顯式 bean 保留不動**（既有 `@ConditionalOnProperty(prefix = "skillshub.security.oauth", name = "enabled", havingValue = "true", matchIfMissing = true)` 把它跟 `oauth.enabled` toggle 同步；real-oauth profile 設 `oauth.enabled=true` 它就建）；real-oauth profile 仍透過該 bean 走既有 Resource Server 路徑，零行為變動。

6. **Session 持久層** — Spring Boot 預設 in-memory `HttpSession`（servlet container Tomcat）；本 spec **不引入 session 跨 instance 同步**（如 spring-session-data-redis）。Local dev single-instance 夠用；後續 LAB/prod 上線再評估（屬 follow-up infra spec 範疇）。

7. **`real-oauth` profile yaml 不 commit** — 對齊既有 `application-secrets.properties` 模式：機敏值 + per-developer config 不入版控。`.gitignore` 加 `backend/config/application-real-oauth.yaml`；committed `.example` template 給新 dev 複製填。client_id 雖非 secret 但放一起方便；client_secret 走同一 yaml（不另開 secrets.properties entry — 該檔已有 `skillshub.db.password` 等用於 placeholder resolution，OAuth client secret 直接寫 yaml 比較直接）。

8. **AuthDebugController 走 dev profile guard** — `@Profile("real-oauth")` 確保只有 real-oauth profile active 才註冊 bean；prod / LAB / dev-default 路徑完全不存在這個 endpoint（even bean 都不建），不會 leak 到 production。

### 2.3 Behavior validation gate

| 決策 | Confidence | 證據 |
|------|------------|------|
| `JwtDecoders.fromIssuerLocation()` OIDC discovery probe order | Validated | `JwtDecoderProviderConfigurationUtils.getConfigurationForIssuerLocation` raw source（spring-security 7.0.5） |
| OIDC + OAuth metadata 雙端點皆 published | Validated | curl `https://auth-dev.omnihubs.cloud/.well-known/{openid-configuration,oauth-authorization-server}` 兩邊 200 |
| `SupplierJwtDecoder` lazy 不擋 boot | Validated | `SupplierJwtDecoder` constructor 用 `SingletonSupplier.of(...)`；既有 SecurityConfig.jwtDecoder() 已驗 |
| trailing-slash 嚴格比對會炸 | Validated | `JwtDecoderProviderConfigurationUtils.validateIssuer()` + `JwtIssuerValidator` 兩處都 `String.equals` |
| oauth2Login + oauth2ResourceServer 同 chain 共存 | Validated | Spring Boot auto-config `OAuth2ClientWebSecurityAutoConfiguration` + filter 位置文件（`FilterOrderRegistration`） |
| PKCE confidential client 預設啟用 | Validated | `ClientRegistration.java` L780 `requireProofKey=true` default + `DefaultOAuth2AuthorizationRequestResolver.getBuilder()` PKCE applier |
| IdP client config 接受 PKCE on confidential client | **Hypothesis** | IdP-side per-client setting 我看不到；smoke 跑成功才能 confirm；fallback 已記錄（§2.2 #3） |
| 真實 IdP claim shape | **Hypothesis** | `claims_supported` metadata 缺；本 spec 跑 smoke 才知道；這就是 trial 目的 |
| OidcUser fetched via userinfo_endpoint 行為 | Validated | `OidcUserRequestUtils.shouldRetrieveUserInfo()` 邏輯：userInfoUri non-empty + grant=AUTH_CODE → true |

兩個 Hypothesis：**(a) IdP 端 PKCE 接受度** — 標準上應該支援，smoke 跑一次就驗；**(b) claim shape** — 整個 spec 的目的就是揭露這個。本 spec 的 first run 等同 POC（per `/planning-spec` Sufficiency Gate「research-phase findings do NOT require an ADR」原則）。

### 2.4 Challenges Considered

1. **如果 IdP 拒絕 PKCE on confidential client** → 第一次 smoke `400 invalid_request` from token endpoint。Fix：`ClientRegistration` builder 加 `.clientSettings(ClientSettings.builder().requireProofKey(false).build())`。寫 follow-up commit；不擋本 spec 走完。
2. **如果真 IdP 完全沒給 `roles` claim** → `JwtAuthenticationConverter` 既有 `setAuthoritiesClaimName("roles")` → 走 S115 graceful empty list path（已驗）；`@PreAuthorize("hasRole('admin')")` 全部 fail-closed。trial 期不打到那些 endpoint 即可；後續 follow-up spec 對齊真 claim 名（可能是 `scope` per RFC 9068 default、可能是企業自訂 namespace 如 `omh:roles`）。
3. **如果 `OAuth2AuthorizedClientService` 拿不到 access token** → 預設 in-memory implementation；single-instance dev 應該 always 拿得到。如 NPE，fallback 是 `OidcUser.getIdToken().getTokenValue()` 走 id_token path（id_token 也是 JWT）。
4. **CSRF 與 OAuth2 Login** — Spring Security default 對 `/login/oauth2/code/*` 的 callback 不需 CSRF token（state param 防 CSRF）；既有 `http.csrf().disable()` 不變，無衝突。
5. **CORS allowlist 包含 IdP origin？** — 不需要。Browser 對 IdP 的請求是 top-level navigation（302 redirect），不是 XHR/fetch；CORS 不適用。既有 `SkillshubProperties.Cors.allowedOrigins` 不動。
6. **session cookie SameSite policy** — Spring Boot default `Lax`；IdP 302 回 callback 是 top-level navigation，cookie 會送（Lax 允許 top-level GET）。不需特別調。
7. **既有 `SecurityConfig.filterChain` 兩 branch（oauth.enabled true/false）改成三 branch** — 內部分支策略保持單一 bean，避免兩個 SecurityFilterChain bean 競爭（per 既有設計 comment）；新增最內層 `if (props.security().oauth().login().enabled())` 套疊在外層 `if (oauth.enabled)` 內。
8. **dev 預設 LAB 模式仍跑 mock-oauth2-server compose container** — 雖然 LAB 模式不打 mock，但 mock 會啟動。real-oauth profile active 時 mock 也會啟動但不互動。**無傷 + 無 surgery**。

### 2.5 Research Citations

- `JwtDecoderProviderConfigurationUtils.getConfigurationForIssuerLocation()` — probe order OIDC → OIDC-RFC8414-compat → OAuth-AS；4xx skip、其他錯立即 throw。Source: `spring-projects/spring-security` `oauth2/oauth2-jose/.../JwtDecoderProviderConfigurationUtils.java`
- `JwtDecoderProviderConfigurationUtils.validateIssuer()` + `JwtIssuerValidator` constructor — 兩段都 `issuer.equals(...)` 嚴格比對；trailing slash 不一致直接 fail（discovery 時 `IllegalStateException`、validate 時 401）
- `SupplierJwtDecoder` 用 `SingletonSupplier.of(...)` lazy；首個 decode 才 trigger discovery
- `OAuth2ResourceServerJwtConfiguration.JwtDecoderConfiguration` — `@ConditionalOnIssuerLocationJwtDecoder` + `@ConditionalOnMissingBean(JwtDecoder.class)`；設 `issuer-uri` 即自動建 `SupplierJwtDecoder`，但本專案有顯式 `@Bean`（覆蓋 auto-config，保留 `@ConditionalOnProperty` toggle 控制權）
- `ClientRegistrations.fromIssuerLocation()` — 同樣 3-endpoint probe；OIDC + RFC 8414 並行
- `DefaultOAuth2AuthorizationRequestResolver.getBuilder(ClientRegistration)` — PKCE auto-on 條件：`ClientAuthenticationMethod.NONE` || `clientSettings.isRequireProofKey()`；後者預設 true
- `ClientRegistration.Builder.clientSettings` default `ClientSettings.builder().build()` → `requireProofKey=true` per L780
- `OAuth2LoginAuthenticationFilter.DEFAULT_FILTER_PROCESSES_URI = "/login/oauth2/code/*"` — 對齊 user 註冊的 redirect URI
- `DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization"` — initiation endpoint
- `OidcAuthorizationCodeAuthenticationProvider` — 有 `openid` scope 走 OIDC path（fetch id_token + userinfo）；無則 fallback `OAuth2LoginAuthenticationProvider`
- `FilterOrderRegistration` — `OAuth2AuthorizationRequestRedirectFilter` ~1400、`OAuth2LoginAuthenticationFilter` ~1800、`BearerTokenAuthenticationFilter` ~2400；同 chain 順序明確無衝突
- IdP discovery dump（curl 2026-05-05）：see §1 visual flow box

無 hypothesis-grade 設計需要 formal POC — 第一次 smoke run 即等同 POC。

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

Scenario: AC-2 — discovery + JWKS 端點可達且配置匹配
  Given AC-1 通過
  When curl http://localhost:8080/oauth2/authorization/skillshub -I
  Then 回 302 Location: https://auth-dev.omnihubs.cloud/oauth2/authorize?client_id=596527ca-...&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Foauth2%2Fcode%2Fskillshub&scope=openid&...
  And query string 含 code_challenge=<base64url> + code_challenge_method=S256（PKCE 啟用）
  And query string 含 state=<random>（CSRF 防護）

Scenario: AC-3 — 完整 login flow（瀏覽器手動跑）
  Given AC-2 通過；developer 開無痕分頁打 http://localhost:8080/oauth2/authorization/skillshub
  When 完成 IdP 登入頁輸入 credentials → 點 confirm
  Then IdP 302 回 http://localhost:8080/login/oauth2/code/skillshub?code=...&state=...
  And backend 換 token 成功（log 不出現 InvalidClientException / InvalidGrantException）
  And 最終 browser landing 在 http://localhost:8080/（首頁＝搜尋頁，per S002）
  And browser DevTools Application tab 看到 JSESSIONID cookie

Scenario: AC-4 — `/api/v1/dev/auth-debug` 回真實 token claim shape
  Given AC-3 完成（session cookie 仍在 browser）
  When 同 browser GET http://localhost:8080/api/v1/dev/auth-debug
  Then 回 200 JSON
  And JSON 含至少 keys: principal_name / oidc_user_attributes / id_token_claims / access_token_claims / authorities
  And id_token_claims.iss == "https://auth-dev.omnihubs.cloud"（驗 trailing-slash 對齊）
  And id_token_claims.sub 非 null 非空
  And access_token_claims 為 decoded JWT payload Map

Scenario: AC-5 — bearer token 路徑同 issuer 也運作（dual-path）
  Given AC-4 完成；developer 從 access_token_claims 拿到完整 access_token（也可從 OAuth2AuthorizedClient）
  When curl -H "Authorization: Bearer <access_token>" http://localhost:8080/api/v1/dev/auth-debug（無 session cookie）
  Then 回 200 JSON
  And principal_name == JWT 的 sub
  And authorities 反映 RS converter 解析結果（可能空 list；取決於 IdP 是否給 roles claim）
  And 此驗證確認 oauth2Login + oauth2ResourceServer 同 chain 對 same issuer JWT 兩路皆過

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
  When run with @TestPropertySource setting issuer-uri="https://auth-dev.omnihubs.cloud/" (with slash)
  Then test asserts that property value 不被自動 normalize；
  And 提供 @DisplayName("AC-8: issuer-uri trailing-slash 必須對齊 metadata") 紀錄該設計約束
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

### 4.4 application-real-oauth.yaml.example — 新增（committed template）

```yaml
# =============================================================================
# Real OAuth IdP — local dev integration trial（S134）
# =============================================================================
# 用途：把本機 backend 切成「不打 mock-oauth2-server，改連真實 IdP」做整合試行。
# 本檔為 .example template；複製為 application-real-oauth.yaml（.gitignore 內）後填值。
#
# 啟動：SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun
# 還原 mock：移除 real-oauth 即可（./gradlew bootRun 預設 local,dev）
#
# 設定步驟：
#   1. cp config/application-real-oauth.yaml.example config/application-real-oauth.yaml
#   2. 填入 IdP 提供的 client_id / client_secret
#   3. 確認 issuer-uri 無 trailing slash（與 IdP metadata `issuer` 欄位一致）
#   4. 確認 IdP 端 redirect URI 註冊為 http://localhost:8080/login/oauth2/code/skillshub
# =============================================================================

skillshub:
  security:
    oauth:
      enabled: true              # 啟用 Resource Server JWT 鏈路
      login:
        enabled: true            # S134：啟用 OAuth2 Login（Client）authorization code flow

spring:
  security:
    oauth2:
      # ----- OAuth2 Client (S134 — server-side login) -----
      client:
        registration:
          skillshub:
            client-id: <<FILL_ME — IdP 提供的 client_id>>
            client-secret: <<FILL_ME — IdP 提供的 client_secret>>
            scope: openid                              # IdP scopes_supported 只列 openid
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
        provider:
          skillshub:
            # ⚠ 必須無 trailing slash — IdP metadata `issuer` 為 https://auth-dev.omnihubs.cloud
            # （不一致會在 ClientRegistrations.fromIssuerLocation 拋 IllegalStateException）
            issuer-uri: https://auth-dev.omnihubs.cloud

      # ----- Resource Server JWT (既有 S011；real-oauth profile override mock URL) -----
      resourceserver:
        jwt:
          issuer-uri: https://auth-dev.omnihubs.cloud    # 同上 — 無 trailing slash
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
| T04 | Manual E2E smoke — real IdP login flow + 真實 claim shape 紀錄（POC） | AC-2, AC-3, AC-4 (real), AC-5 (real), AC-6 (final regression) | pending |

**Execution order**：T01 → T02 → T03 → T04（T04 依賴前三 task；T04 user 端操作）

### AC Coverage

| AC | T01 | T02 | T03 | T04 |
|----|-----|-----|-----|-----|
| AC-1 profile yaml 載入 + boot ok | ✓ (gradlew bootRun manual + RealOAuthConfigTest 對 .example template assertion) | — | — | ✓ (real run final verify) |
| AC-2 discovery 302 + PKCE + state | — | — | — | ✓ (curl) |
| AC-3 完整 login flow → session | — | — | — | ✓ (browser manual) |
| AC-4 claim shape dump | — | ✓ (mock unit test 驗 endpoint contract) | — | ✓ (real claim shape 紀錄到 §7) |
| AC-5 bearer dual-path | — | ✓ (mock JwtAuthenticationToken path) | — | ✓ (real bearer curl) |
| AC-6 dev profile regression | ✓ (./gradlew test 既有全綠 + bootRun 預設仍 LAB) | — | — | ✓ (final regression check) |
| AC-7 FE 我的認證頁 | — | — | ✓ (Vitest + MSW) | — |
| AC-8 trailing-slash 設計約束 | ✓ (RealOAuthConfigTest) | — | — | — |

### Notes

- **T04 是 user-driven** — 必須 user 填 `application-real-oauth.yaml` 的 client_secret 並手動操作瀏覽器；assistant 只能準備工具與紀錄結果。Task loop 跑到 T04 會停下等 user 完成。
- **POC 風險回流**：若 T04 H1 (PKCE) fail，按 spec §2.4 #1 fallback：在 SecurityConfig 對 `skillshub` ClientRegistration 加 `.clientSettings(ClientSettings.builder().requireProofKey(false).build())` 然後重跑。屬範圍內 deviation，紀錄到 spec §7 deviation 段，不 escalate /planning-spec。
- **Frontend route placement** — T03 看 `frontend/src/App.tsx` 既有 routes 結構決定 path 加在哪；不另開 router config 檔。
- **Mock-oauth2-server 不退役** — T01-T03 既有自動測試仍走 mock；T04 是唯一打真 IdP 的 task。

<!-- Section 7 added after implementation -->
