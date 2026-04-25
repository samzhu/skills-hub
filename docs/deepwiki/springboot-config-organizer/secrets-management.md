# 機敏值管理策略

## 統一屬性名稱策略

> 來源：[springboot-config-organizer](https://github.com/samzhu/agent-skills/tree/main/skills/springboot-config-organizer) `references/design-principles.md`

所有需要外部注入的屬性使用 `{app}-{secret-name}` 格式：

```yaml
# application.yaml
spring:
  data:
    mongodb:
      uri: ${skillshub-mongodb-uri:mongodb://localhost:27017/skillshub}

skillshub:
  storage:
    bucket: ${skillshub-storage-bucket:skillshub-packages}
  search:
    vector-store: ${skillshub-vector-store:simple}     # 統一命名，不用 SCREAMING_SNAKE
```

### 命名規則

| 格式 | 範例 | 用途 |
|------|------|------|
| `skillshub-xxx-yyy` | `skillshub-mongodb-uri` | 統一屬性佔位符 |
| `${skillshub-xxx:預設值}` | `${skillshub-storage-bucket:skillshub-packages}` | 在 YAML 中使用，含預設值 |

**好處：**
- 本地用 `application-secrets.properties` 提供值
- GCP 用 Secret Manager 以相同名稱注入
- 有預設值確保本地開發零配置也能啟動
- 所有外部化屬性一目了然（`grep 'skillshub-'` 就能找到全部）

### 反模式：混用命名風格

```yaml
# 不要這樣做
uri: ${skillshub-mongodb-uri:...}         # kebab-case ✓
vector-store: ${SKILLSHUB_VECTOR_STORE:...}  # SCREAMING_SNAKE ✗
project-id: ${GCP_PROJECT_ID}             # 無前綴 ✗
```

Spring Boot 的 relaxed binding 可以將 `skillshub-mongodb-uri` 對應到
環境變數 `SKILLSHUB_MONGODB_URI`，所以 YAML 中統一用 kebab-case 即可。

---

## 本地機敏值：`application-secrets.properties`

```
config/
├── application-secrets.properties          ← 本地機敏值（.gitignore 排除）
└── application-secrets.properties.example  ← 範例檔（commit，新成員參考）
```

### 引入方式

透過環境行為設定檔的 `spring.config.import` 引入：

```yaml
# config/application-dev.yaml
spring:
  config:
    import: "optional:file:./config/application-secrets.properties"
```

**為什麼在 `dev` profile 引入而非 `application.yaml`？**
- `application.yaml` 打包進 Docker Image — 不應該參考本機檔案路徑
- `dev` profile 只在本地開發時啟用 — 語意正確
- `optional:` 確保沒有此檔案也能啟動

### `.example` 檔案範例

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
skillshub-mongodb-uri=mongodb://localhost:27017/skillshub

# --- 儲存 ---
skillshub-storage-bucket=skillshub-packages

# --- AI Embedding（語意搜尋）---
# skillshub-genai-api-key=AIzaSy...
```

---

## GCP 機敏值：Secret Manager 整合

> 來源：[Spring Cloud GCP Secret Manager](https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/main/README.adoc)

### 方式一：`spring.config.import`（推薦）

```yaml
# application-gcp.yaml
spring:
  config:
    import: "gcp-secretmanager://"
```

Secret Manager 中建立的 secret 名稱對應 Spring property 名稱：

```
Secret Manager secret: skillshub-mongodb-uri
  ↕
Spring property:       skillshub-mongodb-uri
```

**好處：**
- 與本地 `application-secrets.properties` 使用完全相同的屬性名稱
- 應用程式碼零修改，切換環境只換 profile
- Secret Manager 提供版本控制、存取稽核、自動輪換

### 方式二：環境變數注入（Cloud Run）

Cloud Run 設定中直接映射 Secret Manager secret 到環境變數：

```
# Cloud Run 環境變數設定
SKILLSHUB_MONGODB_URI → secretmanager:skillshub-mongodb-uri
```

Spring Boot relaxed binding 自動將 `SKILLSHUB_MONGODB_URI` 對應到 `skillshub-mongodb-uri`。

**比較：**

| 面向 | `spring.config.import` | 環境變數注入 |
|------|----------------------|-------------|
| 需要額外依賴 | 需要 `spring-cloud-gcp-starter-secretmanager` | 不需要 |
| 配置方式 | 在 `application-gcp.yaml` 宣告 | 在 Cloud Run UI / Terraform 設定 |
| 動態刷新 | 支援 `@RefreshScope` | 需要重啟 |
| 複雜度 | 低（自動屬性映射） | 中（需管理 Cloud Run 配置） |

### Skills Hub 建議

MVP 階段使用**方式二（環境變數注入）**，因為：
- 不需要額外依賴
- Cloud Run 原生支援 Secret Manager → 環境變數映射
- 配置簡單明瞭

後續可升級到方式一，啟用動態刷新。

---

## 屬性名稱對照表（Skills Hub 完整）

| 統一屬性名稱 | 用途 | 預設值 | 本地來源 | GCP 來源 |
|-------------|------|--------|---------|---------|
| `skillshub-mongodb-uri` | MongoDB 連線 URI | `mongodb://localhost:27017/skillshub` | secrets.properties | Cloud Run env / Secret Manager |
| `skillshub-storage-bucket` | GCS bucket 名稱 | `skillshub-packages` | secrets.properties | Cloud Run env |
| `skillshub-vector-store` | 向量儲存實作 | `simple` | secrets.properties | Cloud Run env |
| `skillshub-genai-api-key` | Google GenAI API Key | `DISABLED` | secrets.properties | Cloud Run env / Secret Manager |
| `skillshub-gcp-project-id` | GCP 專案 ID | (無) | 不需要 | ADC 自動偵測 / Cloud Run env |
| `skillshub-gcp-location` | GCP 區域 | `us-central1` | 不需要 | Cloud Run env |
