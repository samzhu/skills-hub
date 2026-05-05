# S138 — SB4 + SS7 OAuth2 RS Test Path Recovery

> **Status**: 📐 in-design (sections 1-5 ready；§6/§7 由 /planning-tasks 後續加)
> **Type**: Backend test maintenance（test-only；zero production code change）
> **Estimate**: S(7)
> **Triggered by**: S135a-T01 implementing 揭露 18 個 pre-existing test failures（Spring AI M5 binary compat 416/434 PASS；剩 18 個全為 SB4/SS7/spec-ship test maintenance debt）— blocks S135a downstream Phase 4 verification
> **Blocks**: S135a T02-T07（spec-roadmap S135a 暫掛 ⏸ deferred 待此 spec ship）
> **Depends**: 無 production dep；只改 test files

---

## §1 Goal

修 18 個 pre-existing test failures，unblock `./gradlew test` exit 0 → S135a Phase 4 verification 可進行。所有 18 失敗 root cause 為**過往 spec ship 時 production code 演進但 test 沒同步更新**，與 Spring AI M5 升級**無關**（per S135a-T01 verify M5 binary compat 416/434 PASS）。

```
6 個 cluster 對應 6 個過往 spec ship 留下的 test stale-ness:

C1 (10 tests) → S130 加 /api/v1/notifications/**.authenticated() 但 NotificationControllerTest 沒加 .with(jwt())
C2 (2 tests)  → S122 anonymous read 改走 *:read strategy fallback (Bug AW fix) 但 DelegatingPermissionEvaluatorTest 仍期望短路 false
C3a (2 tests) → S098a3-2 加 SkillQueryController BundleInfoQueryService dep 但 SkillsApiAnonymousTest / SkillQueryControllerApiContractTest 沒 mock
C3b (1 test)  → SkillQueryService 加 CurrentUserProvider dep 但 SkillVersionQueryTest 沒 mock
C4 (2 tests)  → S096c 4-tier risk system 把 0-finding 從 LOW 改 NONE 但 ScanOrchestratorTest + RiskAssessmentIntegrationTest 仍期望 LOW
C5 (1 test)   → S027 Dev Mode Admin Bypass 後 ROLE_admin 通過所有 permission 返 200 但 S016EndToEndSmokeTest 仍期望 403
```

**對照**：6 個 cluster 全部是「過往 spec ship 沒做 doc-sync 規格的 test sync」— 屬 test debt 累積。S138 是清債；無新功能。

### Out of Scope

| 項目 | 去向 |
|---|---|
| Production code 修改 | 不需要 — production 行為都是過往 spec ship 後的正確新行為 |
| Spring Security 7 升級 / 配置修改 | sub-agent 確認 SS7 `.with(jwt())` 無 breaking change；不改 SecurityConfig |
| Cache key consolidate ≤ 10 | S025c 範疇；正交於 S138 |
| Spring AI M5 BOM upgrade | S135a-T01 已 verify pass |
| `processTestAot` AOT bug | qa-strategy.md 既登 known limitation；workaround `-x processTestAot` |
| `spring.cloud.gcp.secretmanager.enabled` | S135a-T01 已修補 |

## §2 Approach

### §2.1 Chosen approach — Surgical per-cluster fix（user confirmed 2026-05-05）

每 cluster 對症下藥；最小 diff；不改全域 SecurityConfig / WebMvcSliceTestBase；不破壞既有正常 test。

| Cluster | Fix pattern (≤ 3 lines per test) |
|---|---|
| **C1** NotificationControllerTest × 10 | 每 mockMvc.perform(...) 加 `.with(jwt().jwt(j -> j.subject("alice")))` 對齊 既有 MeControllerTest pattern |
| **C2** DelegatingPermissionEvaluatorTest × 2 | 改 test permission `"read"` → `"write"`（write 仍短路 fail-secure，per `evaluate()` line 109-115）；既有 anonymous + read 行為改成走 strategy 走 S122 fallback 的設計，test 對該設計確認用 write 即足夠 |
| **C3a** SkillsApiAnonymousTest + SkillQueryControllerApiContractTest × 2 | 加一行 `@MockitoBean BundleInfoQueryService bundleInfoService;` field 對齊 SkillQueryController constructor (S098a3-2 加的) |
| **C3b** SkillVersionQueryTest × 1 | 加一行 `@MockitoBean CurrentUserProvider currentUserProvider;` field 對齊 SkillQueryService constructor parameter 6 |
| **C4** ScanOrchestratorTest.noFindingsLow + RiskAssessmentIntegrationTest.AC-1 純 markdown × 2 | 改 `eq("LOW")` → `eq("NONE")` + assertion message 對齊 S096c 4-tier `RiskLevel.NONE` (per glossary：「NONE = 0 findings + no scripts + no allowed-tools」) |
| **C5** S016EndToEndSmokeTest × 1 | Test setup 改非-admin user（去掉 `ROLE_admin` authority）or 改 expect 200（per S027 admin bypass 行為） — implementation 階段選一個對齊 test 原 intent |

