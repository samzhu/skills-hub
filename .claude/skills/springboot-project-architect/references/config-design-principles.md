# Spring Boot 設定檔設計原則

> **⚠ 版本查證義務：** 本文件的原則是可移植的，但文中出現的**框架屬性路徑**
> （`spring.*`、`management.*` 等）僅為範例說明模式，不代表目標版本的正確路徑。
> 框架主版本升級會 rename / deprecate / 搬移屬性。
> 執行時**必須**查證目標版本的官方 Application Properties 索引：
> https://docs.spring.io/spring-boot/appendix/application-properties/index.html
>
> `{app}.*` 屬性由 `@ConfigurationProperties` 控制，不受框架版本影響。

## 1. 雙層 Profile 設計

Spring Boot 的 profile 可同時啟用多個。將 profile 分成**兩個獨立維度**，用組合的方式覆蓋不同情境：

| 維度 | 職責 | 回答的問題 | 範例 |
|------|------|-----------|------|
| **基礎設施層** | 用哪種底層、開哪些 autoconfig | 「在哪種環境跑？」 | `local`, `gcp`, `aws` |
| **環境行為層** | 這環境是什麼角色（值與行為） | 「這個環境是哪個角色？」 | `dev`, `lab`, `sit`, `uat`, `prod` |

### 組合矩陣

```
啟動指令                              基礎設施    行為    場景
─────────────────────────────────────────────────────────────
spring.profiles.active=local,dev      本機 Docker  DEBUG   日常開發
spring.profiles.active=local,lab      本機 Docker  DEBUG   實驗驗證
spring.profiles.active=lab,gcp        GCP 服務     DEBUG   雲端封測
spring.profiles.active=prod,gcp       GCP 服務     INFO    正式環境
```

### 為什麼不用單一維度？

傳統 `dev/staging/prod` 混合基礎設施與行為。新增基礎設施（如 AWS）就要複製所有行為 profile → N×M 爆炸。雙層設計只需 N + M 個 profile。

### 1.1 Profile 載入順序：behavior 先、infra 後

`spring.profiles.active=lab,gcp` 順序有意義 — 後者覆蓋前者衝突項。**behavior 先載讓基礎設定就位，infra 後載補平台專屬覆蓋**。

```
正確:  spring.profiles.active=lab,gcp     ← behavior 先、infra 後
錯誤:  spring.profiles.active=gcp,lab     ← infra 先載；如果 lab 後來覆蓋掉
                                            gcp 的關鍵 infra（如 sm@ resolver）會壞
```

**為什麼這個順序**：
- behavior profile（dev/lab/prod）定義「這環境的角色行為」— 比較廣
- infra profile（gcp）補「這個基礎設施特有的東西」（autoconfig 開關、`spring.config.import: sm@` 等）— 比較窄、需要 trump
- infra 後載確保平台專屬設定不被環境角色蓋掉

`local,dev` 也遵循此規則（dev 先、local 後；無衝突所以順序影響小，但維持慣例）。

### 1.2 嚴格職責分離：infra ≠ behavior

| Layer | 該放什麼 | 不該放什麼 |
|---|---|---|
| **infra profile**（`local` / `gcp`） | autoconfig 開關、平台能力註冊（`spring.docker.compose.*`、`spring.cloud.gcp.*.enabled`、`spring.config.import: sm@`） | 任何 env-specific 值（DB url、帳密、bucket、issuer-uri、pool-size） |
| **behavior profile**（`dev` / `lab` / `prod`） | env-specific 值、log level、actuator scope、springdoc 開關 | 平台 infra 開關（除非明確只在某 behavior 啟用） |

**反模式**：把 `skillshub.security.oauth.enabled=false` 寫進 `application-local.yaml`（infra 層）。
- 如果有人跑 `local,prod`（在本機測 prod 行為），會誤套 OAuth off。
- 修正：把 OAuth 開關移到 `application-dev.yaml`（behavior 層），語意才對。

## 2. 檔案分層架構

```
src/main/resources/                    ← 打包進 Docker Image（classpath）
├── application.yaml                   ← 共用配置，預設接近正式環境
│                                        spring.profiles.default: local,dev
├── application-local.yaml             ← 本地基礎設施
└── application-{cloud}.yaml           ← 雲端基礎設施（gcp/aws）

config/                                ← 外部配置，不打包進 Image
├── application-dev.yaml               ← 開發行為（DEBUG + import secrets）
├── application-lab.yaml               ← Lab 行為（DEBUG + import secrets）
├── application-prod.yaml              ← 正式行為（INFO + 限縮 actuator）
├── application-secrets.properties     ← 機敏值（不 commit）
└── application-secrets.properties.example ← 範例（commit）
```

### 為什麼分兩個目錄？

- **`src/main/resources/`** — classpath，打包進 jar/Docker Image。放**所有環境共用**的基礎設定。
- **`config/`** — Spring Boot 自動掃描的外部配置目錄，**不進** jar/Image。放**環境行為**和**機敏值**。

