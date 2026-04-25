# 雙層 Profile 設計原則

> 來源：[springboot-config-organizer](https://github.com/samzhu/agent-skills/tree/main/skills/springboot-config-organizer) `references/design-principles.md`

## 核心概念

Spring Boot 的 profile 可同時啟用多個。將 profile 分成**兩個獨立維度**，用組合的方式覆蓋不同情境：

| 維度 | 職責 | 回答的問題 | 範例 |
|------|------|-----------|------|
| **基礎設施層** | 連到哪裡（資料庫、雲端服務、訊息佇列） | 「用什麼基礎設施？」 | `local`, `gcp`, `aws` |
| **環境行為層** | 怎麼跑（日誌等級、取樣率、端點開放範圍） | 「行為像哪個環境？」 | `dev`, `lab`, `sit`, `uat`, `prod` |

### 組合矩陣

```
啟動指令                              基礎設施    行為    場景
─────────────────────────────────────────────────────────────
spring.profiles.active=local,dev      本機 Docker  DEBUG   日常開發
spring.profiles.active=local,lab      本機 Docker  DEBUG   實驗驗證
spring.profiles.active=gcp,lab        GCP 服務     DEBUG   雲端實驗環境
spring.profiles.active=gcp,sit        GCP 服務     INFO    整合測試
spring.profiles.active=gcp,uat        GCP 服務     INFO    驗收測試
spring.profiles.active=gcp,prod       GCP 服務     INFO    正式環境
```

### 為什麼不用單一維度？

傳統做法如 `dev`, `staging`, `prod` 將基礎設施與行為混在一起。問題：

- 想在 GCP 上跑 DEBUG 模式？→ 需要額外建 `gcp-dev` profile
- 新增 AWS 支援？→ 要複製 `aws-dev`, `aws-staging`, `aws-prod` 三個 profile
- N 個基礎設施 × M 個環境 = N×M 個 profile → 爆炸性增長

雙層設計只需要 N + M 個 profile。

---

## 檔案分層架構

```
src/main/resources/                    ← 打包進 Docker Image（classpath）
├── application.yaml                   ← 基礎共用配置，預設接近正式環境
│                                        spring.profiles.default: local,dev
├── application-local.yaml             ← 本地基礎設施（Docker Compose 等）
└── application-gcp.yaml               ← GCP 基礎設施（Cloud SQL, GCS, Firestore）

config/                                ← 外部配置，不打包進 Image
├── application-dev.yaml               ← 開發行為（DEBUG 日誌 + import secrets）
├── application-lab.yaml               ← Lab 行為（DEBUG 日誌，功能驗證）
├── application-prod.yaml              ← 正式行為（INFO 日誌，限縮 Actuator）
├── application-secrets.properties     ← 機敏值（本地，不 commit）
└── application-secrets.properties.example ← 機敏值範例（commit，供新成員參考）
```

### 為什麼分兩個目錄？

**`src/main/resources/`** — classpath 資源，會被打包進 Docker Image / jar。
適合放**所有環境都需要的基礎配置**，例如 app name、資料庫連線模板、Actuator 預設。

**`config/`** — Spring Boot 自動掃描的外部配置目錄（`optional:file:./config/`），
**不會**進入 jar/Docker Image。適合放**環境行為配置**和**機敏值**。

> **Spring Boot 文件引用：**
> Config data files are considered in the following order:
> 1. Application properties packaged inside jar
> 2. Profile-specific properties packaged inside jar
> 3. Application properties outside jar
> 4. Profile-specific properties outside jar
>
> — [Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)

因此 `config/` 目錄的檔案**優先級高於** `src/main/resources/` 的檔案，
可以覆蓋 classpath 內的預設值。

---

## Spring Boot 配置載入順序（完整）

### 屬性來源優先級（由低到高）

| # | 來源 | 備註 |
|---|------|------|
| 1 | `SpringApplication.setDefaultProperties()` | 最低優先級 |
| 2 | `@PropertySource` 註解 | 不支援 `logging.*`, `spring.main.*` |
| 3 | Config data files（見下方） | application.yaml 等 |
| 4 | `RandomValuePropertySource` | 僅 `random.*` |
| 5 | OS 環境變數 | `SPRING_PROFILES_ACTIVE` 等 |
| 6 | Java System properties（`-D` 旗標） | |
| 7 | Command-line arguments（`--key=value`） | 最高 runtime 優先級 |

### Config data files 內部載入順序（由低到高）

```
classpath:/application.yaml                         ← 1. jar 內基礎
classpath:/config/application.yaml                  ← 2. jar 內 config/
classpath:/application-{profile}.yaml               ← 3. jar 內 profile
classpath:/config/application-{profile}.yaml        ← 4. jar 內 config/ profile
file:./application.yaml                             ← 5. 工作目錄
file:./config/application.yaml                      ← 6. config/ 子目錄
file:./config/*/application.yaml                    ← 7. config/ 子目錄萬用字元
file:./application-{profile}.yaml                   ← 8. 工作目錄 profile
file:./config/application-{profile}.yaml            ← 9. config/ profile（最高）
file:./config/*/application-{profile}.yaml          ← 10. 萬用字元 profile
```

**Profile 之間的順序：** `spring.profiles.active=local,dev` 時，
`application-dev.yaml` 的設定會覆蓋 `application-local.yaml` 的同名設定（last wins）。

### `spring.config.import` 的行為

```yaml
# config/application-dev.yaml
spring:
  config:
    import: "optional:file:./config/application-secrets.properties"
```

- Import 的內容**插入到宣告文件的正下方**，優先級高於宣告文件
- `optional:` 前綴：檔案不存在時不報錯
- 相對路徑從宣告檔案的位置解析

---

## `spring.profiles.default` vs `spring.profiles.active`

| 屬性 | 用途 | 何時生效 |
|------|------|---------|
| `spring.profiles.default` | 設定預設 profile | **僅當沒有任何 active profile 時** |
| `spring.profiles.active` | 明確啟用 profile | 覆蓋 default，可透過 env var / CLI 設定 |

```yaml
# application.yaml
spring:
  profiles:
    default: local,dev    # 開發者直接 bootRun 就能跑
```

GCP 部署時設定環境變數 `SPRING_PROFILES_ACTIVE=gcp,prod`，
`default` 被忽略，只啟用 `gcp` + `prod`。

---

## Profile Groups（進階）

Spring Boot 支援將多個 profile 打包成一個群組：

```yaml
spring:
  profiles:
    group:
      local-dev:
        - local
        - dev
      gcp-prod:
        - gcp
        - prod
```

啟動時只需 `--spring.profiles.active=local-dev`。
**本專案暫不建議使用** — 雙層組合已經足夠清晰，加 group 反而多一層間接。
