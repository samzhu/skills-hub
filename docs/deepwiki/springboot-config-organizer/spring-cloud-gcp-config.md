# Spring Cloud GCP 配置

> 來源：[Spring Cloud GCP README](https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/main/README.adoc), 版本 8.0.2 BOM

## Skills Hub 使用的 GCP 服務

| GCP 服務 | Spring Cloud GCP 模組 | Skills Hub 用途 |
|---------|----------------------|----------------|
| Cloud Storage | `spring-cloud-gcp-starter-storage` | 技能 zip 封裝儲存 |
| Firestore | `google-cloud-firestore` (native SDK) | 向量搜尋 (S007) |
| Firestore Enterprise | Spring Data MongoDB driver | Event store + read models |
| Vertex AI (Gemini) | Spring AI `spring-ai-google-genai` | Embedding 產生（語意搜尋） |

## GCP 核心配置屬性

### 專案識別

```yaml
spring:
  cloud:
    gcp:
      project-id: ${skillshub-gcp-project-id}    # 通常由 ADC 自動偵測
```

**自動偵測順序：**
1. `GOOGLE_CLOUD_PROJECT` 環境變數
2. App Engine 環境
3. Credentials 檔案中的 project_id
4. Cloud SDK 配置（`gcloud config`）
5. Compute Engine / Cloud Run 中繼資料

→ 在 Cloud Run 上通常**不需要**明確設定 `project-id`。

### 認證（Credentials）

```yaml
# 不需要設定 — 使用 Application Default Credentials (ADC)
# Cloud Run 上由 Workload Identity 自動提供
# 本地開發用 gcloud auth application-default login
```

**反模式：** 不要在設定檔中指定 `spring.cloud.gcp.credentials.location`，
應該完全依賴 ADC。

---

## Cloud Storage 配置

```yaml
# application-gcp.yaml
spring:
  cloud:
    gcp:
      storage:
        enabled: true          # 啟用 GCS 自動配置

# application-local.yaml
spring:
  cloud:
    gcp:
      storage:
        enabled: false         # 本地用 FileSystemStorageService
```

---

## Firestore 配置

### MongoDB 驅動（CRUD + Event Store）

```yaml
# application.yaml
spring:
  data:
    mongodb:
      uri: ${skillshub-mongodb-uri:mongodb://localhost:27017/skillshub}
      database: skillshub
      auto-index-creation: false
```

GCP 部署時 `skillshub-mongodb-uri` 設為：
```
mongodb+srv://{project-id}.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC
```

### Native SDK（向量搜尋）

```yaml
# application-gcp.yaml
spring:
  cloud:
    gcp:
      firestore:
        enabled: true          # 啟用 Firestore native SDK 自動配置

# application-local.yaml
spring:
  cloud:
    gcp:
      firestore:
        enabled: false         # 本地不使用 Firestore native SDK
```

---

## Spring AI + Gemini Embedding 配置

### 本地開發（API Key 模式）

```yaml
# application-local.yaml
spring:
  ai:
    google:
      genai:
        embedding:
          api-key: ${skillshub-genai-api-key:DISABLED}
          text:
            options:
              model: gemini-embedding-2
              dimensions: 768
    model:
      embedding:
        text: none             # 預設禁用，需在 secrets.properties 啟用
```

### GCP 部署（Vertex AI 模式）

```yaml
# application-gcp.yaml
spring:
  ai:
    model:
      embedding:
        text: google-genai     # 啟用 Google GenAI embedding
    google:
      genai:
        embedding:
          project-id: ${skillshub-gcp-project-id}
          location: ${skillshub-gcp-location:us-central1}
          text:
            options:
              model: gemini-embedding-2
              dimensions: 768
```

### 配置衝突分析

目前 `application-local.yaml` 同時設定了 `api-key` 和 `text: none`，
意圖是「預設禁用 embedding，需要時在 secrets.properties 覆蓋」。
這造成兩個問題：

1. `text: none` 全域禁用 embedding — 即使提供了 API key 也不會啟動
2. 需要在 `application-secrets.properties` 中同時設定 key 和 `spring.ai.model.embedding.text=google-genai`

**建議：** 將 `text: none` 改為條件判斷：
- 有 API key → 自動啟用
- 無 API key（`DISABLED`）→ 不啟用

---

## GCP 自動配置排除

本地開發需要排除 GCP 相關自動配置，避免報錯：

```yaml
# application-local.yaml
spring:
  autoconfigure:
    exclude:
      - com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration
```

這是因為沒有 GCP ADC 時，`GcpContextAutoConfiguration` 會嘗試初始化並發出 WARN 日誌。
