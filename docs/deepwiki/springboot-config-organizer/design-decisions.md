# 設計決策與 Skills Hub 改善分析

## 決策表

| # | 決策 | 理由 | 被否決的替代方案 |
|---|------|------|-----------------|
| 1 | 雙層 Profile（基礎設施 × 行為） | 避免 N×M profile 爆炸，靈活組合 | 單一維度 profile（dev/staging/prod）— 新增基礎設施需複製所有環境 |
| 2 | `src/main/resources/` vs `config/` 分層 | Docker Image 只含共用配置，環境行為不進 Image | 全部放 `src/main/resources/` — 行為配置也進 Image，不安全 |
| 3 | `spring.profiles.default: local,dev` | 零配置開發體驗，`bootRun` 即跑 | 不設預設值 — 開發者每次都要指定 profiles |
| 4 | 統一屬性名稱 `{app}-{secret-name}` | 跨環境一致，易於搜尋和管理 | 每個環境用不同屬性名稱 — 難以追蹤和維護 |
| 5 | `optional:file:` 引入 secrets | 檔案不存在不報錯，fallback 到預設值 | 強制要求 secrets 檔案 — 新成員無法直接啟動 |
| 6 | 環境變數注入 Secret Manager（MVP） | 不需額外依賴，Cloud Run 原生支援 | `spring.config.import=gcp-secretmanager://` — 需要額外 starter |
| 7 | `.example` 範例檔 commit | 新成員照抄即可上手，降低入門門檻 | 只寫文件說明 — 容易過時且格式不統一 |

---

## Skills Hub 現況分析

### 檔案結構對照

```
現況（已建立）                          理想狀態（springboot-config-organizer）
─────────────────────────────────────  ─────────────────────────────────────
src/main/resources/                    src/main/resources/
├── application.yaml            ✅     ├── application.yaml
├── application-local.yaml      ✅     ├── application-local.yaml
└── application-gcp.yaml        ✅     └── application-gcp.yaml

config/                                config/
├── application-dev.yaml        ✅     ├── application-dev.yaml
├── application-prod.yaml       ✅     ├── application-lab.yaml        ← 缺少
│                                      ├── application-prod.yaml
├── application-secrets.properties ✅  ├── application-secrets.properties
└── ...example                  ✅     └── ...example
```

### 逐檔分析

#### `application.yaml` — 評分：8/10

**符合的：**
- 共用配置集中（app name, datasource, actuator, logging）
- `spring.profiles.default: local,dev` 零配置開發
- 使用 `${skillshub-xxx:預設值}` 格式（部分）

**待改善的：**
- `skillshub.search.vector-store: ${SKILLSHUB_VECTOR_STORE:simple}` — 應改為 `${skillshub-vector-store:simple}`
- `springdoc` 預設值未設定 — `config/application-prod.yaml` 的 `springdoc.*.enabled=false` 在 Cloud Run 上不會生效（見 data-flow.md）
- 可考慮加入 `springdoc.api-docs.enabled: true` 預設值，讓 prod profile 可覆蓋

#### `application-local.yaml` — 評分：6/10

**符合的：**
- Docker Compose 啟用
- GCP 服務禁用

**待改善的：**
- 職責過重：混合了三類不同關注點
  1. Docker Compose 啟用 → 屬於基礎設施（正確）
  2. GCP 自動配置排除 → 屬於基礎設施（正確）
  3. Spring AI embedding 禁用/配置 → 部分屬於**行為層**
- `spring.ai.google.genai.embedding.api-key: ${GOOGLE_GENAI_API_KEY:DISABLED}` 的命名不符合統一規範
- `spring.ai.model.embedding.text: none` 與 `spring.ai.google.genai.embedding.*` 同時存在，語意衝突

**建議：**
- AI embedding 的 model/dimensions 配置移到 `application.yaml`（共用）
- `api-key` 命名改為 `${skillshub-genai-api-key:DISABLED}`
- 只在 `application-local.yaml` 保留基礎設施相關設定

#### `application-gcp.yaml` — 評分：7/10

**符合的：**
- GCP 服務啟用（Storage, Firestore）
- Docker Compose 禁用
- Spring AI 啟用

**待改善的：**
- `project-id: ${GCP_PROJECT_ID}` 無預設值 — 應改為 `${skillshub-gcp-project-id}` 統一命名
- `location: ${GCP_LOCATION:us-central1}` 無前綴 — 應改為 `${skillshub-gcp-location:us-central1}`
- AI embedding 的 model/dimensions 與 `application-local.yaml` 重複 — 應提取到共用

#### `config/application-dev.yaml` — 評分：9/10

**符合的：**
- `spring.config.import` 引入 secrets
- DEBUG 日誌
- 擴展 Actuator endpoints

**待改善的：**
- 可加入 `beans` 和 `mappings` endpoint 說明（已有但可加註解）

#### `config/application-prod.yaml` — 評分：7/10

**符合的：**
- INFO 日誌
- 限縮 Actuator

**待改善的：**
- `springdoc.api-docs.enabled=false` 在 Cloud Run 上不生效（`config/` 不進 Image）
- 應將 Swagger 預設值移到 `application.yaml` 或 `application-gcp.yaml`

---

## 改善建議摘要

| # | 改善項 | 優先級 | 影響檔案 |
|---|--------|--------|---------|
| 1 | 統一屬性命名為 `skillshub-xxx` | 高 | application.yaml, application-local.yaml, application-gcp.yaml, secrets.properties, secrets.properties.example |
| 2 | 提取 AI embedding model/dimensions 到共用配置 | 高 | application.yaml, application-local.yaml, application-gcp.yaml |
| 3 | `springdoc` 預設值移到 application.yaml | 中 | application.yaml, config/application-prod.yaml |
| 4 | 新增 `config/application-lab.yaml` | 低 | 新檔案 |
| 5 | 精簡 `application-local.yaml` 職責 | 中 | application-local.yaml |
| 6 | 更新 `secrets.properties.example` 使用統一命名 | 低 | secrets.properties.example |

### 對 Skills Hub spec-roadmap 的影響

這些改善不涉及功能變更，屬於**基礎設施重構**。建議作為獨立 spec（S009），
不影響現有功能，不阻塞其他 spec。

**直接適用：** 雙層 Profile 設計已經在專案中實作，此次改善是**精煉既有設計**，
統一命名規範、消除配置衝突、確保 GCP 部署時所有設定正確生效。
