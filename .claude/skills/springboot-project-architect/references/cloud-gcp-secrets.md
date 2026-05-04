# GCP Secret Manager 整合模式

> 此文件為 GCP 特定的 secret 注入參考。通用機敏值管理原則請見
> `config-design-principles.md` §4「統一機敏值管理機制」。

## 兩種注入方式

### 方式 1：Cloud Run env var mount（推薦首選）

Cloud Run 原生支援將 Secret Manager secret 掛載為 env var。
Spring Boot relaxed binding 自動將 env var 對應到屬性，**不需修改任何 YAML**。

```bash
# 部署時指定 secret → env var 對應
gcloud run deploy {app} \
  --set-secrets="{APP}_GENAI_API_KEY=projects/{project}/secrets/{app}-genai-api-key:latest"
```

或在 `service.yaml`（Cloud Run multi-container）內 secret 注入：

```yaml
env:
  - name: {app}.genai.api-key             # K8s RelaxedEnvName 接受 dot
    valueFrom:
      secretKeyRef:
        name: {app}-genai-api-key
        key: latest
```

最簡單，不需額外 Spring 依賴。

**官方文件：** https://cloud.google.com/run/docs/configuring/services/secrets

### 方式 2：Spring Cloud GCP Secret Manager 整合（`sm@` 語法）

適用於需要在 yaml / property value 內**直接引用 Secret Manager**，或將 Cloud Run env var
注入「sm@ URI 字串」靠 Spring 啟動時遞迴 resolve 的場景。

#### 依賴

```kotlin
// build.gradle.kts
implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
```

#### 啟用（在 GCP infra profile 註冊 resolver）

```yaml
# application-gcp.yaml
spring:
  config:
    import: sm@   # 註冊 SecretManagerConfigDataLocationResolver
```

> **注意**：放在雲端 infra profile，不放 `application.yaml`。本機開發不需要也不應該啟用 SM resolver。

#### 語法：`sm@` 前綴

> Spring Cloud GCP **6.0+ 推薦使用 `sm@`**，舊 `sm://` 仍可用但會發出 deprecation warning。

直接在 yaml 內引用 Secret Manager：

```yaml
{app}:
  genai:
    api-key: ${% raw %}${sm@{app}-genai-api-key}{% endraw %}            # 最新版本
    # api-key: ${% raw %}${sm@{app}-genai-api-key/2}{% endraw %}        # 特定版本
    # api-key: ${% raw %}${sm@{app}-genai-api-key:default}{% endraw %}  # 帶預設值
```

#### 進階：env var 注入 sm@ URI 字串（遞迴 resolve）

讓 yaml **不寫死** sm@（保持 base 跨環境可移植），雲端透過 env var 注入 sm@ 字串、Spring 啟動時遞迴 resolve：

```
property:  {app}.db.password （base yaml 用 placeholder ${% raw %}${{app}.db.password}{% endraw %}）

cloud env var:
  name:  {app}.db.password
  value: ${% raw %}${sm@{app}-db-password}{% endraw %}    ← 字面字串

Spring PropertyResolver 啟動時：
  1. 解析 ${% raw %}${{app}.db.password}{% endraw %} → 取 env var 值「${% raw %}${sm@xxx}{% endraw %}」
  2. 遞迴偵測值內含 ${...} → 觸發 SecretManagerPropertySource
  3. SM 拉 secret 實際值
  4. 回填 property
```

**好處**：應用碼跨 env 一致（`@ConfigurationProperties` / `@Value` 取值不變），切環境只是換注入來源。

⚠ **部署工具衝突**：`envsubst` 預設會把 `${sm@xxx}` 當 shell 變數吃掉。用 whitelist 模式只替換指定變數：

```bash
envsubst '$IMG $SA_EMAIL $CLOUDSQL_INSTANCE_CONN $DB_NAME $DB_USER' \
  < scripts/gcp/service.yaml > scripts/gcp/service.rendered.yaml
```

詳見 `config-design-principles.md` §7（envsubst whitelist）。

#### 認證

- **Cloud Run / GKE**：Workload Identity 自動提供 — 不需設定任何 credentials
- **本地模擬 GCP**：`gcloud auth application-default login`（但通常不需要 — local profile 不啟用 SM）

**官方文件：**
https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html#secret-manager

## 選擇建議

| 考量 | 方式 1：env var mount | 方式 2：Spring Cloud GCP `sm@` |
|------|---------------------|------------------------------|
| 額外依賴 | 無 | `spring-cloud-gcp-starter-secretmanager` |
| 設定複雜度 | 最低 | 需 starter + `spring.config.import: sm@` |
| Secret 更新 | 需重新部署 | 支援 runtime refresh |
| 適用平台 | Cloud Run | Cloud Run、GKE、Compute Engine |
| yaml 寫死 sm@ | 不需 | 視情況；建議走「env var 注入 sm@ 字串」保持 base 可移植 |
| 與 `@ConfigurationProperties` 整合 | relaxed binding 自動生效 | `sm@` 語法直接綁定 + 遞迴 resolve |

**原則**：優先方式 1。需要 runtime refresh 或非 Cloud Run 環境才用方式 2。

## GCP 核心配置屬性

| 屬性 | 用途 | 備註 |
|---|---|---|
| `spring.cloud.gcp.project-id` | GCP 專案 ID | 通常由 ADC 自動偵測 |
| `spring.cloud.gcp.credentials.location` | 憑證檔案路徑 | **不建議使用** — 用 ADC |
| `spring.cloud.gcp.storage.enabled` | 啟用 GCS | 預設 true |
| `spring.cloud.gcp.firestore.enabled` | 啟用 Firestore | 預設 true |
| `spring.cloud.gcp.secretmanager.enabled` | 啟用 Secret Manager | 預設 true |

**GCP 認證最佳實務：**

1. **Cloud Run / GKE**：使用 Workload Identity — 不需設定任何 credentials 屬性
2. **本地開發**：`gcloud auth application-default login`
3. **永遠不要**在設定檔中指定 `credentials.location` — 依賴 ADC

## 官方文件連結

- Spring Cloud GCP GitHub: https://github.com/GoogleCloudPlatform/spring-cloud-gcp
- Spring Cloud GCP 文件 (8.0.2): https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html
- Secret Manager 章節: https://googlecloudplatform.github.io/spring-cloud-gcp/8.0.2/reference/html/index.html#secret-manager
- Cloud Run Secret Manager env var mount: https://cloud.google.com/run/docs/configuring/services/secrets
