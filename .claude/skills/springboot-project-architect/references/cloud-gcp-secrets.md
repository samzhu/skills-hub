# GCP Secret Manager 整合模式

> 此文件為 GCP 特定的 secret 注入參考。通用原則請見 `config-design-principles.md` §4。

## 兩種注入方式

### 方式 1: Cloud Run env var mount（推薦首選）

Cloud Run 原生支援將 Secret Manager secret 掛載為 env var。
Spring Boot relaxed binding 自動將 env var 對應到屬性，**不需修改任何 YAML**。

```bash
# 部署時指定 secret → env var 對應
gcloud run deploy {app} \
  --set-secrets="{APP}_GENAI_API_KEY=projects/{project}/secrets/{app}-genai-api-key:latest"
```

此方式最簡單，不需額外 Spring 依賴。

**官方文件：**
https://cloud.google.com/run/docs/configuring/services/secrets

### 方式 2: Spring Cloud GCP Secret Manager 整合

適用於需要 **runtime refresh**、在 YAML 中直接引用 secret、或需要在非 Cloud Run 環境（如 GKE）整合 Secret Manager 的場景。

#### 依賴

```kotlin
// build.gradle.kts
implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
```

#### 啟用

在 GCP 基礎設施 profile 中啟用：

```yaml
# application-gcp.yaml
spring:
  config:
    import: "gcp-secretmanager://"
```

> **注意：** `spring.config.import` 放在雲端特定 profile，不放在 `application.yaml`。
> 本機開發不需要也不應該啟用 Secret Manager。

#### 語法：`sm://` 前綴

啟用後可在 property 值中使用 `sm://` 引用 secret：

```properties
# 引用最新版本
{app}.genai.api-key=${sm://{app}-genai-api-key}

# 引用特定版本
{app}.genai.api-key=${sm://{app}-genai-api-key/2}

# 帶預設值（secret 不存在時 fallback）
{app}.genai.api-key=${sm://{app}-genai-api-key:default-value}
```

#### 認證

- **Cloud Run / GKE:** 使用 Workload Identity — 不需設定任何 credentials
- **本地模擬 GCP:** `gcloud auth application-default login`（但通常不需要，local profile 不啟用 Secret Manager）

**官方文件：**
https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html#secret-manager

## 選擇建議

| 考量 | 方式 1: env var mount | 方式 2: Spring Cloud GCP |
|------|---------------------|------------------------|
| 額外依賴 | 無 | `spring-cloud-gcp-starter-secretmanager` |
| 設定複雜度 | 最低 | 需要 starter + config import |
| Secret 更新 | 需重新部署 | 支援 runtime refresh |
| 適用平台 | Cloud Run | Cloud Run, GKE, Compute Engine |
| 與 `@ConfigurationProperties` 整合 | relaxed binding 自動生效 | `sm://` 語法直接綁定 |

**原則：** 優先選擇方式 1。只在需要 runtime refresh 或非 Cloud Run 環境時才使用方式 2。
