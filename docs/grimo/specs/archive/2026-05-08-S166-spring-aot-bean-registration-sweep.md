# S166 META — Spring AOT Bean Registration Sweep

> **Status**: 📐 in-design（META, 含 S166a/b/c sub-specs）
> **Trigger**: S148e 後 `processTestAot` 解封；首次跑全 verify-all.sh 揭露 ~30 個 AOT 階段 context-load 失敗
> **Why critical**: AOT 不綠 → CI 不能 gate → S158-style「prod 才發現」bug 持續發生（user 2026-05-08 強調「應該還是要測試 processTestAot」）
> **Related**: S148e（unblock processTestAot）, S114b（@EnableCaching 引入）, S025b（slice base classes）

---

## §1 Goal

讓 `./gradlew clean test` (含 `processTestAot`) 全綠 — 取代既有 `-x processTestAot` shortcut。每個失敗類型對應一個 sub-spec ship 增量綠化。

**根本問題**：Spring AOT processor 在 build 階段 pre-compute bean graph，但 test slice annotations（`@WebMvcTest` / `@DataJdbcTest`）和 `@MockitoBean` runtime override 機制對 AOT 不透明 → 缺 bean 無法解 graph → ApplicationContext load 失敗。

## §2 Approach

### Three failure clusters（per 2026-05-08 verify-all.log 分析）

| Cluster | Test base | 失敗模式 | 範例 | Sub-spec |
|---|---|---|---|---|
| **A. RepositorySlice** | `extends RepositorySliceTestBase` | `NoSuchBeanDefinitionException at CacheAspectSupport.java:287` — 無 `CacheManager` bean。`@DataJdbcTest` slice 不載 `CaffeineCacheManagerAutoConfiguration`，base class 也沒補 stub | `DownloadEventRepositoryIdempotencyTest`, `SkillSubscriptionServiceTest`, `SkillScoreRepositoryTest`, `MapJsonbConverterTest`, `DomainEventRepositoryTest`, `V2MigrationTest`, `PgVectorStoreOwnerWriteTest`, `SkillshubPgVectorStoreAclTest`, `SkillshubPgVectorStoreAclSearchTest` | **S166a** XS(2) |
| **B. WebMvcSlice** | `extends WebMvcSliceTestBase` | `NoSuchBeanDefinitionException at DefaultListableBeanFactory.java:2297`（不是 CacheAspect line 287）— ctor injection 缺 bean。`@MockitoBean` 對 AOT processor 不可見（spring-projects/spring-framework#32925），需 `AotStubBeans` 補 stub | `CollectionControllerTest`, `SkillsApiAnonymousTest`, `SkillAclControllerTest`, `SkillSuspendControllerSecurityTest`, `AdminControllerTest`, `QualityScoreControllerTest`, `SecurityReportControllerTest` | **S166b** M(8) |
| **C. FullContext** | `@SpringBootTest`-class | 各種 — `AssertionError` / `NullPointerException` 看似真實 test failure 而非 context load 失敗，但只在 AOT 階段出現 | `S016EndToEndSmokeTest`（AC-7）, `SkillsHubAuthE2ETest`（NPE @ line 303）, `RiskAssessmentIntegrationTest`（AC-1 e2e + AC-1 HIGH + AC-1 NONE）| **S166c** S(5) |

### 修法策略

**S166a — 拆掉 cache 基礎設施**（pivot 2026-05-08，per user「cache 可以先不用」）：

Cache 在 MVP 階段不必要。`@EnableCaching` 是 S114b 為 ACL 評估 dedup 引入的優化，但 MVP 流量下每次 `@PreAuthorize` 走 DB 完全可接受（per CLAUDE.md「Feature First, Security Later」）。**移除 cache 一次解決 cluster A 全部 ~10 個 AOT failure** + 簡化 codebase。

**改動**：

1. `SkillshubApplication.java`：移除 `@EnableCaching` + `import org.springframework.cache.annotation.EnableCaching`
2. `SkillPermissionStrategy.java`：移除 `CacheManager` ctor 參數 + `Cache cache` 欄位 + `cache.get/.put` 邏輯；`evaluate()` 直接 call 底層 ACL 判斷（讓每次 `@PreAuthorize` 走 ACL service / repo）
3. `SkillAclProjectionListener.java`：移除 2 個 `@CacheEvict` annotation + `import`
4. `backend/build.gradle.kts`：移除 `spring-boot-starter-cache` / `caffeine` / `spring-boot-starter-cache-test` 三條 dep（如果沒其他 transitive 用到）
5. `WebMvcSliceTestBase.java`：移除 S148e 加的 `cacheManager()` stub bean（已不需要 — 全域沒 `@EnableCaching` 了）

**Why this is right (vs stub)**:
- 拆 cache > 補 stub：減程式碼 / 減 dep / 減 AOT graph 負擔
- 對齊 MVP 原則：caching 是 premature optimization（S114b 是早期過度設計，當時為 LAB perf 加；現實 MVP 流量無痛）
- 未來若真需要 ACL dedup，可在 S166 後 re-introduce（用 spring-boot-starter-cache + 明確 SLA 數字佐證）

