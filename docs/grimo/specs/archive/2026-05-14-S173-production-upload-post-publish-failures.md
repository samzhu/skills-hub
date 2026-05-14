# S173: Production Upload Post-Publish Failures

> 規格：S173 | 大小：S(10) | 狀態：✅ Local release PASS
> 日期：2026-05-14
> 對應：PRD P2 技能發佈流程 / P3 自動風險評估；spec-roadmap row S173

---

## 1. 目標

`POST /api/v1/skills/upload` 已經把 `transcribe-video` 建成 skill，但上傳後的背景掃描與訂閱詳情 API 在 Cloud Run native revision `skillshub-00022-khz` 仍會噴錯；本 spec 要把這兩個 production-only 失敗修到不再阻塞 post-publish 流程。

實際 log anchor：

```text
2026-05-14T14:04:57Z POST /api/v1/skills/upload -> 201
skillId=427fd92a-4e0c-4f6b-a905-4685a9348b34 name=transcribe-video

2026-05-14T14:05:15Z ScanOrchestrator listener failed:
UnsupportedFeatureError: Record components not available for record class
io.github.samzhu.skillshub.security.scan.engines.LlmJudgement

2026-05-14T14:04:05Z GET /api/v1/me/subscriptions/details -> 500
ERROR: cannot execute UPDATE in a read-only transaction
```

S172 仍在處理 production UI responsive polish；它不改後端 upload、listener、native reflection、transaction。S173 沒有 code-level dependency，可平行設計，但實作時依 `Finish-Current-First` 先收尾當前 task。

使用者動作與系統結果：

```text
Sam 上傳 transcribe-video.zip
  -> skill_versions 多一筆 v1.0.0
  -> event_publication 多一筆 SkillVersionPublishedEvent
  -> SearchProjection / audit / ACL projection 完成
  -> ScanOrchestrator 讀 GCS zip 並寫回 risk assessment
  -> 我的技能 / 訂閱頁讀取不再因 users.last_seen_at refresh 回 500
```

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| Cloud Run log via `gcloud logging read` | `POST /api/v1/skills/upload` 回 `201`；`GET /api/v1/skills/427fd92a-...` 與 `/bundle-info` 回 `200`。 | upload command path 已通；不要重寫 upload / GCS / validation。 |
| Cloud Run log via `gcloud logging read` | `ScanOrchestrator.on(SkillVersionPublishedEvent)` 因 `LlmJudgement` record reflection 缺 metadata 拋 `UnsupportedFeatureError`，Modulith log 顯示 event publication 留 uncompleted。 | 修點是 security scan native hint + `Error` 隔離，不是 LLM prompt 或 GCS。 |
| [ScanOrchestrator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java:118) | 外層只 `catch (Exception)`；`safeAnalyze` 也只 `catch (Exception)`。 | `UnsupportedFeatureError extends Error` 不會被吞，listener 會讓 Modulith publication 未完成。 |
| [LlmJudge.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudge.java:128) | `chatClient.call().entity(LlmJudgement.class)` 讓 Spring AI structured output 以 `LlmJudgement` 當 JSON binding target。 | `LlmJudgement` 與 nested `RiskClaim` 必須註冊 native reflection binding。 |
| [LlmJudgement.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgement.java:26) | `LlmJudgement` 是 top-level record，內含 nested record `RiskClaim`。 | hint 要列兩個 class：`LlmJudgement.class`、`LlmJudgement.RiskClaim.class`。 |
| [debugging-playbook.md §F2](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/debugging-playbook.md:103) | 專案已記錄同 family：Spring AI `BeanOutputConverter` + Jackson record reflection 在 native runtime 缺 hint 會噴 `UnsupportedFeatureError`；fix pattern 是 `@RegisterReflectionForBinding` + `catch (Error)`。 | 直接套既有專案 pattern，避免發明新 native workaround。 |
| [ScoreNativeConfig.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/ScoreNativeConfig.java:21) / [SearchNativeConfig.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SearchNativeConfig.java:20) | score/search module 已用 `@RegisterReflectionForBinding` 處理 record binding。 | 新增 `security.scan` 對應 config，命名為 `ScanNativeConfig`。 |
| [QualityScoreListener.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreListener.java:40) | quality listener 已把不可重試 `Error` 記錄後吞掉，讓 outbox row 完成，避免每分鐘重投。 | `ScanOrchestrator` listener 也要有同等 guard，但 RuntimeException 仍照既有設計 log 後不拋。 |
| [SkillSubscriptionService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/community/SkillSubscriptionService.java:117) | `findSubscriptionDetailsOfCurrentUser()` 標 `@Transactional(readOnly=true)`，第一行呼叫 `users.userId()`。 | OAuth session 下 `users.userId()` 會 refresh `users.last_seen_at`，所以不能在 read-only transaction 裡執行。 |
| [CurrentUserProvider.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/shared/security/CurrentUserProvider.java:148) / [UserUpsertService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserUpsertService.java:82) | OAuth2 login path 會呼叫 `upsertFromOidc`；既有 user 會 `repo.save(user)` 更新 `last_seen_at`。 | `upsertFromOidc` 需要自己的 write transaction，或 read service 需先在無 read-only tx 的位置取 current user。 |
| Spring Framework AOT hints official docs | `@RegisterReflectionForBinding` 會為 JSON binding 所需 reflection hints 註冊指定 class。 | 採用 Spring 原生 AOT hint annotation，不寫手工 `RuntimeHintsRegistrar`。 |
| Spring AI structured output official docs | structured output converter 會用 Java target type 產 schema 並轉回該 type。 | 失敗點是 binding target，而不是 prompt output text。 |
| Spring Framework transaction propagation docs | `PROPAGATION_REQUIRED` 會加入現有 transaction；`PROPAGATION_REQUIRES_NEW` 會開獨立 transaction。 | `UserUpsertService.upsertFromOidc` 若改 `@Transactional(propagation = REQUIRES_NEW)`，可在 read-only caller 裡安全 refresh user。 |

