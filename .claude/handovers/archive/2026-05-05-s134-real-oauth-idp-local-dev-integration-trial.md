---
topic: "S134 — Real OAuth IdP local dev integration trial（T01-T03 PASS；T04 blocked on user-side client_secret + browser login）"
session_type: "development"
status: "blocked"
date: "2026-05-05"
---

# Handover: S134 — Real OAuth IdP Local Dev Integration Trial

## Layer 1 — Portable Summary

### Completed

- **Spec written + roadmap updated**：`docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md`（§1-6 完成，§7 待 T04 補）；roadmap M129 row 落 ⏳ Dev
- **Phase 2 research persisted**：spec §1 含 IdP discovery dump（OIDC + RFC 8414 雙端點皆 published；issuer 為 `https://auth-dev.omnihubs.cloud` **無 trailing slash**）；§2.5 列 11 條 Spring Security 7 raw-source citations
- **T01 PASS — Backend infra**：
  - `build.gradle.kts` 加 `spring-boot-starter-oauth2-client` dep
  - `SkillshubProperties.OAuth` 加 nested `Login` sub-record（default false）
  - `SecurityConfig` 三分支：LAB / RS-only / RS+OAuth2 Login hybrid（`if (oauth.enabled) { ...resource-server...; if (oauth.login.enabled) { http.oauth2Login().defaultSuccessUrl("/", true); } }`）+ `/api/v1/dev/**` requestMatcher
  - `application-real-oauth.yaml.example` template（committed）+ `.gitignore` exception pattern
  - `RealOAuthConfigTest` 1 unit test PASS（AC-8 trailing-slash trap 約束驗證）
  - Collateral fix：`CurrentUserProviderTest` + `SearchConfigTest` 修 pre-existing 2-arg `Search` / 2-arg `Security` ctor（HEAD 編譯壞了，自 S128/S014 起 broken；同時對齊新 OAuth(boolean, Login) ctor）
- **T02 PASS — AuthDebugController**：
  - `shared/security/dev/AuthDebugController.java` `@Profile("real-oauth")` + 雙 path（OAuth2AuthenticationToken session + JwtAuthenticationToken bearer）+ access_token base64url payload decode
  - `AuthDebugControllerTest` 2 tests PASS（oauth2Login post-processor + jwt post-processor）
- **T03 PASS — Frontend `/auth-debug` 「我的認證」頁**：
  - `frontend/src/pages/AuthDebugPage.tsx`（useQuery + 404 sentinel error fallback）
  - `frontend/src/App.tsx` 加 `<Route path="/auth-debug" />`
  - `AuthDebugPage.test.tsx` 2 tests PASS（200 dump + 404 fallback）
  - `npx tsc --noEmit` 0 errors；`npx vitest run` 198/198 tests PASS

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| Approach B：OAuth2 Login (Client) + 既有 Resource Server hybrid + `real-oauth` profile（不 commit） | 用上 user 註冊的 redirect URI `/login/oauth2/code/skillshub`；session + bearer 雙路都驗；mock 路徑零影響；profile-driven 可秒退 | A: 純 RS + 手動 curl 拿 token（不對齊 user intent — 已註冊 redirect URI 沒用上）；C: SPA 自跑 PKCE（FE 大改，超出 trial scope） |
| `issuer-uri: https://auth-dev.omnihubs.cloud`（無 trailing slash） | IdP metadata `issuer` field 為無 slash 字串；`JwtDecoderProviderConfigurationUtils.validateIssuer()` 走 `String.equals` 嚴格比對 — 配錯第一個 decode 直接 `IllegalStateException` | 帶 slash（會炸） |
| `application-real-oauth.yaml` 整個檔案 gitignored，提供 `.example` template | 對齊既有 `application-secrets.properties` 模式；client_secret 不入版控 | 把 secret 拉去 `application-secrets.properties`、yaml 用 placeholder（增複雜度，同樣有 client_id 也要 per-developer） |
| `@Profile("real-oauth")` AuthDebugController bean-level guard | 任何其他 profile 完全不註冊 bean → 不可能 leak 到 LAB / prod；多一道安全閘 | 只用 SecurityConfig requestMatchers 守（bean 還在；風險較高） |
| Pre-existing tech debt collateral fix（2 個 broken test files） | 不修 → my RED test 連 compile 都不過（test compile 是全 set 一起做） | 跳過 → blocking my work；單獨 spec 修 → user mid-flight 已給綠燈「之後再開任務整理」 |
| 用 `-x processTestAot` bypass pre-existing AOT wiring 失敗 | 此 AOT 失敗（`PermissionEvaluator` not resolved on `WebMvcSliceTestBase`）非 S134 引入；屬於 `3b48bc2 feat(native)` 後遺症 | 修 AOT issue → 超出 S134 scope；接受 follow-up tech debt |

