# S115: JWT + ACL Safety Design — Schema Evolution + Graceful Degradation

> Spec: S115 | Size: M(8-10) | Status: 📐 Design
> Date: 2026-05-03
> Pair doc: ADR-006 JWT/ACL Safety Policy（同 commit 寫入 `docs/grimo/adr/`）
> User directive: 2026-05-03 mid-tick interrupt — 把 JWT Token + ACL 控制落成「安全設計文件」，當 token 內容跟原先設計不一樣時不要直接壞掉

---

## 1. Goal

把既有 OAuth2 ResourceServer + JWT + ACL 安全堆疊的「**graceful degradation policy**」明確落成設計文件 + 程式守則 + 自動化測試矩陣 — 以便：

1. **JWT claim schema 演化**（IdP 改 claim 名稱 / 改型別 / 加新 claim / 移除舊 claim）時系統**不直接壞掉**（不噴 500、不一律 401、不誤放權限）
2. **新加入的開發者 / 維運**有單一 reference doc 知道「哪個 claim 是必要的、哪個 fallback 安全保守值是什麼」
3. **既有 6 個 ACL spec（S016 / S017 / S026 / S038 / S060 / S114a）的安全 invariant 統一彙整 + Negative test 矩陣** — 任何回歸由 CI 守住
4. **配合 S114a RBAC ACL projection** — S114a ship 時 `company` principal type 新加入；本 spec 為 S114a 提供 safety baseline

**起源**：user 2026-05-03 mid-tick directive 明確要求「把權限設計落成安全設計文件 + 當 token 內容跟原先設計不一樣時不要直接壞掉」。當前 codebase 已 partial graceful（`CurrentUserProvider` 對 `roles` / `groups` null 走 empty list），但缺：
- 整體 policy doc + ADR
- `sub` claim 缺失時 NPE 風險（`jwt.getName()` 假設 non-null）
- claim type mismatch 觀測能力（silent fallback 為 empty 等同 deny — 但無 ops alerting）
- Cross-cutting negative test 矩陣（壞 token / 缺 claim / 型別錯）

**非目標**（本 spec 不做）：
- JWT 簽發方變更（IdP migration / multi-issuer support） — defer
- Token revocation / blocklist — defer，OIDC discovery 機制依靠 IdP 端
- Custom claim transformation pipeline（ETL claim → role） — 走 JwtAuthenticationConverter 既有 path 不擴
- ACL principal types 新增 — 落在 S114a；本 spec 只 codify 既有行為 + 為新 type 提供 schema 範本

**Visual flow** — graceful claim parsing：

```
HTTP request with Authorization: Bearer <jwt>
   ↓
Spring Security OAuth2 ResourceServer JwtDecoder
   ├─ signature fail / expired / malformed → 401 Unauthorized + WWW-Authenticate header  ✓ (Spring Security 預設)
   └─ valid signature →
       ↓
JwtAuthenticationConverter (S012 既有)
   ├─ extract sub → principal name
   │     └─ sub missing / null / blank → 401 (sub 是必要 claim；不 fallback)  ⚠ 本 spec 補
   ├─ extract roles claim → ROLE_ authorities
   │     ├─ roles 為 List<String> → 正常映射
   │     ├─ roles 缺 → fallback []  ✓
   │     ├─ roles 型別錯（如 String / List<Object>） → log WARN + fallback []  ⚠ 本 spec 補
   │     └─ roles 含非字串元素 → skip 該元素 + log WARN  ⚠ 本 spec 補
   ↓
CurrentUserProvider.current()
   ├─ JwtAuthenticationToken path → CurrentUser(sub, roles[], groups[])
   │     └─ groups claim 同 roles 三條 fallback path  ⚠ 本 spec 補
   ├─ Other authenticated → name + authorities (strip ROLE_) + groups=[]
   └─ Anonymous fallback → (labUserId, ["admin"])  ⚠ 本 spec 加註：fallback 僅 dev/lab 模式合法；prod 須驗 SecurityFilter chain 已驗證
   ↓
@PreAuthorize / AclPrincipalExpander
   └─ 用 currentUser.userId() + roles + groups expand "type:principal:perm" pattern
       └─ ACL JSONB ?| match
```

