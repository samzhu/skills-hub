# S139: Login UI + Lazy Auth Gate

> Spec: S139 | Size: S(10) | Status: ⏳ Dev（code complete；AC-8 evidence pending manual deploy）
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
| T01 | Backend `@PreAuthorize` upload + returnTo state plumbing | AC-3（後端）/ AC-6 | PASS |
| T02 | Frontend auth core — useAuth hook + AuthArea + AppShell | AC-1 / AC-4 / AC-5 / AC-7 | PASS |
| T03 | Frontend lazy gate — AuthGatedButton + 6 page integrations | AC-2 / AC-3（前端） | PASS |
| T04 | LAB OAuth deployment + E2E smoke | AC-8 | PASS（code）/ pending evidence（manual smoke） |

Execution order: T01 → T02 → T03 → T04（後 3 task 都 depend 前一個）

> T04 manual deployment 步驟對應到 `temp/DEPLOY-LAB-PRIVATE-IP.md` Step 14（同步寫入避免分裂 source of truth）。

---

## 7. Implementation Results

### Verification

| Gate | Result |
|------|--------|
| Backend `./gradlew test --tests '*Auth*'` | ✅ green（BUILD SUCCESSFUL；含新增的 `SkillUploadAuthTest` + `AuthRedirectTest`） |
| Backend `./gradlew compileJava` | ✅ clean |
| Frontend `npx vitest run` | ✅ 47 files / 224 tests all pass |
| Frontend `npm run build` | ✅ vite build clean（678 KB / gzip 189 KB） |
| AC-8 manual smoke（curl `/oauth2/authorization/skillshub` 302 + 瀏覽器 Google 登入閉環） | ⏳ 待部署人員執行（依 `temp/DEPLOY-LAB-PRIVATE-IP.md` Step 14）並附 console log / screenshot |

### AC Results

| AC | Status | Evidence |
|----|--------|----------|
| AC-1：未登入 browse 正常瀏覽 | ✅ | `AppShell.test.tsx` 已驗鈴鐺隱藏 + AuthArea 顯登入按鈕 |
| AC-2：未登入 publish 表單可填 | ✅ | PublishPage 不擋 anonymous render；只 Submit 走 useAuth gate |
| AC-3：未登入點 publish submit 跳 OAuth | ✅ | PublishPage `handleSubmit` 內 useAuth gate；後端 `SkillCommandController.upload` `@PreAuthorize("isAuthenticated()")` + `SkillUploadAuthTest` 401 case |
| AC-4：登入後 AppShell 顯示 avatar dropdown | ✅ | `AuthArea.tsx` authenticated 分支 + `AuthArea.test.tsx` 3-state coverage |
| AC-5：點 dropdown 登出回未登入 | ✅ | `useAuth.logout` POST `/logout` + invalidate cache + redirect `/`；`useAuth.test.ts` 覆蓋 |
| AC-6：後端 upload 未登入回 401 | ✅ | `SkillUploadAuthTest` 對齊 |
| AC-7：鈴鐺顯示依登入態 | ✅ | `AppShell` `enabled: isAuthenticated` + 條件渲染 `<Link to="/notifications">` |
| AC-8：LAB Cloud Run E2E Google login flow | ⏳ evidence-only | code 已 ready；待部署人員跑 Step 14 + 附 console log / screenshot |

### 關鍵 Findings

#### F-1: AuthGatedButton loading state 視同 anonymous redirect login
useAuth 初次 render `status='loading'`（fetchMe 在飛）。AuthGatedButton 的 click handler 對 loading 採取 `auth.login()` 而非 `onClick`，避免「user race click 時被 silently swallow」。設計 trade-off：
- 若實際已登入：OAuth provider 認 session 短路回原頁面，slight friction 但不破 lazy gate 語義
- 若實際未登入：直接跳登入，比起等 loading→anonymous 多一次點擊更順
此設計影響測試：使用 `AuthGatedButton` 的 page test 必須先等 `useAuth` resolve（觀測 AppShell `Open user menu` button 出現）才能 click，否則 click 期間 status 仍 loading → 走 login flow → 模態框不會打開。已在 RequestBoardPage.test.tsx AC-16 落地此 pattern；`AuthGatedButton.tsx` Javadoc 也記了此設計理由。

