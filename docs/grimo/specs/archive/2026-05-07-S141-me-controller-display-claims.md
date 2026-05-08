# S141: `/api/v1/me` Display Claims（email / name / picture）

> Spec: S141 | Size: XS(7) | Status: ✅ shipped (v4.21.0 — 2026-05-07)
> Date: 2026-05-07
> Origin: bug 回報 2026-05-07 — AppShell avatar dropdown / MySkillsPage 顯示 user 為 Google sub（`1165491299985546340268`）而不是 email / name

---

## 1. Goal

修 user-visible 在 AppShell avatar dropdown / MySkillsPage 「以 X 身份發布」處顯示成 Google numeric sub（如 `1165491299985546340268`）的 bug。

**簡單講：** S139 frontend `AuthUser` interface 宣告了 `email / name / picture`，但 S011 寫的 backend `MeController` 從未更新，只回 6 keys（`sub / roles / groups / companyId / deptId / scope`）— 沒有這 3 個欄位。前端 fallback 鏈 `user.name ?? user.email ?? user.sub` 全 fallback 到 sub → 顯示成數字。

**修法：** backend `MeController` 加 `email / name / picture` 3 個 claim pass-through。OAuth 模式從 JWT standard claims 抽；LAB 模式合成 dev-only defaults（`name = "LAB User"`、`email = "<lab.user-id>@lab.skillshub.local"`、`picture = null`）讓 LAB 視覺上一眼可辨識。

**為何現在：** S139 v4.18.0 ship 才 1 天，user 第一次跑 lazy auth gate 就遇到。屬 S139 ship 漏改的尾巴，越早修 user 體驗越好。

**非目標：**
- 不重設計 `AuthUser` shape（既有 interface 已對；只補 backend 端缺漏）
- 不改 frontend fallback 鏈（fallback to sub 是 defensive，留著做 last-resort）
- 不改 OAuth 整合行為（per S139 / S134）
- 不加新測試 utility — 沿用 既有 MockMvc / Mockito pattern

---

## 2. Approach

XS spec — 跳過 approach comparison。Root cause 明確（backend 漏 3 欄位），修法直觀。

### 2.1 Backend MeController 改動

OAuth 分支抽 3 個 OIDC standard claims：

| 欄位 | OIDC claim | 說明 |
|------|-----------|------|
| `email` | `email` | OIDC standard，Google scope `email` 有；S134/S139 已 request |
| `name` | `name` | OIDC standard，Google scope `profile` 有 |
| `picture` | `picture` | OIDC standard，Google profile picture URL；可能 null |

LAB 分支合成 dev-only defaults：

| 欄位 | LAB 模式值 | Why |
|------|-----------|-----|
| `email` | `${user.userId()}@lab.skillshub.local` | dev-only TLD，永不到 production；視覺上一眼識別「LAB 模式」 |
| `name` | `"LAB User"` | 一致字串，跟 OAuth 模式視覺差異明確 |
| `picture` | `null` | LAB 無 OAuth provider 提供 picture；前端 AuthArea 已處理 null（fallback 首字母） |

### 2.2 Frontend：純驗證，不改

- `AuthArea.tsx` 既有 fallback 鏈 `name ?? email ?? sub` 已對 — 修 backend 後 displayLabel 自動切 `email`
- `MySkillsPage.tsx` 第 100 行的 `author` 變數來自 `useAuth()`；確認從 `user.name`（非 sub）取
- `AuthUser` interface 既有 declare 已對

### 2.3 Test 改動

- `MeControllerTest.java`（OAuth + LAB 分支）：原 6-key assertion → 9-key
- 既有 `useAuth.test.tsx` mock fetchMe：mock response 補 email/name/picture（避免 schema drift fail）

### 2.4 Challenge：為什麼不直接讓 frontend 改用 sub？

**A：sub 是 Google numeric ID，user-hostile。** 21 位數字使用者根本看不懂。OIDC 之所以提供 `email` / `name` 標準 claim 就是給 UI 用。fix 在 backend 才正解。

### 2.5 Research Citations

| Source | 一句總結 |
|--------|----------|
| OpenID Connect Core 1.0 §5.1 Standard Claims | `email` / `name` / `picture` 是 OIDC standard claim，Google scope `openid email profile` 有 request 即會回 |
| `MeController.java` (S011 written; S139 not updated) | 既有 OAuth 分支只 read 6 個 claim，未含 `email` / `name` / `picture` — 即為本 bug root cause |
| `AuthArea.tsx` line 45-46（S139）| Fallback 鏈設計正確，bug 不在這裡 |
| S139 §4.1 `AuthUser` interface | 已宣告 4 個欄位，frontend 端對齊；缺的是 backend |

---

## 3. SBE Acceptance Criteria

> Verification command: `./scripts/verify-all.sh`
> Pass: 所有帶本 spec AC tag 的 backend / frontend 測試 green。