## 2. Approach

走 **defense-in-depth + observable degradation**：每個 claim parsing point 加 explicit fallback + WARN log + Micrometer counter（observability 給 ops 知道 IdP claim schema 偏離預期），但 **request 不 fail**（除非是 `sub` 必要 claim 缺失 → 401）。

### 2.1 Claim 必要性 vs fallback 三案比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| A. 嚴格 schema validation（缺 claim → 401） | 可預期；快速 fail | IdP 改一個 optional claim 即整個系統 down；違反 user directive「不直接壞掉」 | |
| B. 完全寬鬆（缺 claim → 各自走 fallback） | 永不 fail；最 graceful | `sub` 缺也走 fallback = 安全災難（無法 audit / ACL match 全部壞） | |
| C. **必要 vs optional 分層**：`sub` 必要（缺 → 401）；其他全 optional（缺 → 安全保守值 + WARN log） | 安全核心守住 + 演化彈性 + 觀測 | 需謹慎決定哪些 claim 必要 | ⭐ |

走 **C**。`sub` 為唯一必要 claim（無 sub 即無法歸屬 audit / ACL；缺即 401）。其他全 optional 走 fallback。

### 2.2 Fallback 安全保守值 matrix

「保守」= 最小權限原則（fail-closed）：

| Claim | Type | 必要性 | 缺失 / 型別錯 fallback | Rationale |
|-------|------|--------|------------------------|-----------|
| `sub` | String | **REQUIRED** | 401 Unauthorized + log ERROR | 無法 audit / ACL match；prod 不允許 |
| `roles` | List\<String\> | optional | `[]` empty list（無任何 role）+ log WARN | 無 role = 無 elevated perm；user 只能用自己 user-level ACL |
| `groups` | List\<String\> | optional | `[]` empty list + log WARN | 同 roles 邏輯 |
| `scope` | String | optional | preserve as-is（不 parse）+ no log | OAuth2 scope；本 codebase 不直接 enforce，留 IdP 端 |
| `iss` / `aud` / `exp` | OAuth standard | enforced by Spring | JwtDecoder 直接 reject + 401 | RFC 7519 標準；不可 fallback |
| `company_id` / `dept_id` | String | optional | null + no log | S016 既有可選欄位；用於 `company` principal type（S114a 新加）擴 |
| **未知新 claim** | any | optional | 完全忽略（不破 parsing）| Forward-compat — IdP 加新 claim 不應 break 既有 system |

### 2.3 ACL Principal types — 既有 + future safety matrix

| Principal type | Pattern | Source claim | Empty fallback safe? | 備註 |
|----------------|---------|--------------|----------------------|------|
| `user` | `user:<sub>:<perm>` | `sub` | ❌（sub REQUIRED；缺 → 401 早於 ACL） | S016 |
| `role` | `role:<name>:<perm>` | `roles[]` | ✅（無 role pattern → 不 match → fail-closed） | S016 |
| `group` | `group:<name>:<perm>` | `groups[]` | ✅（同上） | S016 |
| `*:read` | synthetic | none | ✅（永遠加，read perm 公共讀預設行為） | S026 / S038 |
| `company` | `company:<id>:<perm>` | `company_id` | ✅（缺 → 不擴此 pattern → fail-closed） | S114a 新加；本 spec 為其提供 safety baseline |

**安全 invariant**：所有非 `user:` pattern 都 fail-closed — 缺 claim 等同沒有對應 grant，不會「意外」放行。

### 2.4 既有 CurrentUserProvider 三 branch 安全 audit

對齊既有 `CurrentUserProvider.java` (S012)：

