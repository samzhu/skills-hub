# 配置解析流程圖

## Profile 組合解析流程

```
┌──────────────────────────────────────────────────────────────┐
│                     應用程式啟動                              │
│                                                              │
│  有 SPRING_PROFILES_ACTIVE 環境變數？                          │
│     ├── 是 → active profiles = 環境變數值                     │
│     │        例: SPRING_PROFILES_ACTIVE=gcp,prod              │
│     │        → active = [gcp, prod]                          │
│     │                                                        │
│     └── 否 → 讀取 spring.profiles.default                     │
│              → active = [local, dev]                         │
└─────────────────────┬────────────────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────────────────┐
│              載入 Config Data Files（由低到高）                 │
│                                                              │
│  1. classpath:/application.yaml                              │
│     → 基礎共用配置（app name, datasource, actuator, logging） │
│                                                              │
│  2. classpath:/application-{profile}.yaml                    │
│     → application-local.yaml (Docker Compose, GCP 排除)       │
│     → application-gcp.yaml  (GCS, Firestore, Spring AI)      │
│     ※ 後面的 profile 覆蓋前面的                                │
│                                                              │
│  3. file:./config/application-{profile}.yaml                 │
│     → application-dev.yaml  (DEBUG log, import secrets)       │
│     → application-prod.yaml (INFO log, 限縮 actuator)         │
│     ※ 外部配置優先級高於 classpath                              │
│                                                              │
│  4. spring.config.import 引入                                 │
│     → optional:file:./config/application-secrets.properties   │
│     ※ 由 dev profile 引入，優先級高於 dev profile 本身          │
└─────────────────────┬────────────────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────────────────┐
│              OS 環境變數覆蓋                                   │
│                                                              │
│  SKILLSHUB_MONGODB_URI → skillshub-mongodb-uri               │
│  SKILLSHUB_STORAGE_BUCKET → skillshub-storage-bucket         │
│  ※ 環境變數優先級高於所有 config files                         │
└──────────────────────────────────────────────────────────────┘
```

---

## 情境一：本地開發（`local,dev`）

```
啟動：./gradlew bootRun（無環境變數）
  ↓
spring.profiles.default = local,dev
  ↓
載入順序（後者覆蓋前者）：

  ┌─ classpath ──────────────────────────────────────────────────┐
  │ application.yaml                                             │
  │   spring.application.name = skillshub                        │
  │   spring.data.mongodb.uri = ${skillshub-mongodb-uri:...}     │
  │   logging.level.root = INFO                                  │
  │   management.endpoints.web.exposure.include = health,...      │
  │   skillshub.search.vector-store = ${SKILLSHUB_VECTOR_STORE}  │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋
  ┌─ classpath (profile: local) ─────────────────────────────────┐
  │ application-local.yaml                                       │
  │   spring.docker.compose.enabled = true                       │
  │   spring.autoconfigure.exclude = GcpContextAutoConfiguration │
  │   spring.ai.model.embedding.text = none                      │
  │   spring.cloud.gcp.storage.enabled = false                   │
  │   spring.cloud.gcp.firestore.enabled = false                 │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋（dev 是第二個 profile，優先級更高）
  ┌─ config/ (profile: dev) ─────────────────────────────────────┐
  │ config/application-dev.yaml                                  │
  │   spring.config.import = secrets.properties                  │
  │   management.endpoints...include = health,...,env,configprops │
  │   logging.level.io.github.samzhu.skillshub = DEBUG           │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋（import 優先級高於宣告檔案）
  ┌─ import ─────────────────────────────────────────────────────┐
  │ config/application-secrets.properties                        │
  │   skillshub-mongodb-uri = mongodb://localhost:27017/skillshub │
  │   skillshub-storage-bucket = skillshub-packages              │
  │   GOOGLE_GENAI_API_KEY = AIzaSy...                           │
  │   spring.ai.model.embedding.text = google-genai              │
  └──────────────────────────────────────────────────────────────┘

最終生效值：
  spring.data.mongodb.uri = mongodb://localhost:27017/skillshub
  spring.docker.compose.enabled = true
  logging.level.io.github.samzhu.skillshub = DEBUG
  management.endpoints = health,info,metrics,env,configprops,...
  spring.ai.model.embedding.text = google-genai (from secrets)
```

---

## 情境二：GCP 正式環境（`gcp,prod`）

```
啟動：SPRING_PROFILES_ACTIVE=gcp,prod（Cloud Run 環境變數）
  ↓
載入順序（後者覆蓋前者）：

  ┌─ classpath ──────────────────────────────────────────────────┐
  │ application.yaml                                             │
  │   （同上，基礎共用配置）                                       │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋
  ┌─ classpath (profile: gcp) ───────────────────────────────────┐
  │ application-gcp.yaml                                         │
  │   spring.docker.compose.enabled = false                      │
  │   spring.cloud.gcp.storage.enabled = true                    │
  │   spring.cloud.gcp.firestore.enabled = true                  │
  │   spring.ai.model.embedding.text = google-genai              │
  │   spring.ai.google.genai.embedding.project-id = ${...}       │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋（prod 是第二個 profile）
  ┌─ Cloud Run 沒有 config/ 目錄 ──────────────────────────────────┐
  │ config/application-prod.yaml → 不存在，跳過                    │
  │ （prod 配置透過 Docker Image 內的 application.yaml 預設值      │
  │  + application-gcp.yaml 覆蓋即可）                             │
  └──────────────────────────────────────────────────────────────┘
       ↓ 覆蓋
  ┌─ Cloud Run 環境變數 ────────────────────────────────────────────┐
  │   SKILLSHUB_MONGODB_URI = mongodb+srv://...firestore...        │
  │   SKILLSHUB_STORAGE_BUCKET = skillshub-prod-packages           │
  │   GCP_PROJECT_ID = my-gcp-project                              │
  └──────────────────────────────────────────────────────────────┘

最終生效值：
  spring.data.mongodb.uri = mongodb+srv://...firestore...
  spring.cloud.gcp.storage.enabled = true
  spring.ai.model.embedding.text = google-genai
  logging.level.root = INFO
  management.endpoints = health,info
```

**注意：** `config/application-prod.yaml` 在 Cloud Run 上不存在（因為不打包進 Image），
所以 `springdoc.api-docs.enabled=false` 和 `springdoc.swagger-ui.enabled=false` 的設定
目前**不會在 GCP 上生效**。這需要移到 `application.yaml`（預設關閉）或 `application-gcp.yaml`。

---

## Profile 覆蓋矩陣

| 配置項 | application.yaml | local | gcp | dev | prod | secrets |
|--------|-----------------|-------|-----|-----|------|---------|
| `spring.application.name` | `skillshub` | — | — | — | — | — |
| `spring.data.mongodb.uri` | `${skillshub-mongodb-uri:localhost}` | — | — | — | — | 實際值 |
| `spring.docker.compose.enabled` | (未設定) | `true` | `false` | — | — | — |
| `spring.cloud.gcp.storage.enabled` | (未設定) | `false` | `true` | — | — | — |
| `spring.ai.model.embedding.text` | (未設定) | `none` | `google-genai` | — | — | 覆蓋 |
| `logging.level.root` | `INFO` | — | — | — | `INFO` | — |
| `logging.level.skillshub` | (未設定) | — | — | `DEBUG` | `INFO` | — |
| `management.endpoints.include` | `health,info,metrics` | — | — | 更多 | `health,info` | — |
| `springdoc.api-docs.enabled` | (未設定,預設 true) | — | — | — | `false` | — |
