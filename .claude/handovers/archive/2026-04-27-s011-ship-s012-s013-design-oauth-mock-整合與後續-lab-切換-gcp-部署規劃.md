---
topic: "S011 ship + S012/S013 design — OAuth Mock 整合與後續 LAB 切換、GCP 部署規劃"
session_type: "development"
status: "completed"
date: "2026-04-27"
---

# Handover: S011 ship + S012/S013 design

## Layer 1 — Portable Summary

### Completed

- **Deep research → deepwiki**：對 navikt/mock-oauth2-server 完整分析（架構、token 核發、設定、data flow、設計決策、Skills Hub 借鑑分析）。完成於 session 中段，相關目錄 `docs/deepwiki/mock-oauth2-server/` **後被 user 清空**，故未進入 commit。
- **S011 ship**（commit `3b4fc77`，tag `v0.10.0`）：
  - `docker-compose` 加 `ghcr.io/navikt/mock-oauth2-server:3.0.1` service（port 9000:8080）
  - 三組假身分（admin/developer/viewer）核發符合 RFC 7519 + 9068 + SCIM 慣例的 JWT
  - `shared/security/` 新模組：`SecurityConfig`、`MeController` (`/api/v1/me`)、`AdminController` (`/api/v1/admin/echo` + `@PreAuthorize`)
  - 4 測試類別（OAuthMockE2ETest 用 Testcontainers + 3 個 MockMvc 單元測試）共 9 個 S011 測試
  - 全套測試 104/0/0/0 通過（`./gradlew clean test` 37s）
  - Spec archived 到 `specs/archive/`；CHANGELOG `[v0.10.0] - 2026-04-27`；roadmap M9 collapse；M8 (S010) 也順手 collapse
- **S012 spec design**（commit `9043b70`，狀態 🔵 in-design）：OAuth toggle + LAB 模式
  - `skillshub.security.oauth.enabled` property（預設 `true`）
  - `LabSecurityFilter` 注入 `lab-user` + `ROLE_admin` 預設 Authentication
  - `CurrentUserProvider` 統一抽象（OAuth 模式回 JWT subject、LAB 模式回 `lab-user`）
  - AdminController 不變（`@PreAuthorize` 兩模式都運作）
  - File plan: 3 modify + 6 add = 9 檔
- **S013 spec design**（commit `9043b70`，狀態 🔵 in-design）：GCP Cloud Run 部署腳本
  - 6 個 bash 腳本：`scripts/gcp/{01-bootstrap, 02-create-secrets, 03-build-push, 04-deploy, 99-teardown, .env.example}` + README
  - 涵蓋 6 GCP API + AR repo + Firestore Enterprise (MongoDB compat) + GCS + SA + 7 IAM roles + Secret Manager
  - 開發者只需 export 3 個 env var（`GCP_PROJECT_ID`、`GCP_REGION`、`SKILLSHUB_GENAI_API_KEY`）即可一鍵部署
  - Image tag 策略：git short SHA + `:latest` 雙 tag
  - Cloud Run `--allow-unauthenticated`
- **`.claude/loop.md`** 補充 🔵 in-design 處理流程
- 工作目錄全淨；2 個 commits ahead of origin；尚未 push

### Decisions

