# Cloud Run + Spring Boot/Security 部署陷阱

> **適用情境**：Spring Boot 4 / Spring Security 7 application 部署到 Google
> Cloud Run。AOT 相關陷阱另見 `aot-deployment-pitfalls.md`；本檔只蒐集**非
> AOT** 的部署期 / runtime 陷阱。
>
> 所有條目都來自實戰：S139 LAB OAuth 部署過程踩了一遍，每個都附**症狀 →
> 根因 → 修法**三段。

---

## §1 反向代理後 `{baseUrl}` URI template 解析錯（OAuth redirect_uri_mismatch）

### 症狀

OAuth Login 走完 Google 端流程後，callback 回 `redirect_uri_mismatch` error，
或者 `curl -I /oauth2/authorization/{registrationId}` 看到 `location:` 帶錯的
host：

```
location: https://accounts.google.com/o/oauth2/v2/auth?...&redirect_uri=http://localhost:8080/login/oauth2/code/skillshub&...
```

`redirect_uri=http://localhost:8080/...` 而非外部 Cloud Run URL。

### 根因

Spring Security 的 `redirect-uri` 屬性支援兩個 URI template token：
`{baseUrl}` 與 `{registrationId}`。yaml 寫：

```yaml
redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

`{baseUrl}` 是 **Spring Security 的 URI template**（不是 Spring property
placeholder），由 `UriComponentsBuilder.fromHttpUrl(httpRequest.getRequestURL())`
動態解析 — **看到的是「容器收到的 request URL」**。

Cloud Run 把 HTTPS 終結在 proxy（GFE）端，往容器丟的是 `http://localhost:8080/...`
加上 `X-Forwarded-Proto: https` + `X-Forwarded-Host: <external-host>` header。
Spring 預設**不信任** forwarded header → request URL 看到 `http://localhost:8080`
→ `{baseUrl}` 展開成 `http://localhost:8080` → redirect_uri 跟 Google Console
註冊的 `https://...run.app/...` 不一致 → callback 失敗。

### 修法（兩條路）

**方案 A：信任 X-Forwarded-* header（推薦，portable）**

```yaml
# application-{infra}.yaml（lab/prod 都需要）
server:
  forward-headers-strategy: framework
```

`framework` 會啟用 Spring 的 `ForwardedHeaderFilter`，把 request 包成 wrapper
讓 `getRequestURL()` 回傳重組後的外部 URL → `{baseUrl}` 解析正確。yaml
保持 `{baseUrl}/...` portable，custom domain 換也不用動。

**方案 B：寫死 redirect-uri（綁死部署 URL）**

```yaml
# Cloud Run env var override（service.yaml）
- name: spring.security.oauth2.client.registration.skillshub.redirect-uri
  value: https://<external-host>/login/oauth2/code/skillshub
```

env var 優先序高於 yaml，覆蓋 `{baseUrl}` 預設。適用：
- 需要把外部 URL 從 yaml 隔離（含 project number 等敏感資訊不入 git）
- 用 custom domain 後想顯式宣告 redirect-uri 在哪

⚠ **必須與 Google Cloud Console「Authorized redirect URIs」字串完全一致**
（含 https / 路徑 / 無 trailing slash）。不一致 → `redirect_uri_mismatch`。

### 反模式

- **不要**以為 `{baseUrl}` 是 Spring property placeholder（`${baseUrl}`）— 不是。設
  env var 名 `baseUrl` 不會影響它的展開。
- **不要**只動其中一邊（yaml 改了 redirect-uri 但 Console 沒同步註冊，反之亦然）—
  兩邊字串必須完全相同。

---

## §2 CORS 擋同源 POST 但放行同源 GET（unique 403 silent failure）

### 症狀

部署在 Cloud Run，前端 SPA 與 backend 同 host serve（`https://app.run.app/`
serve React、`/api/v1/*` 是 backend）。瀏覽器：
- `GET /api/v1/me` → 200（session 認證正常）
- `POST /api/v1/skills/upload` → **403**（multipart/form-data 上傳）

Cloud Run 看到「POST 403」，Spring Security DEBUG 只有一行
`Securing POST /api/v1/skills/upload`，**之後完全沒任何 log**（filter 鏈中斷）。
custom `AccessDeniedHandler` 不觸發、controller entry log 不觸發。

### 根因

Spring Boot CORS filter（`CorsFilter` 由 `WebMvcAutoConfiguration` 註冊）對
**任何**進來的 request 都檢查 `Origin` header — **不分同源 / 跨源**。

