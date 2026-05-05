---
topic: "S134 T04 — Switched real OAuth IdP from omnihubs to Google; AC-2 PASS, awaiting browser login"
session_type: "development"
status: "in_progress"
date: "2026-05-05"
---

# Handover: S134 T04 — Real IdP Switched to Google, AC-2 PASS

## Layer 1 — Portable Summary

### Completed

- **Takeover from prior session**：`/takeover` 讀回 `2026-05-05-s134-t04-real-idp-login-flow-blocked.md`，confirms 卡點是 IdP-side（user 多次密碼錯 + 誤點 Microsoft SSO link → IdP generic「Whoops 技術問題」+ trace ID `7f56bf8542befe0ed82666af50a51ab8`），與我方 OAuth client 配置無關。
- **User 決定換 IdP 到 Google OAuth**（避開 omnihubs IdP-side 卡點），提供 client_id `[REDACTED]` + client_secret `[REDACTED]` + redirect `http://localhost:8080/login/oauth2/code/skillshub`。
- **更新 `backend/config/application-real-oauth.yaml`**（gitignored）為 Google 配置：
  - `issuer-uri: https://accounts.google.com`（兩處：client.provider.skillshub + resourceserver.jwt；無 trailing slash）
  - `scope: openid,email,profile`（原本只有 `openid`）
  - `client-id` / `client-secret` 換成 Google 的
  - 註解標出 Google access_token 是 opaque（非 JWT）的 caveat
- **Backend 重啟**：`SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun`，bg task `bzkwtfth0`，10.4s 啟動，Tomcat on 8080，PID 9400 confirmed listening。
- **AC-2 PASS — Google authorization redirect**：`curl /oauth2/authorization/skillshub` → 302，Location header 完整且全部對齊 GCP Console：
  - Authorization endpoint = `https://accounts.google.com/o/oauth2/v2/auth`（discovery 出來的，issuer-uri 生效）
  - `client_id=[REDACTED]` ✅
  - `scope=openid email profile` ✅
  - `redirect_uri=http://localhost:8080/login/oauth2/code/skillshub` ✅（對齊 Google Cloud Console）
  - `state=<random>` ✅、`nonce=<random>` ✅、`code_challenge + S256` ✅（PKCE）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| 換 IdP 到 Google（非繼續打 auth-dev.omnihubs.cloud） | 上次 session IdP-side 卡死（lockout 或 IdP bug）user 控不到；Google 是 stable 已知好用的 OIDC IdP | 等 IdP 管理員解 trace ID — 阻塞時間不可控；Google 同樣可驗 H1 PKCE 與大部分 AC-3/4 |
| `scope` 加 `email,profile` | Google OIDC 慣例 — 想拿 user email + name + picture claims；只 `openid` 也能登入但 claim shape 太瘦 | 只 `openid` — claim 不夠豐富，AC-4 dump 沒料 |
| `client-authentication-method: client_secret_basic` 保留不變 | Google 同時支援 `client_secret_basic` 與 `client_secret_post`；basic 是預設且通用 | `client_secret_post` — 沒必要切 |
| 不動 SecurityConfig.java | 純 issuer-uri discovery 設計，IdP 換掉 yaml 就好；Java 端對 IdP 無 hardcode 依賴 | 改 Java code — YAGNI |
| `application-real-oauth.yaml.example` 不動 | 它是 generic placeholder template，不該 Google-specific；只動本地 gitignored 檔 | 一併改 example — 失去模板的通用性 |

### Blockers

**(none — 等 user 在自己瀏覽器完成 Google 登入)**

非 blocker 但是 spec §AC 影響的 caveat（換 Google 後出現的）：

1. **Access token = opaque（非 JWT）** — Google 跟標準 OIDC IdP 最大差別。`AC-5 bearer dual-path` 對 Google 直接失敗（`JwtDecoder` decode 不出 opaque token）。要驗 bearer 路徑得改打 `https://www.googleapis.com/oauth2/v3/userinfo` 做 introspection，或這條 AC 在 spec §7 標 N/A。
2. **沒 `roles` claim** — Google id_token 不帶 `roles`，`JwtAuthenticationConverter.setAuthoritiesClaimName("roles")` 抓空。`@PreAuthorize("hasRole('admin')")` 永遠 false → `/api/v1/admin/**` 在 Google 帳號下無法通過。
3. **Claim shape 跟 omnihubs 不同** — Google id_token 標準 claims：`sub / email / email_verified / name / picture / given_name / family_name / iss / aud / exp / iat`。AC-4 dump 對比基準從「mock vs omnihubs」改成「mock vs Google」，spec §7 比對段需重寫。

### Next Steps

1. **User 在自己瀏覽器**（不走 Chrome MCP，因為是真實 Google 帳號）開 `http://localhost:8080/oauth2/authorization/skillshub`，完成 Google 同意流程
   - 預期落點：`http://localhost:8080/`（首頁）
   - 若失敗：頁面落點 + `/tmp/skillshub-real-oauth.log` 的 callback / token exchange 段
