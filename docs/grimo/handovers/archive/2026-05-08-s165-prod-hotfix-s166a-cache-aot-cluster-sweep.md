---
topic: "S165 prod hotfix + S166a 拆 cache + AOT cluster sweep"
session_type: "debug"
status: "in_progress"
date: "2026-05-08"
---

# Handover: S165 prod hotfix + S166a 拆 cache + AOT cluster sweep

## Layer 1 — Portable Summary

### Completed

- **`.claude/loop.md` 優化**：六階段 → **七階段**（加 SHIP），EXIT label `✅ DONE` 重定義為「`/shipping-release` 跑完 + `git push` 成功」，新增 ALWAYS「spec ship 完立刻跑 `/shipping-release`」規則。**避免本 session 之前 batch 累積 9 specs 沒 ship 的問題**。
- **S165 spec doc 寫好**：`docs/grimo/specs/2026-05-08-S165-jackson-default-view-inclusion-prod-hotfix.md`（XS）。
- **S166 META spec doc 寫好**：`docs/grimo/specs/2026-05-08-S166-spring-aot-bean-registration-sweep.md`（涵蓋 a/b/c sub-specs）。
- **S165 程式 fix（uncommitted）**：`backend/src/main/resources/application.yaml` 加 `spring.jackson.default-view-inclusion: true`。修 prod `GET /api/v1/skills` 回 `{}` 的 root cause。
- **S166a 程式 fix（uncommitted）**：拆掉整個 cache 基礎設施 — 5 個 file 改動（`SkillshubApplication.java` / `SkillPermissionStrategy.java` / `SkillAclProjectionListener.java` / `build.gradle.kts` / `WebMvcSliceTestBase.java`）+ 1 個 test 移 cache assertion（`SkillPermissionStrategyTest.java`）。**驗證後 cluster A 從 ~10 失敗 → 0 失敗**。
- **GlobalExceptionHandler S162 hotfix（uncommitted）**：補 `AccessDeniedException` + `AuthenticationException` specific handlers 在 generic `Exception.class` fallback 之前。修 cluster B 大宗 — `@PreAuthorize` 拒絕拋的 `AuthorizationDeniedException` 被 generic 500 fallback 吞掉變 500 而非 403。
- **Test fixture 修補（uncommitted）**：
  - `CollectionControllerTest`: 補 `@MockitoBean NamedParameterJdbcTemplate`（CollectionQueryController ctor 需）→ 解 8 個 fail
  - `SkillsApiAnonymousTest`: 補 `@MockitoBean SkillDiffQueryService` + `SkillFileDiffService`（S142b 後 SkillQueryController ctor 多 dep）→ 解 1 個 fail
  - `RiskAssessmentIntegrationTest`: 加 `@TestPropertySource("skillshub.security.oauth.enabled=false")` 走 LAB mode → 解 3 個 fail（restTemplate 沒帶 JWT，S139 ship 後 POST 需 auth）
- **roadmap 更新**：加 S165 + S166 META + S166a/b/c row + S2XX-cache deferred backlog row。

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| 拆 cache 而非補 stub（S166a） | MVP 流量無痛；對齊「Feature First」；簡化 codebase；一次解 cluster A 整批 | 補 `RepositorySliceTestBase.AotStubBeans.cacheManager()` stub — 維持 S114b 但 dep 更繁複 |
| loop.md 改七階段加 SHIP | 把 `/shipping-release` skill 寫進工作流；讓「commit 完 ≠ ship 完」明確；防止本 session 早段 18 commits 沒 push 的累積 debt | 只在 ALWAYS rule 加 — 不夠強；commit stage 加描述 — 仍會被忽略 |
| GlobalExceptionHandler 補 Security 特定 handler | 標準 Spring 模式；最小 diff 不動其他 handler | 移除 `Exception.class` fallback — 太激進，會洩漏 framework default body |
| RiskAssessmentIntegrationTest 加 oauth.enabled=false | LAB mode permitAll 對 e2e 適合；改 test 而非改 prod | 補 JWT 到 restTemplate — 工大且需 mock OAuth issuer |
| ScheduleWakeup safety net 1500s | task-notification 是主 wake；25min 涵蓋慢跑情境；超過 5min cache 窗口接受 | 60-270s 短輪詢 — verify ~13min 沒意義 |

### Blockers

**verify-all.sh 結果未確認綠**

最後一次完整跑（在 fixes 之前，commit `5290b41`）結果是 **6 V0N 全 fail**，V01 backend 24/622 tests fail。

| Attempt | Result | Why It Failed |
|---------|--------|---------------|
| 跑 `./scripts/verify-all.sh` 第二次（S166a 後） | V01-V07 全 fail；V01 24 tests fail | S166a 解 cluster A ~10 個；剩 cluster B 15 / cluster C 5 / S165 cluster D 1 / frontend V04-V06 未診斷 |
| 跑 `./scripts/verify-all.sh` 第三次（補完 S165 + S162 + 3 test fixture 後） | **被 user `TaskStop bmwv393tu` 中斷**（handover） | N/A — 中斷，不是失敗 |