瀏覽器送 `Origin` header 的規則（per fetch spec）：
- **GET / HEAD 同源** → 通常**不送** `Origin` header → CorsFilter 看不到 origin
  → pass-through
- **POST**（任何 content-type）→ **always 送** `Origin` header → CorsFilter 檢查
- **跨源請求**（任何 method）→ always 送 → CorsFilter 檢查
- 含 multipart/form-data 的 POST 屬於 **non-simple request**，必送 Origin

如果 `allowedOrigins` 配置不含當前部署的外部 URL（譬如預設只列
`http://localhost:5173, http://localhost:8080` for dev），同源 POST 的 Origin
header 是 `https://app.run.app`，不在 allowlist → Spring
`DefaultCorsProcessor.rejectRequest()` 直接回 **403 + body "Invalid CORS
request"**，filter chain 提早 short-circuit，**完全不過 Spring Security
authorization filter，所以 AccessDeniedHandler 不觸發**。

這是為什麼 GET works / POST fails 的不對稱現象：browser 在 GET 不送
Origin，CORS check 跳過。

### 修法

把部署的外部 origin 加進 allowlist：

```yaml
# Cloud Run env var override（service.yaml）— 含 project number 走 env，不入 git
- name: skillshub.security.cors.allowed-origins
  value: https://<external-host>
```

或若有自訂 domain：
```yaml
  value: https://app.example.com,https://staging.example.com
```

（Spring relaxed binding 對 `List<String>` 接受逗號分隔）

### Diagnostic 心法

**「Filter chain logs 看到 `Securing METHOD path` 之後就斷」是強訊號 → 多半是
CorsFilter / 其他 short-circuit filter 砍掉了**。

```
2026-... DEBUG ... FilterChainProxy : Securing POST /api/v1/skills/upload
[此後沒任何 log]
2026-... POST 403 https://...
```

ExceptionTranslationFilter 跟 AuthorizationFilter 在 filter 鏈尾段，前面任何
filter 直接 `response.sendError()` 或 `response.setStatus()` 都會繞過它們，
custom `AccessDeniedHandler` 完全摸不到。

排查順序：
1. CorsFilter — 看 Origin header 跟 `allowedOrigins` 對不對得上
2. CSRF filter — 多半已 disable，但仍要確認
3. StrictHttpFirewall — 路徑含特殊字元（`;` `\` `%2F`）會被擋
4. 容器 size limit / 反向代理超時 — 先看 GFE access log timestamp 跟 Spring
   filter chain log 之間的時間差

### 反模式

- **不要**假設「同源就不會 hit CORS」— Spring 的 CorsFilter 不分同源跨源。
- **不要**把 allowedOrigins 的預設值留給 `localhost:*` 就推上 production —
  雲端 URL 必須顯式加進 allowlist（透過 env / yaml profile）。
- **不要**一見 403 就往 auth gate 找 — Filter chain 早期 silent rejection（CORS
  / Firewall / CSRF）也產 403，跟 auth 的 AccessDeniedException → 403 路徑不同。

---

## §3 SPA 深層連結直連回 404（Spring 沒對應 controller）

### 症狀

部署後：
- `https://app.run.app/` → 200（serve `static/index.html`）
- `https://app.run.app/publish` → 404 `<ErrorResponse>...No static resource
  publish for request '/publish'</ErrorResponse>`
- `https://app.run.app/my-skills` → 404 同上

從根頁進去 SPA 後 client-side navigate 到 `/publish` 沒事；但**重整 / 書籤 /
OAuth callback 後 redirect 到深層連結**就 404。

### 根因

Spring Boot 的 `ResourceHttpRequestHandler` 服務 `static/` 目錄裡的檔案。
`/index.html` 命中 `static/index.html` → 200。
`/publish` 在 `static/` 沒對應檔 → 404，往 GlobalExceptionHandler 走。

React Router 用 BrowserRouter，URL 由前端 Routes 配置（非 hash router）。
backend 沒對應 controller → 預設回 404，front-end 接不到 location.pathname
無法 render 對應 page。

### 修法

顯式列出 frontend 擁有的路徑前綴，全部 forward 到 `/index.html`：

```java
@Controller
class SpaFallbackController {

    @GetMapping({
        "/browse",
        "/publish", "/publish/**",
        "/my-skills",
        "/collections",
        "/requests",
        "/notifications",
        "/analytics",
        "/flags",
        "/search",
        "/skills", "/skills/**",
        "/docs/**",
    })
    String forwardToIndex() {
        return "forward:/index.html";
    }
}
```

