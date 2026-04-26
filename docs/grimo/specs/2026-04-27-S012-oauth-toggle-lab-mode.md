# S012: OAuth 開關 + LAB 模式

> Spec: S012 | Size: XS(8) | Status: ⏳ Design
> Date: 2026-04-27
> Depends: S011 (✅ shipped) — 沿用 SecurityConfig、MeController、AdminController 結構，本 spec 加 conditional 分支
> Research: `docs/deepwiki/mock-oauth2-server/`、Spring Security 7 SecurityFilterChain（S011 §2.5 已驗證）

---

## 1. Goal

加入 `skillshub.security.oauth.enabled`（預設 `true`）開關：在 LAB 環境設成 `false` 即可關掉整條 OAuth 鏈路，所有 endpoint 直接放行；同時提供 `CurrentUserProvider` 讓未來任何要記錄「誰做的」的 audit 欄位都能在 LAB 模式取得**同一組預設值**（`lab-user` + admin role），不需各自實作 fallback。

**簡單講**：LAB 開發測試功能，OAuth 是雜訊；一個布林屬性切掉，從 SecurityFilterChain 到 `/me` 到 `/admin/echo` 全部以「預設 lab user 身分」運作，未來 audit 欄位也直接讀 `CurrentUserProvider` 就好。

