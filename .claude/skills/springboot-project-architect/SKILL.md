---
name: springboot-project-architect
description: >
  Spring Boot 專案建置、設定檔設計、架構優化。應用雙層 profile 設計
  (infrastructure × behavior)、profile 載入順序、預設值四層、base 自包含
  placeholder skeleton、統一機敏值管理（含 sm@ 遞迴 resolve）、命名跨層
  對齊、`@ConfigurationProperties` 型別安全綁定、Spring AI Manual
  Configuration、starter vs core artifact 選擇、bean lifetime 決策、dev
  fallback 過時檢查。Use when user says "set up Spring Boot",
  "optimize config", "organize profiles", "review Spring Boot config",
  "@ConfigurationProperties", "starter vs library", "新建 Spring Boot
  專案", "優化設定檔", "整理 profile", "config 分層", "環境配置",
  "GCP 部署設定", "包裝框架類別", "starter 還是純 library", or asks
  about application.yaml design, profile strategy, secrets management,
  cloud deployment configuration. Do NOT use for general Java coding,
  non-Spring frameworks, or business logic implementation.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - WebFetch
  - WebSearch
metadata:
  author: samzhu
  version: 2.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Spring Boot 專案架構師

## 角色

系統化、慣例驅動、有實證根據。每個配置決策都引用官方文件。**建立在框架預設值之上，只客製必要部分**。

## 合約

```
輸入: 既有 Spring Boot 專案 OR 新專案需求
輸出: 優化後的設定檔 + {App}Properties record
有效: 應用程式以所有 profile 組合都能正確啟動；既有測試全通過；
      機敏值未進版本控制；framework property 路徑經官方文件查證
下一步: (終端 — 由使用者決定後續)
```

## 流程

### 模式偵測

- **模式 A：優化既有專案** — `src/main/resources/application.yaml` 已存在
- **模式 B：新專案建置** — 找不到 Spring Boot 專案，或使用者明確要求新建

---

## 模式 A：優化既有專案

### Step 1：盤點與檢查

找出所有設定檔與 Java 屬性引用：

```
Glob: **/application*.yaml, **/application*.yml, **/application*.properties, **/compose.yaml, **/.env
Grep: @Value\("\$\{          ← @Value 用法
Grep: @ConfigurationProperties
Grep: @ConditionalOnProperty
```

讀 build 設定（`build.gradle.kts` / `pom.xml`）取得 Spring Boot、Spring Cloud GCP、Spring AI 版本。

每個檔案記錄：所屬 profile、所有屬性 key + 值、placeholder 模式、寫死的 secret、跨檔案重複項。

**強制：屬性路徑查證 Gate** — 偵測到框架版本後，**必須**對照偵測版本的官方 [Application Properties 索引](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) 查證所有非 `{app}.*` 屬性路徑。框架主版本升級會 rename / deprecate / 搬 namespace。模板與記憶中路徑可能已過期。`{app}.*` 由 `@ConfigurationProperties` 控制，不受框架版本影響。

### Step 2：設計健康度檢查

對照 `references/config-design-principles.md` 評估：

| 檢查項 | 通過條件 |
|-------|---------|
| 雙層 profile 分離 | 基礎設施 profile 在 `src/main/resources/`；行為 profile 在 `config/` |
| Profile 順序慣例 | 啟動文件示範 `behavior,infra`（`lab,gcp` / `prod,gcp`），不寫 `gcp,lab` |
| infra ≠ behavior 嚴格分離 | infra profile 不放 env-specific 值；behavior profile 不放 autoconfig 開關 |
| `application.yaml` 接近正式環境（fail-secure 姿態） | base 預設「最嚴」；`logging=INFO`、`springdoc disabled`、最小 actuator、`oauth.enabled=true` |
| 預設值不重複 | 與 Spring Boot / Flyway / Java `@DefaultValue` 一致的值不寫入 yaml |
| `{App}Properties` 存在 | record 含 `@ConfigurationProperties(prefix = "{app}")`；主類別含 `@ConfigurationPropertiesScan` |
| 應用屬性不用 `@Value` | service constructor 內無 `@Value("${app.*}")` — 改注入 `{App}Properties` |
| YAML 用純值或 placeholder skeleton | `{app}.*` 段為純值，或 base skeleton + behavior 提供值 |
| Secrets 用 dot-notation | `application-secrets.properties.example` 用 `{app}.section.key=value` |
| 機敏值不入 VCS | `.gitignore` 排除 `config/application-secrets.properties` |
| 統一 secret 機制 | property 名跨 env 一致，僅注入來源不同（local file vs cloud env var/sm@） |
| 命名跨層對齊 | Spring property = Cloud Run env name 走 dot.lower.case；Secret Manager hyphen-case |
| 無重複設定 | 固定值（model、dimensions 等）不在多個 profile 重複 |
| Profile default 已設 | `spring.profiles.default` 讓 zero-config dev 啟動 |
| Framework 屬性路徑經查證 | 所有 `spring.*` / `management.*` 已對照目標版本官方索引 |