新增 React Route 時手動加進此 list（trade-off：明確 vs DRY）。

### 為什麼不用 catch-all `/**/{path:[^.]*}` regex

regex catchall 雖少維護成本，但會誤攔不存在的 `/api/...` 端點 → 應回 404
JSON 給前端 / curl，不該回 HTML SPA shell。explicit list 更安全。

### 反模式

- **不要**用 `@RequestMapping("/**")` 當 SPA fallback — 蓋過所有 controller，
  RequestMappingHandlerMapping 會 ambiguity warning，且 `/api/...` 未匹配的
  路徑也會被吃掉。
- **不要**靠 `ErrorController` 把 404 改寫成 `forward:/index.html` 就了事 — 真正
  的 API miss 會被偽造成 200 HTML。

---

## §4 LAB OAuth 部署多步驟序列（容易漏步）

對應 `temp/DEPLOY-LAB-PRIVATE-IP.md` Step 14。**每一步都會獨立 fail，**
每漏一步都要等下一輪 `gcloud run services replace` 才知道：

| Step | 動作 | 漏掉的症狀 |
|------|------|----------|
| 1 | Google Cloud Console **Authorized redirect URIs** 加當前部署 URL | 走完 Google login 後 callback `redirect_uri_mismatch` |
| 2 | `gcloud secrets create app-oauth-client-id` + IAM grant runtime SA accessor | `gcloud run services replace` 報 `Secret ... was not found` |
| 3 | 同 2，建 `app-oauth-client-secret` | 同上 |
| 4 | `gcloud secrets versions add app-config-{env} --data-file=app-{env}.yaml` 推新 yaml | 容器讀的還是舊 yaml（沒 OAuth client block）→ runtime 起不來，log 顯示 ClientRegistrationRepository missing |
| 5 | `gcloud builds submit` 拿新 image tag | 跑 4 但沒重 build → image 內 yaml 缺新欄位（看 `spring.config.additional-location` mount 是否 override classpath yaml） |
| 6 | service.yaml `image:` 改新 tag + `gcloud run services replace` | 仍 serve 舊 revision，新改完全不生效 |
| 7 | 瀏覽器跑 OAuth 閉環 + smoke test | — |

**Cloud Run revision 是 immutable**，更新 secret 後**舊 revision 不會自動 pick
up**——必須走 `gcloud run services replace` 起新 revision，才會 mount 新 secret
版本。同理 image 改了也要 replace 才生效。

### 確認手段

```bash
# 看當前 serving revision 用哪個 image
gcloud run services describe SERVICE --region=REGION \
  --format='value(spec.template.spec.containers[0].image)'

# 看 secret 最新版本
gcloud secrets versions list app-config-lab --limit=3
```

部署後測試一直失敗時，**先看是不是真的更新到新 image / secret**（自己改完
yaml 忘記 replace 的情境意外多）。

---

## §5 `/actuator/info` build + git 資訊（Spring 官方推薦組合 + monorepo 注意）

### 設定

兩個獨立來源：

```kotlin
// build.gradle.kts
plugins {
    id("org.springframework.boot") version "..."
    // Spring Boot 官方推薦的 git plugin
    // https://docs.spring.io/spring-boot/how-to/build.html#howto.build.generate-git-info
    id("com.gorylenko.gradle-git-properties") version "2.5.+"
}

springBoot {
    buildInfo()   // Spring Boot Gradle plugin 內建 task；產 META-INF/build-info.properties
}

gitProperties {
    // monorepo（Gradle 在 backend/ subdir 跑）：必須顯式指 root .git
    dotGitDirectory = file("${rootDir}/../.git")
    // CI 環境 gcloud builds submit 預設不上傳 .git/ → graceful skip
    failOnNoGitDirectory = false
}
```

```yaml
# application.yaml — git.mode=full 顯示完整欄位（branch / commit / dirty 等）
management:
  info:
    git:
      mode: full   # 預設 simple 只 commit-id + time
```

### `/actuator/info` 預期輸出

```json
{
  "git": {
    "branch": "main",
    "commit": { "id": { "abbrev": "abc123" }, "time": "2026-..." },
    "dirty": "false",
    ...
  },
  "build": {
    "artifact": "appname",
    "group": "io.example",
    "name": "appname",
    "version": "0.0.1-SNAPSHOT",
    "time": "2026-..."
  }
}
```

### Cloud Build 沒 `.git/` 的處理

`gcloud builds submit` **預設 ignore `.git/`**（內建行為，不需 `.gcloudignore`
顯式排除）。沒設 `failOnNoGitDirectory=false` → cloud build 全掛在
`:generateGitProperties` task。

