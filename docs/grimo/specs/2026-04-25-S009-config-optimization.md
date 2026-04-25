# S009: Spring Boot 設定檔最佳化

> Spec: S009 | Size: XS(7) | Status: ⏳ Design
> Date: 2026-04-25

---

## 1. Goal

統一 Spring Boot 設定檔的屬性命名規範、簡化 AI embedding 配置為 Manual Configuration（API key 模式）、修正 GCP 部署時設定不生效的問題，使雙層 Profile 設計（基礎設施 × 行為）完全符合 [springboot-config-organizer](https://github.com/samzhu/agent-skills/tree/main/skills/springboot-config-organizer) 最佳實務。

此為基礎設施重構 spec，不涉及功能變更，不阻塞其他 spec。無 code-level 依賴。

### 研究報告

完整分析見 `docs/deepwiki/springboot-config-organizer/`（6 個文件）。

---

## 2. Approach

**直接採用（XS 不做方案比較）：** 對齊 springboot-config-organizer 雙層 Profile 設計原則，搭配 Spring AI Manual Configuration 簡化 embedding 配置。

### 2.1 問題與修正對照

| # | 問題 | 根因 | 修正 |
|---|------|------|------|
| 1 | 屬性命名不一致 | `SKILLSHUB_VECTOR_STORE`、`GOOGLE_GENAI_API_KEY`、`GCP_PROJECT_ID` 混用三種命名風格 | 全部統一為 `skillshub-xxx` kebab-case 格式 |
| 2 | AI embedding 配置複雜且重複 | auto-config 需要 `text=none` / `DISABLED` hack、model/dimensions 在 local 和 gcp 重複、gcp 用 project-id/location 增加環境變數 | 改為 Manual Configuration `@Bean`，統一用 API key，model/dimensions 寫在 Java 中 |
| 3 | `springdoc` 在 GCP 上不生效 | `config/application-prod.yaml` 的 `springdoc.*.enabled=false` 不進 Docker Image | 改在 `application.yaml` 預設關閉（接近正式環境），`config/application-dev.yaml` 開啟 |
| 4 | 缺少 `lab` 行為 profile | 用戶需要 `profiles.active=lab,gcp` 但無對應檔案 | 新增 `config/application-lab.yaml`（DEBUG 日誌 + 擴展 Actuator） |
| 5 | `application-local.yaml` 職責混雜 | AI embedding 配置（model/dimensions/api-key/text=none）與基礎設施配置混在一起 | 移除所有 Spring AI 屬性，embedding 改由 SearchConfig `@Bean` 處理 |
| 6 | `secrets.properties.example` 命名不一致 | 使用舊命名風格 | 更新為統一的 `skillshub-xxx` 格式 |

### 2.2 AI Embedding: Manual Configuration 設計

> 參考：[Spring AI 2.0 — Google GenAI Manual Configuration](https://docs.spring.io/spring-ai/reference/2.0/api/embeddings/google-genai-embeddings-text.html#_manual_configuration)

**現況問題：**
- `application-local.yaml`：`spring.ai.model.embedding.text: none` + `api-key: ${GOOGLE_GENAI_API_KEY:DISABLED}` — 兩層 hack
- `application-gcp.yaml`：`project-id: ${GCP_PROJECT_ID}` + `location: ${GCP_LOCATION}` + `text: google-genai` — 多了 2 個環境變數
- model/dimensions 在兩個檔案中重複

**Manual Configuration 方案：**

```java
// SearchConfig.java — 新增 @Bean
@Bean
@ConditionalOnProperty(name = "skillshub-genai-api-key")
EmbeddingModel googleGenAiEmbeddingModel(
        @Value("${skillshub-genai-api-key}") String apiKey) {
    var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
            .apiKey(apiKey)
            .build();
    var options = GoogleGenAiTextEmbeddingOptions.builder()
            .model("gemini-embedding-2")
            .dimensions(768)
            .build();
    return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
}
```

**運作方式：**

```
skillshub-genai-api-key 屬性存在？
  ├── 是 → googleGenAiEmbeddingModel bean 建立 → 真實 embedding
  └── 否 → @ConditionalOnMissingBean → NoOpEmbeddingModel → 零向量（語意搜尋不可用但 app 正常）
```

**好處：**
- YAML 移除所有 `spring.ai.google.genai.embedding.*` 和 `spring.ai.model.embedding.text` 屬性
- 統一用 API key — local/gcp 不分（不需 project-id/location）
- model + dimensions 集中在 Java `@Bean`，不在 YAML 重複
- 不需 `DISABLED` hack — 屬性不存在就用 NoOp
- 開發者選擇性啟用：在 `config/application-secrets.properties` 填入 `skillshub-genai-api-key=AIzaSy...` 即啟用

**auto-config 禁用：** `application.yaml` 設定 `spring.ai.model.embedding.text: none` + 排除 `GoogleGenAiEmbeddingConnectionAutoConfiguration`，避免 auto-config 與 manual bean 衝突。

### 2.3 統一屬性名稱對照

| 舊名稱 | 新名稱 | 說明 |
|--------|--------|------|
| `${SKILLSHUB_VECTOR_STORE:simple}` | `${skillshub-vector-store:simple}` | YAML placeholder 統一 |
| `${GOOGLE_GENAI_API_KEY:DISABLED}` | `${skillshub-genai-api-key}` | 移至 Java `@Bean`，無預設值（不存在=不啟用） |
| `${GCP_PROJECT_ID}` | 移除 | 改用 API key，不需 project-id |
| `${GCP_LOCATION:us-central1}` | 移除 | 改用 API key，不需 location |

> Spring Boot relaxed binding 確保 `skillshub-vector-store` 仍匹配環境變數 `SKILLSHUB_VECTOR_STORE`。Java 程式碼中的 `@ConditionalOnProperty(name = "skillshub.search.vector-store")` 和 `@Value("${skillshub.storage.bucket}")` 不受影響。

### 2.4 Research Citations

| 來源 | 重點 |
|------|------|
| [Spring AI 2.0 Manual Configuration](https://docs.spring.io/spring-ai/reference/2.0/api/embeddings/google-genai-embeddings-text.html#_manual_configuration) | `GoogleGenAiEmbeddingConnectionDetails.builder().apiKey()` + `GoogleGenAiTextEmbeddingOptions.builder().model().dimensions()` |
| [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) | config/ 目錄不進 jar，優先級高於 classpath |
| [springboot-config-organizer v1.2.0](https://github.com/samzhu/agent-skills/tree/main/skills/springboot-config-organizer) | 雙層 Profile 設計原則、`{app}-{secret-name}` 命名規範 |

---

## 3. SBE Acceptance Criteria

**驗證指令：**

```bash
cd backend && ./gradlew test
```

Pass: 所有既有測試通過 + `./gradlew bootRun` 正確啟動。

---

**AC-1: 統一屬性命名**
```
Given 所有設定檔已修改
When  在設定檔中搜尋 placeholder pattern
Then  所有外部化屬性均使用 ${skillshub-xxx} 格式
And   不存在 SCREAMING_SNAKE_CASE 或無前綴的 placeholder（GCP_*, GOOGLE_*）
```

**AC-2: AI embedding 改為 Manual Configuration**
```
Given SearchConfig 新增 googleGenAiEmbeddingModel @Bean
When  application-secrets.properties 含 skillshub-genai-api-key=<有效 key>
Then  GoogleGenAiTextEmbeddingModel bean 建立，語意搜尋可用

Given application-secrets.properties 不含 skillshub-genai-api-key
When  以 profiles.active=local,dev 啟動
Then  NoOpEmbeddingModel 啟用，app 正常啟動，語意搜尋回傳空結果
```

**AC-3: YAML 無 Spring AI embedding 屬性**
```
Given 設定檔最佳化完成
When  檢視 application-local.yaml 和 application-gcp.yaml
Then  兩者均不含 spring.ai.google.genai.embedding.* 屬性
And   application.yaml 僅含 spring.ai.model.embedding.text=none（禁用 auto-config）
```

**AC-4: springdoc 在 GCP 部署時正確關閉**
```
Given application.yaml 預設 springdoc.api-docs.enabled=false
When  以 profiles.active=gcp,prod 啟動（模擬 GCP，無 config/ 目錄）
Then  springdoc API docs 和 Swagger UI 不可存取
```

**AC-5: lab profile 可正常使用**
```
Given config/application-lab.yaml 已建立
When  以 profiles.active=local,lab 啟動
Then  日誌等級為 DEBUG
And   Actuator endpoints 包含 health,info,metrics,env,configprops,beans,mappings
And   springdoc 可存取
```

**AC-6: 既有功能不受影響**
```
Given 所有設定檔 + SearchConfig 已修改
When  執行 ./gradlew test
Then  所有既有測試通過（無迴歸）
And   ./gradlew bootRun 可正常啟動
```

---

## 4. Interface / API Design

### SearchConfig.java 新增 Bean

```java
// 新增 import
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;

// 新增 @Bean — 在 noOpEmbeddingModel 之前
@Bean
@ConditionalOnProperty(name = "skillshub-genai-api-key")
EmbeddingModel googleGenAiEmbeddingModel(
        @Value("${skillshub-genai-api-key}") String apiKey) {
    log.info("Initialising GoogleGenAiTextEmbeddingModel (API key mode, model=gemini-embedding-2)");
    var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
            .apiKey(apiKey)
            .build();
    var options = GoogleGenAiTextEmbeddingOptions.builder()
            .model("gemini-embedding-2")
            .dimensions(768)
            .build();
    return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
}
```

Bean 優先級：
1. `googleGenAiEmbeddingModel` — `@ConditionalOnProperty(skillshub-genai-api-key)` → API key 存在時建立
2. `noOpEmbeddingModel` — `@ConditionalOnMissingBean(EmbeddingModel.class)` → 無 API key 時 fallback

### 配置檔最終結構

```
src/main/resources/                    ← 打包進 Docker Image
├── application.yaml                   ← 共用 + 接近正式環境預設
│     spring.profiles.default: local,dev
│     spring.data.mongodb (skillshub-mongodb-uri)
│     spring.ai.model.embedding.text: none (禁用 auto-config)
│     skillshub.storage.bucket (skillshub-storage-bucket)
│     skillshub.search.vector-store (skillshub-vector-store)
│     springdoc: disabled by default
│     management.endpoints: health,info,metrics
│     logging.level.root: INFO
│
├── application-local.yaml             ← 本地基礎設施（精簡）
│     docker.compose: enabled
│     gcp autoconfigure: excluded
│     gcp storage/firestore: disabled
│     （無 Spring AI 屬性）
│
└── application-gcp.yaml              ← GCP 基礎設施（精簡）
      docker.compose: disabled
      gcp storage/firestore: enabled
      （無 Spring AI 屬性）

config/                                ← 外部配置，不進 Image
├── application-dev.yaml               ← 開發行為
│     spring.config.import: secrets
│     logging: DEBUG
│     management.endpoints: 擴展
│     springdoc: enabled
│
├── application-lab.yaml               ← Lab 行為（NEW）
│     spring.config.import: secrets
│     logging: DEBUG
│     management.endpoints: 擴展
│     springdoc: enabled
│
├── application-prod.yaml              ← 正式行為（精簡）
│     logging: INFO
│     management.endpoints: health,info
│
├── application-secrets.properties     ← 機敏值（不 commit）
└── application-secrets.properties.example ← 範例（commit）
```

### Profile 組合驗證矩陣

| 組合 | 基礎設施 | embedding | springdoc | 日誌 | 場景 |
|------|---------|-----------|-----------|------|------|
| `local,dev` 無 API key | Docker Compose | NoOp | ON | DEBUG | 日常開發（無語意搜尋） |
| `local,dev` 有 API key | Docker Compose | Gemini | ON | DEBUG | 開發 + 語意搜尋 |
| `local,lab` 有 API key | Docker Compose | Gemini | ON | DEBUG | 本地實驗 |
| `gcp,lab` 有 API key | GCP 服務 | Gemini | OFF* | INFO* | GCP 實驗 |
| `gcp,prod` 有 API key | GCP 服務 | Gemini | OFF | INFO | 正式環境 |

> *`gcp,lab` 在 Cloud Run 上 `config/application-lab.yaml` 不存在，行為 fallback 到 `application.yaml` 預設值。若需 DEBUG，透過 Cloud Run 環境變數 `LOGGING_LEVEL_IO_GITHUB_SAMZHU_SKILLSHUB=DEBUG`。

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/java/.../search/SearchConfig.java` | modify | 新增 `googleGenAiEmbeddingModel` `@Bean`（Manual Configuration，API key 模式） |
| `backend/src/main/resources/application.yaml` | modify | 統一 `skillshub-vector-store` 命名 + `spring.ai.model.embedding.text: none` + 排除 auto-config + springdoc 預設關閉 |
| `backend/src/main/resources/application-local.yaml` | modify | 移除所有 `spring.ai.*` 屬性，只保留基礎設施開關 |
| `backend/src/main/resources/application-gcp.yaml` | modify | 移除所有 `spring.ai.*` 屬性，只保留基礎設施開關 |
| `backend/config/application-dev.yaml` | modify | 新增 springdoc enabled |
| `backend/config/application-lab.yaml` | new | Lab 行為 profile — DEBUG 日誌 + 擴展 Actuator + springdoc + import secrets |
| `backend/config/application-prod.yaml` | modify | 移除 springdoc 設定（已由 application.yaml 預設關閉） |
| `backend/config/application-secrets.properties` | modify | 統一命名 `skillshub-genai-api-key` + 移除 `spring.ai.model.embedding.text` 覆蓋 |
| `backend/config/application-secrets.properties.example` | modify | 統一命名 + 更新說明 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