**Caveat**: 每個 anonymous + authenticated request 透過 `@PreAuthorize("hasPermission(...)")` 都會打一次 DB 查 ACL。MVP / LAB 流量 < 100 RPS 不痛。Production 流量上來再 re-introduce。

**S166b — per-test stub bean 補完**（M(8)）：

每個 fail 的 WebMvc slice test 個案分析。方法：

1. 跑單一 test class with `--info`，抓出實際缺哪個 bean
2. 在 test class 補 `@MockitoBean` 或在 `WebMvcSliceTestBase.AotStubBeans` 加 stub
3. 優先把 stub 集中在 base class（多 test 共用）

候選新 stub 預估：`OAuth2 ResourceServer` 相關（401 anonymous test）、`SecurityContextHolderStrategy`、各 service mock 缺漏。具體待 S166b implement 階段確認。

**S166c — 全 context 真 fail 排查**（S(5)）：

各個案不同 root cause，需逐一 reproduce + 修。可能是 AOT 階段對 `@MockBean` lifecycle / `@DynamicPropertySource` / Testcontainers 啟動順序的差異。

---

## §3 Acceptance Criteria

驗證指令：`./scripts/verify-all.sh`（V01-V07 全部 PASS，no skip on AOT）。

### S166a AC

- AC-1: Cluster A 全部 10+ tests 在 `./gradlew clean test` 下綠（不需 `-x processTestAot`）
- AC-2: `WebMvcSliceTestBase` cluster B / FullContext cluster C tests 數量不變（無 collateral regression）

### S166b AC

- AC-1: Cluster B 全部 ~15 tests 綠
- AC-2: 共用 stub 集中在 `WebMvcSliceTestBase.AotStubBeans`，per-test stub 為例外

### S166c AC

- AC-1: Cluster C 全部 ~5 tests 綠
- AC-2: 每個 fix 在 spec §6 紀錄 root cause + symptomatic 對應

---

## §4 Interface Design

### S166a — 五個 file 移除 cache infra

**1. `SkillshubApplication.java`**

```diff
- import org.springframework.cache.annotation.EnableCaching;
  ...
- @EnableCaching
  public class SkillshubApplication {
```

**2. `SkillPermissionStrategy.java`** — 移除 `CacheManager` ctor 參數、`Cache` 欄位、`cache.get/put` 邏輯；`evaluate()` 直接走底層 ACL 判斷。

**3. `SkillAclProjectionListener.java`**

```diff
- import org.springframework.cache.annotation.CacheEvict;
  ...
- @CacheEvict(value = "skill-acl", allEntries = true)
  void onAclGranted(...) { ... }
- @CacheEvict(value = "skill-acl", allEntries = true)
  void onAclRevoked(...) { ... }
```

**4. `backend/build.gradle.kts`**

```diff
- implementation("org.springframework.boot:spring-boot-starter-cache")
- implementation("com.github.ben-manes.caffeine:caffeine")
- testImplementation("org.springframework.boot:spring-boot-starter-cache-test")
```

（保留可能也 OK — `@EnableCaching` 不開時這些 dep 不啟用 auto-config；但拆乾淨減 jar size 並消除 transitive 風險）

**5. `WebMvcSliceTestBase.java`** — 移除 S148e 加的 `cacheManager()` stub bean（已無 `@EnableCaching` → graph 不需要）

### Future re-introduce（deferred backlog）

當 production 流量起來、ACL eval 出現 hot path 時，re-introduce：
- 新 spec（暫定 S2XX）：`spring-boot-starter-cache` + Caffeine + 明確 SLA 觸發條件 + AOT compatibility plan
- 不 reuse S114b 設計 — 重做時應 audit 真實 hot path（profiling-driven）

---

## §5 File Plan

### S166a

| # | File | 動作 |
|---|---|---|
| 1 | `backend/src/main/java/io/github/samzhu/skillshub/SkillshubApplication.java` | M — 移除 `@EnableCaching` + import |
| 2 | `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java` | M — 移除 `CacheManager` ctor 參數 / `Cache` 欄位 / get-put 邏輯 |
| 3 | `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | M — 移除 2 處 `@CacheEvict` annotation + import |
| 4 | `backend/build.gradle.kts` | M — 移除 `starter-cache` / `caffeine` / `cache-test` 三 dep |
| 5 | `backend/src/test/java/io/github/samzhu/skillshub/shared/security/WebMvcSliceTestBase.java` | M — 移除 S148e 加的 `cacheManager()` stub |
| 6 | `docs/grimo/specs/spec-roadmap.md` | M — 加 S166 META + S166a/b/c row |
| 7 | `docs/grimo/CHANGELOG.md` | M — S166a entry（ship 階段） |
| 8 | （可能）existing tests asserting cache behaviour | M — 同步移除 cache 相關 assertion；具體待 grep 確認 |

### S166b/c File Plan

留待 sub-spec 各自 implement 階段詳列。

---

## §6 Verification（per sub-spec ship 後填）

> ⏳ pending S166a implementation

---

## §7 Result（per sub-spec ship 後填）

> ⏳ pending