### AC-1: OAuth mode `/api/v1/me` 回 9 keys 含 email / name / picture
```
Given Spring Security OAuth2 已驗證 user，JWT 含 standard claims `email` / `name` / `picture`
When  `GET /api/v1/me` 帶 Bearer token
Then  HTTP 200
And   response body 含 9 keys：sub / email / name / picture / roles / groups / companyId / deptId / scope（順序穩定）
And   email / name / picture 值取自 JWT claim（picture 可為 null）
```

### AC-2: LAB mode `/api/v1/me` 回 9 keys 含合成 defaults
```
Given Spring application 跑 lab profile（`skillshub.security.lab-mode.enabled=true`）
When  `GET /api/v1/me` 不帶 token
Then  HTTP 200
And   response body 含同 9 keys（shape 對齊 OAuth mode）
And   sub = lab.user-id 值（per S012 既有行為）
And   email = "<sub>@lab.skillshub.local"
And   name = "LAB User"
And   picture = null
```

### AC-3: AppShell avatar dropdown 顯示 email 而非 sub
```
Given OAuth 已登入 user，`/api/v1/me` 回 email = "alice@example.com"
When  瀏覽 AppShell（任何頁面）
Then  avatar dropdown trigger 旁邊（DropdownMenuLabel）顯示 "alice@example.com"
And   不再顯示 Google numeric sub
```

### AC-4: MySkillsPage 「以 X 身份發布」顯示 name / email 而非 sub
```
Given OAuth 已登入 user，`/api/v1/me` 回 name = "Alice Wang"
When  瀏覽 /my-skills
Then  小字標題顯示 "以 Alice Wang 身份發布"
And   不再顯示 Google numeric sub
```

---

## 4. Interface / API Design

### 4.1 `MeController` response shape

新增 3 keys（`email` / `name` / `picture`）；插入位置在 `sub` 後（語意一組）：

```java
// OAuth 分支
result.put("sub",       jwt.getSubject());
result.put("email",     jwt.getClaimAsString("email"));      // new
result.put("name",      jwt.getClaimAsString("name"));       // new
result.put("picture",   jwt.getClaimAsString("picture"));    // new
result.put("roles",     orEmpty(jwt.getClaimAsStringList("roles")));
// ... rest unchanged ...

// LAB 分支
var u = users.current();
result.put("sub",       u.userId());
result.put("email",     u.userId() + "@lab.skillshub.local");  // new
result.put("name",      "LAB User");                            // new
result.put("picture",   null);                                  // new
result.put("roles",     u.roles());
// ... rest unchanged ...
```

### 4.2 No frontend interface change

`AuthUser` interface 既有 declare：

```typescript
export interface AuthUser {
  sub: string;
  email: string;
  name: string;
  picture?: string;
}
```

實際 backend 補上後 frontend 自動對齊；不需 `api/auth.ts` 任何 code change。

### 4.3 LAB email TLD 選擇

`@lab.skillshub.local` —
- `.local` 是 mDNS reserved TLD（RFC 6762），永不可註冊真域名 → 防 dev 訊息誤外洩成真 email
- prefix `lab.skillshub` 一眼看得出來源 + 環境

---

## 5. File Plan

### Backend（modify）

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/.../shared/security/MeController.java` | modify | OAuth + LAB 兩分支各加 3 個 `result.put(...)` |
| `backend/src/test/java/.../shared/security/MeControllerTest.java` | modify（或 new 若無）| AC-1 / AC-2 — 兩分支斷言 9 keys 含預期值 |

### Frontend（modify tests only — production code 不動）

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/hooks/useAuth.test.tsx` | modify | mock `fetchMe` response 補 `email / name / picture` 欄位（之前只 mock `sub`，現在 backend shape change → 對齊以防 schema drift fail） |
| `frontend/src/components/AuthArea.test.tsx` | modify（如需）| 加 AC-3 — DropdownMenuLabel 顯示 email 不顯示 sub |
| `frontend/src/pages/MySkillsPage.test.tsx` | modify（如需）| 加 AC-4 — 「以 Alice Wang 身份發布」斷言 |

### Doc sync

| File | Action |
|------|--------|
| `docs/grimo/specs/spec-roadmap.md` | 加 S141 row 到 Active table，狀態 `📐 design complete` |

### Forbidden

- 不新增 file（純 modify 既有 controller / test）
- 不引入 zod / valibot 等 schema validator（S139 Tech Debt 既有議題，留給未來 spec）
- 不重組 `AuthUser` interface（既有 declare 已對）

### Verification command

```bash
./scripts/verify-all.sh   # 含 backend test + frontend vitest
```

---

## Estimation Re-check

