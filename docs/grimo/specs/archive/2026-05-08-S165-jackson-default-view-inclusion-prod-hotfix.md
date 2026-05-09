# S165 — Jackson default-view-inclusion prod hotfix

> **Status**: 📐 in-design → 🚧 implementing
> **Size**: XS(2)
> **Trigger**: prod `/api/v1/skills` 回傳 `{}`（v4.33.0 S158 部署後）
> **Severity**: P0 — production list endpoint 完全壞掉
> **Related**: S158（觸發來源）, S158b（detail 端後續 owner-conditional 設計）

---

## §1 Goal

**一句話**：Spring Boot 4 / Jackson 3 預設 `DEFAULT_VIEW_INCLUSION=false`，導致 S158 加在 `SkillQueryController.search()` 上的 `@JsonView(Skill.Views.List.class)` 一啟用就把 `Page<Skill>` wrapper 全部欄位（`content` / `totalElements` / `pageable` / ...）也濾掉，prod 端 `/api/v1/skills` 回應變成 `{}`。本 spec 在 `application.yaml` 加 `spring.jackson.mapper.default-view-inclusion: true`，恢復 S158 設計預期的「opt-in 隱藏」語義。

**症狀重現**（2026-05-08 LAB）：

```bash
curl https://skillshub-644359853825.asia-east1.run.app/api/v1/skills
# → HTTP 200 application/json
# → body: {}        ← 應該是 { content: [...], totalElements: ..., pageable: ... }
```

gcloud log 確認：

```
GET 200 /api/v1/skills
DEBUG SkillQueryService : 技能搜尋完成      ← query 跑完，DB up
→ Jackson 序列化把整個 wrapper 倒掉
```

---

## §2 Approach

### Root cause

Jackson `DEFAULT_VIEW_INCLUSION` 兩種語義：

| Setting | 啟用 view 後欄位行為 |
|---|---|
| `true` （opt-out） | 未標 `@JsonView` 的欄位 → **任何 view 下都可見**；只有標了非匹配 view 的欄位被排除 |
| `false`（opt-in，Jackson 3 預設） | 未標 `@JsonView` 的欄位 → **任何 view 下都不可見**；只有標了匹配 view（或其子 view）的欄位才出現 |

S158 的 Skill 類別只標了 `@JsonView(Views.Detail.class)` 在 `aclEntries` / `ownerId` 兩欄位上，意圖是「list view 隱藏這兩欄、其他全留」。這個語義依賴 `default-view-inclusion=true`。

S158 design 文件 / `SkillJsonViewTest.java:27` 註解都明確寫「對齊 Spring Boot 預設行為：default-view-inclusion=true」— **但 Spring Boot 4 / Jackson 3 不再預設開這個 flag**，需要應用層在 `spring.jackson.mapper.default-view-inclusion` 顯式設 `true`（注意：Spring Boot 4 的 `MapperFeature` 屬性 namespace 從 `spring.jackson.*` 改為 `spring.jackson.mapper.*` — 詳 §2 Fix block 註解）。

S158 spec 漏寫 application.yaml 修改步驟 → ship 後 unit test 綠（test 用獨立 `new ObjectMapper()`，不走 Spring auto-config）但 prod 壞。

### Fix

新增 `shared.api.JacksonConfiguration` 提供 `JsonMapperBuilderCustomizer` bean，明確 enable `MapperFeature.DEFAULT_VIEW_INCLUSION`：

```java
@Configuration
public class JacksonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer enableDefaultViewInclusion() {
        return builder -> builder.enable(MapperFeature.DEFAULT_VIEW_INCLUSION);
    }
}
```

**為什麼用 bean 不用 yaml property**：

