# Case Study：Spring Boot 4 + Java 25 Cloud Build AOT Debug

實戰背景：S132 spec 把 image build 搬到 Cloud Build，加入 AOT artifact 讓 cold start 加速。第一次 push 失敗後，**繞了 6 次 CI 循環 + 5 次配置 attempt** 才找到根因。本 case 對應到 SKILL.md 的六階段，每階段都展示「**做錯什麼 vs 該怎麼做**」。

## 症狀

```
> Task :processAot FAILED
Caused by: org.springframework.aop.framework.AopConfigException: Advisor sorting failed
  ...
Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'methodSecurityExpressionHandler'
    → 'delegatingPermissionEvaluator'
    → 'skillPermissionStrategy'
    → 'dataSource'
Caused by: ...DataSourceProperties$DataSourceBeanCreationException:
  Failed to determine a suitable driver class
```

---

## Phase 0 — 症狀分類

**做對的**：第一次看到就分類成「bean creation 在 build-time 階段」。

**做錯的**：抓著「Failed to determine a suitable driver class」這條最後 line，當「URL property 沒注入」處理。

→ Lesson：**底部 line 是事故現場，不是根因**。

---

## Phase 1 — 本機快速重現

**做錯的**：第 1 次 CI 失敗 → 改 config → 推 → 第 2 次失敗 → 改 → 推 → 第 3 次... 第 4 次。每次 1.5-2 分鐘。

**該做的**：第 2 次失敗就應該停下來，本機跑：
```bash
SPRING_PROFILES_ACTIVE=aot ./gradlew processAot --rerun-tasks
```

本機 7 秒 / 次 vs CI 90 秒 / 次。**循環貴 vs 便宜差 13x**。

→ Lesson：**第 2 次遠端失敗就轉本機，無一例外**。

---

## Phase 2 — 完整因果鏈從上往下讀

**做錯的**：盯最後一行 `Failed to determine a suitable driver class`，以為是 DataSource URL 沒注入問題。

**做對的**（後來才做）：完整 stack trace 從上往下讀：

```
1. AopConfigException: Advisor sorting failed                        ← 真正觸發點
2. ↓ MethodSecurityAdvisorRegistrar$AdvisorWrapper.getOrder
3. ↓ DeferringMethodInterceptor.getOrder
4. ↓ PrePostMethodSecurityConfiguration.lambda$postAuthorizeAuthorizationMethodInterceptor$2
5. ↓ methodSecurityExpressionHandler bean instantiate（強制提早建構）
6. ↓ delegatingPermissionEvaluator (ctor 注 List<PermissionStrategy>)
7. ↓ skillPermissionStrategy (ctor 注 DataSource)
8. ↓ dataSource bean 建構
9. Failed to determine a suitable driver class                       ← 事故現場
```

寫成因果句：「**`@EnableMethodSecurity` advisor sort 在 AOT 階段呼叫 `getOrder()`，強制 instantiate `methodSecurityExpressionHandler` bean，連帶把 ctor 鏈上 DataSource 拖出來。AOT processing 不跑 `@ConfigurationProperties` binding，DataSourceProperties.url 是 null，Hikari 推不出 driver class。**」

→ Lesson：**「X 觸發 Y 拉出 Z」格式寫不出來 = 沒理解。回去再讀。**

---

## Phase 3 — 並行派 research agent

**做錯的**：前 4 次 CI 失敗都沒派 research agent，自己 trial-and-error。

**做對的**（第 5 次後才做）：派 agent 找 spring-boot GitHub issue，30 秒回來：