### 2.1.1 既有 AOT / Jackson 序列化紀錄

| 紀錄 | 當時實體症狀 | 當時修法 | S173 重用方式 |
|------|--------------|----------|---------------|
| [S148 GraalVM JudgeResponse 反射修復](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-08-S148-graalvm-judge-reflection-503.md:1) | `QualityJudge.judgeImplementation()` 呼叫 `ChatClient.call().entity(JudgeResponse.class)` 後，native runtime 噴 `UnsupportedFeatureError: Record components not available for record class ...JudgeResponse`；outbox 事件被重投。 | 新增 `ScoreNativeConfig`：`@RegisterReflectionForBinding({JudgeResponse.class, JudgeResponse.DimensionScore.class})`；`QualityScoreListener` 補 `catch (Error)`，讓這類 native metadata bug 不留在 outbox 重試。 | S173 的 `LlmJudge.entity(LlmJudgement.class)` 是同型路徑；新增 `ScanNativeConfig` 必須列 `LlmJudgement` 與 `LlmJudgement.RiskClaim`，`ScanOrchestrator` 也要補 `Error` 邊界。 |
| [S157 Semantic Search Not Functional §2.5](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-08-S157-semantic-search-not-functional.md:98) | `SearchIntentService` 用 `BeanOutputConverter<>(LlmIntentOutput.class)` parse LLM JSON；spec 當時用 grep 確認全 codebase 只有 S148 的 hint，`LlmIntentOutput` 沒被涵蓋，native 一跑會重現 S148 family bug。 | 新增 `SearchNativeConfig`：`@RegisterReflectionForBinding({SearchIntentService.LlmIntentOutput.class})`；`SearchConfigRegressionTest` 直接用 reflection assert annotation 內含 target record。 | AC-S173-1 測試照 S157 風格：不用只寫 JVM Jackson roundtrip，而是直接讀 `ScanNativeConfig` annotation，確認兩個 record class 都在 hint 裡。 |
| [S148b GraalVM AOT 驗證機制](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-09-S148b-graalvm-aot-validation.md:1) | S148 後追問「能不能在本機提早抓 native reflection failure」；POC 跑 `nativeCompile -PexactReachability=true`，build successful in 3m17s，並把 `--exact-reachability-metadata=io.github.samzhu.skillshub` 做成 gated flag。 | 沒新增 application code；把 exact reachability 做成可選 deploy-day / POC 驗證工具。 | S173 不需要另開 POC，但若 deploy 前要提高信心，可跑 `cd backend && ./gradlew nativeCompile -PexactReachability=true` 檢查是否還漏專案 package 的 reflection metadata。 |
| [S165 Jackson default view production hotfix](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-08-S165-jackson-default-view-inclusion-prod-hotfix.md:1) | `/api/v1/skills` 在 production JSON response 變 `{}`；原因是 Spring Boot 4 / Jackson 3 auto-configured mapper 的 `DEFAULT_VIEW_INCLUSION` 行為與裸 `ObjectMapper` 測試不同。 | 用 Spring-managed `JsonMapper` config 修正，並補 full Spring context diagnostic test；避免只靠 `new ObjectMapper()` 的假綠測試。 | S173 的 serialization/AOT guard 應驗 Spring AOT hint annotation 與 deploy log；如果補 JSON parse 測試，也要使用 Spring-managed mapper 或明確說它只驗 JSON shape，不代表 native hint 已涵蓋。 |

### 2.1.2 為什麼 BDD 沒測到這次 `LlmJudgement` AOT 問題

