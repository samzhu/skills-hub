---
topic: "S134 T04 — Real IdP login flow blocked by IdP-side error after multiple password attempts"
session_type: "development"
status: "blocked"
date: "2026-05-05"
---

# Handover: S134 T04 — Real IdP Login Flow Blocked

## Layer 1 — Portable Summary

### Completed

- **Takeover briefing 完成**：從 archive `2026-05-05-s134-real-oauth-idp-local-dev-integration-trial.md` 讀回 S134 上下文（spec / 4 task files / T01-T03 PASS / T04 卡 user prerequisite）
- **User confirmed prerequisite**：`backend/config/application-real-oauth.yaml` 已填妥 client_secret（檔案存在、size 2710 bytes、issuer-uri 無 trailing slash 對齊）
- **Backend 啟動 PASS**：`SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun`（PID 46301，bg `bqa44jv6n`）— `Started SkillshubApplication in 10.763 seconds`，pgvector + mock-oauth2-server compose 容器 healthy，14 Flyway migrations up-to-date，Modulith outbox staleness monitor running
- **AC-2 PASS — discovery 302 + PKCE**：`curl /oauth2/authorization/skillshub` 回 302，Location header 完整：
  - `client_id=596527ca-e045-4cbe-b78b-692c9a303e14` ✅
  - `redirect_uri=http://localhost:8080/login/oauth2/code/skillshub` ✅
  - `scope=openid` ✅
  - `code_challenge=<base64url> + code_challenge_method=S256` ✅（PKCE 啟用，Spring Security 7 default）
  - `state=<random>` ✅（CSRF）
  - `nonce=<random>` ✅（OIDC bonus，IdP advertise OIDC metadata 後自動帶）
- **Chrome MCP 環境就緒**：tab group `780211429`，tabId `574302381`，已導航至 `https://auth-dev.omnihubs.cloud/596527ca.../login`，IdP 登入頁渲染正常（含「請輸入帳號 / 密碼 / 登入」+「使用 Microsoft 登入」SSO link）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| User 自行在 Chrome tab 輸入 IdP 帳號密碼 | 安全規則禁止 assistant 代填密碼（`Never authorize password-based access`） | 代填 — 違反 user_privacy 規範 |
| 每次失敗就 `navigate` 回 `/oauth2/authorization/skillshub`，讓 Spring 發新 state + code_challenge | 舊 state 一次性 — IdP 可能拒舊 state；新 request 乾淨 | reuse 舊 URL — state expire 風險 |
| 不修 production code、不 rollback | T01-T03 backend / FE 都 PASS；卡點純粹 IdP-side credential 問題 | 動 code — 沒根因證據支持 |

### Blockers

**T04 IdP login 連續失敗，IdP 回 generic 錯誤頁**

| Attempt | Result | Why It Failed |
|---------|--------|---------------|
| User 第 1 次輸密碼 | User 自報「密碼記錯了」 | User-side typo |
| User 第 2 次（似乎走了 Microsoft SSO 路徑） | Chrome tab 跳到 `https://login.microsoftonline.com/051cece0-e4dc-4aed-b471-bf29824e1ee6/login` | User 點到「使用 Microsoft 登入」link 而非用本地 IdP form |
| Re-navigate 回 `/oauth2/authorization/skillshub`，user 再試一次 | Tab 落在 `https://auth-dev.omnihubs.cloud/auth-error?error`，頁面顯示「Whoops ! 抱歉，我們遇到了一些技術問題」+ Trace ID `7f56bf8542befe0ed82666af50a51ab8` | IdP 內部 error；backend log 全程**無**任何 `/login/oauth2/code/skillshub` callback 命中（即 user 從未走到我方 redirect_uri）— 表示 IdP 在 token / authorize 階段自己就炸了 |
| 第 3 次 re-navigate（user 還沒輸密碼即發訊「再試一次」） | Tab 又回到 IdP login form，等 user 輸入 | Pending — user 端決定是否再試或先排查 IdP |

Backend `/tmp/skillshub-real-oauth.log` 對 callback 路徑 grep 0 hit，confirms IdP 從未發 code 回我方。

Current hypothesis：
1. **最可能**：user 多次輸錯密碼導致 IdP 帳號 / IP 被臨時 lockout（連到正確密碼也會出錯）
2. **次可能**：IdP-side bug — user 在 Microsoft SSO 跳轉後 session 殘留 / state mismatch（trace ID 給 IdP 管理員可確認）
3. **不太可能**：我方 PKCE / state / redirect_uri 配置錯 — AC-2 curl 已證明 query 都對；且若是我方錯，IdP 會回 OAuth standard error（`invalid_request` / `redirect_uri_mismatch`）而非 generic 「技術問題」頁

### Next Steps