| Decision | Why | Alternatives Rejected |
|---|---|---|
| S011 用 navikt/mock-oauth2-server | MIT licence、JVM 同生態、多 issuer 零設定、JSON_CONFIG 靜態自訂簡單 | Soluto/oidc-server-mock（Duende IdentityServer 商用授權疑慮）；axa-group/oauth2-mock-server（Node 異生態） |
| JWT claim 命名 `roles` / `groups` / `company_id` / `dept_id` | RFC 9068 + SCIM RFC 7643 慣例；`dept_id` 單值（組織歸屬）vs `groups` 陣列（邏輯集合） | Azure AD `tid`、自訂 namespaced URI |
| S011 SecurityConfig 顯式 `JwtDecoder` bean (`SupplierJwtDecoder` 包裝) | Boot 4 / Security 7 auto-config 不會建 JwtDecoder + test classpath yaml 完全覆蓋 main yaml；用 `@Value` default 解雙重問題 | 依賴 auto-config（驗證後不可行） |
| S011 `.with(jwt())` 測試需顯式 `.authorities(...)` | post-processor 不會跑自訂 `JwtAuthenticationConverter`；不指定的話 admin token 通不過 `hasRole` | 預期 converter 會跑（驗證後錯）；T2 Testcontainers 走真實 path 補完 |
| S011 grant type 用 `client_credentials` + 3 個 client_id | curl/CI 友善；不需瀏覽器；`requestParam: client_id` + `match` 篩選 | Interactive login（需瀏覽器）；雙模式並存（超出 XS 範圍） |
| S011 issuer-uri = `http://localhost:9000/skills-hub-dev` | host 跑 bootRun + container 跑 mock，兩端透過 localhost 一致；不需 host.docker.internal | host.docker.internal（增加 onboarding 步驟）；profile 切換（過早抽象） |
| S012 用 property toggle 而非新 profile | env var 與 yaml 都能 override；CI/LAB 用 env var 直接；user 明確選此選項 | 新 LAB profile（與 dual-layer profile 不衝突但增加維護） |
| S012 lab user 帶 `ROLE_admin` | LAB 測試者要能完整驗證 admin 路徑；殘缺體驗無價值 | controller 被 `@ConditionalOnProperty` 關掉；保留但 403 |
| S013 用 bash + gcloud（非 Terraform） | user 明確要求腳本；MVP infra 變動少；零學習成本 | Terraform（IaC 標準但 MVP overkill）；Cloud Build（需先設好 GCP CI） |
| S013 image tag 策略 = git SHA + latest 雙 tag | rollback 可回特定 commit；`:latest` 作 dev 隨手部署預設 | 只用 `:latest`（無 rollback）；semver（MVP overkill） |
| S013 Cloud Run `--allow-unauthenticated` | MVP 內部 demo；OAuth 整合後再收 | `--no-allow-unauthenticated`（MVP 偷懶測試會卡） |

### Blockers

(none — session completed cleanly)

### Next Steps

1. **可選 push remote**：`git push origin main v0.10.0`（兩個未推送 commits + 新 tag）
2. **進 S012 task loop**：`/planning-tasks S012` → 拆 BDD task → `/implementing-task` → `/shipping-release`。預計 1-2 個 task，XS(8)
3. **進 S013 task loop**：`/planning-tasks S013` → S(11) 規模、預計 3-4 task；其中 03-build-push.sh 與 04-deploy.sh 需 docker daemon + 真實 GCP project 才能完整 E2E 驗證；建議先在個人 GCP 測試專案跑 `01-bootstrap.sh` + `02-create-secrets.sh` 驗 idempotency
4. **修 `.claude/loop.md` 規範若有新需求**（已加 🔵 in-design 處理）
5. （可選）若用戶日後想復原 deepwiki 文件，要重新跑 `/deep-research` 對 navikt/mock-oauth2-server

### Lessons Learned

- **Spring Boot 4 / Spring Security 7 OAuth2 Resource Server auto-config 不會自動建 `JwtDecoder` bean**——即使 `spring.security.oauth2.resourceserver.jwt.issuer-uri` 已設。必須顯式宣告 `JwtDecoder` bean，用 `SupplierJwtDecoder` 包裝確保 lazy discovery。記入 architecture doc 為 tech debt。
- **test classpath 的 `application.yaml` 完全覆蓋（非 merge）main `application.yaml`**——所以 `@Value("${prop:default}")` 須帶 default 值，否則測試環境抓不到。
- **`SecurityMockMvcRequestPostProcessors.jwt()` 不會跑自訂 `JwtAuthenticationConverter`**——測試需顯式 `.authorities(new SimpleGrantedAuthority("ROLE_admin"))`。Production 路徑由真實 Spring Security `JwtDecoder` + 自訂 converter 處理。
- **多個 `@SpringBootTest` 類各自起 testcontainer**——Spring Boot 4 文件確認 `@Bean @ServiceConnection` 不可用 static field；要 reuse 須開 `~/.testcontainers.properties` 的 `testcontainers.reuse.enable=true`。本 session 觀察到 docker 累積殘留容器導致 mongo SIGSEGV，user 清資料後 fresh `./gradlew clean test` 即綠。
- **`grafana/otel-lgtm` 容器疑似來自 `org.springframework.ai:spring-ai-spring-boot-testcontainers` 的 auto-config**（line 63 backend/build.gradle.kts），即使 `TestcontainersConfiguration.java` 已註解 LGTM bean。S012 / S013 / 未來 spec 若再遇資源壓力可優先排查此處。
- **Firestore Enterprise + MongoDB compat 啟用指令**：`gcloud firestore databases create --edition=enterprise --enable-mongodb-compatible-data-access`（驗證自官方 docs）。Database ID 規則：lowercase letters/numbers/hyphens、4-63 字元。
- **Cloud Run secret as env var 語法**：`--update-secrets=ENV_VAR=SECRET_NAME:VERSION`；Service Account 須有 `roles/secretmanager.secretAccessor`。
- **CLAUDE.md「Feature First, Security Later」**——S011/S012 設計都嚴守此原則：S001~S010 既有 API 全部維持匿名可達，只有新增 demo 端點要 JWT。
- **Spec writing pattern**：先 deep research（外部 lib）→ planning-spec（grill + design）→ planning-tasks（task files）→ implementing-task（TDD）→ verifying-quality（獨立 QA）→ shipping-release（commit + tag + archive）。本 session 完整跑了一遍 S011，並對 S012/S013 完成 planning-spec 階段。