### `application.yaml` 接近正式環境的原則（fail-secure 姿態）

`application.yaml` 的預設值應該是**正式環境直接可用且安全**的姿態。開發者需要的鬆綁功能（springdoc、DEBUG log、擴展 actuator、OAuth off）在 `config/application-dev.yaml` 開啟。

**核心精神：base 預設「最嚴」，dev 才往下鬆綁**。不要倒過來（base 鬆、prod 才補嚴）— 一旦漏掉一條 prod override，就有安全 / 行為漏洞。

**實務範例（base 該寫的姿態）：**
- `logging.level.root: INFO`（dev 才轉 DEBUG）
- `springdoc.api-docs.enabled: false`（dev 才開）
- `management.endpoints.web.exposure.include: health,info,metrics`（最小必要；dev 才擴）
- `{app}.security.oauth.enabled: true`（fail-secure；dev 才關走 LAB 分支）
- 功能性開關（如 LLM scanner）預設 **on**（功能完成正式環境通常會用），dev 想關才在 dev profile override

### 2.1 預設值四層優先級（高 → 低）

理解這個層級才能決定「該不該寫進 yaml」：

```
1. Cloud Run env var / OS env var       ← 最高（部署時注入）
2. spring.config.import 引入的檔案      ← 例：application-secrets.properties
3. config/application-{profile}.yaml    ← 行為 profile（覆蓋 base）
4. classpath:/application-{profile}.yaml ← infra profile
5. classpath:/application.yaml          ← base
6. Java @DefaultValue                   ← 最底（@ConfigurationProperties record 預設）
```

完整順序見 §6。

### 2.2 不重複預設值原則

**跟上層預設一樣的值 — 不寫**。理由：
- 雜訊干擾理解；讀者要分辨「這條是顯式覆蓋還是 redundant 重複」
- 預設變動時你的 yaml 會跟不上（鎖在過時值）
- yaml 越短越容易維護

| 該寫 | 不該寫（redundant） |
|---|---|
| `spring.threads.virtual.enabled: true`（預設 false） | `spring.flyway.enabled: true`（Flyway artifact 在 classpath 即啟用） |
| `spring.web.error.include-message: always`（預設 never） | `spring.flyway.locations: classpath:db/migration`（預設） |
| `springdoc.api-docs.enabled: false`（預設 true） | `logging.level.root: INFO`（預設） |
| `{app}.scanner.engines.llm.enabled: false`（Java default true） | `spring.servlet.multipart.max-request-size: 10MB`（預設） |

**例外**：跟預設一樣但要顯式表達意圖（防止未來誤改）— 加註解說明，否則應刪。

### 2.3 Java `@DefaultValue` 接手原則

`{App}Properties` 的 record 用 `@DefaultValue("...")` 提供 Java 端預設值。**yaml 不要重複寫**：

```java
public record Storage(
    @DefaultValue("{app}-packages") String bucket,         // ← Java default
    @DefaultValue("./storage-local") String localPath
) {}

public record OAuth(@DefaultValue("true") boolean enabled) {}   // ← fail-secure default
```

```yaml
# ❌ 不該寫（跟 Java @DefaultValue 重複）
{app}:
  storage:
    bucket: {app}-packages
  security:
    oauth:
      enabled: true

# ✓ 該寫的場合：覆蓋 Java default 才寫
# config/application-dev.yaml
{app}:
  security:
    oauth:
      enabled: false        # ← Java default true，dev 顯式覆蓋為 false
```

**好處**：base yaml 大幅瘦身，預設值集中在 Java（型別安全 + 文件自動產生 + IDE autocomplete）。

## 3. @ConfigurationProperties 策略（應用程式名稱當前綴）

> 官方文件：「Using the `@Value("${property}")` annotation to inject configuration properties
> can sometimes be cumbersome, especially if you are working with multiple properties
> or your data is hierarchical in nature. Spring Boot provides an alternative method
> of working with properties that lets **strongly typed beans** govern and validate the
> configuration of your application.」
> — https://docs.spring.io/spring-boot/reference/features/external-config.html

**應用程式自訂屬性，統一建立 `{App}Properties` record，以應用程式名稱為前綴（prefix）。**

### 3.1 為什麼用 @ConfigurationProperties 而非 @Value

| 面向 | `@Value` | `@ConfigurationProperties` |
|------|---------|---------------------------|
| 型別安全 | 否（字串轉換在 runtime） | 是（compile-time） |
| 預設值位置 | 分散在各 `@Value` annotation | 集中在 record `@DefaultValue` |
| IDE 支援 | 有限 | 完整（autocomplete + 驗證） |
| 重構 | 各 class 個別修改 | 改一處 record 即可 |
| 結構 | 扁平 | 階層式 nested record |
| Relaxed binding | 需手動寫 placeholder | 自動支援 env var 覆蓋 |

