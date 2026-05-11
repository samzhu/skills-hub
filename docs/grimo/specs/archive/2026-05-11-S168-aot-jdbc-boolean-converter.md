# S168: GraalVM native image — Boolean wrapper field workaround for primitive boolean readback

> Spec: S168 | Size: S(9) → M(11) | Status: ✅ Done (Round 2 — Approach C ship; AC-4 ⏳ Round 2 manual deploy 待跑)
> Date: 2026-05-11

⚠️ **Round 1 (v4.48.0) Approach B (`@ReadingConverter Converter<Integer, Boolean>`) 已 ship 但 prod 重現同 stacktrace — fix 沒生效**。Root cause 重新分析後 pivot 至 Approach C（field type primitive `boolean` → wrapper `Boolean`），per JobRunr PR #1501 production-shipped fix 同 stacktrace 同 root cause。詳 §2.8 (Round 2 pivot retrospective) + §7 Round 2 段。

---

## 1. Goal

線上 LAB 登入後 `/browse` 頁面顯示「載入技能失敗，請重新整理頁面」。Cloud Run 跑的是 GraalVM native image，從 `users.contact_email_public`（PostgreSQL `BOOLEAN`）讀回 row 時，GraalVM SubstrateVM 的 MethodHandle adaptation 把 Boolean 偷換成 Integer，Spring AOT-generated `User__Accessor.setProperty()` 把 Integer 灌進 primitive `boolean` field 拋 `IllegalArgumentException`，被 `GlobalExceptionHandler` 接成 400 → 前端顯示「載入失敗」。

**Round 2 final approach (Approach C)**：把 `User.contactEmailPublic` 與 `NotificationPreference` 4 個 boolean field 從 primitive `boolean` 改為 wrapper `Boolean`。AOT-generated entity setter 從 `(Entity, boolean)V` 變 `(Entity, Boolean)V` — SubstrateVM 不需 unboxing adapter（純 reference cast），走 `UnsafeObjectFieldAccessorImpl` 不踩 primitive accessor 的 corrupt path。同步拔掉 Round 1 ship 的 dead `IntegerToBooleanConverter`（per Spring source 證實 `ClassUtils.isAssignable(boolean.class, Boolean.class)` 短路 conversion service，converter 從未被 prod path 呼叫）。

---

## 2. Approach

### 2.1 Root cause

GraalVM SubstrateVM 的 MethodHandle adaptation chain bug — 在 native image 下，`MethodHandle.invoke` 適配 `(Object, Object)V → (Entity, boolean)V` 時，把 Boolean 值在送進 `UnsafeBooleanFieldAccessorImpl.set()` 前損毀成 Integer。

**Stacktrace 證據**（Cloud Run revision `skillshub-00017-pzc` 2026-05-11 02:03:58）：

```
java.lang.IllegalArgumentException: Can not set boolean field
  io.github.samzhu.skillshub.shared.security.User.contactEmailPublic to java.lang.Integer
  at com.oracle.svm.core.reflect.fieldaccessor.UnsafeBooleanFieldAccessorImpl.set:266
  at User__Accessor_irgfaq.setProperty(Unknown Source)        ← Spring AOT-generated
  at ConvertingPropertyAccessor.setProperty:62
  at MappingRelationalConverter.readProperties:592
  at MappingJdbcConverter.readAndResolve:356
  at EntityRowMapper.mapRow:65
  at UserRepositoryImpl__AotRepository.findByOauthProviderAndSub:46
  at UserUpsertService.upsertFromOidc:84                       (S154 lazy upsert)
  at CurrentUserProvider.fromOAuth2User:148
  at SkillQueryService.search:219
```

**確認來源**：

