# S139: Login UI + Lazy Auth Gate

> Spec: S139 | Size: S(10) | Status: ⏳ Design
> Date: 2026-05-06

---

## 1. Goal

把 S134 已 ship 的後端 OAuth login flow（Google）連到前端 — **AppShell header 加登入區塊（未登入：登入按鈕；登入後：avatar dropdown），需要身分的動作（上傳 / 集合建立 / flag resolve 等）才在按鈕點擊時 lazy 檢查並跳 OAuth**。頁面本身全部維持公開可瀏覽。同時補後端 `/api/v1/skills/upload` 缺漏的 `@PreAuthorize`，並把 LAB Cloud Run deployment 的 Google OAuth provider 完整接好。

**簡單講**：
- Browse / detail / docs / search → 全公開（per 既有 SecurityConfig `permitAll`）
- 「我的技能」「發佈」「集合」「需求」「待審回報」「數據」「通知」→ 頁面**可進**，但內容隨身分變化；功能按鈕 lazy 檢查
- 點「上傳」「建立集合」「Resolve flag」等動作 → 未登入即 302 到 `/oauth2/authorization/skillshub` → Google 登入 → callback → 自動回原頁
- 登入後 header 右側顯示 avatar + dropdown（email / 我的技能 / 登出）

**非目標**：
- 表單狀態保留（登入後自動帶回 publish 表單 draft）— 留給後續 spec
- 多 IdP 切換 UX（目前單一 Google provider）
- Browse / detail / docs / search 加 button-level gate（無此需求）
- ACL 細粒度權限 UI（owner-only buttons / role badges）— S114a 已 ship 後端，UI 後續 spec

---

## 2. Approach

### 2.1 整體 pattern：Lazy Auth Gate（業界 marketplace 標準）

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| **A. Lazy gate（page 公開、按鈕 / API 動作 lazy 檢查）** | ✅ **yes** | 公開內容 SEO + share 友善；npm / Docker Hub / GitHub 公開 repo 模式；user 已明確選擇 |
| B. Route-level guard（未登入頁面無法進） | no | 阻擋非必要互動，user 明確 reject |
| C. 隱藏未登入時的按鈕 | no | 用戶無法 discover 功能；UX 反模式 |

### 2.2 Frontend 三層結構

```
┌────────────────────────────────────────────────────────────────┐
│  AppShell (header)                                             │
│    ├─ nav links (一律顯示)                                     │
│    ├─ <Bell />     ← useAuth().isAuthenticated 為 true 才顯示 │
│    └─ <AuthArea /> ← 統一封裝「登入按鈕 / avatar dropdown」     │
├────────────────────────────────────────────────────────────────┤
│  Pages（全公開）                                               │
│    ├─ /publish, /collections, /requests 等                    │
│    └─ Action buttons → <AuthGatedButton onClick={...}>        │
├────────────────────────────────────────────────────────────────┤
│  api/auth.ts + hooks/useAuth.ts                                │
│    ├─ GET /api/v1/me → 200 = authenticated / 401 = anonymous  │
│    └─ React Query 30s staleTime；hydrate AuthArea 與 button    │
└────────────────────────────────────────────────────────────────┘
```

### 2.3 LAB OAuth provider：沿用 S134 Google client

| 項目 | 值 |
|---|---|
| OAuth provider | Google（per S134 已驗證 OIDC discovery + PKCE S256） |
| Client | 沿用 GCP project 既有 OAuth 2.0 Client ID（per S134） |
| Authorized redirect URI（LAB 新增） | `https://<lab-cloud-run-url>/login/oauth2/code/skillshub` |
| client-id / client-secret | Secret Manager（new secrets：`skillshub-oauth-client-id` / `skillshub-oauth-client-secret`） |
| issuer-uri | `https://accounts.google.com`（S134 archive §1 P-1 確認無 trailing slash） |
| Scopes | `openid email profile`（per S134 已驗證） |

### 2.4 後端最小補丁

| 端點 | 現況 | 改動 |
|---|---|---|
| `POST /api/v1/skills` | permitAll（漏） | 加 `@PreAuthorize("isAuthenticated()")` |
| `POST /api/v1/skills/upload` | permitAll（漏） | 加 `@PreAuthorize("isAuthenticated()")` |
| `PUT /api/v1/skills/{id}/versions` | `@PreAuthorize("hasPermission(...,'write')")` | 不動（既有 ACL 涵蓋） |
| `GET /api/v1/me` | `authenticated()`（OAuth 模式 401）/ Lab 模式注入 mock | 不動，前端探測登入態用 |

### 2.5 Failure UX：直接 redirect（not modal / not button copy）

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| **A. 立即 302 到 `/oauth2/authorization/skillshub?returnTo=<currentPath>`** | ✅ **yes** | GitHub / Reddit / Vercel 標準；user 點按鈕 = 意圖明確；最少 friction |
| B. Modal「請先登入」 | no | 多一步無意義 confirm |
| C. 改變按鈕文字「登入後上傳」 | no | 每處 button copy 要改；難 i18n |

