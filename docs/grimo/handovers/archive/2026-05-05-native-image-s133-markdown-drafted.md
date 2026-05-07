---
topic: "Native image debug + skill v1.1 retrospective shipped; S133 Skill Markdown Export drafted (next session impl)"
session_type: "development"
status: "in_progress"
date: "2026-05-05"
---

# Handover: Native image (committed) + skill v1.1 + S133 Markdown Export 待實作

## Layer 1 — Portable Summary

> 本 session 兩個 phase：
> 1. 上半段：把 native image 編譯 + 完整 runtime 一路打通，16 build cycle 收斂到
>    minimal fix 並 ship；過程萃取通用 debug 原則升級 root-cause-debugging skill v1.1
> 2. 下半段（user 主導）：起 S133 spec（Skill Markdown Export — 跟上半的 native image
>    無關）；user 主動把 build.gradle.kts native 相關 3 個 task block 註解掉（不要動）

### Completed

- **Native image enablement shipped（commit `3b48bc2`）** — Spring Boot 4 + Java 25 +
  Modulith + Spring AI + GCP starter 11 個 dep native compile + 完整 runtime（Tomcat /
  real PG / Flyway 14 migrations / Modulith outbox），0.5s startup，120-275 MiB
- **Cloud Build OOM fix shipped（commit `a0d90e6`）** — `cloudbuild.yaml` machineType
  `E2_HIGHCPU_8`（8 GB） → `E2_HIGHCPU_32`（32 GB）；native compile 需 16+ GB heap
- **Root-cause-debugging skill v1.1 shipped（commit `b82eeb3`）** — 從 16 build cycle
  萃取 5 個通用原則（Search-Self-First / Project-Memory-Consult / Pattern-Recognition-on-2nd
  / Read-User-Intent / Bisect-Judgement），加 Phase 5.5 + Phase 0 expand + 4 個新 anti-pattern
  rows + 新 case study `references/case-study-spring-native-runtime.md`
- **SpringDoc 移除** — 隨 native image commit ship，因 yaml 預設 `enabled=false` 在
  AOT 階段 baked 進 native context，無法 runtime 啟用；OpenApiVersionTest 跟著刪
- **S133 spec drafted（user 動作，未 commit）** — `docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md`
  + `spec-roadmap.md` 加 M128 row。**注意：S133 在 spec roadmap 是「Skill Markdown
  Export — agent-friendly Copy / Open dropdown」，與 native image 無關**

### Decisions

| Decision | Why | Alternatives Rejected |
|---|---|---|
| 用 `System.getenv` 直連 process env vars 而非 Spring `Environment` / `@ConfigurationProperties` | Spring Boot 4 AOT processing 對 eager bean 跳過 @CP binding（連續 4 次 build 驗證 yaml/system property/CLI args 都失效）；System.getenv 純 JVM API 不受 framework phase 影響 | yaml override / `--spring.datasource.url=` CLI args / `systemProperty()` Gradle DSL — 全失敗（同 error 不變） |
| 用 `FlywayMigrationStrategy` bean 跳 build-time migrate 而非 `flyway.enabled=false` | yaml `enabled=false` baked 進 native context 後 runtime env var 救不回（同 root pattern） | `application-aot.yaml` 設 `flyway.enabled=false` — 已驗證 leak 到 runtime |
| 移除 `ApplicationModulesEndpointConfiguration` + `ModuleObservabilityAutoConfiguration` + `SpringDataRestModuleObservabilityAutoConfiguration` 3 個 modulith autoconfig（in `application-local.yaml`） | Spring Modulith Issue #735/#1556 已知 ArchUnit ClassFileImporter 在 native 沒 reflection metadata；excludes 寫 local.yaml 而非 aot.yaml 因為 list-property profile last-wins | 加 ArchUnit reflection hints — research 確認「native image 連 classfile scan 都不能動，解一個 class 還會連環觸發其他錯誤」 |
| 移除 SpringDoc dep 而非保留+enable | yaml 預設 `enabled=false` baked 進 native context；要保留得在 application-aot.yaml 設 enabled=true，但 SpringDoc 3.0.2 的 Spring Boot 4 native 兼容仍未驗證 | 加 application-aot.yaml override + 賭兼容；user 選擇先移除 |
| Cloud Build `E2_HIGHCPU_32` (32 GB) 而非 N1 系列 | 32 GB 預估剛好涵蓋 16-20 GB native compile 需求 + buffer；E2 比 N1 便宜 | E2_HIGHCPU_8 (8 GB) 已 OOM；N1_HIGHMEM_32 (208 GB) 過剩 4x cost |
| Skill v1.1 把通用原則寫進 SKILL.md 而非單獨 case study | SKILL.md 是 always-loaded 進 system prompt 的；通用原則必須這層；具體實戰寫 case study 只 on-demand 載入 | 全寫 case study — SKILL.md 不會 surface 這幾條到下次 debug |