| 實體證據 | BDD 當時驗到什麼 | 沒驗到什麼 |
|----------|------------------|------------|
| [LlmJudgeTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeTest.java:22) | 用 `CapturingStubChatModel` 回 canned JSON，`ChatClient.create(stub)` 在 JVM 內跑 `.entity(LlmJudgement.class)`，證明 JSON shape 可轉成 `SecurityFinding`。 | JVM 下 `Class.getRecordComponents()` 可直接用；測試不會要求 GraalVM native image 內有 record reflection metadata，所以少 `@RegisterReflectionForBinding` 也會綠。 |
| [LlmJudgeIssueCodeContractTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueCodeContractTest.java:20) | S147 驗 `RiskClaim` 新欄位 `issueCode/remediation/confidence` 會映射到 `SecurityFinding`。 | S147 改了 `LlmJudgement.RiskClaim` record shape，沒有同步要求「凡是被 `ChatClient.entity(...)` / `BeanOutputConverter` 反序列化的 record，都要有 native hint AC」。 |
| [ScanOrchestratorTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java:58) | `throwingAnalyzer()` 丟 `RuntimeException("boom")`，`safeAnalyze` 的 `catch (Exception)` 會吞掉，其他 analyzer 照跑。 | Cloud Run 實際丟的是 `UnsupportedFeatureError`，它是 `Error`，不是 `Exception`；現有 BDD 沒有「analyzer 丟 Error 時 listener 不重拋」情境。 |
| [S147 §7 verification](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-08-S147-scanner-semantic-gap-research.md:459) | S147 ship 記錄 targeted backend test PASS，`SKIP_NATIVE=1 ./scripts/verify-all.sh` PASS；V08a `processAot` 有跑。 | S147 當次明確跳過 V08b native image build；即使 V08b 有跑，它也只 build image，不會在 native executable 裡觸發 upload → outbox → `LlmJudge.entity(LlmJudgement.class)`。 |
| [cloudbuild.yaml](/Users/samzhu/workspace/github-samzhu/skills-hub/cloudbuild.yaml:95) | Cloud Build 用 `./gradlew --no-daemon -x test bootBuildImage -Pspring.profiles.active=gcp,aot,lab` 產 production image。 | `-x test` 代表 image build step 不跑 BDD；缺 native hint 的路徑要等 Cloud Run runtime 真正呼叫 structured output 才會炸。 |
| [scripts/verify-all.sh](/Users/samzhu/workspace/github-samzhu/skills-hub/scripts/verify-all.sh:122) / [qa-strategy.md V08](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/qa-strategy.md:71) | V08a `processAot` 驗 Spring AOT 能產生 bean 初始化 code；V08b `bootBuildImage` 驗 native image 能 build。 | 兩者都不是 `nativeTest`。Spring Boot 官方 native testing 文件說 Gradle `nativeTest` 會把測試放進 native image 後啟動執行；本 repo 目前未啟用 `nativeTest` nightly/CI。 |
| [Spring Framework `@RegisterReflectionForBinding` docs](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aot/hint/annotation/RegisterReflectionForBinding.html) | 官方說此 annotation 會為 data binding / reflection-based serialization 註冊 constructors、fields、properties、record components。 | 現有 BDD 沒有像 [SearchConfigRegressionTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/search/SearchConfigRegressionTest.java:114) 一樣直接 assert `ScanNativeConfig` 的 annotation 內容。 |
| [GraalVM reachability metadata docs](https://www.graalvm.org/dev/reference-manual/native-image/guides/troubleshoot-run-time-errors/) | 官方說 native image 對 runtime 動態呼叫無法都靠 static analysis 預測，可用 `--exact-reachability-metadata=<package>` 提早診斷 missing metadata。 | `backend/build.gradle.kts` 已有 `-PexactReachability=true` gate，但 verify-all / Cloud Build 預設沒有加這個 flag。 |

根因不是「BDD 不會測 native 問題」，而是本次 S147 的 BDD scenario 寫在功能行為層：issue-code mapping、detector output、report contract、upload pipeline。它沒有把 S148/S157 已知家族規則升成測試合約：

```text
只要 production code 新增或改動 record-based structured output target
  -> 必須新增 <Module>NativeConfig
  -> 必須新增 reflection assertion test
  -> 如果該 path 在 Modulith listener 內執行，還要測 Error boundary
```

### 2.2 架構設計

這次不改 domain model、不加 table、不改 public API shape，只補兩個 production runtime contract：

1. `security.scan` module 的 structured output record 要能在 GraalVM native image 裡被 Jackson 反射讀取。
2. `CurrentUserProvider.userId()` 可能寫 `users.last_seen_at`，所以使用它的 read endpoint 不可讓該寫入落在 read-only transaction 裡。

修後資料流：

```text
SkillVersionPublishedEvent
  -> ScanOrchestrator.on(event)
  -> buildContext() 從 GCS 下載 zip
  -> LlmJudge.entity(LlmJudgement.class)
       native image 可讀 record components
  -> persist() 寫 skill_versions.risk_assessment
  -> listener completed，event_publication.completion_date 有值

GET /api/v1/me/subscriptions/details
  -> SkillSubscriptionService.findSubscriptionDetailsOfCurrentUser()
  -> users.userId()
       UserUpsertService.upsertFromOidc() 使用獨立 write transaction refresh users row
  -> 查 skill_subscriptions + skills + users
  -> HTTP 200 + subscription summary list
```

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|----------|--------|-----------|
| A: 新增 `ScanNativeConfig` 並把 `LlmJudgement` / `RiskClaim` 放進 `@RegisterReflectionForBinding`；`ScanOrchestrator` 補 `catch (Error)` | yes | 和 S148/S157 既有 pattern 一致；改動小；不碰 LLM prompt；能直接對 Cloud Run stacktrace。 |
| B: 把 `LlmJudgement` record 改成普通 POJO class | no | 可以避開部分 record reflection，但要改 JSON schema target 與測試；專案其他 structured output 仍用 record + hint，改 POJO 會偏離既有寫法。 |
| C: 關掉 LAB `skillshub.scanner.engines.llm.enabled` | no | 只會藏住錯誤；P3 自動風險評估在 production/LAB 需要 LLM judge 時仍會再壞。 |
| D: 移除 `SkillSubscriptionService` read methods 的 `readOnly=true` | no | 可解 500，但會讓整段查詢 transaction 變 write；問題根源是 `UserUpsertService` 本身缺明確 write boundary。 |
| E: `UserUpsertService.upsertFromOidc` 加 `@Transactional(propagation = REQUIRES_NEW)` | yes | 把「登入身份 refresh」明確包成自己的寫入 transaction；所有 caller 都得到一致行為，`/me`、permission、subscription endpoint 不用逐一猜外層 transaction。 |

### 2.4 Confidence 與 POC

| 決策 | Confidence | 說明 |
|------|------------|------|
| `@RegisterReflectionForBinding` 修 `LlmJudgement` native record reflection | Validated | 專案已有 S148/S157 同 family 修法；官方 Spring AOT hint 文件與 Cloud Run stacktrace 對得上；§2.1.1 已列舊案對照。 |
| `catch (Error)` 讓 scan listener 不把 non-retryable native bug 留在 outbox 重投 | Validated | `QualityScoreListener` 已有同 pattern；debugging-playbook 已把 `UnsupportedFeatureError` 歸類為不該重試。 |
| `upsertFromOidc` 用 `REQUIRES_NEW` 隔離 read-only caller | Validated | Spring transaction docs 明定 `REQUIRES_NEW` 使用獨立 transaction；Cloud Run stacktrace 顯示目前寫入落在 read-only transaction。 |
| native image runtime 真實 LLM scan 不再噴同錯誤 | Hypothesis | 本機 `processAot`/unit test 可驗 hint presence；完整 Cloud Run + Secret Manager + Gemini 需要 deploy 後用 log 驗證。§3 把它列為 evidence AC。 |

POC：not required。這是 production hotfix，root cause 已由 Cloud Run log + 既有 S148 family pattern 定位；task 階段直接實作並用 tests + deploy log 驗證。

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `security/scan/ScanNativeConfig.java` + `ScanNativeConfigTest` | Cloud Run `LlmJudgement` stacktrace；S148/S157 config pattern；S157 `SearchConfigRegressionTest` reflection assertion | annotation classes 包含 `LlmJudgement` 與 `LlmJudgement.RiskClaim` | 少列 nested record 時 test fail | not required |
| T02 | `ScanOrchestrator.safeAnalyze` / listener error guard test | `UnsupportedFeatureError extends Error` 未被 `catch(Exception)` 接住 | analyzer 丟 `UnsupportedFeatureError` 時 `safeAnalyze` 回 `AnalysisOutput.empty()` 或 listener 完成不 rethrow | analyzer 丟普通 `RuntimeException` 維持既有 empty output/log 行為 | not required |
| T03 | `UserUpsertService.upsertFromOidc` transaction boundary + subscription regression test | `/me/subscriptions/details` 500 stacktrace | read-only caller 內呼叫 `findSubscriptionDetailsOfCurrentUser()` 回 200/summary，不再 `cannot execute UPDATE` | 無 OAuth user 或 anonymous 仍照既有 401/empty handling | not required |
| T04 | deploy config/log cleanup evidence | `temp/service.rendered.yaml` 已有 security DEBUG env | 修完 upload 403 後移除 `logging.level.org.springframework.security=DEBUG` 或在 spec §7 記錄保留原因 | 不移除會讓 Cloud Run log 長期被 filter-chain debug 洗版 | not required |
| T05 | `StructuredOutputNativeHintCoverageTest` | S148/S157/S173 同 family 反覆發生；目前 production target 是 `JudgeResponse`、`LlmIntentOutput`、`LlmJudgement` | 本地 `./gradlew test` 掃到 `.entity(X.class)` / `BeanOutputConverter<>(X.class)` target，且每個 target 都出現在某個 `@RegisterReflectionForBinding` config | 新增 `entity(NewRecord.class)` 但沒加 native hint 時 test fail | not required |
| T06 | `development-standards.md` | user 要求「正確修正後把做法紀錄到開發標準」 | 標準明寫 structured output record checklist：新增/修改 record target → native config + reflection guard + Error boundary | 文件沒更新時 AC-S173-7 fail | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-all.sh`
通過條件：所有帶 `AC-S173-*` 的測試都是綠燈，且 deploy 後 `gcloud logging read` 看不到同一組 production stacktrace。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S173-1 | 必做 | Test | native hint 包含 LlmJudgement 兩個 record class |
| AC-S173-2 | 必做 | Test | LLM analyzer 丟 Error 不會讓 scan listener 留 outbox 重投 |
| AC-S173-3 | 必做 | Test | 訂閱詳情 API 不再在 read-only transaction 內更新 users |
| AC-S173-4 | 必做 | Evidence | LAB deploy 後 upload post-publish log 無同 stacktrace |
| AC-S173-5 | 建議 | Inspection | security DEBUG env 從 deploy yaml 移除或留下期限理由 |
| AC-S173-6 | 必做 | Test | 本地測試會擋住 structured output record 缺 native hint |
| AC-S173-7 | 必做 | Docs/Test | 開發標準記錄 structured output native hint checklist |

**AC-S173-1: native hint 包含 LlmJudgement 兩個 record class**
- Given（前提）production image 走 GraalVM native runtime，`LlmJudge` 使用 `entity(LlmJudgement.class)` 反序列化 LLM JSON。
- When（動作）測試讀取 `ScanNativeConfig` 的 `@RegisterReflectionForBinding` annotation。
- Then（結果）annotation class list 包含 `io.github.samzhu.skillshub.security.scan.engines.LlmJudgement`。
- And（而且）annotation class list 包含 `io.github.samzhu.skillshub.security.scan.engines.LlmJudgement.RiskClaim`。

**AC-S173-2: LLM analyzer 丟 Error 不會讓 scan listener 留 outbox 重投**
- Given（前提）一個 `SecurityAnalyzer` 在 `analyze()` 內丟出 `UnsupportedFeatureError` 或測試用 `Error`。
- When（動作）`ScanOrchestrator` 執行該 analyzer。
- Then（結果）scan pipeline 不把該 `Error` 往 listener 外層重拋。
- And（而且）該 analyzer 的 output 視為 empty，其他 analyzer 的 findings 仍可合併。

**AC-S173-3: 訂閱詳情 API 不再在 read-only transaction 內更新 users**
- Given（前提）DB 已有 OAuth user `u_5450fa`，並且該 user 訂閱了一個 skill。
- When（動作）在 read-only service path 讀取 `/api/v1/me/subscriptions/details` 對應的 `findSubscriptionDetailsOfCurrentUser()`。
- Then（結果）回傳 subscription summary list，不拋 `cannot execute UPDATE in a read-only transaction`。
- And（而且）`users.last_seen_at` refresh 仍可發生在自己的 write transaction，不影響後續查詢。

**AC-S173-4: LAB deploy 後 upload post-publish log 無同 stacktrace**
- Given（前提）新 image 部署到 Cloud Run service `skillshub`，region `asia-east1`。
- When（動作）使用 Web UI 上傳一個含 `SKILL.md` 的 zip，並等待至少 60 秒讓 `SkillVersionPublishedEvent` listener 執行。
- Then（結果）`gcloud logging read` 查該 revision 不再出現 `Record components not available for record class io.github.samzhu.skillshub.security.scan.engines.LlmJudgement`。
- And（而且）同一個 skillId 的 `/api/v1/skills/{id}` 回 `200`，且 Cloud Run log 有 `Scan completed` 或等價 risk assessment 完成訊息。

**AC-S173-5: security DEBUG env 從 deploy yaml 移除或留下期限理由**
- Given（前提）[temp/service.rendered.yaml](/Users/samzhu/workspace/github-samzhu/skills-hub/temp/service.rendered.yaml:126) 目前設定 `logging.level.org.springframework.security=DEBUG`。
- When（動作）S173 修完並準備 deploy。
- Then（結果）該 env var 已從 rendered service yaml 移除。
- And（而且）若為追查新問題暫留，spec §7 必須寫明保留原因與預計移除條件。

**AC-S173-6: 本地測試會擋住 structured output record 缺 native hint**
- Given（前提）production source 內有 `ChatClient.call().entity(JudgeResponse.class)`、`new BeanOutputConverter<>(SearchIntentService.LlmIntentOutput.class)`、`ChatClient.call().entity(LlmJudgement.class)` 這類 structured output binding target。
- When（動作）本地執行 `cd backend && ./gradlew test --tests "*StructuredOutputNativeHintCoverageTest"`。
- Then（結果）測試會掃 `backend/src/main/java` 裡的 `.entity(X.class)` 與 `BeanOutputConverter<>(X.class)` target。
- And（而且）每個 target 都必須出現在某個 production `@RegisterReflectionForBinding` annotation 裡。
- And（而且）新增 `entity(NewRecord.class)` 或 `BeanOutputConverter<>(NewRecord.class)` 但未補 native hint 時，該測試必須失敗。

**AC-S173-7: 開發標準記錄 structured output native hint checklist**
- Given（前提）S173 已完成正確修正，且 AC-S173-1/2/6 都是綠燈。
- When（動作）查看 [development-standards.md](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/development-standards.md:246) 的 Spring AI / AOT 開發規範。
- Then（結果）文件明寫：新增或修改 `ChatClient.entity(Record.class)` / `BeanOutputConverter<>(Record.class)` target 時，必須同步新增或更新 `<Module>NativeConfig @RegisterReflectionForBinding(...)`。
- And（而且）文件明寫：同 PR 必須包含 local reflection guard test；如果該 path 在 Modulith listener / async listener 裡執行，必須加 `Error` boundary test，避免 outbox 重投。
- And（而且）文件明寫：只寫 JVM JSON roundtrip 測試不等於 native hint 已被保護。

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S173-2 | analyzer 失敗後立即 empty output，不讓 outbox 每分鐘重投同一個 `Error` 佔用 listener pool。 |
| Security | AC-S173-1, AC-S173-4 | 上傳後安全掃描要完成；關掉 LLM judge 不算修復。 |
| Reliability | AC-S173-2, AC-S173-3, AC-S173-4 | background listener failure 與 read-only transaction failure 都要有明確可驗結果。 |
| Usability | AC-S173-3 | 使用者打開「我的技能 / 訂閱」不再看到 server error。 |
| Maintainability | AC-S173-5, AC-S173-6, AC-S173-7 | temporary DEBUG logging 不長期留在 LAB deploy yaml；同 family AOT bug 由本地測試與開發標準擋住。 |

### AC Well-Formedness Check

| AC | Singular | Unambiguous | Implementation-free | Verifiable | Bounded |
|----|----------|-------------|---------------------|------------|---------|
| AC-S173-1 | pass | pass | pass | pass | pass |
| AC-S173-2 | pass | pass | pass | pass | pass |
| AC-S173-3 | pass | pass | pass | pass | pass |
| AC-S173-4 | pass | pass | pass | pass | pass |
| AC-S173-5 | pass | pass | pass | pass | pass |
| AC-S173-6 | pass | pass | pass | pass | pass |
| AC-S173-7 | pass | pass | pass | pass | pass |

## 4. 介面與 API 設計

### 4.1 Native Hint Config

```java
package io.github.samzhu.skillshub.security.scan;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.security.scan.engines.LlmJudgement;

@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
    LlmJudgement.class,
    LlmJudgement.RiskClaim.class
})
class ScanNativeConfig {}
```

欄位來源：

| Class | 為什麼要列 |
|------|------------|
| `LlmJudgement` | `LlmJudge.analyze()` 的 `.entity(LlmJudgement.class)` 直接 binding target。 |
| `LlmJudgement.RiskClaim` | `claims` list 裡的元素 type，Jackson 需要讀 nested record components。 |

### 4.2 Scan Error Boundary

現況：

```java
private AnalysisOutput safeAnalyze(SecurityAnalyzer analyzer, ScanContext ctx) {
    try {
        var output = analyzer.analyze(ctx);
        return output == null ? AnalysisOutput.empty() : output;
    } catch (Exception e) {
        log.warn("Analyzer {} failed: {}", analyzer.name(), e.toString());
        return AnalysisOutput.empty();
    }
}
```

設計：

```java
private AnalysisOutput safeAnalyze(SecurityAnalyzer analyzer, ScanContext ctx) {
    try {
        var output = analyzer.analyze(ctx);
        return output == null ? AnalysisOutput.empty() : output;
    } catch (Error e) {
        log.atError()
                .addKeyValue("analyzer", analyzer.name())
                .addKeyValue("error", e.getClass().getSimpleName())
                .setCause(e)
                .log("[scan] non-retryable Error in analyzer — skipping analyzer output");
        return AnalysisOutput.empty();
    } catch (Exception e) {
        log.warn("Analyzer {} failed: {}", analyzer.name(), e.toString());
        return AnalysisOutput.empty();
    }
}
```

`catch (Error)` 只包 analyzer boundary，不吞整個 JVM 其他位置的 fatal error。這裡的目的很窄：`UnsupportedFeatureError` 這種 native metadata 問題不會靠 retry 自癒，重投只會讓 `event_publication` 一直卡住。

### 4.3 User Upsert Transaction Boundary

設計：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public User upsertFromOidc(String oauthProvider, String sub, String email,
                           @Nullable String name, @Nullable String avatarUrl) {
    ...
}
```

