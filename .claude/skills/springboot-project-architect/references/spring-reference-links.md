# Spring 框架參考連結與查找技巧

## 如何查找 Spring Boot 設定屬性

### 方法 1: 官方 Application Properties 索引（最全）

```
https://docs.spring.io/spring-boot/appendix/application-properties/index.html
```

此頁面包含 **所有** Spring Boot 支援的設定屬性，按 namespace 分類。當你不確定某個屬性的完整路徑或預設值時，搜尋此頁面。

### 方法 2: 特定模組文件

根據需要查找的功能，直接進入對應模組文件：

```
# 外部配置（profile、載入順序、優先級）
https://docs.spring.io/spring-boot/reference/features/external-config.html

# Profile 設定
https://docs.spring.io/spring-boot/reference/features/profiles.html

# Docker Compose 支援
https://docs.spring.io/spring-boot/reference/features/dev-services.html

# Actuator endpoints
https://docs.spring.io/spring-boot/reference/actuator/endpoints.html

# 日誌設定
https://docs.spring.io/spring-boot/reference/features/logging.html
```

### 方法 3: Auto-configuration 報告

啟動時加 `--debug` 旗標，Spring Boot 會印出完整的 auto-configuration 報告：

```bash
./gradlew bootRun --args='--debug'
```

輸出中搜尋 `CONDITIONS EVALUATION REPORT` — 顯示哪些 auto-configuration 被啟用/跳過，以及原因。

### 方法 4: 原始碼

當文件不足時，直接看 auto-configuration 原始碼：

```
# Spring Boot auto-configuration 原始碼
https://github.com/spring-projects/spring-boot/tree/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure

# 例如找 Servlet multipart 設定
https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.java
```

---

## Spring Cloud GCP

### 核心文件

```
# README（總覽）
https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/main/README.adoc

# 官方文件
https://cloud.google.com/java/docs/spring

# Maven BOM（查版本）
https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/main/spring-cloud-gcp-dependencies/pom.xml
```

### 常用模組

```
# Cloud Storage
https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#spring-resources

# Secret Manager
https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#secret-manager

# Cloud SQL
https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#spring-jdbc
```

### GCP 核心配置屬性

| 屬性 | 用途 | 備註 |
|------|------|------|
| `spring.cloud.gcp.project-id` | GCP 專案 ID | 通常由 ADC 自動偵測 |
| `spring.cloud.gcp.credentials.location` | 憑證檔案路徑 | **不建議使用** — 用 ADC |
| `spring.cloud.gcp.storage.enabled` | 啟用 GCS | 預設 true |
| `spring.cloud.gcp.firestore.enabled` | 啟用 Firestore | 預設 true |

### GCP 認證最佳實務

1. **Cloud Run / GKE**: 使用 Workload Identity — 不需設定任何 credentials 屬性
2. **本地開發**: `gcloud auth application-default login`
3. **永遠不要**在設定檔中指定 `credentials.location` — 依賴 ADC

---

## Spring AI

### 核心文件

```
# Spring AI 總覽
https://docs.spring.io/spring-ai/reference/2.0/index.html

# Google GenAI Embedding（Manual Configuration）
https://docs.spring.io/spring-ai/reference/2.0/api/embeddings/google-genai-embeddings-text.html

# Embedding 模型通用介面
https://docs.spring.io/spring-ai/reference/2.0/api/embeddings.html

# VectorStore 介面
https://docs.spring.io/spring-ai/reference/2.0/api/vectordbs.html
```

### Manual Configuration 關鍵類別

```java
// 連線設定
import org.springframework.ai.google.genai.text.GoogleGenAiEmbeddingConnectionDetails;

// 模型選項
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

// 模型實作
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;

// 通用介面
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
```

### Auto-configuration 禁用

```yaml
# 禁用 embedding model auto-config
spring.ai.model.embedding.text=none

# 排除 connection auto-config（避免啟動時嘗試初始化）
spring.autoconfigure.exclude=org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration
```

---

## Spring Data MongoDB

```
# 官方文件
https://docs.spring.io/spring-data/mongodb/reference/

# Repository 介面
https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories.html

# 配置屬性
# spring.data.mongodb.uri
# spring.data.mongodb.database
# spring.data.mongodb.auto-index-creation
```

### Firestore Enterprise（MongoDB 相容模式）

```
# Firestore MongoDB 相容模式
https://cloud.google.com/firestore/docs/mongodb-compatibility

# 連線 URI 格式
mongodb+srv://{project-id}.firestore.googleapis.com/?retryWrites=false&authMechanism=MONGODB-OIDC

# 限制
# - retryWrites=false 必須設定
# - Change Streams 不支援
# - Vector indexes 只能透過 Firestore native SDK
```

---

## SpringDoc OpenAPI

```
# 官方文件
https://springdoc.org/

# 設定屬性
# springdoc.api-docs.enabled         → 啟用/禁用 OpenAPI spec
# springdoc.swagger-ui.enabled       → 啟用/禁用 Swagger UI
# springdoc.api-docs.path            → API spec 路徑（預設 /v3/api-docs）
# springdoc.swagger-ui.path          → Swagger UI 路徑（預設 /swagger-ui.html）
```

---

## Spring Modulith

```
# 官方文件
https://docs.spring.io/spring-modulith/reference/

# Events
https://docs.spring.io/spring-modulith/reference/events.html

# @ApplicationModuleListener
https://docs.spring.io/spring-modulith/reference/events.html#publication-registry
```

---

## 查版本的技巧

### 1. Spring Boot BOM 管理的版本

```bash
# 查看 Spring Boot BOM 管理了哪些依賴版本
./gradlew dependencies --configuration runtimeClasspath | grep {library-name}
```

### 2. Spring Cloud GCP BOM

```bash
# 查看 Spring Cloud GCP BOM
./gradlew dependencies | grep com.google.cloud
```

### 3. Maven Central 搜尋

```
https://search.maven.org/search?q=g:org.springframework.ai
https://search.maven.org/search?q=g:com.google.cloud%20a:spring-cloud-gcp-dependencies
```

### 4. GitHub Release Notes

```
# Spring Boot
https://github.com/spring-projects/spring-boot/releases

# Spring AI
https://github.com/spring-projects/spring-ai/releases

# Spring Cloud GCP
https://github.com/GoogleCloudPlatform/spring-cloud-gcp/releases
```