### Blockers

**T04 manual E2E smoke + POC validation 卡在 user-only step**

T04 是整個 S134 的 POC（spec §2.3 兩 Hypothesis：IdP-side PKCE 接受度 + 真 IdP claim shape 只能靠真 client_secret + browser login 驗）。需要 user 兩件事：

| 步驟 | 狀態 |
|---|---|
| 1. `cp backend/config/application-real-oauth.yaml.example backend/config/application-real-oauth.yaml` 並填完整 client_secret | ⏳ pending — user 端 |
| 2. 開無痕分頁完成 IdP `https://auth-dev.omnihubs.cloud` 登入 flow，把 `/auth-debug` JSON dump 貼回（mask 敏感欄位） | ⏳ pending — user 端，需真實憑證 |

User 在 session 中段說過「可以呼叫真的 IdP 做整合, 之後再開一個任務來整理」— 授權 assistant 跑真 IdP 整合，但具體 secret 仍要 user 提供。Session 結束時我擺出 3 條 Path（A user 自跑全流程 / B user 只填 yaml 我跑能跑的部分 / C user 給 access_token 我自己跑 client_credentials grant）給 user 選。

Current hypothesis：user 會選 Path A 或 Path C，提供完整 client_secret 後 resume。

### Next Steps

1. **User 端**：填好 `backend/config/application-real-oauth.yaml`（複製 `.example` + 填完整 client_secret = `912e03e0-fad8-...` 完整字串；client_id `596527ca-e045-4cbe-b78b-692c9a303e14` 已知）
2. **User 通知 ready 後**，assistant 跑 T04：
   - `SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun` 啟動
   - curl `/oauth2/authorization/skillshub` 驗 AC-2（302 + PKCE code_challenge S256 + state）
   - 等 user 完成 browser login（AC-3）
   - 拿 user 給的 JSON dump 紀錄真 IdP claim shape 進 spec §7
   - 用 access_token 跑 AC-5 bearer dual-path 驗證
   - 切回預設 profile 跑 AC-6 final regression
3. **Assistant 寫 spec §7** Implementation Results 段（含 AC results table、claim shape vs mock 對比、H1/H2 hypothesis verdict、deviation 紀錄如有）
4. **Assistant 跑 Phase 4 subagent QA review** 獨立驗證
5. **User 跑 `/shipping-release`** archive spec + commit + tag

### Lessons Learned

- **HEAD 狀態：`./gradlew test` 早就 broken**：compile 失敗 4 個 errors（CurrentUserProviderTest 2 + SearchConfigTest 2 — Search 1-arg / Security 3-arg ctor 自 S128 + S014 簡化後從未 sync）；後續 commits 都是 docs / CI / native infra，沒人跑 `./gradlew test`。修 compile 後又揭 `processTestAot` AOT failure（`PermissionEvaluator` not resolved on `WebMvcSliceTestBase`）— 屬 `3b48bc2 feat(native)` 後遺症。再揭 174/419 `@SpringBootTest` 全 context 啟動 fail（`GcpSecretManagerAutoConfiguration` `CredentialsProvider` not resolved；`local` profile 的 `secretmanager.enabled=false` 在某些 test 路徑沒 cascade）。**3 個 pre-existing tech debt 全部與 S134 無關**；T01 已修第 1 個（顯式 collateral fix），第 2-3 個 follow-up spec 處理。
- **Spring Security 7 issuer probe order**（per `JwtDecoderProviderConfigurationUtils.getConfigurationForIssuerLocation()` raw source）：OIDC 標準 → OIDC RFC 8414 compat → OAuth-AS metadata；任一 200 即停。本 IdP OIDC + OAuth metadata 兩者皆有，OIDC 路徑先命中，自動走 ID token + userinfo flow（IdP advertise `userinfo_endpoint: /userinfo`）。
- **Trailing-slash trap**：IdP metadata `issuer` 為「無 trailing slash」字串。`JwtDecoderProviderConfigurationUtils.validateIssuer()` 與 `JwtIssuerValidator` 兩處都走 `String.equals` 嚴格比對；user 給的 URL `https://auth-dev.omnihubs.cloud/`（有 slash）若直接配進 yaml 會炸。Spec §2.2 #1 明示這個 + `RealOAuthConfigTest` 固化此設計約束。
- **Spring Security 7 default PKCE**：`ClientSettings.builder().build()` 預設 `requireProofKey=true`（per `ClientRegistration.java` line 780）— confidential client 也自動帶 PKCE S256。IdP metadata `code_challenge_methods_supported: ["S256"]` 支援。**不需要顯式 disable PKCE**；如 IdP-side client config 拒絕 confidential PKCE 才 fallback `.clientSettings(ClientSettings.builder().requireProofKey(false).build())`（spec §2.4 #1 預告 contingency）。
- **Spring Boot 4 auto-config 已自動建 `SupplierJwtDecoder`**（per `OAuth2ResourceServerJwtConfiguration.JwtDecoderConfiguration` `@ConditionalOnIssuerLocationJwtDecoder + @ConditionalOnMissingBean(JwtDecoder.class)`）— 但本專案有顯式 `@Bean SupplierJwtDecoder` 蓋過 auto-config（保留 `@ConditionalOnProperty` LAB toggle 控制權；spec §2.2 #5 有紀錄）。
- **OAuth2 Login + Resource Server 同 SecurityFilterChain 共存**：filter 位置完全不同（`OAuth2AuthorizationRequestRedirectFilter` ~1400 / `OAuth2LoginAuthenticationFilter` ~1800 / `BearerTokenAuthenticationFilter` ~2400 per `FilterOrderRegistration`）；session-based login + bearer-based RS 互不干擾。
- **Frontend test pattern**：專案無 MSW，用 `vi.fn() + vi.stubGlobal('fetch', ...)`（per `NotificationsPage.test.tsx`）。每 test 獨立 `QueryClient` 避免 cache 跨 test 污染。