| Branch | 觸發場景 | 既有行為 | 安全 audit 結果 |
|--------|---------|----------|----------------|
| (1) JwtAuthenticationToken | OAuth 模式 + 有效 JWT | `jwt.getName()` for sub；`getClaimAsStringList("roles")` + null fallback；同 `groups` (S016) | ⚠ `getName()` 對 sub=null 直接 NPE（Spring 內部）→ 本 spec 補 explicit null check + 401 |
| (2) Other authenticated | LAB 模式 / 非 anonymous 已驗證 token | principal name + authorities (strip ROLE_)；groups 為 `[]` | ✅ 安全；無 NPE 風險（authentication.isAuthenticated() 為 false 時走 branch 3） |
| (3) Anonymous / no auth | 背景 thread / 測試未注入 SecurityContext | fallback `(labUserId, ["admin"])` | ⚠ Prod 走此 branch 屬 leak：應 SecurityFilter chain 早於 controller 守。本 spec 加 invariant：**prod (`oauth.enabled=true`) 模式下 branch 3 不該被打到**；加 Micrometer counter 監測 |

### 2.5 Spring Security OAuth2 error → HTTP code 矩陣

| 情境 | Spring 預設 | HTTP | Body | 是否 graceful |
|------|------------|------|------|--------------|
| Token signature fail | OAuth2AuthenticationException → BearerTokenAuthenticationEntryPoint | 401 | `WWW-Authenticate: Bearer error="invalid_token"` | ✅ |
| Token expired (`exp`) | 同上 | 401 | 同上 | ✅ |
| Token missing (`Authorization` header 空) | 走 anonymous → SecurityFilter chain 決定 | depend on `requestMatchers` | varies | ✅ (per S012 既有) |
| OIDC discovery 失敗（issuer-uri unreachable） | `SupplierJwtDecoder` lazy → 第一個 token request 拋 JwtException | 500 ⚠ | Spring 預設 ErrorResponse | ❌ — 本 spec 加 graceful（log ERROR + 503 retry-after） |
| Token signed but issuer 不在白名單 | Spring 內 jwt validators reject | 401 | invalid_token | ✅ |
| 必要 claim `sub` 缺 | 走 JwtAuthenticationConverter；Spring 預設拋 IllegalArgumentException | 500 ⚠ | varies | ❌ — 本 spec 加 GlobalExceptionHandler 路由 → 401 |
| Optional claim `roles` 型別錯 | `getClaimAsStringList` 內部 try-catch；返 null → fallback empty | 200 + degraded perm | normal | ✅ — 本 spec 補 WARN log + counter |

### 2.6 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| `sub` 必要 claim → 401 | Validated | RFC 7519 §4.1.2 sub 為 OAuth2 standard；無 sub 即 user 識別不能；對齊 Auth0 / Okta 既有產品行為 |
| `roles` / `groups` 缺 → empty list fallback | Validated | S012 既驗 — `getClaimAsStringList` Spring API contract 返 null on missing；既有 codebase 已運作 |
| `*:read` synthetic public 加入 read perm | Validated | S026 / S038 既驗 |
| Micrometer counter 給 claim 偏離 alerting | Validated | 既有 OpenTelemetry stack（Grafana LGTM per CLAUDE.md tech stack）；新加 counter 對齊既有 `download_count` / `flag_count` projection metrics path |
| `oauth.enabled=true` 模式下 fallback branch 3 不該被打到 | **Hypothesis** | 須跑端到端 test 驗證；本 spec 加端到端 negative test 矩陣 |

唯一 Hypothesis 為「prod 模式 fallback branch 不被打到」— 本 spec ship 即驗一輪。**不需 POC**。

### 2.7 Trim list

M(8-10) 一個 cron tick 可能 wall hit；可 defer 的 polish：

- **JwtDecoder OIDC discovery 失敗 graceful 503**（per §2.5 ⚠ row）— MVP 走 Spring 預設 500；polish 加自訂 ErrorHandler；defer
- **Micrometer counter dashboard panel**（Grafana JSON）— MVP 加 counter；dashboard JSON defer
- **多 IdP / multi-issuer trust**（issuer-uri 加 array 支援）— defer 至 IdP migration spec
- **Token revocation blocklist**（access_token revoke）— defer；走 IdP 端 + JWT short TTL

### 2.8 Research Citations