```
┌── ./gradlew bootRun （prod / 對外開發）─────────────────────────┐
│   skillshub.security.oauth.enabled = true (預設)                │
│       ↓                                                          │
│   SecurityConfig 走 S011 路徑：                                  │
│     - JwtDecoder + JwtAuthenticationConverter beans              │
│     - /api/v1/me, /api/v1/admin/** authenticated()              │
│     - JWT subject → CurrentUserProvider.userId()                │
└──────────────────────────────────────────────────────────────────┘

┌── ./gradlew bootRun （LAB / 純功能測試）──────────────────────┐
│   SKILLSHUB_SECURITY_OAUTH_ENABLED=false ./gradlew bootRun       │
│       ↓                                                          │
│   SecurityConfig 走 lab 路徑：                                   │
│     - JwtDecoder / Converter beans 不建立（@ConditionalOnProperty）│
│     - SecurityFilterChain.anyRequest().permitAll()              │
│     - LabSecurityFilter 注入 lab UsernamePasswordAuthenticationToken│
│         principal=lab-user, authorities=[ROLE_admin]             │
│     - CurrentUserProvider.userId() == "lab-user"                │
│     - /api/v1/admin/echo 因 lab user 帶 ROLE_admin → 200        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

### 2.1 設計（XS 規模）

使用者於 grill 階段確認三項決策：

| 決策 | 選擇 | 原因 |
|---|---|---|
| 切換機制 | property `skillshub.security.oauth.enabled`（預設 `true`） | 不另開 profile；env var 與 yaml 都能 override；CI/LAB 用 env var 最直接 |
| 預設 UserID | 固定字串 `lab-user` | 簡單一眼識別「來自 LAB」，未來 audit 欄位看到此值即知為非真實用戶 |
| `/admin/echo` 在 LAB | 仍然可訪問，預設 user 帶 `ROLE_admin` | LAB 測試者要能完整驗證 admin 路徑；不要為了 demo 殘缺體驗 |

### 2.2 與既有架構的契合

| 維度 | 現況（S011） | S012 變動 |
|---|---|---|
| `SkillshubProperties` | 4 nested records: `storage`, `search`, `genai`, `scanner` | 新增 `security` nested record（含 `oauth.enabled` 與 `lab.userId`） |
| `SecurityConfig` | 1 個 SecurityFilterChain bean + JwtDecoder + JwtAuthenticationConverter | SecurityFilterChain 內依 property 分支；JwtDecoder/Converter 加 `@ConditionalOnProperty` 只在 OAuth enabled 時建立 |
| `MeController` | 直接用 `@AuthenticationPrincipal Jwt` | 改用新 `CurrentUserProvider`，兩種模式都能正確取值 |
| `AdminController` | 維持 `@PreAuthorize("hasRole('admin')")` | **不動** — LAB 模式下因為注入 ROLE_admin 的 Authentication，`hasRole` 自然通過 |
| Profile / 設定檔 | `local`/`gcp` × `dev`/`prod` 雙層 | **不新增 profile**；只在 `application.yaml` 加 `skillshub.security.oauth.enabled: true`（顯式預設）並文件化「LAB 用 env var 覆寫為 false」 |

### 2.3 關鍵設計決策

1. **單一 SecurityFilterChain bean，內部分支** — 不用兩個 `@Order` chain。`HttpSecurity` builder 在 bean 方法內依 property 條件呼叫 `.oauth2ResourceServer(...)` 或 `.addFilterBefore(labFilter, ...)`。維持 S011 既有 bean 數量。
2. **JwtDecoder + JwtAuthenticationConverter 用 `@ConditionalOnProperty(matchIfMissing=true)` gate** — OAuth disabled 時兩 bean 不建立；省記憶體也避免 `JwtDecoders.fromIssuerLocation()` lazy supplier 殘留 issuer-uri 連線意圖。
3. **`LabSecurityFilter` 用 `UsernamePasswordAuthenticationToken` 而非 `JwtAuthenticationToken`** — 不偽造 JWT；用 Spring Security 標準的 username/password token，principal=`lab-user`、authorities=`[ROLE_admin]`，是「真實已認證」狀態（`isAuthenticated()=true`），@PreAuthorize / authenticated() 都通過。
4. **`CurrentUserProvider` 從 `SecurityContextHolder` 抽資料，不分模式** — 邏輯簡單：JwtAuthenticationToken → 取 JWT claims；UsernamePasswordAuthenticationToken（lab）→ 取 principal name + authorities。**單一抽象，未來 audit 欄位直接用，不需條件判斷**。
5. **AdminController 不改任何程式** — `@PreAuthorize("hasRole('admin')")` 在兩種模式都運作（OAuth 模式靠 JWT roles claim，LAB 模式靠 LabSecurityFilter 注入 ROLE_admin）。展現 Spring Security 抽象的正確使用。
6. **MeController 改用 CurrentUserProvider** — 既有 `@AuthenticationPrincipal Jwt jwt` 在 LAB 模式下會 NPE（principal 不是 Jwt 而是 String "lab-user"）。透過 `CurrentUserProvider.current()` 統一介面避開類別差異。
7. **預設 `oauth.enabled=true`** — production 行為不變；LAB 必須**顯式關閉**才生效。Fail-secure 預設。
8. **不建 `application-lab.yaml`** — 使用者明確選擇「不另開 profile」；env var `SKILLSHUB_SECURITY_OAUTH_ENABLED=false` 就夠用。CI / docker run / IDE run-config 都可帶。

### 2.4 Challenges Considered

1. **`@PreAuthorize` 在 LAB 模式為何會通？** — Spring Security 的 `hasRole('admin')` 檢查當前 Authentication 的 authorities 是否含 `ROLE_admin`。LabSecurityFilter 注入的 token 帶 `[ROLE_admin]`；判斷通過。**前提**：`@EnableMethodSecurity` 仍在 SecurityConfig（不能 conditional 掉，否則 OAuth 模式失效）。確認過：method security 在無 OAuth 時對 anonymous request 拋 AccessDenied，但對「已認證 user 帶 ROLE_admin」會通過——LabSecurityFilter 把 anonymous 替換成「已認證 lab user」。
2. **JwtDecoder bean 不建立會不會造成 SecurityConfig 拋 NoSuchBeanDefinitionException？** — S011 §7.3 Finding 1 已記載：SecurityConfig 顯式 `JwtDecoder` bean，`oauth2ResourceServer().jwt()` 從容器找。OAuth disabled 時不會呼叫 `oauth2ResourceServer()`，故不需要 JwtDecoder。`@ConditionalOnProperty` 就讓 bean 在 disabled 時不存在；無 NPE 風險。
3. **`@AuthenticationPrincipal Jwt jwt` 何時會壞？** — LAB 模式 principal 是 `String "lab-user"`，不是 `Jwt`。直接用 `@AuthenticationPrincipal Jwt` 會收到 `null`（Spring 看到型別不匹配回 null）。MeController 改用 `CurrentUserProvider` 即解決——provider 內部判斷 Authentication 子型別。
4. **SkillshubProperties 加新 nested record 會破壞既有 ctor？** — `SkillshubProperties` 是 record，加新 component 等於改 constructor signature。記憶中 S010 T1 §7 有相似 drift（為新增 Scanner record 必須更新測試的 ctor 呼叫）。本 spec 須 grep 既有 `new SkillshubProperties(...)` 呼叫並更新。
5. **既有 S011 測試會否破？** — MeControllerTest 用 `.with(jwt())` 注入 JwtAuthenticationToken；CurrentUserProvider 在 JwtAuthenticationToken 路徑回傳 JWT subject，行為等同。AdminControllerTest 直接帶 ROLE_admin authority，行為不變。SkillsApiAnonymousTest 不帶任何 auth，permitAll 路徑不變。理論上零回歸。
6. **CSRF / 既有 OAuthMockE2ETest？** — E2E 測試不啟動 Spring 應用（pure JUnit + Testcontainers），與 SecurityConfig 無關，不受影響。
7. **未來 audit 欄位的 CurrentUserProvider 用法？** — Domain Event 或 Aggregate 建立時 `private final CurrentUserProvider currentUser;` constructor injection；事件 payload 加 `createdBy = currentUser.userId()`。LAB 模式統一寫 `lab-user`，prod 寫 JWT subject。本 spec 不實作任何 audit 欄位（forward-looking only），僅提供 provider。

### 2.5 Research Citations

S011 §2.5 已涵蓋 Spring Security 7 / Spring Boot 4 OAuth2 RS API 全部 surface。本 spec 用到的新 API 點（已驗證）：

- [Spring Security `@ConditionalOnProperty` 控 SecurityFilterChain bean](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.conditionals) — 與 S009 / S010 SkillshubProperties 同 pattern
- [Spring Security `UsernamePasswordAuthenticationToken` 作為 stateful auth 標記](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html#servlet-authentication-usernamepasswordauthenticationtoken) — 標準用法
- `SecurityContextHolder.getContext().setAuthentication()` 注入 — Spring Security 標準 SPI
- [Spring Security `addFilterBefore`](https://docs.spring.io/spring-security/reference/servlet/architecture.html#adding-custom-filter) — 加自訂 filter 到 chain
- 內部研究：S011 §7.3 已記載 SecurityConfig + JwtDecoder bean 互動

無 Hypothesis-grade 設計決策；POC 不需要。

---

## 3. SBE Acceptance Criteria

> 驗證指令：`cd backend && ./gradlew test --tests "*S012*"` 或 `./gradlew test`
> 測試類別：`backend/src/test/java/io/github/samzhu/skillshub/shared/security/`，每 AC 用 `@Tag("AC-N")` + `@DisplayName`

```gherkin
Scenario: AC-1 — OAuth enabled (default) 行為與 S011 一致
  Given 沒有設定 SKILLSHUB_SECURITY_OAUTH_ENABLED 或設定為 true
  When 跑全套既有 S011 測試（MeControllerTest, AdminControllerTest, OAuthMockE2ETest, SkillsApiAnonymousTest）
  Then 全部 9 個既有測試仍通過
  And /api/v1/me 帶 admin token 回 200 + 完整 claims（與 S011 AC-4 行為相同）
  And /api/v1/admin/echo 不帶 token 回 401（authenticated() 擋下）