### Session Summary

User 申請好真實 IdP（`auth-dev.omnihubs.cloud`）OAuth 資訊（issuer / client_id / partial client_secret / redirect URI），要求做 local dev 整合試行。Session 走完 `/planning-spec S134` → `/planning-tasks S134` → `/implementing-task S134` × 3 task loop。Phase 2 research 透過 2 個並行 sub-agent 把 Spring Security 7 OAuth2 Resource Server JWT decoder + OAuth2 Client/Login API 摸到 raw-source 等級（11 條 citation 入 spec §2.5）；同時 WebFetch 真實 IdP discovery（OIDC + RFC 8414 兩 endpoint 都通），確認 issuer 為「無 trailing slash」。寫 spec（S(9)，3 approach 對比，4 grill questions）→ 4 個 task files → T01 backend infra（含 collateral fix 修 pre-existing 2 個 broken test files；發現 3 個 pre-existing tech debt 與 S134 無關，記錄為 follow-up）→ T02 AuthDebugController + 2 unit tests → T03 frontend `/auth-debug` page + 2 tests + 全 198/198 frontend tests pass。T04 manual E2E smoke 卡 user 端 prerequisite（需要 user 填完整 client_secret + 完成瀏覽器登入），handover 結束於提供 3 條 Path 選項給 user 後續執行。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | T01 RealOAuthConfigTest 1/1 PASS；T02 AuthDebugControllerTest 2/2 PASS；T03 frontend AuthDebugPage 2/2 PASS + 全 frontend 198/198 PASS（with `-x processTestAot` workaround for backend pre-existing AOT issue） |

### Uncommitted Changes

```
M backend/.gitignore
M backend/src/main/java/io/github/samzhu/skillshub/SkillshubProperties.java
M backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java
M backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigTest.java
M backend/src/test/java/io/github/samzhu/skillshub/shared/security/CurrentUserProviderTest.java
M backend/build.gradle.kts
M frontend/src/App.tsx
M docs/grimo/specs/spec-roadmap.md
?? backend/config/application-real-oauth.yaml.example
?? backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java
?? backend/src/test/java/io/github/samzhu/skillshub/shared/security/RealOAuthConfigTest.java
?? backend/src/test/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugControllerTest.java
?? frontend/src/pages/AuthDebugPage.tsx
?? frontend/src/pages/AuthDebugPage.test.tsx
?? docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md
?? docs/grimo/tasks/2026-05-05-S134-T01.md  # PASS — Result section 已寫
?? docs/grimo/tasks/2026-05-05-S134-T02.md  # PASS — Result section 已寫
?? docs/grimo/tasks/2026-05-05-S134-T03.md  # PASS — Result section 已寫
?? docs/grimo/tasks/2026-05-05-S134-T04.md  # pending — 等 user prerequisite
# 也有 S133 in-flight 殘留（不在 S134 scope）：
?? docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md
?? docs/grimo/tasks/2026-05-05-S133-T01.md
?? docs/grimo/tasks/2026-05-05-S133-T02.md
?? docs/grimo/tasks/2026-05-05-S133-T03.md
?? .claude/handovers/archive/2026-05-05-native-image-s133-markdown-drafted.md
```

### Recent Commits

