# Spring Boot 設定檔模板

使用時將 `{app}` 替換為應用程式名稱（如 `skillshub`、`myapp`），`{App}` 替換為 PascalCase（如 `Skillshub`、`Myapp`）。

> **⚠ 版本查證義務：** 模板中的 `{app}.*` 屬性由我們控制，可直接使用。
> 但 `spring.*`、`management.*`、`springdoc.*` 等**框架屬性路徑會隨主版本變動**
> （框架主版本升級會 rename / deprecate / 搬移屬性路徑）。
> **執行時必須查證**官方 Application Properties 索引，確認屬性路徑在目標版本仍然有效：
> https://docs.spring.io/spring-boot/appendix/application-properties/index.html

---

## {App}Properties.java（@ConfigurationProperties record）

**放於根 package（與 `{App}Application.java` 同層）。**

```java
package io.github.example.{app};

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {App} 應用程式屬性集中管理。
 *
 * <p>透過 {@link org.springframework.boot.context.properties.ConfigurationPropertiesScan}
 * 在 {@code {App}Application} 中自動掃描。
 *
 * <p>Spring Boot relaxed binding 確保 env var（如 {@code {APP}_STORAGE_BUCKET}）
 * 可直接覆蓋對應屬性，不需在 YAML 中加額外 placeholder。
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html">
 *     Spring Boot External Configuration</a>
 */
@ConfigurationProperties(prefix = "{app}")
public record {App}Properties(
    @DefaultValue Storage storage,
    @DefaultValue Search search,
    @DefaultValue GenAI genai         // 選用：@DefaultValue 確保不為 null，apiKey 可為 null
) {

    /** GCS / 本機儲存設定 */
    public record Storage(
        @DefaultValue("{app}-packages") String bucket,
        @DefaultValue("./storage-local") String localPath
    ) {}

    /** 向量搜尋後端設定 */
    public record Search(
        @DefaultValue("simple") String vectorStore,
        @DefaultValue("{app}_search") String collection
    ) {}

    /**
     * 外部 API 整合設定（範例：Embedding API，依實際 API 替換）。
     * {@code model} 為固定值（所有環境共用），配有 @DefaultValue。
     * {@code apiKey} 為 null 時代表未設定，{@code @ConditionalOnProperty} 會跳過 bean 建立。
     */
    public record GenAI(
        @DefaultValue("your-model-name") String model,  // 依實際 API 替換
        String apiKey
    ) {}
}
```

---

## {App}Application.java（啟用 @ConfigurationPropertiesScan）

```java
package io.github.example.{app};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // 自動掃描並註冊 {App}Properties
public class {App}Application {

    public static void main(String[] args) {
        SpringApplication.run({App}Application.class, args);
    }
}
```

---

## application.yaml（共用配置，接近正式環境）

**`{app}` 段落使用純值，不用 `${...}` placeholder。**
Relaxed binding 自動支援 env var 覆蓋（`{APP}_STORAGE_BUCKET` → `{app}.storage.bucket`）。

```yaml
# =============================================================================
# {App Name} — 共用配置
# =============================================================================
# 此檔案包進 Docker Image。預設值接近正式環境，開發者需要的功能在
# config/application-dev.yaml 開啟。
# =============================================================================

spring:
  application:
    name: {app}

  profiles:
    default: local,dev

  threads:
    virtual:
      enabled: true

  # ----- 資料庫（依實際 DB 調整）-----
  # --- PostgreSQL ---
  # datasource:
  #   url: jdbc:postgresql://localhost:5432/{app}
  #   username: {app}
  #   password: secret

  # --- MongoDB / Firestore Enterprise ---
  # data:
  #   mongodb:
  #     uri: mongodb://localhost:27017/{app}
  #     database: {app}
  #     auto-index-creation: false  # Firestore 不支援 createIndex

# ----- Actuator（最小必要）-----
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true

# ----- 日誌（正式預設）-----
logging:
  level:
    root: INFO

# ----- API 文件（正式預設關閉）-----
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false

# ----- 應用程式自訂屬性（純值，不用 ${...} placeholder）-----
# Relaxed binding 自動支援 env var 覆蓋：
#   {APP}_STORAGE_BUCKET → {app}.storage.bucket
#   {APP}_SEARCH_VECTOR_STORE → {app}.search.vector-store
#   {APP}_GENAI_API_KEY → {app}.genai.api-key
{app}:
  storage:
    bucket: {app}-packages           # 覆蓋：env {APP}_STORAGE_BUCKET
  search:
    vector-store: simple             # 覆蓋：env {APP}_SEARCH_VECTOR_STORE
    collection: {app}_search
  genai:
    model: your-model-name           # 固定值 — 所有環境共用（依實際 API 替換）
    # api-key 不在此設定 — 缺席即停用（NoOp fallback）
    # 本機開發：config/application-secrets.properties 注入
    # 雲端部署：env var {APP}_GENAI_API_KEY（relaxed binding 自動對應）
```