### 3.2 {App}Properties 設計模式

```java
// 放於根 package（與 {App}Application 同層）
// Prefix = spring.application.name（kebab-case）
@ConfigurationProperties(prefix = "{app}")
public record {App}Properties(
    @DefaultValue Storage storage,
    @DefaultValue Search search,
    @DefaultValue GenAI genai        // 選用 section：@DefaultValue 確保不為 null
) {
    /** GCS / 本機儲存設定 */
    public record Storage(
        @DefaultValue("{app}-packages") String bucket,
        @DefaultValue("./storage-local") String localPath
    ) {}

    /** 向量搜尋設定 */
    public record Search(
        @DefaultValue("simple") String vectorStore,
        @DefaultValue("{app}_search") String collection
    ) {}

    /**
     * 外部 API 整合設定（範例：Embedding API，依實際 API 替換）。
     * {@code model} 為固定值（所有環境共用），配有 @DefaultValue。
     * {@code apiKey} 缺席即停用 — 由 @ConditionalOnProperty 控制 bean 是否建立。
     */
    public record GenAI(
        @DefaultValue("your-model-name") String model,  // 依實際 API 替換
        String apiKey
    ) {}
}
```

**`@DefaultValue` 空標注行為（官方文件）：**
> 「To make it contain a non-null instance of `Security` even when no properties are bound
> to it, use an empty `@DefaultValue` annotation.」

- `@DefaultValue Storage storage` → 就算未設 `{app}.storage.*`，`props.storage()` 也不為 null
- `GenAI(String apiKey)` 中 `apiKey` 無 `@DefaultValue` → 未設時為 null，用 `@ConditionalOnProperty` 守門

### 3.3 啟用 @ConfigurationProperties

```java
@SpringBootApplication
@ConfigurationPropertiesScan   // 自動掃描並註冊所有 @ConfigurationProperties
public class {App}Application { ... }
```

或指定掃描範圍：

```java
@ConfigurationPropertiesScan("io.github.example.{app}")
```

### 3.4 YAML 改為純值（消除 ${...} placeholder）

有了 `@ConfigurationProperties` + relaxed binding，YAML **不需要** `${APP_VAR:default}` 格式：

```yaml
# Before（多餘的 placeholder indirection）
{app}:
  storage:
    bucket: ${{app}-storage-bucket:{app}-packages}
  search:
    vector-store: ${APP_SEARCH_VECTOR_STORE:simple}

# After（純值 — relaxed binding 自動支援 env var 覆蓋）
{app}:
  storage:
    bucket: {app}-packages          # {APP}_STORAGE_BUCKET env var 自動覆蓋
  search:
    vector-store: simple            # {APP}_SEARCH_VECTOR_STORE env var 自動覆蓋
    collection: {app}_search
  genai:
    model: your-model-name          # 固定值 — 所有環境共用（依實際 API 替換）
    # api-key 不在此設定 — 缺席即停用（NoOp fallback）
    # 本機：config/application-secrets.properties 注入 {app}.genai.api-key=your-key
    # 雲端：env var {APP}_GENAI_API_KEY（relaxed binding 自動對應）
```

### 3.5 Relaxed Binding 規則

Spring Boot 自動將 SCREAMING_SNAKE_CASE env var 對應到 dot-notation 屬性：

```
SCREAMING_SNAKE env var            → dot-notation property key
─────────────────────────────────────────────────────────────
{APP}_STORAGE_BUCKET               → {app}.storage.bucket
{APP}_SEARCH_VECTOR_STORE          → {app}.search.vector-store
{APP}_GENAI_API_KEY                → {app}.genai.api-key
```

Cloud Run / K8s / 任何環境直接設定 SCREAMING_SNAKE_CASE env var，relaxed binding 自動對應，無需修改任何 YAML。

詳見官方 Relaxed Binding 表格：
https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding

### 3.X base 自包含：placeholder skeleton 模式

當某個結構（如 datasource）跨 env 結構相同、只有具體值差異時，**base 寫 skeleton + placeholder，behavior profile 提供值**。

```yaml
# 概念：base placeholder skeleton
spring:
  datasource:
    url:      ${% raw %}${{app}.db.url}{% endraw %}
    username: ${% raw %}${{app}.db.user}{% endraw %}
    password: ${% raw %}${{app}.db.password}{% endraw %}
    hikari:
      # 跨 env 通用 timing 寫在 base；maximum-pool-size 由各 env override
```

**完整模板**（base + 各 behavior profile 值）見 `references/config-templates.md`「DataSource skeleton 模式」段。

**好處**：
- base datasource 結構穩定（升級框架版本只改一處）
- env profile 只負責「這環境的具體值」— 最小驚奇
- 部署人員只需知道要設哪 3 個變數，不需懂 datasource 結構

**何時用 placeholder skeleton vs 直接寫**：