**Current hypothesis**: 全 fix 落下後 V01 應從 24 → 0 或 ≤2 fail。Frontend V04/V05/V06 未診斷（verify-all.log 每次 reset，舊資訊已沒）。

### Next Steps

**第一優先：跑 verify 確認 backend cluster B/C/D 全綠**

1. **重跑 verify-all.sh**：`cd /Users/samzhu/workspace/github-samzhu/skills-hub && ./scripts/verify-all.sh`（~13min）
2. **檢視結果**：
   - V01 backend test 數值
   - V04/V05/V06 frontend errors（從沒看過真正 root cause）
3. **若 V01 已綠 + V04-06 待修**：先 commit 已落地 fix（拆四個 atomic commit），再攻 frontend
4. **若 V01 仍紅**：grep 剩餘 fail，個案修

**Commit 拆分（須四個 atomic commits，非 batch）**：

a. `fix(S165): Jackson default-view-inclusion=true 修 prod /api/v1/skills 回 {}`
   - File: `backend/src/main/resources/application.yaml` + spec doc + roadmap
b. `refactor(S166a): 移除 cache 基礎設施 — MVP 不需 ACL cache`
   - File: `SkillshubApplication.java` / `SkillPermissionStrategy.java` / `SkillAclProjectionListener.java` / `build.gradle.kts` / `WebMvcSliceTestBase.java` / `SkillPermissionStrategyTest.java` + spec doc + roadmap
c. `fix(S162): GlobalExceptionHandler 不吞 Spring Security AccessDeniedException + AuthenticationException`
   - File: `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`
d. `test(fixture): 補 CollectionControllerTest jdbc mock + SkillsApiAnonymousTest diff/fileDiff mocks + RiskAssessmentIT LAB profile`
   - File: 三個 test files

**SHIP 階段（每個 commit 走 `/shipping-release`）**：
- 依序對 a/b/c 跑 `/shipping-release`（per loop.md SHIP 規則 — 不准 inline 模仿）
- d 是 test fixture 修補，不算 ship 一個 spec — 直接 commit；或併進 b/c 對應 spec ship

**之後（仍 outstanding）**：
- 攻 frontend V04/V05/V06 fail（沒診斷過）
- 處理 9 個本 session 之前的 unship spec 的 batch closeout（S143/S148/S151/S152/S153/S155/S156/S158/S162）— 之前 user 同意 option A 但 prod 中斷，handover 後重新評估是否照原計畫
- LAB curl 確認 prod recovery（push 後 Cloud Run 自動 deploy）

### Lessons Learned

- **`-x processTestAot` 是 prod 出 bug 的 enabler**：S148c/d/e 解開 AOT 後**首次跑** verify 揭露 ~30 失敗，全是長期被 shortcut 遮的 AOT bean graph gap。本 session user 強調「禁用 -x processTestAot」並寫進 loop.md NEVER。
- **Spring Boot 4 / Jackson 3 預設 `DEFAULT_VIEW_INCLUSION=false`**（S158 spec 文件假設 true）。`@JsonView` 啟用時，未標欄位**全部排除** — 對 `Page<Skill>` wrapper 致命。**Jackson 2 ↔ 3 不是 namespace 衝突**：annotations 仍在 `com.fasterxml.jackson.annotation`；只有 `tools.jackson.core` 是 Jackson 3 新 namespace。我之前 v4.36.0 誤判 namespace 是錯的。
- **`@RestControllerAdvice @ExceptionHandler(Exception.class)` 會吞 Spring Security 異常**：`@PreAuthorize` 拒絕拋 `AuthorizationDeniedException`，在 controller method 內 throw → DispatcherServlet 用 advice 處理 → 早於 `ExceptionTranslationFilter` → 變 500 而非 403。標準修法是補 specific handler，不是移除 fallback。
- **本 session 學到不要 batch ship**：早段 18 commits 累積沒 push、9 specs 沒跑 `/shipping-release` 是反 pattern。loop.md 加 SHIP 階段就是這個教訓。
- **Spring AOT 對 `@MockitoBean` runtime override 不可見**：bean graph 在 build 時固定，需要 stub bean 給 graph resolution。`WebMvcSliceTestBase.AotStubBeans.permissionEvaluator()` 就是這個 pattern。
- **新版 controller ctor dep 沒同步 test mock 是常見 drift**：S142b 加了 `SkillDiffQueryService` + `SkillFileDiffService`，`SkillsApiAnonymousTest` 沒跟上。AOT 跑全 suite 才會逼出來。

### Session Summary

