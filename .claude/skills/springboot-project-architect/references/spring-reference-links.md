# Spring 框架參考連結與查找技巧

> Skill 使用時按需載入。所有連結指向**官方文件**，不包含第三方教學。

## Spring 生態總覽

```
# Spring 所有專案一覽（找 starter、查版本相容性）
https://spring.io/projects

# Spring Cloud Release Train（查各 Spring Cloud 子專案對應版本）
https://docs.spring.io/spring-cloud-release/reference/index.html
```

---

## Spring Boot

### 核心文件

```
# 外部配置（profile、載入順序、優先級、relaxed binding）
https://docs.spring.io/spring-boot/reference/features/external-config.html

# Configuration Classes（@Configuration、@ConfigurationProperties、@Bean）
https://docs.spring.io/spring-boot/reference/using/configuration-classes.html

# Application Properties 索引（所有屬性完整列表）
https://docs.spring.io/spring-boot/appendix/application-properties/index.html

# Profile 設定
https://docs.spring.io/spring-boot/reference/features/profiles.html

# Docker Compose 支援
https://docs.spring.io/spring-boot/reference/features/dev-services.html

# Actuator endpoints
https://docs.spring.io/spring-boot/reference/actuator/endpoints.html

# 日誌設定
https://docs.spring.io/spring-boot/reference/features/logging.html
```

### Auto-configuration 報告

啟動時加 `--debug` 旗標，Spring Boot 會印出完整的 auto-configuration 報告：

```bash
./gradlew bootRun --args='--debug'
```

輸出中搜尋 `CONDITIONS EVALUATION REPORT` — 顯示哪些 auto-configuration 被啟用/跳過，以及原因。

### 原始碼

當文件不足時，直接看 auto-configuration 原始碼：

```
https://github.com/spring-projects/spring-boot/tree/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure
```

---

## Spring Data MongoDB

```
# 官方文件
https://docs.spring.io/spring-data/mongodb/reference/

# Repository 介面
https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories.html

# 常用配置屬性（路徑依版本查證，以下為範例）
# 以下為範例，屬性路徑依版本查證：
# spring.mongodb.uri
# spring.mongodb.database
# spring.mongodb.auto-index-creation
```

---

## Spring AI

```
# Spring AI 總覽
https://docs.spring.io/spring-ai/reference/2.0/index.html

# Embedding 模型通用介面
https://docs.spring.io/spring-ai/reference/2.0/api/embeddings.html

# VectorStore 介面
https://docs.spring.io/spring-ai/reference/2.0/api/vectordbs.html
```

---

## Spring Modulith

```
# 官方文件
https://docs.spring.io/spring-modulith/reference/

# Events
https://docs.spring.io/spring-modulith/reference/events.html
```

---

## SpringDoc OpenAPI

```
# 官方文件
https://springdoc.org/

# 常用配置屬性
# springdoc.api-docs.enabled         → 啟用/禁用 OpenAPI spec
# springdoc.swagger-ui.enabled       → 啟用/禁用 Swagger UI
```

---

## 雲端平台整合

### GCP — Spring Cloud GCP

```
# GitHub
https://github.com/GoogleCloudPlatform/spring-cloud-gcp

# 官方文件（8.0.2）
https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html

# Secret Manager
https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html#secret-manager

# Cloud Run — Secret Manager env var mount
https://cloud.google.com/run/docs/configuring/services/secrets
```

詳細整合模式見 `references/cloud-gcp-secrets.md`。

GCP 核心配置屬性：

| 屬性 | 用途 | 備註 |
|------|------|------|
| `spring.cloud.gcp.project-id` | GCP 專案 ID | 通常由 ADC 自動偵測 |
| `spring.cloud.gcp.credentials.location` | 憑證檔案路徑 | **不建議使用** — 用 ADC |
| `spring.cloud.gcp.storage.enabled` | 啟用 GCS | 預設 true |
| `spring.cloud.gcp.firestore.enabled` | 啟用 Firestore | 預設 true |

GCP 認證最佳實務：
1. **Cloud Run / GKE**: 使用 Workload Identity — 不需設定任何 credentials 屬性
2. **本地開發**: `gcloud auth application-default login`
3. **永遠不要**在設定檔中指定 `credentials.location` — 依賴 ADC

### AWS — Spring Cloud AWS

```
# GitHub
https://github.com/awspring/spring-cloud-aws

# 官方文件
https://awspring.io/
```

### Azure — Spring Cloud Azure

```
# Key Vault 整合
https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/configure-spring-boot-starter-java-app-with-azure-key-vault
```

雲端特定的 Secret Manager 整合模式請參考 `references/cloud-{provider}-secrets.md`（目前僅 GCP）。

---

## 查版本的技巧

### 1. Spring Boot BOM 管理的版本

```bash
./gradlew dependencies --configuration runtimeClasspath | grep {library-name}
```

### 2. Spring Cloud GCP BOM

```bash
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

# Spring Cloud AWS
https://github.com/awspring/spring-cloud-aws/releases
```