| 情境 | 建議 |
|---|---|
| 多 env 結構相同、只值不同（datasource、URL params 一致） | **抽變數放 base**（skeleton 模式） |
| 只有單一 env 用、結構獨特（mock-oauth2-server 只在 local Compose 出現） | **直接寫在該 env profile**（不抽變數，避免過度抽象） |

### 3.6 Secrets 檔案使用 dot-notation

```properties
# config/application-secrets.properties
# 使用與 @ConfigurationProperties 屬性路徑一致的 dot-notation

# 選用 API key（存在即啟用真實 bean，缺席即使用 NoOp）
{app}.genai.api-key=AIzaSy...

# 覆蓋 application.yaml 預設值（通常不需要）
# {app}.storage.bucket=my-custom-bucket
```

### 3.7 @ConditionalOnProperty 使用 dot-notation

```java
// 正確：dot-notation 與 @ConfigurationProperties 屬性路徑一致
@Bean
@ConditionalOnProperty(name = "{app}.genai.api-key")
EmbeddingModel realEmbeddingModel({App}Properties props) {
    var apiKey = props.genai().apiKey();   // 從 record 取值，不用 @Value
    ...
}

// 錯誤：flat kebab key（與 @ConfigurationProperties 結構不一致）
@ConditionalOnProperty(name = "{app}-genai-api-key")
EmbeddingModel realEmbeddingModel(@Value("${{app}-genai-api-key}") String apiKey) { ... }
```

> **注意（官方文件）：** `@DefaultValue` 的預設值不反映在 `Environment` 中，
> 因此 `@ConditionalOnProperty` 無法感知 Java record 的預設值 — 兩者各自運作。
> `@ConditionalOnProperty` 直接查詢 `Environment`，與 `@ConfigurationProperties` binding 無關。

## 4. 統一機敏值管理機制

### 4.1 核心精神：同一 property 名跨 env，注入來源不同

**property 名跨 env 統一**（如 `{app}.db.password`），不同環境只是改注入方式：

```
property: {app}.db.password （跨環境同名；應用碼跨 env 一致）

  ┌─ 本機 dev ───────────────────────────────────────────────┐
  │  config/application-secrets.properties                  │
  │    {app}.db.password=secret                             │
  │  (透過 spring.config.import 在 dev/lab profile 引入)    │
  └─────────────────────────────────────────────────────────┘

  ┌─ Cloud Run lab/prod ────────────────────────────────────────┐
  │  service.yaml env var：                                    │
  │    name:  {app}.db.password                                │
  │    value: ${% raw %}${sm@{app}-db-password}{% endraw %}    │ ← 字面字串
  │                                                            │
  │  Spring 啟動時 PropertyResolver 遞迴 resolve：             │
  │    ${% raw %}${{app}.db.password}{% endraw %}              │
  │      ↓ env var 注入字串                                    │
  │    "${% raw %}${sm@xxx}{% endraw %}"                       │
  │      ↓ 遞迴偵測 ${...} 語法                                │
  │    SecretManagerPropertySource 攔截 sm@ 前綴               │
  │      ↓ Secret Manager API                                  │
  │    actual secret value                                     │
  └────────────────────────────────────────────────────────────┘
```

**好處**：
- 應用碼跨 env 一致（`@ConfigurationProperties` / `@Value` 取值不變）
- 切換 env 只是換注入來源，不改 yaml / 不改程式
- 機敏值不進 yaml（不被 git 追蹤、不洩漏）

### 4.2 機敏 vs 非機敏分流

| 類別 | 範例 | 本機 | 雲端 |
|---|---|---|---|
| **機敏值** | DB password、API key、OAuth client secret | `application-secrets.properties`（gitignore） | Secret Manager + 對應雲端整合（如 sm@） |
| **非機敏 env-specific** | DB user、bucket name、URL 字串 | profile yaml 直接寫 | Cloud Run env var 直接注入 literal |

**反模式**：把非機敏值（如 DB user）塞進 Secret Manager — 增加管理成本（一個 secret 一份月費 + IAM 複雜度）但無安全好處。

### 4.3 本地 secrets 引入

```yaml
# config/application-dev.yaml
spring:
  config:
    import: "optional:file:./config/application-secrets.properties"
```

- `optional:` — 檔案不存在不報錯，fallback 到上層預設值
- 只在 `dev`/`lab` profile 引入 — `application.yaml` 不引用本機路徑
- `.gitignore` 排除 `config/application-secrets.properties`
- `.example` 檔案提供模板，新成員照抄即可

### 4.4 雲端注入：env var 直接 + 遞迴 resolve 兩種

**A. 平台 env var mount（直接注入 literal）— 適用一般非機敏 env-specific 值**

```
env var:  {app}.db.user = {app}_app                 ← literal 值
property:    →     {app}.db.user = "{app}_app"
```