1. **第一版誤判 property 名**：原本走 yaml `spring.jackson.default-view-inclusion: true` 是 Jackson 2 / Spring Boot 3 寫法；Spring Boot 4 / Jackson 3 把所有 `MapperFeature` 移到 `spring.jackson.mapper.<feature_name>` namespace（per [Spring Boot 4 docs — Customize Jackson JsonMapper](https://docs.spring.io/spring-boot/how-to/spring-mvc.html#howto.spring-mvc.customize-jackson-jsonmapper)）。改成 `spring.jackson.mapper.default-view-inclusion: true` 在 full `@SpringBootTest` 通過 — yaml path **可以**運作。
2. **改 bean 的真正動機**：`@WebMvcTest` slice 不掃任意 `@Configuration`，但 yaml property 經 Boot 的 `JacksonAutoConfiguration` → `StandardJsonMapperBuilderCustomizer` 套用，所以 yaml path 在 slice 也能生效（理論上）。但實測 slice test `Page<Skill>` 仍被序列成 `{}` — 推測 slice 的 jackson auto-config 應用順序與 prod 有差異或 application.yaml 沒被完整 merge。改成明確的 `JsonMapperBuilderCustomizer` bean + 把 `JacksonConfiguration` 加進 `WebMvcSliceTestBase` 的 `@Import` 清單，bean 能在 prod 與 slice test 都顯式註冊，繞開 property binding 路徑。
3. **single source of truth**：bean 是程式碼層面的明確配置；property 路徑容易隨 Boot 版本變動（如本 case Boot 3→4 namespace 改變），bean 不會。

### Why this is the right fix

1. **符合 S158 原始設計意圖** — spec 文件、test 註解都假設此 flag 為 true。
2. **副作用範圍可控** — 只影響有 `@JsonView` 的 controller method（目前只有 `SkillQueryController.search()`）。沒標 view 的 endpoint 行為完全不變（`@JsonView` 不啟用時，`DEFAULT_VIEW_INCLUSION` 不參與序列化決策）。
3. **與既有 unit test 對齊** — `SkillJsonViewTest` 通過的前提就是 `default-view-inclusion=true`；fix 後 production 行為與 unit test 一致，避免「test 綠但 prod 壞」的 mismatch 再次發生。

### Alternatives considered

| 方案 | Pros | Cons | 結論 |
|---|---|---|---|
| **A. `JsonMapperBuilderCustomizer` bean (本案)** | 程式碼層面明確；不依賴 property namespace；slice test 可顯式 import；跨 Spring Boot 版本穩定 | 多一個 config 類別 | ⭐ Recommended |
| A2. yaml `spring.jackson.mapper.default-view-inclusion: true` | 最小 diff（一行 yaml）；對齊 S158 設計意圖 | 在 `@WebMvcTest` slice 沒 reliable 套用（實測 2026-05-08 仍 reproduce）；property namespace 隨 Boot 版本變動風險 | 否 — slice test 不穩定 |
| B. 移除 controller 的 `@JsonView`，改 DTO（Custom `SkillListResponse`） | 顯式、無 Jackson view 機制依賴 | 多一個 DTO 類別 + projection mapper；S158b detail endpoint 計畫也會跟著重做；本 spec 變 M | 否 — 過度修 |
| C. Revert S158（移所有 `@JsonView`） | 立即修 prod | ACL list 端再次曝露 `aclEntries` / `ownerId`（隱私倒退到 v4.32.0） | 否 — 倒退 |
| D. 在 controller 裝 explicit `MappingJacksonValue` + builder filter | 不依賴 global flag | 大幅複雜化；非標準 Spring 用法 | 否 |

---

## §3 Acceptance Criteria

**驗證指令**：`./gradlew test --tests SkillQueryControllerJsonViewIT`（new test class）+ 既有 `SkillJsonViewTest` 全綠 + LAB curl 實測。

**AC-1**: `GET /api/v1/skills` 回應為合法 paginated body（非 `{}`）。

```
Given application.yaml 含 spring.jackson.mapper.default-view-inclusion: true
When  GET /api/v1/skills
Then  HTTP 200 + body 包含 "content" + "totalElements" + "pageable" 三個 wrapper key
And   body.content 是 array
```

**AC-2**: `GET /api/v1/skills` 回應**不含** `aclEntries` / `ownerId`（S158 隱私不變式保留）。

```
Given application.yaml 含 spring.jackson.mapper.default-view-inclusion: true
When  GET /api/v1/skills （any keyword/category/author 組合）
Then  response body string 不含 "aclEntries"
And   response body string 不含 "ownerId"
```

**AC-3**: `GET /api/v1/skills/{id}` 回應**仍含** `aclEntries` + `ownerId`（detail endpoint 未標 view，行為不應受影響）。

```
Given application.yaml 含 spring.jackson.mapper.default-view-inclusion: true
When  GET /api/v1/skills/{existing-id}
Then  HTTP 200 + body 包含 "aclEntries" key
And   body 包含 "ownerId" key
```

**AC-4**: `SkillJsonViewTest`（既有 unit test）三個 case 全綠（regression check — fix 不應動到既有 view 邏輯）。

---

## §4 Interface / Config

### 1. 新增 `shared.api.JacksonConfiguration`

```java
package io.github.samzhu.skillshub.shared.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.MapperFeature;

@Configuration
public class JacksonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer enableDefaultViewInclusion() {
        return builder -> builder.enable(MapperFeature.DEFAULT_VIEW_INCLUSION);
    }
}
```

Production `@SpringBootApplication` 透過 `@ComponentScan` 自動 pick up（`shared.api` package 在 scan path 內）。

### 2. `WebMvcSliceTestBase` 顯式 `@Import(JacksonConfiguration.class)`

`@WebMvcTest` slice 不掃任意 `@Configuration` bean，需顯式 import：

```java
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcSliceTestBase.AotStubBeans.class})
```

### 3. Diagnostic test — `JacksonViewInclusionDiagnosticTest`

新增 `backend/src/test/java/io/github/samzhu/skillshub/shared/api/JacksonViewInclusionDiagnosticTest.java`：full `@SpringBootTest` 直接斷言 runtime `JsonMapper.serializationConfig().isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION)`，作為 regression 守 future Boot/Jackson 版本變動。

---

## §5 File Plan

| # | File | 動作 | 說明 |
|---|---|---|---|
| 1 | `backend/src/main/java/io/github/samzhu/skillshub/shared/api/JacksonConfiguration.java` | A | 新 `@Configuration` bean — `JsonMapperBuilderCustomizer` 顯式 enable `MapperFeature.DEFAULT_VIEW_INCLUSION` |
| 1b | `backend/src/test/java/io/github/samzhu/skillshub/shared/security/WebMvcSliceTestBase.java` | M | `@Import` 加 `JacksonConfiguration.class`（slice 不掃任意 `@Configuration`） |
| 1c | `backend/src/test/java/io/github/samzhu/skillshub/shared/api/JacksonViewInclusionDiagnosticTest.java` | A | full-context regression — runtime 斷言 `DEFAULT_VIEW_INCLUSION=true` |
| 2 | `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerJsonViewIT.java` | A | WebMvcSlice 整合測 — 透過 Spring Boot 的真實 Jackson 設定走端對端 serialization；覆蓋 AC-1 + AC-2 |
| 3 | `docs/grimo/specs/spec-roadmap.md` | M | 加 S165 row + 更新「最後更新」timestamp |
| 4 | `docs/grimo/CHANGELOG.md` | M | v4.41.0 — S165 hotfix entry |
| 5 | （ship 後）`docs/grimo/specs/archive/` | M | 移本 spec 進 archive |

**Out of scope**:
- 不重設計 S158（list 隱藏 ACL 機制不動）
- 不動 S158b（detail endpoint owner-conditional 仍走原 plan）
- 不補其他 controller 的 `@JsonView` — S165 只修 config gap，不擴大 view 使用

---

## §6 Verification（task 完成後填）

> ⏳ pending implementation

---

## §7 Result（task 完成 + ship 後填）

> ⏳ pending implementation