| 來源 | 用途 |
|------|------|
| RFC 7519 §4.1.2 (`sub`) | sub claim 必要性論證 |
| RFC 9068 (JWT Profile for OAuth 2.0 Access Tokens) | `roles` claim 命名慣例 |
| Spring Security 7 OAuth2 ResourceServer reference §3.4 | JwtAuthenticationConverter customization |
| Spring Security 7 reference §6.5 ExceptionHandling | BearerTokenAuthenticationEntryPoint default |
| Auth0 best practice 2024-Q4 | sub claim guarantee + role claim evolution pattern |
| OWASP API Security Top 10 2023 — API2 (Broken Authentication) | Negative test 範本 |
| Internal: `backend/.../shared/security/CurrentUserProvider.java` | 既有三 branch implementation |
| Internal: `backend/.../shared/security/SecurityConfig.java:159-171` | 既有 JwtAuthenticationConverter |
| Internal: `backend/.../shared/security/AclPrincipalExpander.java` | 既有 ACL pattern expansion |
| Internal: `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md` | ADR 寫法範本 |

## 3. SBE Acceptance Criteria

驗證指令：
- Backend：`./gradlew test`（含本 spec 加的 `JwtSafetyTest` + `CurrentUserProviderTest`）+ `./gradlew test --tests "*Security*Smoke*"`
- Doc：手工 review `docs/grimo/adr/ADR-006-jwt-acl-safety.md` 完整性

---

**AC-1：sub claim 缺失 → 401（不 NPE 500）**
- Given：OAuth 模式啟用；JWT 結構 valid 但 `sub` claim 缺（payload 不含 sub field）
- When：GET 任何 endpoint with this token
- Then：回 401 + ErrorResponse `{error: "invalid_token", message: "missing sub claim"}`；不噴 500；log ERROR 含 「missing sub claim」字樣

**AC-2：roles claim 型別錯（String 不是 List） → fallback []**
- Given：JWT `roles: "admin"`（String 而非 array）
- When：GET `/api/v1/me`
- Then：回 200 + body `roles: []`；log WARN 含「roles claim type mismatch」；Micrometer counter `jwt_claim_anomaly_total{claim="roles",reason="type_mismatch"}` +1

**AC-3：roles claim 含非字串元素 → skip 該元素 + 餘正常**
- Given：JWT `roles: ["admin", 42, null, "viewer"]`
- When：GET `/me`
- Then：回 200 + body `roles: ["admin", "viewer"]`；log WARN 2 次（int + null skip）；counter +2

**AC-4：groups claim 同上 fallback path**
- Given：JWT `groups: null`（顯式 null）
- When：GET `/me`
- Then：回 200 + body `groups: []`；log WARN；counter +1

**AC-5：未知新 claim 完全忽略（forward-compat）**
- Given：JWT 含未來 IdP 加的 `tenant_id`、`scope_v2` claims
- When：GET 任何 endpoint
- Then：回 200；既有 sub / roles / groups 解析正常；新 claim 不破 parsing；無 ERROR / WARN log

**AC-6：完整 OAuth happy path（regression — 不可破既有）**
- Given：JWT `{sub: "alice", roles: ["admin"], groups: ["eng"]}` valid
- When：GET `/me`
- Then：回 200 + body `{sub, roles, groups, ...}` 對應；對 admin-only endpoint 也通過 `@PreAuthorize`

**AC-7：JwtDecoder error → 401 + WWW-Authenticate header**
- Given：JWT 簽名 invalid（or expired exp）
- When：GET `/me`
- Then：回 401 + `WWW-Authenticate: Bearer error="invalid_token"` header；不噴 500

**AC-8：Anonymous fallback branch 在 `oauth.enabled=true` 下不被打到**
- Given：`skillshub.security.oauth.enabled=true`；無 Authorization header request
- When：GET `/api/v1/me`（authenticated endpoint）
- Then：回 401（Spring Security AnonymousAuthenticationFilter → AuthenticationEntryPoint）；**不**走 `CurrentUserProvider` 第三 branch fallback `(labUserId, ["admin"])`；Micrometer counter `jwt_anonymous_fallback_in_prod_total = 0`

**AC-9：LAB 模式 fallback branch 仍正常運作（regression）**
- Given：`oauth.enabled=false`；LAB filter inject lab user
- When：GET `/me`
- Then：回 200 + body `{sub: "lab-user-id", roles: ["admin"], groups: []}`（per labUserId 設定）

