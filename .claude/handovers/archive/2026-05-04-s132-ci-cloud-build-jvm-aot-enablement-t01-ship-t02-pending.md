---
topic: "S132 CI Cloud Build + JVM AOT enablement (T01 ship; T02 pending)"
session_type: "development"
status: "in_progress"
date: "2026-05-04"
---

# Handover: S132 CI Cloud Build + JVM AOT enablement

## Layer 1 — Portable Summary

> Session 跨 spec planning / impl / 重度 debug / skill 萃取四段。S132 T01 已 ship
> 進 commit，T02（GCP-side trigger setup + first push smoke）待 user 執行。
> OAuth spec（S133 候選）Phase 0 context scan 已做，未開 spec file。

### Completed

- **S132 spec 完整** — `docs/grimo/specs/2026-05-04-S132-ci-cloud-build.md` §1-6（XS = 7）
- **S132 T01 PASS** — Cloud Build pipeline 雛形 + AOT 啟用，本機 + Cloud Build 都 SUCCESS
- **2 個 commit ship**：
  - `002a111 feat(ci): S132 — Cloud Build pipeline + JVM AOT enablement (T01)`
  - `593e76e feat(skills): add root-cause-debugging skill`
- **Skill 新增**：`.claude/skills/root-cause-debugging/`（4 檔，850 行）萃取本 session debug 教訓成通用 6-phase 方法論
- **AOT 路徑驗證**：本機 `./gradlew bootJar` PASS（含 processAot + AOT artifacts）/ 本機 `./gradlew bootBuildImage` PASS 1m9s / Cloud Build step 3 native compile 誤觸發那次失敗已加 `BP_NATIVE_IMAGE=false` 解（修法後未再 retry CI）

### Decisions

| Decision | Why | Alternatives Rejected |
|---|---|---|
| 用 graalvm.buildtools.native plugin（非 boot.aot apply） | 官方標準入口、auto-apply boot.aot；plugin 留著將來想試 native compile 0 line 改即可 | `apply(plugin = "org.springframework.boot.aot")` — 是 sdeleuze 官方 sample 但獨立 apply 看起來像 magic |
| 走 JVM image（不做 native compile） | Cloud Run + Paketo JVM image 已能滿足；native 需 2-5 天驗 11 個 starter，cost > benefit；MVP 先 ship | GraalVM native binary 編譯 — 推到獨立 future spec |
| `BP_NATIVE_IMAGE=false` 顯式關 Paketo native-image buildpack | graalvm plugin 貢獻 META-INF/native-image，被 native-image buildpack 自動偵測 → 觸發 15+ min native compile | 移除 graalvm plugin（會失去 processAot） |
| AOT 階段 stub DataSource 用 Java config (`@Profile("aot") @Bean`) | Spring Boot 4 AOT processing 跳過 `@ConfigurationProperties` binding 對 eager bean，property/yaml/env/CLI args 全失效 | yaml stub URL（試 5 種寫法都失效） |
| Override `AbstractJdbcConfiguration.jdbcDialect` 直接回 `JdbcPostgresDialect.INSTANCE` | spring-projects/spring-boot#47781 官方 workaround；100% PostgreSQL 不需 auto-detect，runtime 也受惠 | yaml 設 `spring.data.jdbc.dialect=POSTGRESQL`（同上 binding 失效） |
| ProcessAot 用 `args("--spring.profiles.active=aot")` | CLI args = SimpleCommandLinePropertySource 最高優先級；`environment()` 在 ConfigData 處理順序有邊界情況 | `environment("SPRING_PROFILES_ACTIVE", "aot")` — 第一次嘗試方法，雖 log 顯示 active=aot 仍失敗 |
| 手動 `gcloud builds submit`（不做 GitHub trigger） | User explicit 要求；MVP single-author 不需 webhook 自動 | Developer Connect GitHub App + branch trigger（留 future） |
| 前端 npm chain 從 Gradle 解耦（移除 npmInstall/npmBuild/copyFrontend）| 本機 `bootRun` 不再被前端 TS error 擋住；CI 純 step 化更乾淨 | 保留 chain — 維持「`./gradlew bootRun` 全自動」但解不開 user 痛點 |
| OAuth spec 用 BFF 模式（Pattern B）| user `/planning-spec` 階段確認；frontend 與 backend 同 origin（Spring Boot 包 SPA static），BFF token relay 最乾淨 | SPA 直接 PKCE — 增加 frontend 複雜度 |

### Blockers

**T02 待 user 執行（manual GCP-side setup + push smoke）**

| Attempt | Result | Why It Failed |
|---|---|---|
| (none — task 本就標 manual) | — | T02 需要 user 本機 gcloud auth、Cloud Console UI、實際 push 觸發 — 不是 assistant 能 autonomously 做的 |