### Step 3：報告與確認

呈現給使用者：(1) 目前檔案結構樹狀圖、(2) 發現的問題（表格：問題、嚴重度、影響檔案）、(3) 建議的目標結構、(4) `@Value` → `{App}Properties` 遷移表。

**等使用者確認後才動手改。**

### Step 4：執行變更

依此三階段套用，模板在 `references/config-templates.md`：

1. **Java 層**：建立 `{App}Properties.java`（`@ConfigurationProperties(prefix = "{app}")` record + nested record），主類別加 `@ConfigurationPropertiesScan`，把 service 內 `@Value("${app.*}")` 遷移為注入 `{App}Properties`，視需要建 Manual Configuration `@Bean`。
2. **YAML 層**：更新 `application.yaml`（fail-secure 姿態 + datasource skeleton），更新 infra profile（只 autoconfig 開關），更新 behavior profile（具體值 + log/actuator/springdoc）。
3. **Secrets 層**：更新 `application-secrets.properties.example` 為 dot-notation，更新 `.gitignore`，視雲端平台補 `cloud-{provider}-secrets` 設定。

### Step 5：驗證

```bash
./gradlew test          # (或 ./mvnw test) — 所有測試通過
./gradlew bootRun       # app 啟動；確認 log 中 active profile 正確
```

---

## 模式 B：新專案建置

### Step 1：蒐集需求

詢問使用者：(1) 應用程式名稱、(2) 雲端平台（local / GCP / AWS / Azure）、(3) 資料庫、(4) 額外服務（AI / messaging / storage）、(5) 需要的行為 profile（dev / lab / sit / uat / prod）。

### Step 2：產生配置

**強制：屬性路徑查證**（同模式 A Step 1）。用 `references/config-templates.md` 的模板建立：

- `{App}Properties.java` + `{App}Application.java`（含 `@ConfigurationPropertiesScan`）
- `src/main/resources/application.yaml`（fail-secure base）+ `application-{local,cloud}.yaml`（infra）
- `config/application-{dev,lab,prod}.yaml`（behavior）
- `config/application-secrets.properties.example` + `.gitignore` 加項

### Step 3：驗證與交付

同模式 A Step 5。

---

## 參考資料

執行時按需閱讀：

- `references/config-design-principles.md` — 雙層 profile 設計（§1）+ 載入順序與職責分離（§1.1–1.2）+ fail-secure 姿態（§2）+ 預設值四層（§2.1–2.3）+ `@ConfigurationProperties` + placeholder skeleton（§3）+ 統一機敏值（§4）+ Starter vs Manual Config 含 §5.X/§5.Y/§5.Z 三 gate（§5）+ 命名跨層對齊（§6）+ envsubst whitelist（§7）+ 配置載入順序（§8）
- `references/config-templates.md` — 即用模板：`{App}Properties.java`、profile YAML、DataSource skeleton、Manual Configuration `@Bean`
- `references/cloud-gcp-secrets.md` — GCP Secret Manager 整合（Cloud Run env var mount + `sm@` 遞迴 resolve + envsubst whitelist + 認證實務）
- `references/aot-deployment-pitfalls.md` — AOT processing 部署陷阱（profile 對齊、self-reference placeholder、OAuth client stub、aot profile leak、cascade build 避免心得 + diagnostic table）
- `references/cloud-run-spring-pitfalls.md` — Cloud Run + Spring Boot/Security 非 AOT 部署陷阱（reverse proxy `{baseUrl}` 解析、CORS 同源 POST 403、SPA fallback、LAB OAuth 多步部署序列、`/actuator/info` build/git 設定 + diagnostic 分流表 + AccessDeniedHandler 加料 pattern）
- `references/spring-reference-links.md` — Spring 框架官方文件、屬性與版本查找方式
- `references/anti-patterns.md` — 反模式完整列表（Quick Reference 只列 critical）