**AC-10：ACL principal types 安全 invariant**
- Given：user `alice` 無 roles 且無 groups（所有 optional claim 缺）
- When：嘗試對非 `*:read` 預設 ACL 的 skill `sk-private`（owner=`bob`，acl_entries=`["user:bob:write"]`）做 read
- Then：回 200 + 不 leak `sk-private` row（fail-closed — 無 user-pattern match）

**AC-11：ACL `*:read` synthetic public 仍預設加入 read（regression S026）**
- Given：user `alice` 無 roles + 無 groups
- When：read PUBLISHED skill（acl_entries 含 `*:read`）
- Then：回 200 + skill row（per synthetic public 預設行為）

**AC-12：ADR-006 doc 通過 review**
- Given：`docs/grimo/adr/ADR-006-jwt-acl-safety.md` ship
- When：手工 review
- Then：含「Status / Context / Decision / Consequences / Alternatives Considered」5 段；§Decision 內含 §2.2 fallback matrix + §2.3 principal types matrix + §2.5 error→HTTP matrix 三表；§Consequences 含 trim list（per §2.7）

## 4. Interface / API Design

### 4.1 No new REST endpoints

本 spec 為 cross-cutting safety policy；**不**加新 endpoint。既有 `/api/v1/me` (S012) 既驗為 JWT claim 暴露 endpoint，本 spec 補 graceful parsing 路徑後既有 endpoint 行為更 robust。

### 4.2 ADR-006 doc

```markdown
# ADR-006: JWT + ACL Safety Policy

## Status
Accepted（2026-05-03）

## Context
（per spec §1 Goal）

## Decision

### Required vs optional claims
（per spec §2.2 fallback matrix）

### ACL principal types safety
（per spec §2.3 principal types matrix）

### Error → HTTP code routing
（per spec §2.5 矩陣）

### Observability
所有 claim anomaly 走 Micrometer counter `jwt_claim_anomaly_total`
（labels: claim, reason）+ WARN-level structured log。

## Consequences
- Pros: forward-compat IdP claim schema 演化；observable degradation；prod fallback
  branch 不被誤觸（AC-8）
- Cons: silent degradation（型別錯時 user 拿不到 roles 但 request 200）— 由 ops
  alerting on counter 補；defer 自動 alarm threshold

## Alternatives Considered
（per spec §2.1 三案比較）
```

### 4.3 Backend — CurrentUserProvider 補 sub null check

```java
public CurrentUser current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth instanceof JwtAuthenticationToken jwt) {
        var token = jwt.getToken();
        var sub = token.getSubject();  // 替代 jwt.getName()，更明確
        if (sub == null || sub.isBlank()) {
            // S115 AC-1：missing sub claim → 401（caller controller 走 GlobalExceptionHandler）
            log.error("JWT missing sub claim; request aborted");
            jwtClaimAnomalyCounter.increment("sub", "missing");
            throw new MissingJwtSubException();
        }
        var roles = parseStringListClaim(token, "roles");
        var groups = parseStringListClaim(token, "groups");
        return new CurrentUser(sub, roles, groups);
    }
    // ... branch 2/3 既有 ...
}

/**
 * S115 AC-2/3 — 從 JWT 解 List<String> claim；型別錯 → empty list + WARN + counter；
 * 含 non-string 元素 → skip 該元素 + WARN per element；regression-safe。
 */
private List<String> parseStringListClaim(Jwt token, String claimName) {
    var raw = token.getClaim(claimName);
    if (raw == null) return List.of();  // missing → empty
    if (!(raw instanceof List<?> list)) {
        log.atWarn().addKeyValue("claim", claimName)
            .addKeyValue("actualType", raw.getClass().getSimpleName())
            .log("JWT claim type mismatch; falling back to empty list");
        jwtClaimAnomalyCounter.increment(claimName, "type_mismatch");
        return List.of();
    }
    var result = new ArrayList<String>(list.size());
    for (var item : list) {
        if (item instanceof String s) {
            result.add(s);
        } else {
            log.atWarn().addKeyValue("claim", claimName)
                .addKeyValue("element", item == null ? "null" : item.getClass().getSimpleName())
                .log("JWT claim contains non-string element; skipping");
            jwtClaimAnomalyCounter.increment(claimName, "non_string_element");
        }
    }
    return List.copyOf(result);
}
```