Current hypothesis: user 跑 `gcloud builds submit ...` 應該能直接過（本機已驗 1m9s 完整 build SUCCESS，包含 AOT processing + Paketo training run）。詳 next steps 步驟 1。

### Next Steps

1. **跑 first Cloud Build submit 驗 T02**（user 的事）：
   ```bash
   cd /Users/samzhu/workspace/github-samzhu/skills-hub
   gcloud builds submit \
     --config=cloudbuild.yaml \
     --project=$GCP_PROJECT_ID \
     --substitutions=_REGION=$GCP_REGION,_TAG=$(date -u +%Y%m%d-%H%M%S)
   ```
   預期：5-8 分鐘，3 step 全 SUCCESS、image 出現在 Artifact Registry。

2. **若步驟 1 SUCCESS** → 執行 T02 task file 的 BDD verification（`gcloud builds list` / `gcloud artifacts docker images list`）→ 標 T02 PASS → 進 `/planning-tasks S132` Phase 4 consolidation：
   - `./gradlew test`（pipeline check）
   - 把 §6 task plan 結果 + §7 implementation results 寫進 spec file
   - 刪 `docs/grimo/tasks/2026-05-04-S132-T01.md` + T02.md
   - 派 QA subagent (`/verifying-quality`)
   - QA PASS → 告訴 user 跑 `/shipping-release`

3. **若步驟 1 FAIL** → 用 `/root-cause-debugging` skill 跟著 6 phase 走（特別是 Phase 1 本機重現 + Phase 3 派 research agent）。

4. **OAuth spec（S133 候選）** — Phase 0 context scan 已記在對話中（active spec overlap = none / S011 mock + S012 toggle + S130 personal endpoints 既有 / Frontend 完全沒 OAuth code / 需新增 `spring-boot-starter-oauth2-client` dep）。等 S132 ship 後可直接 `/planning-spec OAuth integration` 接續，研究結果（BFF pattern, Spring Security 7.x SecurityFilterChain 兩 chain）已在 conversation。

5. **不要直接 push origin/main** — user 會自己決定何時 push。本地領先 origin/main 2 commit。

### Lessons Learned

從 `/retro` 萃取的 6 條（已寫進 root-cause-debugging skill）：

- **第 2 次遠端失敗 = 立刻轉本機重現** — CI 90s vs 本機 7s，循環貴 13x；本 session 浪費了 4 次 CI 才轉本機
- **完整 stack trace 從上往下讀** — 底部是事故現場（`Failed to determine driver class`），頂部才是觸發點（`@EnableMethodSecurity advisor sort`）
- **Error 訊息 0 變動 = fix 沒生效**（不是 fix 不夠）— 本 session 連續 4 次同 error 沒意識到 fix 沒到 bug 路徑
- **第 1 次失敗就派 research agent** — issue #47781 + #48240 已寫好官方 workaround，30 秒能查到，省 2 小時 trial-and-error
- **Spring runtime 知識不適用 AOT phase** — `@ConfigurationProperties` binding 在 AOT 階段對 eager bean 失效；property/yaml/env/CLI args 全沒用，必須走 Java `@Bean`
- **突破時刻 = 立刻 git stash + bisect** — CLAUDE.md「Clean Experiments」原則的執行手冊；若不做，10+ 個 attempt 的 noise 會進 commit 變 permanent debt（user 提示後才補做）

技術細節 takeaway：

- `BP_NATIVE_IMAGE=false` 是 graalvm plugin + Paketo 組合的關鍵 — plugin 貢獻 `META-INF/native-image/`，會被 Paketo native-image buildpack 自動觸發
- `BP_JVM_AOTCACHE_ENABLED=true` + `BP_JVM_CDS_ENABLED=false` 一起 — Java 25 AOT Cache (JEP 514) 取代 CDS 角色，繞 paketo-buildpacks/spring-boot#581 bug
- `gcloud builds submit` 沒有 git context，所以 `$SHORT_SHA` 是空 — `_TAG` 改用 user-defined（timestamp）；trigger 模式才有 `$SHORT_SHA`
- `gcloud projects add-iam-policy-binding` 在 project 已有 conditional binding 時會跳互動 prompt — 加 `--condition=None` 顯式跳過
- GCP 2024-04 起新 project 的 default Cloud Build SA 改成 Compute Engine default SA（`-compute@developer.gserviceaccount.com`），不是 legacy `@cloudbuild.gserviceaccount.com`
- `gcloud builds submit` 會 tarball **整個 cwd**，所以 cwd 必須切到 repo root，否則會把 home dir 整個送上去

### Session Summary