> [Issue #47781](https://github.com/spring-projects/spring-boot/issues/47781) "Allow Data JDBC Dialect resolution without requiring DataSource initialization" (status: blocked)
>
> [Issue #48240](https://github.com/spring-projects/spring-boot/issues/48240) "Document the need for a JdbcDialect bean when using Spring Data JDBC and AOT"
>
> 官方 workaround：提供 `@Bean JdbcDialect` 直接回 `JdbcPostgresDialect.INSTANCE`

如果**第 1 次失敗就派**，省 4 次循環 = 6 分鐘 CI + 30 分鐘配置改改看。

→ Lesson：**第 1 次失敗 = 派 research agent，不是 fallback**。

---

## Phase 4 — fix 生效驗證

**做錯的**：連續 5 次嘗試（systemProperty / environment / args / yaml profile / placeholder default），每次失敗 error 訊息**完全一樣**。我繼續換另一種寫法。

**該做的**（第 2 次同 error 就該做）：驗 fix 真的生效。

```bash
SPRING_PROFILES_ACTIVE=aot ./gradlew processAot --info 2>&1 | grep "spring.datasource"
```

輸出顯示 `-Dspring.datasource.url=jdbc:postgresql://...` **真的在 JVM args 裡**，但 error 還是「URL empty」。

意思是：**fix 真的傳進去了，但 Spring 沒讀到**。這時候問題從「我的注法不對」變成「Spring 在這 phase 不讀這個來源」（Phase 5 領域）。

→ Lesson：**連續 2 次 fix 後 error 一字不變 = fix 沒到達 bug 路徑，停手驗 fix 機制，不是換寫法**。

---

## Phase 5 — 挑戰 Spring 預設假設

**做錯的**：相信「Spring property source precedence：CLI args > env > system property > yaml」是 universal truth，套到所有 phase。

**根因**：Spring Boot 4 AOT processing 用 `ApplicationContextAotGenerator.refreshForAotProcessing()`，**這條 path 不執行完整的 `@ConfigurationProperties` binding lifecycle**。對被 advisor sort 強制 instantiate 的 bean，property 永遠不到 `DataSourceProperties.url`。

**轉變思路**：property/yaml 全部繞不過去 → 改用 Java code 直接給 bean。

```java
@Configuration
@Profile("aot")
public class AotStubConfig {
    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/aot_stub");
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }
}
```

加上 issue #47781 的 workaround：override `AbstractJdbcConfiguration.jdbcDialect()` 回 `JdbcPostgresDialect.INSTANCE` 跳過 connection metadata query。

第一次 SUCCESS。

→ Lesson：**「應該會 work」沒 work → 質疑 framework 行為（特別是 phase-specific）**。

---

## Phase 6 — 突破後 minimal-fix bisection

**做錯的**：第一次 SUCCESS 後馬上開始寫 spec / commit / 收尾文字。

實際當時 working state 累積了：
- `application-aot.yaml`：`spring.datasource.url + driver-class-name + username + password + flyway.enabled=false + sql.init.mode=never + data.jdbc.dialect=POSTGRESQL + autoconfigure.exclude×3 項 + docker.compose.enabled=false`
- `build.gradle.kts ProcessAot block`：5 個 systemProperty + 4 個 environment + 4 個 args（belt + suspenders）
- `application.yaml`：placeholder default `${...:default}` 改了沒 revert
- `AotStubConfig.java`：DataSource bean
- `JdbcConfiguration.java`：jdbcDialect override

**該做的**（user push back 後才做）：git stash + 逐項撤回 + 重驗：

```bash
# 真正必要的最小集（5 變更）：
1. id("org.graalvm.buildtools.native") plugin             ← 註冊 processAot task
2. ProcessAot args("--spring.profiles.active=aot")       ← 啟 aot profile
3. AotStubConfig.java (@Profile("aot")) DataSource bean  ← 跳 binding
4. JdbcConfiguration.jdbcDialect() override              ← issue #47781 workaround
5. application-aot.yaml: flyway.enabled=false + GcpContextAutoConfiguration exclude

# Noise 全撤（10+ 項）：
✗ spring.datasource.url 等 yaml 設定（property 不生效）
✗ ProcessAot block 的 systemProperty / environment（property 不生效）
✗ application.yaml placeholder default（profile yaml 已蓋）
✗ application-aot.yaml: spring.datasource.* / spring.data.jdbc.dialect（已被 Java config 解）
✗ application-aot.yaml: docker.compose.enabled=false（docker-compose 不在 processAotClasspath）
✗ application-aot.yaml: sql.init.mode=never（不需要）
```

→ Lesson：**第一個 SUCCESS = 立即 git stash + 逐項撤回，不是寫文字。CLAUDE.md 已寫的「Clean Experiments」原則必須執行**。

---

## 最終 minimal fix（真正必要的修改）

**File 1：build.gradle.kts**
```kotlin
plugins {
    id("org.graalvm.buildtools.native") version "0.11.5"  // 註冊 processAot
    // ...
}

tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
    args("--spring.profiles.active=aot")
}

tasks.named<BootBuildImage>("bootBuildImage") {
    environment.put("BP_NATIVE_IMAGE", "false")           // 防 Paketo 自動編 native
    environment.put("BP_JVM_AOTCACHE_ENABLED", "true")    // Java 25 AOT Cache
    environment.put("BP_JVM_CDS_ENABLED", "false")        // CDS bug #581
    environment.put("TRAINING_RUN_JAVA_TOOL_OPTIONS", "-Dspring.profiles.active=aot")
    environment.put("BPE_DELIM_JAVA_TOOL_OPTIONS", " ")
    environment.put("BPE_APPEND_JAVA_TOOL_OPTIONS", "-Dspring.aot.enabled=true")
}
```

**File 2：shared/aot/AotStubConfig.java（新檔）**
```java
@Configuration
@Profile("aot")
public class AotStubConfig {
    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/aot_stub");
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUsername("aot_stub");
        ds.setPassword("aot_stub");
        return ds;
    }
}
```

**File 3：shared/persistence/JdbcConfiguration.java（既有檔加 override）**
```java
@Bean
@Override
public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) {
    return JdbcPostgresDialect.INSTANCE;  // issue #47781 workaround
}
```

**File 4：src/main/resources/application-aot.yaml（新檔）**
```yaml
spring:
  flyway:
    enabled: false
  autoconfigure:
    exclude:
      - com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration
```

---

## 時間成本對照（如果一開始就照 6 phase）

| 步驟 | 實際發生 | 該做的 |
|---|---|---|
| 第 1 次 CI 失敗 | 改 config 推 | **本機重現 + 派 research agent**（30 秒同時派 + 7 秒本機循環）|
| 找到 issue #47781 | 第 5 次嘗試後派 agent | 第 1 次失敗就派 |
| 套 workaround | 第 6 次嘗試 | 第 1 次失敗找到 issue 後立刻套 |
| Bisect noise | User push back 才做 | 突破當下立刻做 |

**實際耗時**：~ 2 小時 + ~ 6 次 CI（共 9 分鐘 CI） + 5 次本機 attempt
**理想耗時**：~ 15 分鐘（1 次 CI + 1 次 research + 1 次本機驗證 + 1 次 bisect）

**8x slowdown 來自缺六 phase 任一步**。

---

## 關鍵 takeaway（跨技術棧通用）

1. **Phase ≠ runtime**：framework 在不同 lifecycle phase 行為差很多。Spring AOT 是這次的雷，但 Build tool / Container / Test runner 也都有 phase 行為差異
2. **Property/config 行不通就走 code**：Java config 直接 instantiate bean 比扭曲 property source precedence 簡單可靠
3. **GitHub issue search 是 first-thing**：不是 fallback。30 秒派 agent 換 4 次循環。
4. **Bisect 是 ship 的入場券**：累積 noise 不 bisect = permanent debt