2. **Assistant 解 backend log**（bg task `bzkwtfth0`，日誌 `/tmp/skillshub-real-oauth.log`）：
   - `grep -E "login/oauth2/code|InvalidClient|InvalidGrant|invalid_request|invalid_grant|OAuth2|nonce" /tmp/skillshub-real-oauth.log`
   - 預期 `OAuth2LoginAuthenticationFilter` token exchange 成功
   - **驗 H1 PKCE 接受度**：log 不出現 `invalid_request`/`invalid_grant` → H1 PASS（Google 完整支援 PKCE，理論上不需要 contingency `requireProofKey(false)`）
3. **AC-4 claim shape dump**：同 browser session → `http://localhost:8080/auth-debug` → 拿完整 JSON（id_token_claims + access_token_claims）
   - 預期 access_token_claims 段 decode 失敗或 empty（opaque）— 這是 Google 特性
   - 預期 id_token_claims：sub / email / email_verified / name / picture / given_name / family_name / iss=https://accounts.google.com / aud / exp / iat / nonce
4. **AC-5 bearer dual-path**：對 Google **不適用**（access_token opaque）；spec §7 標 N/A 並備註原因。或改打 userinfo endpoint 做 introspection demo（額外工作量）。
5. **AC-6 regression**：Ctrl-C `bzkwtfth0` → `cd backend && ./gradlew bootRun`（無 real-oauth profile）→ `curl /api/v1/skills` 200 + `curl /api/v1/dev/auth-debug` 404（`@Profile` guard）。
6. **Spec §7 寫入 records**：AC-2/3/4/5/6 records、mock vs Google claim 對比、H1/H2 verdict、Google opaque-token caveat。
7. **Phase 4 subagent QA review**。
8. **`/shipping-release`** archive S134 + commit + tag。

### Lessons Learned

- **Spring Security 純 issuer-uri discovery 設計付費了** — IdP 換 omnihubs → Google 零 Java code 改動，純 yaml 1 個檔（client_id/secret/scope/issuer × 2 處）。這是 OIDC + `JwtDecoders.fromIssuerLocation` lazy discovery 的好處。
- **Google OAuth 的「OIDC 但 access_token 是 opaque」是行業已知奇行種** — Google 雖 advertise OIDC（id_token 是 JWT），但 access_token 不是 JWT，理由是 Google 想把 access_token 當 internal opaque handle。對 Resource Server JWT bearer 模式不相容；要做 bearer auth 得用 userinfo endpoint introspection，或選別的 IdP（Auth0 / Keycloak / okta 都發 JWT access_token）。
- **scope 在 yaml 用逗號分隔** — Spring Boot binding 會切成 list，最後 build URL 時 URL-encode 成 `openid%20email%20profile`（空格分隔，OAuth2 spec 要求）。寫法 `openid,email,profile` 與 `openid email profile` 都可以，但 yaml list syntax `[openid, email, profile]` 也可以；選 string + 逗號最簡潔。
- **GCP Console redirect URI 必須 byte-for-byte match** — trailing slash / port / scheme / 大小寫任一不對都炸 `redirect_uri_mismatch`；用 `{baseUrl}/login/oauth2/code/{registrationId}` 模板 resolve 出來是 `http://localhost:8080/login/oauth2/code/skillshub`，必須在 GCP Console 顯式註冊這個值。
- **Chrome MCP 不適合 real Google login** — Chrome MCP tab 是個 isolated 自動化 session，user 用 personal Google 帳號登入時建議走自己日常瀏覽器（Chrome / Safari），避免 cookie / 2FA 設備等問題；MCP 可以保留給 mock-oauth2-server 路徑。

### Session Summary

承接上 session 留下的 S134 T04 卡點（auth-dev.omnihubs.cloud IdP-side error），user 直接決定換 IdP 到 Google OAuth 並提供 credentials。Assistant 純 yaml 切換 `application-real-oauth.yaml`（client_id/secret/scope/issuer × 2 處），SecurityConfig.java 因為純 issuer-uri discovery 設計零改動。重啟 backend 10.4s PASS，AC-2 curl 驗 302 redirect 全部對齊（Google authorization endpoint discovery 生效、PKCE/state/nonce/redirect_uri 全綠）。Briefing user 說明 Google 三大 caveat（opaque access_token / 無 roles claim / claim shape 不同）對剩餘 AC-3/4/5/6 的影響後，user 換班。Handover 留給下次 takeover：等 user 在自己瀏覽器完成 Google 同意流程後，接 backend log 解析跑 AC-3 ~ AC-6 + spec §7 寫 records。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Backend running | YES — bg task `bzkwtfth0`，PID 9400，`lsof :8080` confirmed LISTEN，profile `local,dev,real-oauth`，log `/tmp/skillshub-real-oauth.log`，10.4s 啟動 |
| Test Status | T01-T03 全 PASS（前 session）；本 session 純 manual smoke + curl AC-2，**不**動 production code、**不**跑 unit/integration tests |