### Blockers

**Native image 主流程已 unblock**。下個 session 的「pending」是 S133 Skill Markdown
Export 的 implementation（不是 blocker，是新 work）+ Cloud Build 32 vCPU 升級的實機
驗證（user 已 commit yaml，下次 push 會驗）。

**Build.gradle.kts 用戶端 partial revert（未 commit）**：user 把 3 個 task block 註解掉
（`processResources` / `tasks.withType<ProcessAot>` / `tasks.named<bootBuildImage>`）。
Intent 推測：把 local working tree 切回 JVM image 模式準備做 S133 markdown export，
但 git history 保留 native image 能力（commit `3b48bc2`）— 隨時 revert 註解可重新啟用。
**Don't undo this user modification**.

### Next Steps

1. **驗 Cloud Build 32 vCPU 升級**（user 動作）：下次 push 後 watch Cloud Build log，
   應該見 `Build resources: ~24 GB of memory`（vs 之前 6.30 GB）；native compile 不
   再 OOM。如果還 OOM → 升 N1_HIGHMEM_32（208 GB，最後選項）。

2. **S133 Skill Markdown Export 進設計階段** —
   `docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md` 已 draft（spec 寫到
   §2 設計決策），可進 `/planning-tasks S133` 把剩下 §3-7 補完 + 拆 BDD task。Spec
   summary：`SkillDetailPage` 加「Markdown ▾」dropdown（複製 / 開啟），對應 backend
   alias `GET /api/v1/skills/{id}/skill.md` 委派既有 `FileBrowserService.readFile`。
   XS(8) 估點。

3. **Commit S133 spec + spec-roadmap 變更**（在 S133 動工前 ship 一個小 commit）：
   ```bash
   git add docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md \
           docs/grimo/specs/spec-roadmap.md
   git commit -m "docs(spec): S133 — Skill Markdown Export design"
   ```

4. **可選：build.gradle.kts user-revert 決定是否 commit** — 如果 user 確認長期不要
   native image，把 3 個註解掉的 block 直接刪掉而非註解（per CLAUDE.md「不要 leave
   `// removed` comments」）；如果只是暫停，保留註解。詢問 user 意圖。

5. **本 session 我的 commit naming 錯**：`3b48bc2` `b82eeb3` `a0d90e6` 用 "S133" 標
   native image 工作，但 spec roadmap 真正的 S133 是 Skill Markdown Export。下個
   session 建議：(a) 在 commit log 補 note 說明 label 衝突，或 (b) 直接讓未來 S134+
   接續，這 3 個 commit 不改（git log immutable convention）。

### Lessons Learned

從 16 build cycle 萃取，已寫進 root-cause-debugging skill v1.1（不重複寫；以下是
**對下個 session 直接可用**的 takeaway）：

- **AOT-time disable / `enabled=false` / `ConditionalOn false` 一律 baked into native
  runtime context，env var / yaml runtime override 救不回**。如果想 native runtime
  enable 某 feature，**AOT 階段就要 enable**（在 application-aot.yaml 寫 enabled=true，
  或在 ProcessAot args 啟用 profile）。同 root pattern 在 session 內踩 4 次（Modulith /
  Flyway / JdbcDialect / SpringDoc）。

- **Spring Boot 4 AOT processing 對 eager bean 跳過 @ConfigurationProperties binding** —
  property/yaml/system-property/CLI args 全失效。唯一能 build-time 提供 config 給
  HikariDataSource 等 framework eager bean 的方式是 **Java code（@Bean factory method
  body）**，可結合 `System.getenv` 達 build-time stub + runtime real value。

- **Profile yaml list-property is last-wins not merge** — `spring.profiles.active=aot,local`
  時 `application-local.yaml` 的 `spring.autoconfigure.exclude` list **完全取代**
  `application-aot.yaml` 的同 list。要在多 profile 都生效就得在每個 profile yaml 各
  寫一次（或寫 `application.yaml` always-active）。

- **Spring AOT `ApplicationContextInitializer` hardcode `addActiveProfile(...)` 把
  build-time profile baked 進 native binary**（per spring-boot Issue #41562 / #48408）。
  Native runtime `SPRING_PROFILES_ACTIVE` 不能 swap 出 baked profile。

- **Cloud Build native image 必 32+ GB machine type**。E2_HIGHCPU_8 (8 GB) container
  拿 6.30 GB 在 [6/8] compile 階段 OOM。本 app size 16-20 GB heap 需求。

- **AotStubConfig 的 `dataSource()` 方法 body 在 native runtime 會被 BeanInstanceSupplier
  re-invoke**（不是 baked instance），所以 method body 內讀 `System.getenv` 真的
  生效於 runtime。這是把 build-time stub + runtime real value 統一進同一個 @Bean
  factory method 的關鍵 mechanism。