1. **User 端**：等 5-10 分鐘讓可能的 IdP rate-limit / lockout 退冷，然後**全新無痕分頁**直接打 `https://auth-dev.omnihubs.cloud` 嘗試**獨立**登入（繞開我方 OAuth flow），確認帳號密碼本身可用 + 帳號未被鎖
   - PASS → 回到我方 flow 重試（assistant 會 re-navigate 並等 user 輸密碼）
   - FAIL → 把 trace ID `7f56bf8542befe0ed82666af50a51ab8` 給 IdP 管理員，等對方解鎖 / 修
2. **User 確認可登入後通知 assistant**，assistant 接續：
   - Chrome tab 574302381 navigate `/oauth2/authorization/skillshub` 拿新 state
   - 等 user 完成輸入 → 預期 302 到 `localhost:8080/login/oauth2/code/skillshub?code=...&state=...`
   - 預期 backend log 出現 `OAuth2LoginAuthenticationFilter` token exchange 成功；landing 在 `/`
   - **驗 H1 PKCE 接受度**：log **不**出現 `invalid_request` / `invalid_grant` → H1 PASS；若出現 → 改 SecurityConfig 加 `ClientSettings.builder().requireProofKey(false).build()` programmatic override（spec §2.4 #1 已預告 contingency），重跑 AC-3
3. **AC-4 claim shape dump**：同 browser → `http://localhost:8080/auth-debug` 拿完整 JSON（id_token_claims + access_token_claims）；mask `client_id` 後 8 字元 + `access_token_value` 中段
4. **AC-5 bearer dual-path**：用 step 3 拿到的 access_token 跑 `curl -H "Authorization: Bearer $TOKEN" /api/v1/dev/auth-debug`；驗 `principal_name == sub`、無 session 也通
5. **AC-6 regression**：Ctrl-C bg `bqa44jv6n` → `cd backend && ./gradlew bootRun`（無 real-oauth profile）→ `curl /api/v1/skills` 200 + `curl /api/v1/dev/auth-debug` 404（`@Profile` guard 沒 register bean）
6. **Spec §7 寫入 records**：AC-2/3/4/5/6 records、mock vs real claim 對比、H1/H2 verdict
7. **Phase 4 subagent QA review**
8. **User 跑 `/shipping-release`** archive S134 + commit + tag

### Lessons Learned

- **IdP error 頁是 generic 「Whoops 技術問題」+ trace ID，沒分流 401 / 5xx / lockout** — 不能從錯誤頁文字判斷根因；唯一線索是 backend callback log（若我方零 hit → 純 IdP-side issue，與我方 OAuth client 配置無關）
- **Chrome MCP `tabs_context_mcp`** 顯示當前 tab URL — 是 debug login flow 走到哪一步的最快訊號（auth-error / login form / Microsoft SSO 跳轉都看得出來）
- **Microsoft SSO link 在 IdP login 頁同列**：user 不小心點到會跳到 `login.microsoftonline.com/051cece0-...`；下次 brief user 時要強調點「登入」按鈕而非「使用 Microsoft 登入」link，除非他 IdP 帳號是綁 Microsoft federated
- **每次失敗 navigate 回 `/oauth2/authorization/skillshub` 是必要的** — Spring 會發新 state + code_challenge；reuse 舊 URL 可能 state expire / single-use 限制
- **安全規則明確**：`Never authorize password-based access to an account on the user's behalf, always direct the user to input passwords themselves` — assistant 從頭到尾不該碰密碼欄位

### Session Summary

User 從 archive 接手 S134（real OAuth IdP local dev trial），通知已填好 `application-real-oauth.yaml`，要 assistant 跑 T04 manual E2E smoke。Assistant 起 backend（real-oauth profile，PASS）+ AC-2 curl 驗 302/PKCE/state/nonce 全通；切 Chrome MCP 開 IdP login 頁等 user 輸密碼。User 第一次密碼記錯，第二次似乎誤點了 Microsoft SSO link 跳到 login.microsoftonline.com，re-navigate 回我方 flow 後 IdP 直接回 generic 「Whoops 技術問題」+ trace ID — 我方 backend log 全程零 callback 命中，confirms 卡在 IdP 內部，不是我方 OAuth client 配置問題。Session 在 user 第三次嘗試前換班，handover 留給下次 takeover：先 user 在無痕視窗獨立確認 IdP 帳號可用（驗 lockout / rate-limit hypothesis），再回來重跑 AC-3 → AC-6。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Backend running | YES — bg task `bqa44jv6n`，PID 46301，log `/tmp/skillshub-real-oauth.log`，profile `local,dev,real-oauth`，port 8080 |
| Chrome MCP tab | tabId `574302381`，tab group `780211429`，URL 應為 `https://auth-dev.omnihubs.cloud/596527ca.../login`（最後一次 re-navigate 後） |
| Test Status | T01 1/1 + T02 2/2 + T03 2/2 + frontend 198/198 PASS（前 session）；本 session 純 manual smoke，**不**動 production code |

### Uncommitted Changes