### Session Summary

Session 從 user 詢問「適合 docker compose、簡單自訂 token 內容的 OAuth mock server」開始，先做 navikt/mock-oauth2-server vs Soluto/oidc-server-mock 的對比評估，鎖定前者後產出完整 deepwiki 研究（後被 user 清空）。接著走 `/planning-spec → /planning-tasks → /implementing-task ×2 → /verifying-quality → /shipping-release` 完整流程把 S011 ship 出（tag v0.10.0）。途中遇到 S010 殘留 docker 容器導致全套測試失敗的虛驚（清資料後恢復綠），以及 Spring Boot 4 OAuth2 RS auto-config 不建 JwtDecoder 的真實 bug（顯式宣告 bean 解決）。Ship 完後 user 連續開兩張新 spec：S012（OAuth on/off toggle for LAB）與 S013（GCP Cloud Run 部署腳本），都完成 planning-spec 階段（in-design）並 commit。最終工作目錄全淨，main 領先 origin 2 個 commits + 1 個 tag 待 push。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | `./gradlew clean test` → BUILD SUCCESSFUL in 37s — **104 / 0 / 0 / 0**（含 S001~S011） |
| Tag | `v0.10.0` 已建立（local），未 push |
| Ahead of origin | 2 commits |

### Uncommitted Changes

```
(working tree clean)
```

### Recent Commits

```
9043b70 docs: add S012 OAuth toggle + S013 GCP deploy specs, refine /loop guide
3b4fc77 feat(security): ship S011 — dev environment OAuth Mock 整合
a7143ce feat(security): ship S010 — multi-engine scanner pipeline + SARIF 2.1.0
295310c docs: add S010 multi-engine scanner spec, update roadmap with security backlog
267c874 chore: track storage-local directory with .gitkeep, ignore contents
```

### Key Files

**S011（已 ship，archived）**：
- `docs/grimo/specs/archive/2026-04-25-S011-dev-oauth-mock.md` — 完整 spec，§7 含 11 個 sub-section（驗證、AC results、findings、design drift sync、tech debt、QA review × 2、final resolution）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/SecurityConfig.java` — Spring Security 7 SecurityFilterChain；`@EnableMethodSecurity`；顯式 JwtDecoder + SupplierJwtDecoder lazy 包裝
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/MeController.java` — `/api/v1/me` 用 `@AuthenticationPrincipal Jwt`（S012 會改用 CurrentUserProvider）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AdminController.java` — `/api/v1/admin/echo` + `@PreAuthorize("hasRole('admin')")`
- `backend/config/oauth-mock-config.json` — 三組 client_id mapping
- `backend/compose.yaml` — mongodb + mock-oauth2-server services
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/{Me,Admin,SkillsApiAnonymous,OAuthMockE2E}Test.java` — 9 個測試

**S012（in-design）**：
- `docs/grimo/specs/2026-04-27-S012-oauth-toggle-lab-mode.md` — full spec §1-5；§4.3 `CurrentUserProvider` 介面、§4.4 `LabSecurityFilter`、§4.5 SecurityConfig branch 設計

**S013（in-design）**：
- `docs/grimo/specs/2026-04-27-S013-gcp-deploy-scripts.md` — full spec §1-5；§4.1-4.7 含完整 6 個 bash 腳本範例 + .env.example + README

**核心參考**：
- `docs/grimo/specs/spec-roadmap.md` — M0~M11 全表；M9 (S011 ✅) 已 collapse；M10 (S012 🔵) + M11 (S013 🔵) 詳細區塊
- `docs/grimo/CHANGELOG.md` — 含 v0.10.0 條目
- `docs/grimo/architecture.md` — Spring Boot 4.0.6 + Java 25 + Spring Modulith + Firestore Enterprise (MongoDB driver) + GCS + Vertex AI 設計
- `CLAUDE.md` — 開發原則「Feature First, Security Later」、雙層 profile (`local`/`gcp` × `dev`/`prod`)

**未追蹤工作（隨時可繼續）**：
- 若需 S012/S013 進入實作：`/planning-tasks S012` 或 `/planning-tasks S013`
- 若需 push remote：`git push origin main v0.10.0`
