# Spring Boot 配置反模式完整列表

> 此文件為 SKILL.md 反模式段的完整版。SKILL.md 只列最 critical 的條目；
> 完整對照分類見此處。

## 屬性管理

- **不要**對應用自訂屬性用 `@Value("${app.xxx}")` — 建立 `{App}Properties` record
- **不要**對 `{app}.*` 寫 `${APP_VAR:default}` placeholder — 用純值；relaxed binding 自動處理 `APP_VAR` env var
- **不要**在 `@ConditionalOnProperty` 用 flat kebab key（`"{app}-genai-api-key"`） — 用 dot-notation 對應 `@ConfigurationProperties` 路徑（`"{app}.genai.api-key"`）

## 分層

- **不要**把行為 config（log levels、actuator scope）放 `src/main/resources/` — 屬於 `config/`
- **不要**把 `springdoc.*.enabled=false` 只放 `config/application-prod.yaml` — server 上根本不存在；改在 `application.yaml` 設預設值
- **不要**在 profile 特定檔內設 `spring.profiles.active` — Spring Boot 會 throw error
- **不要**把 env-specific 值（datasource url/帳密、bucket、issuer-uri）放 infra profile — 屬於 behavior profile
- **不要**用 `gcp,lab` 順序啟動 — infra 後載原則：用 `lab,gcp`，否則 lab 可能蓋掉 gcp 的 `spring.config.import: sm@`

## 預設值

- **不要**把跟 Spring Boot / Flyway / Modulith / Java `@DefaultValue` 預設一樣的值寫進 yaml — 雜訊干擾、預設變動時你的 yaml 跟不上
- **不要**把 `application.yaml` 寫成「夠 dev 用就好」的鬆姿態 — base 應該是 fail-secure 正式預設，dev profile 才往下鬆綁
- **不要**重複固定值（model 名、dimensions 等）跨 profile — 放一處在 `application.yaml` 或 `{App}Properties @DefaultValue`；Manual Config 的 `@Bean` 從 properties 讀

## 機敏值

- **不要**在 `application.yaml` 硬編碼雲端特定的 secret 機制（Secret Manager URI、vault 路徑） — 用 env var 注入；雲端整合放雲端特定 profile 或部署設定，詳見 `cloud-*` 參考文件
- **不要**把非機敏值（DB user、bucket name）塞進 Secret Manager — 無安全收益、增管理成本（每 secret 一份月費 + IAM 複雜度）
- **不要**讓 envsubst 把 `${sm@xxx}` 當 shell 變數吃掉 — 用 whitelist 模式只替換指定變數

## 框架版本

- **不要**信賴模板或記憶中的 framework property 路徑 — 框架主版本升級會 rename / deprecate / 搬遷屬性路徑。執行時**必須**查證目標版本的官方 Application Properties 索引。`{app}.*` 由 `@ConfigurationProperties` 控制不受影響，但所有 framework namespace（`spring.*`、`management.*` 等）都可能變動
- **不要**把「測試通過」當作配置值正確的證據 — 已 deprecated 的屬性在新版仍可運作（向下相容），測試無法捕捉 deprecation。查證手段：讀 JAR 內 `META-INF/spring-configuration-metadata.json` 的 `deprecation` 欄位，或查官方索引。**配置值務必查證後再動手，不是測試過就好**

## 依賴與 bean 設計

- **不要**忽略 artifact 名稱 — 選了 `*-starter-*` 就查官方文件用 YAML 屬性配置，不要自己建 bean（衝突風險）；選了純 library 就自己控制，不要期待 auto-config 生效。混用 = 衝突。**描述 artifact 變體的配置策略前必須先讀官方文件確認該變體的設計意圖 — 不可從單一專案的實作反推**
- **不要**在框架已有內建 disable 開關時直接跳到 Manual Configuration — 走決策階梯（§5）
- **不要**疊 workaround（額外 UPDATE / `instanceof` guard / 後補欄位 patch）在 starter 之上 — 當其硬編 SQL/schema 不符客製化需求時，換 core artifact 自實作。→ §5.X
- **不要**反射式把所有 wrapper 註冊成 Spring `@Bean`。Per-call context（user identity、tenant ID、request 上下文）屬於呼叫端 builder 建構的 instance，不是 singleton 的欄位。→ §5.Y
- **不要**保留「dev 便利」fallback 在 production path 已可在 dev 跑得起來後（Docker Compose、emulator、自管化）。dev/prod parity 勝過便利。→ §5.Z

## 死設定

- **不要**留 dead exclude（artifact 不存在的 auto-config 類別）— 例如用 `spring-ai-google-genai-embedding`（core）卻 exclude `GoogleGenAiEmbeddingConnectionAutoConfiguration`（屬於 starter 變體，純 library 沒帶）
- **不要**留過時的設定 placeholder（如已 deprecated 的 fallback property、不再用的 vector store 切換屬性）— 每次 infra 演進掃一次

---

> § 標號對應 `references/config-design-principles.md` 的章節編號。