Scenario: AC-2 — OAuth disabled，permitAll 生效
  Given skillshub.security.oauth.enabled=false
  When 不帶 Authorization header 打 GET /api/v1/me
  Then 回 200
  And response body 含 sub="lab-user", roles=["admin"]
  When 不帶 token 打 GET /api/v1/admin/echo?msg=hello
  Then 回 200
  And response body 含 echo="hello", by="lab-user"

Scenario: AC-3 — OAuth disabled 時 JwtDecoder bean 不存在
  Given oauth.enabled=false 啟動 ApplicationContext
  When 從容器查詢 JwtDecoder bean
  Then 拋 NoSuchBeanDefinitionException（或 getBeansOfType 回空 map）
  And SecurityFilterChain 仍存在
  And LabSecurityFilter 已掛在 chain 上

Scenario: AC-4 — CurrentUserProvider 在兩種模式回傳一致介面
  Given oauth.enabled=true 模式，SecurityContext 含 JwtAuthenticationToken (sub=alice)
  When 呼叫 currentUserProvider.userId()
  Then 回 "alice"
  Given oauth.enabled=false 模式，SecurityContext 含 LabSecurityFilter 注入的 token
  When 呼叫 currentUserProvider.userId()
  Then 回 "lab-user"

Scenario: AC-5 — CurrentUserProvider 在無 SecurityContext 時的安全 fallback
  Given SecurityContextHolder.getContext().getAuthentication() == null（如 background thread 未繼承 context）
  When 呼叫 currentUserProvider.userId()
  Then 回 "lab-user"（不丟 NPE）

Scenario: AC-6 — SkillshubProperties.security 結構正確
  Given application.yaml 含 skillshub.security.oauth.enabled
  When ApplicationContext 啟動
  Then SkillshubProperties.security() 不為 null
  And security.oauth().enabled() 反映 yaml 值
  And security.lab().userId() 預設為 "lab-user"