### 4.4 Backend — `MissingJwtSubException` + GlobalExceptionHandler 路由

```java
// shared/api/MissingJwtSubException.java (new)
public class MissingJwtSubException extends RuntimeException {
    public MissingJwtSubException() { super("missing sub claim"); }
}

// shared/api/GlobalExceptionHandler.java (modify)
@ExceptionHandler(MissingJwtSubException.class)
ResponseEntity<ErrorResponse> handleMissingJwtSub(MissingJwtSubException ex) {
    log.atError().addKeyValue("errorCode", "invalid_token").log("JWT missing required sub claim");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"missing sub claim\"")
        .body(new ErrorResponse("invalid_token", ex.getMessage(), Instant.now()));
}
```

### 4.5 Backend — Micrometer counter

```java
// shared/security/JwtClaimAnomalyMetrics.java (new)
@Component
public class JwtClaimAnomalyMetrics {
    private final MeterRegistry registry;

    public JwtClaimAnomalyMetrics(MeterRegistry registry) { this.registry = registry; }

    public void increment(String claim, String reason) {
        registry.counter("jwt_claim_anomaly_total",
                "claim", claim, "reason", reason).increment();
    }
}
```

對齊 existing OpenTelemetry stack（Grafana LGTM per CLAUDE.md）；`jwt_anonymous_fallback_in_prod_total` counter 加在 CurrentUserProvider branch 3 入口（per AC-8）。

### 4.6 Backend — Tests（負面測試矩陣）

```java
// backend/src/test/.../shared/security/JwtSafetyTest.java (new)
//   AC-1/2/3/4/5/7/10 走 @SpringBootTest + MockMvc + .with(jwt())
//
// backend/src/test/.../shared/security/CurrentUserProviderTest.java (new)
//   AC-1/2/3/4 走 unit-level mock JWT object（測 parseStringListClaim 邊界）
//
// backend/src/test/.../shared/security/JwtAnonymousFallbackProdGuardTest.java (new)
//   AC-8 走 @TestPropertySource(oauth.enabled=true) + 無 token request → 驗 401
//   + counter assertion (jwt_anonymous_fallback_in_prod_total = 0)
//
// 既有 LabModeMeControllerTest.java 跑 regression（AC-9）
```

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../shared/security/CurrentUserProvider.java` | modify | sub null check (`getSubject()` + 預檢) → throw MissingJwtSubException；`parseStringListClaim` helper for roles/groups graceful；inject JwtClaimAnomalyMetrics + log WARN |
| `backend/.../shared/security/JwtClaimAnomalyMetrics.java` | new | Micrometer counter wrapper component |
| `backend/.../shared/api/MissingJwtSubException.java` | new | RuntimeException for missing sub claim |
| `backend/.../shared/api/GlobalExceptionHandler.java` | modify | 加 `@ExceptionHandler(MissingJwtSubException)` → 401 + WWW-Authenticate header |
| `backend/src/test/.../shared/security/CurrentUserProviderTest.java` | new | unit test parseStringListClaim 邊界 + sub null check |
| `backend/src/test/.../shared/security/JwtSafetyTest.java` | new | @SpringBootTest end-to-end AC-1/2/3/4/5/7/10/11 |
| `backend/src/test/.../shared/security/JwtAnonymousFallbackProdGuardTest.java` | new | AC-8 — `oauth.enabled=true` 模式 fallback branch 不被打到 |

### Documentation

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/adr/ADR-006-jwt-acl-safety.md` | new | per §4.2 ADR 5-段範本 + 3 表 (§2.2 / §2.3 / §2.5) |
| `docs/grimo/specs/spec-roadmap.md` | modify | M110 row：📋 → 📐 in-design + 設計摘要 |
| `docs/grimo/development-standards.md` | modify | §Security 加「JWT claim parsing safety policy」段（指向 ADR-006） |
| `docs/grimo/glossary.md` | modify | 加 graceful degradation / fail-closed / claim anomaly 中英對照 |

### Frontend

無 frontend 改動（純 backend safety policy；既有 `/me` UI 行為不變，但 robustness 增加）。

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