行為：

| Caller | 外層 transaction | `upsertFromOidc` 寫 users row |
|--------|------------------|-------------------------------|
| `GET /api/v1/me` | 無或 web request default | 開自己的 write transaction |
| `findSubscriptionDetailsOfCurrentUser()` | `readOnly=true` | 暫停外層 read-only，開 write transaction 更新 `last_seen_at` |
| command endpoint | write transaction | 暫停外層 tx，獨立 refresh user，再回到 command tx |

`REQUIRES_NEW` 的成本是多一次 DB transaction；LAB/production maxScale=1、Hikari pool=3，目前 user refresh 是每個登入 user 的小 row update，成本可接受。若未來流量上來，再另開 spec 把 `last_seen_at` refresh 節流。

### 4.4 Local Release Guard for Structured Output Native Hints

新增一個不依賴 Docker / GraalVM 的本地測試，跑在 V01 `./gradlew clean test` 內。測試做兩件事：

1. 掃 `backend/src/main/java` production source，找出下列 structured output binding target：

```text
.entity(<Target>.class)
new BeanOutputConverter<>(<Target>.class)
```

2. 掃同一批 production source 裡的 `@RegisterReflectionForBinding(...)`，確認每個 target 都被列入 native hint。

目前掃描結果應包含：

| Binding target | 觸發 source | 必須出現於 |
|----------------|-------------|------------|
| `JudgeResponse.class` | `QualityJudge.call().entity(JudgeResponse.class)` | `ScoreNativeConfig` |
| `SearchIntentService.LlmIntentOutput.class` | `new BeanOutputConverter<>(LlmIntentOutput.class)` | `SearchNativeConfig` |
| `LlmJudgement.class` | `LlmJudge.call().entity(LlmJudgement.class)` | `ScanNativeConfig` |

