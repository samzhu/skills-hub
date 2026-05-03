# ADR-006: JWT + ACL Safety Policy

## Status

Accepted（2026-05-03；ship 同 v3.8.2 by S115）

## Context

Skills Hub 走 OAuth2 ResourceServer + JWT + 6 個 ACL spec（S016 / S017 / S026 / S038 / S060 / S114a 設計中）。歷史路徑對 JWT claim parsing 缺整體 graceful degradation policy：

- `CurrentUserProvider.current()` 對 JWT `sub` 缺失走 `jwt.getName()` 直接 NPE → 500（user-visible 災難）
- `roles` / `groups` claim 走 `getClaimAsStringList(name)`：缺失或型別錯都 silent fallback 為 null，無 ops 觀測能力，IdP claim schema 偏離預期時 system 靜默退化
- 未來 IdP 加新 optional claim（如 `tenant_id` / `company_id`）時 forward-compat 行為未明
- 既有 `*:read` synthetic public principal (S026 / S038) 與未來 `company` principal (S114a) 安全行為缺統一矩陣

User 2026-05-03 mid-tick directive 明確要求把權限設計落成「安全設計文件」+ 當 token 內容跟原先設計不一樣時不要直接壞掉。

## Decision

### 1. Required vs optional claims

| Claim | Type | 必要性 | 缺失 / 型別錯 fallback | Rationale |
|-------|------|--------|------------------------|-----------|
| `sub` | String | **REQUIRED** | 401 + `WWW-Authenticate: Bearer error="invalid_token"` + log ERROR | RFC 7519 §4.1.2；無 sub 即無法 audit / ACL match |
| `roles` | List\<String\> | optional | `[]` empty list + WARN log + Micrometer counter | 無 role = 無 elevated perm；user 仍可走 user-pattern ACL |
| `groups` | List\<String\> | optional | `[]` empty list（缺 silent；型別錯 + WARN + counter） | 同 roles 邏輯；S016 既有 |
| 未知新 claim | any | optional | 完全忽略不破 parsing | Forward-compat IdP schema 演化 |

### 2. ACL principal types — 安全行為 matrix

| Principal type | Pattern | Source claim | Empty 缺值 fallback safe? | 備註 |
|----------------|---------|--------------|---------------------------|------|
| `user` | `user:<sub>:<perm>` | `sub` | ❌（sub REQUIRED；缺 → 401 早於 ACL） | S016 |
| `role` | `role:<name>:<perm>` | `roles[]` | ✅（無 role pattern → 不 match → fail-closed） | S016 |
| `group` | `group:<name>:<perm>` | `groups[]` | ✅（同上） | S016 |
| `*:read` | synthetic | none | ✅（永遠加，read perm 公共讀預設） | S026 / S038 |
| `company` | `company:<id>:<perm>` | `company_id` | ✅（缺 → 不擴此 pattern → fail-closed） | S114a 新加；本 ADR 為其提供 baseline |

**安全 invariant**：所有非 `user:` pattern 都 **fail-closed** — 缺 claim 等同沒有對應 grant，不會「意外」放行。

### 3. Spring Security OAuth2 error → HTTP code 路由

| 情境 | HTTP | Body | 是否 graceful |
|------|------|------|---------------|
| Token signature fail / expired / malformed | 401 | `WWW-Authenticate: Bearer error="invalid_token"` | ✅ Spring Security 預設 |
| Token missing | varies | depend on `requestMatchers` | ✅ S012 既有 |
| 必要 claim `sub` 缺 | 401 + Bearer header | `{error: "invalid_token", message: "missing sub claim"}` | ✅（本 ADR 補；取代既有 NPE 500） |
| Optional claim 型別錯 | 200 + degraded perm | normal | ✅（WARN log + Micrometer counter） |
| OIDC discovery 失敗 | 500 ⚠ | varies | ❌ defer（trim list） |

### 4. Observability

- 所有 claim anomaly 走 Micrometer counter `jwt_claim_anomaly_total{claim, reason}`
- WARN-level structured log 含 `claim` + `reason` + `actualType` (型別錯時)
- `reason` 取值：`missing` / `type_mismatch` / `non_string_element`
- Future ops alerting threshold 留 polish backlog（觀察基線後再定）

## Consequences

**Pros**：
- Forward-compat — IdP claim schema 演化（加新 claim / 改 optional 順序）不破系統
- Observable degradation — silent fallback 給 ops counter 監測 IdP 偏離
- Prod fallback branch 不被誤觸 — `oauth.enabled=true` 模式下 anonymous fallback branch 3 (labUserId/admin) 不該被打到（per spec AC-8；本 commit defer test 但 invariant 在 doc 鎖住）
- 安全核心守住 — `sub` REQUIRED + 所有非 user pattern fail-closed

**Cons**：
- Silent degradation 由 ops alerting 補（型別錯 user 拿不到 roles 但 request 200，無 user-visible signal）— 需 observe counter base line + alarm threshold
- AC-8 prod fallback guard test defer 至 follow-up（spec 級別 invariant，未自動化驗證）

**Trim list (defer)**：
- AC-8 端到端 prod fallback guard test（@SpringBootTest + Spring profile + counter assertion infra）
- JwtDecoder OIDC discovery 失敗 graceful 503（MVP 走 Spring 預設 500）
- Micrometer counter Grafana dashboard panel JSON
- 多 IdP / multi-issuer trust（issuer-uri 加 array 支援）
- Token revocation blocklist（走 IdP 端 + JWT short TTL）

## Alternatives Considered

### A. 嚴格 schema validation（缺 claim → 401）
**Rejected**：IdP 改一個 optional claim 即整個系統 down；違反 user directive「不直接壞掉」。

### B. 完全寬鬆（缺 claim → 各自走 fallback，不區分必要性）
**Rejected**：`sub` 缺也走 fallback = 安全災難（無法 audit / ACL match 全部壞）。

### C. 必要 vs optional 分層（**Accepted**）
`sub` 唯一 REQUIRED；其他全 optional 走「安全保守值 + WARN log + counter」。安全核心守住 + 演化彈性 + 觀測性。

## Related

- Spec: `docs/grimo/specs/archive/2026-05-03-S115-jwt-acl-safety-design.md`
- Pre-existing infrastructure: S012 (CurrentUserProvider) / S016 (Row-Level ACL) / S026 (Public-Read Default) / S038 (ACL List `*:read`) / S060 (ACL alignment)
- Future: S114a (RBAC + `company` principal) builds on §2 matrix

## Implementation Snapshot（v3.8.2 ship）

- `backend/.../shared/security/CurrentUserProvider.java`：sub null check + `parseStringListClaim` helper（型別錯 fallback empty + 非字串 element skip per element + counter）
- `backend/.../shared/security/JwtClaimAnomalyMetrics.java`：Micrometer counter wrapper component
- `backend/.../shared/api/MissingJwtSubException.java`：401 + Bearer header
- `backend/.../shared/api/GlobalExceptionHandler.java`：handle MissingJwtSubException → 401 + WWW-Authenticate
- `backend/src/test/.../shared/security/CurrentUserProviderTest.java`：11 個 unit tests（5 既有 + 6 S115 新加）