K8s `RelaxedEnvironmentVariableValidation`（GA 2025-06-28）允許 env name 含 dot — Cloud Run env name 直接對齊 Spring property 名（命名 1:1）。

**B. Secret Manager 整合（遞迴 resolve）— 適用機敏值**

```
env var:  {app}.db.password = ${% raw %}${sm@{app}-db-password}{% endraw %}    ← 字面字串
property:    →     {app}.db.password = "${% raw %}${sm@xxx}{% endraw %}"
PropertyResolver:    →     遞迴觸發 SecretManagerPropertySource    →     actual secret
```

平台特定整合模式見：
- GCP: `references/cloud-gcp-secrets.md`（Spring Cloud GCP `sm@` 語法 + Cloud Run env var）
- AWS / Azure: 未來如需要建立 `references/cloud-{provider}-secrets.md`

> **原則**：不要在 `application.yaml` 直接寫 `sm@xxx` 這類雲端特定 URI（綁住 base 不可移植）。雲端整合放在雲端 profile（如 `application-gcp.yaml` 啟用 `spring.config.import: sm@` resolver），實際 sm@ URI 透過部署層 env var 注入。

## 5. Starter vs Manual Configuration 判斷原則

### 依賴選擇即意圖宣告（Dependency Choice = Intent Declaration）

Spring 生態系的 artifact 命名不是隨意的 — **artifact 名稱本身就是開發者的配置意圖宣告**。

| Artifact 名稱模式 | 意圖 | 配置方式 |
|-------------------|------|---------|
| `*-starter-*`（含 `starter`） | 讓框架管配置 | 查閱官方文件，透過 `application.yaml` 屬性配置；auto-config 自動建立 bean |
| 不含 `starter`（純 library） | 自己控制配置 | 開發者自行決定如何使用：`@Bean` 註冊、直接在 service 建構、或其他方式 |

**範例：**

```
spring-ai-starter-model-google-genai-embedding    ← starter：讓 auto-config 管理
  → 查官方 Application Properties 文件
  → 在 application.yaml 設定 spring.ai.google.genai.embedding.* 屬性
  → auto-config 自動建立 bean

spring-ai-google-genai-embedding                   ← 純 library：自己控制
  → 查官方 Manual Configuration 文件
  → 自行決定如何建立和使用（@Bean、直接 new、factory 等）
  → 配置值從 {App}Properties 或自行管理
```

**這不限於 Spring AI — 整個 Spring 生態系都遵循此慣例：**

```
spring-boot-starter-data-jpa                       ← starter：auto-config DataSource + EntityManager
spring-data-jpa                                    ← 純 library：自己控制 DataSource 配置

spring-cloud-gcp-starter-storage                   ← starter：auto-config GCS Storage client
google-cloud-storage                               ← 純 library：自己控制 Storage client

spring-boot-starter-webmvc                         ← starter：auto-config DispatcherServlet + 內嵌 Tomcat
spring-webmvc                                      ← 純 library：自己控制 servlet container 配置
```

**為什麼這很重要？**

1. **讀 build file 先於讀 YAML** — 看到 `build.gradle.kts` / `pom.xml` 裡的依賴，就知道原作者的配置意圖。不要用 starter 卻自己建 bean（浪費 auto-config 且可能衝突），也不要用純 library 卻期待 auto-config 生效。
2. **官方文件是必讀的** — 不論選 starter 還是純 library，都要先讀過該 artifact 的官方文件。Starter 看 Application Properties 支援哪些屬性；純 library 看 Manual Configuration / builder / factory API。
3. **切換意圖時要換 artifact** — 如果原本用 starter 但發現 auto-config 不夠用（例如需要條件切換、NoOp fallback），應該把 starter 換成純 library，而不是同時保留 starter 又自己建 bean（造成衝突）。

### 核心問題：dev 環境能不能跑？

選擇 Starter（auto-config）還是 Manual Configuration 的關鍵判斷：

```
這個依賴在 dev 環境能用 Docker Compose / Testcontainers 本地跑？
  ├── 是 → 用 Starter（auto-config）直接整合
  │         例：spring-data-jpa + PostgreSQL container
  │             spring-data-mongodb + MongoDB container
  │             spring-cloud-gcp-starter-storage + GCS emulator
  │
  └── 否（呼叫外部 API、需要真實憑證、無法本地模擬）
      → 用 Manual Configuration（@Bean + @ConditionalOnProperty）
            例：Google GenAI Embedding API（需 API key）
                外部 SaaS API（需付費帳號）
                第三方支付閘道
```

### 判斷決策表