設了之後：
- 本機 `./gradlew bootBuildImage`（有 `.git/`） → `git.properties` 正常產 →
  `/actuator/info` 含 `git` + `build` 區塊
- Cloud Build → silently skip `git.properties` → `/actuator/info` 只有 `build`
  區塊，但 build 不 fail

要讓 cloud-built image 也帶 git info：建 `.gcloudignore` 顯式 keep `.git/`。
LAB / 內部部署通常不必要（debug 從 build version 就夠）。

### 反模式

- **不要**期待 plugin 自動找 `.git/` — Gradle 在 sub-project（如 monorepo 的
  `backend/`）跑時，預設找的是 `backend/.git/` 不是 root `.git/`。明確指
  `dotGitDirectory`。
- **不要**讓 `failOnNoGitDirectory` 預設 true 進 CI — CI 沒 `.git/` 時整個 build
  掛掉，產不出 image。

### 引用文件

- [Spring Boot — Generate Git Information](https://docs.spring.io/spring-boot/how-to/build.html#howto.build.generate-git-info)
- [Spring Boot Gradle Plugin — Integrating with Actuator](https://docs.spring.io/spring-boot/gradle-plugin/integrating-with-actuator.html)
- [n0mer/gradle-git-properties README](https://github.com/n0mer/gradle-git-properties)

---

## §6 Diagnostic — 「不知道是誰回的 403/404/401」快速分流

| 症狀 | 第一手診斷 |
|------|----------|
| Filter chain log 看到 `Securing METHOD path` 之後就斷，response 403 | CorsFilter / CSRF filter / StrictHttpFirewall short-circuit（§2）— **不是** auth gate |
| Spring log 完整跑完到 `Secured METHOD path`，response 403 | AuthorizationFilter 拒絕 — `.authenticated()` / `hasRole()` 等不過。看當下 Authentication 是 Anonymous / OAuth2/ JWT 哪一種 |
| Response 404 + `<ErrorResponse>No static resource` | Spring Boot 預設 404（路徑沒 controller、`static/` 沒對應檔）— SPA 深連直連（§3） |
| Response 401 對 `/api/...` | 真的沒認證（無 session、無 Bearer），entry point 觸發 |
| Response 302 to `/oauth2/authorization/...` | 受保護路徑被未認證 user 訪問，OAuth2 Login chain 把 user 引去 IdP |
| OAuth callback 回 `redirect_uri_mismatch` | §1 — `{baseUrl}` 解析錯 / Console redirect URI 跟 yaml 不同步 |
| Cloud Run startup probe fail，log 顯示某 bean missing | AOT profile 漏 / 缺 stub property — 詳 `aot-deployment-pitfalls.md` |

### 高價值 instrumentation pattern

當 Spring 預設 log 不夠看時，custom `AccessDeniedHandler` 是最划算的單一加料：

```java
private AccessDeniedHandler diagnosticAccessDeniedHandler() {
    return (request, response, ex) -> {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        log.atWarn()
            .addKeyValue("path", request.getRequestURI())
            .addKeyValue("method", request.getMethod())
            .addKeyValue("authClass", auth == null ? "null" : auth.getClass().getSimpleName())
            .addKeyValue("authenticated", auth != null && auth.isAuthenticated())
            .addKeyValue("principal", auth == null ? "null" : auth.getName())
            .addKeyValue("authorities", ...)
            .addKeyValue("reason", ex.getMessage())
            .log("Access denied");
        response.sendError(403, ex.getMessage());
    };
}
```

接到 `http.exceptionHandling(eh -> eh.accessDeniedHandler(...))`。**注意：filter
chain 早期 short-circuit（§2 CORS）不會經過 ExceptionTranslationFilter，所以
這個 handler 看不到 — 看不到本身就是訊號**：「不是 auth 拒絕的」。

### 開 Spring Security DEBUG 不重 build

```yaml
# Cloud Run service.yaml env（不需 image 重 build）
- name: logging.level.org.springframework.security
  value: DEBUG
```

`gcloud run services replace` 起新 revision 即時生效。診斷完成後改回去
（避免長期 noise）。

---

## 相關參考

- `aot-deployment-pitfalls.md` — AOT 階段（build-time）陷阱：profile 對齊、
  self-reference placeholder、stub for non-empty validation、aot leak
- `cloud-gcp-secrets.md` — Secret Manager 注入模式（Cloud Run env var mount）
- `config-design-principles.md` — 雙層 profile + placeholder skeleton 設計