### 2.6 Spring Security `SavedRequestAwareAuthenticationSuccessHandler`

OAuth login 完成後預設行為是 redirect 到 `Saved Request`（用戶被擋下時 Spring 存的 URL）。但 SPA 場景下「擋下時是 SPA 內 navigation，後端沒見過」— 改用 query param `returnTo` 顯式傳：

- Frontend `login(returnTo)` 拼 URL：`/oauth2/authorization/skillshub?state=<base64(returnTo)>`
- 後端配 `OAuth2AuthorizationRequestResolver` 讀 state 中 `returnTo`，存 session
- `AuthenticationSuccessHandler` 讀 session 中 returnTo redirect

**Trade-off**：原 S134 用 default success handler（redirect to `/`）。本 spec 加自訂 handler 處理 returnTo。風險：自訂 state 編碼要防 open-redirect（限同源）。

### 2.7 Challenge：為什麼不用更簡單的方案？

- **Q：直接所有 page 都 require login（route guard）就好？**
  A：用戶明確要求 lazy gate；marketplace 公開瀏覽是 product 核心價值（PRD vision）。
- **Q：登入按鈕直接連 `/oauth2/authorization/skillshub`、不傳 returnTo？**
  A：可以，但 user 在 `/publish` 頁點登入，登入完跳到 `/`（首頁）會 disorienting。多一個 returnTo 處理 cost 低。
- **Q：用 Spring `SavedRequestAware` 拿 referer？**
  A：referer 在 OAuth 跨多個 302 redirect 後不可靠；query param 是顯式且穩定的方法。

---

## 3. SBE Acceptance Criteria

> Verification command: `./scripts/verify-all.sh`
> Pass: 所有帶本 spec AC tag 的 backend / frontend 測試 green。

### AC-1: 未登入 browse 頁面正常瀏覽
```
Given 使用者未登入
When  訪問 /browse
Then  看到 skill list（既有 SkillCard 列表）
And   AppShell header 右側顯示「登入」按鈕
And   AppShell 鈴鐺圖示不顯示
```

### AC-2: 未登入 publish 頁面表單可填
```
Given 使用者未登入
When  訪問 /publish
Then  看到完整上傳表單（不被 redirect）
And   Submit 按鈕顯示但點下去走 lazy gate（見 AC-3）
```

### AC-3: 未登入點 publish submit 立即跳 OAuth
```
Given 使用者未登入且在 /publish 填好表單
When  點 Submit 按鈕
Then  瀏覽器立即 302 到 /oauth2/authorization/skillshub?state=<encoded returnTo=/publish>
And   完成 Google 登入後 302 回 /publish
```

### AC-4: 登入後 AppShell 顯示 avatar dropdown
```
Given 使用者完成 Google OAuth login
When  瀏覽任何頁面
Then  AppShell header 右側顯示圓形 avatar（fallback 字母）
And   點 avatar 開出 dropdown，含：email / 我的技能 link / 登出 button
And   鈴鐺圖示開始顯示（poll unread count）
```

### AC-5: 點 dropdown 登出後回未登入狀態
```
Given 使用者已登入
When  點 avatar dropdown → 登出
Then  瀏覽器走 POST /logout（Spring Security 預設）
And   redirect 回 / (Landing)
And   AppShell avatar 變回「登入」按鈕
And   鈴鐺隱藏
```

### AC-6: 後端 upload endpoint 未登入回 401
```
Given API 客戶端無 session cookie / JWT
When  POST /api/v1/skills/upload (multipart)
Then  HTTP 401
And   無 skill 被建立（DB 無新 row）
```

### AC-7: 鈴鐺顯示與否依登入態
```
Given 使用者未登入
When  瀏覽 AppShell
Then  鈴鐺圖示不渲染（DOM 中不存在）

Given 使用者已登入
When  瀏覽 AppShell
Then  鈴鐺圖示渲染且每 30s poll unread count
```

### AC-8: LAB Cloud Run 端到端 Google login flow 通
```
Given LAB Cloud Run service 已部署（含本 spec 設定）
When  瀏覽器訪問 <lab-cloud-run-url>/oauth2/authorization/skillshub
Then  302 到 accounts.google.com
And   完成 Google 登入後 302 回 /login/oauth2/code/skillshub
And   建立 session 後 redirect 到首頁
And   GET /api/v1/me 回 200 帶使用者 email / sub / name claim

# 此 AC evidence-only：本 spec deliverable 是「LAB OAuth flow 通」，
# 由部署人員瀏覽器手動跑一次 + curl /api/v1/me 200 證明
# LAB URL 跟 GCP project ID 不在 spec 內 hardcode（屬部署資訊）
```