| 依賴類型 | 本地開發支援 | 建議方式 | 理由 |
|---------|------------|---------|------|
| Spring Data JPA + PostgreSQL | Docker Compose + Testcontainers | **Starter** | 本地跑得起來，開發測試都方便 |
| Spring Data MongoDB | Docker Compose + Testcontainers | **Starter** | 同上 |
| Spring Cloud GCP Storage | GCS emulator / FileSystem 替代 | **Starter** + 條件切換 | 有 emulator 或可用 FileSystem fallback |
| Redis / RabbitMQ / Kafka | Docker Compose | **Starter** | 本地容器即可 |
| Google GenAI Embedding API | 無法本地模擬，需真實 API key | **Manual Config** | dev 無法跑，需要 NoOp fallback |
| 第三方支付（Stripe 等） | Sandbox 需 API key | **Manual Config** | dev 不一定有 sandbox 帳號 |
| 外部 AI/LLM API | 需付費帳號 | **Manual Config** | dev 不應被迫設定帳號才能啟動 |

### 處理「dev 跑不了」的決策階梯

當一個依賴在 dev 環境跑不了時（缺憑證、需外部 API），**先徹底閱讀官方文件**，依以下順序評估：

```
Step 1: 官方有 enable/disable 開關？
  └── 是 → 用官方開關
        例：spring.ai.model.embedding.text=none
            spring.cloud.gcp.storage.enabled=false

Step 2: 官方開關不好用或不存在？
  └── autoconfigure.exclude 排除特定 auto-config class
        例：spring.autoconfigure.exclude=...GoogleGenAiEmbeddingConnectionAutoConfiguration

Step 3: 排除後仍不滿足需求（需要 NoOp fallback、條件切換）？
  └── Manual Configuration（@Bean + @ConditionalOnProperty）
        例：API key 存在 → 真實 bean；不存在 → NoOp bean

Step 4: 以上都不行？
  └── 自己設計實作（最後手段）
```

**原則：框架已經想過的問題，不要自己重新發明。**

每一步都需要先讀過官方文件，確認框架有無提供機制，不好用再往下走。直接跳到 Step 3 或 Step 4 是常見的錯誤。

### Manual Configuration 模式（外部 API 類型）

**模式：** `@Bean` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean` + `{App}Properties`

```java
// 以 Embedding API 為例 — 實際 API 依專案需求替換

// 真實 bean — api-key 存在時建立
@Bean
@ConditionalOnProperty(name = "{app}.genai.api-key")   // dot-notation
SomeApiClient realApiClient({App}Properties props) {
    return SomeApiClient.builder()
            .apiKey(props.genai().apiKey())             // 從 {App}Properties 取值
            .model(props.genai().model())               // 固定值從 application.yaml 讀取
            .build();
}

// NoOp fallback — api-key 不存在時自動啟用
@Bean
@ConditionalOnMissingBean(SomeApiClient.class)
SomeApiClient noOpApiClient() {
    return new NoOpApiClient();  // 功能停用但 app 正常啟動
}
```

**核心原則：**
- API key 透過 `{App}Properties` 統一管理（型別安全、集中），不用 `@Value`
- 固定值（model 等）從 `application.yaml` → `{App}Properties` → `@Bean`，只設一處
- `@ConditionalOnProperty` 用 dot-notation，與 `@ConfigurationProperties` 屬性路徑一致
- property 不存在 = 不啟用，不需 `DISABLED` hack
- `@ConditionalOnMissingBean` 確保 NoOp fallback 不覆蓋真實 bean

### 必要的 auto-config 排除

使用 Manual Configuration 時，必須排除對應的 auto-configuration class，避免啟動衝突：

```yaml
# application.yaml — 依實際 auto-config class 替換
spring:
  autoconfigure:
    exclude:
      - com.example.SomeAutoConfiguration   # 排除被 Manual Config 取代的 auto-config
```

> **注意：** profile YAML 會**覆蓋**（非合併）base 的 `exclude` list。
> 如果 infrastructure profile 需要額外排除項目，必須包含 base 的所有項目。
> 詳見 §6 配置載入順序。

### 5.X Schema 客製化 Gate（starter vs core artifact）

§5 的決策階梯處理「dev 環境能不能跑」。但還有一個獨立 gate：**starter 的硬編行為（schema、SQL、預設值）能不能吃下你的客製化需求？**

當 starter 跑得起來、auto-config 也能 wire 出 bean，但你需要：
- **客製 schema**（多加欄位、改型別、加 index）
- **改寫核心 SQL**（INSERT / UPDATE 的欄位列、ON CONFLICT 行為）
- **覆寫核心邏輯**（serialization 格式、欄位映射、batch 行為）

→ starter 通常不適合，因為它的 `*AutoConfiguration` 與其建出的 bean 通常是 `final` / 內部欄位 `private` / SQL 寫死在 method body — 沒有乾淨的 override point。

**判斷流程：**

```
Step 1: 列出本專案需要的所有 schema / SQL / 序列化客製化點
Step 2: WebFetch starter 的 *AutoConfiguration class 與它建構的核心 class 原始碼
Step 3: grep 是否有 SQL 字串、寫死的欄位列、final method、private 內部欄位
Step 4: 對每個客製化點問「starter 的硬編行為吃得下嗎？」
        → 全部 yes → 用 starter
        → 任一 no  → 換成不含 starter 的 core artifact，自寫子類或直接實作