Scenario: AC-7 — 既有 S001~S011 端點在 OAuth disabled 時仍正常
  Given oauth.enabled=false
  When GET /api/v1/skills（既有 S001）
  Then 回 200（與 S001 行為一致）
  When POST /api/v1/skills/upload（既有 S003）
  Then 不被 SecurityFilterChain 擋下（業務邏輯仍正常運作）
```

---

## 4. Interface Design

### 4.1 SkillshubProperties.Security 新 nested record

```java
// 加到 SkillshubProperties.java 既有 record 旁
public record SkillshubProperties(
        @DefaultValue Storage storage,
        @DefaultValue Search search,
        @DefaultValue GenAI genai,
        @DefaultValue Scanner scanner,
        @DefaultValue Security security) {

    /**
     * Security 設定 — OAuth 開關 + LAB 模式預設值。
     */
    public record Security(
            @DefaultValue OAuth oauth,
            @DefaultValue Lab lab) {}

    /**
     * @param enabled OAuth 是否啟用；預設 true（fail-secure）。
     *                LAB 環境設 false 跳過整條 JWT 驗證。
     */
    public record OAuth(@DefaultValue("true") boolean enabled) {}

    /**
     * @param userId LAB 模式下 CurrentUserProvider 回傳的預設 userId。
     */
    public record Lab(@DefaultValue("lab-user") String userId) {}
}
```

### 4.2 CurrentUser record

```java
// backend/src/main/java/io/github/samzhu/skillshub/shared/security/CurrentUser.java
package io.github.samzhu.skillshub.shared.security;

import java.util.List;

/**
 * 當前請求的使用者識別 — 從 SecurityContext 抽出的最小欄位集合。
 *
 * <p>OAuth 模式來自 JWT claim；LAB 模式來自 {@link LabSecurityFilter} 注入的預設值。
 *
 * @param userId    JWT sub 或 lab 預設值
 * @param roles     角色清單（去掉 ROLE_ 前綴的業務語意值）
 */
public record CurrentUser(String userId, List<String> roles) {}
```

### 4.3 CurrentUserProvider

```java
// backend/src/main/java/io/github/samzhu/skillshub/shared/security/CurrentUserProvider.java
package io.github.samzhu.skillshub.shared.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * 統一抽象「當前使用者識別」— OAuth 模式回 JWT subject、LAB 模式回預設值。
 *
 * <p>未來任何需要記錄 createdBy / updatedBy 的 audit 欄位，
 * constructor 注入本 provider 並呼叫 {@link #current()} 即可。
 */
@Component
public class CurrentUserProvider {

    private final String labUserId;

    public CurrentUserProvider(SkillshubProperties props) {
        this.labUserId = props.security().lab().userId();
    }

    public CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            var token = jwt.getToken();
            var roles = token.getClaimAsStringList("roles");
            return new CurrentUser(jwt.getName(), roles == null ? List.of() : roles);
        }
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            // LAB 模式：LabSecurityFilter 注入的 UsernamePasswordAuthenticationToken
            var roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
            return new CurrentUser(auth.getName(), roles);
        }
        // 無 SecurityContext (e.g. background thread)：safe fallback
        return new CurrentUser(labUserId, List.of("admin"));
    }

    public String userId() {
        return current().userId();
    }
}
```

### 4.4 LabSecurityFilter — 注入預設 lab Authentication

```java
// backend/src/main/java/io/github/samzhu/skillshub/shared/security/LabSecurityFilter.java
package io.github.samzhu.skillshub.shared.security;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LAB 模式專用 — 把預設 lab user 注入 SecurityContext，
 * 讓 @PreAuthorize / @AuthenticationPrincipal / CurrentUserProvider 都能正確運作。
 *
 * <p>非 OAuth 模式 SecurityFilterChain 會 addFilterBefore 把本 filter 放在
 * 任何 method security / authorization filter 之前。
 */
public class LabSecurityFilter extends OncePerRequestFilter {

    private final String labUserId;