### Uncommitted Changes

```
 M CLAUDE.md
 M backend/src/test/resources/application.yaml
 M docs/grimo/architecture.md
 M docs/grimo/specs/2026-05-02-S101-quality-impact-security-scores.md
 M docs/grimo/specs/spec-roadmap.md
?? .claude/handovers/archive/2026-05-05-s134-t04-real-idp-login-flow-blocked.md
?? docs/grimo/research/
?? docs/grimo/specs/2026-05-05-S135-meta-skill-quality-score-system.md
?? docs/grimo/specs/2026-05-05-S135a-backend-quality-score.md
?? docs/grimo/specs/2026-05-05-S138-sb4-ss7-auth-test-recovery.md
?? docs/grimo/tasks/2026-05-05-S135a-T01.md ... T07.md (S135a 7 個 task files)
# user-only, NOT committed (gitignored), edited this session:
   backend/config/application-real-oauth.yaml  (改成 Google 配置；不在 git diff 裡)
# S134 in-flight code 已被 commit 96387c6 feat(S134-T01)：
#   - SecurityConfig.java 三分支
#   - AuthDebugPage.tsx + AuthDebugController.java
#   - S134-T01..T04 task files
#   - 只剩 §7 待 T04 完成後補
```

### Recent Commits

```
86381e1 feat(skills): add skill-author skill — author + optimize agent skills
ea160dc docs: lean spec-roadmap index + planning-spec/shipping-release skill redesign + DESIGN.md sync
96387c6 feat(S134-T01): Real OAuth backend infra + SecurityConfig three-way branch + AuthDebugPage
bc6eaed docs: Spec-Linked Rationale 原則 + 套用到 ProcessAot baked profile
51e0207 docs(skills): root-cause-debugging v1.2 — Ground-in-Official-Docs 原則
```

### Key Files

**This session edited:**
- `backend/config/application-real-oauth.yaml` — gitignored；本 session 從 omnihubs 切到 Google（client_id/secret/scope `openid,email,profile`/issuer-uri × 2 處 `https://accounts.google.com`）

**Reference for next session:**
- `backend/config/application-real-oauth.yaml.example` — generic placeholder template，**未動**（保留通用性，避免 Google-specific 滲入模板）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` — 純 issuer-uri discovery，IdP 換掉零改動
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java` — `@Profile("real-oauth")` 守衛的 dev endpoint，AC-4 用
- `frontend/src/pages/AuthDebugPage.tsx` — 前端 dump UI，AC-4 用
- `docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md` — spec §1-6 完成，§7 待 T04 完成後補
- `docs/grimo/tasks/2026-05-05-S134-T04.md` — manual smoke checklist + H1/H2 hypothesis；要根據 Google 實況更新（opaque access_token caveat）

**Runtime / Diagnostic:**
- `/tmp/skillshub-real-oauth.log` — 完整 backend log（bg task `bzkwtfth0`）；接班後 `grep -E "login/oauth2/code|invalid_request|invalid_grant|nonce|OAuth2"` 找 callback / token exchange / error
- `lsof -nP -iTCP:8080 -sTCP:LISTEN` — 驗 backend 還活著（換班瞬間 PID 9400）

**Google IdP reference:**
- IdP base: `https://accounts.google.com`
- Discovery: `https://accounts.google.com/.well-known/openid-configuration`
- Authorize endpoint: `https://accounts.google.com/o/oauth2/v2/auth`（discovery 出來）
- Token endpoint: `https://oauth2.googleapis.com/token`（discovery 出來）
- Userinfo endpoint: `https://openidconnect.googleapis.com/v1/userinfo`（如 AC-5 改用 introspection 會用到）
- Client ID: `[REDACTED]`
- Redirect URI 已在 GCP Console 註冊：`http://localhost:8080/login/oauth2/code/skillshub`

### Pre-existing tech debt（**不在 S134 scope；建議 follow-up spec**）

1. ✅ Collateral-fixed in T01：`CurrentUserProviderTest` + `SearchConfigTest` 2-arg ctor（自 S128 + S014 起 broken）
2. 📋 Follow-up：`processTestAot` AOT context wiring on `WebMvcSliceTestBase`（`PermissionEvaluator` not resolved during AOT）— 自 `3b48bc2 feat(native)` 起。S134 跑 test 用 `-x processTestAot` workaround
3. 📋 Follow-up：174/419 `@SpringBootTest` 全 context tests fail（`GcpSecretManagerAutoConfiguration` 缺 `CredentialsProvider`）— `local` profile 的 `secretmanager.enabled=false` 在某些 test 路徑沒 cascade（**已 drafted spec S138** in `docs/grimo/specs/2026-05-05-S138-sb4-ss7-auth-test-recovery.md`）