```

**Exit criterion**：能用 yes/no 回答「starter 的硬編行為是否吃下我的所有客製化需求」，並引用 source code 行號。

**範例（Spring AI vector store）：**

```
情境：本專案 vector_store 表多兩欄 (owner, skill_id) 為 row-level ACL 鋪路。

❌ 走 spring-ai-starter-vector-store-pgvector：
   PgVectorStore.insertOrUpdateBatch() 的 INSERT SQL 寫死 4 欄
   (id, content, metadata, embedding)
   → 每次寫入後須額外 UPDATE 補 owner / skill_id（兩步驟 workaround）
   → 中間視窗 owner=NULL observable；ACL 場景有風險

✅ 走 spring-ai-pgvector-store（純 library）+ 自寫子類：
   extends AbstractObservationVectorStore，doAdd() 自寫 6-欄 INSERT
   一次寫完所有欄位，atomic、無中間視窗
```

**通則**：依賴名稱含 `starter` 是「讓框架管配置」的意圖宣告（§5 開頭原則）。**如果你的客製化需求超過框架預設提供的 properties 配置範圍，這個意圖宣告本身就不再適用** — 應該換 artifact，不是疊 workaround。

### 5.Y Bean Lifetime 決定註冊模式（singleton vs per-request）

設計 wrapper / adapter / facade 類別時，**先決定 state 的天然生命週期，再決定註冊模式**。反射性註冊 Spring `@Bean` 是常見錯誤。

**判斷問題**：這份 state 的生命週期是 **application** / **request** / **operation**？

| State 生命週期 | 註冊模式 | 範例 |
|---|---|---|
| **Application**（read-only、共用） | Singleton `@Bean` | `EmbeddingModel`、`JdbcTemplate`、`ObjectMapper`、Repository |
| **Request / Operation**（per-call context） | **不註冊 Bean**；呼叫端用 builder / factory `new` | 帶 user identity 的 wrapper、帶 tenant ID 的 query builder、單次操作的 unit-of-work |

**核心原則**：**不要把 per-call context（user identity、tenant、request ID）強塞進 singleton bean**。這會逼出兩個壞 pattern：
1. ThreadLocal / RequestScope 隱含上下文 — 測試/背景執行緒踩雷
2. `instanceof` guard / 條件分支處理「context 不存在」場景 — 結構複雜化

正確做法：呼叫端用 builder 建構 instance，把 context 鎖在 instance 屬性裡，操作完即可被 GC。

**範例（per-request VectorStore）：**

```java
// SearchProjection 注入「application 級」依賴：JdbcTemplate / EmbeddingModel / CurrentUserProvider
@EventListener
void onSkillCreated(SkillCreatedEvent event) {
    SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
            .owner(currentUserProvider.userId())   // ← per-call context，鎖在 instance
            .skillId(event.aggregateId())
            .build()
            .add(List.of(doc));                    // ← 操作完 instance 即 GC
}
```

**反例（singleton bean 強塞 per-call context）：**

```java
// ❌ 把 owner/skillId 寫進 singleton bean
@Bean VectorStore vectorStore(...) { ... }

@EventListener
void onSkillCreated(SkillCreatedEvent event) {
    vectorStore.add(...);  // 沒有 owner / skillId 注入點
    // 被迫在 add() 後接 UPDATE 補欄位 → 兩步驟 workaround
    // 或被迫用 ThreadLocal / instanceof guard → 結構壞掉
}
```

**Exit criterion**：能說出明確理由「為什麼 singleton 對」（state 是 read-only 共享）或「為什麼 per-request 對」（state 是 per-call context），不是反射式註冊 `@Bean`。

### 5.Z Fallback Obsolescence Check（dev 路徑變動時重審 fallback）

§5 的「dev 跑不了 → NoOp fallback」處理初始情境。**當基礎設施演進讓 production path 在 dev 也跑得起來時，原本的 fallback 變成 dev/prod 不一致的源頭，必須刪除。**

**典型情境：**
- 原本：vector store 走外部 SaaS 需 API key → dev 用 in-memory `SimpleVectorStore` fallback
- 演進後：搬到自管 PgVector，Docker Compose 自動啟 container → dev 也跑真 PgVector
- **此時 SimpleVectorStore fallback 已過時**：保留只會讓 dev 跑 in-memory 路徑，prod 跑真 SQL 路徑，行為差異可能藏 bug 到上線才暴露

**判斷問題**：這個 fallback 填補的 gap，production path 在 dev 真的填不了？

**Exit criterion**：能用具體場景回答「dev 沒這個 fallback 會怎樣」。如果答不出來（或答案是「現在 dev 也能跑 production path 了」），**刪掉 fallback**。

**為什麼這條容易被忽略**：fallback 加入時是合理的；但它一旦進 codebase，沒人會主動回頭問「現在還需要嗎？」。每次基礎設施重大變動（資料庫切換、API 自管化、emulator 上線）都應該掃一次 fallback。

**反例：**

```yaml
# 原本 dev 走 SimpleVectorStore，prod 走 SaaS — 合理
# 演進後 dev 也走 Docker Compose PgVector — fallback 過時但沒人刪