測試刻意採 source-level scan，而不是只手寫一個 target list。原因：未來新增 `entity(NewRecord.class)` 時，即使 reviewer 忘記把 `NewRecord` 加進測試清單，regex scan 也會先抓到 production source 多了一個 binding target，並因 native hint 缺席而讓本地 test fail。

限制：這不是完整 native runtime 模擬；它保護的是本專案已反覆踩到的 S148/S157/S173 family：Spring AI / BeanOutputConverter 以 runtime `Class<T>` 做 record JSON binding，但 Spring AOT 無法自動推導 target。完整 Cloud Run runtime correctness 仍由 AC-S173-4 deploy log 驗證。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanNativeConfig.java` | new | 註冊 `LlmJudgement` / `RiskClaim` AOT reflection binding。 |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` | modify | `safeAnalyze` 補 `catch (Error)`，讓 single analyzer non-retryable failure 變 empty output。 |
| `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanNativeConfigTest.java` | new | 反射檢查 annotation class list 對齊 AC-S173-1。 |
| `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorErrorBoundaryTest.java` | new/modify | 用 fake analyzer 丟 `Error` 驗 AC-S173-2。 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/aot/StructuredOutputNativeHintCoverageTest.java` | new | 掃 production source 的 structured output target，確認每個 target 都有 `@RegisterReflectionForBinding`；對齊 AC-S173-6。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserUpsertService.java` | modify | `upsertFromOidc` 加明確 write transaction boundary。 |
| `backend/src/test/java/io/github/samzhu/skillshub/community/SkillSubscriptionServiceReadOnlyTxTest.java` | new/modify | 建 OAuth user + subscription fixture，在 read-only caller 中驗不再 500。 |
| `temp/service.rendered.yaml` | modify | 移除 temporary `logging.level.org.springframework.security=DEBUG`，或在 §7 註明保留原因。 |
| `docs/grimo/development-standards.md` | modify | S173 修正確認後，寫入 structured output native hint checklist：native config、local guard、Error boundary。 |
| `docs/grimo/debugging-playbook.md` | modify | 將 S173 的 `LlmJudgement` case 補到 §F2 Known cases，讓下次同 family 不重查。 |