---

## 4. Interface / API Design

### 4.1 Frontend types

```typescript
// frontend/src/api/auth.ts
export interface AuthUser {
  sub: string;          // Google sub claim
  email: string;
  name: string;
  picture?: string;     // Google picture URL（可能為 null）
}

export type AuthState =
  | { status: 'loading' }
  | { status: 'authenticated'; user: AuthUser }
  | { status: 'anonymous' };

// GET /api/v1/me → 200 AuthUser | 401（未登入）
export async function fetchMe(): Promise<AuthUser | null>;
```

```typescript
// frontend/src/hooks/useAuth.ts
export function useAuth(): AuthState & {
  /** 跳 /oauth2/authorization/skillshub?state=<encode(returnTo ?? location.pathname)>  */
  login: (returnTo?: string) => void;
  /** POST /logout 後 reload 到 / */
  logout: () => void;
};
```

### 4.2 AuthArea component（AppShell header 右側）

```typescript
// frontend/src/components/AuthArea.tsx
/**
 * 未登入：<Button>登入</Button> → onClick login()
 * 已登入：<DropdownMenu> avatar trigger → MenuItems(email / 我的技能 / 登出)
 * loading：skeleton（避免按鈕閃爍）
 */
export function AuthArea(): JSX.Element;
```

### 4.3 AuthGatedButton

```typescript
// frontend/src/components/AuthGatedButton.tsx
interface AuthGatedButtonProps extends ButtonProps {
  onClick: () => void;     // 只有 authenticated 才呼叫；anonymous 改走 login()
  children: ReactNode;
}

/**
 * 包裝 shadcn <Button>，點擊時：
 *   useAuth().status === 'authenticated' → 呼叫 onClick
 *   else                                  → useAuth().login(location.pathname)
 * loading 期間 button 維持 enabled（避免閃爍 disabled），但點下去等 hook ready
 */
export function AuthGatedButton(props: AuthGatedButtonProps): JSX.Element;
```

### 4.4 Backend 自訂 OAuth handlers（returnTo state）

```java
// backend/src/main/java/.../shared/security/AuthRedirectConfig.java
@Configuration
@ConditionalOnProperty(name = "skillshub.security.oauth.enabled", matchIfMissing = true)
class AuthRedirectConfig {

  /**
   * 把 frontend 傳的 ?state=<base64(returnTo)> 解出存 session，
   * SuccessHandler 讀回來 redirect。state 限 same-origin path（防 open redirect）。
   */
  @Bean
  OAuth2AuthorizationRequestResolver authRequestResolver(
      ClientRegistrationRepository repo) { ... }

  @Bean
  AuthenticationSuccessHandler oauthSuccessHandler() {
    return (req, res, auth) -> {
      String returnTo = (String) req.getSession().getAttribute("RETURN_TO");
      String safe = (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//"))
          ? returnTo : "/";
      res.sendRedirect(safe);
    };
  }
}
```

### 4.5 Backend `@PreAuthorize` 補丁

```java
// SkillCommandController.java
@PostMapping
@PreAuthorize("isAuthenticated()")    // ADD
ResponseEntity<Map<String, String>> createSkill(...) { ... }

@PostMapping("/upload")
@PreAuthorize("isAuthenticated()")    // ADD
ResponseEntity<Map<String, String>> uploadSkill(...) { ... }
```

### 4.6 Configuration

```yaml
# backend/config/application-lab.yaml — 改 issuer-uri
spring:
  security:
    oauth2:
      client:
        registration:
          skillshub:
            client-id: ${spring.security.oauth2.client.registration.skillshub.client-id}      # env var
            client-secret: ${spring.security.oauth2.client.registration.skillshub.client-secret}
            scope: openid,email,profile
            redirect-uri: "{baseUrl}/login/oauth2/code/skillshub"
        provider:
          skillshub:
            issuer-uri: https://accounts.google.com

skillshub:
  oauth:
    issuer-uri: https://accounts.google.com   # 從 ${...:} 改為實值
```

```yaml
# scripts/gcp/service.rendered.yaml — 加 OAuth client env vars
env:
  - name: spring.security.oauth2.client.registration.skillshub.client-id
    valueFrom:
      secretKeyRef:
        name: skillshub-oauth-client-id
        key: latest
  - name: spring.security.oauth2.client.registration.skillshub.client-secret
    valueFrom:
      secretKeyRef:
        name: skillshub-oauth-client-secret
        key: latest
```

---