---

## application-local.yaml（本地基礎設施）

```yaml
# =============================================================================
# 本地開發基礎設施
# =============================================================================
# Profile: local
# 用途: 本機開發，Docker Compose 啟動依賴服務。
#       無 GCP Application Default Credentials。
# =============================================================================

spring:
  docker:
    compose:
      enabled: true
      lifecycle-management: start-and-stop

  # --- 排除需要 GCP 憑證的 auto-configuration ---
  autoconfigure:
    exclude:
      - com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration

  # --- 停用 GCP 服務（改由 gcp profile 啟用）---
  cloud:
    gcp:
      storage:
        enabled: false
      firestore:
        enabled: false
```

---

## application-gcp.yaml（GCP 基礎設施）

```yaml
# =============================================================================
# GCP 基礎設施
# =============================================================================
# Profile: gcp
# 用途: 部署至 GCP Cloud Run。ADC 由 Workload Identity 自動提供。
# 啟動: SPRING_PROFILES_ACTIVE=gcp,prod
# =============================================================================

spring:
  docker:
    compose:
      enabled: false

  cloud:
    gcp:
      storage:
        enabled: true
      firestore:
        enabled: true

# 覆蓋 application.yaml 的 simple 預設值，改用 Firestore 向量搜尋
{app}:
  search:
    vector-store: firestore
```

---

## config/application-dev.yaml（開發行為）

```yaml
# =============================================================================
# 開發環境行為
# =============================================================================
# Profile: dev
# 用途: 本地開發 — DEBUG 日誌、完整 actuator、springdoc、引入本地機敏值。
# 注意: 位於 config/ 外部目錄，不包進 Docker Image。
# =============================================================================

spring:
  config:
    import: "optional:file:./config/application-secrets.properties"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,env,configprops,beans,mappings

logging:
  level:
    io.github.example.{app}: DEBUG

springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

---

## config/application-lab.yaml（Lab 行為）

```yaml
# =============================================================================
# Lab 環境行為
# =============================================================================
# Profile: lab
# 用途: 實驗環境 — DEBUG 日誌、完整 actuator、springdoc。
#       本地使用: profiles.active=local,lab
#       GCP 使用: SPRING_PROFILES_ACTIVE=gcp,lab（需手動上傳 config/）
# =============================================================================

spring:
  config:
    import: "optional:file:./config/application-secrets.properties"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,env,configprops,beans,mappings

logging:
  level:
    io.github.example.{app}: DEBUG

springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

---

## config/application-prod.yaml（正式行為）

```yaml
# =============================================================================
# 正式環境行為
# =============================================================================
# Profile: prod
# 用途: INFO 日誌、限縮 actuator。
#       springdoc 已由 application.yaml 預設關閉，不需重複。
# 啟動: SPRING_PROFILES_ACTIVE=gcp,prod
# =============================================================================

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    root: INFO
    io.github.example.{app}: INFO
```

---

## config/application-secrets.properties.example

使用 **dot-notation**，與 `@ConfigurationProperties` 屬性路徑一致。