```
M backend/.gitignore
M backend/src/main/java/io/github/samzhu/skillshub/SkillshubProperties.java
M backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java
M backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigTest.java
M backend/src/test/java/io/github/samzhu/skillshub/shared/security/CurrentUserProviderTest.java
M docs/grimo/specs/spec-roadmap.md
M frontend/src/App.tsx
?? backend/config/application-real-oauth.yaml.example
?? backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/
?? backend/src/test/java/io/github/samzhu/skillshub/shared/security/RealOAuthConfigTest.java
?? backend/src/test/java/io/github/samzhu/skillshub/shared/security/dev/
?? docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md
?? docs/grimo/tasks/2026-05-05-S134-T01.md      # PASS
?? docs/grimo/tasks/2026-05-05-S134-T02.md      # PASS
?? docs/grimo/tasks/2026-05-05-S134-T03.md      # PASS
?? docs/grimo/tasks/2026-05-05-S134-T04.md      # in_progress, blocked
?? frontend/src/pages/AuthDebugPage.test.tsx
?? frontend/src/pages/AuthDebugPage.tsx
# user-only, NOT committed (gitignored), confirmed exists this session:
   backend/config/application-real-oauth.yaml  (size 2710, issuer-uri 無 trailing slash)
# S133 in-flight 殘留（不在 S134 scope）：
?? docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md
?? docs/grimo/tasks/2026-05-05-S133-T01.md
?? docs/grimo/tasks/2026-05-05-S133-T02.md
?? docs/grimo/tasks/2026-05-05-S133-T03.md
?? .claude/handovers/archive/2026-05-05-native-image-s133-markdown-drafted.md
?? .claude/handovers/archive/2026-05-05-s134-real-oauth-idp-local-dev-integration-trial.md
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

**Spec / task**
- `docs/grimo/specs/2026-05-05-S134-real-oauth-local-trial.md` — §1-6 完成，§7 待 T04 補
- `docs/grimo/tasks/2026-05-05-S134-T04.md` — 完整 manual smoke checklist + Hypothesis Validation（H1 PKCE / H2 claim shape）+ Records to capture

**Backend runtime**
- 啟動 cmd：`cd backend && SPRING_PROFILES_ACTIVE=local,dev,real-oauth ./gradlew bootRun`
- Bg task ID：`bqa44jv6n`（**takeover 時可能還在跑或已被 user kill**；用 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 驗）
- Log path：`/tmp/skillshub-real-oauth.log`（`grep -E "login/oauth2/code|InvalidClient|InvalidGrant|OAuth2" $log` 是診斷 callback 進來與否的快指令）

**Backend prod code（前 session T01-T02 寫，本 session 不動）**
- `backend/config/application-real-oauth.yaml.example` — 模板
- `backend/config/application-real-oauth.yaml` — **user-filled，gitignored，size 2710 bytes，本 session confirmed 存在**；issuer-uri `https://auth-dev.omnihubs.cloud`（無 slash）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` — 三分支 LAB / RS-only / RS+OAuth2 Login
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/dev/AuthDebugController.java` — `@Profile("real-oauth")` dev endpoint

**IdP reference**
- IdP base：`https://auth-dev.omnihubs.cloud`
- Authorize endpoint：自動發 → `/oauth2/authorize?client_id=...&...`
- Login form path：`/596527ca-e045-4cbe-b78b-692c9a303e14/login`
- Microsoft SSO link：`/oauth2/authorization/265c3c6b-cf06-4cf1-ba2e-73497a4f9098`（**user 別點，除非帳號是 Microsoft federated**）
- Error trace ID（last failure）：`7f56bf8542befe0ed82666af50a51ab8`
- Client ID：`596527ca-e045-4cbe-b78b-692c9a303e14`

**Chrome MCP**
- Tab group：`780211429`
- Tab ID：`574302381`
- 載入工具：`tabs_context_mcp / tabs_create_mcp / navigate / read_page / get_page_text / find / form_input / javascript_tool / read_console_messages / read_network_requests`
- Restart Chrome session：先 `tabs_context_mcp` 看 tabId 是否還在；不在就 `tabs_create_mcp`

### Pre-existing tech debt（**不在 S134 scope；建議 follow-up spec**）

1. ✅ Collateral-fixed in T01：`CurrentUserProviderTest` + `SearchConfigTest` 2-arg ctor（自 S128 + S014 起 broken）
2. 📋 Follow-up：`processTestAot` AOT context wiring on `WebMvcSliceTestBase`（`PermissionEvaluator` not resolved during AOT）— 自 `3b48bc2 feat(native)` 起。S134 跑 test 用 `-x processTestAot` workaround
3. 📋 Follow-up：174/419 `@SpringBootTest` 全 context tests fail（`GcpSecretManagerAutoConfiguration` 缺 `CredentialsProvider`）— `local` profile 的 `secretmanager.enabled=false` 在某些 test 路徑沒 cascade