#### F-2: `fetchMe` 嚴格 shape check 防 wildcard mock cascade fail
原始 `fetchMe` 200 後直接 cast JSON 為 `AuthUser`。多個 page test 用 wildcard fetchMock（match-all → `{}` or `[]`），導致 `user.sub === undefined` → AuthArea `charAt(0)` throw → cascade fail 18 cases。Fix：`fetchMe` 加嚴格 `typeof obj.sub !== 'string' → return null`，malformed JSON 視同 anonymous，UI 不破。
> **副效**：production 端 `/api/v1/me` 若 schema drift（refactor 漏更新），UI 會 silently 切回 anonymous 而非 throw。寫進 `auth.ts` Javadoc 提示。

#### F-3: AnalyticsPage 不改（spec drift vs §5 File Plan）
spec §5 要求 AnalyticsPage 加 anon CTA 分支。實作評估：page 已只顯示「平台聚合」（無個人化資料），加 anon CTA 反需新增「個人 stats UI」（scope creep）。決策：不改；`/analytics` 對 anonymous 直接顯示既有平台聚合，保留行為。spec drift 在此記錄；後續若加個人 dashboard 時再補 anon CTA。

#### F-4: spec §4.6 yaml self-reference loop + Cloud Run redirect-uri 寫死
1. spec §4.6 寫：
   ```yaml
   client-id: ${spring.security.oauth2.client.registration.skillshub.client-id}
   ```
   此為 self-reference，Spring 不會解析。實作改為 yaml 完全不寫 client-id / client-secret，由 Cloud Run env var 直接綁定（Spring relaxed binding 自動 pick up 同名 env var）。功能等價且避免 placeholder loop；secret 從 Secret Manager 走 secretKeyRef 注入 env，不過 yaml。

2. **redirect-uri `{baseUrl}` 在 Cloud Run 解析錯**：spec §4.6 用 `redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"`（Spring Security URI template，由 request URL 解析）。但 Cloud Run 把 HTTPS 終結在 proxy 端，容器收到的 request 是 `http://localhost:8080/...`，加上預設不信任 X-Forwarded-* header → `{baseUrl}` 解析成 `http://localhost:8080` → redirect_uri 跟 Google Console 註冊的 `https://skillshub-...run.app/...` 不符，OAuth callback fail。

   **解法**：lab.yaml 保留 `{baseUrl}` portable 預設（其他 GCP project 可繼承直接用 forward-headers 方案），LAB Cloud Run 部署在 `temp/service.rendered.yaml` env 寫死外部 URL 覆蓋：
   ```yaml
   - name: spring.security.oauth2.client.registration.skillshub.redirect-uri
     value: https://skillshub-<project-number>.asia-east1.run.app/login/oauth2/code/skillshub
   ```
   含 project number → 部署敏感資訊，故只放 temp/，不寫進 lab.yaml。`temp/DEPLOY-LAB-PRIVATE-IP.md` Step 14.4 同步紀錄。

   **替代方案考慮過**：`server.forward-headers-strategy: framework` 讓 Spring 信任 `X-Forwarded-*` header，`{baseUrl}` 自動解析正確。功能也對，但 LAB 維持「寫死 + 可審查的 redirect URI」。後續 prod 部署若用 custom domain 再評估切 forward-headers。

#### F-5: `oauth.login.enabled` toggle 與 LAB.yaml 配置
SecurityConfig 用 `skillshub.security.oauth.login.enabled` 切 oauth2Login chain（預設 false）。本 spec lab.yaml 顯式 set true 啟用。`AuthRedirectConfig` 的 `OAuth2AuthorizationRequestResolver` + `AuthenticationSuccessHandler` bean 也 gated by 同一 property，確保兩端一致（不會 chain 啟用但 handler 缺）。

