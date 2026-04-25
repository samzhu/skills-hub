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
| **基礎設施層** | 連到哪裡（DB、雲端服務、訊息佇列） | 「用什麼基礎設施？」 | `local`, `gcp`, `aws` |
| **環境行為層** | 怎麼跑（日誌等級、取樣率、端點開放範圍） | 「行為像哪個環境？」 | `dev`, `lab`, `sit`, `uat`, `prod` |

### 組合矩陣

```
啟動指令                              基礎設施    行為    場景
─────────────────────────────────────────────────────────────
spring.profiles.active=local,dev      本機 Docker  DEBUG   日常開發
spring.profiles.active=local,lab      本機 Docker  DEBUG   實驗驗證
spring.profiles.active=gcp,lab        GCP 服務     DEBUG   雲端實驗
spring.profiles.active=gcp,prod       GCP 服務     INFO    正式環境
```

### 為什麼不用單一維度？

傳統 `dev/staging/prod` 混合基礎設施與行為。新增基礎設施（如 AWS）就要複製所有行為 profile → N×M 爆炸。雙層設計只需 N + M 個 profile。

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

### `application.yaml` 接近正式環境的原則

`application.yaml` 的預設值應該是正式環境可以直接使用的。開發者需要的功能（springdoc、DEBUG log、擴展 actuator）在 `config/application-dev.yaml` 開啟。

**好處：** GCP 部署時不需要 `config/` 目錄也能正確運作。減少配置負擔。

**實務：**
- `logging.level.root: INFO`（正式預設）
- `springdoc.api-docs.enabled: false`（正式不需要）
- `management.endpoints.web.exposure.include: health,info,metrics`（最小必要）

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

## 4. 機敏值管理

### 本地開發

透過環境行為設定檔的 `spring.config.import` 引入：

```yaml
# config/application-dev.yaml
spring:
  config:
    import: "optional:file:./config/application-secrets.properties"
```

- `optional:` — 檔案不存在不報錯，fallback 到 `application.yaml` 預設值
- 只在 `dev`/`lab` profile 引入 — `application.yaml` 不引用本機路徑
- `.gitignore` 排除 `config/application-secrets.properties`
- `.example` 檔案提供模板，新成員照抄即可

### 雲端部署（通用原則）

**env var 是最通用的 secret 注入機制。** SCREAMING_SNAKE_CASE env var + relaxed binding，不需修改任何 YAML：

```
環境變數（SCREAMING_SNAKE_CASE）:
  {APP}_STORAGE_BUCKET  = {app}-prod-packages   → {app}.storage.bucket
  {APP}_GENAI_API_KEY   = <api-key>             → {app}.genai.api-key

Spring framework 屬性（env var）:
  SPRING_MONGODB_URI = mongodb+srv://...         → spring.mongodb.uri（路徑依版本查證）
```

Secret 的**來源**由部署平台決定（不是 Spring Boot 的職責）：

| 注入方式 | 適用場景 | Spring Boot 設定 |
|---------|---------|-----------------|
| 平台 env var mount（Cloud Run / K8s） | 最簡單，推薦首選 | 無 — relaxed binding 直接生效 |
| Spring Cloud Secret Manager 整合 | 需要 runtime refresh 或 `sm://` 語法 | 加 starter + `spring.config.import` |

**雲端平台特定的 Secret Manager 整合模式，請參考對應的雲端參考文件：**

- GCP: `references/cloud-gcp-secrets.md`（Spring Cloud GCP `sm://` + Cloud Run env var mount）
- AWS: 未來如需要，建立 `references/cloud-aws-secrets.md`
- Azure: 未來如需要，建立 `references/cloud-azure-secrets.md`

> **原則：** 不要在 `application.yaml` 中硬編碼雲端特定的 secret 機制（`sm://`、vault path）。
> 雲端 secret 注入放在雲端特定 profile（如 `application-gcp.yaml`）或部署設定中。

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

## 6. Spring Boot 配置載入順序

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