---

## 6. Task Plan

POC：not required。S173 沒有引入新 SDK 或陌生 framework SPI；native hint 修法直接沿用 S148/S157 已 ship pattern，transaction 修法使用 Spring 既有 `REQUIRES_NEW` 行為。Phase 0 檢查結果：設計與 PRD P2/P3 對齊，upload command path 已通，本 spec 修 post-publish background scan 與 subscription read path。

| Task | 狀態 | AC | 說明 | 驗證命令 |
|------|------|----|------|----------|
| [S173-T01](../tasks/2026-05-14-S173-T01.md) | PASS | AC-S173-1, AC-S173-6 | 新增 `ScanNativeConfig`，並新增本地 structured output native hint coverage guard。 | `cd backend && ./gradlew test --tests "*ScanNativeConfigTest" --tests "*StructuredOutputNativeHintCoverageTest"` |
| [S173-T02](../tasks/2026-05-14-S173-T02.md) | PASS | AC-S173-2 | `ScanOrchestrator.safeAnalyze` 補 `Error` boundary，避免 outbox 重投 non-retryable native metadata bug。 | `cd backend && ./gradlew test --tests "*ScanOrchestrator*Test"` |
| [S173-T03](../tasks/2026-05-14-S173-T03.md) | PASS | AC-S173-3 | `UserUpsertService.upsertFromOidc` 加獨立 write transaction，讓 read-only subscription path 不再 500。 | `cd backend && ./gradlew test --tests "*SkillSubscriptionServiceReadOnlyTxTest"` |
| [S173-T04](../tasks/2026-05-14-S173-T04.md) | PASS | AC-S173-5, AC-S173-7 | 更新 development standards / debugging playbook，並清理 `temp/service.rendered.yaml` temporary security DEBUG env。 | `rg -n "BeanOutputConverter|RegisterReflectionForBinding|LlmJudgement|logging.level.org.springframework.security" docs/grimo/development-standards.md docs/grimo/debugging-playbook.md temp/service.rendered.yaml` |
| [S173-T05](../tasks/2026-05-14-S173-T05.md) | PASS local / POST-RELEASE deploy evidence | AC-S173-4 | `./scripts/verify-all.sh` PASS；deploy 後仍需用 Cloud Run logs 驗證 `LlmJudgement` 與 read-only transaction stacktrace 不再重現。 | `./scripts/verify-all.sh` + `gcloud logging read ...` |