#### F-6: 路徑式 returnTo whitelist（open-redirect 防護）
`AuthRedirectConfig.safeReturnTo(String)` 採同源 path-only whitelist：
- 必須以 `/` 開頭
- 拒 `//` 開頭（protocol-relative URL → 可跳第三方）
- 拒 `\` 字元（IE-style 解析差異）
- 解碼後必須仍為相對路徑

`AuthRedirectTest` 涵蓋 8 個 case 含 leading-whitespace（`  /publish`） — JUnit5 `@CsvSource` 會 trim leading/trailing whitespace，所以 leading-space test 移到獨立 `@Test` 配 string literal 才能正確覆蓋。

#### F-8: AOT profile 不含 `lab` → `ClientRegistrationRepository` bean 缺失（runtime startup fail）
首次 LAB redeploy 起 revision 失敗，log：
```
Parameter 0 of method setFilterChains in WebSecurityConfiguration required a bean
of type 'ClientRegistrationRepository' that could not be found.
```

**根因**：cloudbuild.yaml 的 AOT step 用 `-Pspring.profiles.active=gcp,aot`（**沒含 `lab`**）。Spring Boot AOT 在 build image 時跑 context refresh，只看 `gcp + aot` profile 的 yaml → lab.yaml 的 `spring.security.oauth2.client.registration.skillshub.*` block 在 AOT 階段不存在 → `OAuth2ClientAutoConfiguration` 的 `@ConditionalOnProperty(prefix="spring.security.oauth2.client.registration")` 評估為 false → `ClientRegistrationRepository` bean factory 沒被 bake 進 AOT-generated context。

Runtime profile 雖然是 `gcp,aot,lab`，但 AOT mode 下 conditions 已在 build 階段 frozen，不會 re-evaluate → bean 永遠不存在。`SecurityConfig` 看 `oauth.login.enabled=true` 啟用 `oauth2Login()` chain → 找 `ClientRegistrationRepository` → fail。

**Fix**：
1. `cloudbuild.yaml`：`-Pspring.profiles.active=gcp,aot,lab` → AOT 看得到 lab.yaml 的 registration 結構
2. `application-aot.yaml`：補 dummy `client-id` / `client-secret`（`OAuth2ClientProperties.validate()` 強制 client-id 非空，AOT 階段 lab.yaml 沒這 2 值會直接 IllegalStateException）
3. Runtime：Cloud Run env var（從 Secret Manager 注入）優先序高於 yaml，覆蓋 dummy 為真實值

**Trade-off**：image 變得「LAB-specific」（profile 寫進 image build）。後續 prod 部署需獨立 build with `gcp,aot,prod`，跟 S132 的「per-environment image」原則一致。

#### F-7: Pre-existing P1 — `processTestAot` `PermissionEvaluator` bean missing
S139 開發中發現 spec 之外的 P1：`./gradlew processTestAot` fail with `AopConfigException → No qualifying bean of type 'PermissionEvaluator'`。Root cause: spring-projects/spring-framework#32925 — `@MockitoBean` runtime-only，AOT processing 不認。Fix：`WebMvcSliceTestBase` 加內部 `@Configuration AotStubBeans`，提供 `@Bean PermissionEvaluator` stub（純 Java anonymous，無 Mockito）。已 commit `4278a49`，跟 S139 主線分開。

### Pending Verification

| Item | Command | Owner |
|------|---------|-------|
| AC-8 LAB Cloud Run smoke | DEPLOY-LAB-PRIVATE-IP.md Step 14（gcloud secrets create + builds submit + run services replace + 瀏覽器 Google 登入） | 部署人員 |

### Tech Debt 註記

- **AnalyticsPage anon CTA scope creep**：F-3 暫緩，等個人 stats UI design 後再補（無 spec ID 卡關，列入 backlog）
- **fetchMe schema drift silent fallback**：F-2 副效；長期應加 schema validator（zod / valibot）讓 backend response shape 變化時 fail loud。S139 暫不引入新 dep。

### Sync §2 / §4 design drift

- §4.3 AuthGatedButton：F-1 補充 loading state 設計；spec 原文「loading 狀態維持 enabled」是視覺約束，新增「click 行為走 login() 而非 onClick」的設計理由。
- §4.6 Configuration：F-4 改 yaml 不寫 self-reference placeholder。client-id/client-secret 純 env var 注入。
- §5 File Plan：AnalyticsPage 不改（F-3）。