## 5. File Plan

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/auth.ts` | new | `fetchMe()` — GET /api/v1/me; 401 → null |
| `frontend/src/hooks/useAuth.ts` | new | React Query hook + `login()` / `logout()` |
| `frontend/src/components/AuthArea.tsx` | new | Header right slot：登入按鈕 / avatar dropdown |
| `frontend/src/components/AuthGatedButton.tsx` | new | 包 shadcn Button + lazy gate |
| `frontend/src/components/AppShell.tsx` | modify | 嵌入 `<AuthArea />`；鈴鐺改 `useAuth().status === 'authenticated'` 條件渲染 |
| `frontend/src/pages/PublishPage.tsx` | modify | Submit 按鈕改 `<AuthGatedButton>` |
| `frontend/src/pages/CollectionsPage.tsx` | modify | 「Create collection」按鈕改 `<AuthGatedButton>`（若 stub 階段尚無 button，加 placeholder） |
| `frontend/src/pages/RequestBoardPage.tsx` | modify | 「Post request」按鈕同上 |
| `frontend/src/pages/FlagsQueuePage.tsx` | modify | 「Resolve / Dismiss」按鈕改 `<AuthGatedButton>` |
| `frontend/src/pages/MySkillsPage.tsx` | modify | 未登入顯示「登入後可查看自己的技能」CTA + login 按鈕 |
| `frontend/src/pages/AnalyticsPage.tsx` | modify | 未登入顯示「登入後可查看自己的數據」CTA |
| `frontend/src/pages/NotificationsPage.tsx` | modify | 未登入顯示「登入後可查看通知」CTA |

### Frontend tests

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/components/AuthArea.test.tsx` | new | 3 狀態（loading / anonymous / authenticated）渲染快照 |
| `frontend/src/hooks/useAuth.test.ts` | new | mock fetch，覆蓋 200 / 401 / login() side-effect |
| `frontend/src/components/AuthGatedButton.test.tsx` | new | 點擊 dispatch：authenticated → onClick；anonymous → login() |
| `frontend/src/components/AppShell.test.tsx` | modify | AC-1 / AC-7 — 鈴鐺隨登入態渲染 |

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/.../skill/command/SkillCommandController.java` | modify | `@PostMapping` (createSkill) + `@PostMapping("/upload")` 加 `@PreAuthorize("isAuthenticated()")` |
| `backend/src/main/java/.../shared/security/AuthRedirectConfig.java` | new | OAuth2AuthorizationRequestResolver + SuccessHandler 處理 returnTo state |
| `backend/src/main/java/.../shared/security/SecurityConfig.java` | modify | 把 4.4 的 SuccessHandler 注入 OAuth2 login chain |
| `backend/config/application-lab.yaml` | modify | 4.6 的 OAuth client config + `skillshub.oauth.issuer-uri` 實值 |
| `scripts/gcp/service.rendered.yaml` | modify | 加 `client-id` / `client-secret` 從 Secret Manager 注入 |

### Backend tests

| File | Action | Description |
|------|--------|-------------|
| `backend/src/test/java/.../skill/command/SkillUploadAuthTest.java` | new | AC-6：anonymous POST upload → 401 |
| `backend/src/test/java/.../shared/security/AuthRedirectTest.java` | new | returnTo state encode/decode + open-redirect 防護 |

### Infra（人工執行）

| 動作 | 執行者 |
|---|---|
| Google Cloud Console 加 redirect URI `https://<lab-cloud-run-url>/login/oauth2/code/skillshub` | 部署人員 |
| 建 2 個 Secret：`skillshub-oauth-client-id` + `skillshub-oauth-client-secret`，授權 SA accessor | 部署人員 |
| 重 build image + 重 deploy（含 service.rendered.yaml 改動） | 部署人員 |

### Roadmap update

| File | Action |
|------|--------|
| `docs/grimo/specs/spec-roadmap.md` | 加 S139 row 到 Active table，狀態 `📐 in-design` |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

POC: not required — S134 已驗證 Spring Security 7 OAuth2 Login + Google
OIDC + session-based auth；S114a 提供 RBAC ACL 基礎；前端 React Query +
shadcn DropdownMenu 已在用。returnTo state 機制走標準 Spring Security
擴充點（custom `OAuth2AuthorizationRequestResolver` + custom
`AuthenticationSuccessHandler`），Phase 0 評估風險低，可在 T01 backend
test 內吸收。

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | Backend `@PreAuthorize` upload + returnTo state plumbing | AC-3（後端）/ AC-6 | pending |
| T02 | Frontend auth core — useAuth hook + AuthArea + AppShell | AC-1 / AC-4 / AC-5 / AC-7 | pending |
| T03 | Frontend lazy gate — AuthGatedButton + 7 page integrations | AC-2 / AC-3（前端） | pending |
| T04 | LAB OAuth deployment + E2E smoke | AC-8 | pending |

Execution order: T01 → T02 → T03 → T04（後 3 task 都 depend 前一個）

> T04 manual deployment 步驟對應到 `temp/DEPLOY-LAB-PRIVATE-IP.md` Step 14（同步寫入避免分裂 source of truth）。
