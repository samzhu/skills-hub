# Debugging Playbook — Skills Hub

> **目的**：把跨 spec 的 root-cause-debugging 經驗集中起來，下次踩同類 bug 從「45 分鐘從零 reproduce」變成「2 分鐘 grep + 確認 family」。
>
> **入口是症狀，不是 spec ID** — 你看到一個奇怪行為時不會記得是哪個 spec 修過的；你會記得 log 大概長怎樣、API 回什麼形狀。下面以症狀分組。
>
> **不放這裡的東西**：
> - 軟體架構決策（去 `architecture.md`）
> - 程式碼風格 / coding rule（去 `development-standards.md`）
> - 個別 spec 的完整設計（去 `specs/archive/`）
> - 上游 issue 追蹤 checkpoint（去 `development-standards.md §Upstream Issue Tracking`）

---

## 快查表（症狀 → family）

| 症狀關鍵字 | Family | 跳到 |
|---|---|---|
| 同一個 property，bean A init OK，bean B 永遠 "unavailable" / "not configured" | AOT @Conditional bake-out | [§F1](#f1-aot-conditionalonproperty-bake-out) |
| `UnsupportedFeatureError: Record components not available` | Record reflection 缺 hint | [§F2](#f2-record-reflection-metadata-缺-hint) |
| `IllegalArgumentException: Can not set boolean field ... to java.lang.Integer`（SELECT 才爆，INSERT 不爆） | Boolean primitive corrupt | [§F3](#f3-graalvm-methodhandle-把-boolean-換成-integer) |
| AOT processAot 階段 `OAuth2ClientProperties.validate` / 類似 framework `@ConfigurationProperties` validate fail | Framework @CP validate 在 AOT 強制非空 | [§F4](#f4-framework-configurationproperties-validate-在-aot-強制非空) |
| 改了 `application-aot.yaml` 某設定為 false / disabled，runtime 也跟著 false（即使 env var 設了 true） | AOT profile leak 到 runtime | [§F5](#f5-aot-profile-leak-到-runtime) |
| native build fail 訊息提到 `ArchUnit` / `ClassFileImporter` / `ModuleImportPlugin` | Modulith autoconfig 在 native 炸 ArchUnit | [§F6](#f6-modulith-autoconfig-在-native-炸-archunit) |
| `./gradlew clean test` 在 `processTestAot` 階段噴 `NoSuchBeanDefinitionException`，但 `-x processTestAot` 跑得過 | AOT 看不到 @MockitoBean / 缺 stub bean | [§F7](#f7-processtestaot-context-load-fail) |
| `Cannot mutate the artifacts of configuration ':cyclonedxDirectBom' ...` 或 `Querying the mapped value of task ':cyclonedxBom' before ... has completed` | cyclonedx-bom 3.2.4 vs nativeCompile race | [§F8](#f8-cyclonedx-bom-324-vs-nativecompile-task-graph-race) |

---

## §F1 AOT `@ConditionalOnProperty` bake-out

**症狀**

- LAB / Cloud Run revision startup log 對「同個 property 兩個 bean 分歧」：
  ```
  INFO  BeanA : Initialising real ChatModel (API key mode)
  WARN  BeanB : No EmbeddingModel configured — semantic search disabled.
  INFO  BeanC : LLM ChatClient unavailable — running in fallback mode
  ```
- API 回 silent fallback shape（不是 5xx）— 因為消費端 `Optional<X>` 或 `@ConditionalOnMissingBean` 接管
- runtime env var 確實有設、Secret Manager `${sm@...}` 確實 resolve OK

**Root cause mechanism**

Spring AOT 在 **build time**（CI 容器 / `processAot` task）評估 `@ConditionalOnProperty(name="<key>")`。若該 property 的值在 build time 拿不到（CI 沒 Secret Manager / 沒 runtime env / yaml 沒寫死 stub）→ condition = false → BeanDefinition 從 baked context 排除。Runtime 即使 env var 已注入 + sm@ 已 resolve，bean factory 已沒路徑造此 bean。fallback path 接管 → silent degradation。

「JVM mode 跑得起來」是因為 JVM 在 runtime evaluation；native image 走的是 build-time-baked 決定。

**Known cases**

- **S135a** (v3.14.0) — 首次發現 + 修法。`backend/.../score/judge/QualityJudgeConfig.java:17-23` Javadoc 把這個 bug 與修法寫得最清楚。
- **S157** (in-design 2026-05-11) — `SearchConfig.googleGenAiEmbeddingModel` + `ScannerAiConfig.scannerChatClient` 同坑漏修；連帶發現 `EngineEnabled` nested condition 沒 `matchIfMissing=true` 同樣 bake-out。

**2 分鐘 verify**

```bash
# 1. 對問題 bean 的 condition 寫法
grep -n "@ConditionalOnProperty\|matchIfMissing" <bean-file>

# 2. 該 property 是否在 application*.yaml 顯式有字面值（不是 ${...} placeholder）
grep -rn "<property-name>:" backend/src/main/resources/ backend/config/

# 3. 啟動 log 對比同 property 不同 bean 行為（最強信號）
gcloud logging read 'resource.type=cloud_run_revision AND severity>=INFO
  AND (textPayload:"Initialising" OR textPayload:"unavailable" OR textPayload:"not configured")' \
  --limit=20 --freshness=2h
```

3 個都指向「condition 缺 matchIfMissing + property 在 yaml 無字面值」→ 是這個 family。

**Fix pattern**

Mirror `QualityJudgeConfig`：

```java
// Before — condition AOT bake-out
@Bean
@ConditionalOnProperty(name = "skillshub.genai.api-key")  // build-time false → 排除
EmbeddingModel realModel(SkillshubProperties props) { ... }

// After — condition 拿掉，body runtime branch
@Bean
EmbeddingModel embeddingModel(SkillshubProperties props) {
    if (isBlank(props.genai().apiKey())) {
        return new NoOpEmbeddingModel();  // fallback
    }
    return new RealModel(props.genai().apiKey());  // happy path
}
```

替代寫法：保留 `@ConditionalOnProperty` 但加 `matchIfMissing=true` + 在 base yaml 顯式設值（QualityJudgeConfig 雙重保險）。前提是該 property 本身在 build time 找得到字面值。

**Regression guard**：reflection-based AC（per S157 AC-5/AC-6/AC-7、S168 AC-2/AC-3）— assert factory method annotation set 不再含 `@ConditionalOnProperty(name="<runtime-only-key>")`。

**Anti-patterns**

- ❌ 把 condition 改 `@ConditionalOnExpression("'${...}' != ''")` — 也是 AOT 評估，同樣 bake-out
- ❌ 把 bean 改 `@Lazy` — Spring lazy 不解 AOT 排除
- ❌ AOT yaml 內塞 stub 值（如 S139 OAuth2 client 那條路）— 只在「framework 端 `@ConfigurationProperties.validate()` 強制非空」場景值得（見 §F4），單純為了過 `@ConditionalOnProperty` 不該用 stub

---

## §F2 Record reflection metadata 缺 hint

**症狀**

- native runtime stacktrace：
  ```
  com.oracle.svm.core.jdk.UnsupportedFeatureError:
    Record components not available for record class
    io.github.samzhu.skillshub.score.judge.JudgeResponse
    at java.lang.Class.getRecordComponents
    at Jackson... ObjectMapper.readValue
    at Spring AI BeanOutputConverter.convert
  ```
- Modulith outbox 無限重投（`UnsupportedFeatureError extends Error`，不被 `@ApplicationModuleListener` 預設 `Exception` catch 接 → event 留 `PROCESSING` → `IncompleteEventRepublishTask` 不斷重投）
- 連帶把 health check 拖到 503

**Root cause mechanism**

Spring AI `BeanOutputConverter` 在 runtime 拿 `Class<T>` 餵 Jackson `ObjectMapper.readValue(json, T.class)`，Jackson 對 record 走 `Class.getRecordComponents()` 反射。**因為 T 是 runtime 傳入泛型，AOT processor 無法 trace 到具體 type** → 不會自動產生 reflection metadata → native runtime 缺 hint → `UnsupportedFeatureError`。

JVM 模式無此問題（JDK 反射自動支援 record components）。

**Known cases**

- **S148** (v4.25.0) — `JudgeResponse` + `JudgeResponse.DimensionScore` 修法
- **S157** (in-design) — latent twin：`SearchIntentService.LlmIntentOutput` 沒被涵蓋，bean wiring 修好 ChatClient 真接到 LLM 後必爆

**2 分鐘 verify**

```bash
# 1. 全 codebase 看誰有 @RegisterReflectionForBinding
grep -rn "@RegisterReflectionForBinding" backend/src/main/java

# 2. 找所有 BeanOutputConverter usage（這是常見 trigger）
grep -rn "BeanOutputConverter\|ParameterizedTypeReference" backend/src/main/java

# 3. 對每個 BeanOutputConverter 的 target type，看是否 record + 是否在 #1 結果裡
```

**Fix pattern**

新建（或補進現有的）`<module>/<Module>NativeConfig.java`：

```java
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
    MyRecord.class,                   // top-level record
    MyRecord.NestedRecord.class       // 巢狀 record 顯式列（Spring 對 nested record auto-traverse 但保險起見列出）
})
class SearchNativeConfig {
    // 純 AOT hint 來源，無 bean 宣告
}
```

`List<String>` / `Map<String, Object>` 等 generic 走 Jackson primitive path 不需額外 hint。

**防御性附加 fix（per S148 §3.2）**：consumer 的 listener / handler 加 `catch (Error e)` 分支吞掉並 log — `Error` 不該被 outbox 重試：

```java
try { service.process(event); }
catch (Error e) {
    log.atError().setCause(e).log("Non-retryable Error — skipping outbox retry");
    // 不 re-throw → Modulith 把 publication 標 completed
}
```

**Regression guard**：reflection AC — assert `<Module>NativeConfig` 的 `@RegisterReflectionForBinding.classes()` array 包含此 record（per S157 AC-7）。

**Build-time fast-fail**（可選）：

```bash
cd backend && ./gradlew nativeCompile -PexactReachability=true
```

`backend/build.gradle.kts:200-212` 的 `graalvmNative {}` block 加 `--exact-reachability-metadata=io.github.samzhu.skillshub`，scope 限專案 package。任何漏 hint 在 build 階段 fail 而不是 Cloud Run runtime 才 throw。POC + deploy-day 啟用。

---

## §F3 GraalVM MethodHandle 把 Boolean 換成 Integer

**症狀**

- runtime stacktrace（**SELECT** 才爆，INSERT 不爆）：
  ```
  java.lang.IllegalArgumentException: Can not set boolean field
    <Entity>.<fieldName> to java.lang.Integer
    at com.oracle.svm.core.reflect.fieldaccessor.UnsafeBooleanFieldAccessorImpl.set
    at <Entity>__Accessor_xxx.setProperty (Unknown Source)        ← Spring AOT-generated
    at ConvertingPropertyAccessor.setProperty
    at MappingRelationalConverter.readProperties
    at MappingJdbcConverter.readAndResolve
    at EntityRowMapper.mapRow
  ```
- 同 entity 寫進去（INSERT）沒事，讀出來（SELECT）才爆
- JVM mode 不爆

**Root cause mechanism**

GraalVM SubstrateVM 的 `MethodHandle.invoke` adaptation chain 在 native image 下把 Boolean 值在送進 `UnsafeBooleanFieldAccessorImpl.set()` 前損毀成 Integer（[oracle/graal#5672 GR-45258](https://github.com/oracle/graal/issues/5672)，上游 open 無修復）。

- INSERT 走 `WritingPropertyAccessor.getProperty()` 直讀 boolean field → `JdbcTemplate.setBoolean()` → 不經 corrupted adapter
- SELECT 走 AOT-generated `Entity__Accessor.setProperty()` → `MethodHandle.invoke(entity, Boolean.TRUE)` → 💥 corrupt → `UnsafeBooleanFieldAccessorImpl.set(boolean field, Integer)` → IAE

Spring 自己也踩過：[spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186) + [spring-data-mongodb#5101](https://github.com/spring-projects/spring-data-mongodb/issues/5101)，Spring 認定 GraalVM 上游 bug。

**Known cases**

- **S168 Round 2** (v4.49.0) — `User.contactEmailPublic` + `NotificationPreference` 4 fields
- Round 1 (v4.48.0) 用 `@ReadingConverter Converter<Integer, Boolean>` 嘗試攔截 → ship 完 prod 重現同 stacktrace。Root cause re-analysis 發現 `ConvertingPropertyAccessor.convertIfNecessary` 有 `ClassUtils.isAssignable(boolean.class, Boolean.class) → true` 短路條件 → converter 從未被 prod path 呼叫，是 dead code。

**2 分鐘 verify**

```bash
# 1. stacktrace 看到 UnsafeBooleanFieldAccessorImpl.set + AOT __Accessor → 100% 是這個 family
# 2. 該 entity 的 boolean field 型別
grep -nE "private\s+boolean\s+\w+" backend/src/main/java/.../entity/<EntityName>.java
```

primitive `boolean` = 受影響；`Boolean` wrapper = 已修。

**Fix pattern（per S168 Round 2 真正修法）**

primitive `boolean` field → wrapper `Boolean` field：

```java
// Before
private boolean contactEmailPublic;

// After
@Nullable
private Boolean contactEmailPublic;  // GraalVM oracle/graal#5672 — 走 UnsafeObjectFieldAccessor 不踩 primitive accessor 的 corrupt path
```

AOT-generated `unreflectSetter` 對 `private Boolean` 產生 `(Entity, Boolean)V` MethodHandle，SubstrateVM 純 reference-reference cast 無 unboxing adapter，走 `UnsafeObjectFieldAccessorImpl.set()` 只查 `field.getType().isInstance(value)` → Boolean.class.isInstance(Boolean.TRUE) = true → 通過。`UnsafeBooleanFieldAccessorImpl` corrupt path 從 bytecode 層面被切掉。

**Regression guard**：reflection AC（per S168 AC-2/AC-3）— assert `field.getType() == Boolean.class`（不是 `boolean.class`）。test name 對齊 family 名稱，如 `userContactEmailPublic_mustBeBooleanWrapper`。

**Anti-patterns / 教訓**

- ❌ `@ReadingConverter Converter<Integer, Boolean>` — `ClassUtils.isAssignable` 短路，converter dead code，prod path 永不呼叫（S168 Round 1 false fix）
- ❌ Migration 改 SMALLINT — schema ripple，對 GraalVM bug 沒做任何事
- **教訓 1**：research agent 給結論時若立論基於 framework 內部 call chain，**要追到 source code 那條短路條件**，不要只看 issue 標題 + API 名稱
- **教訓 2**：JVM mode test PASS ≠ native runtime PASS。**JVM test 必須能 reproduce 同 stacktrace** 才算 ground 在真實 path（不是手動餵 mocked input 證明）

---

## §F4 Framework `@ConfigurationProperties.validate()` 在 AOT 強制非空

**症狀**

- `./gradlew bootBuildImage` 或 `./gradlew processAot` 階段 fail：
  ```
  IllegalStateException: Property 'spring.security.oauth2.client.registration.<id>.client-id' is required
  ```
- 不是我們的 code 拋的，是 Spring autoconfig 端的 validate 拋的

**Root cause mechanism**

Spring Boot framework 端的 `@ConfigurationProperties`（如 `OAuth2ClientProperties.validate()`）在 AOT binding 階段強制非空檢查。CI 環境沒這個機敏值 → validate fail → AOT 整個失敗。

跟 §F1 不同：F1 是「我們自己的 condition 排除 bean」；F4 是「framework 端 validate 強制中斷 AOT」。

**Known cases**

- **S139** (v4.18.0) — `OAuth2ClientProperties.validate()` 對 `client-id` 強制非空

**2 分鐘 verify**

```bash
# AOT build log 看 IllegalStateException 的 caller chain
./gradlew processAot 2>&1 | grep -A 5 "IllegalStateException"
```

caller chain 若指向 framework 內部 `<X>Properties.validate` / `<X>Properties.<bind>` → 是這個 family。

**Fix pattern**

`backend/src/main/resources/application-aot.yaml` 內塞 stub 字串。runtime env var（優先序高於 yaml）覆蓋為真值：

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          <id>:
            client-id: aot-stub-client-id              # 過 AOT validate；runtime env var 覆蓋
            client-secret: aot-stub-client-secret
```

注意：
1. 此 stub 只解 framework validate 強制非空；**runtime 仍須 env var 注真值**（service.yaml / cloudbuild env）
2. 若你自己寫的 `@ConfigurationProperties` 有 `validate()` 同樣行為，建議改成「validate 不強制非空，consumer 端 fail-fast」— 把問題延後到實際使用而非 binding 時

---

## §F5 AOT profile leak 到 runtime

**症狀**

- 你在 `application-aot.yaml` 把某個 feature 設 false / disabled（如 `spring.cloud.gcp.secretmanager.enabled=false`）
- 想說「AOT 階段沒 creds 嘛、不該打 API」
- 結果 runtime 也跟著 false — 即使 Cloud Run env var 設了 true，sm@ 也 resolve 不了

**Root cause mechanism**

Spring Boot 4 / `AotStubConfig` 在 AOT 階段把 `aot` profile 寫進 `__ApplicationContextInitializer.addActiveProfile("aot")` baked 進 native binary。runtime 即使 `SPRING_PROFILES_ACTIVE` 沒 `aot`，baked profile 仍 active → `application-aot.yaml` runtime 仍被 load → 你寫的 false 仍生效。

per [spring-boot#41562 / #48408](https://github.com/spring-projects/spring-boot/issues/41562)：runtime `SPRING_PROFILES_ACTIVE` 不能移除已 baked 的 profile。

**Known cases**

- **S132** (v4.17.0) — `spring.cloud.gcp.secretmanager.enabled=false` 在 application-aot.yaml；runtime 透過 `application-gcp.yaml:33` 顯式 `secretmanager.enabled=true` override（profile 順序：aot → behavior → gcp，後載入 wins）

**2 分鐘 verify**

```bash
# 1. application-aot.yaml 看 disable 了什麼
grep -n "enabled: false\|exclude:" backend/src/main/resources/application-aot.yaml

# 2. 對應 runtime profile yaml 看有沒有顯式 re-enable
grep -n "<feature-name>" backend/src/main/resources/application-gcp.yaml \
                         backend/config/application-lab.yaml \
                         backend/config/application-prod.yaml
```

無顯式 re-enable = 是這個 family。

**Fix pattern**

不要假設「runtime 不啟用 aot profile 所以 aot yaml 不影響 runtime」。runtime profile yaml（gcp / lab / prod）顯式蓋回想要的值：

```yaml
# application-aot.yaml — build 階段 disable
spring.cloud.gcp.secretmanager.enabled: false

# application-gcp.yaml — runtime 顯式蓋回（必要！）
spring.cloud.gcp.secretmanager.enabled: true
```

**Anti-patterns**

- ❌ 假設 runtime profile 不含 aot 所以 aot.yaml 不會 load — baked profile 不可移
- ❌ 只在 aot.yaml 裡用 comment「runtime 應該 ...」沒實際在 runtime yaml 蓋回

---

## §F6 Modulith autoconfig 在 native 炸 ArchUnit

**症狀**

- native build 或 native runtime fail：
  ```
  java.lang.ClassNotFoundException: ... ModuleImportPlugin
    at ArchUnit ClassFileImporter
    at org.springframework.modulith.runtime.ApplicationModulesRuntime
  ```
- 跟我們 code 沒直接關係 — autoconfig + ArchUnit + native image 三方衝突

**Root cause mechanism**

`spring-modulith-actuator` / `spring-modulith-observability` autoconfig 在 startup 觸發 `ApplicationModulesRuntime` → ArchUnit `ClassFileImporter` 掃 class graph → native image 階段缺 `ModuleImportPlugin` reflection metadata（[spring-modulith#735](https://github.com/spring-projects/spring-modulith/issues/735) / [#1556](https://github.com/spring-projects/spring-modulith/issues/1556) 上游未修）。

**Known cases**

- 此 workaround 自 native image 啟用以來就 in place；架構 doc `architecture.md:596` 紀錄

**Fix pattern**

`backend/src/main/resources/application-aot.yaml`：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration
      - org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration
      - org.springframework.modulith.observability.autoconfigure.SpringDataRestModuleObservabilityAutoConfiguration
```

3 個都要排除；缺一個就會有對應的 ArchUnit code path 被觸發。

**Tracking trigger**：每次升 spring-modulith 版本時 reviewer 必查 #735 / #1556 狀態（per `development-standards.md §Upstream Issue Tracking`）。上游修了再拔 excludes。

---

## §F7 processTestAot context-load fail

**症狀**

- `./gradlew clean test` 在 `processTestAot` 階段噴一堆 `NoSuchBeanDefinitionException`
- `./gradlew clean test -x processTestAot` 跑得過
- 失敗集中在三種 base class：
  - **Cluster A** `RepositorySliceTestBase` → `NoSuchBeanDefinitionException at CacheAspectSupport.java:287`（缺 `CacheManager`）
  - **Cluster B** `WebMvcSliceTestBase` → `NoSuchBeanDefinitionException at DefaultListableBeanFactory.java:2297`（ctor injection 缺 bean）
  - **Cluster C** `@SpringBootTest` → 各種看起來像「真實 test fail」（NPE / AssertionError）但只在 AOT 階段出現

**Root cause mechanism**

`@MockitoBean` 對 AOT processor 不可見（[spring-framework#32925](https://github.com/spring-projects/spring-framework/issues/32925)），test slice annotations（`@DataJdbcTest` / `@WebMvcTest`）不載完整 autoconfig graph → AOT 階段缺 bean 無法解 graph → context load 失敗。

JVM mode 沒問題 — Mockito runtime override 在 context startup 時動態替換 bean。

**Known cases**

- **S148e** (v4.40.0) — `TestDataControllerTest$CacheStubConfig` vs `WebMvcSliceTestBase$AotStubBeans` 重複定義 cacheManager
- **S148c** (v4.38.0) — Modulith cycle 拖垮 processTestAot
- **S148d** (v4.39.0) — Modulith allowed-targets 缺 `score → security`
- **S166a** (v4.41.0) — 直接拆掉整個 cache 基礎設施（YAGNI），cluster A 全消
- **S166b/c** ⛔ — 取消（S166a + S165 ship 後 cluster B/C 失敗也跟著消，多由 cache infra 連鎖造成）

**2 分鐘 verify**

```bash
cd backend && ./gradlew clean test --info 2>&1 | grep -B 2 "NoSuchBeanDefinitionException" | head -30
```

對失敗 test 看 base class（`extends RepositorySliceTestBase` / `WebMvcSliceTestBase`）+ 缺哪個 bean → 對應 cluster。

**Fix patterns**

依 cluster：

| Cluster | 修法 |
|---|---|
| A. Repository slice 缺 `CacheManager` | **首選**：問「這個 cache 真的需要嗎？」MVP 流量下 `@PreAuthorize` 走 DB 每 request 完全 OK。S166a 直接拆 `@EnableCaching` + cache infra 整套 — 比補 stub 更乾淨（per CLAUDE.md「Feature First, Security Later」）|
| B. WebMvc slice 缺 ctor injection bean | base class（如 `WebMvcSliceTestBase.AotStubBeans`）補 `@TestConfiguration` stub bean；per-test 用 `@MockitoBean` 是 fallback |
| C. Full `@SpringBootTest` context 真 fail | 各個案 reproduce — 多半是 Modulith 循環依賴 / allowed-targets 不完整 / duplicate bean。S148c/d/e pattern 可參考 |

**Anti-pattern**

- ❌ 加 `-x processTestAot` 當作長期 workaround — 把 AOT regression 從 CI gate 開洞，下次 prod 才發現（S148 那次就是這樣中招）
- ❌ 用 `@MockitoBean` 解 AOT cluster A/B — Mockito 對 AOT 不可見，治本要 `@TestConfiguration` stub

---

## §F8 cyclonedx-bom 3.2.4 vs nativeCompile task graph race

**症狀**

`./gradlew nativeCompile` 失敗：

```
V1（plugin 啟用）              : Cannot mutate the artifacts of configuration ':cyclonedxDirectBom' after the configuration was consumed as a variant
V2（excludeTask cyclonedxBom）  : Querying the mapped value of task ':cyclonedxBom' property 'jsonOutput' before task ':cyclonedxBom' has completed is not supported
V3+（plugin 整個註解）          : ✅ 通過
```

**Root cause mechanism**

不是 AOT / reflection 議題，是 build tool 三方互衝：`org.cyclonedx.bom` 3.2.4 plugin + Gradle 9.4.1 + `nativeCompile` task graph，`processResources` ↔ `cyclonedxBom` 互依賴 race。

**Known cases**

- **S148b POC** (2026-05-09) — 發現
- **S148f** ⏸ deferred — 追蹤 spec；上游 [cyclonedx-gradle-plugin #821](https://github.com/CycloneDX/cyclonedx-gradle-plugin/issues/821) open 無修復

**現況 workaround**

`backend/build.gradle.kts:12` cyclonedx-bom plugin 註解。`./gradlew cyclonedxBom` 跑不出 `backend/build/reports/bom.json`；無實際使用者受阻（`scripts/` / `cloudbuild.yaml` / `.github/` 全 grep 過無 SBOM 上傳 pipeline）。

**Reactivate 觸發**（任一）

1. 上游 cyclonedx 4.x 發布（issue #821 解）
2. 新 spec 要做 SBOM upload（Snyk / Dependency Track / etc）
3. 切 native production deploy mode（BP_NATIVE_IMAGE=true）— 那時得先解此衝突

---

## 維護規則

### 何時更新此檔

`/shipping-release` 時，若該 spec §2 Root Cause 對應的 family **不在本檔的「快查表」**，append 一個新 §FN entry。判斷標準：

- ✅ 新 family — 症狀 + mechanism 跟既有 8 個 family 都對不上；其他 spec 未來踩同 root cause 機率 ≥ 30%
- ❌ 非 family — 是現有 family 的新個案；改去既有 §FN 的「Known cases」append 一行

### Entry 必含 6 段

1. **症狀** — 一個直接可觀察的 log / API / error 訊息（不是抽象描述）
2. **Root cause mechanism** — 一段話講為什麼這個 bug 存在
3. **Known cases** — spec ID（含版本）+ 哪個 file:line 是 fix 範例
4. **2 分鐘 verify** — 具體 grep / gcloud / curl 命令；跑完能 confirm/reject
5. **Fix pattern** — 程式碼 snippet（before / after），不是純文字描述
6. **Anti-patterns / 教訓**（可選但建議）— 哪些「看起來合理但實際不 work」的修法，避免下次有人重新試錯

### 不該記什麼

- 一次性 spec 個案（沒 cross-spec applicability）
- 純 framework upgrade follow-up（去 `development-standards.md §Upstream Issue Tracking`）
- 純 ops / deploy script issue（去 `scripts/gcp/DEPLOYMENT.md`）

### Cross-doc 引用慣例

- 此檔引用 spec：用 `**S<NNN>** (v<X.Y.Z>)` 格式
- 此檔引用 architecture / dev-standards：用 `<doc>.md §<section>` 格式
- spec 引用此檔：在 §2 Root Cause 結尾加 `> Family: debugging-playbook.md §F<N>`