### §2.2 Why not Approach B/C（challenge alternatives）

**Approach B（slice 全 disable OAuth via `skillshub.security.oauth.enabled=false`）已排除**：
- 會破壞 MeControllerTest 等真實驗 OAuth 行為的 test（LAB 模式下 `/api/v1/me` 無 JWT 也 200 而非 401）
- 不解 C2 / C4 / C5（這些不是 OAuth 問題）

**Approach C（WebMvcSliceTestBase 自動套 default `.with(jwt())`）已排除**：
- Slice base class 改動 ripple 到所有 11 個 controller test（per S025b 紀錄）
- 不解 C2 / C3 / C4 / C5
- 違反 S025b 既建 minimal-base-class 設計

### §2.3 Research Citations

| Source | 1-line summary |
|---|---|
| [Spring Security MockMvc OAuth2 docs](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/oauth2.html) | `.with(jwt())` SS7 無 breaking change；bypass JwtDecoder + 直接注入 SecurityContext |
| [SS6→7 OAuth2 RS migration](https://docs.spring.io/spring-security/reference/migration/servlet/oauth2.html) | 唯一 breaking change 為 JWT typ validation + BearerToken converter API；對 `.with(jwt())` 行為無影響 |
| [BearerTokenAuthenticationFilter source](https://github.com/spring-projects/spring-security/blob/main/oauth2/oauth2-resource-server/src/main/java/org/springframework/security/oauth2/server/resource/web/authentication/BearerTokenAuthenticationFilter.java) | 無 token 時 `chain.doFilter()` 繼續 — 不 reject 無 Bearer header 的請求；`.with(jwt())` 注入的 SecurityContext 仍能被 SecurityContextHolderFilter 讀到 |
| `cda4e4c feat: S130 ship personal endpoints auth gate` | git log — `/api/v1/notifications/**` 從 permitAll 改 `.authenticated()` 的 commit；C1 cluster 起點 |
| `4939db0 feat: ship S096c — Risk tier 4-level` | git log — 4-tier RiskLevel (NONE/LOW/MEDIUM/HIGH)；C4 cluster 起點 |
| `DelegatingPermissionEvaluator.java:104-115` | S122 anonymous read 走 `ANONYMOUS_READ_PRINCIPALS = Set.of("*:read")` strategy fallback 的 production code |
| `SecurityConfig.java:95-100` | S130 加 `.requestMatchers("/api/v1/notifications/**").authenticated()` 的當前 production state |
| `ScanOrchestrator.java:284` (per failure log) | `level=NONE, findings=0` — 4-tier 行為實證 |
| `SkillQueryController.java:60-66` | constructor `(SkillQueryService, BundleInfoQueryService)` — C3a missing-mock 對應 production state |
| `WebMvcSliceTestBase.java` (S025b) | base class 既宣告 `@MockitoBean JwtDecoder` + `@MockitoBean PermissionEvaluator`；子類用 `.with(jwt())` 為對齊規範 |
| `S135a-T01 verify` | `./gradlew test -x processTestAot` 416/434 PASS confirms M5 binary compat；18 個失敗 100% 為 test debt |

### §2.4 Confidence classification

| Decision | Confidence | Action |
|---|---|---|
| C1 fix 加 `.with(jwt())` | Validated（per Spring Security docs + sub-agent verify + 既有 MeControllerTest pattern） | proceed |
| C2 fix 改 permission to "write" | Validated（per `DelegatingPermissionEvaluator.evaluate()` 直接讀） | proceed |
| C3a + C3b fix 加 missing `@MockitoBean` | Validated（per Caused by chain `NoSuchBeanDefinitionException` 確認缺漏 bean） | proceed |
| C4 fix `eq("LOW")` → `eq("NONE")` | Validated（per failure log `Actual invocations ... "NONE"` 證實） | proceed |
| C5 fix S016EndToEndSmokeTest 對齊 admin bypass | Hypothesis — 失敗訊息 `expected:<403> but was:<200>` 但具體 test 步驟需 implementation 階段讀 test 詳細決定 fix（admin user setup vs change expect） | implement-time decide |

C5 唯一 hypothesis 但 risk 低 — 即使 fix pattern 兩種任一個都是 1-2 行 test 改動。

## §3 Acceptance Criteria

> Verify command: `./gradlew test -x processTestAot`（per qa-strategy.md known limitation；processTestAot bug pre-existing）
> Pass: 0 test failures（vs S135a-T01 baseline 18 failures） — 即 434/434 PASS。

```
Scenario: AC-S138-1 — NotificationControllerTest C1 cluster fix（10 tests）
  Given NotificationControllerTest 13 個 mockMvc.perform(...) call
  When 每個 call 加 .with(jwt().jwt(j -> j.subject("alice"))) post-processor
  Then `./gradlew test --tests NotificationControllerTest` 全綠
  And 既有 mockMvc 邏輯 / Mockito.verify 不變

Scenario: AC-S138-2 — DelegatingPermissionEvaluatorTest C2 cluster fix（2 tests）
  Given hasPermission_anonymous_shortCircuits + hasPermission_nullAuthentication_returnsFalse
  When test 改用 permission="write" 而非 "read"
  Then 兩 test 仍綠（write 仍走 fail-secure 短路 false）
  And 對應 @DisplayName 更新明確 "non-read permission" 語意

Scenario: AC-S138-3a — SkillQueryController slice missing @MockitoBean fix（2 tests）
  Given SkillsApiAnonymousTest + SkillQueryControllerApiContractTest extends WebMvcSliceTestBase
  When 加 @MockitoBean BundleInfoQueryService bundleInfoService field
  Then context load 成功
  And 既有 mockMvc 行為驗證不變

Scenario: AC-S138-3b — SkillVersionQueryTest missing @MockitoBean fix（1 test）
  Given SkillVersionQueryTest WebMvc slice
  When 加 @MockitoBean CurrentUserProvider currentUserProvider field（or 等價 stub）
  Then context load 成功 — SkillQueryService 6 個 ctor params 全 mock

Scenario: AC-S138-4 — 4-tier risk LOW → NONE assertion fix（2 tests）
  Given ScanOrchestratorTest.noFindingsLow + RiskAssessmentIntegrationTest 純 markdown
  When test 改 eq("LOW") → eq("NONE") for 0-finding 場景
  Then 兩 test 全綠
  And test display name 更新 "純 markdown skill → risk level NONE"（per glossary RiskLevel）

Scenario: AC-S138-5 — S016EndToEndSmokeTest admin bypass alignment（1 test）
  Given S016EndToEndSmokeTest 期望 403 但 actual 200
  When implementation 讀 test 完整 setup 後選 a/b：
       a. test setup 改非-admin user（去掉 ROLE_admin authority）→ 維持原 403 expect
       b. expect 改 200 → 對齊 S027 admin bypass 行為
  Then test 綠
  And 不破其他 S016 test 邏輯

Scenario: AC-S138-6 — overall ./gradlew test 0 failure
  Given S138 各 cluster fix 全部 ship
  When `cd backend && ./gradlew test -x processTestAot`
  Then exit 0
  And 434/434 tests PASS
  And 0 modularity violation
```

AC tagging：每 test method `@DisplayName("AC-S138-N: ...")` + `@Tag("AC-S138-N")` per qa-strategy.md。

## §4 Interface Design / Fix Patterns

### §4.1 C1 — NotificationControllerTest .with(jwt()) injection

```java
// Before
mockMvc.perform(get("/api/v1/notifications"))
    .andExpect(status().isOk());

// After（每 mockMvc.perform call 加 .with(jwt())）
mockMvc.perform(get("/api/v1/notifications")
        .with(jwt().jwt(j -> j.subject("alice"))))   // ← 1 行 inline
    .andExpect(status().isOk());
```

Static import 既存：`import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;`

13 個 mockMvc.perform call 全部加；對齊既有 MeControllerTest pattern。

### §4.2 C2 — DelegatingPermissionEvaluatorTest permission update

```java
// Before
var allowed = evaluator.hasPermission(anon, "abc-1", "Skill", "read");
assertThat(allowed).isFalse();

// After
var allowed = evaluator.hasPermission(anon, "abc-1", "Skill", "write");   // write 走 fail-secure
assertThat(allowed).isFalse();
```

兩 test method（hasPermission_anonymous_shortCircuits + hasPermission_nullAuthentication_returnsFalse）改 permission；@DisplayName 更新「non-read permission anonymous → fail-secure」。

S122 production change 仍 valid — read 走 strategy fallback 是 design intent；test 用 write 表示「對非-read permission 仍要短路」就 cover S016 §2.4 Challenge #8 mutation strict 設計。

### §4.3 C3a — Missing @MockitoBean BundleInfoQueryService

```java
@WebMvcTest(SkillQueryController.class)
class SkillsApiAnonymousTest extends WebMvcSliceTestBase {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SkillQueryService skillQueryService;

    @MockitoBean private BundleInfoQueryService bundleInfoQueryService;   // ← NEW per S098a3-2 ctor

    // 既有 test methods 不變
}
```

對齊 SkillQueryController 的 constructor (line 60)。同 fix 套用 SkillQueryControllerApiContractTest。

### §4.4 C3b — Missing @MockitoBean CurrentUserProvider

```java
class SkillVersionQueryTest extends RepositorySliceTestBase {   // 或 WebMvcSliceTestBase 視情況
    @Autowired private SkillQueryService queryService;
    @MockitoBean private CurrentUserProvider currentUserProvider;   // ← NEW per ctor param 6
}
```

實際 fix 視 test setup 結構；可能用 `@TestcontainersConfiguration @Bean @Primary` 既建 stub pattern（per S025a anti-散佈規範）。

### §4.5 C4 — 4-tier RiskLevel assertion update

```java
// Before
verify(m.skillRepo, atLeastOnce())
    .updateRiskLevel(eq("agg-1"), eq("LOW"), any(Instant.class));

// After
verify(m.skillRepo, atLeastOnce())
    .updateRiskLevel(eq("agg-1"), eq("NONE"), any(Instant.class));   // 0-finding → NONE per S096c
```

`@DisplayName` 也對齊：「AC-1.1: 無 finding → finalLevel NONE」。

### §4.6 C5 — S016EndToEndSmokeTest admin bypass

> Implementation-time choice between (a) revert test user role vs (b) update expect — 視 test setup 步驟意圖決定。Both ≤ 3-line diff。

## §5 File Plan

### §5.1 Modified files (all test-only)

| File | Cluster | Change |
|---|---|---|
| `backend/src/test/java/.../notification/NotificationControllerTest.java` | C1 | 13 mockMvc.perform(...) 加 .with(jwt()) |
| `backend/src/test/java/.../shared/security/DelegatingPermissionEvaluatorTest.java` | C2 | 2 test method permission "read" → "write" + @DisplayName 更新 |
| `backend/src/test/java/.../shared/security/SkillsApiAnonymousTest.java` | C3a | 加 @MockitoBean BundleInfoQueryService |
| `backend/src/test/java/.../skill/query/SkillQueryControllerApiContractTest.java` | C3a | 同上 |
| `backend/src/test/java/.../skill/query/SkillVersionQueryTest.java` | C3b | 加 @MockitoBean CurrentUserProvider |
| `backend/src/test/java/.../security/scan/ScanOrchestratorTest.java` | C4 | noFindingsLow eq("LOW") → eq("NONE") + @DisplayName 更新 |
| `backend/src/test/java/.../security/RiskAssessmentIntegrationTest.java` | C4 | AC-1 純 markdown skill assertion 對齊 NONE |
| `backend/src/test/java/.../S016EndToEndSmokeTest.java` | C5 | implementation-time decide (a) or (b) |

8 個 test files；total ~20-25 lines diff。

### §5.2 NEW files

無 — 純 surgical fixes。

### §5.3 NO production code change

ZERO `backend/src/main/...` files modified — all 18 failures' root cause 已是 production 演進的正確新行為，test 跟不上是 debt 而非 production bug。

### §5.4 Doc-sync

| File | Change |
|---|---|
| `docs/grimo/qa-strategy.md` | (可選) 加 known limitation entry：「過往 spec ship 應同 commit 更新對應 test — S138 起每 spec ship `/shipping-release` checklist 加 test sync verify 步驟」 — 預防未來重發 |

`docs/grimo/glossary.md` / `docs/grimo/architecture.md` 不需動（無 domain 概念新增）。

---

> **Next**: `/planning-tasks S138` 拆 1 task（cluster 內所有 fix 一個 PR ship）→ /implementing-task → `./gradlew test -x processTestAot` 0 failure 驗證 → ship → spec-roadmap S135a 從 ⏸ 回 ⏳ Plan resume T02。