    public LabSecurityFilter(String labUserId) {
        this.labUserId = labUserId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var auth = new UsernamePasswordAuthenticationToken(
            labUserId,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_admin"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
```

### 4.5 SecurityConfig 改寫（branch on property）

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private final SkillshubProperties props;

    SecurityConfig(SkillshubProperties props) {
        this.props = props;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (props.security().oauth().enabled()) {
            // ── OAuth 模式（S011 行為）──
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/me").authenticated()
                    .requestMatchers("/api/v1/admin/**").authenticated()
                    .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        } else {
            // ── LAB 模式 ──
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                    new LabSecurityFilter(props.security().lab().userId()),
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        }
        http.csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "skillshub.security.oauth", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(/* @Value 同 S011 */) { /* ... */ }

    @Bean
    @ConditionalOnProperty(prefix = "skillshub.security.oauth", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    JwtAuthenticationConverter jwtAuthenticationConverter() { /* 同 S011 */ }
}
```

### 4.6 MeController 改用 CurrentUserProvider

```java
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final CurrentUserProvider users;

    MeController(CurrentUserProvider users) {
        this.users = users;
    }

    @GetMapping
    Map<String, Object> me() {
        var u = users.current();
        var result = new LinkedHashMap<String, Object>();
        result.put("sub", u.userId());
        result.put("roles", u.roles());
        return result;
    }
}
```

> AdminController 不變（@PreAuthorize 兩模式都運作）。

### 4.7 application.yaml 增量

```yaml
# backend/src/main/resources/application.yaml — 加 skillshub.security 區塊
skillshub:
  security:
    oauth:
      enabled: true        # 預設啟用；LAB 環境用 SKILLSHUB_SECURITY_OAUTH_ENABLED=false 覆寫
    lab:
      user-id: lab-user
```

---

## 5. File Plan

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/SkillshubProperties.java` | M | 加 `Security` / `OAuth` / `Lab` nested records |
| `backend/src/main/java/.../shared/security/SecurityConfig.java` | M | 內部 branch on `props.security().oauth().enabled()`；JwtDecoder/Converter 加 `@ConditionalOnProperty` |
| `backend/src/main/java/.../shared/security/MeController.java` | M | 改用 `CurrentUserProvider` 取代 `@AuthenticationPrincipal Jwt` |
| `backend/src/main/java/.../shared/security/CurrentUser.java` | A | record |
| `backend/src/main/java/.../shared/security/CurrentUserProvider.java` | A | provider bean |
| `backend/src/main/java/.../shared/security/LabSecurityFilter.java` | A | OncePerRequestFilter 注入 lab Authentication |
| `backend/src/main/resources/application.yaml` | M | 加 `skillshub.security.{oauth,lab}` 區塊 |
| `backend/src/test/java/.../shared/security/LabModeMeControllerTest.java` | A | AC-2 (/me) — 用 `@TestPropertySource("skillshub.security.oauth.enabled=false")` |
| `backend/src/test/java/.../shared/security/LabModeAdminControllerTest.java` | A | AC-2 (/admin/echo) |
| `backend/src/test/java/.../shared/security/CurrentUserProviderTest.java` | A | AC-4, AC-5（unit test，不需 SpringBootTest，直接 mock SecurityContextHolder） |
| `backend/src/test/java/.../shared/security/JwtDecoderConditionalTest.java` | A | AC-3 — 驗 OAuth disabled 時 JwtDecoder bean 不存在 |
| `backend/src/test/java/.../shared/security/SkillshubSecurityPropertiesTest.java` | A | AC-6 — `@SpringBootTest` 注入 SkillshubProperties 驗 binding |
| `backend/src/test/java/.../search/SearchConfigTest.java` 等 | M（如有） | 既有 `new SkillshubProperties(...)` ctor 呼叫須加 Security 參數 |

**檔案總數：3 modify + 6 add = 9** + 視 grep 結果可能再加幾個既有測試小修，落在 XS 上限。

既有 S011 測試（MeControllerTest, AdminControllerTest, OAuthMockE2ETest, SkillsApiAnonymousTest）**不修改**——AC-1 要求 default 模式行為與 S011 一致。

---

## 6. Task Plan

> 由 `/planning-tasks S012` 產生。

---

## 7. Implementation Results

> 由 `/planning-tasks S012` 完成所有 tasks 後彙整。

---

## Estimation

| Dimension | Score | Reason |
|---|---|---|
| Technical risk | 1 | `@ConditionalOnProperty` + `OncePerRequestFilter` + `SecurityContextHolder` 都是 Spring 標準 SPI |
| Uncertainty | 1 | 全部 API 在 S011 §2.5 已驗證；本 spec 只做組合 |
| Dependencies | 1 | 無新外部依賴 |
| Scope | 2 | 3 modify + 6 add = 9 檔（其中 4 個是測試） |
| Testing | 2 | 兩種模式各需測一遍；有 SpringBoot context 切換需求（@TestPropertySource） |
| Reversibility | 1 | 純 toggle + 新檔案；可完全還原回 S011 |
| **Total** | **8** | **XS** |