Session 從上次 handover 接手「9 specs 批次 closeout」(option A) — 但 user 立即提報 prod `/browse` 壞掉。挖出 root cause = S158 `@JsonView` + Spring Boot 4 / Jackson 3 預設 `DEFAULT_VIEW_INCLUSION=false` → `Page<Skill>` 序列化成 `{}`，開 S165 spec 一行 yaml 修。User 反思之前 batch ship 沒走 `/shipping-release`、`-x processTestAot` 又遮 AOT bug，要求停止 shortcut + 優化 loop.md + 全綠才 ship。verify-all.sh 揭露 ~30 個 AOT 失敗，分 cluster A（cache）/B（WebMvc slice 缺 mock）/C（FullContext 真 fail）/D（S165 自身）。S166 META spec 開出來，S166a 從「補 stub」pivot 成「拆 cache 基礎設施」（MVP 不需）。Cluster B 抓到 root cause 是 S162 GlobalExceptionHandler `Exception.class` fallback 吞 Security exception。改 5 個 production file + 4 個 test file 全部落 disk，verify-all.sh 跑第三次到一半 user 喊 handover 中斷。下個 shift 重跑 verify 看是否全綠，再依 a/b/c/d 拆 atomic commit + SHIP。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | verify-all.sh 第二次跑（fix 前）：6 V0N FAIL；V01 24/622 backend tests fail。第三次跑（fix 後）被 TaskStop 中斷未確認 |

### Uncommitted Changes

```
 M .claude/loop.md
 M backend/build.gradle.kts
 M backend/src/main/java/io/github/samzhu/skillshub/SkillshubApplication.java
 M backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java
 M backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java
 M backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java
 M backend/src/main/resources/application.yaml
 M backend/src/test/java/io/github/samzhu/skillshub/community/CollectionControllerTest.java
 M backend/src/test/java/io/github/samzhu/skillshub/security/RiskAssessmentIntegrationTest.java
 M backend/src/test/java/io/github/samzhu/skillshub/shared/security/SkillsApiAnonymousTest.java
 M backend/src/test/java/io/github/samzhu/skillshub/shared/security/WebMvcSliceTestBase.java
 M backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategyTest.java
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-05-08-S165-jackson-default-view-inclusion-prod-hotfix.md
?? docs/grimo/specs/2026-05-08-S166-spring-aot-bean-registration-sweep.md
```

### Recent Commits

```
5290b41 refactor(S148e): 移 TestDataControllerTest 重複 cacheManager；processTestAot 全恢復
4e4cfc4 refactor(S148d): security.scan 加 @NamedInterface + score 補 allowed-targets
5f95c4d refactor(S148c): 解 shared↔skill Modulith cycle — 移 ValidationException 至 shared.api
143d0a9 feat(S151): 統一「scores=null」文案為「評分計算中」風格
2d43c20 docs(S151): 寫 spec doc — Quality Score 訊息一致性修正
```

### Key Files

**Production code modifications**:
- `backend/src/main/resources/application.yaml` — 加 `spring.jackson.default-view-inclusion: true`（S165 一行 fix）
- `backend/src/main/java/io/github/samzhu/skillshub/SkillshubApplication.java` — 移除 `@EnableCaching` + import
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java` — 移除 `CacheManager` ctor param + Cache field + cache.get/put 邏輯 + `log` 欄位（無 caller）
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` — 移除 2 處 `@CacheEvict` annotation + import
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` — 加 2 個 specific handler（`AccessDeniedException` → 403 / `AuthenticationException` → 401）放在 `Exception.class` 之前
- `backend/build.gradle.kts` — 移除 3 條 dep（`starter-cache` / `caffeine` / `cache-test`）

**Test fixture modifications**:
- `backend/src/test/java/io/github/samzhu/skillshub/community/CollectionControllerTest.java` — 加 `@MockitoBean NamedParameterJdbcTemplate jdbc`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/SkillsApiAnonymousTest.java` — 加 `@MockitoBean SkillDiffQueryService` + `SkillFileDiffService`
- `backend/src/test/java/io/github/samzhu/skillshub/security/RiskAssessmentIntegrationTest.java` — 加 `@TestPropertySource("skillshub.security.oauth.enabled=false")`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/WebMvcSliceTestBase.java` — 移除 S148e 加的 `cacheManager()` stub（已不需）
- `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategyTest.java` — 移除 cache assertion test + CacheManager / CaffeineCache imports

**Spec docs（新建）**:
- `docs/grimo/specs/2026-05-08-S165-jackson-default-view-inclusion-prod-hotfix.md` — 完整 §1-§5
- `docs/grimo/specs/2026-05-08-S166-spring-aot-bean-registration-sweep.md` — META spec 含 S166a/b/c sub-spec 設計

**Workflow doc**:
- `.claude/loop.md` — 六階段 → 七階段（加 SHIP）；ALWAYS 加「spec ship 完立刻跑 /shipping-release」；EXIT label `✅ DONE` 重定義

**Roadmap**:
- `docs/grimo/specs/spec-roadmap.md` — 加 S165 / S166 META / S166a/b/c / S2XX-cache deferred row；最後更新 timestamp

**Reference（須讀，未動）**:
- `docs/grimo/handovers/HANDOVER.md` — 本 file（前一份內容已 archive 進 `archive/` per /takeover）
- `verify-all.log`（project root）— 第二次跑（fix 前）資料；第三次跑被中斷可能殘留部分輸出

### Background tasks state

- `TaskStop` 已對 `bmwv393tu`（verify-all.sh 第三次跑）執行 — 已停
- `CronDelete 4efb7472` — 已取消下次 ScheduleWakeup
- 其他無 active task / monitor / cron