```properties
# =============================================================================
# 本地開發機敏設定範例（此檔案 commit，實際值不 commit）
# =============================================================================
# 使用方式:
#   1. cp config/application-secrets.properties.example \
#         config/application-secrets.properties
#   2. 編輯填入實際值
#   3. ./gradlew bootRun
#
# 命名規則: dot-notation（與 @ConfigurationProperties 屬性路徑一致）
#   {app}.xxx.yyy = value
# GCP Cloud Run 等效 env var: {APP}_XXX_YYY（relaxed binding 自動對應）
# =============================================================================

# --- 資料庫（本地 Docker Compose 通常不需修改）---
# spring.mongodb.uri=mongodb://localhost:27017/{app}  （屬性路徑依版本查證）

# --- GCS Bucket（本地使用 FileSystem，此值僅供 gcp profile 使用）---
# {app}.storage.bucket={app}-packages

# =============================================================================
# 外部 API 整合（選用）
# =============================================================================
# 設定後對應 @ConditionalOnProperty 的 bean 建立。
# 未設定時使用 NoOp fallback（功能停用但應用程式正常啟動）。
#
# --- Google GenAI Embedding（語意搜尋）---
# 從 Google AI Studio (https://aistudio.google.com/apikey) 取得 API key。
# {app}.genai.api-key=AIzaSy...
#
# 雲端部署等效 env var：{APP}_GENAI_API_KEY（relaxed binding 自動對應）
# 雲端 Secret Manager 注入方式見 references/cloud-{provider}-secrets.md
```

---

## .gitignore 追加

```gitignore
### Secrets ###
config/application-secrets.properties
!config/application-secrets.properties.example
```

---

## Manual Configuration @Bean 模式（外部 API）

**模式：** 注入 `{App}Properties`（非 `@Value`），api-key 存在 → 真實 bean，缺席 → NoOp fallback。

```java
// 以外部 API 為例 — 實際 API client 依專案需求替換
@Configuration
class ExternalApiConfig {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiConfig.class);

    /**
     * 真實 bean — 當 {@code {app}.genai.api-key} 存在於 Environment 時建立。
     */
    @Bean
    @ConditionalOnProperty(name = "{app}.genai.api-key")   // dot-notation
    SomeApiClient realApiClient({App}Properties props) {
        log.info("Initialising real API client (Manual Config)");
        return SomeApiClient.builder()
                .apiKey(props.genai().apiKey())             // 從 {App}Properties 取值
                .model(props.genai().model())               // 固定值從 application.yaml 讀取
                .build();
    }

    /**
     * NoOp fallback — api-key 未設時自動啟用。
     * 功能停用但應用程式正常啟動。
     */
    @Bean
    @ConditionalOnMissingBean(SomeApiClient.class)
    SomeApiClient noOpApiClient() {
        log.warn("No API client configured — feature disabled.");
        return new NoOpApiClient();
    }
}
```

---

## 完成報告模板

```markdown
## 設定檔整理完成

### 檔案結構

src/main/resources/
├── application.yaml          ← 共用配置（接近正式環境，純值 {app}.* 屬性）
├── application-local.yaml    ← 本地基礎設施
└── application-gcp.yaml      ← GCP 基礎設施

src/main/java/.../
└── {App}Properties.java      ← @ConfigurationProperties(prefix = "{app}")

config/
├── application-dev.yaml      ← 開發行為
├── application-lab.yaml      ← Lab 行為
├── application-prod.yaml     ← 正式行為
├── application-secrets.properties.example  ← 範例（dot-notation）
└── application-secrets.properties          ← 機敏值（不 commit）

### {App}Properties 屬性

| 屬性路徑 | Java 存取 | 預設值 | 說明 |
|---------|----------|--------|------|
| {app}.storage.bucket | props.storage().bucket() | {app}-packages | GCS bucket |
| {app}.search.vector-store | props.search().vectorStore() | simple | 向量後端 |
| {app}.genai.model | props.genai().model() | your-model-name | 外部 API model（依實際替換） |
| {app}.genai.api-key | props.genai().apiKey() | — (null=停用) | API key（secrets 注入） |

### 啟動驗證

| 組合 | 結果 |
|------|------|
| local,dev（無 API key） | ✅ 啟動，NoOp embedding |
| local,dev（有 API key） | ✅ 啟動，真實 embedding |
| ./gradlew test | ✅ 所有測試通過 |

### 本地開發啟動

cp config/application-secrets.properties.example config/application-secrets.properties
# 填入 {app}.genai.api-key=AIzaSy...（選用）
./gradlew bootRun
```