執行順序：

```text
T01 -> T02 -> T03 -> T04 -> T05
```

T01 先做，因為它建立本地發版前會擋住同 family AOT 問題的保護網；T04 依賴 T01/T02 的正確修法後再寫入開發標準，避免文件先寫錯。T05 是 evidence task，若 Cloud Run log 還有同 stacktrace，必須回到對應 task 或新增 bug-fix task，不可直接 hotfix。

## 7. Implementation / Verification Log

### Local Verification

2026-05-14 `./scripts/verify-all.sh` exit=0.

| Gate | Result |
|------|--------|
| V01 `./gradlew clean test jacocoTestReport` | PASS |
| V02 LINE coverage | INFO: 85.8% (4624 / 5389) |
| V03 `./gradlew jacocoTestCoverageVerification` | PASS |
| V04 `cd frontend && npm test` | PASS |
| V05 `cd frontend && npm run verify` | PASS |
| V06 `cd frontend && npm test -- --coverage` | PASS |
| V07 `cd e2e && npx playwright test --grep @happy-path` | PASS |
| V08a `./gradlew processAot` | PASS |
| V08b `./gradlew bootBuildImage` | PASS |

`temp/service.rendered.yaml` currently targets Cloud Run service `skillshub`, region `asia-east1`, image `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260514-133813`. The temporary `logging.level.org.springframework.security=DEBUG` env was removed.

### Post-Release Deploy Evidence

Pending post-release. AC-S173-4 production log evidence requires a new image/revision containing S173 changes; user accepted local verification as the release gate on 2026-05-14.

Pre-deploy check on 2026-05-14 against current Cloud Run revision `skillshub-00022-khz` still returns old errors:

- `LlmJudgement` record reflection error: latest returned log timestamp `2026-05-14T15:44:29Z`.
- `cannot execute UPDATE in a read-only transaction`: returned log timestamp `2026-05-14T14:04:05Z`.