```
bc6eaed docs: Spec-Linked Rationale 原則 + 套用到 ProcessAot baked profile
51e0207 docs(skills): root-cause-debugging v1.2 — Ground-in-Official-Docs 原則
e91dc91 fix(native): drive AOT profile via Gradle property; minimum task block
a0d90e6 fix(ci): bump Cloud Build machine to 32 vCPU/32GB for native image OOM
b82eeb3 docs(skills): root-cause-debugging v1.1 — 5 個通用原則 from S133 native runtime 實戰
```

### Key Files

**Spec / 任務文件**
- `docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md` — spec §1-6 ready；§7 待 T04 補
- `docs/grimo/tasks/2026-05-05-S134-T01.md` — PASS（含 Result section + 3 個 pre-existing tech debt 紀錄）
- `docs/grimo/tasks/2026-05-05-S134-T02.md` — PASS
- `docs/grimo/tasks/2026-05-05-S134-T03.md` — PASS
- `docs/grimo/tasks/2026-05-05-S134-T04.md` — pending（含完整 manual smoke checklist + records-to-capture list + H1/H2 hypothesis validation criteria）
- `docs/grimo/specs/spec-roadmap.md` line ~300 — M129 row ⏳ Dev

**Backend production code**
- `backend/build.gradle.kts:46` — 加 `spring-boot-starter-oauth2-client` dep（line 47-48 inline 註解說明）
- `backend/src/main/java/io/github/samzhu/skillshub/SkillshubProperties.java` — `OAuth` 加 `Login` nested sub-record（default `enabled=false`）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` — 三分支邏輯；新增 `if (props.security().oauth().login().enabled()) { http.oauth2Login(...) }` 嵌在 oauth-enabled 內層 + `/api/v1/dev/**` requestMatcher
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java` — `@Profile("real-oauth")` dev endpoint；雙 path（OAuth2AuthenticationToken / JwtAuthenticationToken）+ access_token base64url payload decode helper

**Backend test**
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/RealOAuthConfigTest.java` — AC-8 trailing-slash 約束 unit test（讀 `.example` template 掃 issuer-uri 行）
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugControllerTest.java` — `@WebMvcTest` slice + `@ActiveProfiles({"test","real-oauth"})` + 2 tests
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/CurrentUserProviderTest.java`（modified）— line 244-259 collateral fix
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigTest.java`（modified）— line 36-52 collateral fix

**Backend config**
- `backend/config/application-real-oauth.yaml.example` — committed template（含 trailing-slash warning + 完整 setup 指引）
- `backend/config/application-real-oauth.yaml` — **不存在**，user 需自建（gitignored）
- `backend/.gitignore` — 加 `config/application-real-oauth.yaml` + `!config/application-real-oauth.yaml.example` 對稱 pattern

**Frontend**
- `frontend/src/pages/AuthDebugPage.tsx` — 新 page（useQuery + 404 sentinel pattern）
- `frontend/src/pages/AuthDebugPage.test.tsx` — 2 tests（vi.fn fetch mock + QueryClient provider）
- `frontend/src/App.tsx`（modified）— line 30: `import { AuthDebugPage }` + line 82: `<Route path="/auth-debug" />`

**重要 reference**
- IdP discovery URL：`https://auth-dev.omnihubs.cloud/.well-known/openid-configuration`（OIDC 完整）+ `https://auth-dev.omnihubs.cloud/.well-known/oauth-authorization-server`（RFC 8414）— 兩者皆 published；issuer 字段為 `"https://auth-dev.omnihubs.cloud"`（無 trailing slash）
- IdP redirect URI（已 user 端註冊）：`http://localhost:8080/login/oauth2/code/skillshub`
- Client ID（已知）：`596527ca-e045-4cbe-b78b-692c9a303e14`
- Client Secret prefix only（待 user 給完整）：`912e03e0-fad8-...`

### Pre-existing tech debt（**不在 S134 scope；建議 follow-up spec**）

1. ✅ Collateral-fixed in T01：`CurrentUserProviderTest` + `SearchConfigTest` 2-arg ctor（自 S128 + S014 起 broken）
2. 📋 Follow-up：`processTestAot` AOT context wiring on `WebMvcSliceTestBase`（`PermissionEvaluator` not resolved during AOT）— 自 `3b48bc2 feat(native)` 起。S134 跑 test 用 `-x processTestAot` workaround
3. 📋 Follow-up：174/419 `@SpringBootTest` 全 context tests fail（`GcpSecretManagerAutoConfiguration` 缺 `CredentialsProvider`）— `local` profile 的 `secretmanager.enabled=false` 在某些 test 路徑沒 cascade