skillshub:
  search:
    vector-store: simple   # ← 已過時：dev 也有 PgVector container 可用
```

**通則**：dev 端能跑 production path 了 → 刪 fallback、刪切換屬性、刪 `@ConditionalOnProperty(havingValue="...")`，讓 dev 與 prod 走同一條路徑（dev/prod parity）。

## 6. 命名規則跨層對齊（dot.lower.case 為主軸）

不同層的命名規範不同，但**能對齊的就對齊**，省心智負擔。

| 物件 | 允許字元 | 範例 | 限制來源 |
|---|---|---|---|
| **Spring property name** | `[a-z0-9.-]` | `{app}.db.password` | Spring 慣例 |
| **Cloud Run env name** | dot / dash 等都可 | `{app}.db.password` | K8s `RelaxedEnvironmentVariableValidation` GA 2025-06-28 |
| **Secret Manager secret-id** | `[A-Za-z0-9_-]`（**無 dot**） | `{app}-db-password` | GCP API 命名規範 |
| **Shell var** | C-identifier `[A-Z_][A-Z0-9_]*` | `DB_PASSWORD` | POSIX |

**1:1 對齊原則**：
- Spring property = Cloud Run env name（**走 dot 完全對齊**）— 改一個就改另一個，無翻譯成本
- Secret Manager secret-id 用 hyphen-case（規範限制）— 命名上跟 Spring property 對應但用 hyphen
- Shell var 用 SCREAMING_SNAKE_CASE — 透過 Spring relaxed binding 自動對應到 dot.case

**範例對照**：
```
Spring property:    {app}.db.password
Cloud Run env:      {app}.db.password    ← 1:1 對齊（dot）
Secret Manager:     {app}-db-password    ← hyphen-case
Shell var:          DB_PASSWORD          ← 部署腳本 / .env 用
```

> Cloud Run env name 走 dot 形式是相對近期的能力（2025-06-28 GA）。早期 K8s 強制 C-identifier，env name 必須走 SCREAMING_SNAKE。**確認部署平台 K8s 版本支援 RelaxedEnvironmentVariableValidation 後再採用 dot 形式**；否則維持 SCREAMING_SNAKE + relaxed binding 路徑。

## 7. 工具衝突：envsubst 與 Spring `${...}` 語法

部署腳本常用 `envsubst` 渲染 Cloud Run yaml 模板。但 `envsubst` 預設**會把所有 `${...}` 當 shell 變數替換**，包含本應留給 Spring runtime 的 `${sm@xxx}` placeholder — 結果 `${sm@xxx}` 被替換為空字串，secret 拿不到。

**解法：envsubst whitelist 模式** — 只替換指定變數，其他 `${...}` 保留：

```bash
# 部署腳本（如 04-deploy.sh）
envsubst '$IMG $SA_EMAIL $CLOUDSQL_INSTANCE_CONN $DB_NAME $DB_USER $GCS_BUCKET_NAME' \
  < scripts/gcp/service.yaml > scripts/gcp/service.rendered.yaml
```

**驗證**（部署前 dry run）：
```bash
grep -E 'sm@|value:' scripts/gcp/service.rendered.yaml
# 預期：${sm@xxx} 字串原樣保留
```

**通則**：當部署層工具語法與 Spring property placeholder 衝突時，優先選擇 whitelist / escape 機制，**不要讓部署工具吃掉 Spring 要看的 `${...}`**。

## 8. Spring Boot 配置載入順序

### Config data files 載入優先級（由低到高）

```
classpath:/application.yaml                     ← 1. jar 內基礎
classpath:/application-{profile}.yaml           ← 2. jar 內 profile
file:./config/application.yaml                  ← 3. config/ 基礎
file:./config/application-{profile}.yaml        ← 4. config/ profile（最高）
```

**Profile 之間：** `spring.profiles.active=local,dev` 時，`dev` 的設定覆蓋 `local` 的同名設定（last wins）。

### `spring.config.import` 的行為

Import 的內容優先級**高於**宣告文件。所以 `config/application-dev.yaml` 中 import 的 `secrets.properties` 可以覆蓋 dev 的設定。

### 完整優先級鏈（由低到高）

```
application.yaml (classpath)
  ↑ application-{profile}.yaml (classpath)
  ↑ config/application-{profile}.yaml (filesystem)
  ↑ spring.config.import 引入的檔案
  ↑ OS 環境變數
  ↑ Java System properties (-D)
  ↑ Command-line arguments (--key=value)
```
