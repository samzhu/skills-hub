# Spring Boot 設定檔模板

使用時將 `{app}` 替換為應用程式名稱（如 `skillshub`、`myapp`）。

---

## application.yaml（共用配置，接近正式環境）

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

  # ----- 資料庫 -----
  datasource:                              # 依實際 DB 調整
    url: ${'{app}-db-url:jdbc:postgresql://localhost:5432/{app}'}
    username: ${'{app}-db-username:{app}'}
    password: ${'{app}-db-password:secret'}

  # --- 或 MongoDB ---
  # data:
  #   mongodb:
  #     uri: ${{app}-mongodb-uri:mongodb://localhost:27017/{app}}
  #     database: {app}

  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

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

# ----- 應用程式自訂屬性 -----
# {app}:
#   storage:
#     bucket: ${{app}-storage-bucket:{app}-packages}
```

---

## application-local.yaml（本地基礎設施）

```yaml
# =============================================================================
# 本地開發基礎設施
# =============================================================================
# Profile: local
# 用途: 本機開發，Docker Compose 啟動依賴服務。
# =============================================================================

spring:
  docker:
    compose:
      enabled: true
      lifecycle-management: start-and-stop

  # --- 排除不需要的雲端 auto-configuration ---
  # autoconfigure:
  #   exclude:
  #     - com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration

  # --- 禁用雲端服務 ---
  # cloud:
  #   gcp:
  #     storage:
  #       enabled: false
  #     firestore:
  #       enabled: false
```

---

## application-gcp.yaml（GCP 基礎設施）

```yaml
# =============================================================================
# GCP 基礎設施
# =============================================================================
# Profile: gcp
# 用途: 部署至 GCP Cloud Run。ADC 由 Workload Identity 自動提供。
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
```

---

## config/application-dev.yaml（開發行為）

```yaml
# =============================================================================
# 開發環境行為
# =============================================================================
# Profile: dev
# 用途: 本地開發 — DEBUG 日誌、完整 actuator、springdoc、引入本地機敏值。
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
    com.example.{app}: DEBUG

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
#       GCP 使用: SPRING_PROFILES_ACTIVE=gcp,lab
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
    com.example.{app}: DEBUG

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
# =============================================================================

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    root: INFO
    com.example.{app}: INFO
```

---

## config/application-secrets.properties.example

```properties
# =============================================================================
# 本地開發機敏設定（此檔案已加入 .gitignore，請勿提交）
# =============================================================================
# 使用方式:
#   1. cp application-secrets.properties.example application-secrets.properties
#   2. 編輯填入實際值
#   3. ./gradlew bootRun
# =============================================================================

# --- 資料庫 ---
# {app}-db-url=jdbc:postgresql://localhost:5432/{app}
# {app}-db-username={app}
# {app}-db-password=your-password-here

# --- AI Embedding（語意搜尋，可選）---
# {app}-genai-api-key=AIzaSy...
```

---

## .gitignore 追加

```gitignore
### Secrets ###
config/application-secrets.properties
!config/application-secrets.properties.example
```

---

## 完成報告模板

```markdown
## 設定檔整理完成

### 檔案結構

src/main/resources/
├── application.yaml          ← 共用配置（接近正式環境）
├── application-local.yaml    ← 本地基礎設施
└── application-gcp.yaml      ← GCP 基礎設施

config/
├── application-dev.yaml      ← 開發行為
├── application-lab.yaml      ← Lab 行為
├── application-prod.yaml     ← 正式行為
├── application-secrets.properties.example  ← 範例
└── application-secrets.properties          ← 機敏值（不 commit）

### 統一屬性名稱

| 屬性名稱 | 用途 | 預設值 |
|----------|------|--------|
| {app}-xxx | ... | ... |

### 啟動驗證

| 組合 | 結果 |
|------|------|
| local,dev | ✅ 啟動成功 |
| ./gradlew test | ✅ 所有測試通過 |

### 本地開發啟動

cp config/application-secrets.properties.example config/application-secrets.properties
# 編輯填入實際值
./gradlew bootRun
```
