# Spring Boot 設定檔設計原則

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

## 3. 統一屬性名稱策略

所有需要外部注入的屬性使用 `{app}-{secret-name}` kebab-case 格式：

```yaml
spring:
  data:
    mongodb:
      uri: ${myapp-mongodb-uri:mongodb://localhost:27017/mydb}

myapp:
  storage:
    bucket: ${myapp-storage-bucket:myapp-packages}
```

### 命名規則

| 規則 | 正確 | 錯誤 |
|------|------|------|
| 使用 app 前綴 | `${myapp-db-url}` | `${DB_URL}` |
| kebab-case | `${myapp-genai-api-key}` | `${MYAPP_GENAI_API_KEY}` |
| 含預設值 | `${myapp-db-url:jdbc:...}` | `${myapp-db-url}` (無預設) |
| 不用 DISABLED hack | 先查官方開關（`text=none`）或 `@ConditionalOnProperty` | `${myapp-key:DISABLED}` |

### 環境變數命名

GCP Cloud Run 支援 kebab-case 和帶點的環境變數名稱（如 `myapp-mongodb-uri`、`spring.datasource.url`），所以 **YAML placeholder 和環境變數可以用完全相同的名稱**：

```
YAML:     ${myapp-mongodb-uri:mongodb://localhost:27017/mydb}
Cloud Run: myapp-mongodb-uri = mongodb+srv://...
secrets:   myapp-mongodb-uri=mongodb://localhost:27017/mydb
```

全鏈路統一 kebab-case，不需要轉換。

> 注意：傳統 Linux 環境不支援帶 `-` 的 env var，需依賴 Spring Boot relaxed binding（`MYAPP_MONGODB_URI` → `myapp-mongodb-uri`）。Cloud Run 沒有這個限制。

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

### 雲端部署（GCP Cloud Run）

Cloud Run 支援 kebab-case 環境變數，直接用統一名稱注入：

```
Cloud Run 環境變數:
  myapp-mongodb-uri = mongodb+srv://...
  myapp-storage-bucket = myapp-prod-packages
  myapp-genai-api-key = AIzaSy...
```

與 YAML placeholder、secrets.properties 完全同名，零轉換。

進階：使用 `spring.config.import=gcp-secretmanager://` 整合 GCP Secret Manager（需額外 starter）。

## 5. Starter vs Manual Configuration 判斷原則

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
| Google GenAI Embedding API | 無法本地模擬，需真實 API key | **Manual Config** | dev 無法跑 unit test，需要 NoOp fallback |
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

### Manual Configuration 範例（外部 API 類型）

```java
@Bean
@ConditionalOnProperty(name = "myapp-genai-api-key")
EmbeddingModel googleGenAiEmbeddingModel(
        @Value("${myapp-genai-api-key}") String apiKey) {
    var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
            .apiKey(apiKey)
            .build();
    var options = GoogleGenAiTextEmbeddingOptions.builder()
            .model("gemini-embedding-2")
            .dimensions(768)
            .build();
    return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
}

@Bean
@ConditionalOnMissingBean(EmbeddingModel.class)
EmbeddingModel noOpEmbeddingModel() {
    // 零向量 — 語意搜尋不可用但 app 正常啟動
    return new NoOpEmbeddingModel();
}
```

**好處：**
- YAML 移除所有 `spring.ai.google.genai.embedding.*` 屬性
- 統一用 API key — local/gcp 不分
- model/dimensions 集中在 Java，不在 YAML 重複
- 屬性不存在 = 不啟用（不需 DISABLED hack）
- `application.yaml` 只需 `spring.ai.model.embedding.text: none`（禁用 auto-config）

### 必要的 auto-config 排除

```yaml
# application.yaml
spring:
  ai:
    model:
      embedding:
        text: none
  autoconfigure:
    exclude:
      - org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration
```

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