### Session Summary

User 重啟同一個 `/loop`「在本地端嘗試解決 SpringBoot 無法編譯成 Native Image 的問題」
共 6 次，每次往下解一層 intent：第 1 次「compile 通過」、第 2 次「runtime 啟動」、
第 3 次「DB 連得上」、第 4 次「Flyway migrate」、第 5 次「無 5xx」、第 6 次「functional
endpoint test」。16 個 build cycle 收斂到 4 file minimal fix 並 commit 3b48bc2。
過程把 SpringDoc 移除（yaml 預設 disable + native baked 救不回）、Cloud Build OOM 順
帶修了 a0d90e6。最後 user 點要把這次的 debug 學習提煉成通用 skill 升級而非 build-specific
case study，產出 root-cause-debugging skill v1.1（commit b82eeb3）含 5 條 universal 原則。
Session 結尾 user 起 S133 spec（**Skill Markdown Export，與 native image 無關**）跟
partial revert build.gradle.kts，準備進下個 feature。

---

## Layer 2 — Environment Details

| Property | Value |
|---|---|
| Branch | `main`（領先 origin/main 共 4 個 commit） |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | Native image local runtime 完整驗：`./gradlew bootBuildImage` PASS 2m 51s / `docker run` 跑通 0.5s startup / `/actuator/health` 200 / `/api/v1/skills` 200 / Flyway 14 migrations applied / Modulith outbox scheduled task working / 0 ERROR after warm-up |

### Uncommitted Changes

```
 M backend/build.gradle.kts                                  ← user 註解掉 3 個 task block (intentional)
 M docs/grimo/specs/spec-roadmap.md                          ← +1 row M128 S133 markdown export
?? docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md ← 新 spec draft
```

### Recent Commits

```
a0d90e6 fix(ci): bump Cloud Build machine to 32 vCPU/32GB for native image OOM
b82eeb3 docs(skills): root-cause-debugging v1.1 — 5 個通用原則 from S133 native runtime 實戰
3b48bc2 feat(native): enable Spring Native compilation + full local runtime
593e76e feat(skills): add root-cause-debugging skill
002a111 feat(ci): S132 — Cloud Build pipeline + JVM AOT enablement (T01)
```

> 注意 commit `3b48bc2` `b82eeb3` `a0d90e6` 訊息中的 "S133" 是錯誤 label —
> spec roadmap 真正的 S133 是 Skill Markdown Export（M128）。下次 session 注意。

### Key Files

**本 session 動過 commit 進 git 的**：
- `backend/build.gradle.kts` — `BP_NATIVE_IMAGE=true`、ProcessAot args `aot,local`、
  TRAINING_RUN profile（全在 commit 3b48bc2；user 已在 working tree 註解 3 block 部分 revert）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/aot/AotStubConfig.java` —
  重寫 `dataSource()` 用 `System.getenv` + 加 `flywayMigrationStrategy` bean
- `backend/src/main/resources/application-aot.yaml` — 移 `flyway.enabled=false`
- `backend/src/main/resources/application-local.yaml` — 加 3 條 modulith autoconfig excludes
- `backend/src/main/resources/application.yaml` — 移 `springdoc:` block + comment 字眼
- `backend/src/test/java/io/github/samzhu/skillshub/api/OpenApiVersionTest.java` — 刪
- `cloudbuild.yaml` — `machineType: E2_HIGHCPU_8 → E2_HIGHCPU_32`（commit a0d90e6）
- `.claude/skills/root-cause-debugging/SKILL.md` — Phase 0 expand + Phase 5.5 + 4 個新
  anti-pattern row + version 1.0.0 → 1.1.0
- `.claude/skills/root-cause-debugging/references/case-study-spring-native-runtime.md` — 新
- `.claude/skills/root-cause-debugging/references/checklist.md` — Phase 0 + 卡關信號表
  +4 條

**未 commit 待下個 session 處理**：
- `docs/grimo/specs/2026-05-05-S133-skill-markdown-export.md` — S133 spec design
  WIP（user draft）
- `docs/grimo/specs/spec-roadmap.md` — +1 row M128 S133 markdown export

### Build / Run Commands（驗證 native image 還能跑用）

```bash
# Native compile + run（前提：取消 build.gradle.kts 中 user 註解的 3 個 block）
cd backend && ./gradlew bootBuildImage

# Run native image（需先起 docker compose pgvector）
docker compose -f backend/compose.yaml up -d pgvector
docker run -d --name skillshub-native --network host \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SKILLSHUB_STORAGE_LOCAL_PATH=/tmp/storage-local \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mydatabase \
  -e SPRING_DATASOURCE_USERNAME=myuser \
  -e SPRING_DATASOURCE_PASSWORD=secret \
  skillshub:0.0.1-SNAPSHOT
curl http://localhost:8080/actuator/health   # 期望 200 + db: UP
```