After deploy, run:

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND ("LlmJudgement" OR "cannot execute UPDATE in a read-only transaction")' --project=cfh-vibe-lab --limit=50 --format=json
```

Expected result after deploy: no new log entry for `Record components not available for record class io.github.samzhu.skillshub.security.scan.engines.LlmJudgement`, and no new log entry for `cannot execute UPDATE in a read-only transaction` from `/api/v1/me/subscriptions/details`.

### QA Review — 2026-05-14

Verdict: `PASS` for local release. User decision on 2026-05-14: local verification is sufficient for shipping; Cloud Run post-deploy log evidence remains a required follow-up, but is not a local release gate.

| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | PASS | `./scripts/verify-all.sh` exit=0 at `2026-05-14T15:44:23Z`; V01/V03 backend tests + coverage passed, V04/V05/V06 frontend checks passed, V07 Playwright happy-path passed, V08a AOT passed, V08b native image build passed. |
| Coverage / integration | PASS local / POST-RELEASE deploy | JaCoCo LINE coverage `85.8% (4624 / 5389)`; `skillshub-verify:local` native image built successfully. Cloud Run service still points to revision `skillshub-00022-khz`, image `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260514-133813`; this is recorded as post-release deploy evidence. |
| Manual verification | READY / POST-RELEASE | AC-S173-4 has executable `gcloud logging read` instructions after new deploy + upload wait. |
| Testability gate | CLEAR | Every AC has a test, inspection command, or deploy log command. No new testing infrastructure is required. |

AC status:

| AC | QA status | Evidence |
|----|-----------|----------|
| AC-S173-1 | VERIFIED | `ScanNativeConfigTest` reads `@RegisterReflectionForBinding` and confirms `LlmJudgement` + `LlmJudgement.RiskClaim`. |
| AC-S173-2 | VERIFIED | `ScanOrchestratorTest.analyzerErrorIsolated` covers analyzer `Error` and other analyzer output merge. |
| AC-S173-3 | VERIFIED | `SkillSubscriptionServiceReadOnlyTxTest` calls `findSubscriptionDetailsOfCurrentUser()` with an existing OAuth user and confirms the user refresh write completes. |
| AC-S173-4 | PASS local / POST-RELEASE deploy evidence | Local release passes because `./scripts/verify-all.sh` and V08b native image build are green. `gcloud run services describe skillshub --project=cfh-vibe-lab --region=asia-east1 --format=json` shows the current production revision is still old (`skillshub-00022-khz`), so post-deploy log evidence must be collected after a new revision is deployed. |
| AC-S173-5 | PASS local / POST-RELEASE deploy evidence | `rg -n "logging.level.org.springframework.security" temp/service.rendered.yaml` returns no match. Live Cloud Run service still has `logging.level.org.springframework.security=DEBUG` until the cleaned rendered yaml is deployed. |
| AC-S173-6 | VERIFIED | `StructuredOutputNativeHintCoverageTest` finds current `.entity(X.class)` / `BeanOutputConverter<>(X.class)` targets and confirms all are in `@RegisterReflectionForBinding`. |
| AC-S173-7 | VERIFIED | `development-standards.md` now records the structured output native hint checklist and Error-boundary rule. |

QA code review notes:

- `ScanNativeConfig.java` source comment was shortened to a one-line S173 reference so the detailed rationale stays in this spec, per `AGENTS.md` Spec-Linked Rationale.
- No `TODO` / `FIXME` markers were found in the touched S173 production/test files.
- After the comment cleanup, `cd backend && ./gradlew test --tests "*ScanNativeConfigTest" --tests "*StructuredOutputNativeHintCoverageTest"` passed (`BUILD SUCCESSFUL in 2m 14s`).

Post-release deploy follow-up:

1. Build and deploy a new Cloud Run revision that includes S173 code and the cleaned `temp/service.rendered.yaml`.
2. Confirm `gcloud run services describe skillshub --project=cfh-vibe-lab --region=asia-east1 --format=json` shows a new `latestReadyRevisionName` that is not `skillshub-00022-khz`.
3. Upload a valid `SKILL.md` zip, wait at least 60 seconds, then run the AC-S173-4 `gcloud logging read` command. Expected result: zero new `LlmJudgement` record reflection errors and zero new `cannot execute UPDATE in a read-only transaction` entries from `/api/v1/me/subscriptions/details`.

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---|---|---|
| Tech risk | 2 | 2 | GraalVM AOT / Spring AI structured output 是 production-only 風險，但 S148/S157 已有同 family 修法。 |
| Uncertainty | 1 | 1 | Root cause 由 Cloud Run log、既有 AOT 紀錄與 code path 對齊，需求清楚。 |
| Dependencies | 2 | 3 | 修正橫跨 S147 scan、S148 AOT pattern、S154 user identity、S145 subscription details，且需 Cloud Run deploy 後補 log evidence。 |
| Scope | 2 | 2 | Production code touched `security.scan` + `shared.security`，另加 repo-wide local guard 與標準文件。 |
| Testing | 2 | 3 | 除 JUnit / slice test 外，release gate 跑完整 `verify-all.sh`，含 Playwright 與 V08b native image build。 |
| Reversibility | 1 | 1 | 無 DB schema / public API 變更；可用單一 revert 回復。 |
| **Total** | **10 / S** | **12 / M** | Bucket shift S→M；原因是本地 release guard + Docker native build + deploy-follow-up evidence 一起進入 ship 範圍。 |