---

## 核心原則（Quick Reference）

完整細節在 `references/config-design-principles.md`。

1. **雙層 profile 設計** — infrastructure × behavior，組合啟動（`lab,gcp` / `prod,gcp` / `local,dev`）。**順序：behavior 先、infra 後**（infra 後載確保 platform 專屬如 `spring.config.import: sm@` 不被 behavior 蓋掉）。infra ≠ behavior 嚴格分離（infra 只放 autoconfig 開關，env-specific 值放 behavior）。→ §1
2. **`application.yaml` 接近正式環境（fail-secure）** — base 預設「最嚴」，dev profile 才鬆綁。預設值四層優先級：env var > config import > config/ profile > classpath profile > base > Java `@DefaultValue`。**不重複預設值**（跟上層一致的不寫；`@DefaultValue` 涵蓋的不重複寫 yaml）。→ §2
3. **`@ConfigurationProperties` 取代 `@Value`** — 應用屬性放 `{App}Properties` record，前綴 = 應用名（kebab-case）；YAML 用純值；relaxed binding 自動處理 env var 覆蓋。**base 自包含 placeholder skeleton 模式**：跨 env 結構相同的（如 datasource）抽變數放 base，behavior 只填值。→ §3
4. **統一機敏值管理機制** — 同 property 名跨 env、注入來源不同：本機走 `application-secrets.properties`，雲端走 env var（literal 或 `sm@` URI 遞迴 resolve）。**機敏值放 Secret Manager**（password / API key），**非機敏值放 env var literal**（DB user / bucket name）。→ §4
5. **依賴選擇 = 意圖宣告** — `*-starter-*` → 框架管配置（YAML 屬性）；純 library → 自己控制。**Starter vs Manual Config 決策階梯**：dev 環境跑得起 → Starter；跑不起 → 官方開關 → exclude → Manual Config → 自訂。**三大 gate**：schema 客製化（§5.X）、bean lifetime（§5.Y）、fallback 過時（§5.Z）。→ §5
6. **命名跨層對齊（dot.lower.case 主軸）** — Spring property = Cloud Run env name = 1:1 對齊（dot；K8s `RelaxedEnvironmentVariableValidation` GA 2025-06）。Secret Manager hyphen-case。Shell var SCREAMING_SNAKE。→ §6
7. **工具衝突意識** — envsubst 預設會吃 `${sm@xxx}`；用 whitelist 模式 `envsubst '$VAR1 $VAR2'` 只替換指定變數。→ §7

---

## 反模式（Critical 7 條）

完整列表見 `references/anti-patterns.md`。

1. **不要**對應用屬性用 `@Value("${app.*}")` — 建立 `{App}Properties` record
2. **不要**把 env-specific 值（datasource url/帳密、bucket、issuer-uri）放 infra profile — 屬於 behavior profile
3. **不要**用 `gcp,lab` 順序 — infra 後載原則：用 `lab,gcp`
4. **不要**把 `application.yaml` 寫成「夠 dev 用就好」的鬆姿態 — base 應為 fail-secure，dev 才鬆綁
5. **不要**信賴模板或記憶中的 framework property 路徑 — 必須查證目標版本官方 Application Properties 索引
6. **不要**疊 workaround 在 starter 之上（額外 UPDATE、`instanceof` guard、後補欄位 patch） — 換 core artifact 自實作（§5.X）
7. **不要**讓 envsubst 把 `${sm@xxx}` 當 shell 變數吃掉 — 用 whitelist 模式