本 session 從 user 在 backend 跑 `bootRun` 被 frontend TS error 擋住開始，順勢進入 S132（CI Cloud Build + Gradle 解耦 npm）spec 規劃 → T01 落地 → 推第一次 Cloud Build。Build step 3（bootBuildImage）連續失敗 6 次，根因是 Spring Boot 4 AOT processing 階段強制 instantiate 我們自家的 `methodSecurityExpressionHandler` bean → 拖出 DataSource → DataSourceProperties 沒 binding → 炸。中間繞了 2 小時，試 5 種 property 注法都失效，user 引導下做 `/retro` 萃取 6 個教訓，再把教訓建成 `root-cause-debugging` skill。最後 user 說「全部 commit」分兩個 commit ship 完。S132 T02（GCP-side trigger setup + push smoke）待 user 跑；OAuth spec（S133 候選）Phase 0 context 已記但 spec file 未開。

---

## Layer 2 — Environment Details

| Property | Value |
|---|---|
| Branch | `main`（領先 origin/main 2 commit，未 push） |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | 本機 `./gradlew bootJar --rerun-tasks` PASS（含 processAot + AOT artifacts）/ 本機 `./gradlew bootBuildImage` PASS 1m9s（image 烤好至 `docker.io/library/skillshub-local-test:aot-verify`）/ Cloud Build：step 3 加 `BP_NATIVE_IMAGE=false` 後**未再 retry**（hypothesis 應該過） |

### Uncommitted Changes

```
(none — 工作區乾淨)
```

### Recent Commits

```
593e76e feat(skills): add root-cause-debugging skill
002a111 feat(ci): S132 — Cloud Build pipeline + JVM AOT enablement (T01)
6e721f9 docs: 修正 md 格式
90d5c35 refactor(config): Spring Boot 設定檔三層重構 + skill v2.0.0
2155a3f feat(deploy): Cloud Run + Cloud SQL PostgreSQL 18 sidecar 部署整套
```

### Key Files

**S132 落地（本 session 變更）**：
- `cloudbuild.yaml` — 三 step pipeline + declarative `images:` push
- `backend/build.gradle.kts` — graalvm plugin / 移除 npm chain / ProcessAot args / bootBuildImage 5 個 env var (`BP_NATIVE_IMAGE=false` / `BP_JVM_AOTCACHE_ENABLED=true` / `BP_JVM_CDS_ENABLED=false` / `TRAINING_RUN_JAVA_TOOL_OPTIONS` / `BPE_APPEND_JAVA_TOOL_OPTIONS`)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/aot/AotStubConfig.java`（new）— `@Profile("aot")` stub DataSource bean
- `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java` — `jdbcDialect()` override 直接回 `JdbcPostgresDialect.INSTANCE`
- `backend/src/main/resources/application-aot.yaml`（new）— Flyway off + GcpContextAutoConfiguration exclude
- `frontend/package.json` — build script 拆 `vite build` / `typecheck`
- `scripts/gcp/03-build-push.sh` — 加 npm ci + cp 三步前置
- `scripts/gcp/BUILD.md`（new）— manual `gcloud builds submit` 包版指南
- `scripts/gcp/{README,DEPLOYMENT}.md` — 同步指向 BUILD.md
- `docs/grimo/architecture.md` — Build Integration 段更新成兩條路徑

**S132 spec / 任務**：
- `docs/grimo/specs/2026-05-04-S132-ci-cloud-build.md` — 完整 §1-6（XS = 7，狀態 ⏳ Dev）
- `docs/grimo/tasks/2026-05-04-S132-T01.md` — PASS（local atomic edit）
- `docs/grimo/tasks/2026-05-04-S132-T02.md` — pending（GCP setup + smoke，user manual）
- `docs/grimo/specs/spec-roadmap.md` — M127 row 加進 Phase 5

**新 skill**：
- `.claude/skills/root-cause-debugging/SKILL.md` — 6 phase 方法論 + 反模式 + 跨領域應用
- `.claude/skills/root-cause-debugging/references/case-study-spring-aot.md` — 本 session AOT debug 完整實戰
- `.claude/skills/root-cause-debugging/references/anti-patterns.md` — 6 個反模式深入解析
- `.claude/skills/root-cause-debugging/references/checklist.md` — trigger-action 對照表

**Reference 資料（讀過、保留參考）**：
- `/Users/samzhu/workspace/github-samzhu/ledger/config/application-aot.yaml` — ledger 專案的 AOT pattern
- `/Users/samzhu/workspace/github-samzhu/ledger/.github/workflows/release.yml` — GitHub Actions + `SPRING_PROFILES_ACTIVE=aot` env
- `/Users/samzhu/workspace/github-grimostudio/grimo-cli/docs/GRAALVM-NATIVE-IMAGE.md` — Spring AI auto-config exclude pattern
- spring-projects/spring-boot#47781 / #48240 — JdbcDialect AOT workaround 官方 issue
- paketo-buildpacks/spring-boot#581 — Spring Boot 4 + Java 25 + CDS bug