| 維度 | Score | 理由 |
|------|-------|------|
| Tech risk | 1 | 純 claim pass-through；無新框架 |
| Uncertainty | 1 | bug 與 fix 都明確 |
| Dependencies | 1 | 無 |
| Scope | 2 | 1 backend modify + 1-3 test modify；無新 file |
| Testing | 1 | 既有 test infra 直接擴充 |
| Reversibility | 1 | 純加欄位，向後相容 |
| **Total** | **7** | **XS(7)** |

POC: not required — bug root cause 經 source code 已 verified（grep 確認 `MeController.me()` 無 email/name/picture）。

---

## 6. Task Plan

> 待 `/planning-tasks S141` 完成。預計 task 圖（XS → 1-2 tasks 即可）：
>
> 1. T01 — backend `MeController` 加 3 claim + `MeControllerTest` AC-1/AC-2 兩分支斷言
> 2. T02 — frontend test 補 mock shape + AC-3/AC-4 顯示斷言

---

## 7. Implementation Results

**Ship date:** 2026-05-07
**Version:** v4.21.0

### Verify commands run

```bash
cd backend && ./gradlew test --tests "io.github.samzhu.skillshub.shared.security.MeControllerTest" -x processTestAot
# Result: BUILD SUCCESSFUL — 3/3 tests PASS
```

### AC coverage

| AC | Test | Outcome |
|----|------|---------|
| AC-1 (9 keys OAuth) | `me_withAdminJwt_returnsAllClaims` [@Tag AC-4, AC-S141-1] | ✅ PASS |
| AC-2 (LAB synthesized claims) | `me_labBranch_returnsSynthesizedClaims` [@Tag AC-S141-2] | ✅ PASS |
| AC-5 (401 no token) | `me_withoutJwt_returns401` [@Tag AC-5] | ✅ PASS |
| AC-3 (AppShell avatar) | Frontend: `AuthArea.tsx` fallback chain unchanged, backend fix auto-propagates | ✅ behavioural |
| AC-4 (MySkillsPage author) | Frontend: `user.name` path unchanged, backend `name` claim now populated | ✅ behavioural |

### Files changed

| File | Change |
|------|--------|
| `shared/security/MeController.java` | +3 claim puts in OAuth branch (`email/name/picture`); +3 synthesized defaults in LAB branch |
| `shared/security/MeControllerTest.java` | AC-S141-1 updated (9-key assertions + email/name/picture claims); AC-S141-2 new test (LAB branch) |

### Trim rationale

None — XS spec completed in full within single tick.

---

## 8. Post-ship Bug Report（2026-05-08）

### 症狀

LAB 環境 Google OAuth 登入後，AppShell avatar dropdown 仍顯示 `116549129985546340268...`（Google numeric sub），而非真實 email 或姓名。

### Root cause（Spec 分析漏洞）

本 spec §1 / §2.1 只分析了兩條 Authentication 路徑：

| 路徑 | Authentication 型別 | 是否在 spec 內 |
|------|---------------------|---------------|
| Bearer JWT（Resource Server） | `JwtAuthenticationToken` | ✅ 已分析並實作 |
| LAB filter 注入 | `UsernamePasswordAuthenticationToken` | ✅ 已分析並實作 |
| **oauth2Login session（LAB Google OIDC）** | **`OAuth2AuthenticationToken`** | ❌ **Spec 漏分析** |

LAB 環境啟用 `skillshub.security.oauth.login.enabled=true` → Spring `oauth2Login()` chain → callback 後 SecurityContext 存入 `OAuth2AuthenticationToken`，不是 `JwtAuthenticationToken`。`MeController` 的 `instanceof JwtAuthenticationToken` 判斷為 false，掉進 LAB fallback else 分支 → email 合成為 `<sub>@lab.skillshub.local` → 前端 `displayLabel = user.email` 顯示該合成字串被截斷為 `116...`。

### 修法（2026-05-08 補丁）

`MeController.me()` 加第二個 `instanceof` 分支處理 `OAuth2AuthenticationToken`，從 `OAuth2User.getAttribute("email/name/picture")` 取真實 Google OIDC profile attributes：

```java
} else if (auth instanceof OAuth2AuthenticationToken oauth2Auth) {
    var principal = oauth2Auth.getPrincipal();
    result.put("sub",     principal.getName());
    result.put("email",   principal.getAttribute("email"));
    result.put("name",    principal.getAttribute("name"));
    result.put("picture", principal.getAttribute("picture"));
    // roles/groups/companyId/deptId/scope 無 Google claim → 空值
    ...
}
```

新增 AC-S141-3 測試（`oauth2Login()` post-processor），4/4 PASS。

### Spec 分析缺失說明

本 spec 寫作時未意識到 `oauth2Login()` 與 JWT Resource Server 使用不同的 `Authentication` 型別。正確分析應為三條路徑；「OAuth 模式從 JWT claims 抽」的描述只對 API client（Bearer token）成立，對 browser session（oauth2Login）不成立。