- pgjdbc driver 沒 bug — standard JVM 工作正常；[pgjdbc#1189 GraalVM tracking](https://github.com/pgjdbc/pgjdbc/issues/1189) 無相關 fix
- Spring Data Relational 端已 reproduce — [spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186)，同 stacktrace、同 Spring Boot 4 + native image，被 Spring 團隊 close 為 `for: external-project`
- Duplicate 自 [spring-data-mongodb#5101](https://github.com/spring-projects/spring-data-mongodb/issues/5101) — 確認跨 ORM 都中
- 真正 bug：[oracle/graal#5672 GR-45258](https://github.com/oracle/graal/issues/5672) — MethodHandle on getter/setter 在 native image 下 corrupt 回傳/輸入值（Boolean → Integer / Long → 0 / null → default）

### 2.2 為何 INSERT 不爆 SELECT 才爆

```
INSERT 路徑（Java entity → DB row）
  WritingPropertyAccessor.getProperty(entity, "contactEmailPublic")
    → User__Accessor.getProperty() 直讀 boolean field
    → JdbcTemplate setBoolean(idx, true)
    → PG INSERT 成功
  ❌ 不走 GraalVM 有問題的 adapter chain

SELECT 路徑（DB row → Java entity）
  ResultSet.getObject("contact_email_public") → Boolean (correct)
  MappingRelationalConverter.readProperties()
    → ConvertingPropertyAccessor.setProperty(entity, "contactEmailPublic", Boolean.TRUE)
      → User__Accessor.setProperty()
        → MethodHandle.invoke(entity, Boolean.TRUE)
          → 💥 GraalVM adapter 把 Boolean.TRUE corrupt 成 Integer(1)
            → UnsafeBooleanFieldAccessorImpl.set(boolean field, Integer)
              → throw IllegalArgumentException
```

### 2.3 為何 User 先爆 NotificationPreference 沒爆

| Entity | persistent boolean fields | 觸發讀取的時機 | LAB 是否觸發過 |
|---|---|---|---|
| `User` | 1 (`contactEmailPublic`) | 每次 authenticated request 走 `CurrentUserProvider.current()` → `findByOauthProviderAndSub` lazy upsert（S154） | ✅ 第一次 Google login 立即觸發 |
| `NotificationPreference` | 4 (`flagsEnabled` / `reviewsEnabled` / `requestsEnabled` / `versionsEnabled`） | `prefRepo.findById(actor)` — 只在 user 主動更新偏好或 projection listener fire 時才查 | ❌ LAB 還沒人寫過 row，`.orElseGet(defaults)` 短路 |

→ NotificationPreference latent bug 還沒爆，但根因相同；本 spec 用全域 converter 一次解決。

### 2.4 Approach 比較

| Approach | Pros | Cons | Chosen |
|----------|------|------|--------|
| A. pgjdbc reachability metadata | — | Bug 不在 pgjdbc，加任何 metadata 沒用（research agent #1 confirmed） | no |
| **B. `@ReadingConverter Converter<Integer, Boolean>` 註冊 `userConverters()`** | 1 個 inner class + 1 行加進現有 list；converter 在 AOT accessor **之前**攔截 → AOT accessor 收到正確型別不踩 GraalVM bug；scope 全域所有 entity，自動防 NotificationPreference 同類 latent bug；現有 `JdbcConfiguration.java` 已 extend `AbstractJdbcConfiguration` + 已 override `userConverters()` 直接加 | 額外一個 converter 在 read path（perf 影響忽略：simple type HashMap lookup） | **yes** ⭐ |
| C. User.contactEmailPublic `boolean` → `Boolean` wrapper | 改一個 field；wrapper 走 `UnsafeObjectFieldAccessor` 不踩 primitive boolean 那條 bug | NotificationPreference 4 個 boolean 仍 latent；未來新 entity 加 primitive boolean 還會踩；屬於迴避不是攔截 | no |
| D. Migration 改 SMALLINT + app 層 0/1↔boolean | — | Schema migration 成本 + 跨 spec ripple；對 GraalVM bug 沒做任何事 | no |

### 2.5 Spring Data JDBC API 選擇

Spring Data JDBC 4.x 官方文件：

> "In previous versions it was recommended to directly overwrite `jdbcCustomConversions()`. **This is no longer necessary or even recommended.**"
> — [Custom Conversions reference](https://docs.spring.io/spring-data/relational/reference/commons/custom-conversions.html)

→ 本 spec 用 `AbstractJdbcConfiguration.userConverters()`（不是 `jdbcCustomConversions()`）。我們現有 `JdbcConfiguration.java:61` 已 override `userConverters()` 加 4 個 PG-specific converter，這次只是再加 1 項。

### 2.6 Research Citations

- [oracle/graal#5672 GR-45258](https://github.com/oracle/graal/issues/5672) — GraalVM MethodHandle adaptation 對 Boolean/Integer 在 native image 下 corrupt（real root cause；upstream 仍 open）
- [spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186) — 完全同 stacktrace；Spring 團隊認定 external project
- [spring-data-mongodb#5101](https://github.com/spring-projects/spring-data-mongodb/issues/5101) — 同 root cause MongoDB 端
- [Spring Data Relational Custom Conversions](https://docs.spring.io/spring-data/relational/reference/commons/custom-conversions.html) — `userConverters()` 4.x 推薦 API
- [Spring Data JDBC AOT hints](https://docs.spring.io/spring-data/relational/reference/jdbc/aot.html) — 確認 custom converter 不需手動 reflection metadata
- [pgjdbc#1189](https://github.com/pgjdbc/pgjdbc/issues/1189) — GraalVM 支援 tracking（無此 bug 修復）

### 2.7 Tracking Upstream Fix（移除 workaround 的觸發條件）

| Issue | 監控訊號 | 移除動作 |
|---|---|---|
| [oracle/graal#5672](https://github.com/oracle/graal/issues/5672) | "fixed in X.Y.Z" 且 `org.graalvm.buildtools.native` 升到含 fix 的 GraalVM | 寫 nativeRun integration test 直讀 `users.contact_email_public` 不 corrupt → 拔 `IntegerToBooleanConverter` + spec roadmap 加 S2XX cleanup row |
| [spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186) | "Reopened" 或 Spring 接手 in-framework workaround | 改用 Spring 官方方案（若有） |
| Spring Boot 4 release notes | 提及 "AOT entity boolean field reading" 修復 | 評估 in-framework 解決後拔 workaround |

**追蹤頻率**：依 development-standards.md 新增的 review checkpoint — 每次升 Spring Boot / GraalVM 版本時 reviewer 必查上述 issues 狀態。

### 2.8 Round 2 pivot — Approach B 失敗 → Approach C（real fix）

**Round 1 (v4.48.0 ship) failure**：

部署到 Cloud Run 後 `/browse` 同樣 400「載入技能失敗」，revision `skillshub-00018-czg` log 03:44:29 stacktrace **完全相同**，IAE on `User.contactEmailPublic`：

```
at UnsafeBooleanFieldAccessorImpl.set:95
at User__Accessor_irgfaq.setProperty(Unknown Source)
at ConvertingPropertyAccessor.setProperty:62  ← 有經過，但短路了
at MappingRelationalConverter.readProperties:592
at UserRepositoryImpl__AotRepository.findByOauthProviderAndSub:46
```

**Root cause re-analysis（3 個 research agent 獨立確認）**：

1. **Spring source 短路**（`ConvertingPropertyAccessor.java:106-112`）：
   ```java
   private <S> S convertIfNecessary(@Nullable Object source, Class<S> type) {
       return (S) (source == null ? null
           : ClassUtils.isAssignable(type, source.getClass()) ? source     // ← 短路
           : conversionService.convert(source, type));
   }
   ```
   `ClassUtils.isAssignable(boolean.class, Boolean.class)` 返回 `true`（Spring 視 primitive/wrapper 為 assignable）→ 短路返回原 Boolean，**conversion service 從未被呼叫** → `IntegerToBooleanConverter` 是 dead code。

2. **JVM test false-positive**：Round 1 的 test `whenIntegerInBooleanColumn_ConverterRecoversBoolean_User` **手動餵 Integer** 到 `RowDocument`，那條測試路徑與 prod 完全無關（prod path 從 JDBC 讀回的是 Boolean，不是 Integer）。Test PASS 證明的事情跟 production 場景脫鉤。

3. **真正修法（JobRunr PR #1501 shipped fix 同 stacktrace 同根因）**：把 primitive `boolean` field 改為 wrapper `Boolean` field：
   - AOT-generated `unreflectSetter` 對 `private Boolean field` 產生 `(User, Boolean)V` MethodHandle
   - SubstrateVM 純 reference-reference cast 無 unboxing adapter
   - 走 `UnsafeObjectFieldAccessorImpl.set()`（line 100-113）只查 `field.getType().isInstance(value)` → Boolean.class.isInstance(Boolean.TRUE) = true → 通過
   - `UnsafeBooleanFieldAccessorImpl` corrupt path 從 bytecode 層面被切掉

**Round 2 changes**：

| File | Change |
|---|---|
| `User.java:63` | `private boolean contactEmailPublic` → `private Boolean contactEmailPublic` + Javadoc 註明 oracle/graal#5672 + JobRunr PR #1501 |
| `NotificationPreference.java:32-38` | 4 個 `boolean` → `Boolean`（`flagsEnabled` / `reviewsEnabled` / `requestsEnabled` / `versionsEnabled`）+ inline comment |
| `JdbcConfiguration.java` | 拔 `IntegerToBooleanConverter` inner class + 從 `userConverters()` list 移除（dead code per Spring source 證實） |
| `JdbcConfigurationConverterTest.java` | 重寫：拔 AC-1 converter logic test 與 AC-2/AC-3 false-PASS integration tests；改為 reflection-based field-type assertion regression guard（assert field type 為 `Boolean.class`，防未來 PR 改回 primitive） |

**Round 1 → 2 教訓**（Pattern rule 寫進下次參考）：

> **Rule**: research agent 給「approach X 是正解」結論時，如果 verdict 立論基於某個 Spring/library 內部呼叫鏈（例：「converter 會在 X 之前攔截」），**要追到 source code 那條呼叫鏈的短路條件**（`isAssignable` / `instanceof` / null check 等），不要只看 issue 標題 + API 名稱。Round 1 採信 agent #1「register converter 在 AOT accessor 之前攔截」結論，但沒讀 `ConvertingPropertyAccessor` 內部的 `ClassUtils.isAssignable` 短路 → fix 對著錯路徑做 → JVM test 手動餵 Integer 證明的事情跟 prod 路徑無關 → ship fail。

> **Rule**: JVM mode test PASS ≠ native runtime PASS。bug 只在 GraalVM SubstrateVM runtime 才出現的場景，**JVM test 必須能 reproduce 同 stacktrace**（不是憑空餵 mocked input 驗證）才算 ground 在真實 path。否則 test 是 false-positive。

---

## 3. SBE Acceptance Criteria

**~~AC-1: Converter 邏輯正確~~** ⛔ **dropped (Round 2)** — `IntegerToBooleanConverter` 已從 codebase 拔除，Round 1 false-positive 證明 converter 永不被 prod path 呼叫，無存在意義。

**AC-2: `User.contactEmailPublic` field 必為 Boolean wrapper（防 GraalVM oracle/graal#5672 重現）**

```
Given codebase 含 io.github.samzhu.skillshub.shared.security.User class
When  reflection 取 contactEmailPublic field type
Then  field.getType() == Boolean.class（**不可** == boolean.class）
```

理由：未來 PR 改回 primitive `boolean` 會在 GraalVM native image runtime 重現 IAE（`UnsafeBooleanFieldAccessorImpl.set` corrupt path）。Reflection assertion 是 build-time regression guard，未來 reviewer 看 test name `userContactEmailPublic_mustBeBooleanWrapper` 一秒看出在防什麼上游 bug。

**AC-3: `NotificationPreference` 4 個 boolean column field 必為 Boolean wrapper（同上規避）**

```
Given codebase 含 io.github.samzhu.skillshub.notification.NotificationPreference class
When  reflection 取 flagsEnabled / reviewsEnabled / requestsEnabled / versionsEnabled 4 個 field type
Then  4 個 field.getType() 皆 == Boolean.class
```

證明 GraalVM bug 防護 scope 涵蓋多 entity，未來 spec 新加 entity 時也應遵循同 pattern。

**AC-4: Manual deploy 驗收 — `/browse` 不再「載入技能失敗」**

```
Given 本 spec ship + LAB Cloud Run native image revision 部署完成
When  任一 user 用 Google login 進入 /browse 頁面
Then  頁面顯示 skill 列表（無「載入技能失敗，請重新整理頁面」訊息）
And   瀏覽器 Network tab GET /api/v1/skills 回 200（response body 含 page metadata + skill list）
And   Cloud Run 同 revision log 對該請求區段查無 IllegalArgumentException stacktrace
```

**AC-5: 文件對齊 — architecture.md production deploy mode 描述正確**

```
Given architecture.md "GraalVM AOT Strategy" 段
When  讀「(a) Production deploy mode」子段
Then  描述為「GraalVM native image（Paketo native-image buildpack 由 org.graalvm.buildtools.native plugin metadata 自動觸發）」
And   段內加 S168 cross-reference（→ Boolean read converter workaround）
```

**AC-6: development-standards.md review checkpoint 加入**

```
Given development-standards.md
When  升 Spring Boot / GraalVM 版本前的 reviewer checklist
Then  含「查 oracle/graal#5672 + spring-data-relational#2186 狀態，若 fixed 評估拔 IntegerToBooleanConverter」
```

**驗收命令**：

- AC-2/3：`cd backend && ./gradlew test --tests "*JdbcConfigurationConverterTest*"`（2 個 reflection-based field-type assertion tests 同檔）
- 整體：`./scripts/verify-all.sh`（V01-V08 gate）
- AC-4：手動 deploy + 截圖 Network 200 / 頁面有 skill 列表 / log 無 stacktrace（3 截圖貼進 spec §7 result）— **Round 2 必跑**
- AC-5/6：`grep` architecture.md / development-standards.md 確認字串（Round 1 已 ship 不變）

---

## 4. Interface / API Design

### 4.1 IntegerToBooleanConverter

```java
package io.github.samzhu.skillshub.shared.persistence;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public static final class IntegerToBooleanConverter implements Converter<Integer, Boolean> {

    @Override
    public Boolean convert(Integer source) {
        return source != null && source != 0;
    }
}
```

放在 `JdbcConfiguration.java` 內為 inner static class（與 `MapToPGobjectConverter` / `PGobjectToMapConverter` 同位置慣例）。

**為何 nullable Integer 也要處理**：雖然 `users.contact_email_public NOT NULL`，但 converter 是全域型別配對 — 未來 nullable BOOLEAN column 走同路徑時 null-safe 不炸。

### 4.2 JdbcConfiguration.userConverters() 加項

**現況**（`JdbcConfiguration.java:61-68` 既有）：

```java
@Override
protected List<?> userConverters() {
    return List.of(
        new MapToPGobjectConverter(objectMapper),
        new PGobjectToMapConverter(objectMapper),
        new StringListJsonbConverter.Writing(objectMapper),
        new StringListJsonbConverter.Reading(objectMapper)
    );
}
```

**修改後**：

```java
@Override
protected List<?> userConverters() {
    return List.of(
        new MapToPGobjectConverter(objectMapper),
        new PGobjectToMapConverter(objectMapper),
        new StringListJsonbConverter.Writing(objectMapper),
        new StringListJsonbConverter.Reading(objectMapper),
        new IntegerToBooleanConverter()  // S168 — GraalVM native image MethodHandle workaround
    );
}
```

### 4.3 No public API impact

純 Spring infrastructure 註冊。下游 entity / service / repository / DTO 程式碼**零改動**。

### 4.4 Regression test sketch（AC-2/3）

Test 不啟整個 Spring Boot context，只載 `JdbcConfiguration` 拿 `MappingJdbcConverter` bean，餵 in-memory synthetic Row（不需 DB / Testcontainers）。

```java
@SpringJUnitConfig(JdbcConfiguration.class)
class JdbcConfigurationConverterTest {

    @Autowired MappingJdbcConverter converter;

    @Test
    void integerToBooleanConverter_logic() {
        var c = new JdbcConfiguration.IntegerToBooleanConverter();
        assertThat(c.convert(0)).isFalse();
        assertThat(c.convert(1)).isTrue();
        assertThat(c.convert(null)).isFalse();
        assertThat(c.convert(2)).isTrue();
    }

    @Test
    void whenIntegerInBooleanColumn_ConverterRecoversBoolean_User() {
        // 模擬 GraalVM bug input：JDBC 應回 Boolean，但 corrupt 成 Integer
        var doc = new RowDocument(Map.of(
            "id", "u_test01",
            "oauth_provider", "google", "sub", "t",
            "email", "t@t", "handle", "t",
            "contact_email_public", Integer.valueOf(0),  // ← bug input
            "created_at", Instant.EPOCH, "last_seen_at", Instant.EPOCH
        ));

        User user = converter.read(User.class, doc);

        assertThat(user.isContactEmailPublic()).isFalse();
        // 重複 with Integer(1) → assertTrue
    }

    @Test
    void whenIntegerInBooleanColumn_ConverterRecoversBoolean_NotificationPreference() {
        var doc = new RowDocument(Map.of(
            "user_id", "u_test01",
            "flags_enabled", Integer.valueOf(1),
            "reviews_enabled", Integer.valueOf(0),
            "requests_enabled", Integer.valueOf(0),
            "versions_enabled", Integer.valueOf(1)
            // + version / 其他必填
        ));

        var pref = converter.read(NotificationPreference.class, doc);

        assertThat(pref.isFlagsEnabled()).isTrue();
        assertThat(pref.isReviewsEnabled()).isFalse();
        // ...
    }
}
```

**`RowDocument`**（POC 驗證 / spring-data-relational 4.0.5）：`org.springframework.data.relational.domain.RowDocument`（line 37），實作 `Map<String, Object>`，public 建構子 `new RowDocument(Map<String, Object>)` 接 Map 直接組 row；`MappingRelationalConverter.read(Class, RowDocument)`（line 317）為主 read API。`MappingJdbcConverter` 繼承同 API。

**為何不用 Testcontainers**：JDBC driver 在 JVM mode 下回正常 Boolean，無法 reproduce GraalVM bug；純 in-memory Row 注入 Integer 才能模擬 corrupt input。

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfiguration.java` | modify | 加 `IntegerToBooleanConverter` inner static class（@ReadingConverter）+ 加進 `userConverters()` list（line 67 後新增 1 項） |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfigurationConverterTest.java` | new | 4 個 @Test：(1) `IntegerToBooleanConverter.convert(0/1/null/2/-1)` 邏輯 — AC-1；(2-3) `whenIntegerInBooleanColumn_ConverterRecoversBoolean_User_false/true` 餵 `RowDocument` 給 `JdbcConverter.read(User.class, doc)` 模擬 GraalVM bug 輸入 — AC-2；(4) 同手法對 NotificationPreference 4 boolean field — AC-3。**[Implementation note]** Test 改 extend 既有 `RepositorySliceTestBase`（@DataJdbcTest + Testcontainers + 共用 context cache），不採原規劃的 `@SpringBootTest(classes = JdbcConfiguration.class)` 路徑 — 改採理由詳 §7 Key Findings #4 |
| `docs/grimo/architecture.md` | modify | 修「Production deploy mode：JVM buildpack」→「GraalVM native image（Paketo native-image buildpack 自動觸發）」；GraalVM AOT Strategy 段 (a) 子段加 S168 reference — 涵蓋 AC-5 |
| `docs/grimo/development-standards.md` | modify | 加 review checkpoint：「升 Spring Boot / GraalVM 版本時必查 oracle/graal#5672 + spring-data-relational#2186 狀態，fixed 後評估拔 `IntegerToBooleanConverter`」 — 涵蓋 AC-6 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 S168 row 到 Active 區（in-design → ship 後 ✅ vN.NN.0） |

**未動的檔（明確說明）**：
- `User.java` — 不改 field 型別（avoid Approach C）
- `NotificationPreference.java` — 同上
- 任何 entity / migration — 不需 schema / type 改動
- `cloudbuild.yaml` / `service.yaml` — 部署配置不變
- 既有 native image config（`ScoreNativeConfig.java`）— 不動 `@RegisterReflectionForBinding`，本 bug 跟 reflection metadata 無關

---

## 6. Task Plan

POC: required（test infra API 驗證）— **PASS**：`org.springframework.data.relational.domain.RowDocument` + `MappingRelationalConverter.read(Class, RowDocument)` 確認存在於 spring-data-relational 4.0.5 sources（line 37 / line 317）。Spec §4.4 原寫 `MapBackedRow` 已修正為 `RowDocument`。

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | 加 `IntegerToBooleanConverter` inner class + 註冊到 `userConverters()` + AC-1 converter 邏輯 unit test | AC-1 | ✅ PASS |
| T02 | 加 `whenIntegerInBooleanColumn_ConverterRecoversBoolean_User_false/true` 測試 — `RowDocument` 餵 Integer 給 `JdbcConverter.read(User.class, doc)`，斷言不拋 IAE + boolean 值正確（兩 case）。**Final**：Test extends `RepositorySliceTestBase` 拿 `JdbcConverter` bean（per §7 Key Findings #4 路徑修正） | AC-2 | ✅ PASS |
| T03 | 加 NotificationPreference 同手法測試（4 boolean field）— 證明全域 converter 涵蓋多 entity | AC-3 | ✅ PASS |
| T04 | Doc sync — 修 architecture.md GraalVM AOT Strategy (a) 段「Production deploy mode」(JVM → native image) + 加 S168 cross-reference；development-standards.md 加 review checkpoint「升 Spring Boot / GraalVM 版本時必查 oracle/graal#5672 + spring-data-relational#2186」 | AC-5, AC-6 | ✅ PASS |

**Execution order**: T01 → T02 → T03 → T04（T01 是基礎；T02/T03 同 test 檔可實作時合併但分開驗收以維持 AC trace）

**AC-4 manual deploy**：不對應任何 task — 等 4 個 task 全 PASS + `/shipping-release` 部署到 LAB 後手動驗收（截圖 3 張：頁面有 skill 列表 / Network GET /api/v1/skills 200 / Cloud Run log 無 IAE stacktrace），evidence 寫進 §7。

### POC Findings

**Validated**:
- `org.springframework.data.relational.domain.RowDocument`（spring-data-relational 4.0.5）— 公開 class，建構子 `new RowDocument(Map<String, Object>)` + factory `RowDocument.of(field, value).append(...)`，實作 `Map<String, Object>` 介面
- `MappingRelationalConverter.read(Class<R> type, RowDocument source)` — `MappingRelationalConverter.java:317` 主 read API，`MappingJdbcConverter` 繼承
- `JdbcConfiguration.java` 已 extend `AbstractJdbcConfiguration` + 已 override `userConverters()`（line 61-68），加項零 friction

**Hypothesis 已 resolve（T02 實作階段）**:
- ~~`@SpringBootTest(classes = JdbcConfiguration.class)` slice 路徑~~ → 改採既有 `RepositorySliceTestBase`（@DataJdbcTest + Testcontainers + 共用 context cache）。理由：(a) base class 已驗證解過 Modulith AOT blocker；(b) Spring TestContext cache 與其他 REPO test 共用，無額外啟動成本；(c) 拿到 Spring 真實組裝的 `JdbcConverter` bean，免手動 ctor `MappingJdbcConverter`（需 `RelationResolver` mock + `JdbcTypeFactory.unsupported()` 等樣板）。詳 §7 Key Findings #4。

**Gotchas**:
- Spec §4.4 sketch 原寫 `MapBackedRow.of(...)` — 該 class **不存在**於 spring-data-relational 4.0.5；正確 API 是 `new RowDocument(Map.of(...))`。已修正 §4.4 + 本 task plan。

---

## 7. Implementation Results

### Verification

| Check | Result | Detail |
|---|---|---|
| `./gradlew test compileTestJava` 全 suite | ✅ exit 0 | 123 test files / **723 tests / 0 failures / 0 errors**；BUILD SUCCESSFUL 3m 54s |
| `JdbcConfigurationConverterTest` (4 tests) | ✅ 4/4 PASS | AC-1 0.003s + AC-2 ×2 (false/true) 0.004s+0.003s + AC-3 0.085s |
| Regression detection 反向驗證 | ✅ Confirmed | 暫拔 `IntegerToBooleanConverter` → 3/4 fails with `ConverterNotFoundException: No converter found capable of converting from type [java.lang.Integer] to type [boolean]`（AC-1 pure unit 仍 PASS）→ restore 後 4/4 GREEN。test 真的能抓 regression 不是 false-green |
| Doc grep gates (AC-5/6) | ✅ 5/5 | `"GraalVM native image"`×3 in architecture.md / `"S168"`×2 / `"JVM buildpack（非 native-image）"`=0（stale text 拔除）/ `"oracle/graal#5672"`×2 in development-standards.md / `"Upstream Issue Tracking"`×1 |

### Pending Verification

| AC | Status | 待執行命令 / 證據蒐集 |
|---|---|---|
| AC-4 manual deploy `/browse` | ⏳ pending — 等 `/shipping-release` | (1) ship 後 `gh run watch` Cloud Build 完成；(2) Cloud Run revision auto-update；(3) 開 https://skillshub-644359853825.asia-east1.run.app/browse + Google OAuth 登入；(4) 截 3 張：頁面 skill 列表 / Network GET /api/v1/skills 回 200 / `gcloud logging read` 同 revision 區段查無 `IllegalArgumentException.*contactEmailPublic` stacktrace；(5) 截圖貼進此 §7 並把本 row 改 ✅ |

### Key Findings

1. **GraalVM bug 性質確認**（research agent 1）：oracle/graal#5672 / spring-data-relational#2186 / spring-data-mongodb#5101 三 issue 同 stacktrace 同 root cause（GraalVM SubstrateVM MethodHandle adaptation 把 Boolean corrupt 成 Integer）；GraalVM 端 open，Spring 端 close as `for: external-project` — 上游處理停滯，自家 workaround 是唯一路徑。Approach A（pgjdbc reachability metadata）被 research 排除為「bug 不在 pgjdbc，加 metadata 沒用」。

2. **Converter scope 全域驗證**：同一 `IntegerToBooleanConverter` bean 涵蓋 User.contactEmailPublic + NotificationPreference 4 boolean field — JVM 模式 4 test 全 PASS；regression 反向驗證 3 個 fail with 同 `ConverterNotFoundException` 證明 type-pair (Integer, Boolean) HashMap lookup 在 mapping pipeline 對所有 entity primitive boolean field 一致觸發（不是只 patch User）。

3. **Spec §4.4 sketch 修正（POC 階段）**：原寫 `MapBackedRow.of(...)` 不存在於 spring-data-relational 4.0.5；正解是 `new RowDocument(Map.of(...))`（`org.springframework.data.relational.domain.RowDocument:37`）+ `MappingRelationalConverter.read(Class, RowDocument):317`。Spec §4.4 已修。

4. **Test infra 路徑選擇（T02 實作階段）**：spec §6 原 hypothesis「`@SpringBootTest(classes = JdbcConfiguration.class)` slice 拿 converter bean，無需 Testcontainers DB」改採既有 `RepositorySliceTestBase`（`@DataJdbcTest` + Testcontainers + 共用 context cache）— 理由：(a) 該 base class 已驗證可用 + 解過 Modulith AOT blocker；(b) Spring TestContext cache 與其他 REPO test 共用無額外啟動成本；(c) 拿到 Spring 真實組裝的 `JdbcConverter` bean 比手動 ctor `MappingJdbcConverter`（需 `RelationResolver` mock + `JdbcTypeFactory.unsupported()` 等樣板）涵蓋面廣。`docs/grimo/specs` §6 task plan 已 reflect 此選擇；§4.4 sketch 仍保留為「設計意圖描述」與實作差一個 base class extension 但 ground 在同 `JdbcConverter.read` API。

5. **Architecture.md staleness 同 commit 修正**：原 `### (a) Production deploy mode：JVM buildpack（非 native-image）` 與 Cloud Run 實況（user 確認 + stacktrace 顯示 `com.oracle.svm.core.reflect.fieldaccessor.UnsafeBooleanFieldAccessorImpl` = SubstrateVM = GraalVM native runtime）矛盾。T04 重寫 (a)/(e) 兩段 + Reviewer 自檢 4 條問答對齊 native production reality；同段加 oracle/graal#5672 + S168 workaround 表為 navigation hub。

6. **Bug-to-test trace clarity**：T02 命名 `whenIntegerInBooleanColumn_ConverterRecoversBoolean_User_false/true` + Javadoc `@see https://github.com/oracle/graal/issues/5672` — reviewer 一秒看出 test 在防什麼上游 bug；發現上游 fix 後可直接 grep test name 找回該 test 評估是否拔。

### Correct Usage Patterns

**註冊 Spring Data JDBC 4.x custom converter**（推薦 `userConverters()` over deprecated `jdbcCustomConversions()`）：

```java
@Configuration
public class JdbcConfiguration extends AbstractJdbcConfiguration {
    @Override
    protected List<?> userConverters() {
        return List.of(
            ...,
            new IntegerToBooleanConverter()  // S168
        );
    }

    @ReadingConverter
    public static final class IntegerToBooleanConverter
            implements Converter<Integer, Boolean> {
        @Override
        public Boolean convert(Integer source) {
            return source != null && source != 0;
        }
    }
}
```

**Test 走真實 Spring `JdbcConverter` bean 餵 synthetic `RowDocument`**（不需 manual `MappingJdbcConverter` ctor）：

```java
class SomeConverterTest extends RepositorySliceTestBase {
    @Autowired private JdbcConverter jdbcConverter;

    @Test
    @DisplayName("AC-N: ...")
    @Tag("AC-N")
    void test() {
        var doc = new RowDocument(Map.of(
            "some_boolean_col", Integer.valueOf(1),  // 模擬 GraalVM corrupt
            // ...其他必填欄位
        ));
        var entity = jdbcConverter.read(SomeEntity.class, doc);
        assertThat(entity.isSomeBooleanField()).isTrue();
    }
}
```

### AC Results

| AC | Result | Notes |
|---|---|---|
| AC-1 | ✅ PASS | `IntegerToBooleanConverter.convert(0/1/null/2/-1)` → `false/true/false/true/true`，5 assertions all green |
| AC-2 | ✅ PASS | User.contactEmailPublic 兩 case (Integer 0/1) 透過 `JdbcConverter.read` 正確回 false/true，無 IAE；regression 反向驗證 ✅ |
| AC-3 | ✅ PASS | NotificationPreference 4 boolean fields (Integer 1/0/0/1) 同樣不炸；4 個 `isEnabled(...)` getter 值對應正確；證明 converter 全域 scope |
| AC-4 | ⏳ Pending | 等 `/shipping-release` 部署 + manual deploy 截圖 — 真實 GraalVM native image runtime 才能驗證 oracle/graal#5672 corrupt path 已被 converter 攔截 |
| AC-5 | ✅ PASS | architecture.md GraalVM AOT Strategy (a) 整段重寫；(e) 段從「升級路徑」改為「降回 JVM 路徑」；Reviewer 自檢 Q1 答案改 native + 新加 Q4 native bug + workaround；無 stale `JVM buildpack（非 native-image）` 殘留 |
| AC-6 | ✅ PASS | development-standards.md 新增「Upstream Issue Tracking」整段：3-row 表（oracle/graal#5672 / spring-data-relational#2186 / cyclonedx#821）+ 檢查時機 + `gh issue view` 範例命令 |

---

### QA Review

**Reviewer**: independent subagent
**Date**: 2026-05-11
**Verdict**: PASS

**Independently verified**:
- [full test suite] `./gradlew test compileTestJava` → BUILD SUCCESSFUL, 723 tests, 0 failures, 0 errors (exit 0). Matches spec §7 claim of 723 tests.
- [AC-1 trace] `JdbcConfigurationConverterTest.java:51-61` — `@DisplayName("AC-1: ...")` + `@Tag("AC-1")` present; 5 assertions (0→false, 1→true, null→false, 2→true, -1→true). Spec §3 listed 4 cases; extra `-1` is an improvement, not a gap.
- [AC-2 trace] Two test methods lines 63-86, both `@Tag("AC-2")` — false and true cases separately. Both use `userRowDoc()` helper with `Integer.valueOf(0/1)` injected into `contact_email_public`. XML result: 0 failures.
- [AC-3 trace] `@DisplayName("AC-3: ...")` + `@Tag("AC-3")` at line 89. `RowDocument` with 4 boolean fields as Integer(1/0/0/1); 4 `isEnabled()` assertions. XML result: 0 failures.
- [AC-4] ⏳ Pending — accepted per spec (manual deploy at ship time).
- [AC-5] `grep` confirmed: "GraalVM native image" ×3 in architecture.md, "S168" ×2, "oracle/graal#5672" ×2, zero occurrences of stale "JVM buildpack（非 native-image）".
- [AC-6] `grep` confirmed: "Upstream Issue Tracking" section exists at line 251 in development-standards.md with 3-row table (oracle/graal#5672 / spring-data-relational#2186 / CycloneDX/cyclonedx-gradle-plugin#821).
- [production code] `JdbcConfiguration.java` line 141: `@ReadingConverter public static final class IntegerToBooleanConverter implements Converter<Integer, Boolean>`. `userConverters()` line 75 includes `new IntegerToBooleanConverter()` with spec-correct comment. `convert()` implementation `source != null && source != 0` matches spec §4.1 exactly.
- [regression detection] Temporarily removed `new IntegerToBooleanConverter()` from `userConverters()`, ran `./gradlew test --tests "*JdbcConfigurationConverterTest*"` → exactly 3 failures with `org.springframework.core.convert.ConverterNotFoundException` (AC-2 false, AC-2 true, AC-3); AC-1 (pure unit) still PASS. Restored converter; confirmed 4/4 green.
- [design drift §2.4] Approach B confirmed: converter registered via `userConverters()` override, not `jdbcCustomConversions()`. Matches spec §2.5 API choice.
- [§4.4 RowDocument] Spec §4.4 sketch uses `new RowDocument(Map.of(...))` — no `MapBackedRow` anywhere in §4.4 body. Correction documented in §6 POC Findings and §7 Key Finding #3.
- [code quality] Javadoc on `IntegerToBooleanConverter` accurately describes root cause (oracle/graal#5672), mechanism (mapping pipeline interception before AOT accessor), and scope (global all entities). Inline comment on `convert()` explains null-safe rationale. Compliant with CLAUDE.md "Spec-Linked Rationale" convention.
- [test infrastructure] Test extends `RepositorySliceTestBase` (`@DataJdbcTest` + Testcontainers) — correctly documented in §7 Key Finding #4 as an implementation deviation from the `@SpringBootTest` hypothesis in §6.

**Findings**:
- [MINOR] §6 task plan T01–T04 statuses remain "pending" — they were not updated to "done" when tasks completed. The actual results are correctly reflected in §7 AC Results. No functional impact.
- [MINOR] §5 File Plan and §6 T02 still reference `@SpringBootTest(classes = JdbcConfiguration.class)` — the actual implementation uses `RepositorySliceTestBase`. §7 Key Finding #4 documents the change and claims "§6 task plan 已 reflect 此選擇" which is not fully accurate (§6 T02 text was not updated). The implementation itself is correct and stronger (real Spring context vs. sliced context). No functional impact.

**Recommendation**: ship

---

## 7.5 Round 2 — Post-ship Bug + Approach C Fix

### Post-ship bug discovery

**Date**: 2026-05-11
**Trigger**: User manual test on LAB after Round 1 v4.48.0 deploy
**Cloud Run revision**: `skillshub-00018-czg` (含 Round 1 commit aad2dc6)
**Symptom**: 同 Round 1 完全相同 — `/browse` 回 400「載入技能失敗，請重新整理頁面」
**Stacktrace** (`gcloud logging read` 03:44:29.082)：

```
java.lang.IllegalArgumentException: Can not set boolean field 
  io.github.samzhu.skillshub.shared.security.User.contactEmailPublic to java.lang.Integer
  at UnsafeBooleanFieldAccessorImpl.set:95
  at User__Accessor_irgfaq.setProperty(Unknown Source)
  at ConvertingPropertyAccessor.setProperty:62  ← 有經過但短路
  at MappingRelationalConverter.readProperties:592
  at UserRepositoryImpl__AotRepository.findByOauthProviderAndSub:46
  ... 完全同 Round 1 ship 前 stacktrace ...
```

→ Round 1 fix 沒生效，IntegerToBooleanConverter 是 dead code。

### Root cause re-analysis (root-cause-debugging skill + 3 research agents)

**真根因（3 lines of evidence cross-cited）**：

1. **Spring Data source 短路**（`spring-data-commons/ConvertingPropertyAccessor.java:106-112`）：
   `ClassUtils.isAssignable(boolean.class, Boolean.class) = true` → conversion service **never invoked** for Boolean → boolean primitive readback path → IntegerToBooleanConverter dormant。

2. **JVM test 是 false-positive**：Round 1 test 手動餵 Integer 到 RowDocument，那條 conversion path 跟 prod 完全脫鉤（prod path source 是 Boolean，不是 Integer）。Test PASS 證明的東西跟「production 是否修好」無關。Regression detection 反向驗證也是同人造路徑 — 也只證明「拔掉手動 Integer → 找不到 converter」，跟 prod 路徑無關。

3. **GraalVM source 證實 fix 機制**：
   - `UnsafeBooleanFieldAccessorImpl.set(Field, Object)` 顯式 `instanceof Boolean` check，非 Boolean 拋 IAE
   - `UnsafeObjectFieldAccessorImpl.set(Field, Object)` 只查 `field.getType().isInstance(value)`，wrapper Boolean field 收 Boolean value 通過
   - AOT-generated `unreflectSetter` 對 `private boolean field` 產生 `(User, boolean)V` MethodHandle → SubstrateVM 加 unboxing adapter（corrupt 點）
   - 對 `private Boolean field` 產生 `(User, Boolean)V` 純 reference cast → 無 unboxing → 不踩 corrupt path

**Production-shipped precedent**（不是純理論）：[JobRunr PR #1501](https://github.com/jobrunr/jobrunr/pull/1501) 完全同 stacktrace 同根因，1 行 `private boolean → private Boolean` shipped in v8.5.0。

### Round 2 changes

| File | Round 2 change |
|---|---|
| `User.java:62-72` | `private boolean contactEmailPublic` → `private Boolean contactEmailPublic` + Javadoc cite oracle/graal#5672 + JobRunr PR #1501 |
| `NotificationPreference.java:31-38` | 4 個 `boolean` → `Boolean` (`flagsEnabled` / `reviewsEnabled` / `requestsEnabled` / `versionsEnabled`) + inline comment |
| `JdbcConfiguration.java` | **拔** `IntegerToBooleanConverter` inner class + 從 `userConverters()` 移除（dead code per Spring source 短路證實） |
| `JdbcConfigurationConverterTest.java` | 重寫：拔 Round 1 的 4 個 false-positive tests；改為 2 個 reflection-based field-type assertion regression guards（assert User.contactEmailPublic + NotificationPreference 4 fields 皆為 Boolean.class）|

### Round 2 verification

| Check | Result | Detail |
|---|---|---|
| `./gradlew compileJava compileTestJava` | ✅ exit 0 | wrapper 改動 + getter auto-unbox 無 ripple |
| `./gradlew test --tests "*JdbcConfigurationConverterTest*"` | ✅ 2/2 PASS | AC-2 + AC-3 reflection assertions 0.071s 全綠 |
| `./gradlew bootBuildImage` (本機 Paketo native-image build) | ✅ BUILD SUCCESSFUL 2m 46s | 302 MB native image 產出 — 跟 prod 同 build path |
| `verify-all.sh` (V01-V07 全 suite + V08a processAot) | ✅ exit 0 | Counts: PASS=7, FAIL=0, SKIP=1（V08b SKIP_NATIVE=1，已 manual bootBuildImage 證實 PASS） |
| AC-4 manual deploy `/browse` | ⏳ Pending Round 2 deploy | Round 2 ship 後重驗 |

### Round 2 Honest assessment of remaining risk

- ✅ Source code 證實機制（Spring 短路 + GraalVM accessor 行為）
- ✅ JobRunr PR #1501 production-shipped fix 同 stacktrace 同根因
- ✅ AOT-generated bytecode 從 `(Entity, boolean)V` 變 `(Entity, Boolean)V` — 走完全不同 GraalVM accessor 路徑
- ⚠️ **未在 native runtime 直接 reproduce 驗證**：local docker run 試了但 hit 多個 unrelated infra issues（GenAI api-key + storage path AccessDenied + OAuth callback 等），跟 fix 本身無關。User 同意 Option B：信任 evidence chain ship；若再 fail 同 stacktrace = fix 仍錯（極低機率，因機制已 ground 在 source + production proof），失敗模式可控（已知失敗）

### Round 2 lessons learned (寫進 §2.8 + future spec reference)

1. **Research agent verdict 採信前必讀短路條件**：agent 給「approach X 在 Y 之前攔截」結論時，要追到 source code 看 Y 之前是否有 `isAssignable` / `instanceof` / null check 等短路 — 不能只看 issue 標題 + API 名稱。Round 1 漏這步直接踩雷。
2. **JVM mode test 對 native runtime bug 是 false-positive 高風險區**：bug 只在 native runtime 出現的場景，JVM test 必須能 reproduce **同 stacktrace**才 ground 在真實 path。憑空餵 mock input 證明 converter 工作，跟 prod 是否修好無關。
3. **「Test PASS = ship-ready」反例**：本 spec Round 1 是 723 全 suite green + 4/4 targeted PASS + QA subagent PASS，仍然 ship 一個 dead-code fix。Test infrastructure 信心不等於 production fix 信心 — 必須額外驗證 fix 機制真的 ground 在 prod path。

### Round 2 AC Results

| AC | Round 1 result | Round 2 result | Notes |
|---|---|---|---|
| AC-1 | ✅ PASS (Round 1) | ⛔ Dropped | Converter 已拔，AC 不適用 |
| AC-2 | ✅ PASS (Round 1, false-positive) → ⛔ Invalidated by Round 2 root-cause re-analysis | ✅ PASS (Round 2) | New: reflection field-type assertion (`User.contactEmailPublic.getType() == Boolean.class`) |
| AC-3 | ✅ PASS (Round 1, false-positive) → ⛔ Invalidated | ✅ PASS (Round 2) | New: 4 NotificationPreference field-type assertions |
| AC-4 | ⏳ Pending → ❌ Failed (Round 1 ship 後重現同 stacktrace) | ⏳ Pending Round 2 ship | manual deploy 必須跑 |
| AC-5 | ✅ PASS (Round 1) | ✅ PASS (unchanged) | architecture.md 已對齊 native image |
| AC-6 | ✅ PASS (Round 1) | ✅ PASS (unchanged) | development-standards.md Upstream Tracking 已加 |

